-- Add enrichment status tracking to merchant_cache.
-- PENDING  = stub created, awaiting Plaid API response
-- ENRICHED = Plaid data received and stored in plaid_response

ALTER TABLE merchant_cache
    ADD status VARCHAR(20) NOT NULL DEFAULT 'ENRICHED';

-- All pre-existing rows have a plaid_response, so they are already fully enriched
UPDATE merchant_cache SET status = 'ENRICHED';

CREATE INDEX idx_merchant_cache_status ON merchant_cache(status);
