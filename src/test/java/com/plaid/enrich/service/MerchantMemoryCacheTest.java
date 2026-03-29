package com.plaid.enrich.service;

import com.plaid.enrich.config.EnrichCacheProperties;
import com.plaid.enrich.domain.MerchantCacheEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MerchantMemoryCache Unit Tests")
class MerchantMemoryCacheTest {

    @Mock
    private MerchantCacheRepository repository;

    private MerchantMemoryCache cache;

    @BeforeEach
    void setUp() {
        EnrichCacheProperties props = new EnrichCacheProperties();
        props.setMaxSize(100);
        when(repository.findAll()).thenReturn(List.of());
        cache = new MerchantMemoryCache(props, repository);
        cache.initialize();
    }

    @Test
    @DisplayName("get returns empty on cold cache")
    void getReturnEmptyOnColdCache() {
        assertThat(cache.get("STARBUCKS", "Starbucks")).isEmpty();
        assertThat(cache.missCount()).isEqualTo(1);
        assertThat(cache.hitCount()).isZero();
    }

    @Test
    @DisplayName("get returns entry after put and increments hit counter")
    void getReturnEntryAfterPut() {
        MerchantCacheEntry entry = new MerchantCacheEntry("mid-1", "STARBUCKS", "Starbucks", null, "PENDING");
        cache.put(entry);

        Optional<MerchantCacheEntry> result = cache.get("STARBUCKS", "Starbucks");
        assertThat(result).isPresent();
        assertThat(result.get().merchantId()).isEqualTo("mid-1");
        assertThat(cache.hitCount()).isEqualTo(1);
        assertThat(cache.missCount()).isZero();
    }

    @Test
    @DisplayName("get is case-insensitive via normalised key")
    void getIsCaseInsensitive() {
        cache.put(new MerchantCacheEntry("mid-1", "starbucks coffee", "starbucks", null, "PENDING"));

        assertThat(cache.get("STARBUCKS COFFEE", "STARBUCKS")).isPresent();
        assertThat(cache.get("Starbucks Coffee", "Starbucks")).isPresent();
    }

    @Test
    @DisplayName("getOrCreate calls supplier exactly once on miss and inserts entry")
    void getOrCreateCallsSupplierOnceOnMiss() {
        AtomicInteger callCount = new AtomicInteger();
        MerchantCacheEntry stub = new MerchantCacheEntry("mid-new", "AMAZON", "Amazon", null, "PENDING");

        MerchantMemoryCache.GetOrCreateResult first =
                cache.getOrCreate("AMAZON", "Amazon", () -> { callCount.incrementAndGet(); return stub; });
        MerchantMemoryCache.GetOrCreateResult second =
                cache.getOrCreate("AMAZON", "Amazon", () -> { callCount.incrementAndGet(); return stub; });

        assertThat(first.created()).isTrue();
        assertThat(first.entry().merchantId()).isEqualTo("mid-new");
        assertThat(second.created()).isFalse();
        assertThat(second.entry().merchantId()).isEqualTo("mid-new");
        assertThat(callCount.get()).isEqualTo(1);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("getOrCreate returns existing entry as not-created when key already present")
    void getOrCreateReturnsExistingOnHit() {
        MerchantCacheEntry existing = new MerchantCacheEntry("mid-ex", "COSTCO", "Costco", "{}", "ENRICHED");
        cache.put(existing);

        AtomicInteger callCount = new AtomicInteger();
        MerchantMemoryCache.GetOrCreateResult result =
                cache.getOrCreate("COSTCO", "Costco", () -> { callCount.incrementAndGet(); return existing; });

        assertThat(result.created()).isFalse();
        assertThat(result.entry().merchantId()).isEqualTo("mid-ex");
        assertThat(callCount.get()).isZero();
    }

    @Test
    @DisplayName("update replaces plaidResponse and sets status to ENRICHED")
    void updateReplacesPlaidResponseAndStatus() {
        cache.put(new MerchantCacheEntry("mid-1", "WALMART", "Walmart", null, "PENDING"));

        cache.update("WALMART", "Walmart", "{\"merchant_name\":\"Walmart\"}");

        Optional<MerchantCacheEntry> updated = cache.get("WALMART", "Walmart");
        assertThat(updated).isPresent();
        assertThat(updated.get().status()).isEqualTo("ENRICHED");
        assertThat(updated.get().plaidResponse()).isEqualTo("{\"merchant_name\":\"Walmart\"}");
        assertThat(updated.get().isPending()).isFalse();
    }

    @Test
    @DisplayName("update is a no-op when key is absent")
    void updateIsNoopForAbsentKey() {
        // Should not throw
        cache.update("NONEXISTENT", "", "{\"data\":1}");
        assertThat(cache.size()).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private MerchantCacheEntity buildEntity(String merchantId, String description, String merchantName) {
        MerchantCacheEntity e = new MerchantCacheEntity();
        e.setMerchantId(merchantId);
        e.setDescription(description);
        e.setMerchantName(merchantName);
        e.setStatus("ENRICHED");
        e.setCreatedAt(OffsetDateTime.now());
        return e;
    }

    @Test
    @DisplayName("initialize loads all DB records into cache at startup")
    void initializeLoadsDatabaseRecords() {
        MerchantCacheEntity entity = new MerchantCacheEntity();
        entity.setMerchantId("mid-db");
        entity.setDescription("TARGET");
        entity.setMerchantName("Target");
        entity.setPlaidResponse("{\"merchant_name\":\"Target\"}");
        entity.setStatus("ENRICHED");
        entity.setCreatedAt(OffsetDateTime.now());

        when(repository.findAll()).thenReturn(List.of(entity));
        EnrichCacheProperties props = new EnrichCacheProperties();
        props.setMaxSize(100);
        MerchantMemoryCache freshCache = new MerchantMemoryCache(props, repository);
        freshCache.initialize();

        assertThat(freshCache.size()).isEqualTo(1);
        Optional<MerchantCacheEntry> loaded = freshCache.get("TARGET", "Target");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().merchantId()).isEqualTo("mid-db");
        assertThat(loaded.get().status()).isEqualTo("ENRICHED");
    }

    @Test
    @DisplayName("getOrCreate with full cache invokes supplier but does not cache result")
    void getOrCreateWhenFullDoesNotCache() {
        EnrichCacheProperties props = new EnrichCacheProperties();
        props.setMaxSize(1);
        when(repository.findAll()).thenReturn(List.of());
        MerchantMemoryCache tinyCache = new MerchantMemoryCache(props, repository);
        tinyCache.initialize();

        // Fill the single slot
        tinyCache.getOrCreate("SLOT_A", "", () ->
                new MerchantCacheEntry("mid-a", "SLOT_A", "", null, "PENDING"));
        assertThat(tinyCache.size()).isEqualTo(1);

        // Second entry should not be cached
        MerchantMemoryCache.GetOrCreateResult result = tinyCache.getOrCreate("SLOT_B", "", () ->
                new MerchantCacheEntry("mid-b", "SLOT_B", "", null, "PENDING"));
        assertThat(result.created()).isTrue();
        assertThat(result.entry().merchantId()).isEqualTo("mid-b");
        assertThat(tinyCache.size()).isEqualTo(1); // still 1 — SLOT_B was not inserted
    }

    @Test
    @DisplayName("buildKey handles null description and null merchantName")
    void buildKeyHandlesNulls() {
        assertThat(MerchantMemoryCache.buildKey(null, "Starbucks")).isEqualTo("|starbucks");
        assertThat(MerchantMemoryCache.buildKey("STARBUCKS", null)).isEqualTo("starbucks|");
    }

    @Test
    @DisplayName("initialize stops loading when DB record count exceeds maxSize")
    void initializeTruncatesAtMaxSize() {
        MerchantCacheEntity e1 = buildEntity("mid-1", "DESC_A", "Name A");
        MerchantCacheEntity e2 = buildEntity("mid-2", "DESC_B", "Name B");
        when(repository.findAll()).thenReturn(List.of(e1, e2));

        EnrichCacheProperties props = new EnrichCacheProperties();
        props.setMaxSize(1);
        MerchantMemoryCache tinyCache = new MerchantMemoryCache(props, repository);
        tinyCache.initialize();

        assertThat(tinyCache.size()).isEqualTo(1); // second record skipped
    }

    @Test
    @DisplayName("put skips insertion when cache is already at maxSize")
    void putSkipsInsertWhenFull() {
        EnrichCacheProperties props = new EnrichCacheProperties();
        props.setMaxSize(1);
        when(repository.findAll()).thenReturn(List.of());
        MerchantMemoryCache tinyCache = new MerchantMemoryCache(props, repository);
        tinyCache.initialize();

        tinyCache.put(new MerchantCacheEntry("mid-1", "FIRST", "First", null, "PENDING"));
        assertThat(tinyCache.size()).isEqualTo(1);

        tinyCache.put(new MerchantCacheEntry("mid-2", "SECOND", "Second", null, "PENDING"));
        assertThat(tinyCache.size()).isEqualTo(1); // second put was ignored
    }

    @Test
    @DisplayName("getOrCreate supplier is called exactly once under concurrent access for the same key")
    void getOrCreateIsAtomicUnderConcurrency() throws InterruptedException {
        int threads = 20;
        AtomicInteger supplierCallCount = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException ignored) {}
                cache.getOrCreate("CONCURRENT", "Test", () -> {
                    supplierCallCount.incrementAndGet();
                    return new MerchantCacheEntry("mid-c", "CONCURRENT", "Test", null, "PENDING");
                });
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(supplierCallCount.get()).isEqualTo(1);
        assertThat(cache.size()).isEqualTo(1);
    }
}
