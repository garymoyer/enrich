# Plaid Enrich Service — API Documentation

**Version:** 1.0.0
**Owner:** Platform Team — platform@example.com
**Live API Docs:** `http://<host>:8080/swagger-ui.html`
**OpenAPI Spec:** `http://<host>:8080/api-docs`

---

## Table of Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [Authentication](#3-authentication)
4. [Base URL & Environments](#4-base-url--environments)
5. [API Endpoints](#5-api-endpoints)
   - [POST /api/v1/enrich](#51-post-apiv1enrich)
   - [POST /api/v1/enrich/batch](#52-post-apiv1enrichbatch)
   - [GET /api/v1/enrich/{requestId}](#53-get-apiv1enrichrequestid)
   - [GET /api/v1/enrich/health](#54-get-apiv1enrichhealth)
6. [Data Models](#6-data-models)
7. [Error Handling](#7-error-handling)
8. [Resilience Behaviour](#8-resilience-behaviour)
9. [Observability](#9-observability)
10. [Configuration Reference](#10-configuration-reference)

---

## 1. Overview

The **Plaid Enrich Service** is a production-ready Spring Boot 3.4 microservice that enriches raw financial transactions using the [Plaid Enrich API](https://plaid.com/docs/transactions/enrich/). It provides:

- Merchant name standardisation
- Spending category classification
- Merchant logo and website resolution
- Confidence scoring on enrichment results
- Audit-trail persistence — every request and response is stored with a UUID for later retrieval
- Resilient Plaid API integration via circuit breaker, retry, and bulkhead patterns

### Key Capabilities

| Capability | Detail |
|---|---|
| Single enrichment | Enrich one batch of transactions synchronously |
| Batch enrichment | Enrich multiple batches in parallel via Project Reactor |
| Request tracking | Every call receives a UUID v4 `requestId` for end-to-end traceability |
| Idempotent retrieval | Retrieve any previous enrichment result by `requestId` |
| Health & metrics | Actuator health probes, Prometheus metrics, circuit breaker state |

---

## 2. Architecture

```
Client
  │
  ▼
EnrichmentController          REST layer — validation, routing
  │
  ▼
EnrichmentService             Orchestration — GUID generation, persistence, mapping
  │           │
  ▼           ▼
PlaidApiClient     EnrichmentRepository
(WebClient +       (Spring Data JPA →
 Resilience4j)      Azure SQL / H2)
  │
  ▼
Plaid Enrich API
```

**Technology Stack**

| Layer | Technology |
|---|---|
| Runtime | Java 21, Spring Boot 3.4 |
| HTTP | Spring MVC (sync) + Spring WebFlux (async Plaid calls) |
| Persistence | Spring Data JPA, Flyway, Azure SQL Server |
| Resilience | Resilience4j (circuit breaker, retry, bulkhead) |
| Observability | Micrometer, Prometheus, Azure Application Insights |
| Documentation | SpringDoc OpenAPI 3 / Swagger UI |

---

## 3. Authentication

The service itself does **not** enforce consumer-level authentication in the current version — it is intended to be deployed behind an API gateway or internal network boundary.

Outbound calls to the Plaid API are authenticated with two credentials injected at startup via environment variables:

| Variable | Purpose |
|---|---|
| `PLAID_CLIENT_ID` | Plaid client identifier |
| `PLAID_API_KEY` | Plaid secret API key |

These are never returned in any API response.

---

## 4. Base URL & Environments

| Environment | Base URL |
|---|---|
| Local development | `http://localhost:8080` |
| Production | `https://api.example.com` |

All endpoints are relative to the base URL and share the prefix `/api/v1/enrich`.

---

## 5. API Endpoints

### 5.1 POST /api/v1/enrich

Enriches a single batch of transactions. Generates a UUID for tracking, calls Plaid, persists the request and response, and returns the enriched result.

**Request**

```
POST /api/v1/enrich
Content-Type: application/json
```

**Request Body**

```json
{
  "accountId": "acc_12345",
  "transactions": [
    {
      "description": "STARBUCKS COFFEE #123",
      "amount": 5.75,
      "date": "2026-01-30",
      "merchantName": "Starbucks"
    }
  ]
}
```

| Field | Type | Required | Constraints | Description |
|---|---|---|---|---|
| `accountId` | `string` | Yes | Not blank | Account identifier for the transactions |
| `transactions` | `array` | Yes | Min 1 item | List of transactions to enrich |
| `transactions[].description` | `string` | Yes | Not blank | Transaction description or memo from the bank |
| `transactions[].amount` | `number` | Yes | Positive | Transaction amount |
| `transactions[].date` | `string` | Yes | Format `yyyy-MM-dd` | Transaction date |
| `transactions[].merchantName` | `string` | No | — | Merchant name hint if already known |

**Responses**

---

**200 OK — Enrichment successful**

```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "enrichedTransactions": [
    {
      "transactionId": "txn_1",
      "category": "Food & Drink",
      "merchantName": "Starbucks Coffee",
      "logoUrl": "https://logo.clearbit.com/starbucks.com",
      "metadata": {
        "categoryId": "13005000",
        "website": "https://www.starbucks.com",
        "confidenceLevel": "HIGH",
        "location": "Seattle, WA"
      }
    }
  ],
  "processedAt": "2026-01-30T14:23:45.123+00:00",
  "status": "SUCCESS",
  "errorMessage": null
}
```

> **Note:** If Plaid enrichment fails, the response still returns **200 OK** with `"status": "FAILED"` and a populated `errorMessage`. The request is still persisted and retrievable by `requestId`.

---

**400 Bad Request — Validation failure**

```json
{
  "type": "about:blank",
  "title": "Validation Error",
  "status": 400,
  "detail": "Validation failed for one or more fields",
  "timestamp": "2026-01-30T14:23:45.123+00:00",
  "errors": {
    "accountId": "Account ID is required",
    "transactions[0].amount": "Amount must be positive"
  }
}
```

---

**502 Bad Gateway — Plaid API error**

```json
{
  "type": "https://plaid.com/docs/errors",
  "title": "Plaid API Error",
  "status": 502,
  "detail": "Error communicating with Plaid API: connection refused",
  "timestamp": "2026-01-30T14:23:45.123+00:00",
  "plaidStatusCode": 503,
  "plaidErrorCode": "PLAID_ERROR_CODE"
}
```

---

**Example — curl**

```bash
curl -X POST http://localhost:8080/api/v1/enrich \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "acc_12345",
    "transactions": [{
      "description": "STARBUCKS COFFEE",
      "amount": 5.75,
      "date": "2026-01-30"
    }]
  }'
```

---

### 5.2 POST /api/v1/enrich/batch

Enriches multiple transaction batches in parallel. Each batch receives its own `requestId` and is processed independently. Failed batches do not block successful ones.

**Request**

```
POST /api/v1/enrich/batch
Content-Type: application/json
```

**Request Body**

An array of [EnrichmentRequest](#61-enrichmentrequest) objects (min 1 item).

```json
[
  {
    "accountId": "acc_001",
    "transactions": [
      { "description": "AMAZON", "amount": 29.99, "date": "2026-01-29" }
    ]
  },
  {
    "accountId": "acc_002",
    "transactions": [
      { "description": "UBER", "amount": 14.50, "date": "2026-01-30" }
    ]
  }
]
```

**Responses**

---

**200 OK — Batch processed** (some items may have `"status": "FAILED"`)

```json
[
  {
    "requestId": "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa",
    "enrichedTransactions": [...],
    "processedAt": "2026-01-30T14:23:45.000+00:00",
    "status": "SUCCESS",
    "errorMessage": null
  },
  {
    "requestId": "bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb",
    "enrichedTransactions": [],
    "processedAt": "2026-01-30T14:23:45.001+00:00",
    "status": "FAILED",
    "errorMessage": "Plaid API returned 503"
  }
]
```

---

**400 Bad Request — Empty batch**

```json
{
  "type": "about:blank",
  "title": "Validation Error",
  "status": 400,
  "detail": "Validation failed for one or more fields"
}
```

---

**Example — curl**

```bash
curl -X POST http://localhost:8080/api/v1/enrich/batch \
  -H "Content-Type: application/json" \
  -d '[
    {"accountId":"acc_001","transactions":[{"description":"AMAZON","amount":29.99,"date":"2026-01-29"}]},
    {"accountId":"acc_002","transactions":[{"description":"UBER","amount":14.50,"date":"2026-01-30"}]}
  ]'
```

---

### 5.3 GET /api/v1/enrich/{requestId}

Retrieves a previously processed enrichment result by its UUID. Supports audit, debugging, and idempotent retry scenarios.

**Request**

```
GET /api/v1/enrich/{requestId}
```

| Parameter | Location | Type | Description |
|---|---|---|---|
| `requestId` | Path | UUID string | The UUID returned at enrichment time, e.g. `550e8400-e29b-41d4-a716-446655440000` |

**Responses**

---

**200 OK — Record found**

Returns the same [EnrichmentResponse](#62-enrichmentresponse) structure as the original enrichment call.

---

**404 Not Found — Unknown requestId**

Empty body with HTTP 404 status.

---

**Example — curl**

```bash
curl http://localhost:8080/api/v1/enrich/550e8400-e29b-41d4-a716-446655440000
```

---

### 5.4 GET /api/v1/enrich/health

Simple liveness check for the enrichment controller.

**Request**

```
GET /api/v1/enrich/health
```

**Response — 200 OK**

```
Enrichment service is healthy
```

> For comprehensive health status including database and circuit breaker state, use the [Actuator health endpoint](#91-health-endpoints).

---

## 6. Data Models

### 6.1 EnrichmentRequest

| Field | Type | Required | Constraints | Description |
|---|---|---|---|---|
| `accountId` | `string` | Yes | Not blank | Account identifier |
| `transactions` | `Transaction[]` | Yes | Min 1 | Transactions to enrich |

**Transaction**

| Field | Type | Required | Constraints | Description |
|---|---|---|---|---|
| `description` | `string` | Yes | Not blank | Bank-provided transaction description |
| `amount` | `decimal` | Yes | > 0 | Transaction amount |
| `date` | `string` | Yes | `yyyy-MM-dd` | Transaction date |
| `merchantName` | `string` | No | — | Merchant name hint |

---

### 6.2 EnrichmentResponse

| Field | Type | Description |
|---|---|---|
| `requestId` | `string (UUID)` | Unique identifier for this enrichment request |
| `enrichedTransactions` | `EnrichedTransaction[]` | Array of enriched transaction details |
| `processedAt` | `string (ISO 8601)` | Timestamp when the request was processed |
| `status` | `string` | `SUCCESS` or `FAILED` |
| `errorMessage` | `string \| null` | Error detail if `status` is `FAILED` |

**EnrichedTransaction**

| Field | Type | Description |
|---|---|---|
| `transactionId` | `string` | Transaction identifier from Plaid |
| `category` | `string` | Plaid-determined spending category (e.g. `Food & Drink`) |
| `merchantName` | `string` | Standardised merchant name (e.g. `Starbucks Coffee`) |
| `logoUrl` | `string` | URL to the merchant's logo |
| `metadata` | `object` | Additional Plaid enrichment data (see below) |

**metadata object fields**

| Key | Type | Description |
|---|---|---|
| `categoryId` | `string` | Plaid category ID (e.g. `13005000`) |
| `website` | `string` | Merchant website URL |
| `confidenceLevel` | `string` | Plaid confidence in the enrichment: `HIGH`, `MEDIUM`, or `LOW` |
| *(additional keys)* | `any` | Any extra metadata returned by Plaid in `enrichment_metadata` |

---

### 6.3 Error Response (RFC 7807 Problem Detail)

All error responses follow [RFC 7807 Problem Detail](https://datatracker.ietf.org/doc/html/rfc7807).

| Field | Type | Description |
|---|---|---|
| `type` | `string (URI)` | URI identifying the error type |
| `title` | `string` | Short human-readable error title |
| `status` | `integer` | HTTP status code |
| `detail` | `string` | Human-readable explanation of the error |
| `timestamp` | `string (ISO 8601)` | When the error occurred |
| `errors` | `object` | *(Validation errors only)* Field-level validation messages |
| `plaidStatusCode` | `integer` | *(Plaid errors only)* HTTP status returned by Plaid |
| `plaidErrorCode` | `string` | *(Plaid errors only)* Plaid-specific error code |

---

## 7. Error Handling

### HTTP Status Code Summary

| Status | When | Title |
|---|---|---|
| `200 OK` | Request processed (enrichment may have FAILED internally) | — |
| `400 Bad Request` | Request body fails validation or an `IllegalArgumentException` is thrown | `Validation Error` / `Invalid Request` |
| `404 Not Found` | `requestId` not found in the database | — |
| `500 Internal Server Error` | Unexpected server-side error | `Internal Server Error` |
| `502 Bad Gateway` | Plaid API returned an error or is unreachable | `Plaid API Error` |

### Enrichment-Level Failures

When Plaid enrichment fails (network error, timeout, Plaid 5xx after retries are exhausted), the service returns **HTTP 200** with a body where `"status": "FAILED"`. This allows clients to distinguish between:

- **Transport failure** — the service itself was unreachable (client receives a non-200 or network error)
- **Enrichment failure** — the service received the request but could not complete enrichment (client receives 200 with `FAILED` status)

The failed request is still persisted and retrievable by `requestId`.

---

## 8. Resilience Behaviour

Outbound calls to the Plaid API are protected by three Resilience4j patterns applied in order:

```
Request → Bulkhead → Retry → Circuit Breaker → Plaid API
```

### 8.1 Circuit Breaker

Prevents cascading failures when Plaid is degraded.

| Setting | Value |
|---|---|
| Sliding window | 10 calls (count-based) |
| Minimum calls before evaluation | 5 |
| Failure rate threshold | 50% |
| Wait in OPEN state | 10 seconds |
| Half-open probe calls | 3 |
| Auto-transition OPEN → HALF-OPEN | Enabled |
| Counted as failures | Connection errors, 500, 503, TimeoutException |

**States:** `CLOSED` (normal) → `OPEN` (rejecting calls) → `HALF-OPEN` (probing) → `CLOSED`

Circuit breaker state is exposed via the Actuator at `/actuator/health` and `/actuator/circuitbreakers`.

---

### 8.2 Retry

Automatically retries transient failures before surfacing an error.

| Setting | Value |
|---|---|
| Max attempts | 3 |
| Wait between attempts | 1 second (exponential: ×2 each retry) |
| Retried on | Connection errors, TimeoutException, 503 |
| Not retried on | 400 Bad Request, 401 Unauthorized |

---

### 8.3 Bulkhead (Concurrency Limiter)

Prevents the service from overwhelming Plaid with concurrent requests.

| Setting | Value |
|---|---|
| Max concurrent calls | 10 |
| Max wait for a permit | 2 seconds |

If the bulkhead is saturated, the call fails immediately with a `BulkheadFullException` (surfaced as a `PlaidApiException`).

---

## 9. Observability

### 9.1 Health Endpoints

| Path | Description |
|---|---|
| `GET /actuator/health` | Full health — includes DB, disk, circuit breaker |
| `GET /actuator/health/liveness` | Kubernetes liveness probe |
| `GET /actuator/health/readiness` | Kubernetes readiness probe |
| `GET /actuator/circuitbreakers` | Circuit breaker state and metrics |
| `GET /api/v1/enrich/health` | Lightweight controller-level liveness check |

**Example health response**

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "circuitBreakers": {
      "status": "UP",
      "details": {
        "plaidApi": {
          "status": "UP",
          "details": {
            "state": "CLOSED",
            "failureRate": "10.0%",
            "numberOfBufferedCalls": 10,
            "numberOfFailedCalls": 1
          }
        }
      }
    },
    "livenessState": { "status": "UP" },
    "readinessState": { "status": "UP" }
  }
}
```

---

### 9.2 Metrics

Prometheus metrics are exposed at `GET /actuator/prometheus`.

Key metrics emitted:

| Metric | Description |
|---|---|
| `resilience4j_circuitbreaker_state` | Circuit breaker state (0=CLOSED, 1=OPEN, 2=HALF_OPEN) |
| `resilience4j_circuitbreaker_calls_total` | Total calls by kind (successful, failed, not_permitted) |
| `resilience4j_retry_calls_total` | Retry attempts by outcome |
| `resilience4j_bulkhead_available_concurrent_calls` | Available bulkhead slots |
| `http_server_requests_seconds` | HTTP request latency histogram (by URI, method, status) |
| `spring_data_repository_invocations_seconds` | JPA repository method latency |

---

## 10. Configuration Reference

The following environment variables control runtime behaviour.

### Plaid API

| Variable | Default | Description |
|---|---|---|
| `PLAID_API_BASE_URL` | `http://localhost:8089` | Plaid API base URL |
| `PLAID_API_KEY` | `mock-key` | Plaid secret key |
| `PLAID_CLIENT_ID` | `mock-client-id` | Plaid client ID |

### Database

| Variable | Default | Description |
|---|---|---|
| `DB_URL` | *(none — required in prod)* | JDBC URL for Azure SQL |
| `DB_USERNAME` | *(none — required in prod)* | Database username |
| `DB_PASSWORD` | *(none — required in prod)* | Database password |

### Timeouts

| Setting | Value | Config key |
|---|---|---|
| Connection timeout | 5 000 ms | `plaid.api.timeout.connection` |
| Request timeout | 10 000 ms | `plaid.api.timeout.request` |

### Actuator

| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP server port |

---

## Appendix A — Request & Response Examples

### Successful Enrichment — Full Round Trip

**Request**
```bash
curl -X POST http://localhost:8080/api/v1/enrich \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "acc_12345",
    "transactions": [
      {
        "description": "STARBUCKS COFFEE #4201",
        "amount": 5.75,
        "date": "2026-01-30",
        "merchantName": "Starbucks"
      },
      {
        "description": "AMAZON.COM*AB1CD2EF3",
        "amount": 34.99,
        "date": "2026-01-29"
      }
    ]
  }'
```

**Response**
```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "enrichedTransactions": [
    {
      "transactionId": "txn_1",
      "category": "Food & Drink",
      "merchantName": "Starbucks Coffee",
      "logoUrl": "https://logo.clearbit.com/starbucks.com",
      "metadata": {
        "categoryId": "13005000",
        "website": "https://www.starbucks.com",
        "confidenceLevel": "HIGH"
      }
    },
    {
      "transactionId": "txn_2",
      "category": "Shopping",
      "merchantName": "Amazon",
      "logoUrl": "https://logo.clearbit.com/amazon.com",
      "metadata": {
        "categoryId": "19013000",
        "website": "https://www.amazon.com",
        "confidenceLevel": "HIGH"
      }
    }
  ],
  "processedAt": "2026-01-30T14:23:45.123+00:00",
  "status": "SUCCESS",
  "errorMessage": null
}
```

**Retrieve by requestId**
```bash
curl http://localhost:8080/api/v1/enrich/550e8400-e29b-41d4-a716-446655440000
```

---

### Failed Enrichment (Plaid Unavailable)

**Response — 200 OK with FAILED status**
```json
{
  "requestId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "enrichedTransactions": [],
  "processedAt": "2026-01-30T14:24:00.000+00:00",
  "status": "FAILED",
  "errorMessage": "Plaid API returned 503 after 3 retries"
}
```

---

## Appendix B — Plaid Category Examples

| `categoryId` | `category` | Typical merchants |
|---|---|---|
| `13005000` | Food & Drink | Starbucks, McDonald's, Uber Eats |
| `19013000` | Shopping | Amazon, Walmart, Target |
| `22016000` | Travel | Delta, Airbnb, Marriott |
| `17001000` | Healthcare | CVS Pharmacy, Walgreens |
| `18061000` | Utilities | AT&T, Comcast |

> Category IDs are defined by Plaid and subject to change. Refer to [Plaid's category documentation](https://plaid.com/docs/transactions/categories/) for the full taxonomy.
