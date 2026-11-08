package com.github.mattiasgreen.wiremock.async.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AsyncQueueBridgeTest {

    @Test
    @DisplayName("Should serve mock reply on test queue without leaking to upstream queue")
    void shouldServeMockWithoutLeakingUpstream() {
        InMemoryMessageBroker testBroker = new InMemoryMessageBroker("Broker-A-Test");
        InMemoryMessageBroker upstreamBroker = new InMemoryMessageBroker("Broker-B-Upstream");
        AsyncQueueBridge bridge = new AsyncQueueBridge(testBroker, upstreamBroker);

        // Register mock stub
        bridge.addMockStub("TIER_STANDARD", req -> AsyncMessage.builder()
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
        bridge.processInbound("orders.inbound");

        // System A receives mock reply on orders.responses
        AsyncMessage reply = testBroker.poll("orders.responses");
        assertThat(reply).isNotNull();
        assertThat(reply.correlationId()).isEqualTo("corr-101");
        assertThat(reply.payload()).isEqualTo("{\"status\":\"MOCK_APPROVED\"}");

        // Upstream broker receives 0 messages
        assertThat(upstreamBroker.size("orders.inbound")).isEqualTo(0);
    }

    @Test
    @DisplayName("Should proxy unmatched message to upstream broker, capture reply, and harvest stub for replay")
    void shouldProxyAndHarvestLiveExchange() {
        InMemoryMessageBroker testBroker = new InMemoryMessageBroker("Broker-A-Test");
        InMemoryMessageBroker upstreamBroker = new InMemoryMessageBroker("Broker-B-Upstream");
        AsyncQueueBridge bridge = new AsyncQueueBridge(testBroker, upstreamBroker);

        // System A produces an enterprise message
        AsyncMessage msg = AsyncMessage.builder()
                .correlationId("corr-999")
                .destination("orders.inbound")
                .replyTo("orders.responses")
                .payload("{\"orderId\":\"999\",\"tier\":\"TIER_ENTERPRISE\"}")
                .build();
        testBroker.send("orders.inbound", msg);

        // 1. Bridge forwards across to upstream broker
        bridge.processInbound("orders.inbound");
        assertThat(testBroker.size("orders.inbound")).isEqualTo(0);
        assertThat(upstreamBroker.size("orders.inbound")).isEqualTo(1);

        // 2. Upstream consumes and replies
        AsyncMessage upstreamMsg = upstreamBroker.poll("orders.inbound");
        assertThat(upstreamMsg).isNotNull();
        AsyncMessage upstreamReply = AsyncMessage.builder()
                .correlationId(upstreamMsg.correlationId())
                .destination("orders.responses")
                .payload("{\"status\":\"UPSTREAM_VIP_OK\"}")
                .build();
        upstreamBroker.send("orders.responses", upstreamReply);

        // 3. Bridge processes outbound reply -> forwards to test broker & harvests stub
        bridge.processOutboundReply("orders.responses", "TIER_ENTERPRISE");

        AsyncMessage clientReply = testBroker.poll("orders.responses");
        assertThat(clientReply).isNotNull();
        assertThat(clientReply.payload()).isEqualTo("{\"status\":\"UPSTREAM_VIP_OK\"}");

        // Verify stub was harvested into bridge catalog
        assertThat(bridge.getHarvestedStubs()).containsKey("TIER_ENTERPRISE");

        // 4. Offline Replay: Send second enterprise order with upstream offline
        AsyncMessage repeatMsg = AsyncMessage.builder()
                .correlationId("corr-1000")
                .destination("orders.inbound")
                .replyTo("orders.responses")
                .payload("{\"orderId\":\"1000\",\"tier\":\"TIER_ENTERPRISE\"}")
                .build();
        testBroker.send("orders.inbound", repeatMsg);
        bridge.processInbound("orders.inbound");

        AsyncMessage replayReply = testBroker.poll("orders.responses");
        assertThat(replayReply).isNotNull();
        assertThat(replayReply.payload()).isEqualTo("{\"status\":\"UPSTREAM_VIP_OK\"}");
        assertThat(upstreamBroker.size("orders.inbound")).isEqualTo(0);
    }
}
