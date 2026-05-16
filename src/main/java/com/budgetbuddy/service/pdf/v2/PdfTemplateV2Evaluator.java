package com.budgetbuddy.service.pdf.v2;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
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
            try {
                final Matcher m = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
                        .matcher(fullText);
                if (m.find() && m.groupCount() >= 1) {
                    lastFour = extractLastFour(m.group(1));
                    if (lastFour != null) break;
                }
            } catch (final RuntimeException ex) {
                LOGGER.warn(
                        "v2 last-four rule failed: pattern={} reason={}",
                        pattern, ex.getMessage());
            }
            if (Boolean.TRUE.equals(r.getFilenameFallback()) && filename != null) {
                final Matcher fm = Pattern.compile("(?<=\\D)(\\d{4})(?=\\.\\w+$|$)")
                        .matcher(filename);
                if (fm.find()) {
                    lastFour = fm.group(1);
                    break;
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
        public BigDecimal purchasesTotal;
        public BigDecimal paymentsAndCreditsTotal;
        public BigDecimal feesTotal;
        public BigDecimal interestTotal;
        public LocalDate statementDate;
        public LocalDate statementStart;
        public LocalDate statementEnd;
    }

    public MetadataResult evaluateMetadata(final PdfTemplateV2 template, final String fullText) {
        if (template == null || template.getMetadata() == null || fullText == null) {
            return null;
        }
        final PdfTemplateV2.MetadataRules m = template.getMetadata();
        final MetadataResult r = new MetadataResult();

        r.newBalance = firstLabelAmount(m.getNewBalance(), fullText);
        r.previousBalance = firstLabelAmount(m.getPreviousBalance(), fullText);
        r.purchasesTotal = firstLabelAmount(m.getPurchasesTotal(), fullText);
        r.paymentsAndCreditsTotal = firstLabelAmount(m.getPaymentsTotal(), fullText);
        r.feesTotal = firstLabelAmount(m.getFeesTotal(), fullText);
        r.interestTotal = firstLabelAmount(m.getInterestTotal(), fullText);

        r.statementDate = firstLabelDate(m.getStatementDate(), fullText);

        // Statement period uses a 2-capture-group regex: start, end.
        for (final PdfTemplateV2.PeriodRule pr : m.getStatementPeriod()) {
            final String pat = pr.getPattern();
            if (pat == null) continue;
            try {
                final Matcher mm = Pattern.compile(pat, Pattern.CASE_INSENSITIVE).matcher(fullText);
                if (mm.find() && mm.groupCount() >= 2) {
                    r.statementStart = parseLooseDate(mm.group(1));
                    r.statementEnd = parseLooseDate(mm.group(2));
                    break;
                }
            } catch (final RuntimeException ex) {
                LOGGER.warn("v2 statement-period rule failed: {}", ex.getMessage());
            }
        }
        return r;
    }

    // ---- Internals ----

    private static boolean matchesAny(final PdfTemplateV2.RegexRule r, final String text) {
        if (r.getPattern() != null) {
            try {
                if (Pattern.compile(r.getPattern(), Pattern.CASE_INSENSITIVE)
                        .matcher(text).find()) {
                    return true;
                }
            } catch (final RuntimeException ex) {
                LOGGER.warn("v2 regex compile failed: {}", ex.getMessage());
            }
        }
        for (final String alt : r.getAnyOf()) {
            if (alt == null) continue;
            try {
                if (Pattern.compile(alt, Pattern.CASE_INSENSITIVE)
                        .matcher(text).find()) {
                    return true;
                }
            } catch (final RuntimeException ex) {
                LOGGER.warn("v2 anyOf regex compile failed: {}", ex.getMessage());
            }
        }
        return false;
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
            try {
                final Matcher m = Pattern.compile(rule.getPattern()).matcher(fullText);
                if (m.find() && m.groupCount() >= 1) {
                    return m.group(1).trim();
                }
            } catch (final RuntimeException ex) {
                LOGGER.warn("v2 holder regex failed: {}", ex.getMessage());
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
            final List<PdfTemplateV2.LabelRule> rules, final String text) {
        for (final PdfTemplateV2.LabelRule rule : rules) {
            final BigDecimal v = applyLabelRule(rule, text, DOLLAR_AMOUNT, PdfTemplateV2Evaluator::parseAmount);
            if (v != null) return v;
        }
        return null;
    }

    private static LocalDate firstLabelDate(
            final List<PdfTemplateV2.LabelRule> rules, final String text) {
        for (final PdfTemplateV2.LabelRule rule : rules) {
            final LocalDate v = applyLabelRule(rule, text, SLASH_DATE, PdfTemplateV2Evaluator::parseLooseDate);
            if (v != null) return v;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T applyLabelRule(
            final PdfTemplateV2.LabelRule rule,
            final String text,
            final Pattern fallbackAdjacent,
            final java.util.function.Function<String, T> parser) {
        if (rule == null) return null;
        // Stacked label-then-value layout (Amex Account Summary, etc.):
        // find the header, then the labels in order, then read N $-values and
        // return the one at stacked_index. This is the structural pattern Amex
        // uses for its 5-bucket Account Summary block — labels are printed first,
        // then values, with multiple blank lines in between.
        if (rule.getStackedHeader() != null && !rule.getStackedLabels().isEmpty()
                && rule.getStackedIndex() != null) {
            final BigDecimal v = extractStackedDollarValue(
                    text,
                    rule.getStackedHeader(),
                    rule.getStackedLabels(),
                    rule.getStackedIndex());
            if (v != null) {
                // Stacked rules only produce BigDecimal — return null for other types.
                try {
                    return (T) v;
                } catch (final ClassCastException ignored) {
                    return null;
                }
            }
        }
        // Explicit pattern wins next.
        if (rule.getPattern() != null && !rule.getPattern().isBlank()) {
            try {
                final Matcher m = Pattern.compile(rule.getPattern(), Pattern.CASE_INSENSITIVE)
                        .matcher(text);
                if (m.find() && m.groupCount() >= 1) {
                    return parser.apply(m.group(1));
                }
            } catch (final RuntimeException ex) {
                LOGGER.warn("v2 label-rule pattern failed: {}", ex.getMessage());
            }
            return null;
        }
        // Label + adjacent strategy. CRITICAL: the dollar/date scan starts
        // AFTER the label's position on the line, not at the line start. US
        // Bank prints "Minimum Payment Due $213.00 Previous Balance +$21,420.32"
        // — if Previous Balance is the label, scanning the whole line from
        // position 0 would find $213 first (Minimum Payment Due's value) before
        // $21,420.32. We slice the line at the label's end and scan from there.
        //
        // APR-disclosure rows are skipped explicitly to avoid picking an APR
        // percent as a dollar total.
        //
        // For amount fields, we prefer the LAST non-zero match across the
        // document: Amex prints an all-zero placeholder block before the real
        // Account Total, so first-match would pick the placeholder.
        if (rule.getLabel() != null && fallbackAdjacent != null) {
            final String label = rule.getLabel();
            final String labelLower = label.toLowerCase(java.util.Locale.ROOT);
            final String[] lines = text.split("\\r?\\n");
            T lastNonZero = null;
            T firstAny = null;
            for (int i = 0; i < lines.length; i++) {
                final String line = lines[i];
                // Skip prose paragraphs that happen to contain the label as a
                // substring. Real statement-summary rows are short (typically
                // <120 chars). Longer lines are almost always disclosure prose
                // ("...paid your previous balance in full by the Payment Due
                // Date...") that would otherwise produce false-positive matches.
                if (line.length() > 200) continue;
                final String lineLower = line.toLowerCase(java.util.Locale.ROOT);
                final int labelIdx = lineLower.indexOf(labelLower);
                if (labelIdx < 0) continue;
                if (APR_DISCLOSURE_LINE.matcher(line).find()) continue;
                final String afterLabel = line.substring(labelIdx + label.length());
                T candidate = null;
                final Matcher m = fallbackAdjacent.matcher(afterLabel);
                if (m.find()) {
                    candidate = parser.apply(m.group(1));
                } else {
                    for (int j = i + 1; j < Math.min(lines.length, i + 4); j++) {
                        if (lines[j].isBlank()) continue;
                        if (APR_DISCLOSURE_LINE.matcher(lines[j]).find()) break;
                        final Matcher m2 = fallbackAdjacent.matcher(lines[j]);
                        if (m2.find()) {
                            candidate = parser.apply(m2.group(1));
                        }
                        break;
                    }
                }
                if (candidate == null) continue;
                if (firstAny == null) firstAny = candidate;
                if (candidate instanceof BigDecimal bd) {
                    if (bd.signum() != 0) lastNonZero = candidate;
                } else {
                    return candidate;
                }
            }
            return lastNonZero != null ? lastNonZero : firstAny;
        }
        return null;
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
    static BigDecimal extractStackedDollarValue(
            final String text,
            final String header,
            final List<String> labels,
            final int stackedIndex) {
        if (text == null || header == null || labels == null || labels.isEmpty()) return null;
        if (stackedIndex < 0 || stackedIndex >= labels.size()) return null;
        final String[] lines = text.split("\\r?\\n");
        final Pattern headerPat = Pattern.compile("(?i)^\\s*" + Pattern.quote(header) + "\\s*$");

        for (int i = 0; i < lines.length; i++) {
            if (!headerPat.matcher(lines[i]).find()) continue;
            // Match the label stack.
            int li = 0;
            int j = i + 1;
            while (j < lines.length && li < labels.size()) {
                if (lines[j].isBlank()) { j++; continue; }
                final Pattern p = Pattern.compile(
                        "(?i)^\\s*" + Pattern.quote(labels.get(li)) + "\\s*$");
                if (!p.matcher(lines[j]).find()) break;
                li++; j++;
            }
            if (li != labels.size()) continue;
            // Now read N $-values.
            final Pattern dollar =
                    Pattern.compile("^\\s*([+-])?\\$([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*$");
            int valueIdx = 0;
            while (j < lines.length && valueIdx <= stackedIndex) {
                if (lines[j].isBlank()) { j++; continue; }
                final Matcher m = dollar.matcher(lines[j].trim());
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
