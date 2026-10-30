package com.github.mattiasgreen.wiremock.casual;

import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.util.List;
import java.util.Optional;

/**
 * Seam for WireMock stub matching, file blob retrieval, and request journal recording.
 * Backed by {@link WireMockStoreAdapter} in production and {@link InMemoryCasualStoreAdapter} in tests.
 */
public interface CasualStorePort {

    Optional<StubMapping> findMatchingStub(Request request);

    List<StubMapping> getActiveStubMappings();

    String readBodyFromFile(String bodyFileName);

    void recordServeEvent(ServeEvent serveEvent);

    String getBaseUrl();
}
