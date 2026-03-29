package com.plaid.enrich.service;

/**
 * Immutable value object stored in the in-memory merchant cache.
 *
 * <p>A PENDING entry has a generated merchantId and the transaction's raw description/name, but
 * no Plaid enrichment data yet — that arrives asynchronously via {@link EnrichmentQueueProcessor}.
 * An ENRICHED entry carries the full Plaid JSON payload in {@code plaidResponse}.
 */
public record MerchantCacheEntry(
        String merchantId,
        String description,
        String merchantName,
        String plaidResponse,
        String status
) {
    /** Returns true while the Plaid API call for this merchant has not yet completed. */
    public boolean isPending() {
        return "PENDING".equals(status);
    }

    /** Returns a new entry with the Plaid response applied and status set to ENRICHED. */
    public MerchantCacheEntry withPlaidResponse(String plaidResponse) {
        return new MerchantCacheEntry(merchantId, description, merchantName, plaidResponse, "ENRICHED");
    }
}
