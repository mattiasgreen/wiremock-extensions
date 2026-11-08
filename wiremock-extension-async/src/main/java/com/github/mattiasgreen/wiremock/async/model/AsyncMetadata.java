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
 */
public record AsyncMetadata(
        String protocol,
        String broker,
        String channel,
        AckPolicy ackPolicy,
        AsyncMode mode,
        List<AsyncProduceDirective> produce) {

    public AsyncMetadata {
        protocol = Objects.requireNonNullElse(protocol, "kafka");
        broker = Objects.requireNonNullElse(broker, "");
        channel = Objects.requireNonNullElse(channel, "");
        ackPolicy = Objects.requireNonNullElse(ackPolicy, AckPolicy.ACK);
        mode = Objects.requireNonNullElse(mode, AsyncMode.MOCK);
        produce = produce != null ? List.copyOf(produce) : Collections.emptyList();
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

        return Optional.of(new AsyncMetadata(protocol, broker, channel, ackPolicy, mode, produceList));
    }
}
