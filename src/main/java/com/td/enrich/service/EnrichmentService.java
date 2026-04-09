package com.td.enrich.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.td.enrich.domain.*;
import com.td.enrich.exception.PlaidApiException;
import com.td.enrich.util.GuidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Core business logic for enriching bank transactions with merchant data.
 *
 * <p>This class is the central orchestrator. Its job is to accept a raw transaction
 * (like "STARBUCKS COFFEE #1234"), look up or fetch standardized merchant data
 * (name, category, logo URL), and return the enriched result.
 *
 * <p><b>The two-layer cache strategy:</b>
 * <ol>
 *   <li><b>DB cache</b> ({@code merchant_cache} table): Stores the Plaid response JSON
 *       keyed on {@code (description, merchantName)}. If the pair was enriched before,
 *       we reuse the stored result and never call Plaid again.</li>
 *   <li><b>Background queue</b> ({@link EnrichmentQueueProcessor}): Not used in this
 *       class — synchronous enrichment always calls Plaid directly on a cache miss.</li>
 * </ol>
 *
 * <p><b>Happy-path flow for a single enrichment:</b>
 * <ol>
 *   <li>Generate a UUID for this request.</li>
 *   <li>Persist the request to the DB with status {@code PENDING}.</li>
 *   <li>Call {@link #partitionByCache} to split transactions into cache hits and misses.</li>
 *   <li>If there are any misses, call {@link #fetchAndCacheFromPlaid} once for all of them.</li>
 *   <li>Persist the response as {@code SUCCESS}.</li>
 *   <li>Call {@link #assembleEnrichedResponse} to build the final response in the original
 *       input order, merging hits and freshly fetched entries.</li>
 * </ol>
 *
 * <p>If anything throws, the {@code catch} block in {@link #enrichTransactions} persists
 * a {@code FAILED} record and returns a structured failure response — the caller always
 * receives well-formed JSON instead of a 500 error.
 *
 * <p><b>Thread safety:</b> Spring creates one singleton instance; all mutable state
 * lives in injected beans (repositories, Plaid client) that are themselves thread-safe.
 */
@Service
@Slf4j
public class EnrichmentService {

    private final PlaidApiClient plaidApiClient;
    private final EnrichmentRepository enrichmentRepository;
    private final MerchantCacheRepository merchantCacheRepository;
    private final GuidGenerator guidGenerator;
    private final ObjectMapper objectMapper;

    /**
     * Spring injects all dependencies via this constructor.
     *
     * @param plaidApiClient           HTTP client for the Plaid Enrich API
     * @param enrichmentRepository     JPA repository for the {@code enrichments} audit table
     * @param merchantCacheRepository  JPA repository for the {@code merchant_cache} table
     * @param guidGenerator            utility that creates UUID v4 request IDs
     * @param objectMapper             Jackson mapper for JSON serialization/deserialization
     */
    public EnrichmentService(PlaidApiClient plaidApiClient,
                             EnrichmentRepository enrichmentRepository,
                             MerchantCacheRepository merchantCacheRepository,
                             GuidGenerator guidGenerator,
                             ObjectMapper objectMapper) {
        this.plaidApiClient = plaidApiClient;
        this.enrichmentRepository = enrichmentRepository;
        this.merchantCacheRepository = merchantCacheRepository;
        this.guidGenerator = guidGenerator;
        this.objectMapper = objectMapper;
    }

    // ── Public API ────────────────────────────────────────────────────────────────

    /**
     * Enriches one or more transactions synchronously.
     *
     * <p>Generates a UUID request ID, checks the merchant cache for each transaction,
     * calls Plaid for any cache misses, writes new cache entries, and returns the
     * combined result. The entire flow runs inside a single database transaction
     * ({@code @Transactional}) so the request row and any new cache entries are
     * committed together or rolled back together.
     *
     * <p>This method <em>never</em> throws. All errors are caught and returned as a
     * {@code FAILED} response so the controller always sends a structured JSON reply.
     *
     * @param request the client's enrichment request (account ID + list of transactions)
     * @return an {@link EnrichmentResponse} with {@code status = "SUCCESS"} and the
     *         enriched merchant data, or {@code status = "FAILED"} with an error message
     */
    @Transactional
    public EnrichmentResponse enrichTransactions(EnrichmentRequest request) {
        String requestId = guidGenerator.generate();
        log.info("Starting enrichment for request: {}", requestId);

        try {
            // Save a PENDING row so we have an audit trail even if things go wrong later
            persistRequest(requestId, request, "PENDING");
            EnrichmentResponse response = enrichCore(requestId, request);
            log.info("Successfully enriched request: {}", requestId);
            return response;

        } catch (Exception ex) {
            // Log the full stack trace for debugging, but return a structured failure
            // to the caller instead of propagating the exception as a 500 error
            log.error("Error enriching request: {}", requestId, ex);
            persistResponse(requestId, request, null, "FAILED", ex.getMessage());
            return new EnrichmentResponse(
                    requestId, List.of(), OffsetDateTime.now(), "FAILED", ex.getMessage());
        }
    }

    /**
     * Enriches multiple transaction batches in parallel.
     *
     * <p>Each {@link EnrichmentRequest} in the list is treated independently — it gets
     * its own UUID and goes through the same cache-check → Plaid-call → response flow as
     * single enrichment. All batches are processed concurrently using Reactor's
     * {@code Flux.flatMap}. The response list is in the same order as the input list.
     *
     * <p>The method blocks until every batch completes. Individual failures are isolated:
     * if one batch fails, the others still return their results.
     *
     * <p><b>Why this method is NOT {@code @Transactional}:</b> The reactive operators
     * inside {@code Flux.flatMap} execute on Reactor scheduler threads, not the
     * transaction-bound calling thread. Spring's {@code @Transactional} propagation does
     * not cross thread boundaries, so annotating this method would give a false sense of
     * atomicity — a DB failure for one batch item would NOT roll back successful writes
     * from other items. Each item is independently transactional via the
     * {@code @Transactional} on {@link #enrichTransactions}, which is called by
     * {@link #enrichCore} for each item.
     *
     * @param requests list of independent enrichment requests to process in parallel
     * @return one {@link EnrichmentResponse} per input request, in the same order
     */
    public List<EnrichmentResponse> enrichTransactionsBatch(List<EnrichmentRequest> requests) {
        log.info("Starting batch enrichment for {} requests", requests.size());

        // Pre-assign UUIDs and persist PENDING rows for all requests before starting
        // the parallel Plaid calls; this ensures audit records exist even for fast failures
        List<BatchItem> batchItems = requests.stream()
                .map(req -> {
                    String requestId = guidGenerator.generate();
                    persistRequest(requestId, req, "PENDING");
                    return new BatchItem(requestId, req);
                })
                .collect(Collectors.toList());

        // Flux.flatMap runs the items concurrently (up to 256 by default, further limited
        // by the Plaid bulkhead). collectList() gathers all results; block() waits for them.
        List<EnrichmentResponse> responses = Flux.fromIterable(batchItems)
                .flatMap(this::processBatchItem)
                .collectList()
                .block();

        log.info("Completed batch enrichment: {}/{} successful",
                responses.stream().filter(r -> "SUCCESS".equals(r.status())).count(),
                responses.size());

        return responses;
    }

    /**
     * Retrieves a previously stored enrichment result by its UUID.
     *
     * <p>Delegates response construction to {@link #buildResponseFromEntity} to keep
     * this method focused solely on the repository lookup.
     *
     * @param requestId the UUID assigned by {@link #enrichTransactions}
     * @return an {@code Optional} containing the response if the UUID was found,
     *         or empty if no record exists for that ID
     */
    @Transactional(readOnly = true)
    public Optional<EnrichmentResponse> getEnrichmentById(String requestId) {
        log.debug("Retrieving enrichment record: {}", requestId);
        return enrichmentRepository.findById(requestId)
                .map(entity -> buildResponseFromEntity(entity, requestId));
    }

    // ── Core enrichment pipeline ───────────────────────────────────────────────

    /**
     * The shared enrichment algorithm used by both single and batch flows.
     *
     * <p>Delegates each phase to a focused private method, keeping this method's
     * cyclomatic complexity at 2 (one {@code if} for the cache-miss branch):
     * <ol>
     *   <li>{@link #partitionByCache} — splits transactions into hits and misses</li>
     *   <li>{@link #fetchAndCacheFromPlaid} — calls Plaid and writes DB cache entries
     *       (only when there are misses)</li>
     *   <li>{@link #assembleEnrichedResponse} — merges hits and fresh entries back into
     *       the original input order</li>
     * </ol>
     *
     * @param requestId the UUID for this enrichment (already persisted as PENDING)
     * @param request   the original client request
     * @return the completed {@link EnrichmentResponse} with all transactions enriched
     */
    private EnrichmentResponse enrichCore(String requestId, EnrichmentRequest request) {
        CachePartition partition = partitionByCache(request.transactions());

        PlaidEnrichResponse plaidResponse = null;
        Map<String, MerchantCacheEntity> freshEntries = Map.of(); // empty if all transactions hit cache

        if (!partition.misses().isEmpty()) {
            PlaidFetchResult result = fetchAndCacheFromPlaid(request.accountId(), partition.misses());
            plaidResponse = result.plaidResponse();
            freshEntries = result.freshEntries();
        }

        persistResponse(requestId, request, plaidResponse, "SUCCESS", null);
        return assembleEnrichedResponse(requestId, request.transactions(), partition.hits(), freshEntries);
    }

    /**
     * Separates the transaction list into two groups: those already in the DB merchant
     * cache and those that need a Plaid API call.
     *
     * <p>For each transaction, a composite key of {@code (description, merchantName)} is
     * checked against the {@code merchant_cache} table. Hits are stored in a map keyed
     * by cache key so they can be looked up efficiently during response assembly.
     *
     * @param transactions the full list of transactions from the client request
     * @return a {@link CachePartition} with the hits map and the misses list
     */
    private CachePartition partitionByCache(List<EnrichmentRequest.Transaction> transactions) {
        Map<String, MerchantCacheEntity> hits = new HashMap<>();
        List<EnrichmentRequest.Transaction> misses = new ArrayList<>();

        for (EnrichmentRequest.Transaction tx : transactions) {
            String mn = nvl(tx.merchantName());
            Optional<MerchantCacheEntity> cached =
                    merchantCacheRepository.findByDescriptionAndMerchantName(tx.description(), mn);
            if (cached.isPresent()) {
                hits.put(cacheKey(tx.description(), mn), cached.get());
            } else {
                misses.add(tx);
            }
        }

        return new CachePartition(hits, misses);
    }

    /**
     * Calls the Plaid API once for all uncached transactions, then writes each result
     * to the DB merchant cache.
     *
     * <p>Plaid returns transactions in the same order they were sent, so index {@code i}
     * in the response corresponds to index {@code i} in {@code uncachedTransactions}.
     *
     * <p>Cache writes are delegated to {@link #saveCacheEntryHandlingRace}, which handles
     * the concurrent-insert race condition (two threads enriching the same new merchant
     * at the same time).
     *
     * @param accountId            the account ID to include in the Plaid request
     * @param uncachedTransactions transactions that were not found in the DB cache
     * @return a {@link PlaidFetchResult} containing the freshly written cache entries
     *         and the raw Plaid response (for audit logging in {@link #persistResponse})
     * @throws PlaidApiException    if Plaid returns null or a non-2xx response
     * @throws RuntimeException     if a cache entry cannot be serialized to JSON
     */
    private PlaidFetchResult fetchAndCacheFromPlaid(
            String accountId, List<EnrichmentRequest.Transaction> uncachedTransactions) {

        PlaidEnrichRequest plaidRequest = mapToPlaidRequest(accountId, uncachedTransactions);
        // .block() converts the reactive Mono to a blocking call; acceptable here because
        // we are already on a request thread (not a Reactor event-loop thread)
        PlaidEnrichResponse plaidResponse = plaidApiClient.enrichTransactions(plaidRequest).block();

        if (plaidResponse == null) {
            throw new PlaidApiException("Plaid API returned null response");
        }

        Map<String, MerchantCacheEntity> freshEntries = new HashMap<>();
        for (int i = 0; i < uncachedTransactions.size(); i++) {
            EnrichmentRequest.Transaction tx = uncachedTransactions.get(i);
            // Plaid preserves the input order, so response index i == request index i
            PlaidEnrichResponse.PlaidEnrichedTransaction plaidTx =
                    plaidResponse.enrichedTransactions().get(i);
            String mn = nvl(tx.merchantName());
            try {
                MerchantCacheEntity entry = saveCacheEntryHandlingRace(tx, mn, plaidTx);
                freshEntries.put(cacheKey(tx.description(), mn), entry);
            } catch (JsonProcessingException e) {
                log.error("Error serializing Plaid transaction for cache", e);
                throw new RuntimeException("Error persisting merchant cache entry", e);
            }
        }

        return new PlaidFetchResult(freshEntries, plaidResponse);
    }

    /**
     * Writes one Plaid-enriched transaction to the {@code merchant_cache} table.
     *
     * <p>Handles the concurrent-insert race condition: if two threads both miss the
     * cache for the same merchant and race to insert the same
     * {@code (description, merchantName)} row, the second insert will throw a
     * {@link DataIntegrityViolationException} due to the unique constraint. In that
     * case, this method re-queries the table to get the row committed by the winning
     * thread, so both callers end up with the same (correct) entry.
     *
     * @param tx      the original transaction (supplies description and merchantName)
     * @param mn      the null-coerced merchant name ({@link #nvl} already applied)
     * @param plaidTx the Plaid-enriched transaction to serialize and store
     * @return the saved or re-queried {@link MerchantCacheEntity}
     * @throws JsonProcessingException if the Plaid transaction cannot be serialized to JSON
     */
    private MerchantCacheEntity saveCacheEntryHandlingRace(
            EnrichmentRequest.Transaction tx,
            String mn,
            PlaidEnrichResponse.PlaidEnrichedTransaction plaidTx) throws JsonProcessingException {

        String plaidTxJson = objectMapper.writeValueAsString(plaidTx);
        MerchantCacheEntity cacheEntry = MerchantCacheEntity.builder()
                .merchantId(guidGenerator.generate())
                .description(tx.description())
                .merchantName(mn)
                .plaidResponse(plaidTxJson)
                .createdAt(OffsetDateTime.now())
                .build();
        try {
            merchantCacheRepository.save(cacheEntry);
            return cacheEntry;
        } catch (DataIntegrityViolationException e) {
            // A concurrent request won the race and inserted this (description, merchantName)
            // row first. Re-query to get the winner's row rather than failing.
            log.debug("Cache insert race detected for ({}, {}); re-querying", tx.description(), mn);
            return merchantCacheRepository
                    .findByDescriptionAndMerchantName(tx.description(), mn)
                    .orElseThrow(() -> new RuntimeException(
                            "Cache entry disappeared after concurrent insert race"));
        }
    }

    /**
     * Assembles the final {@link EnrichmentResponse} by iterating over the original
     * transaction list in order and looking up each transaction's cache entry.
     *
     * <p>Each transaction is looked up by its composite key in either {@code cacheHits}
     * (transactions that were already in the cache before this request) or
     * {@code freshEntries} (transactions that were just fetched from Plaid). Preserving
     * the original input order ensures the caller can match response[i] back to request[i].
     *
     * <p>Transactions whose cache entry cannot be deserialized are silently omitted from
     * the result and logged at ERROR level — a single bad entry should not fail the
     * entire response.
     *
     * @param requestId    the UUID for this enrichment request
     * @param transactions the original, ordered transaction list from the client request
     * @param cacheHits    map of composite key → entity for pre-existing cache entries
     * @param freshEntries map of composite key → entity for entries just written to the DB
     * @return a {@code SUCCESS} {@link EnrichmentResponse} with the merged, ordered results
     */
    private EnrichmentResponse assembleEnrichedResponse(
            String requestId,
            List<EnrichmentRequest.Transaction> transactions,
            Map<String, MerchantCacheEntity> cacheHits,
            Map<String, MerchantCacheEntity> freshEntries) {

        List<EnrichmentResponse.EnrichedTransaction> enrichedTransactions = new ArrayList<>();

        for (EnrichmentRequest.Transaction tx : transactions) {
            String mn = nvl(tx.merchantName());
            String key = cacheKey(tx.description(), mn);
            // Check cacheHits first (pre-existing); fall back to freshEntries (just fetched)
            MerchantCacheEntity cacheEntry = cacheHits.containsKey(key)
                    ? cacheHits.get(key)
                    : freshEntries.get(key);

            if (cacheEntry != null) {
                try {
                    PlaidEnrichResponse.PlaidEnrichedTransaction enriched = objectMapper.readValue(
                            cacheEntry.getPlaidResponse(),
                            PlaidEnrichResponse.PlaidEnrichedTransaction.class
                    );
                    enrichedTransactions.add(new EnrichmentResponse.EnrichedTransaction(
                            enriched.id(),
                            cacheEntry.getMerchantId(),
                            enriched.category(),
                            enriched.merchantName(),
                            enriched.logoUrl(),
                            createMetadata(enriched)
                    ));
                } catch (JsonProcessingException e) {
                    // Don't fail the whole response for one bad cache entry — log and skip
                    log.error("Error deserializing cached Plaid transaction for key={}", key, e);
                }
            }
        }

        return new EnrichmentResponse(requestId, enrichedTransactions, OffsetDateTime.now(), "SUCCESS");
    }

    // ── Response construction helpers ─────────────────────────────────────────

    /**
     * Builds an {@link EnrichmentResponse} from a stored {@link EnrichmentEntity}.
     *
     * <p>For a {@code SUCCESS} record: deserializes the original request JSON, re-queries
     * the merchant cache for each transaction, and rebuilds the enriched list.
     *
     * <p>For any other status (usually {@code FAILED}): returns an empty transaction list
     * with the stored status and error message — no cache re-query needed.
     *
     * <p>Extracted from {@link #getEnrichmentById} to reduce that method to a single
     * repository call followed by a method reference — keeping it at CC 1.
     *
     * @param entity    the stored enrichment entity to convert
     * @param requestId the UUID (duplicated here since entity doesn't expose it directly)
     * @return the reconstructed {@link EnrichmentResponse}
     */
    private EnrichmentResponse buildResponseFromEntity(EnrichmentEntity entity, String requestId) {
        if (!"SUCCESS".equals(entity.getStatus())) {
            // Non-SUCCESS record: return the stored status with an empty transaction list
            return new EnrichmentResponse(
                    requestId, List.of(), entity.getCreatedAt(),
                    entity.getStatus(), entity.getErrorMessage()
            );
        }
        try {
            // Deserialize the stored JSON back to a request so we can re-query
            // the merchant cache for each of its transactions
            EnrichmentRequest originalRequest = objectMapper.readValue(
                    entity.getOriginalRequest(), EnrichmentRequest.class);
            List<EnrichmentResponse.EnrichedTransaction> enrichedTransactions =
                    buildEnrichedTransactionsFromCache(originalRequest);
            return new EnrichmentResponse(
                    requestId, enrichedTransactions, entity.getCreatedAt(), "SUCCESS");
        } catch (JsonProcessingException e) {
            log.error("Error parsing stored request for {}", requestId, e);
            throw new RuntimeException("Error retrieving enrichment data", e);
        }
    }

    /**
     * Rebuilds the enriched transaction list for a stored {@code SUCCESS} record by
     * re-querying the merchant cache for each transaction in the original request.
     *
     * <p>Transactions whose {@code (description, merchantName)} pair is no longer in the
     * merchant cache (e.g. if it was purged) are silently omitted from the result.
     *
     * @param request the original enrichment request deserialized from the audit row
     * @return list of enriched transactions, potentially shorter than the original input
     */
    private List<EnrichmentResponse.EnrichedTransaction> buildEnrichedTransactionsFromCache(
            EnrichmentRequest request) {
        if (request.transactions() == null) {
            return List.of();
        }
        List<EnrichmentResponse.EnrichedTransaction> enrichedTransactions = new ArrayList<>();
        for (EnrichmentRequest.Transaction tx : request.transactions()) {
            merchantCacheRepository
                    .findByDescriptionAndMerchantName(tx.description(), nvl(tx.merchantName()))
                    .ifPresent(cacheEntry -> {
                        try {
                            PlaidEnrichResponse.PlaidEnrichedTransaction enriched = objectMapper.readValue(
                                    cacheEntry.getPlaidResponse(),
                                    PlaidEnrichResponse.PlaidEnrichedTransaction.class
                            );
                            enrichedTransactions.add(new EnrichmentResponse.EnrichedTransaction(
                                    enriched.id(),
                                    cacheEntry.getMerchantId(),
                                    enriched.category(),
                                    enriched.merchantName(),
                                    enriched.logoUrl(),
                                    createMetadata(enriched)
                            ));
                        } catch (JsonProcessingException e) {
                            log.error("Error deserializing cached Plaid transaction in getById", e);
                        }
                    });
        }
        return enrichedTransactions;
    }

    /**
     * Wraps a single batch item in a {@code Mono} so Reactor can run it concurrently
     * with other batch items. If {@link #enrichCore} throws, the error is caught here
     * and converted into a {@code FAILED} response so one batch failure doesn't cancel
     * the whole {@code Flux}.
     *
     * @param item holds the pre-assigned UUID and the original request
     * @return a {@code Mono} that emits one {@link EnrichmentResponse} (never an error signal)
     */
    private Mono<EnrichmentResponse> processBatchItem(BatchItem item) {
        // Mono.fromCallable wraps a synchronous operation in a Mono
        return Mono.fromCallable(() -> enrichCore(item.requestId(), item.originalRequest()))
                .onErrorResume(ex -> {
                    log.error("Batch item {} failed: {}", item.requestId(), ex.getMessage());
                    persistResponse(item.requestId(), item.originalRequest(), null, "FAILED", ex.getMessage());
                    return Mono.just(new EnrichmentResponse(
                            item.requestId(), List.of(), OffsetDateTime.now(),
                            "FAILED", ex.getMessage()
                    ));
                });
    }

    // ── Plaid request mapping ─────────────────────────────────────────────────

    /**
     * Converts our internal {@link EnrichmentRequest.Transaction} objects into the
     * {@link PlaidEnrichRequest} format that the Plaid API expects.
     *
     * <p>The {@code clientId} and {@code apiKey} fields are left {@code null} here;
     * {@link PlaidApiClient} fills them in just before each HTTP call so credentials
     * never flow through the business logic layer.
     *
     * @param accountId the account ID to include in the Plaid request
     * @param txs       the subset of transactions to send (only cache misses)
     * @return a ready-to-send {@link PlaidEnrichRequest}
     */
    private PlaidEnrichRequest mapToPlaidRequest(String accountId, List<EnrichmentRequest.Transaction> txs) {
        List<PlaidEnrichRequest.PlaidTransaction> plaidTransactions = txs.stream()
                .map(t -> new PlaidEnrichRequest.PlaidTransaction(
                        t.description(), t.amount(), t.date(), t.merchantName()))
                .collect(Collectors.toList());

        return new PlaidEnrichRequest(
                null, // clientId — injected by PlaidApiClient
                null, // apiKey   — injected by PlaidApiClient
                accountId,
                plaidTransactions
        );
    }

    /**
     * Builds the {@code metadata} map for one enriched transaction.
     *
     * <p>The map always contains {@code categoryId}, {@code website}, and
     * {@code confidenceLevel}. If Plaid returned an {@code enrichmentMetadata} map
     * it is merged in as well (e.g. {@code "location": "Seattle, WA"}).
     *
     * @param transaction the Plaid-enriched transaction to extract metadata from
     * @return a key-value map to include in the response
     */
    private Map<String, Object> createMetadata(PlaidEnrichResponse.PlaidEnrichedTransaction transaction) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("categoryId", transaction.categoryId());
        metadata.put("website", transaction.website());
        metadata.put("confidenceLevel", transaction.confidenceLevel());

        // Merge any additional metadata Plaid returned (e.g. location, industry)
        if (transaction.enrichmentMetadata() != null) {
            metadata.putAll(transaction.enrichmentMetadata());
        }

        return metadata;
    }

    // ── Persistence helpers ───────────────────────────────────────────────────

    /**
     * Serializes the request to JSON and saves a new enrichment entity with the
     * given status. Called at the beginning of each request to establish an audit trail.
     *
     * @param requestId the UUID for this request
     * @param request   the original client request to serialize
     * @param status    the initial status (usually {@code "PENDING"})
     */
    private void persistRequest(String requestId, EnrichmentRequest request, String status) {
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            EnrichmentEntity entity = EnrichmentEntity.builder()
                    .requestId(requestId)
                    .originalRequest(requestJson)
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

    /**
     * Updates the existing enrichment entity with the final status and, optionally,
     * the raw Plaid response JSON. Called at the end of each request to close the
     * audit record.
     *
     * <p>If no entity exists for this {@code requestId} (unusual but defensive),
     * {@link #createFallbackEntity} creates one on the fly so the save never silently
     * fails.
     *
     * @param requestId       the UUID to look up
     * @param originalRequest the original client request (used only if the entity is missing)
     * @param plaidResponse   the Plaid response to serialize, or {@code null} on failure
     * @param status          the final status ({@code "SUCCESS"} or {@code "FAILED"})
     * @param errorMessage    the error description if status is {@code "FAILED"}
     */
    private void persistResponse(String requestId,
                                 EnrichmentRequest originalRequest,
                                 PlaidEnrichResponse plaidResponse,
                                 String status,
                                 String errorMessage) {
        try {
            EnrichmentEntity entity = enrichmentRepository.findById(requestId)
                    .orElseGet(() -> createFallbackEntity(requestId, originalRequest));

            entity.setStatus(status);
            entity.setErrorMessage(errorMessage);

            if (plaidResponse != null) {
                entity.setPlaidResponse(objectMapper.writeValueAsString(plaidResponse));
            }

            enrichmentRepository.save(entity);
            log.debug("Updated enrichment record: {} with status: {}", requestId, status);
        } catch (JsonProcessingException e) {
            log.error("Error serializing response", e);
        }
    }

    /**
     * Creates a minimal {@link EnrichmentEntity} when {@link #persistResponse} cannot
     * find the existing PENDING row (defensive — should not normally happen).
     *
     * <p>If the request itself also fails to serialize, the entity is saved with
     * {@code "{}"} as the stored request so the record still exists for debugging.
     *
     * @param requestId       the UUID for the new entity
     * @param originalRequest the request to serialize; may fail gracefully
     * @return a new, unsaved {@link EnrichmentEntity} ready to be passed to
     *         {@code enrichmentRepository.save}
     */
    private EnrichmentEntity createFallbackEntity(String requestId, EnrichmentRequest originalRequest) {
        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(originalRequest);
        } catch (JsonProcessingException e) {
            requestJson = "{}"; // best-effort: don't let a serialization failure kill the record
        }
        return EnrichmentEntity.builder()
                .requestId(requestId)
                .originalRequest(requestJson)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    // ── Key utilities ─────────────────────────────────────────────────────────

    /**
     * Returns the input string, or an empty string if it is {@code null}.
     *
     * <p>Used before every merchant cache lookup so that a transaction with no
     * {@code merchantName} consistently maps to the key {@code "description|"}
     * instead of {@code "description|null"}.
     *
     * @param s any string, possibly {@code null}
     * @return {@code s} if non-null, otherwise {@code ""}
     */
    private String nvl(String s) {
        return s != null ? s : "";
    }

    /**
     * Builds the composite cache key used to look up a merchant in the DB.
     *
     * <p>The key is {@code description + "|" + merchantName} where {@code merchantName}
     * has already been null-coerced with {@link #nvl}. The pipe character ({@code |})
     * separates the two parts so a description ending with letters and a merchantName
     * starting with letters can never accidentally collide.
     *
     * @param desc     the raw transaction description (e.g. {@code "STARBUCKS COFFEE"})
     * @param merchant the merchant name, or empty string if none
     * @return the composite key string
     */
    private String cacheKey(String desc, String merchant) {
        return desc + "|" + nvl(merchant);
    }

    // ── Private records ───────────────────────────────────────────────────────

    /**
     * The result of splitting a transaction list into cache hits and misses.
     *
     * <p>Returned by {@link #partitionByCache} and consumed by {@link #enrichCore}.
     *
     * @param hits   map of composite cache key → existing {@link MerchantCacheEntity}
     *               for transactions already in the DB cache
     * @param misses ordered list of transactions not found in the DB cache
     *               (these need a Plaid API call)
     */
    private record CachePartition(
            Map<String, MerchantCacheEntity> hits,
            List<EnrichmentRequest.Transaction> misses
    ) {}

    /**
     * The result of a Plaid API call and the subsequent DB cache writes.
     *
     * <p>Returned by {@link #fetchAndCacheFromPlaid} and consumed by {@link #enrichCore}.
     * Both fields are needed at the call site: {@code freshEntries} for response assembly
     * and {@code plaidResponse} for the audit log in {@link #persistResponse}.
     *
     * @param freshEntries map of composite cache key → newly written {@link MerchantCacheEntity}
     * @param plaidResponse the raw Plaid response, stored in the audit table for debugging
     */
    private record PlaidFetchResult(
            Map<String, MerchantCacheEntity> freshEntries,
            PlaidEnrichResponse plaidResponse
    ) {}

    /**
     * Lightweight pairing of a pre-assigned request ID with its original request.
     *
     * <p>Used only inside {@link #enrichTransactionsBatch} to carry the UUID generated
     * in the outer loop into the Reactor pipeline, where it is needed by
     * {@link #processBatchItem}.
     *
     * @param requestId       the UUID already persisted as a PENDING row
     * @param originalRequest the client's enrichment request for this batch item
     */
    private record BatchItem(
            String requestId,
            EnrichmentRequest originalRequest
    ) {}
}
