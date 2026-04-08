package com.td.enrich.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Request body for the {@code POST /api/v1/enrich/batch} endpoint.
 *
 * <p>Batch enrichment is asynchronous: the service accepts the list, assigns each
 * request a UUID, queues the work, and responds immediately with a
 * {@link BatchEnrichmentResponse} containing those UUIDs. The caller then polls
 * {@code GET /api/v1/enrich/{requestId}} for each UUID until the status changes
 * from {@code PROCESSING} to {@code SUCCESS} or {@code FAILED}.
 *
 * <p>Each element in {@code transactions} is a full {@link EnrichmentRequest},
 * so it must include a valid {@code accountId} and at least one transaction.
 * The {@code @Valid} annotation cascades validation down into each element.
 */
public record BatchEnrichmentRequest(

    /**
     * The list of enrichment requests to process asynchronously.
     * Must contain at least one element; each element is individually validated.
     */
    @Valid
    @NotEmpty(message = "Transactions list cannot be empty")
    List<EnrichmentRequest> transactions

) {}
