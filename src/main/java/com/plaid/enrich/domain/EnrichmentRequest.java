package com.plaid.enrich.domain;

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
 * Client request for transaction enrichment.
 * Represents the input from external clients requesting Plaid enrichment services.
 */
@Schema(description = "Request to enrich transaction data via Plaid API")
public record EnrichmentRequest(

    @Schema(description = "Account identifier for the transactions", example = "acc_12345")
    @NotBlank(message = "Account ID is required")
    String accountId,

    @Schema(description = "List of transactions to enrich")
    @NotEmpty(message = "At least one transaction is required")
    @Valid
    List<Transaction> transactions
) {

    /**
     * Individual transaction to be enriched.
     */
    @Schema(description = "Transaction details")
    public record Transaction(

        @Schema(description = "Transaction description/memo", example = "STARBUCKS COFFEE #123")
        @NotBlank(message = "Description is required")
        String description,

        @Schema(description = "Transaction amount", example = "5.75")
        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be positive")
        BigDecimal amount,

        @Schema(description = "Transaction date", example = "2026-01-30")
        @NotNull(message = "Date is required")
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate date,

        @Schema(description = "Merchant name if known", example = "Starbucks")
        String merchantName
    ) {}
}
