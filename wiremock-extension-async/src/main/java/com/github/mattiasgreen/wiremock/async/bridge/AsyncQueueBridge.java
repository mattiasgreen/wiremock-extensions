package com.github.mattiasgreen.wiremock.async.bridge;

import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Adapter delegating queue bridge operations to {@link AsyncEngine}.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 */
public class AsyncQueueBridge {

    private final AsyncEngine engine;

    public AsyncQueueBridge(MessageBroker testBroker, MessageBroker upstreamBroker) {
        this.engine = AsyncEngine.create(testBroker, upstreamBroker);
    }

    public void addMockStub(String payloadKeyword, Function<AsyncMessage, AsyncMessage> replyProducer) {
        engine.addMockStub(payloadKeyword, replyProducer);
    }

    public Map<String, AsyncMessage> getHarvestedStubs() {
        return engine.getHarvestedStubs();
    }

    public List<String> getJournal() {
        return engine.getJournal();
    }

    public boolean processInbound(String channel) {
        return engine.processInbound(channel);
    }

    public boolean processOutboundReply(String replyChannel, String originalMatchKeyword) {
        return engine.processOutboundReply(replyChannel, originalMatchKeyword);
    }
}
