package com.td.enrich.performance;

import com.td.enrich.domain.EnrichmentRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Factory that generates realistic but randomized enrichment requests for performance testing.
 *
 * <p>All methods are stateless and safe to call concurrently from many threads simultaneously.
 * {@link ThreadLocalRandom} is used instead of a shared {@code Random} instance to avoid
 * contention between threads (each thread has its own random number generator).
 *
 * <p>The data arrays (descriptions, merchant names, amounts) are intentionally diverse to
 * exercise the full merchant cache key space — a test that only sends "STARBUCKS" would
 * just measure cache hit performance and wouldn't exercise the Plaid API path.
 */
final class PerformanceTestDataFactory {

    /** 10 distinct bank account IDs to distribute requests across multiple accounts. */
    private static final String[] ACCOUNT_IDS = {
        "acc_001a2b3c", "acc_002d3e4f", "acc_003g4h5i", "acc_004j5k6l", "acc_005m6n7o",
        "acc_006p7q8r", "acc_007s8t9u", "acc_008v9w0x", "acc_009y0z1a", "acc_010b1c2d"
    };

    /**
     * Raw transaction descriptions as they would appear in a bank statement.
     * The index of each description matches the corresponding merchant name below.
     */
    private static final String[] DESCRIPTIONS = {
        "STARBUCKS COFFEE #1234", "AMAZON.COM AMZN.COM/BILL", "WHOLE FOODS MARKET #0581",
        "NETFLIX.COM", "UBER EATS ORDER #9847", "SHELL OIL 12345678",
        "WALMART SUPERCENTER #2345", "TARGET STORE #0124", "MCDONALDS F12345678",
        "SPOTIFY USA", "DELTA AIR LINES TICKET", "MARRIOTT HOTELS",
        "BEST BUY STORES #0452", "CVS PHARMACY #9012", "THE HOME DEPOT #1234",
        "CHIPOTLE ONLINE", "APPLE.COM/BILL", "GOOGLE *GSUITE_MONTHLY",
        "CHEWY.COM", "AMERICAN AIRLINES TICKET"
    };

    /**
     * Human-readable merchant names corresponding to {@link #DESCRIPTIONS}.
     * The shared index ensures description[i] and merchantName[i] refer to the same merchant.
     */
    private static final String[] MERCHANT_NAMES = {
        "Starbucks", "Amazon", "Whole Foods Market", "Netflix", "Uber Eats",
        "Shell", "Walmart", "Target", "McDonald's", "Spotify",
        "Delta Air Lines", "Marriott Hotels", "Best Buy", "CVS Pharmacy", "Home Depot",
        "Chipotle", "Apple", "Google", "Chewy", "American Airlines"
    };

    /** Realistic transaction amounts covering small (coffee) to large (travel) purchases. */
    private static final BigDecimal[] AMOUNTS = {
        new BigDecimal("5.75"), new BigDecimal("42.99"), new BigDecimal("156.30"),
        new BigDecimal("14.99"), new BigDecimal("28.40"), new BigDecimal("65.00"),
        new BigDecimal("123.45"), new BigDecimal("9.99"), new BigDecimal("234.50"),
        new BigDecimal("18.75"), new BigDecimal("450.00"), new BigDecimal("7.25"),
        new BigDecimal("88.00"), new BigDecimal("33.15"), new BigDecimal("199.99"),
        new BigDecimal("12.50"), new BigDecimal("74.20"), new BigDecimal("22.80"),
        new BigDecimal("316.00"), new BigDecimal("55.40")
    };

    /** Utility class — no instances needed. */
    private PerformanceTestDataFactory() {}

    /**
     * Creates a request containing a single randomly chosen transaction.
     *
     * <p>Designed for single-enrichment endpoint load testing where each HTTP request
     * carries exactly one transaction. The account ID, description, amount, and date
     * are all randomized independently.
     *
     * @return a valid {@link EnrichmentRequest} with one transaction
     */
    static EnrichmentRequest createRandomSingleRequest() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        // Pick a random merchant index; description and merchantName share the same index
        // so the pair is always coherent (Starbucks description → Starbucks merchant name)
        int idx = rng.nextInt(DESCRIPTIONS.length);
        return new EnrichmentRequest(
            ACCOUNT_IDS[rng.nextInt(ACCOUNT_IDS.length)],
            List.of(new EnrichmentRequest.Transaction(
                DESCRIPTIONS[idx],
                AMOUNTS[rng.nextInt(AMOUNTS.length)],
                // Spread transaction dates over the past 90 days for realism
                LocalDate.now().minusDays(rng.nextInt(90)),
                MERCHANT_NAMES[idx]
            ))
        );
    }

    /**
     * Creates a request containing 2–5 randomly chosen transactions.
     *
     * <p>Useful for testing the per-request batch enrichment path (a single
     * {@code POST /enrich} with multiple transactions in the body).
     *
     * @return a valid {@link EnrichmentRequest} with 2–5 transactions
     */
    static EnrichmentRequest createRandomMultiRequest() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int txnCount = rng.nextInt(2, 6); // 2 to 5 transactions per request
        List<EnrichmentRequest.Transaction> txns = new ArrayList<>(txnCount);
        for (int i = 0; i < txnCount; i++) {
            int idx = rng.nextInt(DESCRIPTIONS.length);
            txns.add(new EnrichmentRequest.Transaction(
                DESCRIPTIONS[idx],
                AMOUNTS[rng.nextInt(AMOUNTS.length)],
                LocalDate.now().minusDays(rng.nextInt(90)),
                MERCHANT_NAMES[idx]
            ));
        }
        return new EnrichmentRequest(
            ACCOUNT_IDS[rng.nextInt(ACCOUNT_IDS.length)],
            txns
        );
    }
}
