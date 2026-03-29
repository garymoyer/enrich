package com.plaid.enrich.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plaid.enrich.domain.EnrichmentRequest;
import com.plaid.enrich.domain.EnrichmentResponse;
import com.plaid.enrich.exception.PlaidApiException;
import com.plaid.enrich.service.EnrichmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

@WebMvcTest(EnrichmentController.class)
@DisplayName("EnrichmentController Unit Tests")
class EnrichmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EnrichmentService enrichmentService;

    @Test
    @DisplayName("POST /api/v1/enrich - Should return 200 with enriched data")
    void shouldEnrichTransactionsSuccessfully() throws Exception {
        // Given
        EnrichmentRequest request = createTestRequest();
        EnrichmentResponse response = createTestResponse();

        when(enrichmentService.enrichTransactions(any())).thenReturn(response);

        // When/Then
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
        // Given - Missing required field accountId
        String invalidRequest = "{\"transactions\":[]}";

        // When/Then
        mockMvc.perform(post("/api/v1/enrich")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.errors").exists());
    }

    @Test
    @DisplayName("POST /api/v1/enrich - Should validate transaction fields")
    void shouldValidateTransactionFields() throws Exception {
        // Given - Transaction with negative amount
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
    @DisplayName("POST /api/v1/enrich/batch - Should process batch successfully")
    void shouldProcessBatchSuccessfully() throws Exception {
        // Given
        List<EnrichmentRequest> requests = List.of(createTestRequest(), createTestRequest());
        List<EnrichmentResponse> responses = List.of(createTestResponse(), createTestResponse());

        when(enrichmentService.enrichTransactionsBatch(any())).thenReturn(responses);

        // When/Then
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
        // Given
        String emptyBatch = "[]";

        // When/Then
        mockMvc.perform(post("/api/v1/enrich/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyBatch))
                .andExpect(status().isBadRequest());
    }

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
        // Given
        String requestId = "non-existent-id";
        when(enrichmentService.getEnrichmentById(requestId)).thenReturn(Optional.empty());

        // When/Then
        mockMvc.perform(get("/api/v1/enrich/{requestId}", requestId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/enrich/health - Should return healthy status")
    void shouldReturnHealthyStatus() throws Exception {
        // When/Then
        mockMvc.perform(get("/api/v1/enrich/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("Enrichment service is healthy"));
    }

    @Test
    @DisplayName("Should handle service exceptions gracefully")
    void shouldHandleServiceExceptions() throws Exception {
        // Given
        EnrichmentRequest request = createTestRequest();
        when(enrichmentService.enrichTransactions(any()))
                .thenThrow(new RuntimeException("Service error"));

        // When/Then
        mockMvc.perform(post("/api/v1/enrich")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("POST /api/v1/enrich - Should return 502 for PlaidApiException with error details")
    void shouldReturn502ForPlaidApiException() throws Exception {
        // Given
        EnrichmentRequest request = createTestRequest();
        when(enrichmentService.enrichTransactions(any()))
                .thenThrow(new PlaidApiException("Plaid service unavailable", 503, "PLAID_ERROR_001"));

        // When/Then
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
        // Given
        EnrichmentRequest request = createTestRequest();
        when(enrichmentService.enrichTransactions(any()))
                .thenThrow(new IllegalArgumentException("Invalid account ID format"));

        // When/Then
        mockMvc.perform(post("/api/v1/enrich")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Request"))
                .andExpect(jsonPath("$.detail").value("Invalid account ID format"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // Helper methods
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
