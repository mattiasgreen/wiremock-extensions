package com.github.mattiasgreen.wiremock.async.kafka;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * In-process Netty TCP server hosting an ultra-lightweight Kafka wire sink (API versions, Metadata,
 * InitProducerId, Produce, Fetch, ListOffsets, consumer group coordination) with zero external
 * broker daemons.
 *
 * <p>Owns a single shared {@link KafkaTopicLogCoordinator} injected into every per-connection
 * {@link KafkaWireCodec}, and runs Netty {@link NioEventLoopGroup}s on daemon platform threads.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 */
public class KafkaWireSink implements AutoCloseable {

    private final int requestedPort;
    private final Consumer<KafkaProduceRecord> recordConsumer;
    private final KafkaTopicLogCoordinator coordinator = new KafkaTopicLogCoordinator();
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile int boundPort;

    public KafkaWireSink(int port, Consumer<KafkaProduceRecord> recordConsumer) {
        this.requestedPort = port;
        this.recordConsumer = Objects.requireNonNullElse(recordConsumer, r -> {});
    }

    public void stageRecord(KafkaProduceRecord record) {
        coordinator.stage(record);
    }

    public List<KafkaProduceRecord> getStagedRecords() {
        return coordinator.getAllRecords();
    }

    public boolean deleteStagedRecord(String topic, String keyOrId) {
        return coordinator.deleteRecord(topic, keyOrId);
    }

    public void purgeTopic(String topic) {
        coordinator.purgeTopic(topic);
    }

    public synchronized void start() throws InterruptedException {
        if (serverChannel != null && serverChannel.isActive()) {
            return;
        }

        bossGroup = new NioEventLoopGroup(
                1, Thread.ofPlatform().daemon(true).name("kafka-sink-boss-", 0).factory());
        workerGroup = new NioEventLoopGroup(
                2,
                Thread.ofPlatform().daemon(true).name("kafka-sink-worker-", 0).factory());

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        InetSocketAddress localAddress = ch.localAddress();
                        int effectivePort =
                                localAddress != null && localAddress.getPort() > 0 ? localAddress.getPort() : boundPort;
                        ch.pipeline().addLast(new KafkaWireCodec(effectivePort, coordinator, recordConsumer));
                    }
                });

        serverChannel = bootstrap.bind(requestedPort).sync().channel();
        boundPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public synchronized int getPort() {
        return boundPort;
    }

    public synchronized boolean isRunning() {
        return serverChannel != null && serverChannel.isActive();
    }

    synchronized boolean isEventLoopVirtual() throws InterruptedException, ExecutionException, TimeoutException {
        if (bossGroup == null || workerGroup == null) {
            return false;
        }
        boolean bossVirtual =
                bossGroup.submit(() -> Thread.currentThread().isVirtual()).get(5, TimeUnit.SECONDS);
        boolean workerVirtual =
                workerGroup.submit(() -> Thread.currentThread().isVirtual()).get(5, TimeUnit.SECONDS);
        return bossVirtual || workerVirtual;
    }

    @Override
    public synchronized void close() {
        coordinator.clear();
        if (serverChannel != null) {
            try {
                serverChannel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            serverChannel = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        boundPort = 0;
    }
}
