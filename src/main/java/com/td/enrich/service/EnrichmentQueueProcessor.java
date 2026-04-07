package com.td.enrich.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.td.enrich.config.EnrichCacheProperties;
import com.td.enrich.domain.PlaidEnrichRequest;
import com.td.enrich.domain.PlaidEnrichResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.*;

/**
 * Background processor for async Plaid merchant enrichment.
 *
 * <p>When {@link EnrichmentService} encounters a previously unseen (description, merchantName)
 * pair, it atomically creates a PENDING stub in both the DB and the in-memory cache, returns
 * the generated merchantId to the caller immediately, and submits an {@link EnrichmentTask} here.
 *
 * <p>This processor drains the queue using a configurable number of virtual-thread workers.
 * For each task it:
 * <ol>
 *   <li>Calls the Plaid Enrich API.</li>
 *   <li>Atomically updates the {@code merchant_cache} DB row to ENRICHED (via a
 *       {@link TransactionTemplate} so the DB change commits before the cache is touched).</li>
 *   <li>Updates the in-memory {@link MerchantMemoryCache} with the enriched data.</li>
 * </ol>
 *
 * <p>Plaid failures are logged and the task is dropped; the merchant stays PENDING in DB/cache
 * and will be re-enriched on the next request that needs it.
 */
@Component
@Slf4j
public class EnrichmentQueueProcessor {

    /**
     * Carries all data needed for a single Plaid enrichment call.
     * The merchantId is the pre-generated UUID already persisted as a PENDING stub.
     */
    public record EnrichmentTask(
            String merchantId,
            String description,
            String merchantName,
            BigDecimal amount,
            LocalDate date,
            String accountId
    ) {}

    private final BlockingQueue<EnrichmentTask> queue;
    private final ExecutorService workers;
    private final int workerCount;
    private final PlaidApiClient plaidApiClient;
    private final MerchantCacheRepository merchantCacheRepository;
    private final MerchantMemoryCache memoryCache;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;
    private volatile boolean running = true;

    public EnrichmentQueueProcessor(
            EnrichCacheProperties properties,
            PlaidApiClient plaidApiClient,
            MerchantCacheRepository merchantCacheRepository,
            MerchantMemoryCache memoryCache,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.workerCount = properties.getWorkerThreads();
        this.queue = new LinkedBlockingQueue<>(properties.getQueueCapacity());
        // Virtual threads: cheap, non-blocking wait on queue.poll() does not pin a carrier thread.
        // Pool size floored at 1 so the executor is always valid; start() submits workerCount tasks.
        this.workers = Executors.newFixedThreadPool(
                Math.max(1, workerCount),
                Thread.ofVirtual().name("enrich-worker-", 0).factory()
        );
        this.plaidApiClient = plaidApiClient;
        this.merchantCacheRepository = merchantCacheRepository;
        this.memoryCache = memoryCache;
        this.objectMapper = objectMapper;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /** Starts the background worker threads. Called automatically after Spring wiring. */
    @PostConstruct
    public void start() {
        log.info("Starting {} enrichment background workers (virtual threads)", workerCount);
        for (int i = 0; i < workerCount; i++) {
            workers.submit(this::processLoop);
        }
    }

    /**
     * Submits an enrichment task to the queue.
     *
     * @return true if accepted; false if the queue is full (task is dropped with a warning)
     */
    public boolean enqueue(EnrichmentTask task) {
        boolean accepted = queue.offer(task);
        if (!accepted) {
            log.warn("[QUEUE FULL] Dropping enrichment for merchantId={}; queueSize={}",
                    task.merchantId(), queue.size());
        }
        return accepted;
    }

    /** Returns the current number of tasks waiting in the queue. */
    public int queueSize() { return queue.size(); }

    // ── worker loop ─────────────────────────────────────────────────────────

    private void processLoop() {
        while (running || !queue.isEmpty()) {
            try {
                EnrichmentTask task = queue.poll(500, TimeUnit.MILLISECONDS);
                if (task != null) {
                    processTask(task);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Unexpected error in enrichment worker", e);
            }
        }
        log.debug("Enrichment worker exiting");
    }

    private void processTask(EnrichmentTask task) {
        long start = System.nanoTime();
        log.debug("[PLAID START] merchantId={} desc={}", task.merchantId(), task.description());

        try {
            PlaidEnrichResponse response =
                    plaidApiClient.enrichTransactions(buildPlaidRequest(task)).block();

            if (response == null || response.enrichedTransactions().isEmpty()) {
                log.warn("[PLAID EMPTY] merchantId={} — no enrichment data returned", task.merchantId());
                return;
            }

            String plaidJson = objectMapper.writeValueAsString(
                    response.enrichedTransactions().get(0));
            long plaidLatencyMs = (System.nanoTime() - start) / 1_000_000;

            // Update DB first (within its own transaction), then memory cache
            txTemplate.execute(status -> {
                merchantCacheRepository.findById(task.merchantId()).ifPresent(entity -> {
                    entity.setPlaidResponse(plaidJson);
                    entity.setStatus("ENRICHED");
                    merchantCacheRepository.save(entity);
                });
                return null;
            });
            memoryCache.update(task.description(), task.merchantName(), plaidJson);

            log.info("[PLAID ENRICHED] merchantId={} plaidLatencyMs={}", task.merchantId(), plaidLatencyMs);

        } catch (Exception e) {
            log.error("[PLAID FAILED] merchantId={} error={}", task.merchantId(), e.getMessage());
        }
    }

    private PlaidEnrichRequest buildPlaidRequest(EnrichmentTask task) {
        return new PlaidEnrichRequest(
                null, null,   // clientId/secret injected by PlaidApiClient
                task.accountId(),
                List.of(new PlaidEnrichRequest.PlaidTransaction(
                        task.description(),
                        task.amount(),
                        task.date(),
                        task.merchantName()
                ))
        );
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down enrichment queue processor (pending tasks: {})...", queue.size());
        running = false;
        workers.shutdown();
        try {
            if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Enrichment workers did not terminate in 30s; forcing shutdown");
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Enrichment queue processor shut down");
    }
}
