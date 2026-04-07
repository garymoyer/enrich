# TD Enrich Service - Client Integration Guide

## Quick Start

### 1. Service Endpoint

```
Base URL: http://localhost:8080/api/v1/enrich
```

### 2. Authentication (Future)

Currently, the service is unsecured. Authentication will be added via OAuth 2.0 + Spring Security in a future release.

---

## API Endpoints

### Single Transaction Enrichment (Synchronous)

Enrich a single transaction and receive the result immediately.

**Endpoint:** `POST /api/v1/enrich/single`

**Request:**
```json
{
  "description": "STARBUCKS #1234 NEW YORK NY",
  "merchantName": "STARBUCKS",
  "amount": 5.99
}
```

**Response (Success - 200 OK):**
```json
{
  "guid": "550e8400-e29b-41d4-a716-446655440000",
  "description": "STARBUCKS #1234 NEW YORK NY",
  "merchantName": "STARBUCKS",
  "amount": 5.99,
  "plaidResponse": {
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
  },
  "createdAt": "2024-01-15T10:30:45.123Z"
}
```

**Response (Cache Hit):**
- Response time: <5ms
- Plaid response comes from in-memory or database cache
- Same successful 200 OK response

**Response (Plaid API Error):**
```json
{
  "timestamp": "2024-01-15T10:30:45.123Z",
  "status": 502,
  "error": "Bad Gateway",
  "message": "Plaid API returned an error: INVALID_REQUEST",
  "path": "/api/v1/enrich/single"
}
```

**cURL Example:**
```bash
curl -X POST http://localhost:8080/api/v1/enrich/single \
  -H "Content-Type: application/json" \
  -d '{
    "description": "SHELL OIL CENTER HOUSTON TX",
    "merchantName": "SHELL",
    "amount": 45.00
  }'
```

**Java Client Example:**
```java
RestTemplate restTemplate = new RestTemplate();

EnrichmentRequest request = new EnrichmentRequest(
    "SHELL OIL CENTER HOUSTON TX",
    "SHELL",
    new BigDecimal("45.00")
);

ResponseEntity<EnrichmentResponse> response = restTemplate.postForEntity(
    "http://localhost:8080/api/v1/enrich/single",
    request,
    EnrichmentResponse.class
);

EnrichmentResponse enriched = response.getBody();
System.out.println("Merchant: " + enriched.plaidResponse().enrichedTransactions().get(0).merchantName());
```

**Python Client Example:**
```python
import requests

request_data = {
    "description": "AMAZON.COM SEATTLE WA",
    "merchantName": "AMAZON",
    "amount": 29.99
}

response = requests.post(
    "http://localhost:8080/api/v1/enrich/single",
    json=request_data
)

if response.status_code == 200:
    result = response.json()
    print(f"Merchant: {result['plaidResponse']['enrichedTransactions'][0]['merchantName']}")
else:
    print(f"Error: {response.status_code} - {response.text}")
```

---

### Batch Enrichment (Asynchronous - Fire and Forget)

Submit multiple transactions for asynchronous enrichment. Useful for high-volume processing.

**Endpoint:** `POST /api/v1/enrich/batch`

**Request:**
```json
{
  "transactions": [
    {
      "description": "STARBUCKS #1234 NEW YORK NY",
      "merchantName": "STARBUCKS",
      "amount": 5.99
    },
    {
      "description": "UBER TRIP SAN FRANCISCO CA",
      "merchantName": "UBER",
      "amount": 22.50
    },
    {
      "description": "WHOLE FOODS #123 BROOKLYN NY",
      "merchantName": "WHOLE FOODS",
      "amount": 87.42
    }
  ]
}
```

**Response (202 Accepted):**
```json
{
  "requestCount": 3,
  "guids": [
    "550e8400-e29b-41d4-a716-446655440000",
    "550e8400-e29b-41d4-a716-446655440001",
    "550e8400-e29b-41d4-a716-446655440002"
  ],
  "message": "3 transactions enqueued for asynchronous enrichment"
}
```

**Processing:**
- Returns immediately with 202 Accepted
- Tasks are queued for background processing
- Use the returned GUIDs to poll for results (see below)
- Processing rate depends on Plaid API availability and queue size

**cURL Example:**
```bash
curl -X POST http://localhost:8080/api/v1/enrich/batch \
  -H "Content-Type: application/json" \
  -d '{
    "transactions": [
      {
        "description": "SHELL OIL CENTER HOUSTON TX",
        "merchantName": "SHELL",
        "amount": 45.00
      },
      {
        "description": "EXXON #5432 DALLAS TX",
        "merchantName": "EXXON",
        "amount": 60.00
      }
    ]
  }'
```

---

### Retrieve Enriched Result by GUID

Get the enriched result for a previously submitted transaction.

**Endpoint:** `GET /api/v1/enrich/{guid}`

**Parameters:**
- `guid` (path) - UUID returned from single or batch enrichment endpoints

**Response (200 OK - Completed):**
```json
{
  "guid": "550e8400-e29b-41d4-a716-446655440000",
  "description": "STARBUCKS #1234 NEW YORK NY",
  "merchantName": "STARBUCKS",
  "amount": 5.99,
  "plaidResponse": {
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
  },
  "createdAt": "2024-01-15T10:30:45.123Z"
}
```

**Response (202 Accepted - Still Processing):**
```json
{
  "guid": "550e8400-e29b-41d4-a716-446655440002",
  "description": "WHOLE FOODS #123 BROOKLYN NY",
  "merchantName": "WHOLE FOODS",
  "amount": 87.42,
  "plaidResponse": null,
  "createdAt": "2024-01-15T10:30:50.000Z"
}
```

**Response (404 Not Found):**
```json
{
  "timestamp": "2024-01-15T10:35:00.000Z",
  "status": 404,
  "error": "Not Found",
  "message": "No enrichment result found for GUID: invalid-guid",
  "path": "/api/v1/enrich/invalid-guid"
}
```

**cURL Example:**
```bash
curl http://localhost:8080/api/v1/enrich/550e8400-e29b-41d4-a716-446655440000
```

**Polling Pattern (JavaScript):**
```javascript
async function pollForResult(guid, maxAttempts = 30, delayMs = 1000) {
  for (let i = 0; i < maxAttempts; i++) {
    const response = await fetch(`http://localhost:8080/api/v1/enrich/${guid}`);
    
    if (response.status === 200) {
      return await response.json(); // Completed
    } else if (response.status === 202) {
      console.log(`Still processing... (attempt ${i + 1}/${maxAttempts})`);
      await new Promise(resolve => setTimeout(resolve, delayMs));
    } else {
      throw new Error(`Request failed: ${response.status}`);
    }
  }
  throw new Error('Polling timeout');
}

// Usage
const guid = "550e8400-e29b-41d4-a716-446655440002";
pollForResult(guid).then(result => {
  console.log("Merchant:", result.plaidResponse.enrichedTransactions[0].merchantName);
});
```

---

## Field Reference

### EnrichmentRequest

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `description` | String | ✓ | Transaction description (e.g., "STARBUCKS #1234 NEW YORK NY") |
| `merchantName` | String | ✓ | Merchant name (e.g., "STARBUCKS") |
| `amount` | BigDecimal | ✓ | Transaction amount (e.g., 5.99) |

### EnrichmentResponse

| Field | Type | Description |
|-------|------|-------------|
| `guid` | String (UUID) | Unique identifier for this enrichment request |
| `description` | String | Echo of request description |
| `merchantName` | String | Echo of request merchant name |
| `amount` | BigDecimal | Echo of request amount |
| `plaidResponse` | PlaidEnrichResponse | Enrichment data from Plaid API (null if still processing) |
| `createdAt` | ISO 8601 DateTime | When this enrichment was created |

### PlaidEnrichedTransaction

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `merchantName` | String | No | Official merchant name (e.g., "Starbucks Coffee Company") |
| `merchantCategoryCode` | String | Yes | MCC code (e.g., "5812" for restaurants) |
| `logo` | String (URL) | Yes | Merchant logo image URL |
| `website` | String (URL) | Yes | Merchant website URL |
| `phoneNumber` | String | Yes | Merchant phone number |
| `address` | Address | Yes | Physical location details |

### Address

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `street` | String | Yes | Street address |
| `city` | String | Yes | City name |
| `region` | String (2-letter code) | Yes | State/province code (e.g., "NY") |
| `postalCode` | String | Yes | Zip/postal code |
| `country` | String (2-letter code) | Yes | Country code (e.g., "US") |

---

## Error Handling

### HTTP Status Codes

| Code | Scenario | Action |
|------|----------|--------|
| 200 | Success | Process response immediately |
| 202 | Still processing (batch) | Poll again after delay |
| 400 | Bad Request (validation error) | Fix request body and retry |
| 404 | Not Found | GUID doesn't exist; check GUID |
| 409 | Conflict (duplicate key) | This merchant already cached; retry batch |
| 502 | Bad Gateway (Plaid error) | Plaid API error; circuit breaker may be open |
| 500 | Internal Server Error | Unexpected error; check logs and retry |

### Error Response Format

```json
{
  "timestamp": "2024-01-15T10:35:00.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid request: amount must be positive",
  "path": "/api/v1/enrich/single"
}
```

### Handling Plaid API Failures

The service includes automatic retry logic with exponential backoff:
- **1st attempt:** Immediate
- **2nd attempt:** ~1 second delay
- **3rd attempt:** ~2 second delay

If all 3 attempts fail, you'll receive a 502 Bad Gateway response with Plaid's error message.

**Recovery Strategy:**
1. Catch 502 errors in client code
2. Implement exponential backoff on client side (start at 5 seconds)
3. Retry up to 3-5 times
4. Log/alert if all retries fail
5. Consider caching merchant info locally to prevent enrichment requests for known merchants

---

## Performance Considerations

### Response Times

| Scenario | Typical Time | Range |
|----------|--------------|-------|
| Cache hit (in-memory) | <5ms | 1-10ms |
| Cache hit (database) | 10-50ms | 10-100ms |
| Plaid API call (success) | 100-500ms | 50ms-1s |
| With retries (Plaid slow) | 500-3000ms | 100ms-5s |
| Circuit breaker open | <5ms | <5ms (fast fail) |

### Optimization Tips

1. **Batch Large Volumes:**
   - Use `/batch` endpoint for >10 transactions
   - Increases throughput via async processing
   - Typical throughput: 100+ transactions/second

2. **Implement Client-Side Caching:**
   ```java
   Cache<String, PlaidResponse> localCache = ...;
   
   String key = merchantName + ":" + description;
   if (localCache.has(key)) {
       return localCache.get(key); // Skip API call
   }
   
   PlaidResponse response = enrichService.enrich(...);
   localCache.put(key, response);
   return response;
   ```

3. **Leverage Merchant Name Consistency:**
   - Standardize merchant names across your system
   - Higher cache hit ratio
   - Reduced Plaid API calls

4. **Implement Polling with Exponential Backoff:**
   ```javascript
   // Don't hammer the API
   let backoffMs = 1000;
   for (let i = 0; i < maxAttempts; i++) {
     const result = await pollOnce(guid);
     if (result.status === 200) return result;
     
     await wait(backoffMs);
     backoffMs = Math.min(backoffMs * 1.5, 30000); // Cap at 30s
   }
   ```

### Circuit Breaker Behavior

When Plaid API experiences issues:

1. **Normal Operation:** Service forwards all requests to Plaid
2. **High Error Rate (>50% failures):** Circuit breaker opens
3. **Circuit Open:** New requests fail immediately (fast-fail) with 502
4. **Half-Open (After 10s):** Service tries 3 test requests
5. **Recovery:** If 3 half-open requests succeed, circuit closes

**Check Circuit Status:**
```bash
curl http://localhost:8080/actuator/health/circuitbreakers
```

---

## Integration Examples

### Spring Boot REST Template

```java
@Service
public class PlaidEnrichmentClient {
    private final RestTemplate restTemplate = new RestTemplate();
    
    public EnrichmentResponse enrich(String description, String merchantName, BigDecimal amount) {
        var request = new EnrichmentRequest(description, merchantName, amount);
        
        var response = restTemplate.postForEntity(
            "http://localhost:8080/api/v1/enrich/single",
            request,
            EnrichmentResponse.class
        );
        
        return response.getBody();
    }
}
```

### Async with Batch Endpoint

```java
@Service
public class BulkEnrichmentClient {
    private final RestTemplate restTemplate = new RestTemplate();
    
    public List<String> enrichBatch(List<EnrichmentRequest> requests) {
        var batchRequest = new BatchEnrichmentRequest(requests);
        
        var response = restTemplate.postForEntity(
            "http://localhost:8080/api/v1/enrich/batch",
            batchRequest,
            BatchEnrichmentResponse.class
        );
        
        return response.getBody().guids();
    }
    
    public EnrichmentResponse getResult(String guid) {
        return restTemplate.getForObject(
            "http://localhost:8080/api/v1/enrich/" + guid,
            EnrichmentResponse.class
        );
    }
}
```

### Async Processing with CompletableFuture

```java
public CompletableFuture<EnrichmentResponse> enrichAsync(String guid) {
    return CompletableFuture.supplyAsync(() -> {
        long startTime = System.currentTimeMillis();
        
        while (true) {
            EnrichmentResponse response = getResult(guid);
            
            if (response.plaidResponse() != null) {
                return response; // Done
            }
            
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed > 30000) {
                throw new TimeoutException("Enrichment took too long");
            }
            
            try {
                Thread.sleep(1000); // Wait before polling again
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    });
}
```

---

## Monitoring & Debugging

### Health Checks

```bash
# Overall health
curl http://localhost:8080/actuator/health

# Database connectivity
curl http://localhost:8080/actuator/health/db

# Circuit breaker status
curl http://localhost:8080/actuator/health/circuitbreakers

# Readiness (dependencies)
curl http://localhost:8080/actuator/health/readiness

# Liveness (is it still running)
curl http://localhost:8080/actuator/health/liveness
```

### Metrics

```bash
# All metrics
curl http://localhost:8080/actuator/metrics

# Prometheus format
curl http://localhost:8080/actuator/prometheus

# Circuit breaker metrics
curl http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.calls
```

### Logs

Check logs to understand enrichment flow:

```bash
# Development
tail -f target/logs/enrichment.log

# Production (Azure)
az webapp log tail --resource-group <group> --name <app>
```

Sample log lines:
```
2024-01-15T10:30:45.123 INFO  Starting enrichment for request: 550e8400-e29b-41d4-a716-446655440000
2024-01-15T10:30:45.125 DEBUG Cache lookup: key=STARBUCKS|STARBUCKS #1234 NEW YORK NY
2024-01-15T10:30:45.126 DEBUG Cache HIT for merchant: STARBUCKS
2024-01-15T10:30:45.127 INFO  Successfully enriched request: 550e8400-e29b-41d4-a716-446655440000
```

---

## Frequently Asked Questions

**Q: What if I send the same transaction twice?**  
A: If both requests reach the service within a short timeframe, you'll get cached results. For identical (description, merchantName) pairs, the second request hits the cache and returns <5ms.

**Q: Can I use the batch endpoint for 1 transaction?**  
A: Yes, but the single endpoint is optimized for synchronous processing. Use batch for 10+.

**Q: How do I know if my transaction is still processing?**  
A: Call GET /api/v1/enrich/{guid}. If plaidResponse is null, it's still processing (202). When filled, enrichment is complete (200).

**Q: What happens if Plaid API is down?**  
A: The service implements circuit breaker: after 50% failure rate, requests fail fast with 502 Bad Gateway. Once Plaid recovers (10s window), the circuit opens and service recovers automatically.

**Q: Can I increase the cache size?**  
A: Yes, set `enrich.cache.max-size` in application.yml (default: 1000). Larger caches = better hit ratio but more memory.

**Q: How is data persisted?**  
A: All enrichment requests and responses are logged to the database for audit trails. Merchant cache (in-memory + database) survives restarts.

**Q: Is the service thread-safe?**  
A: Yes, fully thread-safe. The merchant cache uses synchronized access, and all repository operations are transactional.

---

## Support

For issues or questions:
1. Check logs: `/actuator/health` and service logs
2. Review this guide for common patterns
3. See [ARCHITECTURE.md](ARCHITECTURE.md) for internal design
4. File an issue with error message + GUID for reproducibility
