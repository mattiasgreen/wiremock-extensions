package com.github.mattiasgreen.wiremock.examples.banking.sdk;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Domain model representing a customer's KYC and risk profile, derived from Banking CRM OpenAPI.
 * Enforces provider invariants upon build.
 */
@JsonDeserialize(builder = CustomerKycProfile.Builder.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class CustomerKycProfile {

    @JsonProperty("customerId")
    private final String customerId;

    @JsonProperty("fullName")
    private final String fullName;

    @JsonProperty("kycStatus")
    private final KycStatus kycStatus;

    @JsonProperty("riskTier")
    private final RiskTier riskTier;

    @JsonProperty("segment")
    private final CustomerSegment segment;

    @JsonProperty("onboardedDate")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private final LocalDate onboardedDate;

    @JsonProperty("asOfDate")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private final LocalDate asOfDate;

    @JsonProperty("processedBy")
    private final String processedBy;

    private CustomerKycProfile(Builder builder) {
        this.customerId = builder.customerId;
        this.fullName = builder.fullName;
        this.kycStatus = builder.kycStatus;
        this.riskTier = builder.riskTier;
        this.segment = builder.segment;
        this.onboardedDate = builder.onboardedDate;
        this.asOfDate = builder.asOfDate;
        this.processedBy = builder.processedBy;
    }

    @JsonProperty("customerId")
    public String customerId() {
        return customerId;
    }

    @JsonProperty("fullName")
    public String fullName() {
        return fullName;
    }

    @JsonProperty("kycStatus")
    public KycStatus kycStatus() {
        return kycStatus;
    }

    @JsonProperty("riskTier")
    public RiskTier riskTier() {
        return riskTier;
    }

    @JsonProperty("segment")
    public CustomerSegment segment() {
        return segment;
    }

    @JsonProperty("onboardedDate")
    public LocalDate onboardedDate() {
        return onboardedDate;
    }

    @JsonProperty("asOfDate")
    public LocalDate asOfDate() {
        return asOfDate;
    }

    @JsonProperty("processedBy")
    public String processedBy() {
        return processedBy;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .customerId(customerId)
                .fullName(fullName)
                .kycStatus(kycStatus)
                .riskTier(riskTier)
                .segment(segment)
                .onboardedDate(onboardedDate)
                .asOfDate(asOfDate)
                .processedBy(processedBy);
    }

    @JsonPOJOBuilder(withPrefix = "")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Builder {

        @JsonProperty("customerId")
        private String customerId;

        @JsonProperty("fullName")
        private String fullName;

        @JsonProperty("kycStatus")
        private KycStatus kycStatus;

        @JsonProperty("riskTier")
        private RiskTier riskTier;

        @JsonProperty("segment")
        private CustomerSegment segment;

        @JsonProperty("onboardedDate")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        private LocalDate onboardedDate;

        @JsonProperty("asOfDate")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        private LocalDate asOfDate;

        @JsonProperty("processedBy")
        private String processedBy;

        @JsonProperty("customerId")
        public Builder customerId(String customerId) {
            this.customerId = customerId;
            return this;
        }

        @JsonProperty("fullName")
        public Builder fullName(String fullName) {
            this.fullName = fullName;
            return this;
        }

        @JsonProperty("kycStatus")
        public Builder kycStatus(KycStatus kycStatus) {
            this.kycStatus = kycStatus;
            return this;
        }

        @JsonProperty("riskTier")
        public Builder riskTier(RiskTier riskTier) {
            this.riskTier = riskTier;
            return this;
        }

        @JsonProperty("segment")
        public Builder segment(CustomerSegment segment) {
            this.segment = segment;
            return this;
        }

        @JsonProperty("onboardedDate")
        public Builder onboardedDate(LocalDate onboardedDate) {
            this.onboardedDate = onboardedDate;
            return this;
        }

        @JsonProperty("asOfDate")
        public Builder asOfDate(LocalDate asOfDate) {
            this.asOfDate = asOfDate;
            return this;
        }

        @JsonProperty("processedBy")
        public Builder processedBy(String processedBy) {
            this.processedBy = processedBy;
            return this;
        }

        /**
         * Validates provider domain invariants before producing the immutable profile.
         */
        public CustomerKycProfile build() {
            Objects.requireNonNull(customerId, "customerId must not be null");
            Objects.requireNonNull(fullName, "fullName must not be null");
            Objects.requireNonNull(kycStatus, "kycStatus must not be null");
            Objects.requireNonNull(riskTier, "riskTier must not be null");
            Objects.requireNonNull(segment, "segment must not be null");
            Objects.requireNonNull(onboardedDate, "onboardedDate must not be null");

            if (segment == CustomerSegment.PREMIER && kycStatus != KycStatus.VERIFIED) {
                throw new IllegalStateException(
                        "Provider invariant violation: PREMIER segment customer must have VERIFIED kycStatus");
            }

            return new CustomerKycProfile(this);
        }
    }
}
