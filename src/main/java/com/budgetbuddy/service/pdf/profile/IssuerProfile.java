package com.budgetbuddy.service.pdf.profile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * Strategy contract for issuer-specific statement-metadata extraction.
 *
 * <p>Before this interface existed, {@code PDFImportService} held ~40 static extractor
 * methods, each backed by a flat array of regex patterns covering every issuer we knew
 * about. Adding a new issuer (Wells Fargo, USB, Citi, Amex, BoA, Apple Card …) meant
 * grafting patterns into 15 separate arrays. The classes had no per-issuer locality:
 * a regex regression on Citi could break Chase silently.
 *
 * <h2>How profiles compose</h2>
 *
 * <p>Each {@link IssuerProfile} owns the regexes and parse logic for ONE issuer family.
 * Multiple product brands within the issuer (e.g. Marriott Bonvoy vs. Freedom Flex vs.
 * Sapphire under Chase) are handled by the same profile when they share statement layout.
 *
 * <p>A {@link IssuerProfileRegistry} runs profiles in declaration order and returns the
 * FIRST one whose {@link #matches} predicate fires against the header lines. The
 * registry's tail is a {@link GenericFallbackProfile} that owns the cross-issuer
 * pattern union via {@link StatementParsingUtilities} — so a statement we don't yet
 * recognize still parses with the generic patterns shared across most US issuers.
 *
 * <h2>Extension paths</h2>
 *
 * <ul>
 *   <li><b>New Java profile</b>: extend {@link AbstractIssuerProfile} and add the class
 *       to {@link IssuerProfileRegistry#defaultRegistry()}. Override only the fields
 *       that need issuer-specific handling — everything else falls back to the generic
 *       implementation.
 *   <li><b>New YAML profile</b>: drop a {@code statement-profiles/<issuer>.yaml} file in
 *       the classpath. {@link YamlBackedIssuerProfile#loadAll} picks it up at startup.
 *       Use this for issuers whose extraction is purely declarative regex matching.
 *   <li><b>Generic catch-all</b>: when neither fires, {@link GenericFallbackProfile}
 *       runs the full union of cross-issuer patterns via
 *       {@link StatementParsingUtilities}.
 * </ul>
 *
 * <h2>Why interface + abstract class?</h2>
 *
 * <p>The interface keeps the contract minimal so YAML-backed profiles and Java subclasses
 * can both satisfy it. {@link AbstractIssuerProfile} carries the default delegation to
 * the shared {@link StatementParsingUtilities} so a fresh Java profile only writes
 * overrides for the fields it actually wants to handle differently.
 */
public interface IssuerProfile {

    /** Short stable identifier — used in logs and metrics ("chase", "wells-fargo", "boa"). */
    String issuerId();

    /** Human-readable display name. */
    String displayName();

    /**
     * Returns true when this profile claims the statement. Implementations should match
     * on header text only — running per-line regex against the whole document would
     * defeat the dispatch optimization.
     *
     * @param headerText concatenation of the first ~30 non-blank lines of the PDF.
     */
    boolean matches(String headerText);

    /**
     * Optional secondary identifier inside the issuer — Wells Fargo Active Cash vs.
     * Wells Fargo Autograph, Chase Marriott Bonvoy vs. Chase Freedom Flex. Returns
     * {@code null} when not detected. Surfaced for analytics, not used by the registry.
     */
    default String detectBrand(String headerText) {
        return null;
    }

    // ---- Statement-summary extractors ----
    // Each method returns null when the field isn't present on this issuer's layout.
    // Default implementations on AbstractIssuerProfile delegate to
    // StatementParsingUtilities for the cross-issuer pattern union; subclasses
    // override individual methods to apply issuer-specific logic.

    default BigDecimal extractNewBalance(String[] lines, ExtractionContext ctx) { return null; }
    default BigDecimal extractPreviousBalance(String[] lines, ExtractionContext ctx) { return null; }
    default BigDecimal extractCreditLimit(String[] lines, ExtractionContext ctx) { return null; }
    default BigDecimal extractAvailableCredit(String[] lines, ExtractionContext ctx) { return null; }
    default BigDecimal extractPastDueAmount(String[] lines, ExtractionContext ctx) { return null; }
    default BigDecimal extractPurchasesTotal(String[] lines, ExtractionContext ctx) { return null; }
    default BigDecimal extractPaymentsAndCreditsTotal(String[] lines, ExtractionContext ctx) { return null; }
    default BigDecimal extractFeesChargedTotal(String[] lines, ExtractionContext ctx) { return null; }
    default BigDecimal extractInterestChargedTotal(String[] lines, ExtractionContext ctx) { return null; }
    default BigDecimal extractPurchaseApr(String[] lines, ExtractionContext ctx) { return null; }
    default BigDecimal extractCashAdvanceApr(String[] lines, ExtractionContext ctx) { return null; }
    default BigDecimal extractBalanceTransferApr(String[] lines, ExtractionContext ctx) { return null; }
    default BigDecimal extractPenaltyApr(String[] lines, ExtractionContext ctx) { return null; }
    default Integer extractBillingDays(String[] lines, ExtractionContext ctx) { return null; }
    default LocalDate extractStatementDate(String[] lines, ExtractionContext ctx) { return null; }
    default Boolean extractAutoPayEnabled(String[] lines, ExtractionContext ctx) { return null; }
    default BigDecimal extractNextAutoPayAmount(String[] lines, ExtractionContext ctx) { return null; }
    default Long extractPointsBalance(String[] lines, ExtractionContext ctx) { return null; }
    default Long extractPointsEarnedThisPeriod(String[] lines, ExtractionContext ctx) { return null; }
    default BigDecimal extractCashBackBalance(String[] lines, ExtractionContext ctx) { return null; }
    default BigDecimal extractYtdFeesCharged(String[] lines, ExtractionContext ctx) { return null; }
    default BigDecimal extractYtdInterestCharged(String[] lines, ExtractionContext ctx) { return null; }

    // ---- Less-common extractors ----
    // Same dispatch pattern as the core extractors above — null default here, the
    // AbstractIssuerProfile delegates to StatementParsingUtilities by default.

    /** Statement-section total: cash advances taken this cycle. */
    default BigDecimal extractCashAdvancesTotal(String[] lines, ExtractionContext ctx) {
        return null;
    }

    /** Statement-section total: balance transfers this cycle. */
    default BigDecimal extractBalanceTransfersTotal(String[] lines, ExtractionContext ctx) {
        return null;
    }

    /** Cash-advance sub-limit (e.g. "Cash Access Line $4,000"). */
    default BigDecimal extractCashAccessLine(String[] lines, ExtractionContext ctx) {
        return null;
    }

    /** Available cash-advance headroom (e.g. "Available for Cash $4,000"). */
    default BigDecimal extractAvailableForCash(String[] lines, ExtractionContext ctx) {
        return null;
    }

    /** Foreign-transaction fee percent (e.g. 3.0 for 3%). */
    default BigDecimal extractForeignTransactionFeePercent(
            String[] lines, ExtractionContext ctx) {
        return null;
    }

    /** Prior-cycle points balance (Chase Amazon Visa-style "Previous points balance"). */
    default Long extractPreviousPointsBalance(String[] lines, ExtractionContext ctx) {
        return null;
    }

    /**
     * Annual membership fee amount + scheduled billing date. Returns a 2-element
     * array {@code [BigDecimal feeAmount, LocalDate dueDate]} or null when no annual
     * fee row is printed (most no-fee cards omit it entirely).
     */
    default Object[] extractAnnualMembershipFeeAndDate(
            String[] lines, ExtractionContext ctx) {
        return null;
    }

    /**
     * Per-category reward multipliers keyed by issuer label
     * (e.g. {@code "amazon.com purchases" -> 5.0}). Empty map when the statement
     * has no rewards section.
     */
    default java.util.Map<String, java.math.BigDecimal> extractRewardMultipliersFromPdf(
            String[] lines, ExtractionContext ctx) {
        return java.util.Collections.emptyMap();
    }

    /**
     * Current cycle's rotating-quarter bonus tier (Chase Freedom).
     * Returns a {@link StatementParsingUtilities.QuarterlyBonus} when present, null
     * for cards without rotating bonuses.
     */
    default StatementParsingUtilities.QuarterlyBonus extractCurrentQuarterBonus(
            String[] lines, ExtractionContext ctx) {
        return null;
    }

    /**
     * Next-quarter activation window (Chase Freedom's "Get 5% in Q1..." disclosure).
     * Returns null for cards / cycles without an upcoming-bonus declaration.
     */
    default StatementParsingUtilities.NextQuarterBonus extractNextQuarterBonus(
            String[] lines, ExtractionContext ctx) {
        return null;
    }

    /**
     * Common context for extractor calls. Carries inferred-year + locale so each method
     * doesn't have to re-derive them. Immutable, safe to share across threads.
     */
    final class ExtractionContext {
        private final Integer inferredYear;
        private final boolean usLocale;

        public ExtractionContext(final Integer inferredYear, final boolean usLocale) {
            this.inferredYear = inferredYear;
            this.usLocale = usLocale;
        }

        public Integer inferredYear() {
            return inferredYear;
        }

        public boolean usLocale() {
            return usLocale;
        }
    }

    /**
     * Tiny helper: does any line of the header text match a pattern? Used by
     * {@link #matches} implementations.
     */
    static boolean headerContains(final String headerText, final Pattern pattern) {
        if (headerText == null) {
            return false;
        }
        return pattern.matcher(headerText).find();
    }
}
