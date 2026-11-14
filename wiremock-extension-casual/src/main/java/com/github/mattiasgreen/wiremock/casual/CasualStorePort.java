package com.github.mattiasgreen.wiremock.casual;

import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.util.List;
import java.util.Optional;

/**
 * Seam for WireMock stub matching, file blob retrieval, and request journal recording.
 * Backed by {@link WireMockStoreAdapter} for in-process WireMock stores,
 * {@link HttpCasualStoreAdapter} for remote HTTP Admin API access, and
 * {@link InMemoryCasualStoreAdapter} in fast unit tests.
 */
public interface CasualStorePort {

    Optional<StubMapping> findMatchingStub(Request request);

    List<StubMapping> getActiveStubMappings();

    String readBodyFromFile(String bodyFileName);

    void recordServeEvent(ServeEvent serveEvent);

    String getBaseUrl();
}
