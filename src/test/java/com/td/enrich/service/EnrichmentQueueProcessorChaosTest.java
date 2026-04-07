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
 * <p>These tests inject adversarial conditions — intermittent upstream failures, queue
 * saturation, and downstream DB failures — to verify that the background enrichment
 * pipeline degrades gracefully and never leaves the in-memory cache in an inconsistent
 * state relative to the database.
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
        lenient().when(transactionManager.getTransaction(any())).thenReturn(txStatus);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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

    private EnrichmentQueueProcessor.EnrichmentTask task(int i) {
        return new EnrichmentQueueProcessor.EnrichmentTask(
                "mid-" + i, "DESC-" + i, "MERCHANT-" + i,
                new BigDecimal("10.00"), LocalDate.of(2026, 1, 1), "acc_chaos");
    }

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

    private MerchantCacheEntity buildEntity(String merchantId) {
        MerchantCacheEntity e = new MerchantCacheEntity();
        e.setMerchantId(merchantId);
        e.setDescription("DESC");
        e.setMerchantName("MERCHANT");
        e.setStatus("PENDING");
        e.setCreatedAt(OffsetDateTime.now());
        return e;
    }

    // ── chaos scenarios ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Chaos: Worker survives intermittent Plaid failures under rapid task flood")
    void workerSurvivesIntermittentPlaidFailuresUnderLoad() throws InterruptedException {
        // Every 3rd call fails — simulates ~33% upstream error rate
        AtomicInteger callCount = new AtomicInteger(0);
        when(plaidApiClient.enrichTransactions(any())).thenAnswer(inv -> {
            int n = callCount.incrementAndGet();
            if (n % 3 == 0) {
                return Mono.error(new PlaidApiException("Intermittent upstream failure", 503));
            }
            return Mono.just(successResponse(n));
        });
        lenient().when(merchantCacheRepository.findById(any()))
                .thenAnswer(inv -> Optional.of(buildEntity(inv.getArgument(0))));
        lenient().when(merchantCacheRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        EnrichmentQueueProcessor processor = buildProcessor(20, 2);

        // Flood 9 tasks: calls 1,2 succeed; 3 fails; 4,5 succeed; 6 fails; 7,8 succeed; 9 fails
        for (int i = 0; i < 9; i++) {
            processor.enqueue(task(i));
        }
        Thread.sleep(1500); // Allow workers to drain the queue

        // 6 of 9 tasks succeed — cache updated at least 4 times (allowing for timing variance)
        verify(memoryCache, atLeast(4)).update(any(), any(), any());

        // Worker is still alive and accepting new work after chaos
        assertThat(processor.enqueue(task(99))).isTrue();

        processor.shutdown();
    }

    @Test
    @DisplayName("Chaos: Queue saturation drops excess tasks without crashing the processor")
    void queueSaturationDropsGracefullyWithoutCrash() {
        // Tiny queue, no workers — queue will not drain during the test
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

        // Exactly 3 accepted (queue capacity), 2 dropped — no crash, no panic
        assertThat(accepted).isEqualTo(3);
        assertThat(rejected).isEqualTo(2);
        assertThat(processor.queueSize()).isEqualTo(3);

        // No Plaid calls or DB/cache writes — workers never started
        verifyNoInteractions(plaidApiClient, merchantCacheRepository, memoryCache);
    }

    @Test
    @DisplayName("Chaos: DB update failure after Plaid success does not update in-memory cache")
    void dbUpdateFailureAfterPlaidSuccessDoesNotCorruptMemoryCache() throws InterruptedException {
        // Plaid call succeeds, but DB save throws (simulates connection loss mid-transaction)
        when(plaidApiClient.enrichTransactions(any()))
                .thenReturn(Mono.just(successResponse(0)));
        when(merchantCacheRepository.findById("mid-0"))
                .thenReturn(Optional.of(buildEntity("mid-0")));
        when(merchantCacheRepository.save(any()))
                .thenThrow(new RuntimeException("DB connection lost"));

        EnrichmentQueueProcessor processor = buildProcessor(5, 1);

        processor.enqueue(task(0));
        Thread.sleep(500);

        // DB save was attempted
        verify(merchantCacheRepository).save(any());

        // Memory cache must NOT be updated — the DB commit failed, so writing to the cache
        // would leave it ahead of the persistent store and cause stale data on pod restart
        verify(memoryCache, never()).update(any(), any(), any());

        // Worker must still be alive after the exception
        assertThat(processor.enqueue(task(1))).isTrue();

        processor.shutdown();
    }
}
