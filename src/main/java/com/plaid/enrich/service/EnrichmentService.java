package com.plaid.enrich.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plaid.enrich.domain.*;
import com.plaid.enrich.util.GuidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for orchestrating transaction enrichment.
 *
 * <h3>Hot-path design</h3>
 * <p>All enrichment requests go through the in-memory {@link MerchantMemoryCache}:
 * <ul>
 *   <li><b>Cache hit (ENRICHED)</b> — returns the stored Plaid data immediately.
 *       Plaid is never called. Latency is O(1) hash lookup, typically sub-microsecond.</li>
 *   <li><b>Cache hit (PENDING)</b> — a prior request already created a stub but Plaid hasn't
 *       responded yet. Returns the stub merchantId instantly; the caller receives PENDING metadata.</li>
 *   <li><b>Cache miss</b> — atomically creates a PENDING stub in both the DB and the memory cache
 *       (via {@link MerchantMemoryCache#getOrCreate}), returns the new merchantId instantly, then
 *       enqueues a background {@link EnrichmentQueueProcessor.EnrichmentTask}. The queue processor
 *       calls Plaid asynchronously and updates DB + cache on completion.</li>
 * </ul>
 *
 * <h3>Latency logging</h3>
 * <p>Each transaction logs {@code [CACHE HIT]} or {@code [CACHE MISS]} with latency in nanoseconds
 * so cache performance is visible in structured logs and test output.
 *
 * <h3>Atomicity of stub creation</h3>
 * <p>The DB stub is committed in its own {@code REQUIRES_NEW} transaction before the cache entry
 * is visible. This ensures that a restart after a crash will not leave orphaned in-memory entries.
 */
@Service
@Slf4j
public class EnrichmentService {

    private final EnrichmentRepository enrichmentRepository;
    private final MerchantCacheRepository merchantCacheRepository;
    private final MerchantMemoryCache memoryCache;
    private final EnrichmentQueueProcessor queueProcessor;
    private final GuidGenerator guidGenerator;
    private final ObjectMapper objectMapper;
    /** Separate transaction for stub creation so it commits before we return to the caller. */
    private final TransactionTemplate requiresNewTx;

    public EnrichmentService(EnrichmentRepository enrichmentRepository,
                             MerchantCacheRepository merchantCacheRepository,
                             MerchantMemoryCache memoryCache,
                             EnrichmentQueueProcessor queueProcessor,
                             GuidGenerator guidGenerator,
                             ObjectMapper objectMapper,
                             PlatformTransactionManager transactionManager) {
        this.enrichmentRepository = enrichmentRepository;
        this.merchantCacheRepository = merchantCacheRepository;
        this.memoryCache = memoryCache;
        this.queueProcessor = queueProcessor;
        this.guidGenerator = guidGenerator;
        this.objectMapper = objectMapper;
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTx = tx;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Enriches transactions for a single request.
     * Returns immediately after resolving merchantIds from cache/stub — no blocking Plaid call.
     */
    @Transactional
    public EnrichmentResponse enrichTransactions(EnrichmentRequest request) {
        String requestId = guidGenerator.generate();
        log.info("Starting enrichment for requestId={}", requestId);
        try {
            persistRequest(requestId, request, "PENDING");
            EnrichmentResponse response = enrichCore(requestId, request);
            log.info("Completed enrichment for requestId={} transactions={}", requestId,
                    response.enrichedTransactions().size());
            return response;
        } catch (Exception ex) {
            log.error("Error enriching requestId={}", requestId, ex);
            persistStatus(requestId, "FAILED", ex.getMessage());
            return new EnrichmentResponse(requestId, List.of(), OffsetDateTime.now(), "FAILED", ex.getMessage());
        }
    }

    /**
     * Enriches multiple transaction batches in parallel.
     * Each batch gets its own requestId and is processed independently.
     */
    @Transactional
    public List<EnrichmentResponse> enrichTransactionsBatch(List<EnrichmentRequest> requests) {
        log.info("Starting batch enrichment for {} requests", requests.size());

        List<BatchItem> items = requests.stream()
                .map(req -> {
                    String requestId = guidGenerator.generate();
                    persistRequest(requestId, req, "PENDING");
                    return new BatchItem(requestId, req);
                })
                .collect(Collectors.toList());

        List<EnrichmentResponse> responses = Flux.fromIterable(items)
                .flatMap(item -> processBatchItem(item))
                .collectList()
                .block();

        log.info("Completed batch enrichment: {}/{} successful",
                responses.stream().filter(r -> "SUCCESS".equals(r.status())).count(),
                responses.size());
        return responses;
    }

    /**
     * Retrieves an enrichment record by requestId.
     * Uses the memory cache for transaction lookup; falls back to DB when entries were evicted.
     */
    @Transactional(readOnly = true)
    public Optional<EnrichmentResponse> getEnrichmentById(String requestId) {
        log.debug("Retrieving enrichment record: {}", requestId);
        return enrichmentRepository.findById(requestId)
                .map(entity -> {
                    if ("SUCCESS".equals(entity.getStatus())) {
                        try {
                            EnrichmentRequest originalRequest = objectMapper.readValue(
                                    entity.getOriginalRequest(), EnrichmentRequest.class);
                            return new EnrichmentResponse(
                                    requestId,
                                    buildEnrichedTransactionsFromCache(originalRequest),
                                    entity.getCreatedAt(),
                                    "SUCCESS"
                            );
                        } catch (JsonProcessingException e) {
                            log.error("Error parsing stored request for {}", requestId, e);
                            throw new RuntimeException("Error retrieving enrichment data", e);
                        }
                    }
                    return new EnrichmentResponse(
                            requestId, List.of(), entity.getCreatedAt(),
                            entity.getStatus(), entity.getErrorMessage()
                    );
                });
    }

    // ── Core enrichment ──────────────────────────────────────────────────────

    /**
     * Resolves a merchantId for every transaction via the memory cache.
     * New merchants get a PENDING stub committed atomically before the method returns.
     */
    private EnrichmentResponse enrichCore(String requestId, EnrichmentRequest request) {
        List<EnrichmentResponse.EnrichedTransaction> enrichedTransactions = new ArrayList<>();

        for (EnrichmentRequest.Transaction tx : request.transactions()) {
            long start = System.nanoTime();
            String mn = nvl(tx.merchantName());

            MerchantMemoryCache.GetOrCreateResult result =
                    memoryCache.getOrCreate(tx.description(), mn, () -> createStub(tx.description(), mn));

            MerchantCacheEntry entry = result.entry();
            long latencyNs = System.nanoTime() - start;

            if (result.created()) {
                log.info("[CACHE MISS] desc={} merchantId={} keyLatencyNs={}",
                        tx.description(), entry.merchantId(), latencyNs);
                queueProcessor.enqueue(new EnrichmentQueueProcessor.EnrichmentTask(
                        entry.merchantId(), tx.description(), mn,
                        tx.amount(), tx.date(), request.accountId()
                ));
            } else {
                log.info("[CACHE HIT] desc={} merchantId={} status={} latencyNs={}",
                        tx.description(), entry.merchantId(), entry.status(), latencyNs);
            }

            enrichedTransactions.add(buildEnrichedTransaction(tx, entry));
        }

        persistStatus(requestId, "SUCCESS", null);
        return new EnrichmentResponse(requestId, enrichedTransactions, OffsetDateTime.now(), "SUCCESS");
    }

    /**
     * Creates a PENDING stub record in the DB (committed in its own transaction so it survives
     * any rollback of the outer transaction) and returns the corresponding cache entry.
     * On concurrent insert collision the winning DB record is re-queried and returned.
     */
    private MerchantCacheEntry createStub(String description, String merchantName) {
        return requiresNewTx.execute(txStatus -> {
            String merchantId = guidGenerator.generate();
            MerchantCacheEntity stub = MerchantCacheEntity.builder()
                    .merchantId(merchantId)
                    .description(description)
                    .merchantName(merchantName)
                    .status("PENDING")
                    .createdAt(OffsetDateTime.now())
                    .build();
            try {
                merchantCacheRepository.save(stub);
                return new MerchantCacheEntry(merchantId, description, merchantName, null, "PENDING");
            } catch (DataIntegrityViolationException e) {
                // A concurrent request won the DB-level unique constraint race
                log.debug("Concurrent stub insert for ({}, {}); re-querying winner", description, merchantName);
                return merchantCacheRepository.findByDescriptionAndMerchantName(description, merchantName)
                        .map(w -> new MerchantCacheEntry(
                                w.getMerchantId(), w.getDescription(), w.getMerchantName(),
                                w.getPlaidResponse(), w.getStatus()))
                        .orElseThrow(() -> new RuntimeException(
                                "Stub disappeared after concurrent insert race for: " + description));
            }
        });
    }

    /**
     * Builds an EnrichedTransaction from a cache entry.
     * PENDING entries return a stub response with enrichmentStatus=PENDING in metadata.
     * ENRICHED entries return the full Plaid-sourced data.
     */
    private EnrichmentResponse.EnrichedTransaction buildEnrichedTransaction(
            EnrichmentRequest.Transaction tx, MerchantCacheEntry entry) {

        if (entry.isPending() || entry.plaidResponse() == null) {
            return new EnrichmentResponse.EnrichedTransaction(
                    tx.description(),
                    entry.merchantId(),
                    null,
                    tx.description(),
                    null,
                    Map.of("enrichmentStatus", "PENDING")
            );
        }
        try {
            PlaidEnrichResponse.PlaidEnrichedTransaction enriched =
                    objectMapper.readValue(entry.plaidResponse(),
                            PlaidEnrichResponse.PlaidEnrichedTransaction.class);
            return new EnrichmentResponse.EnrichedTransaction(
                    enriched.id(),
                    entry.merchantId(),
                    enriched.category(),
                    enriched.merchantName(),
                    enriched.logoUrl(),
                    createMetadata(enriched)
            );
        } catch (JsonProcessingException e) {
            log.error("Error deserializing cached Plaid response for merchantId={}", entry.merchantId(), e);
            return new EnrichmentResponse.EnrichedTransaction(
                    tx.description(), entry.merchantId(), null, tx.description(), null,
                    Map.of("enrichmentStatus", "ERROR")
            );
        }
    }

    /**
     * Reconstructs the enriched transaction list for a prior request from the memory cache,
     * falling back to the DB when cache entries have been evicted.
     * Transactions not found anywhere are silently omitted.
     */
    private List<EnrichmentResponse.EnrichedTransaction> buildEnrichedTransactionsFromCache(
            EnrichmentRequest request) {
        if (request.transactions() == null) return List.of();
        List<EnrichmentResponse.EnrichedTransaction> results = new ArrayList<>();
        for (EnrichmentRequest.Transaction tx : request.transactions()) {
            String mn = nvl(tx.merchantName());
            MerchantCacheEntry entry = memoryCache.get(tx.description(), mn)
                    .orElseGet(() ->
                            merchantCacheRepository.findByDescriptionAndMerchantName(tx.description(), mn)
                                    .map(e -> new MerchantCacheEntry(
                                            e.getMerchantId(), e.getDescription(), e.getMerchantName(),
                                            e.getPlaidResponse(), e.getStatus()))
                                    .orElse(null));
            if (entry != null) {
                results.add(buildEnrichedTransaction(tx, entry));
            }
        }
        return results;
    }

    /** Processes a single batch item reactively, delegating to {@link #enrichCore}. */
    private Mono<EnrichmentResponse> processBatchItem(BatchItem item) {
        return Mono.fromCallable(() -> enrichCore(item.requestId(), item.originalRequest()))
                .onErrorResume(ex -> {
                    log.error("Batch item {} failed: {}", item.requestId(), ex.getMessage());
                    persistStatus(item.requestId(), "FAILED", ex.getMessage());
                    return Mono.just(new EnrichmentResponse(
                            item.requestId(), List.of(), OffsetDateTime.now(), "FAILED", ex.getMessage()
                    ));
                });
    }

    // ── Persistence helpers ──────────────────────────────────────────────────

    private void persistRequest(String requestId, EnrichmentRequest request, String status) {
        try {
            EnrichmentEntity entity = EnrichmentEntity.builder()
                    .requestId(requestId)
                    .originalRequest(objectMapper.writeValueAsString(request))
                    .status(status)
                    .createdAt(OffsetDateTime.now())
                    .build();
            enrichmentRepository.save(entity);
            log.debug("Persisted request: {}", requestId);
        } catch (JsonProcessingException e) {
            log.error("Error serializing request", e);
            throw new RuntimeException("Error persisting request", e);
        }
    }

    private void persistStatus(String requestId, String status, String errorMessage) {
        enrichmentRepository.findById(requestId).ifPresent(entity -> {
            entity.setStatus(status);
            entity.setErrorMessage(errorMessage);
            enrichmentRepository.save(entity);
            log.debug("Updated enrichment record: {} status={}", requestId, status);
        });
    }

    // ── Metadata / utility ───────────────────────────────────────────────────

    private Map<String, Object> createMetadata(PlaidEnrichResponse.PlaidEnrichedTransaction tx) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("categoryId", tx.categoryId());
        metadata.put("website", tx.website());
        metadata.put("confidenceLevel", tx.confidenceLevel());
        if (tx.enrichmentMetadata() != null) {
            metadata.putAll(tx.enrichmentMetadata());
        }
        return metadata;
    }

    /** Coerces null to empty string for cache key consistency. */
    private String nvl(String s) { return s != null ? s : ""; }

    private record BatchItem(String requestId, EnrichmentRequest originalRequest) {}
}
