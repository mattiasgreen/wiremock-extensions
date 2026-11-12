package com.github.mattiasgreen.wiremock.async.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AsyncPriorityBridgeTest {

    private AsyncEngine engine;
    private InMemoryMessageBroker testBroker;
    private InMemoryMessageBroker upstreamBroker;

    @BeforeEach
    void setUp() {
        testBroker = new InMemoryMessageBroker("test-broker");
        upstreamBroker = new InMemoryMessageBroker("upstream-broker");
        engine = AsyncEngine.create(testBroker, upstreamBroker);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    @DisplayName("High-priority mock stub should intercept message and isolate upstream completely")
    void shouldInterceptWithHighPriorityMockStub() {
        engine.addMockStub("VIP-ORDER", msg -> AsyncMessage.builder()
                .correlationId(msg.correlationId())
                .destination(msg.replyTo())
                .payload("{\"status\":\"CONFIRMED_VIP\"}")
                .build());

        // Publish to test broker
        AsyncMessage msg = AsyncMessage.builder()
                .destination("orders.inbound")
                .replyTo("orders.replies")
                .correlationId("vip-101")
                .payload("{\"type\":\"VIP-ORDER\",\"customer\":\"Corp\"}")
                .build();
        testBroker.send("orders.inbound", msg);

        boolean processed = engine.processInbound("orders.inbound");
        assertThat(processed).isTrue();

        // Check reply was delivered to test broker reply channel
        AsyncMessage reply = testBroker.poll("orders.replies");
        assertThat(reply).isNotNull();
        assertThat(reply.payload()).isEqualTo("{\"status\":\"CONFIRMED_VIP\"}");

        // Upstream broker must receive ZERO messages (100% isolation)
        assertThat(upstreamBroker.size("orders.inbound")).isZero();
        assertThat(engine.getServeEvents()).anySatisfy(se -> {
            assertThat(se.getRequest().getBodyAsString()).isEqualTo(msg.payload());
            assertThat(se.getResponseDefinition().getStatus()).isEqualTo(200);
            assertThat(se.getResponseDefinition().isProxyResponse()).isFalse();
        });
    }

    @Test
    @DisplayName("Low-priority bridge rule should apply wildcard capture group rewriting and forward upstream")
    void shouldRewriteChannelAndForwardUpstream() {
        // Priority 10 fallback bridging rule
        engine.addBridgeRule("Regional Orders Bridge", 10, "orders\\.(.*)", "", "upstream-orders-$1");

        AsyncMessage msg = AsyncMessage.builder()
                .destination("orders.germany")
                .correlationId("reg-500")
                .payload("{\"item\":\"Widget\",\"qty\":5}")
                .build();
        testBroker.send("orders.germany", msg);

        boolean processed = engine.processInbound("orders.germany");
        assertThat(processed).isTrue();

        // Upstream broker receives rewritten channel
        assertThat(upstreamBroker.size("upstream-orders-germany")).isEqualTo(1);
        AsyncMessage forwarded = upstreamBroker.poll("upstream-orders-germany");
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.destination()).isEqualTo("upstream-orders-germany");
        assertThat(forwarded.payload()).isEqualTo("{\"item\":\"Widget\",\"qty\":5}");

        assertThat(engine.getServeEvents()).anySatisfy(se -> {
            assertThat(se.getResponseDefinition().isProxyResponse()).isTrue();
            assertThat(se.getResponseDefinition().getProxyBaseUrl()).contains("upstream-orders-germany");
            assertThat(se.getRequest().getBodyAsString()).isEqualTo(msg.payload());
        });
    }

    @Test
    @DisplayName("Missing target queue should trigger 4-pillar 502 proxy error troubleshooting event")
    void shouldTrigger502OnMissingQueueBridge() {
        // Bridge configured to point to a missing destination
        engine.addBridgeRule("Broken Bridge", 10, "broken\\.(.*)", "", "MISSING.QUEUE.TARGET");

        AsyncMessage msg = AsyncMessage.builder()
                .destination("broken.test")
                .payload("{\"data\":\"lost\"}")
                .build();
        testBroker.send("broken.test", msg);

        boolean processed = engine.processInbound("broken.test");
        assertThat(processed).isTrue();

        // Verify proxy errors incremented
        assertThat(engine.getProxyErrors()).isEqualTo(1);
        // Verify 502 troubleshooting ServeEvent
        assertThat(engine.getServeEvents()).anySatisfy(se -> {
            assertThat(se.getResponseDefinition().getStatus()).isEqualTo(502);
            assertThat(se.getResponseDefinition().getBody()).contains("MISSING.QUEUE.TARGET");
        });
    }

    @Test
    @DisplayName("Virtual-thread subscribe loop should receive and process messages asynchronously")
    void shouldSubscribeOnVirtualThread() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AsyncMessage> received = new AtomicReference<>();

        engine.subscribe("notifications.alerts", msg -> {
            received.set(msg);
            latch.countDown();
        });

        testBroker.send(
                "notifications.alerts",
                AsyncMessage.builder()
                        .destination("notifications.alerts")
                        .payload("{\"alert\":\"HIGH_CPU\"}")
                        .build());

        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(received.get()).isNotNull();
        assertThat(received.get().payload()).isEqualTo("{\"alert\":\"HIGH_CPU\"}");
    }
}
