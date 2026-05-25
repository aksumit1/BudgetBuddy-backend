package com.budgetbuddy.service;

import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * Counts how many parsed transactions carry the {@code location} field
 * populated by the PDF extractors, and bucket-counts the shape of those
 * location strings (city-only, "CITY ST", country, etc.) so we can tell
 * how much L6 (location-based lookup) has to work with.
 */
@EnabledIfSystemProperty(named = "pdf.loc.dir", matches = ".+")
class PdfLocationCoverageProbe {

    @Test
    void probe() throws Exception {
        final String dirStr = System.getProperty("pdf.loc.dir");
        final File dir = new File(dirStr);
        final File[] pdfs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        Arrays.sort(pdfs, Comparator.comparing(File::getName));

        final PDFImportService svc = buildService();
        int totalTx = 0;
        int withLocation = 0;
        int withoutLocation = 0;
        final Map<String, Integer> byInstitution = new HashMap<>();
        final Map<String, Integer> byInstitutionWithLoc = new HashMap<>();
        final Map<String, Integer> sampleLocs = new HashMap<>();

        for (final File pdf : pdfs) {
            try (FileInputStream in = new FileInputStream(pdf)) {
                final ImportResult result =
                        svc.parsePDF(in, pdf.getName(), "loc-probe", null);
                final String inst =
                        result.getDetectedAccount() == null
                                ? "?"
                                : result.getDetectedAccount().getInstitutionName();
                for (final ParsedTransaction t : result.getTransactions()) {
                    totalTx++;
                    byInstitution.merge(inst, 1, Integer::sum);
                    if (t.getLocation() != null && !t.getLocation().isBlank()) {
                        withLocation++;
                        byInstitutionWithLoc.merge(inst, 1, Integer::sum);
                        sampleLocs.merge(t.getLocation().trim(), 1, Integer::sum);
                    } else {
                        withoutLocation++;
                    }
                }
            } catch (final Exception e) {
                // skip
            }
        }

        System.out.println();
        System.out.println("=== Location coverage across statements ===");
        System.out.printf("Total transactions:    %d%n", totalTx);
        System.out.printf("  with location:       %d (%.1f%%)%n",
                withLocation, totalTx == 0 ? 0.0 : 100.0 * withLocation / totalTx);
        System.out.printf("  without location:    %d (%.1f%%)%n",
                withoutLocation, totalTx == 0 ? 0.0 : 100.0 * withoutLocation / totalTx);
        System.out.println();
        System.out.println("=== By institution ===");
        System.out.printf("%-25s | %-7s | %-7s | %-7s%n",
                "INSTITUTION", "TX", "WITH_LOC", "PCT");
        System.out.println("--------------------------+---------+---------+--------");
        for (final Map.Entry<String, Integer> e : byInstitution.entrySet()) {
            final int tot = e.getValue();
            final int wl = byInstitutionWithLoc.getOrDefault(e.getKey(), 0);
            System.out.printf("%-25s | %-7d | %-7d | %5.1f%%%n",
                    e.getKey(), tot, wl, tot == 0 ? 0.0 : 100.0 * wl / tot);
        }
        System.out.println();
        System.out.println("=== Top 20 location strings ===");
        sampleLocs.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed())
                .limit(20)
                .forEach(e -> System.out.printf("  %4d  %s%n", e.getValue(), e.getKey()));
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
