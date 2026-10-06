package com.github.mattiasgreen.wiremock.openapi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Options configuring how OpenAPI specifications are converted into WireMock stub mappings.
 */
public record OpenApiStubOptions(
        boolean includeOnlySuccessResponses,
        boolean matchRequiredQueryParams,
        boolean matchRequiredHeaders,
        boolean useDefaultValues,
        boolean useSyntheticExamples,
        GenerationMode generationMode,
        String proxyBaseUrl,
        String targetProject,
        Map<String, String> additionalProxyHeaders) {

    public OpenApiStubOptions {
        Objects.requireNonNull(generationMode, "generationMode must not be null");
        if (additionalProxyHeaders == null) {
            additionalProxyHeaders = Collections.emptyMap();
        } else {
            additionalProxyHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(additionalProxyHeaders));
        }
    }

    /**
     * Backwards-compatible constructor defaulting to SYNTHETIC mode without proxy.
     */
    public OpenApiStubOptions(
            boolean includeOnlySuccessResponses,
            boolean matchRequiredQueryParams,
            boolean matchRequiredHeaders,
            boolean useDefaultValues,
            boolean useSyntheticExamples) {
        this(
                includeOnlySuccessResponses,
                matchRequiredQueryParams,
                matchRequiredHeaders,
                useDefaultValues,
                useSyntheticExamples,
                GenerationMode.SYNTHETIC,
                null,
                null,
                Collections.emptyMap());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static OpenApiStubOptions defaults() {
        return builder().build();
    }

    public static class Builder {
        private boolean includeOnlySuccessResponses = false;
        private boolean matchRequiredQueryParams = true;
        private boolean matchRequiredHeaders = true;
        private boolean useDefaultValues = true;
        private boolean useSyntheticExamples = true;
        private GenerationMode generationMode = GenerationMode.SYNTHETIC;
        private String proxyBaseUrl = null;
        private String targetProject = null;
        private Map<String, String> additionalProxyHeaders = new LinkedHashMap<>();

        public Builder includeOnlySuccessResponses(boolean includeOnlySuccessResponses) {
            this.includeOnlySuccessResponses = includeOnlySuccessResponses;
            return this;
        }

        public Builder matchRequiredQueryParams(boolean matchRequiredQueryParams) {
            this.matchRequiredQueryParams = matchRequiredQueryParams;
            return this;
        }

        public Builder matchRequiredHeaders(boolean matchRequiredHeaders) {
            this.matchRequiredHeaders = matchRequiredHeaders;
            return this;
        }

        public Builder useDefaultValues(boolean useDefaultValues) {
            this.useDefaultValues = useDefaultValues;
            return this;
        }

        public Builder useSyntheticExamples(boolean useSyntheticExamples) {
            this.useSyntheticExamples = useSyntheticExamples;
            return this;
        }

        public Builder generationMode(GenerationMode generationMode) {
            this.generationMode = generationMode != null ? generationMode : GenerationMode.SYNTHETIC;
            return this;
        }

        public Builder proxyBaseUrl(String proxyBaseUrl) {
            this.proxyBaseUrl = proxyBaseUrl;
            if (proxyBaseUrl != null && !proxyBaseUrl.isBlank() && this.generationMode == GenerationMode.SYNTHETIC) {
                this.generationMode = GenerationMode.PROXY;
            }
            return this;
        }

        public Builder targetProject(String targetProject) {
            this.targetProject = targetProject;
            return this;
        }

        public Builder additionalProxyHeaders(Map<String, String> additionalProxyHeaders) {
            if (additionalProxyHeaders != null) {
                this.additionalProxyHeaders.putAll(additionalProxyHeaders);
            }
            return this;
        }

        public Builder additionalProxyHeader(String name, String value) {
            if (name != null && value != null) {
                this.additionalProxyHeaders.put(name, value);
            }
            return this;
        }

        public OpenApiStubOptions build() {
            return new OpenApiStubOptions(
                    includeOnlySuccessResponses,
                    matchRequiredQueryParams,
                    matchRequiredHeaders,
                    useDefaultValues,
                    useSyntheticExamples,
                    generationMode,
                    proxyBaseUrl,
                    targetProject,
                    additionalProxyHeaders);
        }
    }
}
