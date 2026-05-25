package com.budgetbuddy.service;

import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import com.budgetbuddy.service.category.MerchantCategoryRules;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * Build the LLM-batch-backfill input file. For every transaction the L5
 * curated rules engine doesn't already match, we:
 *
 * <ol>
 *   <li>Strip the "noise prefix" (APLPAY / SQ * / TST* / PX* / PWP /
 *       AGARWAL SUMIT KUMAR user-name prefix, plus trailing phone/ZIP).
 *   <li>Strip a trailing City + 2-letter US state, capturing it separately.
 *   <li>Aggregate by normalised merchant; keep one sample description per
 *       cluster + the recovered city/state.
 *   <li>Write the deduped list as TSV: {@code count\tmerchant\tcity\tstate\toriginal_sample}.
 * </ol>
 *
 * Output is consumed by a human / Claude / Anthropic API as a batch
 * classification task. The TSV is intentionally narrow (≈100-200 rows for
 * 170 PDFs) so one LLM round-trip covers it.
 */
@EnabledIfSystemProperty(named = "pdf.miss.dir", matches = ".+")
class PdfMissBatchProbe {

    private static final Pattern TRAILING_PHONE = Pattern.compile("\\+?\\d{1,3}[\\d\\-]{6,}");
    private static final Pattern TRAILING_ZIP = Pattern.compile("\\d{5}(?:-\\d{4})?");
    private static final Pattern STATE_TOKEN = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern CITY_WORD = Pattern.compile("^[A-Za-z][A-Za-z'\\-]+$");

    private static final Set<String> US_STATES = Set.of(
            "AL","AK","AZ","AR","CA","CO","CT","DE","DC","FL","GA","HI","IA","ID","IL","IN",
            "KS","KY","LA","MA","MD","ME","MI","MN","MO","MS","MT","NC","ND","NE","NH","NJ",
            "NM","NV","NY","OH","OK","OR","PA","RI","SC","SD","TN","TX","UT","VA","VT","WA",
            "WI","WV","WY","PR","VI","GU","AS","MP");

    /** Two-token city prefixes (require 3-word city only when first matches). */
    private static final Set<String> MULTI_WORD_CITY_PREFIX = Set.of(
            "SAN","LOS","SANTA","NEW","ST","MT","FORT","SOUTH","NORTH","EAST","WEST","LAKE",
            "PORT","LITTLE");

    /** Anchored noise prefixes to strip from the merchant string. */
    private static final List<Pattern> NOISE_PREFIXES = List.of(
            Pattern.compile("(?i)^APLPAY\\s+"),
            Pattern.compile("(?i)^SQ\\s*\\*\\s*"),
            Pattern.compile("(?i)^TST\\s*\\*\\s*"),
            Pattern.compile("(?i)^PX\\s*\\*\\s*"),
            Pattern.compile("(?i)^PAM\\s*\\*\\s*"),
            Pattern.compile("(?i)^PWP\\s+"),
            Pattern.compile("(?i)^EZCATER\\s*\\*\\s*"),
            // The user's name on every Amex statement-rebate row
            Pattern.compile("(?i)^AGARWAL\\s+SUMIT\\s+KUMAR\\s+"),
            Pattern.compile("(?i)^GARIMA\\s+DIPTI\\s+AGARWAL\\s+"),
            Pattern.compile("(?i)^MUDIT\\s+AGARWAL\\s+"));

    @Test
    void buildBatch() throws Exception {
        final String dirStr = System.getProperty("pdf.miss.dir");
        final File dir = new File(dirStr);
        final File[] pdfs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        Arrays.sort(pdfs, Comparator.comparing(File::getName));

        final PDFImportService svc = buildService();
        final MerchantCategoryRules rules = new MerchantCategoryRules("category-rules-v2.yaml");

        // cluster key = normalized merchant; value = (count, sample-desc, city, state)
        final Map<String, MissEntry> clusters = new HashMap<>();
        int total = 0, missesRaw = 0;

        for (final File pdf : pdfs) {
            try (FileInputStream in = new FileInputStream(pdf)) {
                final ImportResult r =
                        svc.parsePDF(in, pdf.getName(), "miss-batch-probe", null);
                for (final ParsedTransaction t : r.getTransactions()) {
                    total++;
                    final String desc = t.getDescription();
                    if (desc == null || desc.isBlank()) continue;
                    // Skip if L5 curated rules already matches (those aren't misses).
                    if (rules.matchWithDetails(
                                    desc.toLowerCase(Locale.ROOT),
                                    desc.toLowerCase(Locale.ROOT))
                            != null) {
                        continue;
                    }
                    missesRaw++;

                    // Pre-clean: strip phone / ZIP / city-state trailing,
                    // strip noise prefixes, normalize whitespace.
                    final Cleaned c = clean(desc, t.getLocation());
                    final String key = c.normalizedMerchant;
                    if (key.isEmpty() || key.length() < 2) continue;

                    final MissEntry m = clusters.computeIfAbsent(key, k -> new MissEntry());
                    m.count++;
                    if (m.sampleDesc == null) m.sampleDesc = desc.trim();
                    if (m.city == null && c.city != null) m.city = c.city;
                    if (m.state == null && c.state != null) m.state = c.state;
                    if (m.cleanedMerchant == null) m.cleanedMerchant = c.cleanedMerchant;
                }
            } catch (final Exception e) {
                // skip
            }
        }

        // Write TSV — most frequent first
        final java.nio.file.Path out =
                Paths.get(System.getProperty("pdf.miss.out", "/tmp/pdf_misses_to_classify.tsv"));
        Files.createDirectories(out.getParent() == null ? Paths.get(".") : out.getParent());
        try (BufferedWriter w = new BufferedWriter(new FileWriter(out.toFile(), false))) {
            w.write("count\tmerchant\tcity\tstate\tsample_description\n");
            clusters.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, MissEntry>>comparingInt(
                            e -> e.getValue().count).reversed())
                    .forEach(e -> {
                        final MissEntry m = e.getValue();
                        try {
                            w.write(m.count + "\t"
                                    + safe(m.cleanedMerchant) + "\t"
                                    + safe(m.city) + "\t"
                                    + safe(m.state) + "\t"
                                    + safe(m.sampleDesc) + "\n");
                        } catch (final Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    });
        }

        System.out.println();
        System.out.println("============================================================");
        System.out.println("    MISS BATCH (post-preprocessing, deduped)");
        System.out.println("============================================================");
        System.out.printf("Total transactions:           %d%n", total);
        System.out.printf("L5 curated misses (raw):      %d%n", missesRaw);
        System.out.printf("Distinct merchant clusters:   %d%n", clusters.size());
        System.out.println("TSV written: " + out);
        System.out.println();
        System.out.println("=== Top 40 clusters ===");
        System.out.printf("%-5s %-45s %-20s %-3s %s%n",
                "CNT", "MERCHANT (cleaned)", "CITY", "ST", "SAMPLE");
        clusters.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, MissEntry>>comparingInt(
                        e -> e.getValue().count).reversed())
                .limit(40)
                .forEach(e -> {
                    final MissEntry m = e.getValue();
                    System.out.printf("%-5d %-45s %-20s %-3s %s%n",
                            m.count,
                            truncate(m.cleanedMerchant, 45),
                            truncate(m.city == null ? "" : m.city, 20),
                            m.state == null ? "" : m.state,
                            truncate(m.sampleDesc, 60));
                });
    }

    // ---- preprocessing ----

    static final class MissEntry {
        int count;
        String sampleDesc;
        String cleanedMerchant;
        String city;
        String state;
    }

    private record Cleaned(String cleanedMerchant, String normalizedMerchant,
                           String city, String state) {}

    /**
     * Preprocess a description into (cleanedMerchant, city, state). Steps:
     *  1. If extractor's location is "City, ST" parse it and strip the
     *     trailing tokens from the description as well.
     *  2. Otherwise, run the right-to-left heuristic to find trailing
     *     "City ST" + optional ZIP + optional phone.
     *  3. Strip anchored noise prefixes (APLPAY / SQ* / user-name / etc.).
     *  4. Collapse whitespace and lowercase the result for the cluster key.
     */
    static Cleaned clean(final String desc, final String extractorLoc) {
        String working = desc.trim();
        String city = null;
        String state = null;

        // 1. Extractor location wins, if present + parseable.
        if (extractorLoc != null && !extractorLoc.isBlank()) {
            final Matcher m =
                    Pattern.compile("(?i)^(.+?)[\\s,]+([A-Z]{2})\\s*$")
                            .matcher(extractorLoc.trim());
            if (m.matches() && US_STATES.contains(m.group(2).toUpperCase(Locale.ROOT))) {
                city = m.group(1).trim();
                state = m.group(2).toUpperCase(Locale.ROOT);
            }
        }

        // 2. Strip trailing City ST from description regardless (covers both
        //    the case where extractor already had it AND where it didn't).
        final String[] raw = working.split("\\s+");
        if (raw.length >= 3) {
            int end = raw.length;
            if (TRAILING_PHONE.matcher(raw[end - 1]).matches()) end--;
            if (end > 0 && TRAILING_ZIP.matcher(raw[end - 1]).matches()) end--;
            if (end >= 2
                    && STATE_TOKEN.matcher(raw[end - 1]).matches()
                    && US_STATES.contains(raw[end - 1].toUpperCase(Locale.ROOT))) {
                int cityStart = end - 1;
                int words = 0;
                for (int i = end - 2; i >= 0 && words < 3; i--) {
                    final String tok = raw[i];
                    if (CITY_WORD.matcher(tok).matches()) {
                        // 3-word city only when first token is in whitelist
                        if (words == 2
                                && !MULTI_WORD_CITY_PREFIX.contains(tok.toUpperCase(Locale.ROOT))) {
                            break;
                        }
                        cityStart = i;
                        words++;
                    } else {
                        break;
                    }
                }
                if (words > 0) {
                    final StringBuilder cb = new StringBuilder();
                    for (int i = cityStart; i < end - 1; i++) {
                        if (cb.length() > 0) cb.append(' ');
                        cb.append(raw[i]);
                    }
                    if (city == null) city = cb.toString();
                    if (state == null) state = raw[end - 1].toUpperCase(Locale.ROOT);
                    // Strip city + state (+ optional ZIP + phone) from working string.
                    final StringBuilder mb = new StringBuilder();
                    for (int i = 0; i < cityStart; i++) {
                        if (mb.length() > 0) mb.append(' ');
                        mb.append(raw[i]);
                    }
                    working = mb.toString();
                }
            }
        }

        // 3. Strip noise prefixes (repeatedly, in case of stacked).
        boolean changed = true;
        while (changed) {
            changed = false;
            for (final Pattern p : NOISE_PREFIXES) {
                final Matcher m = p.matcher(working);
                if (m.lookingAt()) {
                    working = working.substring(m.end()).trim();
                    changed = true;
                }
            }
        }
        // Strip trailing tokens that look like reference IDs (PPD ID:, Web ID:, Tel ID:, hash codes).
        working = working.replaceAll("(?i)\\s+(PPD|Web|Tel)\\s+ID:.*$", "").trim();
        // Strip a trailing standalone number cluster (transaction hash).
        working = working.replaceAll("\\s+[\\d\\-]{6,}\\s*$", "").trim();

        // 4. Normalised key (lowercased, single-space).
        final String norm = working.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return new Cleaned(working, norm, city, state);
    }

    // ---- helpers ----

    private static String safe(final String s) {
        return s == null ? "" : s.replace("\t", " ").replace("\n", " ");
    }

    private static String truncate(final String s, final int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
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
