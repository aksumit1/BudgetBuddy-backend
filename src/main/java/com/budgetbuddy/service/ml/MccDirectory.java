package com.budgetbuddy.service.ml;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * First-pass Merchant Category Code (MCC) registry.
 *
 * <p>MCC is a 4-digit code card networks assign to every merchant. Bank statements rarely print it,
 * but knowing the MCC lets us categorize with near-perfect precision. This registry gives us two
 * things:
 *
 * <ol>
 *   <li>A <b>curated merchant-keyword → MCC</b> map, for the top ~200 merchants we can identify
 *       from raw statement text. Bootstraps the system without a licensed merchant directory.
 *   <li>A <b>MCC → category</b> map covering the ~50 MCCs that account for the bulk of consumer
 *       spending.
 * </ol>
 *
 * <p>The merchant keyword match is case-insensitive substring — so "STARBUCKS #1234 SEATTLE WA"
 * matches the keyword "starbucks". When multiple keywords match, the longest keyword wins (so
 * "amazon fresh" outranks "amazon" and we get groceries vs general shopping).
 *
 * <p>Extension path is a CSV / table; the in-code map here is the bootstrap. See {@code
 * MCC_PLAN.md} in this directory for the longer-term plan.
 */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
public final class MccDirectory {

    private static final String CHARITY = "charity";

    private static final String DINING = "dining";

    private static final String EDUCATION = "education";

    private static final String ENTERTAINMENT = "entertainment";

    private static final String GROCERIES = "groceries";

    private static final String HEALTH = "health";

    private static final String HEALTHCARE = "healthcare";

    private static final String HOME_IMPROVEMENT = "homeImprovement";

    private static final String SERVICE = "service";

    private static final String SHOPPING = "shopping";

    private static final String SUBSCRIPTIONS = "subscriptions";

    private static final String TRANSPORTATION = "transportation";

    private static final String TRAVEL = "travel";

    private static final String UTILITIES = "utilities";

    private MccDirectory() {
        /* utility class */
    }

    // --- MCC → internal category mapping -----------------------------------
    // Only the consumer-visible categories we support today. Keep in sync
    // with the iOS TransactionCategory enum's rawValues.

    private static final Map<String, String> MCC_TO_CATEGORY = createMccToCategoryMap();

    private static Map<String, String> createMccToCategoryMap() {
        final Map<String, String> m = new HashMap<>();
        // Food & dining
        m.put("5411", GROCERIES); // Grocery stores, supermarkets
        m.put("5412", GROCERIES); // Grocery stores, convenience
        m.put("5441", GROCERIES); // Candy / nut / confectionery
        m.put("5451", GROCERIES); // Dairy products stores
        m.put("5462", DINING); // Bakeries
        m.put("5499", GROCERIES); // Misc food stores
        m.put("5811", DINING); // Caterers
        m.put("5812", DINING); // Eating places / restaurants
        m.put("5813", DINING); // Drinking places / bars
        m.put("5814", DINING); // Fast food restaurants
        // Transportation / fuel
        m.put("5541", TRANSPORTATION); // Service stations (fuel + service)
        m.put("5542", TRANSPORTATION); // Automated fuel dispensers
        m.put("4111", TRANSPORTATION); // Local / commuter passenger transport
        m.put("4121", TRANSPORTATION); // Taxis / limos
        m.put("4112", TRANSPORTATION); // Passenger rail
        m.put("4131", TRANSPORTATION); // Bus lines
        m.put("4511", TRAVEL); // Airlines
        m.put("3000", TRAVEL); // UA / major airlines range start
        m.put("3999", TRAVEL); // major airlines range end
        m.put("4722", TRAVEL); // Travel agencies
        m.put("7011", TRAVEL); // Hotels / lodging
        // Utilities & services
        m.put("4814", UTILITIES); // Telecom
        m.put("4816", UTILITIES); // Cable / internet
        m.put("4899", SUBSCRIPTIONS); // Cable / streaming
        m.put("4900", UTILITIES); // Utilities (electric, gas, water, sanitary)
        m.put("4812", SUBSCRIPTIONS); // Cellular / phone services
        m.put("5968", SUBSCRIPTIONS); // Direct-marketing continuity / subscription
        // Healthcare
        m.put("5912", HEALTHCARE); // Drug stores / pharmacies
        m.put("8011", HEALTHCARE); // Physicians
        m.put("8021", HEALTHCARE); // Dentists
        m.put("8062", HEALTHCARE); // Hospitals
        m.put("8071", HEALTHCARE); // Medical labs
        m.put("8099", HEALTHCARE); // Health services (other)
        m.put("8050", HEALTHCARE); // Nursing / assisted living
        // Shopping
        m.put("5311", SHOPPING); // Department stores
        m.put("5310", SHOPPING); // Discount stores
        m.put("5651", SHOPPING); // Family clothing
        m.put("5661", SHOPPING); // Shoe stores
        m.put("5691", SHOPPING); // Men's + women's clothing
        m.put("5712", SHOPPING); // Furniture / home furnishings
        m.put("5732", "tech"); // Electronics stores
        m.put("5734", "tech"); // Computer software stores
        m.put("5818", "tech"); // Digital goods
        m.put("5942", SHOPPING); // Book stores
        m.put("5945", SHOPPING); // Hobby / toy / game
        m.put("5999", SHOPPING); // Misc retail
        // Entertainment
        m.put("7832", ENTERTAINMENT); // Motion picture theaters
        m.put("7922", ENTERTAINMENT); // Theatrical producers
        m.put("7929", ENTERTAINMENT); // Bands / orchestras
        m.put("7991", ENTERTAINMENT); // Tourist attractions
        m.put("7993", ENTERTAINMENT); // Video amusement game supplies
        m.put("7994", ENTERTAINMENT); // Video game arcades
        // Home / services
        m.put("1711", HOME_IMPROVEMENT); // HVAC / plumbing / sheet metal
        m.put("5211", HOME_IMPROVEMENT); // Lumber / building materials
        m.put("5200", HOME_IMPROVEMENT); // Home supply warehouse
        m.put("5251", HOME_IMPROVEMENT); // Hardware stores
        // Financial / ATMs
        m.put("6010", "cash"); // Manual cash disbursements
        m.put("6011", "cash"); // Automated cash disbursements (ATM)
        m.put("6012", "payment"); // Financial institutions
        // Insurance
        m.put("6300", "insurance"); // Insurance sales / underwriting
        // Education / charity
        m.put("8211", EDUCATION); // Elementary / secondary schools
        m.put("8220", EDUCATION); // Colleges / universities
        m.put("8299", EDUCATION); // Schools — not elsewhere classified
        m.put("8398", CHARITY); // Charitable + social service orgs
        m.put("8641", CHARITY); // Civic / social / fraternal associations
        // Pets
        m.put("0742", "pet"); // Veterinary services
        m.put("5995", "pet"); // Pet shops

        // Expanded coverage added in the second pass:
        // Automotive + repair
        m.put("5531", TRANSPORTATION); // Auto + home supply stores
        m.put("5533", TRANSPORTATION); // Auto parts + accessories
        m.put("5538", TRANSPORTATION); // Auto wholesale
        m.put("7523", TRANSPORTATION); // Parking lots + garages
        m.put("7538", TRANSPORTATION); // Auto service shops
        m.put("7549", TRANSPORTATION); // Towing
        // Travel
        m.put("7512", TRAVEL); // Car rental
        m.put("7513", TRAVEL); // Truck + trailer rental
        m.put("4411", TRAVEL); // Cruise lines
        // Digital + tech
        m.put("5815", ENTERTAINMENT); // Digital goods — media
        m.put("5816", ENTERTAINMENT); // Digital goods — games
        m.put("5817", "tech"); // Digital goods — applications
        // Services (professional + personal)
        m.put("7230", SERVICE); // Beauty / barber
        m.put("7297", SERVICE); // Massage parlours
        m.put("7298", HEALTH); // Spas (distinct from services — health/wellness)
        m.put("7299", SERVICE); // Misc personal services
        m.put("7276", SERVICE); // Tax prep
        m.put("7392", SERVICE); // Management consulting
        m.put("7399", SERVICE); // Business services (misc)
        m.put("8911", SERVICE); // Architectural / engineering
        m.put("8999", SERVICE); // Professional services (not elsewhere)
        // Fitness
        m.put("7997", HEALTH); // Health clubs / gyms
        m.put("7941", HEALTH); // Commercial sports / athletic fields
        // Shopping expansion
        m.put("5611", SHOPPING); // Men's clothing
        m.put("5621", SHOPPING); // Women's ready-to-wear
        m.put("5641", SHOPPING); // Children's + infants' wear
        m.put("5691", SHOPPING); // Men's + women's clothing
        m.put("5722", SHOPPING); // Household appliances
        m.put("5941", SHOPPING); // Sporting goods
        m.put("5944", SHOPPING); // Jewelry + watches
        m.put("5946", SHOPPING); // Camera + photographic supply
        m.put("5947", SHOPPING); // Gift + card + novelty
        m.put("5948", SHOPPING); // Luggage + leather goods
        m.put("5992", SHOPPING); // Florists
        // Alcohol
        m.put("5921", GROCERIES); // Liquor / wine / beer stores
        // Home improvement expansion
        m.put("1520", HOME_IMPROVEMENT); // General contractors
        m.put("1731", HOME_IMPROVEMENT); // Electrical contractors
        m.put("5231", HOME_IMPROVEMENT); // Glass / paint / wallpaper
        m.put("5261", HOME_IMPROVEMENT); // Nurseries / garden supply
        // Real estate + rent
        m.put("6513", "rent"); // Real estate agents + rentals
        // Charity + religious
        m.put("8651", CHARITY); // Political organisations
        m.put("8661", CHARITY); // Religious organisations
        // Investment + brokerage
        m.put("6211", "investment"); // Securities brokers + dealers
        m.put("6051", "transfer"); // FX / money orders / travellers cheques
        // Government / tax / fees
        m.put("9211", "fee"); // Court costs
        m.put("9222", "fee"); // Fines
        m.put("9311", "fee"); // Tax payments
        m.put("9399", "fee"); // Govt services (misc)
        m.put("9402", "fee"); // Postal services (govt)

        return Collections.unmodifiableMap(m);
    }

    // --- Merchant-keyword → MCC inference ---------------------------------
    // Keyword is lowercased; LinkedHashMap so insertion order = priority;
    // lookup walks entries and picks the longest matching key. When two
    // keys tie on length, the first declared wins.

    private static final Map<String, String> MERCHANT_KEYWORD_TO_MCC = createMerchantKeywordMap();

    private static Map<String, String> createMerchantKeywordMap() {
        final Map<String, String> m = new LinkedHashMap<>();
        // Grocery (5411)
        m.put("whole foods", "5411");
        m.put("trader joe", "5411");
        m.put("safeway", "5411");
        m.put("kroger", "5411");
        m.put("publix", "5411");
        m.put("wegmans", "5411");
        m.put("albertsons", "5411");
        m.put("amazon fresh", "5411");
        m.put("instacart", "5411");
        m.put("aldi", "5411");
        m.put("costco", "5411"); // primarily grocery even though wholesale club
        m.put("sams club", "5411");
        m.put("sainsburys", "5411");
        m.put("waitrose", "5411");
        m.put("tesco", "5411");
        m.put("asda", "5411");
        // Dining (5812 / 5814)
        m.put("starbucks", "5814");
        m.put("mcdonalds", "5814");
        m.put("chipotle", "5814");
        m.put("taco bell", "5814");
        m.put("panera", "5814");
        m.put("dominos", "5814");
        m.put("pizza hut", "5814");
        m.put("kfc", "5814");
        m.put("subway", "5814");
        m.put("chick-fil-a", "5814");
        m.put("dunkin", "5814");
        m.put("doordash", "5812");
        m.put("uber eats", "5812");
        m.put("grubhub", "5812");
        m.put("pret a manger", "5814");
        // Fuel / transport
        m.put("shell", "5541");
        m.put("chevron", "5541");
        m.put("exxon", "5541");
        m.put("mobil", "5541");
        m.put("bp ", "5541"); // trailing space so "bpay" doesn't match
        m.put("76 ", "5541"); // awkward but common on receipts
        m.put("arco", "5541");
        m.put("valero", "5541");
        m.put("uber", "4121"); // rideshare
        m.put("lyft", "4121");
        m.put("delta", "4511");
        m.put("united ", "4511");
        m.put("southwest", "4511");
        m.put("american airlines", "4511");
        m.put("jetblue", "4511");
        m.put("alaska airlines", "4511");
        m.put("marriott", "7011");
        m.put("hilton", "7011");
        m.put("hyatt", "7011");
        m.put("airbnb", "7011");
        // Utilities / subscriptions
        m.put("verizon", "4814");
        m.put("at&t", "4814");
        m.put("t-mobile", "4814");
        m.put("comcast", "4816");
        m.put("xfinity", "4816");
        m.put("spectrum", "4816");
        m.put("netflix", "4899");
        m.put("hulu", "4899");
        m.put("spotify", "4899");
        m.put("apple music", "4899");
        m.put("disney plus", "4899");
        m.put("hbo", "4899");
        m.put("youtube", "4899");
        m.put("prime video", "4899");
        m.put("pg&e", "4900");
        m.put("con edison", "4900");
        // Healthcare
        m.put("cvs", "5912");
        m.put("walgreens", "5912");
        m.put("rite aid", "5912");
        m.put("kaiser permanente", "8062");
        // Shopping / tech
        m.put("amazon marketplace", "5999");
        m.put("amazon.com", "5999");
        m.put("amzn mktp", "5999");
        m.put("target", "5311");
        m.put("walmart", "5311");
        m.put("best buy", "5732");
        m.put("apple.com", "5732");
        m.put("microsoft", "5734");
        m.put("adobe", "5734");
        m.put("nike", "5651");
        m.put("zara", "5651");
        m.put("uniqlo", "5651");
        m.put("gap", "5651");
        m.put("old navy", "5651");
        m.put("h&m", "5651");
        // Home improvement
        m.put("home depot", "5211");
        m.put("lowes", "5211");
        m.put("menards", "5211");
        m.put("ace hardware", "5251");
        // Pets
        m.put("chewy", "5995");
        m.put("petco", "5995");
        m.put("petsmart", "5995");

        // === Second-pass expansion ===
        // Grocery (additional chains)
        m.put("ralphs", "5411");
        m.put("vons", "5411");
        m.put("food lion", "5411");
        m.put("harris teeter", "5411");
        m.put("giant eagle", "5411");
        m.put("giant food", "5411");
        m.put("hannaford", "5411");
        m.put("shoprite", "5411");
        m.put("stop & shop", "5411");
        m.put("stop shop", "5411");
        m.put("winn-dixie", "5411");
        m.put("winn dixie", "5411");
        m.put("smart & final", "5411");
        m.put("grocery outlet", "5411");
        m.put("sprouts", "5411");
        m.put("erewhon", "5411");
        m.put("morrisons", "5411");
        m.put("lidl", "5411");
        m.put("big bazaar", "5411"); // India
        m.put("reliance fresh", "5411"); // India
        m.put("more supermarket", "5411"); // India
        // Dining — fast food + chains
        m.put("burger king", "5814");
        m.put("wendys", "5814");
        m.put("arbys", "5814");
        m.put("popeyes", "5814");
        m.put("sonic drive", "5814");
        m.put("panda express", "5814");
        m.put("five guys", "5814");
        m.put("shake shack", "5814");
        m.put("in-n-out", "5814");
        m.put("in n out", "5814");
        m.put("culvers", "5814");
        m.put("white castle", "5814");
        m.put("jimmy john", "5814");
        m.put("jack in the box", "5814");
        m.put("carl's jr", "5814");
        m.put("hardees", "5814");
        m.put("zaxby", "5814");
        m.put("raising cane", "5814");
        m.put("little caesars", "5814");
        m.put("papa johns", "5814");
        m.put("papa murphy", "5814");
        m.put("baskin-robbins", "5814");
        m.put("cold stone", "5814");
        m.put("dairy queen", "5814");
        m.put("tim hortons", "5814");
        m.put("greggs", "5814"); // UK
        m.put("pizza express", "5814"); // UK
        m.put("wagamama", "5814"); // UK
        m.put("nando", "5814"); // UK + SA
        m.put("haldiram", "5814"); // India
        m.put("faasos", "5814"); // India
        // Dining — sit-down chains
        m.put("olive garden", "5812");
        m.put("chilis", "5812");
        m.put("cheesecake factory", "5812");
        m.put("texas roadhouse", "5812");
        m.put("applebee", "5812");
        m.put("ihop", "5812");
        m.put("dennys", "5812");
        m.put("cracker barrel", "5812");
        m.put("outback", "5812");
        m.put("red lobster", "5812");
        m.put("buffalo wild wings", "5812");
        m.put("pf chang", "5812");
        m.put("tgi friday", "5812");
        m.put("the cheesecake factory", "5812");
        // Coffee
        m.put("peet", "5814");
        m.put("caribou coffee", "5814");
        m.put("blue bottle", "5814");
        m.put("philz", "5814");
        m.put("costa coffee", "5814"); // UK
        m.put("caffe nero", "5814"); // UK
        // Meal kit + delivery
        m.put("blue apron", "5812");
        m.put("hellofresh", "5812");
        m.put("factor meal", "5812");
        m.put("freshly", "5812");
        m.put("postmates", "5812");
        m.put("seamless", "5812");
        m.put("zomato", "5812"); // India
        m.put("swiggy", "5812"); // India
        m.put("deliveroo", "5812"); // UK + EU
        // Fuel + auto
        m.put("texaco", "5541");
        m.put("circle k", "5541");
        m.put("sunoco", "5541");
        m.put("marathon", "5541");
        m.put("phillips 66", "5541");
        m.put("citgo", "5541");
        m.put("speedway", "5541");
        m.put("pilot travel", "5541");
        m.put("loves travel", "5541");
        m.put("quik trip", "5541");
        m.put("racetrac", "5541");
        m.put("sheetz", "5541");
        m.put("wawa", "5411"); // Convenience — mostly food/coffee
        m.put("7-eleven", "5411");
        m.put("esso", "5541"); // international
        m.put("total fuel", "5541");
        m.put("indian oil", "5541"); // India
        m.put("hpcl", "5541"); // India
        m.put("bharat petroleum", "5541"); // India
        m.put("jiffy lube", "7538");
        m.put("midas", "7538");
        m.put("firestone", "7538");
        m.put("aamco", "7538");
        m.put("carmax", "5511");
        m.put("carvana", "5511");
        // Rideshare + transit
        m.put("revel", "4121");
        m.put("bird rides", "4121");
        m.put("lime ", "4121"); // trailing space avoids "limestone"
        m.put("bart ", "4112");
        m.put("amtrak", "4112");
        m.put("caltrain", "4112");
        m.put("london underground", "4111");
        m.put("tfl ", "4111"); // Transport for London
        m.put("metrocard", "4111");
        m.put("clipper card", "4111");
        // Airlines (additional)
        m.put("frontier", "4511");
        m.put("spirit airlines", "4511");
        m.put("allegiant", "4511");
        m.put("hawaiian airlines", "4511");
        m.put("air canada", "4511");
        m.put("british airways", "4511");
        m.put("virgin atlantic", "4511");
        m.put("lufthansa", "4511");
        m.put("air france", "4511");
        m.put("klm ", "4511");
        m.put("emirates", "4511");
        m.put("qatar airways", "4511");
        m.put("ana ", "4511");
        m.put("japan airlines", "4511");
        m.put("singapore airlines", "4511");
        m.put("indigo", "4511"); // India
        m.put("vistara", "4511"); // India
        m.put("air india", "4511"); // India
        // Hotels (additional)
        m.put("hilton", "7011");
        m.put("marriott", "7011");
        m.put("hyatt", "7011");
        m.put("holiday inn", "7011");
        m.put("ihg ", "7011");
        m.put("westin", "7011");
        m.put("sheraton", "7011");
        m.put("best western", "7011");
        m.put("courtyard", "7011");
        m.put("fairfield inn", "7011");
        m.put("hampton inn", "7011");
        m.put("doubletree", "7011");
        m.put("wyndham", "7011");
        m.put("oyo rooms", "7011"); // India
        m.put("vrbo", "7011");
        // Telecom + utilities
        m.put("sprint", "4814");
        m.put("cricket wireless", "4814");
        m.put("metro pcs", "4814");
        m.put("boost mobile", "4814");
        m.put("mint mobile", "4814");
        m.put("google fi", "4814");
        m.put("jio", "4814"); // India
        m.put("airtel", "4814"); // India + Africa
        m.put("vodafone", "4814"); // UK + India + EU
        m.put("bt group", "4816"); // UK
        m.put("sky uk", "4899");
        m.put("directv", "4816");
        m.put("dish network", "4816");
        m.put("optimum", "4816");
        m.put("southern california edison", "4900");
        m.put("sce ", "4900");
        m.put("duke energy", "4900");
        m.put("national grid", "4900");
        // Streaming + subscriptions
        m.put("paramount plus", "4899");
        m.put("peacock", "4899");
        m.put("apple tv", "4899");
        m.put("appletv", "4899");
        m.put("crunchyroll", "4899");
        m.put("twitch", "4899");
        m.put("patreon", "4899");
        m.put("onlyfans", "4899");
        m.put("substack", "4899");
        m.put("new york times", "4899");
        m.put("wsj", "4899");
        m.put("audible", "4899");
        m.put("kindle", "4899");
        m.put("apple icloud", "4899");
        m.put("google one", "4899");
        m.put("dropbox", "4899");
        m.put("microsoft 365", "4899");
        m.put("office 365", "4899");
        m.put("1password", "4899");
        m.put("lastpass", "4899");
        m.put("notion", "4899");
        m.put("evernote", "4899");
        m.put("grammarly", "4899");
        m.put("calm ", "4899");
        m.put("headspace", "4899");
        // Healthcare
        m.put("kaiser", "8062");
        m.put("anthem", "6300");
        m.put("blue cross", "6300");
        m.put("blue shield", "6300");
        m.put("united healthcare", "6300");
        m.put("aetna", "6300");
        m.put("cigna", "6300");
        m.put("humana", "6300");
        m.put("quest diagnostics", "8071");
        m.put("labcorp", "8071");
        m.put("one medical", "8011");
        m.put("teladoc", "8099");
        m.put("goodrx", "5912");
        // Insurance (auto, home, renters)
        m.put("geico", "6300");
        m.put("progressive", "6300");
        m.put("state farm", "6300");
        m.put("allstate", "6300");
        m.put("farmers insurance", "6300");
        m.put("liberty mutual", "6300");
        m.put("nationwide", "6300");
        m.put("usaa", "6300");
        m.put("lemonade insurance", "6300");
        m.put("lemonade ", "6300");
        m.put("metlife", "6300");
        m.put("prudential", "6300");
        m.put("root insurance", "6300");
        // Shopping — clothing + style
        m.put("lululemon", "5651");
        m.put("athleta", "5651");
        m.put("under armour", "5651");
        m.put("adidas", "5651");
        m.put("puma", "5651");
        m.put("new balance", "5651");
        m.put("reebok", "5651");
        m.put("patagonia", "5651");
        m.put("north face", "5651");
        m.put("columbia sportswear", "5651");
        m.put("rei ", "5941"); // Recreational Equipment Inc — sporting
        m.put("dicks sporting", "5941");
        m.put("academy sports", "5941");
        m.put("banana republic", "5651");
        m.put("j crew", "5651");
        m.put("j.crew", "5651");
        m.put("ann taylor", "5651");
        m.put("loft ", "5651");
        m.put("abercrombie", "5651");
        m.put("american eagle", "5651");
        m.put("aeropostale", "5651");
        m.put("hollister", "5651");
        m.put("forever 21", "5651");
        m.put("urban outfitters", "5651");
        m.put("anthropologie", "5651");
        m.put("free people", "5651");
        m.put("nordstrom", "5311");
        m.put("nordstrom rack", "5310");
        m.put("macys", "5311");
        m.put("bloomingdale", "5311");
        m.put("saks", "5311");
        m.put("neiman marcus", "5311");
        m.put("kohls", "5311");
        m.put("jcpenney", "5311");
        m.put("dillards", "5311");
        m.put("marshalls", "5310");
        m.put("tj maxx", "5310");
        m.put("tjmaxx", "5310");
        m.put("ross ", "5310");
        m.put("burlington", "5310");
        m.put("big lots", "5310");
        m.put("dollar tree", "5310");
        m.put("dollar general", "5310");
        m.put("family dollar", "5310");
        m.put("five below", "5310");
        m.put("sephora", "5977");
        m.put("ulta ", "5977");
        m.put("bath & body works", "5977");
        m.put("bath body works", "5977");
        m.put("mac cosmetics", "5977");
        // Tech + electronics (additional)
        m.put("bestbuy", "5732");
        m.put("apple store", "5732");
        m.put("frys electronics", "5732");
        m.put("microcenter", "5732");
        m.put("newegg", "5732");
        m.put("b&h photo", "5946");
        m.put("adorama", "5946");
        m.put("gamestop", "5945");
        m.put("steam ", "5816");
        m.put("playstation", "5816");
        m.put("xbox ", "5816");
        m.put("nintendo", "5816");
        m.put("roblox", "5816");
        m.put("epic games", "5816");
        m.put("figma", "5734");
        m.put("github", "5734");
        m.put("gitlab", "5734");
        m.put("jetbrains", "5734");
        m.put("atlassian", "5734");
        m.put("slack", "5734");
        m.put("zoom ", "4816");
        m.put("openai", "5734");
        m.put("anthropic", "5734");
        m.put("claude.ai", "5734");
        // Home improvement (additional)
        m.put("ikea", "5712");
        m.put("pottery barn", "5712");
        m.put("west elm", "5712");
        m.put("crate & barrel", "5712");
        m.put("crate barrel", "5712");
        m.put("williams sonoma", "5712");
        m.put("bed bath & beyond", "5712");
        m.put("bed bath beyond", "5712");
        m.put("at home stores", "5712");
        m.put("overstock", "5712");
        m.put("wayfair", "5712");
        m.put("tractor supply", "5261");
        // Books + office
        m.put("barnes & noble", "5942");
        m.put("barnes noble", "5942");
        m.put("half price books", "5942");
        m.put("staples", "5943");
        m.put("office depot", "5943");
        m.put("officemax", "5943");
        // Pets (additional)
        m.put("pet supplies plus", "5995");
        m.put("banfield", "0742"); // Vet clinics at Petsmart
        m.put("vca animal hospital", "0742");
        // Ride-sharing + transit (additional)
        m.put("bolt.eu", "4121");
        m.put("ola cabs", "4121"); // India
        m.put("ola ", "4121"); // India (short)
        m.put("didi ", "4121"); // China
        // Payment processors (route through transfer)
        m.put("venmo", "6051");
        m.put("cash app", "6051");
        m.put("cashapp", "6051");
        m.put("zelle", "6051");
        m.put("paypal", "6051");
        m.put("western union", "6051");
        m.put("moneygram", "6051");
        m.put("remitly", "6051");
        m.put("wise transfer", "6051");
        m.put("transferwise", "6051");
        // Investment brokerages
        m.put("fidelity", "6211");
        m.put("vanguard", "6211");
        m.put("schwab", "6211");
        m.put("td ameritrade", "6211");
        m.put("etrade", "6211");
        m.put("robinhood", "6211");
        m.put("interactive brokers", "6211");
        m.put("webull", "6211");
        m.put("coinbase", "6051"); // crypto exchange — treat as transfer
        m.put("kraken", "6051");
        m.put("binance", "6051");
        // Fitness
        m.put("planet fitness", "7997");
        m.put("la fitness", "7997");
        m.put("24 hour fitness", "7997");
        m.put("equinox", "7997");
        m.put("soulcycle", "7997");
        m.put("crossfit", "7997");
        m.put("orangetheory", "7997");
        m.put("barry's bootcamp", "7997");
        m.put("anytime fitness", "7997");
        m.put("classpass", "7997");
        m.put("peloton", "5655");

        return Collections.unmodifiableMap(m);
    }

    /**
     * Lookup the MCC for a merchant name via keyword substring match. Returns empty when no keyword
     * matches. Uses longest-match-wins so "amazon fresh" outranks "amazon".
     */
    public static Optional<String> mccForMerchant(final String merchantName) {
        if (merchantName == null || merchantName.isBlank()) {
            return Optional.empty();
        }
        final String normalized = merchantName.toLowerCase(Locale.ROOT);
        String bestMatch = null;
        int bestMatchLength = 0;
        for (final Map.Entry<String, String> entry : MERCHANT_KEYWORD_TO_MCC.entrySet()) {
            final String keyword = entry.getKey();
            if (keyword.length() > bestMatchLength && normalized.contains(keyword)) {
                bestMatch = entry.getValue();
                bestMatchLength = keyword.length();
            }
        }
        return Optional.ofNullable(bestMatch);
    }

    /** Map MCC to our internal category, or empty when the MCC isn't in the registry. */
    public static Optional<String> categoryForMcc(final String mcc) {
        if (mcc == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(MCC_TO_CATEGORY.get(mcc.trim()));
    }

    /**
     * One-call helper: given a merchant name, return the category our registry would assign via
     * MCC. Empty when either the merchant doesn't map to an MCC or the MCC isn't categorised.
     */
    public static Optional<String> categoryForMerchant(final String merchantName) {
        return mccForMerchant(merchantName).flatMap(MccDirectory::categoryForMcc);
    }
}
