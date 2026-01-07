package com.budgetbuddy.service.category.strategy;

import org.springframework.stereotype.Component;

/**
 * Strategy for detecting utilities category
 */
@Component
public class UtilitiesCategoryStrategy extends BaseCategoryStrategy {
    
    @Override
    public String detectCategory(String normalizedMerchantName, String descriptionLower, String merchantName) {
        if (normalizedMerchantName == null || normalizedMerchantName.trim().isEmpty()) {
            return null;
        }
        
        // CRITICAL: Check for utility companies and energy providers BEFORE transportation
        // CRITICAL: Check for municipal utilities FIRST (e.g., "CITY OF BELLEVUE UTILITY")
        // This prevents "CITY" from matching transportation patterns
        if ((normalizedMerchantName.contains("city of") || descriptionLower.contains("city of")) &&
            (normalizedMerchantName.contains("utility") || normalizedMerchantName.contains("utilities") ||
             descriptionLower.contains("utility") || descriptionLower.contains("utilities"))) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected municipal utility (city of ... utility) → 'utilities'");
            return "utilities";
        }
        
        // Common utility company patterns
        String[] utilityCompanies = {
            "puget sound energy", "pse", "pacific gas", "pg&e", "pge",
            "southern california edison", "sce", "san diego gas", "sdge",
            "edison", "con edison", "coned", "duke energy", "dukeenergy",
            "dominion energy", "dominionenergy", "exelon", "first energy", "firstenergy",
            "american electric", "aep", "southern company", "southerncompany",
            "next era", "nextera", "xcel energy", "xcelenergy", "centerpoint",
            "center point", "entergy", "entergy", "evergy", "evergy",
            "pacificorp", "pacific corp", "pge", "portland general", "portlandgeneral"
        };
        for (String company : utilityCompanies) {
            if (normalizedMerchantName.contains(company) || descriptionLower.contains(company)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected utility company '{}' → 'utilities'", company);
                return "utilities";
            }
        }
        
        // Utility patterns (energy, electric, gas, water, etc.)
        // CRITICAL: Check for "utility" keyword BEFORE transportation to prevent false matches
        if (normalizedMerchantName.contains("energy") || normalizedMerchantName.contains("ener ") || normalizedMerchantName.contains("ener billpay") ||
            normalizedMerchantName.contains("electric") || normalizedMerchantName.contains("electricity") ||
            normalizedMerchantName.contains("utility") || normalizedMerchantName.contains("utilities") ||
            normalizedMerchantName.contains("gas company") || normalizedMerchantName.contains("gascompany") ||
            normalizedMerchantName.contains("water company") || normalizedMerchantName.contains("watercompany") ||
            normalizedMerchantName.contains("power company") || normalizedMerchantName.contains("powercompany") ||
            descriptionLower.contains("energy") || descriptionLower.contains("ener ") || descriptionLower.contains("ener billpay") ||
            descriptionLower.contains("electric") || descriptionLower.contains("electricity") ||
            descriptionLower.contains("utility") || descriptionLower.contains("utilities") ||
            descriptionLower.contains("gas company") || descriptionLower.contains("water company") ||
            descriptionLower.contains("power company")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected utility pattern → 'utilities'");
            return "utilities";
        }
        
        // Bill payment patterns (often utilities)
        if ((normalizedMerchantName.contains("billpay") || normalizedMerchantName.contains("bill pay") || 
             descriptionLower.contains("billpay") || descriptionLower.contains("bill pay")) &&
            (normalizedMerchantName.contains("ener") || normalizedMerchantName.contains("energy") || 
             normalizedMerchantName.contains("electric") || normalizedMerchantName.contains("gas") ||
             normalizedMerchantName.contains("water") || normalizedMerchantName.contains("utility") ||
             descriptionLower.contains("ener") || descriptionLower.contains("energy") ||
             descriptionLower.contains("electric") || descriptionLower.contains("gas") ||
             descriptionLower.contains("water") || descriptionLower.contains("utility"))) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected utility bill payment → 'utilities'");
            return "utilities";
        }
        
        // CRITICAL: Cable/Internet Providers - Must come BEFORE transportation
        // Comcast, Xfinity, Spectrum, Charter, Cox, etc.
        String[] cableInternetProviders = {
            "comcast", "xfinity", "xfinity mobile", "xfinitymobile",
            "spectrum", "charter", "charter spectrum",
            "cox", "cox communications",
            "optimum", "altice", "frontier", "frontier communications",
            "centurylink", "century link", "windstream", "suddenlink", "mediacom",
            "dish", "dish network", "directv", "direct tv",
            "att u-verse", "att uverse", "fios", "verizon fios"
        };
        for (String provider : cableInternetProviders) {
            if (normalizedMerchantName.contains(provider) || descriptionLower.contains(provider)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected cable/internet provider '{}' → 'utilities'", provider);
                return "utilities";
            }
        }
        
        // CRITICAL: Phone/Mobile providers - Must come BEFORE transportation to prevent "mobile" matching transportation
        // Verizon Wireless, AT&T, T-Mobile, etc. (excluding Xfinity Mobile which is already covered above)
        String[] phoneProviders = {
            "verizon wireless", "verizonwireless", "verizon",
            "at&t", "att", "at and t", "t-mobile", "tmobile", "t mobile",
            "sprint", "us cellular", "uscellular", "cricket", "cricket wireless",
            "boost mobile", "boostmobile", "metropcs", "metro pcs", "metropcs",
            "mint mobile", "mintmobile", "google fi", "googlefi", "visible",
            "straight talk", "straighttalk", "us mobile", "usmobile"
        };
        for (String provider : phoneProviders) {
            if (normalizedMerchantName.contains(provider) || descriptionLower.contains(provider)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected phone/mobile provider '{}' → 'utilities'", provider);
                return "utilities";
            }
        }
        
        // ========== TRANSPORTATION ==========
        // CRITICAL: Parking services must be checked BEFORE general utility patterns
        // Pay by Phone is a parking payment service, not a utility
        if (normalizedMerchantName.contains("pay by phone") || normalizedMerchantName.contains("paybyphone") ||
            descriptionLower.contains("pay by phone") || descriptionLower.contains("paybyphone") ||
            (merchantName != null && merchantName.toUpperCase().contains("PAY BY PHONE")) ||
            (merchantName != null && merchantName.toUpperCase().contains("PAYBYPHONE"))) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Pay by Phone (parking) → 'transportation'");
            return "transportation";
        }
        
        // SDOT (Seattle Department of Transportation) - parking/toll services
        if (normalizedMerchantName.contains("sdot") || normalizedMerchantName.contains("s dot") ||
            normalizedMerchantName.contains("seattle dot") || descriptionLower.contains("sdot") ||
            (merchantName != null && merchantName.toUpperCase().contains("SDOT"))) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected SDOT (parking/toll) → 'transportation'");
            return "transportation";
        }
        
        // IMPARK - parking service
        if (normalizedMerchantName.contains("impark") || descriptionLower.contains("impark") ||
            (merchantName != null && merchantName.toUpperCase().contains("IMPARK"))) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Impark parking → 'transportation'");
            return "transportation";
        }
        
        // UW pay by phone is parking, not utilities
        if ((normalizedMerchantName.contains("uw") || descriptionLower.contains("uw")) && 
            (normalizedMerchantName.contains("pay by phone") || normalizedMerchantName.contains("paybyphone") ||
             descriptionLower.contains("pay by phone") || descriptionLower.contains("paybyphone"))) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected UW pay by phone (parking) → 'transportation'");
            return "transportation";
        }
        
        // Metropolis parking
        if (normalizedMerchantName.contains("metropolis") && (normalizedMerchantName.contains("parking") || descriptionLower.contains("parking"))) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Metropolis parking → 'transportation'");
            return "transportation";
        }
        
        String[] transportationServices = {
            "uber", "lyft", "taxi", "rapido", "cab", "rideshare",
            "parkmobile", "didi", "grab", "ola", "careem", "gett", "bolt", "amtrak", "greyhound", "bus ", "transit", "metro",
            "parking", "parking meter", "parkingmeter", "garage",
            "gojek", "cabify","blablacar",
            "Indrive", "Waymo", "Chauffeur", "zoox", "yellow cab", "checkers cab",
            "black cab", "ticket machine", "ticketmachine", "lul", "london underground", "underground"
        };
        for (String service : transportationServices) {
            if (normalizedMerchantName.contains(service) || descriptionLower.contains(service)) {
                logger.debug("detectCategoryFromMerchantName: Detected transportation service '{}' → 'transportation'", service);
                return "transportation";
            }
        }
        
        // State Department of Transportation (DOT) patterns - Toll roads, highway authorities
        // Pattern: [State] DOT, [State] Department of Transportation, State Toll Authority
        String[] stateDOTPatterns = {
            // Washington State
            "wsdot", "washington state dot", "washington state department of transportation",
            "goodtogo", "good to go", "good-to-go",
            // California
            "caltrans", "cal trans", "california dot", "california department of transportation",
            "fastrak", "fastrak", "fast trak", "ez pass california",
            // New York
            "nysdot", "ny dot", "new york state dot", "new york state department of transportation",
            "ez pass", "ezpass", "e-zpass", "new york thruway",
            // Texas
            "txdot", "texas dot", "texas department of transportation",
            "ez tag", "eztag", "txtag", "tx tag", "dallas north tollway",
            // Florida
            "fdot", "florida dot", "florida department of transportation",
            "sunpass", "sun pass", "epass", "e pass", "leeway", "lee way",
            // Illinois
            "idot", "illinois dot", "illinois department of transportation",
            "ipass", "i-pass", "illinois tollway",
            // Massachusetts
            "massdot", "mass dot", "massachusetts dot", "massachusetts department of transportation",
            "e-zpass ma", "ezpass ma", "mass pike",
            // Pennsylvania
            "penn dot", "penndot", "pennsylvania dot", "pennsylvania department of transportation",
            "e-zpass pa", "ezpass pa", "pa turnpike", "pennsylvania turnpike",
            // New Jersey
            "njdot", "nj dot", "new jersey dot", "new jersey department of transportation",
            "e-zpass nj", "ezpass nj", "garden state parkway", "new jersey turnpike",
            // Maryland
            "mdot", "md dot", "maryland dot", "maryland department of transportation",
            "e-zpass md", "ezpass md", "maryland transportation authority",
            // Virginia
            "vdot", "va dot", "virginia dot", "virginia department of transportation",
            "ez-pass va", "ezpass va", "virginia transportation authority",
            // General patterns
            "state dot", "state department of transportation", "department of transportation",
            "dot toll", "toll road", "tollway", "toll authority", "toll plaza",
            "highway authority", "transportation authority", "turnpike authority",
            // Additional toll patterns
            "eractoll", "era toll", "toll payment", "toll charge", "toll fee",
            "road toll", "bridge toll", "tunnel toll", "highway toll", "expressway toll"
        };
        for (String dot : stateDOTPatterns) {
            if (normalizedMerchantName.contains(dot) || descriptionLower.contains(dot)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected state DOT/toll '{}' → 'transportation'", dot);
                return "transportation";
            }
        }
        
        // Amex Airlines Fee Reimbursement - transportation (even though it's a credit)
        if (normalizedMerchantName.contains("amex airlines fee reimbursement") || 
            normalizedMerchantName.contains("amexairlinesfeereimbursement") ||
            descriptionLower.contains("amex airlines fee reimbursement") ||
            descriptionLower.contains("amexairlinesfeereimbursement") ||
            (normalizedMerchantName.contains("amex") && normalizedMerchantName.contains("airlines") && 
             (normalizedMerchantName.contains("fee") || normalizedMerchantName.contains("reimbursement")))) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Amex Airlines Fee Reimbursement → 'transportation'");
            return "transportation";
        }
        
        // Car Service (Hona CTR, etc.)
        if (normalizedMerchantName.contains("hona ctr") || normalizedMerchantName.contains("honactr") ||
            normalizedMerchantName.contains("hona car service") || normalizedMerchantName.contains("honacarservice") ||
            descriptionLower.contains("hona ctr") || descriptionLower.contains("honactr") ||
            descriptionLower.contains("hona car service") || descriptionLower.contains("honacarservice") ||
            (normalizedMerchantName.contains("car service") || descriptionLower.contains("car service"))) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected car service → 'transportation'");
            return "transportation";
        }
        
        
        
        return null; // No match found
    }
}
