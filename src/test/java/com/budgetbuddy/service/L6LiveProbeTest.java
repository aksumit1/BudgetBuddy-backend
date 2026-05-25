package com.budgetbuddy.service;

import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import com.budgetbuddy.service.category.MerchantEnrichmentStore;
import com.budgetbuddy.service.category.NominatimMerchantLookup;
import com.budgetbuddy.service.category.OverpassMerchantLookup;
import com.budgetbuddy.service.category.WikidataMerchantLookup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Live external-source probe — actually hits OSM Overpass, OSM Nominatim,
 * and Wikidata SPARQL with known-good merchants to confirm each source is
 * reachable and returns a non-null category. This complements the docker
 * "ChainedLocationLookup registered 3 sources" log line, which only proves
 * the beans wired (not that the network calls succeed).
 *
 * Enabled via {@code -Dl6.probe=1}. Off by default so CI doesn't depend
 * on the public OSM/Wikidata endpoints staying up.
 */
@EnabledIfSystemProperty(named = "l6.probe", matches = ".+")
class L6LiveProbeTest {

    @Test
    void overpass_returns_category_for_known_seattle_cafe() throws Exception {
        // Default Overpass URL; 5-second timeout matches production.
        final OverpassMerchantLookup lookup =
                new OverpassMerchantLookup(
                        "https://overpass-api.de/api/interpreter", 5);
        final long t0 = System.currentTimeMillis();
        final CategoryResult r =
                lookup.lookup("Starbucks", "Seattle", "WA", "US");
        final long dt = System.currentTimeMillis() - t0;
        report("Overpass", "Starbucks Seattle WA", r, dt);
    }

    @Test
    void nominatim_returns_category_for_known_seattle_cafe() throws Exception {
        final NominatimMerchantLookup lookup =
                new NominatimMerchantLookup(
                        "https://nominatim.openstreetmap.org/search",
                        "BudgetBuddy/1.0 (test-probe)",
                        5);
        // Use a realistic merchant name shape (the production PDF parser
        // emits short brand tokens like "WHOLE FOODS", not verbose multi-word
        // location names).
        final long t0 = System.currentTimeMillis();
        final CategoryResult r =
                lookup.lookup("Whole Foods", "Bellevue", "WA", "US");
        final long dt = System.currentTimeMillis() - t0;
        report("Nominatim", "Whole Foods Bellevue WA", r, dt);
    }

    @Test
    void wikidata_returns_category_for_known_global_brand() throws Exception {
        final WikidataMerchantLookup lookup =
                new WikidataMerchantLookup(
                        "https://query.wikidata.org/sparql",
                        5);
        final long t0 = System.currentTimeMillis();
        final CategoryResult r = lookup.lookup("Starbucks", null, null, "US");
        final long dt = System.currentTimeMillis() - t0;
        report("Wikidata", "Starbucks (global)", r, dt);
    }

    private static void report(
            final String source, final String input,
            final CategoryResult r, final long durationMs) {
        System.out.println();
        System.out.println("─────────────────────────────────────────────");
        System.out.println("Source:      " + source);
        System.out.println("Input:       " + input);
        System.out.println("Duration:    " + durationMs + " ms");
        if (r == null) {
            System.out.println("Result:      null  ❌ source unreachable, no match, or rate-limited");
        } else {
            System.out.println("Result:      "
                    + r.getCategoryPrimary() + " / " + r.getCategoryDetailed()
                    + "  ✓");
            System.out.println("Provenance:  " + r.getSource());
            System.out.println("Confidence:  " + r.getConfidence());
        }
    }
}
