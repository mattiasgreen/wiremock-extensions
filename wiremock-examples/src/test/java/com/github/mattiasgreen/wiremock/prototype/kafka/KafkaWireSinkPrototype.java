package com.github.mattiasgreen.wiremock.prototype.kafka;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Throwaway Wire Code Prototype: In-Process Zero-Daemon Netty Kafka Sink.
 *
 * <p>Implements the essential 4 Kafka wire APIs to sink records from standard KafkaProducer clients
 * without running any real Kafka broker daemons:
 *
 * <ul>
 *   <li>API 18: ApiVersions (v0..v3)
 *   <li>API 3: Metadata (v0..v7)
 *   <li>API 22: InitProducerId (v0..v1)
 *   <li>API 0: Produce (v3..v7)
 * </ul>
 */
public class KafkaWireSinkPrototype implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaWireSinkPrototype.class);

    private final int requestedPort;
    private int boundPort;
    private Channel serverChannel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    private final AtomicLong offsetCounter = new AtomicLong(0);
    private final List<ReceivedRecord> receivedRecords = new CopyOnWriteArrayList<>();

    public record ReceivedRecord(
            String topic, int partition, long offset, String key, String value, List<HeaderEntry> headers) {}

    public record HeaderEntry(String key, String value) {}

    public KafkaWireSinkPrototype(int requestedPort) {
        this.requestedPort = requestedPort;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(2);

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        // Kafka frames: 4-byte big-endian length prefix
                        p.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(104_857_600, 0, 4, 0, 4));
                        p.addLast("frameEncoder", new LengthFieldPrepender(4));
                        p.addLast("kafkaHandler", new KafkaSinkHandler());
                    }
                });

        serverChannel = b.bind("127.0.0.1", requestedPort).sync().channel();
        boundPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
        log.info("KafkaWireSinkPrototype bound on 127.0.0.1:{}", boundPort);
    }

    public int getPort() {
        return boundPort;
    }

    public List<ReceivedRecord> getReceivedRecords() {
        return Collections.unmodifiableList(receivedRecords);
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    private class KafkaSinkHandler extends SimpleChannelInboundHandler<ByteBuf> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            short apiKey = msg.readShort();
            short apiVersion = msg.readShort();
            int correlationId = msg.readInt();
            String clientId = readString(msg);

            log.debug(
                    "Received Kafka frame: apiKey={}, apiVersion={}, corrId={}, client={}",
                    apiKey,
                    apiVersion,
                    correlationId,
                    clientId);

            ByteBuf response = ctx.alloc().buffer();

            switch (apiKey) {
                case 18 -> handleApiVersions(response, apiVersion, correlationId, msg);
                case 3 -> handleMetadata(response, apiVersion, correlationId, msg);
                case 22 -> handleInitProducerId(response, apiVersion, correlationId, msg);
                case 0 -> handleProduce(response, apiVersion, correlationId, msg);
                default -> {
                    log.warn("Unhandled API key: {}", apiKey);
                    response.writeInt(correlationId);
                    response.writeShort(35); // UNSUPPORTED_VERSION
                }
            }

            ctx.writeAndFlush(response);
        }

        private void handleApiVersions(ByteBuf out, short apiVersion, int correlationId, ByteBuf in) {
            // Per KIP-511: ApiVersionsResponse MUST ALWAYS use Response Header v0 (correlationId only)
            out.writeInt(correlationId);

            boolean isFlexible = apiVersion >= 3;
            out.writeShort(0); // error_code: 0 (NONE)

            if (isFlexible) {
                writeUnsignedVarint(out, 4 + 1); // Compact array: len + 1
            } else {
                out.writeInt(4); // Standard array
            }

            // Key 0: Produce (v0..v7)
            writeApiKeyEntry(out, (short) 0, (short) 0, (short) 7, isFlexible);
            // Key 3: Metadata (v0..v7)
            writeApiKeyEntry(out, (short) 3, (short) 0, (short) 7, isFlexible);
            // Key 18: ApiVersions (v0..v3)
            writeApiKeyEntry(out, (short) 18, (short) 0, (short) 3, isFlexible);
            // Key 22: InitProducerId (v0..v1)
            writeApiKeyEntry(out, (short) 22, (short) 0, (short) 1, isFlexible);

            out.writeInt(0); // throttle_time_ms = 0

            if (isFlexible) {
                writeUnsignedVarint(out, 0); // tagged fields count = 0
            }
        }

        private void writeApiKeyEntry(ByteBuf out, short key, short minVer, short maxVer, boolean isFlexible) {
            out.writeShort(key);
            out.writeShort(minVer);
            out.writeShort(maxVer);
            if (isFlexible) {
                writeUnsignedVarint(out, 0); // tagged fields
            }
        }

        private void handleMetadata(ByteBuf out, short apiVersion, int correlationId, ByteBuf in) {
            List<String> requestedTopics = new ArrayList<>();
            try {
                if (in.readableBytes() >= 4) {
                    int numTopics = in.readInt();
                    if (numTopics > 0) {
                        for (int t = 0; t < numTopics; t++) {
                            String name = readString(in);
                            if (name != null && !name.isEmpty()) {
                                requestedTopics.add(name);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Error parsing requested metadata topics: {}", e.getMessage());
            }
            if (requestedTopics.isEmpty()) {
                requestedTopics.add("orders.events");
            }

            // Response Header v0
            out.writeInt(correlationId);
            out.writeInt(0); // throttle_time_ms

            // 1. Brokers Array
            out.writeInt(1); // 1 broker
            out.writeInt(0); // node_id = 0
            writeString(out, "127.0.0.1"); // host
            out.writeInt(boundPort); // port
            if (apiVersion >= 1) {
                writeString(out, null); // rack = null
            }

            // 2. Cluster ID & Controller ID
            if (apiVersion >= 2) {
                writeString(out, "wiremock-mock-cluster");
            }
            if (apiVersion >= 1) {
                out.writeInt(0); // controller_id = 0
            }

            // 3. Topics Array
            out.writeInt(requestedTopics.size());
            for (String topic : requestedTopics) {
                out.writeShort(0); // error_code: 0 (NONE)
                writeString(out, topic);
                if (apiVersion >= 1) {
                    out.writeBoolean(false); // is_internal = false
                }

                // Partitions array: 1 partition (partition 0)
                out.writeInt(1);
                out.writeShort(0); // error_code = 0
                out.writeInt(0); // partition_index = 0
                out.writeInt(0); // leader_id = 0
                if (apiVersion >= 7) {
                    out.writeInt(0); // leader_epoch = 0
                }

                // Replicas: [0]
                out.writeInt(1);
                out.writeInt(0);

                // ISR: [0]
                out.writeInt(1);
                out.writeInt(0);

                if (apiVersion >= 5) {
                    out.writeInt(0); // offline_replicas count = 0
                }
            }
        }

        private void handleInitProducerId(ByteBuf out, short apiVersion, int correlationId, ByteBuf in) {
            // Response Header v0
            out.writeInt(correlationId);
            out.writeInt(0); // throttle_time_ms = 0
            out.writeShort(0); // error_code: 0 (NONE)
            out.writeLong(1000L); // producer_id = 1000
            out.writeShort(0); // producer_epoch = 0
        }

        private void handleProduce(ByteBuf out, short apiVersion, int correlationId, ByteBuf in) {
            List<String> producedTopics = new ArrayList<>();
            long baseOffset = offsetCounter.getAndIncrement();

            try {
                if (apiVersion >= 3) {
                    readString(in); // transactional_id
                }
                in.readShort(); // acks
                in.readInt(); // timeout_ms

                int topicCount = in.readInt();
                for (int t = 0; t < topicCount; t++) {
                    String topicName = readString(in);
                    producedTopics.add(topicName);

                    int partitionCount = in.readInt();
                    for (int p = 0; p < partitionCount; p++) {
                        int partitionId = in.readInt();
                        int recordsSize = in.readInt();

                        if (recordsSize > 0 && in.readableBytes() >= recordsSize) {
                            ByteBuf recordsSlice = in.readSlice(recordsSize);
                            parseRecordBatchV2(topicName, partitionId, baseOffset, recordsSlice);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Error parsing ProduceRequest payload: {}", e.getMessage(), e);
            }

            if (producedTopics.isEmpty()) {
                producedTopics.add("orders.events");
            }

            // ProduceResponse
            out.writeInt(correlationId);
            out.writeInt(producedTopics.size());
            for (String topic : producedTopics) {
                writeString(out, topic);
                out.writeInt(1); // 1 partition response
                out.writeInt(0); // partition = 0
                out.writeShort(0); // error_code: 0 (NONE)
                out.writeLong(baseOffset); // base_offset
                if (apiVersion >= 2) {
                    out.writeLong(System.currentTimeMillis()); // log_append_time
                }
                if (apiVersion >= 5) {
                    out.writeLong(0L); // log_start_offset
                }
            }
            out.writeInt(0); // throttle_time_ms = 0
        }

        private void parseRecordBatchV2(String topic, int partition, long baseOffset, ByteBuf in) {
            if (in.readableBytes() < 61) {
                return;
            }

            in.skipBytes(8); // baseOffset
            in.readInt(); // batchLength
            in.skipBytes(4); // partitionLeaderEpoch
            byte magic = in.readByte();
            if (magic != 2) {
                log.warn("Non-v2 RecordBatch (magic={}) received, skipping batch unpack", magic);
                return;
            }
            in.skipBytes(4); // crc
            short attributes = in.readShort(); // attributes
            in.skipBytes(4 + 8 + 8 + 8 + 2 + 4); // deltas, timestamps, producer ID, epoch, sequence
            int recordsCount = in.readInt();

            int compression = attributes & 0x07;
            if (compression != 0) {
                log.warn("Compressed batch (type={}) received, storing marker", compression);
                receivedRecords.add(new ReceivedRecord(
                        topic, partition, baseOffset, null, "[COMPRESSED:" + compression + "]", List.of()));
                return;
            }

            for (int r = 0; r < recordsCount && in.isReadable(); r++) {
                try {
                    int recordLength = readVarint(in);
                    int recordStart = in.readerIndex();

                    in.readByte(); // attributes
                    readVarlong(in); // timestampDelta
                    readVarint(in); // offsetDelta

                    // Key
                    int keyLen = readVarint(in);
                    String key = null;
                    if (keyLen >= 0) {
                        byte[] keyBytes = new byte[keyLen];
                        in.readBytes(keyBytes);
                        key = new String(keyBytes, StandardCharsets.UTF_8);
                    }

                    // Value
                    int valLen = readVarint(in);
                    String value = null;
                    if (valLen >= 0) {
                        byte[] valBytes = new byte[valLen];
                        in.readBytes(valBytes);
                        value = new String(valBytes, StandardCharsets.UTF_8);
                    }

                    // Headers
                    int headerCount = readVarint(in);
                    List<HeaderEntry> headers = new ArrayList<>();
                    for (int h = 0; h < headerCount; h++) {
                        int hKeyLen = readVarint(in);
                        String hKey = "";
                        if (hKeyLen > 0) {
                            byte[] hKeyBytes = new byte[hKeyLen];
                            in.readBytes(hKeyBytes);
                            hKey = new String(hKeyBytes, StandardCharsets.UTF_8);
                        }
                        int hValLen = readVarint(in);
                        String hVal = "";
                        if (hValLen > 0) {
                            byte[] hValBytes = new byte[hValLen];
                            in.readBytes(hValBytes);
                            hVal = new String(hValBytes, StandardCharsets.UTF_8);
                        }
                        headers.add(new HeaderEntry(hKey, hVal));
                    }

                    receivedRecords.add(new ReceivedRecord(topic, partition, baseOffset + r, key, value, headers));
                    log.info(
                            "Sunk Kafka record: topic={}, partition={}, offset={}, key={}, value={}",
                            topic,
                            partition,
                            baseOffset + r,
                            key,
                            value);

                    int consumed = in.readerIndex() - recordStart;
                    if (consumed < recordLength) {
                        in.skipBytes(recordLength - consumed);
                    }
                } catch (Exception e) {
                    log.warn("Error unpacking inner record: {}", e.getMessage());
                    break;
                }
            }
        }
    }

    // --- Wire Protocol Helpers ---

    private static String readString(ByteBuf in) {
        if (in.readableBytes() < 2) return null;
        short len = in.readShort();
        if (len < 0) return null;
        if (in.readableBytes() < len) return null;
        byte[] b = new byte[len];
        in.readBytes(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void writeString(ByteBuf out, String s) {
        if (s == null) {
            out.writeShort(-1);
            return;
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(b.length);
        out.writeBytes(b);
    }

    private static void writeUnsignedVarint(ByteBuf out, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }

    private static int readUnsignedVarint(ByteBuf in) {
        int value = 0;
        int i = 0;
        int b;
        while (((b = in.readByte()) & 0x80) != 0) {
            value |= (b & 0x7F) << i;
            i += 7;
            if (i > 28) throw new IllegalArgumentException("Invalid unsigned varint");
        }
        value |= b << i;
        return value;
    }

    private static int readVarint(ByteBuf in) {
        int raw = readUnsignedVarint(in);
        return (raw >>> 1) ^ -(raw & 1); // ZigZag decode
    }

    private static long readVarlong(ByteBuf in) {
        long value = 0;
        int i = 0;
        long b;
        while (((b = in.readByte()) & 0x80) != 0) {
            value |= (b & 0x7F) << i;
            i += 7;
            if (i > 63) throw new IllegalArgumentException("Invalid varlong");
        }
        value |= b << i;
        return (value >>> 1) ^ -(value & 1); // ZigZag decode
    }
}
