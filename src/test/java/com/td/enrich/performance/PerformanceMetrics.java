package com.td.enrich.performance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Thread-safe collector for performance test metrics.
 *
 * <p>All recording methods ({@link #recordSuccess}, {@link #recordFailure}) are safe
 * to call concurrently from many threads simultaneously. This is critical because the
 * performance test submits requests from a scheduler and a pool of worker threads,
 * all of which report results back to this single metrics instance.
 *
 * <p><b>Data structures used:</b>
 * <ul>
 *   <li>{@link ConcurrentLinkedDeque} for response times — lock-free, supports concurrent
 *       appends; sorting is deferred until a percentile is requested.</li>
 *   <li>{@link AtomicLong} for success/failure counters — single-value counters that
 *       update without locks via CPU compare-and-swap instructions.</li>
 *   <li>{@link ConcurrentHashMap} keyed by elapsed second — allows multiple threads to
 *       record their per-second throughput without contention.</li>
 * </ul>
 */
final class PerformanceMetrics {

    /** The instant this metrics collector was created; used to label the report. */
    private final Instant startTime = Instant.now();

    /** All successful response times in milliseconds, appended concurrently. */
    private final ConcurrentLinkedDeque<Long> responseTimes = new ConcurrentLinkedDeque<>();

    /** Total number of requests that received a 2xx HTTP response. */
    private final AtomicLong successCount = new AtomicLong();

    /** Total number of requests that received a non-2xx response or threw an exception. */
    private final AtomicLong failureCount = new AtomicLong();

    /**
     * Per-second request count. Key = elapsed seconds since test start; Value = request count.
     * Used to render the throughput-over-time chart in the report.
     */
    private final ConcurrentHashMap<Long, AtomicLong> perSecondRequests = new ConcurrentHashMap<>();

    /**
     * Records a successful request.
     *
     * @param responseTimeMs the end-to-end response time in milliseconds
     * @param elapsedSeconds the elapsed test time in seconds (used for TPS chart)
     */
    void recordSuccess(long responseTimeMs, long elapsedSeconds) {
        responseTimes.add(responseTimeMs);
        successCount.incrementAndGet();
        // computeIfAbsent atomically creates the bucket if it doesn't exist yet
        perSecondRequests.computeIfAbsent(elapsedSeconds, k -> new AtomicLong()).incrementAndGet();
    }

    /**
     * Records a failed request (non-2xx response or exception).
     *
     * @param elapsedSeconds the elapsed test time in seconds (used for TPS chart)
     */
    void recordFailure(long elapsedSeconds) {
        failureCount.incrementAndGet();
        perSecondRequests.computeIfAbsent(elapsedSeconds, k -> new AtomicLong()).incrementAndGet();
    }

    /** Returns the total number of requests attempted (successful + failed). */
    long totalRequests() {
        return successCount.get() + failureCount.get();
    }

    long successCount() { return successCount.get(); }
    long failureCount() { return failureCount.get(); }
    Instant startTime() { return startTime; }

    /**
     * Computes the average throughput in requests per second over the test duration.
     *
     * @param durationSeconds the actual test duration in seconds
     * @return requests per second, or 0 if duration is 0
     */
    double actualTps(long durationSeconds) {
        return durationSeconds > 0 ? (double) totalRequests() / durationSeconds : 0;
    }

    /**
     * Returns the response time at the given percentile.
     *
     * <p>For example, {@code percentile(99)} returns the value below which 99% of
     * recorded response times fall — the p99 latency. This is the standard way to
     * measure tail latency in performance testing.
     *
     * @param p the percentile in the range (0, 100]; e.g. 50, 95, 99, 99.9
     * @return the response time at that percentile in milliseconds, or 0 if no data
     */
    long percentile(double p) {
        List<Long> sorted = new ArrayList<>(responseTimes);
        if (sorted.isEmpty()) return 0;
        Collections.sort(sorted);
        // Math.ceil rounds up so index is always valid; -1 converts 1-based to 0-based
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    /** Returns the minimum response time recorded, or 0 if no data. */
    long minResponseTime() {
        return responseTimes.stream().mapToLong(Long::longValue).min().orElse(0);
    }

    /** Returns the maximum response time recorded, or 0 if no data. */
    long maxResponseTime() {
        return responseTimes.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    /** Returns the arithmetic mean response time, or 0 if no data. */
    double meanResponseTime() {
        return responseTimes.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    /**
     * Returns a snapshot of per-second throughput as an immutable map.
     * Key = elapsed seconds; Value = number of requests in that second.
     */
    Map<Long, Long> perSecondThroughput() {
        return perSecondRequests.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }

    /**
     * Renders the per-second throughput as a horizontal bar chart for inclusion in logs.
     *
     * <p>Each line shows the elapsed second, the test phase (RAMP or SUST), the actual
     * request count, and a proportional bar scaled to {@code barWidth} characters.
     *
     * @param rampDurationSec seconds of the ramp phase; determines which phase label to use
     * @return multi-line ASCII bar chart string
     */
    String renderThroughputChart(int rampDurationSec) {
        Map<Long, Long> data = new TreeMap<>(perSecondThroughput()); // TreeMap gives sorted order
        if (data.isEmpty()) return "  (no data)\n";

        long maxTps = data.values().stream().mapToLong(Long::longValue).max().orElse(1);
        int barWidth = 40;
        StringBuilder sb = new StringBuilder();

        for (Map.Entry<Long, Long> entry : data.entrySet()) {
            long sec = entry.getKey();
            long tps = entry.getValue();
            // Scale bar length proportionally to the peak TPS observed
            int bars = (int) (tps * barWidth / maxTps);
            String phase = sec < rampDurationSec ? "RAMP" : "SUST";
            sb.append(String.format("  %4ds [%s] %3d | %s %s%n",
                sec,
                phase,
                tps,
                "#".repeat(bars),
                " ".repeat(Math.max(0, barWidth - bars))));
        }
        return sb.toString();
    }
}
