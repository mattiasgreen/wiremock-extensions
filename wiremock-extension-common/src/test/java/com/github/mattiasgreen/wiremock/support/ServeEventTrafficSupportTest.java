package com.github.mattiasgreen.wiremock.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.ImmutableRequest;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ServeEventTrafficSupportTest {

    @Test
    @DisplayName("Should detect async protocol from canonical X-Async-Protocol and X-Async-Channel headers")
    void shouldDetectProtocolFromCanonicalAsyncHeaders() {
        ImmutableRequest req = ImmutableRequest.create()
                .withMethod(RequestMethod.POST)
                .withAbsoluteUrl("http://localhost:8080/synthetic")
                .withHeaders(new HttpHeaders(
                        new HttpHeader("X-Async-Protocol", "ibmmq"), new HttpHeader("X-Async-Channel", "DEV.QUEUE.1")))
                .build();

        assertThat(ServeEventTrafficSupport.detectProtocol(req)).isEqualTo("ibmmq");
    }

    @Test
    @DisplayName("Should detect async protocol from legacy X-WireMock-Async-Protocol and X-Async-Destination headers")
    void shouldDetectProtocolFromLegacyAsyncHeaders() {
        ImmutableRequest req = ImmutableRequest.create()
                .withMethod(RequestMethod.POST)
                .withAbsoluteUrl("http://localhost:8080/synthetic")
                .withHeaders(new HttpHeaders(
                        new HttpHeader("X-WireMock-Async-Protocol", "amqp"),
                        new HttpHeader("X-Async-Destination", "orders.inbound")))
                .build();

        assertThat(ServeEventTrafficSupport.detectProtocol(req)).isEqualTo("amqp");
    }
}
