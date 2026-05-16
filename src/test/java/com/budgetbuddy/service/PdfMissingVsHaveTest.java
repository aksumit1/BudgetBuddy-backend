package com.budgetbuddy.service;

import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Per-PDF "what we have vs what we don't" diff. For each PDF we enumerate every
 * candidate transaction in the raw PDF text (any line containing a MM/DD[/YY]
 * date AND a $-amount), then diff against what the parser actually returns.
 *
 * The enumeration is intentionally generous — we'd rather over-flag and inspect
 * than under-flag. False positives in the raw enumeration (e.g. disclosure lines
 * that happen to contain a date + dollar amount) show up as "in raw, not parsed"
 * and are reviewed manually.
 *
 * Outputs:
 *   /tmp/pdf_missing_vs_have.csv     — per-file summary (raw count, parsed count, deltas)
 *   /tmp/pdf_missing_vs_have_detail.csv — per-row diff (status, source line / parsed row)
 */
@EnabledIfSystemProperty(named = "pdf.diff.dir", matches = ".+")
class PdfMissingVsHaveTest {

    // MM/DD/YY or MM/DD/YYYY at start of line OR after standard prefixes (AutoPay etc.)
    // and a dollar amount somewhere in the line.
    private static final Pattern RAW_TX_ANCHOR =
            Pattern.compile(
                    "^\\s*(\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?)\\*?\\s+(.+?)\\s+"
                            + "(-?\\$[\\d]{1,3}(?:,\\d{3})*\\.\\d{2})(?:\\s*⧫?)?\\s*$");

    @Test
    void diff() throws Exception {
        final String dirStr = System.getProperty("pdf.diff.dir");
        final File dir = new File(dirStr);
        final File[] pdfs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        Arrays.sort(pdfs, Comparator.comparing(File::getName));

        final PDFImportService svc = buildService();

        final java.nio.file.Path summary =
                Paths.get(System.getProperty("pdf.diff.out", "/tmp/pdf_missing_vs_have.csv"));
        final java.nio.file.Path detail =
                Paths.get(System.getProperty(
                        "pdf.diff.detail", "/tmp/pdf_missing_vs_have_detail.csv"));
        if (summary.getParent() != null) Files.createDirectories(summary.getParent());

        int grandRaw = 0, grandParsed = 0, grandMissing = 0, grandExtra = 0;
        try (BufferedWriter sw = new BufferedWriter(new FileWriter(summary.toFile()));
             BufferedWriter dw = new BufferedWriter(new FileWriter(detail.toFile()))) {
            sw.write("file,raw_count,parsed_count,unique_match,missing_from_parsed,extra_in_parsed\n");
            dw.write("file,status,date,amount,source_or_parsed_description\n");

            for (final File pdf : pdfs) {
                final RawEnum raw = enumerateRaw(pdf);
                final ImportResult result;
                try (FileInputStream in = new FileInputStream(pdf)) {
                    result = svc.parsePDF(in, pdf.getName(), "audit-user", null);
                }
                final List<ParsedTransaction> parsed = result.getTransactions();

                // Build the key sets: (date, amount-absolute)
                // Description tokens (5 best-fit words) — anchored loosely so a different
                // description framing on the same row still matches.
                final Map<String, List<String>> rawByKey = new TreeMap<>();
                for (final RawTx r : raw.txs) {
                    rawByKey.computeIfAbsent(amountKey(r.amount), k -> new ArrayList<>())
                            .add(r.dateRaw + " " + r.description);
                }
                final Map<String, List<ParsedTransaction>> parsedByKey = new TreeMap<>();
                for (final ParsedTransaction t : parsed) {
                    parsedByKey.computeIfAbsent(amountKey(t.getAmount()), k -> new ArrayList<>())
                            .add(t);
                }

                int matched = 0;
                int missing = 0;
                int extra = 0;
                // For each raw amount-key, try to match with parsed amount-key 1:1.
                final Set<String> allKeys = new LinkedHashSet<>();
                allKeys.addAll(rawByKey.keySet());
                allKeys.addAll(parsedByKey.keySet());
                for (final String key : allKeys) {
                    final List<String> r = rawByKey.getOrDefault(key, List.of());
                    final List<ParsedTransaction> p = parsedByKey.getOrDefault(key, List.of());
                    final int common = Math.min(r.size(), p.size());
                    matched += common;
                    for (int i = common; i < r.size(); i++) {
                        missing++;
                        final String[] parts = r.get(i).split(" ", 2);
                        dw.write(csv(pdf.getName()) + ",MISSING," + csv(parts[0])
                                + "," + csv(key) + "," + csv(parts.length > 1 ? parts[1] : "") + "\n");
                    }
                    for (int i = common; i < p.size(); i++) {
                        extra++;
                        final ParsedTransaction t = p.get(i);
                        dw.write(csv(pdf.getName()) + ",EXTRA,"
                                + csv(t.getDate() == null ? "" : t.getDate().toString())
                                + "," + csv(key)
                                + "," + csv(t.getDescription() == null ? "" : t.getDescription()) + "\n");
                    }
                }

                grandRaw += raw.txs.size();
                grandParsed += parsed.size();
                grandMissing += missing;
                grandExtra += extra;

                sw.write(csv(pdf.getName()) + "," + raw.txs.size() + "," + parsed.size()
                        + "," + matched + "," + missing + "," + extra + "\n");

                System.out.printf(
                        "%-58s | raw=%3d parsed=%3d match=%3d miss=%3d extra=%3d%n",
                        trunc(pdf.getName(), 58),
                        raw.txs.size(), parsed.size(), matched, missing, extra);
            }
        }

        System.out.println();
        System.out.println("=== TOTALS ===");
        System.out.println("Raw enumerated:         " + grandRaw);
        System.out.println("Parser captured:        " + grandParsed);
        System.out.println("Matched by amount-key:  " + (grandRaw - grandMissing));
        System.out.println("MISSING from parser:    " + grandMissing);
        System.out.println("EXTRA in parser:        " + grandExtra);
        System.out.println();
        System.out.println("Summary: " + summary);
        System.out.println("Detail:  " + detail);
    }

    private static RawEnum enumerateRaw(final File pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(pdf.toPath()))) {
            final PDFTextStripper s = new PDFTextStripper();
            // Match production: parsePDF uses default text stripping (no sort-by-position),
            // so our raw enumeration sees the same layout PDFImportService does.
            final String text = s.getText(doc);
            // Apply the same preprocessing as PDFImportService so our raw enumeration
            // sees lines AS the parser sees them — otherwise the diff is dominated by
            // structural mismatches (3-line transactions extracted as 3 separate "raw"
            // candidates that the parser correctly stitches into 1 row).
            String t = PDFImportService.stripAndCaptureFxAnnotations(text).getCleanedText();
            t = PDFImportService.stripAmexFxBlocks(t);
            t = PDFImportService.stitchContinuationLines(t);
            t = PDFImportService.splitTransactionFromTrailingSectionHeader(t);
            t = PDFImportService.splitGluedTransactions(t);

            final RawEnum out = new RawEnum();
            for (final String line : t.split("\\r?\\n")) {
                if (line == null || line.isBlank()) continue;
                final Matcher m = RAW_TX_ANCHOR.matcher(line);
                if (m.matches()) {
                    final RawTx tx = new RawTx();
                    tx.dateRaw = m.group(1);
                    tx.description = m.group(2);
                    tx.amount = parseAmount(m.group(3));
                    if (tx.amount != null && !isDisclosureNoise(line)) {
                        out.txs.add(tx);
                    }
                }
            }
            return out;
        }
    }

    /** Reject lines that are statement metadata / disclosure that happen to fit the shape. */
    private static boolean isDisclosureNoise(final String line) {
        final String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("pay over time limit")
                || lower.contains("payment due date")
                || lower.contains("apr ")
                || lower.contains("annual percentage rate")
                || lower.contains("minimum payment")
                || lower.contains("credit limit")
                || lower.contains("new balance")
                || lower.contains("previous balance")
                || lower.contains("available credit")
                || lower.contains("rate expires")
                || lower.matches(".*\\bp\\.\\s*\\d+/\\d+.*")
                || lower.contains("statement period")
                || lower.contains("closing date");
    }

    private static BigDecimal parseAmount(final String raw) {
        if (raw == null) return null;
        String s = raw.replace("$", "").replace(",", "").trim();
        if (s.startsWith("(") && s.endsWith(")")) {
            s = "-" + s.substring(1, s.length() - 1);
        }
        try {
            return new BigDecimal(s).abs();
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static String amountKey(final BigDecimal v) {
        if (v == null) return "?";
        return v.abs().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String csv(final String s) {
        if (s == null) return "";
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String trunc(final String s, final int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static PDFImportService buildService() {
        final com.budgetbuddy.repository.dynamodb.AccountRepository repo =
                org.mockito.Mockito.mock(
                        com.budgetbuddy.repository.dynamodb.AccountRepository.class);
        org.mockito.Mockito.when(repo.findByUserId(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Collections.emptyList());
        final AccountDetectionService det =
                new AccountDetectionService(repo, new BalanceExtractor());
        final ImportCategoryParser cat =
                org.mockito.Mockito.mock(ImportCategoryParser.class);
        final EnhancedPatternMatcher pm = new EnhancedPatternMatcher();

        final com.budgetbuddy.service.pdf.PdfTemplateRegistry reg =
                new com.budgetbuddy.service.pdf.PdfTemplateRegistry();
        try {
            final java.lang.reflect.Field f =
                    com.budgetbuddy.service.pdf.PdfTemplateRegistry.class.getDeclaredField(
                            "resourcePattern");
            f.setAccessible(true);
            f.set(reg, "classpath*:pdf-templates/*.yaml");
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        reg.init();
        return new PDFImportService(det, cat, pm, null, null, reg, null, null, null, null);
    }

    private static final class RawTx {
        String dateRaw;
        String description;
        BigDecimal amount;
    }

    private static final class RawEnum {
        final List<RawTx> txs = new ArrayList<>();
    }
}
