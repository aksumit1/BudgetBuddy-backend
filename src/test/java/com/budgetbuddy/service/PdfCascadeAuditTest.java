package com.budgetbuddy.service;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import com.budgetbuddy.service.category.CategoryCascade;
import java.io.File;
import java.io.FileInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Full-cascade audit: runs every parsed transaction from every PDF in
 * {@code -Dpdf.cascade.dir=<path>} through the production
 * {@link CategoryCascade} (L0…L9 with all real collaborators wired by Spring)
 * and reports which layer made each call.
 *
 * <p>Unlike {@link PdfCategoryAuditTest} which only exercises L5 in
 * isolation, this probe boots the full Spring context — so L2 MCC, L3
 * merchant-DB, L4 Plaid, L6 location (network), L7 fuzzy, L8 ML, and L9
 * fallback all contribute. The output groups by {@code CategoryResult#getSource()}
 * so we can see which layer is doing the work.
 */
@EnabledIfSystemProperty(named = "pdf.cascade.dir", matches = ".+")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestPropertySource(properties = {"app.aws.dynamodb.table-prefix=TestBudgetBuddy"})
class PdfCascadeAuditTest {

    @Autowired private CategoryCascade cascade;
    @Autowired private PDFImportService pdfImport;

    @Test
    void audit() throws Exception {
        final String dirStr = System.getProperty("pdf.cascade.dir");
        final File dir = new File(dirStr);
        final File[] pdfs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        if (pdfs == null || pdfs.length == 0) {
            throw new IllegalStateException("No PDFs found in " + dirStr);
        }
        Arrays.sort(pdfs, Comparator.comparing(File::getName));

        // Aggregates
        final Map<String, Integer> byLayer = new TreeMap<>();
        final Map<String, Integer> byCategory = new TreeMap<>();
        final Map<String, Map<String, Integer>> layerXcategory = new TreeMap<>();
        final Map<String, Integer> uncatMerchants = new HashMap<>();
        int totalTx = 0;
        int totalHit = 0;
        int totalMiss = 0;

        for (final File pdf : pdfs) {
            try (FileInputStream in = new FileInputStream(pdf)) {
                final ImportResult result =
                        pdfImport.parsePDF(in, pdf.getName(), "cascade-audit", null);
                final List<ParsedTransaction> txs = result.getTransactions();
                for (final ParsedTransaction t : txs) {
                    totalTx++;
                    if (t.getDescription() == null || t.getDescription().isBlank()) {
                        byLayer.merge("NO_DESCRIPTION", 1, Integer::sum);
                        totalMiss++;
                        continue;
                    }
                    final CategoryCascade.Context ctx =
                            CategoryCascade.Context.builder()
                                    .userId("cascade-audit")
                                    .merchantName(t.getDescription())
                                    .description(t.getDescription())
                                    .normalizedDescription(
                                            t.getDescription().toLowerCase(Locale.ROOT))
                                    .build();
                    final CategoryResult cr = cascade.classify(ctx);
                    if (cr == null) {
                        byLayer.merge("MISS_FALLTHROUGH", 1, Integer::sum);
                        totalMiss++;
                        uncatMerchants.merge(
                                t.getDescription().trim().toUpperCase(Locale.ROOT),
                                1,
                                Integer::sum);
                    } else {
                        totalHit++;
                        final String layer =
                                cr.getSource() == null ? "UNKNOWN_LAYER" : cr.getSource();
                        final String cat =
                                cr.getCategoryPrimary() == null
                                        ? "?"
                                        : cr.getCategoryPrimary();
                        byLayer.merge(layer, 1, Integer::sum);
                        byCategory.merge(cat, 1, Integer::sum);
                        layerXcategory
                                .computeIfAbsent(layer, k -> new TreeMap<>())
                                .merge(cat, 1, Integer::sum);
                    }
                }
            } catch (final Exception e) {
                System.err.println(
                        "FAILED: " + pdf.getName() + " — "
                                + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        // Report
        System.out.println();
        System.out.println("============================================================");
        System.out.println("              FULL-CASCADE AUDIT (L0–L9)");
        System.out.println("============================================================");
        System.out.printf("Files audited:           %d%n", pdfs.length);
        System.out.printf("Total transactions:      %d%n", totalTx);
        System.out.printf("Cascade hits:            %d (%.1f%%)%n",
                totalHit, totalTx == 0 ? 0.0 : 100.0 * totalHit / totalTx);
        System.out.printf("Cascade misses:          %d%n", totalMiss);
        System.out.println();
        System.out.println("=== Per-layer hit count ===");
        System.out.printf("%-25s | %-7s | %-7s%n", "LAYER", "COUNT", "PCT");
        System.out.println("--------------------------+---------+--------");
        for (final Map.Entry<String, Integer> e : byLayer.entrySet()) {
            System.out.printf("%-25s | %-7d | %5.1f%%%n",
                    e.getKey(), e.getValue(),
                    totalTx == 0 ? 0.0 : 100.0 * e.getValue() / totalTx);
        }
        System.out.println();
        System.out.println("=== Per-category (post-cascade) ===");
        System.out.printf("%-25s | %-7s%n", "CATEGORY", "COUNT");
        System.out.println("--------------------------+---------");
        for (final Map.Entry<String, Integer> e : byCategory.entrySet()) {
            System.out.printf("%-25s | %-7d%n", e.getKey(), e.getValue());
        }
        System.out.println();
        System.out.println("=== Layer × category (top categories per layer) ===");
        for (final Map.Entry<String, Map<String, Integer>> e : layerXcategory.entrySet()) {
            System.out.println("  " + e.getKey() + ":");
            e.getValue().entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(
                            Map.Entry::getValue).reversed())
                    .limit(8)
                    .forEach(en -> System.out.printf("    %4d  %s%n", en.getValue(), en.getKey()));
        }
        System.out.println();
        System.out.println("=== Top 20 cascade misses ===");
        uncatMerchants.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed())
                .limit(20)
                .forEach(e -> System.out.printf("  %4d  %s%n", e.getValue(), e.getKey()));
    }

    @SuppressWarnings("unused")
    private static BigDecimal scale(final BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b.setScale(2, RoundingMode.HALF_UP);
    }
}
