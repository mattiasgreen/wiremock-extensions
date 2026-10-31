package com.github.mattiasgreen.wiremock.casual;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.admin.model.ListStubMappingsResult;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link CasualStorePort} adapter connecting the Casual gateway to WireMock
 * via {@link WireMockServices} SPI, {@link WireMockServer}, or HTTP admin API.
 */
public class WireMockStoreAdapter implements CasualStorePort {

    private static final Logger LOG = LoggerFactory.getLogger(WireMockStoreAdapter.class);

    private final WireMockServer wireMockServer;
    private final WireMockServices wireMockServices;
    private final String baseUrl;
    private final HttpClient httpClient;

    public WireMockStoreAdapter(WireMockServer wireMockServer) {
        this(wireMockServer, null, wireMockServer != null ? wireMockServer.baseUrl() : null);
    }

    public WireMockStoreAdapter(WireMockServices wireMockServices, String baseUrl) {
        this(null, wireMockServices, baseUrl);
    }

    public WireMockStoreAdapter(WireMockServer wireMockServer, WireMockServices wireMockServices, String baseUrl) {
        this.wireMockServer = wireMockServer;
        this.wireMockServices = wireMockServices;
        this.baseUrl = baseUrl != null
                ? baseUrl
                : (wireMockServer != null ? wireMockServer.baseUrl() : "http://localhost:8080");
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @Override
    public Optional<StubMapping> findMatchingStub(Request request) {
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
        if (wireMockServices != null) {
            try {
                return wireMockServices.getStores().getStubStore().getAll().toList();
            } catch (Exception ignored) {
            }
        }
        if (wireMockServer != null) {
            return wireMockServer.getStubMappings();
        }
        Optional<WireMockServices> servicesOpt = CasualRegistry.getInstance().getWireMockServices();
        if (servicesOpt.isPresent()) {
            try {
                return servicesOpt.get().getStores().getStubStore().getAll().toList();
            } catch (Exception ignored) {
            }
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/__admin/mappings"))
                        .GET()
                        .timeout(Duration.ofSeconds(2))
                        .build();
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    ListStubMappingsResult result = Json.read(response.body(), ListStubMappingsResult.class);
                    if (result != null && result.getMappings() != null) {
                        return result.getMappings();
                    }
                }
            } catch (IOException | InterruptedException | RuntimeException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.debug("Failed to query WireMock mappings over HTTP: {}", e.getMessage());
            }
        }
        return Collections.emptyList();
    }

    @Override
    public String readBodyFromFile(String bodyFileName) {
        if (bodyFileName == null || bodyFileName.isBlank()) {
            return null;
        }
        if (wireMockServices != null) {
            try {
                byte[] bytes = wireMockServices
                        .getStores()
                        .getFilesBlobStore()
                        .getStreamSource(bodyFileName)
                        .getStream()
                        .readAllBytes();
                if (bytes != null && bytes.length > 0) {
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        if (wireMockServer != null) {
            try {
                Field appField = WireMockServer.class.getDeclaredField("wireMockApp");
                appField.setAccessible(true);
                Object wireMockApp = appField.get(wireMockServer);
                Field storesField = wireMockApp.getClass().getDeclaredField("stores");
                storesField.setAccessible(true);
                com.github.tomakehurst.wiremock.store.Stores stores =
                        (com.github.tomakehurst.wiremock.store.Stores) storesField.get(wireMockApp);
                if (stores != null && stores.getFilesBlobStore() != null) {
                    byte[] bytes = stores.getFilesBlobStore()
                            .getStreamSource(bodyFileName)
                            .getStream()
                            .readAllBytes();
                    if (bytes != null && bytes.length > 0) {
                        return new String(bytes, StandardCharsets.UTF_8);
                    }
                }
            } catch (ReflectiveOperationException | IOException | RuntimeException ignored) {
            }
            try {
                byte[] bytes = wireMockServer
                        .getOptions()
                        .filesRoot()
                        .getBinaryFileNamed(bodyFileName)
                        .readContents();
                if (bytes != null && bytes.length > 0) {
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            } catch (RuntimeException ignored) {
            }
        }
        Optional<WireMockServices> servicesOpt = CasualRegistry.getInstance().getWireMockServices();
        if (servicesOpt.isPresent()) {
            try {
                byte[] bytes = servicesOpt
                        .get()
                        .getStores()
                        .getFilesBlobStore()
                        .getStreamSource(bodyFileName)
                        .getStream()
                        .readAllBytes();
                if (bytes != null && bytes.length > 0) {
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return null;
    }

    @Override
    public void recordServeEvent(ServeEvent serveEvent) {
        recordServeEventInJournal(serveEvent);
        notifyServeEventListeners(serveEvent);
    }

    private void recordServeEventInJournal(ServeEvent serveEvent) {
        if (wireMockServices != null) {
            try {
                wireMockServices.getStores().getRequestJournalStore().add(serveEvent);
                return;
            } catch (Exception e) {
                LOG.debug("Could not add ServeEvent to WireMockServices RequestJournalStore: {}", e.getMessage());
            }
        }
        if (wireMockServer != null) {
            try {
                Field appField = WireMockServer.class.getDeclaredField("wireMockApp");
                appField.setAccessible(true);
                Object wireMockApp = appField.get(wireMockServer);
                try {
                    Field storesField = wireMockApp.getClass().getDeclaredField("stores");
                    storesField.setAccessible(true);
                    com.github.tomakehurst.wiremock.store.Stores stores =
                            (com.github.tomakehurst.wiremock.store.Stores) storesField.get(wireMockApp);
                    if (stores != null && stores.getRequestJournalStore() != null) {
                        stores.getRequestJournalStore().add(serveEvent);
                        return;
                    }
                } catch (Throwable ignored) {
                }
            } catch (Throwable t) {
                LOG.debug("Could not add ServeEvent to wireMockServer journal: {}", t.getMessage());
            }
        }
        Optional<WireMockServices> servicesOpt = CasualRegistry.getInstance().getWireMockServices();
        if (servicesOpt.isPresent()) {
            try {
                servicesOpt.get().getStores().getRequestJournalStore().add(serveEvent);
            } catch (Exception e) {
                LOG.debug("Could not add ServeEvent to RequestJournalStore: {}", e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void notifyServeEventListeners(ServeEvent serveEvent) {
        Map<String, ServeEventListener> listeners = new LinkedHashMap<>();
        if (wireMockServer != null) {
            try {
                Field appField = WireMockServer.class.getDeclaredField("wireMockApp");
                appField.setAccessible(true);
                Object wireMockApp = appField.get(wireMockServer);
                if (wireMockApp != null) {
                    try {
                        Method getExtensions = wireMockApp.getClass().getMethod("getExtensions");
                        com.github.tomakehurst.wiremock.extension.Extensions exts =
                                (com.github.tomakehurst.wiremock.extension.Extensions)
                                        getExtensions.invoke(wireMockApp);
                        if (exts != null) {
                            listeners.putAll(exts.ofType(ServeEventListener.class));
                        }
                    } catch (Throwable ignored) {
                    }
                    if (listeners.isEmpty()) {
                        Field listenersField = wireMockApp.getClass().getDeclaredField("serveEventListeners");
                        listenersField.setAccessible(true);
                        Map<String, ServeEventListener> map =
                                (Map<String, ServeEventListener>) listenersField.get(wireMockApp);
                        if (map != null) {
                            listeners.putAll(map);
                        }
                    }
                }
            } catch (Throwable t) {
                LOG.debug("Could not resolve ServeEventListeners from wireMockServer: {}", t.getMessage());
            }
        }
        if (listeners.isEmpty() && wireMockServices != null && wireMockServices.getExtensions() != null) {
            listeners.putAll(wireMockServices.getExtensions().ofType(ServeEventListener.class));
        }
        if (listeners.isEmpty()) {
            Optional<WireMockServices> servicesOpt =
                    CasualRegistry.getInstance().getWireMockServices();
            if (servicesOpt.isPresent() && servicesOpt.get().getExtensions() != null) {
                listeners.putAll(servicesOpt.get().getExtensions().ofType(ServeEventListener.class));
            }
        }
        for (ServeEventListener listener : listeners.values()) {
            try {
                listener.afterComplete(serveEvent, Parameters.empty());
            } catch (Throwable t) {
                LOG.warn("Failed to notify ServeEventListener [{}]: {}", listener.getName(), t.getMessage());
            }
        }
    }

    @Override
    public String getBaseUrl() {
        return baseUrl;
    }
}
