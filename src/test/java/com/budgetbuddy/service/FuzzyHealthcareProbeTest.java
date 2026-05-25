package com.budgetbuddy.service;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import com.budgetbuddy.service.category.CategoryCascade;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Diagnostic: for every transaction in the audit dir, runs cascade and
 * captures (description, matched-category, layer). Buckets by category so
 * we can see WHICH 768 transactions are landing in "healthcare" and why.
 */
@EnabledIfSystemProperty(named = "fuzzy.probe.dir", matches = ".+")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestPropertySource(
        properties = {
            "app.aws.dynamodb.table-prefix=TestBudgetBuddy",
            "app.category.overpass.enabled=false",
            "app.category.nominatim.enabled=false",
            "app.category.wikidata.enabled=false"
        })
class FuzzyHealthcareProbeTest {

    @Autowired private CategoryCascade cascade;
    @Autowired private PDFImportService pdfImport;

    @Test
    void probe() throws Exception {
        final String dirStr = System.getProperty("fuzzy.probe.dir");
        final File dir = new File(dirStr);
        final File[] pdfs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        Arrays.sort(pdfs, Comparator.comparing(File::getName));

        final Map<String, List<String>> byCategory = new HashMap<>();
        int totalTx = 0;
        for (final File pdf : pdfs) {
            try (FileInputStream in = new FileInputStream(pdf)) {
                final ImportResult result =
                        pdfImport.parsePDF(in, pdf.getName(), "probe", null);
                for (final ParsedTransaction t : result.getTransactions()) {
                    totalTx++;
                    if (t.getDescription() == null || t.getDescription().isBlank()) continue;
                    final CategoryCascade.Context ctx =
                            CategoryCascade.Context.builder()
                                    .userId("probe")
                                    .merchantName(t.getDescription())
                                    .description(t.getDescription())
                                    .normalizedDescription(
                                            t.getDescription().toLowerCase(Locale.ROOT))
                                    .build();
                    final CategoryResult cr = cascade.classify(ctx);
                    if (cr != null) {
                        final String cat = cr.getCategoryPrimary();
                        if ("healthcare".equals(cat)
                                || "health".equals(cat)
                                || "education".equals(cat)
                                || "travel".equals(cat)) {
                            byCategory
                                    .computeIfAbsent(
                                            cat + " [" + cr.getSource() + "]",
                                            k -> new ArrayList<>())
                                    .add(t.getDescription());
                        }
                    }
                }
            } catch (final Exception e) {
                // skip
            }
        }

        System.out.println();
        System.out.println("=== Total transactions: " + totalTx + " ===");
        for (final Map.Entry<String, List<String>> e : byCategory.entrySet()) {
            System.out.println();
            System.out.println(">>> " + e.getKey() + " (" + e.getValue().size() + " tx) <<<");
            // Bucket by normalized description so we see distinct patterns
            final Map<String, Integer> distinct = new HashMap<>();
            for (final String d : e.getValue()) {
                distinct.merge(d.toUpperCase(Locale.ROOT).trim(), 1, Integer::sum);
            }
            distinct.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                            .reversed())
                    .limit(25)
                    .forEach(en -> System.out.printf("  %4d  %s%n", en.getValue(), en.getKey()));
        }
    }
}
