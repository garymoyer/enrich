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

    private static final EnrichmentQueueProcessor.EnrichmentTask SAMPLE_TASK =
            new EnrichmentQueueProcessor.EnrichmentTask(
                    "mid-001", "STARBUCKS COFFEE", "Starbucks",
                    new BigDecimal("5.75"), LocalDate.of(2026, 1, 30), "acc_123"
            );

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        // TransactionTemplate calls getTransaction(), then the callback, then commit().
        // Returning a mock TransactionStatus is sufficient; commit/rollback are no-op by default.
        // Lenient: tests that don't trigger a Plaid update won't invoke getTransaction().
        lenient().when(transactionManager.getTransaction(any())).thenReturn(txStatus);

        EnrichCacheProperties props = new EnrichCacheProperties();
        props.setQueueCapacity(10);
        props.setWorkerThreads(1);

        processor = new EnrichmentQueueProcessor(
                props, plaidApiClient, merchantCacheRepository,
                memoryCache, objectMapper, transactionManager);
        processor.start();
    }

    @Test
    @DisplayName("enqueue accepts task and returns true")
    void enqueueAcceptsTask() {
        boolean accepted = processor.enqueue(SAMPLE_TASK);
        assertThat(accepted).isTrue();
    }

    @Test
    @DisplayName("enqueue returns false when queue is full")
    void enqueueReturnsFalseWhenFull() {
        EnrichCacheProperties props = new EnrichCacheProperties();
        props.setQueueCapacity(1);
        props.setWorkerThreads(0); // no workers — queue won't drain
        EnrichmentQueueProcessor tinyProcessor = new EnrichmentQueueProcessor(
                props, plaidApiClient, merchantCacheRepository,
                memoryCache, objectMapper, transactionManager);
        // Do NOT call start() — no workers to drain the queue

        assertThat(tinyProcessor.enqueue(SAMPLE_TASK)).isTrue();  // first fills the queue
        assertThat(tinyProcessor.enqueue(SAMPLE_TASK)).isFalse(); // second is rejected
    }

    @Test
    @DisplayName("worker calls Plaid, updates DB record, and refreshes memory cache")
    void workerCallsPlaidAndUpdatesDbAndCache() throws Exception {
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

        // Wait for the background worker to process the task
        Thread.sleep(500);

        // DB updated
        ArgumentCaptor<MerchantCacheEntity> entityCaptor =
                ArgumentCaptor.forClass(MerchantCacheEntity.class);
        verify(merchantCacheRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getStatus()).isEqualTo("ENRICHED");
        assertThat(entityCaptor.getValue().getPlaidResponse()).contains("Starbucks Coffee");

        // Memory cache updated
        verify(memoryCache).update(
                eq("STARBUCKS COFFEE"), eq("Starbucks"), contains("Starbucks Coffee"));

        // Plaid request was non-null and carried the correct description
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
        when(plaidApiClient.enrichTransactions(any()))
                .thenReturn(Mono.error(new PlaidApiException("Plaid down", 503)));

        processor.enqueue(SAMPLE_TASK);
        Thread.sleep(500);

        // DB and cache not touched on failure
        verify(merchantCacheRepository, never()).save(any());
        verify(memoryCache, never()).update(any(), any(), any());

        // Worker is still alive — can accept a new task
        assertThat(processor.enqueue(SAMPLE_TASK)).isTrue();
    }

    @Test
    @DisplayName("empty Plaid response is handled gracefully without updating DB or cache")
    void emptyPlaidResponseHandledGracefully() throws Exception {
        when(plaidApiClient.enrichTransactions(any()))
                .thenReturn(Mono.just(new PlaidEnrichResponse(List.of(), "req_empty")));

        processor.enqueue(SAMPLE_TASK);
        Thread.sleep(500);

        verify(merchantCacheRepository, never()).save(any());
        verify(memoryCache, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("null response from Mono.empty() is handled gracefully without updating DB or cache")
    void nullResponseFromMonoEmptyHandledGracefully() throws Exception {
        when(plaidApiClient.enrichTransactions(any()))
                .thenReturn(Mono.<PlaidEnrichResponse>empty());

        processor.enqueue(SAMPLE_TASK);
        Thread.sleep(500);

        verify(merchantCacheRepository, never()).save(any());
        verify(memoryCache, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("queueSize reflects pending task count")
    void queueSizeReflectsPendingTasks() {
        // Workers are running, but Plaid is not mocked — tasks will fail quickly
        // We just test the counter
        EnrichCacheProperties props = new EnrichCacheProperties();
        props.setQueueCapacity(100);
        props.setWorkerThreads(0); // no workers — tasks stay queued
        EnrichmentQueueProcessor idleProcessor = new EnrichmentQueueProcessor(
                props, plaidApiClient, merchantCacheRepository,
                memoryCache, objectMapper, transactionManager);

        assertThat(idleProcessor.queueSize()).isZero();
        idleProcessor.enqueue(SAMPLE_TASK);
        assertThat(idleProcessor.queueSize()).isEqualTo(1);
        idleProcessor.enqueue(SAMPLE_TASK);
        assertThat(idleProcessor.queueSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("shutdown waits for in-flight tasks to complete")
    void shutdownDrainsInFlightTasks() throws Exception {
        processor.enqueue(SAMPLE_TASK);
        // Small delay to allow task to start
        Thread.sleep(100);
        processor.shutdown();
        // After shutdown, workers have stopped
        assertThat(processor.queueSize()).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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
