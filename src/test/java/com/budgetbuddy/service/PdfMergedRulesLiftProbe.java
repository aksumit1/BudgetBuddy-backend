package com.budgetbuddy.service;

import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import com.budgetbuddy.service.category.MerchantCategoryRules;
import com.budgetbuddy.service.category.MerchantCategoryRules.MatchResult;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;

/**
 * Lift probe with MERGED rules. Concatenates {@code category-rules-v2.yaml}
 * (production) and {@code draft-rules-backfill.yaml} (review draft) into a
 * single YAML, loads that into one {@link MerchantCategoryRules} engine, and
 * lets priority-based tie-breaking pick the winner. This is how the rules
 * would actually behave once the draft is merged into production — not the
 * "try v2 then try draft" approximation of {@code PdfDraftRulesLiftProbe}.
 *
 * <p>Also dumps EVERY still-missing transaction (not just top 25) to a TSV
 * so the long-tail can be reviewed in one place.
 */
@EnabledIfSystemProperty(named = "pdf.merged.dir", matches = ".+")
class PdfMergedRulesLiftProbe {

    @Test
    void measure() throws Exception {
        final String dirStr = System.getProperty("pdf.merged.dir");
        final File dir = new File(dirStr);
        final File[] pdfs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        Arrays.sort(pdfs, Comparator.comparing(File::getName));

        // Concatenate v2 + draft into a temp yaml on the classpath
        final File mergedFile = mergedYaml();
        final String mergedClasspath = mergedFile.getName();
        // Load it. MerchantCategoryRules looks for the resource via
        // ClassPathResource so we put the file on the test-resources path.
        final MerchantCategoryRules merged = new MerchantCategoryRules(mergedClasspath);

        final PDFImportService svc = buildService();

        int total = 0, hits = 0, miss = 0;
        final Map<String, Integer> byCategory = new TreeMap<>();
        final Map<String, Integer> bySource = new TreeMap<>();
        final Map<String, Integer> missCounts = new HashMap<>();
        final Map<String, String> missSample = new HashMap<>();

        for (final File pdf : pdfs) {
            try (FileInputStream in = new FileInputStream(pdf)) {
                final ImportResult r =
                        svc.parsePDF(in, pdf.getName(), "merged-probe", null);
                for (final ParsedTransaction t : r.getTransactions()) {
                    total++;
                    final String d = t.getDescription();
                    if (d == null || d.isBlank()) continue;
                    final String norm = d.toLowerCase(Locale.ROOT);
                    final MatchResult m = merged.matchWithDetails(norm, norm);
                    if (m == null) {
                        miss++;
                        final String key = d.trim().toUpperCase(Locale.ROOT);
                        missCounts.merge(key, 1, Integer::sum);
                        missSample.putIfAbsent(key, d.trim());
                    } else {
                        hits++;
                        byCategory.merge(m.category, 1, Integer::sum);
                        bySource.merge(m.source.name(), 1, Integer::sum);
                    }
                }
            } catch (final Exception e) {
                // skip
            }
        }

        // Dump full miss list to TSV
        final java.nio.file.Path missTsv =
                Paths.get(System.getProperty("pdf.merged.miss",
                        "/tmp/pdf_merged_misses.tsv"));
        try (BufferedWriter w = new BufferedWriter(new FileWriter(missTsv.toFile(), false))) {
            w.write("count\tdescription\n");
            missCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> {
                        try {
                            w.write(e.getValue() + "\t"
                                    + missSample.get(e.getKey()).replace("\t", " ") + "\n");
                        } catch (final Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    });
        }

        System.out.println();
        System.out.println("============================================================");
        System.out.println("    MERGED RULES (v2 + draft) — single engine, priority-aware");
        System.out.println("============================================================");
        System.out.printf("Total transactions:               %d%n", total);
        System.out.printf("L5 hits (merged):                 %d (%.1f%%)%n",
                hits, total == 0 ? 0.0 : 100.0 * hits / total);
        System.out.printf("Still missing:                    %d (%.1f%%)%n",
                miss, total == 0 ? 0.0 : 100.0 * miss / total);
        System.out.println();
        System.out.println("=== Hits by category ===");
        byCategory.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> System.out.printf("  %-22s %d%n", e.getKey(), e.getValue()));
        System.out.println();
        System.out.println("=== Hits by rule source ===");
        bySource.forEach((k, v) -> System.out.printf("  %-22s %d%n", k, v));
        System.out.println();
        System.out.println("Full miss list: " + missTsv);
        System.out.println("Distinct missing clusters: " + missCounts.size());
    }

    private static File mergedYaml() throws Exception {
        // Load both YAMLs from classpath, concatenate their `rules:` sections,
        // write to a temp file that we place under target/test-classes so the
        // ClassPathResource lookup in MerchantCategoryRules can find it.
        final String v2 = readClasspath("category-rules-v2.yaml");
        final String draft = readClasspath("draft-rules-backfill.yaml");
        // Strip the leading `rules:` from the second file and merge.
        final int draftRulesIdx = draft.indexOf("rules:");
        final String draftBody = draftRulesIdx < 0
                ? draft
                : draft.substring(draftRulesIdx + "rules:".length());
        final String merged = v2 + "\n# === Draft rules appended for lift probe ===\n" + draftBody;
        // Write to target/test-classes so the classpath loader can find it.
        final java.nio.file.Path outDir = Paths.get("target/test-classes");
        Files.createDirectories(outDir);
        final java.nio.file.Path out = outDir.resolve("merged-rules-probe.yaml");
        Files.writeString(out, merged, StandardCharsets.UTF_8);
        return out.toFile();
    }

    private static String readClasspath(final String resource) throws Exception {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
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
                accountDetection, categoryParser, patternMatcher, null, null, registry, null, null,
                null, v2Registry);
    }
}
