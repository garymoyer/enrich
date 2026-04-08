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
 * HTTP client for the TD Enrich Service, used by the manual test harness.
 *
 * <p>This class wraps Spring's {@link RestTemplate} with convenience methods that match
 * the service's REST API. It is designed for use in {@link EnrichmentTestHarness} and
 * {@link TestRunner} — NOT in automated unit tests (use Mockito/WireMock there instead).
 *
 * <p><b>Operations provided:</b>
 * <ul>
 *   <li>{@link #enrichSingle} — synchronous single-transaction enrichment</li>
 *   <li>{@link #enrichBatch} — asynchronous batch submission (returns GUIDs to poll)</li>
 *   <li>{@link #pollForResult} — polls for a batch result with exponential backoff</li>
 *   <li>{@link #enrichBatchAndWait} — submits a batch and polls until all results are ready</li>
 *   <li>{@link #isHealthy} — checks the service health endpoint</li>
 *   <li>{@link #getCircuitBreakerStatus} — reads the actuator circuit breaker state</li>
 * </ul>
 *
 * <p><b>Metrics:</b> Every call is timed and the result (success/failure, latency) is
 * recorded in the {@link #metrics} map. Call {@link #getMetrics()} after a test run to
 * see per-operation statistics.
 */
public class TestEnrichmentClient {

    private final RestTemplate restTemplate;

    /** Base URL of the enrichment API, e.g. {@code http://localhost:8080/api/v1/enrich}. */
    private final String baseUrl;

    /** Per-operation metrics keyed by operation name (e.g. "single", "batch_submit"). */
    private final Map<String, ClientMetrics> metrics;

    /**
     * @param baseUrl the service root URL (e.g. {@code http://localhost:8080});
     *                {@code /api/v1/enrich} is appended automatically
     */
    public TestEnrichmentClient(String baseUrl) {
        this.restTemplate = new RestTemplate();
        this.baseUrl = baseUrl + "/api/v1/enrich";
        this.metrics = new ConcurrentHashMap<>();
    }

    /**
     * Enriches a single transaction synchronously.
     *
     * <p>Blocks the calling thread until the service responds. For load testing,
     * call this from multiple threads concurrently via {@link EnrichmentTestHarness#runParallelTests}.
     *
     * @param request the enrichment request to send
     * @return the service's enrichment response
     * @throws RestClientException if the HTTP call fails
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
     * Submits a batch of enrichment requests asynchronously.
     *
     * <p>The service returns immediately with a list of GUIDs — one per request.
     * Use {@link #pollForResult} or {@link #enrichBatchAndWait} to retrieve results.
     *
     * @param requests the list of enrichment requests to submit
     * @return a list of GUIDs in the same order as the input requests
     * @throws RestClientException if the HTTP submission fails
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
     * Retrieves the enrichment result for a specific GUID.
     *
     * <p>Returns immediately — the result may still be in PENDING status if the
     * background worker hasn't processed it yet. Check {@code response.status()} to
     * determine whether enrichment is complete.
     *
     * @param guid the request UUID returned by {@link #enrichBatch}
     * @return the current enrichment result (may be PENDING)
     * @throws RestClientException if the HTTP call fails
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
     * Polls for an enrichment result until it reaches {@code SUCCESS} status or the
     * timeout expires.
     *
     * <p>Uses exponential backoff starting at 100 ms, capped at 5 000 ms, to avoid
     * hammering the service during long enrichment operations.
     *
     * @param guid      the request UUID to poll
     * @param maxWaitMs maximum time to wait in milliseconds before giving up
     * @return the {@code SUCCESS} enrichment response
     * @throws TimeoutException if {@code maxWaitMs} elapses without a SUCCESS status
     */
    public EnrichmentResponse pollForResult(String guid, long maxWaitMs) throws TimeoutException {
        long startTime = System.currentTimeMillis();
        long backoffMs = 100; // start with 100ms, double each iteration up to 5 000ms

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            try {
                EnrichmentResponse response = getResult(guid);

                if ("SUCCESS".equals(response.status())) {
                    return response;
                }

                // Wait before the next poll attempt
                Thread.sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 5_000); // cap at 5 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }

        throw new TimeoutException("Polling timeout after " + maxWaitMs + "ms for GUID: " + guid);
    }

    /**
     * Polls for an enrichment result asynchronously, wrapping {@link #pollForResult}
     * in a {@link CompletableFuture}.
     *
     * <p>Useful when you need to poll for multiple GUIDs concurrently without blocking
     * the calling thread for each one.
     *
     * @param guid      the request UUID to poll
     * @param maxWaitMs maximum polling duration in milliseconds
     * @return a future that completes with the SUCCESS response or fails with a
     *         {@code RuntimeException} wrapping a {@link TimeoutException}
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
     * Submits a batch for async enrichment and then polls until all results are ready.
     *
     * <p>All GUIDs are polled concurrently using {@link CompletableFuture}, so the
     * total wait time is determined by the slowest individual request, not their sum.
     *
     * @param requests   the enrichment requests to submit
     * @param maxWaitMs  maximum time to wait for each result in milliseconds
     * @return the completed enrichment responses in the same order as the input requests
     * @throws Exception if batch submission or polling fails
     */
    public List<EnrichmentResponse> enrichBatchAndWait(List<EnrichmentRequest> requests, long maxWaitMs)
            throws Exception {

        List<String> guids = enrichBatch(requests);
        System.out.println("Submitted " + guids.size() + " transactions for enrichment");

        // Start all polling futures concurrently
        List<CompletableFuture<EnrichmentResponse>> futures = guids.stream()
            .map(guid -> pollForResultAsync(guid, maxWaitMs))
            .collect(Collectors.toList());

        // Wait for all futures to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return futures.stream()
            .map(CompletableFuture::join)
            .collect(Collectors.toList());
    }

    /**
     * Checks whether the service is healthy by calling {@code GET /api/v1/enrich/health}.
     *
     * @return {@code true} if the endpoint responds with HTTP 200; {@code false} otherwise
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
     * Returns the circuit breaker state from the Spring Actuator endpoint.
     *
     * @return a map of circuit breaker names to their current states, or an empty
     *         map if the endpoint is unavailable
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

    /** Returns a snapshot of the collected per-operation metrics. */
    public Map<String, ClientMetrics> getMetrics() {
        return new HashMap<>(metrics);
    }

    /**
     * Records a single call's outcome into the metrics map.
     *
     * <p>{@link Map#compute} is used instead of {@code get} + {@code put} to avoid
     * a race condition where two threads both read a {@code null} value and both try
     * to create a new {@link ClientMetrics} instance.
     */
    private void recordMetric(String operation, long elapsedNanos, int statusCode, boolean success) {
        metrics.compute(operation, (key, existing) -> {
            ClientMetrics m = existing != null ? existing : new ClientMetrics(operation);
            m.recordCall(elapsedNanos, statusCode, success);
            return m;
        });
    }

    /**
     * Accumulated metrics for a single operation type (e.g. "single" or "batch_submit").
     *
     * <p>Not thread-safe on its own — callers must synchronize access, which is handled
     * by {@link Map#compute} in {@link #recordMetric}.
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

        /**
         * Records one call's elapsed time and success/failure outcome.
         *
         * @param elapsedNanos the call duration in nanoseconds (converted to ms internally)
         * @param statusCode   the HTTP status code (not currently used beyond pass/fail)
         * @param success      {@code true} if the call received a 2xx response
         */
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

        /** Returns the average response time across all recorded calls in milliseconds. */
        public double getAverageMs() {
            return callCount > 0 ? (double) totalTimeMs / callCount : 0;
        }

        /** Returns the percentage of calls that received a 2xx response. */
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
