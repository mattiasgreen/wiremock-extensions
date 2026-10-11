package com.github.mattiasgreen.wiremock.netem.impairments;

import com.github.mattiasgreen.wiremock.netem.model.NetEmStats;
import com.github.mattiasgreen.wiremock.netem.model.NetEmStreamDirection;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class NetEmContext {

    private final long connectionId;
    private final Socket clientSocket;
    private final Socket targetSocket;
    private final NetEmStats stats;
    private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();
    private volatile String correlationId;

    public NetEmContext(long connectionId, Socket clientSocket, Socket targetSocket, NetEmStats stats) {
        this.connectionId = connectionId;
        this.clientSocket = Objects.requireNonNull(clientSocket, "clientSocket must not be null");
        this.targetSocket = Objects.requireNonNull(targetSocket, "targetSocket must not be null");
        this.stats = Objects.requireNonNull(stats, "stats must not be null");
    }

    public long getConnectionId() {
        return connectionId;
    }

    public Socket getClientSocket() {
        return clientSocket;
    }

    public Socket getTargetSocket() {
        return targetSocket;
    }

    public NetEmStats getStats() {
        return stats;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Socket getSourceSocket(NetEmStreamDirection direction) {
        return direction == NetEmStreamDirection.UPSTREAM ? clientSocket : targetSocket;
    }

    public Socket getDestinationSocket(NetEmStreamDirection direction) {
        return direction == NetEmStreamDirection.UPSTREAM ? targetSocket : clientSocket;
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public void setAttribute(String key, Object value) {
        if (value != null) {
            attributes.put(key, value);
        } else {
            attributes.remove(key);
        }
    }

    public boolean isClosed() {
        return clientSocket.isClosed() || targetSocket.isClosed();
    }
}
