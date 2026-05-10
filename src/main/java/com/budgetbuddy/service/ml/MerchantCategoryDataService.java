package com.budgetbuddy.service.ml;


import java.util.Locale;
import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Shared Merchant and Category Data Service
 *
 * <p>Single source of truth for all merchant names, categories, and semantic keywords. Both
 * EnhancedCategoryDetectionService and SemanticMatchingService read from this service and transform
 * the data into their optimal data structures.
 *
 * <p>Benefits: - Single source of truth - easier maintenance - Consistency - both services use same
 * merchant/category mappings - Less duplication - change once, both services benefit - Optimized
 * structures - each service transforms data for its use case
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Service
public class MerchantCategoryDataService {

    private static final String MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT = "Merchant count exceeded safety limit ({}). Stopping data load.";

    private static final String CHARITY = "charity";

    private static final String DEPOSIT = "deposit";

    private static final String DINING = "dining";

    private static final String EDUCATION = "education";

    private static final String ENTERTAINMENT = "entertainment";

    private static final String GROCERIES = "groceries";

    private static final String HEALTH = "health";

    private static final String HEALTHCARE = "healthcare";

    private static final String HOME_IMPROVEMENT = "home improvement";

    private static final String INCOME = "income";

    private static final String INSURANCE = "insurance";

    private static final String INVESTMENT = "investment";

    private static final String OTHER = "other";

    private static final String PAYMENT = "payment";

    private static final String SERVICE = "service";

    private static final String SHOPPING = "shopping";

    private static final String TRANSFER = "transfer";

    private static final String TRANSPORTATION = "transportation";

    private static final String TRAVEL = "travel";

    private static final String UTILITIES = "utilities";

    private static final Logger LOGGER = LoggerFactory.getLogger(MerchantCategoryDataService.class);

    /**
     * Core data structure: Map of merchant/keyword to category This is the canonical source - all
     * other structures derive from this
     */
    private final Map<String, String> merchantToCategory = new HashMap<>();

    /** Category to keywords mapping (for semantic matching) Derived from merchantToCategory */
    private final Map<String, Set<String>> categoryToKeywords = new HashMap<>();

    /** Initialize the service CRITICAL: Thread-safe, error handling, boundary checks */
    @PostConstruct
    public void initialize() {
        try {
            // CRITICAL: Load merchant/category data first
            loadMerchantCategoryData();

            // CRITICAL: Build reverse mapping only if data was loaded successfully
            if (!merchantToCategory.isEmpty()) {
                buildCategoryToKeywordsMapping();
            } else {
                LOGGER.warn(
                        "MerchantCategoryDataService initialized with empty data. Services may not function correctly.");
            }

            // CRITICAL: Validate initialization
            final int merchantCount = merchantToCategory.size();
            final int categoryCount = categoryToKeywords.size();

            if (merchantCount == 0) {
                LOGGER.error(
                        "MerchantCategoryDataService initialized with ZERO merchants. This is a critical error.");
            } else if (merchantCount < 100) {
                LOGGER.warn(
                        "MerchantCategoryDataService initialized with only {} merchants. Expected at least 100.",
                        merchantCount);
            }

            LOGGER.info(
                    "MerchantCategoryDataService initialized with {} merchants across {} categories",
                    merchantCount,
                    categoryCount);

        } catch (Exception e) {
            // CRITICAL: Error handling - log but don't throw (allow service to start)
            LOGGER.error(
                    "Critical error initializing MerchantCategoryDataService: {}",
                    e.getMessage(),
                    e);
            // CRITICAL: Clear partial state
            merchantToCategory.clear();
            categoryToKeywords.clear();
        }
    }

    /**
     * Load all merchant and category data CRITICAL: Thread-safe, error handling, boundary checks
     * This is the single source of truth for all merchant/category mappings
     */
    private void loadMerchantCategoryData() {
        // CRITICAL: Thread-safe initialization
        synchronized (merchantToCategory) {
            try {
                // CRITICAL: Clear existing data to prevent stale state
                merchantToCategory.clear();
                categoryToKeywords.clear();

                // CRITICAL: Boundary check - prevent memory exhaustion
                final int MAX_MERCHANTS = 100_000; // Safety limit
                int merchantCount = 0;

                // ========== CASH (23 merchants) ==========
                merchantToCategory.put("atm cash", "cash");
                merchantToCategory.put("atm withdraw", "cash");
                merchantToCategory.put("atm withdrawal", "cash");
                merchantToCategory.put("atms", "cash");
                merchantToCategory.put("cash advance", "cash");
                merchantToCategory.put("cash back", "cash");
                merchantToCategory.put("cash in", "cash");
                merchantToCategory.put("cash out", "cash");
                merchantToCategory.put("cash payment", "cash");
                merchantToCategory.put("cash purchase", "cash");
                merchantToCategory.put("cash return", "cash");
                merchantToCategory.put("cash transaction", "cash");
                merchantToCategory.put("cash withdraw", "cash");
                merchantToCategory.put("cash withdrawal", "cash");
                merchantToCategory.put("cashback", "cash");
                merchantToCategory.put("cashout", "cash");
                merchantToCategory.put("gaap cash", "cash");
                merchantToCategory.put("mcc 6010", "cash");
                merchantToCategory.put("mcc 6011", "cash");
                merchantToCategory.put("mcc6010", "cash");
                merchantToCategory.put("mcc6011", "cash");
                merchantToCategory.put("withdraw", "cash");
                merchantToCategory.put("withdrawal", "cash");

                merchantCount += 23;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== CHARITY (9 merchants) ==========
                merchantToCategory.put("charitable", CHARITY);
                merchantToCategory.put("donate", CHARITY);
                merchantToCategory.put("donating", CHARITY);
                merchantToCategory.put("donation", CHARITY);
                merchantToCategory.put("go fund me", CHARITY);
                merchantToCategory.put("gofundme", CHARITY);
                merchantToCategory.put("non profit", CHARITY);
                merchantToCategory.put("non-profit", CHARITY);
                merchantToCategory.put("nonprofit", CHARITY);
                merchantToCategory.put(CHARITY, CHARITY);
                merchantToCategory.put("foundation", CHARITY);

                merchantCount += 9;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== DEPOSIT (20 merchants) ==========
                merchantToCategory.put("account deposit", DEPOSIT);
                merchantToCategory.put("bank deposit", DEPOSIT);
                merchantToCategory.put("check deposit", DEPOSIT);
                merchantToCategory.put("checking deposit", DEPOSIT);
                merchantToCategory.put("deposit credit", DEPOSIT);
                merchantToCategory.put("deposit id", DEPOSIT);
                merchantToCategory.put("deposit id number", DEPOSIT);
                merchantToCategory.put("deposit payment", DEPOSIT);
                merchantToCategory.put("deposit transaction", DEPOSIT);
                merchantToCategory.put("deposit transfer", DEPOSIT);
                merchantToCategory.put("deposited", DEPOSIT);
                merchantToCategory.put("depositing", DEPOSIT);
                merchantToCategory.put("mobile deposit", DEPOSIT);
                merchantToCategory.put("direct deposit", DEPOSIT);
                merchantToCategory.put("ach deposit", DEPOSIT);
                merchantToCategory.put("depositor", DEPOSIT);
                merchantToCategory.put("deposits", DEPOSIT);
                merchantToCategory.put("electronic deposit", DEPOSIT);
                merchantToCategory.put("fund deposit", DEPOSIT);
                merchantToCategory.put("mobile deposit", DEPOSIT);
                merchantToCategory.put("money deposit", DEPOSIT);
                merchantToCategory.put("online deposit", DEPOSIT);
                merchantToCategory.put("remote deposit", DEPOSIT);
                merchantToCategory.put("savings deposit", DEPOSIT);
                merchantToCategory.put("wire deposit", DEPOSIT);

                merchantCount += 20;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== DINING (274 merchants) ==========
                merchantToCategory.put("applebee", DINING);
                merchantToCategory.put("arby", DINING);
                merchantToCategory.put("baja fresh", DINING);
                merchantToCategory.put("baker", DINING);
                merchantToCategory.put("banaras", DINING);
                merchantToCategory.put("banaras restaurant", DINING);
                merchantToCategory.put("banarasrestaurant", DINING);
                merchantToCategory.put("bbq", DINING);
                merchantToCategory.put("bbq bar", DINING);
                merchantToCategory.put("bbq cuisine", DINING);
                merchantToCategory.put("bbq restaurant", DINING);
                merchantToCategory.put("bistro", DINING);
                merchantToCategory.put("bread", DINING);
                merchantToCategory.put("breakfast", DINING);
                merchantToCategory.put("brew pub", DINING);
                merchantToCategory.put("brewery", DINING);
                merchantToCategory.put("brewing", DINING);
                merchantToCategory.put("brewpub", DINING);
                merchantToCategory.put("brunch", DINING);
                merchantToCategory.put("buffalo wild wings", DINING);
                merchantToCategory.put("burger", DINING);
                merchantToCategory.put("burger and kabob hut", DINING);
                merchantToCategory.put("burgerandkabobhut", DINING);
                merchantToCategory.put("burrito", DINING);
                merchantToCategory.put("bww", DINING);
                merchantToCategory.put("cafe", DINING);
                merchantToCategory.put("caffe", DINING);
                merchantToCategory.put("caffe nero", DINING);
                merchantToCategory.put("caffee", DINING);
                merchantToCategory.put("café", DINING);
                merchantToCategory.put("cake", DINING);
                merchantToCategory.put("canam", DINING);
                merchantToCategory.put("carl's jr", DINING);
                merchantToCategory.put("carls jr", DINING);
                merchantToCategory.put("catering", DINING);
                merchantToCategory.put("cheesecake", DINING);
                merchantToCategory.put("chili", DINING);
                merchantToCategory.put("chinese", DINING);
                merchantToCategory.put("chinese cuisine", DINING);
                merchantToCategory.put("chinese food", DINING);
                merchantToCategory.put("chinese restaurant", DINING);
                merchantToCategory.put("chipotle", DINING);
                merchantToCategory.put("coffee", DINING);
                merchantToCategory.put("coffee purchase", DINING);
                merchantToCategory.put("cucina venti", DINING);
                merchantToCategory.put("cucinaventi", DINING);
                merchantToCategory.put("culver", DINING);
                merchantToCategory.put("daeho", DINING);
                merchantToCategory.put("dairy queen", DINING);
                merchantToCategory.put("deep dive", DINING);
                merchantToCategory.put("deepdive", DINING);
                merchantToCategory.put("del taco", DINING);
                merchantToCategory.put("delivery", DINING);
                merchantToCategory.put("denny", DINING);
                merchantToCategory.put("desi dhaba", DINING);
                merchantToCategory.put("desidhaba", DINING);
                merchantToCategory.put("dhaba", DINING);
                merchantToCategory.put("dim sum", DINING);
                merchantToCategory.put("dim sum bar", DINING);
                merchantToCategory.put("dim sum cuisine", DINING);
                merchantToCategory.put("dim sum restaurant", DINING);
                merchantToCategory.put("diner", DINING);
                merchantToCategory.put("dining out", DINING);
                merchantToCategory.put("dinner", DINING);
                merchantToCategory.put("domino's", DINING);
                merchantToCategory.put("dominos", DINING);
                merchantToCategory.put("door dash", DINING);
                merchantToCategory.put("doordash", DINING);
                merchantToCategory.put("dq", DINING);
                merchantToCategory.put("dumpling", DINING);
                merchantToCategory.put("dumplings", DINING);
                merchantToCategory.put("dunkin", DINING);
                merchantToCategory.put("dunkin donuts", DINING);
                merchantToCategory.put("dunkindonuts", DINING);
                merchantToCategory.put("eat out", DINING);
                merchantToCategory.put("eatery", DINING);
                merchantToCategory.put("falafel", DINING);
                merchantToCategory.put("filipino", DINING);
                merchantToCategory.put("filipino cuisine", DINING);
                merchantToCategory.put("filipino food", DINING);
                merchantToCategory.put("filipino restaurant", DINING);
                merchantToCategory.put("five guys", DINING);
                merchantToCategory.put("food delivery", DINING);
                merchantToCategory.put("food service", DINING);
                merchantToCategory.put("french", DINING);
                merchantToCategory.put("grill", DINING);
                merchantToCategory.put("grilled", DINING);
                merchantToCategory.put("grub hub", DINING);
                merchantToCategory.put("grubhub", DINING);
                merchantToCategory.put("gyros", DINING);
                merchantToCategory.put("gyudon", DINING);
                merchantToCategory.put("gyudon bar", DINING);
                merchantToCategory.put("gyudon cuisine", DINING);
                merchantToCategory.put("gyudon restaurant", DINING);
                merchantToCategory.put("habit", DINING);
                merchantToCategory.put("hardee", DINING);
                merchantToCategory.put("hawaiian cuisine", DINING);
                merchantToCategory.put("hawaiian food", DINING);
                merchantToCategory.put("hawaiian restaurant", DINING);
                merchantToCategory.put("hoffman", DINING);
                merchantToCategory.put("ihop", DINING);
                merchantToCategory.put("in n out", DINING);
                merchantToCategory.put("in-n-out", DINING);
                merchantToCategory.put("indian", DINING);
                merchantToCategory.put("indian cuisine", DINING);
                merchantToCategory.put("indian food", DINING);
                merchantToCategory.put("indian restaurant", DINING);
                merchantToCategory.put("indian sizzler", DINING);
                merchantToCategory.put("indiansizzler", DINING);
                merchantToCategory.put("indonesian", DINING);
                merchantToCategory.put("indonesian cuisine", DINING);
                merchantToCategory.put("indonesian food", DINING);
                merchantToCategory.put("indonesian restaurant", DINING);
                merchantToCategory.put("insomnia cookie", DINING);
                merchantToCategory.put("insomnia cookies", DINING);
                merchantToCategory.put("insomniacookies", DINING);
                merchantToCategory.put("irs meals and entertainment", DINING);
                merchantToCategory.put("italian", DINING);
                merchantToCategory.put("jack in the box", DINING);
                merchantToCategory.put("japanese", DINING);
                merchantToCategory.put("japanese cuisine", DINING);
                merchantToCategory.put("japanese food", DINING);
                merchantToCategory.put("japanese restaurant", DINING);
                merchantToCategory.put("kabob hut", DINING);
                merchantToCategory.put("kabobhut", DINING);
                merchantToCategory.put("karange", DINING);
                merchantToCategory.put("katsu", DINING);
                merchantToCategory.put("kentucky fried chicken", DINING);
                merchantToCategory.put("kfc", DINING);
                merchantToCategory.put("korean", DINING);
                merchantToCategory.put("korean cuisine", DINING);
                merchantToCategory.put("korean food", DINING);
                merchantToCategory.put("korean restaurant", DINING);
                merchantToCategory.put("kyurmaen", DINING);
                merchantToCategory.put("kyurmaen ramen", DINING);
                merchantToCategory.put("laughing monk", DINING);
                merchantToCategory.put("laughing monk brewing", DINING);
                merchantToCategory.put("laughingmonk", DINING);
                merchantToCategory.put("laughingmonk ", DINING);
                merchantToCategory.put("laughingmonk brewing", DINING);
                merchantToCategory.put("little caesars", DINING);
                merchantToCategory.put("lunch", DINING);
                merchantToCategory.put("malaysian", DINING);
                merchantToCategory.put("malaysian cuisine", DINING);
                merchantToCategory.put("malaysian food", DINING);
                merchantToCategory.put("malaysian restaurant", DINING);
                merchantToCategory.put("maximilian", DINING);
                merchantToCategory.put("maxmillen", DINING);
                merchantToCategory.put("maxmillian", DINING);
                merchantToCategory.put("mcc 5812", DINING);
                merchantToCategory.put("mcc5812", DINING);
                merchantToCategory.put("mcdonald", DINING);
                merchantToCategory.put("meal", DINING);
                merchantToCategory.put("mediterranean", DINING);
                merchantToCategory.put("mediterranean cuisine", DINING);
                merchantToCategory.put("mediterranean food", DINING);
                merchantToCategory.put("mediterranean restaurant", DINING);
                merchantToCategory.put("medocino farms", DINING);
                merchantToCategory.put("medocinofarms", DINING);
                merchantToCategory.put("messina", DINING);
                merchantToCategory.put("mexican", DINING);
                merchantToCategory.put("mexican cuisine", DINING);
                merchantToCategory.put("mexican food", DINING);
                merchantToCategory.put("mexican restaurant", DINING);
                merchantToCategory.put("moe", DINING);
                merchantToCategory.put("naics 72", DINING);
                merchantToCategory.put("noodle bar", DINING);
                merchantToCategory.put("noodle cuisine", DINING);
                merchantToCategory.put("noodle restaurant", DINING);
                merchantToCategory.put("noodles", DINING);
                merchantToCategory.put("olive garden", DINING);
                merchantToCategory.put("outback", DINING);
                merchantToCategory.put("p.f. chang", DINING);
                merchantToCategory.put("panda express", DINING);
                merchantToCategory.put("panera", DINING);
                merchantToCategory.put("papa john", DINING);
                merchantToCategory.put("papa murphy", DINING);
                merchantToCategory.put("pastry", DINING);
                merchantToCategory.put("pay pams", DINING);
                merchantToCategory.put("paypams", DINING);
                merchantToCategory.put("persian", DINING);
                merchantToCategory.put("persian cuisine", DINING);
                merchantToCategory.put("persian food", DINING);
                merchantToCategory.put("persian restaurant", DINING);
                merchantToCategory.put("pf chang", DINING);
                merchantToCategory.put("philippine", DINING);
                merchantToCategory.put("philippine cuisine", DINING);
                merchantToCategory.put("philippine food", DINING);
                merchantToCategory.put("philippine restaurant", DINING);
                merchantToCategory.put("pizza", DINING);
                merchantToCategory.put("postmates", DINING);
                merchantToCategory.put("potbelly", DINING);
                merchantToCategory.put("qdoba", DINING);
                merchantToCategory.put("ramen", DINING);
                merchantToCategory.put("ramen bar", DINING);
                merchantToCategory.put("ramen cuisine", DINING);
                merchantToCategory.put("ramen restaurant", DINING);
                merchantToCategory.put("rbl", DINING);
                merchantToCategory.put("rbl*", DINING);
                merchantToCategory.put("red lobster", DINING);
                merchantToCategory.put("red robin", DINING);
                merchantToCategory.put("restaur", DINING);
                merchantToCategory.put("restaurant meal", DINING);
                merchantToCategory.put("resy", DINING);
                merchantToCategory.put("shake shack", DINING);
                merchantToCategory.put("shana thai", DINING);
                merchantToCategory.put("shanathai", DINING);
                merchantToCategory.put("sic 58", DINING);
                merchantToCategory.put("sic 72", DINING);
                merchantToCategory.put("shawarma", DINING);
                merchantToCategory.put("simply indian", DINING);
                merchantToCategory.put("simply indian restaur", DINING);
                merchantToCategory.put("simply indian restaurant", DINING);
                merchantToCategory.put("simplyindian", DINING);
                merchantToCategory.put("simplyindian restaur", DINING);
                merchantToCategory.put("simplyindian restaurant", DINING);
                merchantToCategory.put("singaporean", DINING);
                merchantToCategory.put("singaporean cuisine", DINING);
                merchantToCategory.put("singaporean food", DINING);
                merchantToCategory.put("singaporean restaurant", DINING);
                merchantToCategory.put("skills rainbow", DINING);
                merchantToCategory.put("skills rainbow room", DINING);
                merchantToCategory.put("skillsrainbow", DINING);
                merchantToCategory.put("skillsrainbow room", DINING);
                merchantToCategory.put("sonic", DINING);
                merchantToCategory.put("spanish", DINING);
                merchantToCategory.put("sq", DINING);
                merchantToCategory.put("sq*", DINING);
                merchantToCategory.put("square inc", DINING);
                merchantToCategory.put("square pos", DINING);
                merchantToCategory.put("sunny honey", DINING);
                merchantToCategory.put("sunnyhoney", DINING);
                merchantToCategory.put("starbucks", DINING);
                merchantToCategory.put("subway", DINING);
                merchantToCategory.put("supreme dumplings", DINING);
                merchantToCategory.put("supremedumplings", DINING);
                merchantToCategory.put("sushi", DINING);
                merchantToCategory.put("sushi bar", DINING);
                merchantToCategory.put("sushi cuisine", DINING);
                merchantToCategory.put("sushi restaurant", DINING);
                merchantToCategory.put("taco", DINING);
                merchantToCategory.put("tacos", DINING);
                merchantToCategory.put("take-out", DINING);
                merchantToCategory.put("takeout", DINING);
                merchantToCategory.put("takeout delivery", DINING);
                merchantToCategory.put("texas roadhouse", DINING);
                merchantToCategory.put("thai", DINING);
                merchantToCategory.put("tiffin", DINING);
                merchantToCategory.put("toast", DINING);
                merchantToCategory.put("toast pos", DINING);
                merchantToCategory.put("tonkatsu", DINING);
                merchantToCategory.put("tonkatsu restaurant", DINING);
                merchantToCategory.put("top pot", DINING);
                merchantToCategory.put("top pot donuts", DINING);
                merchantToCategory.put("toppot", DINING);
                merchantToCategory.put("toppotdonuts", DINING);
                merchantToCategory.put("tpd", DINING);
                merchantToCategory.put("tst", DINING);
                merchantToCategory.put("tst*", DINING);
                merchantToCategory.put("tutta bella", DINING);
                merchantToCategory.put("tuttabella", DINING);
                merchantToCategory.put("uber eat", DINING);
                merchantToCategory.put("uber eats", DINING);
                merchantToCategory.put("ubereats", DINING);
                merchantToCategory.put("vietnamese", DINING);
                merchantToCategory.put("vietnamese cuisine", DINING);
                merchantToCategory.put("vietnamese food", DINING);
                merchantToCategory.put("vietnamese restaurant", DINING);
                merchantToCategory.put("waffle house", DINING);
                merchantToCategory.put("wendy", DINING);
                merchantToCategory.put("whataburger", DINING);
                merchantToCategory.put("white castle", DINING);
                merchantToCategory.put("wingstop", DINING);
                merchantToCategory.put("yakiudon", DINING);
                merchantToCategory.put("yakiudon bar", DINING);
                merchantToCategory.put("yakiudon cuisine", DINING);
                merchantToCategory.put("yakiudon restaurant", DINING);
                merchantToCategory.put("zaxby's", DINING);
                merchantToCategory.put("zaxbys", DINING);

                merchantCount += 274;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== EDUCATION (146 merchants) ==========
                merchantToCategory.put("aamc", EDUCATION);
                merchantToCategory.put("academic journal", EDUCATION);
                merchantToCategory.put("act", EDUCATION);
                merchantToCategory.put("act exam", EDUCATION);
                merchantToCategory.put("act test", EDUCATION);
                merchantToCategory.put("anki", EDUCATION);
                merchantToCategory.put("anki remote", EDUCATION);
                merchantToCategory.put("ap exam", EDUCATION);
                merchantToCategory.put("bar exam", EDUCATION);
                merchantToCategory.put("barron", EDUCATION);
                merchantToCategory.put("barron's", EDUCATION);
                merchantToCategory.put("barrons", EDUCATION);
                merchantToCategory.put("bbc", EDUCATION);
                merchantToCategory.put("bbc news", EDUCATION);
                merchantToCategory.put("bellevue school district", EDUCATION);
                merchantToCategory.put("bellevueschooldistrict", EDUCATION);
                merchantToCategory.put("bloomberg", EDUCATION);
                merchantToCategory.put("bloomberg news", EDUCATION);
                merchantToCategory.put("book store", EDUCATION);
                merchantToCategory.put("books", EDUCATION);
                merchantToCategory.put("bookstore", EDUCATION);
                merchantToCategory.put("boston globe", EDUCATION);
                merchantToCategory.put("chicago tribune", EDUCATION);
                merchantToCategory.put("class", EDUCATION);
                merchantToCategory.put("clep", EDUCATION);
                merchantToCategory.put("cnn", EDUCATION);
                merchantToCategory.put("cnn news", EDUCATION);
                merchantToCategory.put("colegio", EDUCATION);
                merchantToCategory.put("college", EDUCATION);
                merchantToCategory.put("collège", EDUCATION);
                merchantToCategory.put("comlex", EDUCATION);
                merchantToCategory.put("course", EDUCATION);
                merchantToCategory.put("d j*barrons", EDUCATION);
                merchantToCategory.put("dj*barrons", EDUCATION);
                merchantToCategory.put("doctorate", EDUCATION);
                merchantToCategory.put("dorm", EDUCATION);
                merchantToCategory.put("economist", EDUCATION);
                merchantToCategory.put(EDUCATION, EDUCATION);
                merchantToCategory.put("educational", EDUCATION);
                merchantToCategory.put("elementary", EDUCATION);
                merchantToCategory.put("elementary school", EDUCATION);
                merchantToCategory.put("elementaryschool", EDUCATION);
                merchantToCategory.put("escuela", EDUCATION);
                merchantToCategory.put("ets", EDUCATION);
                merchantToCategory.put("exam fee", EDUCATION);
                merchantToCategory.put("exam registration", EDUCATION);
                merchantToCategory.put("financial times", EDUCATION);
                merchantToCategory.put("ft.com", EDUCATION);
                merchantToCategory.put("gmat", EDUCATION);
                merchantToCategory.put("graduate school", EDUCATION);
                merchantToCategory.put("graduateschool", EDUCATION);
                merchantToCategory.put("graduation", EDUCATION);
                merchantToCategory.put("graudation fees", EDUCATION);
                merchantToCategory.put("gre", EDUCATION);
                merchantToCategory.put("gurukul", EDUCATION);
                merchantToCategory.put("high school", EDUCATION);
                merchantToCategory.put("highschool", EDUCATION);
                merchantToCategory.put("ib exam", EDUCATION);
                merchantToCategory.put("j barrons", EDUCATION);
                merchantToCategory.put("j*barrons", EDUCATION);
                merchantToCategory.put("journal", EDUCATION);
                merchantToCategory.put("kuttab", EDUCATION);
                merchantToCategory.put("latimes", EDUCATION);
                merchantToCategory.put("lesson", EDUCATION);
                merchantToCategory.put("library", EDUCATION);
                merchantToCategory.put("los angeles times", EDUCATION);
                merchantToCategory.put("lsat", EDUCATION);
                merchantToCategory.put("madrasa", EDUCATION);
                merchantToCategory.put("madrassa", EDUCATION);
                merchantToCategory.put("magazine", EDUCATION);
                merchantToCategory.put("mcat", EDUCATION);
                merchantToCategory.put("middle school", EDUCATION);
                merchantToCategory.put("middleschool", EDUCATION);
                merchantToCategory.put("nclex", EDUCATION);
                merchantToCategory.put("new york times", EDUCATION);
                merchantToCategory.put("newspaper", EDUCATION);
                merchantToCategory.put("ny times", EDUCATION);
                merchantToCategory.put("nytimes", EDUCATION);
                merchantToCategory.put("p.h.d", EDUCATION);
                merchantToCategory.put("pathshala", EDUCATION);
                merchantToCategory.put("pearson vue", EDUCATION);
                merchantToCategory.put("pearsonvue", EDUCATION);
                merchantToCategory.put("peer-reviewed journal", EDUCATION);
                merchantToCategory.put("ph.d", EDUCATION);
                merchantToCategory.put("ph.d.", EDUCATION);
                merchantToCategory.put("phd", EDUCATION);
                merchantToCategory.put("post graduation", EDUCATION);
                merchantToCategory.put("praxis", EDUCATION);
                merchantToCategory.put("prometric", EDUCATION);
                merchantToCategory.put("ptsa", EDUCATION);
                merchantToCategory.put("reading", EDUCATION);
                merchantToCategory.put("research journal", EDUCATION);
                merchantToCategory.put("reuters", EDUCATION);
                merchantToCategory.put("reuters news", EDUCATION);
                merchantToCategory.put("sat", EDUCATION);
                merchantToCategory.put("scholarly journal", EDUCATION);
                merchantToCategory.put("school", EDUCATION);
                merchantToCategory.put("school district", EDUCATION);
                merchantToCategory.put("school fees", EDUCATION);
                merchantToCategory.put("schooldistrict", EDUCATION);
                merchantToCategory.put("schule", EDUCATION);
                merchantToCategory.put("scientific journal", EDUCATION);
                merchantToCategory.put("secondary", EDUCATION);
                merchantToCategory.put("secondary school", EDUCATION);
                merchantToCategory.put("secondaryschool", EDUCATION);
                merchantToCategory.put("senior secondary", EDUCATION);
                merchantToCategory.put("senior secondary school", EDUCATION);
                merchantToCategory.put("seniorschool", EDUCATION);
                merchantToCategory.put("shiksha", EDUCATION);
                merchantToCategory.put("sp anki remote", EDUCATION);
                merchantToCategory.put("spankiremote", EDUCATION);
                merchantToCategory.put("test center", EDUCATION);
                merchantToCategory.put("test fee", EDUCATION);
                merchantToCategory.put("test registration", EDUCATION);
                merchantToCategory.put("text book", EDUCATION);
                merchantToCategory.put("textbook", EDUCATION);
                merchantToCategory.put("the boston globe", EDUCATION);
                merchantToCategory.put("the economist", EDUCATION);
                merchantToCategory.put("the financial times", EDUCATION);
                merchantToCategory.put("the new york times", EDUCATION);
                merchantToCategory.put("the wall street journal", EDUCATION);
                merchantToCategory.put("the washington post", EDUCATION);
                merchantToCategory.put("toefl", EDUCATION);
                merchantToCategory.put("training", EDUCATION);
                merchantToCategory.put("tuition", EDUCATION);
                merchantToCategory.put("tyee middle school", EDUCATION);
                merchantToCategory.put("tyeemiddleschool", EDUCATION);
                merchantToCategory.put("universidad", EDUCATION);
                merchantToCategory.put("university", EDUCATION);
                merchantToCategory.put("university book", EDUCATION);
                merchantToCategory.put("university book store", EDUCATION);
                merchantToCategory.put("university fees", EDUCATION);
                merchantToCategory.put("universitybook", EDUCATION);
                merchantToCategory.put("universitybookstore", EDUCATION);
                merchantToCategory.put("universität", EDUCATION);
                merchantToCategory.put("université", EDUCATION);
                merchantToCategory.put("usa today", EDUCATION);
                merchantToCategory.put("usatoday", EDUCATION);
                merchantToCategory.put("usmle", EDUCATION);
                merchantToCategory.put("vidyalaya", EDUCATION);
                merchantToCategory.put("vue", EDUCATION);
                merchantToCategory.put("wall street journal", EDUCATION);
                merchantToCategory.put("wapo", EDUCATION);
                merchantToCategory.put("washington post", EDUCATION);
                merchantToCategory.put("wsj", EDUCATION);
                merchantToCategory.put("école", EDUCATION);

                merchantCount += 146;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== ENTERTAINMENT (91 merchants) ==========
                merchantToCategory.put("alamo drafthouse", ENTERTAINMENT);
                merchantToCategory.put("amazon prime", ENTERTAINMENT);
                merchantToCategory.put("amc", ENTERTAINMENT);
                merchantToCategory.put("amc theaters", ENTERTAINMENT);
                merchantToCategory.put("apple music", ENTERTAINMENT);
                merchantToCategory.put("apple tv", ENTERTAINMENT);
                merchantToCategory.put("apple tv plus", ENTERTAINMENT);
                merchantToCategory.put("apple tv+", ENTERTAINMENT);
                merchantToCategory.put("arc light", ENTERTAINMENT);
                merchantToCategory.put("arc light cinemas", ENTERTAINMENT);
                merchantToCategory.put("audio", ENTERTAINMENT);
                merchantToCategory.put("camping", ENTERTAINMENT);
                merchantToCategory.put("cape disappointment", ENTERTAINMENT);
                merchantToCategory.put("carmike", ENTERTAINMENT);
                merchantToCategory.put("carmike cinemas", ENTERTAINMENT);
                merchantToCategory.put("cinema", ENTERTAINMENT);
                merchantToCategory.put("cinemark", ENTERTAINMENT);
                merchantToCategory.put("concert", ENTERTAINMENT);
                merchantToCategory.put("conundroom", ENTERTAINMENT);
                merchantToCategory.put("conundroom.us", ENTERTAINMENT);
                merchantToCategory.put("crunchyroll", ENTERTAINMENT);
                merchantToCategory.put("discovery plus", ENTERTAINMENT);
                merchantToCategory.put("disney", ENTERTAINMENT);
                merchantToCategory.put("disney plus", ENTERTAINMENT);
                merchantToCategory.put("disney+", ENTERTAINMENT);
                merchantToCategory.put("entertainment service", ENTERTAINMENT);
                merchantToCategory.put("escape room", ENTERTAINMENT);
                merchantToCategory.put("escaperoom", ENTERTAINMENT);
                merchantToCategory.put("event", ENTERTAINMENT);
                merchantToCategory.put("film", ENTERTAINMENT);
                merchantToCategory.put("fox sports", ENTERTAINMENT);
                merchantToCategory.put("fubo", ENTERTAINMENT);
                merchantToCategory.put("funimation", ENTERTAINMENT);
                merchantToCategory.put("game", ENTERTAINMENT);
                merchantToCategory.put("harkins", ENTERTAINMENT);
                merchantToCategory.put("hbo", ENTERTAINMENT);
                merchantToCategory.put("hbo max", ENTERTAINMENT);
                merchantToCategory.put("hlu", ENTERTAINMENT);
                merchantToCategory.put("hulu", ENTERTAINMENT);
                merchantToCategory.put("hulu plus", ENTERTAINMENT);
                merchantToCategory.put("huluplus", ENTERTAINMENT);
                merchantToCategory.put("imax", ENTERTAINMENT);
                merchantToCategory.put("marcus theaters", ENTERTAINMENT);
                merchantToCategory.put("max", ENTERTAINMENT);
                merchantToCategory.put("mcc 7832", ENTERTAINMENT);
                merchantToCategory.put("mcc 7922", ENTERTAINMENT);
                merchantToCategory.put("mcc7832", ENTERTAINMENT);
                merchantToCategory.put("mcc7922", ENTERTAINMENT);
                merchantToCategory.put("media", ENTERTAINMENT);
                merchantToCategory.put("movie", ENTERTAINMENT);
                merchantToCategory.put("movie theater", ENTERTAINMENT);
                merchantToCategory.put("movietheater", ENTERTAINMENT);
                merchantToCategory.put("music", ENTERTAINMENT);
                merchantToCategory.put("naics 71", ENTERTAINMENT);
                merchantToCategory.put("nbc", ENTERTAINMENT);
                merchantToCategory.put("nbc peacock", ENTERTAINMENT);
                merchantToCategory.put("netflix", ENTERTAINMENT);
                merchantToCategory.put("paramount plus", ENTERTAINMENT);
                merchantToCategory.put("paramount+", ENTERTAINMENT);
                merchantToCategory.put("peacock", ENTERTAINMENT);
                merchantToCategory.put("prime video", ENTERTAINMENT);
                merchantToCategory.put("recreation.gov", ENTERTAINMENT);
                merchantToCategory.put("regal", ENTERTAINMENT);
                merchantToCategory.put("regal cinemas", ENTERTAINMENT);
                merchantToCategory.put("sea world", ENTERTAINMENT);
                merchantToCategory.put("seaworld", ENTERTAINMENT);
                merchantToCategory.put("show", ENTERTAINMENT);
                merchantToCategory.put("showtime", ENTERTAINMENT);
                merchantToCategory.put("sic 78", ENTERTAINMENT);
                merchantToCategory.put("sic 79", ENTERTAINMENT);
                merchantToCategory.put("sling tv", ENTERTAINMENT);
                merchantToCategory.put("sports", ENTERTAINMENT);
                merchantToCategory.put("spotify", ENTERTAINMENT);
                merchantToCategory.put("starz", ENTERTAINMENT);
                merchantToCategory.put("state fair", ENTERTAINMENT);
                merchantToCategory.put("statefair", ENTERTAINMENT);
                merchantToCategory.put("theater", ENTERTAINMENT);
                merchantToCategory.put("theatre", ENTERTAINMENT);
                merchantToCategory.put("ticket", ENTERTAINMENT);
                merchantToCategory.put("top golf", ENTERTAINMENT);
                merchantToCategory.put("topgolf", ENTERTAINMENT);
                merchantToCategory.put("universal", ENTERTAINMENT);
                merchantToCategory.put("universal studio", ENTERTAINMENT);
                merchantToCategory.put("universalstudio", ENTERTAINMENT);
                merchantToCategory.put("video", ENTERTAINMENT);
                merchantToCategory.put("xfinity tv", ENTERTAINMENT);
                merchantToCategory.put("xfinitytv", ENTERTAINMENT);
                merchantToCategory.put("youtube", ENTERTAINMENT);
                merchantToCategory.put("youtube premium", ENTERTAINMENT);
                merchantToCategory.put("youtube tv", ENTERTAINMENT);
                merchantToCategory.put("ytmusic", ENTERTAINMENT);

                merchantCount += 91;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== GROCERIES (164 merchants) ==========
                merchantToCategory.put("99 Ranch Market", GROCERIES);
                merchantToCategory.put("Acme", GROCERIES);
                merchantToCategory.put("Apna Bazar", GROCERIES);
                merchantToCategory.put("Asian Family Market", GROCERIES);
                merchantToCategory.put("Baker's", GROCERIES);
                merchantToCategory.put("Balducci's Food Lovers Market", GROCERIES);
                merchantToCategory.put("Big John’s PFI", GROCERIES);
                merchantToCategory.put("Big Y", GROCERIES);
                merchantToCategory.put("CU", GROCERIES);
                merchantToCategory.put("Carrs", GROCERIES);
                merchantToCategory.put("City Market", GROCERIES);
                merchantToCategory.put("Cumberland Farms", GROCERIES);
                merchantToCategory.put("Desi basket", GROCERIES);
                merchantToCategory.put("Dillon's", GROCERIES);
                merchantToCategory.put("Dillons", GROCERIES);
                merchantToCategory.put("Dollar General", GROCERIES);
                merchantToCategory.put("Dollar Tree", GROCERIES);
                merchantToCategory.put("Dollarama", GROCERIES);
                merchantToCategory.put("Erewhon Market", GROCERIES);
                merchantToCategory.put("Erwan's Market", GROCERIES);
                merchantToCategory.put("Family Dollar", GROCERIES);
                merchantToCategory.put("Five Below", GROCERIES);
                merchantToCategory.put("Food 4 Less", GROCERIES);
                merchantToCategory.put("Foods Co.", GROCERIES);
                merchantToCategory.put("Fou Lee Market & Deli", GROCERIES);
                merchantToCategory.put("Gerbes", GROCERIES);
                merchantToCategory.put("Giant Food", GROCERIES);
                merchantToCategory.put("Glatt Mart", GROCERIES);
                merchantToCategory.put("Gourmet Garage", GROCERIES);
                merchantToCategory.put("H Mart", GROCERIES);
                merchantToCategory.put("Haggen", GROCERIES);
                merchantToCategory.put("Hannaford", GROCERIES);
                merchantToCategory.put("Holyland Market", GROCERIES);
                merchantToCategory.put("India Cash and Carry", GROCERIES);
                merchantToCategory.put("India Metro Hypermarket", GROCERIES);
                merchantToCategory.put("India Supermarket", GROCERIES);
                merchantToCategory.put("Indian CO", GROCERIES);
                merchantToCategory.put("International Deli", GROCERIES);
                merchantToCategory.put("Jagalchi", GROCERIES);
                merchantToCategory.put("Jay C Food Store", GROCERIES);
                merchantToCategory.put("Jewel-Osco", GROCERIES);
                merchantToCategory.put("Kings Food Markets", GROCERIES);
                merchantToCategory.put("LA Superior", GROCERIES);
                merchantToCategory.put("Mariano's", GROCERIES);
                merchantToCategory.put("Market of Choice", GROCERIES);
                merchantToCategory.put("Martin's Food Markets", GROCERIES);
                merchantToCategory.put("Mega Mart", GROCERIES);
                merchantToCategory.put("Metro Market", GROCERIES);
                merchantToCategory.put("Mitsuwa Marketplace", GROCERIES);
                merchantToCategory.put("Online Specialty Stores", GROCERIES);
                merchantToCategory.put("Oskoo Persian & Mediterranean market", GROCERIES);
                merchantToCategory.put("Patel Brothers", GROCERIES);
                merchantToCategory.put("Pavilions", GROCERIES);
                merchantToCategory.put("Pay-Less Super Markets", GROCERIES);
                merchantToCategory.put("Pick 'n Save", GROCERIES);
                merchantToCategory.put("Randalls", GROCERIES);
                merchantToCategory.put("Rose Persian Market & Halal Butchery", GROCERIES);
                merchantToCategory.put("S-Mart", GROCERIES);
                merchantToCategory.put("Shaw's", GROCERIES);
                merchantToCategory.put("Shufersal ", GROCERIES);
                merchantToCategory.put("Smith's Food and Drug", GROCERIES);
                merchantToCategory.put("Sprouts Market", GROCERIES);
                merchantToCategory.put("Star Market", GROCERIES);
                merchantToCategory.put("T&T Supermarket", GROCERIES);
                merchantToCategory.put("The GIANT Company", GROCERIES);
                merchantToCategory.put("The Souk", GROCERIES);
                merchantToCategory.put("Thrive Market", GROCERIES);
                merchantToCategory.put("Tom Thumb", GROCERIES);
                merchantToCategory.put("Tops Friendly Market", GROCERIES);
                merchantToCategory.put(
                        "United Supermarkets (including Market Street, Amigos, and United Express formats)",
                        GROCERIES);
                merchantToCategory.put("Vons", GROCERIES);
                merchantToCategory.put("Weee", GROCERIES);
                merchantToCategory.put("WholeFoods", GROCERIES);
                merchantToCategory.put("Zion Market", GROCERIES);
                merchantToCategory.put("aldi", GROCERIES);
                merchantToCategory.put("amazon fresh", GROCERIES);
                merchantToCategory.put("bj's wholesale club", GROCERIES);
                merchantToCategory.put("chef store", GROCERIES);
                merchantToCategory.put("costco", GROCERIES);
                merchantToCategory.put("costco warehouse", GROCERIES);
                merchantToCategory.put("costco whse", GROCERIES);
                merchantToCategory.put("costcowarehouse", GROCERIES);
                merchantToCategory.put("costcowhse", GROCERIES);
                merchantToCategory.put("dk market", GROCERIES);
                merchantToCategory.put("food", GROCERIES);
                merchantToCategory.put("food center", GROCERIES);
                merchantToCategory.put("food lion", GROCERIES);
                merchantToCategory.put("food market", GROCERIES);
                merchantToCategory.put("food mart", GROCERIES);
                merchantToCategory.put("food shop", GROCERIES);
                merchantToCategory.put("food shopping", GROCERIES);
                merchantToCategory.put("food store", GROCERIES);
                merchantToCategory.put("foodmart", GROCERIES);
                merchantToCategory.put("fred meyer", GROCERIES);
                merchantToCategory.put("fredmeyer", GROCERIES);
                merchantToCategory.put("fresh direct", GROCERIES);
                merchantToCategory.put("fresh food", GROCERIES);
                merchantToCategory.put("freshdirect", GROCERIES);
                merchantToCategory.put("giant", GROCERIES);
                merchantToCategory.put("giant eagle", GROCERIES);
                merchantToCategory.put(GROCERIES, GROCERIES);
                merchantToCategory.put("grocery", GROCERIES);
                merchantToCategory.put("grocery center", GROCERIES);
                merchantToCategory.put("grocery market", GROCERIES);
                merchantToCategory.put("grocery shopping", GROCERIES);
                merchantToCategory.put("grocery store", GROCERIES);
                merchantToCategory.put("h-e-b", GROCERIES);
                merchantToCategory.put("halal", GROCERIES);
                merchantToCategory.put("harris teeter", GROCERIES);
                merchantToCategory.put("heb", GROCERIES);
                merchantToCategory.put("hy-vee", GROCERIES);
                merchantToCategory.put("hypermarket", GROCERIES);
                merchantToCategory.put("hyvee", GROCERIES);
                merchantToCategory.put("imperfect foods", GROCERIES);
                merchantToCategory.put("imperfectfoods", GROCERIES);
                merchantToCategory.put("instacart", GROCERIES);
                merchantToCategory.put("king soopers", GROCERIES);
                merchantToCategory.put("kroger", GROCERIES);
                merchantToCategory.put("lidl", GROCERIES);
                merchantToCategory.put("market", GROCERIES);
                merchantToCategory.put("mayuri", GROCERIES);
                merchantToCategory.put("mcc 5411", GROCERIES);
                merchantToCategory.put("mcc5411", GROCERIES);
                merchantToCategory.put("meet fresh", GROCERIES);
                merchantToCategory.put("meetfresh", GROCERIES);
                merchantToCategory.put("meijer", GROCERIES);
                merchantToCategory.put("naics 11", GROCERIES);
                merchantToCategory.put("pantry", GROCERIES);
                merchantToCategory.put("pcc", GROCERIES);
                merchantToCategory.put("produce", GROCERIES);
                merchantToCategory.put("publix", GROCERIES);
                merchantToCategory.put("qfc", GROCERIES);
                merchantToCategory.put("ralph's", GROCERIES);
                merchantToCategory.put("ralphs", GROCERIES);
                merchantToCategory.put("safeway", GROCERIES);
                merchantToCategory.put("sam's club", GROCERIES);
                merchantToCategory.put("shipt", GROCERIES);
                merchantToCategory.put("shoprite", GROCERIES);
                merchantToCategory.put("sic 01", GROCERIES);
                merchantToCategory.put("sic 02", GROCERIES);
                merchantToCategory.put("sic 07", GROCERIES);
                merchantToCategory.put("sic 20", GROCERIES);
                merchantToCategory.put("sic 54", GROCERIES);
                merchantToCategory.put("sprouts", GROCERIES);
                merchantToCategory.put("sprouts farmers market", GROCERIES);
                merchantToCategory.put("stop & shop", GROCERIES);
                merchantToCategory.put("stop and shop", GROCERIES);
                merchantToCategory.put("store", GROCERIES);
                // Sunny Honey is a SQ*-prefixed Pike Place vendor (prepared-
                // foods stall, not a grocer); mapped to "dining" in the
                // DINING block so SQ* restaurant-POS transactions resolve
                // cleanly.
                merchantToCategory.put("super market", GROCERIES);
                merchantToCategory.put("supermarket shopping", GROCERIES);
                merchantToCategory.put("superstore", GROCERIES);
                merchantToCategory.put("target", GROCERIES);
                merchantToCategory.put("town & country", GROCERIES);
                merchantToCategory.put("town and country", GROCERIES);
                merchantToCategory.put("town&country", GROCERIES);
                merchantToCategory.put("trader joe", GROCERIES);
                merchantToCategory.put("walmart", GROCERIES);
                merchantToCategory.put("wegmans", GROCERIES);
                merchantToCategory.put("whole foods", GROCERIES);
                merchantToCategory.put("winco", GROCERIES);
                merchantToCategory.put("wmt", GROCERIES);
                merchantToCategory.put("iShopIndian", GROCERIES);

                merchantCount += 164;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== HEALTH (130 merchants) ==========
                merchantToCategory.put("24 hour fitness", HEALTH);
                merchantToCategory.put("24-hour fitness", HEALTH);
                merchantToCategory.put("24hour fitness", HEALTH);
                merchantToCategory.put("24hr fitness", HEALTH);
                merchantToCategory.put("anytime fitness", HEALTH);
                merchantToCategory.put("athletic club", HEALTH);
                merchantToCategory.put("athletic facility", HEALTH);
                merchantToCategory.put("athleticclub", HEALTH);
                merchantToCategory.put("badminton", HEALTH);
                merchantToCategory.put("badminton club", HEALTH);
                merchantToCategory.put("badmintonclub", HEALTH);
                merchantToCategory.put("baseball", HEALTH);
                merchantToCategory.put("basketball", HEALTH);
                merchantToCategory.put("beauty parlor", HEALTH);
                merchantToCategory.put("beauty salon", HEALTH);
                merchantToCategory.put("beauty studio", HEALTH);
                merchantToCategory.put("beautyparlor", HEALTH);
                merchantToCategory.put("beautysalon", HEALTH);
                merchantToCategory.put("beautystudio", HEALTH);
                merchantToCategory.put("body waxing", HEALTH);
                merchantToCategory.put("bodywaxing", HEALTH);
                merchantToCategory.put("classpass", HEALTH);
                // Cosmetics / makeup is discretionary shopping, not health —
                // users budget it as beauty spend, not medical. Previously
                // mis-mapped to "health" which inflated healthcare
                // aggregates and under-counted shopping.
                merchantToCategory.put("cosmetic shop", SHOPPING);
                merchantToCategory.put("cosmetic store", SHOPPING);
                merchantToCategory.put("cosmetics", SHOPPING);
                merchantToCategory.put("cosmeticshop", SHOPPING);
                merchantToCategory.put("cosmeticstore", SHOPPING);
                merchantToCategory.put("crossfit", HEALTH);
                merchantToCategory.put("crossfit gym", HEALTH);
                merchantToCategory.put("crunch fitness", HEALTH);
                merchantToCategory.put("cycling", HEALTH);
                merchantToCategory.put("cycling club", HEALTH);
                merchantToCategory.put("dance club", HEALTH);
                merchantToCategory.put("dance studio", HEALTH);
                merchantToCategory.put("equinox", HEALTH);
                merchantToCategory.put("exercise", HEALTH);
                merchantToCategory.put("fitness", HEALTH);
                merchantToCategory.put("fitness center", HEALTH);
                merchantToCategory.put("fitness club", HEALTH);
                merchantToCategory.put("fitness facility", HEALTH);
                merchantToCategory.put("fitnesscenter", HEALTH);
                merchantToCategory.put("fitnessclub", HEALTH);
                merchantToCategory.put("football", HEALTH);
                merchantToCategory.put("gold's gym", HEALTH);
                merchantToCategory.put("golds gym", HEALTH);
                merchantToCategory.put("golf", HEALTH);
                merchantToCategory.put("great clips", HEALTH);
                merchantToCategory.put("greatclips", HEALTH);
                merchantToCategory.put("gym", HEALTH);
                merchantToCategory.put("hair color", HEALTH);
                merchantToCategory.put("hair cut", HEALTH);
                merchantToCategory.put("hair cuts", HEALTH);
                merchantToCategory.put("hair salon", HEALTH);
                merchantToCategory.put("haircolor", HEALTH);
                merchantToCategory.put("haircut", HEALTH);
                merchantToCategory.put("haircuts", HEALTH);
                merchantToCategory.put("hairsalon", HEALTH);
                merchantToCategory.put("health center", HEALTH);
                merchantToCategory.put("health club", HEALTH);
                merchantToCategory.put("healthclub", HEALTH);
                merchantToCategory.put("judo", HEALTH);
                merchantToCategory.put("karate", HEALTH);
                merchantToCategory.put("la fitness", HEALTH);
                merchantToCategory.put("lifetime fitness", HEALTH);
                merchantToCategory.put("lucky hair salin", HEALTH);
                merchantToCategory.put("lucky hair salon", HEALTH);
                merchantToCategory.put("luckyhair", HEALTH);
                merchantToCategory.put("luckyhairsalin", HEALTH);
                merchantToCategory.put("make up", HEALTH);
                merchantToCategory.put("makeup", HEALTH);
                merchantToCategory.put("makeup store", HEALTH);
                merchantToCategory.put("makeupstore", HEALTH);
                merchantToCategory.put("manicure", HEALTH);
                merchantToCategory.put("martial arts", HEALTH);
                merchantToCategory.put("massage", HEALTH);
                merchantToCategory.put("massages", HEALTH);
                merchantToCategory.put("mindbody", HEALTH);
                merchantToCategory.put("nail", HEALTH);
                merchantToCategory.put("nail salon", HEALTH);
                merchantToCategory.put("nails", HEALTH);
                merchantToCategory.put("nailsalon", HEALTH);
                merchantToCategory.put("new york cosmetic", HEALTH);
                merchantToCategory.put("new york cosmetic store", HEALTH);
                merchantToCategory.put("newyorkcosmeticstore", HEALTH);
                merchantToCategory.put("ny cosmetic", HEALTH);
                merchantToCategory.put("ny cosmetic store", HEALTH);
                merchantToCategory.put("nycosmeticstore", HEALTH);
                merchantToCategory.put("orange theory", HEALTH);
                merchantToCategory.put("orangetheory", HEALTH);
                merchantToCategory.put("pedicure", HEALTH);
                merchantToCategory.put("personal trainer", HEALTH);
                merchantToCategory.put("pilates", HEALTH);
                merchantToCategory.put("pilates studio", HEALTH);
                merchantToCategory.put("planet fitness", HEALTH);
                merchantToCategory.put("pro club", HEALTH);
                merchantToCategory.put("proclub", HEALTH);
                merchantToCategory.put("recreation center", HEALTH);
                merchantToCategory.put("running club", HEALTH);
                merchantToCategory.put("salon", HEALTH);
                merchantToCategory.put("seattle badminton club", HEALTH);
                merchantToCategory.put("seattlebadmintonclub", HEALTH);
                merchantToCategory.put("mini mountain", HEALTH);
                merchantToCategory.put("minimountain", HEALTH);
                merchantToCategory.put("ski", HEALTH);
                merchantToCategory.put("ski rental", HEALTH);
                merchantToCategory.put("ski resort", HEALTH);
                merchantToCategory.put("ski school", HEALTH);
                merchantToCategory.put("skin", HEALTH);
                merchantToCategory.put("skin care", HEALTH);
                merchantToCategory.put("skincare", HEALTH);
                merchantToCategory.put("soccer", HEALTH);
                merchantToCategory.put("spa", HEALTH);
                merchantToCategory.put("sports club", HEALTH);
                merchantToCategory.put("sports facility", HEALTH);
                merchantToCategory.put("sportsclub", HEALTH);
                merchantToCategory.put("stop 4 nails", HEALTH);
                merchantToCategory.put("stop four nails", HEALTH);
                merchantToCategory.put("stop4nails", HEALTH);
                merchantToCategory.put("stopfournails", HEALTH);
                merchantToCategory.put("summit at snoqualmie", HEALTH);
                merchantToCategory.put("super cuts", HEALTH);
                merchantToCategory.put("supercuts", HEALTH);
                merchantToCategory.put("swimming", HEALTH);
                merchantToCategory.put("swimming club", HEALTH);
                merchantToCategory.put("taekwondo", HEALTH);
                merchantToCategory.put("tennis", HEALTH);
                merchantToCategory.put("tennis club", HEALTH);
                merchantToCategory.put("toes", HEALTH);
                merchantToCategory.put("waxing", HEALTH);
                merchantToCategory.put("wellness center", HEALTH);
                merchantToCategory.put("workout", HEALTH);
                merchantToCategory.put("ymca", HEALTH);
                merchantToCategory.put("yoga", HEALTH);
                merchantToCategory.put("yoga studio", HEALTH);

                merchantCount += 130;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== HEALTHCARE (74 merchants) ==========
                merchantToCategory.put("aetna", HEALTHCARE);
                merchantToCategory.put("anthem", HEALTHCARE);
                merchantToCategory.put("anthem blue cross", HEALTHCARE);
                merchantToCategory.put("bcbs", HEALTHCARE);
                merchantToCategory.put("blue cross", HEALTHCARE);
                merchantToCategory.put("blue cross blue shield", HEALTHCARE);
                merchantToCategory.put("chiropractor", HEALTHCARE);
                merchantToCategory.put("cigna", HEALTHCARE);
                merchantToCategory.put("clinic", HEALTHCARE);
                merchantToCategory.put("costco pharmacy", HEALTHCARE);
                merchantToCategory.put("cvs", HEALTHCARE);
                merchantToCategory.put("cvs pharmacy", HEALTHCARE);
                merchantToCategory.put("cvs pharmacy store", HEALTHCARE);
                merchantToCategory.put("cvs pharmacy store and clinic", HEALTHCARE);
                merchantToCategory.put("dental", HEALTHCARE);
                merchantToCategory.put("dentist", HEALTHCARE);
                merchantToCategory.put("drugstore", HEALTHCARE);
                merchantToCategory.put("emergency care", HEALTHCARE);
                merchantToCategory.put("eye care", HEALTHCARE);
                merchantToCategory.put("eyeglasses", HEALTHCARE);
                merchantToCategory.put(HEALTH, HEALTHCARE);
                merchantToCategory.put("health care", HEALTHCARE);
                merchantToCategory.put("health service", HEALTHCARE);
                merchantToCategory.put(HEALTHCARE, HEALTHCARE);
                merchantToCategory.put("humana", HEALTHCARE);
                merchantToCategory.put("kaiser", HEALTHCARE);
                merchantToCategory.put("kaiser permanente", HEALTHCARE);
                merchantToCategory.put("kroger pharmacy", HEALTHCARE);
                merchantToCategory.put("mcc 5912", HEALTHCARE);
                merchantToCategory.put("mcc 8011", HEALTHCARE);
                merchantToCategory.put("mcc 8021", HEALTHCARE);
                merchantToCategory.put("mcc 8041", HEALTHCARE);
                merchantToCategory.put("mcc 8042", HEALTHCARE);
                merchantToCategory.put("mcc 8043", HEALTHCARE);
                merchantToCategory.put("mcc 8062", HEALTHCARE);
                merchantToCategory.put("mcc5912", HEALTHCARE);
                merchantToCategory.put("mcc8011", HEALTHCARE);
                merchantToCategory.put("mcc8021", HEALTHCARE);
                merchantToCategory.put("mcc8041", HEALTHCARE);
                merchantToCategory.put("mcc8042", HEALTHCARE);
                merchantToCategory.put("mcc8043", HEALTHCARE);
                merchantToCategory.put("mcc8062", HEALTHCARE);
                merchantToCategory.put("medical care", HEALTHCARE);
                merchantToCategory.put("medication", HEALTHCARE);
                merchantToCategory.put("medicine", HEALTHCARE);
                merchantToCategory.put("naics 62", HEALTHCARE);
                merchantToCategory.put("optical goods", HEALTHCARE);
                merchantToCategory.put("optician", HEALTHCARE);
                merchantToCategory.put("optometrist", HEALTHCARE);
                merchantToCategory.put("optometry", HEALTHCARE);
                merchantToCategory.put("orthodontist", HEALTHCARE);
                merchantToCategory.put("overlake", HEALTHCARE);
                merchantToCategory.put("physician", HEALTHCARE);
                merchantToCategory.put("premera", HEALTHCARE);
                merchantToCategory.put("prescription", HEALTHCARE);
                merchantToCategory.put("providence", HEALTHCARE);
                merchantToCategory.put("rite aid", HEALTHCARE);
                merchantToCategory.put("riteaid", HEALTHCARE);
                merchantToCategory.put("safeway pharmacy", HEALTHCARE);
                merchantToCategory.put("seattle cancer care alliance", HEALTHCARE);
                merchantToCategory.put("seattle children's", HEALTHCARE);
                merchantToCategory.put("seattle genetics", HEALTHCARE);
                merchantToCategory.put("sic 28", HEALTHCARE);
                merchantToCategory.put("sic 80", HEALTHCARE);
                merchantToCategory.put("swedish hospital", HEALTHCARE);
                merchantToCategory.put("target pharmacy", HEALTHCARE);
                merchantToCategory.put("united healthcare", HEALTHCARE);
                merchantToCategory.put("unitedhealthcare", HEALTHCARE);
                merchantToCategory.put("urgent care", HEALTHCARE);
                merchantToCategory.put("virginia mason", HEALTHCARE);
                merchantToCategory.put("vision", HEALTHCARE);
                merchantToCategory.put("walgreens", HEALTHCARE);
                merchantToCategory.put("walgreens pharmacy", HEALTHCARE);
                merchantToCategory.put("walmart pharmacy", HEALTHCARE);

                merchantCount += 74;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== HOME IMPROVEMENT (33 merchants) ==========
                merchantToCategory.put("ace", HOME_IMPROVEMENT);
                merchantToCategory.put("ace hardware", HOME_IMPROVEMENT);
                merchantToCategory.put("behr", HOME_IMPROVEMENT);
                merchantToCategory.put("benjamin moore", HOME_IMPROVEMENT);
                merchantToCategory.put("harbor freight", HOME_IMPROVEMENT);
                merchantToCategory.put("harbor freight tools", HOME_IMPROVEMENT);
                merchantToCategory.put("home depot", HOME_IMPROVEMENT);
                merchantToCategory.put("homedepot", HOME_IMPROVEMENT);
                merchantToCategory.put("irs repairs and maintenance", HOME_IMPROVEMENT);
                merchantToCategory.put("lowes", HOME_IMPROVEMENT);
                merchantToCategory.put("lowes home improvement", HOME_IMPROVEMENT);
                merchantToCategory.put("mcc 1520", HOME_IMPROVEMENT);
                merchantToCategory.put("mcc 5211", HOME_IMPROVEMENT);
                merchantToCategory.put("mcc 5231", HOME_IMPROVEMENT);
                merchantToCategory.put("mcc1520", HOME_IMPROVEMENT);
                merchantToCategory.put("mcc5211", HOME_IMPROVEMENT);
                merchantToCategory.put("mcc5231", HOME_IMPROVEMENT);
                merchantToCategory.put("menards", HOME_IMPROVEMENT);
                merchantToCategory.put("naics 23", HOME_IMPROVEMENT);
                merchantToCategory.put("northern tool", HOME_IMPROVEMENT);
                merchantToCategory.put("northern tool & equipment", HOME_IMPROVEMENT);
                merchantToCategory.put("ppg", HOME_IMPROVEMENT);
                merchantToCategory.put("ppg paints", HOME_IMPROVEMENT);
                merchantToCategory.put("sherwin williams", HOME_IMPROVEMENT);
                merchantToCategory.put("sic 15", HOME_IMPROVEMENT);
                merchantToCategory.put("sic 17", HOME_IMPROVEMENT);
                merchantToCategory.put("sic 24", HOME_IMPROVEMENT);
                merchantToCategory.put("sic 25", HOME_IMPROVEMENT);
                merchantToCategory.put("tractor supply", HOME_IMPROVEMENT);
                merchantToCategory.put("tractor supply company", HOME_IMPROVEMENT);
                merchantToCategory.put("true value", HOME_IMPROVEMENT);
                merchantToCategory.put("truevalue", HOME_IMPROVEMENT);
                merchantToCategory.put("valspar", HOME_IMPROVEMENT);

                merchantCount += 33;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== INCOME (177 merchants) ==========
                merchantToCategory.put("ach 21", INCOME);
                merchantToCategory.put("ach 31", INCOME);
                merchantToCategory.put("ach 41", INCOME);
                merchantToCategory.put("ach credit", INCOME);
                merchantToCategory.put("ach deposit", INCOME);
                merchantToCategory.put("adp", INCOME);
                merchantToCategory.put("adp ez labor", INCOME);
                merchantToCategory.put("adp mobile", INCOME);
                merchantToCategory.put("adp portal", INCOME);
                merchantToCategory.put("adp run", INCOME);
                merchantToCategory.put("adp totalsource", INCOME);
                merchantToCategory.put("adp vantage", INCOME);
                merchantToCategory.put("adp workforce now", INCOME);
                merchantToCategory.put("alphabet inc", INCOME);
                merchantToCategory.put("amazon services", INCOME);
                merchantToCategory.put("apple inc", INCOME);
                merchantToCategory.put("automatic data processing", INCOME);
                merchantToCategory.put("bacs direct credit", INCOME);
                merchantToCategory.put("bamboohr", INCOME);
                merchantToCategory.put("berkshire hathaway", INCOME);
                merchantToCategory.put("bmw", INCOME);
                merchantToCategory.put("boeing", INCOME);
                merchantToCategory.put("cardinal health", INCOME);
                merchantToCategory.put("ceridian", INCOME);
                merchantToCategory.put("ceridian dayforce", INCOME);
                merchantToCategory.put("chevron corporation", INCOME);
                merchantToCategory.put("citigroup", INCOME);
                merchantToCategory.put("coca cola", INCOME);
                merchantToCategory.put("compensation", INCOME);
                merchantToCategory.put("costco wholesale company", INCOME);
                merchantToCategory.put("costco wholesale corporation", INCOME);
                merchantToCategory.put("costco wholesale inc", INCOME);
                merchantToCategory.put("cvs health", INCOME);
                merchantToCategory.put("daimler", INCOME);
                merchantToCategory.put("defense finance", INCOME);
                merchantToCategory.put("defense finance and accounting service", INCOME);
                merchantToCategory.put("department of veterans affairs", INCOME);
                merchantToCategory.put("dfas", INCOME);
                merchantToCategory.put("direct deposit", INCOME);
                merchantToCategory.put("directdeposit", INCOME);
                merchantToCategory.put("earnings", INCOME);
                merchantToCategory.put("exxon mobil", INCOME);
                merchantToCategory.put("facebook inc", INCOME);
                merchantToCategory.put("federal tax refund", INCOME);
                merchantToCategory.put("federal unemployment", INCOME);
                merchantToCategory.put("fiat chrysler", INCOME);
                merchantToCategory.put("ford", INCOME);
                merchantToCategory.put("ford motor", INCOME);
                merchantToCategory.put("gaap accounts receivable", INCOME);
                merchantToCategory.put("gaap dividend revenue", INCOME);
                merchantToCategory.put("gaap interest revenue", INCOME);
                merchantToCategory.put("gaap revenue", INCOME);
                merchantToCategory.put("gaap sales revenue", INCOME);
                merchantToCategory.put("gaap service revenue", INCOME);
                merchantToCategory.put("ge", INCOME);
                merchantToCategory.put("general electric", INCOME);
                merchantToCategory.put("general motors", INCOME);
                merchantToCategory.put("gm", INCOME);
                merchantToCategory.put("google llc", INCOME);
                merchantToCategory.put("gusto", INCOME);
                merchantToCategory.put("heartland payroll", INCOME);
                merchantToCategory.put("home depot corporation", INCOME);
                merchantToCategory.put("home depot inc", INCOME);
                merchantToCategory.put("honda motor", INCOME);
                merchantToCategory.put("internal revenue service", INCOME);
                merchantToCategory.put("intuit payroll", INCOME);
                merchantToCategory.put("irs", INCOME);
                merchantToCategory.put("irs 1099", INCOME);
                merchantToCategory.put("irs 1099-b", INCOME);
                merchantToCategory.put("irs 1099-div", INCOME);
                merchantToCategory.put("irs 1099-g", INCOME);
                merchantToCategory.put("irs 1099-int", INCOME);
                merchantToCategory.put("irs 1099-k", INCOME);
                merchantToCategory.put("irs 1099-misc", INCOME);
                merchantToCategory.put("irs 1099-r", INCOME);
                merchantToCategory.put("irs 1099-s", INCOME);
                merchantToCategory.put("irs refund", INCOME);
                merchantToCategory.put("irs schedule c", INCOME);
                merchantToCategory.put("irs schedule e", INCOME);
                merchantToCategory.put("irs schedule f", INCOME);
                merchantToCategory.put("irs w-2", INCOME);
                merchantToCategory.put("irs w2", INCOME);
                merchantToCategory.put("irs wages", INCOME);
                merchantToCategory.put("isolved", INCOME);
                merchantToCategory.put("isolved hcm", INCOME);
                merchantToCategory.put("johnson & johnson", INCOME);
                merchantToCategory.put("jpmorgan chase & co", INCOME);
                merchantToCategory.put("justworks", INCOME);
                merchantToCategory.put("kronos", INCOME);
                merchantToCategory.put("lockheed martin", INCOME);
                merchantToCategory.put("lowes companies", INCOME);
                merchantToCategory.put("lowes inc", INCOME);
                merchantToCategory.put("mcdonalds corporation", INCOME);
                merchantToCategory.put("mckesson", INCOME);
                merchantToCategory.put("mercedes benz", INCOME);
                merchantToCategory.put("meta platforms", INCOME);
                merchantToCategory.put("microsoft corp", INCOME);
                merchantToCategory.put("microsoft corporation", INCOME);
                merchantToCategory.put("military pay", INCOME);
                merchantToCategory.put("military payroll", INCOME);
                merchantToCategory.put("nike", INCOME);
                merchantToCategory.put("nissan motor", INCOME);
                merchantToCategory.put("northrop grumman", INCOME);
                merchantToCategory.put("onpay", INCOME);
                merchantToCategory.put("patriot software", INCOME);
                merchantToCategory.put("paychex", INCOME);
                merchantToCategory.put("paychex flex", INCOME);
                merchantToCategory.put("paychex inc", INCOME);
                merchantToCategory.put("paychex mobile", INCOME);
                merchantToCategory.put("paychex portal", INCOME);
                merchantToCategory.put("paycom", INCOME);
                merchantToCategory.put("paycom software", INCOME);
                merchantToCategory.put("paycor", INCOME);
                merchantToCategory.put("paylocity", INCOME);
                merchantToCategory.put("payroll ach", INCOME);
                merchantToCategory.put("payroll ach credit", INCOME);
                merchantToCategory.put("payroll ach deposit", INCOME);
                merchantToCategory.put("payroll credit", INCOME);
                merchantToCategory.put("payroll deposit", INCOME);
                merchantToCategory.put("payroll direct deposit", INCOME);
                merchantToCategory.put("payroll payment", INCOME);
                merchantToCategory.put("payroll plus", INCOME);
                merchantToCategory.put("payroll plus solutions", INCOME);
                merchantToCategory.put("payroll transfer", INCOME);
                merchantToCategory.put("pepsico", INCOME);
                merchantToCategory.put("pfizer", INCOME);
                merchantToCategory.put("pg", INCOME);
                merchantToCategory.put("ppd", INCOME);
                merchantToCategory.put("ppd credit", INCOME);
                merchantToCategory.put("ppd entry", INCOME);
                merchantToCategory.put("procter & gamble", INCOME);
                merchantToCategory.put("quickbooks payroll", INCOME);
                merchantToCategory.put("raytheon", INCOME);
                merchantToCategory.put("remuneration", INCOME);
                merchantToCategory.put("salary deposit", INCOME);
                merchantToCategory.put("salary direct deposit", INCOME);
                merchantToCategory.put("social security", INCOME);
                merchantToCategory.put("social security administration", INCOME);
                merchantToCategory.put("square payroll", INCOME);
                merchantToCategory.put("ssa", INCOME);
                merchantToCategory.put("starbucks corporation", INCOME);
                merchantToCategory.put("state tax refund", INCOME);
                merchantToCategory.put("state unemployment", INCOME);
                merchantToCategory.put("stellantis", INCOME);
                merchantToCategory.put("stipend", INCOME);
                merchantToCategory.put("sure payroll", INCOME);
                merchantToCategory.put("surepayroll", INCOME);
                merchantToCategory.put("target corporation", INCOME);
                merchantToCategory.put("tax refund", INCOME);
                merchantToCategory.put("tesla inc", INCOME);
                merchantToCategory.put("tesla motors", INCOME);
                merchantToCategory.put("toyota motor", INCOME);
                merchantToCategory.put("treasury department", INCOME);
                merchantToCategory.put("triple net", INCOME);
                merchantToCategory.put("triplenet", INCOME);
                merchantToCategory.put("ui", INCOME);
                merchantToCategory.put("ukg", INCOME);
                merchantToCategory.put("ultimate software", INCOME);
                merchantToCategory.put("unemployment", INCOME);
                merchantToCategory.put("unemployment insurance", INCOME);
                merchantToCategory.put("unitedhealth group", INCOME);
                merchantToCategory.put("us treasury", INCOME);
                merchantToCategory.put("va", INCOME);
                merchantToCategory.put("va benefits", INCOME);
                merchantToCategory.put("verizon communications", INCOME);
                merchantToCategory.put("veterans affairs", INCOME);
                merchantToCategory.put("veterans benefits", INCOME);
                merchantToCategory.put("volkswagen", INCOME);
                merchantToCategory.put("wage", INCOME);
                merchantToCategory.put("wages", INCOME);
                merchantToCategory.put("walmart inc", INCOME);
                merchantToCategory.put("walmart stores", INCOME);
                merchantToCategory.put("walt disney", INCOME);
                merchantToCategory.put("wave payroll", INCOME);
                merchantToCategory.put("workday", INCOME);
                merchantToCategory.put("workday payroll", INCOME);
                merchantToCategory.put("zenefits", INCOME);

                merchantCount += 177;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== INSURANCE (56 merchants) ==========
                merchantToCategory.put("aarp", INSURANCE);
                merchantToCategory.put("aarp insurance", INSURANCE);
                merchantToCategory.put("aflac", INSURANCE);
                merchantToCategory.put("aflac insurance", INSURANCE);
                merchantToCategory.put("allstate", INSURANCE);
                merchantToCategory.put("allstate home", INSURANCE);
                merchantToCategory.put("american family", INSURANCE);
                merchantToCategory.put("american family insurance", INSURANCE);
                merchantToCategory.put("amfam", INSURANCE);
                merchantToCategory.put("auto insurance", INSURANCE);
                merchantToCategory.put("car insurance", INSURANCE);
                merchantToCategory.put("dental insurance", INSURANCE);
                merchantToCategory.put("disability insurance", INSURANCE);
                merchantToCategory.put("erie", INSURANCE);
                merchantToCategory.put("erie insurance", INSURANCE);
                merchantToCategory.put("esurance", INSURANCE);
                merchantToCategory.put("farmers", INSURANCE);
                merchantToCategory.put("farmers home", INSURANCE);
                merchantToCategory.put("farmers insurance", INSURANCE);
                merchantToCategory.put("geico", INSURANCE);
                merchantToCategory.put("hartford", INSURANCE);
                merchantToCategory.put("health insurance", INSURANCE);
                merchantToCategory.put("home insurance", INSURANCE);
                merchantToCategory.put("homeowner insurance", INSURANCE);
                merchantToCategory.put("irs insurance", INSURANCE);
                merchantToCategory.put("legal insurance", INSURANCE);
                merchantToCategory.put("liberty mutual", INSURANCE);
                merchantToCategory.put("life insurance", INSURANCE);
                merchantToCategory.put("mass mutual", INSURANCE);
                merchantToCategory.put("massmutual", INSURANCE);
                merchantToCategory.put("mcc 6300", INSURANCE);
                merchantToCategory.put("mcc6300", INSURANCE);
                merchantToCategory.put("met life", INSURANCE);
                merchantToCategory.put("metlife", INSURANCE);
                merchantToCategory.put("nationwide", INSURANCE);
                merchantToCategory.put("new york life", INSURANCE);
                merchantToCategory.put("northwestern mutual", INSURANCE);
                merchantToCategory.put("pet insurance", INSURANCE);
                merchantToCategory.put("progressive", INSURANCE);
                merchantToCategory.put("prudential", INSURANCE);
                merchantToCategory.put("prudential financial", INSURANCE);
                merchantToCategory.put("renters insurance", INSURANCE);
                merchantToCategory.put("safeco", INSURANCE);
                merchantToCategory.put("safeco insurance", INSURANCE);
                merchantToCategory.put("sic 63", INSURANCE);
                merchantToCategory.put("sic 64", INSURANCE);
                merchantToCategory.put("state farm", INSURANCE);
                merchantToCategory.put("state farm home", INSURANCE);
                merchantToCategory.put("the hartford", INSURANCE);
                merchantToCategory.put("travel insurance", INSURANCE);
                merchantToCategory.put("travelers", INSURANCE);
                merchantToCategory.put("travelers insurance", INSURANCE);
                merchantToCategory.put("usaa", INSURANCE);
                merchantToCategory.put("usaa home", INSURANCE);
                merchantToCategory.put("vehicle insurance", INSURANCE);
                merchantToCategory.put("vision insurance", INSURANCE);

                merchantCount += 56;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== INVESTMENT (187 merchants) ==========
                merchantToCategory.put("401 k", INVESTMENT);
                merchantToCategory.put("401 k contribution", INVESTMENT);
                merchantToCategory.put("401k contribution", INVESTMENT);
                merchantToCategory.put("acorns", INVESTMENT);
                merchantToCategory.put("ally invest", INVESTMENT);
                merchantToCategory.put("asset management", INVESTMENT);
                merchantToCategory.put("asset manager", INVESTMENT);
                merchantToCategory.put("bank of america merrill", INVESTMENT);
                merchantToCategory.put("barclays", INVESTMENT);
                merchantToCategory.put("barclays investment", INVESTMENT);
                merchantToCategory.put("betterment", INVESTMENT);
                merchantToCategory.put("blackrock", INVESTMENT);
                merchantToCategory.put("bond purchase", INVESTMENT);
                merchantToCategory.put("broker", INVESTMENT);
                merchantToCategory.put("brokerage", INVESTMENT);
                merchantToCategory.put("brokerage account", INVESTMENT);
                merchantToCategory.put("capital gain", INVESTMENT);
                merchantToCategory.put("capital gains", INVESTMENT);
                merchantToCategory.put("cd interest", INVESTMENT);
                merchantToCategory.put("cd investment", INVESTMENT);
                merchantToCategory.put("certificate", INVESTMENT);
                merchantToCategory.put("charles schwab", INVESTMENT);
                merchantToCategory.put("contribution", INVESTMENT);
                merchantToCategory.put("credit suisse", INVESTMENT);
                merchantToCategory.put("deutsche bank", INVESTMENT);
                merchantToCategory.put("directional", INVESTMENT);
                merchantToCategory.put("directional funds", INVESTMENT);
                merchantToCategory.put("dividend", INVESTMENT);
                merchantToCategory.put("dividend income", INVESTMENT);
                merchantToCategory.put("dividend payment", INVESTMENT);
                merchantToCategory.put("dividends", INVESTMENT);
                merchantToCategory.put("e-trade", INVESTMENT);
                merchantToCategory.put("edward jones", INVESTMENT);
                merchantToCategory.put("edwardjones", INVESTMENT);
                merchantToCategory.put("employer contribution", INVESTMENT);
                merchantToCategory.put("employer match", INVESTMENT);
                merchantToCategory.put("empower", INVESTMENT);
                merchantToCategory.put("empower retirement", INVESTMENT);
                merchantToCategory.put("equity account", INVESTMENT);
                merchantToCategory.put("equity broker", INVESTMENT);
                merchantToCategory.put("equity dividend", INVESTMENT);
                merchantToCategory.put("equity investment", INVESTMENT);
                merchantToCategory.put("equity portfolio", INVESTMENT);
                merchantToCategory.put("equity purchase", INVESTMENT);
                merchantToCategory.put("equity sale", INVESTMENT);
                merchantToCategory.put("equity trading", INVESTMENT);
                merchantToCategory.put("etfs", INVESTMENT);
                merchantToCategory.put("etrade", INVESTMENT);
                merchantToCategory.put("etrade financial", INVESTMENT);
                merchantToCategory.put("fidelity", INVESTMENT);
                merchantToCategory.put("fidelity investments", INVESTMENT);
                merchantToCategory.put("franklin", INVESTMENT);
                merchantToCategory.put("franklin templeton", INVESTMENT);
                merchantToCategory.put("fund", INVESTMENT);
                merchantToCategory.put("fund purchase", INVESTMENT);
                merchantToCategory.put("funds", INVESTMENT);
                merchantToCategory.put("gaap assets", INVESTMENT);
                merchantToCategory.put("gaap current assets", INVESTMENT);
                merchantToCategory.put("gaap equity", INVESTMENT);
                merchantToCategory.put("gaap fixed assets", INVESTMENT);
                merchantToCategory.put("gaap property plant equipment", INVESTMENT);
                merchantToCategory.put("gain", INVESTMENT);
                merchantToCategory.put("gains", INVESTMENT);
                merchantToCategory.put("health savings account", INVESTMENT);
                merchantToCategory.put("hsa", INVESTMENT);
                merchantToCategory.put("hsa investment", INVESTMENT);
                merchantToCategory.put("i shares", INVESTMENT);
                merchantToCategory.put("ib", INVESTMENT);
                merchantToCategory.put("ibkr", INVESTMENT);
                merchantToCategory.put("index fund", INVESTMENT);
                merchantToCategory.put("index funds", INVESTMENT);
                merchantToCategory.put("interactive brokers", INVESTMENT);
                merchantToCategory.put("invesco", INVESTMENT);
                merchantToCategory.put("invesco qqq", INVESTMENT);
                merchantToCategory.put("invest", INVESTMENT);
                merchantToCategory.put("invested", INVESTMENT);
                merchantToCategory.put("investing", INVESTMENT);
                merchantToCategory.put("investment account", INVESTMENT);
                merchantToCategory.put("investment company", INVESTMENT);
                merchantToCategory.put("investment firm", INVESTMENT);
                merchantToCategory.put("investment income", INVESTMENT);
                merchantToCategory.put("investment purchase", INVESTMENT);
                merchantToCategory.put("investment return", INVESTMENT);
                merchantToCategory.put("investment transfer", INVESTMENT);
                merchantToCategory.put("investor", INVESTMENT);
                merchantToCategory.put("ira", INVESTMENT);
                merchantToCategory.put("ira contribution", INVESTMENT);
                merchantToCategory.put("irs pension and profit-sharing plans", INVESTMENT);
                merchantToCategory.put("ishares", INVESTMENT);
                merchantToCategory.put("jp morgan", INVESTMENT);
                merchantToCategory.put("jpmorgan", INVESTMENT);
                merchantToCategory.put("jpmorgan chase", INVESTMENT);
                merchantToCategory.put("m1", INVESTMENT);
                merchantToCategory.put("m1 finance", INVESTMENT);
                merchantToCategory.put("merrill", INVESTMENT);
                merchantToCategory.put("merrill lynch", INVESTMENT);
                merchantToCategory.put("morgan stanley", INVESTMENT);
                merchantToCategory.put("morganstanley", INVESTMENT);
                merchantToCategory.put("mutual funds", INVESTMENT);
                merchantToCategory.put("naics 52", INVESTMENT);
                merchantToCategory.put("naics 53", INVESTMENT);
                merchantToCategory.put("nasdaq", INVESTMENT);
                merchantToCategory.put("nyse", INVESTMENT);
                merchantToCategory.put("online transfer from morgan stanley", INVESTMENT);
                merchantToCategory.put("online transfer from morganstanley", INVESTMENT);
                merchantToCategory.put("pension", INVESTMENT);
                merchantToCategory.put("pension fund", INVESTMENT);
                merchantToCategory.put("personal capital", INVESTMENT);
                merchantToCategory.put("portfolio", INVESTMENT);
                merchantToCategory.put("portfolio management", INVESTMENT);
                merchantToCategory.put("pro shares", INVESTMENT);
                merchantToCategory.put("profit", INVESTMENT);
                merchantToCategory.put("profits", INVESTMENT);
                merchantToCategory.put("proshares", INVESTMENT);
                merchantToCategory.put("public", INVESTMENT);
                merchantToCategory.put("public.com", INVESTMENT);
                merchantToCategory.put("raymond james", INVESTMENT);
                merchantToCategory.put("raymondjames", INVESTMENT);
                merchantToCategory.put("retirement", INVESTMENT);
                merchantToCategory.put("retirement account", INVESTMENT);
                merchantToCategory.put("retirement contribution", INVESTMENT);
                merchantToCategory.put("retirement fund", INVESTMENT);
                merchantToCategory.put("retirement plan", INVESTMENT);
                merchantToCategory.put("return on investment", INVESTMENT);
                merchantToCategory.put("robin hood", INVESTMENT);
                merchantToCategory.put("robinhood", INVESTMENT);
                merchantToCategory.put("roi", INVESTMENT);
                merchantToCategory.put("roth ira", INVESTMENT);
                merchantToCategory.put("roth ira contribution", INVESTMENT);
                merchantToCategory.put("schwab", INVESTMENT);
                merchantToCategory.put("securities", INVESTMENT);
                merchantToCategory.put("securities purchase", INVESTMENT);
                merchantToCategory.put("security", INVESTMENT);
                merchantToCategory.put("share", INVESTMENT);
                merchantToCategory.put("share account", INVESTMENT);
                merchantToCategory.put("share broker", INVESTMENT);
                merchantToCategory.put("share dividend", INVESTMENT);
                merchantToCategory.put("share investment", INVESTMENT);
                merchantToCategory.put("share portfolio", INVESTMENT);
                merchantToCategory.put("share purchase", INVESTMENT);
                merchantToCategory.put("share sale", INVESTMENT);
                merchantToCategory.put("share trading", INVESTMENT);
                merchantToCategory.put("shares", INVESTMENT);
                merchantToCategory.put("sic 60", INVESTMENT);
                merchantToCategory.put("sic 61", INVESTMENT);
                merchantToCategory.put("sic 62", INVESTMENT);
                merchantToCategory.put("sic 65", INVESTMENT);
                merchantToCategory.put("sic 67", INVESTMENT);
                merchantToCategory.put("sofi invest", INVESTMENT);
                merchantToCategory.put("stash", INVESTMENT);
                merchantToCategory.put("state street", INVESTMENT);
                merchantToCategory.put("state street global", INVESTMENT);
                merchantToCategory.put("stock", INVESTMENT);
                merchantToCategory.put("stock account", INVESTMENT);
                merchantToCategory.put("stock broker", INVESTMENT);
                merchantToCategory.put("stock dividend", INVESTMENT);
                merchantToCategory.put("stock exchange", INVESTMENT);
                merchantToCategory.put("stock investment", INVESTMENT);
                merchantToCategory.put("stock market", INVESTMENT);
                merchantToCategory.put("stock portfolio", INVESTMENT);
                merchantToCategory.put("stock purchase", INVESTMENT);
                merchantToCategory.put("stock sale", INVESTMENT);
                merchantToCategory.put("stock trading", INVESTMENT);
                merchantToCategory.put("t rowe price", INVESTMENT);
                merchantToCategory.put("t. rowe price", INVESTMENT);
                merchantToCategory.put("tastytrade", INVESTMENT);
                merchantToCategory.put("tastyworks", INVESTMENT);
                merchantToCategory.put("td ameritrade", INVESTMENT);
                merchantToCategory.put("trade", INVESTMENT);
                merchantToCategory.put("trader", INVESTMENT);
                merchantToCategory.put("trading", INVESTMENT);
                merchantToCategory.put("trading account", INVESTMENT);
                merchantToCategory.put("transfer from fidelity", INVESTMENT);
                merchantToCategory.put("transfer from morgan stanley", INVESTMENT);
                merchantToCategory.put("transfer from morganstanley", INVESTMENT);
                merchantToCategory.put("transfer from schwab", INVESTMENT);
                merchantToCategory.put("transfer from vanguard", INVESTMENT);
                merchantToCategory.put("treasuries", INVESTMENT);
                merchantToCategory.put("treasury", INVESTMENT);
                merchantToCategory.put("troweprice", INVESTMENT);
                merchantToCategory.put("ubs", INVESTMENT);
                merchantToCategory.put("ubs financial", INVESTMENT);
                merchantToCategory.put("vanguard", INVESTMENT);
                merchantToCategory.put("vanguard group", INVESTMENT);
                merchantToCategory.put("wealthfront", INVESTMENT);
                merchantToCategory.put("webull", INVESTMENT);
                merchantToCategory.put("webull securities", INVESTMENT);

                merchantCount += 187;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== PAYMENT (275 merchants) ==========
                merchantToCategory.put("53", PAYMENT);
                merchantToCategory.put("ach 22", PAYMENT);
                merchantToCategory.put("ach 23", PAYMENT);
                merchantToCategory.put("ach 24", PAYMENT);
                merchantToCategory.put("ach 32", PAYMENT);
                merchantToCategory.put("ach 33", PAYMENT);
                merchantToCategory.put("ach 34", PAYMENT);
                merchantToCategory.put("ach 42", PAYMENT);
                merchantToCategory.put("ach 43", PAYMENT);
                merchantToCategory.put("ach 44", PAYMENT);
                merchantToCategory.put("aidvantage", PAYMENT);
                merchantToCategory.put("amazon credit card", PAYMENT);
                merchantToCategory.put("amazon store card", PAYMENT);
                merchantToCategory.put("amazon store card payment", PAYMENT);
                merchantToCategory.put("amazon store card pmt", PAYMENT);
                merchantToCategory.put("american express", PAYMENT);
                merchantToCategory.put("american express payment", PAYMENT);
                merchantToCategory.put("amex", PAYMENT);
                merchantToCategory.put("amex payment", PAYMENT);
                merchantToCategory.put("amz credit card payment", PAYMENT);
                merchantToCategory.put("amz store card", PAYMENT);
                merchantToCategory.put("amz store card pmt", PAYMENT);
                merchantToCategory.put("amz storecrd", PAYMENT);
                merchantToCategory.put("amz storecrd pmt", PAYMENT);
                merchantToCategory.put("amz storecrd pmt payment", PAYMENT);
                merchantToCategory.put("amzstorecrd", PAYMENT);
                merchantToCategory.put("arc", PAYMENT);
                merchantToCategory.put("arc entry", PAYMENT);
                merchantToCategory.put("auto loan", PAYMENT);
                merchantToCategory.put("auto pay", PAYMENT);
                merchantToCategory.put("auto payment", PAYMENT);
                merchantToCategory.put("auto-pay", PAYMENT);
                merchantToCategory.put("automatic pay", PAYMENT);
                merchantToCategory.put("automatic payment", PAYMENT);
                merchantToCategory.put("autopay", PAYMENT);
                merchantToCategory.put("autopay payment", PAYMENT);
                merchantToCategory.put("aventium", PAYMENT);
                merchantToCategory.put("bacs direct debit", PAYMENT);
                merchantToCategory.put("bank autopay", PAYMENT);
                merchantToCategory.put("bank of america", PAYMENT);
                merchantToCategory.put("bb&t", PAYMENT);
                merchantToCategory.put("bbt", PAYMENT);
                merchantToCategory.put("boa", PAYMENT);
                merchantToCategory.put("boc", PAYMENT);
                merchantToCategory.put("boc entry", PAYMENT);
                merchantToCategory.put("bofa", PAYMENT);
                merchantToCategory.put("capital one", PAYMENT);
                merchantToCategory.put("capitalone", PAYMENT);
                merchantToCategory.put("car loan", PAYMENT);
                merchantToCategory.put("car payment", PAYMENT);
                merchantToCategory.put("card auto pay", PAYMENT);
                merchantToCategory.put("card autopay", PAYMENT);
                merchantToCategory.put("card balance", PAYMENT);
                merchantToCategory.put("card bill", PAYMENT);
                merchantToCategory.put("card e-payment", PAYMENT);
                merchantToCategory.put("card pay", PAYMENT);
                merchantToCategory.put("card payment", PAYMENT);
                merchantToCategory.put("card pmt", PAYMENT);
                merchantToCategory.put("card statement", PAYMENT);
                merchantToCategory.put("chase", PAYMENT);
                merchantToCategory.put("chase autopay", PAYMENT);
                merchantToCategory.put("chase credit", PAYMENT);
                merchantToCategory.put("check", PAYMENT);
                merchantToCategory.put("check #", PAYMENT);
                merchantToCategory.put("check cashing", PAYMENT);
                merchantToCategory.put("check clearing", PAYMENT);
                merchantToCategory.put("check issued", PAYMENT);
                merchantToCategory.put("check no", PAYMENT);
                merchantToCategory.put("check no.", PAYMENT);
                merchantToCategory.put("check number", PAYMENT);
                merchantToCategory.put("check pay", PAYMENT);
                merchantToCategory.put("check payment", PAYMENT);
                merchantToCategory.put("check purchase", PAYMENT);
                merchantToCategory.put("check transaction", PAYMENT);
                merchantToCategory.put("check written", PAYMENT);
                merchantToCategory.put("checks", PAYMENT);
                merchantToCategory.put("cheque", PAYMENT);
                merchantToCategory.put("cheques", PAYMENT);
                merchantToCategory.put("citi", PAYMENT);
                merchantToCategory.put("citi autopay", PAYMENT);
                merchantToCategory.put("citi autopay payment", PAYMENT);
                merchantToCategory.put("citi card", PAYMENT);
                merchantToCategory.put("citi credit", PAYMENT);
                merchantToCategory.put("citibank", PAYMENT);
                merchantToCategory.put("citicard", PAYMENT);
                merchantToCategory.put("citizens", PAYMENT);
                merchantToCategory.put("citizens bank", PAYMENT);
                merchantToCategory.put("ckd", PAYMENT);
                merchantToCategory.put("ckd entry", PAYMENT);
                merchantToCategory.put("comerica", PAYMENT);
                merchantToCategory.put("comerica bank", PAYMENT);
                merchantToCategory.put("credit auto pay", PAYMENT);
                merchantToCategory.put("credit autopay", PAYMENT);
                merchantToCategory.put("credit balance", PAYMENT);
                merchantToCategory.put("credit bill", PAYMENT);
                merchantToCategory.put("credit card auto pay", PAYMENT);
                merchantToCategory.put("credit card autopay", PAYMENT);
                merchantToCategory.put("credit card balance", PAYMENT);
                merchantToCategory.put("credit card bill", PAYMENT);
                merchantToCategory.put("credit card e-payment", PAYMENT);
                merchantToCategory.put("credit card pay", PAYMENT);
                merchantToCategory.put("credit card payment", PAYMENT);
                merchantToCategory.put("credit card pmt", PAYMENT);
                merchantToCategory.put("credit card statement", PAYMENT);
                merchantToCategory.put("credit crd", PAYMENT);
                merchantToCategory.put("credit e-payment", PAYMENT);
                merchantToCategory.put("credit pay", PAYMENT);
                merchantToCategory.put("credit payment", PAYMENT);
                merchantToCategory.put("credit statement", PAYMENT);
                merchantToCategory.put("creditcard", PAYMENT);
                merchantToCategory.put("debt", PAYMENT);
                merchantToCategory.put("debt auto pay", PAYMENT);
                merchantToCategory.put("debt autopay", PAYMENT);
                merchantToCategory.put("debt installment", PAYMENT);
                merchantToCategory.put("debt monthly", PAYMENT);
                merchantToCategory.put("debt pay", PAYMENT);
                merchantToCategory.put("debt payment", PAYMENT);
                merchantToCategory.put("debt provider", PAYMENT);
                merchantToCategory.put("debt repay", PAYMENT);
                merchantToCategory.put("debt repayment", PAYMENT);
                merchantToCategory.put("debt service", PAYMENT);
                merchantToCategory.put("discover", PAYMENT);
                merchantToCategory.put("discover card", PAYMENT);
                merchantToCategory.put("discover payment", PAYMENT);
                merchantToCategory.put("discover personal loans", PAYMENT);
                merchantToCategory.put("e payment", PAYMENT);
                merchantToCategory.put("e-payment", PAYMENT);
                merchantToCategory.put("ed financial", PAYMENT);
                merchantToCategory.put("edfinancial", PAYMENT);
                merchantToCategory.put("education loan", PAYMENT);
                merchantToCategory.put("electronic payment", PAYMENT);
                merchantToCategory.put("fedloan", PAYMENT);
                merchantToCategory.put("fedloan servicing", PAYMENT);
                merchantToCategory.put("fifth third", PAYMENT);
                merchantToCategory.put("fifth third bank", PAYMENT);
                merchantToCategory.put("first national", PAYMENT);
                merchantToCategory.put("first national bank", PAYMENT);
                merchantToCategory.put("gaap accounts payable", PAYMENT);
                merchantToCategory.put("gaap accrued expenses", PAYMENT);
                merchantToCategory.put("gaap administrative expenses", PAYMENT);
                merchantToCategory.put("gaap amortization expense", PAYMENT);
                merchantToCategory.put("gaap current liabilities", PAYMENT);
                merchantToCategory.put("gaap depreciation expense", PAYMENT);
                merchantToCategory.put("gaap expenses", PAYMENT);
                merchantToCategory.put("gaap interest expense", PAYMENT);
                merchantToCategory.put("gaap liabilities", PAYMENT);
                merchantToCategory.put("gaap long-term debt", PAYMENT);
                merchantToCategory.put("gaap operating expenses", PAYMENT);
                merchantToCategory.put("gaap prepaid expenses", PAYMENT);
                merchantToCategory.put("gaap selling expenses", PAYMENT);
                merchantToCategory.put("gaap tax expense", PAYMENT);
                merchantToCategory.put("great lakes", PAYMENT);
                merchantToCategory.put("great lakes educational", PAYMENT);
                merchantToCategory.put("home loan", PAYMENT);
                merchantToCategory.put("home loan payment", PAYMENT);
                merchantToCategory.put("housing loan", PAYMENT);
                merchantToCategory.put("huntington", PAYMENT);
                merchantToCategory.put("huntington bank", PAYMENT);
                merchantToCategory.put("installment", PAYMENT);
                merchantToCategory.put("installment payment", PAYMENT);
                merchantToCategory.put("irs commissions and fees", PAYMENT);
                merchantToCategory.put("irs contract labor", PAYMENT);
                merchantToCategory.put("irs depreciation", PAYMENT);
                merchantToCategory.put("irs employee benefit programs", PAYMENT);
                merchantToCategory.put("irs estate tax", PAYMENT);
                merchantToCategory.put("irs estimated tax", PAYMENT);
                merchantToCategory.put("irs excise tax", PAYMENT);
                merchantToCategory.put("irs federal tax", PAYMENT);
                merchantToCategory.put("irs gift tax", PAYMENT);
                merchantToCategory.put("irs income tax", PAYMENT);
                merchantToCategory.put("irs interest", PAYMENT);
                merchantToCategory.put("irs local tax", PAYMENT);
                merchantToCategory.put("irs other expenses", PAYMENT);
                merchantToCategory.put("irs payroll tax", PAYMENT);
                merchantToCategory.put("irs property tax", PAYMENT);
                merchantToCategory.put("irs sales tax", PAYMENT);
                merchantToCategory.put("irs self-employment tax", PAYMENT);
                merchantToCategory.put("irs state tax", PAYMENT);
                merchantToCategory.put("irs tax payment", PAYMENT);
                merchantToCategory.put("irs taxes and licenses", PAYMENT);
                merchantToCategory.put("key bank", PAYMENT);
                merchantToCategory.put("keybank", PAYMENT);
                merchantToCategory.put("lending club", PAYMENT);
                merchantToCategory.put("lendingclub", PAYMENT);
                merchantToCategory.put("lightstream", PAYMENT);
                merchantToCategory.put("lightstream loans", PAYMENT);
                merchantToCategory.put("loan", PAYMENT);
                merchantToCategory.put("loan auto pay", PAYMENT);
                merchantToCategory.put("loan autopay", PAYMENT);
                merchantToCategory.put("loan installment", PAYMENT);
                merchantToCategory.put("loan monthly", PAYMENT);
                merchantToCategory.put("loan pay", PAYMENT);
                merchantToCategory.put("loan payment", PAYMENT);
                merchantToCategory.put("loan provider", PAYMENT);
                merchantToCategory.put("loan repay", PAYMENT);
                merchantToCategory.put("loan repayment", PAYMENT);
                merchantToCategory.put("loan service", PAYMENT);
                merchantToCategory.put("m&t bank", PAYMENT);
                merchantToCategory.put("mariner finance", PAYMENT);
                merchantToCategory.put("mastercard", PAYMENT);
                merchantToCategory.put("mastercard payment", PAYMENT);
                merchantToCategory.put("mohela", PAYMENT);
                merchantToCategory.put("monthly payment", PAYMENT);
                merchantToCategory.put("mortgage", PAYMENT);
                merchantToCategory.put("mortgage pay", PAYMENT);
                merchantToCategory.put("mortgage repay", PAYMENT);
                merchantToCategory.put("mt bank", PAYMENT);
                merchantToCategory.put("navient", PAYMENT);
                merchantToCategory.put("nelnet", PAYMENT);
                merchantToCategory.put("one main financial", PAYMENT);
                merchantToCategory.put("onemain", PAYMENT);
                merchantToCategory.put("online payment", PAYMENT);
                merchantToCategory.put("paying", PAYMENT);
                merchantToCategory.put("payment gateway", PAYMENT);
                merchantToCategory.put("payment plan", PAYMENT);
                merchantToCategory.put("payment processing", PAYMENT);
                merchantToCategory.put("payment service", PAYMENT);
                merchantToCategory.put("personal loan", PAYMENT);
                merchantToCategory.put("personal loan pay", PAYMENT);
                merchantToCategory.put("personal loan payment", PAYMENT);
                merchantToCategory.put("pnc", PAYMENT);
                merchantToCategory.put("pnc bank", PAYMENT);
                merchantToCategory.put("pop", PAYMENT);
                merchantToCategory.put("pop entry", PAYMENT);
                merchantToCategory.put("pos", PAYMENT);
                merchantToCategory.put("pos entry", PAYMENT);
                merchantToCategory.put("ppd debit", PAYMENT);
                merchantToCategory.put("prosper", PAYMENT);
                merchantToCategory.put("prosper marketplace", PAYMENT);
                merchantToCategory.put("rcp", PAYMENT);
                merchantToCategory.put("rcp entry", PAYMENT);
                merchantToCategory.put("recurring payment", PAYMENT);
                merchantToCategory.put("regions", PAYMENT);
                merchantToCategory.put("regions bank", PAYMENT);
                merchantToCategory.put("scheduled payment", PAYMENT);
                merchantToCategory.put("sepa direct debit", PAYMENT);
                merchantToCategory.put("sofi", PAYMENT);
                merchantToCategory.put("sofi loans", PAYMENT);
                merchantToCategory.put("springleaf", PAYMENT);
                merchantToCategory.put("storecrd payment", PAYMENT);
                merchantToCategory.put("storecrd pmt", PAYMENT);
                merchantToCategory.put("student loan", PAYMENT);
                merchantToCategory.put("student loan payment", PAYMENT);
                merchantToCategory.put("suntrust", PAYMENT);
                merchantToCategory.put("synchrony", PAYMENT);
                merchantToCategory.put("synchrony bank", PAYMENT);
                merchantToCategory.put("td bank", PAYMENT);
                merchantToCategory.put("tdbank", PAYMENT);
                merchantToCategory.put("tel", PAYMENT);
                merchantToCategory.put("tel credit", PAYMENT);
                merchantToCategory.put("tel debit", PAYMENT);
                merchantToCategory.put("tel entry", PAYMENT);
                merchantToCategory.put("trc", PAYMENT);
                merchantToCategory.put("trc entry", PAYMENT);
                merchantToCategory.put("truist", PAYMENT);
                merchantToCategory.put("trx", PAYMENT);
                merchantToCategory.put("trx entry", PAYMENT);
                merchantToCategory.put("upstart", PAYMENT);
                merchantToCategory.put("us bank", PAYMENT);
                merchantToCategory.put("usbank", PAYMENT);
                merchantToCategory.put("vehicle loan", PAYMENT);
                // "visa" alone is too ambiguous — it's a card network AND
                // a travel document. "visa application" / "visa service"
                // is a travel expense; removed the bare keyword to stop
                // misclassifying immigration/travel fees as payments.
                // `visa payment` (specific phrase) remains below.
                merchantToCategory.put("visa payment", PAYMENT);
                // Bare "web"/"web credit"/"web debit" match any online
                // transaction description (hundreds of merchants mention
                // "web"). Removed to stop mass mis-categorisation; kept
                // the specific "web bill pay" below if present.
                merchantToCategory.put("web bill pay", PAYMENT);
                merchantToCategory.put("web entry", PAYMENT);
                merchantToCategory.put("wells fargo", PAYMENT);
                merchantToCategory.put("wf", PAYMENT);
                merchantToCategory.put("wf credit", PAYMENT);
                merchantToCategory.put("written check", PAYMENT);
                merchantToCategory.put("xck", PAYMENT);
                merchantToCategory.put("xck entry", PAYMENT);
                merchantToCategory.put("zions", PAYMENT);
                merchantToCategory.put("zions bank", PAYMENT);

                merchantCount += 275;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== PET (59 merchants) ==========
                merchantToCategory.put("1800petmeds", "pet");
                merchantToCategory.put("animal clinic", "pet");
                merchantToCategory.put("animal hospital", "pet");
                merchantToCategory.put("animalclinic", "pet");
                merchantToCategory.put("animalhospital", "pet");
                merchantToCategory.put("cat food", "pet");
                merchantToCategory.put("chewy", "pet");
                merchantToCategory.put("chewy.com", "pet");
                merchantToCategory.put("dog food", "pet");
                merchantToCategory.put("embrace", "pet");
                merchantToCategory.put("embrace pet insurance", "pet");
                merchantToCategory.put("fetch bones", "pet");
                merchantToCategory.put("fetchbones", "pet");
                merchantToCategory.put("fido", "pet");
                merchantToCategory.put("figo", "pet");
                merchantToCategory.put("figo pet insurance", "pet");
                merchantToCategory.put("healthy paws", "pet");
                merchantToCategory.put("mcc 5995", "pet");
                merchantToCategory.put("mcc5995", "pet");
                merchantToCategory.put("mud bay", "pet");
                merchantToCategory.put("mudbay", "pet");
                merchantToCategory.put("nationwide pet", "pet");
                merchantToCategory.put("pet care", "pet");
                merchantToCategory.put("pet care clinic", "pet");
                merchantToCategory.put("pet clinic", "pet");
                merchantToCategory.put("pet food", "pet");
                merchantToCategory.put("pet hospital", "pet");
                merchantToCategory.put("pet pharmacy", "pet");
                merchantToCategory.put("pet plan", "pet");
                merchantToCategory.put("pet store", "pet");
                merchantToCategory.put("pet supermarket", "pet");
                merchantToCategory.put("pet supplies", "pet");
                merchantToCategory.put("pet supplies plus", "pet");
                merchantToCategory.put("pet supply", "pet");
                merchantToCategory.put("petcare", "pet");
                merchantToCategory.put("petcare clinic", "pet");
                merchantToCategory.put("petcareclinic", "pet");
                merchantToCategory.put("petclinic", "pet");
                merchantToCategory.put("petco", "pet");
                merchantToCategory.put("petfood", "pet");
                merchantToCategory.put("pethospital", "pet");
                merchantToCategory.put("petland", "pet");
                merchantToCategory.put("petland discounts", "pet");
                merchantToCategory.put("petmeds", "pet");
                merchantToCategory.put("petpharmacy", "pet");
                merchantToCategory.put("petplan", "pet");
                merchantToCategory.put("pets", "pet");
                merchantToCategory.put("pets best", "pet");
                merchantToCategory.put("petsbest", "pet");
                merchantToCategory.put("petsmart", "pet");
                merchantToCategory.put("petstore", "pet");
                merchantToCategory.put("petsupplies", "pet");
                merchantToCategory.put("sea king aquarium", "pet");
                merchantToCategory.put("sp farmers", "pet");
                merchantToCategory.put("trupanion", "pet");
                merchantToCategory.put("vet", "pet");
                merchantToCategory.put("veterinarian", "pet");
                merchantToCategory.put("veterinary", "pet");
                merchantToCategory.put("veterinary clinic", "pet");

                merchantCount += 59;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== RENT (1 merchants) ==========
                merchantToCategory.put("irs rent or lease", "rent");

                merchantCount += 1;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== SERVICE (27 merchants) ==========
                merchantToCategory.put("angie's list", SERVICE);
                merchantToCategory.put("angies list", SERVICE);
                merchantToCategory.put("care.com", SERVICE);
                merchantToCategory.put("carecom", SERVICE);
                merchantToCategory.put("handy", SERVICE);
                merchantToCategory.put("home advisor", SERVICE);
                merchantToCategory.put("homeadvisor", SERVICE);
                merchantToCategory.put("irs legal and professional services", SERVICE);
                merchantToCategory.put("naics 54", SERVICE);
                merchantToCategory.put("naics 55", SERVICE);
                merchantToCategory.put("naics 56", SERVICE);
                merchantToCategory.put("naics 61", SERVICE);
                merchantToCategory.put("naics 81", SERVICE);
                merchantToCategory.put("sic 70", SERVICE);
                merchantToCategory.put("sic 73", SERVICE);
                merchantToCategory.put("sic 76", SERVICE);
                merchantToCategory.put("sic 81", SERVICE);
                merchantToCategory.put("sic 82", SERVICE);
                merchantToCategory.put("sic 83", SERVICE);
                merchantToCategory.put("sic 84", SERVICE);
                merchantToCategory.put("sic 86", SERVICE);
                merchantToCategory.put("sic 87", SERVICE);
                merchantToCategory.put("sic 88", SERVICE);
                merchantToCategory.put("sic 89", SERVICE);
                merchantToCategory.put("task rabbit", SERVICE);
                merchantToCategory.put("taskrabbit", SERVICE);
                merchantToCategory.put("thumbtack", SERVICE);

                merchantCount += 27;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== SHOPPING (159 merchants) ==========
                merchantToCategory.put("academy sports", SHOPPING);
                merchantToCategory.put("ae", SHOPPING);
                merchantToCategory.put("amazon", SHOPPING);
                merchantToCategory.put("amazon.com", SHOPPING);
                merchantToCategory.put("american eagle", SHOPPING);
                merchantToCategory.put("apparel", SHOPPING);
                merchantToCategory.put("attire", SHOPPING);
                merchantToCategory.put("banana republic", SHOPPING);
                merchantToCategory.put("barnes & noble", SHOPPING);
                merchantToCategory.put("barnes and noble", SHOPPING);
                merchantToCategory.put("bass pro shops", SHOPPING);
                merchantToCategory.put("bbb", SHOPPING);
                merchantToCategory.put("bed bath & beyond", SHOPPING);
                merchantToCategory.put("bed bath and beyond", SHOPPING);
                merchantToCategory.put("best buy", SHOPPING);
                merchantToCategory.put("bestbuy", SHOPPING);
                merchantToCategory.put("books a million", SHOPPING);
                merchantToCategory.put("books-a-million", SHOPPING);
                merchantToCategory.put("boutique", SHOPPING);
                merchantToCategory.put("burlington", SHOPPING);
                merchantToCategory.put("burlington coat factory", SHOPPING);
                merchantToCategory.put("buy", SHOPPING);
                merchantToCategory.put("buying", SHOPPING);
                merchantToCategory.put("cabela's", SHOPPING);
                merchantToCategory.put("cabelas", SHOPPING);
                merchantToCategory.put("charles Tyrwhitt", SHOPPING);
                merchantToCategory.put("clothing", SHOPPING);
                merchantToCategory.put("convenience store", SHOPPING);
                merchantToCategory.put("cost plus world market", SHOPPING);
                merchantToCategory.put("department store", SHOPPING);
                merchantToCategory.put("designer shoe warehouse", SHOPPING);
                merchantToCategory.put("dick's sporting goods", SHOPPING);
                merchantToCategory.put("dicks", SHOPPING);
                merchantToCategory.put("dicks sporting goods", SHOPPING);
                merchantToCategory.put("dsw", SHOPPING);
                merchantToCategory.put("ebay", SHOPPING);
                merchantToCategory.put("electronics store", SHOPPING);
                merchantToCategory.put("etsy", SHOPPING);
                merchantToCategory.put("fashion", SHOPPING);
                merchantToCategory.put("finish line", SHOPPING);
                merchantToCategory.put("foot locker", SHOPPING);
                merchantToCategory.put("forever 21", SHOPPING);
                merchantToCategory.put("forever21", SHOPPING);
                merchantToCategory.put("furniture store", SHOPPING);
                merchantToCategory.put("gaap cost of goods sold", SHOPPING);
                merchantToCategory.put("gaap inventory", SHOPPING);
                merchantToCategory.put("game stop", SHOPPING);
                merchantToCategory.put("gamestop", SHOPPING);
                merchantToCategory.put("gap", SHOPPING);
                merchantToCategory.put("garment", SHOPPING);
                merchantToCategory.put("h&m", SHOPPING);
                merchantToCategory.put("hardware store", SHOPPING);
                merchantToCategory.put("hm", SHOPPING);
                merchantToCategory.put("hobby lobby", SHOPPING);
                merchantToCategory.put("home improvement store", SHOPPING);
                merchantToCategory.put("in store", SHOPPING);
                merchantToCategory.put("in-store", SHOPPING);
                merchantToCategory.put("irs advertising", SHOPPING);
                merchantToCategory.put("irs office expenses", SHOPPING);
                merchantToCategory.put("irs supplies", SHOPPING);
                merchantToCategory.put("j.c. penney", SHOPPING);
                merchantToCategory.put("jc penney", SHOPPING);
                merchantToCategory.put("jcpenney", SHOPPING);
                merchantToCategory.put("jo-ann", SHOPPING);
                merchantToCategory.put("joann", SHOPPING);
                merchantToCategory.put("joann fabrics", SHOPPING);
                merchantToCategory.put("kohl's", SHOPPING);
                merchantToCategory.put("kohls", SHOPPING);
                merchantToCategory.put("macy's", SHOPPING);
                merchantToCategory.put("macys", SHOPPING);
                merchantToCategory.put("mall", SHOPPING);
                merchantToCategory.put("marshalls", SHOPPING);
                merchantToCategory.put("mcc 5311", SHOPPING);
                merchantToCategory.put("mcc 5999", SHOPPING);
                merchantToCategory.put("mcc5311", SHOPPING);
                merchantToCategory.put("mcc5999", SHOPPING);
                merchantToCategory.put("men's apparel", SHOPPING);
                merchantToCategory.put("men's clothing", SHOPPING);
                merchantToCategory.put("mens apparel", SHOPPING);
                merchantToCategory.put("mens clothing", SHOPPING);
                merchantToCategory.put("merchandise", SHOPPING);
                merchantToCategory.put("michael's", SHOPPING);
                merchantToCategory.put("michaels", SHOPPING);
                // Mini Mountain is a ski shop; usage is ski rental/lessons, which we bucket as
                // "health"
                // (active fitness), matching "ski" / "ski resort" elsewhere in this file.
                merchantToCategory.put("naics 31", SHOPPING);
                merchantToCategory.put("naics 32", SHOPPING);
                merchantToCategory.put("naics 33", SHOPPING);
                merchantToCategory.put("naics 34", SHOPPING);
                merchantToCategory.put("naics 42", SHOPPING);
                merchantToCategory.put("naics 44", SHOPPING);
                merchantToCategory.put("naics 45", SHOPPING);
                merchantToCategory.put("nordstrom", SHOPPING);
                merchantToCategory.put("nordstrom rack", SHOPPING);
                merchantToCategory.put("old navy", SHOPPING);
                merchantToCategory.put("outdoor gear", SHOPPING);
                merchantToCategory.put("outdoorgear", SHOPPING);
                merchantToCategory.put("outlet", SHOPPING);
                merchantToCategory.put("overstock", SHOPPING);
                merchantToCategory.put("pier 1", SHOPPING);
                merchantToCategory.put("pier 1 imports", SHOPPING);
                merchantToCategory.put("retail outlet", SHOPPING);
                merchantToCategory.put("retail purchase", SHOPPING);
                merchantToCategory.put("retail shop", SHOPPING);
                merchantToCategory.put("retail store", SHOPPING);
                merchantToCategory.put("ross", SHOPPING);
                merchantToCategory.put("ross dress for less", SHOPPING);
                merchantToCategory.put("sears", SHOPPING);
                // Cosmetics / skincare brands — previously only Sephora /
                // Ulta were mapped; direct-from-brand charges slipped
                // through and hit the "amex"/"payment" guard rail.
                merchantToCategory.put("estee lauder", SHOPPING);
                merchantToCategory.put("esteelauder", SHOPPING);
                merchantToCategory.put("clinique", SHOPPING);
                merchantToCategory.put("lancome", SHOPPING);
                merchantToCategory.put("mac cosmetics", SHOPPING);
                merchantToCategory.put("bobbi brown", SHOPPING);
                merchantToCategory.put("sephora", SHOPPING);
                merchantToCategory.put("shop", SHOPPING);
                merchantToCategory.put("shopping center", SHOPPING);
                merchantToCategory.put("shopping mall", SHOPPING);
                merchantToCategory.put("shopping trip", SHOPPING);
                merchantToCategory.put("shops", SHOPPING);
                merchantToCategory.put("sic 22", SHOPPING);
                merchantToCategory.put("sic 23", SHOPPING);
                merchantToCategory.put("sic 26", SHOPPING);
                merchantToCategory.put("sic 27", SHOPPING);
                merchantToCategory.put("sic 30", SHOPPING);
                merchantToCategory.put("sic 31", SHOPPING);
                merchantToCategory.put("sic 32", SHOPPING);
                merchantToCategory.put("sic 33", SHOPPING);
                merchantToCategory.put("sic 34", SHOPPING);
                merchantToCategory.put("sic 39", SHOPPING);
                merchantToCategory.put("sic 50", SHOPPING);
                merchantToCategory.put("sic 51", SHOPPING);
                merchantToCategory.put("sic 52", SHOPPING);
                merchantToCategory.put("sic 53", SHOPPING);
                merchantToCategory.put("sic 55", SHOPPING);
                merchantToCategory.put("sic 56", SHOPPING);
                merchantToCategory.put("sic 57", SHOPPING);
                merchantToCategory.put("sic 59", SHOPPING);
                merchantToCategory.put("ski equipment", SHOPPING);
                merchantToCategory.put("ski gear", SHOPPING);
                merchantToCategory.put("skiequipment", SHOPPING);
                merchantToCategory.put("skigear", SHOPPING);
                merchantToCategory.put("sports equipment", SHOPPING);
                merchantToCategory.put("sports store", SHOPPING);
                merchantToCategory.put("sportsequipment", SHOPPING);
                merchantToCategory.put("store payment", SHOPPING);
                merchantToCategory.put("store purchase", SHOPPING);
                merchantToCategory.put("store shopping", SHOPPING);
                merchantToCategory.put("store transaction", SHOPPING);
                merchantToCategory.put("store visit", SHOPPING);
                merchantToCategory.put("stores", SHOPPING);
                merchantToCategory.put("tj maxx", SHOPPING);
                merchantToCategory.put("tjmaxx", SHOPPING);
                merchantToCategory.put("toy store", SHOPPING);
                merchantToCategory.put("ulta", SHOPPING);
                merchantToCategory.put("ulta beauty", SHOPPING);
                merchantToCategory.put("wardrobe", SHOPPING);
                merchantToCategory.put("wayfair", SHOPPING);
                merchantToCategory.put("women's apparel", SHOPPING);
                merchantToCategory.put("women's clothing", SHOPPING);
                merchantToCategory.put("womens apparel", SHOPPING);
                merchantToCategory.put("womens clothing", SHOPPING);
                merchantToCategory.put("world market", SHOPPING);
                merchantToCategory.put("zappos", SHOPPING);
                merchantToCategory.put("zara", SHOPPING);

                merchantCount += 159;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== TECH (132 merchants) ==========
                merchantToCategory.put("acer", "tech");
                merchantToCategory.put("adobe", "tech");
                merchantToCategory.put("adobe creative", "tech");
                merchantToCategory.put("adobe systems", "tech");
                merchantToCategory.put("advanced micro devices", "tech");
                merchantToCategory.put("ai powered", "tech");
                merchantToCategory.put("akamai", "tech");
                merchantToCategory.put("alphabet", "tech");
                merchantToCategory.put("amazon web services", "tech");
                merchantToCategory.put("amd", "tech");
                merchantToCategory.put("annual subscription", "tech");
                merchantToCategory.put("anthropic", "tech");
                merchantToCategory.put("anthropic ai", "tech");
                merchantToCategory.put("api", "tech");
                merchantToCategory.put("apple", "tech");
                merchantToCategory.put("asus", "tech");
                merchantToCategory.put("audible", "tech");
                merchantToCategory.put("aws", "tech");
                merchantToCategory.put("broadcom", "tech");
                merchantToCategory.put("canva", "tech");
                merchantToCategory.put("canva pro", "tech");
                merchantToCategory.put("chat gpt", "tech");
                merchantToCategory.put("chatgpt", "tech");
                merchantToCategory.put("cisco", "tech");
                merchantToCategory.put("cisco systems", "tech");
                merchantToCategory.put("claude", "tech");
                merchantToCategory.put("cloudflare", "tech");
                merchantToCategory.put("cohere", "tech");
                merchantToCategory.put("copilot", "tech");
                merchantToCategory.put("cursor", "tech");
                merchantToCategory.put("cursor ai", "tech");
                merchantToCategory.put("cursor ai powered ide", "tech");
                merchantToCategory.put("cursor, ai", "tech");
                merchantToCategory.put("databricks", "tech");
                merchantToCategory.put("datadog", "tech");
                merchantToCategory.put("dell", "tech");
                merchantToCategory.put("developer", "tech");
                merchantToCategory.put("discord", "tech");
                merchantToCategory.put("dropbox", "tech");
                merchantToCategory.put("elastic", "tech");
                merchantToCategory.put("elasticsearch", "tech");
                merchantToCategory.put("expressvpn", "tech");
                merchantToCategory.put("facebook", "tech");
                merchantToCategory.put("fastly", "tech");
                merchantToCategory.put("github", "tech");
                merchantToCategory.put("github pro", "tech");
                merchantToCategory.put("google", "tech");
                merchantToCategory.put("google drive", "tech");
                merchantToCategory.put("google one", "tech");
                merchantToCategory.put("grammarly", "tech");
                merchantToCategory.put("hewlett packard", "tech");
                merchantToCategory.put("hp", "tech");
                merchantToCategory.put("hugging face", "tech");
                merchantToCategory.put("huggingface", "tech");
                merchantToCategory.put("ibm", "tech");
                merchantToCategory.put("icloud", "tech");
                merchantToCategory.put("ide", "tech");
                merchantToCategory.put("instagram", "tech");
                merchantToCategory.put("integrated development", "tech");
                merchantToCategory.put("intel", "tech");
                merchantToCategory.put("intel corporation", "tech");
                merchantToCategory.put("international business machines", "tech");
                merchantToCategory.put("kindle unlimited", "tech");
                merchantToCategory.put("lenovo", "tech");
                merchantToCategory.put("lg", "tech");
                merchantToCategory.put("linear", "tech");
                merchantToCategory.put("linkedin", "tech");
                merchantToCategory.put("linkedin premium", "tech");
                merchantToCategory.put("meta", "tech");
                merchantToCategory.put("microsoft", "tech");
                merchantToCategory.put("microsoft 365", "tech");
                merchantToCategory.put("midjourney", "tech");
                merchantToCategory.put("mongodb", "tech");
                merchantToCategory.put("monthly subscription", "tech");
                merchantToCategory.put("msi", "tech");
                merchantToCategory.put("naics 35", "tech");
                merchantToCategory.put("naics 51", "tech");
                merchantToCategory.put("nordvpn", "tech");
                merchantToCategory.put("notion", "tech");
                merchantToCategory.put("nvidia", "tech");
                merchantToCategory.put("nvidia corporation", "tech");
                merchantToCategory.put("obsidian", "tech");
                merchantToCategory.put("office 365", "tech");
                merchantToCategory.put("open ai", "tech");
                merchantToCategory.put("openai", "tech");
                merchantToCategory.put("oracle", "tech");
                merchantToCategory.put("panasonic", "tech");
                // PayPal is primarily a peer-to-peer / merchant payment
                // rail, not a tech purchase. Mapping it to "tech" treated
                // every PayPal transfer as a tech expense. Changed to
                // "transfer" so the default reflects PayPal's actual
                // function; merchant overrides (user category corrections)
                // still win for specific uses.
                merchantToCategory.put("paypal", TRANSFER);
                merchantToCategory.put("venmo", TRANSFER);
                merchantToCategory.put("zelle", TRANSFER);
                merchantToCategory.put("cashapp", TRANSFER);
                merchantToCategory.put("cash app", TRANSFER);
                merchantToCategory.put("perplexity", "tech");
                merchantToCategory.put("premium", "tech");
                merchantToCategory.put("premium membership", "tech");
                merchantToCategory.put("qualcomm", "tech");
                merchantToCategory.put("recurring", "tech");
                merchantToCategory.put("reddit", "tech");
                merchantToCategory.put("redis", "tech");
                merchantToCategory.put("replicate", "tech");
                merchantToCategory.put("saas", "tech");
                merchantToCategory.put("salesforce", "tech");
                merchantToCategory.put("samsung", "tech");
                merchantToCategory.put("scribd", "tech");
                merchantToCategory.put("shopify", "tech");
                merchantToCategory.put("sic 35", "tech");
                merchantToCategory.put("sic 36", "tech");
                merchantToCategory.put("sic 38", "tech");
                merchantToCategory.put("slack", "tech");
                merchantToCategory.put("snap", "tech");
                merchantToCategory.put("snapchat", "tech");
                merchantToCategory.put("snowflake", "tech");
                merchantToCategory.put("software", "tech");
                merchantToCategory.put("sony", "tech");
                merchantToCategory.put("splunk", "tech");
                merchantToCategory.put("square", "tech");
                merchantToCategory.put("stability", "tech");
                merchantToCategory.put("stability ai", "tech");
                merchantToCategory.put("stripe", "tech");
                merchantToCategory.put("subs", "tech");
                merchantToCategory.put("subscr", "tech");
                merchantToCategory.put("subscription", "tech");
                merchantToCategory.put("subscriptions", "tech");
                merchantToCategory.put("surfshark", "tech");
                merchantToCategory.put("tiktok", "tech");
                merchantToCategory.put("together", "tech");
                merchantToCategory.put("together ai", "tech");
                merchantToCategory.put("toshiba", "tech");
                merchantToCategory.put("twilio", "tech");
                merchantToCategory.put("twitter", "tech");
                merchantToCategory.put("vercel", "tech");
                merchantToCategory.put("vercel inc", "tech");
                merchantToCategory.put("wikipedia", "tech");
                merchantToCategory.put("x", "tech");
                merchantToCategory.put("yearly subscription", "tech");
                merchantToCategory.put("zoom", "tech");

                merchantCount += 132;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== TRANSFER (279 merchants) ==========
                merchantToCategory.put("aba number", TRANSFER);
                merchantToCategory.put("aba routing number", TRANSFER);
                merchantToCategory.put("akoya", TRANSFER);
                // Remittance / international money transfer services.
                merchantToCategory.put("remitly", TRANSFER);
                merchantToCategory.put("western union", TRANSFER);
                merchantToCategory.put("westernunion", TRANSFER);
                merchantToCategory.put("wise transfer", TRANSFER);
                merchantToCategory.put("wise.com", TRANSFER);
                merchantToCategory.put("moneygram", TRANSFER);
                merchantToCategory.put("xoom", TRANSFER);
                merchantToCategory.put("ria money transfer", TRANSFER);
                merchantToCategory.put("alliant", TRANSFER);
                merchantToCategory.put("alliant credit union", TRANSFER);
                merchantToCategory.put("alloy", TRANSFER);
                merchantToCategory.put("alloy labs", TRANSFER);
                merchantToCategory.put("ally", TRANSFER);
                merchantToCategory.put("ally bank", TRANSFER);
                merchantToCategory.put("ansi x9", TRANSFER);
                merchantToCategory.put("ansi x9.100", TRANSFER);
                merchantToCategory.put("ansi x9.100-140", TRANSFER);
                merchantToCategory.put("ansi x9.100-187", TRANSFER);
                merchantToCategory.put("ansi x9.13", TRANSFER);
                merchantToCategory.put("ansi x9.37", TRANSFER);
                merchantToCategory.put("ansix9", TRANSFER);
                merchantToCategory.put("bacs", TRANSFER);
                merchantToCategory.put("bacs transfer", TRANSFER);
                merchantToCategory.put("bai 100", TRANSFER);
                merchantToCategory.put("bai 101", TRANSFER);
                merchantToCategory.put("bai 102", TRANSFER);
                merchantToCategory.put("bai 103", TRANSFER);
                merchantToCategory.put("bai 104", TRANSFER);
                merchantToCategory.put("bai 105", TRANSFER);
                merchantToCategory.put("bai 106", TRANSFER);
                merchantToCategory.put("bai 107", TRANSFER);
                merchantToCategory.put("bai 108", TRANSFER);
                merchantToCategory.put("bai 109", TRANSFER);
                merchantToCategory.put("bai 110", TRANSFER);
                merchantToCategory.put("bai 111", TRANSFER);
                merchantToCategory.put("bai 112", TRANSFER);
                merchantToCategory.put("bai 115", TRANSFER);
                merchantToCategory.put("bai 116", TRANSFER);
                merchantToCategory.put("bai 118", TRANSFER);
                merchantToCategory.put("bai 121", TRANSFER);
                merchantToCategory.put("bai 122", TRANSFER);
                merchantToCategory.put("bai 123", TRANSFER);
                merchantToCategory.put("bai 124", TRANSFER);
                merchantToCategory.put("bai 125", TRANSFER);
                merchantToCategory.put("bai 126", TRANSFER);
                merchantToCategory.put("bai 127", TRANSFER);
                merchantToCategory.put("bai 128", TRANSFER);
                merchantToCategory.put("bai 129", TRANSFER);
                merchantToCategory.put("bai 130", TRANSFER);
                merchantToCategory.put("bai 131", TRANSFER);
                merchantToCategory.put("bai 135", TRANSFER);
                merchantToCategory.put("bai 136", TRANSFER);
                merchantToCategory.put("bai 140", TRANSFER);
                merchantToCategory.put("bai 141", TRANSFER);
                merchantToCategory.put("bai 142", TRANSFER);
                merchantToCategory.put("bai 143", TRANSFER);
                merchantToCategory.put("bai 150", TRANSFER);
                merchantToCategory.put("bai 151", TRANSFER);
                merchantToCategory.put("bai 155", TRANSFER);
                merchantToCategory.put("bai 156", TRANSFER);
                merchantToCategory.put("bai 160", TRANSFER);
                merchantToCategory.put("bai 161", TRANSFER);
                merchantToCategory.put("bai 162", TRANSFER);
                merchantToCategory.put("bai 163", TRANSFER);
                merchantToCategory.put("bai 164", TRANSFER);
                merchantToCategory.put("bai 165", TRANSFER);
                merchantToCategory.put("bai 170", TRANSFER);
                merchantToCategory.put("bai 171", TRANSFER);
                merchantToCategory.put("bai 172", TRANSFER);
                merchantToCategory.put("bai 180", TRANSFER);
                merchantToCategory.put("bai 181", TRANSFER);
                merchantToCategory.put("bai 182", TRANSFER);
                merchantToCategory.put("bai 183", TRANSFER);
                merchantToCategory.put("bai 190", TRANSFER);
                merchantToCategory.put("bai 191", TRANSFER);
                merchantToCategory.put("bai 192", TRANSFER);
                merchantToCategory.put("bai 193", TRANSFER);
                merchantToCategory.put("bai 194", TRANSFER);
                merchantToCategory.put("bai 195", TRANSFER);
                merchantToCategory.put("bai 2", TRANSFER);
                merchantToCategory.put("bai 200", TRANSFER);
                merchantToCategory.put("bai 201", TRANSFER);
                merchantToCategory.put("bai 202", TRANSFER);
                merchantToCategory.put("bai 203", TRANSFER);
                merchantToCategory.put("bai 210", TRANSFER);
                merchantToCategory.put("bai 211", TRANSFER);
                merchantToCategory.put("bai 212", TRANSFER);
                merchantToCategory.put("bai 220", TRANSFER);
                merchantToCategory.put("bai 221", TRANSFER);
                merchantToCategory.put("bai 222", TRANSFER);
                merchantToCategory.put("bai code", TRANSFER);
                merchantToCategory.put("bai format", TRANSFER);
                merchantToCategory.put("bai2", TRANSFER);
                merchantToCategory.put("bank identifier code", TRANSFER);
                merchantToCategory.put("bank secrecy act", TRANSFER);
                merchantToCategory.put("bic", TRANSFER);
                merchantToCategory.put("bic code", TRANSFER);
                merchantToCategory.put("bsa", TRANSFER);
                merchantToCategory.put("camt.052", TRANSFER);
                merchantToCategory.put("camt.053", TRANSFER);
                merchantToCategory.put("camt.054", TRANSFER);
                merchantToCategory.put("camt.056", TRANSFER);
                merchantToCategory.put("camt.057", TRANSFER);
                merchantToCategory.put("ccd", TRANSFER);
                merchantToCategory.put("ccd credit", TRANSFER);
                merchantToCategory.put("ccd debit", TRANSFER);
                merchantToCategory.put("ccd entry", TRANSFER);
                merchantToCategory.put("cfonb", TRANSFER);
                merchantToCategory.put("cfonb 120", TRANSFER);
                merchantToCategory.put("cfonb 240", TRANSFER);
                merchantToCategory.put("chaps", TRANSFER);
                merchantToCategory.put("chaps transfer", TRANSFER);
                merchantToCategory.put("charles schwab bank", TRANSFER);
                merchantToCategory.put("chase bank", TRANSFER);
                merchantToCategory.put("chips", TRANSFER);
                merchantToCategory.put("chips credit", TRANSFER);
                merchantToCategory.put("chips debit", TRANSFER);
                merchantToCategory.put("chips transfer", TRANSFER);
                merchantToCategory.put("clearing house interbank payments", TRANSFER);
                merchantToCategory.put("credit karma", TRANSFER);
                merchantToCategory.put("ctr", TRANSFER);
                merchantToCategory.put("ctx", TRANSFER);
                merchantToCategory.put("ctx credit", TRANSFER);
                merchantToCategory.put("ctx debit", TRANSFER);
                merchantToCategory.put("ctx entry", TRANSFER);
                merchantToCategory.put("currency transaction report", TRANSFER);
                merchantToCategory.put("discover bank", TRANSFER);
                merchantToCategory.put("eft", TRANSFER);
                merchantToCategory.put("eft canada", TRANSFER);
                merchantToCategory.put("electronic funds transfer", TRANSFER);
                merchantToCategory.put("envestnet", TRANSFER);
                merchantToCategory.put("envestnet yodlee", TRANSFER);
                merchantToCategory.put("external transfer", TRANSFER);
                merchantToCategory.put("faster payments", TRANSFER);
                merchantToCategory.put("faster payments service", TRANSFER);
                merchantToCategory.put("fed wire", TRANSFER);
                merchantToCategory.put("federal reserve wire", TRANSFER);
                merchantToCategory.put("fedwire", TRANSFER);
                merchantToCategory.put("fedwire credit", TRANSFER);
                merchantToCategory.put("fedwire debit", TRANSFER);
                merchantToCategory.put("fedwire funds transfer", TRANSFER);
                merchantToCategory.put("fincen", TRANSFER);
                merchantToCategory.put("fincen 01", TRANSFER);
                merchantToCategory.put("fincen 02", TRANSFER);
                merchantToCategory.put("fincen 03", TRANSFER);
                merchantToCategory.put("fincen 04", TRANSFER);
                merchantToCategory.put("fincen 05", TRANSFER);
                merchantToCategory.put("fincen 06", TRANSFER);
                merchantToCategory.put("fincen 07", TRANSFER);
                merchantToCategory.put("fincen 08", TRANSFER);
                merchantToCategory.put("fincen 09", TRANSFER);
                merchantToCategory.put("finicity", TRANSFER);
                merchantToCategory.put("first republic", TRANSFER);
                merchantToCategory.put("first republic bank", TRANSFER);
                merchantToCategory.put("fps", TRANSFER);
                merchantToCategory.put("fund transfer", TRANSFER);
                merchantToCategory.put("gaap", TRANSFER);
                merchantToCategory.put("generally accepted accounting principles", TRANSFER);
                merchantToCategory.put("goldman sachs", TRANSFER);
                merchantToCategory.put("iat", TRANSFER);
                merchantToCategory.put("iat credit", TRANSFER);
                merchantToCategory.put("iat debit", TRANSFER);
                merchantToCategory.put("iat entry", TRANSFER);
                merchantToCategory.put("iban", TRANSFER);
                merchantToCategory.put("iban transfer", TRANSFER);
                merchantToCategory.put("immediate payment service", TRANSFER);
                merchantToCategory.put("imps", TRANSFER);
                merchantToCategory.put("imps india", TRANSFER);
                merchantToCategory.put("inter-account transfer", TRANSFER);
                merchantToCategory.put("inter-bank transfer", TRANSFER);
                merchantToCategory.put("interac", TRANSFER);
                merchantToCategory.put("interac e-transfer", TRANSFER);
                merchantToCategory.put("interac etransfer", TRANSFER);
                merchantToCategory.put("internal transfer", TRANSFER);
                merchantToCategory.put("international bank account number", TRANSFER);
                merchantToCategory.put("intra-account transfer", TRANSFER);
                merchantToCategory.put("intra-bank transfer", TRANSFER);
                merchantToCategory.put("intuit", TRANSFER);
                merchantToCategory.put("iso 20022", TRANSFER);
                merchantToCategory.put("iso20022", TRANSFER);
                merchantToCategory.put("marcus", TRANSFER);
                merchantToCategory.put("marcus by goldman sachs", TRANSFER);
                merchantToCategory.put("mcc 6012", TRANSFER);
                merchantToCategory.put("mcc6012", TRANSFER);
                merchantToCategory.put("mint", TRANSFER);
                merchantToCategory.put("money transfer", TRANSFER);
                merchantToCategory.put("mt 101", TRANSFER);
                merchantToCategory.put("mt 102", TRANSFER);
                merchantToCategory.put("mt 103", TRANSFER);
                merchantToCategory.put("mt 104", TRANSFER);
                merchantToCategory.put("mt 110", TRANSFER);
                merchantToCategory.put("mt 111", TRANSFER);
                merchantToCategory.put("mt 200", TRANSFER);
                merchantToCategory.put("mt 201", TRANSFER);
                merchantToCategory.put("mt 202", TRANSFER);
                merchantToCategory.put("mt 210", TRANSFER);
                merchantToCategory.put("mt 900", TRANSFER);
                merchantToCategory.put("mt 910", TRANSFER);
                merchantToCategory.put("mt 940", TRANSFER);
                merchantToCategory.put("mt 942", TRANSFER);
                merchantToCategory.put("mt 950", TRANSFER);
                merchantToCategory.put("mt101", TRANSFER);
                merchantToCategory.put("mt102", TRANSFER);
                merchantToCategory.put("mt103", TRANSFER);
                merchantToCategory.put("mt104", TRANSFER);
                merchantToCategory.put("mt110", TRANSFER);
                merchantToCategory.put("mt111", TRANSFER);
                merchantToCategory.put("mt200", TRANSFER);
                merchantToCategory.put("mt201", TRANSFER);
                merchantToCategory.put("mt202", TRANSFER);
                merchantToCategory.put("mt210", TRANSFER);
                merchantToCategory.put("mt900", TRANSFER);
                merchantToCategory.put("mt910", TRANSFER);
                merchantToCategory.put("mt940", TRANSFER);
                merchantToCategory.put("mt942", TRANSFER);
                merchantToCategory.put("mt950", TRANSFER);
                merchantToCategory.put("mx", TRANSFER);
                merchantToCategory.put("mx technologies", TRANSFER);
                merchantToCategory.put("national electronic funds transfer", TRANSFER);
                merchantToCategory.put("navy federal", TRANSFER);
                merchantToCategory.put("navy federal credit union", TRANSFER);
                merchantToCategory.put("neft", TRANSFER);
                merchantToCategory.put("neft india", TRANSFER);
                merchantToCategory.put("new payments platform", TRANSFER);
                merchantToCategory.put("npp", TRANSFER);
                merchantToCategory.put("npp australia", TRANSFER);
                merchantToCategory.put("ofac", TRANSFER);
                merchantToCategory.put("office of foreign assets control", TRANSFER);
                merchantToCategory.put("pacs.002", TRANSFER);
                merchantToCategory.put("pacs.008", TRANSFER);
                merchantToCategory.put("pacs.009", TRANSFER);
                merchantToCategory.put("pain.001", TRANSFER);
                merchantToCategory.put("pain.002", TRANSFER);
                merchantToCategory.put("pain.008", TRANSFER);
                merchantToCategory.put("pain.009", TRANSFER);
                merchantToCategory.put("payment transfer", TRANSFER);
                merchantToCategory.put("penfed", TRANSFER);
                merchantToCategory.put("pentagon federal", TRANSFER);
                merchantToCategory.put("plaid", TRANSFER);
                merchantToCategory.put("quovo", TRANSFER);
                merchantToCategory.put("real time gross settlement", TRANSFER);
                merchantToCategory.put("routing number", TRANSFER);
                merchantToCategory.put("routing transit number", TRANSFER);
                merchantToCategory.put("rtgs", TRANSFER);
                merchantToCategory.put("rtgs india", TRANSFER);
                merchantToCategory.put("rtn", TRANSFER);
                merchantToCategory.put("sar", TRANSFER);
                merchantToCategory.put("schwab bank", TRANSFER);
                merchantToCategory.put("sdn", TRANSFER);
                merchantToCategory.put("sepa", TRANSFER);
                merchantToCategory.put("sepa credit transfer", TRANSFER);
                merchantToCategory.put("sepa instant", TRANSFER);
                merchantToCategory.put("sepa instant credit transfer", TRANSFER);
                merchantToCategory.put("sepa transfer", TRANSFER);
                merchantToCategory.put("sic 46", TRANSFER);
                merchantToCategory.put("signature bank", TRANSFER);
                merchantToCategory.put("silicon valley bank", TRANSFER);
                merchantToCategory.put("sophtron", TRANSFER);
                merchantToCategory.put("specially designated nationals", TRANSFER);
                merchantToCategory.put("suspicious activity report", TRANSFER);
                merchantToCategory.put("svb", TRANSFER);
                merchantToCategory.put("swift", TRANSFER);
                merchantToCategory.put("swift code", TRANSFER);
                merchantToCategory.put("target 2", TRANSFER);
                merchantToCategory.put("target instant payment settlement", TRANSFER);
                merchantToCategory.put("target2", TRANSFER);
                merchantToCategory.put("target2 transfer", TRANSFER);
                merchantToCategory.put("teller", TRANSFER);
                merchantToCategory.put("teller.io", TRANSFER);
                merchantToCategory.put("tips", TRANSFER);
                merchantToCategory.put("transfer between", TRANSFER);
                merchantToCategory.put("transfer fee", TRANSFER);
                merchantToCategory.put("transfer from", TRANSFER);
                merchantToCategory.put("transfer processing", TRANSFER);
                merchantToCategory.put("transfer service", TRANSFER);
                merchantToCategory.put("transfer to", TRANSFER);
                merchantToCategory.put("transferred", TRANSFER);
                merchantToCategory.put("transferring", TRANSFER);
                merchantToCategory.put("transfers", TRANSFER);
                merchantToCategory.put("unified payments interface", TRANSFER);
                merchantToCategory.put("upi", TRANSFER);
                merchantToCategory.put("upi india", TRANSFER);
                merchantToCategory.put("usaa bank", TRANSFER);
                merchantToCategory.put("yodlee", TRANSFER);

                merchantCount += 279;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== TRANSPORTATION (321 merchants) ==========
                merchantToCategory.put("7-eleven", TRANSPORTATION);
                merchantToCategory.put("76", TRANSPORTATION);
                merchantToCategory.put("76 gas", TRANSPORTATION);
                merchantToCategory.put("76 station", TRANSPORTATION);
                merchantToCategory.put("7eleven", TRANSPORTATION);
                merchantToCategory.put("airport cart", TRANSPORTATION);
                merchantToCategory.put("airport chair", TRANSPORTATION);
                merchantToCategory.put("alfa romeo car service", TRANSPORTATION);
                merchantToCategory.put("alfa romeo ctr", TRANSPORTATION);
                merchantToCategory.put("alfa romeocarservice", TRANSPORTATION);
                merchantToCategory.put("alfa romeoctr", TRANSPORTATION);
                merchantToCategory.put("am/pm", TRANSPORTATION);
                // (Previously "amex airlines fee reimbursement" → transportation;
                // fixed to "travel" in the TRAVEL block below.)
                merchantToCategory.put("ampm", TRANSPORTATION);
                merchantToCategory.put("arco", TRANSPORTATION);
                merchantToCategory.put("arco express", TRANSPORTATION);
                merchantToCategory.put("arco express gas", TRANSPORTATION);
                merchantToCategory.put("aston martin car service", TRANSPORTATION);
                merchantToCategory.put("aston martin ctr", TRANSPORTATION);
                merchantToCategory.put("aston martincarservice", TRANSPORTATION);
                merchantToCategory.put("aston martinctr", TRANSPORTATION);
                merchantToCategory.put("audi car service", TRANSPORTATION);
                merchantToCategory.put("audi ctr", TRANSPORTATION);
                merchantToCategory.put("audicarservice", TRANSPORTATION);
                merchantToCategory.put("audictr", TRANSPORTATION);
                merchantToCategory.put("automobile", TRANSPORTATION);
                merchantToCategory.put("bentley car service", TRANSPORTATION);
                merchantToCategory.put("bentley ctr", TRANSPORTATION);
                merchantToCategory.put("bentleycarservice", TRANSPORTATION);
                merchantToCategory.put("bentleyctr", TRANSPORTATION);
                merchantToCategory.put("bmw car service", TRANSPORTATION);
                merchantToCategory.put("bmw ctr", TRANSPORTATION);
                merchantToCategory.put("bmwcarservice", TRANSPORTATION);
                merchantToCategory.put("bmwctr", TRANSPORTATION);
                merchantToCategory.put("bolt", TRANSPORTATION);
                merchantToCategory.put("bp", TRANSPORTATION);
                merchantToCategory.put("bp fuel", TRANSPORTATION);
                merchantToCategory.put("bp gas", TRANSPORTATION);
                merchantToCategory.put("bp station", TRANSPORTATION);
                merchantToCategory.put("bridge toll", TRANSPORTATION);
                merchantToCategory.put("british petroleum", TRANSPORTATION);
                merchantToCategory.put("buc-ee", TRANSPORTATION);
                merchantToCategory.put("buc-ee's", TRANSPORTATION);
                merchantToCategory.put("buc-ees", TRANSPORTATION);
                merchantToCategory.put("bucee", TRANSPORTATION);
                merchantToCategory.put("bucees", TRANSPORTATION);
                merchantToCategory.put("bugatti car service", TRANSPORTATION);
                merchantToCategory.put("bugatti ctr", TRANSPORTATION);
                merchantToCategory.put("bugatticarservice", TRANSPORTATION);
                merchantToCategory.put("bugattictr", TRANSPORTATION);
                merchantToCategory.put("bus", TRANSPORTATION);
                merchantToCategory.put("cab", TRANSPORTATION);
                merchantToCategory.put("california dot", TRANSPORTATION);
                merchantToCategory.put("caltrans", TRANSPORTATION);
                merchantToCategory.put("car service", TRANSPORTATION);
                merchantToCategory.put("careem", TRANSPORTATION);
                merchantToCategory.put("carservice", TRANSPORTATION);
                merchantToCategory.put("chevrolet car service", TRANSPORTATION);
                merchantToCategory.put("chevrolet ctr", TRANSPORTATION);
                merchantToCategory.put("chevroletcarservice", TRANSPORTATION);
                merchantToCategory.put("chevroletctr", TRANSPORTATION);
                merchantToCategory.put("chevron", TRANSPORTATION);
                merchantToCategory.put("chrysler car service", TRANSPORTATION);
                merchantToCategory.put("chrysler ctr", TRANSPORTATION);
                merchantToCategory.put("chryslercarservice", TRANSPORTATION);
                merchantToCategory.put("chryslerctr", TRANSPORTATION);
                merchantToCategory.put("circle k", TRANSPORTATION);
                merchantToCategory.put("circlek", TRANSPORTATION);
                merchantToCategory.put("citgo", TRANSPORTATION);
                merchantToCategory.put("commute", TRANSPORTATION);
                merchantToCategory.put("conoco", TRANSPORTATION);
                merchantToCategory.put("conocophillips", TRANSPORTATION);
                merchantToCategory.put("costco gas", TRANSPORTATION);
                merchantToCategory.put("costcogas", TRANSPORTATION);
                merchantToCategory.put("department of transportation", TRANSPORTATION);
                merchantToCategory.put("didi", TRANSPORTATION);
                merchantToCategory.put("diesel", TRANSPORTATION);
                merchantToCategory.put("dodge car service", TRANSPORTATION);
                merchantToCategory.put("dodge ctr", TRANSPORTATION);
                merchantToCategory.put("dodgecarservice", TRANSPORTATION);
                merchantToCategory.put("dodgectr", TRANSPORTATION);
                merchantToCategory.put("dot toll", TRANSPORTATION);
                merchantToCategory.put("e-zpass", TRANSPORTATION);
                merchantToCategory.put("epass", TRANSPORTATION);
                merchantToCategory.put("era toll", TRANSPORTATION);
                merchantToCategory.put("eractoll", TRANSPORTATION);
                merchantToCategory.put("eratoll", TRANSPORTATION);
                merchantToCategory.put("esso", TRANSPORTATION);
                merchantToCategory.put("expressway toll", TRANSPORTATION);
                merchantToCategory.put("exxon", TRANSPORTATION);
                merchantToCategory.put("exxonmobil", TRANSPORTATION);
                merchantToCategory.put("ez pass", TRANSPORTATION);
                merchantToCategory.put("ez tag", TRANSPORTATION);
                merchantToCategory.put("ezpass", TRANSPORTATION);
                merchantToCategory.put("fastrak", TRANSPORTATION);
                merchantToCategory.put("fdot", TRANSPORTATION);
                merchantToCategory.put("ferrari car service", TRANSPORTATION);
                merchantToCategory.put("ferrari ctr", TRANSPORTATION);
                merchantToCategory.put("ferraricarservice", TRANSPORTATION);
                merchantToCategory.put("ferrarictr", TRANSPORTATION);
                merchantToCategory.put("fiat car service", TRANSPORTATION);
                merchantToCategory.put("fiat ctr", TRANSPORTATION);
                merchantToCategory.put("fiatacarservice", TRANSPORTATION);
                merchantToCategory.put("fiatctr", TRANSPORTATION);
                merchantToCategory.put("fill up", TRANSPORTATION);
                merchantToCategory.put("filling station", TRANSPORTATION);
                merchantToCategory.put("florida dot", TRANSPORTATION);
                merchantToCategory.put("flying j", TRANSPORTATION);
                merchantToCategory.put("flyingj", TRANSPORTATION);
                merchantToCategory.put("ford car service", TRANSPORTATION);
                merchantToCategory.put("ford ctr", TRANSPORTATION);
                merchantToCategory.put("fordcarservice", TRANSPORTATION);
                merchantToCategory.put("fordctr", TRANSPORTATION);
                merchantToCategory.put("fuel purchase", TRANSPORTATION);
                merchantToCategory.put("fuel station", TRANSPORTATION);
                merchantToCategory.put("garage", TRANSPORTATION);
                merchantToCategory.put("garden state parkway", TRANSPORTATION);
                merchantToCategory.put("gas station", TRANSPORTATION);
                merchantToCategory.put("gasoline", TRANSPORTATION);
                merchantToCategory.put("gett", TRANSPORTATION);
                merchantToCategory.put("gmc car service", TRANSPORTATION);
                merchantToCategory.put("gmc ctr", TRANSPORTATION);
                merchantToCategory.put("gmccarservice", TRANSPORTATION);
                merchantToCategory.put("gmcctr", TRANSPORTATION);
                merchantToCategory.put("good to go", TRANSPORTATION);
                merchantToCategory.put("good-to-go", TRANSPORTATION);
                merchantToCategory.put("goodtogo", TRANSPORTATION);
                merchantToCategory.put("grab", TRANSPORTATION);
                merchantToCategory.put("highway authority", TRANSPORTATION);
                merchantToCategory.put("highway toll", TRANSPORTATION);
                merchantToCategory.put("hona car service", TRANSPORTATION);
                merchantToCategory.put("hona ctr", TRANSPORTATION);
                merchantToCategory.put("honacarservice", TRANSPORTATION);
                merchantToCategory.put("honactr", TRANSPORTATION);
                merchantToCategory.put("honda car service", TRANSPORTATION);
                merchantToCategory.put("honda ctr", TRANSPORTATION);
                merchantToCategory.put("hondacarservice", TRANSPORTATION);
                merchantToCategory.put("hondactr", TRANSPORTATION);
                merchantToCategory.put("hyundai car service", TRANSPORTATION);
                merchantToCategory.put("hyundai ctr", TRANSPORTATION);
                merchantToCategory.put("hyundaicarservice", TRANSPORTATION);
                merchantToCategory.put("hyundaictr", TRANSPORTATION);
                merchantToCategory.put("idot", TRANSPORTATION);
                merchantToCategory.put("illinois dot", TRANSPORTATION);
                merchantToCategory.put("impark", TRANSPORTATION);
                merchantToCategory.put("ipass", TRANSPORTATION);
                merchantToCategory.put("irs car and truck expenses", TRANSPORTATION);
                merchantToCategory.put("jeep car service", TRANSPORTATION);
                merchantToCategory.put("jeep ctr", TRANSPORTATION);
                merchantToCategory.put("jeepcarservice", TRANSPORTATION);
                merchantToCategory.put("jeepctr", TRANSPORTATION);
                merchantToCategory.put("kia car service", TRANSPORTATION);
                merchantToCategory.put("kia ctr", TRANSPORTATION);
                merchantToCategory.put("kiacarservice", TRANSPORTATION);
                merchantToCategory.put("kiactr", TRANSPORTATION);
                merchantToCategory.put("kwik sak", TRANSPORTATION);
                merchantToCategory.put("kwik-sak", TRANSPORTATION);
                merchantToCategory.put("kwiksak", TRANSPORTATION);
                merchantToCategory.put("lamborghini car service", TRANSPORTATION);
                merchantToCategory.put("lamborghini ctr", TRANSPORTATION);
                merchantToCategory.put("lamborghinicarservice", TRANSPORTATION);
                merchantToCategory.put("lamborghinictr", TRANSPORTATION);
                merchantToCategory.put("land rover car service", TRANSPORTATION);
                merchantToCategory.put("land rover ctr", TRANSPORTATION);
                merchantToCategory.put("land rovercarservice", TRANSPORTATION);
                merchantToCategory.put("land roverctr", TRANSPORTATION);
                merchantToCategory.put("london underground", TRANSPORTATION);
                merchantToCategory.put("love's", TRANSPORTATION);
                merchantToCategory.put("loves", TRANSPORTATION);
                merchantToCategory.put("loves travel stops", TRANSPORTATION);
                merchantToCategory.put("lul ticket mach", TRANSPORTATION);
                merchantToCategory.put("lul ticket machine", TRANSPORTATION);
                merchantToCategory.put("lulticketmachine", TRANSPORTATION);
                merchantToCategory.put("lyft", TRANSPORTATION);
                merchantToCategory.put("lyft ride", TRANSPORTATION);
                merchantToCategory.put("marathon", TRANSPORTATION);
                merchantToCategory.put("marathon petroleum", TRANSPORTATION);
                merchantToCategory.put("maryland dot", TRANSPORTATION);
                merchantToCategory.put("massachusetts dot", TRANSPORTATION);
                merchantToCategory.put("massdot", TRANSPORTATION);
                merchantToCategory.put("mcc 5541", TRANSPORTATION);
                merchantToCategory.put("mcc5541", TRANSPORTATION);
                merchantToCategory.put("mclaren car service", TRANSPORTATION);
                merchantToCategory.put("mclaren ctr", TRANSPORTATION);
                merchantToCategory.put("mclarencarservice", TRANSPORTATION);
                merchantToCategory.put("mclarenctr", TRANSPORTATION);
                merchantToCategory.put("mdot", TRANSPORTATION);
                merchantToCategory.put("mercedes-benz car service", TRANSPORTATION);
                merchantToCategory.put("mercedes-benz ctr", TRANSPORTATION);
                merchantToCategory.put("mercedes-benzcarservice", TRANSPORTATION);
                merchantToCategory.put("mercedes-benzctr", TRANSPORTATION);
                merchantToCategory.put("metro", TRANSPORTATION);
                merchantToCategory.put("metropolis parking", TRANSPORTATION);
                merchantToCategory.put("metropolisparking", TRANSPORTATION);
                merchantToCategory.put("mobil", TRANSPORTATION);
                merchantToCategory.put("murphy usa", TRANSPORTATION);
                merchantToCategory.put("murphyusa", TRANSPORTATION);
                merchantToCategory.put("naics 36", TRANSPORTATION);
                merchantToCategory.put("naics 48", TRANSPORTATION);
                merchantToCategory.put("new jersey dot", TRANSPORTATION);
                merchantToCategory.put("new jersey turnpike", TRANSPORTATION);
                merchantToCategory.put("new york state dot", TRANSPORTATION);
                merchantToCategory.put("new york thruway", TRANSPORTATION);
                merchantToCategory.put("nissacarservice", TRANSPORTATION);
                merchantToCategory.put("nissactr", TRANSPORTATION);
                merchantToCategory.put("nissan car service", TRANSPORTATION);
                merchantToCategory.put("nissan ctr", TRANSPORTATION);
                merchantToCategory.put("njdot", TRANSPORTATION);
                merchantToCategory.put("nysdot", TRANSPORTATION);
                merchantToCategory.put("ola", TRANSPORTATION);
                merchantToCategory.put("park mobile", TRANSPORTATION);
                merchantToCategory.put("parking", TRANSPORTATION);
                merchantToCategory.put("parking fee", TRANSPORTATION);
                merchantToCategory.put("parking meter", TRANSPORTATION);
                merchantToCategory.put("parkingmeter", TRANSPORTATION);
                merchantToCategory.put("parkmobile", TRANSPORTATION);
                merchantToCategory.put("pay by phone", TRANSPORTATION);
                merchantToCategory.put("paybyphone", TRANSPORTATION);
                merchantToCategory.put("penn dot", TRANSPORTATION);
                merchantToCategory.put("penndot", TRANSPORTATION);
                merchantToCategory.put("pennsylvania dot", TRANSPORTATION);
                merchantToCategory.put("petrol", TRANSPORTATION);
                merchantToCategory.put("petrol fill up", TRANSPORTATION);
                merchantToCategory.put("petrol station", TRANSPORTATION);
                merchantToCategory.put("phillips 66", TRANSPORTATION);
                merchantToCategory.put("phillips66", TRANSPORTATION);
                merchantToCategory.put("pilot", TRANSPORTATION);
                merchantToCategory.put("pilot flying j", TRANSPORTATION);
                merchantToCategory.put("porsche car service", TRANSPORTATION);
                merchantToCategory.put("porsche ctr", TRANSPORTATION);
                merchantToCategory.put("porschecarservice", TRANSPORTATION);
                merchantToCategory.put("porschectr", TRANSPORTATION);
                merchantToCategory.put("public transport", TRANSPORTATION);
                merchantToCategory.put("qt", TRANSPORTATION);
                merchantToCategory.put("quik trip", TRANSPORTATION);
                merchantToCategory.put("quiktrip", TRANSPORTATION);
                merchantToCategory.put("ram car service", TRANSPORTATION);
                merchantToCategory.put("ram ctr", TRANSPORTATION);
                merchantToCategory.put("ramcarservice", TRANSPORTATION);
                merchantToCategory.put("ramctr", TRANSPORTATION);
                merchantToCategory.put("ride", TRANSPORTATION);
                merchantToCategory.put("ride share", TRANSPORTATION);
                merchantToCategory.put("rideshare", TRANSPORTATION);
                merchantToCategory.put("road toll", TRANSPORTATION);
                merchantToCategory.put("rolls-royce car service", TRANSPORTATION);
                merchantToCategory.put("rolls-royce ctr", TRANSPORTATION);
                merchantToCategory.put("rolls-roycecarservice", TRANSPORTATION);
                merchantToCategory.put("rolls-roycectr", TRANSPORTATION);
                merchantToCategory.put("seattle airport", TRANSPORTATION);
                merchantToCategory.put("seattle ap", TRANSPORTATION);
                merchantToCategory.put("seattleap", TRANSPORTATION);
                merchantToCategory.put("seattleap cart", TRANSPORTATION);
                merchantToCategory.put("seattleap cart/chair", TRANSPORTATION);
                merchantToCategory.put("seattleap chair", TRANSPORTATION);
                merchantToCategory.put("service station", TRANSPORTATION);
                merchantToCategory.put("sheetz", TRANSPORTATION);
                merchantToCategory.put("shell", TRANSPORTATION);
                merchantToCategory.put("sic 37", TRANSPORTATION);
                merchantToCategory.put("sic 42", TRANSPORTATION);
                merchantToCategory.put("sic 44", TRANSPORTATION);
                merchantToCategory.put("sic 45", TRANSPORTATION);
                merchantToCategory.put("sic 47", TRANSPORTATION);
                merchantToCategory.put("sic 75", TRANSPORTATION);
                merchantToCategory.put("speedway", TRANSPORTATION);
                merchantToCategory.put("state department of transportation", TRANSPORTATION);
                merchantToCategory.put("state dot", TRANSPORTATION);
                merchantToCategory.put("sunoco", TRANSPORTATION);
                merchantToCategory.put("sunpass", TRANSPORTATION);
                merchantToCategory.put("ta", TRANSPORTATION);
                merchantToCategory.put("taxi", TRANSPORTATION);
                merchantToCategory.put("texas dot", TRANSPORTATION);
                merchantToCategory.put("ticket machine", TRANSPORTATION);
                merchantToCategory.put("ticketmachine", TRANSPORTATION);
                merchantToCategory.put("toll", TRANSPORTATION);
                merchantToCategory.put("toll authority", TRANSPORTATION);
                merchantToCategory.put("toll charge", TRANSPORTATION);
                merchantToCategory.put("toll fee", TRANSPORTATION);
                merchantToCategory.put("toll payment", TRANSPORTATION);
                merchantToCategory.put("toll road", TRANSPORTATION);
                merchantToCategory.put("tollway", TRANSPORTATION);
                merchantToCategory.put("toyota car service", TRANSPORTATION);
                merchantToCategory.put("toyota ctr", TRANSPORTATION);
                merchantToCategory.put("toyotacarservice", TRANSPORTATION);
                merchantToCategory.put("toyotactr", TRANSPORTATION);
                merchantToCategory.put("train", TRANSPORTATION);
                merchantToCategory.put("transit", TRANSPORTATION);
                merchantToCategory.put("transport", TRANSPORTATION);
                merchantToCategory.put("transportation authority", TRANSPORTATION);
                merchantToCategory.put("travel centers", TRANSPORTATION);
                merchantToCategory.put("travelcenters of america", TRANSPORTATION);
                merchantToCategory.put("truck stop", TRANSPORTATION);
                merchantToCategory.put("tunnel toll", TRANSPORTATION);
                merchantToCategory.put("turnpike authority", TRANSPORTATION);
                merchantToCategory.put("txdot", TRANSPORTATION);
                merchantToCategory.put("txtag", TRANSPORTATION);
                merchantToCategory.put("uber", TRANSPORTATION);
                merchantToCategory.put("uber ride", TRANSPORTATION);
                merchantToCategory.put("union 76", TRANSPORTATION);
                merchantToCategory.put("union76", TRANSPORTATION);
                merchantToCategory.put("unocal 76", TRANSPORTATION);
                merchantToCategory.put("uw pay by phone", TRANSPORTATION);
                merchantToCategory.put("uw paybyphone", TRANSPORTATION);
                merchantToCategory.put("uwpay by phone", TRANSPORTATION);
                merchantToCategory.put("uwpaybyphone", TRANSPORTATION);
                merchantToCategory.put("valero", TRANSPORTATION);
                merchantToCategory.put("vdot", TRANSPORTATION);
                merchantToCategory.put("vehicle", TRANSPORTATION);
                merchantToCategory.put("virginia dot", TRANSPORTATION);
                merchantToCategory.put("volkswagen car service", TRANSPORTATION);
                merchantToCategory.put("volkswagen ctr", TRANSPORTATION);
                merchantToCategory.put("volkswagencarservice", TRANSPORTATION);
                merchantToCategory.put("volkswagenctr", TRANSPORTATION);
                merchantToCategory.put("volvo car service", TRANSPORTATION);
                merchantToCategory.put("volvo ctr", TRANSPORTATION);
                merchantToCategory.put("volvocarservice", TRANSPORTATION);
                merchantToCategory.put("volvoctr", TRANSPORTATION);
                merchantToCategory.put(
                        "washington state department of transportation", TRANSPORTATION);
                merchantToCategory.put("washington state dot", TRANSPORTATION);
                merchantToCategory.put("wawa", TRANSPORTATION);
                merchantToCategory.put("wsdot", TRANSPORTATION);

                merchantCount += 321;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== OTHER (accounting-placeholder rows) ==========
                // Statement lines that represent bank-side bookkeeping, not a
                // real merchant event. These descriptions mostly appear on
                // offer/promotion re-categorisations and have no category
                // signal on their own. Explicitly mapping to "other" keeps
                // ML from semantic-matching them to the nearest noisy class
                // (historically these fell into "transportation").
                merchantToCategory.put("moved to standard purch", OTHER);
                merchantToCategory.put("moved to standard purchase", OTHER);
                merchantToCategory.put("offer moved to standard", OTHER);
                merchantToCategory.put("standard purch", OTHER);

                // ========== FEES ==========
                // Bank/card fees the user actually pays as a fee line —
                // distinct from "payment" (paying down a balance) and from
                // "other" (miscellaneous). These are full-phrase keywords
                // so they never match a regular purchase.
                merchantToCategory.put("foreign transaction fee", "fees");
                merchantToCategory.put("foreign transaction", "fees");
                merchantToCategory.put("overdraft fee", "fees");
                merchantToCategory.put("overdraft charge", "fees");
                merchantToCategory.put("atm fee", "fees");
                merchantToCategory.put("atm surcharge", "fees");
                merchantToCategory.put("wire fee", "fees");
                merchantToCategory.put("wire transfer fee", "fees");
                merchantToCategory.put("late fee", "fees");
                merchantToCategory.put("late payment fee", "fees");
                merchantToCategory.put("annual fee", "fees");
                merchantToCategory.put("annual membership fee", "fees");
                merchantToCategory.put("service charge", "fees");
                merchantToCategory.put("maintenance fee", "fees");
                merchantToCategory.put("monthly service fee", "fees");
                merchantToCategory.put("returned item fee", "fees");
                merchantToCategory.put("nsf fee", "fees");
                merchantToCategory.put("cash advance fee", "fees");
                merchantToCategory.put("stop payment fee", "fees");
                merchantToCategory.put("balance transfer fee", "fees");
                merchantToCategory.put("paper statement fee", "fees");
                merchantToCategory.put("inactivity fee", "fees");
                merchantToCategory.put("minimum balance fee", "fees");

                // ========== TRAVEL (75 merchants) ==========
                // Government travel services — all "travel" because they're
                // fees paid for the ability to travel internationally or
                // through expedited airport lanes. Their descriptions often
                // include "PAYMENT" as a verb (e.g. "PASSPORT SERVICES
                // PAYMENT"), so these multi-word travel keywords must be
                // scored higher than the bare-issuer "payment" rules.
                merchantToCategory.put("tsa precheck", TRAVEL);
                merchantToCategory.put("tsa prechk", TRAVEL);
                merchantToCategory.put("precheck", TRAVEL);
                merchantToCategory.put("global entry", TRAVEL);
                merchantToCategory.put("globalentry", TRAVEL);
                merchantToCategory.put("clear plus", TRAVEL);
                merchantToCategory.put("passport services", TRAVEL);
                // Check/ACH descriptions often concatenate: "PASSPORTSERVICES"
                merchantToCategory.put("passportservices", TRAVEL);
                merchantToCategory.put("passport fee", TRAVEL);
                merchantToCategory.put("us passport", TRAVEL);
                merchantToCategory.put("visa application", TRAVEL);
                merchantToCategory.put("visa service", TRAVEL);
                merchantToCategory.put("visa services", TRAVEL);
                merchantToCategory.put("immigration service", TRAVEL);
                merchantToCategory.put("ttp cbp", TRAVEL); // Trusted Traveler Programs
                merchantToCategory.put("airline fee", TRAVEL);
                merchantToCategory.put("airline fee reimbursement", TRAVEL);
                merchantToCategory.put("airline reimbursement", TRAVEL);
                merchantToCategory.put("amex airline fee reimbursement", TRAVEL);
                merchantToCategory.put("amex airlines fee reimbursement", TRAVEL);
                merchantToCategory.put("baggage fee", TRAVEL);
                merchantToCategory.put("viasat", TRAVEL); // in-flight wifi
                merchantToCategory.put("gogo inflight", TRAVEL);
                merchantToCategory.put("admirals club", TRAVEL);
                merchantToCategory.put("admiralsclub", TRAVEL);
                merchantToCategory.put("airbnb", TRAVEL);
                merchantToCategory.put("airline", TRAVEL);
                merchantToCategory.put("airlines", TRAVEL);
                merchantToCategory.put("airport loung", TRAVEL);
                merchantToCategory.put("airport lounge", TRAVEL);
                merchantToCategory.put("airportlounge", TRAVEL);
                merchantToCategory.put("alaska", TRAVEL);
                merchantToCategory.put("allegiant", TRAVEL);
                merchantToCategory.put("american airlines", TRAVEL);
                merchantToCategory.put("american express centurion", TRAVEL);
                merchantToCategory.put("american express lounge", TRAVEL);
                merchantToCategory.put("americanairlines", TRAVEL);
                merchantToCategory.put("amex centurion", TRAVEL);
                merchantToCategory.put("amex lounge", TRAVEL);
                merchantToCategory.put("axp centurion", TRAVEL);
                merchantToCategory.put("axpcenturion", TRAVEL);
                merchantToCategory.put("best western", TRAVEL);
                merchantToCategory.put("booking.com", TRAVEL);
                merchantToCategory.put("centurion lounge", TRAVEL);
                merchantToCategory.put("centurionlounge", TRAVEL);
                merchantToCategory.put("courtyard", TRAVEL);
                merchantToCategory.put("delta", TRAVEL);
                merchantToCategory.put("delta air lines", TRAVEL);
                merchantToCategory.put("delta airlines", TRAVEL);
                merchantToCategory.put("delta sky club", TRAVEL);
                merchantToCategory.put("deltaskyclub", TRAVEL);
                merchantToCategory.put("embassy suites", TRAVEL);
                merchantToCategory.put("encalm", TRAVEL);
                merchantToCategory.put("encalm lounge", TRAVEL);
                merchantToCategory.put("encalmlounge", TRAVEL);
                merchantToCategory.put("expedia", TRAVEL);
                merchantToCategory.put("frontier", TRAVEL);
                merchantToCategory.put("hampton inn", TRAVEL);
                merchantToCategory.put("hawaiian", TRAVEL);
                merchantToCategory.put("hilton", TRAVEL);
                merchantToCategory.put("holiday inn", TRAVEL);
                merchantToCategory.put("holidayinn", TRAVEL);
                merchantToCategory.put("hotel", TRAVEL);
                merchantToCategory.put("hotels.com", TRAVEL);
                merchantToCategory.put("hyatt", TRAVEL);
                merchantToCategory.put("inn", TRAVEL);
                merchantToCategory.put("irs travel", TRAVEL);
                merchantToCategory.put("jetblue", TRAVEL);
                merchantToCategory.put("kayak", TRAVEL);
                merchantToCategory.put("lounge", TRAVEL);
                merchantToCategory.put("lyft pink", TRAVEL);
                merchantToCategory.put("lyftpink", TRAVEL);
                merchantToCategory.put("marriott", TRAVEL);
                merchantToCategory.put("motel", TRAVEL);
                merchantToCategory.put("orbitz", TRAVEL);
                merchantToCategory.put("pink membership", TRAVEL);
                merchantToCategory.put("pink subscription", TRAVEL);
                merchantToCategory.put("plaza premium lounge", TRAVEL);
                merchantToCategory.put("plazapremiumlounge", TRAVEL);
                merchantToCategory.put("priceline", TRAVEL);
                merchantToCategory.put("priority pass", TRAVEL);
                merchantToCategory.put("prioritypass", TRAVEL);
                merchantToCategory.put("residence inn", TRAVEL);
                merchantToCategory.put("residential inn", TRAVEL);
                merchantToCategory.put("resort", TRAVEL);
                merchantToCategory.put("southwest", TRAVEL);
                merchantToCategory.put("spirit", TRAVEL);
                merchantToCategory.put("travelocity", TRAVEL);
                merchantToCategory.put("uber one", TRAVEL);
                merchantToCategory.put("uber one membership", TRAVEL);
                merchantToCategory.put("uber one subscription", TRAVEL);
                merchantToCategory.put("uberone", TRAVEL);
                merchantToCategory.put("uberone membership", TRAVEL);
                merchantToCategory.put("uberone subscription", TRAVEL);
                merchantToCategory.put("united", TRAVEL);
                merchantToCategory.put("united club", TRAVEL);
                merchantToCategory.put("unitedclub", TRAVEL);
                merchantToCategory.put("vrbo", TRAVEL);

                merchantCount += 75;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                // ========== UTILITIES (193 merchants) ==========
                merchantToCategory.put("aep", UTILITIES);
                merchantToCategory.put("aes", UTILITIES);
                merchantToCategory.put("aes corporation", UTILITIES);
                merchantToCategory.put("altice", UTILITIES);
                merchantToCategory.put("american electric", UTILITIES);
                merchantToCategory.put("american electric power", UTILITIES);
                merchantToCategory.put("american water", UTILITIES);
                merchantToCategory.put("annual bill", UTILITIES);
                merchantToCategory.put("aqua america", UTILITIES);
                merchantToCategory.put("at and t", UTILITIES);
                merchantToCategory.put("at&t", UTILITIES);
                merchantToCategory.put("att", UTILITIES);
                merchantToCategory.put("att u-verse", UTILITIES);
                merchantToCategory.put("att uverse", UTILITIES);
                merchantToCategory.put("auto bill pay", UTILITIES);
                merchantToCategory.put("auto bill payment", UTILITIES);
                merchantToCategory.put("automatic bill pay", UTILITIES);
                merchantToCategory.put("bill", UTILITIES);
                merchantToCategory.put("bill due", UTILITIES);
                merchantToCategory.put("bill invoice", UTILITIES);
                merchantToCategory.put("bill pay", UTILITIES);
                merchantToCategory.put("bill pay provider", UTILITIES);
                merchantToCategory.put("bill pay service", UTILITIES);
                merchantToCategory.put("bill payment", UTILITIES);
                merchantToCategory.put("bill payment provider", UTILITIES);
                merchantToCategory.put("bill payment service", UTILITIES);
                merchantToCategory.put("bill reminder", UTILITIES);
                merchantToCategory.put("bill statement", UTILITIES);
                merchantToCategory.put("billpay", UTILITIES);
                merchantToCategory.put("billpay service", UTILITIES);
                merchantToCategory.put("billpaying", UTILITIES);
                merchantToCategory.put("bills", UTILITIES);
                merchantToCategory.put("boost mobile", UTILITIES);
                merchantToCategory.put("broadband", UTILITIES);
                merchantToCategory.put("cable bill", UTILITIES);
                merchantToCategory.put("cable tv", UTILITIES);
                merchantToCategory.put("california water service", UTILITIES);
                merchantToCategory.put("centerpoint", UTILITIES);
                merchantToCategory.put("centerpoint energy", UTILITIES);
                merchantToCategory.put("century link", UTILITIES);
                merchantToCategory.put("centurylink", UTILITIES);
                merchantToCategory.put("charter", UTILITIES);
                merchantToCategory.put("charter spectrum", UTILITIES);
                merchantToCategory.put("city of bellevue", UTILITIES);
                merchantToCategory.put("city of seattle", UTILITIES);
                merchantToCategory.put("city utility", UTILITIES);
                merchantToCategory.put("comcast", UTILITIES);
                merchantToCategory.put("con edison", UTILITIES);
                merchantToCategory.put("coned", UTILITIES);
                merchantToCategory.put("consolidated edison", UTILITIES);
                merchantToCategory.put("cox", UTILITIES);
                merchantToCategory.put("cox communications", UTILITIES);
                merchantToCategory.put("cricket", UTILITIES);
                merchantToCategory.put("cricket wireless", UTILITIES);
                merchantToCategory.put("direct tv", UTILITIES);
                merchantToCategory.put("directv", UTILITIES);
                merchantToCategory.put("dish", UTILITIES);
                merchantToCategory.put("dish network", UTILITIES);
                merchantToCategory.put("dominion energy", UTILITIES);
                merchantToCategory.put("dominionenergy", UTILITIES);
                merchantToCategory.put("duke energy", UTILITIES);
                merchantToCategory.put("dukeenergy", UTILITIES);
                merchantToCategory.put("edison", UTILITIES);
                merchantToCategory.put("electric bill", UTILITIES);
                merchantToCategory.put("electric company", UTILITIES);
                merchantToCategory.put("electric service", UTILITIES);
                merchantToCategory.put("electricity", UTILITIES);
                merchantToCategory.put("electronic bill pay", UTILITIES);
                merchantToCategory.put("ener billpay", UTILITIES);
                merchantToCategory.put("energy", UTILITIES);
                merchantToCategory.put("energy bill", UTILITIES);
                merchantToCategory.put("energy billpay", UTILITIES);
                merchantToCategory.put("energy company", UTILITIES);
                merchantToCategory.put("entergy", UTILITIES);
                merchantToCategory.put("exelon", UTILITIES);
                merchantToCategory.put("fios", UTILITIES);
                merchantToCategory.put("first energy", UTILITIES);
                merchantToCategory.put("firstenergy", UTILITIES);
                merchantToCategory.put("frontier communications", UTILITIES);
                merchantToCategory.put("gas bill", UTILITIES);
                merchantToCategory.put("gas company", UTILITIES);
                merchantToCategory.put("gas service", UTILITIES);
                merchantToCategory.put("gas utility", UTILITIES);
                merchantToCategory.put("google fi", UTILITIES);
                merchantToCategory.put("heating", UTILITIES);
                merchantToCategory.put("insurance bill", UTILITIES);
                merchantToCategory.put("internet bill", UTILITIES);
                merchantToCategory.put("internet service", UTILITIES);
                merchantToCategory.put("irs utilities", UTILITIES);
                merchantToCategory.put("loan bill", UTILITIES);
                merchantToCategory.put("mcc 4814", UTILITIES);
                merchantToCategory.put("mcc 4816", UTILITIES);
                merchantToCategory.put("mcc 4900", UTILITIES);
                merchantToCategory.put("mcc4814", UTILITIES);
                merchantToCategory.put("mcc4816", UTILITIES);
                merchantToCategory.put("mcc4900", UTILITIES);
                merchantToCategory.put("mediacom", UTILITIES);
                merchantToCategory.put("medical bill", UTILITIES);
                merchantToCategory.put("metro pcs", UTILITIES);
                merchantToCategory.put("metropcs", UTILITIES);
                merchantToCategory.put("mint mobile", UTILITIES);
                merchantToCategory.put("mobile", UTILITIES);
                merchantToCategory.put("monthly bill", UTILITIES);
                merchantToCategory.put("mortgage bill", UTILITIES);
                merchantToCategory.put("municipal utility", UTILITIES);
                merchantToCategory.put("naics 21", UTILITIES);
                merchantToCategory.put("naics 22", UTILITIES);
                merchantToCategory.put("naics 49", UTILITIES);
                merchantToCategory.put("naics 92", UTILITIES);
                merchantToCategory.put("national grid", UTILITIES);
                merchantToCategory.put("natural gas", UTILITIES);
                merchantToCategory.put("online bill pay", UTILITIES);
                merchantToCategory.put("online bill payment", UTILITIES);
                merchantToCategory.put("optimum", UTILITIES);
                merchantToCategory.put("pacific gas", UTILITIES);
                merchantToCategory.put("pacific gas and electric", UTILITIES);
                merchantToCategory.put("pacific power", UTILITIES);
                merchantToCategory.put("pg&e", UTILITIES);
                merchantToCategory.put("pge", UTILITIES);
                merchantToCategory.put("phone bill", UTILITIES);
                merchantToCategory.put("portland general electric", UTILITIES);
                merchantToCategory.put("power", UTILITIES);
                merchantToCategory.put("power bill", UTILITIES);
                merchantToCategory.put("power company", UTILITIES);
                merchantToCategory.put("pse", UTILITIES);
                merchantToCategory.put("public utility", UTILITIES);
                merchantToCategory.put("puget sound energy", UTILITIES);
                merchantToCategory.put("quarterly bill", UTILITIES);
                merchantToCategory.put("recurring bill", UTILITIES);
                merchantToCategory.put("san diego gas", UTILITIES);
                merchantToCategory.put("san diego gas & electric", UTILITIES);
                merchantToCategory.put("sce", UTILITIES);
                merchantToCategory.put("sdge", UTILITIES);
                merchantToCategory.put("seattle public utilities", UTILITIES);
                merchantToCategory.put("service provider", UTILITIES);
                merchantToCategory.put("sewage", UTILITIES);
                merchantToCategory.put("sewer", UTILITIES);
                merchantToCategory.put("sic 10", UTILITIES);
                merchantToCategory.put("sic 13", UTILITIES);
                merchantToCategory.put("sic 21", UTILITIES);
                merchantToCategory.put("sic 29", UTILITIES);
                merchantToCategory.put("sic 40", UTILITIES);
                merchantToCategory.put("sic 41", UTILITIES);
                merchantToCategory.put("sic 48", UTILITIES);
                merchantToCategory.put("sic 49", UTILITIES);
                merchantToCategory.put("sic 91", UTILITIES);
                merchantToCategory.put("sic 92", UTILITIES);
                merchantToCategory.put("sic 93", UTILITIES);
                merchantToCategory.put("sic 94", UTILITIES);
                merchantToCategory.put("sic 95", UTILITIES);
                merchantToCategory.put("sic 96", UTILITIES);
                merchantToCategory.put("sic 97", UTILITIES);
                merchantToCategory.put("southern california edison", UTILITIES);
                merchantToCategory.put("southern company", UTILITIES);
                merchantToCategory.put("southerncompany", UTILITIES);
                merchantToCategory.put("spectrum", UTILITIES);
                merchantToCategory.put("sprint", UTILITIES);
                merchantToCategory.put("spu", UTILITIES);
                merchantToCategory.put("straight talk", UTILITIES);
                merchantToCategory.put("streaming", UTILITIES);
                merchantToCategory.put("suddenlink", UTILITIES);
                merchantToCategory.put("suez water", UTILITIES);
                merchantToCategory.put("t mobile", UTILITIES);
                merchantToCategory.put("t-mobile", UTILITIES);
                merchantToCategory.put("telephone", UTILITIES);
                merchantToCategory.put("television", UTILITIES);
                merchantToCategory.put("tmobile", UTILITIES);
                merchantToCategory.put("tv", UTILITIES);
                merchantToCategory.put("tv bill", UTILITIES);
                merchantToCategory.put("us cellular", UTILITIES);
                merchantToCategory.put("uscellular", UTILITIES);
                merchantToCategory.put(UTILITIES, UTILITIES);
                merchantToCategory.put("utility bill", UTILITIES);
                merchantToCategory.put("utility bill pay", UTILITIES);
                merchantToCategory.put("utility bill payment", UTILITIES);
                merchantToCategory.put("utility company", UTILITIES);
                merchantToCategory.put("utility payment", UTILITIES);
                merchantToCategory.put("utility provider", UTILITIES);
                merchantToCategory.put("verizon", UTILITIES);
                merchantToCategory.put("verizon fios", UTILITIES);
                merchantToCategory.put("verizon wireless", UTILITIES);
                merchantToCategory.put("visible", UTILITIES);
                merchantToCategory.put("water bill", UTILITIES);
                merchantToCategory.put("water company", UTILITIES);
                merchantToCategory.put("water service", UTILITIES);
                merchantToCategory.put("water utility", UTILITIES);
                merchantToCategory.put("wi-fi", UTILITIES);
                merchantToCategory.put("wifi", UTILITIES);
                merchantToCategory.put("windstream", UTILITIES);
                merchantToCategory.put("xcel energy", UTILITIES);
                merchantToCategory.put("xfinity", UTILITIES);
                merchantToCategory.put("xfinity mobile", UTILITIES);
                merchantToCategory.put("xfinitymobile", UTILITIES);

                merchantCount += 193;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            MERCHANT_COUNT_EXCEEDED_SAFETY_LIMIT,
                            MAX_MERCHANTS);
                    return;
                }

                LOGGER.info("Loaded {} merchants across {} categories", merchantCount, 23);
            } catch (OutOfMemoryError e) {
                // CRITICAL: Handle memory errors specifically
                LOGGER.error(
                        "Out of memory loading merchant category data. Clearing data and continuing with empty state.",
                        e);
                merchantToCategory.clear();
                categoryToKeywords.clear();
            } catch (Exception e) {
                // CRITICAL: Error handling - log but don't throw
                LOGGER.error("Error loading merchant category data: {}", e.getMessage(), e);
                // CRITICAL: Clear partial data on error to prevent inconsistent state
                merchantToCategory.clear();
                categoryToKeywords.clear();
            }
        }
    }

    /**
     * Build reverse mapping: category -> set of keywords/merchants This is used by
     * SemanticMatchingService CRITICAL: Thread-safe, error handling, boundary checks
     */
    private void buildCategoryToKeywordsMapping() {
        // CRITICAL: Thread-safe initialization
        synchronized (categoryToKeywords) {
            try {
                // CRITICAL: Clear existing data to prevent stale state
                categoryToKeywords.clear();

                // CRITICAL: Boundary check - prevent memory issues
                final int MAX_KEYWORDS_PER_CATEGORY = 50_000; // Safety limit

                for (final Map.Entry<String, String> entry : merchantToCategory.entrySet()) {
                    // CRITICAL: Null checks
                    final String merchant = entry.getKey();
                    final String category = entry.getValue();

                    if (merchant == null || category == null) {
                        LOGGER.warn(
                                "Skipping null merchant or category in buildCategoryToKeywordsMapping");
                        continue;
                    }

                    // CRITICAL: Boundary check per category
                    final Set<String> keywords = categoryToKeywords.get(category);
                    if (keywords != null && keywords.size() >= MAX_KEYWORDS_PER_CATEGORY) {
                        LOGGER.warn(
                                "Category '{}' has exceeded maximum keywords limit ({}). Skipping additional keywords.",
                                category,
                                MAX_KEYWORDS_PER_CATEGORY);
                        continue;
                    }

                    categoryToKeywords
                            .computeIfAbsent(category, k -> new HashSet<>())
                            .add(merchant);
                }

                LOGGER.debug(
                        "Built category-to-keywords mapping for {} categories",
                        categoryToKeywords.size());

            } catch (OutOfMemoryError e) {
                // CRITICAL: Handle memory errors specifically
                LOGGER.error(
                        "Out of memory building category-to-keywords mapping. Clearing data.", e);
                categoryToKeywords.clear();
            } catch (Exception e) {
                // CRITICAL: Error handling - log but don't throw
                LOGGER.error("Error building category-to-keywords mapping: {}", e.getMessage(), e);
                // CRITICAL: Clear partial data on error
                categoryToKeywords.clear();
            }
        }
    }

    /**
     * Get merchant to category mapping (for EnhancedCategoryDetectionService) CRITICAL: Returns a
     * defensive copy to prevent external modification Thread-safe, error handling, boundary checks
     */
    public Map<String, String> getMerchantToCategoryMap() {
        try {
            // CRITICAL: Thread-safe read with defensive copy
            synchronized (merchantToCategory) {
                // CRITICAL: Boundary check - prevent memory issues
                if (merchantToCategory.size() > 100_000) {
                    LOGGER.warn(
                            "Merchant map size ({}) exceeds safety limit. Returning empty map.",
                            merchantToCategory.size());
                    return new HashMap<>();
                }

                // CRITICAL: Defensive copy to prevent external modification
                return new HashMap<>(merchantToCategory);
            }
        } catch (OutOfMemoryError e) {
            LOGGER.error("Out of memory creating defensive copy of merchant-to-category map", e);
            return new HashMap<>();
        } catch (Exception e) {
            LOGGER.error("Error getting merchant-to-category map: {}", e.getMessage(), e);
            return new HashMap<>();
        }
    }

    /**
     * Get category to keywords mapping (for SemanticMatchingService) CRITICAL: Returns a defensive
     * copy to prevent external modification Thread-safe, error handling, boundary checks
     */
    public Map<String, Set<String>> getCategoryToKeywordsMap() {
        try {
            // CRITICAL: Thread-safe read with defensive copy
            synchronized (categoryToKeywords) {
                // CRITICAL: Boundary check - prevent memory issues
                if (categoryToKeywords.size() > 1000) {
                    LOGGER.warn(
                            "Category map size ({}) exceeds safety limit. Returning empty map.",
                            categoryToKeywords.size());
                    return new HashMap<>();
                }

                final Map<String, Set<String>> result = new HashMap<>();
                for (final Map.Entry<String, Set<String>> entry : categoryToKeywords.entrySet()) {
                    // CRITICAL: Null checks
                    if (entry.getKey() == null || entry.getValue() == null) {
                        LOGGER.warn("Skipping null key or value in category-to-keywords map");
                        continue;
                    }

                    // CRITICAL: Defensive copy of each set
                    result.put(entry.getKey(), new HashSet<>(entry.getValue()));
                }
                return result;
            }
        } catch (OutOfMemoryError e) {
            LOGGER.error("Out of memory creating defensive copy of category-to-keywords map", e);
            return new HashMap<>();
        } catch (Exception e) {
            LOGGER.error("Error getting category-to-keywords map: {}", e.getMessage(), e);
            return new HashMap<>();
        }
    }

    /**
     * Add a merchant/category mapping (for dynamic learning) CRITICAL: Thread-safe, error handling,
     * boundary checks, race condition prevention
     */
    public synchronized void addMerchantCategory(final String merchant, final String category) {
        try {
            // CRITICAL: Null and empty input validation
            if (merchant == null
                    || merchant.isBlank()
                    || category == null
                    || category.isBlank()) {
                LOGGER.warn("Cannot add merchant category: merchant or category is null/empty");
                return;
            }

            // CRITICAL: Boundary check - prevent very long strings
            String normalizedMerchant = merchant.toLowerCase(Locale.ROOT).trim();
            String normalizedCategory = category.toLowerCase(Locale.ROOT).trim();

            if (normalizedMerchant.length() > 1000) {
                LOGGER.warn(
                        "Merchant name too long ({} chars), truncating",
                        normalizedMerchant.length());
                normalizedMerchant = normalizedMerchant.substring(0, 1000);
            }

            if (normalizedCategory.length() > 100) {
                LOGGER.warn(
                        "Category name too long ({} chars), truncating",
                        normalizedCategory.length());
                normalizedCategory = normalizedCategory.substring(0, 100);
            }

            // CRITICAL: Boundary check - prevent memory exhaustion
            if (merchantToCategory.size() >= 100_000) {
                LOGGER.error(
                        "Merchant count ({}) exceeded safety limit. Cannot add more merchants.",
                        merchantToCategory.size());
                return;
            }

            // CRITICAL: Thread-safe update (synchronized method ensures this)
            merchantToCategory.put(normalizedMerchant, normalizedCategory);

            // CRITICAL: Update reverse mapping atomically
            categoryToKeywords
                    .computeIfAbsent(normalizedCategory, k -> new HashSet<>())
                    .add(normalizedMerchant);

            LOGGER.debug(
                    "Added merchant category: '{}' -> '{}'",
                    normalizedMerchant,
                    normalizedCategory);

        } catch (OutOfMemoryError e) {
            LOGGER.error(
                    "Out of memory adding merchant category '{}' -> '{}'", merchant, category, e);
        } catch (Exception e) {
            LOGGER.error(
                    "Error adding merchant category '{}' -> '{}': {}",
                    merchant,
                    category,
                    e.getMessage(),
                    e);
        }
    }

    /** Get category for a merchant CRITICAL: Thread-safe, error handling, boundary checks */
    public String getCategoryForMerchant(final String merchant) {
        try {
            // CRITICAL: Null and empty input validation
            if (merchant == null || merchant.isBlank()) {
                return null;
            }

            // CRITICAL: Boundary check - prevent very long strings
            String normalized = merchant.toLowerCase(Locale.ROOT).trim();
            if (normalized.length() > 1000) {
                LOGGER.warn("Merchant name too long ({} chars), truncating", normalized.length());
                normalized = normalized.substring(0, 1000);
            }

            // CRITICAL: Thread-safe read
            synchronized (merchantToCategory) {
                return merchantToCategory.get(normalized);
            }
        } catch (Exception e) {
            LOGGER.error(
                    "Error getting category for merchant '{}': {}", merchant, e.getMessage(), e);
            return null;
        }
    }

    /** Get all keywords for a category CRITICAL: Thread-safe, error handling, boundary checks */
    public Set<String> getKeywordsForCategory(final String category) {
        try {
            // CRITICAL: Null and empty input validation
            if (category == null || category.isBlank()) {
                return Collections.emptySet();
            }

            final String normalized = category.toLowerCase(Locale.ROOT).trim();

            // CRITICAL: Thread-safe read with defensive copy
            synchronized (categoryToKeywords) {
                final Set<String> keywords = categoryToKeywords.get(normalized);
                if (keywords == null) {
                    return Collections.emptySet();
                }

                // CRITICAL: Boundary check - prevent very large sets
                if (keywords.size() > 50_000) {
                    LOGGER.warn(
                            "Category '{}' has too many keywords ({}). Returning empty set.",
                            category,
                            keywords.size());
                    return Collections.emptySet();
                }

                // CRITICAL: Defensive copy
                return new HashSet<>(keywords);
            }
        } catch (OutOfMemoryError e) {
            LOGGER.error("Out of memory getting keywords for category '{}'", category, e);
            return Collections.emptySet();
        } catch (Exception e) {
            LOGGER.error(
                    "Error getting keywords for category '{}': {}", category, e.getMessage(), e);
            return Collections.emptySet();
        }
    }

    /** Get all categories CRITICAL: Thread-safe, error handling, boundary checks */
    public Set<String> getAllCategories() {
        try {
            // CRITICAL: Thread-safe read with defensive copy
            synchronized (categoryToKeywords) {
                // CRITICAL: Boundary check
                if (categoryToKeywords.size() > 1000) {
                    LOGGER.warn(
                            "Category count ({}) exceeds safety limit. Returning empty set.",
                            categoryToKeywords.size());
                    return Collections.emptySet();
                }

                return new HashSet<>(categoryToKeywords.keySet());
            }
        } catch (Exception e) {
            LOGGER.error("Error getting all categories: {}", e.getMessage(), e);
            return Collections.emptySet();
        }
    }

    /**
     * Rule-based category detection using shared merchant/category keywords. Prefers full-word
     * matches, then longer keywords, then higher frequency.
     */
    public String detectRuleBasedCategory(final String merchantName, final String description) {
        return detectRuleBasedCategory(merchantName, description, null, null);
    }

    /** Rule-based category detection with importer hints (e.g., Plaid categories). */
    public String detectRuleBasedCategory(
            final String merchantName,
            final String description,
            final String importerCategoryPrimary,
            final String importerCategoryDetailed) {
        String combined = normalizeText(merchantName) + " " + normalizeText(description);
        combined = combined.trim();
        if (combined.isEmpty() || categoryToKeywords.isEmpty()) {
            return null;
        }

        final Set<String> tokens = new HashSet<>(Arrays.asList(combined.split("\\s+")));
        final String importerPrimaryNorm = normalizeText(importerCategoryPrimary);
        final String importerDetailedNorm = normalizeText(importerCategoryDetailed);
        final java.util.List<MatchCandidate> candidates = new java.util.ArrayList<>();

        for (final Map.Entry<String, Set<String>> entry : categoryToKeywords.entrySet()) {
            final String category = entry.getKey();
            final Set<String> keywords = entry.getValue();
            if (category == null || keywords == null) {
                continue;
            }
            for (final String keyword : keywords) {
                if (keyword == null) {
                    continue;
                }
                final String normalizedKeyword = normalizeText(keyword);
                if (normalizedKeyword.length() < 3) {
                    continue;
                }
                final boolean fullWord = isFullWordMatch(normalizedKeyword, combined, tokens);
                final boolean partial =
                        !fullWord
                                && normalizedKeyword.length() >= 6
                                && combined.contains(normalizedKeyword);
                if (!fullWord && !partial) {
                    continue;
                }

                final int frequency = countOccurrences(combined, normalizedKeyword);
                double score =
                        (fullWord ? FULL_WORD_BONUS : PARTIAL_BONUS)
                                + Math.min(MAX_LENGTH_WEIGHT, normalizedKeyword.length() / 20.0)
                                + Math.min(MAX_FREQUENCY_WEIGHT, frequency / 5.0);
                if (!importerPrimaryNorm.isEmpty()
                        && importerPrimaryNorm.equals(normalizeText(category))) {
                    score += IMPORTER_PRIMARY_BONUS;
                } else if (!importerDetailedNorm.isEmpty()
                        && importerDetailedNorm.equals(normalizeText(category))) {
                    score += IMPORTER_DETAILED_BONUS;
                }

                candidates.add(
                        new MatchCandidate(
                                category, normalizedKeyword, score, fullWord, frequency));
            }
        }

        // Rank by score, then walk the list and return the first candidate
        // that survives post-validation. Previously we only kept the single
        // best match and discarded it wholesale when the payment guard
        // rejected it — which lost the second-best match (e.g. "airline fee
        // → travel") to a rejected "american express → payment" winner.
        candidates.sort((a, b) -> a.isBetterThan(b) ? -1 : b.isBetterThan(a) ? 1 : 0);

        for (final MatchCandidate candidate : candidates) {
            if (isPaymentFalsePositive(candidate, combined)) {
                continue;
            }
            return candidate.category;
        }
        return null;
    }

    /**
     * Payment-category post-validation. The merchant keyword corpus contains short entries like
     * "amex", "boa", "bofa", "capital one", "american express" that were added to catch
     * <em>payments to</em> those issuers — but those tokens ALSO appear in transactions that merely
     * <em>use</em> the card (e.g. "ESTEE LAUDER ONLINE MELVILLE Amex Offer Credit"). Without this
     * guard, any charge with "amex" in the description lands in "payment" and skews the budget into
     * a black hole. We only accept a "payment" match if the keyword is conclusive on its own or the
     * description contains a payment-action phrase alongside it.
     */
    private static boolean isPaymentFalsePositive(final MatchCandidate candidate, final String combined) {
        if (candidate == null || !PAYMENT.equalsIgnoreCase(candidate.category)) {
            return false;
        }
        if (matchedPaymentKeywordIsConclusive(candidate.keyword)) {
            return false;
        }
        return !containsPaymentActionPhrase(combined);
    }

    /**
     * These keywords are unambiguously "payment" on their own (ACH codes, autopay directives,
     * phrases like "credit card payment").
     */
    private static boolean matchedPaymentKeywordIsConclusive(final String keyword) {
        if (keyword == null) {
            return false;
        }
        return keyword.contains(PAYMENT)
                || keyword.contains("autopay")
                || keyword.contains("auto pay")
                || keyword.contains("auto-pay")
                || keyword.contains("pmt")
                || keyword.contains("bill pay")
                || keyword.contains("direct debit")
                || keyword.startsWith("ach ");
    }

    /**
     * Delegates to the single-source-of-truth {@link
     * com.budgetbuddy.service.category.PaymentPhrases} so this detector and the downstream guard in
     * {@code TransactionTypeCategoryService} stay in lockstep.
     */
    private static boolean containsPaymentActionPhrase(final String combined) {
        return com.budgetbuddy.service.category.PaymentPhrases.isPaymentish(combined);
    }

    private boolean isFullWordMatch(final String keyword, final String combined, final Set<String> tokens) {
        if (keyword.contains(" ")) {
            return combined.contains(keyword);
        }
        return tokens.contains(keyword);
    }

    private int countOccurrences(final String combined, final String keyword) {
        if (keyword.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = combined.indexOf(keyword, idx)) != -1) {
            count++;
            idx += keyword.length();
        }
        return count;
    }

    private String normalizeText(final String input) {
        if (input == null) {
            return "";
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return "";
        }
        normalized = normalized.replaceAll("[^a-z0-9\\s]", " ");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized;
    }

    private static final class MatchCandidate {
        private final String category;
        private final String keyword;
        private final double score;
        private final boolean fullWord;
        private final int frequency;

        private MatchCandidate(
                final String category, final String keyword, final double score, final boolean fullWord, final int frequency) {
            this.category = category;
            this.keyword = keyword;
            this.score = score;
            this.fullWord = fullWord;
            this.frequency = frequency;
        }

        private boolean isBetterThan(final MatchCandidate other) {
            if (Math.abs(this.score - other.score) > CLOSE_SCORE_DELTA) {
                return this.score > other.score;
            }
            if (this.fullWord != other.fullWord) {
                return this.fullWord;
            }
            if (this.frequency != other.frequency) {
                return this.frequency > other.frequency;
            }
            return this.keyword.length() > other.keyword.length();
        }
    }

    private static final double FULL_WORD_BONUS = 2.0;
    private static final double PARTIAL_BONUS = 1.0;
    private static final double MAX_LENGTH_WEIGHT = 1.0;
    private static final double MAX_FREQUENCY_WEIGHT = 1.0;
    private static final double IMPORTER_PRIMARY_BONUS = 0.5;
    private static final double IMPORTER_DETAILED_BONUS = 0.25;
    private static final double CLOSE_SCORE_DELTA = 0.15;

    /** Get total merchant count CRITICAL: Thread-safe */
    public int getMerchantCount() {
        try {
            synchronized (merchantToCategory) {
                return merchantToCategory.size();
            }
        } catch (Exception e) {
            LOGGER.error("Error getting merchant count: {}", e.getMessage(), e);
            return 0;
        }
    }

    /** Get total category count CRITICAL: Thread-safe */
    public int getCategoryCount() {
        try {
            synchronized (categoryToKeywords) {
                return categoryToKeywords.size();
            }
        } catch (Exception e) {
            LOGGER.error("Error getting category count: {}", e.getMessage(), e);
            return 0;
        }
    }
}
