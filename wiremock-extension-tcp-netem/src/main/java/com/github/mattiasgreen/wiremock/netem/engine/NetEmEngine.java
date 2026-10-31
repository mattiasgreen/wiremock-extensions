package com.github.mattiasgreen.wiremock.netem.engine;

import com.github.mattiasgreen.wiremock.netem.journal.NetEmCircularJournal;
import com.github.mattiasgreen.wiremock.netem.model.NetEmProxyConfig;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deep network emulation engine owning proxy lifecycle, circular journal,
 * and deterministic, lease-scoped toxic scheduling.
 *
 * <p>Replaces JVM-wide static singletons with instance-scoped dependency injection,
 * preventing parallel test pollution and eliminating unmanaged sleep threads.
 */
public class NetEmEngine implements AutoCloseable {

    private static final NetEmEngine DEFAULT_INSTANCE = new NetEmEngine();

    public static NetEmEngine getDefault() {
        return DEFAULT_INSTANCE;
    }

    public interface ToxicLease extends AutoCloseable {
        List<String> getAppliedToxicNames();

        void scheduleClose(Duration delay);

        @Override
        void close();
    }

    private final NetEmCircularJournal journal;
    private final ConcurrentMap<String, TcpNetEmProxy> proxies = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "netem-engine-scheduler");
        t.setDaemon(true);
        return t;
    });

    public NetEmEngine() {
        this(new NetEmCircularJournal());
    }

    public NetEmEngine(NetEmCircularJournal journal) {
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
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
        return List.copyOf(proxies.values());
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

    public ToxicLease applyTransientToxics(String correlationId, List<NetEmToxicConfig> toxics, Duration ttl) {
        Objects.requireNonNull(toxics, "toxics must not be null");
        if (toxics.isEmpty()) {
            return new NoOpToxicLease();
        }

        List<String> addedNames = new ArrayList<>();
        for (TcpNetEmProxy proxy : proxies.values()) {
            for (NetEmToxicConfig toxic : toxics) {
                proxy.addToxic(toxic);
                addedNames.add(toxic.getName());
            }
        }

        return new ActiveToxicLease(addedNames, ttl);
    }

    @Override
    public void close() {
        closeAll();
        scheduler.shutdownNow();
    }

    public void closeAll() {
        for (TcpNetEmProxy proxy : proxies.values()) {
            proxy.stop();
        }
        proxies.clear();
    }

    private final class ActiveToxicLease implements ToxicLease {
        private final List<String> appliedNames;
        private volatile ScheduledFuture<?> activeFuture;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        ActiveToxicLease(List<String> appliedNames, Duration ttl) {
            this.appliedNames = List.copyOf(appliedNames);
            if (ttl != null && !ttl.isNegative() && !ttl.isZero()) {
                this.activeFuture = scheduler.schedule(this::close, ttl.toMillis(), TimeUnit.MILLISECONDS);
            }
        }

        @Override
        public List<String> getAppliedToxicNames() {
            return appliedNames;
        }

        @Override
        public void scheduleClose(Duration delay) {
            if (closed.get()) {
                return;
            }
            if (activeFuture != null && !activeFuture.isDone()) {
                activeFuture.cancel(false);
            }
            if (delay != null && !delay.isNegative() && !delay.isZero()) {
                this.activeFuture = scheduler.schedule(this::close, delay.toMillis(), TimeUnit.MILLISECONDS);
            } else {
                close();
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                if (activeFuture != null && !activeFuture.isDone()) {
                    activeFuture.cancel(false);
                }
                for (TcpNetEmProxy proxy : proxies.values()) {
                    for (String name : appliedNames) {
                        proxy.removeToxic(name);
                    }
                }
            }
        }
    }

    private static final class NoOpToxicLease implements ToxicLease {
        @Override
        public List<String> getAppliedToxicNames() {
            return List.of();
        }

        @Override
        public void scheduleClose(Duration delay) {}

        @Override
        public void close() {}
    }
}
