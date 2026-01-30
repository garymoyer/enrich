# Plaid Enrich Service

A production-ready Spring Boot 3.4+ microservice for Plaid Enrich API integration, featuring resilience patterns, comprehensive testing, and Azure deployment support.

## Features

- **Java 21** with Records and virtual threads
- **Spring Boot 3.4.2** with WebFlux for reactive HTTP clients
- **Resilience4j** for retry, circuit breaker, and bulkhead patterns
- **Azure SQL Database** with Flyway migrations
- **WireMock** stubs for development and testing
- **Comprehensive Testing**: 90%+ code coverage, chaos testing, mutation testing
- **OpenAPI/Swagger** documentation
- **Azure App Service** deployment ready
- **Application Insights** monitoring

## Architecture

### Core Components

1. **EnrichmentController** - REST API endpoints
2. **EnrichmentService** - Business orchestration layer
3. **PlaidApiClient** - Resilient WebClient for Plaid API integration
4. **EnrichmentRepository** - Spring Data JPA for persistence

### Key Features

- **GUID Generation**: Each request gets a unique identifier
- **Request/Response Persistence**: Links original requests to Plaid responses
- **Parallel Processing**: Batch endpoint supports concurrent API calls
- **Resilience Patterns**:
  - Retry with exponential backoff (3 attempts)
  - Circuit breaker (50% failure threshold)
  - Bulkhead for parallelization (10 concurrent calls)

## Prerequisites

- Java 21 JDK
- Maven 3.9+
- Docker (for containerization)
- Azure SQL Database (for production) or H2 (for local dev)

## Quick Start

### 1. Clone and Build

```bash
cd /Users/garymoyer/Code/enrich
mvn clean install
```

### 2. Run Locally (Dev Profile)

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

The service will start on `http://localhost:8080` with:
- H2 in-memory database
- WireMock stubs for Plaid API
- Swagger UI at `http://localhost:8080/swagger-ui.html`
- H2 Console at `http://localhost:8080/h2-console`

### 3. Run WireMock (for development)

```bash
docker run -p 8089:8080 -v $(pwd)/src/test/resources/wiremock:/home/wiremock wiremock/wiremock:latest
```

## API Endpoints

### Enrich Single Transaction

```bash
POST /api/v1/enrich
Content-Type: application/json

{
  "accountId": "acc_123",
  "transactions": [
    {
      "description": "STARBUCKS COFFEE",
      "amount": 5.75,
      "date": "2026-01-30",
      "merchantName": "Starbucks"
    }
  ]
}
```

**Response:**

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
        "confidence": "HIGH"
      }
    }
  ],
  "processedAt": "2026-01-30T10:30:00Z",
  "status": "SUCCESS"
}
```

### Enrich Batch Transactions

```bash
POST /api/v1/enrich/batch
Content-Type: application/json

[
  { "accountId": "acc_123", "transactions": [...] },
  { "accountId": "acc_456", "transactions": [...] }
]
```

### Retrieve Enrichment by ID

```bash
GET /api/v1/enrich/{requestId}
```

### Health Check

```bash
GET /actuator/health
```

## Configuration

### Environment Variables

#### Development
```bash
export SPRING_PROFILES_ACTIVE=dev
```

#### Production
```bash
export SPRING_PROFILES_ACTIVE=prod
export AZURE_SQL_CONNECTION_STRING="jdbc:sqlserver://..."
export PLAID_API_BASE_URL="https://production.plaid.com"
export PLAID_API_KEY="your-api-key"
export PLAID_CLIENT_ID="your-client-id"
export APPLICATIONINSIGHTS_CONNECTION_STRING="InstrumentationKey=..."
```

### Spring Profiles

- **dev**: H2 database, WireMock, debug logging
- **test**: Testcontainers SQL Server, fast timeouts
- **prod**: Azure SQL, real Plaid API, JSON logging, Application Insights

## Testing

### Run All Tests

```bash
mvn clean verify
```

### Unit Tests Only

```bash
mvn test
```

### Integration Tests

```bash
mvn verify -DskipUnitTests
```

### Code Coverage Report

```bash
mvn jacoco:report
open target/site/jacoco/index.html
```

Target: **90%+ coverage**

### Mutation Testing (PIT)

```bash
mvn pitest:mutationCoverage
open target/pit-reports/index.html
```

Target: **80%+ mutation coverage**

### Chaos Testing

Run chaos tests to verify resilience:

```bash
mvn test -Dtest=PlaidApiClientChaosTest
```

Tests include:
- Circuit breaker activation on repeated failures
- Retry logic with timeouts
- Slow response handling

## Docker

### Build Image

```bash
docker build -t plaid-enrich-service:latest .
```

### Run Container

```bash
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e AZURE_SQL_CONNECTION_STRING="jdbc:sqlserver://..." \
  -e PLAID_API_KEY="your-key" \
  plaid-enrich-service:latest
```

### Docker Compose (Development)

```bash
docker-compose up
```

## Azure Deployment

### Prerequisites

1. Azure SQL Database provisioned
2. Application Insights resource created
3. Azure App Service (Java 21, Linux)

### Deploy to Azure App Service

```bash
# Login to Azure
az login

# Create resource group
az group create --name rg-plaid-enrich --location eastus

# Create App Service plan
az appservice plan create \
  --name asp-plaid-enrich \
  --resource-group rg-plaid-enrich \
  --sku P1V2 \
  --is-linux

# Create web app
az webapp create \
  --name plaid-enrich-service \
  --resource-group rg-plaid-enrich \
  --plan asp-plaid-enrich \
  --runtime "JAVA:21-java21"

# Configure environment variables
az webapp config appsettings set \
  --name plaid-enrich-service \
  --resource-group rg-plaid-enrich \
  --settings \
    SPRING_PROFILES_ACTIVE=prod \
    AZURE_SQL_CONNECTION_STRING="<your-connection-string>" \
    PLAID_API_KEY="<your-api-key>" \
    PLAID_CLIENT_ID="<your-client-id>" \
    APPLICATIONINSIGHTS_CONNECTION_STRING="<your-insights-key>"

# Deploy JAR
az webapp deploy \
  --name plaid-enrich-service \
  --resource-group rg-plaid-enrich \
  --src-path target/enrich-service-1.0.0.jar \
  --type jar

# Verify deployment
az webapp browse --name plaid-enrich-service --resource-group rg-plaid-enrich
```

### Health Check Configuration

Azure App Service monitors `/actuator/health`:
- **Liveness**: Database connectivity, circuit breaker status
- **Readiness**: Application startup complete

## Monitoring

### Actuator Endpoints

- `/actuator/health` - Health status
- `/actuator/metrics` - Metrics
- `/actuator/prometheus` - Prometheus metrics
- `/actuator/circuitbreakers` - Circuit breaker state
- `/actuator/circuitbreakerevents` - Circuit breaker events

### Application Insights

When deployed to Azure, the service automatically sends:
- Request telemetry
- Dependency telemetry (Plaid API calls)
- Exception telemetry
- Custom metrics (circuit breaker events)
- Distributed tracing

### Logs

View logs in Azure:

```bash
az webapp log tail --name plaid-enrich-service --resource-group rg-plaid-enrich
```

## Performance Tuning

### JVM Options (Azure)

The Dockerfile includes optimized JVM settings:

```
-XX:+UseG1GC
-XX:MaxRAMPercentage=75.0
-XX:InitialRAMPercentage=50.0
-XX:+UseContainerSupport
```

### Connection Pooling

HikariCP is configured for optimal Azure SQL performance:
- Max pool size: 20
- Min idle: 5
- Connection timeout: 30s

### WebClient

- Max connections: 100
- Connection timeout: 5s
- Request timeout: 10s (15s in prod)

## Troubleshooting

### Circuit Breaker Open

Check `/actuator/circuitbreakers` to see state. Wait for `waitDurationInOpenState` (10s) before it transitions to half-open.

### Database Connection Issues

Verify connection string and ensure firewall rules allow Azure App Service IP ranges.

### WireMock Not Running

Start WireMock:
```bash
docker run -p 8089:8080 wiremock/wiremock:latest
```

## Contributing

### Code Quality Standards

- **Unit tests**: 90%+ coverage required
- **Mutation tests**: 80%+ coverage required
- **SOLID principles**: Enforced in PR reviews
- **Clean code**: No code smells, proper naming, single responsibility

### PR Review Checklist

- [ ] All tests pass (`mvn verify`)
- [ ] Code coverage >90% (`mvn jacoco:report`)
- [ ] Mutation coverage >80% (`mvn pitest:mutationCoverage`)
- [ ] No Checkstyle violations
- [ ] OpenAPI documentation updated
- [ ] README updated if needed

## License

Proprietary - All rights reserved

## Support

For issues or questions, contact the platform team.
