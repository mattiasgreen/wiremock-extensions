package com.github.mattiasgreen.wiremock.async.destination;

import com.github.mattiasgreen.wiremock.async.kafka.KafkaProduceRecord;
import com.github.mattiasgreen.wiremock.async.kafka.KafkaWireSink;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link DestinationProvider} adapter backed by an in-process {@link KafkaWireSink}.
 *
 * <p>Exposes stable monotonic {@link KafkaProduceRecord#recordId()} identifiers when peeking
 * records so subsequent delete operations by returned ID succeed reliably.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 */
public final class KafkaWireSinkDestinationAdapter implements DestinationProvider {

    private static final String PROTOCOL = "kafka";

    private final Supplier<KafkaWireSink> sinkSupplier;

    public KafkaWireSinkDestinationAdapter(Supplier<KafkaWireSink> sinkSupplier) {
        this.sinkSupplier = Objects.requireNonNull(sinkSupplier, "sinkSupplier must not be null");
    }

    @Override
    public String getProtocol() {
        return PROTOCOL;
    }

    @Override
    public List<AsyncDestination> listDestinations() {
        KafkaWireSink sink = sinkSupplier.get();
        if (sink == null) {
            return Collections.emptyList();
        }
        Map<String, Integer> topicCounts = new LinkedHashMap<>();
        for (KafkaProduceRecord r : sink.getStagedRecords()) {
            topicCounts.put(r.topic(), topicCounts.getOrDefault(r.topic(), 0) + 1);
        }
        List<AsyncDestination> result = new ArrayList<>(topicCounts.size());
        for (Map.Entry<String, Integer> entry : topicCounts.entrySet()) {
            result.add(new AsyncDestination(
                    "kafka:" + entry.getKey(),
                    PROTOCOL,
                    entry.getKey(),
                    "local-kafka-sink",
                    entry.getValue(),
                    Map.of("partitions", 1, "type", "TOPIC")));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<AsyncMessage> peekMessages(String destination, int limit) {
        KafkaWireSink sink = sinkSupplier.get();
        if (sink == null || destination == null || destination.isBlank()) {
            return Collections.emptyList();
        }
        List<AsyncMessage> result = new ArrayList<>();
        for (KafkaProduceRecord r : sink.getStagedRecords()) {
            if (r.topic().equals(destination)) {
                Map<String, String> headers = r.headers();
                String correlationId = headers.getOrDefault("x-correlation-id", "");
                String replyTo = headers.getOrDefault("x-reply-to", "");
                Instant ts = r.timestamp() > 0 ? Instant.ofEpochMilli(r.timestamp()) : Instant.now();
                result.add(AsyncMessage.builder()
                        .id(r.recordId())
                        .protocol(PROTOCOL)
                        .destination(destination)
                        .key(r.key())
                        .correlationId(correlationId)
                        .replyTo(replyTo)
                        .payload(r.value())
                        .headers(headers)
                        .timestamp(ts)
                        .partition(r.partition())
                        .offset(r.offset())
                        .build());
                if (limit > 0 && result.size() >= limit) {
                    break;
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean deleteMessage(String destination, String messageId) {
        KafkaWireSink sink = sinkSupplier.get();
        if (sink == null || destination == null || destination.isBlank() || messageId == null || messageId.isBlank()) {
            return false;
        }
        return sink.deleteStagedRecord(destination, messageId);
    }

    @Override
    public void purgeDestination(String destination) {
        KafkaWireSink sink = sinkSupplier.get();
        if (sink != null && destination != null && !destination.isBlank()) {
            sink.purgeTopic(destination);
        }
    }

    @Override
    public boolean publishMessage(String destination, AsyncMessage message) {
        KafkaWireSink sink = sinkSupplier.get();
        if (sink == null || destination == null || destination.isBlank() || message == null) {
            return false;
        }
        Map<String, String> headers = new LinkedHashMap<>(message.headers());
        if (message.correlationId() != null && !message.correlationId().isBlank()) {
            headers.putIfAbsent("x-correlation-id", message.correlationId());
        }
        if (message.replyTo() != null && !message.replyTo().isBlank()) {
            headers.putIfAbsent("x-reply-to", message.replyTo());
        }
        int partition = message.partition() != null ? message.partition() : 0;
        long timestamp = message.timestamp() != null ? message.timestamp().toEpochMilli() : System.currentTimeMillis();
        sink.stageRecord(new KafkaProduceRecord(
                destination, partition, message.key(), message.payload(), headers, 0L, timestamp));
        return true;
    }
}
