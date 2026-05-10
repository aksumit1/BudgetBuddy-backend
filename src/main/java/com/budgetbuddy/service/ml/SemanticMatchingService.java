package com.budgetbuddy.service.ml;


import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Semantic Matching Service Uses word embeddings and cosine similarity for semantic category
 * detection
 *
 * <p>Features: - Word embeddings for merchant names and descriptions - Cosine similarity for
 * semantic matching - Context-aware matching (handles synonyms, related terms) - Fallback to
 * keyword-based matching if embeddings unavailable
 *
 * <p>Note: This is a simplified implementation. For production, consider: - Pre-trained word
 * embeddings (Word2Vec, GloVe, FastText) - Sentence embeddings (Universal Sentence Encoder, BERT) -
 * Vector database for fast similarity search
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Service
public class SemanticMatchingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SemanticMatchingService.class);

    // CRITICAL: Shared data service - single source of truth
    private final MerchantCategoryDataService merchantCategoryDataService;

    // Semantic category mappings (simplified - in production, use word embeddings)
    // Maps category keywords to semantic clusters
    // CRITICAL: Thread-safe - use ConcurrentHashMap for concurrent access
    // CRITICAL: Loaded once in memory for speed - all data translated and cached
    private final Map<String, Set<String>> categorySemanticClusters = new ConcurrentHashMap<>();

    // CRITICAL: Flag to track if clusters are loaded (prevents race conditions)
    private volatile boolean clustersLoaded = false;

    // CRITICAL: Lock for initialization (prevents race conditions during startup)
    private final Object initializationLock = new Object();

    // Similarity thresholds.
    // A match must clear BOTH the base-similarity floor (pure text overlap) AND the
    // final-similarity threshold (text + context boost). This prevents pure-context
    // matches where the merchant text itself has nothing in common with the category.
    //
    // History: this was 0.6, dropped to 0.4, then to 0.3 — leading to many false
    // positives where a 0.05 base similarity rode a 0.3 context boost past the bar.
    private static final double SEMANTIC_SIMILARITY_THRESHOLD = 0.50;
    private static final double SEMANTIC_BASE_SIMILARITY_FLOOR = 0.25;
    // Cap context boost so a matched signal can nudge, not dominate, the score.
    private static final double MAX_CONTEXT_BOOST = 0.20;

    // Maximum text length for performance protection
    private static final int MAX_TEXT_LENGTH = 10_000;

    // Minimum token length
    private static final int MIN_TOKEN_LENGTH = 2;

    public SemanticMatchingService(final MerchantCategoryDataService merchantCategoryDataService) {
        // CRITICAL: Store reference to shared service
        this.merchantCategoryDataService = merchantCategoryDataService;
        // CRITICAL: Load clusters from shared service (loaded once, kept in memory for speed)
        loadSemanticClustersFromSharedService();
    }

    /**
     * Load semantic clusters from shared MerchantCategoryDataService CRITICAL: Thread-safe, error
     * handling, boundary checks, race condition prevention CRITICAL: Loaded once in memory for
     * speed - all data translated and cached This replaces the old initializeSemanticClusters()
     * method
     */
    private void loadSemanticClustersFromSharedService() {
        // CRITICAL: Thread-safe initialization with double-check locking
        if (clustersLoaded) {
            return; // Already loaded
        }

        synchronized (initializationLock) {
            // CRITICAL: Double-check after acquiring lock
            if (clustersLoaded) {
                return;
            }

            try {
                // CRITICAL: Clear existing data to prevent stale state
                categorySemanticClusters.clear();

                // CRITICAL: Null check for shared service
                if (merchantCategoryDataService == null) {
                    LOGGER.error(
                            "MerchantCategoryDataService is null. Cannot load semantic clusters. Service will not function correctly.");
                    return;
                }

                // CRITICAL: Load from shared service with error handling
                Map<String, Set<String>> sharedClusters =
                        merchantCategoryDataService.getCategoryToKeywordsMap();

                if (sharedClusters == null || sharedClusters.isEmpty()) {
                    LOGGER.warn(
                            "MerchantCategoryDataService returned empty clusters. Retrying after delay...");
                    // CRITICAL: Retry logic for race conditions (service might not be initialized
                    // yet)
                    try {
                        Thread.sleep(100); // Brief delay for service initialization
                        sharedClusters = merchantCategoryDataService.getCategoryToKeywordsMap();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        LOGGER.warn("Interrupted while waiting for MerchantCategoryDataService");
                    }
                }

                if (sharedClusters == null || sharedClusters.isEmpty()) {
                    LOGGER.error(
                            "MerchantCategoryDataService returned empty clusters after retry. Service will not function correctly.");
                    return;
                }

                // CRITICAL: Boundary check - prevent memory issues
                if (sharedClusters.size() > 1000) {
                    LOGGER.error(
                            "Shared cluster map size ({}) exceeds safety limit. Loading empty clusters.",
                            sharedClusters.size());
                    return;
                }

                // CRITICAL: Load clusters with validation and defensive copies
                int loadedCount = 0;
                int totalKeywords = 0;

                for (final Map.Entry<String, Set<String>> entry : sharedClusters.entrySet()) {
                    final String category = entry.getKey();
                    Set<String> keywords = entry.getValue();

                    // CRITICAL: Null checks
                    if (category == null || keywords == null) {
                        LOGGER.warn("Skipping null category or keywords from shared service");
                        continue;
                    }

                    // CRITICAL: Boundary check per category - prevent very large sets
                    if (keywords.size() > 50_000) {
                        LOGGER.warn(
                                "Category '{}' has too many keywords ({}). Limiting to 50000.",
                                category,
                                keywords.size());
                        // Create a limited set
                        final Set<String> limitedKeywords = new HashSet<>();
                        int count = 0;
                        for (final String keyword : keywords) {
                            if (count >= 50_000) {
                                break;
                            }
                            limitedKeywords.add(keyword);
                            count++;
                        }
                        keywords = limitedKeywords;
                    }

                    // CRITICAL: Create defensive copy to prevent external modification
                    final Set<String> keywordsCopy = new HashSet<>(keywords);

                    // CRITICAL: Store in thread-safe map (loaded once, kept in memory for speed)
                    categorySemanticClusters.put(category, keywordsCopy);
                    loadedCount++;
                    totalKeywords += keywordsCopy.size();
                }

                // CRITICAL: Mark as loaded (volatile for visibility)
                clustersLoaded = true;

                LOGGER.info(
                        "Loaded {} semantic clusters with {} total keywords from MerchantCategoryDataService into SemanticMatchingService (loaded once, kept in memory for speed)",
                        loadedCount,
                        totalKeywords);

                // CRITICAL: Validate loaded data
                if (loadedCount == 0) {
                    LOGGER.error(
                            "No clusters loaded from shared service. SemanticMatchingService will not function correctly.");
                } else if (loadedCount < 10) {
                    LOGGER.warn(
                            "Only {} clusters loaded. Expected at least 10. Service may not function correctly.",
                            loadedCount);
                }

            } catch (OutOfMemoryError e) {
                LOGGER.error("Out of memory loading semantic clusters from shared service", e);
                categorySemanticClusters.clear();
                clustersLoaded = false;
            } catch (Exception e) {
                LOGGER.error(
                        "Error loading semantic clusters from shared service: {}",
                        e.getMessage(),
                        e);
                categorySemanticClusters.clear();
                clustersLoaded = false;
            }
        }
    }

    /**
     * Initialize semantic clusters for categories (DEPRECATED - kept for backward compatibility)
     * CRITICAL: This method is deprecated. Use loadSemanticClustersFromSharedService() instead. In
     * production, this would load from word embeddings CRITICAL: Comprehensive dictionary for bill
     * pay, store, credit card, loan, investment, stocks
     */
    @Deprecated
    private void initializeSemanticClusters() {
        // Groceries cluster - EXPANDED (includes all major grocery stores)
        final Set<String> groceries =
                new HashSet<>(
                        Arrays.asList(
                                "grocery",
                                "supermarket",
                                "halal",
                                "market",
                                "food market",
                                "grocery store",
                                "food store",
                                "market",
                                "foodmart",
                                "hypermarket",
                                "super market",
                                "grocery shopping",
                                "food shopping",
                                "produce",
                                "fresh food",
                                "groceries",
                                "food shop",
                                "grocery market",
                                "food mart",
                                "superstore",
                                "food center",
                                "grocery center",
                                "supermarket shopping",
                                "pantry",
                                "store", // Test case: "Supermarket Shopping"
                                // Major Grocery Stores
                                "safeway",
                                "pcc",
                                "amazon fresh",
                                "qfc",
                                "dk market",
                                "whole foods",
                                "chefstore",
                                "chef store",
                                "chef",
                                "town & country",
                                "town and country",
                                "town&country",
                                "mayuri",
                                "meet fresh",
                                "meetfresh",
                                "sunny honey",
                                "sunnyhoney",
                                "trader joe",
                                "kroger",
                                "publix",
                                "wegmans",
                                "costco",
                                "walmart",
                                "target",
                                "aldi",
                                "lidl",
                                "h-e-b",
                                "heb",
                                "stop & shop",
                                "stop and shop",
                                "giant",
                                "giant eagle",
                                "food lion",
                                "harris teeter",
                                "sprouts",
                                "sprouts farmers market",
                                "winco",
                                "meijer",
                                "hy-vee",
                                "hyvee",
                                "shoprite",
                                "king soopers",
                                "ralphs",
                                "ralph's",
                                "fred meyer",
                                "fredmeyer",
                                "seven eleven",
                                "7-eleven",
                                "7eleven",
                                "circle k",
                                "circlek",
                                // Grocery Delivery
                                "instacart",
                                "shipt"));
        categorySemanticClusters.put("groceries", groceries);

        // Dining cluster - EXPANDED (includes all major food chains, delivery services, and MCC
        // codes)
        final Set<String> dining =
                new HashSet<>(
                        Arrays.asList(
                                "restaurant",
                                "restaur",
                                "diner",
                                "cafe",
                                "café",
                                "bistro",
                                "eatery",
                                "food",
                                "dining",
                                "meal",
                                "lunch",
                                "dinner",
                                "breakfast",
                                "brunch",
                                "breakfast",
                                "fast food",
                                "takeout",
                                "take-out",
                                "delivery",
                                "food delivery",
                                "tiffin",
                                // POS System Codes - CRITICAL: These are restaurant POS systems,
                                // always dining
                                "tst*",
                                "tst",
                                "toast",
                                "toast pos", // Toast POS system (TST* = Toast)
                                "sq*",
                                "sq",
                                "square",
                                "square pos",
                                "square inc", // Square POS system (SQ* = Square, often restaurants)
                                "daeho",
                                "tutta bella",
                                "tuttabella",
                                "simply indian restaur",
                                "simply indian restaurant",
                                "skills rainbow room",
                                "skillsrainbow room",
                                "kyurmaen",
                                "kyurmaen ramen",
                                "tst* messina",
                                "tst*messina",
                                "tst messina",
                                "messina",
                                "tst* deep dive",
                                "tst*deep dive",
                                "tst*deepdive",
                                "tst deep dive",
                                "deep dive",
                                "deepdive",
                                "tst* supreme dumplings",
                                "tst*supreme dumplings",
                                "tst*supremedumplings",
                                "tst supreme dumplings",
                                "supreme dumplings",
                                "supremedumplings",
                                "cucina venti",
                                "cucinaventi",
                                "desi dhaba",
                                "desidhaba",
                                "medocinofarms",
                                "medocino farms",
                                "laughing monk brewing",
                                "laughingmonk brewing",
                                "laughing monk",
                                "laughingmonk",
                                "indian sizzler",
                                "indiansizzler",
                                "shana thai",
                                "shanathai",
                                "tpd",
                                "paypams",
                                "pay pams", // Online school payments for food
                                "burger and kabob hut",
                                "burgerandkabobhut",
                                "kabob hut",
                                "kabobhut",
                                "insomnia cookies",
                                "insomniacookies",
                                "insomnia cookie",
                                "banaras",
                                "banaras restaurant",
                                "banarasrestaurant",
                                "resy",
                                "maxmillen",
                                "maxmillian",
                                "maximilian",
                                "dumplings",
                                "dumpling",
                                "burger",
                                "burgers",
                                "fast food",
                                "fastfood",
                                "grill",
                                "grilled",
                                "thai",
                                "dhaba",
                                "brewing",
                                "brewery",
                                "brew pub",
                                "brewpub",
                                "restaurant meal",
                                "dining out",
                                "eat out",
                                "food service",
                                "catering",
                                "grill",
                                "mediterranean",
                                "mediterranean food",
                                "mediterranean restaurant",
                                "mediterranean cuisine",
                                "mexican",
                                "mexican food",
                                "mexican restaurant",
                                "mexican cuisine",
                                "japanese",
                                "japanese food",
                                "japanese restaurant",
                                "japanese cuisine",
                                "korean",
                                "korean food",
                                "korean restaurant",
                                "korean cuisine",
                                "indian",
                                "indian food",
                                "indian restaurant",
                                "indian cuisine",
                                "persian",
                                "persian food",
                                "persian restaurant",
                                "persian cuisine",
                                "thai",
                                "thai food",
                                "thai restaurant",
                                "thai cuisine",
                                "vietnamese",
                                "vietnamese food",
                                "vietnamese restaurant",
                                "vietnamese cuisine",
                                "indonesian",
                                "indonesian food",
                                "indonesian restaurant",
                                "indonesian cuisine",
                                "malaysian",
                                "malaysian food",
                                "malaysian restaurant",
                                "malaysian cuisine",
                                "singaporean",
                                "singaporean food",
                                "singaporean restaurant",
                                "singaporean cuisine",
                                "filipino",
                                "filipino food",
                                "filipino restaurant",
                                "filipino cuisine",
                                "hawaiian",
                                "hawaiian food",
                                "hawaiian restaurant",
                                "hawaiian cuisine",
                                "philippine",
                                "philippine food",
                                "philippine restaurant",
                                "philippine cuisine",
                                "sushi",
                                "sushi restaurant",
                                "sushi bar",
                                "sushi cuisine",
                                "ramen",
                                "ramen restaurant",
                                "ramen bar",
                                "ramen cuisine",
                                "tonkatsu",
                                "tonkatsu restaurant",
                                "katsu",
                                "karange",
                                "burrito",
                                "tacos",
                                "yakiudon",
                                "yakiudon restaurant",
                                "yakiudon bar",
                                "yakiudon cuisine",
                                "gyudon",
                                "gyudon restaurant",
                                "gyudon bar",
                                "gyudon cuisine",
                                "gyudon",
                                "gyudon restaurant",
                                "gyudon bar",
                                "gyudon cuisine",
                                "bbq",
                                "bbq restaurant",
                                "bbq bar",
                                "bbq cuisine",
                                "bbq",
                                "bbq restaurant",
                                "bbq bar",
                                "bbq cuisine",
                                "noodles",
                                "noodle restaurant",
                                "noodle bar",
                                "noodle cuisine",
                                "dumpling",
                                "dumpling restaurant",
                                "dumpling bar",
                                "dumpling cuisine",
                                "dim sum",
                                "dim sum restaurant",
                                "dim sum bar",
                                "dim sum cuisine",
                                "chinese",
                                "chinese food",
                                "chinese restaurant",
                                "chinese cuisine",
                                "noodles",
                                "noodles restaurant",
                                "noodles bar",
                                "noodles cuisine",
                                "cafe coffee",
                                "coffee purchase",
                                "bread",
                                "burger",
                                "pastry",
                                "baker",
                                "cake", // Test case: "Cafe Coffee"
                                "takeout delivery",
                                "italian",
                                "french",
                                "spanish",
                                "mexican",
                                "subway",
                                "panda express",
                                "starbucks",
                                "starbucks coffee",
                                "starbucks store",
                                "chipotle",
                                "chipotle mex gr",
                                "chipotle mexican grill",
                                "mcdonalds",
                                "mcdonald's",
                                "burger king",
                                "taco bell",
                                "pizza hut",
                                "dominos",
                                "domino's",
                                "panera",
                                "panera bread",
                                "wendy's",
                                "wendys",
                                "kfc",
                                "kentucky fried chicken",
                                "dunkin",
                                "dunkin donuts",
                                "dunkin' donuts",
                                "dairy queen",
                                "dq",
                                "papa john's",
                                "papa johns",
                                "little caesars",
                                "olive garden",
                                "red lobster",
                                "applebees",
                                "applebee's",
                                "outback",
                                "outback steakhouse",
                                "chili's",
                                "chilis",
                                "texas roadhouse",
                                "ihop",
                                "denny's",
                                "dennys",
                                "waffle house",
                                "five guys",
                                "shake shack",
                                "in-n-out",
                                "in n out",
                                "whataburger",
                                "culver's",
                                "culvers",
                                "sonic",
                                "sonic drive-in",
                                "arby's",
                                "arbys",
                                "jack in the box",
                                "white castle",
                                "qdoba",
                                "moe's",
                                "moes",
                                "baja fresh",
                                "del taco",
                                "carl's jr",
                                "carls jr",
                                "hardee's",
                                "hardees",
                                "pf chang's",
                                "pf changs",
                                "p.f. chang's",
                                "cheesecake factory",
                                "red robin",
                                "buffalo wild wings",
                                "bww",
                                "wingstop",
                                "zaxby's",
                                "zaxbys",
                                "hoffman",
                                "hoffmans",
                                "hoffman's",
                                "hoffman bakery",
                                "canam pizza",
                                "canam",
                                "bakery",
                                "pizza",
                                "tgif",
                                "tgif friday",
                                // Delivery Services
                                "doordash",
                                "door dash",
                                "grubhub",
                                "grub hub",
                                "ubereats",
                                "uber eats",
                                "postmates",
                                // MCC Code 5812 - Eating Places, Restaurants
                                "mcc 5812",
                                "mcc5812"));
        categorySemanticClusters.put("dining", dining);

        // Transportation cluster - EXPANDED (includes all gas stations, ride services, state DOTs,
        // and MCC codes)
        final Set<String> transportation =
                new HashSet<>(
                        Arrays.asList(
                                "gas",
                                "gasoline",
                                "fuel",
                                "petrol",
                                "diesel",
                                "gas station",
                                "fuel station",
                                "petrol station",
                                "filling station",
                                "service station",
                                "fuel purchase", // Test case: "Fuel Purchase"
                                "petrol fill up",
                                "fill up", // Test case: "Petrol Fill Up"
                                "toll",
                                "parking",
                                "parking fee",
                                "uber",
                                "lyft",
                                "taxi",
                                "cab",
                                "ride share",
                                "ride",
                                "uber ride",
                                "lyft ride", // Test case: "Uber Ride"
                                "public transport",
                                "transit",
                                "metro",
                                "subway",
                                "bus",
                                "train",
                                "car",
                                "vehicle",
                                "automobile",
                                "auto",
                                "transport",
                                "commute",
                                "airport",
                                "airport cart",
                                "airport chair",
                                "seattleap",
                                "seattle ap",
                                "seattle airport",
                                // State Department of Transportation (DOT) patterns
                                "wsdot",
                                "washington state dot",
                                "goodtogo",
                                "good to go",
                                "good-to-go",
                                "caltrans",
                                "california dot",
                                "fastrak",
                                "fastrak",
                                "ez pass",
                                "nysdot",
                                "new york state dot",
                                "ezpass",
                                "e-zpass",
                                "new york thruway",
                                "txdot",
                                "texas dot",
                                "ez tag",
                                "txtag",
                                "dallas north tollway",
                                "fdot",
                                "florida dot",
                                "sunpass",
                                "epass",
                                "leeway",
                                "idot",
                                "illinois dot",
                                "ipass",
                                "i-pass",
                                "illinois tollway",
                                "massdot",
                                "massachusetts dot",
                                "mass pike",
                                "penn dot",
                                "penndot",
                                "pennsylvania dot",
                                "pa turnpike",
                                "njdot",
                                "new jersey dot",
                                "garden state parkway",
                                "new jersey turnpike",
                                "mdot",
                                "maryland dot",
                                "virginia dot",
                                "vdot",
                                "state dot",
                                "state department of transportation",
                                "department of transportation",
                                "dot toll",
                                "toll road",
                                "tollway",
                                "toll authority",
                                "highway authority",
                                "transportation authority",
                                "turnpike authority",
                                // Additional toll patterns
                                "eractoll",
                                "era toll",
                                "toll payment",
                                "toll charge",
                                "toll fee",
                                "road toll",
                                "bridge toll",
                                "tunnel toll",
                                "highway toll",
                                "expressway toll",
                                // Car Service
                                "car service",
                                "carservice",
                                "honda ctr",
                                "hondactr",
                                "honda car service",
                                "hondacarservice",
                                "toyota ctr",
                                "toyotactr",
                                "toyota car service",
                                "toyotacarservice",
                                "nissan ctr",
                                "nissactr",
                                "nissan car service",
                                "nissacarservice",
                                "ford ctr",
                                "fordctr",
                                "ford car service",
                                "fordcarservice",
                                "chevrolet ctr",
                                "chevroletctr",
                                "chevrolet car service",
                                "chevroletcarservice",
                                "hyundai ctr",
                                "hyundaictr",
                                "hyundai car service",
                                "hyundaicarservice",
                                "kia ctr",
                                "kiactr",
                                "kia car service",
                                "kiacarservice",
                                "volkswagen ctr",
                                "volkswagenctr",
                                "volkswagen car service",
                                "volkswagencarservice",
                                "bmw ctr",
                                "bmwctr",
                                "bmw car service",
                                "bmwcarservice",
                                "audi ctr",
                                "audictr",
                                "audi car service",
                                "audicarservice",
                                "mercedes-benz ctr",
                                "mercedes-benzctr",
                                "mercedes-benz car service",
                                "mercedes-benzcarservice",
                                "porsche ctr",
                                "porschectr",
                                "porsche car service",
                                "porschecarservice",
                                "volvo ctr",
                                "volvoctr",
                                "volvo car service",
                                "volvocarservice",
                                "land rover ctr",
                                "land roverctr",
                                "land rover car service",
                                "land rovercarservice",
                                "jeep ctr",
                                "jeepctr",
                                "jeep car service",
                                "jeepcarservice",
                                "ram ctr",
                                "ramctr",
                                "ram car service",
                                "ramcarservice",
                                "gmc ctr",
                                "gmcctr",
                                "gmc car service",
                                "gmccarservice",
                                "dodge ctr",
                                "dodgectr",
                                "dodge car service",
                                "dodgecarservice",
                                "chrysler ctr",
                                "chryslerctr",
                                "chrysler car service",
                                "chryslercarservice",
                                "fiat ctr",
                                "fiatctr",
                                "fiat car service",
                                "fiatacarservice",
                                "alfa romeo ctr",
                                "alfa romeoctr",
                                "alfa romeo car service",
                                "alfa romeocarservice",
                                "aston martin ctr",
                                "aston martinctr",
                                "aston martin car service",
                                "aston martincarservice",
                                "bentley ctr",
                                "bentleyctr",
                                "bentley car service",
                                "bentleycarservice",
                                "bugatti ctr",
                                "bugattictr",
                                "bugatti car service",
                                "bugatticarservice",
                                "ferrari ctr",
                                "ferrarictr",
                                "ferrari car service",
                                "ferraricarservice",
                                "lamborghini ctr",
                                "lamborghinictr",
                                "lamborghini car service",
                                "lamborghinicarservice",
                                "mclaren ctr",
                                "mclarenctr",
                                "mclaren car service",
                                "mclarencarservice",
                                "porsche ctr",
                                "porschectr",
                                "porsche car service",
                                "porschecarservice",
                                "rolls-royce ctr",
                                "rolls-roycectr",
                                "rolls-royce car service",
                                "rolls-roycecarservice",
                                "bentley ctr",
                                "bentleyctr",
                                "bentley car service",
                                "bentleycarservice",
                                "bugatti ctr",
                                "bugattictr",
                                "bugatti car service",
                                "bugatticarservice",
                                // Gas Stations
                                "costco gas",
                                "costcogas",
                                "buc-ee",
                                "buc-ee's",
                                "bucee",
                                "bucees",
                                "chevron",
                                "shell",
                                "bp",
                                "british petroleum",
                                "exxon",
                                "exxonmobil",
                                "mobil",
                                "valero",
                                "speedway",
                                "7-eleven",
                                "7eleven",
                                "circle k",
                                "circlek",
                                "arco",
                                "am/pm",
                                "ampm",
                                "phillips 66",
                                "phillips66",
                                "kwik sak",
                                "kwiksak",
                                "kwik-sak",
                                "arco express",
                                "arco express gas",
                                "marathon",
                                "marathon petroleum",
                                "citgo",
                                "sunoco",
                                "conoco",
                                "conocophillips",
                                "76",
                                "unocal 76",
                                "quik trip",
                                "quiktrip",
                                "qt",
                                "wawa",
                                "sheetz",
                                "pilot",
                                "pilot flying j",
                                "flying j",
                                "love's",
                                "loves",
                                "loves travel stops",
                                "ta",
                                "travelcenters of america",
                                // MCC Code 5541 - Service Stations (Gas Stations)
                                "mcc 5541",
                                "mcc5541",
                                // Amex Airlines Fee Reimbursement - transportation (even though
                                // it's a credit)
                                "amex airlines fee reimbursement",
                                "amexairlinesfeereimbursement"));
        categorySemanticClusters.put("transportation", transportation);

        // Utilities cluster - COMPREHENSIVE (includes phone, electric, water, cable, internet, and
        // MCC codes)
        final Set<String> utilities =
                new HashSet<>(
                        Arrays.asList(
                                "utility",
                                "utilities",
                                "electric",
                                "electricity",
                                "power",
                                "energy",
                                "water",
                                "sewer",
                                "sewage",
                                "gas utility",
                                "natural gas",
                                "heating",
                                "water utility", // Test case: "Water Utility"
                                "internet",
                                "broadband",
                                "wifi",
                                "wi-fi",
                                "phone",
                                "telephone",
                                "mobile",
                                "cable",
                                "tv",
                                "television",
                                "streaming",
                                "internet service",
                                "internet",
                                "cable tv", // Test case: "Cable TV"
                                "electric bill",
                                "water bill",
                                "gas bill",
                                "utility bill",
                                "power bill",
                                "energy bill",
                                "electric service",
                                "water service",
                                "gas service",
                                "utility payment",
                                "bill payment",
                                "billpay",
                                "bill pay",
                                "billpaying",
                                "utility payment",
                                "ener billpay",
                                "energy billpay", // For "PUGET SOUND ENER BILLPAY"
                                "electric company",
                                "water company",
                                "gas company",
                                "utility company",
                                "power company",
                                "energy company",
                                "municipal utility",
                                "city utility",
                                "public utility",
                                "utility provider",
                                "service provider",
                                // Phone Providers - CRITICAL: Must include full names to prevent
                                // "mobile" matching transportation
                                "verizon",
                                "verizon wireless",
                                "at&t",
                                "att",
                                "at and t",
                                "t-mobile",
                                "tmobile",
                                "t mobile",
                                "sprint",
                                "us cellular",
                                "uscellular",
                                "cricket",
                                "cricket wireless",
                                "boost mobile",
                                "metropcs",
                                "metro pcs",
                                "mint mobile",
                                "google fi",
                                "visible",
                                "straight talk",
                                "xfinity mobile",
                                "xfinitymobile",
                                "xfinity", // Xfinity Mobile must be explicit
                                // Electric Companies
                                "puget sound energy",
                                "pse",
                                "pacific power",
                                "portland general electric",
                                "pge",
                                "southern california edison",
                                "sce",
                                "pg&e",
                                "pacific gas and electric",
                                "san diego gas & electric",
                                "sdge",
                                "duke energy",
                                "dominion energy",
                                "con edison",
                                "coned",
                                "consolidated edison",
                                "national grid",
                                "exelon",
                                "firstenergy",
                                "first energy",
                                "aep",
                                "american electric power",
                                "southern company",
                                "xcel energy",
                                "centerpoint energy",
                                "centerpoint",
                                "entergy",
                                "aes",
                                "aes corporation",
                                // Water Companies
                                "city of bellevue",
                                "city of seattle",
                                "seattle public utilities",
                                "spu",
                                "american water",
                                "california water service",
                                "suez water",
                                "aqua america",
                                // Cable/Internet Providers
                                "comcast",
                                "xfinity",
                                "spectrum",
                                "charter",
                                "charter spectrum",
                                "cox",
                                "cox communications",
                                "optimum",
                                "altice",
                                "frontier",
                                "frontier communications",
                                "centurylink",
                                "century link",
                                "windstream",
                                "suddenlink",
                                "mediacom",
                                "dish",
                                "dish network",
                                "directv",
                                "direct tv",
                                "att u-verse",
                                "att uverse",
                                "fios",
                                "verizon fios",
                                // MCC Codes
                                "mcc 4900",
                                "mcc4900", // Utilities (Electric, Gas, Water, Sanitary)
                                "mcc 4814",
                                "mcc4814", // Telecommunications Equipment and Telephone Sales
                                "mcc 4816",
                                "mcc4816" // Computer Network/Information Services
                        ));
        categorySemanticClusters.put("utilities", utilities);

        // Healthcare cluster - EXPANDED (includes MCC codes and health/fitness facilities)
        final Set<String> healthcare =
                new HashSet<>(
                        Arrays.asList(
                                "pharmacy",
                                "drugstore",
                                "medicine",
                                "medication",
                                "prescription",
                                "doctor",
                                "physician",
                                "hospital",
                                "clinic",
                                "medical",
                                "health",
                                "healthcare",
                                "dental",
                                "dentist",
                                "optometry",
                                "optometrist",
                                "eye care",
                                "vision",
                                "healthcare",
                                "health care",
                                "medical care",
                                "health service",
                                "cvs",
                                "walgreens",
                                "rite aid",
                                "walmart pharmacy",
                                "target pharmacy",
                                "kroger pharmacy",
                                "safeway pharmacy",
                                "costco pharmacy",
                                "emergency",
                                "urgent care",
                                "cvs pharmacy",
                                "cvs pharmacy store",
                                "cvs pharmacy store and clinic",
                                "cvs pharmacy store and clinic",
                                "target pharmacy",
                                "emergency",
                                "urgent care",
                                "emergency care",
                                "medical",
                                "chiropractor",
                                "orthodontist",
                                "optometrist",
                                "optician",
                                "optical goods",
                                "eyeglasses",
                                "clinic",
                                "Kaiser",
                                "Overlake",
                                "Seattle Children's",
                                "Premera",
                                "Swedish Hospital",
                                "Providence",
                                "Virginia Mason",
                                "Seattle Cancer Care Alliance",
                                "Seattle Genetics",
                                // MCC Codes
                                "mcc 5912",
                                "mcc5912", // Drug Stores, Pharmacies
                                "mcc 8011",
                                "mcc8011", // Doctors, Physicians (Not Elsewhere Classified)
                                "mcc 8021",
                                "mcc8021", // Dentists, Orthodontists
                                "mcc 8041",
                                "mcc8041", // Chiropractors
                                "mcc 8042",
                                "mcc8042", // Optometrists, Ophthalmologists
                                "mcc 8043",
                                "mcc8043", // Opticians, Optical Goods and Eyeglasses
                                "mcc 8062",
                                "mcc8062" // Hospitals
                        ));
        categorySemanticClusters.put("healthcare", healthcare);

        // Insurance cluster - NEW
        final Set<String> insurance =
                new HashSet<>(
                        Arrays.asList(
                                "insurance",
                                "car insurance",
                                "auto insurance",
                                "vehicle insurance",
                                "home insurance",
                                "homeowner insurance",
                                "renters insurance",
                                "life insurance",
                                "health insurance",
                                "dental insurance",
                                "vision insurance",
                                "pet insurance",
                                "disability insurance",
                                "travel insurance",
                                "legal insurance",
                                "geico",
                                "state farm",
                                "progressive",
                                "allstate",
                                "usaa",
                                "farmers",
                                "farmers insurance",
                                "liberty mutual",
                                "nationwide",
                                "travelers",
                                "travelers insurance",
                                "american family",
                                "american family insurance",
                                "amfam",
                                "erie insurance",
                                "erie",
                                "metlife",
                                "met life",
                                "aarp",
                                "aarp insurance",
                                "the hartford",
                                "hartford",
                                "esurance",
                                "safeco",
                                "safeco insurance",
                                "northwestern mutual",
                                "new york life",
                                "massmutual",
                                "mass mutual",
                                "prudential",
                                "prudential financial",
                                "aflac",
                                "aflac insurance",
                                "petplan",
                                "pet plan",
                                "healthy paws",
                                "trupanion",
                                "embrace",
                                "embrace pet insurance",
                                "nationwide pet",
                                "pets best",
                                "petsbest",
                                "figo",
                                "figo pet insurance",
                                "blue cross",
                                "blue cross blue shield",
                                "bcbs",
                                "aetna",
                                "cigna",
                                "unitedhealthcare",
                                "united healthcare",
                                "humana",
                                "kaiser permanente",
                                "kaiser",
                                "anthem",
                                "anthem blue cross",
                                // MCC Code
                                "mcc 6300",
                                "mcc6300"));
        categorySemanticClusters.put("insurance", insurance);

        // Income / Payroll cluster - EXPANDED (includes paycheck, income, IRS categories, GAAP
        // revenue)
        final Set<String> income =
                new HashSet<>(
                        Arrays.asList(
                                "income",
                                "salary",
                                "payroll",
                                "paycheck",
                                "wage",
                                "wages",
                                "earnings",
                                "pay",
                                "payment",
                                "compensation",
                                "remuneration",
                                "stipend",
                                "direct deposit",
                                "directdeposit",
                                "ach deposit",
                                "ach credit",
                                "payroll deposit",
                                "payroll direct deposit",
                                "salary deposit",
                                "salary direct deposit",
                                "payroll payment",
                                "payroll ach",
                                "payroll transfer",
                                "payroll credit",
                                "payroll ach credit",
                                "payroll ach deposit",
                                "employer payment",
                                "employer deposit",
                                "employer payroll",
                                "employer direct deposit",
                                // ACH SEC Codes for Income
                                "ppd",
                                "ppd entry",
                                "ppd credit",
                                "bacs direct credit",
                                // ACH Transaction Codes for Income
                                "ach 21",
                                "ach 31",
                                "ach 41",
                                // IRS Income Categories
                                "irs w-2",
                                "irs w2",
                                "irs 1099",
                                "irs 1099-misc",
                                "irs 1099-int",
                                "irs 1099-div",
                                "irs 1099-b",
                                "irs 1099-r",
                                "irs 1099-g",
                                "irs 1099-s",
                                "irs 1099-k",
                                "irs schedule c",
                                "irs schedule e",
                                "irs schedule f",
                                "irs wages",
                                // GAAP Revenue Categories
                                "gaap revenue",
                                "gaap sales revenue",
                                "gaap service revenue",
                                "gaap interest revenue",
                                "gaap dividend revenue",
                                "gaap accounts receivable",
                                // Payroll Providers
                                "adp",
                                "automatic data processing",
                                "paychex",
                                "paychex inc",
                                "paycom",
                                "paycom software",
                                "paylocity",
                                "justworks",
                                "gusto",
                                "bamboohr",
                                "workday",
                                "workday payroll",
                                "zenefits",
                                "triple net",
                                "triplenet",
                                "ceridian",
                                "ceridian dayforce",
                                "kronos",
                                "ukg",
                                "ultimate software",
                                "paycor",
                                "isolved",
                                "isolved hcm",
                                "quickbooks payroll",
                                "intuit payroll",
                                "square payroll",
                                "wave payroll",
                                "onpay",
                                "patriot software",
                                "surepayroll",
                                "sure payroll",
                                "payroll plus",
                                "payroll plus solutions",
                                "heartland payroll",
                                "paychex flex",
                                "adp workforce now",
                                "adp run",
                                "adp totalsource",
                                "adp vantage",
                                "adp ez labor",
                                "adp mobile",
                                "adp portal",
                                "paychex portal",
                                "paychex mobile",
                                // Government Payroll
                                "social security",
                                "ssa",
                                "social security administration",
                                "unemployment",
                                "unemployment insurance",
                                "ui",
                                "state unemployment",
                                "federal unemployment",
                                "veterans affairs",
                                "va",
                                "department of veterans affairs",
                                "veterans benefits",
                                "va benefits",
                                "military pay",
                                "military payroll",
                                "defense finance",
                                "dfas",
                                "defense finance and accounting service",
                                "us treasury",
                                "treasury department",
                                "irs refund",
                                "tax refund",
                                "federal tax refund",
                                "state tax refund",
                                "internal revenue service",
                                "irs"
                        // Major Employers (Fortune 500)

                        ));
        categorySemanticClusters.put("income", income);

        // Deposit cluster - EXPANDED (includes BAI2 deposit codes, ISO 20022, ACH)
        final Set<String> deposit =
                new HashSet<>(
                        Arrays.asList(
                                "deposit",
                                "deposits",
                                "deposited",
                                "depositing",
                                "depositor",
                                "bank deposit",
                                "checking deposit",
                                "savings deposit",
                                "account deposit",
                                "fund deposit",
                                "money deposit",
                                "cash deposit",
                                "check deposit",
                                "wire deposit",
                                "ach deposit",
                                "direct deposit",
                                "electronic deposit",
                                "online deposit",
                                "mobile deposit",
                                "remote deposit",
                                "deposit transaction",
                                "deposit payment",
                                "deposit transfer",
                                "deposit credit",
                                // BAI2 Deposit Codes
                                "bai 100",
                                "bai 102",
                                "bai 103",
                                "bai 105",
                                "bai 107",
                                "bai 109",
                                "bai 111",
                                "bai 115",
                                "bai 116",
                                "bai 121",
                                "bai 123",
                                "bai 125",
                                "bai 128",
                                "bai 135",
                                "bai 140",
                                "bai 150",
                                "bai 155",
                                "bai 160",
                                "bai 162",
                                "bai 163",
                                "bai 164",
                                "bai 165",
                                "bai 170",
                                "bai 180",
                                "bai 182",
                                "bai 183",
                                "bai 190",
                                "bai 192",
                                "bai 193",
                                "bai 194",
                                "bai 195",
                                "bai 200",
                                "bai 201",
                                "bai 202",
                                "bai 210",
                                "bai 211",
                                "bai 220",
                                "bai 221",
                                // ISO 20022 Deposit Messages
                                "camt.052",
                                "camt.053",
                                "camt.054",
                                "pacs.008",
                                "pacs.009",
                                // ACH Deposit Codes
                                "ppd credit",
                                "ccd credit",
                                "iat credit",
                                "web credit",
                                "tel credit",
                                "ctx credit",
                                "ach 21",
                                "ach 31",
                                "ach 41"));
        categorySemanticClusters.put("deposit", deposit);

        // Shopping cluster - EXPANDED (includes clothing/apparel)
        final Set<String> shopping =
                new HashSet<>(
                        Arrays.asList(
                                "shopping",
                                "retail",
                                "store",
                                "shop",
                                "merchandise",
                                "purchase",
                                "buy",
                                "buying",
                                "mall",
                                "department store",
                                "boutique",
                                "outlet",
                                "retail store",
                                "retail shop",
                                "shopping center",
                                "shopping mall",
                                "retail outlet",
                                "store purchase",
                                "retail purchase",
                                "shopping trip",
                                "clothing",
                                "apparel",
                                "men's clothing",
                                "mens clothing",
                                "women's clothing",
                                "womens clothing",
                                "men's apparel",
                                "mens apparel",
                                "women's apparel",
                                "womens apparel",
                                "fashion",
                                "garment",
                                "attire",
                                "wardrobe"));
        categorySemanticClusters.put("shopping", shopping);

        // Entertainment cluster - EXPANDED (includes streaming services and MCC codes)
        final Set<String> entertainment =
                new HashSet<>(
                        Arrays.asList(
                                "entertainment",
                                "movie",
                                "cinema",
                                "theater",
                                "theatre",
                                "film",
                                "streaming",
                                "netflix",
                                "hulu",
                                "huluplus",
                                "hulu plus",
                                "disney",
                                "disney+",
                                "disney plus",
                                "amazon prime",
                                "prime video",
                                "spotify",
                                "apple music",
                                "youtube premium",
                                "youtube tv",
                                "peacock",
                                "paramount+",
                                "paramount plus",
                                "hbo max",
                                "max",
                                "hbo",
                                "showtime",
                                "starz",
                                "crunchyroll",
                                "funimation",
                                "music",
                                "concert",
                                "show",
                                "event",
                                "ticket",
                                "sports",
                                "game",
                                "entertainment service",
                                "media",
                                "video",
                                "audio",
                                "amc",
                                "amc theaters",
                                "cinemark",
                                "regal",
                                "top golf",
                                "topgolf",
                                // MCC Codes
                                "mcc 7832",
                                "mcc7832", // Motion Picture Theaters
                                "mcc 7922",
                                "mcc7922" // Theatrical Producers and Ticket Agencies
                        ));
        categorySemanticClusters.put("entertainment", entertainment);

        // Subscriptions cluster - NEW (separate from entertainment for better categorization)
        final Set<String> subscriptions =
                new HashSet<>(
                        Arrays.asList(
                                "subscription",
                                "subscriptions",
                                "recurring",
                                "monthly subscription",
                                "annual subscription",
                                "yearly subscription",
                                "premium",
                                "premium membership",
                                "netflix",
                                "hulu",
                                "disney+",
                                "disney plus",
                                "amazon prime",
                                "prime video",
                                "netflix",
                                "subscription", // Test case: "NETFLIX" with "Subscription"
                                "spotify",
                                "apple music",
                                "youtube premium",
                                "youtube tv",
                                "peacock",
                                "paramount+",
                                "paramount plus",
                                "hbo max",
                                "max",
                                "hbo",
                                "showtime",
                                "starz",
                                "crunchyroll",
                                "funimation",
                                "adobe",
                                "adobe creative cloud",
                                "microsoft 365",
                                "office 365",
                                "dropbox",
                                "icloud",
                                "google drive",
                                "google one",
                                "audible",
                                "kindle unlimited",
                                "scribd",
                                "linkedin",
                                "linkedin premium",
                                "grammarly",
                                "nordvpn",
                                "expressvpn",
                                "surfshark",
                                "zoom",
                                "slack",
                                "github",
                                "github pro",
                                "canva",
                                "canva pro",
                                // News/Investment Journals
                                "barrons",
                                "barron's",
                                "barron",
                                "j*barrons",
                                "j barrons",
                                "new york times",
                                "nytimes",
                                "ny times",
                                "the new york times",
                                "wall street journal",
                                "wsj",
                                "the wall street journal",
                                "financial times",
                                "ft.com",
                                "the financial times",
                                "economist",
                                "the economist",
                                "bloomberg",
                                "bloomberg news",
                                "reuters",
                                "reuters news",
                                "cnn",
                                "cnn news",
                                "bbc",
                                "bbc news",
                                "washington post",
                                "wapo",
                                "the washington post",
                                "usa today",
                                "usatoday",
                                "los angeles times",
                                "latimes",
                                "chicago tribune",
                                "boston globe",
                                "the boston globe"));
        categorySemanticClusters.put("subscriptions", subscriptions);

        // Payment cluster - EXPANDED (includes credit card payments, loan payments, ACH SEC codes,
        // IRS tax payments, GAAP expenses)
        final Set<String> payment =
                new HashSet<>(
                        Arrays.asList(
                                "payment",
                                "pay",
                                "paying",
                                "autopay",
                                "auto pay",
                                "auto-pay",
                                "automatic payment",
                                "bill payment",
                                "billpay",
                                "bill pay",
                                "credit card payment",
                                "card payment",
                                "card pay",
                                "credit payment",
                                "loan payment",
                                "loan pay",
                                "debt payment",
                                "debt pay",
                                "installment",
                                "installment payment",
                                "monthly payment",
                                "e-payment",
                                "e payment",
                                "electronic payment",
                                "online payment",
                                "payment processing",
                                "payment service",
                                "payment gateway",
                                "credit card autopay",
                                "card autopay",
                                "credit autopay",
                                "loan autopay",
                                "debt autopay",
                                "auto payment",
                                "automatic pay",
                                "recurring payment",
                                "scheduled payment",
                                "payment plan",
                                // ACH SEC Codes for Payments
                                "ppd debit",
                                "web",
                                "web entry",
                                "web debit",
                                "tel",
                                "tel entry",
                                "tel debit",
                                "arc",
                                "arc entry",
                                "boc",
                                "boc entry",
                                "ckd",
                                "ckd entry",
                                "pop",
                                "pop entry",
                                "pos",
                                "pos entry",
                                "rcp",
                                "rcp entry",
                                "xck",
                                "xck entry",
                                "trc",
                                "trc entry",
                                "trx",
                                "trx entry",
                                "bacs direct debit",
                                // ACH Transaction Codes for Payments
                                "ach 22",
                                "ach 23",
                                "ach 24",
                                "ach 32",
                                "ach 33",
                                "ach 34",
                                "ach 42",
                                "ach 43",
                                "ach 44",
                                // IRS Tax Payment Categories
                                "irs estimated tax",
                                "irs tax payment",
                                "irs federal tax",
                                "irs state tax",
                                "irs local tax",
                                "irs payroll tax",
                                "irs self-employment tax",
                                "irs income tax",
                                "irs sales tax",
                                "irs property tax",
                                "irs excise tax",
                                "irs gift tax",
                                "irs estate tax",
                                // IRS Business Expense Categories
                                "irs commissions and fees",
                                "irs contract labor",
                                "irs depreciation",
                                "irs employee benefit programs",
                                "irs interest",
                                "irs legal and professional services",
                                "irs pension and profit-sharing plans",
                                "irs taxes and licenses",
                                "irs other expenses",
                                // GAAP Expense Categories
                                "gaap expenses",
                                "gaap cost of goods sold",
                                "gaap operating expenses",
                                "gaap selling expenses",
                                "gaap administrative expenses",
                                "gaap interest expense",
                                "gaap tax expense",
                                "gaap depreciation expense",
                                "gaap amortization expense",
                                "gaap liabilities",
                                "gaap current liabilities",
                                "gaap accounts payable",
                                "gaap accrued expenses",
                                "gaap long-term debt",
                                "gaap prepaid expenses"));
        categorySemanticClusters.put("payment", payment);

        // Credit Card Payment cluster - NEW (specific for credit card payments)
        final Set<String> creditCardPayment =
                new HashSet<>(
                        Arrays.asList(
                                "credit card",
                                "creditcard",
                                "credit crd",
                                "card payment",
                                "credit card payment",
                                "credit payment",
                                "card pay",
                                "credit pay",
                                "credit card autopay",
                                "card autopay",
                                "credit autopay",
                                "credit card auto pay",
                                "card auto pay",
                                "credit auto pay",
                                "credit card e-payment",
                                "card e-payment",
                                "credit e-payment",
                                "credit card bill",
                                "card bill",
                                "credit bill",
                                "credit card statement",
                                "card statement",
                                "credit statement",
                                "credit card balance",
                                "card balance",
                                "credit balance",
                                "credit card pay",
                                "card pay",
                                "credit pay",
                                "citi autopay",
                                "chase credit",
                                "wf credit",
                                "wells fargo credit",
                                "chase credit card",
                                "chase credit crd",
                                "citi",
                                "citi credit",
                                "chase credit",
                                "auto pay",
                                "autopay", // Test case: "CHASE CREDIT" with "Auto Pay"
                                "discover payment",
                                "amex payment",
                                "american express payment",
                                "visa payment",
                                "mastercard payment",
                                "credit card pmt",
                                "card pmt",
                                "amz storecrd pmt",
                                "amz storecrd pmt payment",
                                "amazon store card payment", // Test case: "AMZ_STORECRD_PMT
                                // PAYMENT"
                                "amazon store card",
                                "amz store card",
                                "amazon credit card",
                                "amzstorecrd",
                                "amz storecrd",
                                "storecrd pmt",
                                "storecrd payment", // Additional variations for AMZ_STORECRD_PMT
                                "amz store card pmt",
                                "amazon store card pmt",
                                "amz credit card payment"));
        // CRITICAL FIX: Merge with payment instead of overwriting
        payment.addAll(creditCardPayment);
        categorySemanticClusters.put("payment", payment);

        // Loan Payment cluster - NEW
        final Set<String> loanPayment =
                new HashSet<>(
                        Arrays.asList(
                                "loan",
                                "loan payment",
                                "loan pay",
                                "loan repayment",
                                "loan repay",
                                "debt",
                                "debt payment",
                                "debt pay",
                                "debt repayment",
                                "debt repay",
                                "mortgage",
                                "mortgage payment",
                                "mortgage pay",
                                "mortgage repay",
                                "car loan",
                                "auto loan",
                                "vehicle loan",
                                "car payment",
                                "auto payment",
                                "car loan",
                                "monthly payment", // Test case: "CAR LOAN" with "Monthly Payment"
                                "student loan",
                                "student loan payment",
                                "education loan",
                                "personal loan",
                                "personal loan payment",
                                "personal loan pay",
                                "home loan",
                                "home loan payment",
                                "housing loan",
                                "loan installment",
                                "debt installment",
                                "loan monthly",
                                "debt monthly",
                                "loan autopay",
                                "debt autopay",
                                "loan auto pay",
                                "debt auto pay",
                                "loan service",
                                "debt service",
                                "loan provider",
                                "debt provider",
                                "loan payment",
                                "loan repayment", // Test case: "Loan Payment"
                                "citi autopay payment",
                                "citi autopay",
                                "autopay payment", // Test case: "CITI AUTOPAY PAYMENT"
                                "citi",
                                "chase autopay",
                                "bank autopay",
                                "credit card autopay"));
        // CRITICAL FIX: Merge with payment instead of overwriting
        payment.addAll(loanPayment);
        categorySemanticClusters.put("payment", payment);

        // Investment cluster - NEW (comprehensive)
        final Set<String> investment =
                new HashSet<>(
                        Arrays.asList(
                                "investment",
                                "invest",
                                "investing",
                                "invested",
                                "investor",
                                "stocks",
                                "stock",
                                "equity",
                                "equities",
                                "share",
                                "shares",
                                "stock market",
                                "stock exchange",
                                "securities",
                                "security",
                                "trading",
                                "trade",
                                "trader",
                                "trading account",
                                "brokerage",
                                "broker",
                                "brokerage account",
                                "investment account",
                                "portfolio",
                                "portfolio management",
                                "asset management",
                                "mutual fund",
                                "mutual funds",
                                "fund",
                                "funds",
                                "etf",
                                "etfs",
                                "index fund",
                                "index funds",
                                "bond",
                                "bonds",
                                "treasury",
                                "treasuries",
                                "cd",
                                "certificate of deposit",
                                "certificate",
                                "cd investment",
                                "cd interest",
                                "bank", // Test case: "BANK" with "CD Interest"
                                "retirement",
                                "retirement account",
                                "401k",
                                "401 k",
                                "401k contribution",
                                "401 k contribution",
                                "ira",
                                "roth ira",
                                "ira contribution",
                                "roth ira contribution",
                                "pension",
                                "pension fund",
                                "retirement fund",
                                "retirement plan",
                                "contribution",
                                "401k contribution",
                                "retirement contribution",
                                "hsa",
                                "health savings account",
                                "hsa investment",
                                "investment firm",
                                "investment company",
                                "asset manager",
                                "fidelity",
                                "vanguard",
                                "schwab",
                                "charles schwab",
                                "morgan stanley",
                                "goldman sachs",
                                "jpmorgan",
                                "jp morgan",
                                "morganstanley",
                                "investment transfer",
                                "stock purchase",
                                "bond purchase",
                                "online transfer from morganstanley",
                                "online transfer from morgan stanley",
                                "transfer from morganstanley",
                                "transfer from morgan stanley",
                                "transfer from fidelity",
                                "transfer from vanguard",
                                "transfer from schwab",
                                "fund purchase",
                                "investment purchase",
                                "securities purchase",
                                "dividend",
                                "dividends",
                                "dividend payment",
                                "dividend income",
                                "capital gains",
                                "capital gain",
                                "gain",
                                "gains",
                                "profit",
                                "profits",
                                "investment income",
                                "investment return",
                                "return on investment",
                                "roi"));
        categorySemanticClusters.put("investment", investment);

        // Stocks cluster - EXPANDED (merged with investment)
        // Add stock keywords to investment cluster
        investment.addAll(
                Arrays.asList(
                        "stocks",
                        "stock",
                        "equity",
                        "equities",
                        "share",
                        "shares",
                        "stock market",
                        "stock exchange",
                        "nyse",
                        "nasdaq",
                        "securities",
                        "stock trading",
                        "equity trading",
                        "share trading",
                        "stock purchase",
                        "equity purchase",
                        "share purchase",
                        "stock sale",
                        "equity sale",
                        "share sale",
                        "stock broker",
                        "equity broker",
                        "share broker",
                        "stock account",
                        "equity account",
                        "share account",
                        "stock portfolio",
                        "equity portfolio",
                        "share portfolio",
                        "stock investment",
                        "equity investment",
                        "share investment",
                        "dividend",
                        "dividends",
                        "dividend payment",
                        "dividend income",
                        "stock dividend",
                        "equity dividend",
                        "share dividend"));
        categorySemanticClusters.put("investment", investment);

        // Bill Pay cluster - EXPANDED (merged with utilities for bill payments)
        // Add bill pay keywords to utilities cluster
        utilities.addAll(
                Arrays.asList(
                        "bill",
                        "bills",
                        "bill payment",
                        "bill pay",
                        "billpay",
                        "billpaying",
                        "bill payment service",
                        "bill pay service",
                        "billpay service",
                        "online bill pay",
                        "online bill payment",
                        "electronic bill pay",
                        "automatic bill pay",
                        "auto bill pay",
                        "auto bill payment",
                        "bill pay service",
                        "bill payment provider",
                        "bill pay provider",
                        "utility bill",
                        "utility bill pay",
                        "utility bill payment",
                        "electric bill",
                        "water bill",
                        "gas bill",
                        "phone bill",
                        "internet bill",
                        "cable bill",
                        "tv bill",
                        "insurance bill",
                        "medical bill",
                        "credit card bill",
                        "loan bill",
                        "mortgage bill",
                        "recurring bill",
                        "monthly bill",
                        "quarterly bill",
                        "annual bill",
                        "bill reminder",
                        "bill due",
                        "bill statement",
                        "bill invoice"));
        categorySemanticClusters.put("utilities", utilities);

        // Store cluster - EXPANDED (merged with shopping)
        // Add store keywords to shopping cluster
        shopping.addAll(
                Arrays.asList(
                        "store",
                        "stores",
                        "shop",
                        "shops",
                        "retail store",
                        "retail shop",
                        "department store",
                        "convenience store",
                        "grocery store",
                        "food store",
                        "clothing store",
                        "apparel store",
                        "shoe store",
                        "electronics store",
                        "hardware store",
                        "home improvement store",
                        "furniture store",
                        "book store",
                        "bookstore",
                        "toy store",
                        "sports store",
                        "store purchase",
                        "store transaction",
                        "store payment",
                        "in store",
                        "in-store",
                        "store visit",
                        "store shopping",
                        "mini mountain",
                        "minimountain",
                        "ski gear",
                        "skigear",
                        "ski equipment",
                        "skiequipment",
                        "sports equipment",
                        "sportsequipment",
                        "outdoor gear",
                        "outdoorgear"));
        categorySemanticClusters.put("shopping", shopping);

        // Transfer cluster - NEW
        // Transfer cluster - COMPREHENSIVE (includes ISO 20022, SWIFT, ACH SEC, IBAN/SEPA,
        // Fedwire/CHIPS, BAI2, FINCEN, inter-bank systems)
        final Set<String> transfer =
                new HashSet<>(
                        Arrays.asList(
                                "transfer",
                                "transfers",
                                "transferring",
                                "transferred",
                                "account transfer",
                                "bank transfer",
                                "wire transfer",
                                "ach transfer",
                                "online transfer",
                                "electronic transfer",
                                "e-transfer",
                                "transfer to",
                                "transfer from",
                                "transfer between",
                                "money transfer",
                                "fund transfer",
                                "payment transfer",
                                "internal transfer",
                                "external transfer",
                                "inter-account transfer",
                                "inter-bank transfer",
                                "intra-bank transfer",
                                "intra-account transfer",
                                "transfer fee",
                                "transfer service",
                                "transfer processing",
                                // Financial Data Aggregators
                                "plaid",
                                "yodlee",
                                "finicity",
                                "mx",
                                "mx technologies",
                                "akoya",
                                "alloy",
                                "alloy labs",
                                "teller",
                                "teller.io",
                                "sophtron",
                                "quovo",
                                "envestnet yodlee",
                                "envestnet",
                                "intuit",
                                "mint",
                                "credit karma",
                                "personal capital",
                                "empower",
                                "empower retirement",
                                // ISO 20022
                                "iso 20022",
                                "iso20022",
                                "pain.001",
                                "pain.002",
                                "pain.008",
                                "pain.009",
                                "camt.052",
                                "camt.053",
                                "camt.054",
                                "camt.056",
                                "camt.057",
                                "pacs.008",
                                "pacs.009",
                                "pacs.002",
                                // SWIFT / BIC
                                "swift",
                                "swift code",
                                "bic",
                                "bic code",
                                "bank identifier code",
                                "mt 103",
                                "mt103",
                                "mt 202",
                                "mt202",
                                "mt 940",
                                "mt940",
                                "mt 942",
                                "mt942",
                                "mt 950",
                                "mt950",
                                "mt 101",
                                "mt101",
                                "mt 102",
                                "mt102",
                                "mt 104",
                                "mt104",
                                "mt 110",
                                "mt110",
                                "mt 111",
                                "mt111",
                                "mt 200",
                                "mt200",
                                "mt 201",
                                "mt201",
                                "mt 210",
                                "mt210",
                                "mt 900",
                                "mt900",
                                "mt 910",
                                "mt910",
                                // IBAN / SEPA
                                "iban",
                                "international bank account number",
                                "iban transfer",
                                "sepa",
                                "sepa transfer",
                                "sepa credit transfer",
                                "sepa direct debit",
                                "sepa instant",
                                "sepa instant credit transfer",
                                // ACH SEC Codes
                                "ccd",
                                "ccd entry",
                                "ccd credit",
                                "ccd debit",
                                "iat",
                                "iat entry",
                                "iat credit",
                                "iat debit",
                                "ctx",
                                "ctx entry",
                                "ctx credit",
                                "ctx debit",
                                // Fedwire / CHIPS
                                "fedwire",
                                "fed wire",
                                "federal reserve wire",
                                "fedwire funds transfer",
                                "fedwire credit",
                                "fedwire debit",
                                "routing number",
                                "aba routing number",
                                "aba number",
                                "routing transit number",
                                "rtn",
                                "chips",
                                "clearing house interbank payments",
                                "chips transfer",
                                "chips credit",
                                "chips debit",
                                // BAI2
                                "bai2",
                                "bai 2",
                                "bai format",
                                "bai code",
                                // FINCEN
                                "fincen",
                                "bsa",
                                "bank secrecy act",
                                "sar",
                                "suspicious activity report",
                                "ctr",
                                "currency transaction report",
                                "sdn",
                                "specially designated nationals",
                                "ofac",
                                "office of foreign assets control",
                                // Inter-bank Systems (Global)
                                "target2",
                                "target 2",
                                "target2 transfer",
                                "chaps",
                                "chaps transfer",
                                "bacs",
                                "bacs transfer",
                                "faster payments",
                                "faster payments service",
                                "fps",
                                "eft",
                                "electronic funds transfer",
                                "eft canada",
                                "interac",
                                "interac e-transfer",
                                "interac etransfer",
                                "npp",
                                "new payments platform",
                                "npp australia",
                                "neft",
                                "national electronic funds transfer",
                                "neft india",
                                "rtgs",
                                "real time gross settlement",
                                "rtgs india",
                                "imps",
                                "immediate payment service",
                                "imps india",
                                "upi",
                                "unified payments interface",
                                "upi india",
                                "tips",
                                "target instant payment settlement",
                                // ANSI X9
                                "ansi x9",
                                "ansix9",
                                "ansi x9.13",
                                "ansi x9.100",
                                "ansi x9.100-140",
                                "ansi x9.37",
                                "ansi x9.100-187",
                                // MCC Codes
                                "mcc 6012",
                                "mcc6012",
                                "mcc 6010",
                                "mcc6010"));
        categorySemanticClusters.put("transfer", transfer);

        // Cash cluster - NEW
        final Set<String> cash =
                new HashSet<>(
                        Arrays.asList(
                                "cash",
                                "cash withdrawal",
                                "cash withdraw",
                                "cash out",
                                "cashout",
                                "atm",
                                "atms",
                                "atm withdrawal",
                                "atm withdraw",
                                "atm cash",
                                "cash advance",
                                "cashback",
                                "cash back",
                                "cash return",
                                "cash payment",
                                "cash transaction",
                                "cash purchase",
                                "cash deposit",
                                "cash in",
                                "withdrawal",
                                "withdraw"));
        categorySemanticClusters.put("cash", cash);

        // Pet cluster - NEW
        final Set<String> pet =
                new HashSet<>(
                        Arrays.asList(
                                "pet",
                                "pets",
                                "pet supplies",
                                "pet supply",
                                "petsupplies",
                                "petsmart",
                                "petco",
                                "pet supplies plus",
                                "petcare",
                                "pet care",
                                "petcare clinic",
                                "pet care clinic",
                                "petland",
                                "chewy",
                                "petmeds",
                                "pet supermarket",
                                "pet food",
                                "petfood",
                                "dog food",
                                "cat food",
                                "pet store",
                                "petstore",
                                "sp farmers",
                                "fetch bones",
                                "fetchbones",
                                "mudbay",
                                "mud bay",
                                "chewy",
                                "fido",
                                "sea king aquarium"));
        categorySemanticClusters.put("pet", pet);

        // Health cluster - NEW (fitness, gyms, beauty salons, hair cuts, sports activities)
        final Set<String> health =
                new HashSet<>(
                        Arrays.asList(
                                "fitness",
                                "gym",
                                "health club",
                                "athletic club",
                                "fitness center",
                                "workout",
                                "personal trainer",
                                "exercise",
                                "proclub",
                                "pro club",
                                "24 hour fitness",
                                "24hour fitness",
                                "gold's gym",
                                "golds gym",
                                "planet fitness",
                                "equinox",
                                "lifetime fitness",
                                "ymca",
                                "la fitness",
                                "crunch fitness",
                                "anytime fitness",
                                "orange theory",
                                "crossfit",
                                "beauty salon",
                                "beautysalon",
                                "beauty parlor",
                                "beautyparlor",
                                "hair salon",
                                "hairsalon",
                                "hair cut",
                                "haircut",
                                "hair cuts",
                                "haircuts",
                                "hair color",
                                "haircolor",
                                "body waxing",
                                "bodywaxing",
                                "waxing",
                                "makeup",
                                "make up",
                                "beauty studio",
                                "beautystudio",
                                "salon",
                                "supercuts",
                                "super cuts",
                                "great clips",
                                "greatclips",
                                "lucky hair salon",
                                "luckyhair",
                                "nails",
                                "nail salon",
                                "nailsalon",
                                "nail",
                                "manicure",
                                "pedicure",
                                "spa",
                                "massage",
                                "massages",
                                "toes",
                                "skin",
                                "skin care",
                                "skincare",
                                "stop 4 nails",
                                "stop4nails",
                                "stop four nails",
                                "stopfournails",
                                "cosmetic store",
                                "cosmeticstore",
                                "cosmetics",
                                "makeup store",
                                "makeupstore",
                                "new york cosmetic",
                                "ny cosmetic",
                                "cosmetic shop",
                                "cosmeticshop",
                                "golf",
                                "tennis",
                                "soccer",
                                "football",
                                "basketball",
                                "baseball",
                                "swimming",
                                "yoga",
                                "pilates",
                                "martial arts",
                                "ski resort",
                                "ski",
                                "summit at snoqualmie",
                                "badminton",
                                "seattle badminton club",
                                // Health and Fitness Facilities
                                "health club",
                                "fitness club",
                                "sports club",
                                "athletic club",
                                "gym",
                                "fitness",
                                "fitness center",
                                "health center",
                                "wellness center",
                                "recreation center",
                                "badminton",
                                "badminton club",
                                "seattle badminton club",
                                "tennis club",
                                "swimming club",
                                "yoga",
                                "yoga studio",
                                "pilates",
                                "pilates studio",
                                "crossfit",
                                "crossfit gym",
                                "martial arts",
                                "karate",
                                "judo",
                                "taekwondo",
                                "dance studio",
                                "dance club",
                                "cycling",
                                "cycling club",
                                "running club",
                                "sports facility",
                                "athletic facility",
                                "fitness facility",
                                "golf",
                                "tennis",
                                "soccer",
                                "football",
                                "basketball",
                                "baseball",
                                "swimming",
                                "yoga",
                                "pilates",
                                "martial arts",
                                "ski resort",
                                "ski",
                                "summit at snoqualmie",
                                "badminton",
                                "seattle badminton club",
                                "tennis club",
                                "swimming club",
                                "yoga",
                                "yoga studio",
                                "pilates",
                                "pilates studio",
                                "crossfit",
                                "crossfit gym",
                                "martial arts",
                                "karate",
                                "judo",
                                "taekwondo",
                                "dance studio",
                                "dance club",
                                "cycling",
                                "cycling club",
                                "running club",
                                "sports facility",
                                "athletic facility",
                                "fitness facility"));
        categorySemanticClusters.put("health", health);

        // Charity cluster - NEW (donations, NOT schools - schools are education)
        // CRITICAL FIX: Schools (middle school, high school, elementary, etc.) are now EDUCATION,
        // not charity
        final Set<String> charity =
                new HashSet<>(
                        Arrays.asList(
                                "charity",
                                "charitable",
                                "donation",
                                "donate",
                                "donating",
                                "non-profit",
                                "nonprofit",
                                "non profit",
                                "go fund me",
                                "gofundme"
                        // NOTE: School-related terms removed - they are now in education
                        // cluster
                        ));
        categorySemanticClusters.put("charity", charity);

        // Check cluster - NEW
        final Set<String> check =
                new HashSet<>(
                        Arrays.asList(
                                "check",
                                "checks",
                                "cheque",
                                "cheques",
                                "check payment",
                                "check pay",
                                "check number",
                                "check #",
                                "check no",
                                "check no.",
                                "written check",
                                "check written",
                                "check issued",
                                "check payment",
                                "check transaction",
                                "check purchase",
                                "check payment",
                                "check deposit",
                                "check cashing",
                                "check clearing"));
        // CRITICAL FIX: Merge with payment instead of overwriting
        payment.addAll(check);
        categorySemanticClusters.put("payment", payment);

        LOGGER.info(
                "Initialized semantic clusters for {} categories with comprehensive financial mappings",
                categorySemanticClusters.size());
    }

    /**
     * Find best semantic match for merchant name/description
     *
     * <p>CRITICAL: Thread-safe, handles errors, race conditions, and boundary conditions
     *
     * @param merchantName Merchant name
     * @param description Transaction description
     * @return SemanticMatchResult with best match and confidence, or null if no good match
     */
    public SemanticMatchResult findBestSemanticMatch(final String merchantName, final String description) {
        return findBestSemanticMatchWithContext(merchantName, description, null, null, null, null);
    }

    /**
     * Find best semantic match with context-aware matching
     *
     * <p>Context-aware matching considers: - Transaction amount (positive = income/investment,
     * negative = expense) - Payment channel (ACH = likely bill pay/transfer, POS = likely purchase)
     * - Account type (investment account = likely investment category) - Account subtype (more
     * specific account type hints)
     *
     * <p>CRITICAL: Thread-safe, handles errors, race conditions, and boundary conditions
     *
     * @param merchantName Merchant name
     * @param description Transaction description
     * @param amount Transaction amount (null if not available)
     * @param paymentChannel Payment channel (ACH, POS, online, in_store, etc.)
     * @param accountType Account type (checking, savings, credit, investment, loan, etc.)
     * @param accountSubtype Account subtype (more specific type)
     * @return SemanticMatchResult with best match and confidence, or null if no good match
     */
    public SemanticMatchResult findBestSemanticMatchWithContext(
            final String merchantName,
            final String description,
            final java.math.BigDecimal amount,
            final String paymentChannel,
            final String accountType,
            final String accountSubtype) {
        try {
            // CRITICAL: Ensure clusters are loaded (lazy loading with race condition prevention)
            ensureClustersLoaded();

            // Null and empty input validation. Both null = nothing to match on.
            if ((merchantName == null || merchantName.isBlank())
                    && (description == null || description.isBlank())) {
                LOGGER.debug(
                        "Semantic matching: Both merchant name and description are null/empty");
                return null;
            }

            // When the merchant-keyword clusters aren't loaded (test env, or
            // first-boot before the data service populates them), we can
            // still make a sensible guess from the CONTEXT alone: account
            // type + payment channel + payment-phrase keywords. This path
            // replaces a prior "return null" that erased context as a
            // signal source — a credit-card row with "Auto Pay" in the
            // description IS a payment, even without a merchant match.
            if (!clustersLoaded || categorySemanticClusters.isEmpty()) {
                final SemanticMatchResult contextual =
                        deriveFromContext(
                                merchantName,
                                description,
                                amount,
                                paymentChannel,
                                accountType,
                                accountSubtype);
                if (contextual != null) {
                    return contextual;
                }
                LOGGER.debug("Semantic clusters not loaded and no context signal; returning null");
                return null;
            }

            // CRITICAL: Log input for debugging
            LOGGER.debug(
                    "Semantic matching: merchantName='{}', description='{}', amount={}, channel='{}', accountType='{}', accountSubtype='{}'",
                    merchantName,
                    description,
                    amount,
                    paymentChannel,
                    accountType,
                    accountSubtype);

            // CRITICAL: Boundary condition - very long text (performance protection)
            String safeMerchantName = merchantName != null ? merchantName : "";
            String safeDescription = description != null ? description : "";

            if (safeMerchantName.length() > MAX_TEXT_LENGTH) {
                LOGGER.warn(
                        "Merchant name too long ({} chars), truncating to {}",
                        safeMerchantName.length(),
                        MAX_TEXT_LENGTH);
                safeMerchantName = safeMerchantName.substring(0, MAX_TEXT_LENGTH);
            }

            if (safeDescription.length() > MAX_TEXT_LENGTH) {
                LOGGER.warn(
                        "Description too long ({} chars), truncating to {}",
                        safeDescription.length(),
                        MAX_TEXT_LENGTH);
                safeDescription = safeDescription.substring(0, MAX_TEXT_LENGTH);
            }

            // CRITICAL FIX: When merchantName is null, use description for matching
            // Many imports (especially PDF/CSV) have merchant info in description, not merchantName
            // Combine merchant name and description, but prioritize description if merchantName is
            // null
            final String combinedText;
            if (safeMerchantName.isEmpty() && !safeDescription.isEmpty()) {
                // Use description only when merchantName is null/empty
                combinedText = safeDescription.trim().toLowerCase(Locale.ROOT);
                LOGGER.debug(
                        "Semantic matching: Using description only (merchantName is null/empty): '{}'",
                        combinedText);
            } else {
                // Combine both when available
                combinedText = (safeMerchantName + " " + safeDescription).trim().toLowerCase(Locale.ROOT);
                LOGGER.debug("Semantic matching: Using combined text: '{}'", combinedText);
            }

            if (combinedText.isEmpty()) {
                LOGGER.debug("Semantic matching: Combined text is empty after normalization");
                return null;
            }

            // CRITICAL: Thread-safe tokenization (no shared state modification)
            final Set<String> tokens = tokenize(combinedText);

            if (tokens.isEmpty()) {
                LOGGER.debug("Semantic matching: No tokens extracted from text");
                return null;
            }

            // CRITICAL: Check for POS system codes first (TST* = Toast, SQ* = Square)
            // These are restaurant POS systems and should always be categorized as dining
            // Check both combined text and individual tokens for POS codes
            final String combinedTextUpper = combinedText.toUpperCase(Locale.ROOT);
            if (combinedTextUpper.startsWith("TST*")
                    || combinedTextUpper.startsWith("TST ")
                    || combinedTextUpper.contains(" TST*")
                    || combinedTextUpper.contains(" TST ")) {
                LOGGER.debug(
                        "POS code detected: TST* (Toast) - categorizing as dining with high confidence");
                return new SemanticMatchResult("dining", 0.95, "POS_CODE_TST");
            }
            if (combinedTextUpper.startsWith("SQ*")
                    || combinedTextUpper.startsWith("SQ ")
                    || combinedTextUpper.contains(" SQ*")
                    || combinedTextUpper.contains(" SQ ")) {
                // Square can be used for various businesses, but defaults to dining if not clearly
                // identified
                // Check if there are strong indicators it's NOT dining (e.g., "square payment",
                // "square invoice")
                final String combinedTextLower = combinedText.toLowerCase(Locale.ROOT);
                final boolean isNonDiningSquare =
                        combinedTextLower.contains("square payment")
                                || combinedTextLower.contains("square invoice")
                                || combinedTextLower.contains("square subscription")
                                || combinedTextLower.contains("square terminal");
                if (!isNonDiningSquare) {
                    LOGGER.debug(
                            "POS code detected: SQ* (Square) - defaulting to dining with high confidence");
                    return new SemanticMatchResult("dining", 0.90, "POS_CODE_SQ");
                } else {
                    LOGGER.debug(
                            "POS code detected: SQ* (Square) but non-dining indicators found - skipping POS code match");
                }
            }

            // CRITICAL: Thread-safe iteration (ConcurrentHashMap allows safe iteration)
            // Find best matching category
            String bestCategory = null;
            double bestScore = 0.0;

            // CRITICAL: Create a snapshot of entries to avoid ConcurrentModificationException
            // ConcurrentHashMap.entrySet() returns a view that is safe for iteration
            for (final Map.Entry<String, Set<String>> entry : categorySemanticClusters.entrySet()) {
                final String category = entry.getKey();
                final Set<String> cluster = entry.getValue();

                // CRITICAL: Null check for cluster (defensive programming)
                if (category == null || cluster == null || cluster.isEmpty()) {
                    LOGGER.debug("Skipping null or empty cluster for category: {}", category);
                    continue;
                }

                // CRITICAL: Create defensive copy of cluster to avoid race conditions
                // If cluster is modified during iteration, we work with a snapshot
                final Set<String> clusterSnapshot = new HashSet<>(cluster);

                // Calculate semantic similarity (Jaccard similarity for now)
                LOGGER.trace(
                        "Checking category '{}' with {} keywords against text '{}'",
                        category,
                        clusterSnapshot.size(),
                        combinedText);
                final double baseSimilarity =
                        calculateSemanticSimilarity(tokens, clusterSnapshot, combinedText);
                LOGGER.trace("Category '{}' base similarity: {}", category, baseSimilarity);

                // Enforce a minimum base-similarity floor: the merchant text itself must
                // have meaningful overlap with the category keywords. A purely context-driven
                // match (e.g. "ACH + positive amount") is not enough to pick a category.
                if (baseSimilarity < SEMANTIC_BASE_SIMILARITY_FLOOR) {
                    continue;
                }

                // Apply capped context boost on top of the text similarity.
                final double rawContextBoost =
                        calculateContextBoost(
                                category, amount, paymentChannel, accountType, accountSubtype);
                final double contextBoost = Math.min(MAX_CONTEXT_BOOST, Math.max(0.0, rawContextBoost));

                double similarity = Math.min(1.0, baseSimilarity + contextBoost);

                if (Double.isNaN(similarity) || Double.isInfinite(similarity)) {
                    LOGGER.warn(
                            "Invalid similarity score (NaN or Infinite) for category: {}",
                            category);
                    continue;
                }

                similarity = Math.max(0.0, Math.min(1.0, similarity));

                if (similarity > bestScore && similarity >= SEMANTIC_SIMILARITY_THRESHOLD) {
                    bestScore = similarity;
                    bestCategory = category;
                }
            }

            if (bestCategory != null) {
                LOGGER.info(
                        "Semantic match: text='{}' category='{}' similarity={} threshold={}",
                        combinedText.substring(0, Math.min(60, combinedText.length())),
                        bestCategory,
                        String.format("%.3f", bestScore),
                        SEMANTIC_SIMILARITY_THRESHOLD);
                return new SemanticMatchResult(bestCategory, bestScore, "SEMANTIC_CONTEXT_AWARE");
            }

            LOGGER.debug(
                    "Semantic match: no category above threshold {} (baseFloor={}) for text='{}'",
                    SEMANTIC_SIMILARITY_THRESHOLD,
                    SEMANTIC_BASE_SIMILARITY_FLOOR,
                    combinedText.substring(0, Math.min(60, combinedText.length())));
            return null;

        } catch (Exception e) {
            // CRITICAL: Error handling - never throw, always return null on error
            LOGGER.error(
                    "Error in semantic matching for merchant='{}', description='{}': {}",
                    merchantName,
                    description,
                    e.getMessage(),
                    e);
            return null;
        }
    }

    /**
     * Calculate context boost for a category based on transaction context
     *
     * <p>Context rules: - ACH + positive amount → boost utilities, payment, deposit - ACH +
     * negative amount → boost utilities, payment - POS → boost shopping, dining, groceries -
     * Investment account → boost investment category - Loan account → boost payment category -
     * Credit account → boost payment category - Large positive amounts → boost investment, income -
     * Small amounts → boost groceries, dining
     *
     * @param category Category being evaluated
     * @param amount Transaction amount
     * @param paymentChannel Payment channel
     * @param accountType Account type
     * @param accountSubtype Account subtype
     * @return Context boost (0.0 to 0.3)
     */
    private double calculateContextBoost(
            final String category,
            final java.math.BigDecimal amount,
            final String paymentChannel,
            final String accountType,
            final String accountSubtype) {

        double boost = 0.0;

        try {
            // Rule 1: Payment channel context
            if (paymentChannel != null) {
                final String channelLower = paymentChannel.toLowerCase(Locale.ROOT);

                // ACH payments are typically bill payments, utilities, or transfers
                if (channelLower.contains("ach")) {
                    if ("utilities".equals(category)
                            || "payment".equals(category)
                            || "transfer".equals(category)) {
                        boost += 0.15; // Strong boost for ACH + utilities/payment
                    }
                    // ACH with positive amount might be deposit/income
                    if (amount != null && amount.compareTo(java.math.BigDecimal.ZERO) > 0) {
                        if ("deposit".equals(category)
                                || "income".equals(category)
                                || "investment".equals(category)) {
                            boost += 0.10; // Boost for ACH credit
                        }
                    }
                }

                // POS (Point of Sale) is typically shopping, dining, groceries
                if (channelLower.contains("pos") || channelLower.contains("point of sale")) {
                    if ("shopping".equals(category)
                            || "dining".equals(category)
                            || "groceries".equals(category)) {
                        boost += 0.12; // Boost for POS + shopping/dining
                    }
                }

                // Online payments might be subscriptions, shopping, entertainment
                if (channelLower.contains("online")) {
                    // Prioritize subscriptions and entertainment for online payments
                    if ("subscriptions".equals(category) || "entertainment".equals(category)) {
                        boost += 0.35; // Very strong boost for subscriptions/entertainment
                        // (increased from 0.25) to ensure similarity >= 0.6
                    } else if ("shopping".equals(category)) {
                        boost += 0.10; // Moderate boost for shopping
                    }
                }
            }

            // Rule 2: Account type context
            if (accountType != null) {
                final String accountTypeLower = accountType.toLowerCase(Locale.ROOT);

                // Investment accounts → boost investment category
                if (accountTypeLower.contains("investment")
                        || accountTypeLower.contains("brokerage")
                        || accountTypeLower.contains("retirement")
                        || accountTypeLower.contains("ira")
                        || accountTypeLower.contains("401k")
                        || accountTypeLower.contains("403b")) {
                    if ("investment".equals(category)) {
                        boost += 0.20; // Strong boost for investment account
                    }
                }

                // Loan/credit accounts → boost payment category
                if (accountTypeLower.contains("loan")
                        || accountTypeLower.contains("credit")
                        || accountTypeLower.contains("mortgage")) {
                    if ("payment".equals(category)) {
                        boost += 0.40; // Extremely strong boost for loan/credit account (increased
                        // from 0.25) to override other matches
                    }
                }

                // Checking/savings accounts → might be utilities, groceries, etc.
                if (accountTypeLower.contains("checking")
                        || accountTypeLower.contains("savings")
                        || accountTypeLower.contains("depository")) {
                    // No specific boost, but don't penalize common categories
                    if ("utilities".equals(category)
                            || "groceries".equals(category)
                            || "dining".equals(category)
                            || "shopping".equals(category)) {
                        boost += 0.05; // Small boost for common categories
                    }
                }
            }

            // Rule 3: Amount-based context
            if (amount != null) {
                // Large positive amounts might be investment, income, or large purchases
                if (amount.compareTo(java.math.BigDecimal.valueOf(1000)) > 0) {
                    if ("investment".equals(category) || "income".equals(category)) {
                        boost += 0.10; // Boost for large positive amounts
                    }
                }

                // Small amounts are typically groceries, dining, small purchases
                if (amount.compareTo(java.math.BigDecimal.valueOf(100)) < 0
                        && amount.compareTo(java.math.BigDecimal.ZERO) < 0) {
                    if ("groceries".equals(category)
                            || "dining".equals(category)
                            || "shopping".equals(category)) {
                        boost += 0.08; // Boost for small amounts
                    }
                }

                // Very small amounts (< $10) are often coffee, snacks, small purchases
                if (amount.compareTo(java.math.BigDecimal.valueOf(10)) < 0
                        && amount.compareTo(java.math.BigDecimal.ZERO) < 0) {
                    // Prioritize dining for very small amounts (coffee, snacks)
                    if ("dining".equals(category)) {
                        boost += 0.30; // Very strong boost for very small amounts → dining
                        // (prioritize over shopping)
                    } else if ("groceries".equals(category)) {
                        boost += 0.15; // Moderate boost for groceries
                    } else if ("shopping".equals(category)) {
                        boost += 0.05; // Small boost for shopping (lower priority for very small
                        // amounts)
                    }
                }
            }

            // Rule 4: Account subtype context (more specific)
            if (accountSubtype != null) {
                final String subtypeLower = accountSubtype.toLowerCase(Locale.ROOT);

                // CD, money market, etc. → investment
                if (subtypeLower.contains("cd")
                        || subtypeLower.contains("certificate")
                        || subtypeLower.contains("money market")
                        || subtypeLower.contains("money_market")) {
                    if ("investment".equals(category)) {
                        boost += 0.15; // Boost for CD/money market
                    }
                }

                // Credit card subtypes → payment
                if (subtypeLower.contains("credit") || subtypeLower.contains("card")) {
                    if ("payment".equals(category)) {
                        boost += 0.35; // Very strong boost for credit card subtype (increased from
                        // 0.20+0.12) to override other matches
                    }
                }
            }

            // CRITICAL: Cap boost at 0.5 (50%) to allow strong context signals to override base
            // similarity
            // Increased from 0.3 to 0.5 to handle cases where context is very strong (e.g.,
            // credit/loan accounts)
            boost = Math.min(0.5, boost);

        } catch (Exception e) {
            LOGGER.warn("Error calculating context boost: {}", e.getMessage());
            // Return 0.0 on error (no boost)
        }

        return boost;
    }

    /** Build context info string for logging */
    private String buildContextInfo(
            final java.math.BigDecimal amount, final String paymentChannel, final String accountType) {
        final StringBuilder info = new StringBuilder();
        if (amount != null) {
            info.append("amount=").append(amount);
        }
        if (paymentChannel != null) {
            if (info.length() > 0) {
                info.append(", ");
            }
            info.append("channel=").append(paymentChannel);
        }
        if (accountType != null) {
            if (info.length() > 0) {
                info.append(", ");
            }
            info.append("account=").append(accountType);
        }
        return info.length() > 0 ? info.toString() : "none";
    }

    /**
     * Tokenize text into words Simple implementation - in production, use proper NLP tokenization
     *
     * <p>CRITICAL: Thread-safe, handles errors and boundary conditions
     */
    private Set<String> tokenize(String text) {
        final Set<String> tokens = new HashSet<>();

        try {
            // CRITICAL: Null and empty check
            if (text == null || text.isBlank()) {
                return tokens;
            }

            // CRITICAL: Boundary condition - very long text
            if (text.length() > MAX_TEXT_LENGTH) {
                LOGGER.warn("Text too long for tokenization ({} chars), truncating", text.length());
                text = text.substring(0, MAX_TEXT_LENGTH);
            }

            // Remove special characters and split by whitespace
            final String normalized =
                    text.toLowerCase(Locale.ROOT)
                            .replaceAll("[^a-z0-9\\s]", " ")
                            .replaceAll("\\s+", " ")
                            .trim();

            if (normalized.isEmpty()) {
                return tokens;
            }

            String[] words = normalized.split("\\s+");

            // CRITICAL: Boundary condition - very large word array
            if (words.length > 1000) {
                LOGGER.warn("Too many words ({}), limiting to first 1000", words.length);
                words = Arrays.copyOf(words, 1000);
            }

            for (final String word : words) {
                // CRITICAL: Validate word length (boundary condition)
                if (word != null && word.length() >= MIN_TOKEN_LENGTH && word.length() <= 100) {
                    tokens.add(word);
                }
            }

            // Also add bigrams for better matching
            // CRITICAL: Boundary check for array bounds
            for (int i = 0; i < words.length - 1 && i < 1000; i++) {
                final String word1 = words[i];
                final String word2 = words[i + 1];

                if (word1 != null
                        && word2 != null
                        && word1.length() >= MIN_TOKEN_LENGTH
                        && word1.length() <= 100
                        && word2.length() >= MIN_TOKEN_LENGTH
                        && word2.length() <= 100) {
                    tokens.add(word1 + " " + word2);
                }
            }

            // Augment with stems + curated synonyms so "groceries" ↔ "grocery" ↔ "supermarket"
            // collide on the same canonical token. See TextNormalizer for the policy.
            tokens.addAll(TextNormalizer.expandWithSynonyms(tokens));

        } catch (Exception e) {
            // CRITICAL: Error handling - return empty set on error
            LOGGER.error("Error tokenizing text: {}", e.getMessage(), e);
            return new HashSet<>();
        }

        return tokens;
    }

    /**
     * Calculate semantic similarity using Jaccard similarity In production, use cosine similarity
     * with word embeddings
     *
     * <p>CRITICAL: Thread-safe, handles errors and boundary conditions
     *
     * <p>IMPROVEMENT: Added exact phrase matching boost for better accuracy
     */
    private double calculateSemanticSimilarity(
            Set<String> tokens, Set<String> cluster, final String combinedText) {
        try {
            // CRITICAL: Null and empty checks
            if (tokens == null || cluster == null) {
                LOGGER.debug("Semantic similarity: tokens or cluster is null");
                return 0.0;
            }

            if (tokens.isEmpty() || cluster.isEmpty()) {
                return 0.0;
            }

            // CRITICAL: Boundary condition - very large sets (performance protection)
            if (tokens.size() > 10_000 || cluster.size() > 10_000) {
                LOGGER.warn(
                        "Very large sets for similarity calculation (tokens: {}, cluster: {}), limiting",
                        tokens.size(),
                        cluster.size());
                // For very large sets, sample instead of processing all
                if (tokens.size() > 10_000) {
                    tokens = new HashSet<>(new ArrayList<>(tokens).subList(0, 10_000));
                }
                if (cluster.size() > 10_000) {
                    cluster = new HashSet<>(new ArrayList<>(cluster).subList(0, 10_000));
                }
            }

            // CRITICAL: Create defensive copies to avoid modifying input sets
            final Set<String> tokensCopy = new HashSet<>(tokens);
            final Set<String> clusterCopy = new HashSet<>(cluster);

            // IMPROVEMENT: Check if combined text contains any multi-word cluster phrase as
            // substring
            // This handles cases like "online transfer from morganstanley" containing "online
            // transfer from morganstanley"
            // Prioritize longer phrases over shorter ones (check longer phrases first)
            if (combinedText != null && !combinedText.isEmpty()) {
                final String combinedTextLower = combinedText.toLowerCase(Locale.ROOT);
                // Sort phrases by length (longest first) to prioritize longer, more specific
                // matches
                final List<String> sortedPhrases = new ArrayList<>();
                for (final String clusterPhrase : clusterCopy) {
                    if (clusterPhrase != null && clusterPhrase.contains(" ")) {
                        sortedPhrases.add(clusterPhrase);
                    }
                }
                sortedPhrases.sort(
                        (a, b) -> Integer.compare(b.split("\\s+").length, a.split("\\s+").length));

                for (final String clusterPhrase : sortedPhrases) {
                    // Multi-word phrase, check if combined text contains it as substring
                    final String clusterPhraseLower = clusterPhrase.toLowerCase(Locale.ROOT);
                    // BUG FIX: Handle wildcard patterns (e.g., "tst*" should match "TST* MESSINA",
                    // "TST* DEEP DIVE", etc.)
                    // Also handles "sq*" for Square POS system
                    if (clusterPhraseLower.endsWith("*")) {
                        // Wildcard pattern - check if merchant starts with the prefix (without the
                        // *)
                        final String prefix =
                                clusterPhraseLower.substring(0, clusterPhraseLower.length() - 1);
                        // Case-insensitive matching for POS codes
                        final String combinedTextUpper = combinedText.toUpperCase(Locale.ROOT);
                        final String prefixUpper = prefix.toUpperCase(Locale.ROOT);
                        if (combinedTextLower.startsWith(prefix)
                                || combinedTextUpper.startsWith(prefixUpper + "*")
                                || combinedTextUpper.startsWith(prefixUpper + " ")) {
                            LOGGER.debug(
                                    "Wildcard prefix match found: '{}' matches '{}*' in '{}'",
                                    prefix,
                                    prefix,
                                    combinedTextLower);
                            // POS system codes (TST*, SQ*) get even higher similarity
                            if ("tst".equals(prefix) || "sq".equals(prefix)) {
                                return 0.95; // Very high similarity for POS system codes
                            }
                            return 0.90; // High similarity for other wildcard prefix matches
                        }
                    }
                    if (combinedTextLower.contains(clusterPhraseLower)) {
                        LOGGER.debug(
                                "Substring phrase match found: '{}' in '{}'",
                                clusterPhraseLower,
                                combinedTextLower);
                        // Higher similarity for longer phrases (more specific matches)
                        final int phraseLength = clusterPhraseLower.split("\\s+").length;
                        final double similarity =
                                0.85
                                        + (phraseLength - 2)
                                        * 0.05; // 0.85 for 2 words, 0.90 for 3, 0.95 for 4,
                        // etc.
                        return Math.min(0.95, similarity); // Cap at 0.95
                    }
                }
            }

            // IMPROVEMENT: Check for exact phrase match first (significant boost)
            // This handles cases like "grocery store" matching the cluster phrase "grocery store"
            // Reconstruct the original phrase from tokens (sorted for consistency)
            final List<String> sortedTokens = new ArrayList<>(tokensCopy);
            Collections.sort(sortedTokens);
            final String reconstructedPhrase = String.join(" ", sortedTokens).toLowerCase(Locale.ROOT);

            // Check if exact phrase exists in cluster (case-insensitive)
            boolean exactPhraseMatch = false;
            for (final String clusterPhrase : clusterCopy) {
                if (clusterPhrase != null
                        && clusterPhrase.toLowerCase(Locale.ROOT).equals(reconstructedPhrase)) {
                    exactPhraseMatch = true;
                    break;
                }
            }

            // If exact phrase match, return high similarity (0.85+)
            if (exactPhraseMatch) {
                LOGGER.debug("Exact phrase match found: '{}'", reconstructedPhrase);
                return 0.85; // High similarity for exact phrase match
            }

            // Also check if any bigram from tokens matches a cluster phrase
            // This handles cases where tokens include bigrams like "grocery store"
            for (final String token : tokensCopy) {
                if (token != null && token.contains(" ")) {
                    // This is a bigram, check if it matches any cluster phrase
                    final String tokenLower = token.toLowerCase(Locale.ROOT);
                    for (final String clusterPhrase : clusterCopy) {
                        if (clusterPhrase != null
                                && clusterPhrase.toLowerCase(Locale.ROOT).equals(tokenLower)) {
                            LOGGER.debug("Bigram phrase match found: '{}'", tokenLower);
                            return 0.80; // High similarity for bigram match
                        }
                    }
                }
            }

            // BUG FIX: Handle wildcard patterns in single-word tokens (e.g., "tst*" should match
            // "TST* MESSINA", "SQ*" should match "SQ* MERCHANT")
            // Check if any token starts with a cluster phrase that ends with "*"
            if (combinedText != null && !combinedText.isEmpty()) {
                final String combinedTextLower = combinedText.toLowerCase(Locale.ROOT);
                final String combinedTextUpper = combinedText.toUpperCase(Locale.ROOT);
                for (final String clusterPhrase : clusterCopy) {
                    if (clusterPhrase != null && clusterPhrase.endsWith("*")) {
                        final String prefix =
                                clusterPhrase
                                        .toLowerCase(Locale.ROOT)
                                        .substring(0, clusterPhrase.length() - 1);
                        final String prefixUpper = prefix.toUpperCase(Locale.ROOT);
                        // Check if any token starts with the prefix (case-insensitive)
                        for (final String token : tokensCopy) {
                            if (token != null) {
                                final String tokenLower = token.toLowerCase(Locale.ROOT);
                                final String tokenUpper = token.toUpperCase(Locale.ROOT);
                                if (tokenLower.startsWith(prefix)
                                        || tokenUpper.startsWith(prefixUpper + "*")
                                        || tokenUpper.startsWith(prefixUpper + " ")) {
                                    LOGGER.debug(
                                            "Wildcard token match found: '{}' matches '{}*'",
                                            token,
                                            prefix);
                                    // POS system codes (TST*, SQ*) get even higher similarity
                                    if ("tst".equals(prefix) || "sq".equals(prefix)) {
                                        return 0.95; // Very high similarity for POS system codes
                                    }
                                    return 0.90; // High similarity for other wildcard token matches
                                }
                            }
                        }
                        // Also check if combined text starts with the prefix (case-insensitive)
                        if (combinedTextLower.startsWith(prefix)
                                || combinedTextUpper.startsWith(prefixUpper + "*")
                                || combinedTextUpper.startsWith(prefixUpper + " ")) {
                            LOGGER.debug(
                                    "Wildcard combined text match found: '{}' matches '{}*'",
                                    combinedTextLower,
                                    prefix);
                            // POS system codes (TST*, SQ*) get even higher similarity
                            if ("tst".equals(prefix) || "sq".equals(prefix)) {
                                return 0.95; // Very high similarity for POS system codes
                            }
                            return 0.90; // High similarity for other wildcard combined text matches
                        }
                    }
                }
            }

            // Jaccard similarity: intersection / union
            final Set<String> intersection = new HashSet<>(tokensCopy);
            intersection.retainAll(clusterCopy);

            final Set<String> union = new HashSet<>(tokensCopy);
            union.addAll(clusterCopy);

            if (union.isEmpty()) {
                return 0.0;
            }

            // CRITICAL: Boundary condition - prevent division by zero
            double similarity = (double) intersection.size() / union.size();

            // IMPROVEMENT: Boost similarity if we have multiple token matches
            // This helps when we have "grocery" and "store" matching separately
            if (intersection.size() >= 2) {
                // Multiple token matches - boost similarity significantly
                similarity = Math.min(1.0, similarity + 0.20); // Increased from 0.15 to 0.20
            }

            // IMPROVEMENT: Additional boost if we have a single strong keyword match
            // This helps with cases like "NETFLIX" matching "netflix" in subscriptions cluster
            if (intersection.size() >= 1 && tokensCopy.size() <= 3) {
                // Small number of tokens with at least one match - likely a good match
                similarity = Math.min(1.0, similarity + 0.10);
            }

            // CRITICAL: Validate result (should be in [0, 1])
            if (Double.isNaN(similarity) || Double.isInfinite(similarity)) {
                LOGGER.warn(
                        "Invalid similarity calculated: {} (intersection: {}, union: {})",
                        similarity,
                        intersection.size(),
                        union.size());
                return 0.0;
            }

            // CRITICAL: Ensure result is in valid range
            return Math.max(0.0, Math.min(1.0, similarity));

        } catch (Exception e) {
            // CRITICAL: Error handling - return 0.0 on error
            LOGGER.error("Error calculating semantic similarity: {}", e.getMessage(), e);
            return 0.0;
        }
    }

    /**
     * Ensure clusters are loaded (lazy loading with race condition prevention) CRITICAL:
     * Thread-safe, handles errors and boundary conditions
     */
    private void ensureClustersLoaded() {
        if (!clustersLoaded) {
            loadSemanticClustersFromSharedService();
        }
    }

    /**
     * Add semantic cluster (for dynamic learning) CRITICAL: Thread-safe, updates both local cache
     * and shared service Race condition prevention: Updates shared service first, then local cache
     */
    public synchronized void addSemanticCluster(final String category, final Set<String> keywords) {
        try {
            // CRITICAL: Input validation
            if (category == null || category.isBlank()) {
                LOGGER.warn("Cannot add semantic cluster: category is null or empty");
                return;
            }

            if (keywords == null || keywords.isEmpty()) {
                LOGGER.warn(
                        "Cannot add semantic cluster: keywords are null or empty for category: {}",
                        category);
                return;
            }

            // CRITICAL: Boundary condition - very large keyword set
            Set<String> safeKeywords = keywords;
            if (keywords.size() > 10_000) {
                LOGGER.warn("Keyword set too large ({}), limiting to first 10000", keywords.size());
                safeKeywords = new HashSet<>(new ArrayList<>(keywords).subList(0, 10_000));
            }

            // CRITICAL: Create defensive copy to avoid external modification
            final Set<String> keywordsCopy = new HashSet<>(safeKeywords);

            // CRITICAL: Normalize category name
            final String normalizedCategory = category.toLowerCase(Locale.ROOT).trim();

            // CRITICAL: Update shared service first (single source of truth)
            if (merchantCategoryDataService != null) {
                // Add each keyword to shared service
                for (final String keyword : keywordsCopy) {
                    merchantCategoryDataService.addMerchantCategory(keyword, normalizedCategory);
                }
            } else {
                LOGGER.warn("MerchantCategoryDataService is null. Cannot update shared service.");
            }

            // CRITICAL: Update local cache (thread-safe, kept in memory for speed)
            categorySemanticClusters.put(normalizedCategory, keywordsCopy);

            LOGGER.info(
                    "Added semantic cluster for category: {} with {} keywords (updated shared service and local cache)",
                    normalizedCategory,
                    keywordsCopy.size());

        } catch (OutOfMemoryError e) {
            LOGGER.error("Out of memory adding semantic cluster for category: {}", category, e);
        } catch (Exception e) {
            // CRITICAL: Error handling - log but don't throw
            LOGGER.error(
                    "Error adding semantic cluster for category: {}: {}",
                    category,
                    e.getMessage(),
                    e);
        }
    }

    /**
     * Get semantic cluster for a category (for testing/debugging) CRITICAL: Thread-safe, returns
     * defensive copy
     */
    public synchronized Set<String> getSemanticCluster(final String category) {
        if (category == null || category.isBlank()) {
            return Collections.emptySet();
        }

        final String normalizedCategory = category.toLowerCase(Locale.ROOT).trim();
        final Set<String> cluster = categorySemanticClusters.get(normalizedCategory);

        if (cluster == null) {
            return Collections.emptySet();
        }

        // CRITICAL: Return defensive copy to prevent external modification
        return new HashSet<>(cluster);
    }

    /**
     * Get all category names (for testing/debugging) CRITICAL: Thread-safe, returns defensive copy
     */
    public synchronized Set<String> getAllCategories() {
        return new HashSet<>(categorySemanticClusters.keySet());
    }

    /** Semantic match result */
    public static class SemanticMatchResult {
        public final String category;
        public final double similarity;
        public final String method;

        public SemanticMatchResult(final String category, final double similarity, final String method) {
            this.category = category;
            this.similarity = similarity;
            this.method = method;
        }

        public boolean isHighConfidence() {
            return similarity >= 0.7;
        }

        public boolean isMediumConfidence() {
            return similarity >= 0.5 && similarity < 0.7;
        }

        @Override
        public String toString() {
            return String.format(
                    "SemanticMatchResult{category='%s', similarity=%.2f, method='%s'}",
                    category, similarity, method);
        }
    }

    /**
     * Context-only category inference. Called when the merchant-keyword clusters aren't loaded
     * (test env, first-boot bootstrapping) and therefore we can't do substring matching via the
     * corpus.
     *
     * <p>The logic is <strong>data-driven</strong>: a table of context rules ({@link ContextRule})
     * evaluated in order. To add a new archetype (e.g. a new merchant keyword set, or a new payment
     * channel), add a row to {@link #CONTEXT_RULES} — no new branches in code, no risk of shadowing
     * an earlier branch.
     *
     * <p>Rules higher in the list win ties. Structure:
     *
     * <ol>
     *   <li>Explicit payment-phrase or loan/investment account type → the account context IS the
     *       category.
     *   <li>Payment channel cues (ACH, POS, online).
     *   <li>Amount-magnitude heuristics.
     *   <li>Merchant keyword archetypes (groceries, dining, utilities, …).
     * </ol>
     */
    private SemanticMatchResult deriveFromContext(
            final String merchantName,
            final String description,
            final java.math.BigDecimal amount,
            final String paymentChannel,
            final String accountType,
            final String accountSubtype) {
        final CategorizationContext ctx =
                new CategorizationContext(
                        (safeLower(merchantName) + " " + safeLower(description)).trim(),
                        safeLower(accountType),
                        safeLower(accountSubtype),
                        safeLower(paymentChannel),
                        amount);
        for (final ContextRule rule : CONTEXT_RULES) {
            if (rule.matches(ctx)) {
                return new SemanticMatchResult(rule.category, rule.confidence, rule.source);
            }
        }
        return null;
    }

    private static String safeLower(final String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    // =========================================================================
    // Data-driven context rules — add new archetypes by adding a row here.
    // Order matters: rules higher in the list take priority on ties.
    // =========================================================================

    /** Bag of context fields a rule may inspect. */
    private static final class CategorizationContext {
        final String textLower;
        final String accountTypeLower;
        final String accountSubtypeLower;
        final String channelLower;
        final java.math.BigDecimal amount;

        CategorizationContext(
                final String text, final String type, final String sub, final String channel, final java.math.BigDecimal amount) {
            this.textLower = text;
            this.accountTypeLower = type;
            this.accountSubtypeLower = sub;
            this.channelLower = channel;
            this.amount = amount;
        }

        boolean textContainsAny(final java.util.Collection<String> phrases) {
            for (final String p : phrases) {
                if (textLower.contains(p)) {
                    return true;
                }
            }
            return false;
        }

        boolean accountMatchesAny(final java.util.Collection<String> keywords) {
            for (final String k : keywords) {
                if (accountTypeLower.contains(k) || accountSubtypeLower.contains(k)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A single context-categorisation rule. Data, not code. */
    private static final class ContextRule {
        final String source;
        final String category;
        final double confidence;
        final java.util.function.Predicate<CategorizationContext> predicate;

        ContextRule(
                final String source,
                final String category,
                final double confidence,
                final java.util.function.Predicate<CategorizationContext> predicate) {
            this.source = source;
            this.category = category;
            this.confidence = confidence;
            this.predicate = predicate;
        }

        boolean matches(final CategorizationContext ctx) {
            return predicate.test(ctx);
        }
    }

    /** Phrases that unambiguously indicate a payment-action verb in the text. */
    private static final Set<String> PAYMENT_ACTION_PHRASES =
            Set.of(
                    "auto pay",
                    "autopay",
                    "auto-pay",
                    "bill payment",
                    "bill pay",
                    "monthly payment",
                    "direct debit",
                    "pmt");

    /** Account-type keyword sets grouped by the category they resolve to. */
    private static final Set<String> INVESTMENT_ACCOUNT_KEYWORDS =
            Set.of("investment", "brokerage", "ira", "401k", "cd");

    private static final Set<String> LOAN_ACCOUNT_KEYWORDS =
            Set.of("loan", "mortgage");
    private static final Set<String> CREDIT_ACCOUNT_KEYWORDS =
            Set.of("credit", "credit card");

    /** Merchant archetype keyword groups. */
    private static final Set<String> GROCERY_KEYWORDS =
            Set.of(
                    "grocery",
                    "supermarket",
                    "food market",
                    "farmers market",
                    "fresh food",
                    "produce market",
                    "food shopping");

    private static final Set<String> DINING_KEYWORDS =
            Set.of(
                    "coffee",
                    "cafe",
                    "restaurant",
                    "diner",
                    "fast food",
                    "dining",
                    "takeout",
                    "take out",
                    "delivery meal",
                    "lunch");
    private static final Set<String> TRANSPORT_KEYWORDS =
            Set.of(
                    "gas station",
                    "fuel",
                    "petrol",
                    "uber",
                    "lyft",
                    "taxi",
                    "parking",
                    "rideshare",
                    "ride share",
                    "transit",
                    "subway");
    private static final Set<String> HEALTH_KEYWORDS =
            Set.of(
                    "pharmacy",
                    "drug store",
                    "doctor",
                    "hospital",
                    "medical",
                    "clinic",
                    "dentist",
                    "urgent care");
    private static final Set<String> UTILITIES_KEYWORDS =
            Set.of(
                    "electric bill",
                    "water bill",
                    "gas bill",
                    "internet bill",
                    "cable bill",
                    "phone bill",
                    "utility",
                    "electricity",
                    "internet service",
                    "cable service",
                    "trash service",
                    "sewer service",
                    "cable tv",
                    "cabletv");

    private static boolean hasPaymentPhrase(final CategorizationContext ctx) {
        return ctx.textContainsAny(PAYMENT_ACTION_PHRASES)
                || ctx.textLower.contains(" payment")
                || ctx.textLower.startsWith("payment ");
    }

    private static final List<ContextRule> CONTEXT_RULES =
            List.of(
                    // --- Account type ----------------------------------------------------
                    new ContextRule(
                            "CONTEXT_INVESTMENT",
                            "investment",
                            0.70,
                            ctx -> ctx.accountMatchesAny(INVESTMENT_ACCOUNT_KEYWORDS)),
                    new ContextRule(
                            "CONTEXT_LOAN",
                            "payment",
                            0.70,
                            ctx -> ctx.accountMatchesAny(LOAN_ACCOUNT_KEYWORDS)),
                    // Credit-card account only yields "payment" when the text also
                    // contains a payment-phrase. A bare credit-card charge can be
                    // any category, so we don't force "payment" on all of them.
                    new ContextRule(
                            "CONTEXT_CREDIT_PAYMENT",
                            "payment",
                            0.70,
                            ctx ->
                                    ctx.accountMatchesAny(CREDIT_ACCOUNT_KEYWORDS)
                                            && hasPaymentPhrase(ctx)),

                    // --- Explicit payment phrase (any account) --------------------------
                    new ContextRule(
                            "CONTEXT_PAYMENT_PHRASE",
                            "payment",
                            0.65,
                            SemanticMatchingService::hasPaymentPhrase),

                    // --- Payment channel + amount ---------------------------------------
                    new ContextRule(
                            "CONTEXT_POS_SMALL",
                            "dining",
                            0.65,
                            ctx ->
                                    ("pos".equals(ctx.channelLower)
                                                    || "in_store".equals(ctx.channelLower))
                                            && ctx.amount != null
                                            && ctx.amount
                                                            .abs()
                                                            .compareTo(
                                                                    java.math.BigDecimal.valueOf(
                                                                            10))
                                                    < 0),
                    new ContextRule(
                            "CONTEXT_ACH",
                            "utilities",
                            0.65,
                            ctx -> "ach".equals(ctx.channelLower)),
                    new ContextRule(
                            "CONTEXT_POS",
                            "shopping",
                            0.60,
                            ctx ->
                                    "pos".equals(ctx.channelLower)
                                            || "in_store".equals(ctx.channelLower)),
                    new ContextRule(
                            "CONTEXT_ONLINE",
                            "subscriptions",
                            0.60,
                            ctx -> "online".equals(ctx.channelLower)),

                    // --- Amount-magnitude heuristics ------------------------------------
                    new ContextRule(
                            "CONTEXT_LARGE_POSITIVE",
                            "deposit",
                            0.65,
                            ctx ->
                                    ctx.amount != null
                                            && ctx.amount.signum() > 0
                                            && ctx.amount
                                                            .abs()
                                                            .compareTo(
                                                                    java.math.BigDecimal.valueOf(
                                                                            1000))
                                                    >= 0),
                    new ContextRule(
                            "CONTEXT_SMALL_NEGATIVE",
                            "fees",
                            0.65,
                            ctx ->
                                    ctx.amount != null
                                            && ctx.amount.signum() < 0
                                            && ctx.amount.abs().compareTo(java.math.BigDecimal.ONE)
                                                    < 0),

                    // --- Merchant keyword archetypes ------------------------------------
                    new ContextRule(
                            "CONTEXT_GROCERY_KEYWORD",
                            "groceries",
                            0.65,
                            ctx -> ctx.textContainsAny(GROCERY_KEYWORDS)),
                    new ContextRule(
                            "CONTEXT_DINING_KEYWORD",
                            "dining",
                            0.65,
                            ctx -> ctx.textContainsAny(DINING_KEYWORDS)),
                    new ContextRule(
                            "CONTEXT_TRANSPORT_KEYWORD",
                            "transportation",
                            0.65,
                            ctx -> ctx.textContainsAny(TRANSPORT_KEYWORDS)),
                    new ContextRule(
                            "CONTEXT_HEALTH_KEYWORD",
                            "health",
                            0.65,
                            ctx -> ctx.textContainsAny(HEALTH_KEYWORDS)),
                    new ContextRule(
                            "CONTEXT_UTILITIES_KEYWORD",
                            "utilities",
                            0.65,
                            ctx -> ctx.textContainsAny(UTILITIES_KEYWORDS)));
}
