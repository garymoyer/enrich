# Testing Guide - TD Enrich Service

## Overview

The TD Enrich Service includes comprehensive testing infrastructure:

- **200 randomized merchant transaction test cases** covering realistic scenarios
- **Mock Plaid API responses** with various data availability scenarios
- **Test client** for synchronous and asynchronous enrichment
- **Testing harness** for batch execution and metrics collection
- **Multiple test scenarios** (cache-heavy, edge cases, stress tests)

---

## Test Infrastructure

### 1. Test Data Generation (`MerchantTestDataGenerator`)

Generates 200 realistic merchant transaction test cases with:
- 100+ predefined merchants across 10+ categories
- Randomized transaction amounts per merchant type
- Varied location formats and descriptions
- Reproducible seed-based randomization

**Usage:**
```java
// Generate 200 standard test cases
List<EnrichmentRequest> cases = MerchantTestDataGenerator.generate200TestCases();

// Generate specific scenario
List<EnrichmentRequest> cacheHeavy = MerchantTestDataGenerator.generateScenario("CACHE_HEAVY");
```

**Available Scenarios:**
- `CACHE_HEAVY` - Repeated merchants to test cache effectiveness
- `HIGH_VARIANCE_AMOUNTS` - Wide range of transaction amounts
- `EDGE_CASES` - Boundary conditions, special characters, null handling
- `STRESS_TEST` - 200 identical transactions

### 2. Mock Plaid Responses (`MockPlaidResponseGenerator`)

Generates randomized Plaid API responses:

**Response Scenarios:**
- **Success (70%)** - Full merchant data with all fields
- **Partial Data (15%)** - Some optional fields missing (logo, website, phone)
- **No Enrichment (15%)** - Empty response (merchant not found)

**Response Types:**
```java
// Standard randomized response
PlaidEnrichResponse response = MockPlaidResponseGenerator.generateMockResponse("STARBUCKS");

// Controlled scenario (0-99 seed)
PlaidEnrichResponse response = MockPlaidResponseGenerator.generateMockResponse("AMAZON", 50);

// Multiple transactions
PlaidEnrichResponse response = MockPlaidResponseGenerator.generateMultipleTransactionsResponse("SHELL", 2);

// International merchant
PlaidEnrichResponse response = MockPlaidResponseGenerator.generateInternationalResponse("SHOP", "GB");

// Edge cases (special characters, intl data)
PlaidEnrichResponse response = MockPlaidResponseGenerator.generateEdgeCaseResponse("CAFÉ");
```

### 3. Test Client (`TestEnrichmentClient`)

Synchronous and asynchronous client for testing the API:

**Synchronous Single Enrichment:**
```java
TestEnrichmentClient client = new TestEnrichmentClient("http://localhost:8080");

EnrichmentRequest request = new EnrichmentRequest(
    "STARBUCKS #1234 NYC",
    "STARBUCKS",
    new BigDecimal("5.45")
);

EnrichmentResponse response = client.enrichSingle(request);
```

**Batch Asynchronous Enrichment:**
```java
List<EnrichmentRequest> batch = List.of(
    new EnrichmentRequest("STARBUCKS #1234 NYC", "STARBUCKS", new BigDecimal("5.45")),
    new EnrichmentRequest("AMAZON", "AMAZON", new BigDecimal("29.99")),
    new EnrichmentRequest("UBER TIP", "UBER", new BigDecimal("3.00"))
);

// Submit and get GUIDs
List<String> guids = client.enrichBatch(batch);

// Poll for results
EnrichmentResponse result = client.pollForResult(guids.get(0), 30000);

// Or wait for all
List<EnrichmentResponse> allResults = client.enrichBatchAndWait(batch, 30000);
```

**Metrics Collection:**
```java
Map<String, TestEnrichmentClient.ClientMetrics> metrics = client.getMetrics();
metrics.forEach((op, stats) -> 
    System.out.println(stats) // Prints: operation: calls, avg time, success rate
);
```

### 4. Test Harness (`EnrichmentTestHarness`)

Orchestrates complete test runs with 200 cases:

**Run All Test Scenarios:**
```java
EnrichmentTestHarness harness = new EnrichmentTestHarness("http://localhost:8080");

// Single enrichment: 200 sequential calls
EnrichmentTestHarness.TestRunReport report1 = harness.runSingleEnrichmentTests();

// Batch: Submit in batches of 20, poll for results
EnrichmentTestHarness.TestRunReport report2 = harness.runBatchEnrichmentTests(20);

// Cache-focused: Many repeated merchants
EnrichmentTestHarness.TestRunReport report3 = harness.runCacheHeavyTests();

// Parallel execution: 200 concurrent calls
EnrichmentTestHarness.TestRunReport report4 = harness.runParallelTests(10);

// Stress test: Identical transaction 200x
EnrichmentTestHarness.TestRunReport report5 = harness.runStressTest();

// Edge cases
EnrichmentTestHarness.TestRunReport report6 = harness.runEdgeCaseTests();
```

**Test Report Includes:**
- Total/passed/failed test count with success rate
- Response time statistics (min, max, avg, P50, P95, P99)
- Estimated cache hit ratio
- Per-merchant cache hit counts
- Service health check
- Client-side metrics (operations, latencies)

---

## WireMock Integration

For integration testing with mocked Plaid API responses:

**Setup (Test Configuration):**
```java
@SpringBootTest
class EnrichmentServiceIntegrationTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
        .options(wireMockConfig()
            .port(8089)  // Matches application-test.yml
            .withRootDirectory("src/test/resources/wiremock"))
        .build();

    @BeforeEach
    void setupMocks() {
        // Success response
        wireMock.stubFor(post(urlPathEqualTo("/enrich/transactions"))
            .willReturn(ok()
                .withBody(SUCCESS_RESPONSE_JSON)
                .withHeader("Content-Type", "application/json")));
    }

    @Test
    void enrichWithMockedPlaid() {
        // Your test code
    }
}
```

**Mock Response Stubs (src/test/resources/wiremock/):**

`plaid-enrich-success.json`:
```json
{
  "__files": {
    "plaid-success-response.json": {
      "enrichedTransactions": [
        {
          "merchantName": "Starbucks Coffee Company",
          "merchantCategoryCode": "5812",
          "logo": "https://...",
          "website": "www.starbucks.com",
          "phoneNumber": "+1-800-STARBKS",
          "address": {
            "street": "1st & Pike Place",
            "city": "Seattle",
            "region": "WA",
            "postalCode": "98101",
            "country": "US"
          }
        }
      ]
    }
  }
}
```

---

## Running Tests

### Unit Tests

```bash
# Run all unit tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=EnrichmentServiceTest

# Run with coverage report
./mvnw test jacoco:report
# Report at: target/site/jacoco/index.html

# Run mutation tests (PIT)
./mvnw org.pitest:pitest-maven:mutationCoverage
# Report at: target/pit-reports/index.html
```

### Integration Tests with Test Client

**Start the service:**
```bash
./mvnw spring-boot:run
```

**Run test harness (separate terminal):**
```bash
# Create a simple test runner class
cat > src/test/java/com/td/enrich/test/HarnessRunner.java << 'EOF'
public class HarnessRunner {
    public static void main(String[] args) {
        EnrichmentTestHarness harness = new EnrichmentTestHarness("http://localhost:8080");
        
        // Run all scenarios
        harness.runSingleEnrichmentTests();
        harness.runBatchEnrichmentTests(20);
        harness.runCacheHeavyTests();
        harness.runStressTest();
        harness.runEdgeCaseTests();
        harness.runParallelTests(10);
    }
}
EOF

javac -cp target/classes src/test/java/com/td/enrich/test/HarnessRunner.java
java -cp target/classes HarnessRunner
```

### Performance Testing

**Local Development (In-Memory DB):**
```bash
./mvnw test -Dspring.profiles.active=test

# Check metrics after tests
curl http://localhost:8080/actuator/metrics/enrichment.cache.hits
curl http://localhost:8080/actuator/metrics/enrichment.request.duration
```

**Docker/Container Testing:**
```bash
# Build container
docker build -t td-enrich:latest .

# Run with performance monitoring
docker run -p 8080:8080 \
  -e PLAID_API_BASE_URL=http://host.docker.internal:8089 \
  td-enrich:latest

# In another terminal, run tests
./mvnw test -Dplaid.api.base.url=http://localhost:8089
```

---

## Test Scenarios Explained

### 1. Single Enrichment (200 sequential calls)
- **Purpose:** Validate basic functionality and measure sequential latency
- **Expected Behavior:**
  - First call to merchant: 200-500ms (Plaid API + DB)
  - Repeat calls: <10ms (cached)
  - Expected cache hit ratio: 70-80%
- **Metrics:**  Response times, cache efficiency

### 2. Batch Enrichment (20 per batch)
- **Purpose:** Validate async queue processor and batch throughput
- **Expected Behavior:**
  - Submission: <50ms per batch
  - Processing time: 1-2 seconds per batch (depending on Plaid)
  - Full 200 transactions: 10-20 seconds
- **Metrics:** Batch throughput, queue latency

### 3. Cache-Heavy (5 merchants, 40 repetitions each)
- **Purpose:** Stress test cache hit rates and concurrency
- **Expected Behavior:**
  - High cache hit ratio (>90%)
  - Very low average response time (<20ms)
  - Consistent latency
- **Metrics:** Cache efficiency, P95 latency

### 4. Parallel Execution (10 concurrent calls)
- **Purpose:** Test virtual thread concurrency and thread safety
- **Expected Behavior:**
  - All 200 requests complete in ~10-15 seconds
  - No race conditions or data corruption
  - Bulkhead limits max concurrent calls
- **Metrics:** Throughput, concurrency behavior

### 5. Stress Test (200 identical transactions)
- **Purpose:** Stress test cache and detect concurrency issues
- **Expected Behavior:**
  - First call: ~500ms (Plaid API)
  - Subsequent calls: <5ms (cached)
  - Zero cache misses after warmup
- **Metrics:** Cache stability, no errors

### 6. Edge Cases
- **Purpose:** Validate robustness with boundary conditions
- **Expected Behavior:**
  - $0.01 transactions: Accepted
  - $50,000 transactions: Accepted
  - Special characters (é, ñ, &): Handled correctly
  - All requests: Return valid responses or proper errors
- **Metrics:** Error rates, edge case coverage

---

## Expected Results

### Baseline Performance (Local Development)

| Scenario | Avg Response | P95 | P99 | Success Rate |
|----------|-------------|-----|-----|--------------|
| Single (cached) | 8ms | 15ms | 25ms | 100% |
| Single (uncached) | 350ms | 500ms | 750ms | 99.5% |
| Batch (10 tx/batch) | 450ms | 600ms | 900ms | 100% |
| Cache-Heavy | 15ms | 30ms | 50ms | 100% |
| Parallel (10) | 400ms | 600ms | 800ms | 99% |
| Stress Test | 8ms | 12ms | 18ms | 100% |

### Cache Hit Ratio (Standard 200 Case Run)

- **Warm-up:** First 20 requests (cache empty): 0 hits
- **Steady state:** Following 100 requests: 75-85 hits (75-85%)
- **Final:** Last 80 requests: 70-78 hits with size-limited cache

---

## Troubleshooting

### Tests Taking Too Long

**Problem:** Batch tests stuck polling  
**Solution:** Increase max wait time or check Plaid API connectivity
```bash
curl http://localhost:8089/enrich/transactions  # Check mock availability
```

### Low Cache Hit Ratio

**Problem:** Expected 70% hits, got 20%  
**Solution:** Check cache configuration
```bash
# Verify cache size in application.yml
enrich:
  cache:
    max-size: 1000  # Should be >= 200 for full test cases
```

### Concurrency Errors

**Problem:** Thread safety issues in parallel tests  
**Solution:** Check for data race in cache or repository operations
```bash
# Run stress test to isolate concurrency issues
harness.runStressTest();

# Check logs for MerchantMemoryCache synchronization warnings
tail -f logs/*.log | grep -i synchron
```

### Circuit Breaker Open

**Problem:** Tests failing with 502 Bad Gateway  
**Solution:** Circuit breaker engaged (too many Plaid API failures)
```bash
# Check circuit status
curl http://localhost:8080/actuator/health/circuitbreakers

# Wait 10 seconds for cool-down, then retry
sleep 10
```

---

## Continuous Integration

### GitHub Actions Example

```yaml
name: Test Suite

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      mssql:
        image: mcr.microsoft.com/mssql/server:latest
        env:
          SA_PASSWORD: YourPassword123!
          ACCEPT_EULA: Y
        options: >-
          --health-cmd="/opt/mssql-tools/bin/sqlcmd -S localhost -U sa -P YourPassword123! -Q 'SELECT 1' || exit 1"
          --health-interval=10s
          --health-timeout=5s
          --health-retries=5
        ports:
          - 1433:1433

    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: maven

      - name: Run Unit Tests
        run: ./mvnw test

      - name: Code Coverage
        run: ./mvnw test jacoco:report

      - name: Mutation Tests
        run: ./mvnw org.pitest:pitest-maven:mutationCoverage

      - name: Upload Coverage
        uses: codecov/codecov-action@v3
```

---

## Next Steps

1. **Run Local Tests:** `./mvnw test`
2. **Start Service:** `./mvnw spring-boot:run`
3. **Run Test Harness:** Use provided `HarnessRunner` class
4. **Review Reports:** Check `target/site/jacoco/` for coverage
5. **Analyze Metrics:** Use `/actuator/metrics` for runtime metrics
6. **Iterate:** Adjust cache size, thread count based on findings
