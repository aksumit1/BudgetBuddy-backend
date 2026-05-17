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

    public static final class ExtractedTransaction {
        public final LocalDate date;
        public final String description;
        public final BigDecimal amount;
        public final String shapeName;

        public ExtractedTransaction(final LocalDate date, final String description,
                final BigDecimal amount, final String shapeName) {
            this.date = date;
            this.description = description;
            this.amount = amount;
            this.shapeName = shapeName;
        }

        @Override public String toString() {
            return "tx{" + date + " " + description + " " + amount + " <" + shapeName + ">}";
        }
    }

    public List<ExtractedTransaction> extract(final PdfTemplateV2 template, final String text) {
        if (template == null || template.getTransactions().isEmpty() || text == null) {
            return List.of();
        }
        final ExtractionContext ctx = new ExtractionContext(text);
        return extract(template, ctx);
    }

    public List<ExtractedTransaction> extract(
            final PdfTemplateV2 template, final ExtractionContext ctx) {
        final List<ExtractedTransaction> out = new ArrayList<>();
        if (template == null || ctx == null || template.getTransactions().isEmpty()) {
            return out;
        }
        // Compute per-shape strip filters once (outside the per-line loop).
        final List<List<Pattern>> stripPerShape = new ArrayList<>();
        for (final PdfTemplateV2.TransactionShape shape : template.getTransactions()) {
            final List<Pattern> stripList = new ArrayList<>();
            for (final String s : shape.getStripLinesMatching()) {
                final Pattern sp = RegexCache.compile(s, Pattern.CASE_INSENSITIVE);
                if (sp != null) stripList.add(sp);
            }
            stripPerShape.add(stripList);
        }
        int i = 0;
        while (i < ctx.lines.length) {
            if (ctx.lines[i].isBlank()) { i++; continue; }
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
            String matchedShapeName = null;
            for (int s = 0; s < template.getTransactions().size(); s++) {
                final PdfTemplateV2.TransactionShape shape = template.getTransactions().get(s);
                if (lineIsStripped(stripPerShape.get(s), ctx.lines[i])) {
                    continue;
                }
                final ConsumeResult attempt = trySingleLine(shape, ctx, i);
                if (attempt != null) {
                    match = attempt.tx;
                    matchedShapeName = shape.getName();
                    consumed = attempt.consumed;
                    break;
                }
                final ConsumeResult attempt2 = tryMultiLine(shape, ctx, i, stripPerShape.get(s));
                if (attempt2 != null) {
                    match = attempt2.tx;
                    matchedShapeName = shape.getName();
                    consumed = attempt2.consumed;
                    break;
                }
            }
            if (match != null) {
                out.add(new ExtractedTransaction(
                        match.date, match.description, match.amount, matchedShapeName));
                i += Math.max(1, consumed);
            } else {
                i++;
            }
        }
        return out;
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
            final ExtractionContext ctx, final int lineIdx) {
        if (shape.getLineRegex() == null) return null;
        final Pattern p = RegexCache.compile(shape.getLineRegex());
        if (p == null) return null;
        final Matcher m = p.matcher(ctx.lines[lineIdx]);
        if (!m.find()) return null;
        try {
            final LocalDate d = parseDate(m.group("date"), shape.getDateFormat());
            final String desc = safeGroup(m, "description");
            final BigDecimal amt = parseAmount(safeGroup(m, "amount"));
            if (d == null || amt == null) return null;
            if (shape.getMinAmount() != null
                    && amt.abs().compareTo(shape.getMinAmount()) < 0) return null;
            return new ConsumeResult(
                    new ExtractedTransaction(d, desc, amt, shape.getName()), 1);
        } catch (final IllegalArgumentException ex) {
            return null;
        }
    }

    private static ConsumeResult tryMultiLine(
            final PdfTemplateV2.TransactionShape shape,
            final ExtractionContext ctx, final int startIdx,
            final List<Pattern> stripPats) {
        if (shape.getStartRegex() == null || shape.getEndRegex() == null) return null;
        final Pattern start = RegexCache.compile(shape.getStartRegex());
        final Pattern end = RegexCache.compile(shape.getEndRegex());
        if (start == null || end == null) return null;
        final Matcher startM = start.matcher(ctx.lines[startIdx]);
        if (!startM.find()) return null;
        LocalDate date;
        String desc;
        try {
            date = parseDate(startM.group("date"), shape.getDateFormat());
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

    private static LocalDate parseDate(final String raw, final String format) {
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
        // Date with no year — default to year-1900 fallback so callers
        // recognize the date but can year-correct downstream.
        try {
            return LocalDate.parse(
                    raw.trim() + "/2000", DateTimeFormatter.ofPattern("M/d/yyyy"));
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
