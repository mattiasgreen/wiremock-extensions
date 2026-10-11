package com.github.mattiasgreen.wiremock.netem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.mattiasgreen.wiremock.netem.impairments.BandwidthImpairment;
import com.github.mattiasgreen.wiremock.netem.impairments.ByteConsumer;
import com.github.mattiasgreen.wiremock.netem.impairments.LatencyImpairment;
import com.github.mattiasgreen.wiremock.netem.impairments.LimitDataImpairment;
import com.github.mattiasgreen.wiremock.netem.impairments.NetEmContext;
import com.github.mattiasgreen.wiremock.netem.impairments.ResetPeerImpairment;
import com.github.mattiasgreen.wiremock.netem.impairments.SlicerImpairment;
import com.github.mattiasgreen.wiremock.netem.impairments.SlowCloseImpairment;
import com.github.mattiasgreen.wiremock.netem.impairments.TimeoutImpairment;
import com.github.mattiasgreen.wiremock.netem.model.NetEmStats;
import com.github.mattiasgreen.wiremock.netem.model.NetEmStreamDirection;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicConfig;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicType;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NetEmImpairmentsTest {

    private ServerSocket serverSocket;
    private Socket clientSocket;
    private Socket targetSocket;
    private NetEmContext context;
    private NetEmStats stats;

    @BeforeEach
    void setUp() throws Exception {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();

        clientSocket = new Socket("127.0.0.1", port);
        targetSocket = serverSocket.accept();

        stats = new NetEmStats();
        context = new NetEmContext(1L, clientSocket, targetSocket, stats);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clientSocket != null && !clientSocket.isClosed()) {
            clientSocket.close();
        }
        if (targetSocket != null && !targetSocket.isClosed()) {
            targetSocket.close();
        }
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
    }

    @Test
    void latencyImpairmentShouldDelayExecution() throws Exception {
        NetEmToxicConfig config = NetEmToxicConfig.builder("test-lat", NetEmToxicType.LATENCY).stream(
                        NetEmStreamDirection.DOWNSTREAM)
                .attribute("latency", 150L)
                .attribute("jitter", 0L)
                .build();
        LatencyImpairment impairment = new LatencyImpairment(config);

        byte[] payload = "hello world".getBytes(StandardCharsets.UTF_8);
        long start = System.currentTimeMillis();
        impairment.intercept(
                payload, 0, payload.length, context, NetEmStreamDirection.DOWNSTREAM, (buf, off, len) -> {});
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(120L);
        assertThat(stats.getImpairmentsInjected()).isEqualTo(1L);
    }

    @Test
    void bandwidthImpairmentShouldThrottleThroughput() throws Exception {
        // 500 bytes at 1000 bytes/sec should sleep ~500ms
        NetEmToxicConfig config = NetEmToxicConfig.builder("test-bw", NetEmToxicType.BANDWIDTH).stream(
                        NetEmStreamDirection.DOWNSTREAM)
                .attribute("rate", 5L) // 5 KB/s = 5000 bytes/sec
                .build();
        BandwidthImpairment impairment = new BandwidthImpairment(config);

        byte[] payload = new byte[1000];
        long start = System.currentTimeMillis();
        impairment.intercept(
                payload, 0, payload.length, context, NetEmStreamDirection.DOWNSTREAM, (buf, off, len) -> {});
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(80L);
        assertThat(stats.getImpairmentsInjected()).isEqualTo(1L);
    }

    @Test
    void resetPeerImpairmentShouldThrowSocketExceptionAndCloseSocket() {
        NetEmToxicConfig config = NetEmToxicConfig.builder("test-rst", NetEmToxicType.RESET_PEER).stream(
                        NetEmStreamDirection.DOWNSTREAM)
                .build();
        ResetPeerImpairment impairment = new ResetPeerImpairment(config);

        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> impairment.intercept(
                        payload, 0, payload.length, context, NetEmStreamDirection.DOWNSTREAM, (buf, off, len) -> {}))
                .isInstanceOf(SocketException.class)
                .hasMessageContaining("TCP RST");

        assertThat(clientSocket.isClosed()).isTrue();
        assertThat(stats.getImpairmentsInjected()).isEqualTo(1L);
    }

    @Test
    void timeoutImpairmentShouldBlackholeAndTimeout() {
        NetEmToxicConfig config = NetEmToxicConfig.builder("test-timeout", NetEmToxicType.TIMEOUT).stream(
                        NetEmStreamDirection.DOWNSTREAM)
                .attribute("timeout", 100L)
                .build();
        TimeoutImpairment impairment = new TimeoutImpairment(config);

        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> impairment.intercept(
                        payload, 0, payload.length, context, NetEmStreamDirection.DOWNSTREAM, (buf, off, len) -> {}))
                .isInstanceOf(SocketTimeoutException.class);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(90L);
        assertThat(stats.getImpairmentsInjected()).isEqualTo(1L);
    }

    @Test
    void slowCloseImpairmentShouldDelayOnEof() throws Exception {
        NetEmToxicConfig config = NetEmToxicConfig.builder("test-slowclose", NetEmToxicType.SLOW_CLOSE).stream(
                        NetEmStreamDirection.DOWNSTREAM)
                .attribute("delay", 100L)
                .build();
        SlowCloseImpairment impairment = new SlowCloseImpairment(config);

        long start = System.currentTimeMillis();
        impairment.onEof(context, NetEmStreamDirection.DOWNSTREAM, (buf, off, len) -> {});
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(90L);
        assertThat(stats.getImpairmentsInjected()).isEqualTo(1L);
    }

    @Test
    void slicerImpairmentShouldSliceBufferIntoChunks() throws Exception {
        NetEmToxicConfig config = NetEmToxicConfig.builder("test-slicer", NetEmToxicType.SLICER).stream(
                        NetEmStreamDirection.DOWNSTREAM)
                .attribute("average_size", 10)
                .attribute("size_variation", 0)
                .attribute("delay", 5L)
                .build();
        SlicerImpairment impairment = new SlicerImpairment(config);

        byte[] payload = new byte[50];
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        AtomicInteger chunks = new AtomicInteger(0);

        impairment.intercept(payload, 0, payload.length, context, NetEmStreamDirection.DOWNSTREAM, (buf, off, len) -> {
            captured.write(buf, off, len);
            chunks.incrementAndGet();
        });

        assertThat(chunks.get()).isEqualTo(5); // 50 / 10 = 5 chunks
        assertThat(captured.toByteArray()).hasSize(50);
        assertThat(stats.getImpairmentsInjected()).isEqualTo(1L);
    }

    @Test
    void limitDataImpairmentShouldCutOffAfterByteQuota() throws Exception {
        NetEmToxicConfig config = NetEmToxicConfig.builder("test-limit", NetEmToxicType.LIMIT_DATA).stream(
                        NetEmStreamDirection.DOWNSTREAM)
                .attribute("bytes", 20L)
                .build();
        LimitDataImpairment impairment = new LimitDataImpairment(config);

        byte[] payload1 = new byte[15];
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ByteConsumer forwarder = captured::write;

        impairment.intercept(payload1, 0, payload1.length, context, NetEmStreamDirection.DOWNSTREAM, forwarder);
        assertThat(captured.toByteArray()).hasSize(15);

        // Next chunk of 15 bytes should only write 5 bytes then close socket
        byte[] payload2 = new byte[15];
        impairment.intercept(payload2, 0, payload2.length, context, NetEmStreamDirection.DOWNSTREAM, forwarder);
        assertThat(captured.toByteArray()).hasSize(20);
        assertThat(clientSocket.isClosed()).isTrue();
    }
}
