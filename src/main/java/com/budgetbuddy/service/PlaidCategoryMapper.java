package com.budgetbuddy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps Plaid's personal finance categories to our internal category system
 * Uses Plaid's category hierarchy as a starting point and enhances with custom logic
 * Supports category overrides - users can override Plaid's categorization
 */
@Service
public class PlaidCategoryMapper {

    private static final Logger logger = LoggerFactory.getLogger(PlaidCategoryMapper.class);
    
    /**
     * Category mapping result containing both primary and detailed categories
     */
    public static class CategoryMapping {
        private final String primary;
        private final String detailed;
        private final boolean overridden;
        
        public CategoryMapping(final String primary, final String detailed, final boolean overridden) {
            this.primary = primary;
            this.detailed = detailed;
            this.overridden = overridden;
        }
        
        public String getPrimary() {
            return primary;
        }
        
        public String getDetailed() {
            return detailed;
        }
        
        public boolean isOverridden() {
            return overridden;
        }
    }

    // Map Plaid primary categories to our internal categories
    private static final Map<String, String> PRIMARY_CATEGORY_MAP = new HashMap<>();
    
    // Map Plaid detailed categories to our internal categories (more specific)
    private static final Map<String, String> DETAILED_CATEGORY_MAP = new HashMap<>();

    static {
        // Initialize primary category mappings
        PRIMARY_CATEGORY_MAP.put("FOOD_AND_DRINK", "dining");
        PRIMARY_CATEGORY_MAP.put("GENERAL_MERCHANDISE", "shopping");
        PRIMARY_CATEGORY_MAP.put("GENERAL_SERVICES", "other");
        PRIMARY_CATEGORY_MAP.put("GOVERNMENT_AND_NON_PROFIT", "other");
        PRIMARY_CATEGORY_MAP.put("HOME_IMPROVEMENT", "other");
        PRIMARY_CATEGORY_MAP.put("MEDICAL", "healthcare");
        PRIMARY_CATEGORY_MAP.put("PERSONAL_CARE", "other");
        PRIMARY_CATEGORY_MAP.put("TRANSPORTATION", "transportation");
        PRIMARY_CATEGORY_MAP.put("TRAVEL", "travel");
        PRIMARY_CATEGORY_MAP.put("RENT_AND_UTILITIES", "rent");
        PRIMARY_CATEGORY_MAP.put("ENTERTAINMENT", "entertainment");
        PRIMARY_CATEGORY_MAP.put("GENERAL_SERVICES", "utilities");
        PRIMARY_CATEGORY_MAP.put("INCOME", "income");
        PRIMARY_CATEGORY_MAP.put("TRANSFER_IN", "income");
        PRIMARY_CATEGORY_MAP.put("TRANSFER_OUT", "other");
        PRIMARY_CATEGORY_MAP.put("LOAN_PAYMENTS", "other");
        PRIMARY_CATEGORY_MAP.put("BANK_FEES", "other");
        PRIMARY_CATEGORY_MAP.put("GAS_STATIONS", "transportation");
        PRIMARY_CATEGORY_MAP.put("GROCERIES", "groceries");
        PRIMARY_CATEGORY_MAP.put("SUBSCRIPTIONS", "subscriptions");
        PRIMARY_CATEGORY_MAP.put("INVESTMENT", "investment");

        // Initialize detailed category mappings (more specific)
        // Food and Drink
        DETAILED_CATEGORY_MAP.put("RESTAURANTS", "dining");
        DETAILED_CATEGORY_MAP.put("FAST_FOOD", "dining");
        DETAILED_CATEGORY_MAP.put("COFFEE_SHOPS", "dining");
        DETAILED_CATEGORY_MAP.put("FOOD_DELIVERY", "dining");
        DETAILED_CATEGORY_MAP.put("GROCERIES", "groceries");
        DETAILED_CATEGORY_MAP.put("SUPERMARKETS", "groceries");
        DETAILED_CATEGORY_MAP.put("ALCOHOL_AND_BARS", "dining");
        
        // Transportation
        DETAILED_CATEGORY_MAP.put("GAS_STATIONS", "transportation");
        DETAILED_CATEGORY_MAP.put("PUBLIC_TRANSPORTATION", "transportation");
        DETAILED_CATEGORY_MAP.put("TAXI", "transportation");
        DETAILED_CATEGORY_MAP.put("RIDE_SHARE", "transportation");
        DETAILED_CATEGORY_MAP.put("PARKING", "transportation");
        DETAILED_CATEGORY_MAP.put("TOLLS", "transportation");
        
        // Shopping
        DETAILED_CATEGORY_MAP.put("GENERAL_MERCHANDISE", "shopping");
        DETAILED_CATEGORY_MAP.put("ONLINE_MARKETPLACES", "shopping");
        DETAILED_CATEGORY_MAP.put("DEPARTMENT_STORES", "shopping");
        DETAILED_CATEGORY_MAP.put("CLOTHING_AND_ACCESSORIES", "shopping");
        DETAILED_CATEGORY_MAP.put("ELECTRONICS", "shopping");
        
        // Entertainment
        DETAILED_CATEGORY_MAP.put("ENTERTAINMENT", "entertainment");
        DETAILED_CATEGORY_MAP.put("MOVIES_AND_DVDS", "entertainment");
        DETAILED_CATEGORY_MAP.put("MUSIC_AND_AUDIO", "subscriptions");
        DETAILED_CATEGORY_MAP.put("GAMES_AND_GAMING", "entertainment");
        DETAILED_CATEGORY_MAP.put("SPORTS_AND_RECREATION", "entertainment");
        
        // Subscriptions
        DETAILED_CATEGORY_MAP.put("SOFTWARE_SUBSCRIPTIONS", "subscriptions");
        DETAILED_CATEGORY_MAP.put("STREAMING_SERVICES", "subscriptions");
        DETAILED_CATEGORY_MAP.put("MUSIC_STREAMING", "subscriptions");
        DETAILED_CATEGORY_MAP.put("NEWS_SUBSCRIPTIONS", "subscriptions");
        DETAILED_CATEGORY_MAP.put("GAMING_SUBSCRIPTIONS", "subscriptions");
        
        // Travel
        DETAILED_CATEGORY_MAP.put("HOTELS_AND_ACCOMMODATIONS", "travel");
        DETAILED_CATEGORY_MAP.put("AIR_TRAVEL", "travel");
        DETAILED_CATEGORY_MAP.put("RENTAL_CARS", "travel");
        DETAILED_CATEGORY_MAP.put("TRAVEL_AGENCIES", "travel");
        
        // Rent and Utilities
        DETAILED_CATEGORY_MAP.put("RENT", "rent");
        DETAILED_CATEGORY_MAP.put("UTILITIES", "utilities");
        DETAILED_CATEGORY_MAP.put("ELECTRICITY", "utilities");
        DETAILED_CATEGORY_MAP.put("WATER", "utilities");
        DETAILED_CATEGORY_MAP.put("GAS_AND_HEATING", "utilities");
        DETAILED_CATEGORY_MAP.put("INTERNET_AND_PHONE", "utilities");
        DETAILED_CATEGORY_MAP.put("CABLE", "utilities");
        
        // Income
        DETAILED_CATEGORY_MAP.put("SALARY", "income");
        DETAILED_CATEGORY_MAP.put("PAYROLL", "income");
        DETAILED_CATEGORY_MAP.put("DIVIDENDS", "income");
        DETAILED_CATEGORY_MAP.put("INTEREST_EARNED", "income");
        DETAILED_CATEGORY_MAP.put("GIG_ECONOMY", "income");
        DETAILED_CATEGORY_MAP.put("RENTAL_INCOME", "income");
        DETAILED_CATEGORY_MAP.put("INVESTMENT_INCOME", "income");
        
        // Healthcare
        DETAILED_CATEGORY_MAP.put("PRIMARY_CARE", "healthcare");
        DETAILED_CATEGORY_MAP.put("DENTAL_CARE", "healthcare");
        DETAILED_CATEGORY_MAP.put("PHARMACIES", "healthcare");
        DETAILED_CATEGORY_MAP.put("HOSPITALS", "healthcare");
        DETAILED_CATEGORY_MAP.put("HEALTH_INSURANCE", "healthcare");
        
        // Investment
        DETAILED_CATEGORY_MAP.put("CD_DEPOSIT", "investment");
        DETAILED_CATEGORY_MAP.put("CERTIFICATE_OF_DEPOSIT", "investment");
        DETAILED_CATEGORY_MAP.put("STOCKS", "investment");
        DETAILED_CATEGORY_MAP.put("BONDS", "investment");
        DETAILED_CATEGORY_MAP.put("MUTUAL_FUNDS", "investment");
        DETAILED_CATEGORY_MAP.put("ETF", "investment");
        DETAILED_CATEGORY_MAP.put("BROKERAGE", "investment");
        DETAILED_CATEGORY_MAP.put("RETIREMENT", "investment");
    }

    /**
     * Maps Plaid's personal finance category to our category structure
     * Returns both primary and detailed categories, preserving Plaid's hierarchy
     * Uses merchant name and description for enhanced categorization when needed
     * 
     * @param plaidCategoryPrimary Plaid's primary category (e.g., "FOOD_AND_DRINK")
     * @param plaidCategoryDetailed Plaid's detailed category (e.g., "RESTAURANTS")
     * @param merchantName Merchant name for additional context
     * @param description Transaction description for additional context
     * @return CategoryMapping with primary, detailed, and override flag
     */
    public CategoryMapping mapPlaidCategory(final String plaidCategoryPrimary, 
                                           final String plaidCategoryDetailed,
                                           final String merchantName,
                                           final String description) {
        String mappedPrimary = null;
        String mappedDetailed = null;
        
        // First, try detailed category mapping (more specific)
        if (plaidCategoryDetailed != null && !plaidCategoryDetailed.isEmpty()) {
            mappedDetailed = DETAILED_CATEGORY_MAP.get(plaidCategoryDetailed.toUpperCase());
            if (mappedDetailed != null) {
                logger.debug("Mapped Plaid detailed category '{}' to '{}'", plaidCategoryDetailed, mappedDetailed);
                // If detailed category is mapped, use it for primary as well (unless primary has a specific mapping)
                // This ensures consistency (e.g., GROCERIES -> groceries for both primary and detailed)
                if (mappedPrimary == null) {
                    mappedPrimary = mappedDetailed;
                }
            }
        }
        
        // Map primary category (only if not already set from detailed category)
        if (mappedPrimary == null && plaidCategoryPrimary != null && !plaidCategoryPrimary.isEmpty()) {
            String upperPrimary = plaidCategoryPrimary.toUpperCase();
            // Map "UNKNOWN_CATEGORY" to "other"
            if ("UNKNOWN_CATEGORY".equals(upperPrimary)) {
                mappedPrimary = "other";
            } else {
                mappedPrimary = PRIMARY_CATEGORY_MAP.get(upperPrimary);
                if (mappedPrimary == null) {
                    // If no mapping found, use Plaid's primary category as-is
                    mappedPrimary = plaidCategoryPrimary;
                }
            }
            logger.debug("Mapped Plaid primary category '{}' to '{}'", plaidCategoryPrimary, mappedPrimary);
        }
        
        // Enhanced logic: Check merchant name and description for better categorization
        String combinedText = ((merchantName != null ? merchantName : "") + " " + 
                              (description != null ? description : "")).toLowerCase();
        
        // CRITICAL: Check for investment-related transactions FIRST (before entertainment)
        // CD deposits, stocks, bonds, etc. should be categorized as investment, not entertainment
        if (combinedText.contains("cd deposit") || combinedText.contains("certificate of deposit") ||
            combinedText.contains("cd maturity") || combinedText.contains("cd interest") ||
            combinedText.contains(" stock") || combinedText.contains(" bond") ||
            combinedText.contains("mutual fund") || combinedText.contains(" etf") ||
            combinedText.contains("401k") || combinedText.contains(" ira") ||
            combinedText.contains("retirement") || combinedText.contains("brokerage")) {
            mappedDetailed = "investment";
            if (mappedPrimary == null || "entertainment".equals(mappedPrimary)) {
                mappedPrimary = "investment";
            }
            logger.debug("Enhanced mapping: detected investment (CD deposit/investment) from merchant/description");
        }
        
        // Enhanced categorization based on merchant/description
        if (mappedDetailed == null) {
            // Check for specific merchants/patterns to determine detailed category
            if (combinedText.contains("mcdonald") || combinedText.contains("starbucks") || 
                combinedText.contains("kfc") || combinedText.contains("burger") || 
                combinedText.contains("pizza") || combinedText.contains("coffee") ||
                combinedText.contains("restaurant") || combinedText.contains("dining")) {
                mappedDetailed = "dining";
                logger.debug("Enhanced mapping: detected dining from merchant/description");
            } else if (combinedText.contains("walmart") || combinedText.contains("target") || 
                      combinedText.contains("kroger") || combinedText.contains("supermarket") ||
                      combinedText.contains("grocer")) {
                mappedDetailed = "groceries";
                logger.debug("Enhanced mapping: detected groceries from merchant/description");
            } else if (combinedText.contains("uber") || combinedText.contains("lyft") || 
                      combinedText.contains("taxi") || combinedText.contains("gas") ||
                      combinedText.contains("fuel")) {
                mappedDetailed = "transportation";
                logger.debug("Enhanced mapping: detected transportation from merchant/description");
            } else if (combinedText.contains("netflix") || combinedText.contains("spotify") || 
                      combinedText.contains("subscription") || combinedText.contains("monthly") ||
                      combinedText.contains("annual")) {
                mappedDetailed = "subscriptions";
                logger.debug("Enhanced mapping: detected subscription from merchant/description");
            }
        }
        
        // Ensure primary is set first
        if (mappedPrimary == null) {
            // If all inputs were null/empty, return UNKNOWN_CATEGORY instead of "other"
            if ((plaidCategoryPrimary == null || plaidCategoryPrimary.isEmpty()) &&
                (plaidCategoryDetailed == null || plaidCategoryDetailed.isEmpty()) &&
                (merchantName == null || merchantName.isEmpty()) &&
                (description == null || description.isEmpty())) {
                mappedPrimary = "UNKNOWN_CATEGORY";
            } else {
                mappedPrimary = "other";
            }
        }
        
        // If still no detailed category, use primary or default
        if (mappedDetailed == null) {
            mappedDetailed = mappedPrimary != null ? mappedPrimary : "UNKNOWN_CATEGORY";
        }
        
        return new CategoryMapping(mappedPrimary, mappedDetailed, false);
    }
    
    /**
     * Applies a user override to the category
     * 
     * @param originalMapping Original category mapping from Plaid
     * @param overridePrimary User's override primary category
     * @param overrideDetailed User's override detailed category
     * @return New CategoryMapping with override flag set to true
     */
    public CategoryMapping applyOverride(final CategoryMapping originalMapping,
                                        final String overridePrimary,
                                        final String overrideDetailed) {
        String primary = overridePrimary != null && !overridePrimary.isEmpty() 
                        ? overridePrimary 
                        : originalMapping.getPrimary();
        String detailed = overrideDetailed != null && !overrideDetailed.isEmpty() 
                         ? overrideDetailed 
                         : originalMapping.getDetailed();
        
        return new CategoryMapping(primary, detailed, true);
    }
}

