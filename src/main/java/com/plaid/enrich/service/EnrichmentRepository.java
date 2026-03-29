package com.plaid.enrich.service;

import com.plaid.enrich.domain.EnrichmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Spring Data JPA repository for EnrichmentEntity.
 * Provides database operations for persisting and retrieving enrichment records.
 */
@Repository
public interface EnrichmentRepository extends JpaRepository<EnrichmentEntity, String> {

    /**
     * Finds all enrichment records with a specific status.
     *
     * @param status the status to filter by (SUCCESS, FAILED, PENDING)
     * @return list of enrichment records with the given status
     */
    List<EnrichmentEntity> findByStatus(String status);

    /**
     * Finds enrichment records created after a specific timestamp.
     *
     * @param createdAt the timestamp threshold
     * @return list of enrichment records created after the timestamp
     */
    List<EnrichmentEntity> findByCreatedAtAfter(OffsetDateTime createdAt);

    /**
     * Finds enrichment records created within a date range.
     *
     * @param startDate the start of the date range
     * @param endDate the end of the date range
     * @return list of enrichment records within the date range
     */
    @Query("SELECT e FROM EnrichmentEntity e WHERE e.createdAt BETWEEN :startDate AND :endDate ORDER BY e.createdAt DESC")
    List<EnrichmentEntity> findByCreatedAtBetween(OffsetDateTime startDate, OffsetDateTime endDate);

    /**
     * Counts records by status.
     *
     * @param status the status to count
     * @return count of records with the given status
     */
    long countByStatus(String status);
}
