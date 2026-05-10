package com.budgetbuddy.service.pdf;

import java.util.regex.Pattern;

/**
 * Compiled regex patterns shared by {@code PDFImportService} for reading bank and credit-card
 * statements.
 *
 * <p>Extracted so that (a) the ~60 lines of pattern declarations don't live at the top of the
 * 5,000-line service file where they make the real logic invisible, (b) pattern behaviour can be
 * unit-tested in isolation (each pattern here has a known set of target shapes and known
 * counter-examples), and (c) adding a new statement format means touching one small file rather
 * than scrolling to the right line of the monolith.
 *
 * <p>Every {@code Pattern} compiled in this class is referenced from the service's parsing hot
 * loops. {@link java.util.regex.Pattern} is thread- safe and {@link java.util.regex.Matcher} is
 * derived per-invocation, so these can safely live as shared constants.
 *
 * <p><strong>Non-obvious choices — read before editing:</strong>
 *
 * <ul>
 *   <li>{@link #US_AMOUNT_PATTERN_STR} is a non-capturing wrapper around three alternative amount
 *       shapes (parenthesised, signed, plain). Changing the number of capture groups here shifts
 *       the index of every downstream {@code group(n)} call — don't reorder branches.
 *   <li>{@code \\d{1,9}} (not {@code \\d{1,3}}) so amounts without thousands separators like {@code
 *       1234.56} still match — some banks emit exactly that form.
 *   <li>{@link #AMOUNT_PATTERN_END_ANCHORED} requires end-of-line. The pattern below that allows
 *       word-boundary matches is deliberately separate so we don't accidentally pluck partial
 *       amounts out of phone numbers or account-number fragments.
 * </ul>
 */
public final class PdfStatementPatterns {

    private PdfStatementPatterns() {}

    // ========== DATE PATTERNS ==========

    /** MM/DD/YY, MM-DD-YY, MM/DD/YYYY, MM-DD-YYYY (year optional). */
    public static final String DATE_PATTERN_STR = "(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?";

    public static final Pattern DATE_PATTERN = Pattern.compile(DATE_PATTERN_STR);

    /**
     * MM/DD with no year — used in contexts where year is inferred from the statement's
     * closing-date metadata.
     */
    public static final String DATE_PATTERN_NO_YEAR_STR = "(\\d{1,2}/\\d{1,2})";

    public static final Pattern DATE_PATTERN_NO_YEAR = Pattern.compile(DATE_PATTERN_NO_YEAR_STR);

    // ========== AMOUNT PATTERNS ==========

    /**
     * Non-capturing wrapper around three alternative amount shapes: 1. Parenthesised negatives:
     * ($123.45), ($1,234.56 CR) 2. Explicitly signed: -$458.40, +$1,234.56 3. Standard: $123.45,
     * $123.45 DR
     *
     * <p>Supports trailing CR/DR/BF suffixes (credit / debit / brought- forward). Decimal places
     * are {@code {1,2}} so both "$1.5" and "$1.50" match — some export flows drop trailing zeros.
     */
    public static final String US_AMOUNT_PATTERN_STR =
            "(?:"
                    + "(\\(\\s*\\$?\\s*\\d{1,9}(?:,\\d{3})*(?:\\.\\d{1,2})?\\s*(?:CR|DR|BF)?\\s*\\))|"
                    + "([-+]\\$\\d{1,9}(?:,\\d{3})*(?:\\.\\d{1,2})?\\s*(?:CR|DR|BF)?)|"
                    + "(\\$\\d{1,9}(?:,\\d{3})*(?:\\.\\d{1,2})?\\s*(?:CR|DR|BF)?)"
                    + ")";

    /**
     * Flexible amount pattern with word boundaries. Used when the amount may appear mid-line rather
     * than at the end. The lookbehind / lookahead guards prevent us from grabbing partial digits
     * out of phone numbers or account-number masks.
     */
    public static final String FALLBACK_AMOUNT_PATTERN_STR =
            "(?<!\\w)(\\$\\d{1,9}(?:,\\d{3})*(?:\\.\\d{1,2})?|"
                    + "[-]\\$?\\d{1,9}(?:,\\d{3})*(?:\\.\\d{1,2})?|"
                    + "[(]\\$?\\d{1,9}(?:,\\d{3})*(?:\\.\\d{1,2})?[)]|"
                    + "\\d{1,9}(?:,\\d{3})*\\.\\d{2}|"
                    + "\\.\\d{1,2})(?!\\d)";

    public static final Pattern FALLBACK_AMOUNT_PATTERN =
            Pattern.compile(FALLBACK_AMOUNT_PATTERN_STR);

    /** End-anchored variant for single-amount-at-end-of-line rows. */
    public static final Pattern AMOUNT_PATTERN_END_ANCHORED =
            Pattern.compile(
                    "([-+]?\\$\\d{1,9}(?:,\\d{3})*(?:\\.\\d{1,2})?\\s*(?:CR|DR|BF)?|"
                            + "\\(\\s*\\$?\\s*\\d{1,9}(?:,\\d{3})*(?:\\.\\d{1,2})?\\s*(?:CR|DR|BF)?\\s*\\)|"
                            + "\\.\\d{1,2})\\s*$",
                    Pattern.CASE_INSENSITIVE);

    // ========== LINE SHAPE PATTERNS ==========

    /** Pattern 1: "12/15 GROCERY STORE $45.67" — date, description, amount. */
    public static final Pattern PATTERN1_DATE_DESC_AMOUNT =
            Pattern.compile("^(\\d{1,2}/\\d{1,2})\\s+(.+?)\\s+" + US_AMOUNT_PATTERN_STR + "$");

    /** Pattern 2: Optional prefix text, then date, description, amount. */
    public static final Pattern PATTERN2_PREFIX_DATE_DESC_AMOUNT =
            Pattern.compile(
                    "(\\d{1,2}/\\d{1,2})\\s+(.+?)\\s+" + US_AMOUNT_PATTERN_STR + "$",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Pattern 4: Chase-family multi-card format — "1234 12/15 12/16 AUTHCODE MERCHANT CITY ST
     * $45.67".
     */
    public static final Pattern PATTERN4_CARD_DATES_ID_DESC_LOC_AMOUNT =
            Pattern.compile(
                    "^(\\d{4})\\s+(\\d{1,2}/\\d{1,2})\\s+(\\d{1,2}/\\d{1,2})\\s+"
                            + "([A-Z0-9]+)\\s+(.+?)\\s+([A-Z][A-Z\\s]{1,20})\\s+("
                            + US_AMOUNT_PATTERN_STR
                            + ")$");

    /** Pattern 5: Two dates (trans + post), merchant, location, amount. */
    public static final Pattern PATTERN5_TWO_DATES_MERCHANT_LOC_AMOUNT =
            Pattern.compile(
                    "^(\\d{1,2}/\\d{1,2})\\s+(\\d{1,2}/\\d{1,2})\\s+(.+?)\\s+"
                            + "([A-Z][A-Z\\s]{1,20})\\s+("
                            + US_AMOUNT_PATTERN_STR
                            + ")$");

    /** Pattern 7, line 1: full-year date + description on one line. */
    public static final Pattern PATTERN7_LINE1_DATE_DESC =
            Pattern.compile("^(\\d{1,2}/\\d{1,2}/\\d{2,4})\\*?\\s+(.+)$");

    /**
     * Pattern 7, line 3: amount alone on its own line. Optional trailing diamond glyph is a Chase
     * statement convention. Word boundaries prevent the amount-alone regex from swallowing
     * fragments of phone numbers.
     */
    public static final Pattern PATTERN7_LINE3_AMOUNT =
            Pattern.compile("^(?<!\\w)(" + US_AMOUNT_PATTERN_STR + ")(?!\\w)\\s*[⧫]?$");

    // ========== FILTER PATTERNS ==========

    /**
     * Matches headers/footers that should be skipped during transaction extraction ("Credits",
     * "Charges", "Balance transfers"). Case- insensitive so "CREDITS" and "credits" both hit.
     */
    public static final Pattern INFORMATIONAL_PHRASES =
            Pattern.compile(
                    ".*(credits|charges|amount|purchases|balance transfers).*",
                    Pattern.CASE_INSENSITIVE);
}
