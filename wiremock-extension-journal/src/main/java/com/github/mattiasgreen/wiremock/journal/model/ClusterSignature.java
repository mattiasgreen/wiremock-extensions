package com.github.mattiasgreen.wiremock.journal.model;

import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.net.URI;
import java.util.Objects;

/**
 * Structural classification signature identifying the payload schema cluster.
 * Used for targeted Zstandard dictionary training without schema cross-contamination.
 */
public record ClusterSignature(String key) {

    public ClusterSignature {
        Objects.requireNonNull(key, "key must not be null");
    }

    public static ClusterSignature of(ServeEvent event) {
        if (event == null) {
            return new ClusterSignature("unmatched:unknown");
        }

        ResponseDefinition respDef = event.getResponseDefinition();
        Request request = event.getRequest();
        StubMapping stub = event.getStubMapping();

        // 1. Proxied call (HTTP or Casual)
        if (respDef != null && respDef.isProxyResponse()) {
            String proxyUrl = respDef.getProxyUrl();
            if (proxyUrl == null && stub != null && stub.getResponse() != null) {
                proxyUrl = stub.getResponse().getProxyUrl();
            }

            // Casual proxy check
            if (request != null && request.containsHeader("X-Casual-Service")) {
                String service = request.getHeader("X-Casual-Service");
                return new ClusterSignature("casual-proxy:" + service);
            }

            // Async queue / broker proxy check
            if (request != null && request.containsHeader("X-Async-Channel")) {
                String protocol =
                        request.containsHeader("X-Async-Protocol") ? request.getHeader("X-Async-Protocol") : "generic";
                return new ClusterSignature("async-proxy:" + protocol + ":" + request.getHeader("X-Async-Channel"));
            }
            if (request != null && request.getUrl() != null && request.getUrl().startsWith("/__async/")) {
                return new ClusterSignature("async-proxy:" + cleanAsyncPath(request.getUrl()));
            }

            if (proxyUrl != null && !proxyUrl.isBlank()) {
                try {
                    URI uri = URI.create(proxyUrl);
                    String host = uri.getHost() != null ? uri.getHost() : "upstream";
                    String path = uri.getPath() != null && !uri.getPath().isEmpty() ? uri.getPath() : "/";
                    String rootSegment = path.equals("/") ? "/" : "/" + path.split("/")[1];
                    return new ClusterSignature("proxy:" + host + rootSegment);
                } catch (IllegalArgumentException | IndexOutOfBoundsException ignored) {
                    return new ClusterSignature("proxy:generic");
                }
            }
            return new ClusterSignature("proxy:generic");
        }

        // 2. Concrete Stubbed response
        if (stub != null && stub.getId() != null) {
            return new ClusterSignature("stub:" + stub.getId());
        }

        // 3. Casual offline mock
        if (request != null && request.containsHeader("X-Casual-Service")) {
            return new ClusterSignature("casual-stub:" + request.getHeader("X-Casual-Service"));
        }

        // 4. Async messaging queue / channel clustering
        if (request != null && request.containsHeader("X-Async-Channel")) {
            String protocol =
                    request.containsHeader("X-Async-Protocol") ? request.getHeader("X-Async-Protocol") : "generic";
            return new ClusterSignature("async-queue:" + protocol + ":" + request.getHeader("X-Async-Channel"));
        }
        if (request != null && request.getUrl() != null && request.getUrl().startsWith("/__async/")) {
            return new ClusterSignature("async-queue:" + cleanAsyncPath(request.getUrl()));
        }

        // 5. Unmatched traffic fallback
        String method = request != null && request.getMethod() != null
                ? request.getMethod().getName()
                : "GET";
        return new ClusterSignature("unmatched:" + method);
    }

    private static String cleanAsyncPath(String url) {
        String path = url.substring("/__async/".length());
        int queryIdx = path.indexOf('?');
        if (queryIdx >= 0) {
            path = path.substring(0, queryIdx);
        }
        return path.replace('/', ':');
    }
}
