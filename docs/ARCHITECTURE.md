# TD Enrich Service Architecture

## System Overview

The TD Enrich Service is a production-ready Spring Boot microservice that enriches financial transaction data using the Plaid API. It provides transaction enrichment with intelligent caching, circuit breaker resilience patterns, and comprehensive testing infrastructure.

```
┌─────────────────────────────────────────────────────────────────┐
│                        Client Applications                       │
└──────────────────────────┬──────────────────────────────────────┘
                           │ REST API
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│              EnrichmentController (REST Endpoints)              │
│  • POST /api/v1/enrich/single - Single transaction enrichment  │
│  • POST /api/v1/enrich/batch  - Batch enrichment (async)       │
│  • GET  /api/v1/enrich/{guid} - Get enriched result by GUID    │
└──────────────────┬──────────────────────────────────────────────┘
                   │
    ┌──────────────┴──────────────────┐
    ▼                                  ▼
┌─────────────────────────┐    ┌─────────────────────────┐
│  EnrichmentService      │    │ GlobalExceptionHandler  │
│  (Business Logic)       │    │   (Error Handling)      │
│ • GUID generation       │    │     REST responses      │
│ • Merchant cache lookup │    │     Error mapping       │
│ • Request/response      │    │     Validation          │
│   persistence           │    └─────────────────────────┘
└──────────────┬──────────────────────────────────┐
               │                                  │
    ┌──────────┴────────────┐         ┌──────────┴──────────┐
    ▼                       ▼         ▼                     ▼
┌──────────────┐  ┌──────────────────┐  ┌──────────────┐  ┌────────────┐
│ PlaidApiClient│  │EnrichmentQueue   │  │Merchant Cache│  │Repository  │
│ (with         │  │Processor         │  │(In-Memory +  │  │(JPA/SQL)   │
│  Resilience)  │  │(Async Processing)│  │ Database)    │  │            │
│ • WebClient   │  │ • Virtual Threads│  │ • LRU cache  │  │Persistence│
│ • Retry       │  │ • Queue mgmt     │  │ • DB lookup  │  │ • H2 dev   │
│ • Circuit     │  │ • Error handling │  │ • Thread-safe│  │ • SQL prod │
│   breaker     │  │ • Status tracking│  │              │  │            │
│ • Timeout     │  │                  │  │              │  │            │
│ • Bulkhead    │  │                  │  │              │  │            │
└────────┬──────┘  └──────────────────┘  └──────────────┘  └────────────┘
         │
         │ HTTP (WebClient - Reactive)
         ▼
    ┌─────────────────────┐
    │   Plaid API         │
    │ /enrich/transactions│
    └─────────────────────┘
```

## Core Components

### 1. EnrichmentController
**Package:** `com.td.enrich.controller`  
**Endpoints:**
- `POST /api/v1/enrich/single` - Synchronous single transaction enrichment
- `POST /api/v1/enrich/batch` - Asynchronous batch processing
- `GET /api/v1/enrich/{guid}` - Retrieve enriched result by GUID

Responsible for:
- Request validation
- OpenAPI documentation
- Error transformation
- Response serialization

### 2. EnrichmentService
**Package:** `com.td.enrich.service`  
**Orchestrates:**
- GUID generation for traceable requests
- Merchant cache lookup (in-memory + database)
- Plaid API calls for cache misses
- Persistence of requests/responses
- Result mapping and transformation

**Thread Safety:** Fully thread-safe with proper synchronization on cache operations.

### 3. PlaidApiClient
**Package:** `com.td.enrich.service`  
**Features:**
- Reactive WebClient for non-blocking HTTP
- Resilience4j patterns:
  - **Circuit Breaker:** Stops calling Plaid when failure rate exceeds 50%
  - **Retry:** Exponential backoff with up to 3 attempts
  - **Bulkhead:** Max 10 concurrent Plaid calls
  - **Timeout:** 10-second request timeout
- Comprehensive error handling and logging
- Request/response validation

### 4. MerchantMemoryCache
**Package:** `com.td.enrich.service`  
**Design:**
- LRU (Least Recently Used) cache with configurable max size (default: 1000)
- Keyed on (description, merchantName) tuple
- Stores `MerchantCacheEntry` objects
- Thread-safe operations (synchronized access)
- Initialization from database on startup
- Manual eviction support

**Cache States:**
- **PENDING:** Awaiting async Plaid enrichment
- **ENRICHED:** Contains full Plaid JSON response

### 5. EnrichmentQueueProcessor
**Package:** `com.td.enrich.service`  
**Async Processing:**
- Virtual threads (Project Loom) for lightweight async workers
- Configurable worker pool (default: 1)
- Task queue with size limit (default: 1000)
- Graceful shutdown with pending task handling
- Automatic status transitions (PENDING → ENRICHED)

### 6. Data Persistence
**Entities:**
- `EnrichmentEntity` - Request/response audit log
- `MerchantCacheEntity` - Cache with (description, merchantName) uniqueness

**Databases:**
- **Development/Testing:** H2 (in-memory)
- **Production:** Azure SQL Database

**Flyway Migrations:**
- V1: Initial schema (enrichment requests/responses)
- V2: Merchant cache table
- V3: Cache status tracking

## Request Flow

### Single Transaction Enrichment (Synchronous)

```
Client Request
    ↓
EnrichmentController.enrichSingle()
    ↓
EnrichmentService.enrichSingle()
    ├─ Generate GUID
    ├─ Check merchant cache (memory)
    │   └─ Hit: Return cached Plaid response
    │   └─ Miss: → Plaid API call
    ├─ Call PlaidApiClient.enrichTransactions()
    │   └─ With resilience patterns (retry, circuit breaker, timeout)
    ├─ Persist request/response to database
    ├─ Update merchant cache (memory + database)
    └─ Return EnrichmentResponse
    ↓
EnrichmentController returns HTTP 200
```

### Batch Enrichment (Asynchronous)

```
Client Request (batch of N transactions)
    ↓
EnrichmentController.enrichBatch()
    ├─ Validate request
    ├─ Create N enrichment tasks
    ├─ Enqueue to EnrichmentQueueProcessor
    └─ Return 202 Accepted with GUIDs
    ↓
[Client polls with GET /api/v1/enrich/{guid}]
    ↓
EnrichmentQueueProcessor (background workers)
    ├─ Dequeue task from queue
    ├─ Call enrichSingle() for each transaction
    └─ Update status in EnrichmentEntity
```

## Resilience Patterns

### Circuit Breaker (PlaidApiClient)
- **Failure Rate Threshold:** 50%
- **Sliding Window:** 10 most recent calls
- **Cool-down:** 10 seconds in OPEN state
- **Half-open:** Try 3 calls to recover

### Retry Strategy
- **Max Attempts:** 3
- **Backoff:** Exponential (base 2)
- **Jitter:** Added to prevent thundering herd
- **Retryable Exceptions:**
  - `WebClientRequestException` (network issues)
  - `TimeoutException`
  - `ServiceUnavailable` (5xx transient)

### Bulkhead (Thread Pool Isolation)
- **Max Concurrent Calls:** 10
- **Core Threads:** 5
- **Max Threads:** 10
- **Queue Capacity:** 50

### Timeout
- **Connection Timeout:** 5 seconds
- **Request Timeout:** 10 seconds

## Data Models

### EnrichmentRequest (DDD)
```java
record EnrichmentRequest(
    String description,           // Transaction text (e.g., "STARBUCKS")
    String merchantName,          // Parsed merchant name
    BigDecimal amount            // Transaction amount
)
```

### PlaidEnrichResponse
```java
record PlaidEnrichResponse(
    List<PlaidEnrichedTransaction> enrichedTransactions
)

record PlaidEnrichedTransaction(
    String merchantName,
    String merchantCategoryCode,
    String logo,
    String website,
    String phoneNumber,
    Address address
)
```

### EnrichmentEntity (JPA)
- Audit log of all enrichment requests
- Links GUID → request data → response data
- Timestamps for performance tracking

### MerchantCacheEntity (JPA)
- Keyed on: (description, merchantName)
- Stores: Full Plaid JSON response
- Status: PENDING or ENRICHED
- Timestamps: Created at

## Configuration

### application.yml
```yaml
# Plaid API Configuration
plaid:
  base-url: ${PLAID_API_BASE_URL}
  api-key: ${PLAID_API_KEY}
  client-id: ${PLAID_CLIENT_ID}
  timeout:
    connection: 5000    # milliseconds
    request: 10000      # milliseconds

# Merchant Cache Configuration
enrich:
  cache:
    max-size: 1000                    # LRU capacity
    queue-size: 1000                  # Async task queue
    worker-threads: 1                 # Virtual thread workers

# Resilience4j Patterns
resilience4j:
  circuitbreaker:
    instances:
      plaidApi:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        
  retry:
    instances:
      plaidApi:
        max-attempts: 3
        exponential-backoff-multiplier: 2
        
  bulkhead:
    instances:
      plaidApi:
        max-concurrent-calls: 10
```

## Testing Strategy

### Unit Tests (74 tests)
- **Coverage:** 90%+ code coverage with JaCoCo
- **Mutation Testing:** 80%+ mutation score with PIT
- **Test Pyramiid:**
  - Controller tests (mocked service)
  - Service tests (mocked repository + API client)
  - Repository tests (H2 database)
  - Utility tests (GUID generation)

### Integration Tests
- WireMock for Plaid API mocking
- Testcontainers for database isolation
- Test profiles per environment

### Performance Tests
- Virtual thread throughput benchmarks
- Cache hit/miss analysis
- Resilience pattern performance under load

## Deployment Architecture

### Azure App Service
- Spring Boot embedded Tomcat
- Health check endpoints (`/actuator/health`)
- Graceful shutdown (30-second drain timeout)

### Azure SQL Database
- Connection pooling (HikariCP)
- SSL/TLS encryption
- Managed identity authentication

### Monitoring & Observability
- **Metrics:** Prometheus-compatible (via Actuator)
- **Logs:** Structured logging (JSON format)
- **Health Checks:**
  - Readiness: All dependencies healthy
  - Liveness: Application still running
  - Database connectivity verified

## Error Handling

### Global Exception Handler
Transforms exceptions into REST responses:
- **PlaidApiException** → 502 Bad Gateway
- **DataIntegrityViolationException** → 409 Conflict
- **Validation errors** → 400 Bad Request
- **Unexpected errors** → 500 Internal Server Error

## Thread Safety

### Concurrent Access Patterns
- **Merchant Memory Cache:** Synchronized map with manual synchronization on complex operations
- **Database:** Spring Data transactional boundaries
- **GUID Generation:** Thread-safe UUID generation
- **Virtual Thread Integration:** Natural concurrency via async/await patterns

## Performance Characteristics

### Caching Impact
- **Cache Hit:** <5ms (in-memory lookup)
- **Cache Miss:** 50-500ms (Plaid API call + retry overhead)
- **Cache Hit Ratio:** Target 70-80% for typical workloads

### Async Processing
- **Batch Throughput:** 100+ transactions/second with default config
- **Queue Latency:** <1ms per task enqueue
- **Worker Latency:** Depends on Plaid API response time

## Extension Points

### Custom Merchant Cache Eviction
Extend `MerchantMemoryCache` to implement custom eviction strategies.

### Custom Plaid Response Mapping
Extend `EnrichmentService` to add custom transformations before persistence.

### Custom Resilience Patterns
Add additional Resilience4j patterns (e.g., RateLimiter) without changing core logic.

## Dependencies

### Core Framework
- Spring Boot 3.4.2
- Spring Data JPA
- Spring WebFlux (WebClient)
- Spring Security (for future auth)

### Resilience & Observability
- Resilience4j 2.2.0
- Micrometer (metrics)
- Prometheus (monitoring)

### Testing
- JUnit 5 (Jupiter)
- Mockito
- WireMock (API mocking)
- Testcontainers
- JaCoCo (code coverage)
- PIT (mutation testing)

### Persistence
- Flyway (database migrations)
- HikariCP (connection pooling)
- H2 (in-memory for testing)
- MSSQL Driver (production Azure SQL)

## Future Roadmap

1. **Circuit Breaker Metrics:** Real-time dashboard of Plaid API health
2. **Advanced Caching:** Redis for distributed cache across instances
3. **GraphQL API:** Complement REST with flexible querying
4. **Event Streaming:** Kafka integration for high-volume enrichment
5. **ML-based Categorization:** Predict merchant category with ML model
6. **Rate Limiting:** Per-client API quotas via Resilience4j RateLimiter
7. **Batch Webhook Callbacks:** Notify clients when batch processing completes
8. **Cache Warming:** Proactive cache loading for popular merchants
