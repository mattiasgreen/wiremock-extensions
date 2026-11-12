package com.github.mattiasgreen.wiremock.async.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.async.bridge.InMemoryMessageBroker;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AsyncEngineTest {

    private InMemoryMessageBroker testBroker;
    private InMemoryMessageBroker upstreamBroker;
    private AsyncEngine engine;

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
    @DisplayName("Should intercept matching message and respond immediately with mock without upstream leakage")
    void shouldInterceptMockStubWithoutUpstreamLeakage() {
        engine.addMockStub("GET_BALANCE", req -> AsyncMessage.builder()
                .correlationId(req.correlationId())
                .destination(req.replyTo())
                .payload("{\"balance\": 1500.00}")
                .build());

        AsyncMessage msg = AsyncMessage.builder()
                .correlationId("corr-101")
                .destination("accounts.requests")
                .replyTo("accounts.replies")
                .payload("{\"action\":\"GET_BALANCE\",\"accountId\":\"ACC-99\"}")
                .build();
        testBroker.send("accounts.requests", msg);

        boolean processed = engine.processInbound("accounts.requests");
        assertThat(processed).isTrue();

        // 1. Upstream receives 0 messages (100% mock isolation)
        assertThat(upstreamBroker.size("accounts.requests")).isZero();

        // 2. Test broker receives mock reply
        AsyncMessage reply = testBroker.poll("accounts.replies");
        assertThat(reply).isNotNull();
        assertThat(reply.payload()).isEqualTo("{\"balance\": 1500.00}");
        assertThat(reply.correlationId()).isEqualTo("corr-101");

        // 3. Engine counters & journal updated
        assertThat(engine.getMessagesConsumed()).isEqualTo(1L);
        assertThat(engine.getMessagesProduced()).isEqualTo(1L);
        assertThat(engine.getMessagesProxied()).isZero();
        assertThat(engine.getServeEvents()).anySatisfy(se -> {
            assertThat(se.getRequest().getBodyAsString())
                    .isEqualTo("{\"action\":\"GET_BALANCE\",\"accountId\":\"ACC-99\"}");
            assertThat(se.getResponseDefinition().getStatus()).isEqualTo(200);
        });
    }

    @Test
    @DisplayName("Should forward unmatched message to upstream broker in proxy mode and track metrics")
    void shouldForwardUnmatchedMessageToUpstream() {
        AsyncMessage msg = AsyncMessage.builder()
                .correlationId("corr-202")
                .destination("orders.input")
                .replyTo("orders.output")
                .payload("{\"orderId\":\"ORD-777\",\"amount\":49.99}")
                .build();
        testBroker.send("orders.input", msg);

        boolean processed = engine.processInbound("orders.input");
        assertThat(processed).isTrue();

        // Forwarded to upstream broker
        assertThat(upstreamBroker.size("orders.input")).isEqualTo(1);
        AsyncMessage upstreamMsg = upstreamBroker.poll("orders.input");
        assertThat(upstreamMsg).isNotNull();
        assertThat(upstreamMsg.payload()).isEqualTo("{\"orderId\":\"ORD-777\",\"amount\":49.99}");

        // Metrics & journal
        assertThat(engine.getMessagesConsumed()).isEqualTo(1L);
        assertThat(engine.getMessagesProxied()).isEqualTo(1L);
        assertThat(engine.getServeEvents()).anySatisfy(se -> {
            assertThat(se.getResponseDefinition().isProxyResponse()).isTrue();
            assertThat(se.getResponseDefinition().getProxyBaseUrl()).contains("orders.input");
            assertThat(se.getRequest().getBodyAsString()).isEqualTo("{\"orderId\":\"ORD-777\",\"amount\":49.99}");
        });
    }

    @Test
    @DisplayName("Should harvest upstream live replies and replay them offline")
    void shouldHarvestAndReplayLiveRepliesOffline() {
        // 1. Upstream produces live response
        AsyncMessage liveReply = AsyncMessage.builder()
                .correlationId("corr-303")
                .destination("payments.responses")
                .payload("{\"status\":\"APPROVED\",\"authCode\":\"AUTH-999\"}")
                .build();
        upstreamBroker.send("payments.responses", liveReply);

        // 2. Process outbound reply and harvest
        boolean harvested = engine.processOutboundReply("payments.responses", "PAY-999");
        assertThat(harvested).isTrue();
        assertThat(engine.getHarvestedStubs()).containsKey("PAY-999");

        // 3. Forwarded to test broker
        AsyncMessage forwarded = testBroker.poll("payments.responses");
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.payload()).contains("AUTH-999");

        // 4. Offline replay: Upstream broker has 0 messages
        AsyncMessage req2 = AsyncMessage.builder()
                .correlationId("corr-404")
                .destination("payments.requests")
                .replyTo("payments.responses")
                .payload("{\"ref\":\"PAY-999\"}")
                .build();
        testBroker.send("payments.requests", req2);

        boolean replayed = engine.processInbound("payments.requests");
        assertThat(replayed).isTrue();
        assertThat(upstreamBroker.size("payments.requests")).isZero();

        AsyncMessage replayResp = testBroker.poll("payments.responses");
        assertThat(replayResp).isNotNull();
        assertThat(replayResp.correlationId()).isEqualTo("corr-404");
        assertThat(replayResp.payload()).contains("AUTH-999");
        assertThat(engine.getServeEvents()).anySatisfy(se -> {
            assertThat(se.getRequest().getBodyAsString()).isEqualTo("{\"ref\":\"PAY-999\"}");
            assertThat(se.getResponseDefinition().getBody()).contains("AUTH-999");
        });
    }

    @Test
    @DisplayName("Should publish messages and record journal entries directly")
    void shouldPublishAndRecordJournal() {
        List<AsyncMessage> published = Collections.synchronizedList(new ArrayList<>());
        engine.registerPublisher("events.topic", (dest, msg) -> published.add(msg));

        AsyncMessage msg = AsyncMessage.builder()
                .id("msg-1")
                .destination("events.topic")
                .payload("{\"event\":\"USER_CREATED\"}")
                .build();

        engine.publish("events.topic", msg);

        assertThat(published).hasSize(1);
        assertThat(published.get(0).payload()).isEqualTo("{\"event\":\"USER_CREATED\"}");
        assertThat(engine.getMessagesProduced()).isEqualTo(1L);
        assertThat(engine.getServeEvents()).anySatisfy(se -> {
            assertThat(se.getRequest().getBodyAsString()).isEqualTo("{\"event\":\"USER_CREATED\"}");
            assertThat(se.getResponseDefinition().getBody()).contains("PUBLISHED");
        });
    }
}
