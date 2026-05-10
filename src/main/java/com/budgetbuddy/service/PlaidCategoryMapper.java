package com.budgetbuddy.service;


import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Maps Plaid's personal finance categories to our internal category system Uses Plaid's category
 * hierarchy as a starting point and enhances with custom logic Supports category overrides - users
 * can override Plaid's categorization
 */
@Service
public class PlaidCategoryMapper {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaidCategoryMapper.class);

    /** Category mapping result containing both primary and detailed categories */
    public static class CategoryMapping {
        private final String primary;
        private final String detailed;
        private final boolean overridden;

        public CategoryMapping(
                final String primary, final String detailed, final boolean overridden) {
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
        PRIMARY_CATEGORY_MAP.put(
                "PAYMENT", "payment"); // Credit card payments and recurring ACH payments

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

        // Income - CRITICAL: "income" is ONLY a primary category type, NOT a detailed category
        // Use specific income types (salary, interest, dividend, etc.) as detailed categories
        DETAILED_CATEGORY_MAP.put("SALARY", "salary");
        DETAILED_CATEGORY_MAP.put("PAYROLL", "salary");
        DETAILED_CATEGORY_MAP.put("DIVIDENDS", "dividend");
        DETAILED_CATEGORY_MAP.put(
                "INTEREST_EARNED",
                "interest"); // Note: CD interest will be overridden to investment by enhanced logic
        DETAILED_CATEGORY_MAP.put("GIG_ECONOMY", "otherIncome");
        DETAILED_CATEGORY_MAP.put("RENTAL_INCOME", "rentIncome");
        DETAILED_CATEGORY_MAP.put(
                "INVESTMENT_INCOME",
                "dividend"); // Note: Investment income from CD deposits will be overridden to
        // investment by enhanced logic

        // Healthcare
        DETAILED_CATEGORY_MAP.put("PRIMARY_CARE", "healthcare");
        DETAILED_CATEGORY_MAP.put("DENTAL_CARE", "healthcare");
        DETAILED_CATEGORY_MAP.put("PHARMACIES", "healthcare");
        DETAILED_CATEGORY_MAP.put("HOSPITALS", "healthcare");
        DETAILED_CATEGORY_MAP.put("HEALTH_INSURANCE", "healthcare");

        // Investment - specific types
        DETAILED_CATEGORY_MAP.put("CD_DEPOSIT", "cd");
        DETAILED_CATEGORY_MAP.put("CERTIFICATE_OF_DEPOSIT", "cd");
        DETAILED_CATEGORY_MAP.put("STOCKS", "stocks");
        DETAILED_CATEGORY_MAP.put("BONDS", "bonds");
        DETAILED_CATEGORY_MAP.put("MUTUAL_FUNDS", "mutualFunds");
        DETAILED_CATEGORY_MAP.put("ETF", "etf");
        DETAILED_CATEGORY_MAP.put("BROKERAGE", "otherInvestment");
        DETAILED_CATEGORY_MAP.put("RETIREMENT", "otherInvestment");
        // Legacy investment mapping (fallback)
        DETAILED_CATEGORY_MAP.put("INVESTMENT", "investment");

        // Payment
        DETAILED_CATEGORY_MAP.put("CREDIT_CARD_PAYMENT", "payment");
        DETAILED_CATEGORY_MAP.put("LOAN_PAYMENT", "payment");
    }

    /**
     * Maps Plaid's personal finance category to our category structure Returns both primary and
     * detailed categories, preserving Plaid's hierarchy Uses merchant name and description for
     * enhanced categorization when needed
     *
     * @param plaidCategoryPrimary Plaid's primary category (e.g., "FOOD_AND_DRINK")
     * @param plaidCategoryDetailed Plaid's detailed category (e.g., "RESTAURANTS")
     * @param merchantName Merchant name for additional context
     * @param description Transaction description for additional context
     * @return CategoryMapping with primary, detailed, and override flag
     */
    public CategoryMapping mapPlaidCategory(
            final String plaidCategoryPrimary,
            final String plaidCategoryDetailed,
            final String merchantName,
            final String description) {
        return mapPlaidCategory(
                plaidCategoryPrimary, plaidCategoryDetailed, merchantName, description, null, null);
    }

    /**
     * Maps Plaid's personal finance category to our category structure Returns both primary and
     * detailed categories, preserving Plaid's hierarchy Uses merchant name and description for
     * enhanced categorization when needed
     *
     * @param plaidCategoryPrimary Plaid's primary category (e.g., "FOOD_AND_DRINK")
     * @param plaidCategoryDetailed Plaid's detailed category (e.g., "RESTAURANTS")
     * @param merchantName Merchant name for additional context
     * @param description Transaction description for additional context
     * @param paymentChannel Payment channel (e.g., "ach", "online", "in_store") - optional, used
     *     for ACH credit detection
     * @param amount Transaction amount - optional, used for ACH credit detection (positive =
     *     credit)
     * @return CategoryMapping with primary, detailed, and override flag
     */
    public CategoryMapping mapPlaidCategory(
            final String plaidCategoryPrimary,
            final String plaidCategoryDetailed,
            final String merchantName,
            final String description,
            final String paymentChannel,
            final java.math.BigDecimal amount) {
        String mappedPrimary = null;
        String mappedDetailed = null;

        // First, try detailed category mapping (more specific)
        if (plaidCategoryDetailed != null && !plaidCategoryDetailed.isEmpty()) {
            mappedDetailed = DETAILED_CATEGORY_MAP.get(plaidCategoryDetailed.toUpperCase(Locale.ROOT));
            if (mappedDetailed != null) {
                LOGGER.debug(
                        "Mapped Plaid detailed category '{}' to '{}'",
                        plaidCategoryDetailed,
                        mappedDetailed);
                // If detailed category is mapped, use it for primary as well (unless primary has a
                // specific mapping)
                // This ensures consistency (e.g., GROCERIES -> groceries for both primary and
                // detailed)
                // CRITICAL: For income categories, primary should always be "income", not the
                // detailed category
                // Income detailed categories (salary, interest, dividend, etc.) should have primary
                // = "income"
                if (mappedPrimary == null) {
                    // Check if this is an income-related detailed category
                    final boolean isIncomeDetailed =
                            "salary".equals(mappedDetailed)
                                    || "interest".equals(mappedDetailed)
                                    || "dividend".equals(mappedDetailed)
                                    || "stipend".equals(mappedDetailed)
                                    || "rentIncome".equals(mappedDetailed)
                                    || "tips".equals(mappedDetailed)
                                    || "otherIncome".equals(mappedDetailed);
                    if (isIncomeDetailed) {
                        // For income detailed categories, primary should be "income", not the
                        // detailed category
                        mappedPrimary =
                                null; // Will be set to "income" by primary category mapping below
                    } else {
                        mappedPrimary = mappedDetailed;
                    }
                }
            }
        }

        // Map primary category (only if not already set from detailed category)
        if (mappedPrimary == null
                && plaidCategoryPrimary != null
                && !plaidCategoryPrimary.isEmpty()) {
            final String upperPrimary = plaidCategoryPrimary.toUpperCase(Locale.ROOT);
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
            LOGGER.debug(
                    "Mapped Plaid primary category '{}' to '{}'",
                    plaidCategoryPrimary,
                    mappedPrimary);
        }

        // Enhanced logic: Check merchant name and description for better categorization
        final String combinedText =
                ((merchantName != null ? merchantName : "")
                        + " "
                        + (description != null ? description : ""))
                        .toLowerCase(Locale.ROOT);

        // Prepare lowercase versions for pattern matching
        final String descriptionLower = (description != null ? description : "").toLowerCase(Locale.ROOT);
        final String merchantLower = (merchantName != null ? merchantName : "").toLowerCase(Locale.ROOT);
        final String combinedTextLower = (merchantLower + " " + descriptionLower).toLowerCase(Locale.ROOT);

        // CRITICAL: Check for investment-related transactions FIRST (before entertainment and
        // income)
        // CD deposits, stocks, bonds, etc. should be categorized as specific investment types, not
        // entertainment or income
        final String detectedInvestmentType =
                determineInvestmentType(combinedTextLower, description, merchantName);
        if (detectedInvestmentType != null) {
            mappedDetailed = detectedInvestmentType;
            // ALWAYS override primary to "investment" when investment type is detected
            // This ensures CD deposits, stocks, etc. are not categorized as income, salary,
            // entertainment, etc.
            mappedPrimary = "investment";
            LOGGER.debug(
                    "Enhanced mapping: detected investment type '{}' from merchant/description, overriding primary to 'investment'",
                    detectedInvestmentType);
        }

        // CRITICAL: ACH credits should be income, not rent/expense
        // This MUST happen BEFORE payment detection to ensure ACH credits are income, not payments
        // This overrides any category that might have been incorrectly assigned (e.g., "rent" or
        // "utilities" from Plaid)
        // Check for "ACH Electronic Credit" in description/merchant name (case-insensitive)
        // This handles cases like "ACH Electronic CreditGUSTO PAY 123456" where paymentChannel
        // might not be set correctly
        // CRITICAL: Rely on description pattern alone - "ACH Electronic Credit" is a strong signal
        // for income
        // regardless of amount sign (Plaid may send income as +ve or -ve depending on account type)
        final boolean isACHCreditByDescription =
                combinedTextLower.contains("ach electronic credit")
                        || combinedTextLower.contains("ach credit")
                        || (combinedTextLower.contains("ach")
                        && combinedTextLower.contains("credit"));

        // Also check by paymentChannel, but only if it's NOT an ACH debit
        // For channel-based detection, we need to distinguish credits from debits
        // Check for ACH debit patterns first to avoid false positives
        final boolean isACHDebitPattern =
                combinedTextLower.contains("ach electronic debit")
                        || combinedTextLower.contains("ach debit")
                        || (combinedTextLower.contains("ach")
                        && combinedTextLower.contains("debit"));

        // ACH credit by channel: paymentChannel is "ach" AND not an ACH debit pattern
        // CRITICAL: For channel-based detection without explicit credit/debit keywords in
        // description,
        // we need to check amount sign to distinguish credits from debits
        // After normalization: positive = income (credit), negative = expense (debit)
        // This is safe because normalization already handles Plaid's sign reversal
        final boolean isACHCreditByChannel =
                "ach".equalsIgnoreCase(paymentChannel)
                        && !isACHDebitPattern
                        && amount != null
                        && amount.compareTo(java.math.BigDecimal.ZERO) > 0;

        final boolean isACHCredit = isACHCreditByDescription || isACHCreditByChannel;

        if (isACHCredit) {
            // ACH credit is income, not expense
            // CRITICAL: Use "deposit" as the category since we don't know if it's really salary
            // Only use specific income categories if we can clearly identify them (e.g., interest,
            // dividend)
            // For generic ACH credits without clear indicators, use "deposit" instead of defaulting
            // to "salary"
            final String incomeCategory = determineIncomeCategory(description, merchantName);

            // If determineIncomeCategory returns "salary", check if there are explicit salary
            // indicators
            // Only use "salary" if there are explicit keywords or payroll service names
            // Generic ACH credits without indicators should default to "deposit"
            if ("salary".equals(incomeCategory)) {
                // Check if description/merchant contains explicit salary keywords or payroll
                // service names
                // Use combinedTextLower which is already defined and lowercase
                final boolean hasSalaryIndicators =
                        combinedTextLower.contains("salary")
                                || combinedTextLower.contains("payroll")
                                || combinedTextLower.contains("paycheck")
                                || combinedTextLower.contains("direct deposit")
                                || combinedTextLower.contains("wages")
                                || combinedTextLower.contains("pay stub")
                                || combinedTextLower.contains("payroll deposit")
                                || combinedTextLower.contains("payroll direct")
                                || combinedTextLower.contains("gusto")
                                || combinedTextLower.contains("adp")
                                || combinedTextLower.contains("paychex")
                                || combinedTextLower.contains("quickbooks payroll")
                                || combinedTextLower.contains("paycom")
                                || combinedTextLower.contains("bamboohr");

                if (hasSalaryIndicators) {
                    // Has explicit salary indicators - use "salary"
                    mappedDetailed = "salary";
                } else {
                    // No explicit salary indicators - use "deposit" instead
                    mappedDetailed = "deposit";
                }
            } else if ("interest".equals(incomeCategory)
                    || "dividend".equals(incomeCategory)
                    || "stipend".equals(incomeCategory)
                    || "rentIncome".equals(incomeCategory)
                    || "tips".equals(incomeCategory)
                    || "otherIncome".equals(incomeCategory)) {
                // Use the specific income category that was detected
                mappedDetailed = incomeCategory;
            } else {
                // Default to "deposit" for ACH credits when we can't determine the specific type
                mappedDetailed = "deposit";
            }

            mappedPrimary = "income";
            LOGGER.debug(
                    "Enhanced mapping: ACH credit detected (description={}, paymentChannel={}, amount={}) - categorized as income/{}",
                    description,
                    paymentChannel,
                    amount,
                    mappedDetailed);
        }

        // CRITICAL: Check for payments AFTER ACH credit check (so ACH credits are income, not
        // payments)
        // Credit card payments: transactions with "credit card payment" in description

        final boolean isCreditCardPayment =
                (descriptionLower.contains("credit card")
                        || descriptionLower.contains("creditcard")
                        || descriptionLower.contains("cc payment")
                        || descriptionLower.contains("card payment"))
                        && (descriptionLower.contains("payment")
                        || descriptionLower.contains("pay")
                        || descriptionLower.contains("transfer"));

        // Automatic payments: Check for "automatic payment", "autopay", "auto pay", etc.
        // This should be categorized as payment, not expense
        final boolean isAutomaticPayment =
                combinedTextLower.contains("automatic payment")
                        || combinedTextLower.contains("autopay")
                        || combinedTextLower.contains("auto pay")
                        || combinedTextLower.contains("auto-pay")
                        || combinedTextLower.contains("autopayment")
                        || (combinedTextLower.contains("automatic")
                        && combinedTextLower.contains("payment"));

        // Direct payments: Check for "directpay", "direct pay", "direct payment", "directpayment",
        // "automatyment"
        // CRITICAL: Positive amounts with payment keywords like "DIRECTPAY" should be Payment type,
        // not Expense
        // This handles cases like "1% Cashback Bonus +$0.06 DIRECTPAY FULL BALANCE" with positive
        // amount
        final boolean isDirectPayment =
                combinedTextLower.contains("directpay")
                        || combinedTextLower.contains("direct pay")
                        || combinedTextLower.contains("direct-pay")
                        || combinedTextLower.contains("direct payment")
                        || combinedTextLower.contains("directpayment")
                        || combinedTextLower.contains("automatyment"); // Common misspelling

        // ACH Debit: Check for "ACH Debit" or "ACH Electronic Debit" in description/merchant name
        // This should be categorized as payment, not expense
        final boolean isACHDebitByDescription =
                !isACHCredit
                        && // Don't treat ACH credits as debits
                        (combinedTextLower.contains("ach electronic debit")
                                || combinedTextLower.contains("ach debit")
                                || (combinedTextLower.contains("ach")
                                && combinedTextLower.contains("debit")
                                && amount != null
                                && amount.compareTo(java.math.BigDecimal.ZERO) < 0));

        // Recurring ACH payments: negative ACH transactions with recurring keywords
        // NOTE: Only for negative ACH transactions (debits), not credits
        final boolean isRecurringACHPayment =
                !isACHCredit
                        && // Don't treat ACH credits as payments
                        paymentChannel != null
                        && "ach".equalsIgnoreCase(paymentChannel)
                        && amount != null
                        && amount.compareTo(java.math.BigDecimal.ZERO) < 0
                        && (combinedTextLower.contains("recurring")
                        || combinedTextLower.contains("monthly")
                        || combinedTextLower.contains("subscription")
                        || combinedTextLower.contains("autopay")
                        || combinedTextLower.contains("auto pay")
                        || combinedTextLower.contains("auto-pay")
                        || combinedTextLower.contains("bill pay")
                        || combinedTextLower.contains("billpay")
                        || combinedTextLower.contains("recurring charge"));

        // Also check for ACH debit by paymentChannel and negative amount
        final boolean isACHDebitByChannel =
                !isACHCredit
                        && // Don't treat ACH credits as debits
                        paymentChannel != null
                        && "ach".equalsIgnoreCase(paymentChannel)
                        && amount != null
                        && amount.compareTo(java.math.BigDecimal.ZERO) < 0;

        final boolean isACHDebit = isACHDebitByDescription || isACHDebitByChannel;

        if (isCreditCardPayment
                || isRecurringACHPayment
                || isACHDebit
                || isAutomaticPayment
                || isDirectPayment) {
            mappedDetailed = "payment";
            mappedPrimary = "payment";
            final String paymentType =
                    isCreditCardPayment
                            ? "Credit card payment"
                            : isDirectPayment
                            ? "Direct payment"
                            : isAutomaticPayment
                            ? "Automatic payment"
                            : isACHDebit ? "ACH debit" : "Recurring ACH payment";
            LOGGER.debug(
                    "Enhanced mapping: {} detected (description={}, paymentChannel={}, amount={}) - overriding category to payment",
                    paymentType,
                    description,
                    paymentChannel,
                    amount);
        }

        // CRITICAL: Check for airline transactions and override to travel
        // This MUST happen AFTER payment detection but BEFORE other enhanced categorization
        // This ensures airline transactions are always categorized as travel, not utilities or
        // other categories
        // Check for airline keywords in merchant name and description
        final boolean isAirlineTransaction =
                detectAirlineTransaction(combinedTextLower, merchantLower, descriptionLower);
        if (isAirlineTransaction) {
            mappedDetailed = "travel";
            mappedPrimary = "travel";
            LOGGER.debug(
                    "Enhanced mapping: airline transaction detected (merchant={}, description={}) - overriding category to travel",
                    merchantName,
                    description);
        }

        // CRITICAL: Check for airport lounge transactions and override to travel
        // This MUST happen AFTER airline detection but BEFORE other enhanced categorization
        // This ensures airport lounges (like AXP Centurion Lounge) are categorized as travel, not
        // utilities or other categories
        final boolean isAirportLounge =
                detectAirportLounge(combinedTextLower, merchantLower, descriptionLower);
        if (isAirportLounge) {
            mappedDetailed = "travel";
            mappedPrimary = "travel";
            LOGGER.debug(
                    "Enhanced mapping: airport lounge transaction detected (merchant={}, description={}) - overriding category to travel",
                    merchantName,
                    description);
        }

        // CRITICAL: Check for shopping/retail transactions and override to shopping
        // This MUST happen AFTER airline detection but BEFORE transportation checks
        // This ensures shopping retailers (like Lululemon) are categorized as shopping, not
        // transportation
        // The issue: "lul" in "lululemon" might match transportation keywords, so we need to detect
        // shopping first
        // BUT: Don't override if Plaid already categorized as GROCERIES (e.g., Walmart with
        // GROCERIES category)
        final boolean isPlaidGroceries =
                ("GROCERIES".equalsIgnoreCase(plaidCategoryDetailed))
                        || ("GROCERIES".equalsIgnoreCase(plaidCategoryPrimary));
        final boolean isShoppingTransaction =
                !isPlaidGroceries
                        && detectShoppingTransaction(
                        combinedTextLower, merchantLower, descriptionLower);
        if (isShoppingTransaction) {
            mappedDetailed = "shopping";
            mappedPrimary = "shopping";
            LOGGER.debug(
                    "Enhanced mapping: shopping transaction detected (merchant={}, description={}) - overriding category to shopping",
                    merchantName,
                    description);
        }

        // CRITICAL: Check for movie theater/entertainment transactions and override to
        // entertainment
        // This MUST happen AFTER shopping detection to ensure movie theaters are categorized as
        // entertainment, not education or other categories
        // This ensures AMC and other movie theaters are always categorized as entertainment
        final boolean isEntertainmentTransaction =
                detectEntertainmentTransaction(combinedTextLower, merchantLower, descriptionLower);
        if (isEntertainmentTransaction) {
            mappedDetailed = "entertainment";
            mappedPrimary = "entertainment";
            LOGGER.debug(
                    "Enhanced mapping: entertainment transaction detected (merchant={}, description={}) - overriding category to entertainment",
                    merchantName,
                    description);
        }

        // CRITICAL: Check for holdings/company transactions and override to other
        // This MUST happen BEFORE dining detection to prevent holdings companies from being
        // categorized as dining
        // Holdings companies (like TRG Holdings) are business entities and should be "other", not
        // "dining"
        final boolean isHoldingsCompany =
                detectHoldingsCompany(combinedTextLower, merchantLower, descriptionLower);
        if (isHoldingsCompany) {
            mappedDetailed = "other";
            mappedPrimary = "other";
            LOGGER.debug(
                    "Enhanced mapping: holdings company detected (merchant={}, description={}) - overriding category to other",
                    merchantName,
                    description);
        }

        // Enhanced categorization based on merchant/description
        if (mappedDetailed == null) {
            // Check for specific merchants/patterns to determine detailed category
            if (combinedText.contains("mcdonald")
                    || combinedText.contains("starbucks")
                    || combinedText.contains("kfc")
                    || combinedText.contains("burger")
                    || combinedText.contains("pizza")
                    || combinedText.contains("coffee")
                    || combinedText.contains("restaurant")
                    || combinedText.contains("dining")) {
                mappedDetailed = "dining";
                LOGGER.debug("Enhanced mapping: detected dining from merchant/description");
            } else if (combinedText.contains("walmart")
                    || combinedText.contains("target")
                    || combinedText.contains("kroger")
                    || combinedText.contains("supermarket")
                    || combinedText.contains("grocer")) {
                mappedDetailed = "groceries";
                LOGGER.debug("Enhanced mapping: detected groceries from merchant/description");
            } else if (combinedText.contains("uber")
                    || combinedText.contains("lyft")
                    || combinedText.contains("taxi")
                    || combinedText.contains("gas")
                    || combinedText.contains("fuel")) {
                mappedDetailed = "transportation";
                LOGGER.debug("Enhanced mapping: detected transportation from merchant/description");
            } else if (combinedText.contains("netflix")
                    || combinedText.contains("spotify")
                    || combinedText.contains("subscription")
                    || combinedText.contains("monthly")
                    || combinedText.contains("annual")) {
                mappedDetailed = "subscriptions";
                LOGGER.debug("Enhanced mapping: detected subscription from merchant/description");
            }
        }

        // Ensure primary is set first
        if (mappedPrimary == null) {
            // If all inputs were null/empty, return UNKNOWN_CATEGORY instead of "other"
            if ((plaidCategoryPrimary == null || plaidCategoryPrimary.isEmpty())
                    && (plaidCategoryDetailed == null || plaidCategoryDetailed.isEmpty())
                    && (merchantName == null || merchantName.isEmpty())
                    && (description == null || description.isEmpty())) {
                mappedPrimary = "UNKNOWN_CATEGORY";
            } else {
                mappedPrimary = "other";
            }
        }

        // If still no detailed category, use primary or default
        if (mappedDetailed == null) {
            mappedDetailed = mappedPrimary;
        }

        // CRITICAL: If mappedDetailed is "income", determine specific income category from
        // description
        // This ensures income transactions use specific categories (salary, interest, dividend,
        // etc.) instead of generic "income"
        // BUT: Only do this if we haven't already detected an investment type (prevents CD deposits
        // from being categorized as salary/interest)
        // AND: Only do this if it's NOT an ACH credit (ACH credits already have specific income
        // category determined above)
        // NOTE: For interest payments, we want Income/Interest, not Income/Income
        if (("income".equals(mappedDetailed) || "income".equals(mappedPrimary))
                && detectedInvestmentType == null
                && !isACHCredit) {
            // Determine specific income category from description
            final String specificIncomeCategory = determineIncomeCategory(description, merchantName);
            if (!"income".equals(specificIncomeCategory)) {
                mappedDetailed = specificIncomeCategory;
                LOGGER.debug(
                        "Enhanced mapping: determined specific income category '{}' from description/merchant",
                        specificIncomeCategory);
            }
        }

        // CRITICAL FIX: Override category if description contains interest keywords (like "INTRST
        // PYMNT")
        // This ensures interest payments are always categorized as Income/Interest, even if Plaid
        // sent "other" or no category
        // This MUST happen AFTER all other categorization logic to ensure it overrides incorrect
        // categorizations
        // Check for interest keywords in description (including misspellings like "INTRST", "INTR",
        // etc.)
        if (detectedInvestmentType == null && !isACHCredit) {
            final String interestCategory = determineInterestFromDescription(description, merchantName);
            if (interestCategory != null) {
                mappedDetailed = interestCategory;
                mappedPrimary = "income";
                LOGGER.debug(
                        "Enhanced mapping: overrode category to Income/Interest for transaction with description: {}",
                        description);
            }
        }

        return new CategoryMapping(mappedPrimary, mappedDetailed, false);
    }

    /**
     * Determines specific income category from transaction description and merchant name Scans for
     * keywords to categorize income as: salary, interest, dividend, stipend, rentIncome, tips, or
     * otherIncome
     *
     * @param description Transaction description
     * @param merchantName Merchant name
     * @return Specific income category string, or "income" if no specific match found
     */
    /**
     * Determines specific investment type from description and merchant name Scans for keywords
     * like CD, bonds, stocks, 401K, IRA, etc. Returns null if not an investment, or the specific
     * investment type category string
     */
    private String determineInvestmentType(
            final String combinedTextLower, final String description, final String merchantName) {
        // CD (Certificate of Deposit) - check first as it's most specific
        if (combinedTextLower.contains("cd deposit")
                || combinedTextLower.contains("certificate of deposit")
                || combinedTextLower.contains("cd maturity")
                || combinedTextLower.contains("cd interest")
                || combinedTextLower.contains(" cd ")
                || combinedTextLower.contains("cd ")
                || combinedTextLower.contains(" certificate of deposit")) {
            return "cd";
        }

        // Municipal Bonds - check before generic bonds
        if (combinedTextLower.contains("municipal bond")
                || combinedTextLower.contains("muni bond")
                || combinedTextLower.contains("muni ")
                || combinedTextLower.contains("municipal")) {
            return "municipalBonds";
        }

        // Treasury Bills / T-Bills - check before generic bonds
        if (combinedTextLower.contains("treasury bill")
                || combinedTextLower.contains("t-bill")
                || combinedTextLower.contains("t bill")
                || combinedTextLower.contains("treasury")
                || combinedTextLower.contains("us treasury")
                || combinedTextLower.contains("u.s. treasury")) {
            return "tBills";
        }

        // Bonds (generic) - check after municipal and treasury
        if (combinedTextLower.contains(" bond")
                || combinedTextLower.contains("bond ")
                || combinedTextLower.contains("corporate bond")
                || combinedTextLower.contains("government bond")) {
            return "bonds";
        }

        // 401K
        if (combinedTextLower.contains("401k")
                || combinedTextLower.contains("401(k)")
                || combinedTextLower.contains("401 k")
                || combinedTextLower.contains("four zero one k")) {
            return "fourZeroOneK";
        }

        // 529 Plan
        if (combinedTextLower.contains("529")
                || combinedTextLower.contains("five two nine")
                || combinedTextLower.contains("529 plan")
                || combinedTextLower.contains("college savings")) {
            return "fiveTwoNine";
        }

        // IRA
        if (combinedTextLower.contains(" ira ")
                || combinedTextLower.contains("ira ")
                || combinedTextLower.contains("individual retirement")
                || combinedTextLower.contains("roth ira")
                || combinedTextLower.contains("traditional ira")
                || combinedTextLower.contains("sep ira")) {
            return "ira";
        }

        // Stocks
        if (combinedTextLower.contains(" stock")
                || combinedTextLower.contains("stock ")
                || combinedTextLower.contains("equity")
                || combinedTextLower.contains("share")
                || combinedTextLower.contains("common stock")
                || combinedTextLower.contains("preferred stock")) {
            return "stocks";
        }

        // ETF - check BEFORE mutual funds (ETF is more specific)
        if (combinedTextLower.contains(" etf")
                || combinedTextLower.contains("etf ")
                || combinedTextLower.contains("exchange traded fund")
                || combinedTextLower.contains("exchange-traded")) {
            return "etf";
        }

        // Mutual Funds - check AFTER ETF (mutual fund pattern is broader)
        if (combinedTextLower.contains("mutual fund")
                || combinedTextLower.contains("mutualfund")
                || (combinedTextLower.contains("fund ")
                        && !combinedTextLower.contains("etf")
                        && (combinedTextLower.contains("investment")
                                || combinedTextLower.contains("portfolio")))) {
            return "mutualFunds";
        }

        // Money Market
        if (combinedTextLower.contains("money market")
                || combinedTextLower.contains("moneymarket")
                || combinedTextLower.contains("mm account")
                || combinedTextLower.contains("mm fund")) {
            return "moneyMarket";
        }

        // Precious Metals
        if (combinedTextLower.contains("gold")
                || combinedTextLower.contains("silver")
                || combinedTextLower.contains("platinum")
                || combinedTextLower.contains("palladium")
                || combinedTextLower.contains("precious metal")
                || combinedTextLower.contains("bullion")) {
            return "preciousMetals";
        }

        // Crypto
        if (combinedTextLower.contains("crypto")
                || combinedTextLower.contains("bitcoin")
                || combinedTextLower.contains("ethereum")
                || combinedTextLower.contains("blockchain")
                || combinedTextLower.contains("btc")
                || combinedTextLower.contains("eth")
                || combinedTextLower.contains("cryptocurrency")
                || combinedTextLower.contains("digital currency")) {
            return "crypto";
        }

        // Generic investment keywords (fallback to generic investment category)
        if (combinedTextLower.contains("investment")
                || combinedTextLower.contains("brokerage")
                || combinedTextLower.contains("retirement")
                || combinedTextLower.contains("portfolio")
                || combinedTextLower.contains("securities")
                || combinedTextLower.contains("trading")) {
            return "otherInvestment";
        }

        // Not an investment
        return null;
    }

    /**
     * Determines if a transaction is an interest payment based on description This is a separate
     * method to allow checking interest even when category is "other"
     *
     * @param description Transaction description
     * @param merchantName Merchant name
     * @return "interest" if interest payment detected, null otherwise
     */
    private String determineInterestFromDescription(
            final String description, final String merchantName) {
        if (description == null && merchantName == null) {
            return null;
        }

        final String combinedText =
                ((merchantName != null ? merchantName : "")
                        + " "
                        + (description != null ? description : ""))
                        .toLowerCase(Locale.ROOT);

        // Interest - check for interest, interest payment, interest earned, savings interest
        // CRITICAL: Also check for misspellings like "INTRST", "INTR", "INTREST", "INTRST PYMNT",
        // etc.
        // CRITICAL: Exclude CD interest (handled as investment) and certificate-related interest
        final boolean isInterest =
                combinedText.contains("interest")
                        || combinedText.contains("intrst")
                        || // Misspelling: INTRST
                        combinedText.contains("intr ")
                        || // Misspelling: INTR (with space)
                        combinedText.contains("intrest")
                        || // Misspelling: INTREST
                        combinedText.contains("intr payment")
                        || // INTR payment
                        combinedText.contains("intrst payment")
                        || // INTRST payment
                        combinedText.contains(
                                "intrst pymnt"); // INTRST PYMNT (common bank abbreviation)

        if (isInterest
                && !combinedText.contains("cd interest")
                && // CD interest is handled separately (investment)
                !combinedText.contains("certificate")
                && !combinedText.contains("cd ")) { // Also exclude any CD-related interest
            return "interest";
        }

        return null;
    }

    private String determineIncomeCategory(final String description, final String merchantName) {
        if (description == null && merchantName == null) {
            return "salary"; // Default to salary (most common income type) - "income" is ONLY a
            // primary category, not detailed
        }

        final String combinedText =
                ((merchantName != null ? merchantName : "")
                        + " "
                        + (description != null ? description : ""))
                        .toLowerCase(Locale.ROOT);

        // Salary/Payroll - check for salary, payroll, paycheck, direct deposit, wages
        // CRITICAL: Include payroll services like Gusto, ADP, Paychex, etc.
        // CRITICAL: Exclude CD deposits (handled as investment)
        if ((combinedText.contains("salary")
                        || combinedText.contains("payroll")
                        || combinedText.contains("paycheck")
                        || combinedText.contains("direct deposit")
                        || combinedText.contains("wages")
                        || combinedText.contains("pay stub")
                        || combinedText.contains("payroll deposit")
                        || combinedText.contains("payroll direct")
                        || combinedText.contains("gusto")
                        || combinedText.contains("adp")
                        || combinedText.contains("paychex")
                        || combinedText.contains("quickbooks payroll")
                        || combinedText.contains("paycom")
                        || combinedText.contains("bamboohr"))
                && !combinedText.contains("cd deposit")
                && // Exclude CD deposits
                !combinedText.contains("certificate of deposit")
                && // Exclude certificate of deposit
                !combinedText.contains(" cd ")) { // Exclude any CD-related deposits
            return "salary";
        }

        // Interest - check for interest, interest payment, interest earned, savings interest
        // CRITICAL: Also check for misspellings like "INTRST", "INTR", "INTREST", etc.
        // CRITICAL: Exclude CD interest (handled as investment) and certificate-related interest
        final boolean isInterest =
                combinedText.contains("interest")
                        || combinedText.contains("intrst")
                        || // Misspelling: INTRST
                        combinedText.contains("intr ")
                        || // Misspelling: INTR (with space)
                        combinedText.contains("intrest")
                        || // Misspelling: INTREST
                        combinedText.contains("intr payment")
                        || // INTR payment
                        combinedText.contains("intrst payment")
                        || // INTRST payment
                        combinedText.contains(
                                "intrst pymnt"); // INTRST PYMNT (common bank abbreviation)

        if (isInterest
                && !combinedText.contains("cd interest")
                && // CD interest is handled separately (investment)
                !combinedText.contains("certificate")
                && !combinedText.contains("cd ")) { // Also exclude any CD-related interest
            return "interest";
        }

        // Dividend - check for dividend, dividend payment, stock dividend
        if (combinedText.contains("dividend")
                || combinedText.contains("stock dividend")
                || combinedText.contains("dividend payment")) {
            return "dividend";
        }

        // Stipend - check for stipend, scholarship, grant, fellowship
        if (combinedText.contains("stipend")
                || combinedText.contains("scholarship")
                || combinedText.contains("grant")
                || combinedText.contains("fellowship")
                || combinedText.contains("bursary")) {
            return "stipend";
        }

        // Rental Income - check for rent received, rental income, property income
        if (combinedText.contains("rent received")
                || combinedText.contains("rental income")
                || combinedText.contains("property income")
                || combinedText.contains("rent payment received")
                || (combinedText.contains("rent")
                        && (combinedText.contains("received")
                                || combinedText.contains("income")))) {
            return "rentIncome";
        }

        // Tips - check for tips, gratuity, tip income
        if (combinedText.contains("tip")
                || combinedText.contains("gratuity")
                || combinedText.contains("tip income")
                || combinedText.contains("tips received")) {
            return "tips";
        }

        // Default to salary (most common income type) if no specific match
        // CRITICAL: "income" is ONLY a primary category type, NOT a detailed category
        return "salary";
    }

    /**
     * Detects if a transaction is an airline transaction based on merchant name and description
     * Checks for common airline names and airline-related keywords
     *
     * @param combinedTextLower Combined merchant name and description in lowercase
     * @param merchantLower Merchant name in lowercase
     * @param descriptionLower Description in lowercase
     * @return true if airline transaction detected, false otherwise
     */
    private boolean detectAirlineTransaction(
            final String combinedTextLower,
            final String merchantLower,
            final String descriptionLower) {
        // Strong indicators of airline transactions
        final boolean hasPassengerTicket =
                combinedTextLower.contains("passenger ticket")
                        || combinedTextLower.contains("passenger name")
                        || combinedTextLower.contains("ticket number");

        final boolean hasFlightDetails =
                combinedTextLower.contains("date of departure")
                        || combinedTextLower.contains("carrier:")
                        || (combinedTextLower.contains("from:")
                        && combinedTextLower.contains("to:"));

        // Check for common airline names (case-insensitive matching already done via lowercase)
        // DELTA AIR LINES, DELTA AIR, DELTA
        final boolean isDelta =
                combinedTextLower.contains("delta air lines")
                        || combinedTextLower.contains("delta air")
                        || (combinedTextLower.contains("delta")
                        && (combinedTextLower.contains("airline")
                        || combinedTextLower.contains("airlines")
                        || combinedTextLower.contains("flight")
                        || combinedTextLower.contains("ticket")
                        || combinedTextLower.contains("passenger")
                        || combinedTextLower.contains("carrier")
                        || hasPassengerTicket
                        || hasFlightDetails));

        // ALASKA AIRLINES, ALASKA AIR
        final boolean isAlaska =
                combinedTextLower.contains("alaska airlines")
                        || combinedTextLower.contains("alaska air")
                        || (combinedTextLower.contains("alaska")
                        && (combinedTextLower.contains("airline")
                        || combinedTextLower.contains("airlines")
                        || combinedTextLower.contains("flight")
                        || combinedTextLower.contains("ticket")
                        || combinedTextLower.contains("passenger")
                        || combinedTextLower.contains("carrier")
                        || hasPassengerTicket
                        || hasFlightDetails));

        // Other major airlines
        final boolean isUnited =
                combinedTextLower.contains("united airlines")
                        || combinedTextLower.contains("united air")
                        || (combinedTextLower.contains("united")
                        && (combinedTextLower.contains("airline")
                        || combinedTextLower.contains("airlines")
                        || combinedTextLower.contains("flight")
                        || combinedTextLower.contains("ticket")
                        || combinedTextLower.contains("passenger")
                        || combinedTextLower.contains("carrier")
                        || hasPassengerTicket
                        || hasFlightDetails));

        final boolean isAmerican =
                combinedTextLower.contains("american airlines")
                        || combinedTextLower.contains("american air")
                        || (combinedTextLower.contains("american")
                        && (combinedTextLower.contains("airline")
                        || combinedTextLower.contains("airlines")
                        || combinedTextLower.contains("flight")
                        || combinedTextLower.contains("ticket")
                        || combinedTextLower.contains("passenger")
                        || combinedTextLower.contains("carrier")
                        || hasPassengerTicket
                        || hasFlightDetails));

        final boolean isSouthwest =
                combinedTextLower.contains("southwest airlines")
                        || combinedTextLower.contains("southwest air");

        final boolean isJetBlue =
                combinedTextLower.contains("jetblue") || combinedTextLower.contains("jet blue");

        final boolean isSpirit =
                combinedTextLower.contains("spirit airlines")
                        || combinedTextLower.contains("spirit air");

        final boolean isFrontier =
                combinedTextLower.contains("frontier airlines")
                        || combinedTextLower.contains("frontier air");

        // Check for generic airline keywords (flight, ticket, passenger, carrier, departure, etc.)
        // Only match if combined with airline context to avoid false positives
        final boolean hasAirlineKeywords =
                (combinedTextLower.contains("airline") || combinedTextLower.contains("airlines"))
                        && (combinedTextLower.contains("flight")
                        || combinedTextLower.contains("ticket")
                        || combinedTextLower.contains("passenger")
                        || combinedTextLower.contains("carrier")
                        || combinedTextLower.contains("departure")
                        || combinedTextLower.contains("airport")
                        || hasPassengerTicket
                        || hasFlightDetails);

        return isDelta
                || isAlaska
                || isUnited
                || isAmerican
                || isSouthwest
                || isJetBlue
                || isSpirit
                || isFrontier
                || hasAirlineKeywords;
    }

    /**
     * Detects if a transaction is an airport lounge transaction based on merchant name and
     * description Checks for common airport lounge names (Centurion Lounge, Priority Pass, airline
     * lounges, etc.) This is critical to prevent false positives where airport lounges are
     * categorized as utilities or other categories
     *
     * @param combinedTextLower Combined merchant name and description in lowercase
     * @param merchantLower Merchant name in lowercase
     * @param descriptionLower Description in lowercase
     * @return true if airport lounge transaction detected, false otherwise
     */
    private boolean detectAirportLounge(
            final String combinedTextLower,
            final String merchantLower,
            final String descriptionLower) {
        // AXP Centurion Lounge (American Express)
        final boolean isCenturionLounge =
                combinedTextLower.contains("centurion lounge")
                        || combinedTextLower.contains("centurionlounge")
                        || combinedTextLower.contains("axp centurion")
                        || combinedTextLower.contains("axpcenturion")
                        || (combinedTextLower.contains("axp")
                        && combinedTextLower.contains("centurion"))
                        || combinedTextLower.contains("american express centurion")
                        || combinedTextLower.contains("amex centurion");

        // Priority Pass lounges
        final boolean isPriorityPass =
                combinedTextLower.contains("priority pass")
                        || combinedTextLower.contains("prioritypass");

        // Airline-specific lounges
        final boolean isDeltaSkyClub =
                combinedTextLower.contains("delta sky club")
                        || combinedTextLower.contains("deltaskyclub");

        final boolean isUnitedClub =
                combinedTextLower.contains("united club")
                        || combinedTextLower.contains("unitedclub");

        final boolean isAdmiralsClub =
                combinedTextLower.contains("admirals club")
                        || combinedTextLower.contains("admiralsclub")
                        || combinedTextLower.contains("american airlines lounge");

        final boolean isAlaskaLounge =
                combinedTextLower.contains("alaska lounge")
                        || combinedTextLower.contains("alaskalounge")
                        || combinedTextLower.contains("alaska airlines lounge");

        final boolean isJetBlueMint =
                combinedTextLower.contains("jetblue mint")
                        || combinedTextLower.contains("jet blue mint");

        // Other airport lounges
        final boolean isPlazaPremium =
                combinedTextLower.contains("plaza premium lounge")
                        || combinedTextLower.contains("plazapremiumlounge");

        final boolean isEncalm =
                combinedTextLower.contains("encalm lounge")
                        || combinedTextLower.contains("encalmlounge")
                        || combinedTextLower.contains("encalm");

        final boolean isAmericanExpressLounge =
                combinedTextLower.contains("american express lounge")
                        || combinedTextLower.contains("amex lounge");

        // Generic airport lounge keywords (only if combined with airport/travel context)
        final boolean isGenericLounge =
                (combinedTextLower.contains("airport lounge")
                        || combinedTextLower.contains("airportlounge")
                        || (combinedTextLower.contains("lounge")
                        && (combinedTextLower.contains("airport")
                        || combinedTextLower.contains("terminal")
                        || combinedTextLower.contains("gate"))))
                        && !combinedTextLower.contains("hotel lounge")
                        && // Exclude hotel lounges (different category)
                        !combinedTextLower.contains(
                                "restaurant lounge"); // Exclude restaurant lounges

        return isCenturionLounge
                || isPriorityPass
                || isDeltaSkyClub
                || isUnitedClub
                || isAdmiralsClub
                || isAlaskaLounge
                || isJetBlueMint
                || isPlazaPremium
                || isEncalm
                || isAmericanExpressLounge
                || isGenericLounge;
    }

    /**
     * Detects if a transaction is a shopping/retail transaction based on merchant name and
     * description Checks for common shopping retailers and clothing stores This is critical to
     * prevent false positives where shopping retailers are categorized as transportation (e.g.,
     * "lul" in "lululemon" might match transportation keywords)
     *
     * @param combinedTextLower Combined merchant name and description in lowercase
     * @param merchantLower Merchant name in lowercase
     * @param descriptionLower Description in lowercase
     * @return true if shopping transaction detected, false otherwise
     */
    private boolean detectShoppingTransaction(
            final String combinedTextLower,
            final String merchantLower,
            final String descriptionLower) {
        // Clothing and athletic wear retailers
        final boolean isLululemon =
                combinedTextLower.contains("lululemon")
                        || combinedTextLower.contains("lulu lemon")
                        || combinedTextLower.contains("lululemon athletica");

        final boolean isNike =
                combinedTextLower.contains("nike") || combinedTextLower.contains("nikestore");

        final boolean isAdidas = combinedTextLower.contains("adidas");

        final boolean isUnderArmour =
                combinedTextLower.contains("under armour")
                        || combinedTextLower.contains("underarmour");

        final boolean isAthleta = combinedTextLower.contains("athleta");

        final boolean isFabletics = combinedTextLower.contains("fabletics");

        // Department stores and general retailers
        final boolean isNordstrom = combinedTextLower.contains("nordstrom");

        final boolean isMacy = combinedTextLower.contains("macy") || combinedTextLower.contains("macy's");

        final boolean isTarget = combinedTextLower.contains("target");

        final boolean isWalmart = combinedTextLower.contains("walmart");

        final boolean isBestBuy =
                combinedTextLower.contains("best buy") || combinedTextLower.contains("bestbuy");

        final boolean isAmazon = combinedTextLower.contains("amazon");

        // Clothing-specific stores
        final boolean isGap = combinedTextLower.contains("gap");

        final boolean isOldNavy = combinedTextLower.contains("old navy");

        final boolean isBananaRepublic =
                combinedTextLower.contains("banana republic")
                        || combinedTextLower.contains("bananarepublic");

        final boolean isJCPenney =
                combinedTextLower.contains("jcpenney")
                        || combinedTextLower.contains("jc penney")
                        || combinedTextLower.contains("j.c. penney");

        final boolean isKohls =
                combinedTextLower.contains("kohl") || combinedTextLower.contains("kohl's");

        // Beauty and cosmetics
        final boolean isSephora = combinedTextLower.contains("sephora");

        final boolean isUlta = combinedTextLower.contains("ulta");

        // Generic shopping keywords (only if combined with retail context)
        final boolean hasShoppingKeywords =
                (combinedTextLower.contains("clothing")
                        || combinedTextLower.contains("apparel")
                        || combinedTextLower.contains("retail")
                        || combinedTextLower.contains("store")
                        || combinedTextLower.contains("shop"))
                        && !combinedTextLower.contains("gas station")
                        && !combinedTextLower.contains("gas store");

        return isLululemon
                || isNike
                || isAdidas
                || isUnderArmour
                || isAthleta
                || isFabletics
                || isNordstrom
                || isMacy
                || isTarget
                || isWalmart
                || isBestBuy
                || isAmazon
                || isGap
                || isOldNavy
                || isBananaRepublic
                || isJCPenney
                || isKohls
                || isSephora
                || isUlta
                || hasShoppingKeywords;
    }

    /**
     * Detects if a transaction is an entertainment transaction (movie theaters, concerts, etc.)
     * based on merchant name and description Checks for common movie theater chains and
     * entertainment venues This is critical to prevent false positives where movie theaters are
     * categorized as education or other categories
     *
     * @param combinedTextLower Combined merchant name and description in lowercase
     * @param merchantLower Merchant name in lowercase
     * @param descriptionLower Description in lowercase
     * @return true if entertainment transaction detected, false otherwise
     */
    private boolean detectEntertainmentTransaction(
            final String combinedTextLower,
            final String merchantLower,
            final String descriptionLower) {
        // Movie theater chains
        // AMC Theaters - check for "amc" but exclude AMC Networks (TV network)
        // AMC theater locations often have patterns like "AMC 2434 FACTORIA" or "AMC THEATERS"
        // If merchant/description starts with "AMC" or contains "AMC" followed by numbers, it's
        // likely a theater
        final boolean isAMC =
                combinedTextLower.contains("amc")
                        && !combinedTextLower.contains("amc network")
                        && // Exclude AMC Networks (TV network)
                        !combinedTextLower.contains("amc theaters network")
                        && // Exclude network-related
                        (combinedTextLower.contains("theater")
                                || combinedTextLower.contains("theatre")
                                || combinedTextLower.contains("cinema")
                                || combinedTextLower.contains("movie")
                                || combinedTextLower.contains("factor")
                                || // AMC locations often have "FACTORIA" in name
                                merchantLower.startsWith("amc")
                                || // Merchant name starts with "amc" (e.g., "AMC 2434 FACTORIA")
                                descriptionLower.startsWith("amc")
                                || // Description starts with "amc"
                                java.util.regex.Pattern.compile(".*amc\\s+\\d+.*")
                                        .matcher(combinedTextLower)
                                        .find()); // Pattern like "AMC 2434"

        final boolean isRegal =
                combinedTextLower.contains("regal") || combinedTextLower.contains("regal cinemas");

        final boolean isCinemark = combinedTextLower.contains("cinemark");

        final boolean isCineplex = combinedTextLower.contains("cineplex");

        final boolean isMarcus =
                combinedTextLower.contains("marcus theaters")
                        || combinedTextLower.contains("marcus cinema");

        final boolean isAlamo = combinedTextLower.contains("alamo drafthouse");

        final boolean isLandmark = combinedTextLower.contains("landmark theaters");

        final boolean isShowcase = combinedTextLower.contains("showcase cinemas");

        // Generic movie theater keywords
        final boolean isMovieTheater =
                (combinedTextLower.contains("movie theater")
                        || combinedTextLower.contains("movie theatre")
                        || combinedTextLower.contains("cinema")
                        || combinedTextLower.contains("theater")
                        || combinedTextLower.contains("theatre"))
                        && !combinedTextLower.contains("home theater")
                        && // Exclude home theater equipment
                        !combinedTextLower.contains("theater equipment");

        // Other entertainment venues
        final boolean isConcertVenue =
                combinedTextLower.contains("concert")
                        || combinedTextLower.contains("live music")
                        || combinedTextLower.contains("music venue");

        final boolean isSportsVenue =
                (combinedTextLower.contains("stadium")
                        || combinedTextLower.contains("arena")
                        || combinedTextLower.contains("ballpark"))
                        && !combinedTextLower.contains("home")
                        && // Exclude home stadium equipment
                        !combinedTextLower.contains("equipment");

        final boolean isThemePark =
                combinedTextLower.contains("theme park")
                        || combinedTextLower.contains("amusement park")
                        || combinedTextLower.contains("disney")
                        || combinedTextLower.contains("universal studios")
                        || combinedTextLower.contains("six flags");

        return isAMC
                || isRegal
                || isCinemark
                || isCineplex
                || isMarcus
                || isAlamo
                || isLandmark
                || isShowcase
                || isMovieTheater
                || isConcertVenue
                || isSportsVenue
                || isThemePark;
    }

    /**
     * Detects if a transaction is from a holdings/company entity based on merchant name and
     * description Checks for common holdings company patterns (e.g., "TRG HOLDINGS LIMITED") This
     * is critical to prevent false positives where holdings companies are categorized as dining or
     * other categories Holdings companies are business entities and should be categorized as
     * "other", not "dining"
     *
     * @param combinedTextLower Combined merchant name and description in lowercase
     * @param merchantLower Merchant name in lowercase
     * @param descriptionLower Description in lowercase
     * @return true if holdings company detected, false otherwise
     */
    private boolean detectHoldingsCompany(
            final String combinedTextLower,
            final String merchantLower,
            final String descriptionLower) {
        // Check for "holdings" keyword (most common indicator)
        final boolean hasHoldings =
                combinedTextLower.contains("holdings")
                        || combinedTextLower.contains("holdings limited")
                        || combinedTextLower.contains("holdingslimited");

        // Check for specific holdings companies
        final boolean isTRGHoldings =
                combinedTextLower.contains("trg holdings")
                        || combinedTextLower.contains("trgholdings")
                        || (combinedTextLower.contains("trg")
                        && combinedTextLower.contains("holdings"));

        // Exclude healthcare-related holdings (they should be healthcare, not other)
        final boolean isHealthcareRelated =
                combinedTextLower.contains("health")
                        || combinedTextLower.contains("medical")
                        || combinedTextLower.contains("clinic")
                        || combinedTextLower.contains("hospital")
                        || combinedTextLower.contains("healthcare");

        // Holdings companies are business entities - default to "other"
        // Only exclude if it's clearly healthcare-related
        return (hasHoldings || isTRGHoldings) && !isHealthcareRelated;
    }

    /**
     * Applies a user override to the category
     *
     * @param originalMapping Original category mapping from Plaid
     * @param overridePrimary User's override primary category
     * @param overrideDetailed User's override detailed category
     * @return New CategoryMapping with override flag set to true
     */
    public CategoryMapping applyOverride(
            final CategoryMapping originalMapping,
            final String overridePrimary,
            final String overrideDetailed) {
        final String primary =
                overridePrimary != null && !overridePrimary.isEmpty()
                        ? overridePrimary
                        : originalMapping.getPrimary();
        final String detailed =
                overrideDetailed != null && !overrideDetailed.isEmpty()
                        ? overrideDetailed
                        : originalMapping.getDetailed();

        return new CategoryMapping(primary, detailed, true);
    }
}
