package com.github.mattiasgreen.wiremock.netem.engine;

import com.github.mattiasgreen.wiremock.netem.impairments.NetEmContext;
import com.github.mattiasgreen.wiremock.netem.impairments.NetEmImpairment;
import com.github.mattiasgreen.wiremock.netem.impairments.NetEmImpairmentFactory;
import com.github.mattiasgreen.wiremock.netem.journal.NetEmCircularJournal;
import com.github.mattiasgreen.wiremock.netem.journal.NetEmJournalEntry;
import com.github.mattiasgreen.wiremock.netem.model.NetEmPreset;
import com.github.mattiasgreen.wiremock.netem.model.NetEmProxyConfig;
import com.github.mattiasgreen.wiremock.netem.model.NetEmStats;
import com.github.mattiasgreen.wiremock.netem.model.NetEmStreamDirection;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class TcpNetEmProxy implements AutoCloseable {

    private volatile NetEmProxyConfig config;
    private final NetEmCircularJournal journal;
    private final NetEmStats stats = new NetEmStats();
    private final ConcurrentMap<String, NetEmImpairment> impairments = new ConcurrentHashMap<>();
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong connectionCounter = new AtomicLong(0);

    private volatile ServerSocket serverSocket;
    private volatile int boundPort;

    public TcpNetEmProxy(NetEmProxyConfig config) {
        this(config, new NetEmCircularJournal());
    }

    public TcpNetEmProxy(NetEmProxyConfig config, NetEmCircularJournal journal) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
    }

    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }

        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(config.getListenPort()));
        this.serverSocket = ss;
        this.boundPort = ss.getLocalPort();
        if (config.getListenPort() != boundPort) {
            this.config = config.withListenPort(boundPort);
        }

        running.set(true);
        Thread.ofVirtual().name("netem-" + config.getName() + "-accept").start(this::acceptLoop);
    }

    private void acceptLoop() {
        ServerSocket ss = this.serverSocket;
        while (running.get() && ss != null && !ss.isClosed()) {
            try {
                Socket clientSocket = ss.accept();
                if (!config.isEnabled()) {
                    closeQuietly(clientSocket);
                    continue;
                }
                Thread.ofVirtual()
                        .name("netem-" + config.getName() + "-conn-" + connectionCounter.incrementAndGet())
                        .start(() -> handleConnection(clientSocket));
            } catch (IOException e) {
                if (!running.get()) {
                    break;
                }
            }
        }
    }

    private void handleConnection(Socket clientSocket) {
        activeSockets.add(clientSocket);
        Socket rawTargetSocket = null;
        try {
            rawTargetSocket = new Socket();
            rawTargetSocket.connect(new InetSocketAddress(config.getTargetHost(), config.getTargetPort()), 5000);
            activeSockets.add(rawTargetSocket);

            clientSocket.setTcpNoDelay(true);
            rawTargetSocket.setTcpNoDelay(true);

            long connId = connectionCounter.get();
            final Socket targetSocket = rawTargetSocket;
            NetEmContext context = new NetEmContext(connId, config.getName(), clientSocket, targetSocket, stats);
            stats.incrementActiveConnections();

            Thread upThread = Thread.ofVirtual()
                    .name("netem-" + config.getName() + "-up-" + connId)
                    .start(() -> {
                        new NetEmPipe(
                                        clientSocket,
                                        targetSocket,
                                        NetEmStreamDirection.UPSTREAM,
                                        context,
                                        impairments::values,
                                        journal)
                                .run();
                    });

            Thread downThread = Thread.ofVirtual()
                    .name("netem-" + config.getName() + "-down-" + connId)
                    .start(() -> {
                        new NetEmPipe(
                                        targetSocket,
                                        clientSocket,
                                        NetEmStreamDirection.DOWNSTREAM,
                                        context,
                                        impairments::values,
                                        journal)
                                .run();
                    });

            try {
                upThread.join();
                downThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                stats.decrementActiveConnections();
                activeSockets.remove(clientSocket);
                activeSockets.remove(targetSocket);

                if (context.isTransactionActive()) {
                    context.completeTransaction(journal, context.getTerminationReason());
                } else if (context.getBytesUpstream() == 0
                        && context.getBytesDownstream() == 0
                        && context.isImpaired()) {
                    long connUpBytes = context.getBytesUpstream();
                    long connDownBytes = context.getBytesDownstream();
                    boolean impaired = context.isImpaired();
                    stats.recordTransferCompletion(impaired, connUpBytes, connDownBytes);

                    String clientAddr = clientSocket.getRemoteSocketAddress() != null
                            ? clientSocket.getRemoteSocketAddress().toString()
                            : "unknown";

                    NetEmJournalEntry entry = new NetEmJournalEntry(
                            journal.getTotalRecordedCount(),
                            context.getStartTimeEpochMs(),
                            config.getName(),
                            clientAddr,
                            context.getScope(),
                            context.getDurationMs(),
                            connUpBytes,
                            connDownBytes,
                            impaired,
                            context.getAppliedToxicsString(),
                            context.getTerminationReason());
                    journal.record(entry);
                }
            }

        } catch (IOException e) {
            closeQuietly(clientSocket);
            closeQuietly(rawTargetSocket);
            activeSockets.remove(clientSocket);
            activeSockets.remove(rawTargetSocket);
            String clientAddr = clientSocket.getRemoteSocketAddress() != null
                    ? clientSocket.getRemoteSocketAddress().toString()
                    : "unknown";
            NetEmJournalEntry entry = new NetEmJournalEntry(
                    journal.getTotalRecordedCount(),
                    System.currentTimeMillis(),
                    config.getName(),
                    clientAddr,
                    null,
                    0,
                    0,
                    0,
                    false,
                    "",
                    "CONNECT_FAILED: " + e.getMessage());
            journal.record(entry);
        }
    }

    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // Quiet close
            }
        }
        for (Socket s : activeSockets) {
            closeQuietly(s);
        }
        activeSockets.clear();
    }

    @Override
    public void close() {
        stop();
    }

    public NetEmProxyConfig getConfig() {
        return config;
    }

    public synchronized int getPort() {
        return boundPort;
    }

    public boolean isRunning() {
        return running.get();
    }

    public NetEmStats getStats() {
        return stats;
    }

    public NetEmCircularJournal getJournal() {
        return journal;
    }

    public void setEnabled(boolean enabled) {
        this.config = config.withEnabled(enabled);
    }

    public void addToxic(NetEmToxicConfig toxicConfig) {
        Objects.requireNonNull(toxicConfig, "toxicConfig must not be null");
        NetEmImpairment impairment = NetEmImpairmentFactory.create(toxicConfig);
        impairments.put(toxicConfig.getName(), impairment);
    }

    public boolean removeToxic(String toxicName) {
        return impairments.remove(toxicName) != null;
    }

    public void clearToxics() {
        impairments.clear();
    }

    public Collection<NetEmToxicConfig> getToxics() {
        return impairments.values().stream().map(NetEmImpairment::getConfig).toList();
    }

    public void applyPreset(String presetName) {
        clearToxics();
        List<NetEmToxicConfig> toxics = NetEmPreset.getPresetToxics(presetName);
        for (NetEmToxicConfig toxic : toxics) {
            addToxic(toxic);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Quiet close
            }
        }
    }
}
