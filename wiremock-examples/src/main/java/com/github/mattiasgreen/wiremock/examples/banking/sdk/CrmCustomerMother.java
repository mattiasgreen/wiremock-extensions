package com.github.mattiasgreen.wiremock.examples.banking.sdk;

import java.time.LocalDate;

/**
 * Object Mother synthesizing authentic domain customer profiles based on recorded baselines.
 * Exposes pre-populated fluent builders allowing individual tests to override only relevant traits.
 */
public final class CrmCustomerMother {

    private CrmCustomerMother() {}

    /**
     * Synthesizes a Premier VIP customer with low risk and long tenure (> 2 years).
     *
     * @return pre-populated builder
     */
    public static CustomerKycProfile.Builder premierVipCustomer() {
        return CustomerKycProfile.builder()
                .customerId("CUST-42")
                .fullName("Eleanor Vance")
                .kycStatus(KycStatus.VERIFIED)
                .riskTier(RiskTier.LOW)
                .segment(CustomerSegment.PREMIER)
                .onboardedDate(LocalDate.now().minusYears(4))
                .asOfDate(LocalDate.now())
                .processedBy("crm-testkit");
    }

    /**
     * Synthesizes a newly onboarded Standard customer with high risk and pending KYC verification.
     *
     * @return pre-populated builder
     */
    public static CustomerKycProfile.Builder highRiskNewCustomer() {
        return CustomerKycProfile.builder()
                .customerId("CUST-99")
                .fullName("Marcus Thorne")
                .kycStatus(KycStatus.PENDING)
                .riskTier(RiskTier.HIGH)
                .segment(CustomerSegment.STANDARD)
                .onboardedDate(LocalDate.now().minusDays(10))
                .asOfDate(LocalDate.now())
                .processedBy("crm-testkit");
    }
}
