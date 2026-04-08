package com.td.enrich.service;

import com.td.enrich.domain.EnrichmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Database access interface for the {@code enrichment_records} table.
 *
 * <p>Like {@link MerchantCacheRepository}, this interface is implemented automatically
 * by Spring Data JPA. Declaring a method with the correct name or a {@code @Query}
 * annotation is enough — no boilerplate SQL or JDBC code is needed.
 *
 * <p>Every enrichment request writes a row here so operations teams have a full
 * audit trail: what was requested, what Plaid returned, and whether it succeeded.
 * The primary key is {@code requestId} (a UUID string), so the inherited
 * {@code findById(requestId)} method is the main read path used by
 * {@code GET /api/v1/enrich/{requestId}}.
 *
 * <p>The extra query methods below are provided for operational tooling (dashboards,
 * alerting) and are not called in the main enrichment request flow.
 */
@Repository
public interface EnrichmentRepository extends JpaRepository<EnrichmentEntity, String> {

    /**
     * Returns all records with a given status.
     *
     * <p>Useful for operational dashboards: find all {@code FAILED} records from the
     * last hour to investigate errors.
     *
     * @param status one of {@code "SUCCESS"}, {@code "FAILED"}, or {@code "PENDING"}
     * @return list of matching records, unordered
     */
    List<EnrichmentEntity> findByStatus(String status);

    /**
     * Returns all records created after a given timestamp, ordered by the database's
     * natural storage order (not guaranteed to be time-ordered unless sorted).
     *
     * <p>Useful for incremental processing: "give me everything created since the
     * last time I checked."
     *
     * @param createdAt the exclusive lower-bound timestamp
     * @return list of records created after {@code createdAt}
     */
    List<EnrichmentEntity> findByCreatedAtAfter(OffsetDateTime createdAt);

    /**
     * Returns records whose {@code created_at} falls within an inclusive date range,
     * ordered newest-first.
     *
     * <p>The {@code @Query} annotation supplies a JPQL query directly because the
     * Spring method-name convention doesn't handle {@code BETWEEN ... ORDER BY}
     * in one step.
     *
     * @param startDate beginning of the range (inclusive)
     * @param endDate   end of the range (inclusive)
     * @return records in the range, sorted descending by {@code createdAt}
     */
    @Query("SELECT e FROM EnrichmentEntity e WHERE e.createdAt BETWEEN :startDate AND :endDate ORDER BY e.createdAt DESC")
    List<EnrichmentEntity> findByCreatedAtBetween(OffsetDateTime startDate, OffsetDateTime endDate);

    /**
     * Returns the count of records with a given status.
     *
     * <p>Useful for Prometheus-style metrics: "how many requests are currently stuck
     * in PENDING?" Spring generates an optimized {@code COUNT} query automatically.
     *
     * @param status one of {@code "SUCCESS"}, {@code "FAILED"}, or {@code "PENDING"}
     * @return the number of matching records
     */
    long countByStatus(String status);
}
