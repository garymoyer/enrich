package com.td.enrich.service;

import com.td.enrich.config.EnrichCacheProperties;
import com.td.enrich.domain.MerchantCacheEntity;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * In-memory merchant cache backed by a {@link ConcurrentHashMap}.
 *
 * <p>The cache is populated at startup from the {@code merchant_cache} DB table and serves as the
 * primary lookup path for all enrichment requests, eliminating per-request DB round-trips.
 *
 * <p>Key design properties:
 * <ul>
 *   <li>Configurable maximum size (default 1 million entries).</li>
 *   <li>Atomic {@link #getOrCreate} using {@link ConcurrentHashMap#computeIfAbsent} — the
 *       supplier is called at most once per key even under heavy concurrency.</li>
 *   <li>Thread-safe {@link #update} for background Plaid response writes.</li>
 *   <li>Hit/miss counters exposed for monitoring.</li>
 * </ul>
 *
 * <p>Cache key is {@code lowercase(description) + "|" + lowercase(merchantName)}, making lookups
 * case-insensitive.
 */
@Component
@Slf4j
public class MerchantMemoryCache {

    private final ConcurrentHashMap<String, MerchantCacheEntry> cache;
    private final int maxSize;
    private final MerchantCacheRepository repository;
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();

    public MerchantMemoryCache(EnrichCacheProperties properties, MerchantCacheRepository repository) {
        this.maxSize = properties.getMaxSize();
        // Initial capacity: cap at 64K to avoid allocating a 1M-bucket map eagerly
        this.cache = new ConcurrentHashMap<>(Math.min(maxSize, 65_536));
        this.repository = repository;
    }

    /**
     * Loads all merchant records from the database into the in-memory cache.
     * Called once during Spring context initialization.
     */
    @PostConstruct
    public void initialize() {
        long start = System.nanoTime();
        log.info("Initializing merchant memory cache (max-size={})...", maxSize);

        List<MerchantCacheEntity> entities = repository.findAll();
        int loaded = 0;
        for (MerchantCacheEntity e : entities) {
            if (loaded >= maxSize) {
                log.warn("Merchant memory cache reached max-size={} during init; {} records not loaded",
                        maxSize, entities.size() - loaded);
                break;
            }
            cache.put(
                    buildKey(e.getDescription(), e.getMerchantName()),
                    new MerchantCacheEntry(
                            e.getMerchantId(), e.getDescription(), e.getMerchantName(),
                            e.getPlaidResponse(), e.getStatus()
                    )
            );
            loaded++;
        }
        log.info("Merchant memory cache initialized: {}/{} records loaded in {}ms",
                loaded, entities.size(), (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * Returns the cached entry for (description, merchantName) if present.
     * Increments hit or miss counter accordingly.
     */
    public Optional<MerchantCacheEntry> get(String description, String merchantName) {
        MerchantCacheEntry entry = cache.get(buildKey(description, merchantName));
        if (entry != null) {
            hitCount.incrementAndGet();
        } else {
            missCount.incrementAndGet();
        }
        return Optional.ofNullable(entry);
    }

    /**
     * Returns the existing entry if present, or atomically creates and inserts a new one.
     *
     * <p>The {@code supplier} is called at most once per key (guaranteed by
     * {@link ConcurrentHashMap#computeIfAbsent}). If the cache has reached {@code maxSize},
     * the supplier is still called so the caller can persist the entry to DB, but the result
     * is not inserted into the in-memory cache.
     *
     * @param supplier produces a new {@link MerchantCacheEntry}; must not return null
     * @return a result carrying the entry and a flag indicating whether it was just created
     */
    public GetOrCreateResult getOrCreate(String description, String merchantName,
                                         Supplier<MerchantCacheEntry> supplier) {
        String key = buildKey(description, merchantName);

        // Fast path: already in cache (avoid computeIfAbsent overhead on hits)
        MerchantCacheEntry existing = cache.get(key);
        if (existing != null) {
            hitCount.incrementAndGet();
            return new GetOrCreateResult(existing, false);
        }
        missCount.incrementAndGet();

        if (cache.size() >= maxSize) {
            log.warn("Merchant memory cache full ({} entries); entry for [{}] will not be cached in memory",
                    maxSize, key);
            return new GetOrCreateResult(supplier.get(), true);
        }

        // computeIfAbsent is atomic: supplier called exactly once even under concurrent access
        AtomicBoolean created = new AtomicBoolean(false);
        MerchantCacheEntry entry = cache.computeIfAbsent(key, k -> {
            created.set(true);
            return supplier.get();
        });
        return new GetOrCreateResult(entry, created.get());
    }

    /**
     * Updates an existing entry with the enriched Plaid response after background enrichment.
     * No-op if the entry has been evicted.
     */
    public void update(String description, String merchantName, String plaidResponse) {
        cache.computeIfPresent(
                buildKey(description, merchantName),
                (k, existing) -> existing.withPlaidResponse(plaidResponse)
        );
    }

    /** Unconditionally inserts an entry (used when cache was full during getOrCreate). */
    public void put(MerchantCacheEntry entry) {
        if (cache.size() < maxSize) {
            cache.put(buildKey(entry.description(), entry.merchantName()), entry);
        }
    }

    public int size() { return cache.size(); }
    public long hitCount() { return hitCount.get(); }
    public long missCount() { return missCount.get(); }

    /** Builds the composite cache key: lowercase description + "|" + lowercase merchantName. */
    static String buildKey(String description, String merchantName) {
        return (description == null ? "" : description.toLowerCase())
                + "|"
                + (merchantName == null ? "" : merchantName.toLowerCase());
    }

    /**
     * Result of a {@link #getOrCreate} call.
     *
     * @param entry   the cache entry (existing or newly created)
     * @param created true if the supplier was invoked and a new entry was created
     */
    public record GetOrCreateResult(MerchantCacheEntry entry, boolean created) {}
}
