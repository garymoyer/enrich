package com.td.enrich;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

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
 *   <li><b>Observability</b> — Prometheus metrics via Spring Boot Actuator, structured
 *       JSON logging, and OpenAPI/Swagger documentation at {@code /swagger-ui.html}.</li>
 * </ul>
 *
 * <p>Spring Boot auto-configuration wires everything together. The {@code main} method
 * below is the only code that needs to run to start the entire service.
 */
@SpringBootApplication
public class EnrichServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnrichServiceApplication.class, args);
    }
}
