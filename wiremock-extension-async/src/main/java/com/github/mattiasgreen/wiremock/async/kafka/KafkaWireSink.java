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
import java.util.Objects;
import java.util.function.Consumer;

/**
 * In-process Netty TCP server hosting an ultra-lightweight Kafka wire sink (API versions, Metadata,
 * InitProducerId, Produce) with zero external broker daemons.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 */
public class KafkaWireSink implements AutoCloseable {

    private final int requestedPort;
    private final Consumer<KafkaProduceRecord> recordConsumer;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private int boundPort;

    public KafkaWireSink(int port, Consumer<KafkaProduceRecord> recordConsumer) {
        this.requestedPort = port;
        this.recordConsumer = Objects.requireNonNullElse(recordConsumer, r -> {});
    }

    public synchronized void start() throws InterruptedException {
        if (serverChannel != null && serverChannel.isActive()) {
            return;
        }

        bossGroup = new NioEventLoopGroup(
                1, Thread.ofVirtual().name("kafka-sink-boss-", 0).factory());
        workerGroup = new NioEventLoopGroup(
                2, Thread.ofVirtual().name("kafka-sink-worker-", 0).factory());

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new KafkaWireCodec(boundPort, recordConsumer));
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

    @Override
    public synchronized void close() {
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
    }
}
