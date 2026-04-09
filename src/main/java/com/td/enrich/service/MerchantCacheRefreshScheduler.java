package com.td.enrich.service;

import com.td.enrich.config.EnrichCacheProperties;
import com.td.enrich.domain.MerchantCacheEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Scheduled job that re-enriches merchant cache entries whose Plaid data has gone stale.
 *
 * <p><b>Why this exists:</b> Merchant data changes over time — logos get updated,
 * categories get reassigned, official names change. Without a refresh mechanism, an
 * entry written once would serve stale data forever. This scheduler finds entries older
 * than the configured TTL and re-submits them to the {@link EnrichmentQueueProcessor}
 * so the background workers fetch fresh data from Plaid asynchronously.
 *
 * <p><b>How it works:</b>
 * <ol>
 *   <li>Runs once per day at 02:00 UTC (configurable via Spring cron expression).</li>
 *   <li>Queries the {@code merchant_cache} table for ENRICHED rows where
 *       {@code last_enriched_at} is older than {@code now - ttlDays}.</li>
 *   <li>For each stale entry, builds an {@link EnrichmentQueueProcessor.EnrichmentTask}
 *       and calls {@link EnrichmentQueueProcessor#enqueue}. The worker pool picks them
 *       up asynchronously — the scheduler does not wait for Plaid responses.</li>
 *   <li>If {@code ttl-days} is set to {@code 0}, the entire job is a no-op (useful
 *       in test environments to avoid background scheduler noise).</li>
 * </ol>
 *
 * <p><b>Back-pressure:</b> If the queue is full, {@link EnrichmentQueueProcessor#enqueue}
 * returns {@code false} and the entry is skipped. It will be re-queued on the next
 * scheduler run (next day), so no entries are permanently lost.
 *
 * <p><b>Enabling scheduling:</b> This class relies on {@code @EnableScheduling} being
 * present somewhere in the application context. It is declared on
 * {@link com.td.enrich.EnrichServiceApplication}.
 */
@Component
@Slf4j
public class MerchantCacheRefreshScheduler {

    private final MerchantCacheRepository merchantCacheRepository;
    private final EnrichmentQueueProcessor queueProcessor;
    private final int ttlDays;

    /**
     * Spring injects all dependencies via this constructor.
     *
     * @param merchantCacheRepository repository for querying stale cache entries
     * @param queueProcessor          the background enrichment queue to re-submit tasks to
     * @param properties              cache config — reads {@code enrich.cache.ttl-days}
     */
    public MerchantCacheRefreshScheduler(MerchantCacheRepository merchantCacheRepository,
                                          EnrichmentQueueProcessor queueProcessor,
                                          EnrichCacheProperties properties) {
        this.merchantCacheRepository = merchantCacheRepository;
        this.queueProcessor = queueProcessor;
        this.ttlDays = properties.getTtlDays();
    }

    /**
     * Finds all stale cache entries and re-queues them for Plaid enrichment.
     *
     * <p>Runs daily at 02:00 UTC. The off-peak time reduces contention with normal
     * request traffic. The cron expression is {@code "0 0 2 * * *"}:
     * {@code second=0, minute=0, hour=2, day=any, month=any, weekday=any}.
     *
     * <p>If {@code ttl-days = 0} the method returns immediately — this allows the
     * scheduler bean to exist (so it compiles and tests can inject it) without actually
     * running any queries in environments where TTL is disabled.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void refreshStaleEntries() {
        if (ttlDays <= 0) {
            log.debug("Cache TTL refresh is disabled (ttl-days={}); skipping", ttlDays);
            return;
        }

        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(ttlDays);
        log.info("Starting merchant cache TTL refresh: re-queuing entries older than {} days (before {})",
                ttlDays, cutoff);

        List<MerchantCacheEntity> staleEntries =
                merchantCacheRepository.findByStatusAndLastEnrichedAtBefore("ENRICHED", cutoff);

        if (staleEntries.isEmpty()) {
            log.info("No stale merchant cache entries found");
            return;
        }

        int queued = 0;
        int dropped = 0;

        for (MerchantCacheEntity entry : staleEntries) {
            // Build a minimal task — amount and date are not stored in the cache entity,
            // so we use placeholder values. Plaid uses description and merchantName as the
            // primary enrichment signals; amount and date improve accuracy but are optional.
            EnrichmentQueueProcessor.EnrichmentTask task = new EnrichmentQueueProcessor.EnrichmentTask(
                    entry.getMerchantId(),
                    entry.getDescription(),
                    entry.getMerchantName(),
                    BigDecimal.ZERO,      // amount not stored — Plaid enriches by description
                    LocalDate.now(),      // date not stored — use today as a placeholder
                    "refresh-scheduler"   // accountId not stored — use a sentinel value
            );

            if (queueProcessor.enqueue(task)) {
                queued++;
            } else {
                // Queue is full — this entry will be retried on the next scheduled run
                dropped++;
            }
        }

        log.info("Merchant cache TTL refresh complete: {} entries queued, {} dropped (queue full)",
                queued, dropped);
    }
}
