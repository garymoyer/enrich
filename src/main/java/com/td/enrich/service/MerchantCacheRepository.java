package com.td.enrich.service;

import com.td.enrich.domain.MerchantCacheEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Database access interface for the {@code merchant_cache} table.
 *
 * <p>Spring Data JPA generates the implementation of this interface automatically at
 * startup — you don't write any SQL or implementation code here. Declaring a method
 * with the right naming convention (e.g. {@code findBy...}) is enough for Spring to
 * generate the correct query.
 *
 * <p>The primary key for {@link MerchantCacheEntity} is {@code merchantId} (a UUID
 * string), so inherited methods like {@code findById(merchantId)} and
 * {@code save(entity)} are already available from {@link JpaRepository} without any
 * additional code.
 *
 * <p>This interface is used in two places:
 * <ul>
 *   <li>{@link EnrichmentService} — checks for cache hits before calling Plaid, and
 *       saves new entries on cache misses.</li>
 *   <li>{@link EnrichmentQueueProcessor} — updates the {@code plaid_response} and
 *       {@code status} columns after a background Plaid call completes.</li>
 * </ul>
 */
@Repository
public interface MerchantCacheRepository extends JpaRepository<MerchantCacheEntity, String> {

    /**
     * Looks up a cached merchant by its composite key: (description, merchantName).
     *
     * <p>Spring translates this method name into a query equivalent to:
     * <pre>{@code
     * SELECT * FROM merchant_cache
     *  WHERE description = ? AND merchant_name = ?
     * }</pre>
     *
     * <p>Important: callers must coerce a {@code null} merchant name to {@code ""}
     * before calling this method, because SQL {@code NULL != NULL} — two null values
     * would never match each other in the database.
     *
     * @param description  the raw transaction description (case-sensitive)
     * @param merchantName the merchant name hint, or {@code ""} if none was provided
     * @return an {@link Optional} containing the cached entry if one exists,
     *         or {@link Optional#empty()} on a cache miss
     */
    Optional<MerchantCacheEntity> findByDescriptionAndMerchantName(String description, String merchantName);

    /**
     * Finds all ENRICHED entries whose Plaid data is older than the given cutoff time.
     *
     * <p>Used by the TTL refresh scheduler to identify stale cache entries that should
     * be re-submitted to the enrichment queue. Only {@code ENRICHED} rows are returned
     * — {@code PENDING} rows are already awaiting enrichment and do not need to be
     * re-queued.
     *
     * <p>Spring translates this method name into a query equivalent to:
     * <pre>{@code
     * SELECT * FROM merchant_cache
     *  WHERE status = 'ENRICHED'
     *    AND last_enriched_at < ?
     * }</pre>
     *
     * @param cutoff the threshold timestamp; rows with {@code last_enriched_at} before
     *               this value are considered stale
     * @return all stale ENRICHED cache entries, potentially an empty list
     */
    List<MerchantCacheEntity> findByStatusAndLastEnrichedAtBefore(String status, OffsetDateTime cutoff);
}
