package com.td.enrich.test;

import com.td.enrich.domain.EnrichmentRequest;
import com.td.enrich.domain.EnrichmentResponse;
import com.td.enrich.domain.BatchEnrichmentRequest;
import com.td.enrich.domain.BatchEnrichmentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Test client for the TD Enrich Service.
 * 
 * Provides both synchronous and asynchronous enrichment APIs:
 * - Single transaction enrichment (blocking)
 * - Batch enrichment with polling (non-blocking)
 * - Health check and circuit breaker monitoring
 * - Metrics and statistics collection
 */
public class TestEnrichmentClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final Map<String, ClientMetrics> metrics;

    public TestEnrichmentClient(String baseUrl) {
        this.restTemplate = new RestTemplate();
        this.baseUrl = baseUrl + "/api/v1/enrich";
        this.metrics = new ConcurrentHashMap<>();
    }

    /**
     * Enriches a single transaction synchronously.
     * Blocks until Plaid API call completes or times out.
     */
    public EnrichmentResponse enrichSingle(EnrichmentRequest request) throws RestClientException {
        long startTime = System.nanoTime();
        
        try {
            ResponseEntity<EnrichmentResponse> response = restTemplate.postForEntity(
                baseUrl + "/single",
                request,
                EnrichmentResponse.class
            );
            
            long elapsed = System.nanoTime() - startTime;
            recordMetric("single", elapsed, response.getStatusCode().value(), true);
            
            return response.getBody();
        } catch (Exception e) {
            long elapsed = System.nanoTime() - startTime;
            recordMetric("single", elapsed, 500, false);
            throw e;
        }
    }

    /**
     * Submits batch of transactions for asynchronous enrichment.
     * Returns immediately with GUIDs to poll for results.
     */
    public List<String> enrichBatch(List<EnrichmentRequest> requests) throws RestClientException {
        long startTime = System.nanoTime();
        
        try {
            BatchEnrichmentRequest batchRequest = new BatchEnrichmentRequest(requests);
            
            ResponseEntity<BatchEnrichmentResponse> response = restTemplate.postForEntity(
                baseUrl + "/batch",
                batchRequest,
                BatchEnrichmentResponse.class
            );
            
            long elapsed = System.nanoTime() - startTime;
            recordMetric("batch_submit", elapsed, response.getStatusCode().value(), true);
            
            return response.getBody().guids();
        } catch (Exception e) {
            long elapsed = System.nanoTime() - startTime;
            recordMetric("batch_submit", elapsed, 500, false);
            throw e;
        }
    }

    /**
     * Retrieves enrichment result by GUID.
     * Returns immediately; check plaidResponse field to determine if ready.
     */
    public EnrichmentResponse getResult(String guid) throws RestClientException {
        long startTime = System.nanoTime();
        
        try {
            ResponseEntity<EnrichmentResponse> response = restTemplate.getForEntity(
                baseUrl + "/" + guid,
                EnrichmentResponse.class
            );
            
            long elapsed = System.nanoTime() - startTime;
            recordMetric("get_result", elapsed, response.getStatusCode().value(), true);
            
            return response.getBody();
        } catch (Exception e) {
            long elapsed = System.nanoTime() - startTime;
            recordMetric("get_result", elapsed, 500, false);
            throw e;
        }
    }

    /**
     * Polls for batch enrichment result with exponential backoff.
     * 
     * @param guid The enrichment GUID
     * @param maxWaitMs Maximum time to wait (default 30 seconds)
     * @return EnrichmentResponse once plaidResponse is populated
     * @throws TimeoutException if maxWaitMs exceeded
     */
    public EnrichmentResponse pollForResult(String guid, long maxWaitMs) throws TimeoutException {
        long startTime = System.currentTimeMillis();
        long backoffMs = 100;
        
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            try {
                EnrichmentResponse response = getResult(guid);
                
                if ("SUCCESS".equals(response.status())) {
                    return response; // Done
                }
                
                // Exponential backoff
                Thread.sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 5000); // Cap at 5 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        
        throw new TimeoutException("Polling timeout after " + maxWaitMs + "ms for GUID: " + guid);
    }

    /**
     * Asynchronously polls for enrichment results.
     */
    public CompletableFuture<EnrichmentResponse> pollForResultAsync(String guid, long maxWaitMs) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return pollForResult(guid, maxWaitMs);
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Enriches multiple requests asynchronously using batch API.
     * Waits for all results before returning.
     */
    public List<EnrichmentResponse> enrichBatchAndWait(List<EnrichmentRequest> requests, long maxWaitMs) 
            throws Exception {
        
        // Submit batch
        List<String> guids = enrichBatch(requests);
        System.out.println("Submitted " + guids.size() + " transactions for enrichment");
        
        // Poll for all results
        List<CompletableFuture<EnrichmentResponse>> futures = guids.stream()
            .map(guid -> pollForResultAsync(guid, maxWaitMs))
            .collect(Collectors.toList());
        
        CompletableFuture<Void> allOf = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0])
        );
        
        allOf.join();
        
        return futures.stream()
            .map(CompletableFuture::join)
            .collect(Collectors.toList());
    }

    /**
     * Checks service health.
     */
    public boolean isHealthy() {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl.substring(0, baseUrl.lastIndexOf('/')) + "/health",
                String.class
            );
            return response.getStatusCode() == HttpStatus.OK;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Gets circuit breaker status.
     */
    public Map<String, Object> getCircuitBreakerStatus() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(
                baseUrl.substring(0, baseUrl.lastIndexOf('/')) + "/health/circuitbreakers",
                Map.class
            );
            return response;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /**
     * Gets current metrics.
     */
    public Map<String, ClientMetrics> getMetrics() {
        return new HashMap<>(metrics);
    }

    private void recordMetric(String operation, long elapsedNanos, int statusCode, boolean success) {
        metrics.compute(operation, (key, existing) -> {
            ClientMetrics m = existing != null ? existing : new ClientMetrics(operation);
            m.recordCall(elapsedNanos, statusCode, success);
            return m;
        });
    }

    /**
     * Metrics for a single operation type.
     */
    public static class ClientMetrics {
        public final String operation;
        public long callCount = 0;
        public long totalTimeMs = 0;
        public long minMsn = Long.MAX_VALUE;
        public long maxMs = 0;
        public long successCount = 0;
        public long failureCount = 0;

        public ClientMetrics(String operation) {
            this.operation = operation;
        }

        void recordCall(long elapsedNanos, int statusCode, boolean success) {
            callCount++;
            long elapsedMs = elapsedNanos / 1_000_000;
            totalTimeMs += elapsedMs;
            minMsn = Math.min(minMsn, elapsedMs);
            maxMs = Math.max(maxMs, elapsedMs);
            
            if (success) {
                successCount++;
            } else {
                failureCount++;
            }
        }

        public double getAverageMs() {
            return callCount > 0 ? (double) totalTimeMs / callCount : 0;
        }

        public double getSuccessRate() {
            return callCount > 0 ? (double) successCount / callCount * 100 : 0;
        }

        @Override
        public String toString() {
            return String.format(
                "%s: %d calls, %.2f ms avg, %d min, %d max, %.1f%% success",
                operation, callCount, getAverageMs(), minMsn, maxMs, getSuccessRate()
            );
        }
    }
}
