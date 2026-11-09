package com.github.mattiasgreen.wiremock.async.model;

import com.github.tomakehurst.wiremock.common.Metadata;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Domain model representing {@code metadata.async} configuration on a WireMock StubMapping.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 *
 * @param protocol transport protocol (e.g. "kafka", "amqp", "casual")
 * @param broker connection URI string
 * @param channel queue or topic destination name
 * @param ackPolicy acknowledgment policy
 * @param mode operating mode (MOCK vs PROXY)
 * @param produce outbound message instructions
 * @param channelPattern regex pattern for wildcard channel matching
 * @param proxyBroker upstream broker URI for proxy forwarding
 * @param proxyChannel upstream destination channel or replacement pattern
 * @param feed consumer feed stub configuration
 */
public record AsyncMetadata(
        String protocol,
        String broker,
        String channel,
        AckPolicy ackPolicy,
        AsyncMode mode,
        List<AsyncProduceDirective> produce,
        String channelPattern,
        String proxyBroker,
        String proxyChannel,
        AsyncFeedDirective feed) {

    public AsyncMetadata {
        protocol = Objects.requireNonNullElse(protocol, "kafka");
        broker = Objects.requireNonNullElse(broker, "");
        channel = Objects.requireNonNullElse(channel, "");
        ackPolicy = Objects.requireNonNullElse(ackPolicy, AckPolicy.ACK);
        mode = Objects.requireNonNullElse(mode, AsyncMode.MOCK);
        produce = produce != null ? List.copyOf(produce) : Collections.emptyList();
        channelPattern = Objects.requireNonNullElse(channelPattern, "");
        proxyBroker = Objects.requireNonNullElse(proxyBroker, "");
        proxyChannel = Objects.requireNonNullElse(proxyChannel, "");
    }

    public AsyncMetadata(
            String protocol,
            String broker,
            String channel,
            AckPolicy ackPolicy,
            AsyncMode mode,
            List<AsyncProduceDirective> produce) {
        this(protocol, broker, channel, ackPolicy, mode, produce, "", "", "", null);
    }

    @SuppressWarnings("unchecked")
    public static Optional<AsyncMetadata> from(Metadata metadata) {
        if (metadata == null || !metadata.containsKey("async")) {
            return Optional.empty();
        }

        Object asyncObj = metadata.get("async");
        if (!(asyncObj instanceof Map<?, ?> rawMap)) {
            return Optional.empty();
        }

        Map<String, Object> map = (Map<String, Object>) rawMap;
        String protocol = (String) map.getOrDefault("protocol", "kafka");
        String broker = (String) map.getOrDefault("broker", "");
        String channel = (String) map.getOrDefault("channel", map.get("destination"));
        AckPolicy ackPolicy = AckPolicy.fromString((String) map.get("ackPolicy"));
        AsyncMode mode = AsyncMode.fromString((String) map.get("mode"));

        String channelPattern = (String) map.getOrDefault("channelPattern", "");
        String proxyBroker = (String) map.getOrDefault("proxyBroker", "");
        String proxyChannel = (String) map.getOrDefault("proxyChannel", "");

        List<AsyncProduceDirective> produceList = new ArrayList<>();
        Object produceObj = map.get("produce");
        if (produceObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> itemMap) {
                    produceList.add(AsyncProduceDirective.fromMap((Map<String, Object>) itemMap));
                }
            }
        } else if (produceObj instanceof Map<?, ?> singleMap) {
            produceList.add(AsyncProduceDirective.fromMap((Map<String, Object>) singleMap));
        }

        AsyncFeedDirective feed = null;
        Object feedObj = map.get("feed");
        if (feedObj instanceof Map<?, ?> feedMap) {
            feed = AsyncFeedDirective.fromMap((Map<String, Object>) feedMap);
        }

        return Optional.of(new AsyncMetadata(
                protocol,
                broker,
                channel,
                ackPolicy,
                mode,
                produceList,
                channelPattern,
                proxyBroker,
                proxyChannel,
                feed));
    }
}
