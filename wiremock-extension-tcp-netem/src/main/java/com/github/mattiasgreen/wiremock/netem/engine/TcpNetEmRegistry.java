package com.github.mattiasgreen.wiremock.netem.engine;

import com.github.mattiasgreen.wiremock.netem.journal.NetEmCircularJournal;
import com.github.mattiasgreen.wiremock.netem.model.NetEmProxyConfig;
import java.io.IOException;
import java.util.Collection;
import java.util.Optional;

/**
 * Backward-compatible registry delegate exposing the default {@link NetEmEngine}.
 */
public class TcpNetEmRegistry {

    private static final TcpNetEmRegistry INSTANCE = new TcpNetEmRegistry();
    private final NetEmEngine engine = NetEmEngine.getDefault();

    private TcpNetEmRegistry() {}

    public static TcpNetEmRegistry getInstance() {
        return INSTANCE;
    }

    public NetEmEngine getEngine() {
        return engine;
    }

    public NetEmCircularJournal getJournal() {
        return engine.getJournal();
    }

    public TcpNetEmProxy register(NetEmProxyConfig config) throws IOException {
        return engine.register(config);
    }

    public Optional<TcpNetEmProxy> get(String name) {
        return engine.get(name);
    }

    public Collection<TcpNetEmProxy> list() {
        return engine.list();
    }

    public boolean remove(String name) {
        return engine.remove(name);
    }

    public void resetAll() {
        engine.resetAll();
    }

    public void closeAll() {
        engine.closeAll();
    }
}
