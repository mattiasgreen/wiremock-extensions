package com.github.mattiasgreen.wiremock.examples.banking.sdk;

import java.time.LocalDate;

/**
 * Client interface for interacting with the Banking CRM service.
 */
public interface CrmClient {

    /**
     * Retrieves the KYC and risk profile for a given customer as of an effective date.
     *
     * @param customerId unique customer identifier
     * @param asOf effective evaluation date
     * @return customer profile
     */
    CustomerKycProfile getCustomerKyc(String customerId, LocalDate asOf);
}
