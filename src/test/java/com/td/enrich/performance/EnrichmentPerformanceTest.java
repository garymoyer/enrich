package com.td.enrich.performance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance (load) test that ramps to 100 TPS and sustains it for 5 minutes.
 *
 * <p><b>Purpose:</b> Verify that the service can sustain production-level throughput
 * against a real Plaid dev-environment API without exceeding the p99 latency budget
 * or error-rate threshold.
 *
 * <p><b>Test profile:</b> Activated with {@code @ActiveProfiles("perf")} so it picks
 * up the {@code application-perf.yml} configuration (real Plaid credentials, longer
 * timeouts, etc.). Run with:
 * <pre>{@code
 * ./mvnw test -Pperformance -Dspring.profiles.active=perf
 * }</pre>
 *
 * <p><b>Load shape:</b>
 * <ol>
 *   <li><b>Ramp phase (30 s):</b> TPS increases linearly from 1 req/s to
 *       {@link #TARGET_TPS} (100 req/s). This avoids slamming the service cold and
 *       lets the JVM JIT-compile hot paths.</li>
 *   <li><b>Sustained phase (300 s):</b> Holds at exactly {@link #TARGET_TPS} for
 *       5 minutes to expose memory leaks, connection pool exhaustion, or cache issues
 *       that only appear under prolonged load.</li>
 * </ol>
 *
 * <p><b>Pass/fail criteria:</b>
 * <ul>
 *   <li>All in-flight requests complete within 30 s of the scheduler stopping.</li>
 *   <li>Error rate &lt; 1% of total requests.</li>
 *   <li>p99 response time &lt; 5 000 ms.</li>
 * </ul>
 *
 * <p><b>Report:</b> After the test a formatted text report is written to
 * {@code target/performance-reports/perf-report-YYYYMMDD-HHmmss.txt} and printed
 * to stdout. The report includes a per-second TPS bar chart and full latency percentiles.
 *
 * <p>{@code @SpringBootTest(webEnvironment = RANDOM_PORT)} starts the full application
 * on an available port; {@code TestRestTemplate} sends real HTTP requests to it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("perf")
@Tag("performance")
@DisplayName("Enrichment Service Performance Test — live Plaid dev API")
class EnrichmentPerformanceTest {

    /** Target sustained throughput in requests per second. */
    static final int TARGET_TPS = 100;
    /** Duration of the linear ramp-up phase in seconds. */
    static final int RAMP_DURATION_SEC = 30;
    /** Duration of the sustained load phase in seconds. */
    static final int SUSTAINED_DURATION_SEC = 300;
    /** Total test duration = ramp + sustained. */
    static final int TOTAL_DURATION_SEC = RAMP_DURATION_SEC + SUSTAINED_DURATION_SEC;

    /** The random port chosen by Spring Boot — injected by the test framework. */
    @LocalServerPort
    int port;

    /**
     * TestRestTemplate is a test-friendly REST client pre-configured with the local
     * server port. Spring auto-configures and injects it.
     */
    @Autowired
    TestRestTemplate restTemplate;

    /** The Plaid API base URL from application-perf.yml — included in the report. */
    @Value("${plaid.api.base-url}")
    String plaidBaseUrl;

    /** HTTP headers pre-set to {@code Content-Type: application/json}. */
    HttpHeaders jsonHeaders;

    @BeforeEach
    void setUp() {
        jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
    }

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES) // 330 s test + 2 min safety margin
    @DisplayName("Ramp to 100 TPS over 30s then sustain for 5 minutes, reporting response times")
    void shouldSustain100TpsFor5Minutes() throws InterruptedException, IOException {
        PerformanceMetrics metrics = new PerformanceMetrics();
        long startMs = System.currentTimeMillis();
        AtomicBoolean done = new AtomicBoolean(false);

        // Scheduler fires once per second to submit that second's batch of requests
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        // Worker pool executes the actual HTTP calls; newCachedThreadPool scales up as needed
        ExecutorService workers = Executors.newCachedThreadPool();

        scheduler.scheduleAtFixedRate(() -> {
            long elapsedSec = (System.currentTimeMillis() - startMs) / 1000;
            if (elapsedSec >= TOTAL_DURATION_SEC) {
                done.set(true); // signal the polling loop below to stop
                return;
            }
            int targetTps = tpsForSecond(elapsedSec);
            for (int i = 0; i < targetTps; i++) {
                long capturedSec = elapsedSec; // capture for lambda — local variable must be effectively final
                workers.submit(() -> sendRequest(metrics, capturedSec));
            }
        }, 0, 1, TimeUnit.SECONDS);

        // Poll until the scheduler sets done = true
        while (!done.get()) {
            Thread.sleep(500);
        }
        scheduler.shutdownNow();
        workers.shutdown();
        // Wait up to 30 s for in-flight requests to finish — this is the "clean shutdown" window
        boolean cleanShutdown = workers.awaitTermination(30, TimeUnit.SECONDS);

        long actualDurationSec = (System.currentTimeMillis() - startMs) / 1000;
        String report = buildReport(metrics, actualDurationSec);
        printReport(report);
        writeReport(report, startMs);

        // ── Pass/fail assertions ──────────────────────────────────────────────
        assertThat(cleanShutdown).as("All in-flight requests completed within shutdown window").isTrue();
        double errorRate = metrics.totalRequests() > 0
            ? (double) metrics.failureCount() / metrics.totalRequests()
            : 1.0;
        assertThat(errorRate).as("Error rate must be below 1%%").isLessThan(0.01);
        assertThat(metrics.percentile(99)).as("p99 response time must be below 5000ms").isLessThan(5000L);
    }

    // ── Rate control ──────────────────────────────────────────────────────────

    /**
     * Returns the target TPS for the given elapsed second.
     *
     * <ul>
     *   <li>Second 0: 1 req/s (avoids a flood on the very first tick)</li>
     *   <li>Seconds 1–29: linear ramp from 1 to {@link #TARGET_TPS}</li>
     *   <li>Second 30+: constant {@link #TARGET_TPS}</li>
     * </ul>
     *
     * @param elapsedSec seconds elapsed since test start
     * @return number of requests to submit in this second
     */
    private int tpsForSecond(long elapsedSec) {
        if (elapsedSec == 0) return 1;
        if (elapsedSec < RAMP_DURATION_SEC) {
            return (int) Math.round(TARGET_TPS * (double) elapsedSec / RAMP_DURATION_SEC);
        }
        return TARGET_TPS;
    }

    // ── HTTP execution ────────────────────────────────────────────────────────

    /**
     * Sends a single enrichment request and records the result in {@code metrics}.
     *
     * <p>Runs on a worker thread. Catches all exceptions so a single failed request
     * doesn't interrupt the worker pool.
     *
     * @param metrics    the shared metrics collector
     * @param elapsedSec the elapsed second at submission time (for TPS chart)
     */
    private void sendRequest(PerformanceMetrics metrics, long elapsedSec) {
        long start = System.currentTimeMillis();
        try {
            HttpEntity<?> entity = new HttpEntity<>(
                PerformanceTestDataFactory.createRandomSingleRequest(), jsonHeaders);
            ResponseEntity<String> response =
                restTemplate.postForEntity("/api/v1/enrich", entity, String.class);
            long elapsed = System.currentTimeMillis() - start;
            if (response.getStatusCode().is2xxSuccessful()) {
                metrics.recordSuccess(elapsed, elapsedSec);
            } else {
                metrics.recordFailure(elapsedSec);
            }
        } catch (Exception e) {
            metrics.recordFailure(elapsedSec);
        }
    }

    // ── Report generation ─────────────────────────────────────────────────────

    /**
     * Assembles the complete performance report as a formatted string.
     *
     * <p>The report includes test configuration, result summary, latency percentiles,
     * and a sampled per-second TPS bar chart. The chart is sampled (every second during
     * ramp, every 5th second during sustained) to keep the report readable for a
     * 5-minute run.
     *
     * @param metrics         the collected test metrics
     * @param durationSec     actual test duration in seconds
     * @return the full report text
     */
    private String buildReport(PerformanceMetrics metrics, long durationSec) {
        long total = metrics.totalRequests();
        long success = metrics.successCount();
        long failure = metrics.failureCount();
        double errorPct = total > 0 ? 100.0 * failure / total : 0;
        double successPct = total > 0 ? 100.0 * success / total : 0;

        String line = "=".repeat(72);
        String thin = "-".repeat(72);

        return line + "\n" +
            "  ENRICHMENT SERVICE PERFORMANCE TEST REPORT\n" +
            line + "\n\n" +
            "  Generated : " + ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\n" +
            "  Started   : " + metrics.startTime() + "\n\n" +
            thin + "\n" +
            "  TEST CONFIGURATION\n" +
            thin + "\n" +
            String.format("  Plaid API Target  : %s%n", plaidBaseUrl) +
            String.format("  Target TPS        : %d req/s%n", TARGET_TPS) +
            String.format("  Ramp Duration     : %d seconds%n", RAMP_DURATION_SEC) +
            String.format("  Sustained Duration: %d seconds%n", SUSTAINED_DURATION_SEC) +
            String.format("  Actual Duration   : %d seconds%n%n", durationSec) +
            thin + "\n" +
            "  RESULTS SUMMARY\n" +
            thin + "\n" +
            String.format("  Total Requests    : %,d%n", total) +
            String.format("  Successful        : %,d  (%.1f%%)%n", success, successPct) +
            String.format("  Failed            : %,d  (%.1f%%)%n", failure, errorPct) +
            String.format("  Actual Avg TPS    : %.2f req/s%n%n", metrics.actualTps(durationSec)) +
            thin + "\n" +
            "  RESPONSE TIME PERCENTILES\n" +
            thin + "\n" +
            String.format("  Min               : %d ms%n", metrics.minResponseTime()) +
            String.format("  Mean              : %.1f ms%n", metrics.meanResponseTime()) +
            String.format("  p50 (Median)      : %d ms%n", metrics.percentile(50)) +
            String.format("  p75               : %d ms%n", metrics.percentile(75)) +
            String.format("  p90               : %d ms%n", metrics.percentile(90)) +
            String.format("  p95               : %d ms%n", metrics.percentile(95)) +
            String.format("  p99               : %d ms%n", metrics.percentile(99)) +
            String.format("  p99.9             : %d ms%n", metrics.percentile(99.9)) +
            String.format("  Max               : %d ms%n%n", metrics.maxResponseTime()) +
            thin + "\n" +
            "  THROUGHPUT OVER TIME (requests / second) — sampled per second\n" +
            "  Format: <elapsed>s [phase] <actual> | bar (max = " + TARGET_TPS + " req/s)\n" +
            thin + "\n" +
            renderSampledChart(metrics) +
            "\n" + line + "\n";
    }

    /**
     * Renders the per-second TPS chart, sampling every second during the ramp phase
     * and every 5th second during the sustained phase to keep the report concise.
     *
     * <p>Each bar is scaled to 40 characters where the full bar width represents
     * {@link #TARGET_TPS} requests/second.
     *
     * @param metrics the collected metrics
     * @return multi-line ASCII chart string
     */
    private String renderSampledChart(PerformanceMetrics metrics) {
        Map<Long, Long> data = new TreeMap<>(metrics.perSecondThroughput());
        if (data.isEmpty()) return "  (no data)\n";

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Long> entry : data.entrySet()) {
            long sec = entry.getKey();
            // Print every second during ramp; skip non-multiples-of-5 during sustained
            if (sec >= RAMP_DURATION_SEC && sec % 5 != 0) continue;

            long tps = entry.getValue();
            int bars = (int) Math.round(tps * 40.0 / TARGET_TPS);
            bars = Math.min(bars, 40); // never exceed chart width
            String phase = sec < RAMP_DURATION_SEC ? "RAMP" : "SUST";
            sb.append(String.format("  %4ds [%s] %4d | %s%n",
                sec, phase, tps, "#".repeat(bars)));
        }
        return sb.toString();
    }

    private void printReport(String report) {
        System.out.println("\n" + report);
    }

    /**
     * Writes the report to {@code target/performance-reports/perf-report-<timestamp>.txt}.
     * Creates the directory if it doesn't exist.
     */
    private void writeReport(String report, long startMs) throws IOException {
        Path dir = Paths.get("target", "performance-reports");
        Files.createDirectories(dir);
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = dir.resolve("perf-report-" + timestamp + ".txt");
        Files.writeString(file, report);
        System.out.printf("  Performance report written to: %s%n%n", file.toAbsolutePath());
    }
}
