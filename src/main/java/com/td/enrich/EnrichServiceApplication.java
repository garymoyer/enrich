package com.td.enrich;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the TD Enrich Service.
 *
 * <p>This microservice accepts financial transaction data from clients, looks up
 * enriched merchant information (category, logo, standardized name) from the Plaid
 * API, caches results to avoid redundant upstream calls, and returns the enriched
 * data to the caller.
 *
 * <p>Key capabilities:
 * <ul>
 *   <li><b>Plaid API integration</b> — reactive HTTP client ({@code WebClient}) with
 *       automatic retry, circuit breaking, and concurrent-call limiting.</li>
 *   <li><b>Two-layer merchant cache</b> — an in-memory {@link com.td.enrich.service.MerchantMemoryCache}
 *       backed by a persistent {@code merchant_cache} database table, so cache
 *       survives pod restarts.</li>
 *   <li><b>Async background enrichment</b> — new merchants are served immediately with
 *       a stub ID; the {@link com.td.enrich.service.EnrichmentQueueProcessor} fills in
 *       Plaid data asynchronously so the caller never waits for a cold-cache miss.</li>
 *   <li><b>Persistence and audit trail</b> — every request/response pair is stored in
 *       the {@code enrichment_records} table.</li>
 *   <li><b>TTL cache refresh</b> — {@link com.td.enrich.service.MerchantCacheRefreshScheduler}
 *       runs nightly at 02:00 UTC to re-enrich entries older than {@code enrich.cache.ttl-days}
 *       so merchant logos, names, and categories stay fresh. Enabled by {@code @EnableScheduling}
 *       on this class.</li>
 *   <li><b>API key authentication</b> — all enrichment endpoints require an
 *       {@code X-API-Key} header matching the {@code ENRICH_API_KEY} environment variable.
 *       Health and actuator probe endpoints remain open for load balancers.</li>
 *   <li><b>Observability</b> — Prometheus metrics via Spring Boot Actuator, structured
 *       JSON logging, and OpenAPI/Swagger documentation at {@code /swagger-ui.html}
 *       (enabled only when {@code ENABLE_SWAGGER=true}).</li>
 * </ul>
 *
 * <p>Spring Boot auto-configuration wires everything together. The {@code main} method
 * below is the only code that needs to run to start the entire service.
 */
@SpringBootApplication
@EnableScheduling
public class EnrichServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnrichServiceApplication.class, args);
    }
}
