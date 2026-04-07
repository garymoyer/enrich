package com.td.enrich.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Request for batch transaction enrichment (asynchronous).
 * Accept multiple transactions and return GUIDs for polling.
 */
public record BatchEnrichmentRequest(
    @Valid
    @NotEmpty(message = "Transactions list cannot be empty")
    List<EnrichmentRequest> transactions
) {
}
