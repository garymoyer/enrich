package com.td.enrich.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Represents a request from a client asking us to enrich one or more transactions.
 *
 * <p>A client sends this object as the JSON body of a {@code POST /api/v1/enrich}
 * request. Spring automatically validates every field annotated with
 * {@code @NotBlank}, {@code @NotNull}, etc. before the controller method runs.
 * If validation fails, Spring returns a 400 Bad Request before any business
 * logic executes.
 *
 * <p>This is a Java <em>record</em>, which means:
 * <ul>
 *   <li>All fields are set exactly once via the constructor — there are no setters.</li>
 *   <li>Equality, hashing, and {@code toString} are generated automatically.</li>
 *   <li>Fields are accessed with {@code request.accountId()}, not {@code getAccountId()}.</li>
 * </ul>
 *
 * <p>Example JSON body:
 * <pre>{@code
 * {
 *   "accountId": "acc_12345",
 *   "transactions": [
 *     {
 *       "description": "STARBUCKS COFFEE #1234 SEATTLE WA",
 *       "amount": 5.75,
 *       "date": "2026-04-07",
 *       "merchantName": "Starbucks"
 *     }
 *   ]
 * }
 * }</pre>
 */
@Schema(description = "Request to enrich one or more transactions via the Plaid API")
public record EnrichmentRequest(

    /**
     * The caller's account identifier. Used to scope the request for audit logging.
     * Must be a non-blank string (e.g. "acc_12345").
     */
    @Schema(description = "Account identifier for the requesting system", example = "acc_12345")
    @NotBlank(message = "Account ID is required")
    String accountId,

    /**
     * The list of transactions to enrich. At least one transaction is required.
     * The {@code @Valid} annotation tells Spring to also validate each
     * {@link Transaction} object inside the list.
     */
    @Schema(description = "Transactions to enrich — at least one required")
    @NotEmpty(message = "At least one transaction is required")
    @Valid
    List<Transaction> transactions

) {

    /**
     * A single transaction submitted for enrichment.
     *
     * <p>Only {@code description}, {@code amount}, and {@code date} are required.
     * Providing {@code merchantName} as a hint improves Plaid's matching accuracy
     * but is not mandatory.
     */
    @Schema(description = "A single transaction to enrich")
    public record Transaction(

        /**
         * The raw transaction description exactly as it appears on the bank statement
         * (e.g. "STARBUCKS COFFEE #1234 SEATTLE WA"). Plaid uses this string as the
         * primary signal for merchant matching.
         */
        @Schema(description = "Raw transaction description from the bank statement",
                example = "STARBUCKS COFFEE #1234 SEATTLE WA")
        @NotBlank(message = "Description is required")
        String description,

        /**
         * The transaction amount in the account's currency. Must be greater than zero.
         * Plaid may use the amount to disambiguate merchants (e.g. coffee shop vs.
         * grocery store with the same name).
         */
        @Schema(description = "Transaction amount — must be greater than zero", example = "5.75")
        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be positive")
        BigDecimal amount,

        /**
         * The date the transaction occurred. Used by Plaid for context.
         * Jackson serializes/deserializes this as "yyyy-MM-dd" (e.g. "2026-04-07").
         */
        @Schema(description = "Date of the transaction in YYYY-MM-DD format", example = "2026-04-07")
        @NotNull(message = "Date is required")
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate date,

        /**
         * An optional hint for the merchant name if the caller already knows it
         * (e.g. "Starbucks"). Providing this can improve Plaid matching confidence.
         * May be {@code null}.
         */
        @Schema(description = "Optional known merchant name — improves Plaid matching",
                example = "Starbucks", nullable = true)
        String merchantName

    ) {}
}
