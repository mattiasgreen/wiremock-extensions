package com.github.mattiasgreen.wiremock.async.kafka;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor;
import org.apache.kafka.clients.consumer.internals.ConsumerProtocol;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.message.ApiVersionsResponseData;
import org.apache.kafka.common.message.FetchRequestData;
import org.apache.kafka.common.message.FetchResponseData;
import org.apache.kafka.common.message.FindCoordinatorRequestData;
import org.apache.kafka.common.message.FindCoordinatorResponseData;
import org.apache.kafka.common.message.HeartbeatResponseData;
import org.apache.kafka.common.message.InitProducerIdResponseData;
import org.apache.kafka.common.message.JoinGroupRequestData;
import org.apache.kafka.common.message.JoinGroupResponseData;
import org.apache.kafka.common.message.ListOffsetsRequestData;
import org.apache.kafka.common.message.ListOffsetsResponseData;
import org.apache.kafka.common.message.MetadataRequestData;
import org.apache.kafka.common.message.MetadataResponseData;
import org.apache.kafka.common.message.OffsetCommitRequestData;
import org.apache.kafka.common.message.OffsetCommitResponseData;
import org.apache.kafka.common.message.OffsetFetchRequestData;
import org.apache.kafka.common.message.OffsetFetchResponseData;
import org.apache.kafka.common.message.ProduceRequestData;
import org.apache.kafka.common.message.ProduceResponseData;
import org.apache.kafka.common.message.SyncGroupRequestData;
import org.apache.kafka.common.message.SyncGroupResponseData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ApiMessage;
import org.apache.kafka.common.protocol.ByteBufferAccessor;
import org.apache.kafka.common.protocol.MessageUtil;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.MemoryRecordsBuilder;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.record.Records;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.requests.RequestHeader;
import org.apache.kafka.common.requests.ResponseHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin Netty ChannelHandler that decodes Kafka wire protocol frames and delegates all topic-log
 * and consumer-group state to a shared {@link KafkaTopicLogCoordinator}.
 */
public class KafkaWireCodec extends ByteToMessageDecoder {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaWireCodec.class);

    private final int port;
    private final KafkaTopicLogCoordinator coordinator;
    private final Consumer<KafkaProduceRecord> recordConsumer;

    public KafkaWireCodec(int port, Consumer<KafkaProduceRecord> recordConsumer) {
        this(port, new KafkaTopicLogCoordinator(), recordConsumer);
    }

    public KafkaWireCodec(int port, KafkaTopicLogCoordinator coordinator, Consumer<KafkaProduceRecord> recordConsumer) {
        this.port = port;
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.recordConsumer = Objects.requireNonNullElse(recordConsumer, r -> {});
    }

    public void stageRecord(KafkaProduceRecord record) {
        coordinator.stage(record);
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
            byte[] frameBytes = new byte[messageLength];
            frame.readBytes(frameBytes);
            ByteBuffer buffer = ByteBuffer.wrap(frameBytes);

            processFrame(ctx, buffer);
        }
    }

    private void processFrame(ChannelHandlerContext ctx, ByteBuffer buffer) {
        RequestHeader header = RequestHeader.parse(buffer);
        ByteBufferAccessor accessor = new ByteBufferAccessor(buffer);
        short version = header.apiVersion();

        switch (header.apiKey()) {
            case API_VERSIONS -> handleApiVersions(ctx, header);
            case METADATA -> handleMetadata(ctx, header, accessor, version);
            case INIT_PRODUCER_ID -> handleInitProducerId(ctx, header);
            case PRODUCE -> handleProduce(ctx, header, accessor, version);
            case FIND_COORDINATOR -> handleFindCoordinator(ctx, header, accessor, version);
            case JOIN_GROUP -> handleJoinGroup(ctx, header, accessor, version);
            case SYNC_GROUP -> handleSyncGroup(ctx, header, accessor, version);
            case HEARTBEAT -> handleHeartbeat(ctx, header);
            case LIST_OFFSETS -> handleListOffsets(ctx, header, accessor, version);
            case FETCH -> handleFetch(ctx, header, accessor, version);
            case OFFSET_COMMIT -> handleOffsetCommit(ctx, header, accessor, version);
            case OFFSET_FETCH -> handleOffsetFetch(ctx, header, accessor, version);
            default -> {
                LOG.debug("[KAFKA-CODEC] Unhandled apiKey={} version={}", header.apiKey(), version);
                ApiVersionsResponseData unsupported = new ApiVersionsResponseData().setErrorCode((short) 35);
                sendResponse(ctx, header, unsupported);
            }
        }
    }

    private void sendResponse(ChannelHandlerContext ctx, RequestHeader header, ApiMessage message) {
        short headerVersion = header.apiKey() == ApiKeys.API_VERSIONS
                ? 0
                : header.apiKey().responseHeaderVersion(header.apiVersion());
        ResponseHeader responseHeader = new ResponseHeader(header.correlationId(), headerVersion);
        ByteBuffer headerBytes = MessageUtil.toByteBuffer(responseHeader.data(), headerVersion);
        ByteBuffer bodyBytes = MessageUtil.toByteBuffer(message, header.apiVersion());

        int totalLen = headerBytes.remaining() + bodyBytes.remaining();
        ByteBuf out = ctx.alloc().buffer(totalLen + 4);
        out.writeInt(totalLen);
        out.writeBytes(headerBytes);
        out.writeBytes(bodyBytes);
        ctx.writeAndFlush(out);
    }

    private void handleApiVersions(ChannelHandlerContext ctx, RequestHeader header) {
        ApiVersionsResponseData data = new ApiVersionsResponseData();
        data.setErrorCode((short) 0);

        List<ApiKeys> supportedKeys = List.of(
                ApiKeys.PRODUCE,
                ApiKeys.FETCH,
                ApiKeys.LIST_OFFSETS,
                ApiKeys.METADATA,
                ApiKeys.OFFSET_COMMIT,
                ApiKeys.OFFSET_FETCH,
                ApiKeys.FIND_COORDINATOR,
                ApiKeys.JOIN_GROUP,
                ApiKeys.HEARTBEAT,
                ApiKeys.SYNC_GROUP,
                ApiKeys.API_VERSIONS,
                ApiKeys.INIT_PRODUCER_ID);

        for (ApiKeys key : supportedKeys) {
            short maxVer = (key == ApiKeys.OFFSET_FETCH) ? (short) 7 : key.latestVersion();
            data.apiKeys()
                    .add(new ApiVersionsResponseData.ApiVersion()
                            .setApiKey(key.id)
                            .setMinVersion(key.oldestVersion())
                            .setMaxVersion(maxVer));
        }
        sendResponse(ctx, header, data);
    }

    private void handleMetadata(
            ChannelHandlerContext ctx, RequestHeader header, ByteBufferAccessor accessor, short version) {
        MetadataRequestData req = new MetadataRequestData(accessor, version);
        MetadataResponseData data = new MetadataResponseData();
        data.setThrottleTimeMs(0);
        data.setClusterId("wiremock-mock-cluster");
        data.setControllerId(0);

        data.brokers()
                .add(new MetadataResponseData.MetadataResponseBroker()
                        .setNodeId(0)
                        .setHost("127.0.0.1")
                        .setPort(port)
                        .setRack(null));

        List<String> requestedTopics = new ArrayList<>();
        if (req.topics() != null) {
            for (MetadataRequestData.MetadataRequestTopic t : req.topics()) {
                if (t.name() != null && !t.name().isBlank()) {
                    requestedTopics.add(t.name());
                }
            }
        }
        if (requestedTopics.isEmpty()) {
            Set<String> knownTopics = coordinator.listTopics();
            if (knownTopics.isEmpty()) {
                requestedTopics.add("orders.events");
            } else {
                requestedTopics.addAll(knownTopics);
            }
        }

        for (String topic : requestedTopics) {
            MetadataResponseData.MetadataResponseTopic tData = new MetadataResponseData.MetadataResponseTopic()
                    .setErrorCode((short) 0)
                    .setName(topic)
                    .setIsInternal(false);

            tData.partitions()
                    .add(new MetadataResponseData.MetadataResponsePartition()
                            .setErrorCode((short) 0)
                            .setPartitionIndex(0)
                            .setLeaderId(0)
                            .setLeaderEpoch(0)
                            .setReplicaNodes(List.of(0))
                            .setIsrNodes(List.of(0))
                            .setOfflineReplicas(Collections.emptyList()));

            data.topics().add(tData);
        }

        sendResponse(ctx, header, data);
    }

    private void handleInitProducerId(ChannelHandlerContext ctx, RequestHeader header) {
        InitProducerIdResponseData data = new InitProducerIdResponseData()
                .setErrorCode((short) 0)
                .setProducerId(1000L)
                .setProducerEpoch((short) 0)
                .setThrottleTimeMs(0);
        sendResponse(ctx, header, data);
    }

    private void handleFindCoordinator(
            ChannelHandlerContext ctx, RequestHeader header, ByteBufferAccessor accessor, short version) {
        FindCoordinatorRequestData req = new FindCoordinatorRequestData(accessor, version);
        FindCoordinatorResponseData data =
                new FindCoordinatorResponseData().setErrorCode((short) 0).setThrottleTimeMs(0);

        if (version >= 4) {
            String key = req.key() != null
                    ? req.key()
                    : (!req.coordinatorKeys().isEmpty() ? req.coordinatorKeys().get(0) : "default");
            data.coordinators()
                    .add(new FindCoordinatorResponseData.Coordinator()
                            .setKey(key)
                            .setNodeId(0)
                            .setHost("127.0.0.1")
                            .setPort(port)
                            .setErrorCode((short) 0));
        } else {
            data.setNodeId(0).setHost("127.0.0.1").setPort(port);
        }
        sendResponse(ctx, header, data);
    }

    private void handleJoinGroup(
            ChannelHandlerContext ctx, RequestHeader header, ByteBufferAccessor accessor, short version) {
        JoinGroupRequestData req = new JoinGroupRequestData(accessor, version);
        String memberId = req.memberId().isEmpty() ? "wm-consumer-" + UUID.randomUUID() : req.memberId();

        byte[] clientProtocolMetadata = new byte[0];
        if (!req.protocols().isEmpty()) {
            clientProtocolMetadata = req.protocols().iterator().next().metadata();
            try {
                var sub = ConsumerProtocol.deserializeSubscription(ByteBuffer.wrap(clientProtocolMetadata));
                if (sub.topics() != null && !sub.topics().isEmpty()) {
                    coordinator.recordMemberSubscription(memberId, sub.topics());
                }
            } catch (RuntimeException e) {
                LOG.debug("[KAFKA-SINK] Unable to parse subscription metadata: {}", sanitizeLog(e.getMessage()));
            }
        }

        JoinGroupResponseData data = new JoinGroupResponseData()
                .setErrorCode((short) 0)
                .setGenerationId(1)
                .setProtocolType("consumer")
                .setProtocolName("range")
                .setLeader(memberId)
                .setMemberId(memberId)
                .setThrottleTimeMs(0);

        data.members()
                .add(new JoinGroupResponseData.JoinGroupResponseMember()
                        .setMemberId(memberId)
                        .setMetadata(clientProtocolMetadata));

        sendResponse(ctx, header, data);
    }

    private void handleSyncGroup(
            ChannelHandlerContext ctx, RequestHeader header, ByteBufferAccessor accessor, short version) {
        SyncGroupRequestData req = new SyncGroupRequestData(accessor, version);
        byte[] rawAssignment = null;

        if (req.assignments() != null) {
            for (SyncGroupRequestData.SyncGroupRequestAssignment a : req.assignments()) {
                if (req.memberId().equals(a.memberId()) && a.assignment() != null && a.assignment().length > 0) {
                    rawAssignment = a.assignment();
                    break;
                }
            }
        }

        if (rawAssignment == null) {
            List<String> subscribedTopics = coordinator.getMemberSubscription(req.memberId());
            List<TopicPartition> partitions = new ArrayList<>();
            if (subscribedTopics.isEmpty()) {
                partitions.add(new TopicPartition("orders.events", 0));
            } else {
                for (String topic : subscribedTopics) {
                    partitions.add(new TopicPartition(topic, 0));
                }
            }
            ConsumerPartitionAssignor.Assignment assignment = new ConsumerPartitionAssignor.Assignment(partitions);
            ByteBuffer assignmentBuffer = ConsumerProtocol.serializeAssignment(assignment);
            rawAssignment = new byte[assignmentBuffer.remaining()];
            assignmentBuffer.get(rawAssignment);
        }

        SyncGroupResponseData data = new SyncGroupResponseData()
                .setErrorCode((short) 0)
                .setProtocolType("consumer")
                .setProtocolName("range")
                .setAssignment(rawAssignment)
                .setThrottleTimeMs(0);

        sendResponse(ctx, header, data);
    }

    private void handleHeartbeat(ChannelHandlerContext ctx, RequestHeader header) {
        HeartbeatResponseData data =
                new HeartbeatResponseData().setErrorCode((short) 0).setThrottleTimeMs(0);
        sendResponse(ctx, header, data);
    }

    private void handleListOffsets(
            ChannelHandlerContext ctx, RequestHeader header, ByteBufferAccessor accessor, short version) {
        ListOffsetsRequestData req = new ListOffsetsRequestData(accessor, version);
        ListOffsetsResponseData data = new ListOffsetsResponseData().setThrottleTimeMs(0);

        for (ListOffsetsRequestData.ListOffsetsTopic t : req.topics()) {
            ListOffsetsResponseData.ListOffsetsTopicResponse tResp =
                    new ListOffsetsResponseData.ListOffsetsTopicResponse().setName(t.name());
            for (ListOffsetsRequestData.ListOffsetsPartition p : t.partitions()) {
                long offset = coordinator.listOffset(t.name(), p.partitionIndex(), p.timestamp());
                tResp.partitions()
                        .add(new ListOffsetsResponseData.ListOffsetsPartitionResponse()
                                .setPartitionIndex(p.partitionIndex())
                                .setErrorCode((short) 0)
                                .setTimestamp(-1L)
                                .setOffset(offset)
                                .setLeaderEpoch(0));
            }
            data.topics().add(tResp);
        }

        sendResponse(ctx, header, data);
    }

    private void handleOffsetCommit(
            ChannelHandlerContext ctx, RequestHeader header, ByteBufferAccessor accessor, short version) {
        OffsetCommitRequestData req = new OffsetCommitRequestData(accessor, version);
        OffsetCommitResponseData data = new OffsetCommitResponseData().setThrottleTimeMs(0);

        for (OffsetCommitRequestData.OffsetCommitRequestTopic t : req.topics()) {
            OffsetCommitResponseData.OffsetCommitResponseTopic tResp =
                    new OffsetCommitResponseData.OffsetCommitResponseTopic().setName(t.name());
            for (OffsetCommitRequestData.OffsetCommitRequestPartition p : t.partitions()) {
                coordinator.commitOffset(req.groupId(), t.name(), p.partitionIndex(), p.committedOffset());
                tResp.partitions()
                        .add(new OffsetCommitResponseData.OffsetCommitResponsePartition()
                                .setPartitionIndex(p.partitionIndex())
                                .setErrorCode((short) 0));
            }
            data.topics().add(tResp);
        }

        sendResponse(ctx, header, data);
    }

    private void handleOffsetFetch(
            ChannelHandlerContext ctx, RequestHeader header, ByteBufferAccessor accessor, short version) {
        OffsetFetchRequestData req = new OffsetFetchRequestData(accessor, version);
        OffsetFetchResponseData data =
                new OffsetFetchResponseData().setErrorCode((short) 0).setThrottleTimeMs(0);

        if (req.topics() != null) {
            for (OffsetFetchRequestData.OffsetFetchRequestTopic t : req.topics()) {
                OffsetFetchResponseData.OffsetFetchResponseTopic tResp =
                        new OffsetFetchResponseData.OffsetFetchResponseTopic().setName(t.name());
                for (int partition : t.partitionIndexes()) {
                    long committed = coordinator.fetchCommittedOffset(req.groupId(), t.name(), partition);
                    tResp.partitions()
                            .add(new OffsetFetchResponseData.OffsetFetchResponsePartition()
                                    .setPartitionIndex(partition)
                                    .setCommittedOffset(committed)
                                    .setCommittedLeaderEpoch(0)
                                    .setMetadata(null)
                                    .setErrorCode((short) 0));
                }
                data.topics().add(tResp);
            }
        } else {
            Map<String, OffsetFetchResponseData.OffsetFetchResponseTopic> byTopic = new LinkedHashMap<>();
            for (Map.Entry<KafkaTopicLogCoordinator.TopicPartitionKey, Long> entry :
                    coordinator.fetchAllCommittedOffsets(req.groupId()).entrySet()) {
                KafkaTopicLogCoordinator.TopicPartitionKey tp = entry.getKey();
                OffsetFetchResponseData.OffsetFetchResponseTopic tResp = byTopic.computeIfAbsent(
                        tp.topic(), name -> new OffsetFetchResponseData.OffsetFetchResponseTopic().setName(name));
                tResp.partitions()
                        .add(new OffsetFetchResponseData.OffsetFetchResponsePartition()
                                .setPartitionIndex(tp.partition())
                                .setCommittedOffset(entry.getValue())
                                .setCommittedLeaderEpoch(0)
                                .setMetadata(null)
                                .setErrorCode((short) 0));
            }
            data.topics().addAll(byTopic.values());
        }

        sendResponse(ctx, header, data);
    }

    private void handleFetch(
            ChannelHandlerContext ctx, RequestHeader header, ByteBufferAccessor accessor, short version) {
        FetchRequestData req = new FetchRequestData(accessor, version);
        FetchResponseData data =
                new FetchResponseData().setErrorCode((short) 0).setSessionId(0).setThrottleTimeMs(0);

        for (FetchRequestData.FetchTopic topic : req.topics()) {
            FetchResponseData.FetchableTopicResponse tResp =
                    new FetchResponseData.FetchableTopicResponse().setTopic(topic.topic());

            for (FetchRequestData.FetchPartition partition : topic.partitions()) {
                KafkaTopicLogCoordinator.FetchSlice slice =
                        coordinator.fetch(topic.topic(), partition.partition(), partition.fetchOffset());

                FetchResponseData.PartitionData pData = new FetchResponseData.PartitionData()
                        .setPartitionIndex(partition.partition())
                        .setErrorCode((short) 0)
                        .setHighWatermark(slice.highWatermark())
                        .setLastStableOffset(slice.highWatermark())
                        .setLogStartOffset(slice.logStartOffset());

                if (slice.records().isEmpty()) {
                    pData.setRecords(MemoryRecords.EMPTY);
                } else {
                    pData.setRecords(buildMemoryRecords(slice.records()));
                }

                tResp.partitions().add(pData);
            }
            data.responses().add(tResp);
        }

        sendResponse(ctx, header, data);
    }

    private static MemoryRecords buildMemoryRecords(List<KafkaProduceRecord> records) {
        long baseOffset = records.get(0).offset();
        int estimatedSize = 1024;
        for (KafkaProduceRecord r : records) {
            estimatedSize += r.key().length() + r.value().length() + 128;
        }
        try (MemoryRecordsBuilder builder = MemoryRecords.builder(
                ByteBuffer.allocate(estimatedSize),
                RecordBatch.CURRENT_MAGIC_VALUE,
                Compression.NONE,
                TimestampType.CREATE_TIME,
                baseOffset)) {
            for (KafkaProduceRecord r : records) {
                byte[] keyBytes = r.key().getBytes(StandardCharsets.UTF_8);
                byte[] valBytes = r.value().getBytes(StandardCharsets.UTF_8);
                Header[] headers = new Header[r.headers().size()];
                int hIdx = 0;
                for (Map.Entry<String, String> h : r.headers().entrySet()) {
                    headers[hIdx++] = new RecordHeader(
                            h.getKey(),
                            h.getValue() != null ? h.getValue().getBytes(StandardCharsets.UTF_8) : new byte[0]);
                }
                builder.appendWithOffset(r.offset(), r.timestamp(), keyBytes, valBytes, headers);
            }
            return builder.build();
        }
    }

    private void handleProduce(
            ChannelHandlerContext ctx, RequestHeader header, ByteBufferAccessor accessor, short version) {
        ProduceRequestData req = new ProduceRequestData(accessor, version);
        ProduceResponseData data = new ProduceResponseData().setThrottleTimeMs(0);

        for (ProduceRequestData.TopicProduceData t : req.topicData()) {
            ProduceResponseData.TopicProduceResponse tResp =
                    new ProduceResponseData.TopicProduceResponse().setName(t.name());

            for (ProduceRequestData.PartitionProduceData p : t.partitionData()) {
                long baseOffset = -1L;
                if (p.records() instanceof Records records) {
                    for (Record r : records.records()) {
                        String key = r.hasKey()
                                ? StandardCharsets.UTF_8.decode(r.key()).toString()
                                : "";
                        String value = r.hasValue()
                                ? StandardCharsets.UTF_8.decode(r.value()).toString()
                                : "";
                        Map<String, String> headers = new HashMap<>();
                        if (r.headers() != null) {
                            for (Header h : r.headers()) {
                                headers.put(
                                        h.key(),
                                        h.value() != null ? new String(h.value(), StandardCharsets.UTF_8) : "");
                            }
                        }

                        KafkaProduceRecord appended =
                                coordinator.append(t.name(), p.index(), key, value, headers, r.timestamp());
                        if (baseOffset < 0L) {
                            baseOffset = appended.offset();
                        }
                        recordConsumer.accept(appended);
                    }
                }
                if (baseOffset < 0L) {
                    baseOffset = coordinator.highWatermark(t.name(), p.index());
                }

                tResp.partitionResponses()
                        .add(new ProduceResponseData.PartitionProduceResponse()
                                .setIndex(p.index())
                                .setErrorCode((short) 0)
                                .setBaseOffset(baseOffset)
                                .setLogAppendTimeMs(System.currentTimeMillis())
                                .setLogStartOffset(coordinator.logStartOffset(t.name(), p.index())));
            }
            data.responses().add(tResp);
        }

        sendResponse(ctx, header, data);
    }

    private static String sanitizeLog(String value) {
        return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
    }
}
