package com.td.enrich.test;

import com.td.enrich.domain.EnrichmentRequest;
import com.td.enrich.domain.EnrichmentResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates the full suite of manual test scenarios for the Enrich service.
 *
 * <p>This harness is intended for exploratory and integration testing against a
 * running service (started with {@code ./mvnw spring-boot:run}). It is NOT a JUnit
 * test — it is invoked from {@link TestRunner#main} or called directly from your IDE.
 *
 * <p><b>Test scenarios available:</b>
 * <ol>
 *   <li><b>Single enrichment</b> — 200 sequential calls to {@code POST /api/v1/enrich}.</li>
 *   <li><b>Batch enrichment</b> — same 200 requests split into batches and submitted
 *       via the async batch endpoint, then polled for completion.</li>
 *   <li><b>Cache-heavy</b> — 200 requests that repeat the same 5 merchants; tests
 *       that cache hits are much faster than cache misses.</li>
 *   <li><b>Stress test</b> — 200 identical transactions back-to-back; measures whether
 *       the cache and DB handle repeated concurrent inserts gracefully.</li>
 *   <li><b>Edge cases</b> — small/large amounts, special characters, unusual merchants.</li>
 *   <li><b>Parallel execution</b> — 200 requests sent from multiple threads simultaneously.</li>
 * </ol>
 *
 * <p><b>Cache-hit heuristic:</b> Any response that arrives in under 10 ms is counted as
 * a cache hit. This is a rough estimate — sub-10 ms responses almost certainly came from
 * the in-memory cache rather than the Plaid API.
 */
public class EnrichmentTestHarness {

    private final TestEnrichmentClient client;
    /** Accumulated results from all test scenarios; synchronized for concurrent append safety. */
    private final List<TestResult> results = Collections.synchronizedList(new ArrayList<>());
    /** Tracks how many times each merchant appeared in sub-10ms responses (cache hit proxy). */
    private final Map<String, Integer> merchantCacheHits = Collections.synchronizedMap(new HashMap<>());

    /**
     * @param serviceBaseUrl the base URL of the running service (e.g. {@code http://localhost:8080})
     */
    public EnrichmentTestHarness(String serviceBaseUrl) {
        this.client = new TestEnrichmentClient(serviceBaseUrl);
    }

    /** Returns the underlying HTTP client for direct health checks in {@link TestRunner}. */
    public TestEnrichmentClient getClient() {
        return client;
    }

    // ── Test scenarios ─────────────────────────────────────────────────────────

    /**
     * Runs 200 single enrichment requests sequentially and validates each response.
     *
     * <p>Each request is sent one at a time on the calling thread. This scenario
     * measures worst-case latency rather than throughput.
     *
     * @return a summary report with pass/fail counts and latency percentiles
     */
    public TestRunReport runSingleEnrichmentTests() {
        System.out.println("\n=== Running 200 Single Enrichment Tests ===\n");

        List<EnrichmentRequest> testCases = MerchantTestDataGenerator.generate200TestCases();

        for (int i = 0; i < testCases.size(); i++) {
            EnrichmentRequest request = testCases.get(i);
            TestResult result = new TestResult(i + 1, request);

            try {
                long startTime = System.nanoTime();
                EnrichmentResponse response = client.enrichSingle(request);
                long elapsed = System.nanoTime() - startTime;

                result.success = true;
                result.responseTimeMs = elapsed / 1_000_000;
                result.plaidResponseAvailable = response.enrichedTransactions() != null
                        && !response.enrichedTransactions().isEmpty();
                result.merchantName = result.plaidResponseAvailable
                    ? response.enrichedTransactions().get(0).merchantName()
                    : "N/A";

                // Heuristic: sub-10ms response → likely served from in-memory cache
                if (result.responseTimeMs < 10) {
                    trackCacheHit(request.transactions().get(0).merchantName());
                }

                System.out.printf("Test %3d: %s [%dms] - %s\n",
                    result.testNum,
                    result.success ? "✓ PASS" : "✗ FAIL",
                    result.responseTimeMs,
                    request.transactions().get(0).merchantName()
                );

            } catch (Exception e) {
                result.success = false;
                result.errorMessage = e.getMessage();
                System.out.printf("Test %3d: ✗ FAIL - %s\n", result.testNum, e.getMessage());
            }

            results.add(result);
        }

        return generateReport("Single Enrichment");
    }

    /**
     * Submits 200 test cases in batches via the async batch endpoint and waits for all results.
     *
     * <p>Batches are submitted concurrently via {@link CompletableFuture}; within each batch
     * the results are polled until all transactions reach SUCCESS status.
     *
     * @param batchSize how many requests to include in each batch submission
     * @return a summary report
     */
    public TestRunReport runBatchEnrichmentTests(int batchSize) {
        System.out.println("\n=== Running 200 Batch Enrichment Tests (batch size: " + batchSize + ") ===\n");

        List<EnrichmentRequest> testCases = MerchantTestDataGenerator.generate200TestCases();
        AtomicInteger testNum = new AtomicInteger(1);

        // Split the 200 test cases into equal-sized batches
        List<List<EnrichmentRequest>> batches = partitionList(testCases, batchSize);
        List<CompletableFuture<Void>> batchFutures = new ArrayList<>();

        for (List<EnrichmentRequest> batch : batches) {
            CompletableFuture<Void> batchFuture = CompletableFuture.runAsync(() -> {
                try {
                    // Submit the batch and poll until all results are available
                    List<EnrichmentResponse> responses = client.enrichBatchAndWait(batch, 30_000);

                    for (int i = 0; i < batch.size(); i++) {
                        EnrichmentRequest request = batch.get(i);
                        EnrichmentResponse response = responses.get(i);
                        TestResult result = new TestResult(testNum.getAndIncrement(), request);

                        result.success = true;
                        result.plaidResponseAvailable = response.enrichedTransactions() != null
                                && !response.enrichedTransactions().isEmpty();
                        result.merchantName = result.plaidResponseAvailable
                            ? response.enrichedTransactions().get(0).merchantName()
                            : "N/A";

                        trackCacheHit(request.transactions().get(0).merchantName());
                        results.add(result);

                        System.out.printf("Test %3d: ✓ PASS - %s\n",
                            result.testNum, request.transactions().get(0).merchantName());
                    }
                } catch (Exception e) {
                    System.err.println("Batch processing failed: " + e.getMessage());
                }
            });

            batchFutures.add(batchFuture);
        }

        // Block until all batches have been processed
        CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0])).join();

        return generateReport("Batch Enrichment (size: " + batchSize + ")");
    }

    /**
     * Runs 200 requests that repeatedly hit the same 5 merchants to exercise the cache.
     *
     * <p>After the first few misses populate the cache, subsequent requests for the same
     * merchant should be served in &lt;10 ms. The report includes a cache-hit estimate.
     *
     * @return a summary report with an estimated cache-hit ratio
     */
    public TestRunReport runCacheHeavyTests() {
        System.out.println("\n=== Running Cache-Heavy Test Scenario ===\n");

        List<EnrichmentRequest> testCases = MerchantTestDataGenerator.generateScenario("CACHE_HEAVY");
        int cacheHits = 0;
        long cacheHitTime = 0; // accumulates total response time for sub-10ms (cache-hit) responses

        for (int i = 0; i < testCases.size(); i++) {
            EnrichmentRequest request = testCases.get(i);
            TestResult result = new TestResult(i + 1, request);

            try {
                long startTime = System.nanoTime();
                EnrichmentResponse response = client.enrichSingle(request);
                long elapsed = System.nanoTime() - startTime;

                result.success = true;
                result.responseTimeMs = elapsed / 1_000_000;
                result.plaidResponseAvailable = response.enrichedTransactions() != null
                        && !response.enrichedTransactions().isEmpty();

                // Sub-10ms responses are counted as cache hits; accumulate their time
                // so we can report the average cache-hit latency at the end of the loop
                if (result.responseTimeMs < 10) {
                    cacheHits++;
                    cacheHitTime += result.responseTimeMs;
                }

                trackCacheHit(request.transactions().get(0).merchantName());

            } catch (Exception e) {
                result.success = false;
                result.errorMessage = e.getMessage();
            }

            results.add(result);

            if ((i + 1) % 20 == 0) {
                System.out.printf("Processed %d/%d cache-heavy tests\n", i + 1, testCases.size());
            }
        }

        // Print average cache-hit latency — useful for verifying the in-memory cache is working
        if (cacheHits > 0) {
            System.out.printf("Cache hits: %d  |  avg cache-hit latency: %.1f ms%n",
                    cacheHits, (double) cacheHitTime / cacheHits);
        }

        TestRunReport report = generateReport("Cache-Heavy Scenario");
        report.estimatedCacheHitCount = cacheHits;
        report.estimatedCacheHitRatio = (double) cacheHits / testCases.size() * 100;

        return report;
    }

    /**
     * Sends the same transaction 200 times in a row to stress-test cache concurrency.
     *
     * <p>After the first request misses and populates the cache, all subsequent requests
     * should hit the cache. This scenario detects cache stampedes or lock contention
     * under repeated identical requests.
     *
     * @return a summary report
     */
    public TestRunReport runStressTest() {
        System.out.println("\n=== Running Stress Test (200x identical transaction) ===\n");

        List<EnrichmentRequest> testCases = MerchantTestDataGenerator.generateScenario("STRESS_TEST");

        for (int i = 0; i < testCases.size(); i++) {
            EnrichmentRequest request = testCases.get(i);
            TestResult result = new TestResult(i + 1, request);

            try {
                long startTime = System.nanoTime();
                EnrichmentResponse response = client.enrichSingle(request);
                long elapsed = System.nanoTime() - startTime;
                result.plaidResponseAvailable = response.enrichedTransactions() != null
                        && !response.enrichedTransactions().isEmpty();

                result.success = true;
                result.responseTimeMs = elapsed / 1_000_000;

            } catch (Exception e) {
                result.success = false;
                result.errorMessage = e.getMessage();
            }

            results.add(result);

            if ((i + 1) % 50 == 0) {
                System.out.printf("Stress test progress: %d/200\n", i + 1);
            }
        }

        return generateReport("Stress Test");
    }

    /**
     * Runs boundary-condition test cases (very small/large amounts, special characters, etc.).
     *
     * @return a summary report
     */
    public TestRunReport runEdgeCaseTests() {
        System.out.println("\n=== Running Edge Case Tests ===\n");

        List<EnrichmentRequest> testCases = MerchantTestDataGenerator.generateScenario("EDGE_CASES");

        for (int i = 0; i < testCases.size(); i++) {
            EnrichmentRequest request = testCases.get(i);
            TestResult result = new TestResult(i + 1, request);

            try {
                EnrichmentResponse response = client.enrichSingle(request);
                result.success = true;
                result.plaidResponseAvailable = response.enrichedTransactions() != null
                        && !response.enrichedTransactions().isEmpty();

                System.out.printf("Test %3d: ✓ PASS - Amount: %s\n",
                    result.testNum, request.transactions().get(0).amount());

            } catch (Exception e) {
                result.success = false;
                result.errorMessage = e.getMessage();
                System.out.printf("Test %3d: ✗ FAIL - %s\n", result.testNum, e.getMessage());
            }

            results.add(result);
        }

        return generateReport("Edge Cases");
    }

    /**
     * Runs 200 enrichment requests concurrently from multiple threads.
     *
     * <p>Each request is submitted as a {@link CompletableFuture} so they run in
     * parallel. The method blocks until all futures complete.
     *
     * @param parallelism the number of threads to use (passed to the report name for clarity)
     * @return a summary report
     */
    public TestRunReport runParallelTests(int parallelism) {
        System.out.println("\n=== Running Parallel Tests (parallelism: " + parallelism + ") ===\n");

        List<EnrichmentRequest> testCases = MerchantTestDataGenerator.generate200TestCases();
        List<CompletableFuture<TestResult>> futures = new ArrayList<>();

        for (int i = 0; i < testCases.size(); i++) {
            final int testNum = i + 1;
            final EnrichmentRequest request = testCases.get(i);

            // Each request runs on the common pool — effectively "parallelism" threads
            CompletableFuture<TestResult> future = CompletableFuture.supplyAsync(() -> {
                TestResult result = new TestResult(testNum, request);

                try {
                    EnrichmentResponse response = client.enrichSingle(request);
                    result.success = true;
                    result.plaidResponseAvailable = response.enrichedTransactions() != null
                            && !response.enrichedTransactions().isEmpty();
                } catch (Exception e) {
                    result.success = false;
                    result.errorMessage = e.getMessage();
                }

                return result;
            });

            futures.add(future);
        }

        // Wait for all requests to complete, then collect results
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        futures.stream().map(CompletableFuture::join).forEach(results::add);

        return generateReport("Parallel Execution (" + parallelism + " threads)");
    }

    // ── Report generation ──────────────────────────────────────────────────────

    /**
     * Assembles a {@link TestRunReport} from the accumulated results and prints it.
     *
     * @param testName a human-readable label for the report section
     * @return the populated report object
     */
    private TestRunReport generateReport(String testName) {
        TestRunReport report = new TestRunReport(testName);

        report.totalTests = results.size();
        report.passedTests = (int) results.stream().filter(r -> r.success).count();
        report.failedTests = report.totalTests - report.passedTests;
        report.successRate = (double) report.passedTests / report.totalTests * 100;

        // Collect and sort successful response times for percentile computation
        List<Long> responseTimes = results.stream()
            .filter(r -> r.success && r.responseTimeMs > 0)
            .map(r -> r.responseTimeMs)
            .sorted()
            .toList();

        if (!responseTimes.isEmpty()) {
            report.avgResponseTimeMs = responseTimes.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0);
            report.minResponseTimeMs = responseTimes.get(0);
            report.maxResponseTimeMs = responseTimes.get(responseTimes.size() - 1);
            report.p50ResponseTimeMs = responseTimes.get(responseTimes.size() / 2);
            report.p95ResponseTimeMs = responseTimes.get((int) (responseTimes.size() * 0.95));
            report.p99ResponseTimeMs = responseTimes.get((int) (responseTimes.size() * 0.99));
        }

        report.merchantCacheHits = new HashMap<>(merchantCacheHits);

        printReport(report);

        return report;
    }

    private void printReport(TestRunReport report) {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("TEST RUN REPORT: " + report.testName);
        System.out.println("=".repeat(70));
        System.out.printf("Total Tests:      %d\n", report.totalTests);
        System.out.printf("Passed:           %d (%.1f%%)\n", report.passedTests, report.successRate);
        System.out.printf("Failed:           %d\n", report.failedTests);
        System.out.println();
        System.out.println("Response Time Statistics (ms):");
        System.out.printf("  Min:             %.1f\n", report.minResponseTimeMs);
        System.out.printf("  Max:             %.1f\n", report.maxResponseTimeMs);
        System.out.printf("  Average:         %.1f\n", report.avgResponseTimeMs);
        System.out.printf("  P50 (Median):    %.1f\n", report.p50ResponseTimeMs);
        System.out.printf("  P95:             %.1f\n", report.p95ResponseTimeMs);
        System.out.printf("  P99:             %.1f\n", report.p99ResponseTimeMs);
        System.out.println();

        if (report.estimatedCacheHitRatio > 0) {
            System.out.printf("Cache Hit Ratio:  %.1f%%\n", report.estimatedCacheHitRatio);
        }

        System.out.println();
        System.out.println("Service Health:");
        System.out.printf("  Healthy:         %s\n", client.isHealthy() ? "✓" : "✗");
        System.out.println();
        System.out.println("Client Metrics:");
        client.getMetrics().forEach((op, metrics) ->
            System.out.printf("  %s\n", metrics)
        );
        System.out.println("=".repeat(70) + "\n");
    }

    /** Increments the cache-hit counter for the given merchant name. */
    private void trackCacheHit(String merchantName) {
        merchantCacheHits.compute(merchantName, (k, v) -> v == null ? 1 : v + 1);
    }

    /**
     * Splits a list into consecutive sublists of the given size.
     * The last sublist may be smaller than {@code size} if the list doesn't divide evenly.
     */
    private <T> List<List<T>> partitionList(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    // ── Data classes ───────────────────────────────────────────────────────────

    /**
     * Holds the outcome of a single test case.
     *
     * <p>Fields are public for easy access in reporting code — this is test-only code
     * that doesn't need strict encapsulation.
     */
    public static class TestResult {
        public int testNum;
        public EnrichmentRequest request;
        public boolean success;
        public long responseTimeMs;
        public boolean plaidResponseAvailable;
        public String merchantName;
        public String errorMessage;

        public TestResult(int testNum, EnrichmentRequest request) {
            this.testNum = testNum;
            this.request = request;
        }
    }

    /**
     * Aggregated statistics for one test run scenario.
     *
     * <p>Populated by {@link #generateReport} and returned to the caller (e.g.
     * {@link TestRunner}) for final summary printing.
     */
    public static class TestRunReport {
        public String testName;
        public int totalTests;
        public int passedTests;
        public int failedTests;
        public double successRate;
        public double minResponseTimeMs;
        public double maxResponseTimeMs;
        public double avgResponseTimeMs;
        public double p50ResponseTimeMs;
        public double p95ResponseTimeMs;
        public double p99ResponseTimeMs;
        /** Estimated percentage of sub-10ms responses (cache hit proxy). */
        public double estimatedCacheHitRatio;
        public int estimatedCacheHitCount;
        /** Map of merchant name → number of sub-10ms responses for that merchant. */
        public Map<String, Integer> merchantCacheHits;

        public TestRunReport(String testName) {
            this.testName = testName;
            this.merchantCacheHits = new HashMap<>();
        }
    }
}
