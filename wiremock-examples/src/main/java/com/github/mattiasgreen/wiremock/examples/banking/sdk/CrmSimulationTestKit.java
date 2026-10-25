package com.github.mattiasgreen.wiremock.examples.banking.sdk;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.time.LocalDate;
import java.util.Objects;
import org.mockito.Mockito;

/**
 * Dual-Mode Simulation DSL supporting both Level 2 (In-JVM Mockito) and Level 3 (WireMock HTTP)
 * simulation using the exact same domain profile builders.
 */
public final class CrmSimulationTestKit {

    private final Mode mode;
    private final WireMockServer wireMockServer;
    private final CrmClient mockitoClient;
    private final ObjectMapper objectMapper;

    private enum Mode {
        WIREMOCK,
        MOCKITO
    }

    private CrmSimulationTestKit(WireMockServer wireMockServer) {
        this.mode = Mode.WIREMOCK;
        this.wireMockServer = Objects.requireNonNull(wireMockServer, "wireMockServer must not be null");
        this.mockitoClient = null;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private CrmSimulationTestKit(CrmClient mockitoClient) {
        this.mode = Mode.MOCKITO;
        this.mockitoClient = Objects.requireNonNull(mockitoClient, "mockitoClient must not be null");
        this.wireMockServer = null;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Creates a simulation testkit bound to a running WireMock server (Level 3 Wire Boundary).
     *
     * @param wireMockServer running WireMock server
     * @return simulation testkit
     */
    public static CrmSimulationTestKit forWireMock(WireMockServer wireMockServer) {
        return new CrmSimulationTestKit(wireMockServer);
    }

    /**
     * Creates a simulation testkit bound to an in-memory Mockito mock client (Level 2 JVM Boundary).
     *
     * @param mockitoClient Mockito mock of {@link CrmClient}
     * @return simulation testkit
     */
    public static CrmSimulationTestKit forMockito(CrmClient mockitoClient) {
        return new CrmSimulationTestKit(mockitoClient);
    }

    /**
     * Stubs the customer KYC profile across either WireMock or Mockito.
     *
     * @param profile synthesized customer KYC profile
     */
    public void stubCustomerKyc(CustomerKycProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");

        if (mode == Mode.MOCKITO) {
            Mockito.when(mockitoClient.getCustomerKyc(eq(profile.customerId()), any(LocalDate.class)))
                    .thenReturn(profile);
            Mockito.when(mockitoClient.getCustomerKyc(eq(profile.customerId()), any()))
                    .thenReturn(profile);
        } else {
            try {
                String json = objectMapper.writeValueAsString(profile);
                wireMockServer.stubFor(WireMock.get(WireMock.urlPathTemplate("/api/crm/customers/{customerId}/kyc"))
                        .withPathParam("customerId", WireMock.equalTo(profile.customerId()))
                        .withQueryParam("asOf", WireMock.matching(".+"))
                        .atPriority(1)
                        .willReturn(WireMock.aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(json)));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize CustomerKycProfile to JSON", e);
            }
        }
    }
}
