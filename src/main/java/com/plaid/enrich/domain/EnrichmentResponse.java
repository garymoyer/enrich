package com.plaid.enrich.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Client response containing enriched transaction data.
 * Includes the GUID for tracking and enriched transaction details from Plaid.
 */
@Schema(description = "Response containing enriched transaction data")
public record EnrichmentResponse(

    @Schema(description = "Unique GUID for this enrichment request",
            example = "550e8400-e29b-41d4-a716-446655440000")
    String requestId,

    @Schema(description = "List of enriched transactions with Plaid data")
    List<EnrichedTransaction> enrichedTransactions,

    @Schema(description = "Timestamp when the request was processed")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    OffsetDateTime processedAt,

    @Schema(description = "Processing status", example = "SUCCESS")
    String status,

    @Schema(description = "Error message if status is FAILED")
    String errorMessage
) {

    /**
     * Convenience constructor without error message.
     */
    public EnrichmentResponse(String requestId,
                             List<EnrichedTransaction> enrichedTransactions,
                             OffsetDateTime processedAt,
                             String status) {
        this(requestId, enrichedTransactions, processedAt, status, null);
    }

    /**
     * Enriched transaction with Plaid metadata.
     */
    @Schema(description = "Single enriched transaction with Plaid data")
    public record EnrichedTransaction(

        @Schema(description = "Transaction identifier", example = "txn_1")
        String transactionId,

        @Schema(description = "Plaid-determined category", example = "Food & Drink")
        String category,

        @Schema(description = "Standardized merchant name", example = "Starbucks Coffee")
        String merchantName,

        @Schema(description = "URL to merchant logo",
                example = "https://logo.clearbit.com/starbucks.com")
        String logoUrl,

        @Schema(description = "Additional Plaid metadata")
        Map<String, Object> metadata
    ) {}
}
