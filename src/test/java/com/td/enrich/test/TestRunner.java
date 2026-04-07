package com.td.enrich.test;

/**
 * Example runner for the TD Enrich Service test harness.
 * 
 * This demonstrates how to run comprehensive test scenarios against a live service.
 * 
 * Prerequisites:
 * 1. Service running: ./mvnw spring-boot:run
 * 2. Compile test classes: ./mvnw compile test-compile
 * 3. Set CLASSPATH and run this class
 * 
 * Usage:
 * ```
 * java -cp "target/classes:target/test-classes:$(mvn dependency:build-classpath -q -Dmdep.outputFile=/dev/fd/1)" \
 *      com.td.enrich.test.TestRunner
 * ```
 */
public class TestRunner {

    public static void main(String[] args) {
        String serviceUrl = System.getProperty("service.url", "http://localhost:8080");
        System.out.println("\n╔════════════════════════════════════════════════════════════════════╗");
        System.out.println("║     TD Enrich Service - Comprehensive Test Suite                  ║");
        System.out.println("║     200 Merchant Transaction Test Cases with Scenarios            ║");
        System.out.println("╚════════════════════════════════════════════════════════════════════╝\n");
        System.out.println("Service URL: " + serviceUrl);
        System.out.println("Testing infrastructure ready.\n");

        EnrichmentTestHarness harness = new EnrichmentTestHarness(serviceUrl);

        // Check service health first
        System.out.println("Checking service health...");
        if (!harness.getClient().isHealthy()) {
            System.err.println("✗ Service not available at " + serviceUrl);
            System.err.println("\nPlease start the service with: ./mvnw spring-boot:run");
            System.exit(1);
        }
        System.out.println("✓ Service is healthy\n");

        // Run all test scenarios
        try {
            long startTime = System.currentTimeMillis();

            System.out.println("\n[1/6] Running Single Enrichment Tests (200 sequential calls)...");
            EnrichmentTestHarness.TestRunReport report1 = harness.runSingleEnrichmentTests();

            System.out.println("\n[2/6] Running Batch Enrichment Tests (batches of 20)...");
            EnrichmentTestHarness.TestRunReport report2 = harness.runBatchEnrichmentTests(20);

            System.out.println("\n[3/6] Running Cache-Heavy Test Scenario...");
            EnrichmentTestHarness.TestRunReport report3 = harness.runCacheHeavyTests();

            System.out.println("\n[4/6] Running Stress Test (200 identical transactions)...");
            EnrichmentTestHarness.TestRunReport report4 = harness.runStressTest();

            System.out.println("\n[5/6] Running Edge Case Tests...");
            EnrichmentTestHarness.TestRunReport report5 = harness.runEdgeCaseTests();

            System.out.println("\n[6/6] Running Parallel Execution Tests (10 threads)...");
            EnrichmentTestHarness.TestRunReport report6 = harness.runParallelTests(10);

            long totalDuration = System.currentTimeMillis() - startTime;

            // Final summary
            printFinalSummary(report1, report2, report3, report4, report5, report6, totalDuration);

        } catch (Exception e) {
            System.err.println("\n✗ Test execution failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printFinalSummary(
            EnrichmentTestHarness.TestRunReport r1,
            EnrichmentTestHarness.TestRunReport r2,
            EnrichmentTestHarness.TestRunReport r3,
            EnrichmentTestHarness.TestRunReport r4,
            EnrichmentTestHarness.TestRunReport r5,
            EnrichmentTestHarness.TestRunReport r6,
            long totalDurationMs) {

        System.out.println("\n" + "═".repeat(80));
        System.out.println("FINAL TEST SUMMARY - ALL 6 SCENARIOS COMPLETED");
        System.out.println("═".repeat(80) + "\n");

        printScenarioSummary("Single Enrichment", r1);
        printScenarioSummary("Batch Enrichment", r2);
        printScenarioSummary("Cache-Heavy", r3);
        printScenarioSummary("Stress Test", r4);
        printScenarioSummary("Edge Cases", r5);
        printScenarioSummary("Parallel Execution", r6);

        System.out.println("\nAGGREGATE STATISTICS:");
        System.out.println("─".repeat(80));

        int totalTests = r1.totalTests + r2.totalTests + r3.totalTests + r4.totalTests + r5.totalTests + r6.totalTests;
        int totalPassed = r1.passedTests + r2.passedTests + r3.passedTests + r4.passedTests + r5.passedTests + r6.passedTests;
        double overallSuccessRate = (double) totalPassed / totalTests * 100;

        double avgResponseTime = (r1.avgResponseTimeMs + r2.avgResponseTimeMs + r3.avgResponseTimeMs + 
                                  r4.avgResponseTimeMs + r5.avgResponseTimeMs + r6.avgResponseTimeMs) / 6;

        System.out.printf("Total Test Cases:     %d\n", totalTests);
        System.out.printf("Total Passed:         %d (%.1f%%)\n", totalPassed, overallSuccessRate);
        System.out.printf("Total Failed:         %d\n", totalTests - totalPassed);
        System.out.printf("Average Response Time: %.1f ms\n", avgResponseTime);
        System.out.printf("Total Duration:       %.1f seconds\n\n", totalDurationMs / 1000.0);

        if (overallSuccessRate >= 99.0) {
            System.out.println("✓ TEST SUITE PASSED - All scenarios completed successfully!");
        } else if (overallSuccessRate >= 95.0) {
            System.out.println("⚠ TEST SUITE PASSED WITH WARNINGS - Minor failures detected");
        } else {
            System.out.println("✗ TEST SUITE FAILED - Significant failures detected");
        }

        System.out.println("═".repeat(80) + "\n");
    }

    private static void printScenarioSummary(String name, EnrichmentTestHarness.TestRunReport report) {
        String status = report.successRate >= 99.0 ? "✓" : report.successRate >= 95.0 ? "⚠" : "✗";
        System.out.printf("%s %-30s %3d tests, %.1f%% success, %.1f ms avg\n",
            status,
            name + ":",
            report.totalTests,
            report.successRate,
            report.avgResponseTimeMs
        );
    }
}
