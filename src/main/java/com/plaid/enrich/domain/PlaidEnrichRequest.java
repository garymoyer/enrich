package com.plaid.enrich.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Request payload for Plaid Enrich API.
 * This record maps to the structure expected by Plaid's enrichment endpoint.
 */
public record PlaidEnrichRequest(

    @JsonProperty("client_id")
    String clientId,

    @JsonProperty("secret")
    String secret,

    @JsonProperty("account_id")
    String accountId,

    @JsonProperty("transactions")
    List<PlaidTransaction> transactions
) {

    /**
     * Transaction format expected by Plaid API.
     */
    public record PlaidTransaction(

        @JsonProperty("description")
        String description,

        @JsonProperty("amount")
        BigDecimal amount,

        @JsonProperty("date")
        LocalDate date,

        @JsonProperty("merchant_name")
        String merchantName
    ) {}
}
