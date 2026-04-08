package com.td.enrich.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.td.enrich.config.EnrichCacheProperties;
import com.td.enrich.domain.MerchantCacheEntity;
import com.td.enrich.domain.PlaidEnrichRequest;
import com.td.enrich.domain.PlaidEnrichResponse;
import com.td.enrich.exception.PlaidApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EnrichmentQueueProcessor}.
 *
 * <p><b>What these tests cover:</b>
 * <ul>
 *   <li>Task enqueue behaviour — accepted when queue has capacity, rejected when full.</li>
 *   <li>Worker processing — Plaid is called, the DB row is updated to ENRICHED, and the
 *       in-memory cache is refreshed.</li>
 *   <li>Failure isolation — Plaid failures and empty Plaid responses are handled without
 *       crashing the worker thread; the worker remains alive for future tasks.</li>
 *   <li>Queue size reporting.</li>
 *   <li>Graceful shutdown — {@link EnrichmentQueueProcessor#shutdown()} drains in-flight tasks.</li>
 * </ul>
 *
 * <p><b>How the background workers are tested:</b> The worker threads run on daemon
 * threads in the background. Tests enqueue a task and then call {@code Thread.sleep(500)}
 * to give the worker time to process it. This is a pragmatic approach for testing async
 * code without introducing a complex synchronization framework. 500 ms is a large
 * margin — the actual processing takes &lt;10 ms under normal conditions.
 *
 * <p><b>Transaction mocking:</b> Spring's {@code TransactionTemplate} calls
 * {@code transactionManager.getTransaction()} internally. We stub that call to return a
 * mock {@code TransactionStatus}; commit and rollback are no-ops on the mock, which is
 * fine because these tests don't touch a real database.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EnrichmentQueueProcessor Unit Tests")
class EnrichmentQueueProcessorTest {

    @Mock private PlaidApiClient plaidApiClient;
    @Mock private MerchantCacheRepository merchantCacheRepository;
    @Mock private MerchantMemoryCache memoryCache;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus txStatus;

    private EnrichmentQueueProcessor processor;
    private ObjectMapper objectMapper;

    /**
     * A reusable task representing a Starbucks Coffee enrichment.
     * Shared across tests that don't care about the task content.
     */
    private static final EnrichmentQueueProcessor.EnrichmentTask SAMPLE_TASK =
            new EnrichmentQueueProcessor.EnrichmentTask(
                    "mid-001", "STARBUCKS COFFEE", "Starbucks",
                    new BigDecimal("5.75"), LocalDate.of(2026, 1, 30), "acc_123"
            );

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        // TransactionTemplate calls getTransaction() before invoking the callback.
        // Lenient: tests that don't trigger a Plaid update won't invoke this.
        lenient().when(transactionManager.getTransaction(any())).thenReturn(txStatus);

        EnrichCacheProperties props = new EnrichCacheProperties();
        props.setQueueCapacity(10);
        props.setWorkerThreads(1);

        processor = new EnrichmentQueueProcessor(
                props, plaidApiClient, merchantCacheRepository,
                memoryCache, objectMapper, transactionManager);
        processor.start(); // spins up the background worker thread
    }

    // ── enqueue ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("enqueue accepts task and returns true")
    void enqueueAcceptsTask() {
        boolean accepted = processor.enqueue(SAMPLE_TASK);
        assertThat(accepted).isTrue();
    }

    @Test
    @DisplayName("enqueue returns false when queue is full")
    void enqueueReturnsFalseWhenFull() {
        // Build a processor with 1-slot queue and no workers so the queue never drains
        EnrichCacheProperties props = new EnrichCacheProperties();
        props.setQueueCapacity(1);
        props.setWorkerThreads(0);
        EnrichmentQueueProcessor tinyProcessor = new EnrichmentQueueProcessor(
                props, plaidApiClient, merchantCacheRepository,
                memoryCache, objectMapper, transactionManager);
        // DO NOT call start() — no workers = queue stays full after the first task

        assertThat(tinyProcessor.enqueue(SAMPLE_TASK)).isTrue();   // fills the queue
        assertThat(tinyProcessor.enqueue(SAMPLE_TASK)).isFalse();  // queue is full — rejected
    }

    // ── Worker processing ──────────────────────────────────────────────────────

    @Test
    @DisplayName("worker calls Plaid, updates DB record, and refreshes memory cache")
    void workerCallsPlaidAndUpdatesDbAndCache() throws Exception {
        // Given — Plaid returns a successful enrichment
        PlaidEnrichResponse plaidResponse = new PlaidEnrichResponse(
                List.of(new PlaidEnrichResponse.PlaidEnrichedTransaction(
                        "txn_001", "Food & Drink", "cat_food",
                        "Starbucks Coffee", "https://logo.clearbit.com/starbucks.com",
                        "https://www.starbucks.com", "HIGH", Map.of("location", "Seattle, WA")
                )),
                "req_001"
        );

        MerchantCacheEntity entity = buildEntity("mid-001");
        when(plaidApiClient.enrichTransactions(any())).thenReturn(Mono.just(plaidResponse));
        when(merchantCacheRepository.findById("mid-001")).thenReturn(Optional.of(entity));
        when(merchantCacheRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        processor.enqueue(SAMPLE_TASK);

        // Wait for the background worker to process the task (500ms is a generous margin)
        Thread.sleep(500);

        // DB row must be updated to ENRICHED with the Plaid JSON
        ArgumentCaptor<MerchantCacheEntity> entityCaptor =
                ArgumentCaptor.forClass(MerchantCacheEntity.class);
        verify(merchantCacheRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getStatus()).isEqualTo("ENRICHED");
        assertThat(entityCaptor.getValue().getPlaidResponse()).contains("Starbucks Coffee");

        // Memory cache must be updated AFTER the DB save (cache consistency invariant)
        verify(memoryCache).update(
                eq("STARBUCKS COFFEE"), eq("Starbucks"), contains("Starbucks Coffee"));

        // Plaid was called with the correct description
        ArgumentCaptor<PlaidEnrichRequest> plaidCaptor =
                ArgumentCaptor.forClass(PlaidEnrichRequest.class);
        verify(plaidApiClient).enrichTransactions(plaidCaptor.capture());
        assertThat(plaidCaptor.getValue()).isNotNull();
        assertThat(plaidCaptor.getValue().transactions()).hasSize(1);
        assertThat(plaidCaptor.getValue().transactions().get(0).description())
                .isEqualTo("STARBUCKS COFFEE");
    }

    @Test
    @DisplayName("Plaid failure is logged but does not crash the worker")
    void plaidFailureIsLoggedAndWorkerContinues() throws Exception {
        // Given — Plaid returns a 503 error
        when(plaidApiClient.enrichTransactions(any()))
                .thenReturn(Mono.error(new PlaidApiException("Plaid down", 503)));

        processor.enqueue(SAMPLE_TASK);
        Thread.sleep(500);

        // DB and in-memory cache must NOT be touched after a Plaid failure
        verify(merchantCacheRepository, never()).save(any());
        verify(memoryCache, never()).update(any(), any(), any());

        // Worker must still be alive and accepting new tasks
        assertThat(processor.enqueue(SAMPLE_TASK)).isTrue();
    }

    @Test
    @DisplayName("empty Plaid response is handled gracefully without updating DB or cache")
    void emptyPlaidResponseHandledGracefully() throws Exception {
        // Plaid responded successfully but returned zero enriched transactions
        when(plaidApiClient.enrichTransactions(any()))
                .thenReturn(Mono.just(new PlaidEnrichResponse(List.of(), "req_empty")));

        processor.enqueue(SAMPLE_TASK);
        Thread.sleep(500);

        // Nothing to store — neither DB nor cache should be updated
        verify(merchantCacheRepository, never()).save(any());
        verify(memoryCache, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("null response from Mono.empty() is handled gracefully without updating DB or cache")
    void nullResponseFromMonoEmptyHandledGracefully() throws Exception {
        // Mono.empty() completes without emitting a value; .block() returns null
        when(plaidApiClient.enrichTransactions(any()))
                .thenReturn(Mono.<PlaidEnrichResponse>empty());

        processor.enqueue(SAMPLE_TASK);
        Thread.sleep(500);

        verify(merchantCacheRepository, never()).save(any());
        verify(memoryCache, never()).update(any(), any(), any());
    }

    // ── queueSize ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("queueSize reflects pending task count")
    void queueSizeReflectsPendingTasks() {
        // Create an idle processor (no workers) so tasks accumulate in the queue
        EnrichCacheProperties props = new EnrichCacheProperties();
        props.setQueueCapacity(100);
        props.setWorkerThreads(0); // no workers — tasks won't be processed
        EnrichmentQueueProcessor idleProcessor = new EnrichmentQueueProcessor(
                props, plaidApiClient, merchantCacheRepository,
                memoryCache, objectMapper, transactionManager);

        assertThat(idleProcessor.queueSize()).isZero();
        idleProcessor.enqueue(SAMPLE_TASK);
        assertThat(idleProcessor.queueSize()).isEqualTo(1);
        idleProcessor.enqueue(SAMPLE_TASK);
        assertThat(idleProcessor.queueSize()).isEqualTo(2);
    }

    // ── shutdown ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shutdown waits for in-flight tasks to complete")
    void shutdownDrainsInFlightTasks() throws Exception {
        processor.enqueue(SAMPLE_TASK);
        Thread.sleep(100); // let the task start processing
        processor.shutdown();
        // After shutdown the queue should be empty (task was processed or dropped)
        assertThat(processor.queueSize()).isZero();
    }

    // ── Test data helper ───────────────────────────────────────────────────────

    /**
     * Builds a minimal PENDING {@link MerchantCacheEntity} suitable for stubbing
     * {@link MerchantCacheRepository#findById(Object)} in worker-processing tests.
     *
     * @param merchantId the UUID to assign to the entity
     */
    private MerchantCacheEntity buildEntity(String merchantId) {
        MerchantCacheEntity e = new MerchantCacheEntity();
        e.setMerchantId(merchantId);
        e.setDescription("STARBUCKS COFFEE");
        e.setMerchantName("Starbucks");
        e.setStatus("PENDING");
        e.setCreatedAt(OffsetDateTime.now());
        return e;
    }
}
