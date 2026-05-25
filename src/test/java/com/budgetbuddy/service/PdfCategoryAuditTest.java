package com.budgetbuddy.service;

import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import com.budgetbuddy.service.category.MerchantCategoryRules;
import com.budgetbuddy.service.category.MerchantCategoryRules.MatchResult;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * Category audit: parses every PDF and runs every transaction through the
 * real {@link MerchantCategoryRules} engine (the L5 curated layer of the
 * cascade — the same YAML rules production loads). Reports:
 *
 *   • Per-category breakdown across all statements (count + total amount)
 *   • Per-direction split (DEBIT vs CREDIT) for each category
 *   • Top uncategorised merchants (so we know what to add rules for)
 *   • Per-statement category coverage % (how many tx have a category)
 *
 * Enabled via {@code -Dpdf.cat.dir=<path>} (off in CI).
 */
@EnabledIfSystemProperty(named = "pdf.cat.dir", matches = ".+")
class PdfCategoryAuditTest {

    @Test
    void audit() throws Exception {
        final String dirStr = System.getProperty("pdf.cat.dir");
        final File dir = new File(dirStr);
        final File[] pdfs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        if (pdfs == null || pdfs.length == 0) {
            throw new IllegalStateException("No PDFs found in " + dirStr);
        }
        Arrays.sort(pdfs, Comparator.comparing(File::getName));

        final PDFImportService svc = buildService();
        final MerchantCategoryRules rules = new MerchantCategoryRules("category-rules-v2.yaml");

        // Aggregates
        final Map<String, CategoryAgg> byCategory = new TreeMap<>();
        final Map<String, Integer> uncatMerchants = new HashMap<>();
        final Map<String, Integer> bySource = new TreeMap<>();
        final List<StatementRow> statementRows = new ArrayList<>();
        int totalTx = 0;
        int totalCategorised = 0;
        int totalDebit = 0;
        int totalCredit = 0;

        for (final File pdf : pdfs) {
            final StatementRow sr = new StatementRow();
            sr.file = pdf.getName();
            try (FileInputStream in = new FileInputStream(pdf)) {
                final ImportResult result =
                        svc.parsePDF(in, pdf.getName(), "audit-user", null);
                sr.institution =
                        result.getDetectedAccount() == null
                                ? "?"
                                : result.getDetectedAccount().getInstitutionName();
                final List<ParsedTransaction> txs = result.getTransactions();
                sr.txCount = txs.size();

                for (final ParsedTransaction t : txs) {
                    totalTx++;
                    final BigDecimal amt =
                            t.getAmount() == null ? BigDecimal.ZERO : t.getAmount().abs();
                    final String flow =
                            t.getFlowDirection() == null
                                    ? "?"
                                    : t.getFlowDirection().name();
                    if ("CREDIT".equals(flow)) totalCredit++; else totalDebit++;

                    final String descLower =
                            t.getDescription() == null
                                    ? null
                                    : t.getDescription().toLowerCase(Locale.ROOT);
                    final MatchResult mr =
                            descLower == null ? null : rules.matchWithDetails(descLower, descLower);
                    final String cat;
                    final String source;
                    if (mr != null) {
                        cat = mr.category;
                        source = mr.source.name();
                        totalCategorised++;
                        sr.categorised++;
                    } else {
                        cat = "uncategorised";
                        source = "NONE";
                        final String merchant =
                                t.getDescription() == null
                                        ? "?"
                                        : t.getDescription().trim().toUpperCase(Locale.ROOT);
                        uncatMerchants.merge(merchant, 1, Integer::sum);
                    }
                    bySource.merge(source, 1, Integer::sum);

                    final CategoryAgg a =
                            byCategory.computeIfAbsent(cat, k -> new CategoryAgg());
                    a.count++;
                    a.totalAbs = a.totalAbs.add(amt);
                    if ("CREDIT".equals(flow)) {
                        a.credits++;
                        a.creditAbs = a.creditAbs.add(amt);
                    } else {
                        a.debits++;
                        a.debitAbs = a.debitAbs.add(amt);
                    }
                }
            } catch (final Exception e) {
                sr.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            statementRows.add(sr);
        }

        // CSV
        final java.nio.file.Path csv =
                Paths.get(System.getProperty("pdf.cat.out", "/tmp/pdf_category_audit.csv"));
        if (csv.getParent() != null) Files.createDirectories(csv.getParent());
        writeReport(csv.toFile(), byCategory, uncatMerchants, statementRows);

        // Console
        System.out.println();
        System.out.println("============================================================");
        System.out.println("                  CATEGORY AUDIT REPORT");
        System.out.println("============================================================");
        System.out.printf("Files audited:           %d%n", pdfs.length);
        System.out.printf("Total transactions:      %d%n", totalTx);
        System.out.printf("  DEBIT  (money out):    %d%n", totalDebit);
        System.out.printf("  CREDIT (money in):     %d%n", totalCredit);
        System.out.printf("Categorised by rules:    %d (%.1f%%)%n",
                totalCategorised,
                totalTx == 0 ? 0.0 : (100.0 * totalCategorised / totalTx));
        System.out.printf("Uncategorised:           %d%n", totalTx - totalCategorised);
        System.out.println();
        System.out.println("=== Per-category breakdown ===");
        System.out.printf("%-25s | %-7s | %-7s | %-7s | %-14s | %-14s%n",
                "CATEGORY", "COUNT", "DEBITS", "CREDITS", "DEBIT_TOTAL", "CREDIT_TOTAL");
        System.out.println("---------------------------+---------+---------+---------+----------------+----------------");
        for (final Map.Entry<String, CategoryAgg> e : byCategory.entrySet()) {
            final CategoryAgg a = e.getValue();
            System.out.printf("%-25s | %-7d | %-7d | %-7d | %-14s | %-14s%n",
                    e.getKey(),
                    a.count,
                    a.debits,
                    a.credits,
                    a.debitAbs.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    a.creditAbs.setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        System.out.println();
        System.out.println("=== Rule-source provenance ===");
        for (final Map.Entry<String, Integer> e : bySource.entrySet()) {
            System.out.printf("  %-20s : %d%n", e.getKey(), e.getValue());
        }
        System.out.println();
        System.out.println("=== Top 25 uncategorised merchant descriptions ===");
        uncatMerchants.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed())
                .limit(25)
                .forEach(e -> System.out.printf("  %4d  %s%n", e.getValue(), e.getKey()));
        System.out.println();
        System.out.println("Report: " + csv);
    }

    private static PDFImportService buildService() {
        final com.budgetbuddy.repository.dynamodb.AccountRepository accountRepo =
                Mockito.mock(com.budgetbuddy.repository.dynamodb.AccountRepository.class);
        Mockito.when(accountRepo.findByUserId(ArgumentMatchers.anyString()))
                .thenReturn(java.util.Collections.emptyList());
        final AccountDetectionService accountDetection =
                new AccountDetectionService(accountRepo, new BalanceExtractor());
        final ImportCategoryParser categoryParser = Mockito.mock(ImportCategoryParser.class);
        Mockito.when(
                        categoryParser.parseCategory(
                                ArgumentMatchers.any(),
                                ArgumentMatchers.any(),
                                ArgumentMatchers.any(),
                                ArgumentMatchers.any(),
                                ArgumentMatchers.any(),
                                ArgumentMatchers.any()))
                .thenReturn("Uncategorized");
        final EnhancedPatternMatcher patternMatcher = new EnhancedPatternMatcher();

        final com.budgetbuddy.service.pdf.PdfTemplateRegistry registry =
                new com.budgetbuddy.service.pdf.PdfTemplateRegistry();
        try {
            final Field f =
                    com.budgetbuddy.service.pdf.PdfTemplateRegistry.class.getDeclaredField(
                            "resourcePattern");
            f.setAccessible(true);
            f.set(registry, "classpath*:pdf-templates/*.yaml");
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        registry.init();
        final com.budgetbuddy.service.pdf.v2.PdfTemplateV2Registry v2Registry =
                new com.budgetbuddy.service.pdf.v2.PdfTemplateV2Registry();
        v2Registry.initForTesting("classpath*:pdf-templates-v2/*.yaml");
        return new PDFImportService(
                accountDetection,
                categoryParser,
                patternMatcher,
                null,
                null,
                registry,
                null,
                null,
                null,
                v2Registry);
    }

    private static void writeReport(
            final File out,
            final Map<String, CategoryAgg> byCategory,
            final Map<String, Integer> uncatMerchants,
            final List<StatementRow> statementRows)
            throws Exception {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(out, false))) {
            w.write("section,key,count,debits,credits,debitTotal,creditTotal\n");
            for (final Map.Entry<String, CategoryAgg> e : byCategory.entrySet()) {
                final CategoryAgg a = e.getValue();
                w.write(
                        "category,"
                                + escape(e.getKey()) + ","
                                + a.count + "," + a.debits + "," + a.credits + ","
                                + a.debitAbs.setScale(2, RoundingMode.HALF_UP).toPlainString() + ","
                                + a.creditAbs.setScale(2, RoundingMode.HALF_UP).toPlainString()
                                + "\n");
            }
            w.write("\n");
            w.write("file,institution,txCount,categorised,error\n");
            for (final StatementRow s : statementRows) {
                w.write(
                        escape(s.file) + ","
                                + escape(s.institution == null ? "" : s.institution) + ","
                                + s.txCount + "," + s.categorised + ","
                                + escape(s.error == null ? "" : s.error) + "\n");
            }
            w.write("\n");
            w.write("uncategorised_merchant,count\n");
            uncatMerchants.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                            .reversed())
                    .forEach(e -> {
                        try {
                            w.write(escape(e.getKey()) + "," + e.getValue() + "\n");
                        } catch (final Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    });
        }
    }

    private static String escape(final String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static final class CategoryAgg {
        int count;
        int debits;
        int credits;
        BigDecimal totalAbs = BigDecimal.ZERO;
        BigDecimal debitAbs = BigDecimal.ZERO;
        BigDecimal creditAbs = BigDecimal.ZERO;
    }

    private static final class StatementRow {
        String file;
        String institution;
        int txCount;
        int categorised;
        String error;
    }
}
