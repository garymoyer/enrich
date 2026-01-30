package com.plaid.enrich.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Response payload from Plaid Enrich API.
 * This record maps to the structure returned by Plaid's enrichment endpoint.
 */
public record PlaidEnrichResponse(

    @JsonProperty("enriched_transactions")
    List<PlaidEnrichedTransaction> enrichedTransactions,

    @JsonProperty("request_id")
    String requestId
) {

    /**
     * Enriched transaction data from Plaid.
     */
    public record PlaidEnrichedTransaction(

        @JsonProperty("id")
        String id,

        @JsonProperty("category")
        String category,

        @JsonProperty("category_id")
        String categoryId,

        @JsonProperty("merchant_name")
        String merchantName,

        @JsonProperty("logo_url")
        String logoUrl,

        @JsonProperty("website")
        String website,

        @JsonProperty("confidence_level")
        String confidenceLevel,

        @JsonProperty("enrichment_metadata")
        Map<String, Object> enrichmentMetadata
    ) {}
}
