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
 * Background worker pool that asynchronously enriches merchant data via the Plaid API.
 *
 * <p><b>Role in the system:</b> When a new (description, merchantName) pair is first
 * encountered, a PENDING stub is inserted into both the DB ({@code merchant_cache} table)
 * and the in-memory {@link MerchantMemoryCache}. The caller gets a {@code merchantId}
 * immediately and does not wait for Plaid. Instead, an {@link EnrichmentTask} is
 * submitted to this processor, which calls Plaid in the background and later fills in
 * the Plaid response data.
 *
 * <p><b>Architecture — producer / consumer:</b>
 * <ul>
 *   <li>Producers call {@link #enqueue(EnrichmentTask)} from request threads.</li>
 *   <li>A bounded {@link LinkedBlockingQueue} decouples producers from workers. If the
 *       queue is full, new tasks are dropped (not blocked) to protect request latency.
 *       A warning is logged for each dropped task.</li>
 *   <li>A fixed thread pool of daemon threads drains the queue. Thread count is
 *       configured via {@code enrich.cache.worker-threads}. Each thread runs
 *       {@link #processLoop()} until the processor shuts down.</li>
 * </ul>
 *
 * <p><b>Cache consistency invariant:</b> The DB row is always updated <em>before</em>
 * the in-memory cache entry. This is enforced by the ordering in {@link #processTask}:
 * the DB write runs inside a {@link TransactionTemplate} that commits synchronously;
 * only after a successful commit does {@link MerchantMemoryCache#update} run. If the
 * DB write throws, the in-memory cache is never touched — so on the next pod restart
 * the DB is still authoritative and no stale data leaks into the memory cache.
 *
 * <p><b>Failure handling:</b> If Plaid returns an error or if the DB update fails, the
 * exception is logged and the task is dropped. The merchant entry remains PENDING in
 * both the DB and in-memory cache. The next request for the same merchant will trigger
 * a new enrichment attempt.
 *
 * <p><b>Lifecycle:</b> {@link #start()} is called by Spring after all beans are wired
 * ({@code @PostConstruct}). {@link #shutdown()} is called when the Spring context closes
 * ({@code @PreDestroy}) — it signals workers to stop, waits up to 30 seconds for them
 * to finish in-flight tasks, then force-kills any stragglers.
 */
@Component
@Slf4j
public class EnrichmentQueueProcessor {

    /**
     * All data needed to make a single Plaid enrichment call for a new merchant.
     *
     * <p>This is a Java record — an immutable data class. The fields map directly to
     * what Plaid needs: the transaction's raw description, amount, date, and account ID.
     * The {@code merchantId} is the UUID already stored in the DB as a PENDING stub;
     * we carry it here so the worker knows which DB row to update with the Plaid result.
     */
    public record EnrichmentTask(
            /** The pre-generated UUID stored in {@code merchant_cache} as PENDING. */
            String merchantId,
            /** The raw transaction description (e.g. {@code "STARBUCKS COFFEE #1234"}). */
            String description,
            /** The merchant name hint from the transaction, or empty string if none. */
            String merchantName,
            /** The transaction amount (used by Plaid to improve enrichment accuracy). */
            BigDecimal amount,
            /** The transaction date. */
            LocalDate date,
            /** The bank account ID associated with this transaction. */
            String accountId
    ) {}

    /** The bounded queue that decouples request threads from background workers. */
    private final BlockingQueue<EnrichmentTask> queue;
    /** The fixed-size thread pool running the worker loops. */
    private final ExecutorService workers;
    /** How many worker threads were configured (used for logging). */
    private final int workerCount;
    private final PlaidApiClient plaidApiClient;
    private final MerchantCacheRepository merchantCacheRepository;
    private final MerchantMemoryCache memoryCache;
    private final ObjectMapper objectMapper;
    /** Wraps DB writes in their own transaction so we can commit before updating the cache. */
    private final TransactionTemplate txTemplate;
    /**
     * Flag that tells worker loops to stop accepting new tasks when {@code false}.
     * {@code volatile} ensures all threads see the updated value immediately.
     */
    private volatile boolean running = true;

    /**
     * Spring calls this constructor to wire all dependencies.
     *
     * <p>The thread pool uses daemon threads (daemon threads don't prevent the JVM
     * from exiting if they are still running when the main thread finishes). Each
     * thread is named {@code enrich-worker-N} so it's identifiable in thread dumps.
     *
     * @param properties         cache/queue configuration (queue capacity, worker count)
     * @param plaidApiClient     the HTTP client for calling Plaid
     * @param merchantCacheRepository JPA repository for the {@code merchant_cache} table
     * @param memoryCache        the in-memory merchant cache to update after enrichment
     * @param objectMapper       Jackson mapper for serializing Plaid responses to JSON
     * @param transactionManager Spring's transaction manager (used to create the
     *                           {@link TransactionTemplate} for DB writes)
     */
    public EnrichmentQueueProcessor(
            EnrichCacheProperties properties,
            PlaidApiClient plaidApiClient,
            MerchantCacheRepository merchantCacheRepository,
            MerchantMemoryCache memoryCache,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.workerCount = properties.getWorkerThreads();
        this.queue = new LinkedBlockingQueue<>(properties.getQueueCapacity());
        // AtomicInteger counter gives each thread a unique name (enrich-worker-0, enrich-worker-1, ...)
        java.util.concurrent.atomic.AtomicInteger workerIndex = new java.util.concurrent.atomic.AtomicInteger(0);
        this.workers = Executors.newFixedThreadPool(
                Math.max(1, workerCount),
                r -> {
                    Thread t = new Thread(r, "enrich-worker-" + workerIndex.getAndIncrement());
                    t.setDaemon(true); // daemon: doesn't block JVM shutdown
                    return t;
                }
        );
        this.plaidApiClient = plaidApiClient;
        this.merchantCacheRepository = merchantCacheRepository;
        this.memoryCache = memoryCache;
        this.objectMapper = objectMapper;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Starts the background worker threads.
     *
     * <p>Called automatically by Spring after this bean is fully constructed
     * ({@code @PostConstruct}). Each worker thread runs {@link #processLoop()}
     * in a loop until {@link #shutdown()} sets {@code running = false}.
     */
    @PostConstruct
    public void start() {
        log.info("Starting {} enrichment background workers", workerCount);
        for (int i = 0; i < workerCount; i++) {
            workers.submit(this::processLoop);
        }
    }

    /**
     * Adds an enrichment task to the queue.
     *
     * <p>Uses a non-blocking {@link BlockingQueue#offer} — if the queue is already at
     * capacity, the task is dropped immediately rather than making the calling
     * request thread wait. The merchant will remain in PENDING status in the DB/cache
     * and will be re-enriched next time a request encounters it.
     *
     * @param task the enrichment task to queue
     * @return {@code true} if the task was accepted; {@code false} if the queue was full
     *         and the task was dropped
     */
    public boolean enqueue(EnrichmentTask task) {
        boolean accepted = queue.offer(task);
        if (!accepted) {
            log.warn("[QUEUE FULL] Dropping enrichment for merchantId={}; queueSize={}",
                    task.merchantId(), queue.size());
        }
        return accepted;
    }

    /**
     * Returns the current number of tasks waiting in the queue.
     *
     * <p>Useful for monitoring: a persistently high queue size indicates the workers
     * are falling behind the inbound task rate. Consider increasing
     * {@code enrich.cache.worker-threads} if that happens.
     */
    public int queueSize() { return queue.size(); }

    // ── Worker loop ────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} while the worker loop should keep running.
     *
     * <p>The condition has two parts:
     * <ul>
     *   <li>{@code running} — {@code true} until {@link #shutdown()} sets it to
     *       {@code false}, meaning "we have not been asked to stop yet".</li>
     *   <li>{@code !queue.isEmpty()} — even after {@code running} is set to
     *       {@code false}, the loop continues until all already-queued tasks have
     *       been drained. This ensures in-flight work completes during a graceful
     *       shutdown rather than being silently dropped.</li>
     * </ul>
     *
     * <p>Extracted from {@link #processLoop} so the while-condition is self-documenting
     * and reduces that method's cyclomatic complexity by one decision point.
     *
     * @return {@code true} if the worker should poll for another task
     */
    private boolean shouldContinueProcessing() {
        return running || !queue.isEmpty();
    }

    /**
     * The main loop run by each worker thread.
     *
     * <p>The thread waits up to 500 milliseconds for the next task. The short
     * poll timeout lets {@link #shouldContinueProcessing()} be re-evaluated
     * frequently so the thread notices a shutdown signal quickly without busy-waiting.
     *
     * <p>Unexpected exceptions (besides {@link InterruptedException}) are caught and
     * logged at ERROR level. The worker then continues running rather than dying —
     * a crashed worker would reduce throughput silently, which is harder to diagnose.
     */
    private void processLoop() {
        while (shouldContinueProcessing()) {
            try {
                // Wait up to 500ms for a task; returns null if none arrives in time
                EnrichmentTask task = queue.poll(500, TimeUnit.MILLISECONDS);
                if (task != null) {
                    processTask(task);
                }
            } catch (InterruptedException e) {
                // Thread was interrupted (e.g. by workers.shutdownNow()) — exit cleanly
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Unexpected error in enrichment worker", e);
                // Do NOT break — keep the worker alive so other tasks can be processed
            }
        }
        log.debug("Enrichment worker exiting");
    }

    /**
     * Processes a single enrichment task: calls Plaid, commits the result to the DB,
     * then updates the in-memory cache.
     *
     * <p><b>Ordering is critical:</b> The DB write (step 2) runs inside a
     * {@link TransactionTemplate} that commits before this method calls
     * {@link MerchantMemoryCache#update} (step 3). If the DB write throws, the
     * {@code TransactionTemplate} rolls back and {@link MerchantMemoryCache#update}
     * is never called — so the DB and in-memory cache stay in sync.
     *
     * @param task the enrichment task dequeued from the worker queue
     */
    private void processTask(EnrichmentTask task) {
        long start = System.nanoTime();
        log.debug("[PLAID START] merchantId={} desc={}", task.merchantId(), task.description());

        try {
            // Step 1: Call Plaid. .block() converts the reactive Mono to a blocking call.
            PlaidEnrichResponse response =
                    plaidApiClient.enrichTransactions(buildPlaidRequest(task)).block();

            if (response == null || response.enrichedTransactions().isEmpty()) {
                log.warn("[PLAID EMPTY] merchantId={} — no enrichment data returned", task.merchantId());
                return; // Leave the merchant in PENDING status; retry next time it's requested
            }

            // Serialize the first (and typically only) enriched transaction to JSON
            String plaidJson = objectMapper.writeValueAsString(
                    response.enrichedTransactions().get(0));
            long plaidLatencyMs = (System.nanoTime() - start) / 1_000_000;

            // Step 2: Update the DB row FIRST, inside its own transaction.
            // If this throws, the TransactionTemplate rolls back automatically and
            // we skip step 3 entirely — the memory cache is never touched.
            txTemplate.execute(status -> {
                merchantCacheRepository.findById(task.merchantId()).ifPresent(entity -> {
                    entity.setPlaidResponse(plaidJson);
                    entity.setStatus("ENRICHED");
                    merchantCacheRepository.save(entity);
                });
                return null; // TransactionTemplate requires a return value; null is fine
            });

            // Step 3: Update the in-memory cache ONLY after the DB transaction commits.
            // This guarantees that DB and memory cache are always consistent:
            // the DB is updated before the memory cache, never after.
            memoryCache.update(task.description(), task.merchantName(), plaidJson);

            log.info("[PLAID ENRICHED] merchantId={} plaidLatencyMs={}", task.merchantId(), plaidLatencyMs);

        } catch (Exception e) {
            // Log the failure but do NOT re-throw — the worker loop handles exceptions.
            // The merchant stays PENDING and will be re-enriched on the next request.
            log.error("[PLAID FAILED] merchantId={} error={}", task.merchantId(), e.getMessage());
        }
    }

    /**
     * Converts an {@link EnrichmentTask} to the {@link PlaidEnrichRequest} format.
     *
     * <p>{@code clientId} and {@code apiKey} are left {@code null} here because
     * {@link PlaidApiClient} injects the credentials from its own {@code @Value} fields
     * just before each HTTP call. This keeps credentials out of the task data and the
     * business logic layer.
     *
     * @param task the enrichment task to convert
     * @return a ready-to-send Plaid request containing one transaction
     */
    private PlaidEnrichRequest buildPlaidRequest(EnrichmentTask task) {
        return new PlaidEnrichRequest(
                null, null,   // clientId and secret injected by PlaidApiClient
                task.accountId(),
                List.of(new PlaidEnrichRequest.PlaidTransaction(
                        task.description(),
                        task.amount(),
                        task.date(),
                        task.merchantName()
                ))
        );
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Gracefully shuts down the worker pool when the Spring context closes.
     *
     * <p>Called automatically by Spring before destroying this bean ({@code @PreDestroy}).
     * Steps:
     * <ol>
     *   <li>Sets {@code running = false} so worker loops stop accepting new tasks after
     *       they finish their current one.</li>
     *   <li>Calls {@link ExecutorService#shutdown()} to signal the pool to stop.</li>
     *   <li>Waits up to 30 seconds for all in-flight tasks to complete.</li>
     *   <li>If the 30-second window expires, calls {@link ExecutorService#shutdownNow()}
     *       to interrupt any remaining threads.</li>
     * </ol>
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down enrichment queue processor (pending tasks: {})...", queue.size());
        running = false;
        workers.shutdown();
        try {
            if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Enrichment workers did not terminate in 30s; forcing shutdown");
                workers.shutdownNow(); // Sends InterruptedException to blocked threads
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt(); // Restore interrupted status
        }
        log.info("Enrichment queue processor shut down");
    }
}
