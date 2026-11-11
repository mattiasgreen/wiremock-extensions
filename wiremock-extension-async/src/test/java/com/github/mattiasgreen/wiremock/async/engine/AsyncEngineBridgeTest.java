package com.github.mattiasgreen.wiremock.async.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.async.bridge.InMemoryMessageBroker;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AsyncEngineBridgeTest {

    @Test
    @DisplayName("Should serve mock reply on test queue, synthesize ServeEvent, and not leak to upstream queue")
    void shouldServeMockWithoutLeakingUpstream() {
        InMemoryMessageBroker testBroker = new InMemoryMessageBroker("Broker-A-Test");
        InMemoryMessageBroker upstreamBroker = new InMemoryMessageBroker("Broker-B-Upstream");
        AsyncEngine engine = AsyncEngine.create(testBroker, upstreamBroker);

        List<ServeEvent> capturedEvents = new ArrayList<>();
        engine.setJournalConsumer(capturedEvents::add);

        // Register mock stub
        engine.addMockStub("TIER_STANDARD", req -> AsyncMessage.builder()
                .correlationId(req.correlationId())
                .destination(req.replyTo())
                .payload("{\"status\":\"MOCK_APPROVED\"}")
                .build());

        // System A sends message to orders.inbound on test broker
        AsyncMessage msg = AsyncMessage.builder()
                .correlationId("corr-101")
                .destination("orders.inbound")
                .replyTo("orders.responses")
                .payload("{\"orderId\":\"101\",\"tier\":\"TIER_STANDARD\"}")
                .build();
        testBroker.send("orders.inbound", msg);

        // Process bridge inbound cycle
        engine.processInbound("orders.inbound");

        // System A receives mock reply on orders.responses
        AsyncMessage reply = testBroker.poll("orders.responses");
        assertThat(reply).isNotNull();
        assertThat(reply.correlationId()).isEqualTo("corr-101");
        assertThat(reply.payload()).isEqualTo("{\"status\":\"MOCK_APPROVED\"}");

        // Upstream broker receives 0 messages
        assertThat(upstreamBroker.size("orders.inbound")).isEqualTo(0);

        // Verify authentic ServeEvent recorded
        assertThat(capturedEvents).isNotEmpty();
        ServeEvent se = capturedEvents.get(0);
        assertThat(se.getRequest().getUrl()).isEqualTo("/__async/async/orders.inbound");
        assertThat(se.getResponse().getBodyAsString()).isEqualTo("{\"status\":\"MOCK_APPROVED\"}");
    }

    @Test
    @DisplayName(
            "Should proxy unmatched message to upstream broker, synthesize bridged ServeEvent, and harvest replay stub")
    void shouldProxyAndHarvestLiveExchange() {
        InMemoryMessageBroker testBroker = new InMemoryMessageBroker("Broker-A-Test");
        InMemoryMessageBroker upstreamBroker = new InMemoryMessageBroker("Broker-B-Upstream");
        AsyncEngine engine = AsyncEngine.create(testBroker, upstreamBroker);

        List<ServeEvent> capturedEvents = new ArrayList<>();
        engine.setJournalConsumer(capturedEvents::add);

        // System A produces an enterprise message
        AsyncMessage msg = AsyncMessage.builder()
                .correlationId("corr-999")
                .destination("orders.inbound")
                .replyTo("orders.responses")
                .payload("{\"orderId\":\"999\",\"tier\":\"TIER_ENTERPRISE\"}")
                .build();
        testBroker.send("orders.inbound", msg);

        // 1. Engine forwards across to upstream broker
        engine.processInbound("orders.inbound");
        assertThat(testBroker.size("orders.inbound")).isEqualTo(0);
        assertThat(upstreamBroker.size("orders.inbound")).isEqualTo(1);

        // Verify bridged ServeEvent synthesized
        assertThat(capturedEvents).hasSize(1);
        ServeEvent bridgeEvent = capturedEvents.get(0);
        assertThat(bridgeEvent.getResponseDefinition().isProxyResponse()).isTrue();
        assertThat(bridgeEvent.getResponseDefinition().getProxyBaseUrl()).contains("upstream/orders.inbound");

        // 2. Upstream consumes and replies
        AsyncMessage upstreamMsg = upstreamBroker.poll("orders.inbound");
        assertThat(upstreamMsg).isNotNull();
        AsyncMessage upstreamReply = AsyncMessage.builder()
                .correlationId(upstreamMsg.correlationId())
                .destination("orders.responses")
                .payload("{\"status\":\"UPSTREAM_VIP_OK\"}")
                .build();
        upstreamBroker.send("orders.responses", upstreamReply);

        // 3. Engine processes outbound reply -> forwards to test broker & harvests stub
        engine.processOutboundReply("orders.responses", "TIER_ENTERPRISE");

        AsyncMessage clientReply = testBroker.poll("orders.responses");
        assertThat(clientReply).isNotNull();
        assertThat(clientReply.payload()).isEqualTo("{\"status\":\"UPSTREAM_VIP_OK\"}");

        // Verify stub was harvested into engine catalog
        assertThat(engine.getHarvestedStubs()).containsKey("TIER_ENTERPRISE");

        // 4. Offline Replay: Send second enterprise order with upstream offline
        AsyncMessage repeatMsg = AsyncMessage.builder()
                .correlationId("corr-1000")
                .destination("orders.inbound")
                .replyTo("orders.responses")
                .payload("{\"orderId\":\"1000\",\"tier\":\"TIER_ENTERPRISE\"}")
                .build();
        testBroker.send("orders.inbound", repeatMsg);
        engine.processInbound("orders.inbound");

        AsyncMessage replayReply = testBroker.poll("orders.responses");
        assertThat(replayReply).isNotNull();
        assertThat(replayReply.payload()).isEqualTo("{\"status\":\"UPSTREAM_VIP_OK\"}");
        assertThat(upstreamBroker.size("orders.inbound")).isEqualTo(0);
    }
}
