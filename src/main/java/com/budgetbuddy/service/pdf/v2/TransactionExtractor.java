package com.budgetbuddy.service.pdf.v2;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * YAML-driven transaction-shape extractor.
 *
 * <p>Walks the document line by line. For each non-blank line, tries every
 * shape in order; the first that matches consumes its lines and emits an
 * {@link ExtractedTransaction}. Shapes can be:
 *
 * <ul>
 *   <li><b>single-line</b> ({@code line_regex}): one transaction per matched
 *       line, with named captures {@code date}, {@code description},
 *       {@code amount}.</li>
 *   <li><b>multi-line</b> ({@code start_regex} + {@code end_regex}): a
 *       transaction starts where {@code start_regex} matches (captures date
 *       + description), and ends at the next line within {@code max_lines}
 *       where {@code end_regex} matches (captures amount). Description from
 *       intermediate lines is concatenated.</li>
 * </ul>
 *
 * <p>FX-block lines (Amex foreign-tx info) are stripped per-shape via
 * {@code strip_lines_matching} before any matching happens.
 *
 * <p>This evaluator is intentionally standalone: it doesn't write into
 * {@code PDFImportService.ImportResult}. Integration is a separate task — the
 * existing legacy multi-line code path is currently load-bearing for corpus
 * reconciliation, and switching the production parser to the v2 extractor
 * has to wait until every issuer's transactions are covered by YAML shapes
 * with full coverage at parity. For now this primitive lets new YAML shapes
 * be authored + unit-tested without touching production.
 */
public final class TransactionExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionExtractor.class);
    private static final int DEFAULT_MAX_LINES = 5;
    /** Multi-line scans hop at most this far to find the end line. */
    private static final int HARD_MAX_LINES = 12;

    /**
     * APR-disclosure lines look like transactions (date, percent, dollar
     * amounts) but are actually fee-rate disclosures. Skip them everywhere
     * to prevent false-positive transaction matches like
     * "PURCHASES 18.49% variable $0.00 30 $0.00 $13,696.35".
     */
    private static final Pattern APR_DISCLOSURE_LINE = Pattern.compile(
            "(?i)\\b\\d{1,2}\\.\\d{1,3}\\s*%\\s*(?:\\(?v\\)?|variable)");

    /**
     * Sign conventions a shape can declare.
     *
     * <ul>
     *   <li>{@link #CREDIT_CARD_PURCHASE}: positive amount in PDF means a
     *       PURCHASE / debit on a credit-card account. The amount on the
     *       extracted transaction is negated; FlowDirection.DEBIT.</li>
     *   <li>{@link #CREDIT_CARD_CREDIT}: positive amount in PDF means a
     *       CREDIT — refund, payment received, adjustment in the
     *       cardholder's favour. The amount stays positive;
     *       FlowDirection.CREDIT.</li>
     *   <li>{@link #PRESERVE}: amount stays exactly as parsed (PDF sign).
     *       FlowDirection derived from sign.</li>
     * </ul>
     *
     * <p>The legacy {@code "credit-positive"} string from v1 layouts maps to
     * {@link #CREDIT_CARD_PURCHASE} for backward compatibility.
     */
    public enum SignConvention { CREDIT_CARD_PURCHASE, CREDIT_CARD_CREDIT, PRESERVE }

    /**
     * UNKNOWN means the YAML shape did not opt into a credit-card sign
     * convention (sign_convention: preserve or unset). The cutover layer
     * is expected to fall back to description-based payment detection in
     * that case. DEBIT / CREDIT are explicit opt-ins; the cutover trusts
     * them as-is.
     */
    public enum Direction { DEBIT, CREDIT, UNKNOWN }

    public static final class ExtractedTransaction {
        public final LocalDate date;
        public final String description;
        public final BigDecimal amount;
        public final Direction direction;
        public final String shapeName;
        /**
         * Cardholder name when the source PDF carries a per-cardholder section
         * header (Amex family card "MUDIT AGARWAL / Card Ending 1-21010") and a
         * {@code card_holders} anchor in the YAML matches it. Null on
         * single-cardholder statements.
         */
        public final String userName;
        /**
         * Last 4 digits of the card identifier from the cardholder section
         * header. Null when the matching anchor only captures a name, or when
         * the statement is single-cardholder.
         */
        public final String cardLastFour;

        /**
         * Original foreign-currency amount on a foreign-transaction row, when
         * the matched shape's {@code fx_lines} captured one. Null on the
         * common non-FX case.
         */
        public BigDecimal fxOriginalAmount;
        /**
         * Original-currency display name or ISO code, captured from the FX
         * detail block. Null on the common non-FX case.
         */
        public String fxOriginalCurrency;
        /**
         * Exchange rate captured from the FX detail block. Null on the common
         * non-FX case.
         */
        public BigDecimal fxRate;

        public ExtractedTransaction(final LocalDate date, final String description,
                final BigDecimal amount, final Direction direction, final String shapeName,
                final String userName, final String cardLastFour) {
            this.date = date;
            this.description = description;
            this.amount = amount;
            this.direction = direction;
            this.shapeName = shapeName;
            this.userName = userName;
            this.cardLastFour = cardLastFour;
            this.fxOriginalAmount = null;
            this.fxOriginalCurrency = null;
            this.fxRate = null;
        }

        public ExtractedTransaction(final LocalDate date, final String description,
                final BigDecimal amount, final Direction direction, final String shapeName) {
            this(date, description, amount, direction, shapeName, null, null);
        }

        // Backward-compat constructor for existing tests that don't care about
        // direction. Inferred from the amount sign.
        public ExtractedTransaction(final LocalDate date, final String description,
                final BigDecimal amount, final String shapeName) {
            this(date, description, amount,
                    amount != null && amount.signum() < 0 ? Direction.DEBIT : Direction.CREDIT,
                    shapeName, null, null);
        }

        @Override public String toString() {
            return "tx{" + date + " " + description + " " + amount
                    + " " + direction + " <" + shapeName + ">"
                    + (userName != null ? " user=" + userName : "")
                    + (cardLastFour != null ? " card=" + cardLastFour : "")
                    + (fxOriginalAmount != null || fxOriginalCurrency != null || fxRate != null
                            ? " fx=" + fxOriginalAmount + " " + fxOriginalCurrency
                                    + " @ " + fxRate : "")
                    + "}";
        }
    }

    private static SignConvention parseSignConvention(final String raw) {
        // Default is PRESERVE — the extractor returns the parsed amount as-is.
        // Shapes that want explicit credit-card semantics opt in via
        // sign_convention: credit-card-purchase (purchases → DEBIT) or
        // sign_convention: credit-card-credit (refunds / payments → CREDIT).
        // The legacy v1 "credit-positive" string maps to CREDIT_CARD_PURCHASE
        // for compatibility with that schema.
        if (raw == null || raw.isBlank()) return SignConvention.PRESERVE;
        final String s = raw.toLowerCase(java.util.Locale.ROOT).trim();
        switch (s) {
            case "credit-positive":
            case "credit-card-purchase":
            case "purchase":
                return SignConvention.CREDIT_CARD_PURCHASE;
            case "credit-card-credit":
            case "credit":
            case "refund":
                return SignConvention.CREDIT_CARD_CREDIT;
            case "preserve":
            case "raw":
                return SignConvention.PRESERVE;
            default:
                LOGGER.warn("Unknown sign_convention '{}' — defaulting to preserve", raw);
                return SignConvention.PRESERVE;
        }
    }

    private static ExtractedTransaction applySign(final LocalDate date, final String desc,
            final BigDecimal rawAmount, final SignConvention conv, final String shapeName,
            final String userName, final String cardLastFour) {
        if (rawAmount == null) return null;
        final BigDecimal pdfAmt = rawAmount.abs();
        switch (conv) {
            case CREDIT_CARD_CREDIT:
                return new ExtractedTransaction(date, desc, pdfAmt, Direction.CREDIT, shapeName,
                        userName, cardLastFour);
            case PRESERVE:
                // Unannotated shape — leave amount and direction to the
                // downstream cutover (which applies description-based payment
                // detection). Direction.UNKNOWN signals "no opinion here".
                return new ExtractedTransaction(date, desc, rawAmount, Direction.UNKNOWN, shapeName,
                        userName, cardLastFour);
            case CREDIT_CARD_PURCHASE:
            default:
                return new ExtractedTransaction(date, desc, pdfAmt.negate(),
                        Direction.DEBIT, shapeName, userName, cardLastFour);
        }
    }

    public List<ExtractedTransaction> extract(final PdfTemplateV2 template, final String text) {
        return extract(template, text, null);
    }

    /**
     * Extract with an explicit fallback year for dates that don't carry one
     * in the source (most credit-card statements print MM/DD only). Without
     * this the parser falls back to year 2000 — which is correct in tests
     * but wrong in production. PDFImportService infers the statement year
     * from filename/closing-date and passes it here.
     */
    public List<ExtractedTransaction> extract(
            final PdfTemplateV2 template, final String text, final Integer inferredYear) {
        if (template == null || template.getTransactions().isEmpty() || text == null) {
            return List.of();
        }
        final ExtractionContext ctx = new ExtractionContext(text);
        return extract(template, ctx, inferredYear);
    }

    public List<ExtractedTransaction> extract(
            final PdfTemplateV2 template, final ExtractionContext ctx) {
        return extract(template, ctx, null);
    }

    public List<ExtractedTransaction> extract(
            final PdfTemplateV2 template, final ExtractionContext ctx, final Integer inferredYear) {
        final List<ExtractedTransaction> out = new ArrayList<>();
        if (template == null || ctx == null || template.getTransactions().isEmpty()) {
            return out;
        }
        // Compute per-shape strip filters once (outside the per-line loop).
        final List<List<Pattern>> stripPerShape = new ArrayList<>();
        // Pre-compile fx_lines patterns per shape. Compiled with
        // CASE_INSENSITIVE so YAML authors can omit the (?i) prefix when the
        // PDF emits the currency word in either case ("Japanese Yen" vs
        // "JAPANESE YEN"). Empty list when the shape has no fx_lines.
        final List<List<Pattern>> fxLinesPerShape = new ArrayList<>();
        for (final PdfTemplateV2.TransactionShape shape : template.getTransactions()) {
            final List<Pattern> stripList = new ArrayList<>();
            for (final String s : shape.getStripLinesMatching()) {
                final Pattern sp = RegexCache.compile(s, Pattern.CASE_INSENSITIVE);
                if (sp != null) stripList.add(sp);
            }
            stripPerShape.add(stripList);
            final List<Pattern> fxList = new ArrayList<>();
            for (final String s : shape.getFxLines()) {
                final Pattern fp = RegexCache.compile(s, Pattern.CASE_INSENSITIVE);
                if (fp != null) fxList.add(fp);
            }
            fxLinesPerShape.add(fxList);
        }
        // Aggregate FX patterns across ALL shapes so we can scan after any
        // matched tx — a tx matched by shape A may be followed by FX detail
        // lines defined in shape B (e.g. one shape per section, but the FX
        // format is the same per issuer).
        final List<Pattern> allFxPatterns = new ArrayList<>();
        for (final List<Pattern> ps : fxLinesPerShape) {
            allFxPatterns.addAll(ps);
        }
        // section_anchor support: track the most recently-seen section header
        // (case-insensitive substring match) so a shape with section_anchor
        // set only fires when that header is "above" the current line. Section
        // membership is reset every time we cross another section header that
        // a different shape is anchored to. This is how WF distinguishes the
        // "Other Credits" refund section from the "Purchases" section — same
        // line shape, but the section dictates sign convention.
        final java.util.Set<String> sectionAnchors = new java.util.LinkedHashSet<>();
        for (final PdfTemplateV2.TransactionShape s : template.getTransactions()) {
            if (s.getSectionAnchor() != null && !s.getSectionAnchor().isBlank()) {
                sectionAnchors.add(s.getSectionAnchor());
            }
        }
        // Pre-compile every card_holders anchor pattern once. The extractor
        // walks these in YAML order against each non-blank line; the FIRST
        // matching anchor "sticks" — its captured user_name + card_last_four
        // become the per-transaction context until the next anchor matches.
        // Anchors that need to support both a two-line "name then card-ending"
        // form (Amex authorized-user section: "MUDIT AGARWAL\nCard Ending
        // 1-21010") and a single-line "name + ending" form (Amex primary
        // continuation: "AGARWAL SUMIT KUMAR Account Ending 1-21002") are
        // expressed as TWO anchors — one carries only user_name (name_only:
        // true) and primes the current name, the other captures both fields.
        final List<CompiledCardHolder> compiledHolders = new ArrayList<>();
        for (final PdfTemplateV2.CardHolderAnchor a : template.getCardHolders()) {
            if (a == null || a.getPattern() == null || a.getPattern().isBlank()) continue;
            final Pattern p = RegexCache.compile(a.getPattern());
            if (p == null) continue;
            Pattern namePat = null;
            if (a.getNamePattern() != null && !a.getNamePattern().isBlank()) {
                namePat = RegexCache.compile(a.getNamePattern());
            }
            compiledHolders.add(new CompiledCardHolder(p, a, namePat));
        }
        String currentSection = null;
        String currentUser = null;
        String currentCardLast4 = null;
        int i = 0;
        while (i < ctx.lines.length) {
            if (ctx.lines[i].isBlank()) { i++; continue; }
            // Update section tracking before evaluating shapes — a line whose
            // text matches a known section anchor BECOMES the current section.
            for (final String anchor : sectionAnchors) {
                if (ctx.linesLower[i].contains(anchor.toLowerCase(java.util.Locale.ROOT))) {
                    currentSection = anchor;
                    break;
                }
            }
            // Cardholder anchor tracking: family-card / authorized-user PDFs
            // print a per-section header naming the cardholder + their card
            // last-four. When any compiled anchor matches the current line we
            // set the local currentUser / currentCardLast4 context and skip
            // shape evaluation for this line (an anchor line is NEVER itself
            // a transaction). Updates are sticky — they persist until the
            // next matching anchor line.
            if (!compiledHolders.isEmpty()) {
                boolean anchorMatched = false;
                for (final CompiledCardHolder h : compiledHolders) {
                    final Matcher hm = h.pattern.matcher(ctx.lines[i]);
                    if (!hm.find()) continue;
                    String name = extractAnchorGroup(hm, h.anchor.getUserNameGroup(),
                            "userName");
                    final String last4 = extractAnchorGroup(hm, h.anchor.getCardLastFourGroup(),
                            "cardLastFour");
                    // name_from_prev_line: the cardholder name lives on the
                    // previous non-blank line, not in the matched line itself.
                    // Used for Amex's two-line authorized-user header.
                    if (h.anchor.isNameFromPrevLine() && (name == null || name.isBlank())) {
                        name = lookupPrevLineName(ctx, i, h.prevLineNamePattern);
                    }
                    if (name != null && !name.isBlank()) {
                        currentUser = normalizeName(name);
                    }
                    if (last4 != null && !last4.isBlank()) {
                        currentCardLast4 = last4.trim();
                    }
                    anchorMatched = true;
                    break;
                }
                if (anchorMatched) {
                    i++;
                    continue;
                }
            }
            // Skip APR-disclosure rows globally — they look like a date+amount
            // transaction but are fee-rate disclosures. Without this skip,
            // shapes that use a date-then-amount regex pull "PURCHASES 18.49%
            // variable $0.00 ... $13,696.35" as a bogus transaction.
            if (APR_DISCLOSURE_LINE.matcher(ctx.lines[i]).find()) {
                i++;
                continue;
            }
            int consumed = 0;
            ExtractedTransaction match = null;
            SignConvention matchedConv = SignConvention.CREDIT_CARD_PURCHASE;
            for (int s = 0; s < template.getTransactions().size(); s++) {
                final PdfTemplateV2.TransactionShape shape = template.getTransactions().get(s);
                // section_anchor gating: a shape with section_anchor only fires
                // when the most recently-seen header matches. Shapes without
                // section_anchor are unscoped and always considered.
                if (shape.getSectionAnchor() != null
                        && !shape.getSectionAnchor().equals(currentSection)) {
                    continue;
                }
                if (lineIsStripped(stripPerShape.get(s), ctx.lines[i])) {
                    continue;
                }
                final ConsumeResult attempt = trySingleLine(shape, ctx, i, inferredYear);
                if (attempt != null) {
                    match = attempt.tx;
                    matchedConv = parseSignConvention(shape.getSignConvention());
                    consumed = attempt.consumed;
                    break;
                }
                final ConsumeResult attempt2 = tryMultiLine(shape, ctx, i, stripPerShape.get(s), inferredYear);
                if (attempt2 != null) {
                    match = attempt2.tx;
                    matchedConv = parseSignConvention(shape.getSignConvention());
                    consumed = attempt2.consumed;
                    break;
                }
            }
            if (match != null) {
                final ExtractedTransaction signed = applySign(
                        match.date, match.description, match.amount, matchedConv, match.shapeName,
                        currentUser, currentCardLast4);
                if (signed != null) {
                    // Carry over any FX captures that the shape's own
                    // line_regex / end_regex captured into the intermediate
                    // `match` object. applySign builds a fresh
                    // ExtractedTransaction so we have to copy these by hand.
                    signed.fxOriginalAmount = match.fxOriginalAmount;
                    signed.fxOriginalCurrency = match.fxOriginalCurrency;
                    signed.fxRate = match.fxRate;
                    // FX-detail capture. Scan the next 1-5 lines for matches
                    // against any shape's fx_lines patterns. Each captured
                    // field (fx_orig_amount / fx_orig_code / fx_rate) attaches
                    // to the just-extracted transaction. Lines that successfully
                    // capture are "consumed" so the outer walk skips past them
                    // (otherwise they could be re-parsed as bogus transactions
                    // — Chase's "06/04 SWISS FRANC" FX-header line matches the
                    // generic chase-purchase regex). Scanning stops at the next
                    // transaction-line match or after 5 lines of no match.
                    if (!allFxPatterns.isEmpty()) {
                        final int fxConsumed = scanFxLines(
                                signed, ctx, i + Math.max(1, consumed), allFxPatterns,
                                template, inferredYear);
                        consumed = Math.max(consumed, 0) + fxConsumed;
                    }
                    out.add(signed);
                }
                i += Math.max(1, consumed);
            } else {
                i++;
            }
        }
        return out;
    }

    /**
     * Scan up to 5 lines starting at {@code startIdx} for FX-detail matches.
     * For each line, try each pattern; the first one to match contributes its
     * named groups (fx_orig_amount, fx_orig_code, fx_rate) to {@code tx}.
     * Returns the count of consecutive lines consumed (so the outer walk can
     * skip them). Stops early if:
     *  - the line itself looks like a new transaction (any shape's line_regex
     *    or start_regex matches); we do NOT eat that line
     *  - 5 lines scanned with NO FX match (return whatever was consumed so far)
     */
    private static int scanFxLines(
            final ExtractedTransaction tx, final ExtractionContext ctx, final int startIdx,
            final List<Pattern> fxPatterns, final PdfTemplateV2 template,
            final Integer inferredYear) {
        int consumed = 0;
        int blankSkipped = 0;
        for (int j = startIdx; j < ctx.lines.length && (j - startIdx) < 5 + blankSkipped; j++) {
            if (ctx.lines[j].isBlank()) { blankSkipped++; continue; }
            // If this line opens a new transaction in ANY shape, halt — that
            // line belongs to the next tx, not the FX block of this one.
            if (lineLooksLikeNewTx(template, ctx, j, inferredYear)) {
                break;
            }
            boolean matched = false;
            for (final Pattern p : fxPatterns) {
                final Matcher m = p.matcher(ctx.lines[j]);
                if (!m.find()) continue;
                applyFxCaptures(tx, m);
                matched = true;
                break;
            }
            if (matched) {
                consumed++;
            } else {
                // No FX match on this line — stop scanning. FX detail blocks
                // are always contiguous; a gap means we've left the block.
                break;
            }
        }
        return consumed;
    }

    /** Check whether the line at {@code idx} matches any shape's
     *  {@code line_regex} or {@code start_regex} — i.e. would be picked up as
     *  a new transaction by the outer walk. Used by FX scanning to know
     *  when to halt. */
    private static boolean lineLooksLikeNewTx(final PdfTemplateV2 template,
            final ExtractionContext ctx, final int idx, final Integer inferredYear) {
        for (final PdfTemplateV2.TransactionShape shape : template.getTransactions()) {
            if (shape.getLineRegex() != null) {
                final Pattern p = RegexCache.compile(shape.getLineRegex());
                if (p != null && p.matcher(ctx.lines[idx]).find()) {
                    // Require a parsable date — empty/invalid date groups
                    // mean it's not actually a tx (just a similar shape).
                    final Matcher m = p.matcher(ctx.lines[idx]);
                    if (m.find()) {
                        try {
                            final LocalDate d = parseDate(
                                    m.group("date"), shape.getDateFormat(), inferredYear);
                            if (d != null) return true;
                        } catch (final IllegalArgumentException ignored) { /* not a tx */ }
                    }
                }
            }
            if (shape.getStartRegex() != null) {
                final Pattern p = RegexCache.compile(shape.getStartRegex());
                if (p != null && p.matcher(ctx.lines[idx]).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Pull fx_orig_amount / fx_orig_code / fx_rate from a matched matcher and
     *  attach to the transaction. Missing groups are ignored — different
     *  patterns capture different subsets of the FX fields. Existing values
     *  are NOT overwritten by null, so the first non-null wins per field. */
    private static void applyFxCaptures(final ExtractedTransaction tx, final Matcher m) {
        final String origAmount = safeGroup(m, "fxOrigAmount");
        if (origAmount != null && !origAmount.isBlank() && tx.fxOriginalAmount == null) {
            final BigDecimal parsed = parseAmount(origAmount);
            if (parsed != null) tx.fxOriginalAmount = parsed;
        }
        final String origCode = safeGroup(m, "fxOrigCode");
        if (origCode != null && !origCode.isBlank() && tx.fxOriginalCurrency == null) {
            tx.fxOriginalCurrency = origCode.trim();
        }
        final String rate = safeGroup(m, "fxRate");
        if (rate != null && !rate.isBlank() && tx.fxRate == null) {
            final BigDecimal parsed = parseAmount(rate);
            if (parsed != null) tx.fxRate = parsed;
        }
    }

    private static final class CompiledCardHolder {
        final Pattern pattern;
        final PdfTemplateV2.CardHolderAnchor anchor;
        final Pattern prevLineNamePattern;
        CompiledCardHolder(final Pattern p, final PdfTemplateV2.CardHolderAnchor a,
                final Pattern prevNamePat) {
            this.pattern = p;
            this.anchor = a;
            this.prevLineNamePattern = prevNamePat;
        }
    }

    /**
     * All-caps "FIRST LAST" / "LAST FIRST MIDDLE" default name shape used when
     * a cardholder anchor declares {@code name_from_prev_line} without an
     * explicit {@code name_pattern}. Matches 2-4 ALL-CAPS tokens — the
     * cardholder section header on every Amex family-card statement in the
     * corpus.
     */
    private static final Pattern DEFAULT_PREV_NAME_PATTERN = Pattern.compile(
            "^\\s*(?<userName>[A-Z][A-Z]+(?:\\s+[A-Z][A-Z]+){1,4})\\s*$");

    private static String lookupPrevLineName(final ExtractionContext ctx, final int idx,
            final Pattern overridePat) {
        for (int j = idx - 1; j >= 0 && j >= idx - 4; j--) {
            if (ctx.lines[j] == null || ctx.lines[j].isBlank()) continue;
            final Pattern use = overridePat != null ? overridePat : DEFAULT_PREV_NAME_PATTERN;
            final Matcher m = use.matcher(ctx.lines[j]);
            if (!m.matches()) {
                // First non-blank line above didn't match — don't peek further
                // (we'd risk pulling an unrelated name from earlier in the page).
                return null;
            }
            try {
                final String named = m.group("userName");
                if (named != null && !named.isBlank()) return named;
            } catch (final IllegalArgumentException ignored) { /* no named group */ }
            if (m.groupCount() >= 1) {
                final String g = m.group(1);
                if (g != null && !g.isBlank()) return g;
            }
            return ctx.lines[j].trim();
        }
        return null;
    }

    /**
     * Pull a captured group from a cardholder-anchor match. Prefers the named
     * group ({@code user_name} / {@code card_last_four}); falls back to the
     * indexed group when the YAML uses the indexed style instead.
     */
    private static String extractAnchorGroup(final Matcher m, final Integer indexOverride,
            final String namedGroup) {
        // Try named-capture form first (lets YAML authors avoid index counting).
        try {
            final String v = m.group(namedGroup);
            if (v != null) return v;
        } catch (final IllegalArgumentException ignored) { /* not a named group */ }
        if (indexOverride != null && indexOverride >= 0 && indexOverride <= m.groupCount()) {
            try {
                return m.group(indexOverride);
            } catch (final IndexOutOfBoundsException ignored) { /* out of range */ }
        }
        return null;
    }

    /**
     * Collapse PDF-extracted name whitespace: "AGARWAL  SUMIT KUMAR " →
     * "AGARWAL SUMIT KUMAR". Keeps the case the PDF prints so downstream
     * consumers see the same name the source uses.
     */
    private static String normalizeName(final String raw) {
        if (raw == null) return null;
        final String collapsed = raw.replaceAll("\\s+", " ").trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    private static boolean lineIsStripped(final List<Pattern> stripPats, final String line) {
        for (final Pattern p : stripPats) {
            if (p.matcher(line).find()) return true;
        }
        return false;
    }

    private static final class ConsumeResult {
        final ExtractedTransaction tx;
        final int consumed;
        ConsumeResult(final ExtractedTransaction tx, final int consumed) {
            this.tx = tx;
            this.consumed = consumed;
        }
    }

    private static ConsumeResult trySingleLine(
            final PdfTemplateV2.TransactionShape shape,
            final ExtractionContext ctx, final int lineIdx, final Integer inferredYear) {
        if (shape.getLineRegex() == null) return null;
        final Pattern p = RegexCache.compile(shape.getLineRegex());
        if (p == null) return null;
        final Matcher m = p.matcher(ctx.lines[lineIdx]);
        if (!m.find()) return null;
        try {
            final LocalDate d = parseDate(m.group("date"), shape.getDateFormat(), inferredYear);
            final String desc = safeGroup(m, "description");
            final BigDecimal amt = parseAmount(safeGroup(m, "amount"));
            if (d == null || amt == null) return null;
            if (shape.getMinAmount() != null
                    && amt.abs().compareTo(shape.getMinAmount()) < 0) return null;
            final ExtractedTransaction tx = new ExtractedTransaction(d, desc, amt, shape.getName());
            // FX capture from the tx's own line_regex. When the issuer's FX
            // info is glued into the parent line by the stitch pass (Amex:
            // "...MISC/SPECIALTY RETAIL 637 Japanese Yen $3.99 ⧫") the YAML
            // can name fx_orig_amount / fx_orig_code / fx_rate groups in
            // line_regex itself and we lift them here, in addition to (or
            // instead of) the post-line fx_lines scan.
            applyFxCaptures(tx, m);
            return new ConsumeResult(tx, 1);
        } catch (final IllegalArgumentException ex) {
            return null;
        }
    }

    private static ConsumeResult tryMultiLine(
            final PdfTemplateV2.TransactionShape shape,
            final ExtractionContext ctx, final int startIdx,
            final List<Pattern> stripPats, final Integer inferredYear) {
        if (shape.getStartRegex() == null || shape.getEndRegex() == null) return null;
        final Pattern start = RegexCache.compile(shape.getStartRegex());
        final Pattern end = RegexCache.compile(shape.getEndRegex());
        if (start == null || end == null) return null;
        final Matcher startM = start.matcher(ctx.lines[startIdx]);
        if (!startM.find()) return null;
        LocalDate date;
        String desc;
        try {
            date = parseDate(startM.group("date"), shape.getDateFormat(), inferredYear);
            desc = safeGroup(startM, "description");
        } catch (final IllegalArgumentException ex) {
            return null;
        }
        if (date == null) return null;
        final int maxScan = Math.min(
                ctx.lines.length,
                startIdx + 1 + Math.min(
                        HARD_MAX_LINES,
                        shape.getMaxLines() == null ? DEFAULT_MAX_LINES : shape.getMaxLines()));
        final StringBuilder descBuilder = new StringBuilder(desc == null ? "" : desc);
        for (int j = startIdx + 1; j < maxScan; j++) {
            if (ctx.lines[j].isBlank()) continue;
            if (lineIsStripped(stripPats, ctx.lines[j])) continue;
            final Matcher endM = end.matcher(ctx.lines[j]);
            if (endM.find()) {
                try {
                    final BigDecimal amt = parseAmount(safeGroup(endM, "amount"));
                    if (amt == null) continue;
                    if (shape.getMinAmount() != null
                            && amt.abs().compareTo(shape.getMinAmount()) < 0) return null;
                    return new ConsumeResult(
                            new ExtractedTransaction(date, descBuilder.toString().trim(), amt,
                                    shape.getName()),
                            j - startIdx + 1);
                } catch (final IllegalArgumentException ex) {
                    continue;
                }
            }
            // Intermediate line: append to description.
            if (descBuilder.length() > 0) descBuilder.append(' ');
            descBuilder.append(ctx.lines[j].trim());
        }
        return null;
    }

    private static String safeGroup(final Matcher m, final String name) {
        try {
            return m.group(name);
        } catch (final IllegalArgumentException ex) {
            return null;
        }
    }

    /** Backward-compatible overload; falls back to 2000 when no year supplied. */
    static LocalDate parseDate(final String raw, final String format) {
        return parseDate(raw, format, null);
    }

    /**
     * Parse a date string. When the source carries only MM/DD (no year),
     * the {@code inferredYear} parameter is used as the year — this is the
     * statement year inferred by PDFImportService from filename + closing
     * date. Without inferredYear we fall back to 2000 (only relevant in
     * unit tests; production always passes the inferred year).
     */
    static LocalDate parseDate(final String raw, final String format, final Integer inferredYear) {
        if (raw == null) return null;
        final String[] fmts;
        if (format != null && !format.isBlank()) {
            fmts = new String[] { format, "M/d/yyyy", "M/d/yy", "yyyy-MM-dd" };
        } else {
            fmts = new String[] { "M/d/yyyy", "M/d/yy", "MM/dd/yyyy", "MM/dd/yy", "yyyy-MM-dd" };
        }
        for (final String f : fmts) {
            try {
                return LocalDate.parse(raw.trim(), DateTimeFormatter.ofPattern(f));
            } catch (final DateTimeParseException ignored) { /* try next */ }
        }
        // Date with no year — graft the inferred statement year (or 2000
        // as test fallback) and re-parse as M/d/yyyy.
        final int fallbackYear = inferredYear == null ? 2000 : inferredYear;
        try {
            return LocalDate.parse(
                    raw.trim() + "/" + fallbackYear, DateTimeFormatter.ofPattern("M/d/yyyy"));
        } catch (final DateTimeParseException ignored) {
            LOGGER.debug("v2 tx date parse failed: '{}'", raw);
            return null;
        }
    }

    private static BigDecimal parseAmount(final String raw) {
        if (raw == null) return null;
        try {
            String s = raw.replace("$", "").replace(",", "").trim();
            if (s.startsWith("(") && s.endsWith(")")) {
                s = "-" + s.substring(1, s.length() - 1);
            }
            return new BigDecimal(s);
        } catch (final NumberFormatException ex) {
            return null;
        }
    }
}
