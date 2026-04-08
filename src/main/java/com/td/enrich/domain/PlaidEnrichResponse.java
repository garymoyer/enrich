package com.td.enrich.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Maps the JSON response body that Plaid returns from its enrichment endpoint.
 *
 * <p>Plaid returns snake_case field names. The {@code @JsonProperty} annotations tell
 * Jackson how to deserialize the incoming JSON into these Java fields. After
 * deserialization, the data is stored in the merchant cache and transformed into
 * our own {@link EnrichmentResponse} format before being returned to the client.
 *
 * <p>Example Plaid JSON that this record deserializes:
 * <pre>{@code
 * {
 *   "enriched_transactions": [
 *     {
 *       "id":                "txn_001",
 *       "category":          "Coffee Shops",
 *       "category_id":       "13005000",
 *       "merchant_name":     "Starbucks Coffee",
 *       "logo_url":          "https://logo.clearbit.com/starbucks.com",
 *       "website":           "https://www.starbucks.com",
 *       "confidence_level":  "HIGH",
 *       "enrichment_metadata": { "location": "Seattle, WA" }
 *     }
 *   ],
 *   "request_id": "plaid_req_12345"
 * }
 * }</pre>
 */
public record PlaidEnrichResponse(

    /**
     * The list of enriched transactions. Plaid returns one entry for each transaction
     * we sent, in the same order. If Plaid cannot match a transaction, it may return
     * an entry with null/empty fields rather than omitting it entirely.
     */
    @JsonProperty("enriched_transactions")
    List<PlaidEnrichedTransaction> enrichedTransactions,

    /**
     * Plaid's own request identifier, useful for cross-referencing with Plaid's
     * support logs when diagnosing upstream issues.
     */
    @JsonProperty("request_id")
    String requestId

) {

    /**
     * A single enriched transaction as returned by Plaid.
     *
     * <p>This object is serialized to JSON and stored verbatim in the
     * {@code merchant_cache.plaid_response} column. When a cache hit occurs,
     * the stored JSON is deserialized back into this record to build the response —
     * so the column is effectively a serialized snapshot of this type.
     */
    public record PlaidEnrichedTransaction(

        /** Plaid's transaction ID. Echoed back in our {@link EnrichmentResponse.EnrichedTransaction}. */
        @JsonProperty("id")
        String id,

        /**
         * Human-readable category (e.g. "Coffee Shops", "Grocery Stores").
         * Mapped directly into {@link EnrichmentResponse.EnrichedTransaction#category()}.
         */
        @JsonProperty("category")
        String category,

        /**
         * Plaid's internal numeric category code. Stored in the {@code metadata} map
         * of our response under the key {@code "categoryId"}.
         */
        @JsonProperty("category_id")
        String categoryId,

        /**
         * Standardized merchant name (e.g. "Starbucks Coffee" rather than the raw
         * "STARBUCKS #1234 SEATTLE WA"). This is the cleaned-up name shown to end users.
         */
        @JsonProperty("merchant_name")
        String merchantName,

        /**
         * URL to the merchant's logo, or {@code null} if Plaid doesn't have one.
         * Safe to render in a UI as an {@code <img>} src.
         */
        @JsonProperty("logo_url")
        String logoUrl,

        /**
         * The merchant's website URL (e.g. "https://www.starbucks.com").
         * Stored in the {@code metadata} map under the key {@code "website"}.
         */
        @JsonProperty("website")
        String website,

        /**
         * Plaid's confidence in its merchant match: "HIGH", "MEDIUM", or "LOW".
         * Stored in the {@code metadata} map under the key {@code "confidenceLevel"}.
         * Callers can use this to decide whether to display or trust the enrichment result.
         */
        @JsonProperty("confidence_level")
        String confidenceLevel,

        /**
         * A free-form map of extra data Plaid may return (e.g. location, MCC code).
         * All entries are merged directly into the top-level {@code metadata} map in
         * the client-facing {@link EnrichmentResponse.EnrichedTransaction}.
         */
        @JsonProperty("enrichment_metadata")
        Map<String, Object> enrichmentMetadata

    ) {}
}
