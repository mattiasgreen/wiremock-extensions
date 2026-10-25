package com.github.mattiasgreen.wiremock.examples.banking.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Production-ready HTTP client implementation using standard JDK {@link HttpClient} and Jackson.
 */
public class HttpCrmClient implements CrmClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpCrmClient(String baseUrl) {
        this(baseUrl, HttpClient.newHttpClient());
    }

    public HttpCrmClient(String baseUrl, HttpClient httpClient) {
        this.baseUrl =
                Objects.requireNonNull(baseUrl, "baseUrl must not be null").replaceAll("/+$", "");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public CustomerKycProfile getCustomerKyc(String customerId, LocalDate asOf) {
        Objects.requireNonNull(customerId, "customerId must not be null");
        String url = String.format("%s/api/crm/customers/%s/kyc", baseUrl, customerId);
        if (asOf != null) {
            url += "?asOf=" + asOf;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException(String.format(
                        "CRM API call failed with status %d: %s", response.statusCode(), response.body()));
            }
            return objectMapper.readValue(response.body(), CustomerKycProfile.class);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to call CRM service", e);
        }
    }
}
