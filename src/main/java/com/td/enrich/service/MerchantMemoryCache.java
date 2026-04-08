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
 * An in-memory lookup table for merchant data, backed by a {@link ConcurrentHashMap}.
 *
 * <p><b>Why this exists:</b> Every enrichment request needs to check whether we've
 * already seen a given (description, merchantName) pair so we can skip the Plaid API
 * call. Going to the database for every lookup adds a round-trip on every request.
 * This class eliminates that round-trip by keeping a copy of all known merchants
 * in heap memory.
 *
 * <p><b>Startup population:</b> When Spring starts the application, it calls
 * {@link #initialize()} (via {@code @PostConstruct}). That method reads every row from
 * the {@code merchant_cache} database table and inserts it into the map. Subsequent
 * requests can be answered from memory without touching the database.
 *
 * <p><b>Cache key format:</b> {@code lowercase(description) + "|" + lowercase(merchantName)}.
 * Lowercasing makes lookups case-insensitive: "STARBUCKS" and "Starbucks" resolve
 * to the same entry.
 *
 * <p><b>Concurrency model:</b> {@link ConcurrentHashMap} allows safe concurrent reads
 * and writes without explicit locking. The {@link #getOrCreate} method uses
 * {@link ConcurrentHashMap#computeIfAbsent} to guarantee the supplier runs at most once
 * per key even when dozens of request threads race on the same merchant simultaneously.
 *
 * <p><b>Maximum size:</b> Configured via {@code enrich.cache.max-size} (default
 * 1 million entries). Once the map is full, new entries are still created by calling
 * the supplier (so they can be persisted to the DB) but are not inserted into the
 * in-memory map. This prevents unbounded heap growth at high merchant cardinality.
 *
 * <p><b>Monitoring:</b> Hit and miss counters are tracked with {@link AtomicLong}
 * and exposed via {@link #hitCount()} / {@link #missCount()} for Actuator metrics.
 */
@Component
@Slf4j
public class MerchantMemoryCache {

    private final ConcurrentHashMap<String, MerchantCacheEntry> cache;
    private final int maxSize;
    private final MerchantCacheRepository repository;
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();

    /**
     * Spring calls this constructor and injects the configuration and repository.
     *
     * <p>The initial map capacity is capped at 64 K buckets to avoid allocating a
     * 1-million-bucket array eagerly at startup; the map grows as entries are added.
     *
     * @param properties cache configuration (max size, worker threads, etc.)
     * @param repository JPA repository used to load existing merchants at startup
     */
    public MerchantMemoryCache(EnrichCacheProperties properties, MerchantCacheRepository repository) {
        this.maxSize = properties.getMaxSize();
        // Initial capacity is capped to avoid over-allocating when the configured max is large
        this.cache = new ConcurrentHashMap<>(Math.min(maxSize, 65_536));
        this.repository = repository;
    }

    /**
     * Loads all existing merchant records from the database into the in-memory map.
     *
     * <p>Called automatically by Spring after all beans have been wired together
     * (that's what {@code @PostConstruct} means — "run this after construction is done").
     * The startup completes in milliseconds for small datasets; larger datasets may
     * take a few seconds, which is logged at INFO level.
     *
     * <p>If the database contains more rows than {@code maxSize}, loading stops at
     * the limit and a warning is logged. The remaining rows will be fetched from the
     * DB on demand as they are encountered.
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
     * Returns the cached entry for the given (description, merchantName) pair, if present.
     *
     * <p>Increments the hit counter on a cache hit and the miss counter on a miss so
     * the ratio can be monitored.
     *
     * @param description  the raw transaction description (e.g. {@code "STARBUCKS COFFEE"})
     * @param merchantName the merchant name, or an empty string if not provided
     * @return an {@code Optional} containing the entry if found, or empty if not found
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
     * Returns the existing entry if present; otherwise calls the supplier to create
     * a new one and atomically inserts it into the cache.
     *
     * <p><b>Why this method exists:</b> When two threads both miss the cache for the
     * same merchant at the same time, a naive "get → if null, create → put" sequence
     * could call the supplier twice and create two entries. This method uses
     * {@link ConcurrentHashMap#computeIfAbsent}, which guarantees the supplier is
     * called <em>at most once per key</em> even under heavy concurrent load.
     *
     * <p><b>When the cache is full:</b> The supplier is still called so the caller can
     * persist the entry to the database, but the result is NOT inserted into the
     * in-memory map (to prevent unbounded memory growth).
     *
     * @param description  the raw transaction description
     * @param merchantName the merchant name, or empty string
     * @param supplier     a function that creates a new {@link MerchantCacheEntry};
     *                     called at most once per key
     * @return a {@link GetOrCreateResult} carrying the entry and a flag indicating
     *         whether it was newly created ({@code true}) or already existed ({@code false})
     */
    public GetOrCreateResult getOrCreate(String description, String merchantName,
                                         Supplier<MerchantCacheEntry> supplier) {
        String key = buildKey(description, merchantName);

        // Fast path: already in cache — skip computeIfAbsent overhead
        MerchantCacheEntry existing = cache.get(key);
        if (existing != null) {
            hitCount.incrementAndGet();
            return new GetOrCreateResult(existing, false);
        }
        missCount.incrementAndGet();

        // Cache is full — call the supplier so the caller can still persist to DB,
        // but don't insert into the in-memory map
        if (cache.size() >= maxSize) {
            log.warn("Merchant memory cache full ({} entries); entry for [{}] will not be cached in memory",
                    maxSize, key);
            return new GetOrCreateResult(supplier.get(), true);
        }

        // computeIfAbsent is atomic: if two threads reach this line for the same key,
        // exactly one of them will call supplier.get() and insert the result.
        // The other thread will find the entry the first thread already inserted.
        AtomicBoolean created = new AtomicBoolean(false);
        MerchantCacheEntry entry = cache.computeIfAbsent(key, k -> {
            created.set(true);
            return supplier.get();
        });
        return new GetOrCreateResult(entry, created.get());
    }

    /**
     * Replaces a PENDING entry's {@code plaidResponse} with the enriched data returned
     * by Plaid, and updates the status to {@code "ENRICHED"}.
     *
     * <p>Called by {@link EnrichmentQueueProcessor} after it receives a successful
     * response from Plaid. {@link ConcurrentHashMap#computeIfPresent} is used so the
     * operation is a no-op if the entry has already been evicted or was never inserted.
     *
     * <p><b>Important invariant:</b> This method should only be called <em>after</em>
     * the corresponding DB row has been updated inside a committed transaction. That
     * ordering guarantees that if the process crashes between the DB write and this
     * call, the in-memory cache will be stale but the DB is authoritative — the next
     * startup will reload the correct data from the DB.
     *
     * @param description  the raw transaction description (used to build the cache key)
     * @param merchantName the merchant name (used to build the cache key)
     * @param plaidResponse the enriched Plaid response JSON to store
     */
    public void update(String description, String merchantName, String plaidResponse) {
        cache.computeIfPresent(
                buildKey(description, merchantName),
                // withPlaidResponse creates a new immutable MerchantCacheEntry with the updated fields
                (k, existing) -> existing.withPlaidResponse(plaidResponse)
        );
    }

    /**
     * Unconditionally inserts an entry into the map.
     *
     * <p>Used after {@link #getOrCreate} returns {@code created=true} but the cache was
     * full at that time (so the supplier result was not auto-inserted). A later
     * {@link #put} call can be made if space has freed up by then.
     *
     * @param entry the entry to insert (silently ignored if the cache is still full)
     */
    public void put(MerchantCacheEntry entry) {
        if (cache.size() < maxSize) {
            cache.put(buildKey(entry.description(), entry.merchantName()), entry);
        }
    }

    /** Returns the current number of entries in the map. */
    public int size() { return cache.size(); }

    /** Returns the cumulative number of successful cache lookups since startup. */
    public long hitCount() { return hitCount.get(); }

    /** Returns the cumulative number of cache misses since startup. */
    public long missCount() { return missCount.get(); }

    /**
     * Builds the composite lookup key for the given (description, merchantName) pair.
     *
     * <p>Both parts are lowercased so lookups are case-insensitive. A pipe character
     * ({@code |}) separates them so "AB" + "C" and "A" + "BC" produce different keys.
     * {@code null} values are treated as empty strings.
     *
     * <p>This method is package-private (not {@code private}) so it can be called
     * directly in unit tests to verify key construction without going through the
     * full {@link #get} or {@link #getOrCreate} methods.
     *
     * @param description  the transaction description (may be {@code null})
     * @param merchantName the merchant name (may be {@code null})
     * @return the normalized composite key, e.g. {@code "starbucks coffee|starbucks"}
     */
    static String buildKey(String description, String merchantName) {
        return (description == null ? "" : description.toLowerCase())
                + "|"
                + (merchantName == null ? "" : merchantName.toLowerCase());
    }

    /**
     * Return type for {@link #getOrCreate}.
     *
     * <p>Callers use the {@code created} flag to decide whether they need to persist
     * the entry to the database: if {@code created == true}, the entry is brand-new
     * and must be saved; if {@code created == false}, it already exists in the cache
     * and (presumably) in the DB as well.
     *
     * @param entry   the cache entry — either the pre-existing one or the newly created one
     * @param created {@code true} if the supplier was called and a new entry was made;
     *                {@code false} if an existing entry was returned
     */
    public record GetOrCreateResult(MerchantCacheEntry entry, boolean created) {}
}
