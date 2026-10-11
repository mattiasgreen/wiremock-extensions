package com.github.mattiasgreen.wiremock.netem.engine;

import com.github.mattiasgreen.wiremock.netem.journal.NetEmCircularJournal;
import com.github.mattiasgreen.wiremock.netem.model.NetEmProxyConfig;
import java.io.IOException;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class TcpNetEmRegistry {

    private static final TcpNetEmRegistry INSTANCE = new TcpNetEmRegistry();

    private final NetEmCircularJournal journal = new NetEmCircularJournal();
    private final ConcurrentMap<String, TcpNetEmProxy> proxies = new ConcurrentHashMap<>();

    private TcpNetEmRegistry() {}

    public static TcpNetEmRegistry getInstance() {
        return INSTANCE;
    }

    public NetEmCircularJournal getJournal() {
        return journal;
    }

    public TcpNetEmProxy register(NetEmProxyConfig config) throws IOException {
        Objects.requireNonNull(config, "config must not be null");
        TcpNetEmProxy existing = proxies.get(config.getName());
        if (existing != null) {
            existing.stop();
        }
        TcpNetEmProxy proxy = new TcpNetEmProxy(config, journal);
        proxy.start();
        proxies.put(config.getName(), proxy);
        return proxy;
    }

    public Optional<TcpNetEmProxy> get(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(proxies.get(name));
    }

    public Collection<TcpNetEmProxy> list() {
        return proxies.values();
    }

    public boolean remove(String name) {
        if (name == null) {
            return false;
        }
        TcpNetEmProxy removed = proxies.remove(name);
        if (removed != null) {
            removed.stop();
            return true;
        }
        return false;
    }

    public void resetAll() {
        for (TcpNetEmProxy proxy : proxies.values()) {
            proxy.clearToxics();
            proxy.setEnabled(true);
        }
    }

    public void closeAll() {
        for (TcpNetEmProxy proxy : proxies.values()) {
            proxy.stop();
        }
        proxies.clear();
    }
}
