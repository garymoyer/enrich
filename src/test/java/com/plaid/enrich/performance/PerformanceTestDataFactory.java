package com.plaid.enrich.performance;

import com.plaid.enrich.domain.EnrichmentRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates diverse, realistic test data for performance testing.
 * All methods are stateless and safe to call from concurrent virtual threads.
 */
final class PerformanceTestDataFactory {

    private static final String[] ACCOUNT_IDS = {
        "acc_001a2b3c", "acc_002d3e4f", "acc_003g4h5i", "acc_004j5k6l", "acc_005m6n7o",
        "acc_006p7q8r", "acc_007s8t9u", "acc_008v9w0x", "acc_009y0z1a", "acc_010b1c2d"
    };

    private static final String[] DESCRIPTIONS = {
        "STARBUCKS COFFEE #1234", "AMAZON.COM AMZN.COM/BILL", "WHOLE FOODS MARKET #0581",
        "NETFLIX.COM", "UBER EATS ORDER #9847", "SHELL OIL 12345678",
        "WALMART SUPERCENTER #2345", "TARGET STORE #0124", "MCDONALDS F12345678",
        "SPOTIFY USA", "DELTA AIR LINES TICKET", "MARRIOTT HOTELS",
        "BEST BUY STORES #0452", "CVS PHARMACY #9012", "THE HOME DEPOT #1234",
        "CHIPOTLE ONLINE", "APPLE.COM/BILL", "GOOGLE *GSUITE_MONTHLY",
        "CHEWY.COM", "AMERICAN AIRLINES TICKET"
    };

    private static final String[] MERCHANT_NAMES = {
        "Starbucks", "Amazon", "Whole Foods Market", "Netflix", "Uber Eats",
        "Shell", "Walmart", "Target", "McDonald's", "Spotify",
        "Delta Air Lines", "Marriott Hotels", "Best Buy", "CVS Pharmacy", "Home Depot",
        "Chipotle", "Apple", "Google", "Chewy", "American Airlines"
    };

    private static final BigDecimal[] AMOUNTS = {
        new BigDecimal("5.75"), new BigDecimal("42.99"), new BigDecimal("156.30"),
        new BigDecimal("14.99"), new BigDecimal("28.40"), new BigDecimal("65.00"),
        new BigDecimal("123.45"), new BigDecimal("9.99"), new BigDecimal("234.50"),
        new BigDecimal("18.75"), new BigDecimal("450.00"), new BigDecimal("7.25"),
        new BigDecimal("88.00"), new BigDecimal("33.15"), new BigDecimal("199.99"),
        new BigDecimal("12.50"), new BigDecimal("74.20"), new BigDecimal("22.80"),
        new BigDecimal("316.00"), new BigDecimal("55.40")
    };

    private PerformanceTestDataFactory() {}

    /**
     * Creates a request with a single random transaction.
     * Designed for single-enrichment endpoint load testing.
     */
    static EnrichmentRequest createRandomSingleRequest() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int idx = rng.nextInt(DESCRIPTIONS.length);
        return new EnrichmentRequest(
            ACCOUNT_IDS[rng.nextInt(ACCOUNT_IDS.length)],
            List.of(new EnrichmentRequest.Transaction(
                DESCRIPTIONS[idx],
                AMOUNTS[rng.nextInt(AMOUNTS.length)],
                LocalDate.now().minusDays(rng.nextInt(90)),
                MERCHANT_NAMES[idx]
            ))
        );
    }

    /**
     * Creates a request with multiple random transactions (2-5).
     * Useful for testing the batch-like behavior of multi-transaction single requests.
     */
    static EnrichmentRequest createRandomMultiRequest() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int txnCount = rng.nextInt(2, 6);
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
