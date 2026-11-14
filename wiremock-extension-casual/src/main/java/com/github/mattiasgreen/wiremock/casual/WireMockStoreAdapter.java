package com.github.mattiasgreen.wiremock.casual;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.core.Options;
import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionDeclarations;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.store.Stores;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-process {@link CasualStorePort} adapter connecting the Casual gateway directly to WireMock
 * public store and extension contracts ({@link WireMockServices}, {@link WireMockServer},
 * {@link Options}, and {@link Stores}) with zero reflection or HTTP loopback calls.
 */
public final class WireMockStoreAdapter implements CasualStorePort {

    private static final Logger LOG = LoggerFactory.getLogger(WireMockStoreAdapter.class);

    private final WireMockServer wireMockServer;
    private final WireMockServices wireMockServices;
    private final String explicitBaseUrl;

    public WireMockStoreAdapter(WireMockServer wireMockServer) {
        this(Objects.requireNonNull(wireMockServer, "wireMockServer must not be null"), null, null);
    }

    public WireMockStoreAdapter(WireMockServices wireMockServices, String baseUrl) {
        this(null, Objects.requireNonNull(wireMockServices, "wireMockServices must not be null"), baseUrl);
    }

    public WireMockStoreAdapter(WireMockServer wireMockServer, WireMockServices wireMockServices, String baseUrl) {
        if (wireMockServer == null && wireMockServices == null) {
            throw new IllegalArgumentException(
                    "In-process WireMockStoreAdapter requires WireMockServer or WireMockServices (use HttpCasualStoreAdapter for remote URLs)");
        }
        this.wireMockServer = wireMockServer;
        this.wireMockServices = wireMockServices;
        this.explicitBaseUrl = normalizeOptionalBaseUrl(baseUrl);
    }

    private static String normalizeOptionalBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public Optional<StubMapping> findMatchingStub(Request request) {
        if (request == null) {
            return Optional.empty();
        }
        List<StubMapping> mappings = new ArrayList<>(getActiveStubMappings());
        mappings.sort((a, b) -> {
            int pa = a.getPriority() != null ? a.getPriority() : 5;
            int pb = b.getPriority() != null ? b.getPriority() : 5;
            return Integer.compare(pa, pb);
        });

        for (StubMapping mapping : mappings) {
            if (mapping.getRequest() != null
                    && mapping.getRequest().match(request).isExactMatch()) {
                return Optional.of(mapping);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<StubMapping> getActiveStubMappings() {
        if (wireMockServer != null) {
            return excludeParkedStubs(wireMockServer.getStubMappings().stream());
        }
        Stores stores = resolveStores();
        if (stores != null && stores.getStubStore() != null) {
            try {
                return excludeParkedStubs(stores.getStubStore().getAll());
            } catch (RuntimeException e) {
                LOG.debug("Could not query StubStore directly: {}", sanitize(e.getMessage()));
            }
        }
        return Collections.emptyList();
    }

    private static List<StubMapping> excludeParkedStubs(Stream<StubMapping> stubs) {
        return stubs.filter(s -> s != null && (s.getPriority() == null || s.getPriority() < 100))
                .toList();
    }

    @Override
    public String readBodyFromFile(String bodyFileName) {
        if (bodyFileName == null || bodyFileName.isBlank()) {
            return null;
        }
        Stores stores = resolveStores();
        if (stores != null && stores.getFilesBlobStore() != null) {
            try {
                Optional<InputStream> streamOpt = stores.getFilesBlobStore().getStream(bodyFileName);
                if (streamOpt.isPresent()) {
                    try (InputStream in = streamOpt.get()) {
                        byte[] bytes = in.readAllBytes();
                        if (bytes.length > 0) {
                            return new String(bytes, StandardCharsets.UTF_8);
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOG.debug(
                        "Could not read body file '{}' from BlobStore: {}",
                        sanitize(bodyFileName),
                        sanitize(e.getMessage()));
            }
        }

        FileSource filesRoot = resolveFilesRoot();
        if (filesRoot != null) {
            try {
                byte[] bytes = filesRoot.getBinaryFileNamed(bodyFileName).readContents();
                if (bytes != null && bytes.length > 0) {
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            } catch (RuntimeException e) {
                LOG.debug(
                        "Could not read body file '{}' from FileSource: {}",
                        sanitize(bodyFileName),
                        sanitize(e.getMessage()));
            }
        }
        return null;
    }

    @Override
    public void recordServeEvent(ServeEvent serveEvent) {
        if (serveEvent == null) {
            return;
        }
        recordServeEventInJournal(serveEvent);
        notifyServeEventListeners(serveEvent);
    }

    private void recordServeEventInJournal(ServeEvent serveEvent) {
        if (wireMockServer != null
                && wireMockServer.getOptions() != null
                && wireMockServer.getOptions().requestJournalDisabled()) {
            return;
        }
        Stores stores = resolveStores();
        if (stores != null && stores.getRequestJournalStore() != null) {
            try {
                stores.getRequestJournalStore().add(serveEvent);
            } catch (RuntimeException e) {
                LOG.debug("Could not add ServeEvent to RequestJournalStore: {}", sanitize(e.getMessage()));
            }
        }
    }

    private void notifyServeEventListeners(ServeEvent serveEvent) {
        List<ServeEventListener> listeners = resolveServeEventListeners();
        for (ServeEventListener listener : listeners) {
            try {
                listener.afterComplete(serveEvent, Parameters.empty());
            } catch (RuntimeException e) {
                LOG.warn(
                        "Failed to notify ServeEventListener [{}]: {}",
                        sanitize(listener.getName()),
                        sanitize(e.getMessage()));
            }
        }
    }

    private List<ServeEventListener> resolveServeEventListeners() {
        List<ServeEventListener> ordered = new ArrayList<>();
        Set<ServeEventListener> seenInstances = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> seenNames = new HashSet<>();

        if (wireMockServices != null && wireMockServices.getExtensions() != null) {
            for (ServeEventListener listener : wireMockServices
                    .getExtensions()
                    .ofType(ServeEventListener.class)
                    .values()) {
                registerUniqueListener(listener, ordered, seenInstances, seenNames);
            }
        }

        if (wireMockServer != null && wireMockServer.getOptions() != null) {
            Options options = wireMockServer.getOptions();
            CasualRegistry.getInstance()
                    .getWireMockServices(options.getStores())
                    .filter(services -> services.getExtensions() != null)
                    .ifPresent(services -> {
                        for (ServeEventListener listener : services.getExtensions()
                                .ofType(ServeEventListener.class)
                                .values()) {
                            registerUniqueListener(listener, ordered, seenInstances, seenNames);
                        }
                    });

            ExtensionDeclarations declarations = options.getDeclaredExtensions();
            if (declarations != null && declarations.getInstances() != null) {
                for (Extension ext : declarations.getInstances().values()) {
                    if (ext instanceof ServeEventListener listener) {
                        registerUniqueListener(listener, ordered, seenInstances, seenNames);
                    }
                }
            }
        }

        if (wireMockServices == null && wireMockServer == null) {
            CasualRegistry.getInstance()
                    .getWireMockServices()
                    .filter(services -> services.getExtensions() != null)
                    .ifPresent(services -> {
                        for (ServeEventListener listener : services.getExtensions()
                                .ofType(ServeEventListener.class)
                                .values()) {
                            registerUniqueListener(listener, ordered, seenInstances, seenNames);
                        }
                    });
        }

        return ordered;
    }

    private static void registerUniqueListener(
            ServeEventListener listener,
            List<ServeEventListener> ordered,
            Set<ServeEventListener> seenInstances,
            Set<String> seenNames) {
        if (listener == null || !seenInstances.add(listener)) {
            return;
        }
        String name = listener.getName();
        if (name != null && !seenNames.add(name)) {
            return;
        }
        ordered.add(listener);
    }

    private Stores resolveStores() {
        if (wireMockServices != null && wireMockServices.getStores() != null) {
            return wireMockServices.getStores();
        }
        if (wireMockServer != null
                && wireMockServer.getOptions() != null
                && wireMockServer.getOptions().getStores() != null) {
            return wireMockServer.getOptions().getStores();
        }
        return CasualRegistry.getInstance()
                .getWireMockServices()
                .map(WireMockServices::getStores)
                .orElse(null);
    }

    private FileSource resolveFilesRoot() {
        if (wireMockServices != null && wireMockServices.getFiles() != null) {
            return wireMockServices.getFiles();
        }
        if (wireMockServer != null && wireMockServer.getOptions() != null) {
            return wireMockServer.getOptions().filesRoot();
        }
        return CasualRegistry.getInstance()
                .getWireMockServices()
                .map(WireMockServices::getFiles)
                .orElse(null);
    }

    @Override
    public String getBaseUrl() {
        if (explicitBaseUrl != null) {
            return explicitBaseUrl;
        }
        if (wireMockServer != null && wireMockServer.isRunning()) {
            try {
                return wireMockServer.baseUrl();
            } catch (IllegalStateException ignored) {
                // Fall back when HTTP listener is disabled
            }
        }
        return "http://localhost:8080";
    }

    private static String sanitize(String value) {
        return value != null ? value.replace('\r', ' ').replace('\n', ' ') : "";
    }
}
