package com.budgetbuddy.service;

import java.util.*;

/**
 * Generates realistic test data for bank and credit card statements
 * Simulates various real-world scenarios, edge cases, and boundary conditions
 */
public class RealWorldStatementTestDataGenerator {
    
    // Real-world merchant names including payment services
    private static final String[] MERCHANTS = {
        "AMAZON.COM", "STARBUCKS", "WALMART", "TARGET", "COSTCO", "WHOLE FOODS",
        "SHELL", "EXXON", "BP", "CHEVRON", "UBER", "LYFT", "DOORDASH", "GRUBHUB",
        "NETFLIX", "SPOTIFY", "APPLE", "GOOGLE", "MICROSOFT", "AT&T", "VERIZON",
        "CHASE", "BANK OF AMERICA", "WELLS FARGO", "CITIBANK", "AMERICAN EXPRESS",
        "US BANK", "DISCOVER", "SYNCHRONY BANK", "CAPITAL ONE", "VISA", "MASTERCARD",
        "APPLE CARD", "AMAZON PAY", "GOOGLE PAY", "PAYPAL", "VENMO", "PAYPAL MASTERCARD",
        "HOME DEPOT", "LOWE'S", "BEST BUY", "MACY'S", "NORDSTROM", "GAP",
        "MCDONALD'S", "SUBWAY", "TACO BELL", "PIZZA HUT", "DOMINO'S",
        "HILTON", "MARRIOTT", "HYATT", "DELTA", "UNITED", "AMERICAN AIRLINES",
        "PETCO", "PETSMART", "CVS", "WALGREENS", "RITE AID"
    };
    
    // Real-world locations
    private static final String[] LOCATIONS = {
        "SEATTLE WA", "NEW YORK NY", "LOS ANGELES CA", "CHICAGO IL", "HOUSTON TX",
        "PHOENIX AZ", "PHILADELPHIA PA", "SAN ANTONIO TX", "SAN DIEGO CA", "DALLAS TX",
        "SAN JOSE CA", "AUSTIN TX", "JACKSONVILLE FL", "SAN FRANCISCO CA", "INDIANAPOLIS IN",
        "COLUMBUS OH", "FORT WORTH TX", "CHARLOTTE NC", "DETROIT MI", "EL PASO TX"
    };
    
    // Transaction types
    private static final String[] TRANSACTION_TYPES = {
        "PURCHASE", "PAYMENT", "DEPOSIT", "WITHDRAWAL", "FEE", "INTEREST",
        "REFUND", "CREDIT", "DEBIT", "TRANSFER", "AUTOMATIC PAYMENT"
    };
    
    /**
     * Generate a realistic transaction line in Pattern 1 format
     */
    public static String generatePattern1Transaction(int month, int day, int year, String merchant, double amount) {
        String date = String.format("%02d/%02d", month, day);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        if (amount < 0) {
            amountStr = "-" + amountStr;
        }
        return String.format("%s     %s %s", date, merchant, amountStr);
    }
    
    /**
     * Generate a realistic transaction line with CR/DR indicators
     */
    public static String generateTransactionWithCRDR(int month, int day, String merchant, double amount, boolean isCredit) {
        String date = String.format("%02d/%02d", month, day);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        String indicator = isCredit ? "CR" : "DR";
        return String.format("%s     %s %s %s", date, merchant, amountStr, indicator);
    }
    
    /**
     * Generate a realistic transaction line with parentheses (negative)
     */
    public static String generateTransactionWithParentheses(int month, int day, String merchant, double amount) {
        String date = String.format("%02d/%02d", month, day);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("($%.2f)", absAmount);
        return String.format("%s     %s %s", date, merchant, amountStr);
    }
    
    /**
     * Generate a realistic transaction line in Pattern 4 format (with card number and location)
     */
    public static String generatePattern4Transaction(String cardLast4, int month1, int day1, int month2, int day2, 
                                                     String transactionId, String merchant, String location, double amount) {
        String date1 = String.format("%02d/%02d", month1, day1);
        String date2 = String.format("%02d/%02d", month2, day2);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("%.2f", absAmount);
        return String.format("%s %s %s %s %s %s %s", 
            cardLast4, date1, date2, transactionId, merchant, location, amountStr);
    }
    
    /**
     * Generate a realistic transaction line in Pattern 5 format (with two dates, merchant, location)
     */
    public static String generatePattern5Transaction(int month1, int day1, int month2, int day2, 
                                                     String merchant, String location, double amount) {
        String date1 = String.format("%02d/%02d", month1, day1);
        String date2 = String.format("%02d/%02d", month2, day2);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s %s %s %s %s", date1, date2, merchant, location, amountStr);
    }
    
    /**
     * Generate a realistic American Express multi-line transaction
     */
    public static List<String> generateAmexTransaction(int month, int day, int year, String userName, 
                                                        String description, String merchant, double amount) {
        List<String> lines = new ArrayList<>();
        String date = String.format("%02d/%02d/%02d", month, day, year % 100);
        lines.add(String.format("%s* %s %s", date, userName, description));
        lines.add(merchant);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("-$%.2f", absAmount);
        lines.add(amountStr + " ⧫");
        return lines;
    }
    
    /**
     * Generate edge case: transaction with extra whitespace
     */
    public static String generateTransactionWithExtraWhitespace(int month, int day, String merchant, double amount) {
        String date = String.format("%02d/%02d", month, day);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s     %s     %s", date, merchant, amountStr);
    }
    
    /**
     * Generate edge case: transaction with tabs
     */
    public static String generateTransactionWithTabs(int month, int day, String merchant, double amount) {
        String date = String.format("%02d/%02d", month, day);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s\t%s\t%s", date, merchant, amountStr);
    }
    
    /**
     * Generate edge case: transaction with very long merchant name
     */
    public static String generateTransactionWithLongMerchant(int month, int day, double amount) {
        String date = String.format("%02d/%02d", month, day);
        String merchant = "VERY LONG MERCHANT NAME " + "X".repeat(200);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s     %s %s", date, merchant, amountStr);
    }
    
    /**
     * Generate edge case: transaction with very short merchant name
     */
    public static String generateTransactionWithShortMerchant(int month, int day, double amount) {
        String date = String.format("%02d/%02d", month, day);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s     AB %s", date, amountStr);
    }
    
    /**
     * Generate edge case: transaction with zero amount
     */
    public static String generateTransactionWithZeroAmount(int month, int day, String merchant) {
        String date = String.format("%02d/%02d", month, day);
        return String.format("%s     %s $0.00", date, merchant);
    }
    
    /**
     * Generate edge case: transaction with very large amount
     */
    public static String generateTransactionWithLargeAmount(int month, int day, String merchant) {
        String date = String.format("%02d/%02d", month, day);
        return String.format("%s     %s $999,999.99", date, merchant);
    }
    
    /**
     * Generate edge case: transaction with very small amount
     */
    public static String generateTransactionWithSmallAmount(int month, int day, String merchant) {
        String date = String.format("%02d/%02d", month, day);
        return String.format("%s     %s $0.01", date, merchant);
    }
    
    /**
     * Generate edge case: transaction with date far in the future
     */
    public static String generateTransactionWithFutureDate(String merchant, double amount) {
        String date = "12/31/2050";
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s     %s %s", date, merchant, amountStr);
    }
    
    /**
     * Generate edge case: transaction with date far in the past
     */
    public static String generateTransactionWithPastDate(String merchant, double amount) {
        String date = "01/01/2000";
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s     %s %s", date, merchant, amountStr);
    }
    
    /**
     * Generate edge case: transaction without year in date
     */
    public static String generateTransactionWithoutYear(int month, int day, String merchant, double amount) {
        String date = String.format("%02d/%02d", month, day);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s     %s %s", date, merchant, amountStr);
    }
    
    /**
     * Generate edge case: transaction with amount without currency symbol
     */
    public static String generateTransactionWithoutCurrency(int month, int day, String merchant, double amount) {
        String date = String.format("%02d/%02d", month, day);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("%.2f", absAmount);
        return String.format("%s     %s %s", date, merchant, amountStr);
    }
    
    /**
     * Generate edge case: transaction with amount using comma as thousands separator
     */
    public static String generateTransactionWithCommaSeparator(int month, int day, String merchant, double amount) {
        String date = String.format("%02d/%02d", month, day);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%,.2f", absAmount);
        return String.format("%s     %s %s", date, merchant, amountStr);
    }
    
    /**
     * Generate informational line (should be skipped)
     */
    public static String generateInformationalLine() {
        return "Pay Over Time 12/30/2022 19.49% (v) $0.00 $0.00";
    }
    
    /**
     * Generate payment due date line (should be skipped)
     */
    public static String generatePaymentDueDateLine() {
        return "12/27/25. This date may not be the same date your bank will debit your";
    }
    
    /**
     * Generate a complete realistic statement with various transaction types
     */
    public static List<String> generateRealisticStatement(int year, int month) {
        List<String> lines = new ArrayList<>();
        Random random = new Random(42); // Fixed seed for reproducibility
        
        // Generate 20-30 transactions
        int numTransactions = 20 + random.nextInt(11);
        
        for (int i = 0; i < numTransactions; i++) {
            int day = 1 + random.nextInt(28); // Avoid day 29-31 for simplicity
            String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            double amount = 5.00 + random.nextDouble() * 500.00; // $5 to $505
            
            // Randomly choose transaction format
            int format = random.nextInt(5);
            switch (format) {
                case 0:
                    lines.add(generatePattern1Transaction(month, day, year, merchant, amount));
                    break;
                case 1:
                    lines.add(generateTransactionWithCRDR(month, day, merchant, amount, random.nextBoolean()));
                    break;
                case 2:
                    if (amount < 0) {
                        double absAmount = amount < 0 ? -amount : amount;
                        lines.add(generateTransactionWithParentheses(month, day, merchant, absAmount));
                    } else {
                        lines.add(generatePattern1Transaction(month, day, year, merchant, amount));
                    }
                    break;
                case 3:
                    String location = LOCATIONS[random.nextInt(LOCATIONS.length)];
                    lines.add(generatePattern5Transaction(month, day, month, day, merchant, location, amount));
                    break;
                case 4:
                    String cardLast4 = String.format("%04d", random.nextInt(10000));
                    String transactionId = generateTransactionId();
                    String location2 = LOCATIONS[random.nextInt(LOCATIONS.length)];
                    lines.add(generatePattern4Transaction(cardLast4, month, day, month, day, 
                                                          transactionId, merchant, location2, amount));
                    break;
            }
        }
        
        // Add some edge cases
        lines.add(generateTransactionWithZeroAmount(month, 15, "TEST MERCHANT"));
        lines.add(generateTransactionWithLargeAmount(month, 16, "LARGE PURCHASE"));
        lines.add(generateTransactionWithSmallAmount(month, 17, "SMALL PURCHASE"));
        
        // Add some informational lines (should be skipped)
        lines.add(generateInformationalLine());
        lines.add(generatePaymentDueDateLine());
        
        return lines;
    }
    
    /**
     * Generate a random transaction ID
     */
    private static String generateTransactionId() {
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < 12; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
    
    /**
     * Get a random merchant name
     */
    public static String getRandomMerchant() {
        Random random = new Random();
        return MERCHANTS[random.nextInt(MERCHANTS.length)];
    }
    
    /**
     * Get a random location
     */
    public static String getRandomLocation() {
        Random random = new Random();
        return LOCATIONS[random.nextInt(LOCATIONS.length)];
    }
    
    /**
     * Generate Citibank statement transaction (Pattern: Date Description Amount)
     */
    public static String generateCitibankTransaction(int month, int day, String description, double amount) {
        String date = String.format("%02d/%02d", month, day);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        if (amount < 0) {
            amountStr = "-" + amountStr;
        }
        return String.format("%s %s %s", date, description, amountStr);
    }
    
    /**
     * Generate US Bank statement transaction (Pattern: Date PostDate Description Amount)
     */
    public static String generateUSBankTransaction(int month1, int day1, int month2, int day2, String description, double amount) {
        String date1 = String.format("%02d/%02d", month1, day1);
        String date2 = String.format("%02d/%02d", month2, day2);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s %s %s %s", date1, date2, description, amountStr);
    }
    
    /**
     * Generate Discover statement transaction (Pattern: Date Description Amount CR/DR)
     */
    public static String generateDiscoverTransaction(int month, int day, String description, double amount, boolean isCredit) {
        String date = String.format("%02d/%02d/%02d", month, day, 24);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        String indicator = isCredit ? "CR" : "DR";
        return String.format("%s %s %s %s", date, description, amountStr, indicator);
    }
    
    /**
     * Generate Synchrony Bank statement transaction (Pattern: Date Description Amount)
     */
    public static String generateSynchronyTransaction(int month, int day, String description, double amount) {
        String date = String.format("%02d/%02d/%04d", month, day, 2024);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        if (amount < 0) {
            amountStr = "(" + amountStr + ")";
        }
        return String.format("%s %s %s", date, description, amountStr);
    }
    
    /**
     * Generate Capital One statement transaction (Pattern: Date Description Location Amount)
     */
    public static String generateCapitalOneTransaction(int month, int day, String description, String location, double amount) {
        String date = String.format("%02d/%02d", month, day);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s %s %s %s", date, description, location, amountStr);
    }
    
    /**
     * Generate Apple Card statement transaction (Pattern: Date Description Amount)
     */
    public static String generateAppleCardTransaction(int month, int day, String description, double amount) {
        String date = String.format("%02d/%02d", month, day);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("-$%.2f", absAmount); // Apple Card shows negative for purchases
        return String.format("%s %s %s", date, description, amountStr);
    }
    
    /**
     * Generate PayPal statement transaction (Pattern: Date Description Amount)
     */
    public static String generatePayPalTransaction(int month, int day, int year, String description, double amount) {
        String date = String.format("%02d/%02d/%04d", month, day, year);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("%.2f", absAmount);
        String sign = amount < 0 ? "-" : "+";
        return String.format("%s %s %s%s", date, description, sign, amountStr);
    }
    
    /**
     * Generate Venmo statement transaction (Pattern: Date Description Amount)
     */
    public static String generateVenmoTransaction(int month, int day, String description, double amount) {
        String date = String.format("%02d/%02d", month, day);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        String sign = amount < 0 ? "-" : "+";
        return String.format("%s %s %s%s", date, description, sign, amountStr);
    }
    
    /**
     * Generate Amazon Pay statement transaction (Pattern: Date Description Amount)
     */
    public static String generateAmazonPayTransaction(int month, int day, String description, double amount) {
        String date = String.format("%02d/%02d/%04d", month, day, 2024);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s Amazon Pay - %s %s", date, description, amountStr);
    }
    
    /**
     * Generate Google Pay statement transaction (Pattern: Date Description Amount)
     */
    public static String generateGooglePayTransaction(int month, int day, String description, double amount) {
        String date = String.format("%02d/%02d", month, day);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s GOOGLE PAY *%s %s", date, description, amountStr);
    }
    
    /**
     * Generate PayPal Mastercard statement transaction (Pattern: Date Description Amount)
     */
    public static String generatePayPalMastercardTransaction(int month, int day, String description, double amount) {
        String date = String.format("%02d/%02d/%02d", month, day, 24);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s %s %s", date, description, amountStr);
    }
    
    /**
     * Generate Amex Green Card statement transaction (multi-line format)
     */
    public static List<String> generateAmexGreenTransaction(int month, int day, int year, String userName, 
                                                             String description, String merchant, double amount) {
        List<String> lines = new ArrayList<>();
        String date = String.format("%02d/%02d/%02d", month, day, year % 100);
        lines.add(String.format("%s* %s %s", date, userName, description));
        lines.add(merchant);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("-$%.2f", absAmount);
        lines.add(amountStr + " ⧫");
        lines.add(""); // Empty line separator
        return lines;
    }
    
    /**
     * Generate Amex Goal Card statement transaction (multi-line format)
     */
    public static List<String> generateAmexGoalTransaction(int month, int day, int year, String userName, 
                                                            String description, String merchant, double amount) {
        List<String> lines = new ArrayList<>();
        String date = String.format("%02d/%02d/%02d", month, day, year % 100);
        lines.add(String.format("%s* %s %s", date, userName, description));
        lines.add(merchant);
        double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("-$%.2f", absAmount);
        lines.add(amountStr + " ⧫");
        return lines;
    }
    
    /**
     * Generate a complete Citibank statement
     */
    public static List<String> generateCitibankStatement(int year, int month) {
        List<String> lines = new ArrayList<>();
        Random random = new Random(42);
        
        lines.add("CITIBANK CREDIT CARD STATEMENT");
        lines.add("ACCOUNT ENDING IN 1234");
        lines.add("");
        
        for (int i = 0; i < 15; i++) {
            int day = 1 + random.nextInt(28);
            String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            double amount = 10.00 + random.nextDouble() * 300.00;
            lines.add(generateCitibankTransaction(month, day, merchant, amount));
        }
        
        return lines;
    }
    
    /**
     * Generate a complete US Bank statement
     */
    public static List<String> generateUSBankStatement(int year, int month) {
        List<String> lines = new ArrayList<>();
        Random random = new Random(43);
        
        lines.add("U.S. BANK CREDIT CARD STATEMENT");
        lines.add("");
        
        for (int i = 0; i < 12; i++) {
            int day = 1 + random.nextInt(28);
            String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            double amount = 15.00 + random.nextDouble() * 400.00;
            lines.add(generateUSBankTransaction(month, day, month, day, merchant, amount));
        }
        
        return lines;
    }
    
    /**
     * Generate a complete Discover statement
     */
    public static List<String> generateDiscoverStatement(int year, int month) {
        List<String> lines = new ArrayList<>();
        Random random = new Random(44);
        
        lines.add("DISCOVER CARD STATEMENT");
        lines.add("");
        
        for (int i = 0; i < 18; i++) {
            int day = 1 + random.nextInt(28);
            String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            double amount = 20.00 + random.nextDouble() * 500.00;
            boolean isCredit = random.nextBoolean();
            lines.add(generateDiscoverTransaction(month, day, merchant, isCredit ? amount : -amount, isCredit));
        }
        
        return lines;
    }
    
    /**
     * Generate a complete Synchrony Bank statement
     */
    public static List<String> generateSynchronyStatement(int year, int month) {
        List<String> lines = new ArrayList<>();
        Random random = new Random(45);
        
        lines.add("SYNCHRONY BANK CREDIT CARD");
        lines.add("");
        
        for (int i = 0; i < 10; i++) {
            int day = 1 + random.nextInt(28);
            String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            double amount = 25.00 + random.nextDouble() * 350.00;
            lines.add(generateSynchronyTransaction(month, day, merchant, amount));
        }
        
        return lines;
    }
    
    /**
     * Generate a complete Capital One statement
     */
    public static List<String> generateCapitalOneStatement(int year, int month) {
        List<String> lines = new ArrayList<>();
        Random random = new Random(46);
        
        lines.add("CAPITAL ONE CREDIT CARD STATEMENT");
        lines.add("");
        
        for (int i = 0; i < 20; i++) {
            int day = 1 + random.nextInt(28);
            String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            String location = LOCATIONS[random.nextInt(LOCATIONS.length)];
            double amount = 12.00 + random.nextDouble() * 450.00;
            lines.add(generateCapitalOneTransaction(month, day, merchant, location, amount));
        }
        
        return lines;
    }
    
    /**
     * Generate a complete Apple Card statement
     */
    public static List<String> generateAppleCardStatement(int year, int month) {
        List<String> lines = new ArrayList<>();
        Random random = new Random(47);
        
        lines.add("Apple Card Statement");
        lines.add("");
        
        for (int i = 0; i < 25; i++) {
            int day = 1 + random.nextInt(28);
            String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            double amount = 8.00 + random.nextDouble() * 250.00;
            lines.add(generateAppleCardTransaction(month, day, merchant, amount));
        }
        
        return lines;
    }
    
    /**
     * Generate a complete PayPal statement
     */
    public static List<String> generatePayPalStatement(int year, int month) {
        List<String> lines = new ArrayList<>();
        Random random = new Random(48);
        
        lines.add("PayPal Account Statement");
        lines.add("");
        
        for (int i = 0; i < 30; i++) {
            int day = 1 + random.nextInt(28);
            String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            double amount = 5.00 + random.nextDouble() * 200.00;
            boolean isCredit = random.nextBoolean();
            lines.add(generatePayPalTransaction(month, day, year, merchant, isCredit ? amount : -amount));
        }
        
        return lines;
    }
    
    /**
     * Generate a complete Venmo statement
     */
    public static List<String> generateVenmoStatement(int year, int month) {
        List<String> lines = new ArrayList<>();
        Random random = new Random(49);
        
        lines.add("Venmo Transaction History");
        lines.add("");
        
        for (int i = 0; i < 35; i++) {
            int day = 1 + random.nextInt(28);
            String merchant = "VENMO PAYMENT TO " + MERCHANTS[random.nextInt(MERCHANTS.length)];
            double amount = 10.00 + random.nextDouble() * 150.00;
            boolean isCredit = random.nextBoolean();
            lines.add(generateVenmoTransaction(month, day, merchant, isCredit ? amount : -amount));
        }
        
        return lines;
    }
    
    /**
     * Generate a complete PayPal Mastercard statement
     */
    public static List<String> generatePayPalMastercardStatement(int year, int month) {
        List<String> lines = new ArrayList<>();
        Random random = new Random(50);
        
        lines.add("PayPal Mastercard Statement");
        lines.add("");
        
        for (int i = 0; i < 22; i++) {
            int day = 1 + random.nextInt(28);
            String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            double amount = 15.00 + random.nextDouble() * 300.00;
            lines.add(generatePayPalMastercardTransaction(month, day, merchant, amount));
        }
        
        return lines;
    }
    
    /**
     * Generate a complete Amex Green Card statement
     */
    public static List<String> generateAmexGreenStatement(int year, int month) {
        List<String> lines = new ArrayList<>();
        Random random = new Random(51);
        
        lines.add("AMERICAN EXPRESS GREEN CARD STATEMENT");
        lines.add("");
        
        for (int i = 0; i < 15; i++) {
            int day = 1 + random.nextInt(28);
            String userName = "JOHN DOE";
            String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            String description = "PURCHASE AT " + merchant;
            double amount = 30.00 + random.nextDouble() * 600.00;
            lines.addAll(generateAmexGreenTransaction(month, day, year, userName, description, merchant, amount));
        }
        
        return lines;
    }
    
    /**
     * Generate a complete Amex Goal Card statement
     */
    public static List<String> generateAmexGoalStatement(int year, int month) {
        List<String> lines = new ArrayList<>();
        Random random = new Random(52);
        
        lines.add("AMERICAN EXPRESS GOAL CARD STATEMENT");
        lines.add("");
        
        for (int i = 0; i < 12; i++) {
            int day = 1 + random.nextInt(28);
            String userName = "JANE SMITH";
            String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            String description = "PURCHASE AT " + merchant;
            double amount = 25.00 + random.nextDouble() * 550.00;
            lines.addAll(generateAmexGoalTransaction(month, day, year, userName, description, merchant, amount));
        }
        
        return lines;
    }
}

