package com.budgetbuddy.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates realistic test data for bank and credit card statements Simulates various real-world
 * scenarios, edge cases, and boundary conditions
 */
// `new Random(seed)` is intentional — seeded for reproducible test data; the
// generator scope is one method per call, so it's fine to reuse-once.
@SuppressFBWarnings(
        value = "DMI_RANDOM_USED_ONLY_ONCE",
        justification = "seeded Random per generator call for reproducible test data")
public final class RealWorldStatementTestDataGenerator {

    // Real-world merchant names including payment services
    private static final String[] MERCHANTS = {
        "AMAZON.COM",
        "STARBUCKS",
        "WALMART",
        "TARGET",
        "COSTCO",
        "WHOLE FOODS",
        "SHELL",
        "EXXON",
        "BP",
        "CHEVRON",
        "UBER",
        "LYFT",
        "DOORDASH",
        "GRUBHUB",
        "NETFLIX",
        "SPOTIFY",
        "APPLE",
        "GOOGLE",
        "MICROSOFT",
        "AT&T",
        "VERIZON",
        "CHASE",
        "BANK OF AMERICA",
        "WELLS FARGO",
        "CITIBANK",
        "AMERICAN EXPRESS",
        "US BANK",
        "DISCOVER",
        "SYNCHRONY BANK",
        "CAPITAL ONE",
        "VISA",
        "MASTERCARD",
        "APPLE CARD",
        "AMAZON PAY",
        "GOOGLE PAY",
        "PAYPAL",
        "VENMO",
        "PAYPAL MASTERCARD",
        "HOME DEPOT",
        "LOWE'S",
        "BEST BUY",
        "MACY'S",
        "NORDSTROM",
        "GAP",
        "MCDONALD'S",
        "SUBWAY",
        "TACO BELL",
        "PIZZA HUT",
        "DOMINO'S",
        "HILTON",
        "MARRIOTT",
        "HYATT",
        "DELTA",
        "UNITED",
        "AMERICAN AIRLINES",
        "PETCO",
        "PETSMART",
        "CVS",
        "WALGREENS",
        "RITE AID"
    };

    // Real-world locations
    private static final String[] LOCATIONS = {
        "SEATTLE WA", "NEW YORK NY", "LOS ANGELES CA", "CHICAGO IL", "HOUSTON TX",
        "PHOENIX AZ", "PHILADELPHIA PA", "SAN ANTONIO TX", "SAN DIEGO CA", "DALLAS TX",
        "SAN JOSE CA", "AUSTIN TX", "JACKSONVILLE FL", "SAN FRANCISCO CA", "INDIANAPOLIS IN",
        "COLUMBUS OH", "FORT WORTH TX", "CHARLOTTE NC", "DETROIT MI", "EL PASO TX"
    };

    /** Generate a realistic transaction line in Pattern 1 format */
    public static String generatePattern1Transaction(
            final int month, final int day, final int year, final String merchant, final double amount) {
        final String date = String.format("%02d/%02d", month, day);
        final double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        if (amount < 0) {
            amountStr = "-" + amountStr;
        }
        return String.format("%s     %s %s", date, merchant, amountStr);
    }

    /** Generate a realistic transaction line with CR/DR indicators */
    public static String generateTransactionWithCRDR(
            final int month, final int day, final String merchant, final double amount, final boolean isCredit) {
        final String date = String.format("%02d/%02d", month, day);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("$%.2f", absAmount);
        final String indicator = isCredit ? "CR" : "DR";
        return String.format("%s     %s %s %s", date, merchant, amountStr, indicator);
    }

    /** Generate a realistic transaction line with parentheses (negative) */
    public static String generateTransactionWithParentheses(
            final int month, final int day, final String merchant, final double amount) {
        final String date = String.format("%02d/%02d", month, day);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("($%.2f)", absAmount);
        return String.format("%s     %s %s", date, merchant, amountStr);
    }

    /** Generate a realistic transaction line in Pattern 4 format (with card number and location) */
    public static String generatePattern4Transaction(
            final String cardLast4,
            final int month1,
            final int day1,
            final int month2,
            final int day2,
            final String transactionId,
            final String merchant,
            final String location,
            final double amount) {
        final String date1 = String.format("%02d/%02d", month1, day1);
        final String date2 = String.format("%02d/%02d", month2, day2);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("%.2f", absAmount);
        return String.format(
                "%s %s %s %s %s %s %s",
                cardLast4, date1, date2, transactionId, merchant, location, amountStr);
    }

    /**
     * Generate a realistic transaction line in Pattern 5 format (with two dates, merchant,
     * location)
     */
    public static String generatePattern5Transaction(
            final int month1,
            final int day1,
            final int month2,
            final int day2,
            final String merchant,
            final String location,
            final double amount) {
        final String date1 = String.format("%02d/%02d", month1, day1);
        final String date2 = String.format("%02d/%02d", month2, day2);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s %s %s %s %s", date1, date2, merchant, location, amountStr);
    }

    /** Generate a realistic American Express multi-line transaction */
    public static List<String> generateAmexTransaction(
            final int month,
            final int day,
            final int year,
            final String userName,
            final String description,
            final String merchant,
            final double amount) {
        final List<String> lines = new ArrayList<>();
        final String date = String.format("%02d/%02d/%02d", month, day, year % 100);
        lines.add(String.format("%s* %s %s", date, userName, description));
        lines.add(merchant);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("-$%.2f", absAmount);
        lines.add(amountStr + " ⧫");
        return lines;
    }

    /** Generate edge case: transaction with extra whitespace */
    public static String generateTransactionWithExtraWhitespace(
            final int month, final int day, final String merchant, final double amount) {
        final String date = String.format("%02d/%02d", month, day);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s     %s     %s", date, merchant, amountStr);
    }

    /** Generate edge case: transaction with tabs */
    public static String generateTransactionWithTabs(
            final int month, final int day, final String merchant, final double amount) {
        final String date = String.format("%02d/%02d", month, day);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s\t%s\t%s", date, merchant, amountStr);
    }

    /** Generate edge case: transaction with very long merchant name */
    public static String generateTransactionWithLongMerchant(final int month, final int day, final double amount) {
        final String date = String.format("%02d/%02d", month, day);
        final String merchant = "VERY LONG MERCHANT NAME " + "X".repeat(200);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s     %s %s", date, merchant, amountStr);
    }

    /** Generate edge case: transaction with very short merchant name */
    public static String generateTransactionWithShortMerchant(final int month, final int day, final double amount) {
        final String date = String.format("%02d/%02d", month, day);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s     AB %s", date, amountStr);
    }

    /** Generate edge case: transaction with zero amount */
    public static String generateTransactionWithZeroAmount(final int month, final int day, final String merchant) {
        final String date = String.format("%02d/%02d", month, day);
        return String.format("%s     %s $0.00", date, merchant);
    }

    /** Generate edge case: transaction with very large amount */
    public static String generateTransactionWithLargeAmount(final int month, final int day, final String merchant) {
        final String date = String.format("%02d/%02d", month, day);
        return String.format("%s     %s $999,999.99", date, merchant);
    }

    /** Generate edge case: transaction with very small amount */
    public static String generateTransactionWithSmallAmount(final int month, final int day, final String merchant) {
        final String date = String.format("%02d/%02d", month, day);
        return String.format("%s     %s $0.01", date, merchant);
    }

    /** Generate edge case: transaction with date far in the future */
    public static String generateTransactionWithFutureDate(final String merchant, final double amount) {
        final String date = "12/31/2050";
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s     %s %s", date, merchant, amountStr);
    }

    /** Generate edge case: transaction with date far in the past */
    public static String generateTransactionWithPastDate(final String merchant, final double amount) {
        final String date = "01/01/2000";
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s     %s %s", date, merchant, amountStr);
    }

    /** Generate edge case: transaction without year in date */
    public static String generateTransactionWithoutYear(
            final int month, final int day, final String merchant, final double amount) {
        final String date = String.format("%02d/%02d", month, day);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s     %s %s", date, merchant, amountStr);
    }

    /** Generate edge case: transaction with amount without currency symbol */
    public static String generateTransactionWithoutCurrency(
            final int month, final int day, final String merchant, final double amount) {
        final String date = String.format("%02d/%02d", month, day);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("%.2f", absAmount);
        return String.format("%s     %s %s", date, merchant, amountStr);
    }

    /** Generate edge case: transaction with amount using comma as thousands separator */
    public static String generateTransactionWithCommaSeparator(
            final int month, final int day, final String merchant, final double amount) {
        final String date = String.format("%02d/%02d", month, day);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("$%,.2f", absAmount);
        return String.format("%s     %s %s", date, merchant, amountStr);
    }

    /** Generate informational line (should be skipped) */
    public static String generateInformationalLine() {
        return "Pay Over Time 12/30/2022 19.49% (v) $0.00 $0.00";
    }

    /** Generate payment due date line (should be skipped) */
    public static String generatePaymentDueDateLine() {
        return "12/27/25. This date may not be the same date your bank will debit your";
    }

    /** Generate a complete realistic statement with various transaction types */
    public static List<String> generateRealisticStatement(final int year, final int month) {
        final List<String> lines = new ArrayList<>();
        final Random random = new Random(42); // Fixed seed for reproducibility

        // Generate 20-30 transactions
        final int numTransactions = 20 + random.nextInt(11);

        for (int i = 0; i < numTransactions; i++) {
            final int day = 1 + random.nextInt(28); // Avoid day 29-31 for simplicity
            final String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            final double amount = 5.00 + random.nextDouble() * 500.00; // $5 to $505

            // Randomly choose transaction format
            final int format = random.nextInt(5);
            switch (format) {
                case 0:
                    lines.add(generatePattern1Transaction(month, day, year, merchant, amount));
                    break;
                case 1:
                    lines.add(
                            generateTransactionWithCRDR(
                                    month, day, merchant, amount, random.nextBoolean()));
                    break;
                case 2:
                    if (amount < 0) {
                        final double absAmount = amount < 0 ? -amount : amount;
                        lines.add(
                                generateTransactionWithParentheses(
                                        month, day, merchant, absAmount));
                    } else {
                        lines.add(generatePattern1Transaction(month, day, year, merchant, amount));
                    }
                    break;
                case 3:
                    final String location = LOCATIONS[random.nextInt(LOCATIONS.length)];
                    lines.add(
                            generatePattern5Transaction(
                                    month, day, month, day, merchant, location, amount));
                    break;
                case 4:
                    final String cardLast4 = String.format("%04d", random.nextInt(10_000));
                    final String transactionId = generateTransactionId();
                    final String location2 = LOCATIONS[random.nextInt(LOCATIONS.length)];
                    lines.add(
                            generatePattern4Transaction(
                                    cardLast4,
                                    month,
                                    day,
                                    month,
                                    day,
                                    transactionId,
                                    merchant,
                                    location2,
                                    amount));
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

    /** Generate a random transaction ID */
    private static String generateTransactionId() {
        final Random random = new Random();
        final StringBuilder sb = new StringBuilder();
        final String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < 12; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /** Get a random merchant name */
    public static String getRandomMerchant() {
        final Random random = new Random();
        return MERCHANTS[random.nextInt(MERCHANTS.length)];
    }

    /** Get a random location */
    public static String getRandomLocation() {
        final Random random = new Random();
        return LOCATIONS[random.nextInt(LOCATIONS.length)];
    }

    /** Generate Citibank statement transaction (Pattern: Date Description Amount) */
    public static String generateCitibankTransaction(
            final int month, final int day, final String description, final double amount) {
        final String date = String.format("%02d/%02d", month, day);
        final double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        if (amount < 0) {
            amountStr = "-" + amountStr;
        }
        return String.format("%s %s %s", date, description, amountStr);
    }

    /** Generate US Bank statement transaction (Pattern: Date PostDate Description Amount) */
    public static String generateUSBankTransaction(
            final int month1, final int day1, final int month2, final int day2, final String description, final double amount) {
        final String date1 = String.format("%02d/%02d", month1, day1);
        final String date2 = String.format("%02d/%02d", month2, day2);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s %s %s %s", date1, date2, description, amountStr);
    }

    /** Generate Discover statement transaction (Pattern: Date Description Amount CR/DR) */
    public static String generateDiscoverTransaction(
            final int month, final int day, final String description, final double amount, final boolean isCredit) {
        final String date = String.format("%02d/%02d/%02d", month, day, 24);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("$%.2f", absAmount);
        final String indicator = isCredit ? "CR" : "DR";
        return String.format("%s %s %s %s", date, description, amountStr, indicator);
    }

    /** Generate Synchrony Bank statement transaction (Pattern: Date Description Amount) */
    public static String generateSynchronyTransaction(
            final int month, final int day, final String description, final double amount) {
        final String date = String.format("%02d/%02d/%04d", month, day, 2024);
        final double absAmount = amount < 0 ? -amount : amount;
        String amountStr = String.format("$%.2f", absAmount);
        if (amount < 0) {
            amountStr = "(" + amountStr + ")";
        }
        return String.format("%s %s %s", date, description, amountStr);
    }

    /** Generate Capital One statement transaction (Pattern: Date Description Location Amount) */
    public static String generateCapitalOneTransaction(
            final int month, final int day, final String description, final String location, final double amount) {
        final String date = String.format("%02d/%02d", month, day);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s %s %s %s", date, description, location, amountStr);
    }

    /** Generate Apple Card statement transaction (Pattern: Date Description Amount) */
    public static String generateAppleCardTransaction(
            final int month, final int day, final String description, final double amount) {
        final String date = String.format("%02d/%02d", month, day);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr =
                String.format("-$%.2f", absAmount); // Apple Card shows negative for purchases
        return String.format("%s %s %s", date, description, amountStr);
    }

    /** Generate PayPal statement transaction (Pattern: Date Description Amount) */
    public static String generatePayPalTransaction(
            final int month, final int day, final int year, final String description, final double amount) {
        final String date = String.format("%02d/%02d/%04d", month, day, year);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("%.2f", absAmount);
        final String sign = amount < 0 ? "-" : "+";
        return String.format("%s %s %s%s", date, description, sign, amountStr);
    }

    /** Generate Venmo statement transaction (Pattern: Date Description Amount) */
    public static String generateVenmoTransaction(
            final int month, final int day, final String description, final double amount) {
        final String date = String.format("%02d/%02d", month, day);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("$%.2f", absAmount);
        final String sign = amount < 0 ? "-" : "+";
        return String.format("%s %s %s%s", date, description, sign, amountStr);
    }

    /** Generate Amazon Pay statement transaction (Pattern: Date Description Amount) */
    public static String generateAmazonPayTransaction(
            final int month, final int day, final String description, final double amount) {
        final String date = String.format("%02d/%02d/%04d", month, day, 2024);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s Amazon Pay - %s %s", date, description, amountStr);
    }

    /** Generate Google Pay statement transaction (Pattern: Date Description Amount) */
    public static String generateGooglePayTransaction(
            final int month, final int day, final String description, final double amount) {
        final String date = String.format("%02d/%02d", month, day);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s GOOGLE PAY *%s %s", date, description, amountStr);
    }

    /** Generate PayPal Mastercard statement transaction (Pattern: Date Description Amount) */
    public static String generatePayPalMastercardTransaction(
            final int month, final int day, final String description, final double amount) {
        final String date = String.format("%02d/%02d/%02d", month, day, 24);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("$%.2f", absAmount);
        return String.format("%s %s %s", date, description, amountStr);
    }

    /** Generate Amex Green Card statement transaction (multi-line format) */
    public static List<String> generateAmexGreenTransaction(
            final int month,
            final int day,
            final int year,
            final String userName,
            final String description,
            final String merchant,
            final double amount) {
        final List<String> lines = new ArrayList<>();
        final String date = String.format("%02d/%02d/%02d", month, day, year % 100);
        lines.add(String.format("%s* %s %s", date, userName, description));
        lines.add(merchant);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("-$%.2f", absAmount);
        lines.add(amountStr + " ⧫");
        lines.add(""); // Empty line separator
        return lines;
    }

    /** Generate Amex Goal Card statement transaction (multi-line format) */
    public static List<String> generateAmexGoalTransaction(
            final int month,
            final int day,
            final int year,
            final String userName,
            final String description,
            final String merchant,
            final double amount) {
        final List<String> lines = new ArrayList<>();
        final String date = String.format("%02d/%02d/%02d", month, day, year % 100);
        lines.add(String.format("%s* %s %s", date, userName, description));
        lines.add(merchant);
        final double absAmount = amount < 0 ? -amount : amount;
        final String amountStr = String.format("-$%.2f", absAmount);
        lines.add(amountStr + " ⧫");
        return lines;
    }

    /** Generate a complete Citibank statement */
    public static List<String> generateCitibankStatement(final int year, final int month) {
        final List<String> lines = new ArrayList<>();
        final Random random = new Random(42);

        lines.add("CITIBANK CREDIT CARD STATEMENT");
        lines.add("ACCOUNT ENDING IN 1234");
        lines.add("");

        for (int i = 0; i < 15; i++) {
            final int day = 1 + random.nextInt(28);
            final String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            final double amount = 10.00 + random.nextDouble() * 300.00;
            lines.add(generateCitibankTransaction(month, day, merchant, amount));
        }

        return lines;
    }

    /** Generate a complete US Bank statement */
    public static List<String> generateUSBankStatement(final int year, final int month) {
        final List<String> lines = new ArrayList<>();
        final Random random = new Random(43);

        lines.add("U.S. BANK CREDIT CARD STATEMENT");
        lines.add("");

        for (int i = 0; i < 12; i++) {
            final int day = 1 + random.nextInt(28);
            final String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            final double amount = 15.00 + random.nextDouble() * 400.00;
            lines.add(generateUSBankTransaction(month, day, month, day, merchant, amount));
        }

        return lines;
    }

    /** Generate a complete Discover statement */
    public static List<String> generateDiscoverStatement(final int year, final int month) {
        final List<String> lines = new ArrayList<>();
        final Random random = new Random(44);

        lines.add("DISCOVER CARD STATEMENT");
        lines.add("");

        for (int i = 0; i < 18; i++) {
            final int day = 1 + random.nextInt(28);
            final String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            final double amount = 20.00 + random.nextDouble() * 500.00;
            final boolean isCredit = random.nextBoolean();
            lines.add(
                    generateDiscoverTransaction(
                            month, day, merchant, isCredit ? amount : -amount, isCredit));
        }

        return lines;
    }

    /** Generate a complete Synchrony Bank statement */
    public static List<String> generateSynchronyStatement(final int year, final int month) {
        final List<String> lines = new ArrayList<>();
        final Random random = new Random(45);

        lines.add("SYNCHRONY BANK CREDIT CARD");
        lines.add("");

        for (int i = 0; i < 10; i++) {
            final int day = 1 + random.nextInt(28);
            final String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            final double amount = 25.00 + random.nextDouble() * 350.00;
            lines.add(generateSynchronyTransaction(month, day, merchant, amount));
        }

        return lines;
    }

    /** Generate a complete Capital One statement */
    public static List<String> generateCapitalOneStatement(final int year, final int month) {
        final List<String> lines = new ArrayList<>();
        final Random random = new Random(46);

        lines.add("CAPITAL ONE CREDIT CARD STATEMENT");
        lines.add("");

        for (int i = 0; i < 20; i++) {
            final int day = 1 + random.nextInt(28);
            final String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            final String location = LOCATIONS[random.nextInt(LOCATIONS.length)];
            final double amount = 12.00 + random.nextDouble() * 450.00;
            lines.add(generateCapitalOneTransaction(month, day, merchant, location, amount));
        }

        return lines;
    }

    /** Generate a complete Apple Card statement */
    public static List<String> generateAppleCardStatement(final int year, final int month) {
        final List<String> lines = new ArrayList<>();
        final Random random = new Random(47);

        lines.add("Apple Card Statement");
        lines.add("");

        for (int i = 0; i < 25; i++) {
            final int day = 1 + random.nextInt(28);
            final String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            final double amount = 8.00 + random.nextDouble() * 250.00;
            lines.add(generateAppleCardTransaction(month, day, merchant, amount));
        }

        return lines;
    }

    /** Generate a complete PayPal statement */
    public static List<String> generatePayPalStatement(final int year, final int month) {
        final List<String> lines = new ArrayList<>();
        final Random random = new Random(48);

        lines.add("PayPal Account Statement");
        lines.add("");

        for (int i = 0; i < 30; i++) {
            final int day = 1 + random.nextInt(28);
            final String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            final double amount = 5.00 + random.nextDouble() * 200.00;
            final boolean isCredit = random.nextBoolean();
            lines.add(
                    generatePayPalTransaction(
                            month, day, year, merchant, isCredit ? amount : -amount));
        }

        return lines;
    }

    /** Generate a complete Venmo statement */
    public static List<String> generateVenmoStatement(final int year, final int month) {
        final List<String> lines = new ArrayList<>();
        final Random random = new Random(49);

        lines.add("Venmo Transaction History");
        lines.add("");

        for (int i = 0; i < 35; i++) {
            final int day = 1 + random.nextInt(28);
            final String merchant = "VENMO PAYMENT TO " + MERCHANTS[random.nextInt(MERCHANTS.length)];
            final double amount = 10.00 + random.nextDouble() * 150.00;
            final boolean isCredit = random.nextBoolean();
            lines.add(generateVenmoTransaction(month, day, merchant, isCredit ? amount : -amount));
        }

        return lines;
    }

    /** Generate a complete PayPal Mastercard statement */
    public static List<String> generatePayPalMastercardStatement(final int year, final int month) {
        final List<String> lines = new ArrayList<>();
        final Random random = new Random(50);

        lines.add("PayPal Mastercard Statement");
        lines.add("");

        for (int i = 0; i < 22; i++) {
            final int day = 1 + random.nextInt(28);
            final String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            final double amount = 15.00 + random.nextDouble() * 300.00;
            lines.add(generatePayPalMastercardTransaction(month, day, merchant, amount));
        }

        return lines;
    }

    /** Generate a complete Amex Green Card statement */
    public static List<String> generateAmexGreenStatement(final int year, final int month) {
        final List<String> lines = new ArrayList<>();
        final Random random = new Random(51);

        lines.add("AMERICAN EXPRESS GREEN CARD STATEMENT");
        lines.add("");

        for (int i = 0; i < 15; i++) {
            final int day = 1 + random.nextInt(28);
            final String userName = "JOHN DOE";
            final String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            final String description = "PURCHASE AT " + merchant;
            final double amount = 30.00 + random.nextDouble() * 600.00;
            lines.addAll(
                    generateAmexGreenTransaction(
                            month, day, year, userName, description, merchant, amount));
        }

        return lines;
    }

    /** Generate a complete Amex Goal Card statement */
    public static List<String> generateAmexGoalStatement(final int year, final int month) {
        final List<String> lines = new ArrayList<>();
        final Random random = new Random(52);

        lines.add("AMERICAN EXPRESS GOAL CARD STATEMENT");
        lines.add("");

        for (int i = 0; i < 12; i++) {
            final int day = 1 + random.nextInt(28);
            final String userName = "JANE SMITH";
            final String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            final String description = "PURCHASE AT " + merchant;
            final double amount = 25.00 + random.nextDouble() * 550.00;
            lines.addAll(
                    generateAmexGoalTransaction(
                            month, day, year, userName, description, merchant, amount));
        }

        return lines;
    }

    private RealWorldStatementTestDataGenerator() {
    }
}
