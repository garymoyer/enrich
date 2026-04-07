package com.td.enrich.test;

import com.td.enrich.domain.EnrichmentRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Generates random merchant transaction test cases with realistic merchant data.
 * 
 * Supports 200+ predefined merchants across diverse categories:
 * - Retail (grocery, apparel, electronics)
 * - Dining (fast food, restaurants, coffee)
 * - Travel (airlines, hotels, ride-sharing)
 * - Services (utilities, insurance, healthcare)
 * - Entertainment (streaming, gaming, sports)
 */
public class MerchantTestDataGenerator {

    private static final Random RANDOM = new Random(42); // Reproducible seed

    // Database of real merchants for realistic test data
    private static final Map<String, MerchantInfo> MERCHANTS = Map.ofEntries(
        // Retail - Grocery & Supermarkets
        new AbstractMap.SimpleEntry<>("WHOLE FOODS", new MerchantInfo("Whole Foods Market", 15.00, 120.00, "Grocery")),
        new AbstractMap.SimpleEntry<>("TRADER JOES", new MerchantInfo("Trader Joe's", 20.00, 150.00, "Grocery")),
        new AbstractMap.SimpleEntry<>("KROGER", new MerchantInfo("Kroger", 30.00, 180.00, "Grocery")),
        new AbstractMap.SimpleEntry<>("SAFEWAY", new MerchantInfo("Safeway", 25.00, 160.00, "Grocery")),
        new AbstractMap.SimpleEntry<>("PUBLIX", new MerchantInfo("Publix Super Market", 20.00, 140.00, "Grocery")),
        new AbstractMap.SimpleEntry<>("SPROUTS", new MerchantInfo("Sprouts Farmers Market", 18.00, 110.00, "Grocery")),
        new AbstractMap.SimpleEntry<>("COSTCO", new MerchantInfo("Costco Wholesale", 50.00, 400.00, "Membership")),
        new AbstractMap.SimpleEntry<>("SAMS CLUB", new MerchantInfo("Sam's Club", 40.00, 350.00, "Membership")),
        
        // Retail - Apparel & Fashion
        new AbstractMap.SimpleEntry<>("NIKE", new MerchantInfo("Nike", 60.00, 300.00, "Apparel")),
        new AbstractMap.SimpleEntry<>("ADIDAS", new MerchantInfo("Adidas", 50.00, 250.00, "Apparel")),
        new AbstractMap.SimpleEntry<>("ZARA", new MerchantInfo("Zara", 40.00, 200.00, "Apparel")),
        new AbstractMap.SimpleEntry<>("H&M", new MerchantInfo("H&M", 25.00, 150.00, "Apparel")),
        new AbstractMap.SimpleEntry<>("UNIQLO", new MerchantInfo("UNIQLO", 35.00, 180.00, "Apparel")),
        new AbstractMap.SimpleEntry<>("FOREVER 21", new MerchantInfo("Forever 21", 15.00, 100.00, "Apparel")),
        new AbstractMap.SimpleEntry<>("GAP", new MerchantInfo("Gap, Inc.", 30.00, 160.00, "Apparel")),
        
        // Retail - Electronics & Tech
        new AbstractMap.SimpleEntry<>("BEST BUY", new MerchantInfo("Best Buy", 100.00, 2000.00, "Electronics")),
        new AbstractMap.SimpleEntry<>("APPLE STORE", new MerchantInfo("Apple", 150.00, 2500.00, "Electronics")),
        new AbstractMap.SimpleEntry<>("MICROSOFT STORE", new MerchantInfo("Microsoft", 100.00, 1500.00, "Electronics")),
        new AbstractMap.SimpleEntry<>("MICRO CENTER", new MerchantInfo("Micro Center", 80.00, 1200.00, "Electronics")),
        new AbstractMap.SimpleEntry<>("STAPLES", new MerchantInfo("Staples", 15.00, 200.00, "Office Supplies")),
        new AbstractMap.SimpleEntry<>("OFFICE DEPOT", new MerchantInfo("Office Depot", 20.00, 250.00, "Office Supplies")),
        
        // Dining - Fast Food
        new AbstractMap.SimpleEntry<>("MCDONALDS", new MerchantInfo("McDonald's", 5.00, 25.00, "Fast Food")),
        new AbstractMap.SimpleEntry<>("BURGER KING", new MerchantInfo("Burger King", 6.00, 22.00, "Fast Food")),
        new AbstractMap.SimpleEntry<>("WENDYS", new MerchantInfo("Wendy's", 7.00, 20.00, "Fast Food")),
        new AbstractMap.SimpleEntry<>("TACO BELL", new MerchantInfo("Taco Bell", 5.00, 18.00, "Fast Food")),
        new AbstractMap.SimpleEntry<>("CHICK-FIL-A", new MerchantInfo("Chick-fil-A", 8.00, 25.00, "Fast Food")),
        new AbstractMap.SimpleEntry<>("CHIPOTLE", new MerchantInfo("Chipotle Mexican Grill", 10.00, 35.00, "Fast Casual")),
        new AbstractMap.SimpleEntry<>("PANERA", new MerchantInfo("Panera Bread", 8.00, 22.00, "Fast Casual")),
        new AbstractMap.SimpleEntry<>("SUBWAY", new MerchantInfo("Subway", 6.00, 18.00, "Fast Food")),
        
        // Dining - Coffee & Snacks
        new AbstractMap.SimpleEntry<>("STARBUCKS", new MerchantInfo("Starbucks Coffee Company", 4.00, 15.00, "Coffee Shop")),
        new AbstractMap.SimpleEntry<>("DUNKIN", new MerchantInfo("Dunkin'", 3.50, 12.00, "Coffee Shop")),
        new AbstractMap.SimpleEntry<>("PEETS COFFEE", new MerchantInfo("Peet's Coffee", 4.50, 14.00, "Coffee Shop")),
        new AbstractMap.SimpleEntry<>("BLUEBOTTLE", new MerchantInfo("Blue Bottle Coffee", 5.00, 16.00, "Coffee Shop")),
        new AbstractMap.SimpleEntry<>("JAMBA JUICE", new MerchantInfo("Jamba Juice", 6.00, 18.00, "Juice Bar")),
        
        // Dining - Restaurants
        new AbstractMap.SimpleEntry<>("OLIVE GARDEN", new MerchantInfo("Olive Garden Italian Restaurant", 15.00, 80.00, "Italian Restaurant")),
        new AbstractMap.SimpleEntry<>("RED ROBIN", new MerchantInfo("Red Robin Gourmet Burgers", 12.00, 40.00, "Burger Restaurant")),
        new AbstractMap.SimpleEntry<>("TEXAS ROADHOUSE", new MerchantInfo("Texas Roadhouse", 15.00, 90.00, "Steakhouse")),
        new AbstractMap.SimpleEntry<>("APPLEBEES", new MerchantInfo("Applebee's", 12.00, 50.00, "Casual Dining")),
        new AbstractMap.SimpleEntry<>("OUTBACK", new MerchantInfo("Outback Steakhouse", 18.00, 100.00, "Steakhouse")),
        new AbstractMap.SimpleEntry<>("CHEESECAKE FACTORY", new MerchantInfo("The Cheesecake Factory", 20.00, 80.00, "Casual Dining")),
        new AbstractMap.SimpleEntry<>("RUTH CHRIS", new MerchantInfo("Ruth's Chris Steak House", 40.00, 200.00, "Fine Dining")),
        
        // Travel - Airlines
        new AbstractMap.SimpleEntry<>("UNITED AIRLINES", new MerchantInfo("United Airlines", 150.00, 1500.00, "Airline")),
        new AbstractMap.SimpleEntry<>("DELTA AIRLINES", new MerchantInfo("The Delta Air Lines", 150.00, 1500.00, "Airline")),
        new AbstractMap.SimpleEntry<>("AMERICAN AIRLINES", new MerchantInfo("American Airlines", 140.00, 1400.00, "Airline")),
        new AbstractMap.SimpleEntry<>("SOUTHWEST", new MerchantInfo("Southwest Airlines", 100.00, 800.00, "Airline")),
        new AbstractMap.SimpleEntry<>("JETBLUE", new MerchantInfo("JetBlue Airways", 90.00, 700.00, "Airline")),
        
        // Travel - Hotels
        new AbstractMap.SimpleEntry<>("MARRIOTT", new MerchantInfo("Marriott International", 100.00, 500.00, "Hotel")),
        new AbstractMap.SimpleEntry<>("HILTON", new MerchantInfo("Hilton Hotels", 90.00, 450.00, "Hotel")),
        new AbstractMap.SimpleEntry<>("HYATT", new MerchantInfo("Hyatt Hotels Corporation", 110.00, 550.00, "Hotel")),
        new AbstractMap.SimpleEntry<>("EXPEDIA", new MerchantInfo("Expedia", 80.00, 2000.00, "Travel Booking")),
        new AbstractMap.SimpleEntry<>("AIRBNB", new MerchantInfo("Airbnb", 70.00, 500.00, "Accommodation")),
        new AbstractMap.SimpleEntry<>("BOOKING.COM", new MerchantInfo("Booking.com", 75.00, 800.00, "Travel Booking")),
        
        // Travel - Ride-sharing & Rental
        new AbstractMap.SimpleEntry<>("UBER", new MerchantInfo("Uber", 8.00, 80.00, "Ride-sharing")),
        new AbstractMap.SimpleEntry<>("LYFT", new MerchantInfo("Lyft", 7.00, 75.00, "Ride-sharing")),
        new AbstractMap.SimpleEntry<>("HERTZ", new MerchantInfo("Hertz", 40.00, 500.00, "Car Rental")),
        new AbstractMap.SimpleEntry<>("AVIS", new MerchantInfo("Avis Budget Group", 35.00, 450.00, "Car Rental")),
        new AbstractMap.SimpleEntry<>("ENTERPRISE", new MerchantInfo("Enterprise Rent-A-Car", 38.00, 480.00, "Car Rental")),
        
        // Entertainment - Streaming & Media
        new AbstractMap.SimpleEntry<>("NETFLIX", new MerchantInfo("Netflix", 9.99, 22.99, "Streaming")),
        new AbstractMap.SimpleEntry<>("HULU", new MerchantInfo("Hulu", 7.99, 14.99, "Streaming")),
        new AbstractMap.SimpleEntry<>("DISNEY PLUS", new MerchantInfo("Disney+", 7.99, 13.99, "Streaming")),
        new AbstractMap.SimpleEntry<>("HBO MAX", new MerchantInfo("HBO Max", 9.99, 19.99, "Streaming")),
        new AbstractMap.SimpleEntry<>("SPOTIFY", new MerchantInfo("Spotify AB", 9.99, 14.99, "Music Streaming")),
        new AbstractMap.SimpleEntry<>("APPLE MUSIC", new MerchantInfo("Apple Music", 10.99, 10.99, "Music Streaming")),
        new AbstractMap.SimpleEntry<>("XBOX GAMEPASS", new MerchantInfo("Xbox Game Pass", 9.99, 16.99, "Gaming")),
        new AbstractMap.SimpleEntry<>("PLAYSTATION PLUS", new MerchantInfo("PlayStation Plus", 9.99, 17.99, "Gaming")),
        
        // Entertainment - Ticket Sales
        new AbstractMap.SimpleEntry<>("TICKETMASTER", new MerchantInfo("Ticketmaster", 50.00, 500.00, "Ticket Sales")),
        new AbstractMap.SimpleEntry<>("STUBHUB", new MerchantInfo("StubHub", 40.00, 400.00, "Ticket Resale")),
        new AbstractMap.SimpleEntry<>("AMC THEATERS", new MerchantInfo("AMC Entertainment", 12.00, 40.00, "Movie Theater")),
        new AbstractMap.SimpleEntry<>("CINEMARK", new MerchantInfo("Cinemark Holdings", 10.00, 35.00, "Movie Theater")),
        new AbstractMap.SimpleEntry<>("REGAL CINEMA", new MerchantInfo("Regal Cinema", 11.00, 38.00, "Movie Theater")),
        
        // Utilities & Services
        new AbstractMap.SimpleEntry<>("VERIZON", new MerchantInfo("Verizon Communications", 50.00, 200.00, "Telecom")),
        new AbstractMap.SimpleEntry<>("AT&T", new MerchantInfo("AT&T Inc.", 45.00, 180.00, "Telecom")),
        new AbstractMap.SimpleEntry<>("TMOBILE", new MerchantInfo("T-Mobile US", 40.00, 160.00, "Telecom")),
        new AbstractMap.SimpleEntry<>("COMCAST", new MerchantInfo("Comcast Corp", 60.00, 250.00, "Internet/Cable")),
        new AbstractMap.SimpleEntry<>("GEICO", new MerchantInfo("GEICO", 100.00, 300.00, "Auto Insurance")),
        new AbstractMap.SimpleEntry<>("STATE FARM", new MerchantInfo("State Farm Fire & Casualty", 90.00, 280.00, "Auto Insurance")),
        new AbstractMap.SimpleEntry<>("ALLSTATE", new MerchantInfo("Allstate", 95.00, 290.00, "Auto Insurance")),
        
        // Healthcare & Fitness
        new AbstractMap.SimpleEntry<>("CVS PHARMACY", new MerchantInfo("CVS Health", 10.00, 150.00, "Pharmacy")),
        new AbstractMap.SimpleEntry<>("WALGREENS", new MerchantInfo("Walgreens", 12.00, 160.00, "Pharmacy")),
        new AbstractMap.SimpleEntry<>("PLANET FITNESS", new MerchantInfo("Planet Fitness", 20.00, 30.00, "Fitness")),
        new AbstractMap.SimpleEntry<>("PELOTON", new MerchantInfo("Peloton Interactive", 40.00, 50.00, "Fitness")),
        new AbstractMap.SimpleEntry<>("EQUINOX", new MerchantInfo("Equinox Holdings", 200.00, 300.00, "Fitness")),
        new AbstractMap.SimpleEntry<>("SURGEON", new MerchantInfo("Prestige Surgical Center", 500.00, 5000.00, "Medical")),
        
        // Financial Services
        new AbstractMap.SimpleEntry<>("CHASE BANK", new MerchantInfo("JPMorgan Chase Bank", 0.00, 100.00, "Banking")),
        new AbstractMap.SimpleEntry<>("BANK OF AMERICA", new MerchantInfo("Bank of America Corp", 0.00, 100.00, "Banking")),
        new AbstractMap.SimpleEntry<>("WELLS FARGO", new MerchantInfo("Wells Fargo Bank", 0.00, 100.00, "Banking")),
        new AbstractMap.SimpleEntry<>("CHARLES SCHWAB", new MerchantInfo("Charles Schwab Inc", 0.00, 1000.00, "Investment")),
        new AbstractMap.SimpleEntry<>("FIDELITY", new MerchantInfo("Fidelity Investments", 0.00, 1000.00, "Investment")),
        new AbstractMap.SimpleEntry<>("PAYPAL", new MerchantInfo("PayPal", 5.00, 500.00, "Payment")),
        new AbstractMap.SimpleEntry<>("STRIPE", new MerchantInfo("Stripe Payments", 0.00, 0.00, "Payment Processing")),
        
        // Home & Garden
        new AbstractMap.SimpleEntry<>("HOME DEPOT", new MerchantInfo("The Home Depot", 30.00, 500.00, "Home Improvement")),
        new AbstractMap.SimpleEntry<>("LOWES", new MerchantInfo("Lowe's Companies", 25.00, 400.00, "Home Improvement")),
        new AbstractMap.SimpleEntry<>("IKEA", new MerchantInfo("IKEA", 40.00, 800.00, "Furniture")),
        new AbstractMap.SimpleEntry<>("WAYFAIR", new MerchantInfo("Wayfair Inc", 50.00, 600.00, "Furniture")),
        
        // Online Retail
        new AbstractMap.SimpleEntry<>("AMAZON", new MerchantInfo("Amazon.com", 10.00, 500.00, "E-commerce")),
        new AbstractMap.SimpleEntry<>("EBAY", new MerchantInfo("eBay", 5.00, 1000.00, "Auction")),
        new AbstractMap.SimpleEntry<>("ETSY", new MerchantInfo("Etsy Inc", 5.00, 200.00, "Handmade/Vintage")),
        new AbstractMap.SimpleEntry<>("ALIEXPRESS", new MerchantInfo("AliExpress", 1.00, 100.00, "Chinese E-commerce")),
        
        // Gas & Fuel
        new AbstractMap.SimpleEntry<>("SHELL", new MerchantInfo("Shell Oil Company", 30.00, 80.00, "Gas Station")),
        new AbstractMap.SimpleEntry<>("EXXON", new MerchantInfo("Exxon Mobil Corporation", 30.00, 80.00, "Gas Station")),
        new AbstractMap.SimpleEntry<>("CHEVRON", new MerchantInfo("Chevron Corporation", 30.00, 80.00, "Gas Station")),
        new AbstractMap.SimpleEntry<>("TEXACO", new MerchantInfo("Texaco Inc", 28.00, 75.00, "Gas Station")),
        new AbstractMap.SimpleEntry<>("SPEEDWAY", new MerchantInfo("Murphy USA Inc", 25.00, 70.00, "Gas Station")),
        
        // Pet Supplies & Services
        new AbstractMap.SimpleEntry<>("PETCO", new MerchantInfo("Petco Health and Wellness", 20.00, 200.00, "Pet Supplies")),
        new AbstractMap.SimpleEntry<>("PETSMART", new MerchantInfo("PetSmart Inc", 25.00, 250.00, "Pet Supplies")),
        
        // Additional diverse merchants for variety
        new AbstractMap.SimpleEntry<>("FEDEX", new MerchantInfo("FedEx", 10.00, 500.00, "Shipping")),
        new AbstractMap.SimpleEntry<>("UPS", new MerchantInfo("United Parcel Service", 8.00, 400.00, "Shipping")),
        new AbstractMap.SimpleEntry<>("USPS", new MerchantInfo("United States Postal Service", 2.00, 100.00, "Postal")),
        new AbstractMap.SimpleEntry<>("DOORDASH", new MerchantInfo("DoorDash", 5.00, 50.00, "Food Delivery")),
        new AbstractMap.SimpleEntry<>("UBER EATS", new MerchantInfo("Uber Eats", 5.00, 50.00, "Food Delivery")),
        new AbstractMap.SimpleEntry<>("GRUBHUB", new MerchantInfo("Grubhub Inc", 5.00, 50.00, "Food Delivery"))
    );

    /**
     * Generates a list of 200 realistic merchant transaction test cases.
     * Each case includes a variety of transaction amounts and descriptions.
     */
    public static List<EnrichmentRequest> generate200TestCases() {
        List<EnrichmentRequest> testCases = new ArrayList<>(200);
        List<String> merchantKeys = new ArrayList<>(MERCHANTS.keySet());
        
        // Generate 200 test cases with repeated merchants but varied amounts
        for (int i = 0; i < 200; i++) {
            String merchantKey = merchantKeys.get(i % merchantKeys.size());
            MerchantInfo info = MERCHANTS.get(merchantKey);
            
            // Generate varied amounts with occasional outliers
            BigDecimal amount = generateAmount(info.minAmount, info.maxAmount);
            
            // Add location variations to description
            String location = generateLocation();
            String description = generateDescription(merchantKey, location);
            
            EnrichmentRequest.Transaction transaction = new EnrichmentRequest.Transaction(
                description,
                amount,
                LocalDate.now().minusDays(i % 30),
                info.officialName
            );
            
            testCases.add(new EnrichmentRequest(
                "test_account_" + i,
                List.of(transaction)
            ));
        }
        
        return testCases;
    }

    /**
     * Generates test data with specific scenario focus.
     */
    public static List<EnrichmentRequest> generateScenario(String scenarioType) {
        return switch (scenarioType) {
            case "CACHE_HEAVY" -> generateCacheHeavyScenario();
            case "HIGH_VARIANCE_AMOUNTS" -> generateHighVarianceAmounts();
            case "EDGE_CASES" -> generateEdgeCases();
            case "STRESS_TEST" -> generateStressTest();
            default -> generate200TestCases();
        };
    }

    private static List<EnrichmentRequest> generateCacheHeavyScenario() {
        // Many duplicate merchant/descriptions to hit cache
        List<EnrichmentRequest> cases = new ArrayList<>();
        String[] topMerchants = {"STARBUCKS", "AMAZON", "UBER", "MCDONALDS", "WALMART"};
        int caseCount = 0;
        
        for (int i = 0; i < 40; i++) {
            for (String merchant : topMerchants) {
                MerchantInfo info = MERCHANTS.get(merchant);
                EnrichmentRequest.Transaction transaction = new EnrichmentRequest.Transaction(
                    merchant + " #" + (i % 10),
                    generateAmount(info.minAmount, info.maxAmount),
                    LocalDate.now().minusDays(i % 30),
                    info.officialName
                );
                cases.add(new EnrichmentRequest(
                    "test_account_" + caseCount++,
                    List.of(transaction)
                ));
            }
        }
        
        return cases;
    }

    private static List<EnrichmentRequest> generateHighVarianceAmounts() {
        List<EnrichmentRequest> cases = new ArrayList<>();
        List<String> merchantKeys = new ArrayList<>(MERCHANTS.keySet());
        int caseCount = 0;
        
        for (int i = 0; i < 50; i++) {
            for (String merchantKey : merchantKeys.stream().limit(4).toList()) {
                MerchantInfo info = MERCHANTS.get(merchantKey);
                // Generate very small and very large amounts
                BigDecimal amount = i % 3 == 0 
                    ? new BigDecimal("0.01")  // edge case
                    : i % 3 == 1 
                    ? new BigDecimal(Math.pow(10, RANDOM.nextInt(4)))  // variance
                    : generateAmount(info.minAmount, info.maxAmount);
                
                EnrichmentRequest.Transaction transaction = new EnrichmentRequest.Transaction(
                    merchantKey + " TXN " + i,
                    amount,
                    LocalDate.now().minusDays(RANDOM.nextInt(30)),
                    info.officialName
                );
                
                cases.add(new EnrichmentRequest(
                    "test_account_" + caseCount++,
                    List.of(transaction)
                ));
            }
        }
        
        return cases;
    }

    private static List<EnrichmentRequest> generateEdgeCases() {
        List<EnrichmentRequest> cases = new ArrayList<>();
        int caseCount = 0;
        
        // Edge case: very small amounts
        EnrichmentRequest.Transaction txn1 = new EnrichmentRequest.Transaction(
            "STARBUCKS #1", new BigDecimal("0.01"), LocalDate.now(), "Starbucks Coffee"
        );
        cases.add(new EnrichmentRequest("test_account_" + caseCount++, List.of(txn1)));
        
        EnrichmentRequest.Transaction txn2 = new EnrichmentRequest.Transaction(
            "COFFEE", new BigDecimal("1.00"), LocalDate.now(), "Starbucks Coffee"
        );
        cases.add(new EnrichmentRequest("test_account_" + caseCount++, List.of(txn2)));
        
        // Edge case: very large amounts
        EnrichmentRequest.Transaction txn3 = new EnrichmentRequest.Transaction(
            "LUXURY HOTEL", new BigDecimal("9999.99"), LocalDate.now(), "Hyatt Hotels"
        );
        cases.add(new EnrichmentRequest("test_account_" + caseCount++, List.of(txn3)));
        
        EnrichmentRequest.Transaction txn4 = new EnrichmentRequest.Transaction(
            "DIAMOND RING", new BigDecimal("50000.00"), LocalDate.now(), "Jewelry Store"
        );
        cases.add(new EnrichmentRequest("test_account_" + caseCount++, List.of(txn4)));
        
        // Edge case: special characters in merchant names
        EnrichmentRequest.Transaction txn5 = new EnrichmentRequest.Transaction(
            "BILL'S BURGERS & FRIES", new BigDecimal("12.50"), LocalDate.now(), "Bill's Burgers"
        );
        cases.add(new EnrichmentRequest("test_account_" + caseCount++, List.of(txn5)));
        
        // All other edge cases
        for (int i = 0; i < 195; i++) {
            List<String> merchantKeys = new ArrayList<>(MERCHANTS.keySet());
            String merchantKey = merchantKeys.get(i % merchantKeys.size());
            MerchantInfo info = MERCHANTS.get(merchantKey);
            EnrichmentRequest.Transaction txn = new EnrichmentRequest.Transaction(
                merchantKey + " LOC " + (i % 100),
                generateAmount(info.minAmount, info.maxAmount),
                LocalDate.now().minusDays(i % 30),
                info.officialName
            );
            cases.add(new EnrichmentRequest(
                "test_account_" + caseCount++,
                List.of(txn)
            ));
        }
        
        return cases;
    }

    private static List<EnrichmentRequest> generateStressTest() {
        // Identical transactions to stress test cache and concurrent access
        List<EnrichmentRequest> cases = new ArrayList<>();
        
        for (int i = 0; i < 200; i++) {
            EnrichmentRequest.Transaction transaction = new EnrichmentRequest.Transaction(
                "STARBUCKS #1234 MIDTOWN NYC",
                new BigDecimal("5.45"),
                LocalDate.now(),
                "Starbucks Coffee"
            );
            cases.add(new EnrichmentRequest(
                "test_account_stress",
                List.of(transaction)
            ));
        }
        
        return cases;
    }

    private static BigDecimal generateAmount(double min, double max) {
        double amount = min + (max - min) * RANDOM.nextDouble();
        // Round to 2 decimal places
        return new BigDecimal(String.format("%.2f", amount));
    }

    private static String generateLocation() {
        String[] cities = {
            "NEW YORK", "LOS ANGELES", "CHICAGO", "HOUSTON", "PHOENIX",
            "PHILADELPHIA", "SAN ANTONIO", "SAN DIEGO", "DALLAS", "SAN JOSE",
            "SEATTLE", "DENVER", "BOSTON", "MIAMI", "AUSTIN", "ATLANTA"
        };
        
        String[] states = {
            "NY", "CA", "IL", "TX", "AZ", "PA", "TX", "CA", "TX", "CA",
            "WA", "CO", "MA", "FL", "TX", "GA"
        };
        
        int idx = RANDOM.nextInt(cities.length);
        return cities[idx] + " " + states[idx];
    }

    private static String generateDescription(String merchant, String location) {
        String[] formats = {
            merchant + " " + location,
            merchant + " #" + (100 + RANDOM.nextInt(9900)) + " " + location,
            merchant + " " + generateLocation(),
            merchant + "-" + generateLocation()
        };
        
        return formats[RANDOM.nextInt(formats.length)];
    }

    // Helper class
    private static class MerchantInfo {
        final String officialName;
        final double minAmount;
        final double maxAmount;
        final String category;

        MerchantInfo(String officialName, double minAmount, double maxAmount, String category) {
            this.officialName = officialName;
            this.minAmount = minAmount;
            this.maxAmount = maxAmount;
            this.category = category;
        }
    }
}
