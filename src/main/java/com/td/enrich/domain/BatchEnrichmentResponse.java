package com.td.enrich.domain;

import java.util.List;

/**
 * Response for batch transaction enrichment submission.
 * Returns immediately (202 Accepted) with GUIDs for polling.
 */
public record BatchEnrichmentResponse(
    int requestCount,
    List<String> guids,
    String message
) {
}
