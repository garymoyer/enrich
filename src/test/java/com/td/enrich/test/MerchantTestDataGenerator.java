package com.td.enrich.test;

import com.td.enrich.domain.EnrichmentRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Builds realistic {@link EnrichmentRequest} objects for use in the manual test harness.
 *
 * <p>This class is <em>not</em> a JUnit test — it is a data factory used by
 * {@link EnrichmentTestHarness} and {@link TestRunner} to produce request payloads
 * without having to hand-write hundreds of test cases. It should never appear in
 * automated unit or integration tests; use Mockito/WireMock there instead.
 *
 * <h2>Merchant catalog</h2>
 * <p>The static {@link #MERCHANTS} map holds 85+ real-world merchants grouped into
 * categories:
 * <ul>
 *   <li><b>Retail</b> — grocery, apparel, electronics, home improvement, online</li>
 *   <li><b>Dining</b> — fast food, fast casual, sit-down restaurants, coffee shops</li>
 *   <li><b>Travel</b> — airlines, hotels, ride-sharing, car rental</li>
 *   <li><b>Entertainment</b> — streaming, gaming, movie theaters, ticket sales</li>
 *   <li><b>Services</b> — telecom, insurance, healthcare, financial, shipping</li>
 *   <li><b>Gas &amp; Fuel</b> — major petroleum brands</li>
 *   <li><b>Pets</b> — pet supply chains</li>
 * </ul>
 * Each entry stores the merchant's official name and a realistic transaction amount range
 * (min/max) so generated amounts look like real bank statement entries.
 *
 * <h2>Scenarios</h2>
 * <p>Call {@link #generateScenario(String)} with one of four scenario names to produce
 * test data tailored to a specific scenario:
 * <ul>
 *   <li><b>CACHE_HEAVY</b> — 200 requests across only 5 merchants; many will hit the
 *       in-memory cache rather than calling Plaid, letting you measure cache-hit performance.</li>
 *   <li><b>HIGH_VARIANCE_AMOUNTS</b> — mix of $0.01, powers-of-10, and normal amounts
 *       to verify the service handles extreme values without overflow or rounding errors.</li>
 *   <li><b>EDGE_CASES</b> — special characters, very small/large amounts, merchants not in
 *       any known catalog (exercises the fallback enrichment path).</li>
 *   <li><b>STRESS_TEST</b> — 200 requests for the exact same transaction; all requests
 *       should resolve to the same cache entry, exercising concurrent read paths.</li>
 * </ul>
 *
 * <h2>Reproducibility</h2>
 * <p>The shared {@link #RANDOM} instance uses a fixed seed ({@code 42}).  The same call
 * sequence always produces the same data, making test runs comparable across environments.
 */
public class MerchantTestDataGenerator {

    /**
     * Shared random number generator with a fixed seed so every test run produces
     * the same sequence of merchants, amounts, and locations.  This makes it easier
     * to reproduce a failure: if run N always produces a "HIGH_VARIANCE_AMOUNTS" failure
     * at request 47, run N+1 will produce the same request at the same position.
     */
    private static final Random RANDOM = new Random(42);

    /**
     * Catalog of known merchants keyed by their ALL-CAPS lookup key.
     *
     * <p>The key is what you would typically see in a raw bank statement description
     * (e.g., {@code "STARBUCKS"}, {@code "MCDONALDS"}).  The value holds the official
     * name as Plaid would return it, plus a typical amount range used when generating
     * randomized transaction amounts.
     *
     * <p>The map is built from {@link Map#ofEntries} so it can exceed the 10-entry
     * limit of the simpler {@link Map#of} factory method.
     */
    private static final Map<String, MerchantInfo> MERCHANTS = Map.ofEntries(
        // ── Retail — Grocery & Supermarkets ─────────────────────────────────────────
        new AbstractMap.SimpleEntry<>("WHOLE FOODS", new MerchantInfo("Whole Foods Market", 15.00, 120.00, "Grocery")),
        new AbstractMap.SimpleEntry<>("TRADER JOES", new MerchantInfo("Trader Joe's", 20.00, 150.00, "Grocery")),
        new AbstractMap.SimpleEntry<>("KROGER", new MerchantInfo("Kroger", 30.00, 180.00, "Grocery")),
        new AbstractMap.SimpleEntry<>("SAFEWAY", new MerchantInfo("Safeway", 25.00, 160.00, "Grocery")),
        new AbstractMap.SimpleEntry<>("PUBLIX", new MerchantInfo("Publix Super Market", 20.00, 140.00, "Grocery")),
        new AbstractMap.SimpleEntry<>("SPROUTS", new MerchantInfo("Sprouts Farmers Market", 18.00, 110.00, "Grocery")),
        new AbstractMap.SimpleEntry<>("COSTCO", new MerchantInfo("Costco Wholesale", 50.00, 400.00, "Membership")),
        new AbstractMap.SimpleEntry<>("SAMS CLUB", new MerchantInfo("Sam's Club", 40.00, 350.00, "Membership")),

        // ── Retail — Apparel & Fashion ───────────────────────────────────────────────
        new AbstractMap.SimpleEntry<>("NIKE", new MerchantInfo("Nike", 60.00, 300.00, "Apparel")),
        new AbstractMap.SimpleEntry<>("ADIDAS", new MerchantInfo("Adidas", 50.00, 250.00, "Apparel")),
        new AbstractMap.SimpleEntry<>("ZARA", new MerchantInfo("Zara", 40.00, 200.00, "Apparel")),
        new AbstractMap.SimpleEntry<>("H&M", new MerchantInfo("H&M", 25.00, 150.00, "Apparel")),
        new AbstractMap.SimpleEntry<>("UNIQLO", new MerchantInfo("UNIQLO", 35.00, 180.00, "Apparel")),
        new AbstractMap.SimpleEntry<>("FOREVER 21", new MerchantInfo("Forever 21", 15.00, 100.00, "Apparel")),
        new AbstractMap.SimpleEntry<>("GAP", new MerchantInfo("Gap, Inc.", 30.00, 160.00, "Apparel")),

        // ── Retail — Electronics & Tech ──────────────────────────────────────────────
        new AbstractMap.SimpleEntry<>("BEST BUY", new MerchantInfo("Best Buy", 100.00, 2000.00, "Electronics")),
        new AbstractMap.SimpleEntry<>("APPLE STORE", new MerchantInfo("Apple", 150.00, 2500.00, "Electronics")),
        new AbstractMap.SimpleEntry<>("MICROSOFT STORE", new MerchantInfo("Microsoft", 100.00, 1500.00, "Electronics")),
        new AbstractMap.SimpleEntry<>("MICRO CENTER", new MerchantInfo("Micro Center", 80.00, 1200.00, "Electronics")),
        new AbstractMap.SimpleEntry<>("STAPLES", new MerchantInfo("Staples", 15.00, 200.00, "Office Supplies")),
        new AbstractMap.SimpleEntry<>("OFFICE DEPOT", new MerchantInfo("Office Depot", 20.00, 250.00, "Office Supplies")),

        // ── Dining — Fast Food ───────────────────────────────────────────────────────
        new AbstractMap.SimpleEntry<>("MCDONALDS", new MerchantInfo("McDonald's", 5.00, 25.00, "Fast Food")),
        new AbstractMap.SimpleEntry<>("BURGER KING", new MerchantInfo("Burger King", 6.00, 22.00, "Fast Food")),
        new AbstractMap.SimpleEntry<>("WENDYS", new MerchantInfo("Wendy's", 7.00, 20.00, "Fast Food")),
        new AbstractMap.SimpleEntry<>("TACO BELL", new MerchantInfo("Taco Bell", 5.00, 18.00, "Fast Food")),
        new AbstractMap.SimpleEntry<>("CHICK-FIL-A", new MerchantInfo("Chick-fil-A", 8.00, 25.00, "Fast Food")),
        new AbstractMap.SimpleEntry<>("CHIPOTLE", new MerchantInfo("Chipotle Mexican Grill", 10.00, 35.00, "Fast Casual")),
        new AbstractMap.SimpleEntry<>("PANERA", new MerchantInfo("Panera Bread", 8.00, 22.00, "Fast Casual")),
        new AbstractMap.SimpleEntry<>("SUBWAY", new MerchantInfo("Subway", 6.00, 18.00, "Fast Food")),

        // ── Dining — Coffee & Snacks ─────────────────────────────────────────────────
        new AbstractMap.SimpleEntry<>("STARBUCKS", new MerchantInfo("Starbucks Coffee Company", 4.00, 15.00, "Coffee Shop")),
        new AbstractMap.SimpleEntry<>("DUNKIN", new MerchantInfo("Dunkin'", 3.50, 12.00, "Coffee Shop")),
        new AbstractMap.SimpleEntry<>("PEETS COFFEE", new MerchantInfo("Peet's Coffee", 4.50, 14.00, "Coffee Shop")),
        new AbstractMap.SimpleEntry<>("BLUEBOTTLE", new MerchantInfo("Blue Bottle Coffee", 5.00, 16.00, "Coffee Shop")),
        new AbstractMap.SimpleEntry<>("JAMBA JUICE", new MerchantInfo("Jamba Juice", 6.00, 18.00, "Juice Bar")),

        // ── Dining — Sit-Down Restaurants ───────────────────────────────────────────
        new AbstractMap.SimpleEntry<>("OLIVE GARDEN", new MerchantInfo("Olive Garden Italian Restaurant", 15.00, 80.00, "Italian Restaurant")),
        new AbstractMap.SimpleEntry<>("RED ROBIN", new MerchantInfo("Red Robin Gourmet Burgers", 12.00, 40.00, "Burger Restaurant")),
        new AbstractMap.SimpleEntry<>("TEXAS ROADHOUSE", new MerchantInfo("Texas Roadhouse", 15.00, 90.00, "Steakhouse")),
        new AbstractMap.SimpleEntry<>("APPLEBEES", new MerchantInfo("Applebee's", 12.00, 50.00, "Casual Dining")),
        new AbstractMap.SimpleEntry<>("OUTBACK", new MerchantInfo("Outback Steakhouse", 18.00, 100.00, "Steakhouse")),
        new AbstractMap.SimpleEntry<>("CHEESECAKE FACTORY", new MerchantInfo("The Cheesecake Factory", 20.00, 80.00, "Casual Dining")),
        new AbstractMap.SimpleEntry<>("RUTH CHRIS", new MerchantInfo("Ruth's Chris Steak House", 40.00, 200.00, "Fine Dining")),

        // ── Travel — Airlines ────────────────────────────────────────────────────────
        new AbstractMap.SimpleEntry<>("UNITED AIRLINES", new MerchantInfo("United Airlines", 150.00, 1500.00, "Airline")),
        new AbstractMap.SimpleEntry<>("DELTA AIRLINES", new MerchantInfo("The Delta Air Lines", 150.00, 1500.00, "Airline")),
        new AbstractMap.SimpleEntry<>("AMERICAN AIRLINES", new MerchantInfo("American Airlines", 140.00, 1400.00, "Airline")),
        new AbstractMap.SimpleEntry<>("SOUTHWEST", new MerchantInfo("Southwest Airlines", 100.00, 800.00, "Airline")),
        new AbstractMap.SimpleEntry<>("JETBLUE", new MerchantInfo("JetBlue Airways", 90.00, 700.00, "Airline")),

        // ── Travel — Hotels ──────────────────────────────────────────────────────────
        new AbstractMap.SimpleEntry<>("MARRIOTT", new MerchantInfo("Marriott International", 100.00, 500.00, "Hotel")),
        new AbstractMap.SimpleEntry<>("HILTON", new MerchantInfo("Hilton Hotels", 90.00, 450.00, "Hotel")),
        new AbstractMap.SimpleEntry<>("HYATT", new MerchantInfo("Hyatt Hotels Corporation", 110.00, 550.00, "Hotel")),
        new AbstractMap.SimpleEntry<>("EXPEDIA", new MerchantInfo("Expedia", 80.00, 2000.00, "Travel Booking")),
        new AbstractMap.SimpleEntry<>("AIRBNB", new MerchantInfo("Airbnb", 70.00, 500.00, "Accommodation")),
        new AbstractMap.SimpleEntry<>("BOOKING.COM", new MerchantInfo("Booking.com", 75.00, 800.00, "Travel Booking")),

        // ── Travel — Ride-sharing & Car Rental ──────────────────────────────────────
        new AbstractMap.SimpleEntry<>("UBER", new MerchantInfo("Uber", 8.00, 80.00, "Ride-sharing")),
        new AbstractMap.SimpleEntry<>("LYFT", new MerchantInfo("Lyft", 7.00, 75.00, "Ride-sharing")),
        new AbstractMap.SimpleEntry<>("HERTZ", new MerchantInfo("Hertz", 40.00, 500.00, "Car Rental")),
        new AbstractMap.SimpleEntry<>("AVIS", new MerchantInfo("Avis Budget Group", 35.00, 450.00, "Car Rental")),
        new AbstractMap.SimpleEntry<>("ENTERPRISE", new MerchantInfo("Enterprise Rent-A-Car", 38.00, 480.00, "Car Rental")),

        // ── Entertainment — Streaming & Gaming ──────────────────────────────────────
        new AbstractMap.SimpleEntry<>("NETFLIX", new MerchantInfo("Netflix", 9.99, 22.99, "Streaming")),
        new AbstractMap.SimpleEntry<>("HULU", new MerchantInfo("Hulu", 7.99, 14.99, "Streaming")),
        new AbstractMap.SimpleEntry<>("DISNEY PLUS", new MerchantInfo("Disney+", 7.99, 13.99, "Streaming")),
        new AbstractMap.SimpleEntry<>("HBO MAX", new MerchantInfo("HBO Max", 9.99, 19.99, "Streaming")),
        new AbstractMap.SimpleEntry<>("SPOTIFY", new MerchantInfo("Spotify AB", 9.99, 14.99, "Music Streaming")),
        new AbstractMap.SimpleEntry<>("APPLE MUSIC", new MerchantInfo("Apple Music", 10.99, 10.99, "Music Streaming")),
        new AbstractMap.SimpleEntry<>("XBOX GAMEPASS", new MerchantInfo("Xbox Game Pass", 9.99, 16.99, "Gaming")),
        new AbstractMap.SimpleEntry<>("PLAYSTATION PLUS", new MerchantInfo("PlayStation Plus", 9.99, 17.99, "Gaming")),

        // ── Entertainment — Tickets & Theaters ──────────────────────────────────────
        new AbstractMap.SimpleEntry<>("TICKETMASTER", new MerchantInfo("Ticketmaster", 50.00, 500.00, "Ticket Sales")),
        new AbstractMap.SimpleEntry<>("STUBHUB", new MerchantInfo("StubHub", 40.00, 400.00, "Ticket Resale")),
        new AbstractMap.SimpleEntry<>("AMC THEATERS", new MerchantInfo("AMC Entertainment", 12.00, 40.00, "Movie Theater")),
        new AbstractMap.SimpleEntry<>("CINEMARK", new MerchantInfo("Cinemark Holdings", 10.00, 35.00, "Movie Theater")),
        new AbstractMap.SimpleEntry<>("REGAL CINEMA", new MerchantInfo("Regal Cinema", 11.00, 38.00, "Movie Theater")),

        // ── Utilities & Telecom ──────────────────────────────────────────────────────
        new AbstractMap.SimpleEntry<>("VERIZON", new MerchantInfo("Verizon Communications", 50.00, 200.00, "Telecom")),
        new AbstractMap.SimpleEntry<>("AT&T", new MerchantInfo("AT&T Inc.", 45.00, 180.00, "Telecom")),
        new AbstractMap.SimpleEntry<>("TMOBILE", new MerchantInfo("T-Mobile US", 40.00, 160.00, "Telecom")),
        new AbstractMap.SimpleEntry<>("COMCAST", new MerchantInfo("Comcast Corp", 60.00, 250.00, "Internet/Cable")),
        new AbstractMap.SimpleEntry<>("GEICO", new MerchantInfo("GEICO", 100.00, 300.00, "Auto Insurance")),
        new AbstractMap.SimpleEntry<>("STATE FARM", new MerchantInfo("State Farm Fire & Casualty", 90.00, 280.00, "Auto Insurance")),
        new AbstractMap.SimpleEntry<>("ALLSTATE", new MerchantInfo("Allstate", 95.00, 290.00, "Auto Insurance")),

        // ── Healthcare & Fitness ─────────────────────────────────────────────────────
        new AbstractMap.SimpleEntry<>("CVS PHARMACY", new MerchantInfo("CVS Health", 10.00, 150.00, "Pharmacy")),
        new AbstractMap.SimpleEntry<>("WALGREENS", new MerchantInfo("Walgreens", 12.00, 160.00, "Pharmacy")),
        new AbstractMap.SimpleEntry<>("PLANET FITNESS", new MerchantInfo("Planet Fitness", 20.00, 30.00, "Fitness")),
        new AbstractMap.SimpleEntry<>("PELOTON", new MerchantInfo("Peloton Interactive", 40.00, 50.00, "Fitness")),
        new AbstractMap.SimpleEntry<>("EQUINOX", new MerchantInfo("Equinox Holdings", 200.00, 300.00, "Fitness")),
        new AbstractMap.SimpleEntry<>("SURGEON", new MerchantInfo("Prestige Surgical Center", 500.00, 5000.00, "Medical")),

        // ── Financial Services ───────────────────────────────────────────────────────
        new AbstractMap.SimpleEntry<>("CHASE BANK", new MerchantInfo("JPMorgan Chase Bank", 0.00, 100.00, "Banking")),
        new AbstractMap.SimpleEntry<>("BANK OF AMERICA", new MerchantInfo("Bank of America Corp", 0.00, 100.00, "Banking")),
        new AbstractMap.SimpleEntry<>("WELLS FARGO", new MerchantInfo("Wells Fargo Bank", 0.00, 100.00, "Banking")),
        new AbstractMap.SimpleEntry<>("CHARLES SCHWAB", new MerchantInfo("Charles Schwab Inc", 0.00, 1000.00, "Investment")),
        new AbstractMap.SimpleEntry<>("FIDELITY", new MerchantInfo("Fidelity Investments", 0.00, 1000.00, "Investment")),
        new AbstractMap.SimpleEntry<>("PAYPAL", new MerchantInfo("PayPal", 5.00, 500.00, "Payment")),
        new AbstractMap.SimpleEntry<>("STRIPE", new MerchantInfo("Stripe Payments", 0.00, 0.00, "Payment Processing")),

        // ── Home & Garden ────────────────────────────────────────────────────────────
        new AbstractMap.SimpleEntry<>("HOME DEPOT", new MerchantInfo("The Home Depot", 30.00, 500.00, "Home Improvement")),
        new AbstractMap.SimpleEntry<>("LOWES", new MerchantInfo("Lowe's Companies", 25.00, 400.00, "Home Improvement")),
        new AbstractMap.SimpleEntry<>("IKEA", new MerchantInfo("IKEA", 40.00, 800.00, "Furniture")),
        new AbstractMap.SimpleEntry<>("WAYFAIR", new MerchantInfo("Wayfair Inc", 50.00, 600.00, "Furniture")),

        // ── Online Retail ────────────────────────────────────────────────────────────
        new AbstractMap.SimpleEntry<>("AMAZON", new MerchantInfo("Amazon.com", 10.00, 500.00, "E-commerce")),
        new AbstractMap.SimpleEntry<>("EBAY", new MerchantInfo("eBay", 5.00, 1000.00, "Auction")),
        new AbstractMap.SimpleEntry<>("ETSY", new MerchantInfo("Etsy Inc", 5.00, 200.00, "Handmade/Vintage")),
        new AbstractMap.SimpleEntry<>("ALIEXPRESS", new MerchantInfo("AliExpress", 1.00, 100.00, "Chinese E-commerce")),

        // ── Gas & Fuel ───────────────────────────────────────────────────────────────
        new AbstractMap.SimpleEntry<>("SHELL", new MerchantInfo("Shell Oil Company", 30.00, 80.00, "Gas Station")),
        new AbstractMap.SimpleEntry<>("EXXON", new MerchantInfo("Exxon Mobil Corporation", 30.00, 80.00, "Gas Station")),
        new AbstractMap.SimpleEntry<>("CHEVRON", new MerchantInfo("Chevron Corporation", 30.00, 80.00, "Gas Station")),
        new AbstractMap.SimpleEntry<>("TEXACO", new MerchantInfo("Texaco Inc", 28.00, 75.00, "Gas Station")),
        new AbstractMap.SimpleEntry<>("SPEEDWAY", new MerchantInfo("Murphy USA Inc", 25.00, 70.00, "Gas Station")),

        // ── Pet Supplies ─────────────────────────────────────────────────────────────
        new AbstractMap.SimpleEntry<>("PETCO", new MerchantInfo("Petco Health and Wellness", 20.00, 200.00, "Pet Supplies")),
        new AbstractMap.SimpleEntry<>("PETSMART", new MerchantInfo("PetSmart Inc", 25.00, 250.00, "Pet Supplies")),

        // ── Shipping & Delivery ──────────────────────────────────────────────────────
        new AbstractMap.SimpleEntry<>("FEDEX", new MerchantInfo("FedEx", 10.00, 500.00, "Shipping")),
        new AbstractMap.SimpleEntry<>("UPS", new MerchantInfo("United Parcel Service", 8.00, 400.00, "Shipping")),
        new AbstractMap.SimpleEntry<>("USPS", new MerchantInfo("United States Postal Service", 2.00, 100.00, "Postal")),
        new AbstractMap.SimpleEntry<>("DOORDASH", new MerchantInfo("DoorDash", 5.00, 50.00, "Food Delivery")),
        new AbstractMap.SimpleEntry<>("UBER EATS", new MerchantInfo("Uber Eats", 5.00, 50.00, "Food Delivery")),
        new AbstractMap.SimpleEntry<>("GRUBHUB", new MerchantInfo("Grubhub Inc", 5.00, 50.00, "Food Delivery"))
    );

    // ── Public factory methods ────────────────────────────────────────────────────

    /**
     * Generates exactly 200 enrichment requests drawn from the full merchant catalog.
     *
     * <p>Merchants are selected by cycling through the catalog in order
     * ({@code i % merchantKeys.size()}), so every merchant appears at least once in a
     * 200-request run.  Each request gets a randomly generated amount within that
     * merchant's normal range and a randomized city + state location suffix in the
     * description, mimicking how real bank statements look.
     *
     * <p>Transaction dates are spread over the past 30 days ({@code today - i % 30}),
     * giving the results a realistic distribution rather than all being today.
     *
     * @return a new list of 200 {@link EnrichmentRequest} objects, one transaction each
     */
    public static List<EnrichmentRequest> generate200TestCases() {
        List<EnrichmentRequest> testCases = new ArrayList<>(200);
        List<String> merchantKeys = new ArrayList<>(MERCHANTS.keySet());

        for (int i = 0; i < 200; i++) {
            // Cycle through the catalog so every merchant appears at least once
            String merchantKey = merchantKeys.get(i % merchantKeys.size());
            MerchantInfo info = MERCHANTS.get(merchantKey);

            BigDecimal amount = generateAmount(info.minAmount, info.maxAmount);

            // Add a city/state suffix to the raw description — e.g. "STARBUCKS SEATTLE WA #4201"
            String location = generateLocation();
            String description = generateDescription(merchantKey, location);

            EnrichmentRequest.Transaction transaction = new EnrichmentRequest.Transaction(
                description,
                amount,
                // Spread dates over the past 30 days for realism
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
     * Generates test data tuned to a specific test scenario.
     *
     * <p>This is the primary entry point for scenario-based tests in
     * {@link EnrichmentTestHarness}.  Pass one of the four recognized scenario names:
     *
     * <ul>
     *   <li>{@code "CACHE_HEAVY"} — 200 requests concentrated on 5 popular merchants.
     *       After the first pass, nearly all requests should be served from the in-memory
     *       cache.  Use this to measure the cache-hit latency path.</li>
     *   <li>{@code "HIGH_VARIANCE_AMOUNTS"} — Cycles through tiny ($0.01), power-of-10,
     *       and normal amounts.  Confirms the service doesn't break on unusual currency
     *       values.</li>
     *   <li>{@code "EDGE_CASES"} — Starts with known edge cases (special characters,
     *       very large/small amounts) then fills to 200 with normal requests.</li>
     *   <li>{@code "STRESS_TEST"} — 200 identical requests for the same Starbucks
     *       transaction.  All threads race to write the same cache key; the first write
     *       wins and the rest must not corrupt it.</li>
     * </ul>
     *
     * <p>Any unrecognized scenario name falls back to {@link #generate200TestCases()}.
     *
     * @param scenarioType one of the four scenario names above (case-sensitive)
     * @return the generated list of enrichment requests for the chosen scenario
     */
    public static List<EnrichmentRequest> generateScenario(String scenarioType) {
        return switch (scenarioType) {
            case "CACHE_HEAVY"          -> generateCacheHeavyScenario();
            case "HIGH_VARIANCE_AMOUNTS"-> generateHighVarianceAmounts();
            case "EDGE_CASES"           -> generateEdgeCases();
            case "STRESS_TEST"          -> generateStressTest();
            default                     -> generate200TestCases(); // unknown scenario → default data
        };
    }

    // ── Private scenario builders ─────────────────────────────────────────────────

    /**
     * Generates 200 requests using only 5 high-frequency merchants.
     *
     * <p>The outer loop (40 iterations) × 5 merchants = exactly 200 requests.
     * Each pass through the inner loop uses a slightly different store number
     * ({@code merchant + " #" + (i % 10)}) so descriptions vary, but the cache key —
     * derived from the merchant name — stays the same.  This means the first 5
     * requests miss the cache; every subsequent request hits it.
     */
    private static List<EnrichmentRequest> generateCacheHeavyScenario() {
        List<EnrichmentRequest> cases = new ArrayList<>();
        String[] topMerchants = {"STARBUCKS", "AMAZON", "UBER", "MCDONALDS", "WALMART"};
        int caseCount = 0;

        for (int i = 0; i < 40; i++) {
            for (String merchant : topMerchants) {
                MerchantInfo info = MERCHANTS.get(merchant);
                EnrichmentRequest.Transaction transaction = new EnrichmentRequest.Transaction(
                    // Vary store number on each pass so descriptions are not identical,
                    // but the merchant name (cache key) is always the same
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

    /**
     * Generates 200 requests with deliberately extreme transaction amounts.
     *
     * <p>Every third request uses {@code $0.01} (the smallest legal transaction),
     * every second-third uses a power of 10 (1, 10, 100, or 1000) to test boundary
     * handling, and the rest use the merchant's normal amount range.  This helps catch
     * bugs like integer overflow, improper {@code BigDecimal} scaling, or validation
     * that accidentally rejects valid edge-case amounts.
     *
     * <p>Only the first 4 merchants in the catalog are used to keep the test focused
     * on amounts rather than merchant diversity.
     */
    private static List<EnrichmentRequest> generateHighVarianceAmounts() {
        List<EnrichmentRequest> cases = new ArrayList<>();
        List<String> merchantKeys = new ArrayList<>(MERCHANTS.keySet());
        int caseCount = 0;

        for (int i = 0; i < 50; i++) {
            // Limit to first 4 merchants so 50 * 4 = 200 requests total
            for (String merchantKey : merchantKeys.stream().limit(4).toList()) {
                MerchantInfo info = MERCHANTS.get(merchantKey);

                // Rotate through three amount types:
                //   i % 3 == 0 → $0.01 (extreme minimum)
                //   i % 3 == 1 → random power of 10 (1, 10, 100, or 1000)
                //   i % 3 == 2 → normal range for this merchant
                BigDecimal amount = i % 3 == 0
                    ? new BigDecimal("0.01")
                    : i % 3 == 1
                    ? new BigDecimal(Math.pow(10, RANDOM.nextInt(4)))
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

    /**
     * Generates 200 requests that cover tricky edge cases the service must handle.
     *
     * <p>The first 5 requests are hand-crafted to cover specific edge cases:
     * <ol>
     *   <li>$0.01 minimum amount — should not fail validation</li>
     *   <li>Single-word description — no location suffix, no store number</li>
     *   <li>$9,999.99 — largest reasonable hotel charge</li>
     *   <li>$50,000.00 — luxury / outlier transaction (jewelry, car down payment)</li>
     *   <li>Apostrophe and ampersand in description — UTF-8 special characters</li>
     * </ol>
     * The remaining 195 requests are filled with standard catalog data.
     */
    private static List<EnrichmentRequest> generateEdgeCases() {
        List<EnrichmentRequest> cases = new ArrayList<>();
        int caseCount = 0;

        // ── Hand-crafted edge cases ───────────────────────────────────────────────

        // 1. Minimum possible amount — must not fail validation
        EnrichmentRequest.Transaction txn1 = new EnrichmentRequest.Transaction(
            "STARBUCKS #1", new BigDecimal("0.01"), LocalDate.now(), "Starbucks Coffee"
        );
        cases.add(new EnrichmentRequest("test_account_" + caseCount++, List.of(txn1)));

        // 2. Very short description with no location — tests the minimal description path
        EnrichmentRequest.Transaction txn2 = new EnrichmentRequest.Transaction(
            "COFFEE", new BigDecimal("1.00"), LocalDate.now(), "Starbucks Coffee"
        );
        cases.add(new EnrichmentRequest("test_account_" + caseCount++, List.of(txn2)));

        // 3. Large hotel charge — common for multi-night stays; amount is within normal range
        EnrichmentRequest.Transaction txn3 = new EnrichmentRequest.Transaction(
            "LUXURY HOTEL", new BigDecimal("9999.99"), LocalDate.now(), "Hyatt Hotels"
        );
        cases.add(new EnrichmentRequest("test_account_" + caseCount++, List.of(txn3)));

        // 4. Outlier amount — jewelry, car down payment, etc.
        EnrichmentRequest.Transaction txn4 = new EnrichmentRequest.Transaction(
            "DIAMOND RING", new BigDecimal("50000.00"), LocalDate.now(), "Jewelry Store"
        );
        cases.add(new EnrichmentRequest("test_account_" + caseCount++, List.of(txn4)));

        // 5. Apostrophe and ampersand in description — must survive JSON serialization
        EnrichmentRequest.Transaction txn5 = new EnrichmentRequest.Transaction(
            "BILL'S BURGERS & FRIES", new BigDecimal("12.50"), LocalDate.now(), "Bill's Burgers"
        );
        cases.add(new EnrichmentRequest("test_account_" + caseCount++, List.of(txn5)));

        // ── Fill remaining 195 slots with standard catalog data ───────────────────
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

    /**
     * Generates 200 requests that all describe the exact same Starbucks transaction.
     *
     * <p>Every request has the same account ID, description, amount, date, and merchant
     * name.  Under concurrent load this means multiple threads will simultaneously try
     * to write the same cache key.  The service must handle this gracefully:
     * {@code ConcurrentHashMap.computeIfAbsent} guarantees the supplier runs at most
     * once per key, so only one write should reach the database; the rest should find
     * the entry already present.
     */
    private static List<EnrichmentRequest> generateStressTest() {
        List<EnrichmentRequest> cases = new ArrayList<>();

        for (int i = 0; i < 200; i++) {
            EnrichmentRequest.Transaction transaction = new EnrichmentRequest.Transaction(
                // Fixed description and amount — every request is byte-for-byte identical
                "STARBUCKS #1234 MIDTOWN NYC",
                new BigDecimal("5.45"),
                LocalDate.now(),
                "Starbucks Coffee"
            );
            cases.add(new EnrichmentRequest(
                "test_account_stress", // same account ID for all 200
                List.of(transaction)
            ));
        }

        return cases;
    }

    // ── Private helpers ───────────────────────────────────────────────────────────

    /**
     * Returns a random {@link BigDecimal} in [{@code min}, {@code max}] rounded to
     * 2 decimal places.
     *
     * <p>Uses {@link String#format} to round rather than {@code BigDecimal.setScale}
     * to avoid the rounding ambiguity of {@code HALF_UP} vs {@code HALF_EVEN} — the
     * exact cent value doesn't matter here, only that the amount looks realistic.
     *
     * @param min lower bound (inclusive)
     * @param max upper bound (inclusive)
     * @return a random amount within the range
     */
    private static BigDecimal generateAmount(double min, double max) {
        double amount = min + (max - min) * RANDOM.nextDouble();
        return new BigDecimal(String.format("%.2f", amount));
    }

    /**
     * Returns a random US city + state abbreviation string, e.g. {@code "SEATTLE WA"}.
     *
     * <p>The city and state arrays share the same index, so index 10 always gives
     * "SEATTLE WA" — they are never mixed up.  This is a deliberate design choice
     * to keep generated descriptions consistent (you won't see "CHICAGO CA").
     *
     * @return a city-state string in ALL CAPS
     */
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

        // Use the same index for both arrays so city and state always match
        int idx = RANDOM.nextInt(cities.length);
        return cities[idx] + " " + states[idx];
    }

    /**
     * Generates a raw bank statement description for a merchant and city/state.
     *
     * <p>Randomly picks one of four format templates to vary the output:
     * <ol>
     *   <li>{@code "STARBUCKS SEATTLE WA"}</li>
     *   <li>{@code "STARBUCKS #4201 SEATTLE WA"} (store number variant)</li>
     *   <li>{@code "STARBUCKS DENVER CO"} (different random city)</li>
     *   <li>{@code "STARBUCKS-DENVER CO"} (hyphen separator instead of space)</li>
     * </ol>
     * The variety ensures the service is tested against a realistic distribution of
     * description formats, not just a single predictable pattern.
     *
     * @param merchant the merchant key (ALL CAPS), used as-is in the description
     * @param location a city-state string from {@link #generateLocation}
     * @return the formatted bank statement description
     */
    private static String generateDescription(String merchant, String location) {
        String[] formats = {
            merchant + " " + location,                                      // plain
            merchant + " #" + (100 + RANDOM.nextInt(9900)) + " " + location, // with store number
            merchant + " " + generateLocation(),                            // different random city
            merchant + "-" + generateLocation()                             // hyphen separator
        };

        return formats[RANDOM.nextInt(formats.length)];
    }

    // ── Inner class ───────────────────────────────────────────────────────────────

    /**
     * Holds the metadata for a single known merchant.
     *
     * <p>This is a private implementation detail — callers never receive a
     * {@code MerchantInfo} directly; they get fully-formed {@link EnrichmentRequest}
     * objects instead.
     */
    private static class MerchantInfo {
        /** The merchant's canonical name as Plaid returns it (proper casing). */
        final String officialName;

        /** Lowest typical transaction amount for this merchant (in USD). */
        final double minAmount;

        /** Highest typical transaction amount for this merchant (in USD). */
        final double maxAmount;

        /** The category or type of merchant (e.g., "Grocery", "Fast Food"). */
        final String category;

        MerchantInfo(String officialName, double minAmount, double maxAmount, String category) {
            this.officialName = officialName;
            this.minAmount = minAmount;
            this.maxAmount = maxAmount;
            this.category = category;
        }
    }
}
