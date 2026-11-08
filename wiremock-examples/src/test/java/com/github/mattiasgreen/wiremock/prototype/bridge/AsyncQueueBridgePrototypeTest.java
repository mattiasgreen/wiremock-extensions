package com.github.mattiasgreen.wiremock.prototype.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prototype 2: The "Async Queue Bridge" Spike (Mock vs. Proxy Harvesting).
 *
 * <p>Proves the dual-broker bridge architecture:
 * <ul>
 *   <li>System A connects to Broker A (Test Broker) with queue "orders.inbound"
 *   <li>System B connects to Broker B (Real Upstream Broker) with identical queue "orders.inbound"
 *   <li>When stub matches: WireMock serves mock reply on Broker A, keeping Broker B isolated
 *   <li>When in proxy mode: WireMock bridges to Broker B, captures System B's reply, returns it to
 *       System A on Broker A, and auto-records a harvested stub for offline replay!
 * </ul>
 */
class AsyncQueueBridgePrototypeTest {

    public record Message(String correlationId, String replyTo, String payload) {}

    static class InProcessBroker {
        private final String name;
        private final Map<String, Deque<Message>> queues = new HashMap<>();

        public InProcessBroker(String name) {
            this.name = name;
        }

        public synchronized void send(String queue, Message msg) {
            queues.computeIfAbsent(queue, k -> new ArrayDeque<>()).add(msg);
        }

        public synchronized Message poll(String queue) {
            Deque<Message> q = queues.get(queue);
            return (q != null && !q.isEmpty()) ? q.poll() : null;
        }

        public synchronized int queueSize(String queue) {
            Deque<Message> q = queues.get(queue);
            return q != null ? q.size() : 0;
        }
    }

    static class WireMockQueueBridge {
        private final InProcessBroker testBroker;
        private final InProcessBroker upstreamBroker;
        private final Map<String, String> mockStubs = new HashMap<>(); // payload pattern -> mock reply
        private final Map<String, String> harvestedStubs = new HashMap<>(); // captured live exchanges
        private final List<String> journal = new ArrayList<>();

        public WireMockQueueBridge(InProcessBroker testBroker, InProcessBroker upstreamBroker) {
            this.testBroker = testBroker;
            this.upstreamBroker = upstreamBroker;
        }

        public void addMockStub(String requestKeyword, String mockReply) {
            mockStubs.put(requestKeyword, mockReply);
        }

        public Map<String, String> getHarvestedStubs() {
            return Collections.unmodifiableMap(harvestedStubs);
        }

        public List<String> getJournal() {
            return Collections.unmodifiableList(journal);
        }

        /**
         * Bridge step: pulls from testBroker, checks stubs, and either mocks or proxies upstream.
         */
        public void processInbound(String queueName) {
            Message msg = testBroker.poll(queueName);
            if (msg == null) return;

            // Check if active mock stub matches
            for (Map.Entry<String, String> stub : mockStubs.entrySet()) {
                if (msg.payload().contains(stub.getKey())) {
                    journal.add("MOCK_MATCH:" + msg.payload() + " -> " + stub.getValue());
                    // Reply to System A on testBroker reply queue
                    if (msg.replyTo() != null) {
                        testBroker.send(msg.replyTo(), new Message(msg.correlationId(), null, stub.getValue()));
                    }
                    return; // Intercepted: Broker B never sees this!
                }
            }

            // Check if previously harvested stub matches
            for (Map.Entry<String, String> stub : harvestedStubs.entrySet()) {
                if (msg.payload().contains(stub.getKey())) {
                    journal.add("HARVESTED_REPLAY:" + msg.payload() + " -> " + stub.getValue());
                    if (msg.replyTo() != null) {
                        testBroker.send(msg.replyTo(), new Message(msg.correlationId(), null, stub.getValue()));
                    }
                    return;
                }
            }

            // Passthrough / Proxy Mode: Forward to upstreamBroker with identical queue name
            journal.add("PROXY_FORWARD_TO_UPSTREAM:" + msg.payload());
            upstreamBroker.send(queueName, msg);
        }

        /**
         * Capture upstream reply from Broker B, forward to Broker A, and harvest stub into catalog.
         */
        public void processOutboundReply(String replyQueue, String originalRequestKeyword) {
            Message reply = upstreamBroker.poll(replyQueue);
            if (reply == null) return;

            journal.add("PROXY_REPLY_FROM_UPSTREAM:" + reply.payload() + " (HARVESTED)");
            harvestedStubs.put(originalRequestKeyword, reply.payload());

            // Forward reply back to System A on testBroker
            testBroker.send(replyQueue, reply);
        }
    }

    @Test
    @DisplayName("Should mock matching message on Broker A without leaking to Broker B")
    void shouldMockOnTestBrokerWithoutLeakingToUpstream() {
        InProcessBroker brokerA = new InProcessBroker("Broker-A-Test");
        InProcessBroker brokerB = new InProcessBroker("Broker-B-Prod");
        WireMockQueueBridge bridge = new WireMockQueueBridge(brokerA, brokerB);

        // Configure mock stub
        bridge.addMockStub("TIER_STANDARD", "{\"status\":\"MOCK_APPROVED\"}");

        // System A sends message on Broker A with identical queue name "orders.inbound"
        Message orderA = new Message("corr-1", "orders.responses", "{\"orderId\":\"101\",\"type\":\"TIER_STANDARD\"}");
        brokerA.send("orders.inbound", orderA);

        // Bridge executes
        bridge.processInbound("orders.inbound");

        // Assert: System A received mock reply on Broker A
        Message reply = brokerA.poll("orders.responses");
        assertThat(reply).isNotNull();
        assertThat(reply.correlationId()).isEqualTo("corr-1");
        assertThat(reply.payload()).isEqualTo("{\"status\":\"MOCK_APPROVED\"}");

        // Assert: Broker B (Upstream) received NOTHING (100% isolation!)
        assertThat(brokerB.queueSize("orders.inbound")).isEqualTo(0);
    }

    @Test
    @DisplayName("Should proxy unmatched message to Broker B, return reply to Broker A, and harvest stub")
    void shouldProxyAndHarvestAcrossBrokersWithZeroCodeChanges() {
        InProcessBroker brokerA = new InProcessBroker("Broker-A-Test");
        InProcessBroker brokerB = new InProcessBroker("Broker-B-Prod");
        WireMockQueueBridge bridge = new WireMockQueueBridge(brokerA, brokerB);

        // No stub registered for TIER_ENTERPRISE. System A produces to Broker A:
        Message order =
                new Message("corr-99", "orders.responses", "{\"orderId\":\"999\",\"type\":\"TIER_ENTERPRISE\"}");
        brokerA.send("orders.inbound", order);

        // 1. Bridge forwards across to Broker B
        bridge.processInbound("orders.inbound");
        assertThat(brokerA.queueSize("orders.inbound")).isEqualTo(0);
        assertThat(brokerB.queueSize("orders.inbound")).isEqualTo(1);

        // 2. Real System B consumes from Broker B and replies to orders.responses on Broker B
        Message fromBrokerB = brokerB.poll("orders.inbound");
        assertThat(fromBrokerB).isNotNull();
        brokerB.send("orders.responses", new Message("corr-99", null, "{\"status\":\"UPSTREAM_VERIFIED_VIP\"}"));

        // 3. Bridge intercepts reply from Broker B, forwards to Broker A, and harvests the stub
        bridge.processOutboundReply("orders.responses", "TIER_ENTERPRISE");

        // Assert: System A gets real reply on Broker A
        Message finalReply = brokerA.poll("orders.responses");
        assertThat(finalReply).isNotNull();
        assertThat(finalReply.payload()).isEqualTo("{\"status\":\"UPSTREAM_VERIFIED_VIP\"}");

        // Assert: Stub was harvested into WireMock catalog!
        assertThat(bridge.getHarvestedStubs()).containsKey("TIER_ENTERPRISE");
        assertThat(bridge.getHarvestedStubs().get("TIER_ENTERPRISE"))
                .isEqualTo("{\"status\":\"UPSTREAM_VERIFIED_VIP\"}");

        // 4. Verification of Replay: System A sends second message with TIER_ENTERPRISE
        Message repeatOrder =
                new Message("corr-100", "orders.responses", "{\"orderId\":\"1000\",\"type\":\"TIER_ENTERPRISE\"}");
        brokerA.send("orders.inbound", repeatOrder);
        bridge.processInbound("orders.inbound");

        // System A gets replayed harvested stub directly from memory!
        Message replayReply = brokerA.poll("orders.responses");
        assertThat(replayReply).isNotNull();
        assertThat(replayReply.payload()).isEqualTo("{\"status\":\"UPSTREAM_VERIFIED_VIP\"}");
        // Broker B received nothing on the repeat!
        assertThat(brokerB.queueSize("orders.inbound")).isEqualTo(0);
    }
}
