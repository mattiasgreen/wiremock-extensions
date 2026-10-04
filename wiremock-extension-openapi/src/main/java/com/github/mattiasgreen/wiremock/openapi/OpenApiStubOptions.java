package com.github.mattiasgreen.wiremock.openapi;

/**
 * Options configuring how OpenAPI specifications are converted into WireMock stub mappings.
 */
public record OpenApiStubOptions(
        boolean includeOnlySuccessResponses,
        boolean matchRequiredQueryParams,
        boolean matchRequiredHeaders,
        boolean useDefaultValues,
        boolean useSyntheticExamples) {

    public OpenApiStubOptions {
        // canonical constructor
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

        public OpenApiStubOptions build() {
            return new OpenApiStubOptions(
                    includeOnlySuccessResponses,
                    matchRequiredQueryParams,
                    matchRequiredHeaders,
                    useDefaultValues,
                    useSyntheticExamples);
        }
    }
}
