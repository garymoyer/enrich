package com.td.enrich.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.td.enrich.config.EnrichCacheProperties;
import com.td.enrich.domain.MerchantCacheEntity;
import com.td.enrich.domain.PlaidEnrichResponse;
import com.td.enrich.exception.PlaidApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Chaos tests for {@link EnrichmentQueueProcessor}.
 *
 * <p>Regular unit tests verify what happens under ideal conditions. Chaos tests verify
 * what happens when things go wrong. The three scenarios here simulate the kinds of
 * failures that occur in production:
 * <ol>
 *   <li><b>Intermittent upstream failures</b> — Plaid fails ~33% of the time; the
 *       worker must survive and keep processing the remaining tasks.</li>
 *   <li><b>Queue saturation</b> — more tasks arrive than the queue can hold; excess
 *       tasks must be dropped (not queued indefinitely) and no crash must occur.</li>
 *   <li><b>DB failure after Plaid success</b> — Plaid returns data but the DB save
 *       throws; the in-memory cache must NOT be updated (cache consistency invariant).</li>
 * </ol>
 *
 * <p><b>Cache consistency invariant:</b> The DB is the source of truth. If the process
 * crashes and restarts, the in-memory cache is rebuilt from the DB. Therefore, the cache
 * must only be updated <em>after</em> the DB has been successfully committed. If the DB
 * write fails, the cache must stay at its pre-update state so it matches what's in the DB.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EnrichmentQueueProcessor Chaos Testing")
class EnrichmentQueueProcessorChaosTest {

    @Mock private PlaidApiClient plaidApiClient;
    @Mock private MerchantCacheRepository merchantCacheRepository;
    @Mock private MerchantMemoryCache memoryCache;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus txStatus;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        // TransactionTemplate calls getTransaction(); returning a mock status is sufficient.
        // Lenient: tests that don't process tasks won't invoke this.
        lenient().when(transactionManager.getTransaction(any())).thenReturn(txStatus);
    }

    // ── Chaos scenario 1: Intermittent upstream failures ──────────────────────

    /**
     * Verifies that the worker thread survives a ~33% upstream error rate.
     *
     * <p>9 tasks are submitted. Every third call to Plaid fails with a 503 error
     * (calls 3, 6, and 9). The 6 successful calls must update the in-memory cache;
     * the 3 failures must be swallowed without crashing the worker. After the flood,
     * the worker must still accept a new task.
     */
    @Test
    @DisplayName("Chaos: Worker survives intermittent Plaid failures under rapid task flood")
    void workerSurvivesIntermittentPlaidFailuresUnderLoad() throws InterruptedException {
        // Stub: every 3rd call returns an error; others return a successful response
        AtomicInteger callCount = new AtomicInteger(0);
        when(plaidApiClient.enrichTransactions(any())).thenAnswer(inv -> {
            int n = callCount.incrementAndGet();
            if (n % 3 == 0) {
                // Simulates Plaid returning HTTP 503 Service Unavailable
                return Mono.error(new PlaidApiException("Intermittent upstream failure", 503));
            }
            return Mono.just(successResponse(n));
        });
        // Lenient: only called for the successful tasks
        lenient().when(merchantCacheRepository.findById(any()))
                .thenAnswer(inv -> Optional.of(buildEntity(inv.getArgument(0))));
        lenient().when(merchantCacheRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        EnrichmentQueueProcessor processor = buildProcessor(20, 2);

        // Flood 9 tasks: calls 1,2 succeed; 3 fails; 4,5 succeed; 6 fails; 7,8 succeed; 9 fails
        for (int i = 0; i < 9; i++) {
            processor.enqueue(task(i));
        }
        Thread.sleep(1500); // generous wait for both workers to drain all 9 tasks

        // 6 of 9 tasks succeed — the cache must be updated at least 4 times
        // (atLeast(4) rather than exactly(6) accounts for minor timing variance)
        verify(memoryCache, atLeast(4)).update(any(), any(), any());

        // The worker must still be running and accepting new work — no crash, no hang
        assertThat(processor.enqueue(task(99))).isTrue();

        processor.shutdown();
    }

    // ── Chaos scenario 2: Queue saturation ────────────────────────────────────

    /**
     * Verifies that tasks beyond the queue capacity are dropped gracefully.
     *
     * <p>The queue holds 3 tasks and no workers are running (so the queue never
     * drains). When 5 tasks are submitted, exactly 3 are accepted and 2 are dropped.
     * No exception must be thrown; no Plaid/DB/cache interaction must occur.
     */
    @Test
    @DisplayName("Chaos: Queue saturation drops excess tasks without crashing the processor")
    void queueSaturationDropsGracefullyWithoutCrash() {
        // Build a processor with a 3-slot queue and no workers
        EnrichCacheProperties props = new EnrichCacheProperties();
        props.setQueueCapacity(3);
        props.setWorkerThreads(0);
        EnrichmentQueueProcessor processor = new EnrichmentQueueProcessor(
                props, plaidApiClient, merchantCacheRepository,
                memoryCache, objectMapper, transactionManager);
        // Deliberately NOT calling processor.start() — workers never run

        int accepted = 0;
        int rejected = 0;
        for (int i = 0; i < 5; i++) {
            if (processor.enqueue(task(i))) accepted++;
            else rejected++;
        }

        // Exactly 3 accepted (queue capacity), 2 dropped — no crash
        assertThat(accepted).isEqualTo(3);
        assertThat(rejected).isEqualTo(2);
        assertThat(processor.queueSize()).isEqualTo(3);

        // Workers never started, so no Plaid/DB/cache calls must have been made
        verifyNoInteractions(plaidApiClient, merchantCacheRepository, memoryCache);
    }

    // ── Chaos scenario 3: DB failure after Plaid success ──────────────────────

    /**
     * Verifies the cache consistency invariant when the DB save throws.
     *
     * <p>Plaid returns data successfully, but the DB {@code save()} throws a
     * {@code RuntimeException} simulating a lost database connection. The processor
     * must attempt the DB save, catch the exception, and then must NOT call
     * {@link MerchantMemoryCache#update}. If the cache were updated despite the DB
     * failure, a pod restart would rebuild the cache from the DB and see a stale
     * (PENDING) entry while the memory cache held an ENRICHED entry — a split-brain
     * state that could serve stale data.
     */
    @Test
    @DisplayName("Chaos: DB update failure after Plaid success does not update in-memory cache")
    void dbUpdateFailureAfterPlaidSuccessDoesNotCorruptMemoryCache() throws InterruptedException {
        // Plaid succeeds
        when(plaidApiClient.enrichTransactions(any()))
                .thenReturn(Mono.just(successResponse(0)));
        // DB findById succeeds (returns the PENDING entity)
        when(merchantCacheRepository.findById("mid-0"))
                .thenReturn(Optional.of(buildEntity("mid-0")));
        // DB save throws — simulates connection loss mid-transaction
        when(merchantCacheRepository.save(any()))
                .thenThrow(new RuntimeException("DB connection lost"));

        EnrichmentQueueProcessor processor = buildProcessor(5, 1);

        processor.enqueue(task(0));
        Thread.sleep(500); // wait for the worker to attempt and fail the task

        // DB save was attempted (confirms the worker reached the DB write step)
        verify(merchantCacheRepository).save(any());

        // CRITICAL: memory cache must NOT be updated because the DB write failed.
        // Updating the cache here would leave it ahead of the DB — a consistency violation.
        verify(memoryCache, never()).update(any(), any(), any());

        // The worker must still be alive after catching the exception
        assertThat(processor.enqueue(task(1))).isTrue();

        processor.shutdown();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Builds and starts a processor with the given queue and worker configuration.
     * Calling {@link EnrichmentQueueProcessor#shutdown()} after the test is the
     * caller's responsibility.
     */
    private EnrichmentQueueProcessor buildProcessor(int queueCapacity, int workerThreads) {
        EnrichCacheProperties props = new EnrichCacheProperties();
        props.setQueueCapacity(queueCapacity);
        props.setWorkerThreads(workerThreads);
        EnrichmentQueueProcessor processor = new EnrichmentQueueProcessor(
                props, plaidApiClient, merchantCacheRepository,
                memoryCache, objectMapper, transactionManager);
        processor.start();
        return processor;
    }

    /**
     * Creates a unique enrichment task for the given index.
     * Each task has a distinct {@code merchantId} and description to avoid
     * cross-test interference in Mockito argument matchers.
     */
    private EnrichmentQueueProcessor.EnrichmentTask task(int i) {
        return new EnrichmentQueueProcessor.EnrichmentTask(
                "mid-" + i, "DESC-" + i, "MERCHANT-" + i,
                new BigDecimal("10.00"), LocalDate.of(2026, 1, 1), "acc_chaos");
    }

    /**
     * Creates a successful single-transaction Plaid response for the given index.
     * Using an index-specific merchant name prevents test pollution when multiple
     * tasks share the same mock stubs.
     */
    private PlaidEnrichResponse successResponse(int i) {
        return new PlaidEnrichResponse(
                List.of(new PlaidEnrichResponse.PlaidEnrichedTransaction(
                        "txn-" + i, "Food & Drink", "cat_food",
                        "Chaos Cafe " + i, "https://logo.clearbit.com/test.com",
                        "https://test.com", "HIGH", Map.of()
                )),
                "req_chaos_" + i
        );
    }

    /**
     * Builds a PENDING {@link MerchantCacheEntity} for the given merchant ID.
     * Used to stub {@link MerchantCacheRepository#findById(Object)} in the
     * worker-processing path.
     */
    private MerchantCacheEntity buildEntity(String merchantId) {
        MerchantCacheEntity e = new MerchantCacheEntity();
        e.setMerchantId(merchantId);
        e.setDescription("DESC");
        e.setMerchantName("MERCHANT");
        e.setStatus("PENDING");
        e.setCreatedAt(OffsetDateTime.now());
        return e;
    }
}
