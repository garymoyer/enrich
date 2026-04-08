package com.td.enrich.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.td.enrich.domain.EnrichmentRequest;
import com.td.enrich.domain.EnrichmentResponse;
import com.td.enrich.exception.PlaidApiException;
import com.td.enrich.service.EnrichmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link EnrichmentController}.
 *
 * <p><b>What these tests cover:</b>
 * <ul>
 *   <li>All four endpoints: {@code POST /enrich}, {@code POST /enrich/batch},
 *       {@code GET /enrich/{requestId}}, {@code GET /enrich/health}.</li>
 *   <li>Successful responses (HTTP 200) with correct JSON fields.</li>
 *   <li>Input validation (HTTP 400 for missing or invalid request fields).</li>
 *   <li>Upstream failure responses (HTTP 502 for {@link PlaidApiException}).</li>
 *   <li>Unexpected errors (HTTP 500 with RFC 7807 Problem Detail format).</li>
 *   <li>Not-found path (HTTP 404 for unknown request IDs).</li>
 * </ul>
 *
 * <p><b>{@code @WebMvcTest}:</b> This annotation starts only the Spring MVC layer —
 * the controller, filters, and exception handlers — but NOT the full application context
 * (no database, no Plaid client, etc.). This makes the tests fast and focused on HTTP
 * behaviour. {@link EnrichmentService} is replaced with a {@code @MockBean} so each
 * test can control exactly what the service returns.
 *
 * <p><b>{@link MockMvc}:</b> A test utility provided by Spring that lets us send
 * simulated HTTP requests without starting a real server. Requests are processed through
 * the full Spring MVC pipeline (including validation and exception handling).
 */
@WebMvcTest(EnrichmentController.class)
@DisplayName("EnrichmentController Unit Tests")
class EnrichmentControllerTest {

    /** MockMvc is auto-configured by @WebMvcTest and injected here. */
    @Autowired
    private MockMvc mockMvc;

    /** Used to serialize Java objects to JSON for the request body. */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * {@code @MockitoBean} replaces the real {@link EnrichmentService} bean in the
     * Spring context with a Mockito mock. Each test stubs only the method(s) it needs
     * via {@code when(...).thenReturn(...)}. Prefer {@code @MockitoBean} over the
     * deprecated {@code @MockBean} from Spring Boot 3.4+.
     */
    @MockitoBean
    private EnrichmentService enrichmentService;

    // ── POST /api/v1/enrich ────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/enrich - Should return 200 with enriched data")
    void shouldEnrichTransactionsSuccessfully() throws Exception {
        // Given
        EnrichmentRequest request = createTestRequest();
        EnrichmentResponse response = createTestResponse();

        when(enrichmentService.enrichTransactions(any())).thenReturn(response);

        // When/Then — perform the request and assert the response JSON fields
        mockMvc.perform(post("/api/v1/enrich")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("550e8400-e29b-41d4-a716-446655440000"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.enrichedTransactions").isArray())
                .andExpect(jsonPath("$.enrichedTransactions", hasSize(1)))
                .andExpect(jsonPath("$.enrichedTransactions[0].merchantName").value("Starbucks Coffee"))
                .andExpect(jsonPath("$.enrichedTransactions[0].merchantId").value("merchant-id-001"));
    }

    @Test
    @DisplayName("POST /api/v1/enrich - Should return 400 for invalid request")
    void shouldReturn400ForInvalidRequest() throws Exception {
        // Given — missing required field "accountId" triggers Bean Validation
        String invalidRequest = "{\"transactions\":[]}";

        // When/Then — GlobalExceptionHandler converts MethodArgumentNotValidException → 400
        mockMvc.perform(post("/api/v1/enrich")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRequest))
                .andExpect(status().isBadRequest())
                // Response follows RFC 7807 Problem Detail format
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.errors").exists()); // map of field → error message
    }

    @Test
    @DisplayName("POST /api/v1/enrich - Should validate transaction fields")
    void shouldValidateTransactionFields() throws Exception {
        // Given — transaction amount is negative (fails @Positive validation)
        String invalidRequest = """
                {
                    "accountId": "acc_123",
                    "transactions": [{
                        "description": "Test",
                        "amount": -5.00,
                        "date": "2026-01-30",
                        "merchantName": "Test Merchant"
                    }]
                }
                """;

        // When/Then
        mockMvc.perform(post("/api/v1/enrich")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.detail").value("Validation failed for one or more fields"));
    }

    @Test
    @DisplayName("POST /api/v1/enrich - Should return 502 for PlaidApiException with error details")
    void shouldReturn502ForPlaidApiException() throws Exception {
        // Given — the service layer throws a PlaidApiException (Plaid returned 503)
        EnrichmentRequest request = createTestRequest();
        when(enrichmentService.enrichTransactions(any()))
                .thenThrow(new PlaidApiException("Plaid service unavailable", 503, "PLAID_ERROR_001"));

        // When/Then — GlobalExceptionHandler converts PlaidApiException → 502 Bad Gateway
        mockMvc.perform(post("/api/v1/enrich")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.title").value("Plaid API Error"))
                .andExpect(jsonPath("$.plaidStatusCode").value(503))
                .andExpect(jsonPath("$.plaidErrorCode").value("PLAID_ERROR_001"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("POST /api/v1/enrich - Should return 400 for IllegalArgumentException")
    void shouldReturn400ForIllegalArgumentException() throws Exception {
        // Given — service throws IllegalArgumentException (e.g. invalid account ID format)
        EnrichmentRequest request = createTestRequest();
        when(enrichmentService.enrichTransactions(any()))
                .thenThrow(new IllegalArgumentException("Invalid account ID format"));

        // When/Then — GlobalExceptionHandler converts IllegalArgumentException → 400
        mockMvc.perform(post("/api/v1/enrich")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Request"))
                .andExpect(jsonPath("$.detail").value("Invalid account ID format"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("Should handle service exceptions gracefully")
    void shouldHandleServiceExceptions() throws Exception {
        // Given — an unexpected RuntimeException not covered by specific handlers
        EnrichmentRequest request = createTestRequest();
        when(enrichmentService.enrichTransactions(any()))
                .thenThrow(new RuntimeException("Service error"));

        // When/Then — GlobalExceptionHandler's catch-all returns 500
        mockMvc.perform(post("/api/v1/enrich")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ── POST /api/v1/enrich/batch ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/enrich/batch - Should process batch successfully")
    void shouldProcessBatchSuccessfully() throws Exception {
        // Given — two requests, two successful responses
        List<EnrichmentRequest> requests = List.of(createTestRequest(), createTestRequest());
        List<EnrichmentResponse> responses = List.of(createTestResponse(), createTestResponse());

        when(enrichmentService.enrichTransactionsBatch(any())).thenReturn(responses);

        // When/Then — response is a JSON array with two elements
        mockMvc.perform(post("/api/v1/enrich/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requests)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$[1].status").value("SUCCESS"));
    }

    @Test
    @DisplayName("POST /api/v1/enrich/batch - Should return 400 for empty batch")
    void shouldReturn400ForEmptyBatch() throws Exception {
        // Given — empty list; the controller short-circuits with 400 before calling the service
        String emptyBatch = "[]";

        // When/Then
        mockMvc.perform(post("/api/v1/enrich/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyBatch))
                .andExpect(status().isBadRequest());
    }

    // ── GET /api/v1/enrich/{requestId} ────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/enrich/{requestId} - Should return enrichment by ID")
    void shouldGetEnrichmentById() throws Exception {
        // Given
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentResponse response = createTestResponse();

        when(enrichmentService.getEnrichmentById(requestId)).thenReturn(Optional.of(response));

        // When/Then
        mockMvc.perform(get("/api/v1/enrich/{requestId}", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.enrichedTransactions[0].merchantId").value("merchant-id-001"));
    }

    @Test
    @DisplayName("GET /api/v1/enrich/{requestId} - Should return 404 when not found")
    void shouldReturn404WhenNotFound() throws Exception {
        // Given — service returns empty Optional for unknown IDs
        String requestId = "non-existent-id";
        when(enrichmentService.getEnrichmentById(requestId)).thenReturn(Optional.empty());

        // When/Then — controller maps Optional.empty() to ResponseEntity.notFound() = 404
        mockMvc.perform(get("/api/v1/enrich/{requestId}", requestId))
                .andExpect(status().isNotFound());
    }

    // ── GET /api/v1/enrich/health ──────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/enrich/health - Should return healthy status")
    void shouldReturnHealthyStatus() throws Exception {
        // When/Then — simple liveness check; no service call needed
        mockMvc.perform(get("/api/v1/enrich/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("Enrichment service is healthy"));
    }

    // ── Test data helpers ──────────────────────────────────────────────────────

    /**
     * Creates a valid single-transaction enrichment request for use in test setups.
     */
    private EnrichmentRequest createTestRequest() {
        return new EnrichmentRequest(
                "acc_12345",
                List.of(new EnrichmentRequest.Transaction(
                        "STARBUCKS COFFEE",
                        new BigDecimal("5.75"),
                        LocalDate.of(2026, 1, 30),
                        "Starbucks"
                ))
        );
    }

    /**
     * Creates a successful enrichment response matching {@link #createTestRequest()}.
     */
    private EnrichmentResponse createTestResponse() {
        return new EnrichmentResponse(
                "550e8400-e29b-41d4-a716-446655440000",
                List.of(new EnrichmentResponse.EnrichedTransaction(
                        "txn_001",
                        "merchant-id-001",
                        "Food & Drink",
                        "Starbucks Coffee",
                        "https://logo.clearbit.com/starbucks.com",
                        Map.of("categoryId", "13005000")
                )),
                OffsetDateTime.now(),
                "SUCCESS"
        );
    }
}
