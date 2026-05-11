package com.budgetbuddy.service.category.strategy;

import java.util.Locale;
import org.springframework.stereotype.Component;

/** Strategy for detecting utilities category */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.UnusedFormalParameter"})
@Component
public class UtilitiesCategoryStrategy extends BaseCategoryStrategy {

    private static final String ELECTRIC = "electric";

    private static final String ENERGY = "energy";

    private static final String PARKING = "parking";

    private static final String PAY_BY_PHONE = "pay by phone";

    private static final String PAYBYPHONE = "paybyphone";

    private static final String UTILITIES = "utilities";

    private static final String UTILITY = "utility";
    private static final String TRANSPORTATION = "transportation";

    @Override
    public String detectCategory(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        if (normalizedMerchantName == null || normalizedMerchantName.isBlank()) {
            return null;
        }

        String result;
        result = detectUtilitiesMunicipal(normalizedMerchantName, descriptionLower, merchantName);
        if (result != null) {
            return result;
        }
        result = detectUtilitiesCommon(normalizedMerchantName, descriptionLower, merchantName);
        if (result != null) {
            return result;
        }
        result = detectUtilitiesPatterns(normalizedMerchantName, descriptionLower, merchantName);
        if (result != null) {
            return result;
        }
        result = detectUtilitiesBillPayment(normalizedMerchantName, descriptionLower, merchantName);
        if (result != null) {
            return result;
        }
        result =
                detectUtilitiesCableInternet(
                        normalizedMerchantName, descriptionLower, merchantName);
        if (result != null) {
            return result;
        }
        result = detectUtilitiesPhoneMobile(normalizedMerchantName, descriptionLower, merchantName);
        if (result != null) {
            return result;
        }
        result =
                detectUtilitiesTransportation(
                        normalizedMerchantName, descriptionLower, merchantName);
        if (result != null) {
            return result;
        }
        return null;
    }

    private String detectUtilitiesMunicipal(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        // CRITICAL: Check for utility companies and energy providers BEFORE transportation
        // CRITICAL: Check for municipal utilities FIRST (e.g., "CITY OF BELLEVUE UTILITY")
        // This prevents "CITY" from matching transportation patterns
        if ((normalizedMerchantName.contains("city of") || descriptionLower.contains("city of"))
                && (normalizedMerchantName.contains(UTILITY)
                        || normalizedMerchantName.contains(UTILITIES)
                        || descriptionLower.contains(UTILITY)
                        || descriptionLower.contains(UTILITIES))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected municipal utility (city of ... utility) → 'utilities'");
            return UTILITIES;
        }
        return null;
    }

    private String detectUtilitiesCommon(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        // Common utility company patterns
        final String[] utilityCompanies = {
            "puget sound energy",
            "pse",
            "pacific gas",
            "pg&e",
            "pge",
            "southern california edison",
            "sce",
            "san diego gas",
            "sdge",
            "edison",
            "con edison",
            "coned",
            "duke energy",
            "dukeenergy",
            "dominion energy",
            "dominionenergy",
            "exelon",
            "first energy",
            "firstenergy",
            "american electric",
            "aep",
            "southern company",
            "southerncompany",
            "next era",
            "nextera",
            "xcel energy",
            "xcelenergy",
            "centerpoint",
            "center point",
            "entergy",
            "entergy",
            "evergy",
            "evergy",
            "pacificorp",
            "pacific corp",
            "pge",
            "portland general",
            "portlandgeneral"
        };
        for (final String company : utilityCompanies) {
            if (normalizedMerchantName.contains(company) || descriptionLower.contains(company)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromMerchantName: Detected utility company '{}' → 'utilities'",
                        company);
                return UTILITIES;
            }
        }
        return null;
    }

    private String detectUtilitiesPatterns(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        // Utility patterns (energy, electric, gas, water, etc.)
        // CRITICAL: Check for "utility" keyword BEFORE transportation to prevent false matches
        if (normalizedMerchantName.contains(ENERGY)
                || normalizedMerchantName.contains("ener ")
                || normalizedMerchantName.contains("ener billpay")
                || normalizedMerchantName.contains(ELECTRIC)
                || normalizedMerchantName.contains("electricity")
                || normalizedMerchantName.contains(UTILITY)
                || normalizedMerchantName.contains(UTILITIES)
                || normalizedMerchantName.contains("gas company")
                || normalizedMerchantName.contains("gascompany")
                || normalizedMerchantName.contains("water company")
                || normalizedMerchantName.contains("watercompany")
                || normalizedMerchantName.contains("power company")
                || normalizedMerchantName.contains("powercompany")
                || descriptionLower.contains(ENERGY)
                || descriptionLower.contains("ener ")
                || descriptionLower.contains("ener billpay")
                || descriptionLower.contains(ELECTRIC)
                || descriptionLower.contains("electricity")
                || descriptionLower.contains(UTILITY)
                || descriptionLower.contains(UTILITIES)
                || descriptionLower.contains("gas company")
                || descriptionLower.contains("water company")
                || descriptionLower.contains("power company")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected utility pattern → 'utilities'");
            return UTILITIES;
        }
        return null;
    }

    private String detectUtilitiesBillPayment(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        // Bill payment patterns (often utilities)
        if ((normalizedMerchantName.contains("billpay")
                        || normalizedMerchantName.contains("bill pay")
                        || descriptionLower.contains("billpay")
                        || descriptionLower.contains("bill pay"))
                && (normalizedMerchantName.contains("ener")
                        || normalizedMerchantName.contains(ENERGY)
                        || normalizedMerchantName.contains(ELECTRIC)
                        || normalizedMerchantName.contains("gas")
                        || normalizedMerchantName.contains("water")
                        || normalizedMerchantName.contains(UTILITY)
                        || descriptionLower.contains("ener")
                        || descriptionLower.contains(ENERGY)
                        || descriptionLower.contains(ELECTRIC)
                        || descriptionLower.contains("gas")
                        || descriptionLower.contains("water")
                        || descriptionLower.contains(UTILITY))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected utility bill payment → 'utilities'");
            return UTILITIES;
        }
        return null;
    }

    private String detectUtilitiesCableInternet(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        // CRITICAL: Cable/Internet Providers - Must come BEFORE transportation
        // Comcast, Xfinity, Spectrum, Charter, Cox, etc.
        final String[] cableInternetProviders = {
            "comcast",
            "xfinity",
            "xfinity mobile",
            "xfinitymobile",
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
            "verizon fios"
        };
        for (final String provider : cableInternetProviders) {
            if (normalizedMerchantName.contains(provider) || descriptionLower.contains(provider)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromMerchantName: Detected cable/internet provider '{}' → 'utilities'",
                        provider);
                return UTILITIES;
            }
        }
        return null;
    }

    private String detectUtilitiesPhoneMobile(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        // CRITICAL: Phone/Mobile providers - Must come BEFORE transportation to prevent "mobile"
        // matching transportation
        // Verizon Wireless, AT&T, T-Mobile, etc. (excluding Xfinity Mobile which is already covered
        // above)
        final String[] phoneProviders = {
            "verizon wireless",
            "verizonwireless",
            "verizon",
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
            "boostmobile",
            "metropcs",
            "metro pcs",
            "metropcs",
            "mint mobile",
            "mintmobile",
            "google fi",
            "googlefi",
            "visible",
            "straight talk",
            "straighttalk",
            "us mobile",
            "usmobile"
        };
        for (final String provider : phoneProviders) {
            if (normalizedMerchantName.contains(provider) || descriptionLower.contains(provider)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromMerchantName: Detected phone/mobile provider '{}' → 'utilities'",
                        provider);
                return UTILITIES;
            }
        }
        return null;
    }

    private String detectUtilitiesTransportation(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        // ========== TRANSPORTATION ==========
        // CRITICAL: Parking services must be checked BEFORE general utility patterns
        // Pay by Phone is a parking payment service, not a utility
        if (normalizedMerchantName.contains(PAY_BY_PHONE)
                || normalizedMerchantName.contains(PAYBYPHONE)
                || descriptionLower.contains(PAY_BY_PHONE)
                || descriptionLower.contains(PAYBYPHONE)
                || (merchantName != null
                        && merchantName.toUpperCase(Locale.ROOT).contains("PAY BY PHONE"))
                || (merchantName != null
                        && merchantName.toUpperCase(Locale.ROOT).contains("PAYBYPHONE"))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected Pay by Phone (parking) → 'transportation'");
            return TRANSPORTATION;
        }

        // SDOT (Seattle Department of Transportation) - parking/toll services
        if (normalizedMerchantName.contains("sdot")
                || normalizedMerchantName.contains("s dot")
                || normalizedMerchantName.contains("seattle dot")
                || descriptionLower.contains("sdot")
                || (merchantName != null
                        && merchantName.toUpperCase(Locale.ROOT).contains("SDOT"))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected SDOT (parking/toll) → 'transportation'");
            return TRANSPORTATION;
        }

        // IMPARK - parking service
        if (normalizedMerchantName.contains("impark")
                || descriptionLower.contains("impark")
                || (merchantName != null
                        && merchantName.toUpperCase(Locale.ROOT).contains("IMPARK"))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected Impark parking → 'transportation'");
            return TRANSPORTATION;
        }

        // UW pay by phone is parking, not utilities
        if ((normalizedMerchantName.contains("uw") || descriptionLower.contains("uw"))
                && (normalizedMerchantName.contains(PAY_BY_PHONE)
                        || normalizedMerchantName.contains(PAYBYPHONE)
                        || descriptionLower.contains(PAY_BY_PHONE)
                        || descriptionLower.contains(PAYBYPHONE))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected UW pay by phone (parking) → 'transportation'");
            return TRANSPORTATION;
        }

        // Metropolis parking
        if (normalizedMerchantName.contains("metropolis")
                && (normalizedMerchantName.contains(PARKING)
                        || descriptionLower.contains(PARKING))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected Metropolis parking → 'transportation'");
            return TRANSPORTATION;
        }

        final String[] transportationServices = {
            "uber",
            "lyft",
            "taxi",
            "rapido",
            "cab",
            "rideshare",
            "parkmobile",
            "didi",
            "grab",
            "ola",
            "careem",
            "gett",
            "bolt",
            "amtrak",
            "greyhound",
            "bus ",
            "transit",
            "metro",
            PARKING,
            "parking meter",
            "parkingmeter",
            "garage",
            "gojek",
            "cabify",
            "blablacar",
            "Indrive",
            "Waymo",
            "Chauffeur",
            "zoox",
            "yellow cab",
            "checkers cab",
            "black cab",
            "ticket machine",
            "ticketmachine",
            "lul",
            "london underground",
            "underground"
        };
        for (final String service : transportationServices) {
            if (normalizedMerchantName.contains(service) || descriptionLower.contains(service)) {
                LOGGER.debug(
                        "detectCategoryFromMerchantName: Detected transportation service '{}' → 'transportation'",
                        service);
                return TRANSPORTATION;
            }
        }

        // State Department of Transportation (DOT) patterns - Toll roads, highway authorities
        // Pattern: [State] DOT, [State] Department of Transportation, State Toll Authority
        final String[] stateDOTPatterns = {
            // Washington State
            "wsdot",
            "washington state dot",
            "washington state department of transportation",
            "goodtogo",
            "good to go",
            "good-to-go",
            // California
            "caltrans",
            "cal trans",
            "california dot",
            "california department of transportation",
            "fastrak",
            "fastrak",
            "fast trak",
            "ez pass california",
            // New York
            "nysdot",
            "ny dot",
            "new york state dot",
            "new york state department of transportation",
            "ez pass",
            "ezpass",
            "e-zpass",
            "new york thruway",
            // Texas
            "txdot",
            "texas dot",
            "texas department of transportation",
            "ez tag",
            "eztag",
            "txtag",
            "tx tag",
            "dallas north tollway",
            // Florida
            "fdot",
            "florida dot",
            "florida department of transportation",
            "sunpass",
            "sun pass",
            "epass",
            "e pass",
            "leeway",
            "lee way",
            // Illinois
            "idot",
            "illinois dot",
            "illinois department of transportation",
            "ipass",
            "i-pass",
            "illinois tollway",
            // Massachusetts
            "massdot",
            "mass dot",
            "massachusetts dot",
            "massachusetts department of transportation",
            "e-zpass ma",
            "ezpass ma",
            "mass pike",
            // Pennsylvania
            "penn dot",
            "penndot",
            "pennsylvania dot",
            "pennsylvania department of transportation",
            "e-zpass pa",
            "ezpass pa",
            "pa turnpike",
            "pennsylvania turnpike",
            // New Jersey
            "njdot",
            "nj dot",
            "new jersey dot",
            "new jersey department of transportation",
            "e-zpass nj",
            "ezpass nj",
            "garden state parkway",
            "new jersey turnpike",
            // Maryland
            "mdot",
            "md dot",
            "maryland dot",
            "maryland department of transportation",
            "e-zpass md",
            "ezpass md",
            "maryland transportation authority",
            // Virginia
            "vdot",
            "va dot",
            "virginia dot",
            "virginia department of transportation",
            "ez-pass va",
            "ezpass va",
            "virginia transportation authority",
            // General patterns
            "state dot",
            "state department of transportation",
            "department of transportation",
            "dot toll",
            "toll road",
            "tollway",
            "toll authority",
            "toll plaza",
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
            "expressway toll"
        };
        for (final String dot : stateDOTPatterns) {
            if (normalizedMerchantName.contains(dot) || descriptionLower.contains(dot)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromMerchantName: Detected state DOT/toll '{}' → 'transportation'",
                        dot);
                return TRANSPORTATION;
            }
        }

        // Amex Airlines Fee Reimbursement - transportation (even though it's a credit)
        if (normalizedMerchantName.contains("amex airlines fee reimbursement")
                || normalizedMerchantName.contains("amexairlinesfeereimbursement")
                || descriptionLower.contains("amex airlines fee reimbursement")
                || descriptionLower.contains("amexairlinesfeereimbursement")
                || (normalizedMerchantName.contains("amex")
                        && normalizedMerchantName.contains("airlines")
                        && (normalizedMerchantName.contains("fee")
                                || normalizedMerchantName.contains("reimbursement")))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected Amex Airlines Fee Reimbursement → 'transportation'");
            return TRANSPORTATION;
        }

        // Car Service (Hona CTR, etc.)
        if (normalizedMerchantName.contains("hona ctr")
                || normalizedMerchantName.contains("honactr")
                || normalizedMerchantName.contains("hona car service")
                || normalizedMerchantName.contains("honacarservice")
                || descriptionLower.contains("hona ctr")
                || descriptionLower.contains("honactr")
                || descriptionLower.contains("hona car service")
                || descriptionLower.contains("honacarservice")
                || (normalizedMerchantName.contains("car service")
                        || descriptionLower.contains("car service"))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected car service → 'transportation'");
            return TRANSPORTATION;
        }

        return null;
    }
}
