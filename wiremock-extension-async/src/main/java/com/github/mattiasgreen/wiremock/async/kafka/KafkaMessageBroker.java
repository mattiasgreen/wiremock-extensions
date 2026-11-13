package com.github.mattiasgreen.wiremock.async.kafka;

import com.github.mattiasgreen.wiremock.async.bridge.MessageBroker;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestination;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-performance Kafka {@link MessageBroker} implementing synchronous upstream acknowledgment,
 * detached non-committing peeking, and virtual-thread connection pooling against remote clusters.
 */
public class KafkaMessageBroker implements MessageBroker, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaMessageBroker.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 5;

    private final String name;
    private final String bootstrapServers;
    private final Map<String, String> properties;

    private volatile KafkaProducer<String, String> producer;
    private volatile boolean closed = false;

    public KafkaMessageBroker(String name, String bootstrapServers) {
        this(name, bootstrapServers, Collections.emptyMap());
    }

    public KafkaMessageBroker(String name, String bootstrapServers, Map<String, String> properties) {
        this.name = Objects.requireNonNullElse(name, "kafka-broker");
        this.bootstrapServers = Objects.requireNonNull(bootstrapServers, "bootstrapServers must not be null");
        this.properties = properties != null ? Map.copyOf(properties) : Collections.emptyMap();
    }

    public String getName() {
        return name;
    }

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    private synchronized KafkaProducer<String, String> getOrCreateProducer() {
        if (producer == null && !closed) {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.ACKS_CONFIG, "1");
            props.put(ProducerConfig.RETRIES_CONFIG, 1);
            props.put(ProducerConfig.CLIENT_ID_CONFIG, "wiremock-kafka-bridge-" + name);
            props.putAll(properties);

            String safeBootstrap = bootstrapServers.replaceAll("[\r\n]", "");
            LOG.info("[KAFKA-BROKER] Initializing pooled KafkaProducer to {}", safeBootstrap);
            producer = new KafkaProducer<>(props);
        }
        return producer;
    }

    @Override
    public void send(String topic, AsyncMessage message) {
        if (topic == null || message == null || closed) {
            return;
        }

        KafkaProducer<String, String> p = getOrCreateProducer();
        if (p == null) {
            return;
        }

        List<Header> headers = new ArrayList<>();
        for (Map.Entry<String, String> h : message.headers().entrySet()) {
            headers.add(new RecordHeader(h.getKey(), h.getValue().getBytes(StandardCharsets.UTF_8)));
        }

        ProducerRecord<String, String> record = new ProducerRecord<>(
                topic,
                null,
                message.timestamp() != null ? message.timestamp().toEpochMilli() : System.currentTimeMillis(),
                message.key(),
                message.payload(),
                headers);

        try {
            // Synchronous Upstream Bridge Acknowledgment (ADR 0023)
            Future<RecordMetadata> future = p.send(record);
            RecordMetadata meta = future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            LOG.debug(
                    "[KAFKA-BROKER] Successfully sent message to {} partition={} offset={}",
                    meta.topic(),
                    meta.partition(),
                    meta.offset());
        } catch (Exception e) {
            String sanitized = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.error("[KAFKA-BROKER] Synchronous bridge dispatch failed to topic {}: {}", topic, sanitized);
            throw new RuntimeException("Synchronous Kafka dispatch failed: " + sanitized, e);
        }
    }

    @Override
    public AsyncMessage poll(String topic) {
        List<AsyncMessage> peeked = peek(topic);
        return !peeked.isEmpty() ? peeked.get(0) : null;
    }

    @Override
    public List<AsyncMessage> peek(String topic) {
        return peek(topic, 0);
    }

    @Override
    public void purge(String channel) {
        purgeTopic(channel);
    }

    /**
     * Performs a detached, non-committing multi-partition browse on the target Kafka topic,
     * reading up to {@code limit} recent records without creating or mutating consumer group offsets.
     */
    @Override
    public List<AsyncMessage> peek(String topic, int limit) {
        if (topic == null || topic.isBlank() || closed) {
            return Collections.emptyList();
        }

        List<AsyncMessage> result = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(buildDetachedConsumerProps())) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic, Duration.ofSeconds(3));
            if (infos == null || infos.isEmpty()) {
                return Collections.emptyList();
            }

            List<TopicPartition> partitions = new ArrayList<>(infos.size());
            for (PartitionInfo info : infos) {
                partitions.add(new TopicPartition(topic, info.partition()));
            }
            consumer.assign(partitions);

            Map<TopicPartition, Long> begins = consumer.beginningOffsets(partitions, Duration.ofSeconds(3));
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, Duration.ofSeconds(3));
            Map<TopicPartition, Long> targetEnds = new LinkedHashMap<>();

            for (TopicPartition tp : partitions) {
                long begin = begins.getOrDefault(tp, 0L);
                long end = ends.getOrDefault(tp, 0L);
                if (end > begin) {
                    long startOffset = limit > 0 ? Math.max(begin, end - limit) : begin;
                    consumer.seek(tp, startOffset);
                    targetEnds.put(tp, end);
                }
            }

            if (targetEnds.isEmpty()) {
                return Collections.emptyList();
            }

            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (!targetEnds.isEmpty() && System.nanoTime() < deadlineNanos) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
                for (ConsumerRecord<String, String> r : records) {
                    result.add(toAsyncMessage(topic, r));
                }
                List<TopicPartition> completed = new ArrayList<>();
                for (Map.Entry<TopicPartition, Long> entry : targetEnds.entrySet()) {
                    if (consumer.position(entry.getKey()) >= entry.getValue()) {
                        completed.add(entry.getKey());
                    }
                }
                completed.forEach(targetEnds::remove);
            }
        } catch (RuntimeException e) {
            String safeTopic = topic.replaceAll("[\r\n]", "");
            String sanitized = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.debug("[KAFKA-BROKER] Detached peek failed for topic [{}]: {}", safeTopic, sanitized);
        }

        result.sort(Comparator.comparing(AsyncMessage::timestamp).reversed());
        if (limit > 0 && result.size() > limit) {
            return Collections.unmodifiableList(new ArrayList<>(result.subList(0, limit)));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<AsyncDestination> discoverDestinations() {
        List<String> topics = listChannels();
        List<AsyncDestination> list = new ArrayList<>(topics.size());
        for (String topic : topics) {
            int count = size(topic);
            list.add(new AsyncDestination(
                    "kafka:" + name + ":" + topic,
                    "kafka",
                    topic,
                    name,
                    count,
                    Map.of("type", "TOPIC", "discovery", "ADMIN_CLIENT")));
        }
        return Collections.unmodifiableList(list);
    }

    @Override
    public List<String> listChannels() {
        if (closed) {
            return Collections.emptyList();
        }
        try (AdminClient admin = AdminClient.create(buildAdminProps())) {
            Set<String> names = admin.listTopics().names().get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<String> topics = new ArrayList<>();
            for (String t : names) {
                if (!isInternalTopic(t)) {
                    topics.add(t);
                }
            }
            Collections.sort(topics);
            return Collections.unmodifiableList(topics);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            String sanitized = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.debug("[KAFKA-BROKER] Failed to list remote Kafka topics: {}", sanitized);
            return Collections.emptyList();
        }
    }

    @Override
    public int size(String topic) {
        if (topic == null || topic.isBlank() || closed) {
            return 0;
        }
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(buildDetachedConsumerProps())) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic, Duration.ofSeconds(3));
            if (infos == null || infos.isEmpty()) {
                return 0;
            }
            List<TopicPartition> partitions = new ArrayList<>(infos.size());
            for (PartitionInfo info : infos) {
                partitions.add(new TopicPartition(topic, info.partition()));
            }
            Map<TopicPartition, Long> begins = consumer.beginningOffsets(partitions, Duration.ofSeconds(3));
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, Duration.ofSeconds(3));
            long total = 0;
            for (TopicPartition tp : partitions) {
                long diff = ends.getOrDefault(tp, 0L) - begins.getOrDefault(tp, 0L);
                if (diff > 0) {
                    total += diff;
                }
            }
            return (int) Math.min(Integer.MAX_VALUE, total);
        } catch (RuntimeException e) {
            String safeTopic = topic.replaceAll("[\r\n]", "");
            String sanitized = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.debug("[KAFKA-BROKER] Failed to calculate size for topic [{}]: {}", safeTopic, sanitized);
            return 0;
        }
    }

    /**
     * Advances low-water marks across all partitions of {@code topic} up to their current end offsets.
     */
    public boolean purgeTopic(String topic) {
        if (topic == null || topic.isBlank() || closed) {
            return false;
        }
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(buildDetachedConsumerProps());
                AdminClient admin = AdminClient.create(buildAdminProps())) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic, Duration.ofSeconds(3));
            if (infos == null || infos.isEmpty()) {
                return false;
            }
            List<TopicPartition> partitions = new ArrayList<>(infos.size());
            for (PartitionInfo info : infos) {
                partitions.add(new TopicPartition(topic, info.partition()));
            }
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, Duration.ofSeconds(3));
            Map<TopicPartition, RecordsToDelete> toDelete = new LinkedHashMap<>();
            for (Map.Entry<TopicPartition, Long> entry : ends.entrySet()) {
                toDelete.put(entry.getKey(), RecordsToDelete.beforeOffset(entry.getValue()));
            }
            if (!toDelete.isEmpty()) {
                admin.deleteRecords(toDelete).all().get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            String safeTopic = topic.replaceAll("[\r\n]", "");
            String sanitized = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.debug("[KAFKA-BROKER] Failed to purge remote topic [{}]: {}", safeTopic, sanitized);
            return false;
        }
    }

    static boolean isInternalTopic(String topic) {
        return topic == null
                || topic.isBlank()
                || topic.startsWith("__consumer_offsets")
                || topic.startsWith("__transaction_state")
                || topic.startsWith("_schemas");
    }

    private Map<String, Object> buildDetachedConsumerProps() {
        Map<String, Object> props = new HashMap<>(properties);
        props.remove(ConsumerConfig.GROUP_ID_CONFIG);
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "wiremock-detached-peek-" + UUID.randomUUID());
        props.putIfAbsent(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 4000);
        props.putIfAbsent(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
        return props;
    }

    private Map<String, Object> buildAdminProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.CLIENT_ID_CONFIG, "wiremock-kafka-admin-" + name);
        props.putIfAbsent(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
        props.putIfAbsent(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 4000);
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String k = entry.getKey();
            if (!"acks".equals(k) && !"compression.type".equals(k)) {
                props.put(k, entry.getValue());
            }
        }
        return props;
    }

    private static AsyncMessage toAsyncMessage(String topic, ConsumerRecord<String, String> r) {
        Map<String, String> hdrs = new LinkedHashMap<>();
        String correlationId = "";
        String replyTo = "";
        for (Header h : r.headers()) {
            String val = h.value() != null ? new String(h.value(), StandardCharsets.UTF_8) : "";
            hdrs.put(h.key(), val);
            if ("x-correlation-id".equalsIgnoreCase(h.key()) || "correlationId".equalsIgnoreCase(h.key())) {
                correlationId = val;
            } else if ("x-reply-to".equalsIgnoreCase(h.key()) || "replyTo".equalsIgnoreCase(h.key())) {
                replyTo = val;
            }
        }
        String value = Objects.requireNonNullElse(r.value(), "");
        String id = topic + "-" + r.partition() + "-" + r.offset();
        return AsyncMessage.builder()
                .id(id)
                .protocol("kafka")
                .destination(topic)
                .key(r.key() != null ? r.key() : "")
                .correlationId(correlationId)
                .replyTo(replyTo)
                .timestamp(Instant.ofEpochMilli(r.timestamp()))
                .headers(hdrs)
                .payload(value)
                .partition(r.partition())
                .offset(r.offset())
                .build();
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (producer != null) {
            try {
                producer.close(Duration.ofSeconds(1));
            } catch (Exception e) {
                LOG.debug("[KAFKA-BROKER] Error closing producer: {}", e.getMessage());
            }
            producer = null;
        }
    }
}
