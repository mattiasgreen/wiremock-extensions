package com.github.mattiasgreen.wiremock.examples.banking.sdk;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Consumer business service evaluating credit card application terms based on CRM customer tenure
 * and risk profile. This service demonstrates business logic that is sensitive to temporal sliding
 * windows and dynamic customer IDs.
 */
public class CreditCardOnboardingEvaluator {

    private final CrmClient crmClient;

    public CreditCardOnboardingEvaluator(CrmClient crmClient) {
        this.crmClient = Objects.requireNonNull(crmClient, "crmClient must not be null");
    }

    public EvaluationResult evaluate(String customerId, LocalDate evaluationDate) {
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(evaluationDate, "evaluationDate must not be null");

        CustomerKycProfile profile = crmClient.getCustomerKyc(customerId, evaluationDate);

        if (profile.kycStatus() != KycStatus.VERIFIED) {
            return new EvaluationResult(false, "REJECTED_KYC_PENDING", 0.0);
        }

        if (profile.riskTier() == RiskTier.HIGH) {
            return new EvaluationResult(false, "REJECTED_HIGH_RISK", 0.0);
        }

        long tenureYears = ChronoUnit.YEARS.between(profile.onboardedDate(), evaluationDate);
        if (profile.segment() == CustomerSegment.PREMIER && tenureYears >= 2) {
            return new EvaluationResult(true, "WAIVED_ANNUAL_FEE_VIP", 0.0);
        }

        return new EvaluationResult(true, "STANDARD_FEE", 199.0);
    }

    public record EvaluationResult(boolean approved, String feeCode, double feeAmount) {}
}
