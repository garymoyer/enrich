# Prompt 01 — Initial Service Build

**Session date:** 2026-01-30
**Resulting commit:** `1ed9d64` — *first commit*

---

## Prompt

Build a production-ready Spring Boot microservice that integrates with the Plaid Enrich API. The service should be deployable to Azure App Service and backed by Azure SQL Database.

### Requirements

#### Technology Stack
- Java 21 (use Records, virtual threads where appropriate)
- Spring Boot 3.4+
- Maven build tool
- WebClient (reactive, non-blocking) for all HTTP calls
- Spring Data JPA + Flyway for database migrations
- Azure SQL Database (H2 in-memory for local dev)
- Resilience4j for fault tolerance
- SpringDoc OpenAPI / Swagger UI
- Spring Boot Actuator

#### API Design
Expose the following REST endpoints:

- `POST /api/v1/enrich` — Accept a batch of transactions for a single account, call Plaid Enrich, persist the request/response pair, and return enriched results with a generated GUID as `requestId`
- `POST /api/v1/enrich/batch` — Accept a list of account batches and process them in parallel
- `GET /api/v1/enrich/{requestId}` — Retrieve a previously enriched result by its GUID
- `GET /actuator/health` — Health check (database + circuit breaker state)

Each transaction input has: `description`, `amount`, `date`, `merchantName` (optional).
Each enriched transaction output has: `transactionId`, `category`, `merchantName`, `logoUrl`, `metadata` (including `confidence`).

#### Resilience Patterns
- **Retry**: 3 attempts, exponential backoff (1s base, 2x multiplier), retry on `WebClientRequestException` and `TimeoutException`, do not retry on 400/401
- **Circuit breaker**: sliding window of 10, 50% failure threshold, 10s wait in open state, 3 test calls in half-open
- **Bulkhead**: max 10 concurrent calls, 2s max wait, thread pool (5 core / 10 max / 50 queue)
- **Timeouts**: 5s connection, 10s request (15s in prod)
- **Connection pooling**: max 100 connections, 20s idle timeout

#### Persistence
Store each enrichment request/response as a row in an `enrichment_records` table:
- `request_id` (VARCHAR 36, primary key — the generated GUID)
- `original_request` (NVARCHAR MAX, JSON)
- `plaid_response` (NVARCHAR MAX, JSON)
- `created_at`, `updated_at` (DATETIME2)
- `status` (VARCHAR 20: SUCCESS / FAILED / PENDING)
- `error_message` (NVARCHAR MAX, nullable)
- Indexes on `created_at` and `status`

#### Testing
- Unit tests with JUnit 5, Mockito, AssertJ — target 90%+ JaCoCo coverage
- WireMock stubs for Plaid API (place stubs under `src/test/resources/wiremock/`)
- Chaos tests (`PlaidApiClientChaosTest`) covering: 500 errors with retry, circuit breaker opening, timeout + retry, intermittent failures, connection refused, malformed JSON
- PIT mutation testing configured with 80% threshold
- Testcontainers SQL Server for integration tests (`test` profile)

#### Spring Profiles
- `dev` — H2 in-memory, WireMock at `localhost:8089`, debug logging, H2 console enabled
- `test` — Testcontainers SQL Server, fast timeouts, WireMock stubs
- `prod` — Azure SQL, real Plaid endpoint, JSON structured logging (Logstash encoder), Application Insights, JVM optimizations

#### Docker
Multi-stage Dockerfile:
- Stage 1 (builder): Eclipse Temurin 21 JDK Alpine, Maven build, dependency cache layer
- Stage 2 (runtime): Eclipse Temurin 21 JRE Alpine, non-root user, Azure JVM flags (`-XX:+UseG1GC`, `-XX:MaxRAMPercentage=75.0`, `-XX:InitialRAMPercentage=50.0`, `-XX:+UseContainerSupport`, `-XX:+TieredCompilation`), health check on `/actuator/health`, port 8080
- Include a `.dockerignore`

#### Documentation
- Comprehensive `README.md` covering: architecture, quick start, API examples, all Spring profiles, environment variables, testing instructions, Docker, Azure deployment (with `az` CLI commands), monitoring, troubleshooting
- Swagger/OpenAPI annotations on all endpoints
- `IMPLEMENTATION_SUMMARY.md` summarising all deliverables and verification commands

Place the project at `/Users/garymoyer/Code/enrich`.
