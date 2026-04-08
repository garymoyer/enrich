package com.td.enrich.test;

import com.td.enrich.domain.PlaidEnrichResponse;
import java.util.*;

/**
 * Generates mock Plaid API responses for use in test harness scenarios.
 *
 * <p>This class does NOT contact the real Plaid API. Instead it returns pre-built
 * {@link PlaidEnrichResponse} objects that mimic the structure of a real Plaid response.
 * It is used by {@link EnrichmentTestHarness} and related manual test utilities —
 * NOT by automated unit or integration tests (those use WireMock or Mockito).
 *
 * <p><b>Scenarios available:</b>
 * <ul>
 *   <li><b>70% probability — success with full data:</b> merchant name, category, logo, website.</li>
 *   <li><b>15% probability — partial data:</b> logo and/or website may be randomly absent
 *       (realistic because not every merchant has a Clearbit logo URL).</li>
 *   <li><b>15% probability — no enrichment:</b> Plaid returns an empty transaction list
 *       (happens for ATM withdrawals, obscure merchants, etc.).</li>
 * </ul>
 *
 * <p>The seed ({@code new Random(42)}) is fixed so test runs are reproducible —
 * the same merchant name will get the same scenario on every run.
 */
public class MockPlaidResponseGenerator {

    /** Fixed seed for reproducibility — same input always produces the same response. */
    private static final Random RANDOM = new Random(42);

    /**
     * Known merchant metadata keyed by the UPPER-CASE merchant name.
     * Merchants not in this map fall through to {@link #generateDefaultMetadata}.
     */
    private static final Map<String, MerchantMetadata> METADATA = Map.ofEntries(
        new AbstractMap.SimpleEntry<>("STARBUCKS", new MerchantMetadata(
            "Starbucks Coffee Company", "5812", "https://logo.com/starbucks.png",
            "www.starbucks.com")),
        new AbstractMap.SimpleEntry<>("AMAZON", new MerchantMetadata(
            "Amazon.com Inc", "5961", "https://logo.com/amazon.png",
            "www.amazon.com")),
        new AbstractMap.SimpleEntry<>("UBER", new MerchantMetadata(
            "Uber Technologies Inc", "4121", "https://logo.com/uber.png",
            "www.uber.com")),
        new AbstractMap.SimpleEntry<>("MCDONALDS", new MerchantMetadata(
            "McDonald's Corporation", "5812", "https://logo.com/mcdonalds.png",
            "www.mcdonalds.com")),
        new AbstractMap.SimpleEntry<>("WHOLE FOODS", new MerchantMetadata(
            "Whole Foods Market Inc", "5411", "https://logo.com/wholefoods.png",
            "www.wholefoodsmarket.com")),
        new AbstractMap.SimpleEntry<>("NETFLIX", new MerchantMetadata(
            "Netflix Inc", "4899", "https://logo.com/netflix.png",
            "www.netflix.com")),
        new AbstractMap.SimpleEntry<>("NIKE", new MerchantMetadata(
            "Nike Inc", "5661", "https://logo.com/nike.png",
            "www.nike.com")),
        new AbstractMap.SimpleEntry<>("CHASE BANK", new MerchantMetadata(
            "JPMorgan Chase Bank NA", "6011", null,  // no logo — deliberate null test case
            "www.chase.com"))
    );

    /**
     * Generates a randomized mock Plaid response using a random scenario seed.
     *
     * @param merchantName the merchant to look up in the pre-defined metadata map
     * @return a mock response with full, partial, or no enrichment data
     */
    public static PlaidEnrichResponse generateMockResponse(String merchantName) {
        return generateMockResponse(merchantName, RANDOM.nextInt(100));
    }

    /**
     * Generates a mock Plaid response using a deterministic scenario seed.
     *
     * <p>The seed controls which scenario fires:
     * <ul>
     *   <li>0–70: success with full merchant data</li>
     *   <li>71–85: partial data (logo or website may be null)</li>
     *   <li>86–99: no enrichment available (empty response)</li>
     * </ul>
     *
     * @param merchantName  the merchant to enrich
     * @param scenarioSeed  a value 0–99 that determines the scenario
     * @return the generated mock response
     */
    public static PlaidEnrichResponse generateMockResponse(String merchantName, int scenarioSeed) {
        if (scenarioSeed <= 70) {
            return generateSuccessResponse(merchantName);
        } else if (scenarioSeed <= 85) {
            return generatePartialDataResponse(merchantName);
        } else {
            return generateEmptyResponse();
        }
    }

    /**
     * Generates a successful response with complete merchant data.
     *
     * <p>Falls back to {@link #generateDefaultMetadata} for merchants not in the
     * {@link #METADATA} map.
     */
    private static PlaidEnrichResponse generateSuccessResponse(String merchantName) {
        MerchantMetadata meta = METADATA.getOrDefault(merchantName, generateDefaultMetadata(merchantName));

        PlaidEnrichResponse.PlaidEnrichedTransaction transaction = new PlaidEnrichResponse.PlaidEnrichedTransaction(
            UUID.randomUUID().toString(),
            "transactions",
            meta.merchantCategoryCode,
            meta.merchantName,
            meta.logo,
            meta.website,
            "MEDIUM",
            Map.of("enriched_at", System.currentTimeMillis())
        );

        return new PlaidEnrichResponse(Collections.singletonList(transaction), UUID.randomUUID().toString());
    }

    /**
     * Generates a response with some fields randomly absent.
     *
     * <p>Simulates merchants where Plaid has a name and category but no logo or website.
     * This is a realistic scenario that the service must handle gracefully (null logo
     * and website are valid Plaid response fields).
     */
    private static PlaidEnrichResponse generatePartialDataResponse(String merchantName) {
        MerchantMetadata meta = METADATA.getOrDefault(merchantName, generateDefaultMetadata(merchantName));

        // Randomly omit optional fields — each has ~50% chance of being null
        String logo = RANDOM.nextBoolean() ? meta.logo : null;
        String website = RANDOM.nextBoolean() ? meta.website : null;

        PlaidEnrichResponse.PlaidEnrichedTransaction transaction = new PlaidEnrichResponse.PlaidEnrichedTransaction(
            UUID.randomUUID().toString(),
            "transactions",
            meta.merchantCategoryCode,
            meta.merchantName,
            logo,    // may be null
            website, // may be null
            "LOW",   // lower confidence when partial data
            Map.of("enriched_at", System.currentTimeMillis())
        );

        return new PlaidEnrichResponse(Collections.singletonList(transaction), UUID.randomUUID().toString());
    }

    /**
     * Generates an empty response — no enrichment data available for this merchant.
     *
     * <p>This happens for ATM withdrawals, obscure local merchants, or transactions
     * that Plaid cannot categorize. The service must return an empty enriched list
     * for this case rather than failing.
     */
    private static PlaidEnrichResponse generateEmptyResponse() {
        return new PlaidEnrichResponse(Collections.emptyList(), UUID.randomUUID().toString());
    }

    /**
     * Generates a response with multiple enriched transactions for the same merchant.
     *
     * <p>This is a rare but valid Plaid response shape. The service must handle it
     * without assuming exactly one transaction per response.
     *
     * @param merchantName the merchant name for all transactions
     * @param count        how many enriched transactions to include
     */
    public static PlaidEnrichResponse generateMultipleTransactionsResponse(String merchantName, int count) {
        List<PlaidEnrichResponse.PlaidEnrichedTransaction> transactions = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            MerchantMetadata meta = METADATA.getOrDefault(merchantName, generateDefaultMetadata(merchantName));
            // Append " #N" to the name for the 2nd and later transactions to distinguish them
            transactions.add(new PlaidEnrichResponse.PlaidEnrichedTransaction(
                UUID.randomUUID().toString(),
                "transactions",
                meta.merchantCategoryCode,
                meta.merchantName + (i > 0 ? " #" + (i + 1) : ""),
                meta.logo,
                meta.website,
                "MEDIUM",
                Map.of("enriched_at", System.currentTimeMillis())
            ));
        }

        return new PlaidEnrichResponse(transactions, UUID.randomUUID().toString());
    }

    /**
     * Generates a response with international merchant data including a country field.
     *
     * @param merchantName the base merchant name
     * @param country      the country code or name to append (e.g. "CA" for Canada)
     */
    public static PlaidEnrichResponse generateInternationalResponse(String merchantName, String country) {
        MerchantMetadata meta = METADATA.getOrDefault(merchantName, generateDefaultMetadata(merchantName));

        PlaidEnrichResponse.PlaidEnrichedTransaction transaction = new PlaidEnrichResponse.PlaidEnrichedTransaction(
            UUID.randomUUID().toString(),
            "transactions",
            meta.merchantCategoryCode,
            meta.merchantName + " (" + country + ")",
            meta.logo,
            meta.website,
            "MEDIUM",
            Map.of(
                "enriched_at", System.currentTimeMillis(),
                "country", country
            )
        );

        return new PlaidEnrichResponse(Collections.singletonList(transaction), UUID.randomUUID().toString());
    }

    /**
     * Generates a response with special characters in the merchant name.
     *
     * <p>Tests that the service handles UTF-8 characters (accented letters, ampersands,
     * apostrophes) without corrupting the stored JSON or producing a serialization error.
     *
     * @param merchantName the base name to augment with special characters
     */
    public static PlaidEnrichResponse generateEdgeCaseResponse(String merchantName) {
        PlaidEnrichResponse.PlaidEnrichedTransaction transaction = new PlaidEnrichResponse.PlaidEnrichedTransaction(
            UUID.randomUUID().toString(),
            "transactions",
            "5812",
            merchantName + "'s Café & Bar - Zürich", // apostrophe, accented é, umlaut ü, ampersand
            null,
            "www.example.com",
            "HIGH",
            Map.of(
                "enriched_at", System.currentTimeMillis(),
                "special_chars", true
            )
        );

        return new PlaidEnrichResponse(Collections.singletonList(transaction), UUID.randomUUID().toString());
    }

    /**
     * Generates default metadata for a merchant not found in the {@link #METADATA} map.
     *
     * <p>Picks a random merchant category code and derives a logo URL and website
     * from the merchant name by lowercasing and removing spaces.
     */
    private static MerchantMetadata generateDefaultMetadata(String merchantName) {
        String[] categories = {
            "5812", // Restaurants
            "5411", // Grocery stores
            "4899", // Other services
            "5661", // Shoe stores
            "6211"  // Security brokers
        };

        String category = categories[RANDOM.nextInt(categories.length)];
        String officialName = capitalizeWords(merchantName);

        return new MerchantMetadata(
            officialName,
            category,
            "https://logo.com/" + merchantName.toLowerCase().replace(" ", "-") + ".png",
            "www." + merchantName.toLowerCase().replace(" ", "") + ".com"
        );
    }

    /**
     * Capitalizes the first letter of each word and lowercases the rest.
     * Example: {@code "WHOLE FOODS"} → {@code "Whole Foods"}.
     */
    private static String capitalizeWords(String input) {
        String[] words = input.split(" ");
        StringBuilder result = new StringBuilder();

        for (String word : words) {
            if (result.length() > 0) result.append(" ");
            result.append(word.substring(0, 1).toUpperCase())
                  .append(word.substring(1).toLowerCase());
        }

        return result.toString();
    }

    /**
     * Internal holder for known merchant metadata.
     * Not exposed outside this class — callers get {@link PlaidEnrichResponse} objects.
     */
    private static class MerchantMetadata {
        final String merchantName;
        final String merchantCategoryCode;
        final String logo;     // may be null for some merchants (e.g. banks)
        final String website;

        MerchantMetadata(String merchantName, String merchantCategoryCode, String logo, String website) {
            this.merchantName = merchantName;
            this.merchantCategoryCode = merchantCategoryCode;
            this.logo = logo;
            this.website = website;
        }
    }
}
