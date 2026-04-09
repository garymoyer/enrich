package com.td.enrich.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Database row that caches enrichment data for a specific (description, merchantName) pair.
 *
 * <p><b>Why this table exists:</b> Calling the Plaid API for every transaction would be
 * slow and expensive. Instead, the first time we see a particular transaction description
 * (e.g. "STARBUCKS COFFEE #1234"), we call Plaid, store the result here, and assign it
 * a stable {@code merchantId} UUID. All future transactions with the same description
 * are served from this cache — no Plaid call needed.
 *
 * <p><b>The cache key</b> is the combination of {@code (description, merchantName)}.
 * The unique constraint {@code UQ_merchant_desc_name} on the table enforces that no
 * two rows ever share the same pair. If two threads try to insert the same pair
 * simultaneously, the database rejects the second insert with a
 * {@link org.springframework.dao.DataIntegrityViolationException}, which the service
 * handles gracefully by re-reading the row the first thread won.
 *
 * <p><b>Status lifecycle:</b>
 * <ol>
 *   <li>{@code PENDING} — row created immediately when we first see this merchant;
 *       {@code plaidResponse} is {@code null} at this point. The
 *       {@link com.td.enrich.service.EnrichmentQueueProcessor} will fill it in shortly.</li>
 *   <li>{@code ENRICHED} — Plaid returned data; {@code plaidResponse} holds the full
 *       JSON from {@link PlaidEnrichResponse.PlaidEnrichedTransaction}.</li>
 * </ol>
 *
 * <p>The in-memory {@link com.td.enrich.service.MerchantMemoryCache} mirrors this table
 * and is loaded from it at startup, so every pod has a warm cache on boot.
 */
@Entity
@Table(
    name = "merchant_cache",
    uniqueConstraints = @UniqueConstraint(
        name = "UQ_merchant_desc_name",
        columnNames = {"description", "merchant_name"}
    )
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantCacheEntity {

    /**
     * Stable UUID assigned to this (description, merchantName) pair.
     * Generated once by {@link com.td.enrich.util.GuidGenerator} and never changes,
     * so downstream systems can safely store it as a foreign key.
     */
    @Id
    @Column(name = "merchant_id", nullable = false, length = 36)
    private String merchantId;

    /**
     * The raw transaction description used as the first half of the cache key
     * (e.g. "STARBUCKS COFFEE #1234 SEATTLE WA").
     */
    @Column(name = "description", nullable = false, columnDefinition = "NVARCHAR(500)")
    private String description;

    /**
     * The merchant name hint used as the second half of the cache key.
     * {@code null} is coerced to an empty string {@code ""} before writing so that
     * the unique constraint works consistently (SQL {@code NULL != NULL}).
     */
    @Column(name = "merchant_name", nullable = false, columnDefinition = "NVARCHAR(255)")
    private String merchantName;

    /**
     * The full Plaid enrichment result stored as JSON. This is a serialized
     * {@link PlaidEnrichResponse.PlaidEnrichedTransaction}. It is {@code null} while
     * {@code status = PENDING} and populated once the background worker completes the
     * Plaid call.
     */
    @Column(name = "plaid_response", columnDefinition = "NVARCHAR(MAX)")
    private String plaidResponse;

    /**
     * Current enrichment state: {@code "PENDING"} or {@code "ENRICHED"}.
     * See the class-level Javadoc for the full lifecycle description.
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /**
     * UTC timestamp when this row was first inserted. Never updated after creation.
     */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /**
     * UTC timestamp of the most recent successful Plaid enrichment for this merchant.
     * Updated every time the background queue processor writes a fresh Plaid response.
     * {@code null} while {@code status = PENDING} (Plaid has not yet returned data).
     *
     * <p>Used by the TTL refresh scheduler to find stale entries — any row whose
     * {@code last_enriched_at} is older than {@code enrich.cache.ttl-days} will be
     * re-submitted to the enrichment queue.
     */
    @Column(name = "last_enriched_at")
    private OffsetDateTime lastEnrichedAt;
}
