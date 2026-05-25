package com.budgetbuddy.service;

import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import com.budgetbuddy.service.category.MerchantCategoryRules;
import com.budgetbuddy.service.category.MerchantCategoryRules.MatchResult;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
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

/**
 * Empirical-lift probe: measure how many of the production rule misses
 * the draft YAML (draft-rules-backfill.yaml) would catch.
 *
 * <p>Two engines side by side, NEITHER touches production. For every
 * transaction:
 * <ol>
 *   <li>Try the production v2 curated rules.
 *   <li>If they miss, try the draft.
 *   <li>Tally: tx caught only by v2, only by draft, by both, by neither.
 *       Also track per-category counts from the draft so we can see
 *       which rule blocks are pulling weight.
 * </ol>
 */
@EnabledIfSystemProperty(named = "pdf.lift.dir", matches = ".+")
class PdfDraftRulesLiftProbe {

    @Test
    void measure() throws Exception {
        final String dirStr = System.getProperty("pdf.lift.dir");
        final File dir = new File(dirStr);
        final File[] pdfs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        Arrays.sort(pdfs, Comparator.comparing(File::getName));

        final PDFImportService svc = buildService();
        final MerchantCategoryRules v2 = new MerchantCategoryRules("category-rules-v2.yaml");
        final MerchantCategoryRules draft =
                new MerchantCategoryRules("draft-rules-backfill.yaml");

        int total = 0;
        int v2Hits = 0;
        int draftLift = 0;
        int stripLift = 0;          // caught only after noise-prefix stripping
        int stillMiss = 0;
        final Map<String, Integer> draftByCategory = new TreeMap<>();
        final Map<String, Integer> stripByCategory = new TreeMap<>();
        final Map<String, Integer> stillMissTopMerchants = new HashMap<>();

        for (final File pdf : pdfs) {
            try (FileInputStream in = new FileInputStream(pdf)) {
                final ImportResult r =
                        svc.parsePDF(in, pdf.getName(), "lift-probe", null);
                for (final ParsedTransaction t : r.getTransactions()) {
                    total++;
                    final String d = t.getDescription();
                    if (d == null || d.isBlank()) continue;
                    final String norm = d.toLowerCase(Locale.ROOT);

                    // Stage 1: raw description vs production v2 rules
                    final MatchResult v2m = v2.matchWithDetails(norm, norm);
                    if (v2m != null) {
                        v2Hits++;
                        continue;
                    }
                    // Stage 2: raw description vs draft rules
                    final MatchResult dm = draft.matchWithDetails(norm, norm);
                    if (dm != null) {
                        draftLift++;
                        draftByCategory.merge(dm.category, 1, Integer::sum);
                        continue;
                    }
                    // Stage 3: strip noise prefixes (APLPAY / SQ* / TST* /
                    // user-name / etc.) and try BOTH rule engines again.
                    // This measures whether the prefix is the only thing
                    // hiding an otherwise-matchable merchant token.
                    final String stripped = stripNoise(norm);
                    if (!stripped.equals(norm)) {
                        final MatchResult v2s = v2.matchWithDetails(stripped, stripped);
                        if (v2s != null) {
                            stripLift++;
                            stripByCategory.merge("v2: " + v2s.category, 1, Integer::sum);
                            continue;
                        }
                        final MatchResult ds = draft.matchWithDetails(stripped, stripped);
                        if (ds != null) {
                            stripLift++;
                            stripByCategory.merge("draft: " + ds.category, 1, Integer::sum);
                            continue;
                        }
                    }
                    stillMiss++;
                    stillMissTopMerchants.merge(
                            d.trim().toUpperCase(Locale.ROOT), 1, Integer::sum);
                }
            } catch (final Exception e) {
                // skip
            }
        }

        // Report
        System.out.println();
        System.out.println("============================================================");
        System.out.println("    DRAFT RULES — EMPIRICAL LIFT");
        System.out.println("============================================================");
        System.out.printf("Total transactions:               %d%n", total);
        System.out.printf("v2 production hits:               %d (%.1f%%)%n",
                v2Hits, pct(v2Hits, total));
        System.out.printf("Draft lift (raw):                 %d (+%.1f pp)%n",
                draftLift, pct(draftLift, total));
        System.out.printf("Strip-prefix lift (raw misses):   %d (+%.1f pp)%n",
                stripLift, pct(stripLift, total));
        final int combined = v2Hits + draftLift + stripLift;
        System.out.printf("Combined L5 coverage:             %d (%.1f%%)%n",
                combined, pct(combined, total));
        System.out.printf("Still missing after all stages:   %d (%.1f%%)%n",
                stillMiss, pct(stillMiss, total));
        System.out.println();
        System.out.println("=== Draft lift by category (raw match) ===");
        draftByCategory.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> System.out.printf("  %-25s %d%n", e.getKey(), e.getValue()));
        System.out.println();
        System.out.println("=== Strip-prefix lift by (engine: category) ===");
        if (stripByCategory.isEmpty()) {
            System.out.println("  (none — stripping APLPAY / user-name / SQ* / TST* prefixes didn't catch anything new)");
        } else {
            stripByCategory.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> System.out.printf("  %-30s %d%n", e.getKey(), e.getValue()));
        }
        System.out.println();
        System.out.println("=== Top 25 still-missing merchant descriptions ===");
        stillMissTopMerchants.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(25)
                .forEach(e -> System.out.printf("  %4d  %s%n", e.getValue(),
                        e.getKey().length() > 90
                                ? e.getKey().substring(0, 89) + "…"
                                : e.getKey()));
    }

    private static double pct(final int n, final int d) {
        return d == 0 ? 0.0 : 100.0 * n / d;
    }

    /** Anchored noise prefixes we strip before retrying rule match. */
    private static final java.util.List<java.util.regex.Pattern> NOISE_PREFIXES =
            java.util.List.of(
                    java.util.regex.Pattern.compile("(?i)^aplpay\\s+"),
                    java.util.regex.Pattern.compile("(?i)^sq\\s*\\*\\s*"),
                    java.util.regex.Pattern.compile("(?i)^tst\\s*\\*\\s*"),
                    java.util.regex.Pattern.compile("(?i)^px\\s*\\*\\s*"),
                    java.util.regex.Pattern.compile("(?i)^pam\\s*\\*\\s*"),
                    java.util.regex.Pattern.compile("(?i)^pwp\\s+"),
                    java.util.regex.Pattern.compile("(?i)^ezcater\\s*\\*\\s*"),
                    java.util.regex.Pattern.compile("(?i)^bt\\s*\\*\\s*"),
                    java.util.regex.Pattern.compile("(?i)^py\\s*\\*\\s*"),
                    // The cardholder's name shows up as a prefix on every Amex
                    // statement-credit row. Strip it so the real merchant token
                    // (HLU*HULU, AMEX OFFER CREDIT, etc.) lines up with rules.
                    java.util.regex.Pattern.compile("(?i)^agarwal\\s+sumit\\s+kumar\\s+"),
                    java.util.regex.Pattern.compile("(?i)^garima\\s+dipti\\s+agarwal\\s+"),
                    java.util.regex.Pattern.compile("(?i)^mudit\\s+agarwal\\s+"));

    /**
     * Strip stacked noise prefixes (APLPAY / SQ* / TST* / cardholder name)
     * and return the cleaned merchant string. Loops until no prefix
     * matches — handles inputs like {@code APLPAY SQ * MERCHANT} or
     * {@code AGARWAL SUMIT KUMAR APLPAY MERCHANT}.
     */
    private static String stripNoise(final String s) {
        String working = s;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (final java.util.regex.Pattern p : NOISE_PREFIXES) {
                final java.util.regex.Matcher m = p.matcher(working);
                if (m.lookingAt()) {
                    working = working.substring(m.end()).trim();
                    changed = true;
                }
            }
        }
        return working;
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
