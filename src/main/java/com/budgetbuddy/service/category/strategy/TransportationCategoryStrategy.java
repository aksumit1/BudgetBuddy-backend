package com.budgetbuddy.service.category.strategy;

import java.util.Locale;
import org.springframework.stereotype.Component;

/** Strategy for detecting transportation category */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
@Component
public class TransportationCategoryStrategy extends BaseCategoryStrategy {

    private static final String TRANSPORTATION = "transportation";

    private static final String CHAIR = "chair";

    private static final String CHEVRON = "chevron";

    private static final String DISNEY = "disney";

    private static final String ESCAPE_ROOM = "escape room";

    private static final String ESCAPEROOM = "escaperoom";

    private static final String PAYPAMS = "paypams";

    private static final String SCHOOL_DISTRICT = "school district";

    private static final String SCHOOLDISTRICT = "schooldistrict";

    private static final String TOEFL = "toefl";

    @Override
    public String detectCategory(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        if (normalizedMerchantName == null || normalizedMerchantName.isBlank()) {
            return null;
        }

        // CRITICAL: Airport expenses (carts, chairs, parking, etc.) must come BEFORE utilities
        // check
        // "SEATTLEAP" (Seattle Airport) should not match "Seattle Public Utilities"
        if (normalizedMerchantName.contains("seattleap")
                || normalizedMerchantName.contains("seattle ap")
                || normalizedMerchantName.contains("seattle airport")
                || descriptionLower.contains("seattleap")
                || descriptionLower.contains("seattle ap")
                || descriptionLower.contains("seattle airport")) {
            // Airport cart/chair rentals
            if (normalizedMerchantName.contains("cart")
                    || normalizedMerchantName.contains(CHAIR)
                    || descriptionLower.contains("cart")
                    || descriptionLower.contains(CHAIR)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromMerchantName: Detected airport cart/chair → 'transportation'");
                return TRANSPORTATION;
            }
        }
        // General airport cart/chair patterns
        if ((normalizedMerchantName.contains("airport") || descriptionLower.contains("airport"))
                && (normalizedMerchantName.contains("cart")
                        || normalizedMerchantName.contains(CHAIR)
                        || descriptionLower.contains("cart")
                        || descriptionLower.contains(CHAIR))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected airport cart/chair → 'transportation'");
            return TRANSPORTATION;
        }

        // ========== GAS STATIONS ==========
        if (normalizedMerchantName.contains(CHEVRON) || descriptionLower.contains(CHEVRON)) {
            return TRANSPORTATION;
        }
        final String[] gasStations = {
            "shell", "bp ", "bp.", "exxon", "mobil", "esso",
            "arco", "valero", "citgo", "speedway", "7-eleven", "7eleven",
            "circle k", "circlek", CHEVRON, "texaco", "phillips 66", "phillips66",
            "conoco", "marathon", "sunoco", "sinclair", "kwik trip", "kwiktrip",
            "kwik sak", "kwiksak", "buc-ee", "bucees", "buc-ee's", "bucees's"
        };
        for (final String station : gasStations) {
            if (normalizedMerchantName.contains(station) || descriptionLower.contains(station)) {
                return TRANSPORTATION;
            }
        }

        // CRITICAL FIX: "76" gas station must be more specific to avoid matching "CHECK 176"
        // Only match "76" if it's clearly a gas station (e.g., "76 station", "76 gas", "union 76")
        if ((normalizedMerchantName.contains("76 station")
                        || normalizedMerchantName.contains("76 gas")
                        || normalizedMerchantName.contains("union 76")
                        || normalizedMerchantName.contains("76 fuel")
                        || descriptionLower.contains("76 station")
                        || descriptionLower.contains("76 gas")
                        || descriptionLower.contains("union 76")
                        || descriptionLower.contains("76 fuel"))
                && !normalizedMerchantName.contains("check")
                && !descriptionLower.contains("check")) {
            return TRANSPORTATION;
        }

        // Gas Station Patterns
        if (normalizedMerchantName.contains("gas station")
                || normalizedMerchantName.contains("gasstation")
                || normalizedMerchantName.contains("fuel")
                || normalizedMerchantName.contains("petrol")
                || normalizedMerchantName.contains("gas ")
                || descriptionLower.contains("gas station")) {
            return TRANSPORTATION;
        }

        // ========== CAR DEALERSHIPS AND SERVICES ==========
        // Honda CTR / Honda dealerships - transportation
        if (normalizedMerchantName.contains("honda ctr")
                || normalizedMerchantName.contains("hondactr")
                || normalizedMerchantName.contains("honda of")
                || normalizedMerchantName.contains("honda dealership")
                || normalizedMerchantName.contains("honda dealer")
                || descriptionLower.contains("honda ctr")
                || descriptionLower.contains("honda of")
                || (merchantName != null
                        && merchantName.toUpperCase(Locale.ROOT).contains("HONDA CTR"))
                || (merchantName != null
                        && merchantName.toUpperCase(Locale.ROOT).contains("HONDA OF"))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected Honda (dealership/service) → 'transportation'");
            return TRANSPORTATION;
        }

        // ========== ENTERTAINMENT ==========
        if (normalizedMerchantName.contains("amc") || descriptionLower.contains("amc")) {
            return "entertainment";
        }
        // State Fair, Disney, Universal Studio, Sea World
        if (normalizedMerchantName.contains("state fair")
                || normalizedMerchantName.contains("statefair")
                || normalizedMerchantName.contains(DISNEY)
                || descriptionLower.contains(DISNEY)
                || normalizedMerchantName.contains("universal studio")
                || normalizedMerchantName.contains("universalstudio")
                || normalizedMerchantName.contains("universal studios")
                || descriptionLower.contains("universal studio")
                || normalizedMerchantName.contains("sea world")
                || normalizedMerchantName.contains("seaworld")
                || descriptionLower.contains("sea world")
                || descriptionLower.contains("seaworld")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected theme park/fair → 'entertainment'");
            return "entertainment";
        }
        // Camping (Cape Disappointment, recreation.gov)
        if (normalizedMerchantName.contains("camping")
                || descriptionLower.contains("camping")
                || normalizedMerchantName.contains("cape disappointment")
                || descriptionLower.contains("cape disappointment")
                || normalizedMerchantName.contains("recreation.gov")
                || descriptionLower.contains("recreation.gov")) {
            LOGGER.debug("🏷️ detectCategoryFromMerchantName: Detected camping → 'entertainment'");
            return "entertainment";
        }
        final String[] entertainmentVenues = {
            "cinemark",
            "regal",
            "carmike",
            "marcus",
            "harkins",
            "alamo drafthouse",
            "alamodrafthouse",
            "movie theater",
            "movietheater",
            "cinema",
            "theater",
            "theatre",
            "imax",
            ESCAPE_ROOM,
            ESCAPEROOM,
            "escape rooms",
            "escaperooms",
            "countdown rooms",
            "countdownrooms"
        };
        for (final String venue : entertainmentVenues) {
            if (normalizedMerchantName.contains(venue) || descriptionLower.contains(venue)) {
                return "entertainment";
            }
        }

        // Streaming Services - Must come before general entertainment patterns
        final String[] streamingServicesInEntertainment = {
            "netflix",
            "hulu",
            "huluplus",
            "hulu plus",
            DISNEY,
            "disney+",
            "disneyplus",
            "hbo",
            "hbo max",
            "hbomax",
            "paramount",
            "paramount+",
            "paramount plus",
            "peacock",
            "nbc peacock",
            "spotify",
            "apple music",
            "applemusic",
            "youtube premium",
            "youtubepremium",
            "youtube tv",
            "youtubetv",
            "amazon prime",
            "amazonprime",
            "prime video",
            "primevideo",
            "showtime",
            "starz",
            "crunchyroll",
            "funimation"
        };
        for (final String service : streamingServicesInEntertainment) {
            if (normalizedMerchantName.contains(service) || descriptionLower.contains(service)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromMerchantName: Detected streaming service '{}' → 'entertainment'",
                        service);
                return "entertainment";
            }
        }

        // Entertainment Patterns
        if (normalizedMerchantName.contains("entertainment")
                || normalizedMerchantName.contains("arcade")
                || normalizedMerchantName.contains("bowling")
                || normalizedMerchantName.contains("mini golf")
                || normalizedMerchantName.contains("laser tag")
                || descriptionLower.contains("entertainment")) {
            return "entertainment";
        }

        // Top Golf and similar entertainment venues
        if (normalizedMerchantName.contains("top golf")
                || normalizedMerchantName.contains("topgolf")
                || descriptionLower.contains("top golf")
                || descriptionLower.contains("topgolf")) {
            LOGGER.debug("🏷️ detectCategoryFromMerchantName: Detected Top Golf → 'entertainment'");
            return "entertainment";
        }

        // Escape rooms (entertainment)
        if (normalizedMerchantName.contains(ESCAPE_ROOM)
                || normalizedMerchantName.contains(ESCAPEROOM)
                || normalizedMerchantName.contains("conundroom")
                || descriptionLower.contains(ESCAPE_ROOM)
                || descriptionLower.contains(ESCAPEROOM)
                || descriptionLower.contains("conundroom")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected escape room → 'entertainment'");
            return "entertainment";
        }

        // ========== EDUCATION/SCHOOL PAYMENTS ==========
        // PayPAMS - online school payments for food (dining)
        if (normalizedMerchantName.contains(PAYPAMS)
                || normalizedMerchantName.contains("pay pams")
                || descriptionLower.contains(PAYPAMS)
                || descriptionLower.contains("pay pams")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected PayPAMS (school food payment) → 'dining'");
            return "dining";
        }

        // School District payments - should be categorized as "education"
        // Check for any school district (not just Bellevue)
        if ((normalizedMerchantName.contains(SCHOOL_DISTRICT)
                        || normalizedMerchantName.contains(SCHOOLDISTRICT)
                        || normalizedMerchantName.contains("school distri"))
                || (descriptionLower.contains(SCHOOL_DISTRICT)
                        || descriptionLower.contains(SCHOOLDISTRICT)
                        || descriptionLower.contains("school distri"))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected School District → 'education'");
            return "education";
        }

        // Bellevue School District - school district payment (education, not charity)
        if (normalizedMerchantName.contains("bellevue school district")
                || normalizedMerchantName.contains("bellevueschooldistrict")
                || normalizedMerchantName.contains("bellevue school distri")
                || descriptionLower.contains("bellevue school district")
                || descriptionLower.contains("bellevueschooldistrict")
                || descriptionLower.contains("bellevue school distri")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected Bellevue School District → 'education'");
            return "education";
        }

        // WA Vehicle Licensing - Department of Licensing (transportation)
        if (normalizedMerchantName.contains("wa vehicle licensing")
                || normalizedMerchantName.contains("wav vehicle licensing")
                || normalizedMerchantName.contains("wa vehicle license")
                || normalizedMerchantName.contains("vehicle licensing")
                || descriptionLower.contains("wa vehicle licensing")
                || descriptionLower.contains("vehicle licensing")
                || (merchantName != null
                        && merchantName
                                .toUpperCase(Locale.ROOT)
                                .contains("WA VEHICLE LICENSING"))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected WA Vehicle Licensing → 'transportation'");
            return TRANSPORTATION;
        }

        // 76-FALCO - Gas station (76 brand)
        if (normalizedMerchantName.contains("76-falco")
                || normalizedMerchantName.contains("76 falco")
                || normalizedMerchantName.contains("76falco")
                || descriptionLower.contains("76-falco")
                || descriptionLower.contains("76 falco")
                || (merchantName != null
                        && merchantName.toUpperCase(Locale.ROOT).contains("76-FALCO"))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected 76-FALCO gas station → 'transportation'");
            return TRANSPORTATION;
        }

        // Education-related items (school, books, reading, tuition, etc.) - categorized as
        // "education"
        // CRITICAL: Check for bookstores first (University Book Store, etc.)
        if (normalizedMerchantName.contains("university book store")
                || normalizedMerchantName.contains("universitybookstore")
                || normalizedMerchantName.contains("university bookstore")
                || descriptionLower.contains("university book store")
                || (merchantName != null
                        && merchantName
                                .toUpperCase(Locale.ROOT)
                                .contains("UNIVERSITY BOOK STORE"))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected University Book Store → 'education'");
            return "education";
        }

        // CRITICAL FIX: Check for exam/testing keywords FIRST (AAMC, SAT, TOEFL, GRE, GMAT, LSAT,
        // MCAT, etc.)
        // These should be categorized as "education" even if they're sometimes miscategorized as
        // "entertainment"
        // VUE (Pearson VUE) - testing center for professional exams
        if ((normalizedMerchantName.contains("vue") || descriptionLower.contains("vue"))
                && (normalizedMerchantName.contains("exam")
                        || descriptionLower.contains("exam")
                        || normalizedMerchantName.contains("test")
                        || descriptionLower.contains("test")
                        || normalizedMerchantName.contains("aamc")
                        || descriptionLower.contains("aamc")
                        || normalizedMerchantName.contains("sat")
                        || descriptionLower.contains("sat")
                        || normalizedMerchantName.contains(TOEFL)
                        || descriptionLower.contains(TOEFL)
                        || normalizedMerchantName.contains("gre")
                        || descriptionLower.contains("gre")
                        || normalizedMerchantName.contains("gmat")
                        || descriptionLower.contains("gmat")
                        || normalizedMerchantName.contains("lsat")
                        || descriptionLower.contains("lsat")
                        || normalizedMerchantName.contains("mcat")
                        || descriptionLower.contains("mcat")
                        || normalizedMerchantName.contains("act")
                        || descriptionLower.contains("act"))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected VUE exam/testing → 'education'");
            return "education";
        }

        // Exam/testing keywords (AAMC, SAT, TOEFL, GRE, GMAT, LSAT, MCAT, ACT, AP, IB, etc.)
        final String[] examKeywords = {
            "aamc",
            "sat",
            TOEFL,
            "gre",
            "gmat",
            "lsat",
            "mcat",
            "act",
            "ap exam",
            "ib exam",
            "clep",
            "praxis",
            "bar exam",
            "nclex",
            "usmle",
            "comlex",
            "test registration",
            "test fee",
            "test center",
            "pearson vue",
            "ets",
            "prometric"
        };
        for (final String exam : examKeywords) {
            if (normalizedMerchantName.contains(exam)
                    || descriptionLower.contains(exam)
                    || (merchantName != null
                            && merchantName
                                    .toUpperCase(Locale.ROOT)
                                    .contains(exam.toUpperCase(Locale.ROOT)))) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromMerchantName: Detected exam/testing keyword '{}' → 'education'",
                        exam);
                return "education";
            }
        }

        // SP ANKI REMOTE - Education (Anki remote learning/spaced repetition)
        if (normalizedMerchantName.contains("sp anki remote")
                || normalizedMerchantName.contains("spankiremote")
                || normalizedMerchantName.contains("anki remote")
                || descriptionLower.contains("sp anki remote")
                || descriptionLower.contains("anki remote")
                || (merchantName != null
                        && merchantName.toUpperCase(Locale.ROOT).contains("SP ANKI REMOTE"))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected SP ANKI REMOTE → 'education'");
            return "education";
        }

        // Regional school/college names - Education
        // CRITICAL: Check for regional terms BEFORE generic education keywords to ensure they're
        // always detected
        // Indian: Gurukul, Vidyalaya, Shiksha, Pathshala
        // Spanish: Escuela, Colegio, Universidad
        // French: École, Collège, Université
        // German: Schule, Universität
        // Arabic: Madrasa, Kuttab
        final String[] regionalSchoolTerms = {
            "gurukul",
            "vidyalaya",
            "shiksha",
            "pathshala",
            "escuela",
            "colegio",
            "universidad",
            "école",
            "collège",
            "université",
            "schule",
            "universität",
            "madrasa",
            "kuttab",
            "madrassa"
        };
        for (final String term : regionalSchoolTerms) {
            if (normalizedMerchantName.contains(term)
                    || descriptionLower.contains(term)
                    || (merchantName != null
                            && merchantName
                                    .toUpperCase(Locale.ROOT)
                                    .contains(term.toUpperCase(Locale.ROOT)))) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromMerchantName: Detected regional school term '{}' → 'education'",
                        term);
                return "education";
            }
        }

        // CRITICAL: Check for school types FIRST (middle school, high school, elementary, etc.)
        final String[] schoolTypes = {
            "middle school",
            "middleschool",
            "high school",
            "highschool",
            "elementary school",
            "elementaryschool",
            "elementary",
            "secondary school",
            "secondaryschool",
            "senior secondary school",
            "seniorschool",
            "college",
            "university",
            "phd",
            "ph.d",
            "ph.d.",
            "doctorate",
            "graduate school",
            "graduateschool",
            SCHOOL_DISTRICT,
            SCHOOLDISTRICT
        };
        for (final String school : schoolTypes) {
            if (normalizedMerchantName.contains(school)
                    || descriptionLower.contains(school)
                    || (merchantName != null
                            && merchantName
                                    .toUpperCase(Locale.ROOT)
                                    .contains(school.toUpperCase(Locale.ROOT)))) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromMerchantName: Detected school type '{}' → 'education'",
                        school);
                return "education";
            }
        }

        // Educational media (books, newspapers, magazines, journals)
        final String[] educationalMedia = {
            "newspaper",
            "magazine",
            "journal",
            "books",
            "bookstore",
            "book store",
            "textbook",
            "text book",
            "library",
            "academic journal",
            "research journal",
            "scientific journal"
        };
        for (final String media : educationalMedia) {
            if (normalizedMerchantName.contains(media)
                    || descriptionLower.contains(media)
                    || (merchantName != null
                            && merchantName
                                    .toUpperCase(Locale.ROOT)
                                    .contains(media.toUpperCase(Locale.ROOT)))) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromMerchantName: Detected educational media '{}' → 'education'",
                        media);
                return "education";
            }
        }

        final String[] educationKeywords = {
            "school",
            "university",
            "college",
            "tuition",
            "tuition fee",
            "books",
            "bookstore",
            "book store",
            "reading",
            "textbook",
            "text book",
            "education",
            "educational",
            "course",
            "class",
            "lesson",
            "training"
        };
        for (final String edu : educationKeywords) {
            if (normalizedMerchantName.contains(edu) || descriptionLower.contains(edu)) {
                // Skip if it's a school payment (PayPAMS) or school district - those are handled
                // separately
                if (!normalizedMerchantName.contains(PAYPAMS)
                        && !normalizedMerchantName.contains(SCHOOL_DISTRICT)
                        && !descriptionLower.contains(PAYPAMS)
                        && !descriptionLower.contains(SCHOOL_DISTRICT)) {
                    LOGGER.debug(
                            "🏷️ detectCategoryFromMerchantName: Detected education item '{}' → 'education'",
                            edu);
                    return "education";
                }
            }
        }

        return null; // No match found
    }
}
