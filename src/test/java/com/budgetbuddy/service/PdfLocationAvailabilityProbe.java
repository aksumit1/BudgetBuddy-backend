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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * Pure data-availability probe (no Spring, no cascade, no production-code
 * changes). For every parsed transaction we:
 *
 * <ol>
 *   <li>Check whether the production extractor already populated
 *       {@code ParsedTransaction.location}.
 *   <li>If not, try a heuristic right-to-left token walk that recognises
 *       trailing {@code [merchant…] CITY ST [ZIP]? [PHONE]?} patterns.
 *   <li>Bucket by source ({@code EXTRACTOR}, {@code HEURISTIC}, {@code NONE})
 *       per institution, and surface the top descriptions in each bucket.
 * </ol>
 *
 * Output answers "how many transactions actually carry a merchant address
 * we could feed to L6 location lookup?" with no cascade-bug interference.
 */
@EnabledIfSystemProperty(named = "pdf.locavail.dir", matches = ".+")
class PdfLocationAvailabilityProbe {

    /** Matches strings that look like 7-15 digits with optional + / dashes. */
    private static final Pattern TRAILING_PHONE =
            Pattern.compile("\\+?\\d{1,3}[\\d\\-]{6,}");
    private static final Pattern TRAILING_ZIP = Pattern.compile("\\d{5}(?:-\\d{4})?");
    private static final Pattern STATE_TOKEN = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern CITY_WORD = Pattern.compile("^[A-Za-z][A-Za-z.'\\-]+$");

    private static final java.util.Set<String> US_STATES = java.util.Set.of(
            "AL","AK","AZ","AR","CA","CO","CT","DE","DC","FL","GA","HI","IA","ID","IL","IN",
            "KS","KY","LA","MA","MD","ME","MI","MN","MO","MS","MT","NC","ND","NE","NH","NJ",
            "NM","NV","NY","OH","OK","OR","PA","RI","SC","SD","TN","TX","UT","VA","VT","WA",
            "WI","WV","WY","PR","VI","GU","AS","MP");

    private static final java.util.Set<String> NON_CITY = java.util.Set.of(
            "HELP.UBER.COM", "WWW", "COM", "ORG", "NET", "HTTPS", "HTTP", "ONLINE", "USA");

    @Test
    void probe() throws Exception {
        final String dirStr = System.getProperty("pdf.locavail.dir");
        final File dir = new File(dirStr);
        final File[] pdfs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        Arrays.sort(pdfs, Comparator.comparing(File::getName));

        final PDFImportService svc = buildService();
        int total = 0, fromExtractor = 0, fromHeuristic = 0, none = 0;
        final Map<String, int[]> byInst = new HashMap<>(); // [total, ext, heur]
        final Map<String, Integer> sampleExt = new HashMap<>();
        final Map<String, Integer> sampleHeur = new HashMap<>();
        final java.util.List<String> sampleNone = new java.util.ArrayList<>();

        for (final File pdf : pdfs) {
            try (FileInputStream in = new FileInputStream(pdf)) {
                final ImportResult result =
                        svc.parsePDF(in, pdf.getName(), "loc-avail-probe", null);
                final String inst =
                        result.getDetectedAccount() == null
                                ? "?"
                                : result.getDetectedAccount().getInstitutionName();
                final int[] bucket = byInst.computeIfAbsent(inst, k -> new int[3]);
                for (final ParsedTransaction t : result.getTransactions()) {
                    total++;
                    bucket[0]++;
                    if (t.getLocation() != null && !t.getLocation().isBlank()) {
                        fromExtractor++;
                        bucket[1]++;
                        sampleExt.merge(t.getLocation().trim(), 1, Integer::sum);
                    } else {
                        final String[] split = heuristicSplit(t.getDescription());
                        if (split != null) {
                            fromHeuristic++;
                            bucket[2]++;
                            final String key = split[0] + ", " + split[1];
                            sampleHeur.merge(key, 1, Integer::sum);
                        } else {
                            none++;
                            if (sampleNone.size() < 25
                                    && t.getDescription() != null
                                    && !t.getDescription().isBlank()) {
                                sampleNone.add(t.getDescription());
                            }
                        }
                    }
                }
            } catch (final Exception e) {
                // skip
            }
        }

        System.out.println();
        System.out.println("============================================================");
        System.out.println("    LOCATION AVAILABILITY (no Spring, no cascade)");
        System.out.println("============================================================");
        System.out.printf("Total transactions:        %d%n", total);
        System.out.printf("  from production extractor: %d (%.1f%%)%n",
                fromExtractor, pct(fromExtractor, total));
        System.out.printf("  from offline heuristic:    %d (+%.1f%% on top)%n",
                fromHeuristic, pct(fromHeuristic, total));
        System.out.printf("  combined coverage:         %d (%.1f%%)%n",
                fromExtractor + fromHeuristic, pct(fromExtractor + fromHeuristic, total));
        System.out.printf("  still no location:         %d (%.1f%%)%n",
                none, pct(none, total));
        System.out.println();
        System.out.println("=== Per-institution coverage ===");
        System.out.printf("%-25s | %-6s | %-6s | %-6s | %-6s | %-6s%n",
                "INSTITUTION", "TOTAL", "EXT", "HEUR", "BOTH", "BOTH%");
        System.out.println("--------------------------+--------+--------+--------+--------+--------");
        byInst.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, int[]>>comparingInt(
                        e -> e.getValue()[0]).reversed())
                .forEach(e -> {
                    final int[] b = e.getValue();
                    final int both = b[1] + b[2];
                    System.out.printf("%-25s | %-6d | %-6d | %-6d | %-6d | %5.1f%%%n",
                            e.getKey(), b[0], b[1], b[2], both, pct(both, b[0]));
                });
        System.out.println();
        System.out.println("=== Top 15 heuristic-recovered locations ===");
        sampleHeur.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed())
                .limit(15)
                .forEach(e -> System.out.printf("  %4d  %s%n", e.getValue(), e.getKey()));
        System.out.println();
        System.out.println("=== Top 15 extractor locations (sanity) ===");
        sampleExt.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed())
                .limit(15)
                .forEach(e -> System.out.printf("  %4d  %s%n", e.getValue(), e.getKey()));
        System.out.println();
        System.out.println("=== 25 descriptions with NO recoverable location ===");
        sampleNone.forEach(s -> System.out.println("  " + s));
    }

    private static double pct(final int n, final int d) {
        return d == 0 ? 0.0 : 100.0 * n / d;
    }

    /**
     * Right-to-left token walk. Trims trailing phone + ZIP, then expects the
     * last surviving token to be a 2-letter US state, with 1-3 preceding
     * city tokens. Anything before the city is merchant.
     */
    private static String[] heuristicSplit(final String desc) {
        if (desc == null || desc.length() < 8) return null;
        final String[] raw = desc.trim().split("\\s+");
        if (raw.length < 3) return null;
        int end = raw.length;
        if (end > 0 && TRAILING_PHONE.matcher(raw[end - 1]).matches()) end--;
        if (end > 0 && TRAILING_ZIP.matcher(raw[end - 1]).matches()) end--;
        if (end < 2) return null;
        final String state = raw[end - 1].toUpperCase(Locale.ROOT);
        if (!STATE_TOKEN.matcher(state).matches()) return null;
        if (!US_STATES.contains(state)) return null;
        int cityStart = end - 1;
        int words = 0;
        for (int i = end - 2; i >= 0 && words < 3; i--) {
            final String tok = raw[i];
            if (CITY_WORD.matcher(tok).matches()) {
                cityStart = i;
                words++;
            } else {
                break;
            }
        }
        if (words == 0) return null;
        final StringBuilder city = new StringBuilder();
        for (int i = cityStart; i < end - 1; i++) {
            if (city.length() > 0) city.append(' ');
            city.append(raw[i]);
        }
        if (NON_CITY.contains(city.toString().toUpperCase(Locale.ROOT))) return null;
        final StringBuilder merchant = new StringBuilder();
        for (int i = 0; i < cityStart; i++) {
            if (merchant.length() > 0) merchant.append(' ');
            merchant.append(raw[i]);
        }
        if (merchant.length() < 3) return null;
        return new String[] {city.toString(), state, merchant.toString()};
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
