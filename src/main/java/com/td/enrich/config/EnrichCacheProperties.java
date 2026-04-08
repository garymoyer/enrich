package com.td.enrich.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the in-memory merchant cache and background enrichment queue.
 *
 * <p>Spring Boot reads these values from the {@code enrich.cache.*} namespace in
 * {@code application.yml} (or environment variables) and injects them here automatically
 * via {@link ConfigurationProperties}. You can override any value at deployment time
 * without recompiling — just set the corresponding environment variable or YAML property.
 *
 * <p>Example {@code application.yml} snippet:
 * <pre>{@code
 * enrich:
 *   cache:
 *     max-size: 500000       # reduce memory footprint for smaller deployments
 *     queue-capacity: 5000
 *     worker-threads: 2
 * }</pre>
 *
 * <p>The defaults below are tuned for a standard production pod with 1 GB of heap:
 * <ul>
 *   <li>1 million cache entries ≈ 200–400 MB depending on description length.</li>
 *   <li>10,000 queue slots provides enough buffer for a burst of incoming transactions
 *       before back-pressure kicks in.</li>
 *   <li>4 worker threads is sufficient for typical Plaid API latency (~200–500 ms),
 *       giving roughly 8–20 enrichments/second per pod.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "enrich.cache")
public class EnrichCacheProperties {

    /**
     * Maximum number of entries the in-memory {@link com.td.enrich.service.MerchantMemoryCache}
     * will hold. Once this limit is reached, new merchants are written to the database
     * but not added to the in-memory map. Those merchants will be re-cached on the next
     * pod restart when the cache is reloaded from the database.
     *
     * <p>Default: 1,000,000 entries.
     */
    private int maxSize = 1_000_000;

    /**
     * Maximum number of pending enrichment tasks the
     * {@link com.td.enrich.service.EnrichmentQueueProcessor} queue will hold at once.
     * If the queue is full when a new task arrives, the task is dropped and a warning
     * is logged — the merchant stays {@code PENDING} and will be enriched on the next
     * request that needs it.
     *
     * <p>Default: 10,000 tasks.
     */
    private int queueCapacity = 10_000;

    /**
     * Number of background worker threads that drain the enrichment queue.
     * Each worker blocks waiting for a task, calls Plaid when one arrives,
     * then updates the database and in-memory cache before looping back.
     *
     * <p>Increase this value if you observe the queue depth growing consistently
     * (check the {@code enrich.queue.size} metric). Decrease it to limit concurrency
     * against the Plaid API.
     *
     * <p>Default: 4 workers.
     */
    private int workerThreads = 4;

    public int getMaxSize() { return maxSize; }
    public void setMaxSize(int maxSize) { this.maxSize = maxSize; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
}
