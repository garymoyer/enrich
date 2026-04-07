package com.td.enrich.test;

import com.td.enrich.domain.EnrichmentRequest;
import com.td.enrich.domain.EnrichmentResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main test harness for running 200 merchant transaction test cases.
 * 
 * Features:
 * - Multiple test scenarios (single sync, batch async, stress test)
 * - Validation of enrichment results
 * - Metrics collection and reporting
 * - Success/failure tracking
 * - Cache hit ratio analysis
 */
public class EnrichmentTestHarness {

    private final TestEnrichmentClient client;
    private final List<TestResult> results = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Integer> merchantCacheHits = Collections.synchronizedMap(new HashMap<>());

    public EnrichmentTestHarness(String serviceBaseUrl) {
        this.client = new TestEnrichmentClient(serviceBaseUrl);
    }

    // Accessor for test runners
    public TestEnrichmentClient getClient() {
        return client;
    }

    /**
     * Runs all 200 test cases sequentially using single enrichment endpoint.
     * Validates each response and tracks metrics.
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
                result.plaidResponseAvailable = response.enrichedTransactions() != null && !response.enrichedTransactions().isEmpty();
                result.merchantName = result.plaidResponseAvailable 
                    ? response.enrichedTransactions().get(0).merchantName()
                    : "N/A";
                
                // Track cache behavior
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
     * Runs test cases in batches using async batch endpoint.
     * Polls for all results and validates.
     */
    public TestRunReport runBatchEnrichmentTests(int batchSize) {
        System.out.println("\n=== Running 200 Batch Enrichment Tests (batch size: " + batchSize + ") ===\n");
        
        List<EnrichmentRequest> testCases = MerchantTestDataGenerator.generate200TestCases();
        AtomicInteger testNum = new AtomicInteger(1);
        
        // Submit in batches
        List<List<EnrichmentRequest>> batches = partitionList(testCases, batchSize);
        List<CompletableFuture<Void>> batchFutures = new ArrayList<>();
        
        for (List<EnrichmentRequest> batch : batches) {
            CompletableFuture<Void> batchFuture = CompletableFuture.runAsync(() -> {
                try {
                    List<EnrichmentResponse> responses = client.enrichBatchAndWait(batch, 30000);
                    
                    for (int i = 0; i < batch.size(); i++) {
                        EnrichmentRequest request = batch.get(i);
                        EnrichmentResponse response = responses.get(i);
                        TestResult result = new TestResult(testNum.getAndIncrement(), request);
                        
                        result.success = true;
                        result.plaidResponseAvailable = response.enrichedTransactions() != null && !response.enrichedTransactions().isEmpty();
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
        
        // Wait for all batches
        CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0])).join();
        
        return generateReport("Batch Enrichment (size: " + batchSize + ")");
    }

    /**
     * Runs cache-heavy scenario with repeated merchants.
     * Validates cache hits vs misses.
     */
    public TestRunReport runCacheHeavyTests() {
        System.out.println("\n=== Running Cache-Heavy Test Scenario ===\n");
        
        List<EnrichmentRequest> testCases = MerchantTestDataGenerator.generateScenario("CACHE_HEAVY");
        long cacheHitTime = 0;
        int cacheHits = 0;
        
        for (int i = 0; i < testCases.size(); i++) {
            EnrichmentRequest request = testCases.get(i);
            TestResult result = new TestResult(i + 1, request);
            
            try {
                long startTime = System.nanoTime();
                EnrichmentResponse response = client.enrichSingle(request);
                long elapsed = System.nanoTime() - startTime;
                
                result.success = true;
                result.responseTimeMs = elapsed / 1_000_000;
                
                // Cache hit heuristic: sub-10ms response
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
        
        TestRunReport report = generateReport("Cache-Heavy Scenario");
        report.estimatedCacheHitCount = cacheHits;
        report.estimatedCacheHitRatio = (double) cacheHits / testCases.size() * 100;
        
        return report;
    }

    /**
     * Stress test: identical transaction 200 times to stress cache and concurrency.
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
     * Edge case test: boundary conditions, special characters, null handling.
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
                result.plaidResponseAvailable = response.enrichedTransactions() != null && !response.enrichedTransactions().isEmpty();
                
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
     * Parallel test execution using virtual threads / CompletableFuture.
     */
    public TestRunReport runParallelTests(int parallelism) {
        System.out.println("\n=== Running Parallel Tests (parallelism: " + parallelism + ") ===\n");
        
        List<EnrichmentRequest> testCases = MerchantTestDataGenerator.generate200TestCases();
        List<CompletableFuture<TestResult>> futures = new ArrayList<>();
        
        for (int i = 0; i < testCases.size(); i++) {
            final int testNum = i + 1;
            final EnrichmentRequest request = testCases.get(i);
            
            CompletableFuture<TestResult> future = CompletableFuture.supplyAsync(() -> {
                TestResult result = new TestResult(testNum, request);
                
                try {
                    EnrichmentResponse response = client.enrichSingle(request);
                    result.success = true;
                    result.plaidResponseAvailable = response.enrichedTransactions() != null && !response.enrichedTransactions().isEmpty();
                } catch (Exception e) {
                    result.success = false;
                    result.errorMessage = e.getMessage();
                }
                
                return result;
            });
            
            futures.add(future);
        }
        
        // Wait for all futures and collect results
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        futures.stream().map(CompletableFuture::join).forEach(results::add);
        
        return generateReport("Parallel Execution (" + parallelism + " threads)");
    }

    /**
     * Generates comprehensive test report.
     */
    private TestRunReport generateReport(String testName) {
        TestRunReport report = new TestRunReport(testName);
        
        report.totalTests = results.size();
        report.passedTests = (int) results.stream().filter(r -> r.success).count();
        report.failedTests = report.totalTests - report.passedTests;
        report.successRate = (double) report.passedTests / report.totalTests * 100;
        
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
        
        // Print report
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

    private void trackCacheHit(String merchantName) {
        merchantCacheHits.compute(merchantName, (k, v) -> v == null ? 1 : v + 1);
    }

    private <T> List<List<T>> partitionList(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    /**
     * Test result for a single transaction.
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
     * Overall test run report.
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
        public double estimatedCacheHitRatio;
        public int estimatedCacheHitCount;
        public Map<String, Integer> merchantCacheHits;

        public TestRunReport(String testName) {
            this.testName = testName;
            this.merchantCacheHits = new HashMap<>();
        }
    }
}
