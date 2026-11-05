package com.github.mattiasgreen.wiremock.journal.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.journal.model.JournalStats;
import com.github.mattiasgreen.wiremock.journal.store.CompressedRequestJournalStore;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.QueryParameter;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.store.Stores;
import java.util.*;

/**
 * Admin API Extension exposing real-time journal compression statistics, ad-hoc compaction triggers,
 * and fast free-text search across compressed journal entries.
 */
public class JournalAdminEndpoint implements AdminApiExtension {

    public static final String EXTENSION_NAME = "journal-admin-endpoint";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Stores stores;
    private final CompressedRequestJournalStore directStore;

    public JournalAdminEndpoint(Stores stores) {
        this.stores = stores;
        if (stores != null && stores.getRequestJournalStore() instanceof CompressedRequestJournalStore c) {
            this.directStore = c;
        } else {
            this.directStore = null;
        }
    }

    public JournalAdminEndpoint(CompressedRequestJournalStore directStore) {
        this.stores = null;
        this.directStore = directStore;
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public void contributeAdminApiRoutes(Router router) {
        // GET /journal/stats
        router.add(RequestMethod.GET, "/journal/stats", (admin, serveEvent, pathParams) -> {
            try {
                Stores currentStores = admin != null && admin.getOptions() != null
                        ? admin.getOptions().getStores()
                        : null;
                CompressedRequestJournalStore store = resolveStore(currentStores);
                JournalStats stats = store != null ? store.getStats() : fallbackStats(currentStores);
                String json = MAPPER.writeValueAsString(stats);
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)
                        .build();
            } catch (Exception e) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"" + e.getMessage() + "\"}")
                        .build();
            }
        });

        // POST /journal/compact
        router.add(RequestMethod.POST, "/journal/compact", (admin, serveEvent, pathParams) -> {
            try {
                Stores currentStores = admin != null && admin.getOptions() != null
                        ? admin.getOptions().getStores()
                        : null;
                CompressedRequestJournalStore store = resolveStore(currentStores);
                if (store != null) {
                    store.rebalanceAsync(false);
                    JournalStats stats = store.getStats();
                    String json = MAPPER.writeValueAsString(stats);
                    return ResponseDefinitionBuilder.responseDefinition()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(json)
                            .build();
                }
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\": \"noop\"}")
                        .build();
            } catch (Exception e) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"" + e.getMessage() + "\"}")
                        .build();
            }
        });

        // POST /journal/rebalance
        router.add(RequestMethod.POST, "/journal/rebalance", (admin, serveEvent, pathParams) -> {
            try {
                QueryParameter fullParam = serveEvent.getRequest().queryParameter("full");
                boolean full =
                        fullParam != null && fullParam.isPresent() && "true".equalsIgnoreCase(fullParam.firstValue());

                Stores currentStores = admin != null && admin.getOptions() != null
                        ? admin.getOptions().getStores()
                        : null;
                CompressedRequestJournalStore store = resolveStore(currentStores);
                if (store != null) {
                    store.rebalanceAsync(full);
                    JournalStats stats = store.getStats();
                    String json = MAPPER.writeValueAsString(stats);
                    return ResponseDefinitionBuilder.responseDefinition()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(json)
                            .build();
                }
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\": \"noop\"}")
                        .build();
            } catch (Exception e) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"" + e.getMessage() + "\"}")
                        .build();
            }
        });

        // GET /journal/search?query=...
        router.add(RequestMethod.GET, "/journal/search", (admin, serveEvent, pathParams) -> {
            try {
                QueryParameter param = serveEvent.getRequest().queryParameter("query");
                String query = param != null && param.isPresent() ? param.firstValue() : "";

                Stores currentStores = admin != null && admin.getOptions() != null
                        ? admin.getOptions().getStores()
                        : null;
                CompressedRequestJournalStore store = resolveStore(currentStores);
                List<UUID> matchingIds;
                if (store != null) {
                    matchingIds = store.search(query);
                } else {
                    matchingIds = Collections.emptyList();
                }

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("query", query);
                resp.put("totalMatches", matchingIds.size());
                resp.put("matchedEventIds", matchingIds);

                String json = MAPPER.writeValueAsString(resp);
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)
                        .build();
            } catch (Exception e) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"" + e.getMessage() + "\"}")
                        .build();
            }
        });
    }

    private CompressedRequestJournalStore resolveStore(Stores currentStores) {
        if (directStore != null) {
            return directStore;
        }
        if (stores != null && stores.getRequestJournalStore() instanceof CompressedRequestJournalStore c) {
            return c;
        }
        if (currentStores != null
                && currentStores.getRequestJournalStore() instanceof CompressedRequestJournalStore c) {
            return c;
        }
        return null;
    }

    private JournalStats fallbackStats(Stores currentStores) {
        long count = 0;
        if (currentStores != null && currentStores.getRequestJournalStore() != null) {
            count = currentStores.getRequestJournalStore().getAll().count();
        }
        return new JournalStats(count, 0, 0, 1.0, 0, 0, 0, Runtime.getRuntime().maxMemory());
    }
}
