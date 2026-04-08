package com.td.enrich.test;

/**
 * Command-line entry point for the TD Enrich Service manual test harness.
 *
 * <p>This class is NOT a JUnit test — it has a standard {@code main} method and is
 * run directly from the command line against a <em>live, already-running</em> service
 * instance.  Use it for exploratory testing, smoke testing after a deployment, or
 * demonstrating the service to stakeholders.
 *
 * <h2>Prerequisites</h2>
 * <ol>
 *   <li>Start the service: {@code ./mvnw spring-boot:run}</li>
 *   <li>Compile test classes: {@code ./mvnw compile test-compile}</li>
 *   <li>Run this class with the full classpath (see below).</li>
 * </ol>
 *
 * <h2>How to run</h2>
 * <pre>{@code
 * java -cp "target/classes:target/test-classes:$(mvn dependency:build-classpath -q -Dmdep.outputFile=/dev/fd/1)" \
 *      com.td.enrich.test.TestRunner
 * }</pre>
 *
 * <p>To point at a non-local service, override the {@code service.url} system property:
 * <pre>{@code
 * java -Dservice.url=https://enrich-staging.example.com \
 *      -cp "..." com.td.enrich.test.TestRunner
 * }</pre>
 *
 * <h2>What it does</h2>
 * <p>The runner executes all 6 scenarios in {@link EnrichmentTestHarness} in sequence
 * and prints a per-scenario summary table followed by aggregate statistics.  The exit
 * code is {@code 0} on success and {@code 1} on any failure, making it usable in a
 * CI smoke-test step (with the understanding that it requires a live service).
 *
 * <h2>Pass/fail thresholds</h2>
 * <ul>
 *   <li><b>PASSED</b> — overall success rate &ge; 99%</li>
 *   <li><b>PASSED WITH WARNINGS</b> — overall success rate &ge; 95% but &lt; 99%</li>
 *   <li><b>FAILED</b> — overall success rate &lt; 95%</li>
 * </ul>
 */
public class TestRunner {

    /**
     * Runs all 6 test scenarios against the service and prints a final summary.
     *
     * <p>Flow:
     * <ol>
     *   <li>Read the {@code service.url} system property (default: {@code http://localhost:8080}).</li>
     *   <li>Perform a health check — exit immediately with code {@code 1} if the service
     *       is not reachable.  This prevents confusing "all tests failed" output when the
     *       real problem is simply that the service isn't running.</li>
     *   <li>Run scenarios 1–6 in order, printing progress as each completes.</li>
     *   <li>Call {@link #printFinalSummary} to display aggregate statistics.</li>
     * </ol>
     *
     * @param args command-line arguments (not used; configuration is via system properties)
     */
    public static void main(String[] args) {
        // Read the target URL from the system property, defaulting to localhost
        String serviceUrl = System.getProperty("service.url", "http://localhost:8080");

        System.out.println("\n╔════════════════════════════════════════════════════════════════════╗");
        System.out.println("║     TD Enrich Service - Comprehensive Test Suite                  ║");
        System.out.println("║     200 Merchant Transaction Test Cases with Scenarios            ║");
        System.out.println("╚════════════════════════════════════════════════════════════════════╝\n");
        System.out.println("Service URL: " + serviceUrl);
        System.out.println("Testing infrastructure ready.\n");

        EnrichmentTestHarness harness = new EnrichmentTestHarness(serviceUrl);

        // ── Health check ──────────────────────────────────────────────────────────
        // Always check health before running tests.  If the service is down, every
        // test will fail with a connection error, which is misleading.  A clear
        // "service not available" message is much easier to diagnose.
        System.out.println("Checking service health...");
        if (!harness.getClient().isHealthy()) {
            System.err.println("✗ Service not available at " + serviceUrl);
            System.err.println("\nPlease start the service with: ./mvnw spring-boot:run");
            System.exit(1); // non-zero exit code signals failure to calling scripts
        }
        System.out.println("✓ Service is healthy\n");

        // ── Run all 6 scenarios ───────────────────────────────────────────────────
        try {
            // Record start time so the final summary can report total wall-clock duration
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

            // Print the consolidated summary across all 6 scenarios
            printFinalSummary(report1, report2, report3, report4, report5, report6, totalDuration);

        } catch (Exception e) {
            // A thrown exception means a scenario couldn't even start (e.g. network error
            // during test submission), not just a high failure rate within a scenario.
            System.err.println("\n✗ Test execution failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Prints the final aggregated summary across all 6 scenario reports.
     *
     * <p>The output has two sections:
     * <ol>
     *   <li>A per-scenario one-liner (pass/warn/fail icon, name, test count, success
     *       rate, average response time) produced by {@link #printScenarioSummary}.</li>
     *   <li>Aggregate totals: sum of all tests, pass count, fail count, average
     *       response time across all scenarios, and total wall-clock duration.</li>
     * </ol>
     *
     * <p>The overall result badge follows the thresholds in the class-level Javadoc.
     *
     * @param r1              report from scenario 1 (single enrichment)
     * @param r2              report from scenario 2 (batch enrichment)
     * @param r3              report from scenario 3 (cache-heavy)
     * @param r4              report from scenario 4 (stress test)
     * @param r5              report from scenario 5 (edge cases)
     * @param r6              report from scenario 6 (parallel execution)
     * @param totalDurationMs total elapsed wall-clock time in milliseconds
     */
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

        // Print one summary line per scenario
        printScenarioSummary("Single Enrichment",  r1);
        printScenarioSummary("Batch Enrichment",   r2);
        printScenarioSummary("Cache-Heavy",         r3);
        printScenarioSummary("Stress Test",         r4);
        printScenarioSummary("Edge Cases",          r5);
        printScenarioSummary("Parallel Execution",  r6);

        System.out.println("\nAGGREGATE STATISTICS:");
        System.out.println("─".repeat(80));

        // Sum test counts and compute an overall success rate
        int totalTests  = r1.totalTests  + r2.totalTests  + r3.totalTests
                        + r4.totalTests  + r5.totalTests  + r6.totalTests;
        int totalPassed = r1.passedTests + r2.passedTests + r3.passedTests
                        + r4.passedTests + r5.passedTests + r6.passedTests;
        double overallSuccessRate = (double) totalPassed / totalTests * 100;

        // Average response time across scenarios — note this is an average-of-averages,
        // not a true weighted average, but it's sufficient for a quick health check
        double avgResponseTime = (r1.avgResponseTimeMs + r2.avgResponseTimeMs + r3.avgResponseTimeMs
                                + r4.avgResponseTimeMs + r5.avgResponseTimeMs + r6.avgResponseTimeMs) / 6;

        System.out.printf("Total Test Cases:     %d\n", totalTests);
        System.out.printf("Total Passed:         %d (%.1f%%)\n", totalPassed, overallSuccessRate);
        System.out.printf("Total Failed:         %d\n", totalTests - totalPassed);
        System.out.printf("Average Response Time: %.1f ms\n", avgResponseTime);
        System.out.printf("Total Duration:       %.1f seconds\n\n", totalDurationMs / 1000.0);

        // Overall pass/fail verdict based on the thresholds documented in the class Javadoc
        if (overallSuccessRate >= 99.0) {
            System.out.println("✓ TEST SUITE PASSED - All scenarios completed successfully!");
        } else if (overallSuccessRate >= 95.0) {
            System.out.println("⚠ TEST SUITE PASSED WITH WARNINGS - Minor failures detected");
        } else {
            System.out.println("✗ TEST SUITE FAILED - Significant failures detected");
        }

        System.out.println("═".repeat(80) + "\n");
    }

    /**
     * Prints a single-line status summary for one scenario.
     *
     * <p>The status icon encodes the result at a glance:
     * <ul>
     *   <li>{@code ✓} — success rate &ge; 99%</li>
     *   <li>{@code ⚠} — success rate &ge; 95% but &lt; 99%</li>
     *   <li>{@code ✗} — success rate &lt; 95%</li>
     * </ul>
     * Example output:
     * <pre>
     * ✓ Single Enrichment:           200 tests, 100.0% success, 42.3 ms avg
     * ⚠ Edge Cases:                  200 tests,  96.5% success, 87.1 ms avg
     * </pre>
     *
     * @param name   human-readable scenario label
     * @param report the scenario's result report
     */
    private static void printScenarioSummary(String name, EnrichmentTestHarness.TestRunReport report) {
        // Choose an icon based on the success rate thresholds
        String status = report.successRate >= 99.0 ? "✓"
                      : report.successRate >= 95.0 ? "⚠"
                      : "✗";

        System.out.printf("%s %-30s %3d tests, %.1f%% success, %.1f ms avg\n",
            status,
            name + ":",           // pad the name to 30 chars so columns line up
            report.totalTests,
            report.successRate,
            report.avgResponseTimeMs
        );
    }
}
