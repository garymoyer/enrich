package com.td.enrich.test;

import com.td.enrich.domain.PlaidEnrichResponse;
import java.util.*;

/**
 * Generates randomized Plaid API responses to cover various scenarios:
 * - Success cases with full merchant data
 * - Partial data (missing optional fields like logo, website)
 * - Empty responses (no enrichment data available)
 * - Multiple enriched transactions
 * - Edge cases (special characters, international data)
 */
public class MockPlaidResponseGenerator {

    private static final Random RANDOM = new Random(42);

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
            "JPMorgan Chase Bank NA", "6011", null,
            "www.chase.com"))
    );

    /**
     * Generates a randomized mock Plaid response based on the merchant name.
     * Covers various scenarios: success, partial data, no data available.
     */
    public static PlaidEnrichResponse generateMockResponse(String merchantName) {
        return generateMockResponse(merchantName, RANDOM.nextInt(100));
    }

    /**
     * Generates response with configurable success rate scenario.
     * 
     * @param merchantName The merchant to enrich
     * @param scenarioSeed 0-99: determines success/partial/failure scenario
     */
    public static PlaidEnrichResponse generateMockResponse(String merchantName, int scenarioSeed) {
        // Scenarios:
        // 0-70:   Success with full data
        // 71-85:  Success with partial data
        // 86-99:  No enrichment available
        
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
     */
    private static PlaidEnrichResponse generateSuccessResponse(String merchantName) {
        MerchantMetadata meta = METADATA.getOrDefault(merchantName, generateDefaultMetadata(merchantName));
        
        PlaidEnrichResponse.PlaidEnrichedTransaction transaction = new PlaidEnrichResponse.PlaidEnrichedTransaction(
            UUID.randomUUID().toString(),                      // id
            "transactions",                                    // category
            meta.merchantCategoryCode,                         // categoryId (MCC)
            meta.merchantName,                                 // merchantName
            meta.logo,                                         // logoUrl
            meta.website,                                      // website
            "MEDIUM",                                          // confidenceLevel
            Map.of("enriched_at", System.currentTimeMillis())  // enrichmentMetadata
        );
        
        return new PlaidEnrichResponse(Collections.singletonList(transaction), UUID.randomUUID().toString());
    }

    /**
     * Generates response with some missing fields (realistic scenario).
     */
    private static PlaidEnrichResponse generatePartialDataResponse(String merchantName) {
        MerchantMetadata meta = METADATA.getOrDefault(merchantName, generateDefaultMetadata(merchantName));
        
        // Randomly omit optional fields
        String logo = RANDOM.nextBoolean() ? meta.logo : null;
        String website = RANDOM.nextBoolean() ? meta.website : null;
        
        PlaidEnrichResponse.PlaidEnrichedTransaction transaction = new PlaidEnrichResponse.PlaidEnrichedTransaction(
            UUID.randomUUID().toString(),
            "transactions",
            meta.merchantCategoryCode,
            meta.merchantName,
            logo,       // May be null
            website,    // May be null
            "LOW",      // Lower confidence with partial data
            Map.of("enriched_at", System.currentTimeMillis())
        );
        
        return new PlaidEnrichResponse(Collections.singletonList(transaction), UUID.randomUUID().toString());
    }

    /**
     * Generates empty response (no enrichment available for this merchant).
     */
    private static PlaidEnrichResponse generateEmptyResponse() {
        return new PlaidEnrichResponse(Collections.emptyList(), UUID.randomUUID().toString());
    }

    /**
     * Generates a response with multiple enriched transactions (rare but possible).
     */
    public static PlaidEnrichResponse generateMultipleTransactionsResponse(String merchantName, int count) {
        List<PlaidEnrichResponse.PlaidEnrichedTransaction> transactions = new ArrayList<>();
        
        for (int i = 0; i < count; i++) {
            MerchantMetadata meta = METADATA.getOrDefault(merchantName, generateDefaultMetadata(merchantName));
            
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
     * Generates response with international merchant data.
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
     * Generates response with special characters and edge cases.
     */
    public static PlaidEnrichResponse generateEdgeCaseResponse(String merchantName) {
        PlaidEnrichResponse.PlaidEnrichedTransaction transaction = new PlaidEnrichResponse.PlaidEnrichedTransaction(
            UUID.randomUUID().toString(),
            "transactions",
            "5812",
            merchantName + "'s Café & Bar - Zürich",
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

    // Helper class for merchant metadata
    private static class MerchantMetadata {
        final String merchantName;
        final String merchantCategoryCode;
        final String logo;
        final String website;

        MerchantMetadata(String merchantName, String merchantCategoryCode, String logo, String website) {
            this.merchantName = merchantName;
            this.merchantCategoryCode = merchantCategoryCode;
            this.logo = logo;
            this.website = website;
        }
    }
}
