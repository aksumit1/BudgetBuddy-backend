package com.budgetbuddy.service.pdf.v2;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates v2 YAML templates against PDF text to produce extracted fields.
 *
 * <p>Stateless and side-effect free. Each public method takes raw text + a
 * template and returns a small Result object so the caller (PDFImportService)
 * can wire the values into its existing {@code ImportResult} without coupling
 * back to v2 internals.
 *
 * <p>The evaluator is intentionally tolerant: every rule kind has a documented
 * fallback, and a malformed rule logs WARN and yields {@code null} rather than
 * throwing. The v2 layer must NEVER break an import that the v1 layer would
 * have succeeded on.
 */
public final class PdfTemplateV2Evaluator {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PdfTemplateV2Evaluator.class);

    /**
     * Dollar-amount regex used by {@code adjacent: dollar} label rules. Requires
     * a literal {@code $} so loose decimals like APR percentages
     * ("Purchases 18.49% (v) 0.00") aren't matched as dollar amounts. Signs may
     * appear before OR after the {@code $} sigil; thousands separator optional.
     */
    private static final Pattern DOLLAR_AMOUNT =
            Pattern.compile(
                    "(?:[-+]\\s*\\$|\\$\\s*[-+]?)\\s*"
                            + "(\\d{1,9}(?:,\\d{3})*\\.\\d{2})");

    /**
     * Lines that LOOK like a labelled total but are actually APR-disclosure rows
     * ("Purchases 18.49% (v) ... 0.00") or other percentage-bearing rows. We
     * skip these when scanning for {@code adjacent: dollar} matches to avoid the
     * GenericFallbackProfile pitfall that picks an APR rate as a purchases total.
     */
    private static final Pattern APR_DISCLOSURE_LINE =
            Pattern.compile(
                    "(?i)\\b\\d{1,2}\\.\\d{1,3}\\s*%\\s*\\(?v\\)?",
                    Pattern.CASE_INSENSITIVE);

    /** Date regex used by `adjacent: date` label rules. */
    private static final Pattern SLASH_DATE =
            Pattern.compile("(\\d{1,2}/\\d{1,2}/\\d{2,4})");

    // ---- Card detection ----

    public static final class CardDetectionResult {
        public final String institution;
        public final String lastFour;
        public final String accountHolder;

        public CardDetectionResult(
                final String institution, final String lastFour, final String accountHolder) {
            this.institution = institution;
            this.lastFour = lastFour;
            this.accountHolder = accountHolder;
        }
    }

    public CardDetectionResult evaluateCardDetection(
            final PdfTemplateV2 template, final String fullText, final String filename) {
        if (template == null || template.getCardDetection() == null || fullText == null) {
            return null;
        }
        final PdfTemplateV2.CardDetection cd = template.getCardDetection();

        // Institution: any matching rule confirms (we picked this template because
        // detection already pointed here). We still scan so a template can carry
        // multiple aliases and downstream code can use the canonical form.
        String institution = null;
        for (final PdfTemplateV2.RegexRule r : cd.getInstitutionMatch()) {
            if (matchesAny(r, fullText)) {
                institution = template.getInstitution();
                break;
            }
        }

        // Last-4: first matching rule wins. Returns the masked-stripped 4-digit
        // suffix so callers can store it directly. Filename fallback honoured.
        String lastFour = null;
        for (final PdfTemplateV2.RegexRule r : cd.getLastFour()) {
            final String pattern = r.getPattern();
            if (pattern == null) continue;
            final Pattern p = RegexCache.compile(pattern, Pattern.CASE_INSENSITIVE);
            if (p != null) {
                try {
                    final Matcher m = RegexCache.safeMatcher(p, fullText);
                    if (m.find() && m.groupCount() >= 1) {
                        lastFour = extractLastFour(m.group(1));
                        if (lastFour != null) break;
                    }
                } catch (final RegexCache.RegexTimeoutException ex) {
                    LOGGER.warn("v2 last-four regex timeout: pattern={}", pattern);
                }
            }
            if (Boolean.TRUE.equals(r.getFilenameFallback()) && filename != null) {
                final Pattern fp = RegexCache.compile("(?<=\\D)(\\d{4})(?=\\.\\w+$|$)");
                if (fp != null) {
                    final Matcher fm = fp.matcher(filename);
                    if (fm.find()) {
                        lastFour = fm.group(1);
                        break;
                    }
                }
            }
        }

        // Account holder: simplest implementation — find a line above the
        // "Account Ending" marker. The lines_above_account rule says how far
        // up to look. The detected holder is the last ALL-CAPS-ish line above.
        String accountHolder = null;
        for (final PdfTemplateV2.HolderRule h : cd.getAccountHolder()) {
            accountHolder = extractHolderName(fullText, h);
            if (accountHolder != null) break;
        }

        return new CardDetectionResult(institution, lastFour, accountHolder);
    }

    // ---- Statement metadata ----

    public static final class MetadataResult {
        public BigDecimal newBalance;
        public BigDecimal previousBalance;
        public BigDecimal creditLimit;
        public BigDecimal availableCredit;
        public BigDecimal minimumPaymentDue;
        public LocalDate paymentDueDate;
        public BigDecimal purchasesTotal;
        public BigDecimal paymentsAndCreditsTotal;
        public BigDecimal feesTotal;
        public BigDecimal interestTotal;
        public BigDecimal ytdFees;
        public BigDecimal ytdInterest;
        public BigDecimal purchaseApr;
        public BigDecimal cashAdvanceApr;
        public BigDecimal balanceTransferApr;
        public BigDecimal penaltyApr;
        public Long pointsBalance;
        public Long pointsEarned;
        public Long previousPointsBalance;
        public BigDecimal cashbackBalance;
        public Boolean autopayEnabled;
        public BigDecimal nextAutopayAmount;
        public BigDecimal annualFee;
        public LocalDate annualFeeDueDate;
        public BigDecimal foreignTxFeePercent;
        public Integer billingDays;
        public LocalDate statementDate;
        public LocalDate statementStart;
        public LocalDate statementEnd;
        /**
         * Per-field rule provenance: which rule (by index in the field's rule
         * list) and what kind ("label" / "pattern" / "stacked") fired to
         * produce the value. Useful for debugging — when a field comes back
         * null, the corresponding entry is null; when it's set, this records
         * the winning rule. Populated by the evaluator; not part of the
         * public extraction contract — treat as a debug aid only.
         */
        public Map<String, ExtractionContext.RuleHit> provenance =
                Collections.emptyMap();
    }

    public MetadataResult evaluateMetadata(final PdfTemplateV2 template, final String fullText) {
        if (template == null || template.getMetadata() == null || fullText == null) {
            return null;
        }
        final PdfTemplateV2.MetadataRules m = template.getMetadata();
        final MetadataResult r = new MetadataResult();
        final ExtractionContext ctx = new ExtractionContext(fullText);

        r.newBalance = firstLabelAmount("new_balance", m.getNewBalance(), ctx);
        r.previousBalance = firstLabelAmount("previous_balance", m.getPreviousBalance(), ctx);
        r.creditLimit = firstLabelAmount("credit_limit", m.getCreditLimit(), ctx);
        r.availableCredit = firstLabelAmount("available_credit", m.getAvailableCredit(), ctx);
        r.minimumPaymentDue = firstLabelAmount("minimum_payment_due", m.getMinimumPaymentDue(), ctx);
        r.paymentDueDate = firstLabelDate("payment_due_date", m.getPaymentDueDate(), ctx);
        r.purchasesTotal = firstLabelAmount("purchases_total", m.getPurchasesTotal(), ctx);
        r.paymentsAndCreditsTotal = m.isPaymentsTotalSum()
                ? sumLabelAmounts("payments_total", m.getPaymentsTotal(), ctx)
                : firstLabelAmount("payments_total", m.getPaymentsTotal(), ctx);
        r.feesTotal = firstLabelAmount("fees_total", m.getFeesTotal(), ctx);
        r.interestTotal = firstLabelAmount("interest_total", m.getInterestTotal(), ctx);
        r.ytdFees = firstLabelAmount("ytd_fees", m.getYtdFees(), ctx);
        r.ytdInterest = firstLabelAmount("ytd_interest", m.getYtdInterest(), ctx);
        r.purchaseApr = firstLabelPercent("purchase_apr", m.getPurchaseApr(), ctx);
        r.cashAdvanceApr = firstLabelPercent("cash_advance_apr", m.getCashAdvanceApr(), ctx);
        r.balanceTransferApr = firstLabelPercent("balance_transfer_apr", m.getBalanceTransferApr(), ctx);
        r.penaltyApr = firstLabelPercent("penalty_apr", m.getPenaltyApr(), ctx);
        r.pointsBalance = firstLabelInteger("points_balance", m.getPointsBalance(), ctx);
        r.pointsEarned = firstLabelInteger("points_earned", m.getPointsEarned(), ctx);
        r.previousPointsBalance = firstLabelInteger(
                "previous_points_balance", m.getPreviousPointsBalance(), ctx);
        r.cashbackBalance = firstLabelAmount("cashback_balance", m.getCashbackBalance(), ctx);
        r.autopayEnabled = firstLabelBoolean("autopay_enabled", m.getAutopayEnabled(), ctx);
        r.nextAutopayAmount = firstLabelAmount("next_autopay_amount", m.getNextAutopayAmount(), ctx);
        r.annualFee = firstLabelAmount("annual_fee", m.getAnnualFee(), ctx);
        r.annualFeeDueDate = firstLabelDate("annual_fee_due_date", m.getAnnualFeeDueDate(), ctx);
        r.foreignTxFeePercent = firstLabelPercent(
                "foreign_tx_fee_percent", m.getForeignTxFeePercent(), ctx);
        r.billingDays = firstLabelIntegerSmall("billing_days", m.getBillingDays(), ctx);

        r.statementDate = firstLabelDate("statement_date", m.getStatementDate(), ctx);

        // Per-field bounds check: log WARN when an extracted value falls
        // outside its sane range. We leave the value as extracted (the YAML
        // author may have a legitimate edge case) — the log just makes the
        // anomaly visible. See FieldBounds for the registry.
        FieldBounds.checkAll(r);
        // Math-identity sanity check: prev - paymentsCredits + purchases + fees
        // + interest = newBalance. When all five values exist and the identity
        // breaks by more than 1¢, surface a debug warning so silent extraction
        // misses are visible in logs without failing the import.
        warnIfMathIdentityBroken(r);
        r.provenance = ctx.getProvenance();

        // Statement period uses a 2-capture-group regex: start, end.
        for (final PdfTemplateV2.PeriodRule pr : m.getStatementPeriod()) {
            final String pat = pr.getPattern();
            if (pat == null) continue;
            final Pattern p = RegexCache.compile(pat, Pattern.CASE_INSENSITIVE);
            if (p == null) continue;
            try {
                final Matcher mm = RegexCache.safeMatcher(p, ctx.fullText);
                if (mm.find() && mm.groupCount() >= 2) {
                    r.statementStart = parseLooseDate(mm.group(1));
                    r.statementEnd = parseLooseDate(mm.group(2));
                    break;
                }
            } catch (final RegexCache.RegexTimeoutException ex) {
                LOGGER.warn("v2 statement-period regex timeout: pattern={}", pat);
            }
        }
        return r;
    }

    // ---- Internals ----

    private static boolean matchesAny(final PdfTemplateV2.RegexRule r, final String text) {
        if (r.getPattern() != null) {
            if (cachedRegexFind(r.getPattern(), text)) return true;
        }
        for (final String alt : r.getAnyOf()) {
            if (alt == null) continue;
            if (cachedRegexFind(alt, text)) return true;
        }
        return false;
    }

    /**
     * Cached + timeout-safe regex {@code .find()}. Returns false on any
     * failure (uncompileable pattern, runaway evaluation). The first call to
     * a bad pattern logs WARN via {@link RegexCache}; subsequent calls return
     * false silently.
     */
    private static boolean cachedRegexFind(final String pattern, final CharSequence text) {
        final Pattern p = RegexCache.compile(pattern, Pattern.CASE_INSENSITIVE);
        if (p == null) return false;
        try {
            return RegexCache.safeMatcher(p, text).find();
        } catch (final RegexCache.RegexTimeoutException ex) {
            LOGGER.warn("v2 regex timeout on pattern '{}': {}", pattern, ex.getMessage());
            return false;
        }
    }

    /** Strip mask glyphs from a capture and return the last 4 digits, or null. */
    private static String extractLastFour(final String raw) {
        if (raw == null) return null;
        final String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() < 3) return null;
        return digits.length() > 4 ? digits.substring(digits.length() - 4) : digits;
    }

    private static String extractHolderName(
            final String fullText, final PdfTemplateV2.HolderRule rule) {
        if (rule == null) return null;
        if (rule.getPattern() != null && !rule.getPattern().isBlank()) {
            final Pattern p = RegexCache.compile(rule.getPattern());
            if (p != null) {
                try {
                    final Matcher m = RegexCache.safeMatcher(p, fullText);
                    if (m.find() && m.groupCount() >= 1) {
                        return m.group(1).trim();
                    }
                } catch (final RegexCache.RegexTimeoutException ex) {
                    LOGGER.warn("v2 holder regex timeout: pattern={}", rule.getPattern());
                }
            }
        }
        // Default strategy: walk the lines, find the "Account Ending" marker,
        // and pick the nearest preceding 1-3-line ALL-CAPS-ish name. This is
        // intentionally narrow — anything more elaborate goes via an explicit
        // pattern in YAML.
        final String[] lines = fullText.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].toLowerCase().contains("account ending")) {
                for (int j = i - 1; j >= Math.max(0, i - 3); j--) {
                    final String candidate = lines[j].trim();
                    if (looksLikeHolder(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static boolean looksLikeHolder(final String candidate) {
        if (candidate == null || candidate.length() < 4 || candidate.length() > 80) return false;
        // Must contain at least one ALL-CAPS word ≥3 letters and 2+ tokens.
        // We deliberately tolerate trailing alphanumeric junk (e.g. account-code
        // suffix) — callers strip that downstream.
        final String[] toks = candidate.trim().split("\\s+");
        if (toks.length < 2 || toks.length > 8) return false;
        int allCapsLetterTokens = 0;
        for (final String t : toks) {
            if (t.matches("[A-Z]{3,}")) allCapsLetterTokens++;
        }
        return allCapsLetterTokens >= 2;
    }

    private static BigDecimal firstLabelAmount(
            final String field,
            final List<PdfTemplateV2.LabelRule> rules, final ExtractionContext ctx) {
        for (int i = 0; i < rules.size(); i++) {
            final BigDecimal v = applyLabelRule(field, i, rules.get(i), ctx,
                    DOLLAR_AMOUNT, PdfTemplateV2Evaluator::parseAmount);
            if (v != null) return v;
        }
        return null;
    }

    /**
     * Sum every rule's amount match, ignoring null. Used when the issuer
     * prints a field as multiple constituent rows (e.g. Citi prints
     * "Payments -$4,784.23" and "Credits -$319.45" on separate lines, and
     * what this app calls payments_total is their sum).
     */
    private static BigDecimal sumLabelAmounts(
            final String field,
            final List<PdfTemplateV2.LabelRule> rules, final ExtractionContext ctx) {
        BigDecimal sum = null;
        for (int i = 0; i < rules.size(); i++) {
            final BigDecimal v = applyLabelRule(field, i, rules.get(i), ctx,
                    DOLLAR_AMOUNT, PdfTemplateV2Evaluator::parseAmount);
            if (v != null) sum = (sum == null ? BigDecimal.ZERO : sum).add(v.abs());
        }
        return sum;
    }

    private static LocalDate firstLabelDate(
            final String field,
            final List<PdfTemplateV2.LabelRule> rules, final ExtractionContext ctx) {
        for (int i = 0; i < rules.size(); i++) {
            final LocalDate v = applyLabelRule(field, i, rules.get(i), ctx,
                    SLASH_DATE, PdfTemplateV2Evaluator::parseLooseDate);
            if (v != null) return v;
        }
        return null;
    }

    /** Percent value (e.g. "19.49%"). Returns 19.49 as a BigDecimal. */
    private static final Pattern PERCENT_VALUE =
            Pattern.compile("(\\d{1,2}(?:\\.\\d{1,3})?)\\s*%");

    private static BigDecimal firstLabelPercent(
            final String field,
            final List<PdfTemplateV2.LabelRule> rules, final ExtractionContext ctx) {
        for (int i = 0; i < rules.size(); i++) {
            final BigDecimal v = applyLabelRule(field, i, rules.get(i), ctx,
                    PERCENT_VALUE, PdfTemplateV2Evaluator::parseAmount);
            if (v != null) return v;
        }
        return null;
    }

    /** Integer value with optional thousands separator. Used for points balances. */
    private static final Pattern INTEGER_VALUE =
            Pattern.compile("(\\d{1,3}(?:,\\d{3})+|\\d{2,12})");

    private static Long firstLabelInteger(
            final String field,
            final List<PdfTemplateV2.LabelRule> rules, final ExtractionContext ctx) {
        for (int i = 0; i < rules.size(); i++) {
            final Long v = applyLabelRule(field, i, rules.get(i), ctx, INTEGER_VALUE,
                    raw -> {
                        if (raw == null) return null;
                        try { return Long.parseLong(raw.replace(",", "")); }
                        catch (final NumberFormatException e) { return null; }
                    });
            if (v != null) return v;
        }
        return null;
    }

    /** Small integer (1-365), for billing-days. */
    private static final Pattern SMALL_INTEGER = Pattern.compile("(\\d{1,3})");

    private static Integer firstLabelIntegerSmall(
            final String field,
            final List<PdfTemplateV2.LabelRule> rules, final ExtractionContext ctx) {
        for (int i = 0; i < rules.size(); i++) {
            final Integer v = applyLabelRule(field, i, rules.get(i), ctx, SMALL_INTEGER,
                    raw -> {
                        if (raw == null) return null;
                        try { return Integer.parseInt(raw); }
                        catch (final NumberFormatException e) { return null; }
                    });
            if (v != null && v >= 1 && v <= 365) return v;
        }
        return null;
    }

    /**
     * Boolean rule: returns true when the label phrase is found anywhere
     * (e.g. "AUTOPAY IS ON"), false when "AUTOPAY IS OFF" is found, null when
     * neither. Caller supplies both as separate LabelRules — first match wins.
     */
    private static Boolean firstLabelBoolean(
            final String field,
            final List<PdfTemplateV2.LabelRule> rules, final ExtractionContext ctx) {
        for (int i = 0; i < rules.size(); i++) {
            final PdfTemplateV2.LabelRule rule = rules.get(i);
            if (rule.getLabel() == null) continue;
            if (ctx.fullTextLower.contains(rule.getLabel().toLowerCase(java.util.Locale.ROOT))) {
                ctx.recordHit(field,
                        ExtractionContext.RuleHit.hit(i, "label", rule.getLabel()));
                if ("false".equalsIgnoreCase(rule.getPattern())) return Boolean.FALSE;
                return Boolean.TRUE;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T applyLabelRule(
            final String field,
            final int ruleIndex,
            final PdfTemplateV2.LabelRule rule,
            final ExtractionContext ctx,
            final Pattern fallbackAdjacent,
            final java.util.function.Function<String, T> parser) {
        if (rule == null) return null;
        // Determine the scope (full text vs. after-section). The scope is
        // computed once and reused across the three strategies below. The
        // line index offset is preserved so provenance points back to a real
        // line number in the source document, not the scoped substring.
        final ScopedText scope = scopeFor(rule, ctx);

        // Stacked label-then-value layout (Amex Account Summary, etc.).
        if (rule.getStackedHeader() != null && !rule.getStackedLabels().isEmpty()
                && rule.getStackedIndex() != null) {
            final BigDecimal v = extractStackedDollarValue(
                    scope.lines,
                    rule.getStackedHeader(),
                    rule.getStackedLabels(),
                    rule.getStackedIndex());
            if (v != null) {
                ctx.recordHit(field, ExtractionContext.RuleHit.hit(
                        ruleIndex, "stacked", v.toPlainString()));
                try {
                    return (T) v;
                } catch (final ClassCastException ignored) {
                    return null;
                }
            }
        }
        // Explicit pattern wins next.
        if (rule.getPattern() != null && !rule.getPattern().isBlank()) {
            final Pattern p = RegexCache.compile(rule.getPattern(), Pattern.CASE_INSENSITIVE);
            if (p == null) return null;
            try {
                final Matcher m = RegexCache.safeMatcher(p, scope.text);
                if (m.find() && m.groupCount() >= 1) {
                    final String captured = m.group(1);
                    final T parsed = parser.apply(captured);
                    if (parsed != null) {
                        ctx.recordHit(field, ExtractionContext.RuleHit.hit(
                                ruleIndex, "pattern", captured));
                    }
                    return parsed;
                }
            } catch (final RegexCache.RegexTimeoutException ex) {
                LOGGER.warn("v2 regex timeout: field={} pattern={} reason={}",
                        field, rule.getPattern(), ex.getMessage());
            }
            return null;
        }
        // Label + adjacent strategy.
        if (rule.getLabel() != null && fallbackAdjacent != null) {
            final String label = rule.getLabel();
            final String labelLower = label.toLowerCase(java.util.Locale.ROOT);
            T lastNonZero = null;
            T firstAny = null;
            String capturedForProvenance = null;
            for (int i = 0; i < scope.lines.length; i++) {
                final String line = scope.lines[i];
                if (line.length() > 200) continue;
                final String lineLower = scope.linesLower[i];
                final int labelIdx = lineLower.indexOf(labelLower);
                if (labelIdx < 0) continue;
                if (APR_DISCLOSURE_LINE.matcher(line).find()) continue;
                final String afterLabel = line.substring(labelIdx + label.length());
                T candidate = null;
                String captured = null;
                final Matcher mm = fallbackAdjacent.matcher(afterLabel);
                if (mm.find()) {
                    captured = mm.group(1);
                    candidate = parser.apply(captured);
                } else {
                    for (int j = i + 1; j < Math.min(scope.lines.length, i + 4); j++) {
                        if (scope.lines[j].isBlank()) continue;
                        if (APR_DISCLOSURE_LINE.matcher(scope.lines[j]).find()) break;
                        final Matcher m2 = fallbackAdjacent.matcher(scope.lines[j]);
                        if (m2.find()) {
                            captured = m2.group(1);
                            candidate = parser.apply(captured);
                        }
                        break;
                    }
                }
                if (candidate == null) continue;
                if (firstAny == null) {
                    firstAny = candidate;
                    capturedForProvenance = captured;
                }
                if (candidate instanceof BigDecimal bd) {
                    if (bd.signum() != 0) {
                        lastNonZero = candidate;
                        capturedForProvenance = captured;
                    }
                } else {
                    ctx.recordHit(field, ExtractionContext.RuleHit.hit(
                            ruleIndex, "label", captured));
                    return candidate;
                }
            }
            final T result = lastNonZero != null ? lastNonZero : firstAny;
            if (result != null) {
                ctx.recordHit(field, ExtractionContext.RuleHit.hit(
                        ruleIndex, "label", capturedForProvenance));
            }
            return result;
        }
        return null;
    }

    /** Section-scoped view of the document text. */
    private static final class ScopedText {
        final String text;
        final String[] lines;
        final String[] linesLower;
        ScopedText(final String text, final String[] lines, final String[] linesLower) {
            this.text = text;
            this.lines = lines;
            this.linesLower = linesLower;
        }
    }

    private static ScopedText scopeFor(final PdfTemplateV2.LabelRule rule,
            final ExtractionContext ctx) {
        if (rule.getAfterSection() == null || rule.getAfterSection().isBlank()) {
            return new ScopedText(ctx.fullText, ctx.lines, ctx.linesLower);
        }
        final String needle = rule.getAfterSection().toLowerCase(java.util.Locale.ROOT);
        int startLine = -1;
        for (int i = 0; i < ctx.linesLower.length; i++) {
            if (ctx.linesLower[i].contains(needle)) {
                startLine = i + 1;
                break;
            }
        }
        if (startLine < 0) {
            // Section not found — return empty scope so the rule cannot match.
            return new ScopedText("", new String[0], new String[0]);
        }
        final int len = ctx.lines.length - startLine;
        final String[] scopedLines = new String[len];
        final String[] scopedLower = new String[len];
        System.arraycopy(ctx.lines, startLine, scopedLines, 0, len);
        System.arraycopy(ctx.linesLower, startLine, scopedLower, 0, len);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append('\n');
            sb.append(scopedLines[i]);
        }
        return new ScopedText(sb.toString(), scopedLines, scopedLower);
    }

    /**
     * Scan a stacked label-then-value block and return the dollar value at
     * {@code stackedIndex}. Layout pattern:
     *
     * <pre>
     *   &lt;header&gt;
     *   &lt;label[0]&gt;
     *   &lt;label[1]&gt;
     *   ...
     *   &lt;label[N-1]&gt;
     *   $value[0]
     *   $value[1]
     *   ...
     *   $value[N-1]
     * </pre>
     *
     * Tolerates blank lines between labels and between values. Returns null when
     * the header isn't found, when the labels don't all appear in order, or when
     * fewer than {@code stackedIndex+1} dollar values follow.
     */
    /**
     * String-text entry point preserved for backward compatibility with the
     * existing test surface. Splits and delegates.
     */
    static BigDecimal extractStackedDollarValue(
            final String text,
            final String header,
            final List<String> labels,
            final int stackedIndex) {
        if (text == null) return null;
        return extractStackedDollarValue(text.split("\\r?\\n"), header, labels, stackedIndex);
    }

    /** Pre-split overload — preferred when the caller already has split lines. */
    static BigDecimal extractStackedDollarValue(
            final String[] lines,
            final String header,
            final List<String> labels,
            final int stackedIndex) {
        if (lines == null || header == null || labels == null || labels.isEmpty()) return null;
        if (stackedIndex < 0 || stackedIndex >= labels.size()) return null;
        final Pattern headerPat = RegexCache.compile(
                "(?i)^\\s*" + Pattern.quote(header) + "\\s*$");
        if (headerPat == null) return null;

        for (int i = 0; i < lines.length; i++) {
            if (!headerPat.matcher(lines[i]).find()) continue;
            // Match the label stack.
            int li = 0;
            int j = i + 1;
            while (j < lines.length && li < labels.size()) {
                if (lines[j].isBlank()) { j++; continue; }
                final Pattern p = RegexCache.compile(
                        "(?i)^\\s*" + Pattern.quote(labels.get(li)) + "\\s*$");
                if (p == null) break;
                if (!p.matcher(lines[j]).find()) break;
                li++; j++;
            }
            if (li != labels.size()) continue;
            // Now read N $-values.
            int valueIdx = 0;
            while (j < lines.length && valueIdx <= stackedIndex) {
                if (lines[j].isBlank()) { j++; continue; }
                final Matcher m = STACKED_DOLLAR_VALUE.matcher(lines[j].trim());
                if (!m.find()) break;
                if (valueIdx == stackedIndex) {
                    try {
                        BigDecimal v = new BigDecimal(m.group(2).replace(",", ""));
                        if ("-".equals(m.group(1))) v = v.negate();
                        return v;
                    } catch (final NumberFormatException ignored) {
                        return null;
                    }
                }
                valueIdx++; j++;
            }
        }
        return null;
    }

    private static final Pattern STACKED_DOLLAR_VALUE = Pattern.compile(
            "^\\s*([+-])?\\$([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*$");

    /**
     * Runtime self-check: when the five identity components are all present,
     * verify they sum to {@code newBalance} within 1¢ tolerance. The sign of
     * {@code paymentsAndCreditsTotal} is treated as absolute value (we
     * always subtract it) because issuers disagree on whether to print it
     * signed. A break is logged at WARN so a regression in a YAML rule that
     * causes one component to silently drop produces a noisy log without
     * breaking the import.
     */
    private static void warnIfMathIdentityBroken(
            final PdfTemplateV2Evaluator.MetadataResult r) {
        if (r.newBalance == null || r.previousBalance == null
                || r.paymentsAndCreditsTotal == null || r.purchasesTotal == null) {
            return;
        }
        final BigDecimal fees = r.feesTotal != null ? r.feesTotal : BigDecimal.ZERO;
        final BigDecimal interest = r.interestTotal != null ? r.interestTotal : BigDecimal.ZERO;
        final BigDecimal payments = r.paymentsAndCreditsTotal.abs();
        final BigDecimal computed = r.previousBalance
                .subtract(payments)
                .add(r.purchasesTotal)
                .add(fees)
                .add(interest);
        final BigDecimal delta = computed.subtract(r.newBalance).abs();
        if (delta.compareTo(new BigDecimal("0.01")) > 0) {
            LOGGER.warn(
                    "v2 math-identity break: prev({}) - payments({}) + purchases({}) "
                            + "+ fees({}) + interest({}) = computed({}) vs newBalance({}), delta={}",
                    r.previousBalance, payments, r.purchasesTotal, fees, interest,
                    computed, r.newBalance, delta);
        }
    }

    static BigDecimal parseAmount(final String raw) {
        if (raw == null) return null;
        try {
            String s = raw.replace("$", "").replace(",", "").trim();
            if (s.startsWith("(") && s.endsWith(")")) {
                s = "-" + s.substring(1, s.length() - 1);
            }
            return new BigDecimal(s);
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    static LocalDate parseLooseDate(final String raw) {
        if (raw == null) return null;
        final String[] fmts = {
                "M/d/yyyy", "M/d/yy", "MM/dd/yyyy", "MM/dd/yy", "yyyy-MM-dd"
        };
        for (final String f : fmts) {
            try {
                return LocalDate.parse(raw.trim(), DateTimeFormatter.ofPattern(f));
            } catch (final DateTimeParseException ignored) {
                // try next
            }
        }
        return null;
    }
}
