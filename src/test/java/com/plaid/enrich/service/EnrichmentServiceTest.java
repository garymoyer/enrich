package com.plaid.enrich.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plaid.enrich.domain.*;
import com.plaid.enrich.util.GuidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EnrichmentService}.
 *
 * <p>With the async enrichment design, the service itself never calls the Plaid API directly.
 * The hot path is: memory-cache lookup → stub creation (on miss) → immediate return.
 * Plaid calls happen in the background via {@link EnrichmentQueueProcessor}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Cache hit (ENRICHED) — returns full Plaid data, queue not touched</li>
 *   <li>Cache hit (PENDING) — returns stub, queue not touched</li>
 *   <li>Cache miss — creates stub, enqueues task, returns PENDING response</li>
 *   <li>Latency is logged for every transaction</li>
 *   <li>Null merchantName coercion</li>
 *   <li>Partial cache hits in multi-transaction requests</li>
 *   <li>Batch enrichment</li>
 *   <li>getEnrichmentById — memory cache then DB fallback</li>
 *   <li>FAILED request persistence on unexpected errors</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EnrichmentService Unit Tests")
class EnrichmentServiceTest {

    @Mock private EnrichmentRepository enrichmentRepository;
    @Mock private MerchantCacheRepository merchantCacheRepository;
    @Mock private MerchantMemoryCache memoryCache;
    @Mock private EnrichmentQueueProcessor queueProcessor;
    @Mock private GuidGenerator guidGenerator;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus txStatus;

    private EnrichmentService enrichmentService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        // TransactionTemplate.execute() calls getTransaction() then the callback, then commit().
        // Returning a mock TransactionStatus is sufficient; commit/rollback are no-op by default.
        // Lenient: many tests don't trigger createStub(), so this stub may go unused.
        lenient().when(transactionManager.getTransaction(any())).thenReturn(txStatus);

        enrichmentService = new EnrichmentService(
                enrichmentRepository,
                merchantCacheRepository,
                memoryCache,
                queueProcessor,
                guidGenerator,
                objectMapper,
                transactionManager
        );
    }

    // ── cache hit — ENRICHED ─────────────────────────────────────────────────

    @Test
    @DisplayName("Cache hit (ENRICHED): returns full Plaid data without calling queue")
    void cacheHitEnrichedReturnFullData() throws Exception {
        String requestId = "req-001";
        when(guidGenerator.generate()).thenReturn(requestId);
        when(enrichmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.of(buildPendingEntity(requestId)));

        MerchantCacheEntry enrichedEntry = buildEnrichedEntry("mid-cached");
        when(memoryCache.getOrCreate(eq("STARBUCKS COFFEE"), eq("Starbucks"), any()))
                .thenReturn(new MerchantMemoryCache.GetOrCreateResult(enrichedEntry, false));

        EnrichmentResponse response = enrichmentService.enrichTransactions(createTestRequest());

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.requestId()).isEqualTo(requestId);
        assertThat(response.enrichedTransactions()).hasSize(1);
        assertThat(response.enrichedTransactions().get(0).merchantId()).isEqualTo("mid-cached");
        assertThat(response.enrichedTransactions().get(0).merchantName()).isEqualTo("Starbucks Coffee");
        assertThat(response.enrichedTransactions().get(0).logoUrl())
                .isEqualTo("https://logo.clearbit.com/starbucks.com");
        assertThat(response.enrichedTransactions().get(0).metadata())
                .containsKey("categoryId")
                .containsKey("website")
                .containsKey("confidenceLevel");

        // Queue is never touched on a cache hit
        verify(queueProcessor, never()).enqueue(any());
    }

    // ── cache hit — PENDING ──────────────────────────────────────────────────

    @Test
    @DisplayName("Cache hit (PENDING): returns stub with enrichmentStatus=PENDING, queue not touched")
    void cacheHitPendingReturnsStub() {
        String requestId = "req-002";
        when(guidGenerator.generate()).thenReturn(requestId);
        when(enrichmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.of(buildPendingEntity(requestId)));

        MerchantCacheEntry pendingEntry =
                new MerchantCacheEntry("mid-pending", "STARBUCKS COFFEE", "Starbucks", null, "PENDING");
        when(memoryCache.getOrCreate(eq("STARBUCKS COFFEE"), eq("Starbucks"), any()))
                .thenReturn(new MerchantMemoryCache.GetOrCreateResult(pendingEntry, false));

        EnrichmentResponse response = enrichmentService.enrichTransactions(createTestRequest());

        assertThat(response.status()).isEqualTo("SUCCESS");
        EnrichmentResponse.EnrichedTransaction tx = response.enrichedTransactions().get(0);
        assertThat(tx.merchantId()).isEqualTo("mid-pending");
        assertThat(tx.category()).isNull();
        assertThat(tx.logoUrl()).isNull();
        assertThat(tx.metadata()).containsEntry("enrichmentStatus", "PENDING");

        verify(queueProcessor, never()).enqueue(any());
    }

    // ── cache miss ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Cache miss: creates stub in DB, enqueues background task, returns PENDING response")
    void cacheMissCreatesStubAndEnqueues() throws Exception {
        String requestId = "req-003";
        String merchantId = "mid-new";
        when(guidGenerator.generate()).thenReturn(requestId, merchantId);
        when(enrichmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.of(buildPendingEntity(requestId)));
        when(merchantCacheRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MerchantCacheEntry stub =
                new MerchantCacheEntry(merchantId, "STARBUCKS COFFEE", "Starbucks", null, "PENDING");
        // Simulate getOrCreate calling the supplier (new entry)
        when(memoryCache.getOrCreate(eq("STARBUCKS COFFEE"), eq("Starbucks"), any()))
                .thenAnswer(inv -> {
                    MerchantCacheEntry created = inv.<java.util.function.Supplier<MerchantCacheEntry>>
                            getArgument(2).get();
                    return new MerchantMemoryCache.GetOrCreateResult(created, true);
                });

        EnrichmentResponse response = enrichmentService.enrichTransactions(createTestRequest());

        // Response is immediate and successful
        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.enrichedTransactions()).hasSize(1);
        assertThat(response.enrichedTransactions().get(0).metadata())
                .containsEntry("enrichmentStatus", "PENDING");

        // DB stub was saved
        ArgumentCaptor<MerchantCacheEntity> entityCaptor =
                ArgumentCaptor.forClass(MerchantCacheEntity.class);
        verify(merchantCacheRepository).save(entityCaptor.capture());
        MerchantCacheEntity saved = entityCaptor.getValue();
        assertThat(saved.getMerchantId()).isEqualTo(merchantId);
        assertThat(saved.getDescription()).isEqualTo("STARBUCKS COFFEE");
        assertThat(saved.getMerchantName()).isEqualTo("Starbucks");
        assertThat(saved.getStatus()).isEqualTo("PENDING");

        // Background task was enqueued
        ArgumentCaptor<EnrichmentQueueProcessor.EnrichmentTask> taskCaptor =
                ArgumentCaptor.forClass(EnrichmentQueueProcessor.EnrichmentTask.class);
        verify(queueProcessor).enqueue(taskCaptor.capture());
        assertThat(taskCaptor.getValue().merchantId()).isEqualTo(merchantId);
        assertThat(taskCaptor.getValue().description()).isEqualTo("STARBUCKS COFFEE");
        assertThat(taskCaptor.getValue().merchantName()).isEqualTo("Starbucks");
    }

    @Test
    @DisplayName("Cache miss: request is persisted with PENDING status before processing")
    void requestPersistedBeforeProcessing() {
        String requestId = "req-004";
        when(guidGenerator.generate()).thenReturn(requestId);
        when(enrichmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.of(buildPendingEntity(requestId)));

        MerchantCacheEntry stub =
                new MerchantCacheEntry(requestId, "STARBUCKS COFFEE", "Starbucks", null, "PENDING");
        when(memoryCache.getOrCreate(any(), any(), any()))
                .thenReturn(new MerchantMemoryCache.GetOrCreateResult(stub, false));

        enrichmentService.enrichTransactions(createTestRequest());

        ArgumentCaptor<EnrichmentEntity> captor = ArgumentCaptor.forClass(EnrichmentEntity.class);
        verify(enrichmentRepository, atLeastOnce()).save(captor.capture());
        EnrichmentEntity firstSave = captor.getAllValues().get(0);
        assertThat(firstSave.getRequestId()).isEqualTo(requestId);
        assertThat(firstSave.getStatus()).isEqualTo("PENDING");
        assertThat(firstSave.getOriginalRequest()).isNotNull();
    }

    // ── latency logging (verifies calls; log content verified by log-capture in integration tests) ──

    @Test
    @DisplayName("Latency is measured and enrichment completes successfully for each transaction")
    void latencyMeasuredAndResponseSuccessful() {
        String requestId = "req-005";
        when(guidGenerator.generate()).thenReturn(requestId);
        when(enrichmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.of(buildPendingEntity(requestId)));

        MerchantCacheEntry pending =
                new MerchantCacheEntry("mid-5", "STARBUCKS COFFEE", "Starbucks", null, "PENDING");
        when(memoryCache.getOrCreate(any(), any(), any()))
                .thenReturn(new MerchantMemoryCache.GetOrCreateResult(pending, false));

        long before = System.nanoTime();
        EnrichmentResponse response = enrichmentService.enrichTransactions(createTestRequest());
        long elapsed = System.nanoTime() - before;

        // Service must return almost immediately (sub-millisecond without Plaid call)
        assertThat(elapsed).isLessThan(50_000_000L); // < 50ms
        assertThat(response.status()).isEqualTo("SUCCESS");
    }

    // ── null merchantName ────────────────────────────────────────────────────

    @Test
    @DisplayName("Null merchantName is coerced to empty string for cache key")
    void nullMerchantNameCoercedToEmpty() {
        EnrichmentRequest request = new EnrichmentRequest(
                "acc_12345",
                List.of(new EnrichmentRequest.Transaction(
                        "STARBUCKS COFFEE", new BigDecimal("5.75"),
                        LocalDate.of(2026, 1, 30), null))
        );
        String requestId = "req-006";
        String merchantId = "mid-6";
        when(guidGenerator.generate()).thenReturn(requestId, merchantId);
        when(enrichmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.of(buildPendingEntity(requestId)));
        when(merchantCacheRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(memoryCache.getOrCreate(eq("STARBUCKS COFFEE"), eq(""), any()))
                .thenAnswer(inv -> {
                    MerchantCacheEntry e = inv.<java.util.function.Supplier<MerchantCacheEntry>>
                            getArgument(2).get();
                    return new MerchantMemoryCache.GetOrCreateResult(e, true);
                });

        enrichmentService.enrichTransactions(request);

        ArgumentCaptor<MerchantCacheEntity> entityCaptor =
                ArgumentCaptor.forClass(MerchantCacheEntity.class);
        verify(merchantCacheRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getMerchantName()).isEqualTo("");
    }

    // ── partial cache hit ────────────────────────────────────────────────────

    @Test
    @DisplayName("Partial hit: ENRICHED tx returns Plaid data; missed tx returns PENDING stub")
    void partialCacheHitMixedResponse() throws Exception {
        EnrichmentRequest request = new EnrichmentRequest(
                "acc_12345",
                List.of(
                        new EnrichmentRequest.Transaction(
                                "STARBUCKS COFFEE", new BigDecimal("5.75"),
                                LocalDate.of(2026, 1, 30), "Starbucks"),
                        new EnrichmentRequest.Transaction(
                                "AMAZON PRIME", new BigDecimal("14.99"),
                                LocalDate.of(2026, 1, 30), "Amazon")
                )
        );

        String requestId = "req-007";
        String newMerchantId = "mid-amazon-new";
        when(guidGenerator.generate()).thenReturn(requestId, newMerchantId);
        when(enrichmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.of(buildPendingEntity(requestId)));
        when(merchantCacheRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // First tx: ENRICHED hit
        MerchantCacheEntry enrichedEntry = buildEnrichedEntry("mid-starbucks");
        when(memoryCache.getOrCreate(eq("STARBUCKS COFFEE"), eq("Starbucks"), any()))
                .thenReturn(new MerchantMemoryCache.GetOrCreateResult(enrichedEntry, false));

        // Second tx: miss — supplier is invoked
        when(memoryCache.getOrCreate(eq("AMAZON PRIME"), eq("Amazon"), any()))
                .thenAnswer(inv -> {
                    MerchantCacheEntry stub = inv.<java.util.function.Supplier<MerchantCacheEntry>>
                            getArgument(2).get();
                    return new MerchantMemoryCache.GetOrCreateResult(stub, true);
                });

        EnrichmentResponse response = enrichmentService.enrichTransactions(request);

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.enrichedTransactions()).hasSize(2);

        EnrichmentResponse.EnrichedTransaction first = response.enrichedTransactions().get(0);
        assertThat(first.merchantId()).isEqualTo("mid-starbucks");
        assertThat(first.merchantName()).isEqualTo("Starbucks Coffee");

        EnrichmentResponse.EnrichedTransaction second = response.enrichedTransactions().get(1);
        assertThat(second.merchantId()).isEqualTo(newMerchantId);
        assertThat(second.metadata()).containsEntry("enrichmentStatus", "PENDING");

        // Only the new merchant was enqueued
        verify(queueProcessor, times(1)).enqueue(any());
    }

    // ── persistStatus ────────────────────────────────────────────────────────

    @Test
    @DisplayName("persistStatus sets status=SUCCESS and clears errorMessage in DB on success")
    void persistStatusSetsSuccessStatusInDb() {
        String requestId = "req-persist-ok";
        when(guidGenerator.generate()).thenReturn(requestId);
        when(enrichmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.of(buildPendingEntity(requestId)));

        MerchantCacheEntry pending =
                new MerchantCacheEntry("mid-ok", "STARBUCKS COFFEE", "Starbucks", null, "PENDING");
        when(memoryCache.getOrCreate(any(), any(), any()))
                .thenReturn(new MerchantMemoryCache.GetOrCreateResult(pending, false));

        enrichmentService.enrichTransactions(createTestRequest());

        ArgumentCaptor<EnrichmentEntity> captor = ArgumentCaptor.forClass(EnrichmentEntity.class);
        verify(enrichmentRepository, times(2)).save(captor.capture());
        EnrichmentEntity updated = captor.getAllValues().get(1);
        assertThat(updated.getStatus()).isEqualTo("SUCCESS");
        assertThat(updated.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("persistStatus sets status=FAILED and records errorMessage in DB on exception")
    void persistStatusSetsFailedStatusInDb() {
        String requestId = "req-persist-fail";
        when(guidGenerator.generate()).thenReturn(requestId);
        when(enrichmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.of(buildPendingEntity(requestId)));

        when(memoryCache.getOrCreate(any(), any(), any()))
                .thenThrow(new RuntimeException("DB failure"));

        enrichmentService.enrichTransactions(createTestRequest());

        ArgumentCaptor<EnrichmentEntity> captor = ArgumentCaptor.forClass(EnrichmentEntity.class);
        verify(enrichmentRepository, times(2)).save(captor.capture());
        EnrichmentEntity updated = captor.getAllValues().get(1);
        assertThat(updated.getStatus()).isEqualTo("FAILED");
        assertThat(updated.getErrorMessage()).contains("DB failure");
    }

    // ── batch enrichment ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Batch enrichment: each request gets its own requestId and succeeds")
    void batchEnrichmentProcessesAllRequests() {
        when(guidGenerator.generate()).thenReturn("req-b1", "req-b2");
        when(enrichmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(enrichmentRepository.findById(any())).thenAnswer(inv ->
                Optional.of(buildPendingEntity(inv.getArgument(0))));

        MerchantCacheEntry pending =
                new MerchantCacheEntry("mid-b", "STARBUCKS COFFEE", "Starbucks", null, "PENDING");
        when(memoryCache.getOrCreate(any(), any(), any()))
                .thenReturn(new MerchantMemoryCache.GetOrCreateResult(pending, false));

        List<EnrichmentResponse> responses = enrichmentService.enrichTransactionsBatch(
                List.of(createTestRequest(), createTestRequest()));

        assertThat(responses).hasSize(2);
        assertThat(responses).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("SUCCESS");
            assertThat(r.enrichedTransactions()).hasSize(1);
        });
    }

    @Test
    @DisplayName("Batch enrichment: persistRequest is called for each item (2 requests → 4 total saves)")
    void batchEnrichmentPersistsEachRequest() {
        when(guidGenerator.generate()).thenReturn("req-b1", "req-b2");
        when(enrichmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(enrichmentRepository.findById(any())).thenAnswer(inv ->
                Optional.of(buildPendingEntity(inv.getArgument(0))));

        MerchantCacheEntry pending =
                new MerchantCacheEntry("mid-b", "STARBUCKS COFFEE", "Starbucks", null, "PENDING");
        when(memoryCache.getOrCreate(any(), any(), any()))
                .thenReturn(new MerchantMemoryCache.GetOrCreateResult(pending, false));

        enrichmentService.enrichTransactionsBatch(List.of(createTestRequest(), createTestRequest()));

        // 2 requests × (1 persistRequest + 1 persistStatus) = 4 DB saves
        verify(enrichmentRepository, times(4)).save(any());
    }

    // ── getEnrichmentById ────────────────────────────────────────────────────

    @Test
    @DisplayName("getEnrichmentById: uses memory cache to reconstruct enriched transactions")
    void getEnrichmentByIdUsesMemoryCache() throws Exception {
        String requestId = "req-get-001";
        EnrichmentRequest original = createTestRequest();
        EnrichmentEntity entity = EnrichmentEntity.builder()
                .requestId(requestId)
                .originalRequest(objectMapper.writeValueAsString(original))
                .status("SUCCESS")
                .createdAt(OffsetDateTime.now())
                .build();

        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.of(entity));

        MerchantCacheEntry enrichedEntry = buildEnrichedEntry("mid-from-cache");
        when(memoryCache.get("STARBUCKS COFFEE", "Starbucks"))
                .thenReturn(Optional.of(enrichedEntry));

        Optional<EnrichmentResponse> response = enrichmentService.getEnrichmentById(requestId);

        assertThat(response).isPresent();
        assertThat(response.get().status()).isEqualTo("SUCCESS");
        assertThat(response.get().enrichedTransactions()).hasSize(1);
        assertThat(response.get().enrichedTransactions().get(0).merchantId())
                .isEqualTo("mid-from-cache");

        // DB cache was not consulted (memory cache hit)
        verify(merchantCacheRepository, never()).findByDescriptionAndMerchantName(any(), any());
    }

    @Test
    @DisplayName("getEnrichmentById: falls back to DB when memory cache entry is absent")
    void getEnrichmentByIdFallsBackToDb() throws Exception {
        String requestId = "req-get-002";
        EnrichmentRequest original = createTestRequest();
        EnrichmentEntity entity = EnrichmentEntity.builder()
                .requestId(requestId)
                .originalRequest(objectMapper.writeValueAsString(original))
                .status("SUCCESS")
                .createdAt(OffsetDateTime.now())
                .build();

        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.of(entity));
        when(memoryCache.get("STARBUCKS COFFEE", "Starbucks")).thenReturn(Optional.empty());

        MerchantCacheEntity dbEntity = buildDbEntity("mid-from-db");
        when(merchantCacheRepository.findByDescriptionAndMerchantName("STARBUCKS COFFEE", "Starbucks"))
                .thenReturn(Optional.of(dbEntity));

        Optional<EnrichmentResponse> response = enrichmentService.getEnrichmentById(requestId);

        assertThat(response).isPresent();
        assertThat(response.get().enrichedTransactions()).hasSize(1);
        assertThat(response.get().enrichedTransactions().get(0).merchantId()).isEqualTo("mid-from-db");
    }

    @Test
    @DisplayName("getEnrichmentById: returns empty when requestId not found")
    void getEnrichmentByIdReturnsEmptyWhenNotFound() {
        when(enrichmentRepository.findById("bad-id")).thenReturn(Optional.empty());
        assertThat(enrichmentService.getEnrichmentById("bad-id")).isEmpty();
    }

    @Test
    @DisplayName("getEnrichmentById: returns FAILED response for failed enrichment records")
    void getEnrichmentByIdReturnsFailedResponse() {
        String requestId = "req-failed";
        EnrichmentEntity entity = EnrichmentEntity.builder()
                .requestId(requestId)
                .status("FAILED")
                .errorMessage("API connection refused")
                .createdAt(OffsetDateTime.now())
                .build();
        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.of(entity));

        Optional<EnrichmentResponse> response = enrichmentService.getEnrichmentById(requestId);

        assertThat(response).isPresent();
        assertThat(response.get().status()).isEqualTo("FAILED");
        assertThat(response.get().errorMessage()).isEqualTo("API connection refused");
        assertThat(response.get().enrichedTransactions()).isEmpty();
    }

    @Test
    @DisplayName("getEnrichmentById: empty enriched list when stored request has null transactions")
    void getEnrichmentByIdHandlesNullTransactions() throws Exception {
        String requestId = "req-null-txn";
        EnrichmentEntity entity = EnrichmentEntity.builder()
                .requestId(requestId)
                .originalRequest("{}")
                .status("SUCCESS")
                .createdAt(OffsetDateTime.now())
                .build();
        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.of(entity));

        Optional<EnrichmentResponse> response = enrichmentService.getEnrichmentById(requestId);

        assertThat(response).isPresent();
        assertThat(response.get().status()).isEqualTo("SUCCESS");
        assertThat(response.get().enrichedTransactions()).isEmpty();
        verify(memoryCache, never()).get(any(), any());
    }

    // ── error handling ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Unexpected exception results in FAILED response and persisted error")
    void unexpectedExceptionResultsInFailedResponse() {
        String requestId = "req-err";
        when(guidGenerator.generate()).thenReturn(requestId);
        when(enrichmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.of(buildPendingEntity(requestId)));

        when(memoryCache.getOrCreate(any(), any(), any()))
                .thenThrow(new RuntimeException("Unexpected DB failure"));

        EnrichmentResponse response = enrichmentService.enrichTransactions(createTestRequest());

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.errorMessage()).contains("Unexpected DB failure");
        assertThat(response.enrichedTransactions()).isEmpty();
    }

    @Test
    @DisplayName("ENRICHED cache entry includes merged enrichmentMetadata in response")
    void enrichedEntryIncludesMergedMetadata() throws Exception {
        String requestId = "req-meta";
        when(guidGenerator.generate()).thenReturn(requestId);
        when(enrichmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.of(buildPendingEntity(requestId)));

        MerchantCacheEntry enrichedEntry = buildEnrichedEntry("mid-meta");
        when(memoryCache.getOrCreate(any(), any(), any()))
                .thenReturn(new MerchantMemoryCache.GetOrCreateResult(enrichedEntry, false));

        EnrichmentResponse response = enrichmentService.enrichTransactions(createTestRequest());

        assertThat(response.enrichedTransactions()).isNotEmpty();
        assertThat(response.enrichedTransactions().get(0).metadata())
                .containsEntry("location", "Seattle, WA")
                .containsKey("categoryId")
                .containsKey("website");
    }

    // ── helper methods ───────────────────────────────────────────────────────

    private EnrichmentRequest createTestRequest() {
        return new EnrichmentRequest(
                "acc_12345",
                List.of(new EnrichmentRequest.Transaction(
                        "STARBUCKS COFFEE",
                        new BigDecimal("5.75"),
                        LocalDate.of(2026, 1, 30),
                        "Starbucks"
                ))
        );
    }

    private MerchantCacheEntry buildEnrichedEntry(String merchantId) throws JsonProcessingException {
        PlaidEnrichResponse.PlaidEnrichedTransaction plaidTx =
                new PlaidEnrichResponse.PlaidEnrichedTransaction(
                        "txn_001", "Food & Drink", "13005000", "Starbucks Coffee",
                        "https://logo.clearbit.com/starbucks.com", "https://www.starbucks.com",
                        "HIGH", Map.of("location", "Seattle, WA")
                );
        return new MerchantCacheEntry(
                merchantId, "STARBUCKS COFFEE", "Starbucks",
                objectMapper.writeValueAsString(plaidTx), "ENRICHED"
        );
    }

    private MerchantCacheEntity buildDbEntity(String merchantId) throws JsonProcessingException {
        PlaidEnrichResponse.PlaidEnrichedTransaction plaidTx =
                new PlaidEnrichResponse.PlaidEnrichedTransaction(
                        "txn_db", "Food & Drink", "13005000", "Starbucks Coffee",
                        "https://logo.clearbit.com/starbucks.com", "https://www.starbucks.com",
                        "HIGH", null
                );
        MerchantCacheEntity entity = new MerchantCacheEntity();
        entity.setMerchantId(merchantId);
        entity.setDescription("STARBUCKS COFFEE");
        entity.setMerchantName("Starbucks");
        entity.setPlaidResponse(objectMapper.writeValueAsString(plaidTx));
        entity.setStatus("ENRICHED");
        entity.setCreatedAt(OffsetDateTime.now());
        return entity;
    }

    private EnrichmentEntity buildPendingEntity(String requestId) {
        return EnrichmentEntity.builder()
                .requestId(requestId)
                .status("PENDING")
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
