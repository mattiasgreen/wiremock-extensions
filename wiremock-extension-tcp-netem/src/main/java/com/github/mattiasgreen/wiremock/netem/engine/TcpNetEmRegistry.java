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

    private TcpNetEmRegistry() {}

    public static TcpNetEmRegistry getInstance() {
        return INSTANCE;
    }

    public NetEmEngine getEngine() {
        return NetEmEngine.getDefault();
    }

    public NetEmCircularJournal getJournal() {
        return getEngine().getJournal();
    }

    public TcpNetEmProxy register(NetEmProxyConfig config) throws IOException {
        return getEngine().register(config);
    }

    public Optional<TcpNetEmProxy> get(String name) {
        return getEngine().get(name);
    }

    public Collection<TcpNetEmProxy> list() {
        return getEngine().list();
    }

    public boolean remove(String name) {
        return getEngine().remove(name);
    }

    public void resetAll() {
        getEngine().resetAll();
    }

    public void closeAll() {
        getEngine().closeAll();
    }
}
