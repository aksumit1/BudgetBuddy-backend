package com.budgetbuddy.service.category.strategy;

import java.util.Locale;
import org.springframework.stereotype.Component;

/** Strategy for detecting dining category */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
@Component
public class DiningCategoryStrategy extends BaseCategoryStrategy {

    private static final String DINING = "dining";

    private static final String PIZZERIA = "pizzeria";

    @Override
    public String detectCategory(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        if (normalizedMerchantName == null || normalizedMerchantName.isBlank()) {
            return null;
        }

        String result;
        result = detectDiningFastFood(normalizedMerchantName, descriptionLower, merchantName);
        if (result != null) {
            return result;
        }
        result =
                detectDiningAdditionalChains(
                        normalizedMerchantName, descriptionLower, merchantName);
        if (result != null) {
            return result;
        }
        result = detectDiningCafePatterns(normalizedMerchantName, descriptionLower, merchantName);
        if (result != null) {
            return result;
        }
        result = detectDiningTiffins(normalizedMerchantName, descriptionLower, merchantName);
        if (result != null) {
            return result;
        }
        result = detectDiningSpecificRest(normalizedMerchantName, descriptionLower, merchantName);
        if (result != null) {
            return result;
        }
        result = detectDiningTstToastPos(normalizedMerchantName, descriptionLower, merchantName);
        if (result != null) {
            return result;
        }
        result = detectDiningToastPos(normalizedMerchantName, descriptionLower, merchantName);
        if (result != null) {
            return result;
        }
        result = detectDiningSqSquarePos(normalizedMerchantName, descriptionLower, merchantName);
        if (result != null) {
            return result;
        }
        result = detectDiningRblPos(normalizedMerchantName, descriptionLower, merchantName);
        if (result != null) {
            return result;
        }
        result = detectDiningHmsHost(normalizedMerchantName, descriptionLower, merchantName);
        if (result != null) {
            return result;
        }
        result = detectDiningSandwichHouse(normalizedMerchantName, descriptionLower, merchantName);
        if (result != null) {
            return result;
        }
        result = detectDiningFoodKeywords(normalizedMerchantName, descriptionLower, merchantName);
        if (result != null) {
            return result;
        }
        result =
                detectDiningRestaurantPatterns(
                        normalizedMerchantName, descriptionLower, merchantName);
        if (result != null) {
            return result;
        }
        return null;
    }

    private String detectDiningFastFood(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        // Fast Food Chains
        if (normalizedMerchantName.contains("subway") || descriptionLower.contains("subway")) {
            return DINING;
        }
        if (normalizedMerchantName.contains("panda express")
                || normalizedMerchantName.contains("pandaexpress")
                || descriptionLower.contains("panda express")) {
            return DINING;
        }
        if (normalizedMerchantName.contains("starbucks")
                || descriptionLower.contains("starbucks")) {
            return DINING;
        }
        if (normalizedMerchantName.contains("chipotle") || descriptionLower.contains("chipotle")) {
            return DINING;
        }
        return null;
    }

    private String detectDiningAdditionalChains(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        // Additional Fast Food Chains
        final String[] fastFoodChains = {
            "mcdonald",
            "mcdonalds",
            "burger king",
            "burgerking",
            "wendy's",
            "wendys",
            "taco bell",
            "tacobell",
            "kfc",
            "pizza hut",
            "pizzahut",
            "domino's",
            "dominos",
            "papa john's",
            "papajohns",
            "little caesar",
            "littlecaesar",
            "papa murphy",
            "papamurphy",
            "dunkin",
            "dunkin donuts",
            "dunkindonuts",
            "tim hortons",
            "timhortons",
            "panera",
            "panera bread",
            "panerabread",
            "jamba juice",
            "jambajuice",
            "smoothie king",
            "smoothieking",
            "qdoba",
            "moe's",
            "moes",
            "baja fresh",
            "bajafresh"
        };
        for (final String chain : fastFoodChains) {
            if (normalizedMerchantName.contains(chain) || descriptionLower.contains(chain)) {
                return DINING;
            }
        }
        return null;
    }

    private String detectDiningCafePatterns(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        // Cafe & Cafeteria Patterns
        // CRITICAL: Include "caffe" (Italian spelling, e.g., CAFFE Nero) in addition to "cafe"
        if (normalizedMerchantName.contains("cafe")
                || normalizedMerchantName.contains("café")
                || normalizedMerchantName.contains("caffe")
                || normalizedMerchantName.contains("cafeteria")
                || normalizedMerchantName.contains("coffee")
                || normalizedMerchantName.contains("espresso")
                || normalizedMerchantName.contains("latte")
                || descriptionLower.contains("cafe")
                || descriptionLower.contains("café")
                || descriptionLower.contains("caffe")
                || descriptionLower.contains("cafeteria")) {
            return DINING;
        }
        return null;
    }

    private String detectDiningTiffins(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        // Tiffins (Indian meal delivery)
        if (normalizedMerchantName.contains("tiffin")
                || descriptionLower.contains("tiffin")
                || normalizedMerchantName.contains("tiffins")
                || descriptionLower.contains("tiffins")) {
            return DINING;
        }
        return null;
    }

    private String detectDiningSpecificRest(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        // Specific Restaurants - Must come before general restaurant patterns
        final String[] specificRestaurants = {
            "daeho",
            "tutta bella",
            "tuttabella",
            "simply indian restaur",
            "simply indian restaurant",
            "simplyindian restaur",
            "simplyindian restaurant",
            "skills rainbow room",
            "skillsrainbow room",
            "kyurmaen",
            "kyurmaen ramen",
            "kyuramen",
            "kyuramen ramen",
            "deep dive",
            "deepdive",
            "messina",
            "supreme dumplings",
            "supremedumplings",
            "cucina venti",
            "cucinaventi",
            "desi dhaba",
            "desidhaba",
            "medocinofarms",
            "medocino farms",
            "mendocinofarms",
            "mendocino farms",
            "laughing monk brewing",
            "laughingmonk brewing",
            "laughing monk",
            "laughingmonk",
            "indian sizzler",
            "indiansizzler",
            "shana thai",
            "shanathai",
            "tpd",
            "tpd 5th ave",
            "tpd 5th avenue",
            "pike place market",
            "pikeplace market",
            "pikeplacemarket",
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
            "maximillen",
            "sunny honey",
            "sunnyhoney",
            "sq* sunny honey",
            "sq*sunny honey",
            "il fornaio",
            "ilfornaio",
            "wingstop",
            "labcor",
            "labcorp",
            "ukvi",
            "canam pizza",
            "canampizza"
        };
        for (final String restaurant : specificRestaurants) {
            if (normalizedMerchantName.contains(restaurant)
                    || descriptionLower.contains(restaurant)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromMerchantName: Detected restaurant '{}' → 'dining'",
                        restaurant);
                return DINING;
            }
        }
        return null;
    }

    private String detectDiningTstToastPos(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        // TST* pattern - Transaction Service Terminal (restaurant pattern, Toast POS system)
        // CRITICAL: Check both normalized and original merchant name, and description
        // TST* can appear anywhere in the merchant name, not just at the start
        if (normalizedMerchantName.contains("tst*")
                || normalizedMerchantName.contains("tst ")
                || descriptionLower.contains("tst*")
                || descriptionLower.contains("tst ")
                || (merchantName != null
                        && (merchantName.toUpperCase(Locale.ROOT).contains("TST*")
                                || merchantName.toUpperCase(Locale.ROOT).contains("TST ")))) {
            LOGGER.debug("🏷️ detectCategoryFromMerchantName: Detected TST* pattern → 'dining'");
            return DINING;
        }
        return null;
    }

    private String detectDiningToastPos(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        // TOAST pattern - Toast POS system (restaurant pattern)
        // TOAST is the company name, TST* is their POS terminal code
        if (normalizedMerchantName.contains("toast")
                || descriptionLower.contains("toast")
                || (merchantName != null
                        && merchantName.toUpperCase(Locale.ROOT).contains("TOAST"))) {
            LOGGER.debug("🏷️ detectCategoryFromMerchantName: Detected TOAST pattern → 'dining'");
            return DINING;
        }
        return null;
    }

    private String detectDiningSqSquarePos(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        // SQ* pattern - Square POS system (often used by restaurants)
        // CRITICAL: Check both normalized and original merchant name, and description
        // SQ* can appear anywhere in the merchant name
        if (normalizedMerchantName.contains("sq*")
                || normalizedMerchantName.contains("sq ")
                || descriptionLower.contains("sq*")
                || descriptionLower.contains("sq ")
                || (merchantName != null
                        && (merchantName.toUpperCase(Locale.ROOT).contains("SQ*")
                                || merchantName.toUpperCase(Locale.ROOT).contains("SQ ")))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected SQ* pattern (Square POS) → 'dining'");
            return DINING;
        }
        return null;
    }

    private String detectDiningRblPos(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        // RBL* pattern - Restaurant POS system in India (similar to TST* and SQ*)
        // CRITICAL: Check both normalized and original merchant name, and description
        if (normalizedMerchantName.contains("rbl*")
                || normalizedMerchantName.contains("rbl ")
                || descriptionLower.contains("rbl*")
                || descriptionLower.contains("rbl ")
                || (merchantName != null
                        && (merchantName.toUpperCase(Locale.ROOT).contains("RBL*")
                                || merchantName.toUpperCase(Locale.ROOT).contains("RBL ")))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected RBL* pattern (Indian restaurant POS) → 'dining'");
            return DINING;
        }
        return null;
    }

    private String detectDiningHmsHost(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        // HMS Host services - airport/restaurant dining services
        if (normalizedMerchantName.contains("hms host")
                || normalizedMerchantName.contains("hmshost")
                || normalizedMerchantName.contains("hms host services")
                || descriptionLower.contains("hms host")
                || (merchantName != null
                        && merchantName.toUpperCase(Locale.ROOT).contains("HMS HOST"))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected HMS Host services → 'dining'");
            return DINING;
        }
        return null;
    }

    private String detectDiningSandwichHouse(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        // Sandwich House - restaurant name
        if (normalizedMerchantName.contains("sandwich house")
                || normalizedMerchantName.contains("sandwichhouse")
                || descriptionLower.contains("sandwich house")
                || descriptionLower.contains("sandwichhouse")
                || (merchantName != null
                        && merchantName.toUpperCase(Locale.ROOT).contains("SANDWICH HOUSE"))) {
            LOGGER.debug("🏷️ detectCategoryFromMerchantName: Detected Sandwich House → 'dining'");
            return DINING;
        }
        return null;
    }

    private String detectDiningFoodKeywords(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        // Food-related keywords that indicate restaurants (dumplings, burger, fast food, grill,
        // thai, dhaba, brewing, pizza, kitchen)
        if (normalizedMerchantName.contains("dumplings")
                || normalizedMerchantName.contains("dumpling")
                || normalizedMerchantName.contains("burger")
                || normalizedMerchantName.contains("burgers")
                || normalizedMerchantName.contains("fast food")
                || normalizedMerchantName.contains("fastfood")
                || normalizedMerchantName.contains("grill")
                || normalizedMerchantName.contains("grilled")
                || normalizedMerchantName.contains("thai")
                || normalizedMerchantName.contains("dhaba")
                || normalizedMerchantName.contains("brewing")
                || normalizedMerchantName.contains("brewery")
                || normalizedMerchantName.contains("brew pub")
                || normalizedMerchantName.contains("brewpub")
                || normalizedMerchantName.contains("pizza")
                || normalizedMerchantName.contains(PIZZERIA)
                || normalizedMerchantName.contains("kitchen")
                || normalizedMerchantName.contains("sandwich")
                || descriptionLower.contains("dumplings")
                || descriptionLower.contains("dumpling")
                || descriptionLower.contains("burger")
                || descriptionLower.contains("burgers")
                || descriptionLower.contains("fast food")
                || descriptionLower.contains("fastfood")
                || descriptionLower.contains("grill")
                || descriptionLower.contains("grilled")
                || descriptionLower.contains("thai")
                || descriptionLower.contains("dhaba")
                || descriptionLower.contains("brewing")
                || descriptionLower.contains("brewery")
                || descriptionLower.contains("brew pub")
                || descriptionLower.contains("brewpub")
                || descriptionLower.contains("pizza")
                || descriptionLower.contains(PIZZERIA)
                || descriptionLower.contains("kitchen")
                || descriptionLower.contains("sandwich")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected food/restaurant keyword → 'dining'");
            return DINING;
        }
        return null;
    }

    private String detectDiningRestaurantPatterns(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        // Restaurant Patterns (Global) - Includes "restaur" as keyword for restaurant
        if (normalizedMerchantName.contains("restaurant")
                || normalizedMerchantName.contains("rest ")
                || normalizedMerchantName.contains("restaur")
                || normalizedMerchantName.contains("restaur ")
                || normalizedMerchantName.contains("diner")
                || normalizedMerchantName.contains("bistro")
                || normalizedMerchantName.contains("steakhouse")
                || normalizedMerchantName.contains(PIZZERIA)
                || normalizedMerchantName.contains("trattoria")
                || normalizedMerchantName.contains("tavern")
                || normalizedMerchantName.contains("pub")
                || descriptionLower.contains("restaurant")
                || descriptionLower.contains("restaur")
                || descriptionLower.contains(DINING)) {
            return DINING;
        }

        return null;
    }
}
