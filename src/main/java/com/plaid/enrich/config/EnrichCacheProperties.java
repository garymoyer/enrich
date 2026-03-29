package com.plaid.enrich.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the in-memory merchant cache and background enrichment queue.
 * Bound from the {@code enrich.cache} namespace in application.yml.
 */
@ConfigurationProperties(prefix = "enrich.cache")
public class EnrichCacheProperties {

    /** Maximum number of merchant entries held in the in-memory cache. Default: 1,000,000. */
    private int maxSize = 1_000_000;

    /** Capacity of the background enrichment queue. Default: 10,000. */
    private int queueCapacity = 10_000;

    /** Number of virtual-thread workers draining the enrichment queue. Default: 4. */
    private int workerThreads = 4;

    public int getMaxSize() { return maxSize; }
    public void setMaxSize(int maxSize) { this.maxSize = maxSize; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
}
