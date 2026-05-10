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
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Service
public class MerchantCategoryDataService {

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
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
                            MAX_MERCHANTS);
                    return;
                }

                // ========== CHARITY (9 merchants) ==========
                merchantToCategory.put("charitable", "charity");
                merchantToCategory.put("donate", "charity");
                merchantToCategory.put("donating", "charity");
                merchantToCategory.put("donation", "charity");
                merchantToCategory.put("go fund me", "charity");
                merchantToCategory.put("gofundme", "charity");
                merchantToCategory.put("non profit", "charity");
                merchantToCategory.put("non-profit", "charity");
                merchantToCategory.put("nonprofit", "charity");
                merchantToCategory.put("charity", "charity");
                merchantToCategory.put("foundation", "charity");

                merchantCount += 9;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
                            MAX_MERCHANTS);
                    return;
                }

                // ========== DEPOSIT (20 merchants) ==========
                merchantToCategory.put("account deposit", "deposit");
                merchantToCategory.put("bank deposit", "deposit");
                merchantToCategory.put("check deposit", "deposit");
                merchantToCategory.put("checking deposit", "deposit");
                merchantToCategory.put("deposit credit", "deposit");
                merchantToCategory.put("deposit id", "deposit");
                merchantToCategory.put("deposit id number", "deposit");
                merchantToCategory.put("deposit payment", "deposit");
                merchantToCategory.put("deposit transaction", "deposit");
                merchantToCategory.put("deposit transfer", "deposit");
                merchantToCategory.put("deposited", "deposit");
                merchantToCategory.put("depositing", "deposit");
                merchantToCategory.put("mobile deposit", "deposit");
                merchantToCategory.put("direct deposit", "deposit");
                merchantToCategory.put("ach deposit", "deposit");
                merchantToCategory.put("depositor", "deposit");
                merchantToCategory.put("deposits", "deposit");
                merchantToCategory.put("electronic deposit", "deposit");
                merchantToCategory.put("fund deposit", "deposit");
                merchantToCategory.put("mobile deposit", "deposit");
                merchantToCategory.put("money deposit", "deposit");
                merchantToCategory.put("online deposit", "deposit");
                merchantToCategory.put("remote deposit", "deposit");
                merchantToCategory.put("savings deposit", "deposit");
                merchantToCategory.put("wire deposit", "deposit");

                merchantCount += 20;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
                            MAX_MERCHANTS);
                    return;
                }

                // ========== DINING (274 merchants) ==========
                merchantToCategory.put("applebee", "dining");
                merchantToCategory.put("arby", "dining");
                merchantToCategory.put("baja fresh", "dining");
                merchantToCategory.put("baker", "dining");
                merchantToCategory.put("banaras", "dining");
                merchantToCategory.put("banaras restaurant", "dining");
                merchantToCategory.put("banarasrestaurant", "dining");
                merchantToCategory.put("bbq", "dining");
                merchantToCategory.put("bbq bar", "dining");
                merchantToCategory.put("bbq cuisine", "dining");
                merchantToCategory.put("bbq restaurant", "dining");
                merchantToCategory.put("bistro", "dining");
                merchantToCategory.put("bread", "dining");
                merchantToCategory.put("breakfast", "dining");
                merchantToCategory.put("brew pub", "dining");
                merchantToCategory.put("brewery", "dining");
                merchantToCategory.put("brewing", "dining");
                merchantToCategory.put("brewpub", "dining");
                merchantToCategory.put("brunch", "dining");
                merchantToCategory.put("buffalo wild wings", "dining");
                merchantToCategory.put("burger", "dining");
                merchantToCategory.put("burger and kabob hut", "dining");
                merchantToCategory.put("burgerandkabobhut", "dining");
                merchantToCategory.put("burrito", "dining");
                merchantToCategory.put("bww", "dining");
                merchantToCategory.put("cafe", "dining");
                merchantToCategory.put("caffe", "dining");
                merchantToCategory.put("caffe nero", "dining");
                merchantToCategory.put("caffee", "dining");
                merchantToCategory.put("café", "dining");
                merchantToCategory.put("cake", "dining");
                merchantToCategory.put("canam", "dining");
                merchantToCategory.put("carl's jr", "dining");
                merchantToCategory.put("carls jr", "dining");
                merchantToCategory.put("catering", "dining");
                merchantToCategory.put("cheesecake", "dining");
                merchantToCategory.put("chili", "dining");
                merchantToCategory.put("chinese", "dining");
                merchantToCategory.put("chinese cuisine", "dining");
                merchantToCategory.put("chinese food", "dining");
                merchantToCategory.put("chinese restaurant", "dining");
                merchantToCategory.put("chipotle", "dining");
                merchantToCategory.put("coffee", "dining");
                merchantToCategory.put("coffee purchase", "dining");
                merchantToCategory.put("cucina venti", "dining");
                merchantToCategory.put("cucinaventi", "dining");
                merchantToCategory.put("culver", "dining");
                merchantToCategory.put("daeho", "dining");
                merchantToCategory.put("dairy queen", "dining");
                merchantToCategory.put("deep dive", "dining");
                merchantToCategory.put("deepdive", "dining");
                merchantToCategory.put("del taco", "dining");
                merchantToCategory.put("delivery", "dining");
                merchantToCategory.put("denny", "dining");
                merchantToCategory.put("desi dhaba", "dining");
                merchantToCategory.put("desidhaba", "dining");
                merchantToCategory.put("dhaba", "dining");
                merchantToCategory.put("dim sum", "dining");
                merchantToCategory.put("dim sum bar", "dining");
                merchantToCategory.put("dim sum cuisine", "dining");
                merchantToCategory.put("dim sum restaurant", "dining");
                merchantToCategory.put("diner", "dining");
                merchantToCategory.put("dining out", "dining");
                merchantToCategory.put("dinner", "dining");
                merchantToCategory.put("domino's", "dining");
                merchantToCategory.put("dominos", "dining");
                merchantToCategory.put("door dash", "dining");
                merchantToCategory.put("doordash", "dining");
                merchantToCategory.put("dq", "dining");
                merchantToCategory.put("dumpling", "dining");
                merchantToCategory.put("dumplings", "dining");
                merchantToCategory.put("dunkin", "dining");
                merchantToCategory.put("dunkin donuts", "dining");
                merchantToCategory.put("dunkindonuts", "dining");
                merchantToCategory.put("eat out", "dining");
                merchantToCategory.put("eatery", "dining");
                merchantToCategory.put("falafel", "dining");
                merchantToCategory.put("filipino", "dining");
                merchantToCategory.put("filipino cuisine", "dining");
                merchantToCategory.put("filipino food", "dining");
                merchantToCategory.put("filipino restaurant", "dining");
                merchantToCategory.put("five guys", "dining");
                merchantToCategory.put("food delivery", "dining");
                merchantToCategory.put("food service", "dining");
                merchantToCategory.put("french", "dining");
                merchantToCategory.put("grill", "dining");
                merchantToCategory.put("grilled", "dining");
                merchantToCategory.put("grub hub", "dining");
                merchantToCategory.put("grubhub", "dining");
                merchantToCategory.put("gyros", "dining");
                merchantToCategory.put("gyudon", "dining");
                merchantToCategory.put("gyudon bar", "dining");
                merchantToCategory.put("gyudon cuisine", "dining");
                merchantToCategory.put("gyudon restaurant", "dining");
                merchantToCategory.put("habit", "dining");
                merchantToCategory.put("hardee", "dining");
                merchantToCategory.put("hawaiian cuisine", "dining");
                merchantToCategory.put("hawaiian food", "dining");
                merchantToCategory.put("hawaiian restaurant", "dining");
                merchantToCategory.put("hoffman", "dining");
                merchantToCategory.put("ihop", "dining");
                merchantToCategory.put("in n out", "dining");
                merchantToCategory.put("in-n-out", "dining");
                merchantToCategory.put("indian", "dining");
                merchantToCategory.put("indian cuisine", "dining");
                merchantToCategory.put("indian food", "dining");
                merchantToCategory.put("indian restaurant", "dining");
                merchantToCategory.put("indian sizzler", "dining");
                merchantToCategory.put("indiansizzler", "dining");
                merchantToCategory.put("indonesian", "dining");
                merchantToCategory.put("indonesian cuisine", "dining");
                merchantToCategory.put("indonesian food", "dining");
                merchantToCategory.put("indonesian restaurant", "dining");
                merchantToCategory.put("insomnia cookie", "dining");
                merchantToCategory.put("insomnia cookies", "dining");
                merchantToCategory.put("insomniacookies", "dining");
                merchantToCategory.put("irs meals and entertainment", "dining");
                merchantToCategory.put("italian", "dining");
                merchantToCategory.put("jack in the box", "dining");
                merchantToCategory.put("japanese", "dining");
                merchantToCategory.put("japanese cuisine", "dining");
                merchantToCategory.put("japanese food", "dining");
                merchantToCategory.put("japanese restaurant", "dining");
                merchantToCategory.put("kabob hut", "dining");
                merchantToCategory.put("kabobhut", "dining");
                merchantToCategory.put("karange", "dining");
                merchantToCategory.put("katsu", "dining");
                merchantToCategory.put("kentucky fried chicken", "dining");
                merchantToCategory.put("kfc", "dining");
                merchantToCategory.put("korean", "dining");
                merchantToCategory.put("korean cuisine", "dining");
                merchantToCategory.put("korean food", "dining");
                merchantToCategory.put("korean restaurant", "dining");
                merchantToCategory.put("kyurmaen", "dining");
                merchantToCategory.put("kyurmaen ramen", "dining");
                merchantToCategory.put("laughing monk", "dining");
                merchantToCategory.put("laughing monk brewing", "dining");
                merchantToCategory.put("laughingmonk", "dining");
                merchantToCategory.put("laughingmonk ", "dining");
                merchantToCategory.put("laughingmonk brewing", "dining");
                merchantToCategory.put("little caesars", "dining");
                merchantToCategory.put("lunch", "dining");
                merchantToCategory.put("malaysian", "dining");
                merchantToCategory.put("malaysian cuisine", "dining");
                merchantToCategory.put("malaysian food", "dining");
                merchantToCategory.put("malaysian restaurant", "dining");
                merchantToCategory.put("maximilian", "dining");
                merchantToCategory.put("maxmillen", "dining");
                merchantToCategory.put("maxmillian", "dining");
                merchantToCategory.put("mcc 5812", "dining");
                merchantToCategory.put("mcc5812", "dining");
                merchantToCategory.put("mcdonald", "dining");
                merchantToCategory.put("meal", "dining");
                merchantToCategory.put("mediterranean", "dining");
                merchantToCategory.put("mediterranean cuisine", "dining");
                merchantToCategory.put("mediterranean food", "dining");
                merchantToCategory.put("mediterranean restaurant", "dining");
                merchantToCategory.put("medocino farms", "dining");
                merchantToCategory.put("medocinofarms", "dining");
                merchantToCategory.put("messina", "dining");
                merchantToCategory.put("mexican", "dining");
                merchantToCategory.put("mexican cuisine", "dining");
                merchantToCategory.put("mexican food", "dining");
                merchantToCategory.put("mexican restaurant", "dining");
                merchantToCategory.put("moe", "dining");
                merchantToCategory.put("naics 72", "dining");
                merchantToCategory.put("noodle bar", "dining");
                merchantToCategory.put("noodle cuisine", "dining");
                merchantToCategory.put("noodle restaurant", "dining");
                merchantToCategory.put("noodles", "dining");
                merchantToCategory.put("olive garden", "dining");
                merchantToCategory.put("outback", "dining");
                merchantToCategory.put("p.f. chang", "dining");
                merchantToCategory.put("panda express", "dining");
                merchantToCategory.put("panera", "dining");
                merchantToCategory.put("papa john", "dining");
                merchantToCategory.put("papa murphy", "dining");
                merchantToCategory.put("pastry", "dining");
                merchantToCategory.put("pay pams", "dining");
                merchantToCategory.put("paypams", "dining");
                merchantToCategory.put("persian", "dining");
                merchantToCategory.put("persian cuisine", "dining");
                merchantToCategory.put("persian food", "dining");
                merchantToCategory.put("persian restaurant", "dining");
                merchantToCategory.put("pf chang", "dining");
                merchantToCategory.put("philippine", "dining");
                merchantToCategory.put("philippine cuisine", "dining");
                merchantToCategory.put("philippine food", "dining");
                merchantToCategory.put("philippine restaurant", "dining");
                merchantToCategory.put("pizza", "dining");
                merchantToCategory.put("postmates", "dining");
                merchantToCategory.put("potbelly", "dining");
                merchantToCategory.put("qdoba", "dining");
                merchantToCategory.put("ramen", "dining");
                merchantToCategory.put("ramen bar", "dining");
                merchantToCategory.put("ramen cuisine", "dining");
                merchantToCategory.put("ramen restaurant", "dining");
                merchantToCategory.put("rbl", "dining");
                merchantToCategory.put("rbl*", "dining");
                merchantToCategory.put("red lobster", "dining");
                merchantToCategory.put("red robin", "dining");
                merchantToCategory.put("restaur", "dining");
                merchantToCategory.put("restaurant meal", "dining");
                merchantToCategory.put("resy", "dining");
                merchantToCategory.put("shake shack", "dining");
                merchantToCategory.put("shana thai", "dining");
                merchantToCategory.put("shanathai", "dining");
                merchantToCategory.put("sic 58", "dining");
                merchantToCategory.put("sic 72", "dining");
                merchantToCategory.put("shawarma", "dining");
                merchantToCategory.put("simply indian", "dining");
                merchantToCategory.put("simply indian restaur", "dining");
                merchantToCategory.put("simply indian restaurant", "dining");
                merchantToCategory.put("simplyindian", "dining");
                merchantToCategory.put("simplyindian restaur", "dining");
                merchantToCategory.put("simplyindian restaurant", "dining");
                merchantToCategory.put("singaporean", "dining");
                merchantToCategory.put("singaporean cuisine", "dining");
                merchantToCategory.put("singaporean food", "dining");
                merchantToCategory.put("singaporean restaurant", "dining");
                merchantToCategory.put("skills rainbow", "dining");
                merchantToCategory.put("skills rainbow room", "dining");
                merchantToCategory.put("skillsrainbow", "dining");
                merchantToCategory.put("skillsrainbow room", "dining");
                merchantToCategory.put("sonic", "dining");
                merchantToCategory.put("spanish", "dining");
                merchantToCategory.put("sq", "dining");
                merchantToCategory.put("sq*", "dining");
                merchantToCategory.put("square inc", "dining");
                merchantToCategory.put("square pos", "dining");
                merchantToCategory.put("sunny honey", "dining");
                merchantToCategory.put("sunnyhoney", "dining");
                merchantToCategory.put("starbucks", "dining");
                merchantToCategory.put("subway", "dining");
                merchantToCategory.put("supreme dumplings", "dining");
                merchantToCategory.put("supremedumplings", "dining");
                merchantToCategory.put("sushi", "dining");
                merchantToCategory.put("sushi bar", "dining");
                merchantToCategory.put("sushi cuisine", "dining");
                merchantToCategory.put("sushi restaurant", "dining");
                merchantToCategory.put("taco", "dining");
                merchantToCategory.put("tacos", "dining");
                merchantToCategory.put("take-out", "dining");
                merchantToCategory.put("takeout", "dining");
                merchantToCategory.put("takeout delivery", "dining");
                merchantToCategory.put("texas roadhouse", "dining");
                merchantToCategory.put("thai", "dining");
                merchantToCategory.put("tiffin", "dining");
                merchantToCategory.put("toast", "dining");
                merchantToCategory.put("toast pos", "dining");
                merchantToCategory.put("tonkatsu", "dining");
                merchantToCategory.put("tonkatsu restaurant", "dining");
                merchantToCategory.put("top pot", "dining");
                merchantToCategory.put("top pot donuts", "dining");
                merchantToCategory.put("toppot", "dining");
                merchantToCategory.put("toppotdonuts", "dining");
                merchantToCategory.put("tpd", "dining");
                merchantToCategory.put("tst", "dining");
                merchantToCategory.put("tst*", "dining");
                merchantToCategory.put("tutta bella", "dining");
                merchantToCategory.put("tuttabella", "dining");
                merchantToCategory.put("uber eat", "dining");
                merchantToCategory.put("uber eats", "dining");
                merchantToCategory.put("ubereats", "dining");
                merchantToCategory.put("vietnamese", "dining");
                merchantToCategory.put("vietnamese cuisine", "dining");
                merchantToCategory.put("vietnamese food", "dining");
                merchantToCategory.put("vietnamese restaurant", "dining");
                merchantToCategory.put("waffle house", "dining");
                merchantToCategory.put("wendy", "dining");
                merchantToCategory.put("whataburger", "dining");
                merchantToCategory.put("white castle", "dining");
                merchantToCategory.put("wingstop", "dining");
                merchantToCategory.put("yakiudon", "dining");
                merchantToCategory.put("yakiudon bar", "dining");
                merchantToCategory.put("yakiudon cuisine", "dining");
                merchantToCategory.put("yakiudon restaurant", "dining");
                merchantToCategory.put("zaxby's", "dining");
                merchantToCategory.put("zaxbys", "dining");

                merchantCount += 274;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
                            MAX_MERCHANTS);
                    return;
                }

                // ========== EDUCATION (146 merchants) ==========
                merchantToCategory.put("aamc", "education");
                merchantToCategory.put("academic journal", "education");
                merchantToCategory.put("act", "education");
                merchantToCategory.put("act exam", "education");
                merchantToCategory.put("act test", "education");
                merchantToCategory.put("anki", "education");
                merchantToCategory.put("anki remote", "education");
                merchantToCategory.put("ap exam", "education");
                merchantToCategory.put("bar exam", "education");
                merchantToCategory.put("barron", "education");
                merchantToCategory.put("barron's", "education");
                merchantToCategory.put("barrons", "education");
                merchantToCategory.put("bbc", "education");
                merchantToCategory.put("bbc news", "education");
                merchantToCategory.put("bellevue school district", "education");
                merchantToCategory.put("bellevueschooldistrict", "education");
                merchantToCategory.put("bloomberg", "education");
                merchantToCategory.put("bloomberg news", "education");
                merchantToCategory.put("book store", "education");
                merchantToCategory.put("books", "education");
                merchantToCategory.put("bookstore", "education");
                merchantToCategory.put("boston globe", "education");
                merchantToCategory.put("chicago tribune", "education");
                merchantToCategory.put("class", "education");
                merchantToCategory.put("clep", "education");
                merchantToCategory.put("cnn", "education");
                merchantToCategory.put("cnn news", "education");
                merchantToCategory.put("colegio", "education");
                merchantToCategory.put("college", "education");
                merchantToCategory.put("collège", "education");
                merchantToCategory.put("comlex", "education");
                merchantToCategory.put("course", "education");
                merchantToCategory.put("d j*barrons", "education");
                merchantToCategory.put("dj*barrons", "education");
                merchantToCategory.put("doctorate", "education");
                merchantToCategory.put("dorm", "education");
                merchantToCategory.put("economist", "education");
                merchantToCategory.put("education", "education");
                merchantToCategory.put("educational", "education");
                merchantToCategory.put("elementary", "education");
                merchantToCategory.put("elementary school", "education");
                merchantToCategory.put("elementaryschool", "education");
                merchantToCategory.put("escuela", "education");
                merchantToCategory.put("ets", "education");
                merchantToCategory.put("exam fee", "education");
                merchantToCategory.put("exam registration", "education");
                merchantToCategory.put("financial times", "education");
                merchantToCategory.put("ft.com", "education");
                merchantToCategory.put("gmat", "education");
                merchantToCategory.put("graduate school", "education");
                merchantToCategory.put("graduateschool", "education");
                merchantToCategory.put("graduation", "education");
                merchantToCategory.put("graudation fees", "education");
                merchantToCategory.put("gre", "education");
                merchantToCategory.put("gurukul", "education");
                merchantToCategory.put("high school", "education");
                merchantToCategory.put("highschool", "education");
                merchantToCategory.put("ib exam", "education");
                merchantToCategory.put("j barrons", "education");
                merchantToCategory.put("j*barrons", "education");
                merchantToCategory.put("journal", "education");
                merchantToCategory.put("kuttab", "education");
                merchantToCategory.put("latimes", "education");
                merchantToCategory.put("lesson", "education");
                merchantToCategory.put("library", "education");
                merchantToCategory.put("los angeles times", "education");
                merchantToCategory.put("lsat", "education");
                merchantToCategory.put("madrasa", "education");
                merchantToCategory.put("madrassa", "education");
                merchantToCategory.put("magazine", "education");
                merchantToCategory.put("mcat", "education");
                merchantToCategory.put("middle school", "education");
                merchantToCategory.put("middleschool", "education");
                merchantToCategory.put("nclex", "education");
                merchantToCategory.put("new york times", "education");
                merchantToCategory.put("newspaper", "education");
                merchantToCategory.put("ny times", "education");
                merchantToCategory.put("nytimes", "education");
                merchantToCategory.put("p.h.d", "education");
                merchantToCategory.put("pathshala", "education");
                merchantToCategory.put("pearson vue", "education");
                merchantToCategory.put("pearsonvue", "education");
                merchantToCategory.put("peer-reviewed journal", "education");
                merchantToCategory.put("ph.d", "education");
                merchantToCategory.put("ph.d.", "education");
                merchantToCategory.put("phd", "education");
                merchantToCategory.put("post graduation", "education");
                merchantToCategory.put("praxis", "education");
                merchantToCategory.put("prometric", "education");
                merchantToCategory.put("ptsa", "education");
                merchantToCategory.put("reading", "education");
                merchantToCategory.put("research journal", "education");
                merchantToCategory.put("reuters", "education");
                merchantToCategory.put("reuters news", "education");
                merchantToCategory.put("sat", "education");
                merchantToCategory.put("scholarly journal", "education");
                merchantToCategory.put("school", "education");
                merchantToCategory.put("school district", "education");
                merchantToCategory.put("school fees", "education");
                merchantToCategory.put("schooldistrict", "education");
                merchantToCategory.put("schule", "education");
                merchantToCategory.put("scientific journal", "education");
                merchantToCategory.put("secondary", "education");
                merchantToCategory.put("secondary school", "education");
                merchantToCategory.put("secondaryschool", "education");
                merchantToCategory.put("senior secondary", "education");
                merchantToCategory.put("senior secondary school", "education");
                merchantToCategory.put("seniorschool", "education");
                merchantToCategory.put("shiksha", "education");
                merchantToCategory.put("sp anki remote", "education");
                merchantToCategory.put("spankiremote", "education");
                merchantToCategory.put("test center", "education");
                merchantToCategory.put("test fee", "education");
                merchantToCategory.put("test registration", "education");
                merchantToCategory.put("text book", "education");
                merchantToCategory.put("textbook", "education");
                merchantToCategory.put("the boston globe", "education");
                merchantToCategory.put("the economist", "education");
                merchantToCategory.put("the financial times", "education");
                merchantToCategory.put("the new york times", "education");
                merchantToCategory.put("the wall street journal", "education");
                merchantToCategory.put("the washington post", "education");
                merchantToCategory.put("toefl", "education");
                merchantToCategory.put("training", "education");
                merchantToCategory.put("tuition", "education");
                merchantToCategory.put("tyee middle school", "education");
                merchantToCategory.put("tyeemiddleschool", "education");
                merchantToCategory.put("universidad", "education");
                merchantToCategory.put("university", "education");
                merchantToCategory.put("university book", "education");
                merchantToCategory.put("university book store", "education");
                merchantToCategory.put("university fees", "education");
                merchantToCategory.put("universitybook", "education");
                merchantToCategory.put("universitybookstore", "education");
                merchantToCategory.put("universität", "education");
                merchantToCategory.put("université", "education");
                merchantToCategory.put("usa today", "education");
                merchantToCategory.put("usatoday", "education");
                merchantToCategory.put("usmle", "education");
                merchantToCategory.put("vidyalaya", "education");
                merchantToCategory.put("vue", "education");
                merchantToCategory.put("wall street journal", "education");
                merchantToCategory.put("wapo", "education");
                merchantToCategory.put("washington post", "education");
                merchantToCategory.put("wsj", "education");
                merchantToCategory.put("école", "education");

                merchantCount += 146;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
                            MAX_MERCHANTS);
                    return;
                }

                // ========== ENTERTAINMENT (91 merchants) ==========
                merchantToCategory.put("alamo drafthouse", "entertainment");
                merchantToCategory.put("amazon prime", "entertainment");
                merchantToCategory.put("amc", "entertainment");
                merchantToCategory.put("amc theaters", "entertainment");
                merchantToCategory.put("apple music", "entertainment");
                merchantToCategory.put("apple tv", "entertainment");
                merchantToCategory.put("apple tv plus", "entertainment");
                merchantToCategory.put("apple tv+", "entertainment");
                merchantToCategory.put("arc light", "entertainment");
                merchantToCategory.put("arc light cinemas", "entertainment");
                merchantToCategory.put("audio", "entertainment");
                merchantToCategory.put("camping", "entertainment");
                merchantToCategory.put("cape disappointment", "entertainment");
                merchantToCategory.put("carmike", "entertainment");
                merchantToCategory.put("carmike cinemas", "entertainment");
                merchantToCategory.put("cinema", "entertainment");
                merchantToCategory.put("cinemark", "entertainment");
                merchantToCategory.put("concert", "entertainment");
                merchantToCategory.put("conundroom", "entertainment");
                merchantToCategory.put("conundroom.us", "entertainment");
                merchantToCategory.put("crunchyroll", "entertainment");
                merchantToCategory.put("discovery plus", "entertainment");
                merchantToCategory.put("disney", "entertainment");
                merchantToCategory.put("disney plus", "entertainment");
                merchantToCategory.put("disney+", "entertainment");
                merchantToCategory.put("entertainment service", "entertainment");
                merchantToCategory.put("escape room", "entertainment");
                merchantToCategory.put("escaperoom", "entertainment");
                merchantToCategory.put("event", "entertainment");
                merchantToCategory.put("film", "entertainment");
                merchantToCategory.put("fox sports", "entertainment");
                merchantToCategory.put("fubo", "entertainment");
                merchantToCategory.put("funimation", "entertainment");
                merchantToCategory.put("game", "entertainment");
                merchantToCategory.put("harkins", "entertainment");
                merchantToCategory.put("hbo", "entertainment");
                merchantToCategory.put("hbo max", "entertainment");
                merchantToCategory.put("hlu", "entertainment");
                merchantToCategory.put("hulu", "entertainment");
                merchantToCategory.put("hulu plus", "entertainment");
                merchantToCategory.put("huluplus", "entertainment");
                merchantToCategory.put("imax", "entertainment");
                merchantToCategory.put("marcus theaters", "entertainment");
                merchantToCategory.put("max", "entertainment");
                merchantToCategory.put("mcc 7832", "entertainment");
                merchantToCategory.put("mcc 7922", "entertainment");
                merchantToCategory.put("mcc7832", "entertainment");
                merchantToCategory.put("mcc7922", "entertainment");
                merchantToCategory.put("media", "entertainment");
                merchantToCategory.put("movie", "entertainment");
                merchantToCategory.put("movie theater", "entertainment");
                merchantToCategory.put("movietheater", "entertainment");
                merchantToCategory.put("music", "entertainment");
                merchantToCategory.put("naics 71", "entertainment");
                merchantToCategory.put("nbc", "entertainment");
                merchantToCategory.put("nbc peacock", "entertainment");
                merchantToCategory.put("netflix", "entertainment");
                merchantToCategory.put("paramount plus", "entertainment");
                merchantToCategory.put("paramount+", "entertainment");
                merchantToCategory.put("peacock", "entertainment");
                merchantToCategory.put("prime video", "entertainment");
                merchantToCategory.put("recreation.gov", "entertainment");
                merchantToCategory.put("regal", "entertainment");
                merchantToCategory.put("regal cinemas", "entertainment");
                merchantToCategory.put("sea world", "entertainment");
                merchantToCategory.put("seaworld", "entertainment");
                merchantToCategory.put("show", "entertainment");
                merchantToCategory.put("showtime", "entertainment");
                merchantToCategory.put("sic 78", "entertainment");
                merchantToCategory.put("sic 79", "entertainment");
                merchantToCategory.put("sling tv", "entertainment");
                merchantToCategory.put("sports", "entertainment");
                merchantToCategory.put("spotify", "entertainment");
                merchantToCategory.put("starz", "entertainment");
                merchantToCategory.put("state fair", "entertainment");
                merchantToCategory.put("statefair", "entertainment");
                merchantToCategory.put("theater", "entertainment");
                merchantToCategory.put("theatre", "entertainment");
                merchantToCategory.put("ticket", "entertainment");
                merchantToCategory.put("top golf", "entertainment");
                merchantToCategory.put("topgolf", "entertainment");
                merchantToCategory.put("universal", "entertainment");
                merchantToCategory.put("universal studio", "entertainment");
                merchantToCategory.put("universalstudio", "entertainment");
                merchantToCategory.put("video", "entertainment");
                merchantToCategory.put("xfinity tv", "entertainment");
                merchantToCategory.put("xfinitytv", "entertainment");
                merchantToCategory.put("youtube", "entertainment");
                merchantToCategory.put("youtube premium", "entertainment");
                merchantToCategory.put("youtube tv", "entertainment");
                merchantToCategory.put("ytmusic", "entertainment");

                merchantCount += 91;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
                            MAX_MERCHANTS);
                    return;
                }

                // ========== GROCERIES (164 merchants) ==========
                merchantToCategory.put("99 Ranch Market", "groceries");
                merchantToCategory.put("Acme", "groceries");
                merchantToCategory.put("Apna Bazar", "groceries");
                merchantToCategory.put("Asian Family Market", "groceries");
                merchantToCategory.put("Baker's", "groceries");
                merchantToCategory.put("Balducci's Food Lovers Market", "groceries");
                merchantToCategory.put("Big John’s PFI", "groceries");
                merchantToCategory.put("Big Y", "groceries");
                merchantToCategory.put("CU", "groceries");
                merchantToCategory.put("Carrs", "groceries");
                merchantToCategory.put("City Market", "groceries");
                merchantToCategory.put("Cumberland Farms", "groceries");
                merchantToCategory.put("Desi basket", "groceries");
                merchantToCategory.put("Dillon's", "groceries");
                merchantToCategory.put("Dillons", "groceries");
                merchantToCategory.put("Dollar General", "groceries");
                merchantToCategory.put("Dollar Tree", "groceries");
                merchantToCategory.put("Dollarama", "groceries");
                merchantToCategory.put("Erewhon Market", "groceries");
                merchantToCategory.put("Erwan's Market", "groceries");
                merchantToCategory.put("Family Dollar", "groceries");
                merchantToCategory.put("Five Below", "groceries");
                merchantToCategory.put("Food 4 Less", "groceries");
                merchantToCategory.put("Foods Co.", "groceries");
                merchantToCategory.put("Fou Lee Market & Deli", "groceries");
                merchantToCategory.put("Gerbes", "groceries");
                merchantToCategory.put("Giant Food", "groceries");
                merchantToCategory.put("Glatt Mart", "groceries");
                merchantToCategory.put("Gourmet Garage", "groceries");
                merchantToCategory.put("H Mart", "groceries");
                merchantToCategory.put("Haggen", "groceries");
                merchantToCategory.put("Hannaford", "groceries");
                merchantToCategory.put("Holyland Market", "groceries");
                merchantToCategory.put("India Cash and Carry", "groceries");
                merchantToCategory.put("India Metro Hypermarket", "groceries");
                merchantToCategory.put("India Supermarket", "groceries");
                merchantToCategory.put("Indian CO", "groceries");
                merchantToCategory.put("International Deli", "groceries");
                merchantToCategory.put("Jagalchi", "groceries");
                merchantToCategory.put("Jay C Food Store", "groceries");
                merchantToCategory.put("Jewel-Osco", "groceries");
                merchantToCategory.put("Kings Food Markets", "groceries");
                merchantToCategory.put("LA Superior", "groceries");
                merchantToCategory.put("Mariano's", "groceries");
                merchantToCategory.put("Market of Choice", "groceries");
                merchantToCategory.put("Martin's Food Markets", "groceries");
                merchantToCategory.put("Mega Mart", "groceries");
                merchantToCategory.put("Metro Market", "groceries");
                merchantToCategory.put("Mitsuwa Marketplace", "groceries");
                merchantToCategory.put("Online Specialty Stores", "groceries");
                merchantToCategory.put("Oskoo Persian & Mediterranean market", "groceries");
                merchantToCategory.put("Patel Brothers", "groceries");
                merchantToCategory.put("Pavilions", "groceries");
                merchantToCategory.put("Pay-Less Super Markets", "groceries");
                merchantToCategory.put("Pick 'n Save", "groceries");
                merchantToCategory.put("Randalls", "groceries");
                merchantToCategory.put("Rose Persian Market & Halal Butchery", "groceries");
                merchantToCategory.put("S-Mart", "groceries");
                merchantToCategory.put("Shaw's", "groceries");
                merchantToCategory.put("Shufersal ", "groceries");
                merchantToCategory.put("Smith's Food and Drug", "groceries");
                merchantToCategory.put("Sprouts Market", "groceries");
                merchantToCategory.put("Star Market", "groceries");
                merchantToCategory.put("T&T Supermarket", "groceries");
                merchantToCategory.put("The GIANT Company", "groceries");
                merchantToCategory.put("The Souk", "groceries");
                merchantToCategory.put("Thrive Market", "groceries");
                merchantToCategory.put("Tom Thumb", "groceries");
                merchantToCategory.put("Tops Friendly Market", "groceries");
                merchantToCategory.put(
                        "United Supermarkets (including Market Street, Amigos, and United Express formats)",
                        "groceries");
                merchantToCategory.put("Vons", "groceries");
                merchantToCategory.put("Weee", "groceries");
                merchantToCategory.put("WholeFoods", "groceries");
                merchantToCategory.put("Zion Market", "groceries");
                merchantToCategory.put("aldi", "groceries");
                merchantToCategory.put("amazon fresh", "groceries");
                merchantToCategory.put("bj's wholesale club", "groceries");
                merchantToCategory.put("chef store", "groceries");
                merchantToCategory.put("costco", "groceries");
                merchantToCategory.put("costco warehouse", "groceries");
                merchantToCategory.put("costco whse", "groceries");
                merchantToCategory.put("costcowarehouse", "groceries");
                merchantToCategory.put("costcowhse", "groceries");
                merchantToCategory.put("dk market", "groceries");
                merchantToCategory.put("food", "groceries");
                merchantToCategory.put("food center", "groceries");
                merchantToCategory.put("food lion", "groceries");
                merchantToCategory.put("food market", "groceries");
                merchantToCategory.put("food mart", "groceries");
                merchantToCategory.put("food shop", "groceries");
                merchantToCategory.put("food shopping", "groceries");
                merchantToCategory.put("food store", "groceries");
                merchantToCategory.put("foodmart", "groceries");
                merchantToCategory.put("fred meyer", "groceries");
                merchantToCategory.put("fredmeyer", "groceries");
                merchantToCategory.put("fresh direct", "groceries");
                merchantToCategory.put("fresh food", "groceries");
                merchantToCategory.put("freshdirect", "groceries");
                merchantToCategory.put("giant", "groceries");
                merchantToCategory.put("giant eagle", "groceries");
                merchantToCategory.put("groceries", "groceries");
                merchantToCategory.put("grocery", "groceries");
                merchantToCategory.put("grocery center", "groceries");
                merchantToCategory.put("grocery market", "groceries");
                merchantToCategory.put("grocery shopping", "groceries");
                merchantToCategory.put("grocery store", "groceries");
                merchantToCategory.put("h-e-b", "groceries");
                merchantToCategory.put("halal", "groceries");
                merchantToCategory.put("harris teeter", "groceries");
                merchantToCategory.put("heb", "groceries");
                merchantToCategory.put("hy-vee", "groceries");
                merchantToCategory.put("hypermarket", "groceries");
                merchantToCategory.put("hyvee", "groceries");
                merchantToCategory.put("imperfect foods", "groceries");
                merchantToCategory.put("imperfectfoods", "groceries");
                merchantToCategory.put("instacart", "groceries");
                merchantToCategory.put("king soopers", "groceries");
                merchantToCategory.put("kroger", "groceries");
                merchantToCategory.put("lidl", "groceries");
                merchantToCategory.put("market", "groceries");
                merchantToCategory.put("mayuri", "groceries");
                merchantToCategory.put("mcc 5411", "groceries");
                merchantToCategory.put("mcc5411", "groceries");
                merchantToCategory.put("meet fresh", "groceries");
                merchantToCategory.put("meetfresh", "groceries");
                merchantToCategory.put("meijer", "groceries");
                merchantToCategory.put("naics 11", "groceries");
                merchantToCategory.put("pantry", "groceries");
                merchantToCategory.put("pcc", "groceries");
                merchantToCategory.put("produce", "groceries");
                merchantToCategory.put("publix", "groceries");
                merchantToCategory.put("qfc", "groceries");
                merchantToCategory.put("ralph's", "groceries");
                merchantToCategory.put("ralphs", "groceries");
                merchantToCategory.put("safeway", "groceries");
                merchantToCategory.put("sam's club", "groceries");
                merchantToCategory.put("shipt", "groceries");
                merchantToCategory.put("shoprite", "groceries");
                merchantToCategory.put("sic 01", "groceries");
                merchantToCategory.put("sic 02", "groceries");
                merchantToCategory.put("sic 07", "groceries");
                merchantToCategory.put("sic 20", "groceries");
                merchantToCategory.put("sic 54", "groceries");
                merchantToCategory.put("sprouts", "groceries");
                merchantToCategory.put("sprouts farmers market", "groceries");
                merchantToCategory.put("stop & shop", "groceries");
                merchantToCategory.put("stop and shop", "groceries");
                merchantToCategory.put("store", "groceries");
                // Sunny Honey is a SQ*-prefixed Pike Place vendor (prepared-
                // foods stall, not a grocer); mapped to "dining" in the
                // DINING block so SQ* restaurant-POS transactions resolve
                // cleanly.
                merchantToCategory.put("super market", "groceries");
                merchantToCategory.put("supermarket shopping", "groceries");
                merchantToCategory.put("superstore", "groceries");
                merchantToCategory.put("target", "groceries");
                merchantToCategory.put("town & country", "groceries");
                merchantToCategory.put("town and country", "groceries");
                merchantToCategory.put("town&country", "groceries");
                merchantToCategory.put("trader joe", "groceries");
                merchantToCategory.put("walmart", "groceries");
                merchantToCategory.put("wegmans", "groceries");
                merchantToCategory.put("whole foods", "groceries");
                merchantToCategory.put("winco", "groceries");
                merchantToCategory.put("wmt", "groceries");
                merchantToCategory.put("iShopIndian", "groceries");

                merchantCount += 164;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
                            MAX_MERCHANTS);
                    return;
                }

                // ========== HEALTH (130 merchants) ==========
                merchantToCategory.put("24 hour fitness", "health");
                merchantToCategory.put("24-hour fitness", "health");
                merchantToCategory.put("24hour fitness", "health");
                merchantToCategory.put("24hr fitness", "health");
                merchantToCategory.put("anytime fitness", "health");
                merchantToCategory.put("athletic club", "health");
                merchantToCategory.put("athletic facility", "health");
                merchantToCategory.put("athleticclub", "health");
                merchantToCategory.put("badminton", "health");
                merchantToCategory.put("badminton club", "health");
                merchantToCategory.put("badmintonclub", "health");
                merchantToCategory.put("baseball", "health");
                merchantToCategory.put("basketball", "health");
                merchantToCategory.put("beauty parlor", "health");
                merchantToCategory.put("beauty salon", "health");
                merchantToCategory.put("beauty studio", "health");
                merchantToCategory.put("beautyparlor", "health");
                merchantToCategory.put("beautysalon", "health");
                merchantToCategory.put("beautystudio", "health");
                merchantToCategory.put("body waxing", "health");
                merchantToCategory.put("bodywaxing", "health");
                merchantToCategory.put("classpass", "health");
                // Cosmetics / makeup is discretionary shopping, not health —
                // users budget it as beauty spend, not medical. Previously
                // mis-mapped to "health" which inflated healthcare
                // aggregates and under-counted shopping.
                merchantToCategory.put("cosmetic shop", "shopping");
                merchantToCategory.put("cosmetic store", "shopping");
                merchantToCategory.put("cosmetics", "shopping");
                merchantToCategory.put("cosmeticshop", "shopping");
                merchantToCategory.put("cosmeticstore", "shopping");
                merchantToCategory.put("crossfit", "health");
                merchantToCategory.put("crossfit gym", "health");
                merchantToCategory.put("crunch fitness", "health");
                merchantToCategory.put("cycling", "health");
                merchantToCategory.put("cycling club", "health");
                merchantToCategory.put("dance club", "health");
                merchantToCategory.put("dance studio", "health");
                merchantToCategory.put("equinox", "health");
                merchantToCategory.put("exercise", "health");
                merchantToCategory.put("fitness", "health");
                merchantToCategory.put("fitness center", "health");
                merchantToCategory.put("fitness club", "health");
                merchantToCategory.put("fitness facility", "health");
                merchantToCategory.put("fitnesscenter", "health");
                merchantToCategory.put("fitnessclub", "health");
                merchantToCategory.put("football", "health");
                merchantToCategory.put("gold's gym", "health");
                merchantToCategory.put("golds gym", "health");
                merchantToCategory.put("golf", "health");
                merchantToCategory.put("great clips", "health");
                merchantToCategory.put("greatclips", "health");
                merchantToCategory.put("gym", "health");
                merchantToCategory.put("hair color", "health");
                merchantToCategory.put("hair cut", "health");
                merchantToCategory.put("hair cuts", "health");
                merchantToCategory.put("hair salon", "health");
                merchantToCategory.put("haircolor", "health");
                merchantToCategory.put("haircut", "health");
                merchantToCategory.put("haircuts", "health");
                merchantToCategory.put("hairsalon", "health");
                merchantToCategory.put("health center", "health");
                merchantToCategory.put("health club", "health");
                merchantToCategory.put("healthclub", "health");
                merchantToCategory.put("judo", "health");
                merchantToCategory.put("karate", "health");
                merchantToCategory.put("la fitness", "health");
                merchantToCategory.put("lifetime fitness", "health");
                merchantToCategory.put("lucky hair salin", "health");
                merchantToCategory.put("lucky hair salon", "health");
                merchantToCategory.put("luckyhair", "health");
                merchantToCategory.put("luckyhairsalin", "health");
                merchantToCategory.put("make up", "health");
                merchantToCategory.put("makeup", "health");
                merchantToCategory.put("makeup store", "health");
                merchantToCategory.put("makeupstore", "health");
                merchantToCategory.put("manicure", "health");
                merchantToCategory.put("martial arts", "health");
                merchantToCategory.put("massage", "health");
                merchantToCategory.put("massages", "health");
                merchantToCategory.put("mindbody", "health");
                merchantToCategory.put("nail", "health");
                merchantToCategory.put("nail salon", "health");
                merchantToCategory.put("nails", "health");
                merchantToCategory.put("nailsalon", "health");
                merchantToCategory.put("new york cosmetic", "health");
                merchantToCategory.put("new york cosmetic store", "health");
                merchantToCategory.put("newyorkcosmeticstore", "health");
                merchantToCategory.put("ny cosmetic", "health");
                merchantToCategory.put("ny cosmetic store", "health");
                merchantToCategory.put("nycosmeticstore", "health");
                merchantToCategory.put("orange theory", "health");
                merchantToCategory.put("orangetheory", "health");
                merchantToCategory.put("pedicure", "health");
                merchantToCategory.put("personal trainer", "health");
                merchantToCategory.put("pilates", "health");
                merchantToCategory.put("pilates studio", "health");
                merchantToCategory.put("planet fitness", "health");
                merchantToCategory.put("pro club", "health");
                merchantToCategory.put("proclub", "health");
                merchantToCategory.put("recreation center", "health");
                merchantToCategory.put("running club", "health");
                merchantToCategory.put("salon", "health");
                merchantToCategory.put("seattle badminton club", "health");
                merchantToCategory.put("seattlebadmintonclub", "health");
                merchantToCategory.put("mini mountain", "health");
                merchantToCategory.put("minimountain", "health");
                merchantToCategory.put("ski", "health");
                merchantToCategory.put("ski rental", "health");
                merchantToCategory.put("ski resort", "health");
                merchantToCategory.put("ski school", "health");
                merchantToCategory.put("skin", "health");
                merchantToCategory.put("skin care", "health");
                merchantToCategory.put("skincare", "health");
                merchantToCategory.put("soccer", "health");
                merchantToCategory.put("spa", "health");
                merchantToCategory.put("sports club", "health");
                merchantToCategory.put("sports facility", "health");
                merchantToCategory.put("sportsclub", "health");
                merchantToCategory.put("stop 4 nails", "health");
                merchantToCategory.put("stop four nails", "health");
                merchantToCategory.put("stop4nails", "health");
                merchantToCategory.put("stopfournails", "health");
                merchantToCategory.put("summit at snoqualmie", "health");
                merchantToCategory.put("super cuts", "health");
                merchantToCategory.put("supercuts", "health");
                merchantToCategory.put("swimming", "health");
                merchantToCategory.put("swimming club", "health");
                merchantToCategory.put("taekwondo", "health");
                merchantToCategory.put("tennis", "health");
                merchantToCategory.put("tennis club", "health");
                merchantToCategory.put("toes", "health");
                merchantToCategory.put("waxing", "health");
                merchantToCategory.put("wellness center", "health");
                merchantToCategory.put("workout", "health");
                merchantToCategory.put("ymca", "health");
                merchantToCategory.put("yoga", "health");
                merchantToCategory.put("yoga studio", "health");

                merchantCount += 130;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
                            MAX_MERCHANTS);
                    return;
                }

                // ========== HEALTHCARE (74 merchants) ==========
                merchantToCategory.put("aetna", "healthcare");
                merchantToCategory.put("anthem", "healthcare");
                merchantToCategory.put("anthem blue cross", "healthcare");
                merchantToCategory.put("bcbs", "healthcare");
                merchantToCategory.put("blue cross", "healthcare");
                merchantToCategory.put("blue cross blue shield", "healthcare");
                merchantToCategory.put("chiropractor", "healthcare");
                merchantToCategory.put("cigna", "healthcare");
                merchantToCategory.put("clinic", "healthcare");
                merchantToCategory.put("costco pharmacy", "healthcare");
                merchantToCategory.put("cvs", "healthcare");
                merchantToCategory.put("cvs pharmacy", "healthcare");
                merchantToCategory.put("cvs pharmacy store", "healthcare");
                merchantToCategory.put("cvs pharmacy store and clinic", "healthcare");
                merchantToCategory.put("dental", "healthcare");
                merchantToCategory.put("dentist", "healthcare");
                merchantToCategory.put("drugstore", "healthcare");
                merchantToCategory.put("emergency care", "healthcare");
                merchantToCategory.put("eye care", "healthcare");
                merchantToCategory.put("eyeglasses", "healthcare");
                merchantToCategory.put("health", "healthcare");
                merchantToCategory.put("health care", "healthcare");
                merchantToCategory.put("health service", "healthcare");
                merchantToCategory.put("healthcare", "healthcare");
                merchantToCategory.put("humana", "healthcare");
                merchantToCategory.put("kaiser", "healthcare");
                merchantToCategory.put("kaiser permanente", "healthcare");
                merchantToCategory.put("kroger pharmacy", "healthcare");
                merchantToCategory.put("mcc 5912", "healthcare");
                merchantToCategory.put("mcc 8011", "healthcare");
                merchantToCategory.put("mcc 8021", "healthcare");
                merchantToCategory.put("mcc 8041", "healthcare");
                merchantToCategory.put("mcc 8042", "healthcare");
                merchantToCategory.put("mcc 8043", "healthcare");
                merchantToCategory.put("mcc 8062", "healthcare");
                merchantToCategory.put("mcc5912", "healthcare");
                merchantToCategory.put("mcc8011", "healthcare");
                merchantToCategory.put("mcc8021", "healthcare");
                merchantToCategory.put("mcc8041", "healthcare");
                merchantToCategory.put("mcc8042", "healthcare");
                merchantToCategory.put("mcc8043", "healthcare");
                merchantToCategory.put("mcc8062", "healthcare");
                merchantToCategory.put("medical care", "healthcare");
                merchantToCategory.put("medication", "healthcare");
                merchantToCategory.put("medicine", "healthcare");
                merchantToCategory.put("naics 62", "healthcare");
                merchantToCategory.put("optical goods", "healthcare");
                merchantToCategory.put("optician", "healthcare");
                merchantToCategory.put("optometrist", "healthcare");
                merchantToCategory.put("optometry", "healthcare");
                merchantToCategory.put("orthodontist", "healthcare");
                merchantToCategory.put("overlake", "healthcare");
                merchantToCategory.put("physician", "healthcare");
                merchantToCategory.put("premera", "healthcare");
                merchantToCategory.put("prescription", "healthcare");
                merchantToCategory.put("providence", "healthcare");
                merchantToCategory.put("rite aid", "healthcare");
                merchantToCategory.put("riteaid", "healthcare");
                merchantToCategory.put("safeway pharmacy", "healthcare");
                merchantToCategory.put("seattle cancer care alliance", "healthcare");
                merchantToCategory.put("seattle children's", "healthcare");
                merchantToCategory.put("seattle genetics", "healthcare");
                merchantToCategory.put("sic 28", "healthcare");
                merchantToCategory.put("sic 80", "healthcare");
                merchantToCategory.put("swedish hospital", "healthcare");
                merchantToCategory.put("target pharmacy", "healthcare");
                merchantToCategory.put("united healthcare", "healthcare");
                merchantToCategory.put("unitedhealthcare", "healthcare");
                merchantToCategory.put("urgent care", "healthcare");
                merchantToCategory.put("virginia mason", "healthcare");
                merchantToCategory.put("vision", "healthcare");
                merchantToCategory.put("walgreens", "healthcare");
                merchantToCategory.put("walgreens pharmacy", "healthcare");
                merchantToCategory.put("walmart pharmacy", "healthcare");

                merchantCount += 74;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
                            MAX_MERCHANTS);
                    return;
                }

                // ========== HOME IMPROVEMENT (33 merchants) ==========
                merchantToCategory.put("ace", "home improvement");
                merchantToCategory.put("ace hardware", "home improvement");
                merchantToCategory.put("behr", "home improvement");
                merchantToCategory.put("benjamin moore", "home improvement");
                merchantToCategory.put("harbor freight", "home improvement");
                merchantToCategory.put("harbor freight tools", "home improvement");
                merchantToCategory.put("home depot", "home improvement");
                merchantToCategory.put("homedepot", "home improvement");
                merchantToCategory.put("irs repairs and maintenance", "home improvement");
                merchantToCategory.put("lowes", "home improvement");
                merchantToCategory.put("lowes home improvement", "home improvement");
                merchantToCategory.put("mcc 1520", "home improvement");
                merchantToCategory.put("mcc 5211", "home improvement");
                merchantToCategory.put("mcc 5231", "home improvement");
                merchantToCategory.put("mcc1520", "home improvement");
                merchantToCategory.put("mcc5211", "home improvement");
                merchantToCategory.put("mcc5231", "home improvement");
                merchantToCategory.put("menards", "home improvement");
                merchantToCategory.put("naics 23", "home improvement");
                merchantToCategory.put("northern tool", "home improvement");
                merchantToCategory.put("northern tool & equipment", "home improvement");
                merchantToCategory.put("ppg", "home improvement");
                merchantToCategory.put("ppg paints", "home improvement");
                merchantToCategory.put("sherwin williams", "home improvement");
                merchantToCategory.put("sic 15", "home improvement");
                merchantToCategory.put("sic 17", "home improvement");
                merchantToCategory.put("sic 24", "home improvement");
                merchantToCategory.put("sic 25", "home improvement");
                merchantToCategory.put("tractor supply", "home improvement");
                merchantToCategory.put("tractor supply company", "home improvement");
                merchantToCategory.put("true value", "home improvement");
                merchantToCategory.put("truevalue", "home improvement");
                merchantToCategory.put("valspar", "home improvement");

                merchantCount += 33;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
                            MAX_MERCHANTS);
                    return;
                }

                // ========== INCOME (177 merchants) ==========
                merchantToCategory.put("ach 21", "income");
                merchantToCategory.put("ach 31", "income");
                merchantToCategory.put("ach 41", "income");
                merchantToCategory.put("ach credit", "income");
                merchantToCategory.put("ach deposit", "income");
                merchantToCategory.put("adp", "income");
                merchantToCategory.put("adp ez labor", "income");
                merchantToCategory.put("adp mobile", "income");
                merchantToCategory.put("adp portal", "income");
                merchantToCategory.put("adp run", "income");
                merchantToCategory.put("adp totalsource", "income");
                merchantToCategory.put("adp vantage", "income");
                merchantToCategory.put("adp workforce now", "income");
                merchantToCategory.put("alphabet inc", "income");
                merchantToCategory.put("amazon services", "income");
                merchantToCategory.put("apple inc", "income");
                merchantToCategory.put("automatic data processing", "income");
                merchantToCategory.put("bacs direct credit", "income");
                merchantToCategory.put("bamboohr", "income");
                merchantToCategory.put("berkshire hathaway", "income");
                merchantToCategory.put("bmw", "income");
                merchantToCategory.put("boeing", "income");
                merchantToCategory.put("cardinal health", "income");
                merchantToCategory.put("ceridian", "income");
                merchantToCategory.put("ceridian dayforce", "income");
                merchantToCategory.put("chevron corporation", "income");
                merchantToCategory.put("citigroup", "income");
                merchantToCategory.put("coca cola", "income");
                merchantToCategory.put("compensation", "income");
                merchantToCategory.put("costco wholesale company", "income");
                merchantToCategory.put("costco wholesale corporation", "income");
                merchantToCategory.put("costco wholesale inc", "income");
                merchantToCategory.put("cvs health", "income");
                merchantToCategory.put("daimler", "income");
                merchantToCategory.put("defense finance", "income");
                merchantToCategory.put("defense finance and accounting service", "income");
                merchantToCategory.put("department of veterans affairs", "income");
                merchantToCategory.put("dfas", "income");
                merchantToCategory.put("direct deposit", "income");
                merchantToCategory.put("directdeposit", "income");
                merchantToCategory.put("earnings", "income");
                merchantToCategory.put("exxon mobil", "income");
                merchantToCategory.put("facebook inc", "income");
                merchantToCategory.put("federal tax refund", "income");
                merchantToCategory.put("federal unemployment", "income");
                merchantToCategory.put("fiat chrysler", "income");
                merchantToCategory.put("ford", "income");
                merchantToCategory.put("ford motor", "income");
                merchantToCategory.put("gaap accounts receivable", "income");
                merchantToCategory.put("gaap dividend revenue", "income");
                merchantToCategory.put("gaap interest revenue", "income");
                merchantToCategory.put("gaap revenue", "income");
                merchantToCategory.put("gaap sales revenue", "income");
                merchantToCategory.put("gaap service revenue", "income");
                merchantToCategory.put("ge", "income");
                merchantToCategory.put("general electric", "income");
                merchantToCategory.put("general motors", "income");
                merchantToCategory.put("gm", "income");
                merchantToCategory.put("google llc", "income");
                merchantToCategory.put("gusto", "income");
                merchantToCategory.put("heartland payroll", "income");
                merchantToCategory.put("home depot corporation", "income");
                merchantToCategory.put("home depot inc", "income");
                merchantToCategory.put("honda motor", "income");
                merchantToCategory.put("internal revenue service", "income");
                merchantToCategory.put("intuit payroll", "income");
                merchantToCategory.put("irs", "income");
                merchantToCategory.put("irs 1099", "income");
                merchantToCategory.put("irs 1099-b", "income");
                merchantToCategory.put("irs 1099-div", "income");
                merchantToCategory.put("irs 1099-g", "income");
                merchantToCategory.put("irs 1099-int", "income");
                merchantToCategory.put("irs 1099-k", "income");
                merchantToCategory.put("irs 1099-misc", "income");
                merchantToCategory.put("irs 1099-r", "income");
                merchantToCategory.put("irs 1099-s", "income");
                merchantToCategory.put("irs refund", "income");
                merchantToCategory.put("irs schedule c", "income");
                merchantToCategory.put("irs schedule e", "income");
                merchantToCategory.put("irs schedule f", "income");
                merchantToCategory.put("irs w-2", "income");
                merchantToCategory.put("irs w2", "income");
                merchantToCategory.put("irs wages", "income");
                merchantToCategory.put("isolved", "income");
                merchantToCategory.put("isolved hcm", "income");
                merchantToCategory.put("johnson & johnson", "income");
                merchantToCategory.put("jpmorgan chase & co", "income");
                merchantToCategory.put("justworks", "income");
                merchantToCategory.put("kronos", "income");
                merchantToCategory.put("lockheed martin", "income");
                merchantToCategory.put("lowes companies", "income");
                merchantToCategory.put("lowes inc", "income");
                merchantToCategory.put("mcdonalds corporation", "income");
                merchantToCategory.put("mckesson", "income");
                merchantToCategory.put("mercedes benz", "income");
                merchantToCategory.put("meta platforms", "income");
                merchantToCategory.put("microsoft corp", "income");
                merchantToCategory.put("microsoft corporation", "income");
                merchantToCategory.put("military pay", "income");
                merchantToCategory.put("military payroll", "income");
                merchantToCategory.put("nike", "income");
                merchantToCategory.put("nissan motor", "income");
                merchantToCategory.put("northrop grumman", "income");
                merchantToCategory.put("onpay", "income");
                merchantToCategory.put("patriot software", "income");
                merchantToCategory.put("paychex", "income");
                merchantToCategory.put("paychex flex", "income");
                merchantToCategory.put("paychex inc", "income");
                merchantToCategory.put("paychex mobile", "income");
                merchantToCategory.put("paychex portal", "income");
                merchantToCategory.put("paycom", "income");
                merchantToCategory.put("paycom software", "income");
                merchantToCategory.put("paycor", "income");
                merchantToCategory.put("paylocity", "income");
                merchantToCategory.put("payroll ach", "income");
                merchantToCategory.put("payroll ach credit", "income");
                merchantToCategory.put("payroll ach deposit", "income");
                merchantToCategory.put("payroll credit", "income");
                merchantToCategory.put("payroll deposit", "income");
                merchantToCategory.put("payroll direct deposit", "income");
                merchantToCategory.put("payroll payment", "income");
                merchantToCategory.put("payroll plus", "income");
                merchantToCategory.put("payroll plus solutions", "income");
                merchantToCategory.put("payroll transfer", "income");
                merchantToCategory.put("pepsico", "income");
                merchantToCategory.put("pfizer", "income");
                merchantToCategory.put("pg", "income");
                merchantToCategory.put("ppd", "income");
                merchantToCategory.put("ppd credit", "income");
                merchantToCategory.put("ppd entry", "income");
                merchantToCategory.put("procter & gamble", "income");
                merchantToCategory.put("quickbooks payroll", "income");
                merchantToCategory.put("raytheon", "income");
                merchantToCategory.put("remuneration", "income");
                merchantToCategory.put("salary deposit", "income");
                merchantToCategory.put("salary direct deposit", "income");
                merchantToCategory.put("social security", "income");
                merchantToCategory.put("social security administration", "income");
                merchantToCategory.put("square payroll", "income");
                merchantToCategory.put("ssa", "income");
                merchantToCategory.put("starbucks corporation", "income");
                merchantToCategory.put("state tax refund", "income");
                merchantToCategory.put("state unemployment", "income");
                merchantToCategory.put("stellantis", "income");
                merchantToCategory.put("stipend", "income");
                merchantToCategory.put("sure payroll", "income");
                merchantToCategory.put("surepayroll", "income");
                merchantToCategory.put("target corporation", "income");
                merchantToCategory.put("tax refund", "income");
                merchantToCategory.put("tesla inc", "income");
                merchantToCategory.put("tesla motors", "income");
                merchantToCategory.put("toyota motor", "income");
                merchantToCategory.put("treasury department", "income");
                merchantToCategory.put("triple net", "income");
                merchantToCategory.put("triplenet", "income");
                merchantToCategory.put("ui", "income");
                merchantToCategory.put("ukg", "income");
                merchantToCategory.put("ultimate software", "income");
                merchantToCategory.put("unemployment", "income");
                merchantToCategory.put("unemployment insurance", "income");
                merchantToCategory.put("unitedhealth group", "income");
                merchantToCategory.put("us treasury", "income");
                merchantToCategory.put("va", "income");
                merchantToCategory.put("va benefits", "income");
                merchantToCategory.put("verizon communications", "income");
                merchantToCategory.put("veterans affairs", "income");
                merchantToCategory.put("veterans benefits", "income");
                merchantToCategory.put("volkswagen", "income");
                merchantToCategory.put("wage", "income");
                merchantToCategory.put("wages", "income");
                merchantToCategory.put("walmart inc", "income");
                merchantToCategory.put("walmart stores", "income");
                merchantToCategory.put("walt disney", "income");
                merchantToCategory.put("wave payroll", "income");
                merchantToCategory.put("workday", "income");
                merchantToCategory.put("workday payroll", "income");
                merchantToCategory.put("zenefits", "income");

                merchantCount += 177;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
                            MAX_MERCHANTS);
                    return;
                }

                // ========== INSURANCE (56 merchants) ==========
                merchantToCategory.put("aarp", "insurance");
                merchantToCategory.put("aarp insurance", "insurance");
                merchantToCategory.put("aflac", "insurance");
                merchantToCategory.put("aflac insurance", "insurance");
                merchantToCategory.put("allstate", "insurance");
                merchantToCategory.put("allstate home", "insurance");
                merchantToCategory.put("american family", "insurance");
                merchantToCategory.put("american family insurance", "insurance");
                merchantToCategory.put("amfam", "insurance");
                merchantToCategory.put("auto insurance", "insurance");
                merchantToCategory.put("car insurance", "insurance");
                merchantToCategory.put("dental insurance", "insurance");
                merchantToCategory.put("disability insurance", "insurance");
                merchantToCategory.put("erie", "insurance");
                merchantToCategory.put("erie insurance", "insurance");
                merchantToCategory.put("esurance", "insurance");
                merchantToCategory.put("farmers", "insurance");
                merchantToCategory.put("farmers home", "insurance");
                merchantToCategory.put("farmers insurance", "insurance");
                merchantToCategory.put("geico", "insurance");
                merchantToCategory.put("hartford", "insurance");
                merchantToCategory.put("health insurance", "insurance");
                merchantToCategory.put("home insurance", "insurance");
                merchantToCategory.put("homeowner insurance", "insurance");
                merchantToCategory.put("irs insurance", "insurance");
                merchantToCategory.put("legal insurance", "insurance");
                merchantToCategory.put("liberty mutual", "insurance");
                merchantToCategory.put("life insurance", "insurance");
                merchantToCategory.put("mass mutual", "insurance");
                merchantToCategory.put("massmutual", "insurance");
                merchantToCategory.put("mcc 6300", "insurance");
                merchantToCategory.put("mcc6300", "insurance");
                merchantToCategory.put("met life", "insurance");
                merchantToCategory.put("metlife", "insurance");
                merchantToCategory.put("nationwide", "insurance");
                merchantToCategory.put("new york life", "insurance");
                merchantToCategory.put("northwestern mutual", "insurance");
                merchantToCategory.put("pet insurance", "insurance");
                merchantToCategory.put("progressive", "insurance");
                merchantToCategory.put("prudential", "insurance");
                merchantToCategory.put("prudential financial", "insurance");
                merchantToCategory.put("renters insurance", "insurance");
                merchantToCategory.put("safeco", "insurance");
                merchantToCategory.put("safeco insurance", "insurance");
                merchantToCategory.put("sic 63", "insurance");
                merchantToCategory.put("sic 64", "insurance");
                merchantToCategory.put("state farm", "insurance");
                merchantToCategory.put("state farm home", "insurance");
                merchantToCategory.put("the hartford", "insurance");
                merchantToCategory.put("travel insurance", "insurance");
                merchantToCategory.put("travelers", "insurance");
                merchantToCategory.put("travelers insurance", "insurance");
                merchantToCategory.put("usaa", "insurance");
                merchantToCategory.put("usaa home", "insurance");
                merchantToCategory.put("vehicle insurance", "insurance");
                merchantToCategory.put("vision insurance", "insurance");

                merchantCount += 56;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
                            MAX_MERCHANTS);
                    return;
                }

                // ========== INVESTMENT (187 merchants) ==========
                merchantToCategory.put("401 k", "investment");
                merchantToCategory.put("401 k contribution", "investment");
                merchantToCategory.put("401k contribution", "investment");
                merchantToCategory.put("acorns", "investment");
                merchantToCategory.put("ally invest", "investment");
                merchantToCategory.put("asset management", "investment");
                merchantToCategory.put("asset manager", "investment");
                merchantToCategory.put("bank of america merrill", "investment");
                merchantToCategory.put("barclays", "investment");
                merchantToCategory.put("barclays investment", "investment");
                merchantToCategory.put("betterment", "investment");
                merchantToCategory.put("blackrock", "investment");
                merchantToCategory.put("bond purchase", "investment");
                merchantToCategory.put("broker", "investment");
                merchantToCategory.put("brokerage", "investment");
                merchantToCategory.put("brokerage account", "investment");
                merchantToCategory.put("capital gain", "investment");
                merchantToCategory.put("capital gains", "investment");
                merchantToCategory.put("cd interest", "investment");
                merchantToCategory.put("cd investment", "investment");
                merchantToCategory.put("certificate", "investment");
                merchantToCategory.put("charles schwab", "investment");
                merchantToCategory.put("contribution", "investment");
                merchantToCategory.put("credit suisse", "investment");
                merchantToCategory.put("deutsche bank", "investment");
                merchantToCategory.put("directional", "investment");
                merchantToCategory.put("directional funds", "investment");
                merchantToCategory.put("dividend", "investment");
                merchantToCategory.put("dividend income", "investment");
                merchantToCategory.put("dividend payment", "investment");
                merchantToCategory.put("dividends", "investment");
                merchantToCategory.put("e-trade", "investment");
                merchantToCategory.put("edward jones", "investment");
                merchantToCategory.put("edwardjones", "investment");
                merchantToCategory.put("employer contribution", "investment");
                merchantToCategory.put("employer match", "investment");
                merchantToCategory.put("empower", "investment");
                merchantToCategory.put("empower retirement", "investment");
                merchantToCategory.put("equity account", "investment");
                merchantToCategory.put("equity broker", "investment");
                merchantToCategory.put("equity dividend", "investment");
                merchantToCategory.put("equity investment", "investment");
                merchantToCategory.put("equity portfolio", "investment");
                merchantToCategory.put("equity purchase", "investment");
                merchantToCategory.put("equity sale", "investment");
                merchantToCategory.put("equity trading", "investment");
                merchantToCategory.put("etfs", "investment");
                merchantToCategory.put("etrade", "investment");
                merchantToCategory.put("etrade financial", "investment");
                merchantToCategory.put("fidelity", "investment");
                merchantToCategory.put("fidelity investments", "investment");
                merchantToCategory.put("franklin", "investment");
                merchantToCategory.put("franklin templeton", "investment");
                merchantToCategory.put("fund", "investment");
                merchantToCategory.put("fund purchase", "investment");
                merchantToCategory.put("funds", "investment");
                merchantToCategory.put("gaap assets", "investment");
                merchantToCategory.put("gaap current assets", "investment");
                merchantToCategory.put("gaap equity", "investment");
                merchantToCategory.put("gaap fixed assets", "investment");
                merchantToCategory.put("gaap property plant equipment", "investment");
                merchantToCategory.put("gain", "investment");
                merchantToCategory.put("gains", "investment");
                merchantToCategory.put("health savings account", "investment");
                merchantToCategory.put("hsa", "investment");
                merchantToCategory.put("hsa investment", "investment");
                merchantToCategory.put("i shares", "investment");
                merchantToCategory.put("ib", "investment");
                merchantToCategory.put("ibkr", "investment");
                merchantToCategory.put("index fund", "investment");
                merchantToCategory.put("index funds", "investment");
                merchantToCategory.put("interactive brokers", "investment");
                merchantToCategory.put("invesco", "investment");
                merchantToCategory.put("invesco qqq", "investment");
                merchantToCategory.put("invest", "investment");
                merchantToCategory.put("invested", "investment");
                merchantToCategory.put("investing", "investment");
                merchantToCategory.put("investment account", "investment");
                merchantToCategory.put("investment company", "investment");
                merchantToCategory.put("investment firm", "investment");
                merchantToCategory.put("investment income", "investment");
                merchantToCategory.put("investment purchase", "investment");
                merchantToCategory.put("investment return", "investment");
                merchantToCategory.put("investment transfer", "investment");
                merchantToCategory.put("investor", "investment");
                merchantToCategory.put("ira", "investment");
                merchantToCategory.put("ira contribution", "investment");
                merchantToCategory.put("irs pension and profit-sharing plans", "investment");
                merchantToCategory.put("ishares", "investment");
                merchantToCategory.put("jp morgan", "investment");
                merchantToCategory.put("jpmorgan", "investment");
                merchantToCategory.put("jpmorgan chase", "investment");
                merchantToCategory.put("m1", "investment");
                merchantToCategory.put("m1 finance", "investment");
                merchantToCategory.put("merrill", "investment");
                merchantToCategory.put("merrill lynch", "investment");
                merchantToCategory.put("morgan stanley", "investment");
                merchantToCategory.put("morganstanley", "investment");
                merchantToCategory.put("mutual funds", "investment");
                merchantToCategory.put("naics 52", "investment");
                merchantToCategory.put("naics 53", "investment");
                merchantToCategory.put("nasdaq", "investment");
                merchantToCategory.put("nyse", "investment");
                merchantToCategory.put("online transfer from morgan stanley", "investment");
                merchantToCategory.put("online transfer from morganstanley", "investment");
                merchantToCategory.put("pension", "investment");
                merchantToCategory.put("pension fund", "investment");
                merchantToCategory.put("personal capital", "investment");
                merchantToCategory.put("portfolio", "investment");
                merchantToCategory.put("portfolio management", "investment");
                merchantToCategory.put("pro shares", "investment");
                merchantToCategory.put("profit", "investment");
                merchantToCategory.put("profits", "investment");
                merchantToCategory.put("proshares", "investment");
                merchantToCategory.put("public", "investment");
                merchantToCategory.put("public.com", "investment");
                merchantToCategory.put("raymond james", "investment");
                merchantToCategory.put("raymondjames", "investment");
                merchantToCategory.put("retirement", "investment");
                merchantToCategory.put("retirement account", "investment");
                merchantToCategory.put("retirement contribution", "investment");
                merchantToCategory.put("retirement fund", "investment");
                merchantToCategory.put("retirement plan", "investment");
                merchantToCategory.put("return on investment", "investment");
                merchantToCategory.put("robin hood", "investment");
                merchantToCategory.put("robinhood", "investment");
                merchantToCategory.put("roi", "investment");
                merchantToCategory.put("roth ira", "investment");
                merchantToCategory.put("roth ira contribution", "investment");
                merchantToCategory.put("schwab", "investment");
                merchantToCategory.put("securities", "investment");
                merchantToCategory.put("securities purchase", "investment");
                merchantToCategory.put("security", "investment");
                merchantToCategory.put("share", "investment");
                merchantToCategory.put("share account", "investment");
                merchantToCategory.put("share broker", "investment");
                merchantToCategory.put("share dividend", "investment");
                merchantToCategory.put("share investment", "investment");
                merchantToCategory.put("share portfolio", "investment");
                merchantToCategory.put("share purchase", "investment");
                merchantToCategory.put("share sale", "investment");
                merchantToCategory.put("share trading", "investment");
                merchantToCategory.put("shares", "investment");
                merchantToCategory.put("sic 60", "investment");
                merchantToCategory.put("sic 61", "investment");
                merchantToCategory.put("sic 62", "investment");
                merchantToCategory.put("sic 65", "investment");
                merchantToCategory.put("sic 67", "investment");
                merchantToCategory.put("sofi invest", "investment");
                merchantToCategory.put("stash", "investment");
                merchantToCategory.put("state street", "investment");
                merchantToCategory.put("state street global", "investment");
                merchantToCategory.put("stock", "investment");
                merchantToCategory.put("stock account", "investment");
                merchantToCategory.put("stock broker", "investment");
                merchantToCategory.put("stock dividend", "investment");
                merchantToCategory.put("stock exchange", "investment");
                merchantToCategory.put("stock investment", "investment");
                merchantToCategory.put("stock market", "investment");
                merchantToCategory.put("stock portfolio", "investment");
                merchantToCategory.put("stock purchase", "investment");
                merchantToCategory.put("stock sale", "investment");
                merchantToCategory.put("stock trading", "investment");
                merchantToCategory.put("t rowe price", "investment");
                merchantToCategory.put("t. rowe price", "investment");
                merchantToCategory.put("tastytrade", "investment");
                merchantToCategory.put("tastyworks", "investment");
                merchantToCategory.put("td ameritrade", "investment");
                merchantToCategory.put("trade", "investment");
                merchantToCategory.put("trader", "investment");
                merchantToCategory.put("trading", "investment");
                merchantToCategory.put("trading account", "investment");
                merchantToCategory.put("transfer from fidelity", "investment");
                merchantToCategory.put("transfer from morgan stanley", "investment");
                merchantToCategory.put("transfer from morganstanley", "investment");
                merchantToCategory.put("transfer from schwab", "investment");
                merchantToCategory.put("transfer from vanguard", "investment");
                merchantToCategory.put("treasuries", "investment");
                merchantToCategory.put("treasury", "investment");
                merchantToCategory.put("troweprice", "investment");
                merchantToCategory.put("ubs", "investment");
                merchantToCategory.put("ubs financial", "investment");
                merchantToCategory.put("vanguard", "investment");
                merchantToCategory.put("vanguard group", "investment");
                merchantToCategory.put("wealthfront", "investment");
                merchantToCategory.put("webull", "investment");
                merchantToCategory.put("webull securities", "investment");

                merchantCount += 187;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
                            MAX_MERCHANTS);
                    return;
                }

                // ========== PAYMENT (275 merchants) ==========
                merchantToCategory.put("53", "payment");
                merchantToCategory.put("ach 22", "payment");
                merchantToCategory.put("ach 23", "payment");
                merchantToCategory.put("ach 24", "payment");
                merchantToCategory.put("ach 32", "payment");
                merchantToCategory.put("ach 33", "payment");
                merchantToCategory.put("ach 34", "payment");
                merchantToCategory.put("ach 42", "payment");
                merchantToCategory.put("ach 43", "payment");
                merchantToCategory.put("ach 44", "payment");
                merchantToCategory.put("aidvantage", "payment");
                merchantToCategory.put("amazon credit card", "payment");
                merchantToCategory.put("amazon store card", "payment");
                merchantToCategory.put("amazon store card payment", "payment");
                merchantToCategory.put("amazon store card pmt", "payment");
                merchantToCategory.put("american express", "payment");
                merchantToCategory.put("american express payment", "payment");
                merchantToCategory.put("amex", "payment");
                merchantToCategory.put("amex payment", "payment");
                merchantToCategory.put("amz credit card payment", "payment");
                merchantToCategory.put("amz store card", "payment");
                merchantToCategory.put("amz store card pmt", "payment");
                merchantToCategory.put("amz storecrd", "payment");
                merchantToCategory.put("amz storecrd pmt", "payment");
                merchantToCategory.put("amz storecrd pmt payment", "payment");
                merchantToCategory.put("amzstorecrd", "payment");
                merchantToCategory.put("arc", "payment");
                merchantToCategory.put("arc entry", "payment");
                merchantToCategory.put("auto loan", "payment");
                merchantToCategory.put("auto pay", "payment");
                merchantToCategory.put("auto payment", "payment");
                merchantToCategory.put("auto-pay", "payment");
                merchantToCategory.put("automatic pay", "payment");
                merchantToCategory.put("automatic payment", "payment");
                merchantToCategory.put("autopay", "payment");
                merchantToCategory.put("autopay payment", "payment");
                merchantToCategory.put("aventium", "payment");
                merchantToCategory.put("bacs direct debit", "payment");
                merchantToCategory.put("bank autopay", "payment");
                merchantToCategory.put("bank of america", "payment");
                merchantToCategory.put("bb&t", "payment");
                merchantToCategory.put("bbt", "payment");
                merchantToCategory.put("boa", "payment");
                merchantToCategory.put("boc", "payment");
                merchantToCategory.put("boc entry", "payment");
                merchantToCategory.put("bofa", "payment");
                merchantToCategory.put("capital one", "payment");
                merchantToCategory.put("capitalone", "payment");
                merchantToCategory.put("car loan", "payment");
                merchantToCategory.put("car payment", "payment");
                merchantToCategory.put("card auto pay", "payment");
                merchantToCategory.put("card autopay", "payment");
                merchantToCategory.put("card balance", "payment");
                merchantToCategory.put("card bill", "payment");
                merchantToCategory.put("card e-payment", "payment");
                merchantToCategory.put("card pay", "payment");
                merchantToCategory.put("card payment", "payment");
                merchantToCategory.put("card pmt", "payment");
                merchantToCategory.put("card statement", "payment");
                merchantToCategory.put("chase", "payment");
                merchantToCategory.put("chase autopay", "payment");
                merchantToCategory.put("chase credit", "payment");
                merchantToCategory.put("check", "payment");
                merchantToCategory.put("check #", "payment");
                merchantToCategory.put("check cashing", "payment");
                merchantToCategory.put("check clearing", "payment");
                merchantToCategory.put("check issued", "payment");
                merchantToCategory.put("check no", "payment");
                merchantToCategory.put("check no.", "payment");
                merchantToCategory.put("check number", "payment");
                merchantToCategory.put("check pay", "payment");
                merchantToCategory.put("check payment", "payment");
                merchantToCategory.put("check purchase", "payment");
                merchantToCategory.put("check transaction", "payment");
                merchantToCategory.put("check written", "payment");
                merchantToCategory.put("checks", "payment");
                merchantToCategory.put("cheque", "payment");
                merchantToCategory.put("cheques", "payment");
                merchantToCategory.put("citi", "payment");
                merchantToCategory.put("citi autopay", "payment");
                merchantToCategory.put("citi autopay payment", "payment");
                merchantToCategory.put("citi card", "payment");
                merchantToCategory.put("citi credit", "payment");
                merchantToCategory.put("citibank", "payment");
                merchantToCategory.put("citicard", "payment");
                merchantToCategory.put("citizens", "payment");
                merchantToCategory.put("citizens bank", "payment");
                merchantToCategory.put("ckd", "payment");
                merchantToCategory.put("ckd entry", "payment");
                merchantToCategory.put("comerica", "payment");
                merchantToCategory.put("comerica bank", "payment");
                merchantToCategory.put("credit auto pay", "payment");
                merchantToCategory.put("credit autopay", "payment");
                merchantToCategory.put("credit balance", "payment");
                merchantToCategory.put("credit bill", "payment");
                merchantToCategory.put("credit card auto pay", "payment");
                merchantToCategory.put("credit card autopay", "payment");
                merchantToCategory.put("credit card balance", "payment");
                merchantToCategory.put("credit card bill", "payment");
                merchantToCategory.put("credit card e-payment", "payment");
                merchantToCategory.put("credit card pay", "payment");
                merchantToCategory.put("credit card payment", "payment");
                merchantToCategory.put("credit card pmt", "payment");
                merchantToCategory.put("credit card statement", "payment");
                merchantToCategory.put("credit crd", "payment");
                merchantToCategory.put("credit e-payment", "payment");
                merchantToCategory.put("credit pay", "payment");
                merchantToCategory.put("credit payment", "payment");
                merchantToCategory.put("credit statement", "payment");
                merchantToCategory.put("creditcard", "payment");
                merchantToCategory.put("debt", "payment");
                merchantToCategory.put("debt auto pay", "payment");
                merchantToCategory.put("debt autopay", "payment");
                merchantToCategory.put("debt installment", "payment");
                merchantToCategory.put("debt monthly", "payment");
                merchantToCategory.put("debt pay", "payment");
                merchantToCategory.put("debt payment", "payment");
                merchantToCategory.put("debt provider", "payment");
                merchantToCategory.put("debt repay", "payment");
                merchantToCategory.put("debt repayment", "payment");
                merchantToCategory.put("debt service", "payment");
                merchantToCategory.put("discover", "payment");
                merchantToCategory.put("discover card", "payment");
                merchantToCategory.put("discover payment", "payment");
                merchantToCategory.put("discover personal loans", "payment");
                merchantToCategory.put("e payment", "payment");
                merchantToCategory.put("e-payment", "payment");
                merchantToCategory.put("ed financial", "payment");
                merchantToCategory.put("edfinancial", "payment");
                merchantToCategory.put("education loan", "payment");
                merchantToCategory.put("electronic payment", "payment");
                merchantToCategory.put("fedloan", "payment");
                merchantToCategory.put("fedloan servicing", "payment");
                merchantToCategory.put("fifth third", "payment");
                merchantToCategory.put("fifth third bank", "payment");
                merchantToCategory.put("first national", "payment");
                merchantToCategory.put("first national bank", "payment");
                merchantToCategory.put("gaap accounts payable", "payment");
                merchantToCategory.put("gaap accrued expenses", "payment");
                merchantToCategory.put("gaap administrative expenses", "payment");
                merchantToCategory.put("gaap amortization expense", "payment");
                merchantToCategory.put("gaap current liabilities", "payment");
                merchantToCategory.put("gaap depreciation expense", "payment");
                merchantToCategory.put("gaap expenses", "payment");
                merchantToCategory.put("gaap interest expense", "payment");
                merchantToCategory.put("gaap liabilities", "payment");
                merchantToCategory.put("gaap long-term debt", "payment");
                merchantToCategory.put("gaap operating expenses", "payment");
                merchantToCategory.put("gaap prepaid expenses", "payment");
                merchantToCategory.put("gaap selling expenses", "payment");
                merchantToCategory.put("gaap tax expense", "payment");
                merchantToCategory.put("great lakes", "payment");
                merchantToCategory.put("great lakes educational", "payment");
                merchantToCategory.put("home loan", "payment");
                merchantToCategory.put("home loan payment", "payment");
                merchantToCategory.put("housing loan", "payment");
                merchantToCategory.put("huntington", "payment");
                merchantToCategory.put("huntington bank", "payment");
                merchantToCategory.put("installment", "payment");
                merchantToCategory.put("installment payment", "payment");
                merchantToCategory.put("irs commissions and fees", "payment");
                merchantToCategory.put("irs contract labor", "payment");
                merchantToCategory.put("irs depreciation", "payment");
                merchantToCategory.put("irs employee benefit programs", "payment");
                merchantToCategory.put("irs estate tax", "payment");
                merchantToCategory.put("irs estimated tax", "payment");
                merchantToCategory.put("irs excise tax", "payment");
                merchantToCategory.put("irs federal tax", "payment");
                merchantToCategory.put("irs gift tax", "payment");
                merchantToCategory.put("irs income tax", "payment");
                merchantToCategory.put("irs interest", "payment");
                merchantToCategory.put("irs local tax", "payment");
                merchantToCategory.put("irs other expenses", "payment");
                merchantToCategory.put("irs payroll tax", "payment");
                merchantToCategory.put("irs property tax", "payment");
                merchantToCategory.put("irs sales tax", "payment");
                merchantToCategory.put("irs self-employment tax", "payment");
                merchantToCategory.put("irs state tax", "payment");
                merchantToCategory.put("irs tax payment", "payment");
                merchantToCategory.put("irs taxes and licenses", "payment");
                merchantToCategory.put("key bank", "payment");
                merchantToCategory.put("keybank", "payment");
                merchantToCategory.put("lending club", "payment");
                merchantToCategory.put("lendingclub", "payment");
                merchantToCategory.put("lightstream", "payment");
                merchantToCategory.put("lightstream loans", "payment");
                merchantToCategory.put("loan", "payment");
                merchantToCategory.put("loan auto pay", "payment");
                merchantToCategory.put("loan autopay", "payment");
                merchantToCategory.put("loan installment", "payment");
                merchantToCategory.put("loan monthly", "payment");
                merchantToCategory.put("loan pay", "payment");
                merchantToCategory.put("loan payment", "payment");
                merchantToCategory.put("loan provider", "payment");
                merchantToCategory.put("loan repay", "payment");
                merchantToCategory.put("loan repayment", "payment");
                merchantToCategory.put("loan service", "payment");
                merchantToCategory.put("m&t bank", "payment");
                merchantToCategory.put("mariner finance", "payment");
                merchantToCategory.put("mastercard", "payment");
                merchantToCategory.put("mastercard payment", "payment");
                merchantToCategory.put("mohela", "payment");
                merchantToCategory.put("monthly payment", "payment");
                merchantToCategory.put("mortgage", "payment");
                merchantToCategory.put("mortgage pay", "payment");
                merchantToCategory.put("mortgage repay", "payment");
                merchantToCategory.put("mt bank", "payment");
                merchantToCategory.put("navient", "payment");
                merchantToCategory.put("nelnet", "payment");
                merchantToCategory.put("one main financial", "payment");
                merchantToCategory.put("onemain", "payment");
                merchantToCategory.put("online payment", "payment");
                merchantToCategory.put("paying", "payment");
                merchantToCategory.put("payment gateway", "payment");
                merchantToCategory.put("payment plan", "payment");
                merchantToCategory.put("payment processing", "payment");
                merchantToCategory.put("payment service", "payment");
                merchantToCategory.put("personal loan", "payment");
                merchantToCategory.put("personal loan pay", "payment");
                merchantToCategory.put("personal loan payment", "payment");
                merchantToCategory.put("pnc", "payment");
                merchantToCategory.put("pnc bank", "payment");
                merchantToCategory.put("pop", "payment");
                merchantToCategory.put("pop entry", "payment");
                merchantToCategory.put("pos", "payment");
                merchantToCategory.put("pos entry", "payment");
                merchantToCategory.put("ppd debit", "payment");
                merchantToCategory.put("prosper", "payment");
                merchantToCategory.put("prosper marketplace", "payment");
                merchantToCategory.put("rcp", "payment");
                merchantToCategory.put("rcp entry", "payment");
                merchantToCategory.put("recurring payment", "payment");
                merchantToCategory.put("regions", "payment");
                merchantToCategory.put("regions bank", "payment");
                merchantToCategory.put("scheduled payment", "payment");
                merchantToCategory.put("sepa direct debit", "payment");
                merchantToCategory.put("sofi", "payment");
                merchantToCategory.put("sofi loans", "payment");
                merchantToCategory.put("springleaf", "payment");
                merchantToCategory.put("storecrd payment", "payment");
                merchantToCategory.put("storecrd pmt", "payment");
                merchantToCategory.put("student loan", "payment");
                merchantToCategory.put("student loan payment", "payment");
                merchantToCategory.put("suntrust", "payment");
                merchantToCategory.put("synchrony", "payment");
                merchantToCategory.put("synchrony bank", "payment");
                merchantToCategory.put("td bank", "payment");
                merchantToCategory.put("tdbank", "payment");
                merchantToCategory.put("tel", "payment");
                merchantToCategory.put("tel credit", "payment");
                merchantToCategory.put("tel debit", "payment");
                merchantToCategory.put("tel entry", "payment");
                merchantToCategory.put("trc", "payment");
                merchantToCategory.put("trc entry", "payment");
                merchantToCategory.put("truist", "payment");
                merchantToCategory.put("trx", "payment");
                merchantToCategory.put("trx entry", "payment");
                merchantToCategory.put("upstart", "payment");
                merchantToCategory.put("us bank", "payment");
                merchantToCategory.put("usbank", "payment");
                merchantToCategory.put("vehicle loan", "payment");
                // "visa" alone is too ambiguous — it's a card network AND
                // a travel document. "visa application" / "visa service"
                // is a travel expense; removed the bare keyword to stop
                // misclassifying immigration/travel fees as payments.
                // `visa payment` (specific phrase) remains below.
                merchantToCategory.put("visa payment", "payment");
                // Bare "web"/"web credit"/"web debit" match any online
                // transaction description (hundreds of merchants mention
                // "web"). Removed to stop mass mis-categorisation; kept
                // the specific "web bill pay" below if present.
                merchantToCategory.put("web bill pay", "payment");
                merchantToCategory.put("web entry", "payment");
                merchantToCategory.put("wells fargo", "payment");
                merchantToCategory.put("wf", "payment");
                merchantToCategory.put("wf credit", "payment");
                merchantToCategory.put("written check", "payment");
                merchantToCategory.put("xck", "payment");
                merchantToCategory.put("xck entry", "payment");
                merchantToCategory.put("zions", "payment");
                merchantToCategory.put("zions bank", "payment");

                merchantCount += 275;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
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
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
                            MAX_MERCHANTS);
                    return;
                }

                // ========== RENT (1 merchants) ==========
                merchantToCategory.put("irs rent or lease", "rent");

                merchantCount += 1;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
                            MAX_MERCHANTS);
                    return;
                }

                // ========== SERVICE (27 merchants) ==========
                merchantToCategory.put("angie's list", "service");
                merchantToCategory.put("angies list", "service");
                merchantToCategory.put("care.com", "service");
                merchantToCategory.put("carecom", "service");
                merchantToCategory.put("handy", "service");
                merchantToCategory.put("home advisor", "service");
                merchantToCategory.put("homeadvisor", "service");
                merchantToCategory.put("irs legal and professional services", "service");
                merchantToCategory.put("naics 54", "service");
                merchantToCategory.put("naics 55", "service");
                merchantToCategory.put("naics 56", "service");
                merchantToCategory.put("naics 61", "service");
                merchantToCategory.put("naics 81", "service");
                merchantToCategory.put("sic 70", "service");
                merchantToCategory.put("sic 73", "service");
                merchantToCategory.put("sic 76", "service");
                merchantToCategory.put("sic 81", "service");
                merchantToCategory.put("sic 82", "service");
                merchantToCategory.put("sic 83", "service");
                merchantToCategory.put("sic 84", "service");
                merchantToCategory.put("sic 86", "service");
                merchantToCategory.put("sic 87", "service");
                merchantToCategory.put("sic 88", "service");
                merchantToCategory.put("sic 89", "service");
                merchantToCategory.put("task rabbit", "service");
                merchantToCategory.put("taskrabbit", "service");
                merchantToCategory.put("thumbtack", "service");

                merchantCount += 27;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
                            MAX_MERCHANTS);
                    return;
                }

                // ========== SHOPPING (159 merchants) ==========
                merchantToCategory.put("academy sports", "shopping");
                merchantToCategory.put("ae", "shopping");
                merchantToCategory.put("amazon", "shopping");
                merchantToCategory.put("amazon.com", "shopping");
                merchantToCategory.put("american eagle", "shopping");
                merchantToCategory.put("apparel", "shopping");
                merchantToCategory.put("attire", "shopping");
                merchantToCategory.put("banana republic", "shopping");
                merchantToCategory.put("barnes & noble", "shopping");
                merchantToCategory.put("barnes and noble", "shopping");
                merchantToCategory.put("bass pro shops", "shopping");
                merchantToCategory.put("bbb", "shopping");
                merchantToCategory.put("bed bath & beyond", "shopping");
                merchantToCategory.put("bed bath and beyond", "shopping");
                merchantToCategory.put("best buy", "shopping");
                merchantToCategory.put("bestbuy", "shopping");
                merchantToCategory.put("books a million", "shopping");
                merchantToCategory.put("books-a-million", "shopping");
                merchantToCategory.put("boutique", "shopping");
                merchantToCategory.put("burlington", "shopping");
                merchantToCategory.put("burlington coat factory", "shopping");
                merchantToCategory.put("buy", "shopping");
                merchantToCategory.put("buying", "shopping");
                merchantToCategory.put("cabela's", "shopping");
                merchantToCategory.put("cabelas", "shopping");
                merchantToCategory.put("charles Tyrwhitt", "shopping");
                merchantToCategory.put("clothing", "shopping");
                merchantToCategory.put("convenience store", "shopping");
                merchantToCategory.put("cost plus world market", "shopping");
                merchantToCategory.put("department store", "shopping");
                merchantToCategory.put("designer shoe warehouse", "shopping");
                merchantToCategory.put("dick's sporting goods", "shopping");
                merchantToCategory.put("dicks", "shopping");
                merchantToCategory.put("dicks sporting goods", "shopping");
                merchantToCategory.put("dsw", "shopping");
                merchantToCategory.put("ebay", "shopping");
                merchantToCategory.put("electronics store", "shopping");
                merchantToCategory.put("etsy", "shopping");
                merchantToCategory.put("fashion", "shopping");
                merchantToCategory.put("finish line", "shopping");
                merchantToCategory.put("foot locker", "shopping");
                merchantToCategory.put("forever 21", "shopping");
                merchantToCategory.put("forever21", "shopping");
                merchantToCategory.put("furniture store", "shopping");
                merchantToCategory.put("gaap cost of goods sold", "shopping");
                merchantToCategory.put("gaap inventory", "shopping");
                merchantToCategory.put("game stop", "shopping");
                merchantToCategory.put("gamestop", "shopping");
                merchantToCategory.put("gap", "shopping");
                merchantToCategory.put("garment", "shopping");
                merchantToCategory.put("h&m", "shopping");
                merchantToCategory.put("hardware store", "shopping");
                merchantToCategory.put("hm", "shopping");
                merchantToCategory.put("hobby lobby", "shopping");
                merchantToCategory.put("home improvement store", "shopping");
                merchantToCategory.put("in store", "shopping");
                merchantToCategory.put("in-store", "shopping");
                merchantToCategory.put("irs advertising", "shopping");
                merchantToCategory.put("irs office expenses", "shopping");
                merchantToCategory.put("irs supplies", "shopping");
                merchantToCategory.put("j.c. penney", "shopping");
                merchantToCategory.put("jc penney", "shopping");
                merchantToCategory.put("jcpenney", "shopping");
                merchantToCategory.put("jo-ann", "shopping");
                merchantToCategory.put("joann", "shopping");
                merchantToCategory.put("joann fabrics", "shopping");
                merchantToCategory.put("kohl's", "shopping");
                merchantToCategory.put("kohls", "shopping");
                merchantToCategory.put("macy's", "shopping");
                merchantToCategory.put("macys", "shopping");
                merchantToCategory.put("mall", "shopping");
                merchantToCategory.put("marshalls", "shopping");
                merchantToCategory.put("mcc 5311", "shopping");
                merchantToCategory.put("mcc 5999", "shopping");
                merchantToCategory.put("mcc5311", "shopping");
                merchantToCategory.put("mcc5999", "shopping");
                merchantToCategory.put("men's apparel", "shopping");
                merchantToCategory.put("men's clothing", "shopping");
                merchantToCategory.put("mens apparel", "shopping");
                merchantToCategory.put("mens clothing", "shopping");
                merchantToCategory.put("merchandise", "shopping");
                merchantToCategory.put("michael's", "shopping");
                merchantToCategory.put("michaels", "shopping");
                // Mini Mountain is a ski shop; usage is ski rental/lessons, which we bucket as
                // "health"
                // (active fitness), matching "ski" / "ski resort" elsewhere in this file.
                merchantToCategory.put("naics 31", "shopping");
                merchantToCategory.put("naics 32", "shopping");
                merchantToCategory.put("naics 33", "shopping");
                merchantToCategory.put("naics 34", "shopping");
                merchantToCategory.put("naics 42", "shopping");
                merchantToCategory.put("naics 44", "shopping");
                merchantToCategory.put("naics 45", "shopping");
                merchantToCategory.put("nordstrom", "shopping");
                merchantToCategory.put("nordstrom rack", "shopping");
                merchantToCategory.put("old navy", "shopping");
                merchantToCategory.put("outdoor gear", "shopping");
                merchantToCategory.put("outdoorgear", "shopping");
                merchantToCategory.put("outlet", "shopping");
                merchantToCategory.put("overstock", "shopping");
                merchantToCategory.put("pier 1", "shopping");
                merchantToCategory.put("pier 1 imports", "shopping");
                merchantToCategory.put("retail outlet", "shopping");
                merchantToCategory.put("retail purchase", "shopping");
                merchantToCategory.put("retail shop", "shopping");
                merchantToCategory.put("retail store", "shopping");
                merchantToCategory.put("ross", "shopping");
                merchantToCategory.put("ross dress for less", "shopping");
                merchantToCategory.put("sears", "shopping");
                // Cosmetics / skincare brands — previously only Sephora /
                // Ulta were mapped; direct-from-brand charges slipped
                // through and hit the "amex"/"payment" guard rail.
                merchantToCategory.put("estee lauder", "shopping");
                merchantToCategory.put("esteelauder", "shopping");
                merchantToCategory.put("clinique", "shopping");
                merchantToCategory.put("lancome", "shopping");
                merchantToCategory.put("mac cosmetics", "shopping");
                merchantToCategory.put("bobbi brown", "shopping");
                merchantToCategory.put("sephora", "shopping");
                merchantToCategory.put("shop", "shopping");
                merchantToCategory.put("shopping center", "shopping");
                merchantToCategory.put("shopping mall", "shopping");
                merchantToCategory.put("shopping trip", "shopping");
                merchantToCategory.put("shops", "shopping");
                merchantToCategory.put("sic 22", "shopping");
                merchantToCategory.put("sic 23", "shopping");
                merchantToCategory.put("sic 26", "shopping");
                merchantToCategory.put("sic 27", "shopping");
                merchantToCategory.put("sic 30", "shopping");
                merchantToCategory.put("sic 31", "shopping");
                merchantToCategory.put("sic 32", "shopping");
                merchantToCategory.put("sic 33", "shopping");
                merchantToCategory.put("sic 34", "shopping");
                merchantToCategory.put("sic 39", "shopping");
                merchantToCategory.put("sic 50", "shopping");
                merchantToCategory.put("sic 51", "shopping");
                merchantToCategory.put("sic 52", "shopping");
                merchantToCategory.put("sic 53", "shopping");
                merchantToCategory.put("sic 55", "shopping");
                merchantToCategory.put("sic 56", "shopping");
                merchantToCategory.put("sic 57", "shopping");
                merchantToCategory.put("sic 59", "shopping");
                merchantToCategory.put("ski equipment", "shopping");
                merchantToCategory.put("ski gear", "shopping");
                merchantToCategory.put("skiequipment", "shopping");
                merchantToCategory.put("skigear", "shopping");
                merchantToCategory.put("sports equipment", "shopping");
                merchantToCategory.put("sports store", "shopping");
                merchantToCategory.put("sportsequipment", "shopping");
                merchantToCategory.put("store payment", "shopping");
                merchantToCategory.put("store purchase", "shopping");
                merchantToCategory.put("store shopping", "shopping");
                merchantToCategory.put("store transaction", "shopping");
                merchantToCategory.put("store visit", "shopping");
                merchantToCategory.put("stores", "shopping");
                merchantToCategory.put("tj maxx", "shopping");
                merchantToCategory.put("tjmaxx", "shopping");
                merchantToCategory.put("toy store", "shopping");
                merchantToCategory.put("ulta", "shopping");
                merchantToCategory.put("ulta beauty", "shopping");
                merchantToCategory.put("wardrobe", "shopping");
                merchantToCategory.put("wayfair", "shopping");
                merchantToCategory.put("women's apparel", "shopping");
                merchantToCategory.put("women's clothing", "shopping");
                merchantToCategory.put("womens apparel", "shopping");
                merchantToCategory.put("womens clothing", "shopping");
                merchantToCategory.put("world market", "shopping");
                merchantToCategory.put("zappos", "shopping");
                merchantToCategory.put("zara", "shopping");

                merchantCount += 159;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
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
                merchantToCategory.put("paypal", "transfer");
                merchantToCategory.put("venmo", "transfer");
                merchantToCategory.put("zelle", "transfer");
                merchantToCategory.put("cashapp", "transfer");
                merchantToCategory.put("cash app", "transfer");
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
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
                            MAX_MERCHANTS);
                    return;
                }

                // ========== TRANSFER (279 merchants) ==========
                merchantToCategory.put("aba number", "transfer");
                merchantToCategory.put("aba routing number", "transfer");
                merchantToCategory.put("akoya", "transfer");
                // Remittance / international money transfer services.
                merchantToCategory.put("remitly", "transfer");
                merchantToCategory.put("western union", "transfer");
                merchantToCategory.put("westernunion", "transfer");
                merchantToCategory.put("wise transfer", "transfer");
                merchantToCategory.put("wise.com", "transfer");
                merchantToCategory.put("moneygram", "transfer");
                merchantToCategory.put("xoom", "transfer");
                merchantToCategory.put("ria money transfer", "transfer");
                merchantToCategory.put("alliant", "transfer");
                merchantToCategory.put("alliant credit union", "transfer");
                merchantToCategory.put("alloy", "transfer");
                merchantToCategory.put("alloy labs", "transfer");
                merchantToCategory.put("ally", "transfer");
                merchantToCategory.put("ally bank", "transfer");
                merchantToCategory.put("ansi x9", "transfer");
                merchantToCategory.put("ansi x9.100", "transfer");
                merchantToCategory.put("ansi x9.100-140", "transfer");
                merchantToCategory.put("ansi x9.100-187", "transfer");
                merchantToCategory.put("ansi x9.13", "transfer");
                merchantToCategory.put("ansi x9.37", "transfer");
                merchantToCategory.put("ansix9", "transfer");
                merchantToCategory.put("bacs", "transfer");
                merchantToCategory.put("bacs transfer", "transfer");
                merchantToCategory.put("bai 100", "transfer");
                merchantToCategory.put("bai 101", "transfer");
                merchantToCategory.put("bai 102", "transfer");
                merchantToCategory.put("bai 103", "transfer");
                merchantToCategory.put("bai 104", "transfer");
                merchantToCategory.put("bai 105", "transfer");
                merchantToCategory.put("bai 106", "transfer");
                merchantToCategory.put("bai 107", "transfer");
                merchantToCategory.put("bai 108", "transfer");
                merchantToCategory.put("bai 109", "transfer");
                merchantToCategory.put("bai 110", "transfer");
                merchantToCategory.put("bai 111", "transfer");
                merchantToCategory.put("bai 112", "transfer");
                merchantToCategory.put("bai 115", "transfer");
                merchantToCategory.put("bai 116", "transfer");
                merchantToCategory.put("bai 118", "transfer");
                merchantToCategory.put("bai 121", "transfer");
                merchantToCategory.put("bai 122", "transfer");
                merchantToCategory.put("bai 123", "transfer");
                merchantToCategory.put("bai 124", "transfer");
                merchantToCategory.put("bai 125", "transfer");
                merchantToCategory.put("bai 126", "transfer");
                merchantToCategory.put("bai 127", "transfer");
                merchantToCategory.put("bai 128", "transfer");
                merchantToCategory.put("bai 129", "transfer");
                merchantToCategory.put("bai 130", "transfer");
                merchantToCategory.put("bai 131", "transfer");
                merchantToCategory.put("bai 135", "transfer");
                merchantToCategory.put("bai 136", "transfer");
                merchantToCategory.put("bai 140", "transfer");
                merchantToCategory.put("bai 141", "transfer");
                merchantToCategory.put("bai 142", "transfer");
                merchantToCategory.put("bai 143", "transfer");
                merchantToCategory.put("bai 150", "transfer");
                merchantToCategory.put("bai 151", "transfer");
                merchantToCategory.put("bai 155", "transfer");
                merchantToCategory.put("bai 156", "transfer");
                merchantToCategory.put("bai 160", "transfer");
                merchantToCategory.put("bai 161", "transfer");
                merchantToCategory.put("bai 162", "transfer");
                merchantToCategory.put("bai 163", "transfer");
                merchantToCategory.put("bai 164", "transfer");
                merchantToCategory.put("bai 165", "transfer");
                merchantToCategory.put("bai 170", "transfer");
                merchantToCategory.put("bai 171", "transfer");
                merchantToCategory.put("bai 172", "transfer");
                merchantToCategory.put("bai 180", "transfer");
                merchantToCategory.put("bai 181", "transfer");
                merchantToCategory.put("bai 182", "transfer");
                merchantToCategory.put("bai 183", "transfer");
                merchantToCategory.put("bai 190", "transfer");
                merchantToCategory.put("bai 191", "transfer");
                merchantToCategory.put("bai 192", "transfer");
                merchantToCategory.put("bai 193", "transfer");
                merchantToCategory.put("bai 194", "transfer");
                merchantToCategory.put("bai 195", "transfer");
                merchantToCategory.put("bai 2", "transfer");
                merchantToCategory.put("bai 200", "transfer");
                merchantToCategory.put("bai 201", "transfer");
                merchantToCategory.put("bai 202", "transfer");
                merchantToCategory.put("bai 203", "transfer");
                merchantToCategory.put("bai 210", "transfer");
                merchantToCategory.put("bai 211", "transfer");
                merchantToCategory.put("bai 212", "transfer");
                merchantToCategory.put("bai 220", "transfer");
                merchantToCategory.put("bai 221", "transfer");
                merchantToCategory.put("bai 222", "transfer");
                merchantToCategory.put("bai code", "transfer");
                merchantToCategory.put("bai format", "transfer");
                merchantToCategory.put("bai2", "transfer");
                merchantToCategory.put("bank identifier code", "transfer");
                merchantToCategory.put("bank secrecy act", "transfer");
                merchantToCategory.put("bic", "transfer");
                merchantToCategory.put("bic code", "transfer");
                merchantToCategory.put("bsa", "transfer");
                merchantToCategory.put("camt.052", "transfer");
                merchantToCategory.put("camt.053", "transfer");
                merchantToCategory.put("camt.054", "transfer");
                merchantToCategory.put("camt.056", "transfer");
                merchantToCategory.put("camt.057", "transfer");
                merchantToCategory.put("ccd", "transfer");
                merchantToCategory.put("ccd credit", "transfer");
                merchantToCategory.put("ccd debit", "transfer");
                merchantToCategory.put("ccd entry", "transfer");
                merchantToCategory.put("cfonb", "transfer");
                merchantToCategory.put("cfonb 120", "transfer");
                merchantToCategory.put("cfonb 240", "transfer");
                merchantToCategory.put("chaps", "transfer");
                merchantToCategory.put("chaps transfer", "transfer");
                merchantToCategory.put("charles schwab bank", "transfer");
                merchantToCategory.put("chase bank", "transfer");
                merchantToCategory.put("chips", "transfer");
                merchantToCategory.put("chips credit", "transfer");
                merchantToCategory.put("chips debit", "transfer");
                merchantToCategory.put("chips transfer", "transfer");
                merchantToCategory.put("clearing house interbank payments", "transfer");
                merchantToCategory.put("credit karma", "transfer");
                merchantToCategory.put("ctr", "transfer");
                merchantToCategory.put("ctx", "transfer");
                merchantToCategory.put("ctx credit", "transfer");
                merchantToCategory.put("ctx debit", "transfer");
                merchantToCategory.put("ctx entry", "transfer");
                merchantToCategory.put("currency transaction report", "transfer");
                merchantToCategory.put("discover bank", "transfer");
                merchantToCategory.put("eft", "transfer");
                merchantToCategory.put("eft canada", "transfer");
                merchantToCategory.put("electronic funds transfer", "transfer");
                merchantToCategory.put("envestnet", "transfer");
                merchantToCategory.put("envestnet yodlee", "transfer");
                merchantToCategory.put("external transfer", "transfer");
                merchantToCategory.put("faster payments", "transfer");
                merchantToCategory.put("faster payments service", "transfer");
                merchantToCategory.put("fed wire", "transfer");
                merchantToCategory.put("federal reserve wire", "transfer");
                merchantToCategory.put("fedwire", "transfer");
                merchantToCategory.put("fedwire credit", "transfer");
                merchantToCategory.put("fedwire debit", "transfer");
                merchantToCategory.put("fedwire funds transfer", "transfer");
                merchantToCategory.put("fincen", "transfer");
                merchantToCategory.put("fincen 01", "transfer");
                merchantToCategory.put("fincen 02", "transfer");
                merchantToCategory.put("fincen 03", "transfer");
                merchantToCategory.put("fincen 04", "transfer");
                merchantToCategory.put("fincen 05", "transfer");
                merchantToCategory.put("fincen 06", "transfer");
                merchantToCategory.put("fincen 07", "transfer");
                merchantToCategory.put("fincen 08", "transfer");
                merchantToCategory.put("fincen 09", "transfer");
                merchantToCategory.put("finicity", "transfer");
                merchantToCategory.put("first republic", "transfer");
                merchantToCategory.put("first republic bank", "transfer");
                merchantToCategory.put("fps", "transfer");
                merchantToCategory.put("fund transfer", "transfer");
                merchantToCategory.put("gaap", "transfer");
                merchantToCategory.put("generally accepted accounting principles", "transfer");
                merchantToCategory.put("goldman sachs", "transfer");
                merchantToCategory.put("iat", "transfer");
                merchantToCategory.put("iat credit", "transfer");
                merchantToCategory.put("iat debit", "transfer");
                merchantToCategory.put("iat entry", "transfer");
                merchantToCategory.put("iban", "transfer");
                merchantToCategory.put("iban transfer", "transfer");
                merchantToCategory.put("immediate payment service", "transfer");
                merchantToCategory.put("imps", "transfer");
                merchantToCategory.put("imps india", "transfer");
                merchantToCategory.put("inter-account transfer", "transfer");
                merchantToCategory.put("inter-bank transfer", "transfer");
                merchantToCategory.put("interac", "transfer");
                merchantToCategory.put("interac e-transfer", "transfer");
                merchantToCategory.put("interac etransfer", "transfer");
                merchantToCategory.put("internal transfer", "transfer");
                merchantToCategory.put("international bank account number", "transfer");
                merchantToCategory.put("intra-account transfer", "transfer");
                merchantToCategory.put("intra-bank transfer", "transfer");
                merchantToCategory.put("intuit", "transfer");
                merchantToCategory.put("iso 20022", "transfer");
                merchantToCategory.put("iso20022", "transfer");
                merchantToCategory.put("marcus", "transfer");
                merchantToCategory.put("marcus by goldman sachs", "transfer");
                merchantToCategory.put("mcc 6012", "transfer");
                merchantToCategory.put("mcc6012", "transfer");
                merchantToCategory.put("mint", "transfer");
                merchantToCategory.put("money transfer", "transfer");
                merchantToCategory.put("mt 101", "transfer");
                merchantToCategory.put("mt 102", "transfer");
                merchantToCategory.put("mt 103", "transfer");
                merchantToCategory.put("mt 104", "transfer");
                merchantToCategory.put("mt 110", "transfer");
                merchantToCategory.put("mt 111", "transfer");
                merchantToCategory.put("mt 200", "transfer");
                merchantToCategory.put("mt 201", "transfer");
                merchantToCategory.put("mt 202", "transfer");
                merchantToCategory.put("mt 210", "transfer");
                merchantToCategory.put("mt 900", "transfer");
                merchantToCategory.put("mt 910", "transfer");
                merchantToCategory.put("mt 940", "transfer");
                merchantToCategory.put("mt 942", "transfer");
                merchantToCategory.put("mt 950", "transfer");
                merchantToCategory.put("mt101", "transfer");
                merchantToCategory.put("mt102", "transfer");
                merchantToCategory.put("mt103", "transfer");
                merchantToCategory.put("mt104", "transfer");
                merchantToCategory.put("mt110", "transfer");
                merchantToCategory.put("mt111", "transfer");
                merchantToCategory.put("mt200", "transfer");
                merchantToCategory.put("mt201", "transfer");
                merchantToCategory.put("mt202", "transfer");
                merchantToCategory.put("mt210", "transfer");
                merchantToCategory.put("mt900", "transfer");
                merchantToCategory.put("mt910", "transfer");
                merchantToCategory.put("mt940", "transfer");
                merchantToCategory.put("mt942", "transfer");
                merchantToCategory.put("mt950", "transfer");
                merchantToCategory.put("mx", "transfer");
                merchantToCategory.put("mx technologies", "transfer");
                merchantToCategory.put("national electronic funds transfer", "transfer");
                merchantToCategory.put("navy federal", "transfer");
                merchantToCategory.put("navy federal credit union", "transfer");
                merchantToCategory.put("neft", "transfer");
                merchantToCategory.put("neft india", "transfer");
                merchantToCategory.put("new payments platform", "transfer");
                merchantToCategory.put("npp", "transfer");
                merchantToCategory.put("npp australia", "transfer");
                merchantToCategory.put("ofac", "transfer");
                merchantToCategory.put("office of foreign assets control", "transfer");
                merchantToCategory.put("pacs.002", "transfer");
                merchantToCategory.put("pacs.008", "transfer");
                merchantToCategory.put("pacs.009", "transfer");
                merchantToCategory.put("pain.001", "transfer");
                merchantToCategory.put("pain.002", "transfer");
                merchantToCategory.put("pain.008", "transfer");
                merchantToCategory.put("pain.009", "transfer");
                merchantToCategory.put("payment transfer", "transfer");
                merchantToCategory.put("penfed", "transfer");
                merchantToCategory.put("pentagon federal", "transfer");
                merchantToCategory.put("plaid", "transfer");
                merchantToCategory.put("quovo", "transfer");
                merchantToCategory.put("real time gross settlement", "transfer");
                merchantToCategory.put("routing number", "transfer");
                merchantToCategory.put("routing transit number", "transfer");
                merchantToCategory.put("rtgs", "transfer");
                merchantToCategory.put("rtgs india", "transfer");
                merchantToCategory.put("rtn", "transfer");
                merchantToCategory.put("sar", "transfer");
                merchantToCategory.put("schwab bank", "transfer");
                merchantToCategory.put("sdn", "transfer");
                merchantToCategory.put("sepa", "transfer");
                merchantToCategory.put("sepa credit transfer", "transfer");
                merchantToCategory.put("sepa instant", "transfer");
                merchantToCategory.put("sepa instant credit transfer", "transfer");
                merchantToCategory.put("sepa transfer", "transfer");
                merchantToCategory.put("sic 46", "transfer");
                merchantToCategory.put("signature bank", "transfer");
                merchantToCategory.put("silicon valley bank", "transfer");
                merchantToCategory.put("sophtron", "transfer");
                merchantToCategory.put("specially designated nationals", "transfer");
                merchantToCategory.put("suspicious activity report", "transfer");
                merchantToCategory.put("svb", "transfer");
                merchantToCategory.put("swift", "transfer");
                merchantToCategory.put("swift code", "transfer");
                merchantToCategory.put("target 2", "transfer");
                merchantToCategory.put("target instant payment settlement", "transfer");
                merchantToCategory.put("target2", "transfer");
                merchantToCategory.put("target2 transfer", "transfer");
                merchantToCategory.put("teller", "transfer");
                merchantToCategory.put("teller.io", "transfer");
                merchantToCategory.put("tips", "transfer");
                merchantToCategory.put("transfer between", "transfer");
                merchantToCategory.put("transfer fee", "transfer");
                merchantToCategory.put("transfer from", "transfer");
                merchantToCategory.put("transfer processing", "transfer");
                merchantToCategory.put("transfer service", "transfer");
                merchantToCategory.put("transfer to", "transfer");
                merchantToCategory.put("transferred", "transfer");
                merchantToCategory.put("transferring", "transfer");
                merchantToCategory.put("transfers", "transfer");
                merchantToCategory.put("unified payments interface", "transfer");
                merchantToCategory.put("upi", "transfer");
                merchantToCategory.put("upi india", "transfer");
                merchantToCategory.put("usaa bank", "transfer");
                merchantToCategory.put("yodlee", "transfer");

                merchantCount += 279;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
                            MAX_MERCHANTS);
                    return;
                }

                // ========== TRANSPORTATION (321 merchants) ==========
                merchantToCategory.put("7-eleven", "transportation");
                merchantToCategory.put("76", "transportation");
                merchantToCategory.put("76 gas", "transportation");
                merchantToCategory.put("76 station", "transportation");
                merchantToCategory.put("7eleven", "transportation");
                merchantToCategory.put("airport cart", "transportation");
                merchantToCategory.put("airport chair", "transportation");
                merchantToCategory.put("alfa romeo car service", "transportation");
                merchantToCategory.put("alfa romeo ctr", "transportation");
                merchantToCategory.put("alfa romeocarservice", "transportation");
                merchantToCategory.put("alfa romeoctr", "transportation");
                merchantToCategory.put("am/pm", "transportation");
                // (Previously "amex airlines fee reimbursement" → transportation;
                // fixed to "travel" in the TRAVEL block below.)
                merchantToCategory.put("ampm", "transportation");
                merchantToCategory.put("arco", "transportation");
                merchantToCategory.put("arco express", "transportation");
                merchantToCategory.put("arco express gas", "transportation");
                merchantToCategory.put("aston martin car service", "transportation");
                merchantToCategory.put("aston martin ctr", "transportation");
                merchantToCategory.put("aston martincarservice", "transportation");
                merchantToCategory.put("aston martinctr", "transportation");
                merchantToCategory.put("audi car service", "transportation");
                merchantToCategory.put("audi ctr", "transportation");
                merchantToCategory.put("audicarservice", "transportation");
                merchantToCategory.put("audictr", "transportation");
                merchantToCategory.put("automobile", "transportation");
                merchantToCategory.put("bentley car service", "transportation");
                merchantToCategory.put("bentley ctr", "transportation");
                merchantToCategory.put("bentleycarservice", "transportation");
                merchantToCategory.put("bentleyctr", "transportation");
                merchantToCategory.put("bmw car service", "transportation");
                merchantToCategory.put("bmw ctr", "transportation");
                merchantToCategory.put("bmwcarservice", "transportation");
                merchantToCategory.put("bmwctr", "transportation");
                merchantToCategory.put("bolt", "transportation");
                merchantToCategory.put("bp", "transportation");
                merchantToCategory.put("bp fuel", "transportation");
                merchantToCategory.put("bp gas", "transportation");
                merchantToCategory.put("bp station", "transportation");
                merchantToCategory.put("bridge toll", "transportation");
                merchantToCategory.put("british petroleum", "transportation");
                merchantToCategory.put("buc-ee", "transportation");
                merchantToCategory.put("buc-ee's", "transportation");
                merchantToCategory.put("buc-ees", "transportation");
                merchantToCategory.put("bucee", "transportation");
                merchantToCategory.put("bucees", "transportation");
                merchantToCategory.put("bugatti car service", "transportation");
                merchantToCategory.put("bugatti ctr", "transportation");
                merchantToCategory.put("bugatticarservice", "transportation");
                merchantToCategory.put("bugattictr", "transportation");
                merchantToCategory.put("bus", "transportation");
                merchantToCategory.put("cab", "transportation");
                merchantToCategory.put("california dot", "transportation");
                merchantToCategory.put("caltrans", "transportation");
                merchantToCategory.put("car service", "transportation");
                merchantToCategory.put("careem", "transportation");
                merchantToCategory.put("carservice", "transportation");
                merchantToCategory.put("chevrolet car service", "transportation");
                merchantToCategory.put("chevrolet ctr", "transportation");
                merchantToCategory.put("chevroletcarservice", "transportation");
                merchantToCategory.put("chevroletctr", "transportation");
                merchantToCategory.put("chevron", "transportation");
                merchantToCategory.put("chrysler car service", "transportation");
                merchantToCategory.put("chrysler ctr", "transportation");
                merchantToCategory.put("chryslercarservice", "transportation");
                merchantToCategory.put("chryslerctr", "transportation");
                merchantToCategory.put("circle k", "transportation");
                merchantToCategory.put("circlek", "transportation");
                merchantToCategory.put("citgo", "transportation");
                merchantToCategory.put("commute", "transportation");
                merchantToCategory.put("conoco", "transportation");
                merchantToCategory.put("conocophillips", "transportation");
                merchantToCategory.put("costco gas", "transportation");
                merchantToCategory.put("costcogas", "transportation");
                merchantToCategory.put("department of transportation", "transportation");
                merchantToCategory.put("didi", "transportation");
                merchantToCategory.put("diesel", "transportation");
                merchantToCategory.put("dodge car service", "transportation");
                merchantToCategory.put("dodge ctr", "transportation");
                merchantToCategory.put("dodgecarservice", "transportation");
                merchantToCategory.put("dodgectr", "transportation");
                merchantToCategory.put("dot toll", "transportation");
                merchantToCategory.put("e-zpass", "transportation");
                merchantToCategory.put("epass", "transportation");
                merchantToCategory.put("era toll", "transportation");
                merchantToCategory.put("eractoll", "transportation");
                merchantToCategory.put("eratoll", "transportation");
                merchantToCategory.put("esso", "transportation");
                merchantToCategory.put("expressway toll", "transportation");
                merchantToCategory.put("exxon", "transportation");
                merchantToCategory.put("exxonmobil", "transportation");
                merchantToCategory.put("ez pass", "transportation");
                merchantToCategory.put("ez tag", "transportation");
                merchantToCategory.put("ezpass", "transportation");
                merchantToCategory.put("fastrak", "transportation");
                merchantToCategory.put("fdot", "transportation");
                merchantToCategory.put("ferrari car service", "transportation");
                merchantToCategory.put("ferrari ctr", "transportation");
                merchantToCategory.put("ferraricarservice", "transportation");
                merchantToCategory.put("ferrarictr", "transportation");
                merchantToCategory.put("fiat car service", "transportation");
                merchantToCategory.put("fiat ctr", "transportation");
                merchantToCategory.put("fiatacarservice", "transportation");
                merchantToCategory.put("fiatctr", "transportation");
                merchantToCategory.put("fill up", "transportation");
                merchantToCategory.put("filling station", "transportation");
                merchantToCategory.put("florida dot", "transportation");
                merchantToCategory.put("flying j", "transportation");
                merchantToCategory.put("flyingj", "transportation");
                merchantToCategory.put("ford car service", "transportation");
                merchantToCategory.put("ford ctr", "transportation");
                merchantToCategory.put("fordcarservice", "transportation");
                merchantToCategory.put("fordctr", "transportation");
                merchantToCategory.put("fuel purchase", "transportation");
                merchantToCategory.put("fuel station", "transportation");
                merchantToCategory.put("garage", "transportation");
                merchantToCategory.put("garden state parkway", "transportation");
                merchantToCategory.put("gas station", "transportation");
                merchantToCategory.put("gasoline", "transportation");
                merchantToCategory.put("gett", "transportation");
                merchantToCategory.put("gmc car service", "transportation");
                merchantToCategory.put("gmc ctr", "transportation");
                merchantToCategory.put("gmccarservice", "transportation");
                merchantToCategory.put("gmcctr", "transportation");
                merchantToCategory.put("good to go", "transportation");
                merchantToCategory.put("good-to-go", "transportation");
                merchantToCategory.put("goodtogo", "transportation");
                merchantToCategory.put("grab", "transportation");
                merchantToCategory.put("highway authority", "transportation");
                merchantToCategory.put("highway toll", "transportation");
                merchantToCategory.put("hona car service", "transportation");
                merchantToCategory.put("hona ctr", "transportation");
                merchantToCategory.put("honacarservice", "transportation");
                merchantToCategory.put("honactr", "transportation");
                merchantToCategory.put("honda car service", "transportation");
                merchantToCategory.put("honda ctr", "transportation");
                merchantToCategory.put("hondacarservice", "transportation");
                merchantToCategory.put("hondactr", "transportation");
                merchantToCategory.put("hyundai car service", "transportation");
                merchantToCategory.put("hyundai ctr", "transportation");
                merchantToCategory.put("hyundaicarservice", "transportation");
                merchantToCategory.put("hyundaictr", "transportation");
                merchantToCategory.put("idot", "transportation");
                merchantToCategory.put("illinois dot", "transportation");
                merchantToCategory.put("impark", "transportation");
                merchantToCategory.put("ipass", "transportation");
                merchantToCategory.put("irs car and truck expenses", "transportation");
                merchantToCategory.put("jeep car service", "transportation");
                merchantToCategory.put("jeep ctr", "transportation");
                merchantToCategory.put("jeepcarservice", "transportation");
                merchantToCategory.put("jeepctr", "transportation");
                merchantToCategory.put("kia car service", "transportation");
                merchantToCategory.put("kia ctr", "transportation");
                merchantToCategory.put("kiacarservice", "transportation");
                merchantToCategory.put("kiactr", "transportation");
                merchantToCategory.put("kwik sak", "transportation");
                merchantToCategory.put("kwik-sak", "transportation");
                merchantToCategory.put("kwiksak", "transportation");
                merchantToCategory.put("lamborghini car service", "transportation");
                merchantToCategory.put("lamborghini ctr", "transportation");
                merchantToCategory.put("lamborghinicarservice", "transportation");
                merchantToCategory.put("lamborghinictr", "transportation");
                merchantToCategory.put("land rover car service", "transportation");
                merchantToCategory.put("land rover ctr", "transportation");
                merchantToCategory.put("land rovercarservice", "transportation");
                merchantToCategory.put("land roverctr", "transportation");
                merchantToCategory.put("london underground", "transportation");
                merchantToCategory.put("love's", "transportation");
                merchantToCategory.put("loves", "transportation");
                merchantToCategory.put("loves travel stops", "transportation");
                merchantToCategory.put("lul ticket mach", "transportation");
                merchantToCategory.put("lul ticket machine", "transportation");
                merchantToCategory.put("lulticketmachine", "transportation");
                merchantToCategory.put("lyft", "transportation");
                merchantToCategory.put("lyft ride", "transportation");
                merchantToCategory.put("marathon", "transportation");
                merchantToCategory.put("marathon petroleum", "transportation");
                merchantToCategory.put("maryland dot", "transportation");
                merchantToCategory.put("massachusetts dot", "transportation");
                merchantToCategory.put("massdot", "transportation");
                merchantToCategory.put("mcc 5541", "transportation");
                merchantToCategory.put("mcc5541", "transportation");
                merchantToCategory.put("mclaren car service", "transportation");
                merchantToCategory.put("mclaren ctr", "transportation");
                merchantToCategory.put("mclarencarservice", "transportation");
                merchantToCategory.put("mclarenctr", "transportation");
                merchantToCategory.put("mdot", "transportation");
                merchantToCategory.put("mercedes-benz car service", "transportation");
                merchantToCategory.put("mercedes-benz ctr", "transportation");
                merchantToCategory.put("mercedes-benzcarservice", "transportation");
                merchantToCategory.put("mercedes-benzctr", "transportation");
                merchantToCategory.put("metro", "transportation");
                merchantToCategory.put("metropolis parking", "transportation");
                merchantToCategory.put("metropolisparking", "transportation");
                merchantToCategory.put("mobil", "transportation");
                merchantToCategory.put("murphy usa", "transportation");
                merchantToCategory.put("murphyusa", "transportation");
                merchantToCategory.put("naics 36", "transportation");
                merchantToCategory.put("naics 48", "transportation");
                merchantToCategory.put("new jersey dot", "transportation");
                merchantToCategory.put("new jersey turnpike", "transportation");
                merchantToCategory.put("new york state dot", "transportation");
                merchantToCategory.put("new york thruway", "transportation");
                merchantToCategory.put("nissacarservice", "transportation");
                merchantToCategory.put("nissactr", "transportation");
                merchantToCategory.put("nissan car service", "transportation");
                merchantToCategory.put("nissan ctr", "transportation");
                merchantToCategory.put("njdot", "transportation");
                merchantToCategory.put("nysdot", "transportation");
                merchantToCategory.put("ola", "transportation");
                merchantToCategory.put("park mobile", "transportation");
                merchantToCategory.put("parking", "transportation");
                merchantToCategory.put("parking fee", "transportation");
                merchantToCategory.put("parking meter", "transportation");
                merchantToCategory.put("parkingmeter", "transportation");
                merchantToCategory.put("parkmobile", "transportation");
                merchantToCategory.put("pay by phone", "transportation");
                merchantToCategory.put("paybyphone", "transportation");
                merchantToCategory.put("penn dot", "transportation");
                merchantToCategory.put("penndot", "transportation");
                merchantToCategory.put("pennsylvania dot", "transportation");
                merchantToCategory.put("petrol", "transportation");
                merchantToCategory.put("petrol fill up", "transportation");
                merchantToCategory.put("petrol station", "transportation");
                merchantToCategory.put("phillips 66", "transportation");
                merchantToCategory.put("phillips66", "transportation");
                merchantToCategory.put("pilot", "transportation");
                merchantToCategory.put("pilot flying j", "transportation");
                merchantToCategory.put("porsche car service", "transportation");
                merchantToCategory.put("porsche ctr", "transportation");
                merchantToCategory.put("porschecarservice", "transportation");
                merchantToCategory.put("porschectr", "transportation");
                merchantToCategory.put("public transport", "transportation");
                merchantToCategory.put("qt", "transportation");
                merchantToCategory.put("quik trip", "transportation");
                merchantToCategory.put("quiktrip", "transportation");
                merchantToCategory.put("ram car service", "transportation");
                merchantToCategory.put("ram ctr", "transportation");
                merchantToCategory.put("ramcarservice", "transportation");
                merchantToCategory.put("ramctr", "transportation");
                merchantToCategory.put("ride", "transportation");
                merchantToCategory.put("ride share", "transportation");
                merchantToCategory.put("rideshare", "transportation");
                merchantToCategory.put("road toll", "transportation");
                merchantToCategory.put("rolls-royce car service", "transportation");
                merchantToCategory.put("rolls-royce ctr", "transportation");
                merchantToCategory.put("rolls-roycecarservice", "transportation");
                merchantToCategory.put("rolls-roycectr", "transportation");
                merchantToCategory.put("seattle airport", "transportation");
                merchantToCategory.put("seattle ap", "transportation");
                merchantToCategory.put("seattleap", "transportation");
                merchantToCategory.put("seattleap cart", "transportation");
                merchantToCategory.put("seattleap cart/chair", "transportation");
                merchantToCategory.put("seattleap chair", "transportation");
                merchantToCategory.put("service station", "transportation");
                merchantToCategory.put("sheetz", "transportation");
                merchantToCategory.put("shell", "transportation");
                merchantToCategory.put("sic 37", "transportation");
                merchantToCategory.put("sic 42", "transportation");
                merchantToCategory.put("sic 44", "transportation");
                merchantToCategory.put("sic 45", "transportation");
                merchantToCategory.put("sic 47", "transportation");
                merchantToCategory.put("sic 75", "transportation");
                merchantToCategory.put("speedway", "transportation");
                merchantToCategory.put("state department of transportation", "transportation");
                merchantToCategory.put("state dot", "transportation");
                merchantToCategory.put("sunoco", "transportation");
                merchantToCategory.put("sunpass", "transportation");
                merchantToCategory.put("ta", "transportation");
                merchantToCategory.put("taxi", "transportation");
                merchantToCategory.put("texas dot", "transportation");
                merchantToCategory.put("ticket machine", "transportation");
                merchantToCategory.put("ticketmachine", "transportation");
                merchantToCategory.put("toll", "transportation");
                merchantToCategory.put("toll authority", "transportation");
                merchantToCategory.put("toll charge", "transportation");
                merchantToCategory.put("toll fee", "transportation");
                merchantToCategory.put("toll payment", "transportation");
                merchantToCategory.put("toll road", "transportation");
                merchantToCategory.put("tollway", "transportation");
                merchantToCategory.put("toyota car service", "transportation");
                merchantToCategory.put("toyota ctr", "transportation");
                merchantToCategory.put("toyotacarservice", "transportation");
                merchantToCategory.put("toyotactr", "transportation");
                merchantToCategory.put("train", "transportation");
                merchantToCategory.put("transit", "transportation");
                merchantToCategory.put("transport", "transportation");
                merchantToCategory.put("transportation authority", "transportation");
                merchantToCategory.put("travel centers", "transportation");
                merchantToCategory.put("travelcenters of america", "transportation");
                merchantToCategory.put("truck stop", "transportation");
                merchantToCategory.put("tunnel toll", "transportation");
                merchantToCategory.put("turnpike authority", "transportation");
                merchantToCategory.put("txdot", "transportation");
                merchantToCategory.put("txtag", "transportation");
                merchantToCategory.put("uber", "transportation");
                merchantToCategory.put("uber ride", "transportation");
                merchantToCategory.put("union 76", "transportation");
                merchantToCategory.put("union76", "transportation");
                merchantToCategory.put("unocal 76", "transportation");
                merchantToCategory.put("uw pay by phone", "transportation");
                merchantToCategory.put("uw paybyphone", "transportation");
                merchantToCategory.put("uwpay by phone", "transportation");
                merchantToCategory.put("uwpaybyphone", "transportation");
                merchantToCategory.put("valero", "transportation");
                merchantToCategory.put("vdot", "transportation");
                merchantToCategory.put("vehicle", "transportation");
                merchantToCategory.put("virginia dot", "transportation");
                merchantToCategory.put("volkswagen car service", "transportation");
                merchantToCategory.put("volkswagen ctr", "transportation");
                merchantToCategory.put("volkswagencarservice", "transportation");
                merchantToCategory.put("volkswagenctr", "transportation");
                merchantToCategory.put("volvo car service", "transportation");
                merchantToCategory.put("volvo ctr", "transportation");
                merchantToCategory.put("volvocarservice", "transportation");
                merchantToCategory.put("volvoctr", "transportation");
                merchantToCategory.put(
                        "washington state department of transportation", "transportation");
                merchantToCategory.put("washington state dot", "transportation");
                merchantToCategory.put("wawa", "transportation");
                merchantToCategory.put("wsdot", "transportation");

                merchantCount += 321;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
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
                merchantToCategory.put("moved to standard purch", "other");
                merchantToCategory.put("moved to standard purchase", "other");
                merchantToCategory.put("offer moved to standard", "other");
                merchantToCategory.put("standard purch", "other");

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
                merchantToCategory.put("tsa precheck", "travel");
                merchantToCategory.put("tsa prechk", "travel");
                merchantToCategory.put("precheck", "travel");
                merchantToCategory.put("global entry", "travel");
                merchantToCategory.put("globalentry", "travel");
                merchantToCategory.put("clear plus", "travel");
                merchantToCategory.put("passport services", "travel");
                // Check/ACH descriptions often concatenate: "PASSPORTSERVICES"
                merchantToCategory.put("passportservices", "travel");
                merchantToCategory.put("passport fee", "travel");
                merchantToCategory.put("us passport", "travel");
                merchantToCategory.put("visa application", "travel");
                merchantToCategory.put("visa service", "travel");
                merchantToCategory.put("visa services", "travel");
                merchantToCategory.put("immigration service", "travel");
                merchantToCategory.put("ttp cbp", "travel"); // Trusted Traveler Programs
                merchantToCategory.put("airline fee", "travel");
                merchantToCategory.put("airline fee reimbursement", "travel");
                merchantToCategory.put("airline reimbursement", "travel");
                merchantToCategory.put("amex airline fee reimbursement", "travel");
                merchantToCategory.put("amex airlines fee reimbursement", "travel");
                merchantToCategory.put("baggage fee", "travel");
                merchantToCategory.put("viasat", "travel"); // in-flight wifi
                merchantToCategory.put("gogo inflight", "travel");
                merchantToCategory.put("admirals club", "travel");
                merchantToCategory.put("admiralsclub", "travel");
                merchantToCategory.put("airbnb", "travel");
                merchantToCategory.put("airline", "travel");
                merchantToCategory.put("airlines", "travel");
                merchantToCategory.put("airport loung", "travel");
                merchantToCategory.put("airport lounge", "travel");
                merchantToCategory.put("airportlounge", "travel");
                merchantToCategory.put("alaska", "travel");
                merchantToCategory.put("allegiant", "travel");
                merchantToCategory.put("american airlines", "travel");
                merchantToCategory.put("american express centurion", "travel");
                merchantToCategory.put("american express lounge", "travel");
                merchantToCategory.put("americanairlines", "travel");
                merchantToCategory.put("amex centurion", "travel");
                merchantToCategory.put("amex lounge", "travel");
                merchantToCategory.put("axp centurion", "travel");
                merchantToCategory.put("axpcenturion", "travel");
                merchantToCategory.put("best western", "travel");
                merchantToCategory.put("booking.com", "travel");
                merchantToCategory.put("centurion lounge", "travel");
                merchantToCategory.put("centurionlounge", "travel");
                merchantToCategory.put("courtyard", "travel");
                merchantToCategory.put("delta", "travel");
                merchantToCategory.put("delta air lines", "travel");
                merchantToCategory.put("delta airlines", "travel");
                merchantToCategory.put("delta sky club", "travel");
                merchantToCategory.put("deltaskyclub", "travel");
                merchantToCategory.put("embassy suites", "travel");
                merchantToCategory.put("encalm", "travel");
                merchantToCategory.put("encalm lounge", "travel");
                merchantToCategory.put("encalmlounge", "travel");
                merchantToCategory.put("expedia", "travel");
                merchantToCategory.put("frontier", "travel");
                merchantToCategory.put("hampton inn", "travel");
                merchantToCategory.put("hawaiian", "travel");
                merchantToCategory.put("hilton", "travel");
                merchantToCategory.put("holiday inn", "travel");
                merchantToCategory.put("holidayinn", "travel");
                merchantToCategory.put("hotel", "travel");
                merchantToCategory.put("hotels.com", "travel");
                merchantToCategory.put("hyatt", "travel");
                merchantToCategory.put("inn", "travel");
                merchantToCategory.put("irs travel", "travel");
                merchantToCategory.put("jetblue", "travel");
                merchantToCategory.put("kayak", "travel");
                merchantToCategory.put("lounge", "travel");
                merchantToCategory.put("lyft pink", "travel");
                merchantToCategory.put("lyftpink", "travel");
                merchantToCategory.put("marriott", "travel");
                merchantToCategory.put("motel", "travel");
                merchantToCategory.put("orbitz", "travel");
                merchantToCategory.put("pink membership", "travel");
                merchantToCategory.put("pink subscription", "travel");
                merchantToCategory.put("plaza premium lounge", "travel");
                merchantToCategory.put("plazapremiumlounge", "travel");
                merchantToCategory.put("priceline", "travel");
                merchantToCategory.put("priority pass", "travel");
                merchantToCategory.put("prioritypass", "travel");
                merchantToCategory.put("residence inn", "travel");
                merchantToCategory.put("residential inn", "travel");
                merchantToCategory.put("resort", "travel");
                merchantToCategory.put("southwest", "travel");
                merchantToCategory.put("spirit", "travel");
                merchantToCategory.put("travelocity", "travel");
                merchantToCategory.put("uber one", "travel");
                merchantToCategory.put("uber one membership", "travel");
                merchantToCategory.put("uber one subscription", "travel");
                merchantToCategory.put("uberone", "travel");
                merchantToCategory.put("uberone membership", "travel");
                merchantToCategory.put("uberone subscription", "travel");
                merchantToCategory.put("united", "travel");
                merchantToCategory.put("united club", "travel");
                merchantToCategory.put("unitedclub", "travel");
                merchantToCategory.put("vrbo", "travel");

                merchantCount += 75;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
                            MAX_MERCHANTS);
                    return;
                }

                // ========== UTILITIES (193 merchants) ==========
                merchantToCategory.put("aep", "utilities");
                merchantToCategory.put("aes", "utilities");
                merchantToCategory.put("aes corporation", "utilities");
                merchantToCategory.put("altice", "utilities");
                merchantToCategory.put("american electric", "utilities");
                merchantToCategory.put("american electric power", "utilities");
                merchantToCategory.put("american water", "utilities");
                merchantToCategory.put("annual bill", "utilities");
                merchantToCategory.put("aqua america", "utilities");
                merchantToCategory.put("at and t", "utilities");
                merchantToCategory.put("at&t", "utilities");
                merchantToCategory.put("att", "utilities");
                merchantToCategory.put("att u-verse", "utilities");
                merchantToCategory.put("att uverse", "utilities");
                merchantToCategory.put("auto bill pay", "utilities");
                merchantToCategory.put("auto bill payment", "utilities");
                merchantToCategory.put("automatic bill pay", "utilities");
                merchantToCategory.put("bill", "utilities");
                merchantToCategory.put("bill due", "utilities");
                merchantToCategory.put("bill invoice", "utilities");
                merchantToCategory.put("bill pay", "utilities");
                merchantToCategory.put("bill pay provider", "utilities");
                merchantToCategory.put("bill pay service", "utilities");
                merchantToCategory.put("bill payment", "utilities");
                merchantToCategory.put("bill payment provider", "utilities");
                merchantToCategory.put("bill payment service", "utilities");
                merchantToCategory.put("bill reminder", "utilities");
                merchantToCategory.put("bill statement", "utilities");
                merchantToCategory.put("billpay", "utilities");
                merchantToCategory.put("billpay service", "utilities");
                merchantToCategory.put("billpaying", "utilities");
                merchantToCategory.put("bills", "utilities");
                merchantToCategory.put("boost mobile", "utilities");
                merchantToCategory.put("broadband", "utilities");
                merchantToCategory.put("cable bill", "utilities");
                merchantToCategory.put("cable tv", "utilities");
                merchantToCategory.put("california water service", "utilities");
                merchantToCategory.put("centerpoint", "utilities");
                merchantToCategory.put("centerpoint energy", "utilities");
                merchantToCategory.put("century link", "utilities");
                merchantToCategory.put("centurylink", "utilities");
                merchantToCategory.put("charter", "utilities");
                merchantToCategory.put("charter spectrum", "utilities");
                merchantToCategory.put("city of bellevue", "utilities");
                merchantToCategory.put("city of seattle", "utilities");
                merchantToCategory.put("city utility", "utilities");
                merchantToCategory.put("comcast", "utilities");
                merchantToCategory.put("con edison", "utilities");
                merchantToCategory.put("coned", "utilities");
                merchantToCategory.put("consolidated edison", "utilities");
                merchantToCategory.put("cox", "utilities");
                merchantToCategory.put("cox communications", "utilities");
                merchantToCategory.put("cricket", "utilities");
                merchantToCategory.put("cricket wireless", "utilities");
                merchantToCategory.put("direct tv", "utilities");
                merchantToCategory.put("directv", "utilities");
                merchantToCategory.put("dish", "utilities");
                merchantToCategory.put("dish network", "utilities");
                merchantToCategory.put("dominion energy", "utilities");
                merchantToCategory.put("dominionenergy", "utilities");
                merchantToCategory.put("duke energy", "utilities");
                merchantToCategory.put("dukeenergy", "utilities");
                merchantToCategory.put("edison", "utilities");
                merchantToCategory.put("electric bill", "utilities");
                merchantToCategory.put("electric company", "utilities");
                merchantToCategory.put("electric service", "utilities");
                merchantToCategory.put("electricity", "utilities");
                merchantToCategory.put("electronic bill pay", "utilities");
                merchantToCategory.put("ener billpay", "utilities");
                merchantToCategory.put("energy", "utilities");
                merchantToCategory.put("energy bill", "utilities");
                merchantToCategory.put("energy billpay", "utilities");
                merchantToCategory.put("energy company", "utilities");
                merchantToCategory.put("entergy", "utilities");
                merchantToCategory.put("exelon", "utilities");
                merchantToCategory.put("fios", "utilities");
                merchantToCategory.put("first energy", "utilities");
                merchantToCategory.put("firstenergy", "utilities");
                merchantToCategory.put("frontier communications", "utilities");
                merchantToCategory.put("gas bill", "utilities");
                merchantToCategory.put("gas company", "utilities");
                merchantToCategory.put("gas service", "utilities");
                merchantToCategory.put("gas utility", "utilities");
                merchantToCategory.put("google fi", "utilities");
                merchantToCategory.put("heating", "utilities");
                merchantToCategory.put("insurance bill", "utilities");
                merchantToCategory.put("internet bill", "utilities");
                merchantToCategory.put("internet service", "utilities");
                merchantToCategory.put("irs utilities", "utilities");
                merchantToCategory.put("loan bill", "utilities");
                merchantToCategory.put("mcc 4814", "utilities");
                merchantToCategory.put("mcc 4816", "utilities");
                merchantToCategory.put("mcc 4900", "utilities");
                merchantToCategory.put("mcc4814", "utilities");
                merchantToCategory.put("mcc4816", "utilities");
                merchantToCategory.put("mcc4900", "utilities");
                merchantToCategory.put("mediacom", "utilities");
                merchantToCategory.put("medical bill", "utilities");
                merchantToCategory.put("metro pcs", "utilities");
                merchantToCategory.put("metropcs", "utilities");
                merchantToCategory.put("mint mobile", "utilities");
                merchantToCategory.put("mobile", "utilities");
                merchantToCategory.put("monthly bill", "utilities");
                merchantToCategory.put("mortgage bill", "utilities");
                merchantToCategory.put("municipal utility", "utilities");
                merchantToCategory.put("naics 21", "utilities");
                merchantToCategory.put("naics 22", "utilities");
                merchantToCategory.put("naics 49", "utilities");
                merchantToCategory.put("naics 92", "utilities");
                merchantToCategory.put("national grid", "utilities");
                merchantToCategory.put("natural gas", "utilities");
                merchantToCategory.put("online bill pay", "utilities");
                merchantToCategory.put("online bill payment", "utilities");
                merchantToCategory.put("optimum", "utilities");
                merchantToCategory.put("pacific gas", "utilities");
                merchantToCategory.put("pacific gas and electric", "utilities");
                merchantToCategory.put("pacific power", "utilities");
                merchantToCategory.put("pg&e", "utilities");
                merchantToCategory.put("pge", "utilities");
                merchantToCategory.put("phone bill", "utilities");
                merchantToCategory.put("portland general electric", "utilities");
                merchantToCategory.put("power", "utilities");
                merchantToCategory.put("power bill", "utilities");
                merchantToCategory.put("power company", "utilities");
                merchantToCategory.put("pse", "utilities");
                merchantToCategory.put("public utility", "utilities");
                merchantToCategory.put("puget sound energy", "utilities");
                merchantToCategory.put("quarterly bill", "utilities");
                merchantToCategory.put("recurring bill", "utilities");
                merchantToCategory.put("san diego gas", "utilities");
                merchantToCategory.put("san diego gas & electric", "utilities");
                merchantToCategory.put("sce", "utilities");
                merchantToCategory.put("sdge", "utilities");
                merchantToCategory.put("seattle public utilities", "utilities");
                merchantToCategory.put("service provider", "utilities");
                merchantToCategory.put("sewage", "utilities");
                merchantToCategory.put("sewer", "utilities");
                merchantToCategory.put("sic 10", "utilities");
                merchantToCategory.put("sic 13", "utilities");
                merchantToCategory.put("sic 21", "utilities");
                merchantToCategory.put("sic 29", "utilities");
                merchantToCategory.put("sic 40", "utilities");
                merchantToCategory.put("sic 41", "utilities");
                merchantToCategory.put("sic 48", "utilities");
                merchantToCategory.put("sic 49", "utilities");
                merchantToCategory.put("sic 91", "utilities");
                merchantToCategory.put("sic 92", "utilities");
                merchantToCategory.put("sic 93", "utilities");
                merchantToCategory.put("sic 94", "utilities");
                merchantToCategory.put("sic 95", "utilities");
                merchantToCategory.put("sic 96", "utilities");
                merchantToCategory.put("sic 97", "utilities");
                merchantToCategory.put("southern california edison", "utilities");
                merchantToCategory.put("southern company", "utilities");
                merchantToCategory.put("southerncompany", "utilities");
                merchantToCategory.put("spectrum", "utilities");
                merchantToCategory.put("sprint", "utilities");
                merchantToCategory.put("spu", "utilities");
                merchantToCategory.put("straight talk", "utilities");
                merchantToCategory.put("streaming", "utilities");
                merchantToCategory.put("suddenlink", "utilities");
                merchantToCategory.put("suez water", "utilities");
                merchantToCategory.put("t mobile", "utilities");
                merchantToCategory.put("t-mobile", "utilities");
                merchantToCategory.put("telephone", "utilities");
                merchantToCategory.put("television", "utilities");
                merchantToCategory.put("tmobile", "utilities");
                merchantToCategory.put("tv", "utilities");
                merchantToCategory.put("tv bill", "utilities");
                merchantToCategory.put("us cellular", "utilities");
                merchantToCategory.put("uscellular", "utilities");
                merchantToCategory.put("utilities", "utilities");
                merchantToCategory.put("utility bill", "utilities");
                merchantToCategory.put("utility bill pay", "utilities");
                merchantToCategory.put("utility bill payment", "utilities");
                merchantToCategory.put("utility company", "utilities");
                merchantToCategory.put("utility payment", "utilities");
                merchantToCategory.put("utility provider", "utilities");
                merchantToCategory.put("verizon", "utilities");
                merchantToCategory.put("verizon fios", "utilities");
                merchantToCategory.put("verizon wireless", "utilities");
                merchantToCategory.put("visible", "utilities");
                merchantToCategory.put("water bill", "utilities");
                merchantToCategory.put("water company", "utilities");
                merchantToCategory.put("water service", "utilities");
                merchantToCategory.put("water utility", "utilities");
                merchantToCategory.put("wi-fi", "utilities");
                merchantToCategory.put("wifi", "utilities");
                merchantToCategory.put("windstream", "utilities");
                merchantToCategory.put("xcel energy", "utilities");
                merchantToCategory.put("xfinity", "utilities");
                merchantToCategory.put("xfinity mobile", "utilities");
                merchantToCategory.put("xfinitymobile", "utilities");

                merchantCount += 193;
                // CRITICAL: Boundary check after each category
                if (merchantCount > MAX_MERCHANTS) {
                    LOGGER.error(
                            "Merchant count exceeded safety limit ({}). Stopping data load.",
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
        if (candidate == null || !"payment".equalsIgnoreCase(candidate.category)) {
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
        return keyword.contains("payment")
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
