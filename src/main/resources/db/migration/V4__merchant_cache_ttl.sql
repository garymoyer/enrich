-- Add last_enriched_at column to support TTL-based cache refresh.
--
-- When the background worker successfully writes a Plaid response it also sets
-- last_enriched_at = GETUTCDATE(). A scheduled job queries for rows where this
-- column is older than the configured ttl-days threshold and re-submits them for
-- enrichment so merchant data (logos, categories, names) stays fresh over time.
--
-- Pre-existing ENRICHED rows are seeded with created_at so they are not all
-- immediately flagged as stale after the migration runs.

ALTER TABLE merchant_cache
    ADD last_enriched_at DATETIME2 NULL;

-- Seed existing enriched rows with their creation timestamp so the scheduler
-- does not immediately re-queue every entry on first deployment.
UPDATE merchant_cache
    SET last_enriched_at = created_at
    WHERE status = 'ENRICHED';

CREATE INDEX idx_merchant_cache_last_enriched ON merchant_cache(last_enriched_at);
