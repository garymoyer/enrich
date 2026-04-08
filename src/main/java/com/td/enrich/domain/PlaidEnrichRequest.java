package com.td.enrich.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The JSON body we send to Plaid's {@code POST /enrich/transactions} endpoint.
 *
 * <p>Plaid's API uses snake_case field names (e.g. {@code client_id}), while our
 * internal code uses camelCase. The {@code @JsonProperty} annotations on each field
 * tell Jackson (the JSON library) how to translate between the two when serializing
 * this object to JSON before sending it over the network.
 *
 * <p>The {@code clientId} and {@code secret} fields are injected by
 * {@link com.td.enrich.service.PlaidApiClient} just before each outbound call —
 * callers upstream pass {@code null} for those two fields and let the client fill
 * them in from environment variables, keeping credentials out of business logic.
 *
 * <p>Example JSON this record produces:
 * <pre>{@code
 * {
 *   "client_id":   "client_abc123",
 *   "secret":      "secret_xyz789",
 *   "account_id":  "acc_12345",
 *   "transactions": [
 *     {
 *       "description":   "STARBUCKS COFFEE #1234",
 *       "amount":        5.75,
 *       "date":          "2026-04-07",
 *       "merchant_name": "Starbucks"
 *     }
 *   ]
 * }
 * }</pre>
 */
public record PlaidEnrichRequest(

    /** Plaid client ID credential. Injected by {@link com.td.enrich.service.PlaidApiClient}. */
    @JsonProperty("client_id")
    String clientId,

    /** Plaid secret credential. Injected by {@link com.td.enrich.service.PlaidApiClient}. */
    @JsonProperty("secret")
    String secret,

    /** The account ID from the original client request, passed through to Plaid. */
    @JsonProperty("account_id")
    String accountId,

    /** The list of transactions to enrich. Mapped from {@link EnrichmentRequest.Transaction} objects. */
    @JsonProperty("transactions")
    List<PlaidTransaction> transactions

) {

    /**
     * A single transaction in the format Plaid expects.
     *
     * <p>This is a subset of our internal {@link EnrichmentRequest.Transaction} — Plaid
     * only needs the fields listed here. The mapping from internal to Plaid format is
     * done in {@link com.td.enrich.service.EnrichmentService}.
     */
    public record PlaidTransaction(

        /** Raw transaction description from the bank statement. Primary matching signal for Plaid. */
        @JsonProperty("description")
        String description,

        /** Transaction amount. Plaid may use this to distinguish merchant types. */
        @JsonProperty("amount")
        BigDecimal amount,

        /** Transaction date. Plaid serializes this as "YYYY-MM-DD". */
        @JsonProperty("date")
        LocalDate date,

        /** Optional known merchant name hint — improves Plaid matching accuracy when provided. */
        @JsonProperty("merchant_name")
        String merchantName

    ) {}
}
