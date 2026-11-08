package com.github.mattiasgreen.wiremock.async.kafka;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Netty ChannelHandler that decodes Kafka binary wire protocol requests (ApiVersions, Metadata,
 * InitProducerId, Produce) and replies with valid Kafka wire responses.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 */
public class KafkaWireCodec extends ByteToMessageDecoder {

    private static final short API_PRODUCE = 0;
    private static final short API_METADATA = 3;
    private static final short API_API_VERSIONS = 18;
    private static final short API_INIT_PRODUCER_ID = 22;

    private final int port;
    private final Consumer<KafkaProduceRecord> recordConsumer;
    private final Map<String, AtomicLong> topicOffsets = new ConcurrentHashMap<>();

    public KafkaWireCodec(int port, Consumer<KafkaProduceRecord> recordConsumer) {
        this.port = port;
        this.recordConsumer = recordConsumer;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.readableBytes() >= 4) {
            in.markReaderIndex();
            int messageLength = in.readInt();
            if (messageLength < 0 || messageLength > 64 * 1024 * 1024) {
                ctx.close();
                return;
            }
            if (in.readableBytes() < messageLength) {
                in.resetReaderIndex();
                return;
            }

            ByteBuf frame = in.readSlice(messageLength);
            processFrame(ctx, frame);
        }
    }

    private void processFrame(ChannelHandlerContext ctx, ByteBuf frame) {
        short apiKey = frame.readShort();
        frame.readShort(); // api_version
        int correlationId = frame.readInt();
        readString(frame); // client_id

        ByteBuf out = ctx.alloc().buffer();
        int lengthPlaceholder = out.writerIndex();
        out.writeInt(0); // placeholder for total frame length

        switch (apiKey) {
            case API_API_VERSIONS -> handleApiVersions(out, correlationId);
            case API_METADATA -> handleMetadata(out, correlationId, frame);
            case API_INIT_PRODUCER_ID -> handleInitProducerId(out, correlationId);
            case API_PRODUCE -> handleProduce(out, correlationId, frame);
            default -> {
                // Unknown API key: return correlationId and generic error
                out.writeInt(correlationId);
                out.writeShort(35); // UNSUPPORTED_VERSION
            }
        }

        int totalLen = out.writerIndex() - lengthPlaceholder - 4;
        out.setInt(lengthPlaceholder, totalLen);
        ctx.writeAndFlush(out);
    }

    private void handleApiVersions(ByteBuf out, int correlationId) {
        // Per KIP-511, ApiVersionsResponse always uses Header v0 (correlationId only)
        out.writeInt(correlationId);
        out.writeShort(0); // error_code: NONE

        // Return supported API keys
        out.writeInt(4); // 4 API keys supported

        out.writeShort(API_API_VERSIONS);
        out.writeShort(0); // min_version
        out.writeShort(3); // max_version

        out.writeShort(API_METADATA);
        out.writeShort(0);
        out.writeShort(7); // Non-flexible v7

        out.writeShort(API_INIT_PRODUCER_ID);
        out.writeShort(0);
        out.writeShort(4);

        out.writeShort(API_PRODUCE);
        out.writeShort(0);
        out.writeShort(7); // Non-flexible v7

        out.writeInt(0); // throttle_time_ms
    }

    private void handleMetadata(ByteBuf out, int correlationId, ByteBuf frame) {
        out.writeInt(correlationId);
        out.writeInt(0); // throttle_time_ms

        // 1 Broker: Node 0 at 127.0.0.1:port
        out.writeInt(1); // brokers array length = 1
        out.writeInt(0); // node_id = 0
        writeString(out, "127.0.0.1");
        out.writeInt(port);
        writeString(out, null); // rack

        writeString(out, "wiremock-mock-cluster");
        out.writeInt(0); // controller_id = 0

        // Read requested topics
        List<String> requestedTopics = new ArrayList<>();
        if (frame.readableBytes() >= 4) {
            int topicsLen = frame.readInt();
            for (int i = 0; i < topicsLen && frame.readableBytes() >= 2; i++) {
                String t = readString(frame);
                if (t != null && !t.isBlank()) {
                    requestedTopics.add(t);
                }
            }
        }
        if (requestedTopics.isEmpty()) {
            requestedTopics.add("orders.events");
        }

        // Return metadata for each topic
        out.writeInt(requestedTopics.size());
        for (String topic : requestedTopics) {
            out.writeShort(0); // error_code = 0
            writeString(out, topic);
            out.writeByte(0); // is_internal = false

            // 1 partition (partition 0)
            out.writeInt(1); // partitions array length = 1
            out.writeShort(0); // partition error_code = 0
            out.writeInt(0); // partition_index = 0
            out.writeInt(0); // leader_id = 0
            out.writeInt(0); // leader_epoch = 0

            out.writeInt(1); // replica_nodes length
            out.writeInt(0); // replica 0

            out.writeInt(1); // isr_nodes length
            out.writeInt(0); // isr 0

            out.writeInt(0); // offline_replicas length = 0
        }
    }

    private void handleInitProducerId(ByteBuf out, int correlationId) {
        out.writeInt(correlationId);
        out.writeInt(0); // throttle_time_ms
        out.writeShort(0); // error_code: NONE
        out.writeLong(1000L); // producer_id
        out.writeShort(0); // producer_epoch
    }

    private void handleProduce(ByteBuf out, int correlationId, ByteBuf frame) {
        out.writeInt(correlationId);

        // Skip non-flexible produce request header fields
        readString(frame); // transactional_id
        frame.readShort(); // acks
        frame.readInt(); // timeout_ms

        int topicsLength = frame.readInt();
        List<TopicProduceResult> results = new ArrayList<>();

        for (int i = 0; i < topicsLength; i++) {
            String topicName = readString(frame);
            int partitionsLen = frame.readInt();

            for (int p = 0; p < partitionsLen; p++) {
                int partitionId = frame.readInt();
                int recordsLength = frame.readInt();

                long baseOffset = 0L;
                if (recordsLength > 0 && frame.readableBytes() >= recordsLength) {
                    ByteBuf recordsBuf = frame.readSlice(recordsLength);
                    baseOffset = parseRecordBatch(topicName, partitionId, recordsBuf);
                }
                results.add(new TopicProduceResult(topicName, partitionId, baseOffset));
            }
        }

        // Encode ProduceResponse v7
        out.writeInt(results.size());
        for (TopicProduceResult res : results) {
            writeString(out, res.topic);
            out.writeInt(1); // 1 partition response
            out.writeInt(res.partition);
            out.writeShort(0); // error_code = 0
            out.writeLong(res.baseOffset);
            out.writeLong(System.currentTimeMillis()); // log_append_time
            out.writeLong(0L); // log_start_offset
        }
        out.writeInt(0); // throttle_time_ms
    }

    private long parseRecordBatch(String topic, int partition, ByteBuf buf) {
        if (buf.readableBytes() < 61) {
            return 0L;
        }

        buf.readLong(); // base_offset
        buf.readInt(); // batch_length
        buf.readInt(); // partition_leader_epoch
        byte magic = buf.readByte();
        if (magic != 2) {
            return 0L;
        }

        buf.readInt(); // crc
        buf.readShort(); // attributes
        buf.readInt(); // last_offset_delta
        long firstTimestamp = buf.readLong();
        buf.readLong(); // max_timestamp
        buf.readLong(); // producer_id
        buf.readShort(); // producer_epoch
        buf.readInt(); // base_sequence
        int recordsCount = buf.readInt();

        AtomicLong offsetCounter = topicOffsets.computeIfAbsent(topic + ":" + partition, k -> new AtomicLong(0));
        long startOffset = offsetCounter.get();

        for (int r = 0; r < recordsCount && buf.isReadable(); r++) {
            readVarint(buf); // length
            buf.readByte(); // attributes
            readVarint(buf); // timestamp_delta
            readVarint(buf); // offset_delta

            // Key
            int keyLen = readVarint(buf);
            String key = "";
            if (keyLen > 0 && buf.readableBytes() >= keyLen) {
                byte[] keyBytes = new byte[keyLen];
                buf.readBytes(keyBytes);
                key = new String(keyBytes, StandardCharsets.UTF_8);
            }

            // Value
            int valueLen = readVarint(buf);
            String value = "";
            if (valueLen > 0 && buf.readableBytes() >= valueLen) {
                byte[] valueBytes = new byte[valueLen];
                buf.readBytes(valueBytes);
                value = new String(valueBytes, StandardCharsets.UTF_8);
            }

            // Headers
            int headersCount = readVarint(buf);
            Map<String, String> headers = Collections.emptyMap();
            if (headersCount > 0) {
                headers = new HashMap<>();
                for (int h = 0; h < headersCount && buf.isReadable(); h++) {
                    int hKeyLen = readVarint(buf);
                    String hKey = "";
                    if (hKeyLen > 0 && buf.readableBytes() >= hKeyLen) {
                        byte[] hk = new byte[hKeyLen];
                        buf.readBytes(hk);
                        hKey = new String(hk, StandardCharsets.UTF_8);
                    }
                    int hValLen = readVarint(buf);
                    String hVal = "";
                    if (hValLen > 0 && buf.readableBytes() >= hValLen) {
                        byte[] hv = new byte[hValLen];
                        buf.readBytes(hv);
                        hVal = new String(hv, StandardCharsets.UTF_8);
                    }
                    headers.put(hKey, hVal);
                }
            }

            long currentOffset = offsetCounter.getAndIncrement();
            if (recordConsumer != null) {
                recordConsumer.accept(
                        new KafkaProduceRecord(topic, partition, key, value, headers, currentOffset, firstTimestamp));
            }
        }

        return startOffset;
    }

    private int readVarint(ByteBuf buf) {
        int value = 0;
        int shift = 0;
        while (buf.isReadable()) {
            byte b = buf.readByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return (value >>> 1) ^ -(value & 1); // decode zigzag
    }

    private static String readString(ByteBuf buf) {
        if (buf.readableBytes() < 2) return null;
        short len = buf.readShort();
        if (len <= 0 || buf.readableBytes() < len) return null;
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(ByteBuf buf, String s) {
        if (s == null) {
            buf.writeShort(-1);
            return;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }

    private record TopicProduceResult(String topic, int partition, long baseOffset) {}
}
