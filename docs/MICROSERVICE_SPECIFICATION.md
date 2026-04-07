# TD Enrich Service - Microservice Specification

**Version:** 1.0.0  
**Last Updated:** April 7, 2026  
**Audience:** Backend Engineers, SREs, API Integrators

---

## Table of Contents

1. [Overview](#overview)
2. [Audience and Prerequisites](#audience-and-prerequisites)
3. [API Surface (Public)](#api-surface-public)
4. [Upstream Integration](#upstream-integration)
5. [Data Models and Schema](#data-models-and-schema)
6. [Authentication & Authorization](#authentication--authorization)
7. [Error Handling and Retries](#error-handling-and-retries)
8. [Observability](#observability)
9. [Security](#security)
10. [Scalability and Deployment](#scalability-and-deployment)
11. [Testing](#testing)
12. [Maintenance & Runbook](#maintenance--runbook)
13. [FAQ and Changelog](#faq-and-changelog)
14. [Appendix](#appendix)

---

## Overview

The **TD Enrich Service** is a Spring Boot microservice that enriches financial transaction data by leveraging the Plaid API to provide standardized merchant information, categories, and confidence scores. It exposes RESTful endpoints for single and batch transaction enrichment, implements intelligent merchant caching to minimize upstream API calls, and provides high availability through circuit breaking, retry logic, and horizontal scaling. The service is designed for transaction processing platforms, fintech applications, and wallet services that require accurate, real-time merchant enrichment at scale.

---

## Audience and Prerequisites

### Target Audiences

1. **Backend Engineers** – Implementing transaction enrichment in downstream services
2. **Platform/SRE Teams** – Deploying, monitoring, and scaling the service
3. **API Integrators** – Consuming the public REST API from client applications
4. **Security Engineers** – Reviewing threat models and compliance configurations
5. **Data Engineers** – Analyzing enrichment logs, audit trails, and cache performance

### Required Knowledge

- Basic HTTP/REST API concepts and status codes
- JSON request/response payload structures
- OAuth 2.0 bearer tokens and JWT claims
- Container deployment concepts (Docker, Kubernetes)
- Structured logging and OpenTelemetry observability

### Versions and Dependencies

| Component | Version | Notes |
|-----------|---------|-------|
| Java | 21 (LTS) | Project Loom virtual threads enabled |
| Spring Boot | 3.4.2 | Spring Data JPA, WebFlux, Actuator |
| Resilience4j | 2.2.0 | Circuit breaker, retry, bulkhead, timeout |
| OpenTelemetry | 1.40.0 | Tracing and metrics export |
| H2 Database | Latest | Development/testing; switches to Azure SQL in production |
| Plaid API | Latest | Upstream financial data provider |

### Environment Variables

| Variable | Example | Required | Purpose |
|----------|---------|----------|---------|
| `SERVICE_PORT` | `8080` | No | HTTP server port (default: 8080) |
| `CACHE_MAX_SIZE` | `10000` | No | Merchant cache capacity (default: 10000) |
| `CACHE_TTL_MINUTES` | `1440` | No | Merchant cache time-to-live in minutes (default: 1 day) |
| `PLAID_API_KEY` | `${PLAID_SECRET}` | Yes | Upstream Plaid API secret key |
| `PLAID_CLIENT_ID` | `${PLAID_ID}` | Yes | Upstream Plaid client identifier |
| `DB_URL` | `jdbc:h2:mem:enrich` | No | Database JDBC URL (H2 for dev, Azure SQL for prod) |
| `DB_USER` | `sa` | No | Database username |
| `DB_PASSWORD` | `${DB_SECRET}` | Yes | Database password |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://otel-collector:4317` | No | OpenTelemetry collector endpoint |
| `CIRCUIT_BREAKER_THRESHOLD` | `50` | No | Circuit breaker failure threshold % (default: 50) |
| `CIRCUIT_BREAKER_COOL_DOWN_SECONDS` | `10` | No | Circuit breaker recovery wait time (default: 10s) |

See [.env.example](#envexample) in the Appendix for a complete template.

---

## API Surface (Public)

### OpenAPI 3.0 Specification

```yaml
openapi: 3.0.3
info:
  title: TD Enrich Service
  version: 1.0.0
  description: >
    Transaction enrichment microservice providing merchant data, categories, and confidence scores
    via the Plaid API. Supports single sync and batch async enrichment patterns with intelligent caching.
  contact:
    name: Platform Team
    email: platform@company.com
  license:
    name: Proprietary
    url: https://company.com/license

servers:
  - url: https://api.company.com/enrich/v1
    description: Production
    variables: {}
  - url: http://localhost:8080/api/v1/enrich
    description: Local Development

paths:
  /health:
    get:
      summary: Health check endpoint
      operationId: getHealth
      tags:
        - Health
      responses:
        '200':
          description: Service is healthy
          content:
            application/json:
              schema:
                type: object
                properties:
                  status:
                    type: string
                    enum: [UP, DOWN]
                  timestamp:
                    type: string
                    format: date-time
                  checks:
                    type: object
                    properties:
                      database:
                        type: string
                        enum: [UP, DOWN]
                      plaidApi:
                        type: string
                        enum: [UP, DOWN]
                      cache:
                        type: string
                        enum: [UP, DOWN]
              example:
                status: UP
                timestamp: '2026-04-07T15:30:00Z'
                checks:
                  database: UP
                  plaidApi: UP
                  cache: UP
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

  /single:
    post:
      summary: Enrich a single transaction synchronously
      description: |
        Accepts a single transaction and enriches it with merchant data from Plaid API.
        Blocks until enrichment completes or timeout is reached (10 seconds).
        Results are cached to minimize future API calls.
      operationId: enrichSingle
      tags:
        - Enrichment
      security:
        - bearerAuth: [ ]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/EnrichmentRequest'
            example:
              accountId: 'acc_12345'
              transactions:
                - description: 'STARBUCKS COFFEE #1234 SEATTLE WA'
                  amount: 5.75
                  date: '2026-04-07'
                  merchantName: 'Starbucks'
      responses:
        '200':
          description: Transaction enriched successfully
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/EnrichmentResponse'
              example:
                requestId: '550e8400-e29b-41d4-a716-446655440000'
                enrichedTransactions:
                  - transactionId: 'txn_001'
                    merchantId: 'merchant-id-starbucks-001'
                    category: 'Coffee Shops'
                    merchantName: 'Starbucks Coffee'
                    logoUrl: 'https://logo.clearbit.com/starbucks.com'
                    metadata:
                      confidence: 0.99
                      enrichmentMetadata:
                        location: 'Seattle, WA'
                        category_code: '5461'
                processedAt: '2026-04-07T15:30:15.123Z'
                status: 'SUCCESS'
                errorMessage: null
        '400':
          $ref: '#/components/responses/BadRequest'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '429':
          $ref: '#/components/responses/TooManyRequests'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

  /batch:
    post:
      summary: Enrich multiple transactions asynchronously
      description: |
        Accepts a batch of transactions for asynchronous enrichment.
        Returns immediately with GUIDs for polling results.
        Batch processing is handled by a queue processor with virtual threads.
      operationId: enrichBatch
      tags:
        - Enrichment
      security:
        - bearerAuth: [ ]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/BatchEnrichmentRequest'
            example:
              transactions:
                - description: 'STARBUCKS #1234'
                  amount: 5.75
                  date: '2026-04-07'
                  merchantName: 'Starbucks'
                - description: 'AMAZON.COM #6789'
                  amount: 45.99
                  date: '2026-04-07'
                  merchantName: 'Amazon'
      responses:
        '202':
          description: Batch submitted for asynchronous processing
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/BatchEnrichmentResponse'
              example:
                count: 2
                guids:
                  - '550e8400-e29b-41d4-a716-446655440001'
                  - '550e8400-e29b-41d4-a716-446655440002'
                message: 'Batch of 2 transactions submitted for enrichment'
        '400':
          $ref: '#/components/responses/BadRequest'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '429':
          $ref: '#/components/responses/TooManyRequests'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

  /{guid}:
    get:
      summary: Poll for batch enrichment result
      description: |
        Retrieves enrichment result by GUID.
        If enrichment is complete, returns EnrichmentResponse.
        If still processing, returns response with status=PROCESSING.
      operationId: getEnrichmentResult
      tags:
        - Enrichment
      security:
        - bearerAuth: [ ]
      parameters:
        - in: path
          name: guid
          required: true
          schema:
            type: string
            format: uuid
          description: Enrichment request GUID from /batch endpoint
          example: '550e8400-e29b-41d4-a716-446655440001'
      responses:
        '200':
          description: Enrichment result (complete or in progress)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/EnrichmentResponse'
              example:
                requestId: '550e8400-e29b-41d4-a716-446655440001'
                enrichedTransactions:
                  - transactionId: 'txn_001'
                    merchantId: 'merchant-id-starbucks-001'
                    category: 'Coffee Shops'
                    merchantName: 'Starbucks Coffee'
                    logoUrl: 'https://logo.clearbit.com/starbucks.com'
                    metadata: { }
                processedAt: '2026-04-07T15:30:15.123Z'
                status: 'SUCCESS'
                errorMessage: null
        '202':
          description: Still processing (returned if not yet complete)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/EnrichmentResponse'
              example:
                requestId: '550e8400-e29b-41d4-a716-446655440001'
                enrichedTransactions: [ ]
                processedAt: null
                status: 'PROCESSING'
                errorMessage: null
        '401':
          $ref: '#/components/responses/Unauthorized'
        '404':
          $ref: '#/components/responses/NotFound'
        '429':
          $ref: '#/components/responses/TooManyRequests'

components:
  schemas:
    EnrichmentRequest:
      type: object
      required: [accountId, transactions]
      properties:
        accountId:
          type: string
          minLength: 1
          maxLength: 255
          description: Unique account identifier for the requestor
          example: 'acc_12345'
        transactions:
          type: array
          minItems: 1
          maxItems: 200
          description: List of transactions to enrich (max 200 per request)
          items:
            $ref: '#/components/schemas/Transaction'

    Transaction:
      type: object
      required: [description, amount, date]
      properties:
        description:
          type: string
          minLength: 1
          maxLength: 255
          description: Transaction description or merchant name
          example: 'STARBUCKS COFFEE #1234 SEATTLE WA'
        amount:
          type: number
          format: decimal
          minimum: 0
          maximum: 999999.99
          description: Transaction amount in USD
          example: 5.75
        date:
          type: string
          format: date
          description: Transaction date in YYYY-MM-DD format
          example: '2026-04-07'
        merchantName:
          type: string
          minLength: 1
          maxLength: 255
          nullable: true
          description: Known merchant name (optional hint for enrichment)
          example: 'Starbucks'

    BatchEnrichmentRequest:
      type: object
      required: [transactions]
      properties:
        transactions:
          type: array
          minItems: 1
          maxItems: 1000
          items:
            $ref: '#/components/schemas/Transaction'

    EnrichmentResponse:
      type: object
      required: [requestId, enrichedTransactions, status]
      properties:
        requestId:
          type: string
          format: uuid
          description: Unique GUID for this enrichment request
          example: '550e8400-e29b-41d4-a716-446655440000'
        enrichedTransactions:
          type: array
          description: List of enriched transactions
          items:
            $ref: '#/components/schemas/EnrichedTransaction'
        processedAt:
          type: string
          format: date-time
          nullable: true
          description: Timestamp when enrichment completed
          example: '2026-04-07T15:30:15.123Z'
        status:
          type: string
          enum: [SUCCESS, PROCESSING, FAILED]
          description: Overall enrichment status
          example: 'SUCCESS'
        errorMessage:
          type: string
          nullable: true
          description: Error message if status is FAILED
          example: null

    EnrichedTransaction:
      type: object
      required: [transactionId, merchantId, category, merchantName]
      properties:
        transactionId:
          type: string
          description: Unique transaction identifier within the request
          example: 'txn_001'
        merchantId:
          type: string
          format: uuid
          description: Unique merchant identifier in cache (stable across time)
          example: 'merchant-id-starbucks-001'
        category:
          type: string
          description: Merchant category from Plaid
          example: 'Coffee Shops'
        merchantName:
          type: string
          description: Standardized merchant name from Plaid
          example: 'Starbucks Coffee'
        logoUrl:
          type: string
          format: uri
          nullable: true
          description: URL to merchant logo
          example: 'https://logo.clearbit.com/starbucks.com'
        metadata:
          type: object
          additionalProperties: true
          description: Additional metadata from Plaid (confidence, location, etc.)
          example:
            confidence: 0.99
            enrichmentMetadata:
              location: 'Seattle, WA'
              category_code: '5461'

    BatchEnrichmentResponse:
      type: object
      required: [count, guids, message]
      properties:
        count:
          type: integer
          minimum: 1
          description: Number of transactions submitted
          example: 2
        guids:
          type: array
          items:
            type: string
            format: uuid
          description: GUIDs for polling results
          example:
            - '550e8400-e29b-41d4-a716-446655440001'
            - '550e8400-e29b-41d4-a716-446655440002'
        message:
          type: string
          description: Human-readable status message
          example: 'Batch of 2 transactions submitted for enrichment'

    ErrorResponse:
      type: object
      required: [error, statusCode, timestamp, traceId]
      properties:
        error:
          type: string
          description: Error code identifier
          example: 'INVALID_REQUEST'
        message:
          type: string
          description: Human-readable error message
          example: 'Transaction amount must be positive'
        statusCode:
          type: integer
          example: 400
        timestamp:
          type: string
          format: date-time
          example: '2026-04-07T15:30:00Z'
        traceId:
          type: string
          description: OpenTelemetry trace ID for debugging
          example: '4bf92f3577b34da6a3ce929d0e0e4736'

  responses:
    BadRequest:
      description: Invalid request format or validation failure
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
          example:
            error: INVALID_REQUEST
            message: 'Transaction amount must be between 0 and 999999.99'
            statusCode: 400
            timestamp: '2026-04-07T15:30:00Z'
            traceId: '4bf92f3577b34da6a3ce929d0e0e4736'

    Unauthorized:
      description: Missing or invalid authentication token
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
          example:
            error: UNAUTHORIZED
            message: 'Bearer token is missing or invalid'
            statusCode: 401
            timestamp: '2026-04-07T15:30:00Z'
            traceId: '4bf92f3577b34da6a3ce929d0e0e4736'

    NotFound:
      description: Resource (GUID) not found
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
          example:
            error: NOT_FOUND
            message: 'Enrichment request with GUID 550e8400-e29b-41d4-a716-446655440001 not found'
            statusCode: 404
            timestamp: '2026-04-07T15:30:00Z'
            traceId: '4bf92f3577b34da6a3ce929d0e0e4736'

    TooManyRequests:
      description: Rate limit exceeded
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
          example:
            error: RATE_LIMITED
            message: 'Rate limit exceeded: 100 requests per minute'
            statusCode: 429
            timestamp: '2026-04-07T15:30:00Z'
            traceId: '4bf92f3577b34da6a3ce929d0e0e4736'

    ServiceUnavailable:
      description: Service unavailable (circuit breaker open or upstream failure)
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
          example:
            error: SERVICE_UNAVAILABLE
            message: 'Upstream Plaid API is temporarily unavailable. Circuit breaker is open.'
            statusCode: 503
            timestamp: '2026-04-07T15:30:00Z'
            traceId: '4bf92f3577b34da6a3ce929d0e0e4736'

  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
      description: >
        OAuth 2.0 Bearer token (JWT).
        Token must contain 'enrichment:read' or 'enrichment:write' scope.
        Issued by Authorization Server at https://auth.company.com
```

### Endpoint Details

#### 1. POST `/single` – Synchronous Single Transaction Enrichment

**Purpose:** Enrich a single transaction in a blocking, synchronous manner.

**Request Example (cURL):**
```bash
curl -X POST http://localhost:8080/api/v1/enrich/single \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "acc_customer_001",
    "transactions": [
      {
        "description": "WHOLE FOODS MKT #10234 BROOKLYN NY",
        "amount": 87.43,
        "date": "2026-04-07",
        "merchantName": "Whole Foods"
      }
    ]
  }'
```

**Request Example (JavaScript):**
```javascript
const response = await fetch('http://localhost:8080/api/v1/enrich/single', {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json'
  },
  body: JSON.stringify({
    accountId: 'acc_customer_001',
    transactions: [
      {
        description: 'WHOLE FOODS MKT #10234 BROOKLYN NY',
        amount: 87.43,
        date: '2026-04-07',
        merchantName: 'Whole Foods'
      }
    ]
  })
});

const data = await response.json();
console.log(data.enrichedTransactions[0].merchantName); // "Whole Foods Market"
```

**Successful Response (200 OK):**
```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440001",
  "enrichedTransactions": [
    {
      "transactionId": "txn_001",
      "merchantId": "merchant-1234-wf-brooklyn",
      "category": "Grocery Stores",
      "merchantName": "Whole Foods Market",
      "logoUrl": "https://logo.clearbit.com/wholefoodsmarket.com",
      "metadata": {
        "confidence": 0.98,
        "enrichmentMetadata": {
          "location": "Brooklyn, NY",
          "category_code": "5411",
          "mcc": "5411"
        }
      }
    }
  ],
  "processedAt": "2026-04-07T15:30:15.123Z",
  "status": "SUCCESS",
  "errorMessage": null
}
```

**Error Responses:**

| Status | Error Code | Message | Cause |
|--------|-----------|---------|-------|
| 400 | `INVALID_REQUEST` | "Transaction amount must be positive" | Amount ≤ 0 or exceeds max |
| 400 | `INVALID_REQUEST` | "Transaction date must be in YYYY-MM-DD format" | Date parsing failure |
| 401 | `UNAUTHORIZED` | "Bearer token is missing or invalid" | Missing/expired JWT |
| 429 | `RATE_LIMITED` | "Rate limit exceeded: 100 requests per minute" | Client quota exhausted |
| 503 | `SERVICE_UNAVAILABLE` | "Circuit breaker is open; Plaid API unavailable" | Upstream failure threshold met |

**Authentication:** Bearer token with `enrichment:write` scope (see [Authentication & Authorization](#authentication--authorization)).

**Timeout:** 10 seconds (configurable). Requests exceeding this will return 503 Service Unavailable.

---

#### 2. POST `/batch` – Asynchronous Batch Enrichment

**Purpose:** Submit multiple transactions for non-blocking, asynchronous enrichment.

**Request Example (cURL):**
```bash
curl -X POST http://localhost:8080/api/v1/enrich/batch \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "transactions": [
      {
        "description": "UBER TRIP SF BAY 04-07",
        "amount": 18.50,
        "date": "2026-04-07"
      },
      {
        "description": "GRUBHUB DELIVERY",
        "amount": 32.99,
        "date": "2026-04-07",
        "merchantName": "Grubhub"
      }
    ]
  }'
```

**Request Example (Python):**
```python
import requests

token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
headers = {
    "Authorization": f"Bearer {token}",
    "Content-Type": "application/json"
}
payload = {
    "transactions": [
        {
            "description": "UBER TRIP SF BAY 04-07",
            "amount": 18.50,
            "date": "2026-04-07"
        },
        {
            "description": "GRUBHUB DELIVERY",
            "amount": 32.99,
            "date": "2026-04-07",
            "merchantName": "Grubhub"
        }
    ]
}

response = requests.post(
    "http://localhost:8080/api/v1/enrich/batch",
    json=payload,
    headers=headers
)
print(response.json())  # { "count": 2, "guids": [...], "message": "..." }
```

**Successful Response (202 Accepted):**
```json
{
  "count": 2,
  "guids": [
    "550e8400-e29b-41d4-a716-446655440002",
    "550e8400-e29b-41d4-a716-446655440003"
  ],
  "message": "Batch of 2 transactions submitted for enrichment"
}
```

**Post-Response Action:** Client must poll `GET /{guid}` to check result status.

---

#### 3. GET `/{guid}` – Poll Batch Enrichment Result

**Purpose:** Retrieve enrichment result for a single transaction submitted via `/batch`.

**Request Example (cURL):**
```bash
# Poll until status != "PROCESSING"
GUID="550e8400-e29b-41d4-a716-446655440002"
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/enrich/$GUID
```

**Request Example (JavaScript):**
```javascript
async function pollForResult(guid, maxWaitMs = 30000) {
  const startTime = Date.now();
  const backoffMs = 100;
  let currentBackoff = backoffMs;

  while (Date.now() - startTime < maxWaitMs) {
    const response = await fetch(`http://localhost:8080/api/v1/enrich/${guid}`, {
      headers: { 'Authorization': `Bearer ${token}` }
    });

    const data = await response.json();

    if (data.status === 'SUCCESS' || data.status === 'FAILED') {
      return data; // Done
    }

    // Exponential backoff
    await new Promise(r => setTimeout(r, currentBackoff));
    currentBackoff = Math.min(currentBackoff * 2, 5000);
  }

  throw new Error(`Polling timeout after ${maxWaitMs}ms`);
}

const result = await pollForResult('550e8400-e29b-41d4-a716-446655440002');
console.log(result.enrichedTransactions[0].merchantName);
```

**Response While Processing (202 Accepted):**
```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440002",
  "enrichedTransactions": [],
  "processedAt": null,
  "status": "PROCESSING",
  "errorMessage": null
}
```

**Response After Completion (200 OK):**
```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440002",
  "enrichedTransactions": [
    {
      "transactionId": "txn_001",
      "merchantId": "merchant-uber-sf",
      "category": "Taxis & Limousines",
      "merchantName": "Uber",
      "logoUrl": "https://logo.clearbit.com/uber.com",
      "metadata": {
        "confidence": 0.99,
        "enrichmentMetadata": {
          "location": "San Francisco, CA",
          "category_code": "4121",
          "mcc": "4121"
        }
      }
    }
  ],
  "processedAt": "2026-04-07T15:30:25.456Z",
  "status": "SUCCESS",
  "errorMessage": null
}
```

---

## Upstream Integration

### Plaid API Integration

The TD Enrich Service consumes the **Plaid Enrichment API** to retrieve merchant data. This section describes the request/response mapping, authentication, rate limits, and resilience patterns.

### Upstream Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/transactions/enrich` | `POST` | Enrich single or batch transactions with merchant metadata |
| `/webhooks/{webhookId}` | `POST` | Async result callback (optional; not used currently) |

### Request/Response Mapping

**Service Request:**
```json
{
  "accountId": "acc_customer_001",
  "transactions": [
    {
      "description": "STARBUCKS #1234",
      "amount": 5.75,
      "date": "2026-04-07",
      "merchantName": "Starbucks"
    }
  ]
}
```

**Transformed Plaid Request:**
```json
{
  "client_id": "PLAID_CLIENT_ID",
  "secret": "PLAID_SECRET_KEY",
  "transactions": [
    {
      "description": "STARBUCKS #1234",
      "amount": 5.75,
      "date": "2026-04-07"
    }
  ]
}
```

**Plaid Response:**
```json
{
  "enriched_transactions": [
    {
      "description": "STARBUCKS #1234",
      "enriched_transactions": [
        {
          "transaction_id": "txn_001",
          "merchant_name": "Starbucks Coffee",
          "logo_url": "https://logo.clearbit.com/starbucks.com",
          "category": "Coffee Shops",
          "merchant_category_code": "5461",
          "enrichment_metadata": {
            "location": "Seattle, WA",
            "confidence": 0.99
          }
        }
      ]
    }
  ],
  "request_id": "plaid_req_12345"
}
```

**Service Response (as seen by client):**
```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440001",
  "enrichedTransactions": [
    {
      "transactionId": "txn_001",
      "merchantId": "merchant-starbucks-seattle",
      "category": "Coffee Shops",
      "merchantName": "Starbucks Coffee",
      "logoUrl": "https://logo.clearbit.com/starbucks.com",
      "metadata": {
        "confidence": 0.99,
        "enrichmentMetadata": {
          "location": "Seattle, WA",
          "category_code": "5461"
        }
      }
    }
  ],
  "processedAt": "2026-04-07T15:30:15.123Z",
  "status": "SUCCESS",
  "errorMessage": null
}
```

### Sequence Diagram

```
Client                    TD Enrich Service         Plaid API        Cache
  |                              |                     |              |
  |-- POST /single ------------>|                     |              |
  |                              |-- Check Cache ---------->         |
  |                              |<-- Cache Hit (optional) ---------|
  |                              | (if found, skip to response)      |
  |                              |                                   |
  |                              |-- Auth (Bearer Token) ---------->|
  |                              |-- Validate Input ------>|         |
  |                              |                         |         |
  |                              |-- POST /enrich -------->|         |
  |                              |<-- Merchant Data -------|         |
  |                              |                                   |
  |                              |-- Transform & Store Cache ------->
  |                              |                                   |
  |<-- 200 OK + Enriched Data ---|                                   |
  |                              |                                   |
  
Batch Flow:
  |                              |                     |              |
  |-- POST /batch ----------->  |                     |              |
  |<-- 202 Accepted (GUIDs) ---|                     |              |
  |                              | [Queue Processing in Background]  |
  |                              |                     |              |
  |                              |-- Dequeue Items ------>|          |
  |                              |-- Enrich in Parallel ->|          |
  |                              |<-- Results -----------|          |
  |                              |-- Store in DB ------->|          |
  |                              |                                   |
  |-- GET /{guid} ----------->  | [Poll Until Ready]    |          |
  |<-- 202 / 200 + Results --|                     |              |
```

### Authentication to Upstream API

**Mechanism:** API Key in request body (Plaid requirement, not OAuth)

**Storage:** Environment variables `PLAID_CLIENT_ID` and `PLAID_API_KEY` (secure vault)

**Request Header (Service to Plaid):**
```
POST https://api.plaid.com/transactions/enrich HTTP/1.1
Content-Type: application/json
User-Agent: td-enrich-service/1.0.0

{
  "client_id": "{{PLAID_CLIENT_ID}}",
  "secret": "{{PLAID_API_KEY}}",
  "transactions": [...]
}
```

### Rate Limits

| Limit | Value | Handling |
|-------|-------|----------|
| Requests per minute | 1000 req/min | Queue + adaptive backoff per Plaid headers |
| Requests per day | 1M req/day | Service-enforced quota tracking |
| Concurrent connections | 10 | Bulkhead isolates Plaid calls |

**Response Headers from Plaid:**
```
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 987
X-RateLimit-Reset: 1649268614
```

**Service Handling:**
- Resilience4j **Retry** (3 attempts, exponential backoff with jitter)
- Resilience4j **CircuitBreaker** (50% failure threshold, 10s cool-down)
- Resilience4j **Bulkhead** (max 10 concurrent calls)
- Resilience4j **Timeout** (10s per call)

### Retry Policy

```
Attempt 1: immediate
Attempt 2: wait 1s + jitter (0-500ms)
Attempt 3: wait 2s + jitter (0-500ms)
Attempt 4: FAIL (circuit breaker open)
```

**Idempotency:** Service is idempotent at the cache level. Same merchant description + date always returns same `merchantId`.

---

## Data Models and Schema

### Domain Models

#### 1. EnrichmentRequest

```json
{
  "accountId": "string (1-255 chars)",
  "transactions": [
    {
      "description": "string (1-255 chars, required)",
      "amount": "decimal (0 to 999999.99, required)",
      "date": "string (YYYY-MM-DD, required)",
      "merchantName": "string (optional)"
    }
  ]
}
```

**Validation Rules:**
- `accountId`: Non-null, non-blank
- `transactions`: 1-200 items per request
- `amount`: Positive, max 6 digits before decimal, 2 after
- `date`: Valid past or current date, not future
- `description`: Non-blank, stripped of leading/trailing whitespace

---

#### 2. EnrichedTransaction

```json
{
  "transactionId": "string",
  "merchantId": "uuid",
  "category": "string",
  "merchantName": "string",
  "logoUrl": "uri (optional)",
  "metadata": {
    "confidence": 0.0-1.0,
    "enrichmentMetadata": {
      "location": "string (optional)",
      "category_code": "string (optional)",
      "mcc": "string (optional)"
    }
  }
}
```

---

#### 3. MerchantCacheEntity (Persistent)

```java
@Entity
@Table(name = "merchant_cache")
public class MerchantCacheEntity {
  @Id
  @Column(name = "merchant_id")
  private String merchantId;  // UUID

  @Column(name = "description", nullable = false)
  private String description;

  @Column(name = "merchant_name", nullable = false)
  private String merchantName;

  @Column(name = "category")
  private String category;

  @Column(name = "logo_url")
  private String logoUrl;

  @Column(name = "plaid_response", columnDefinition = "CLOB")
  private String plaidResponse;  // JSON

  @Column(name = "status")
  private String status;  // PENDING, ENRICHED

  @Column(name = "created_at", nullable = false)
  @CreationTimestamp
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  @UpdateTimestamp
  private OffsetDateTime updatedAt;
}
```

---

### Database Schema

#### H2 (Development)

```sql
CREATE TABLE IF NOT EXISTS merchant_cache (
    merchant_id VARCHAR(36) PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    merchant_name VARCHAR(255) NOT NULL,
    category VARCHAR(100),
    logo_url VARCHAR(2048),
    plaid_response CLOB,
    status VARCHAR(20) DEFAULT 'ENRICHED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    INDEX idx_description (description),
    INDEX idx_status (status)
);

CREATE TABLE IF NOT EXISTS enrichment_request_audit (
    request_id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(255) NOT NULL,
    transaction_count INT NOT NULL,
    status VARCHAR(20) DEFAULT 'PROCESSING',
    response_json CLOB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    INDEX idx_account_id (account_id),
    INDEX idx_status (status)
);
```

#### Azure SQL (Production)

```sql
CREATE TABLE dbo.merchant_cache (
    merchant_id NVARCHAR(36) PRIMARY KEY NONCLUSTERED,
    description NVARCHAR(255) NOT NULL,
    merchant_name NVARCHAR(255) NOT NULL,
    category NVARCHAR(100),
    logo_url NVARCHAR(2048),
    plaid_response NVARCHAR(MAX),
    status NVARCHAR(20) DEFAULT 'ENRICHED' NOT NULL,
    created_at DATETIME2 NOT NULL DEFAULT GETUTCDATE(),
    updated_at DATETIME2,
    CLUSTERED COLUMNSTORE INDEX CCI_merchant_cache
);

CREATE NONCLUSTERED INDEX idx_description ON dbo.merchant_cache(description)
    INCLUDE (merchant_name, category, logo_url);

CREATE NONCLUSTERED INDEX idx_status ON dbo.merchant_cache(status)
    INCLUDE (created_at);
```

#### Migration Example (Flyway)

```sql
-- V1__initial_schema.sql
CREATE TABLE merchant_cache (
    merchant_id VARCHAR(36) PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    merchant_name VARCHAR(255) NOT NULL,
    category VARCHAR(100),
    logo_url VARCHAR(2048),
    plaid_response CLOB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_description (description)
);

-- V2__merchant_cache_status.sql
ALTER TABLE merchant_cache ADD COLUMN status VARCHAR(20) DEFAULT 'ENRICHED';
ALTER TABLE merchant_cache ADD INDEX idx_status (status);

-- V3__audit_table.sql
CREATE TABLE enrichment_request_audit (
    request_id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(255) NOT NULL,
    transaction_count INT NOT NULL,
    status VARCHAR(20) DEFAULT 'PROCESSING',
    response_json CLOB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    INDEX idx_account_id (account_id)
);
```

---

### Sample Objects

**Starbucks (Cached):**
```json
{
  "merchantId": "merchant-1234-starbucks-seattle",
  "description": "STARBUCKS COFFEE #1234 SEATTLE WA",
  "merchantName": "Starbucks Coffee",
  "category": "Coffee Shops",
  "logoUrl": "https://logo.clearbit.com/starbucks.com",
  "plaidResponse": {
    "merchant_name": "Starbucks Coffee",
    "logo_url": "https://logo.clearbit.com/starbucks.com",
    "category": "Coffee Shops",
    "merchant_category_code": "5461",
    "confidence": 0.99,
    "enrichment_metadata": {"location": "Seattle, WA"}
  },
  "status": "ENRICHED",
  "createdAt": "2026-04-01T10:00:00Z",
  "updatedAt": "2026-04-07T15:30:00Z"
}
```

**Amazon (Cached):**
```json
{
  "merchantId": "merchant-5678-amazon-online",
  "description": "AMAZON.COM #6789 CUSTOMER",
  "merchantName": "Amazon.com, Inc.",
  "category": "Internet Services",
  "logoUrl": "https://logo.clearbit.com/amazon.com",
  "plaidResponse": {
    "merchant_name": "Amazon.com, Inc.",
    "logo_url": "https://logo.clearbit.com/amazon.com",
    "category": "Internet Services",
    "merchant_category_code": "5969",
    "confidence": 0.98
  },
  "status": "ENRICHED",
  "createdAt": "2026-03-15T14:22:00Z",
  "updatedAt": "2026-04-06T09:15:00Z"
}
```

---

## Authentication & Authorization

### OAuth 2.0 / JWT Flow

**Issuer:** Authorization Server at `https://auth.company.com`

**Token Endpoint:**
```
POST https://auth.company.com/oauth/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&client_id=XXXX&client_secret=YYYY&scope=enrichment:write
```

**JWT Token Structure (decoded):**
```json
{
  "iss": "https://auth.company.com",
  "sub": "client-id-xyz",
  "aud": ["https://api.company.com/enrich"],
  "scope": ["enrichment:read", "enrichment:write"],
  "iat": 1649268000,
  "exp": 1649271600,
  "claims": {
    "accountId": "acc_customer_001",
    "tier": "premium"
  }
}
```

**Token Validation (Service):**
1. Extract `Authorization: Bearer <token>` header
2. Verify signature using issuer's public key (cached, refreshed daily)
3. Check `exp` (expiration)
4. Validate `aud` (audience) matches service
5. Check scope contains `enrichment:read` or `enrichment:write`
6. Extract `sub` (client ID) for audit logging

**Scopes:**

| Scope | Allows |
|-------|--------|
| `enrichment:read` | GET /{guid} (poll results) |
| `enrichment:write` | POST /single, POST /batch (submit for enrichment) |
| `enrichment:admin` | Bypass rate limits, access admin endpoints |

**Bearer Token in Request:**
```bash
curl -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  https://api.company.com/enrich/v1/single
```

### Service-to-Service Authentication (Upstream: Plaid)

**Mechanism:** API Key (Plaid doesn't support OAuth)

**Environment Variables (Vault Storage):**
```
PLAID_CLIENT_ID = "client_xyzabc"
PLAID_API_KEY = "secret_longstring_encrypted"
```

**Request to Plaid:**
```json
{
  "client_id": "client_xyzabc",
  "secret": "secret_longstring_encrypted",
  "transactions": [...]
}
```

**Key Rotation:**
1. New key deployed to vault
2. Service reads from vault on reload (Spring Cloud Config refresh)
3. Subsequent Plaid calls use new key
4. Old key revoked in Plaid console after verification window

---

## Error Handling and Retries

### Error Classification

#### Client Errors (4xx)

| Code | Error | Reason | Action |
|------|-------|--------|--------|
| 400 | `INVALID_REQUEST` | Malformed input, validation failure | Client must fix request |
| 401 | `UNAUTHORIZED` | Missing/invalid JWT token | Obtain new token from auth server |
| 403 | `FORBIDDEN` | Token valid but insufficient scopes | Request new token with correct scopes |
| 429 | `RATE_LIMITED` | Quota exceeded (100/min per account) | Implement exponential backoff; retry after `Retry-After` header |

#### Server Errors (5xx)

| Code | Error | Reason | Action |
|------|-------|--------|--------|
| 500 | `INTERNAL_ERROR` | Unexpected service error (bug, db connection lost) | Retry with backoff; escalate to on-call |
| 503 | `SERVICE_UNAVAILABLE` | Circuit breaker open (Plaid repeatedly failing) or database down | Retry after cool-down; check upstream status |

### Standard Error JSON Schema

```json
{
  "error": "ERROR_CODE",
  "message": "Human-readable error message",
  "statusCode": 400,
  "timestamp": "2026-04-07T15:30:00Z",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

**Example:**
```json
{
  "error": "RATE_LIMITED",
  "message": "Rate limit exceeded: 100 requests per minute per account ID.",
  "statusCode": 429,
  "timestamp": "2026-04-07T15:30:00Z",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

### Retry Policy

**For Client Errors (4xx):** No retry (unless explicitly indicated by `Retry-After` header)

**For Server/Upstream Errors (5xx):**

```
Initial Backoff: 100 ms
Max Backoff: 5000 ms
Multiplier: 2.0 (exponential)
Jitter: ±20% random

Attempt 1: 0ms           -> 100ms wait
Attempt 2: 100-120ms     -> 200-240ms actual
Attempt 3: 200-240ms     -> 400-480ms actual
(Max 3 retries; give up after ~700ms total)
```

**Pseudocode (Resilience4j-based):**

```java
// Retry policy
RetryConfig retryConfig = RetryConfig.custom()
    .maxAttempts(3)
    .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(100, 2, 0.2))
    .retryOnException(e -> e instanceof IOException || e instanceof TimeoutException)
    .ignoreExceptions(ValidationException.class)
    .build();

Retry retry = Retry.of("plaidApi", retryConfig);

// Circuit breaker policy
CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
    .failureRateThreshold(50.0)
    .slowCallRateThreshold(50.0)
    .slowCallDurationThreshold(java.time.Duration.ofSeconds(10))
    .waitDurationInOpenState(java.time.Duration.ofSeconds(10))
    .permittedNumberOfCallsInHalfOpenState(3)
    .minimumNumberOfCalls(5)
    .build();

CircuitBreaker cb = CircuitBreaker.of("plaidApi", cbConfig);

// Retry + Circuit Breaker
Retry decorate(Supplier<EnrichmentResponse> fn) {
    return Retry.decorateSupplier(retry, 
           () -> CircuitBreaker.decorateSupplier(cb, fn).get());
}
```

### Idempotency

**Strategy:** Database constraints + cache lookup

1. **Idempotency Key (optional client header):** `Idempotency-Key: <uuid>`
2. **Server checks:** For same `accountId` + same `transaction.description` + same `transaction.date`, return cached `merchantId`
3. **Database unique constraint:** `UNIQUE(accountId, description, date)` on audit table

**Example:**
```bash
# Request 1
curl -X POST /single \
  -H "Idempotency-Key: req-123" \
  -d '{"accountId":"acc_1", "transactions":[{"description":"COFFEE","amount":5,"date":"2026-04-07"}]}'

# Response 1: 200 OK with merchantId = "merchant-abc"

# Request 2 (identical, with same Idempotency-Key)
curl -X POST /single \
  -H "Idempotency-Key: req-123" \
  -d '{"accountId":"acc_1", "transactions":[{"description":"COFFEE","amount":5,"date":"2026-04-07"}]}'

# Response 2: 200 OK with same merchantId = "merchant-abc" (from cache, no upstream call)
```

---

## Observability

### Structured Logging

**Log Format:** JSON (single-line per event)

**Schema:**
```json
{
  "timestamp": "2026-04-07T15:30:15.123456Z",
  "level": "INFO",
  "logger": "com.td.enrich.service.EnrichmentService",
  "thread": "virtual-thread-12345",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "d9014888-7c73-4dd5-ae44-62c3130577db",
  "message": "Enrichment completed successfully",
  "accountId": "acc_customer_001",
  "transactionCount": 1,
  "cacheHit": true,
  "responseTimeMs": 45,
  "plaidLatencyMs": 0,
  "merchantId": "merchant-starbucks-seattle",
  "errorMessage": null
}
```

**Log Levels:**

| Level | When to Use | Example |
|-------|-----------|---------|
| DEBUG | Detailed diagnostic info | Cache lookup, input validation |
| INFO | Significant events | Enrichment started, completed, cache hit/miss |
| WARN | Recoverable issues | Circuit breaker transitioning, retry attempt |
| ERROR | Errors affecting client | Request validation failed, upstream timeout |
| FATAL | Service-wide failures | Database connection lost, fatal OOM |

**Sample Log Entries:**

```json
{ "level": "INFO", "message": "Enrichment request started", "accountId": "acc_1", "transactionCount": 1 }
{ "level": "DEBUG", "message": "Cache lookup for STARBUCKS #1234", "description": "STARBUCKS #1234", "hit": false }
{ "level": "INFO", "message": "Calling Plaid API", "endpoint": "/transactions/enrich", "retryAttempt": 1 }
{ "level": "WARN", "message": "Plaid API timeout, retrying", "retryAttempt": 2, "backoffMs": 200 }
{ "level": "INFO", "message": "Enrichment completed", "status": "SUCCESS", "responseTimeMs": 500, "cacheHit": false }
{ "level": "ERROR", "message": "Invalid request: amount exceeds max", "statusCode": 400, "traceId": "..." }
```

---

### Metrics (Prometheus)

**Metric Names and Labels:**

```
# Request volume
enrich_requests_total{method="POST", endpoint="/single", status="200"} 15234
enrich_requests_total{method="POST", endpoint="/single", status="400"} 342
enrich_requests_total{method="POST", endpoint="/batch", status="202"} 8901

# Request latency (histograms)
enrich_request_duration_seconds_bucket{endpoint="/single", le="0.01"} 5000
enrich_request_duration_seconds_bucket{endpoint="/single", le="0.05"} 12000
enrich_request_duration_seconds_bucket{endpoint="/single", le="0.1"} 14500
enrich_request_duration_seconds_bucket{endpoint="/single", le="+Inf"} 15234
enrich_request_duration_seconds_count{endpoint="/single"} 15234
enrich_request_duration_seconds_sum{endpoint="/single"} 450.234

# Upstream (Plaid) latency
enrich_plaid_latency_seconds_bucket{operation="enrich", le="0.5"} 14000
enrich_plaid_latency_seconds_bucket{operation="enrich", le="1.0"} 14900
enrich_plaid_latency_seconds_bucket{operation="enrich", le="+Inf"} 14950

# Error rates
enrich_errors_total{type="validation", status_code="400"} 342
enrich_errors_total{type="upstream", status_code="503"} 25
enrich_errors_total{type="timeout", status_code="504"} 12

# Cache metrics
enrich_cache_operations_total{operation="hit"} 8234
enrich_cache_operations_total{operation="miss"} 2134
enrich_cache_size_bytes{bucket="merchant_cache"} 52428800
enrich_cache_evictions_total{reason="ttl_expired"} 156

# Circuit breaker status
enrich_circuit_breaker_state{name="plaidApi", state="CLOSED"} 1
enrich_circuit_breaker_calls_total{name="plaidApi", result="success"} 9850
enrich_circuit_breaker_calls_total{name="plaidApi", result="failure"} 150
enrich_circuit_breaker_calls_total{name="plaidApi", result="slow_call"} 45

# Retry metrics
enrich_retries_total{endpoint="/plaid", attempt=1} 9850
enrich_retries_total{endpoint="/plaid", attempt=2} 145
enrich_retries_total{endpoint="/plaid", attempt=3} 8
```

---

### Tracing (OpenTelemetry)

**Span Hierarchy:**

```
Root Span: "POST /single"
├── Span: "ValidateRequest"
├── Span: "CheckCache" (duration: 1ms)
├── Span: "EnrichTransaction"
│   ├── Span: "CallPlaidAPI" (duration: 234ms)
│   └── Span: "ParseResponse"
├── Span: "SaveCache" (duration: 5ms)
└── Span: "SerializeResponse"
```

**Span Attributes:**
```
POST /single
  trace_id: 4bf92f3577b34da6a3ce929d0e0e4736
  span_id: d9014888-7c73-4dd5-ae44-62c3130577db
  parent_span_id: (root)
  status: OK
  duration_ms: 245
  accountId: acc_customer_001
  transactionCount: 1
  
  CallPlaidAPI
    trace_id: 4bf92f3577b34da6a3ce929d0e0e4736
    span_id: a1b2c3d4e5f6g7h8
    parent_span_id: d9014888-7c73-4dd5-ae44-62c3130577db
    status: OK
    duration_ms: 234
    plaid.endpoint: /transactions/enrich
    plaid.status_code: 200
    http.status_code: 200
```

**Instrumentation (Spring Boot):**
```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: prometheus, health, trace
  metrics:
    export:
      otlp:
        enabled: true
        endpoint: http://otel-collector:4317
  tracing:
    sampling:
      probability: 0.1  # 10% of requests
```

---

### Dashboards & Alerting

**Prometheus Alert Rules:**

```yaml
groups:
  - name: enrich_service
    interval: 30s
    rules:
      # High error rate
      - alert: HighErrorRate
        expr: |
          (
            sum(rate(enrich_requests_total{status=~"5.."}[5m]))
            /
            sum(rate(enrich_requests_total[5m]))
          ) > 0.05
        for: 5m
        annotations:
          summary: "Error rate exceeded 5% for 5 minutes"
          severity: critical

      # Circuit breaker open
      - alert: CircuitBreakerOpen
        expr: enrich_circuit_breaker_state{state="OPEN"} == 1
        for: 1m
        annotations:
          summary: "Plaid API circuit breaker is OPEN"
          severity: critical

      # High latency
      - alert: HighLatency
        expr: |
          histogram_quantile(0.95, enrich_request_duration_seconds{endpoint="/single"}) > 2.0
        for: 5m
        annotations:
          summary: "P95 latency exceeded 2 seconds"
          severity: warning

      # Cache eviction spike
      - alert: HighCacheEvictionRate
        expr: |
          rate(enrich_cache_evictions_total[5m]) > 10
        for: 5m
        annotations:
          summary: "Cache evictions exceed 10/sec"
          severity: warning

      # Pod restart loop
      - alert: PodRestartingFrequently
        expr: |
          rate(kube_pod_container_status_restarts_total{pod=~"enrich-.*"}[1h]) > 0.5
        for: 5m
        annotations:
          summary: "Pod restarting more than 1x per 2 hours"
          severity: critical
```

---

## Security

### Input Validation

**All inputs validated server-side:**

| Field | Validation | Example |
|-------|-----------|---------|
| `accountId` | 1-255 chars, alphanumeric + hyphen | `acc_12345`, `cust-xyz` |
| `description` | 1-255 chars, stripped whitespace | `STARBUCKS #1234` |
| `amount` | Positive decimal, max 999999.99 | `5.75`, `9999.99` |
| `date` | YYYY-MM-DD, not future | `2026-04-07` |
| `merchantName` | 1-255 chars, optional | `Starbucks` |

**Implementation (Java):**
```java
@Validated
@RestController
public class EnrichmentController {
    
    @PostMapping("/single")
    public ResponseEntity<EnrichmentResponse> enrichSingle(
        @Valid @RequestBody EnrichmentRequest request) {
        // @Valid triggers bean validation
        // javax.validation annotations on request POJO
    }
}

@Data
public class EnrichmentRequest {
    @NotBlank(message = "accountId is required")
    @Size(min = 1, max = 255)
    private String accountId;

    @NotEmpty(message = "transactions are required")
    @Size(min = 1, max = 200)
    private List<@Valid Transaction> transactions;
}

@Data
public class Transaction {
    @NotBlank
    @Size(min = 1, max = 255)
    private String description;

    @NotNull
    @DecimalMin("0")
    @DecimalMax("999999.99")
    private BigDecimal amount;

    @NotNull
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}")
    private LocalDate date;

    @Size(min = 1, max = 255)
    private String merchantName;
}
```

---

### Rate Limiting

**Limit:** 100 requests per minute per `accountId` (OAuth `sub` claim)

**Implementation (Spring Cloud Gateway / Resilience4j):**
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: enrich
          uri: lb://enrich-service
          predicates:
            - Path=/api/v1/enrich/**
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter:
                  replenishRate: 100
                  burstCapacity: 150
                key-resolver: "#{@accountIdKeyResolver}"
```

**Response When Limited (429):**
```json
{
  "error": "RATE_LIMITED",
  "message": "Rate limit exceeded: 100 requests per minute",
  "statusCode": 429,
  "timestamp": "2026-04-07T15:30:00Z",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "retryAfter": 35
}
```

---

### Secrets Management

**Storage Locations:**

| Secret | Storage | Rotation | Notes |
|--------|---------|----------|-------|
| `PLAID_CLIENT_ID` | HashiCorp Vault | Annually | Accessible to service account only |
| `PLAID_API_KEY` | HashiCorp Vault | Every 90 days | rotationSchedule in Vault |
| `DB_PASSWORD` | HashiCorp Vault | Every 180 days | Managed by DBA team |
| `JWT_PUBLIC_KEY` | ConfigMap (non-secret) | Annually | Downloaded periodically from auth server |
| `ENCRYPTION_KEY` | Vault | On-demand | Used for database field encryption |

**Vault Integration (Spring Cloud Config):**
```yaml
# bootstrap.yml
spring:
  cloud:
    config:
      server:
        vault:
          host: vault.internal.company.com
          port: 8200
          scheme: https
          authentication: KUBERNETES
          kubernetes-path: kubernetes_staging
          kubernetes-role: enrich-service-role
```

**Key Rotation Procedure (Plaid API Key):**
1. Generate new key in Plaid dashboard
2. Add new key to Vault: `secret/data/enrich/plaid/api-keys`
3. Service reloads config (via Spring Cloud Config refresh)
4. All subsequent requests use new key
5. After 24-hour transition period, revoke old key in Plaid dashboard
6. Confirm no failed requests with old key in logs

---

### Dependency Security Scanning

**Build-time Scanning (Maven):**
```bash
./mvnw org.owasp:dependency-check-maven:check
```

**Runtime Scanning (Trivy container image):**
```bash
trivy image --severity HIGH,CRITICAL gcr.io/company/enrich-service:latest
```

**Process:**
1. Weekly scans triggered by CI/CD pipeline
2. HIGH/CRITICAL vulnerabilities block deployment
3. Vulnerability report sent to security team
4. Dependency updates deployed within SLA (24h for CRITICAL, 7d for HIGH)

---

### Threat Model & Mitigations

| # | Threat | Risk | Mitigation |
|---|--------|------|-----------|
| 1 | **Unauthorized API Access** | Attacker enriches transactions without payment | JWT signatures validated; Bearer token required; scopes enforced |
| 2 | **Man-in-the-Middle (Plaid)** | Attacker intercepts Plaid API calls | TLS 1.3+ required; certificate pinning; service-to-service mTLS |
| 3 | **Cache Poisoning** | Malicious merchant data stored persistently | Input validation on all cache writes; Plaid response signature verification |
| 4 | **Rate Limit Bypass** | Attacker floods service with requests | Per-account rate limiting; distributed rate limiting via Redis; WAF rules |
| 5 | **SQL Injection (Database)** | Attacker modifies merchant cache via injection | Parameterized queries (JPA); prepared statements; no dynamic SQL |

---

## Scalability and Deployment

### Resource Sizing Guidance

**CPU & Memory Recommendations:**

| Scenario | Replicas | CPU/Pod | Memory/Pod | Notes |
|----------|----------|---------|-----------|-------|
| Dev/Staging | 1-2 | 500m | 512Mi | Single pod acceptable |
| Production (5K req/min) | 3 | 2 | 1Gi | Baseline production |
| Production (50K req/min) | 10 | 4 | 2Gi | Peak load; virtual threads |
| Production (100K+ req/min) | 20+ | 8 | 4Gi | Multiple zones; auto-scaling |

**Concurrency Model:**
- **Request threads:** Project Loom virtual threads (99,999 concurrent requests)
- **Database pool:** HikariCP, 20 connections per pod
- **HTTP client threads:** 10 (configured via Resilience4j Bulkhead)

**Caching:**
- **In-memory (local):** LinkedHashMap, LRU eviction, 10,000 entries/pod (~50 MB)
- **Distributed (optional):** Redis for multi-pod consistency (cluster mode)

---

### Horizontal Scaling

**Auto-Scaling Policy (Kubernetes HPA):**

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: enrich-service-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: enrich-service
  minReplicas: 3
  maxReplicas: 20
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
        - type: Percent
          value: 50
          periodSeconds: 60
    scaleUp:
      stabilizationWindowSeconds: 60
      policies:
        - type: Percent
          value: 100
          periodSeconds: 30
```

**Load-Balancing:** Round-robin (Kubernetes Service dns-based)

---

### Container & Kubernetes Deployment

**Dockerfile:**

```dockerfile
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy built JAR from Maven
COPY target/enrich-service-1.0.0.jar app.jar

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD java -jar /app/app.jar --actuator.health

# Metadata
LABEL org.opencontainers.image.title="TD Enrich Service"
LABEL org.opencontainers.image.version="1.0.0"
LABEL org.opencontainers.image.source="https://github.com/company/enrich-service"

# Non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-jar", "app.jar"]
```

**Kubernetes Deployment:**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: enrich-service
  namespace: production
  labels:
    app: enrich-service
    version: v1
spec:
  replicas: 3
  selector:
    matchLabels:
      app: enrich-service
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  template:
    metadata:
      labels:
        app: enrich-service
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      serviceAccountName: enrich-service
      securityContext:
        runAsNonRoot: true
        fsReadOnlyRootFilesystem: true
      containers:
        - name: enrich-service
          image: gcr.io/company/enrich-service:1.0.0
          imagePullPolicy: IfNotPresent
          ports:
            - name: http
              containerPort: 8080
              protocol: TCP
          env:
            - name: SERVICE_PORT
              value: "8080"
            - name: CACHE_MAX_SIZE
              value: "10000"
            - name: CACHE_TTL_MINUTES
              value: "1440"
            - name: OTEL_EXPORTER_OTLP_ENDPOINT
              value: "http://otel-collector.monitoring:4317"
            - name: PLAID_CLIENT_ID
              valueFrom:
                secretKeyRef:
                  name: plaid-credentials
                  key: client-id
            - name: PLAID_API_KEY
              valueFrom:
                secretKeyRef:
                  name: plaid-credentials
                  key: api-key
            - name: DB_URL
              valueFrom:
                configMapKeyRef:
                  name: database-config
                  key: jdbc-url
            - name: DB_USER
              valueFrom:
                secretKeyRef:
                  name: database-credentials
                  key: username
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: database-credentials
                  key: password
            - name: SPRING_PROFILES_ACTIVE
              value: "production"
          resources:
            requests:
              cpu: 2
              memory: 1Gi
            limits:
              cpu: 4
              memory: 2Gi
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
            timeoutSeconds: 3
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 5
            timeoutSeconds: 3
            failureThreshold: 2
          volumeMounts:
            - name: tmp
              mountPath: /tmp
            - name: cache
              mountPath: /app/cache
      volumes:
        - name: tmp
          emptyDir:
            sizeLimit: 1Gi
        - name: cache
          emptyDir:
            sizeLimit: 500Mi
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector:
                  matchExpressions:
                    - key: app
                      operator: In
                      values:
                        - enrich-service
                topologyKey: kubernetes.io/hostname
---
apiVersion: v1
kind: Service
metadata:
  name: enrich-service
  namespace: production
  labels:
    app: enrich-service
spec:
  type: ClusterIP
  selector:
    app: enrich-service
  ports:
    - name: http
      port: 80
      targetPort: 8080
      protocol: TCP
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: enrich-service-hpa
  namespace: production
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: enrich-service
  minReplicas: 3
  maxReplicas: 20
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
```

---

### CI/CD Pipeline

**GitHub Actions Workflow:**

```yaml
name: Build, Test & Deploy

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up JDK 21
        uses: actions/setup-java@v3
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven
      
      - name: Run Tests
        run: ./mvnw clean test
      
      - name: Run SonarQube Analysis
        run: ./mvnw sonar:sonar -Dsonar.projectKey=enrich-service
      
      - name: Security Scan (OWASP Dependency Check)
        run: ./mvnw org.owasp:dependency-check-maven:check
      
      - name: Build JAR
        run: ./mvnw clean package -DskipTests
      
      - name: Build Docker Image
        run: |
          docker build -t gcr.io/company/enrich-service:${{ github.sha }} .
          docker tag gcr.io/company/enrich-service:${{ github.sha }} gcr.io/company/enrich-service:latest
      
      - name: Scan Image (Trivy)
        run: trivy image --severity HIGH,CRITICAL gcr.io/company/enrich-service:${{ github.sha }}
      
      - name: Push Image  (if main branch)
        if: github.ref == 'refs/heads/main'
        run: |
          echo ${{ secrets.GCP_SA_KEY }} | docker login -u _json_key --password-stdin https://gcr.io
          docker push gcr.io/company/enrich-service:${{ github.sha }}
          docker push gcr.io/company/enrich-service:latest

  deploy:
    runs-on: ubuntu-latest
    needs: build
    if: github.ref == 'refs/heads/main' && github.event_name == 'push'
    steps:
      - uses: actions/checkout@v3
      
      - name: Canary Deploy (5% traffic)
        run: |
          kubectl set image deployment/enrich-service-canary \
            enrich-service=gcr.io/company/enrich-service:${{ github.sha }} \
            -n production
      
      - name: Wait & Verify (5 minutes)
        run: sleep 300 && ./scripts/verify-canary.sh
      
      - name: Full Rollout (100% traffic)
        run: |
          kubectl set image deployment/enrich-service \
            enrich-service=gcr.io/company/enrich-service:${{ github.sha }} \
            -n production
      
      - name: Smoke Tests
        run: ./scripts/smoke-tests.sh
      
      - name: On Failure: Rollback
        if: failure()
        run: |
          kubectl set image deployment/enrich-service \
            enrich-service=gcr.io/company/enrich-service:$(git describe --tags --abbrev=0) \
            -n production
```

---

## Testing

### Test Strategy

**Test Pyramid:**
- **Unit Tests (55%):** Domain logic, validation, caching, queue processor
- **Integration Tests (20%):** Database, Plaid API (mocked via WireMock)
- **Chaos Tests (15%):** Resilience patterns — circuit breaker states, bulkhead saturation, retry correctness, queue overflow, DB failure isolation
- **Contract Tests (5%):** Client expectations (Pact)
- **E2E Tests (5%):** Full request flow in staging environment

---

### Unit Tests

**Example: MerchantTestDataGenerator**

```java
@DisplayName("MerchantTestDataGenerator")
public class MerchantTestDataGeneratorTest {

    @Test
    @DisplayName("should generate 200 test cases")
    void shouldGenerate200TestCases() {
        List<EnrichmentRequest> cases = MerchantTestDataGenerator.generate200TestCases();
        assertThat(cases).hasSize(200);
    }

    @Test
    @DisplayName("should generate valid transaction amounts within merchant range")
    void shouldGenerateValidAmounts() {
        List<EnrichmentRequest> cases = MerchantTestDataGenerator.generateScenario("CACHE_HEAVY");
        cases.forEach(req -> {
            req.transactions().forEach(txn -> {
                assertThat(txn.amount())
                    .isGreaterThan(BigDecimal.ZERO)
                    .isLessThanOrEqualTo(new BigDecimal("999999.99"));
            });
        });
    }
}
```

---

### Integration Tests

**Example: EnrichmentServiceTest (WireMock)**

```java
@SpringBootTest
@AutoConfigureMockMvc
public class EnrichmentServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EnrichmentRepository repo;

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
        .options(new WireMockConfiguration()
            .port(8081)
            .globalTemplating(true))
        .build();

    @BeforeEach
    void setupMocks() {
        wireMock.stubFor(post("/transactions/enrich")
            .willReturn(aResponse()
                .withStatus(200)
                .withJsonBody(MockPlaidResponseGenerator.generateMockResponse(
                    "Starbucks Coffee", 70))));
    }

    @Test
    @DisplayName("should enrich single transaction with Plaid data")
    void shouldEnrichSingleTransaction() throws Exception {
        String requestBody = """
            {
              "accountId": "acc_test_001",
              "transactions": [
                {
                  "description": "STARBUCKS COFFEE #1234",
                  "amount": 5.75,
                  "date": "2026-04-07",
                  "merchantName": "Starbucks"
                }
              ]
            }
            """;

        mockMvc.perform(post("/api/v1/enrich/single")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody)
                .header("Authorization", "Bearer " + generateTestToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("SUCCESS")))
            .andExpect(jsonPath("$.enrichedTransactions[0].merchantName", is("Starbucks Coffee")))
            .andExpect(jsonPath("$.enrichedTransactions[0].category", is("Coffee Shops")));
    }

    @Test
    @DisplayName("should return 400 for invalid request")
    void shouldReturnBadRequestForInvalidInput() throws Exception {
        String requestBody = """
            {
              "accountId": "",
              "transactions": []
            }
            """;

        mockMvc.perform(post("/api/v1/enrich/single")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody)
                .header("Authorization", "Bearer " + generateTestToken()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", is("INVALID_REQUEST")));
    }
}
```

---

### Contract Tests (Pact)

**Pact Specification:**

```groovy
@ExtendWith(PactProviderTestExt.class)
@PactDirectory("src/test/resources/pacts")
@Provider("enrich_service")
public class EnrichmentServiceContractTest {

    @RestTemplate
    RestTemplate restTemplate = new RestTemplate();

    @PactTestFor(providerName = "enrich_service", port = "8080")
    @Test
    void testEnrichSingleEndpoint(MockServerConfig config) {
        // Contract: Client expects /single to return 200 with enrichedTransactions
        PactDslJsonBody body = new PactDslJsonBody()
            .stringType("requestId", UUID.randomUUID().toString())
            .array("enrichedTransactions")
                .object()
                    .stringType("transactionId", "txn_001")
                    .stringType("merchantId", "merchant-123")
                    .stringType("merchantName", "Starbucks Coffee")
                .closeObject()
            .closeArray()
            .stringType("status", "SUCCESS");

        config.mockServerConfig()
            .expectPost("/api/v1/enrich/single")
            .respondsWith()
            .body(body)
            .status(200);
    }
}
```

---

### Chaos Testing

Chaos tests verify that the service's resilience infrastructure — Resilience4j circuit breaker, retry, bulkhead, and the background enrichment queue — behaves correctly under adversarial conditions. Unlike integration tests, which confirm the happy path with a cooperative upstream, chaos tests deliberately break dependencies and observe whether the system degrades gracefully, recovers predictably, and never leaves shared state (DB + in-memory cache) inconsistent.

#### Why Chaos Tests Matter for This Service

The TD Enrich Service has two independently-failable subsystems:

1. **`PlaidApiClient`** — a reactive HTTP client wrapped in Resilience4j retry, circuit breaker, and bulkhead. Its failure modes include upstream 4xx/5xx errors, network timeouts, connection refusals, malformed response bodies, and concurrent request saturation.
2. **`EnrichmentQueueProcessor`** — a background worker pool that drains an in-memory queue. Its failure modes include queue overflow, intermittent Plaid errors during background enrichment, and DB transaction failures mid-write.

Chaos tests use WireMock to inject network faults into the PlaidApiClient layer and Mockito to inject DB/Plaid failures into the queue processor layer. No real network calls are made.

---

#### PlaidApiClient Chaos Scenarios

Test class: [PlaidApiClientChaosTest.java](src/test/java/com/td/enrich/service/PlaidApiClientChaosTest.java)

| Scenario | Fault Injected | Expected Behavior |
|----------|---------------|-------------------|
| Retry on 500, eventual success | First 2 requests return 500; 3rd returns 200 | Retry fires twice; 3rd attempt succeeds; `retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt() > 0` |
| Circuit breaker opens on sustained failures | All requests return 500 | After 6+ failures the circuit breaker transitions to `OPEN`; subsequent calls are short-circuited without hitting WireMock |
| Timeout triggers retry | Response delayed 6 s (beyond 10 s request timeout at default config; confirmed with 30 s verifier) | `PlaidApiException` propagated; `retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt() > 0` |
| Intermittent 50 % failure rate | Two stubs compete — one 500, one 200 | Some calls succeed over 10 attempts; service remains functional |
| Connection refused | Client pointed at a non-listening port | `PlaidApiException` propagated cleanly within 15 s |
| Malformed JSON body | 200 response with `{ invalid json` | Deserialization error propagates; no swallowed exception |
| **4xx errors are not retried** | 400 Bad Request response | Exactly 1 WireMock hit; `retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt() > 0` |
| **Bulkhead saturation** | Bulkhead configured with `maxConcurrentCalls=1, maxWaitDuration=0`; first call held open with 3 s delay | Second concurrent call is rejected immediately; `bulkhead.getMetrics().getNumberOfRejectedCalls() > 0` |
| **Circuit breaker HALF_OPEN recovery** | CB tripped OPEN via 5 failures; `waitDurationInOpenState=600ms`; one successful call sent after wait | CB transitions `OPEN → HALF_OPEN → CLOSED`; state confirmed after successful probe call |

**Key assertions to understand:**

- `retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()` — confirms 4xx errors bypass the retry loop entirely. This is critical: retrying a client error (400, 401) wastes cycles and amplifies load on Plaid.
- `bulkhead.getMetrics().getNumberOfRejectedCalls()` — confirms the bulkhead shed excess concurrency rather than queuing it, which would defeat the bulkhead's purpose of bounding resource consumption.
- `circuitBreaker.getState()` — state assertions are the primary signal for CB scenarios. Metrics alone (call counts) are insufficient because they don't confirm a state transition occurred.

---

#### EnrichmentQueueProcessor Chaos Scenarios

Test class: [EnrichmentQueueProcessorChaosTest.java](src/test/java/com/td/enrich/service/EnrichmentQueueProcessorChaosTest.java)

| Scenario | Fault Injected | Expected Behavior |
|----------|---------------|-------------------|
| **Intermittent failures under flood** | Every 3rd Plaid call returns 503; 9 tasks submitted to 2 workers | ~6 tasks succeed, ~3 fail; `memoryCache.update()` called ≥ 4 times; worker alive and accepting new tasks after draining |
| **Queue saturation** | Queue capacity = 3, 0 workers (queue never drains); 5 tasks submitted | 3 accepted, 2 dropped (logged as `[QUEUE FULL]`); `queueSize() == 3`; no Plaid calls, no DB writes |
| **DB failure after Plaid success** | Plaid returns 200; `merchantCacheRepository.save()` throws `RuntimeException` | `save()` called once; `memoryCache.update()` never called (cache consistency invariant preserved); worker survives |

**The cache consistency invariant** (scenario 3) is the most operationally significant:

The processor writes to the DB inside a `TransactionTemplate` and only updates the in-memory `MerchantMemoryCache` *after* the transaction commits. If `save()` throws, the `TransactionTemplate` rolls back, the exception propagates out of `processTask`, and `memoryCache.update()` is never reached. This ordering guarantee means a pod restart will reload the DB state into memory without any "phantom enriched" entries that were never actually persisted.

---

#### Running Chaos Tests

Chaos tests are co-located with unit tests and run as part of the standard Maven test phase:

```bash
# Run all tests including chaos
./mvnw test

# Run only chaos test classes
./mvnw test -Dtest="PlaidApiClientChaosTest,EnrichmentQueueProcessorChaosTest"

# Run with verbose output to see chaos scenario names
./mvnw test -Dtest="PlaidApiClientChaosTest" -Dsurefire.failIfNoSpecifiedTests=false
```

**Expected output (chaos tests):**
```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.34 s
  ✓ Chaos: Should retry on 500 errors and eventually succeed
  ✓ Chaos: Should open circuit breaker after multiple failures
  ✓ Chaos: Should handle timeout and retry
  ✓ Chaos: Should handle intermittent failures
  ✓ Chaos: Should handle connection refused
  ✓ Chaos: Should handle malformed JSON response
  ✓ Chaos: Should not retry on 4xx client errors
  ✓ Chaos: Should reject calls when bulkhead is saturated
  ✓ Chaos: Circuit breaker recovers through HALF_OPEN back to CLOSED

[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.91 s
  ✓ Chaos: Worker survives intermittent Plaid failures under rapid task flood
  ✓ Chaos: Queue saturation drops excess tasks without crashing the processor
  ✓ Chaos: DB update failure after Plaid success does not update in-memory cache
```

**Timing notes:** Circuit breaker half-open recovery tests sleep up to 800 ms for state transition. The timeout test verifies within a 30 s window due to the 6 s fixed delay. Total chaos suite runtime is approximately 12–15 seconds.

---

### Postman/Newman Collection

```json
{
  "info": {
    "name": "TD Enrich Service",
    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
  },
  "item": [
    {
      "name": "Health Check",
      "request": {
        "method": "GET",
        "url": "http://localhost:8080/api/v1/enrich/health",
        "header": []
      },
      "response": []
    },
    {
      "name": "Enrich Single Transaction",
      "request": {
        "method": "POST",
        "url": "http://localhost:8080/api/v1/enrich/single",
        "header": [
          {
            "key": "Authorization",
            "value": "Bearer {{token}}"
          },
          {
            "key": "Content-Type",
            "value": "application/json"
          }
        ],
        "body": {
          "mode": "raw",
          "raw": "{\"accountId\": \"acc_test_001\", \"transactions\": [{\"description\": \"STARBUCKS #1234\", \"amount\": 5.75, \"date\": \"2026-04-07\"}]}"
        }
      }
    },
    {
      "name": "Enrich Batch",
      "request": {
        "method": "POST",
        "url": "http://localhost:8080/api/v1/enrich/batch",
        "header": [
          {
            "key": "Authorization",
            "value": "Bearer {{token}}"
          }
        ],
        "body": {
          "mode": "raw",
          "raw": "{\"transactions\": [{\"description\": \"COFFEE\", \"amount\": 5, \"date\": \"2026-04-07\"}, {\"description\": \"PIZZA\", \"amount\": 20, \"date\": \"2026-04-07\"}]}"
        }
      }
    },
    {
      "name": "Poll Result",
      "request": {
        "method": "GET",
        "url": "http://localhost:8080/api/v1/enrich/{{guid}}",
        "header": [
          {
            "key": "Authorization",
            "value": "Bearer {{token}}"
          }
        ]
      }
    }
  ]
}
```

---

## Maintenance & Runbook

### Common Operations

#### 1. Rotate Plaid API Key

**Steps:**
1. Generate new key in Plaid dashboard
2. Update Vault secret:
   ```bash
   vault kv put secret/enrich/plaid client_id="new_id" api_key="new_key"
   ```
3. Refresh service configuration:
   ```bash
   kubectl rollout restart deployment/enrich-service -n production
   ```
4. Verify requests are using new key (check logs for 10s period)
5. Revoke old key in Plaid dashboard

---

#### 2. Clear Merchant Cache

**Scenario:** Stale merchant data cached due to Plaid API update

```bash
# Connect to pod
kubectl exec -it enrich-service-xyz -n production -- /bin/bash

# SQL to clear cache (H2 or Azure SQL)
mysql -u sa -p
DELETE FROM merchant_cache WHERE merchant_id = 'merchant-starbucks-seattle';

# Or clear entire cache (affects performance briefly)
DELETE FROM merchant_cache;
```

**Alternative (via API):** Not exposed; requires direct database access or pod restart

---

#### 3. Scale Up Replicas

```bash
kubectl scale deployment enrich-service --replicas=10 -n production
```

Auto-scaling should handle this, but manual scaling available for emergencies.

---

#### 4. View Real-time Logs

```bash
# Tail logs from all pods
kubectl logs -f -l app=enrich-service -n production --all-containers=true

# Filter by trace ID
kubectl logs -f -l app=enrich-service -n production | grep "4bf92f3577b34da6a3ce929d0e0e4736"

# View structured JSON logs
kubectl logs -f -l app=enrich-service -n production | jq '.level, .message, .error'
```

---

#### 5. Access Traces

```bash
# Via Jaeger UI (if deployed)
open http://jaeger.internal.company.com:16686

# Query span by trace ID
curl "http://jaeger-query:16686/api/traces/4bf92f3577b34da6a3ce929d0e0e4736"
```

---

#### 6. Database Migration (Schema Update)

```bash
# Flyway applies migrations automatically on startup, but manual trigger:
./mvnw flyway:migrate

# View migration status
./mvnw flyway:info
```

---

#### 7. Rollback to Previous Version

```bash
# List previous deployments
kubectl rollout history deployment/enrich-service -n production

# Rollback to previous version
kubectl rollout undo deployment/enrich-service -n production

# Or specify exact revision
kubectl rollout undo deployment/enrich-service --to-revision=3 -n production

# Watch rollback progress
kubectl rollout status deployment/enrich-service -n production -w
```

---

### Troubleshooting Checklist

**Problem: High error rate (>5% 5xx errors)**

Diagnostic commands:
```bash
# 1. Check Plaid API status
curl https://status.plaid.com/api/status

# 2. Check circuit breaker state
kubectl logs -l app=enrich-service -n production | grep "circuit_breaker"

# 3. Check database connectivity
kubectl exec -it enrich-service-xyz -- java -jar app.jar --db-health-check

# 4. Check rate limiting
kubectl logs -l app=enrich-service -n production | grep "RATE_LIMITED" | wc -l

# 5. Increase log level to DEBUG temporarily
kubectl set env deployment/enrich-service LOGGING_LEVEL_COM_TD_ENRICH=DEBUG -n production
```

**Problem: High latency (P95 > 2s)**

Diagnostic commands:
```bash
# 1. Check cache hit ratio
kubectl logs -l app=enrich-service -n production | grep "cache_hit" | tail -1000 | jq -r '.cacheHit' | uniq -c

# 2. Check Plaid API latency
kubectl logs -l app=enrich-service -n production | grep "plaidLatencyMs" | jq '.plaidLatencyMs' | sort -n | tail -100 | awk '{sum+=$1} END {print "Avg:", sum/NR}'

# 3. Check database query performance
kubectl logs -l app=enrich-service -n production | grep "database_query_ms" | jq '.duration_ms' | sort -n | tail -50

# 4. Check pod resource usage
kubectl top pod -l app=enrich-service -n production

# 5. Scale up if CPU/Memory near limits
kubectl scale deployment enrich-service --replicas=15 -n production
```

**Problem: Pod crashes/restarts**

Diagnostic commands:
```bash
# 1. Check pod events
kubectl describe pod enrich-service-xyz -n production

# 2. Check crash logs
kubectl logs -p enrich-service-xyz -n production  # -p = previous

# 3. Check resource limits
kubectl get resourcequota -n production

# 4. Check node status
kubectl describe node <node-name>

# 5. Increase memory limit temporarily
kubectl set resources deployment enrich-service --limits=memory=4Gi -n production
```

---

## FAQ and Changelog

### FAQ: Top 10 Questions

**Q1: How do I get an OAuth token to call the API?**

A: Contact the Platform team to register your client. You'll receive `client_id` and `client_secret`. Use these to request a token from `https://auth.company.com/oauth/token` with scope `enrichment:write`.

**Q2: What's the rate limit, and how do I increase it?**

A: Standard limit is 100 req/min per `accountId`. Premium clients can request higher limits via support. Contact platform@company.com.

**Q3: Why is enrichment sometimes empty (`enrichedTransactions: []`)?**

A: This happens when Plaid cannot match the merchant description. Try including a `merchantName` hint. High-confidence matches (>0.85) are returned in the `metadata.confidence` field.

**Q4: Can I batch 1000 transactions in a single request?**

A: Maximum batch size is 1000 transactions per request. Larger requests will return 400 Bad Request. For bulk enrichment, submit multiple batch requests in parallel.

**Q5: How long is the polling timeout for batch results?**

A: Results are typically ready within 2-5 seconds. Polling returns 202 (still processing) until complete. Timeout is 30 minutes; after that, the request is discarded.

**Q6: Will my enriched data be stale if Plaid updates merchant info?**

A: Cached results are valid for 24 hours (configurable via `CACHE_TTL_MINUTES`). After TTL expires, the next request will fetch fresh data from Plaid.

**Q7: What happens if Plaid API is down?**

A: If failures exceed 50% over 5 requests, the circuit breaker opens (503 Service Unavailable). The service automatically retries after 10 seconds of downtime.

**Q8: Can you enrich transactions from the future?**

A: No. Transaction dates must be today or in the past. Future dates will return 400 Bad Request.

**Q9: Is there an SLA for enrichment latency?**

A: Single enrichment: <500ms p95 (with cache). Batch enrichment: <5 seconds for typical batches. No SLA during Plaid outages or service DoS.

**Q10: How do I monitor my API usage?**

A: Use the Prometheus metrics endpoint at `/actuator/prometheus`. Query `enrich_requests_total` for volume and `enrich_request_duration_seconds` for latency histograms.

---

### Changelog Template

```markdown
# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added
- New feature description

### Changed
- Changed behavior description

### Fixed
- Bug fix description

### Deprecated
- Deprecated feature

### Removed
- Removed feature

### Security
- Security patch description

## [1.0.0] - 2026-04-07

### Added
- Initial production release
- Single transaction enrichment endpoint (`POST /single`)
- Batch transaction enrichment endpoint (`POST /batch`)
- Merchant caching with 24-hour TTL
- Circuit breaker, retry, and timeout resilience patterns
- OpenTelemetry tracing and Prometheus metrics
- JWT authentication with OAuth 2.0 bearer tokens

### Fixed
- Improved error messages for better debugging

```

---

## Appendix

### .env.example

```bash
# Application Configuration
SERVICE_PORT=8080
SPRING_PROFILES_ACTIVE=dev

# Caching
CACHE_MAX_SIZE=10000
CACHE_TTL_MINUTES=1440

# Plaid API
PLAID_CLIENT_ID=client_abc123
PLAID_API_KEY=secret_longstring_xyz789

# Database
DB_URL=jdbc:h2:mem:enrich
DB_USER=sa
DB_PASSWORD=password

# Resilience Patterns
CIRCUIT_BREAKER_THRESHOLD=50
CIRCUIT_BREAKER_COOL_DOWN_SECONDS=10
RETRY_MAX_ATTEMPTS=3
TIMEOUT_SECONDS=10

# Observability
LOGGING_LEVEL_COM_TD_ENRICH=INFO
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
OTEL_TRACES_SAMPLER_ARG=0.1  # 10% sampling
```

---

### Sample cURL Requests

```bash
# 1. Health check
curl http://localhost:8080/api/v1/enrich/health

# 2. Single enrichment (requires valid token)
TOKEN="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
curl -X POST http://localhost:8080/api/v1/enrich/single \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "acc_test_001",
    "transactions": [
      {
        "description": "STARBUCKS #1234 Seattle WA",
        "amount": 5.75,
        "date": "2026-04-07"
      }
    ]
  }'

# 3. Batch enrichment
curl -X POST http://localhost:8080/api/v1/enrich/batch \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "transactions": [
      {"description": "COFFEE", "amount": 5, "date": "2026-04-07"},
      {"description": "PIZZA", "amount": 20, "date": "2026-04-07"}
    ]
  }' | jq '.guids[0]' -r > /tmp/guid.txt

# 4. Poll result
GUID=$(cat /tmp/guid.txt)
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/enrich/$GUID
```

---

### Glossary

| Term | Definition |
|------|-----------|
| **MCC** | Merchant Category Code (4-digit ISO code) |
| **Circuit Breaker** | Resilience pattern that stops requests when failure rate exceeds threshold |
| **Idempotency** | Property ensuring same request produces same result when called multiple times |
| **TTL** | Time-to-Live; duration before cached data expires |
| **Bulkhead** | Isolation pattern limiting concurrent calls to a resource |
| **OpenTelemetry** | Observability framework for tracing, metrics, and logging |
| **JWT** | JSON Web Token; signed credential for authentication |
| **Plaid API** | Third-party financial data aggregation platform |
| **Enrichment** | Process of adding metadata (merchant, category) to raw transaction data |
| **Pod** | Kubernetes scheduling unit; typically one container per pod |
| **HPA** | Horizontal Pod Autoscaler; scales replicas based on metrics |

---

**End of Specification**

---

*For questions or updates, contact platform@company.com*
