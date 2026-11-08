package com.github.mattiasgreen.wiremock.async.bridge;

import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Intelligent dual-broker queue bridge managing mock interception and transparent upstream forwarding
 * across identical queue names without requiring application changes.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 */
public class AsyncQueueBridge {

    private final MessageBroker testBroker;
    private final MessageBroker upstreamBroker;
    private final Map<String, Function<AsyncMessage, AsyncMessage>> mockStubs = new ConcurrentHashMap<>();
    private final Map<String, AsyncMessage> harvestedStubs = new ConcurrentHashMap<>();
    private final List<String> journal = Collections.synchronizedList(new ArrayList<>());

    public AsyncQueueBridge(MessageBroker testBroker, MessageBroker upstreamBroker) {
        this.testBroker = Objects.requireNonNull(testBroker, "testBroker must not be null");
        this.upstreamBroker = Objects.requireNonNull(upstreamBroker, "upstreamBroker must not be null");
    }

    public void addMockStub(String payloadKeyword, Function<AsyncMessage, AsyncMessage> replyProducer) {
        mockStubs.put(payloadKeyword, replyProducer);
    }

    public Map<String, AsyncMessage> getHarvestedStubs() {
        return Collections.unmodifiableMap(harvestedStubs);
    }

    public List<String> getJournal() {
        return Collections.unmodifiableList(new ArrayList<>(journal));
    }

    /**
     * Pulls the next message from the test broker. If a configured mock matches, it produces an immediate
     * reply onto the test broker reply channel. If no mock matches, it proxies the message across to the
     * upstream broker with the identical channel name.
     *
     * @param channel channel name to process
     * @return true if a message was processed, false if the queue was empty
     */
    public boolean processInbound(String channel) {
        AsyncMessage msg = testBroker.poll(channel);
        if (msg == null) {
            return false;
        }

        // 1. Check active mock stubs
        for (Map.Entry<String, Function<AsyncMessage, AsyncMessage>> stub : mockStubs.entrySet()) {
            if (msg.payload().contains(stub.getKey())) {
                journal.add("MOCK_MATCH:" + msg.payload());
                AsyncMessage reply = stub.getValue().apply(msg);
                if (reply != null && msg.replyTo() != null && !msg.replyTo().isBlank()) {
                    testBroker.send(msg.replyTo(), reply);
                }
                return true; // 100% Mock isolation: Upstream receives 0 messages
            }
        }

        // 2. Check previously harvested stubs for offline replay
        for (Map.Entry<String, AsyncMessage> harvested : harvestedStubs.entrySet()) {
            if (msg.payload().contains(harvested.getKey())) {
                journal.add("HARVESTED_REPLAY:" + msg.payload());
                AsyncMessage template = harvested.getValue();
                AsyncMessage reply = AsyncMessage.builder()
                        .correlationId(msg.correlationId())
                        .destination(msg.replyTo())
                        .payload(template.payload())
                        .headers(template.headers())
                        .build();
                if (msg.replyTo() != null && !msg.replyTo().isBlank()) {
                    testBroker.send(msg.replyTo(), reply);
                }
                return true;
            }
        }

        // 3. Proxy Mode: Forward to upstream broker with identical channel name
        journal.add("PROXY_FORWARD:" + msg.payload());
        upstreamBroker.send(channel, msg);
        return true;
    }

    /**
     * Intercepts a live reply emitted by the upstream system, forwards it to the test broker for System A,
     * and harvests the exchange into the bridge catalog for offline replay.
     *
     * @param replyChannel reply queue or channel name
     * @param originalMatchKeyword keyword to correlate with subsequent inbound requests
     * @return true if a reply was processed, false if the reply queue was empty
     */
    public boolean processOutboundReply(String replyChannel, String originalMatchKeyword) {
        AsyncMessage reply = upstreamBroker.poll(replyChannel);
        if (reply == null) {
            return false;
        }

        journal.add("PROXY_REPLY_HARVESTED:" + reply.payload());
        if (originalMatchKeyword != null && !originalMatchKeyword.isBlank()) {
            harvestedStubs.put(originalMatchKeyword, reply);
        }

        // Forward to test broker for System A
        testBroker.send(replyChannel, reply);
        return true;
    }
}
