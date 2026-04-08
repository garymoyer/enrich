package com.td.enrich.service;

/**
 * An immutable snapshot of one merchant stored in the in-memory cache.
 *
 * <p>The in-memory cache ({@link MerchantMemoryCache}) holds instances of this record
 * keyed on {@code lowercase(description) + "|" + lowercase(merchantName)}. It mirrors
 * the {@link com.td.enrich.domain.MerchantCacheEntity} database table, but lives in
 * heap memory so lookups are nanosecond-fast without a database round-trip.
 *
 * <p><b>Two-phase lifecycle:</b>
 * <ol>
 *   <li><b>PENDING</b> — the entry is created the moment a new (description, merchantName)
 *       pair is first encountered. At this point {@code plaidResponse} is {@code null}
 *       because the Plaid API call has not happened yet. The service returns the generated
 *       {@code merchantId} immediately so the caller isn't blocked.</li>
 *   <li><b>ENRICHED</b> — once the {@link EnrichmentQueueProcessor} completes the
 *       background Plaid call, it calls {@link #withPlaidResponse(String)} to produce a
 *       new ENRICHED entry and replaces the PENDING entry in the cache.
 *       Because this record is <em>immutable</em>, "updating" means creating a new
 *       instance — the old one is simply discarded.</li>
 * </ol>
 *
 * <p>This class is a Java <em>record</em>, so all fields are final, and equality /
 * hashing / toString are generated automatically.
 */
public record MerchantCacheEntry(

    /**
     * The stable UUID assigned to this (description, merchantName) merchant.
     * Generated once by {@link com.td.enrich.util.GuidGenerator} and never changed.
     */
    String merchantId,

    /** Transaction description used as the first half of the cache lookup key. */
    String description,

    /** Merchant name hint used as the second half of the cache lookup key. */
    String merchantName,

    /**
     * The Plaid enrichment result serialized as JSON.
     * {@code null} while {@code status = "PENDING"}; populated once Plaid responds.
     * Stored as a JSON string (not a parsed object) so this record stays lightweight
     * and doesn't drag Jackson parsing into every cache lookup.
     */
    String plaidResponse,

    /**
     * Current enrichment state: {@code "PENDING"} or {@code "ENRICHED"}.
     * Use {@link #isPending()} rather than comparing this string directly.
     */
    String status

) {

    /**
     * Returns {@code true} when the Plaid API call for this merchant has not yet
     * completed. A PENDING entry has a valid {@code merchantId} that can be returned
     * to callers immediately, but the full merchant data (category, logo, etc.) is
     * not yet available.
     *
     * @return true if this entry is still awaiting enrichment
     */
    public boolean isPending() {
        return "PENDING".equals(status);
    }

    /**
     * Produces a new ENRICHED entry by copying this one and replacing the
     * {@code plaidResponse} and {@code status} fields.
     *
     * <p>Because records are immutable, we can't modify this instance in place.
     * Instead, {@link MerchantMemoryCache#update} replaces the cache entry atomically
     * using the new object returned here.
     *
     * @param plaidResponse the Plaid JSON response for this merchant
     * @return a new {@code MerchantCacheEntry} with status {@code "ENRICHED"}
     */
    public MerchantCacheEntry withPlaidResponse(String plaidResponse) {
        return new MerchantCacheEntry(merchantId, description, merchantName, plaidResponse, "ENRICHED");
    }
}
