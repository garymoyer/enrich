package com.td.enrich.domain;

import java.util.List;

/**
 * Immediate response returned for a {@code POST /api/v1/enrich/batch} request.
 *
 * <p>Because batch enrichment is asynchronous, this response is sent back before
 * any actual Plaid calls have been made. It tells the caller:
 * <ol>
 *   <li>How many requests were accepted ({@link #requestCount}).</li>
 *   <li>A UUID for each accepted request ({@link #guids}) — one per element in the
 *       batch input. The caller uses these UUIDs to poll
 *       {@code GET /api/v1/enrich/{guid}} until enrichment completes.</li>
 *   <li>A human-readable confirmation ({@link #message}).</li>
 * </ol>
 *
 * <p>Example JSON response for a two-transaction batch:
 * <pre>{@code
 * {
 *   "requestCount": 2,
 *   "guids": [
 *     "550e8400-e29b-41d4-a716-446655440001",
 *     "550e8400-e29b-41d4-a716-446655440002"
 *   ],
 *   "message": "Batch of 2 transactions submitted for enrichment"
 * }
 * }</pre>
 */
public record BatchEnrichmentResponse(

    /** Number of requests accepted into the processing queue. */
    int requestCount,

    /**
     * UUIDs assigned to each request, in the same order as the input list.
     * Poll {@code GET /api/v1/enrich/{guid}} for each one to retrieve results.
     */
    List<String> guids,

    /** Human-readable confirmation message (e.g. "Batch of 2 transactions submitted"). */
    String message

) {}
