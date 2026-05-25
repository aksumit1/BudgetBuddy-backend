package com.budgetbuddy.service;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import com.budgetbuddy.service.category.CategoryCascade;
import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Standalone probe: for every parsed transaction we post-process the
 * description with a heuristic "City, ST" splitter (we don't touch the
 * production extractors — that's the parallel agent's territory), thread
 * the extracted city/state/country into the {@link CategoryCascade.Context},
 * and report how many transactions L6 now picks up.
 *
 * <p>Two passes per transaction so the diff is visible:
 * <ol>
 *   <li>Cascade WITHOUT location (description only)
 *   <li>Cascade WITH the heuristic location threaded in
 * </ol>
 *
 * The location heuristic recognises three common patterns at the END of a
 * description:
 * <ul>
 *   <li>{@code MERCHANT NAME CITY ST}   → US two-letter state
 *   <li>{@code MERCHANT NAME CITY ST 12345-6789} → plus ZIP
 *   <li>{@code MERCHANT NAME CITY, ST}  → comma-separated
 * </ul>
 *
 * Doesn't try to be exhaustive — non-US locations, online-only merchants,
 * and degenerate Apple-Pay strings are left null. We just want to measure
 * the *floor* of how much location data is actually present.
 */
@EnabledIfSystemProperty(named = "pdf.locx.dir", matches = ".+")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestPropertySource(
        properties = {
            "app.aws.dynamodb.table-prefix=TestBudgetBuddy",
            // Network sources OFF — we want to see what data is *available*,
            // not wait minutes for Wikidata timeouts. Flip these on after we
            // know the splitter is producing good city/state values.
            "app.category.overpass.enabled=false",
            "app.category.nominatim.enabled=false",
            "app.category.wikidata.enabled=false"
        })
class PdfCascadeWithLocationProbeTest {

    @Autowired private CategoryCascade cascade;
    @Autowired private PDFImportService pdfImport;

    /** Phone-number-shaped trailing token to strip before scanning. */
    private static final Pattern TRAILING_PHONE =
            Pattern.compile("\\+?[\\d]{1,3}[\\d\\-]{6,}$");
    /** ZIP-shaped trailing token. */
    private static final Pattern TRAILING_ZIP = Pattern.compile("\\d{5}(?:-\\d{4})?$");
    /** Two-letter all-caps US state. */
    private static final Pattern STATE_TOKEN = Pattern.compile("^[A-Z]{2}$");
    /** Valid "word" token in a city name (allows internal apostrophes/hyphens). */
    private static final Pattern CITY_WORD = Pattern.compile("^[A-Za-z][A-Za-z.'\\-]+$");

    /** US two-letter state/territory codes we trust. */
    private static final java.util.Set<String> US_STATES = java.util.Set.of(
            "AL","AK","AZ","AR","CA","CO","CT","DE","DC","FL","GA","HI","IA","ID","IL","IN",
            "KS","KY","LA","MA","MD","ME","MI","MN","MO","MS","MT","NC","ND","NE","NH","NJ",
            "NM","NV","NY","OH","OK","OR","PA","RI","SC","SD","TN","TX","UT","VA","VT","WA",
            "WI","WV","WY","PR","VI","GU","AS","MP");

    /** Skip these "city" words — they're noise from URL-shaped strings. */
    private static final java.util.Set<String> NON_CITY = java.util.Set.of(
            "HELP.UBER.COM", "HTTPS://PROD.", "WWW", "COM", "ORG", "NET");

    @Test
    void probe() throws Exception {
        final String dirStr = System.getProperty("pdf.locx.dir");
        final File dir = new File(dirStr);
        final File[] pdfs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        Arrays.sort(pdfs, Comparator.comparing(File::getName));

        int totalTx = 0;
        int locFromExtractor = 0;
        int locFromHeuristic = 0;
        int locTotal = 0;
        final Map<String, Integer> layerBefore = new TreeMap<>();
        final Map<String, Integer> layerAfter = new TreeMap<>();
        final Map<String, Integer> l6Hits = new TreeMap<>();
        final java.util.List<String> l6SampleHits = new java.util.ArrayList<>();

        for (final File pdf : pdfs) {
            try (FileInputStream in = new FileInputStream(pdf)) {
                final ImportResult result =
                        pdfImport.parsePDF(in, pdf.getName(), "loc-probe", null);
                for (final ParsedTransaction t : result.getTransactions()) {
                    totalTx++;
                    final String desc = t.getDescription();
                    if (desc == null || desc.isBlank()) continue;

                    // ---- before: no location ----
                    final CategoryCascade.Context ctxNone =
                            CategoryCascade.Context.builder()
                                    .userId("loc-probe")
                                    .merchantName(desc)
                                    .description(desc)
                                    .normalizedDescription(desc.toLowerCase(Locale.ROOT))
                                    .build();
                    final CategoryResult before = cascade.classify(ctxNone);
                    layerBefore.merge(
                            before == null ? "MISS" : before.getSource(), 1, Integer::sum);

                    // ---- collect location: extractor first, fall back to heuristic ----
                    String[] cityState = parseLocation(t.getLocation());
                    String source = cityState == null ? null : "EXTRACTOR";
                    if (cityState == null) {
                        cityState = heuristicSplit(desc);
                        if (cityState != null) source = "HEURISTIC";
                    }
                    String merchantStripped = desc;
                    if (cityState != null) {
                        locTotal++;
                        if ("EXTRACTOR".equals(source)) locFromExtractor++;
                        else locFromHeuristic++;
                        // For the cascade input we strip the trailing CITY ST from
                        // the merchant name so APLPAY-style prefixes line up.
                        merchantStripped = cityState[2] == null ? desc : cityState[2];
                    }

                    // ---- after: location threaded ----
                    final CategoryCascade.Context.Builder b =
                            CategoryCascade.Context.builder()
                                    .userId("loc-probe")
                                    .merchantName(merchantStripped)
                                    .description(desc)
                                    .normalizedDescription(
                                            desc.toLowerCase(Locale.ROOT));
                    if (cityState != null) {
                        b.city(cityState[0]).state(cityState[1]).country("US");
                    }
                    final CategoryResult after = cascade.classify(b.build());
                    final String layerAfterName =
                            after == null ? "MISS" : after.getSource();
                    layerAfter.merge(layerAfterName, 1, Integer::sum);

                    // Track L6 wins explicitly + sample
                    if (after != null && "LOCATION_LOOKUP".equals(after.getSource())) {
                        l6Hits.merge(after.getCategoryPrimary(), 1, Integer::sum);
                        if (l6SampleHits.size() < 20) {
                            l6SampleHits.add(
                                    desc + "  →  " + after.getCategoryPrimary());
                        }
                    }
                }
            } catch (final Exception e) {
                // skip
            }
        }

        System.out.println();
        System.out.println("============================================================");
        System.out.println("    CASCADE WITH LOCATION-HEURISTIC THREADED (L6 enabled probe)");
        System.out.println("============================================================");
        System.out.printf("Total transactions:          %d%n", totalTx);
        System.out.printf("Tx with parsed location:     %d (%.1f%%)%n",
                locTotal, totalTx == 0 ? 0.0 : 100.0 * locTotal / totalTx);
        System.out.printf("  from extractor:            %d%n", locFromExtractor);
        System.out.printf("  from heuristic split:      %d%n", locFromHeuristic);
        System.out.println();
        System.out.println("=== Layer counts: BEFORE (no location) ===");
        layerBefore.forEach((k, v) -> System.out.printf("  %-30s %d%n", k, v));
        System.out.println();
        System.out.println("=== Layer counts: AFTER (location threaded) ===");
        layerAfter.forEach((k, v) -> System.out.printf("  %-30s %d%n", k, v));
        System.out.println();
        System.out.println("=== L6 LOCATION_LOOKUP wins by category ===");
        if (l6Hits.isEmpty()) {
            System.out.println("  (none — but per-source network was disabled. "
                    + "Re-run with -Dapp.category.overpass.enabled=true)");
        } else {
            l6Hits.forEach((k, v) -> System.out.printf("  %-25s %d%n", k, v));
        }
        if (!l6SampleHits.isEmpty()) {
            System.out.println();
            System.out.println("=== Sample L6 matches ===");
            l6SampleHits.forEach(s -> System.out.println("  " + s));
        }
    }

    /**
     * Parse "City, ST" / "City ST" out of the production extractor's
     * already-populated {@code location} field. Returns
     * {@code [city, state, mname]} where {@code mname} is null (extractor
     * already stripped the merchant suffix).
     */
    private static String[] parseLocation(final String loc) {
        if (loc == null || loc.isBlank()) return null;
        final Matcher m = Pattern.compile("(?i)^(.+?)[\\s,]+([A-Z]{2})\\s*$").matcher(loc.trim());
        if (m.matches() && US_STATES.contains(m.group(2).toUpperCase(Locale.ROOT))) {
            return new String[] {m.group(1).trim(), m.group(2).toUpperCase(Locale.ROOT), null};
        }
        return null;
    }

    /**
     * Right-to-left token walk. Scans the trailing tokens of a description for
     * the pattern <code>(phone)? (zip)? STATE city(1-3 words) ...merchant</code>
     * and returns {@code [city, state, merchantStripped]}, or null.
     *
     * <p>The token walk is more reliable than a single regex because the city
     * name can be 1-3 words with internal punctuation and the line may end in
     * a phone number, a ZIP, or both. Regexes that try to capture this with
     * non-greedy quantifiers consistently mis-grab the city.
     */
    private static String[] heuristicSplit(final String desc) {
        if (desc == null || desc.length() < 8) return null;
        // Tokenise on whitespace; preserve order. Strip empty tokens that come
        // from multiple-space runs.
        final String[] raw = desc.trim().split("\\s+");
        if (raw.length < 3) return null;
        int end = raw.length; // exclusive
        // Strip trailing phone-shaped token.
        if (end > 0 && TRAILING_PHONE.matcher(raw[end - 1]).matches()) end--;
        // Strip trailing ZIP-shaped token.
        if (end > 0 && TRAILING_ZIP.matcher(raw[end - 1]).matches()) end--;
        if (end < 2) return null;
        // Last remaining token must be a 2-letter US state.
        final String state = raw[end - 1].toUpperCase(Locale.ROOT);
        if (!STATE_TOKEN.matcher(state).matches()) return null;
        if (!US_STATES.contains(state)) return null;
        // Walk back at most 3 tokens for the city. A token is a city-word if
        // it looks like a name (alpha + optional dash/apostrophe). We stop as
        // soon as we hit a non-city-word (digit-bearing, alphanumeric mix,
        // sentinel like "PPD", etc.) so we don't gobble half the description.
        int cityStart = end - 1; // index of state; we'll walk back
        int cityWords = 0;
        for (int i = end - 2; i >= 0 && cityWords < 3; i--) {
            final String tok = raw[i];
            if (CITY_WORD.matcher(tok).matches()) {
                cityStart = i;
                cityWords++;
            } else {
                break;
            }
        }
        if (cityWords == 0) return null;
        final StringBuilder city = new StringBuilder();
        for (int i = cityStart; i < end - 1; i++) {
            if (city.length() > 0) city.append(' ');
            city.append(raw[i]);
        }
        if (NON_CITY.contains(city.toString().toUpperCase(Locale.ROOT))) return null;
        // Merchant is everything before the city tokens.
        final StringBuilder merchant = new StringBuilder();
        for (int i = 0; i < cityStart; i++) {
            if (merchant.length() > 0) merchant.append(' ');
            merchant.append(raw[i]);
        }
        if (merchant.length() < 3) return null;
        return new String[] {city.toString(), state, merchant.toString()};
    }
}
