package com.plaid.enrich.performance;

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
 * All recording methods are safe to call concurrently from virtual threads.
 */
final class PerformanceMetrics {

    private final Instant startTime = Instant.now();
    private final ConcurrentLinkedDeque<Long> responseTimes = new ConcurrentLinkedDeque<>();
    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();
    private final ConcurrentHashMap<Long, AtomicLong> perSecondRequests = new ConcurrentHashMap<>();

    void recordSuccess(long responseTimeMs, long elapsedSeconds) {
        responseTimes.add(responseTimeMs);
        successCount.incrementAndGet();
        perSecondRequests.computeIfAbsent(elapsedSeconds, k -> new AtomicLong()).incrementAndGet();
    }

    void recordFailure(long elapsedSeconds) {
        failureCount.incrementAndGet();
        perSecondRequests.computeIfAbsent(elapsedSeconds, k -> new AtomicLong()).incrementAndGet();
    }

    long totalRequests() {
        return successCount.get() + failureCount.get();
    }

    long successCount() { return successCount.get(); }
    long failureCount() { return failureCount.get(); }
    Instant startTime() { return startTime; }

    double actualTps(long durationSeconds) {
        return durationSeconds > 0 ? (double) totalRequests() / durationSeconds : 0;
    }

    long percentile(double p) {
        List<Long> sorted = new ArrayList<>(responseTimes);
        if (sorted.isEmpty()) return 0;
        Collections.sort(sorted);
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    long minResponseTime() {
        return responseTimes.stream().mapToLong(Long::longValue).min().orElse(0);
    }

    long maxResponseTime() {
        return responseTimes.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    double meanResponseTime() {
        return responseTimes.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    Map<Long, Long> perSecondThroughput() {
        return perSecondRequests.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }

    String renderThroughputChart(int rampDurationSec) {
        Map<Long, Long> data = new TreeMap<>(perSecondThroughput());
        if (data.isEmpty()) return "  (no data)\n";

        long maxTps = data.values().stream().mapToLong(Long::longValue).max().orElse(1);
        int barWidth = 40;
        StringBuilder sb = new StringBuilder();

        for (Map.Entry<Long, Long> entry : data.entrySet()) {
            long sec = entry.getKey();
            long tps = entry.getValue();
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
