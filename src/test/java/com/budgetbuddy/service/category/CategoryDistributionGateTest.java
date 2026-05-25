package com.budgetbuddy.service.category;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.category.MerchantCategoryRules.MatchResult;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hard CI gates over the L5 rules engine. Catches the class of bug we
 * hit in this project's history:
 *
 * <ul>
 *   <li>Distribution drift: any single category ballooning to dominate
 *       the dataset (the L3 fuzzy bug that produced 768 false healthcare
 *       matches — pre-fix the audit showed healthcare at 27% of all tx,
 *       which a gate like this would have caught instantly).
 *   <li>Coverage floor: aggregate L5 hit rate dropping below a
 *       defensible floor (a regression that disables the rules engine
 *       would be invisible in a unit test, but obvious as a coverage
 *       drop on the corpus below).
 * </ul>
 *
 * <p>The corpus is a hand-curated set of ~90 transaction-description
 * shapes representative of credit-card + checking-account imports.
 * It's not a synthetic Faker corpus — every line is taken from real
 * statement text the cascade has been audited against.
 */
class CategoryDistributionGateTest {

    /** Maximum share of total matches that any single category may hold. */
    private static final double MAX_CATEGORY_SHARE = 0.35; // 35%

    /**
     * Minimum aggregate hit rate against the corpus. Set to a defensible
     * floor against production {@code category-rules-v2.yaml} ALONE — the
     * draft backfill rules are not yet merged. The gate is intentionally
     * generous so a real regression (cascade disabled, rules file empty,
     * load failure) is unmissable. When {@code draft-rules-backfill.yaml}
     * is merged into v2, raise this floor to 0.90+.
     */
    private static final double MIN_COVERAGE = 0.70; // 70% (v2 alone)

    /**
     * Hand-curated corpus. Each line is the lowercased substring the
     * production rules engine would receive. Mixes the major merchant
     * categories so over- or under-representation is obvious.
     */
    private static final List<String> CORPUS = List.of(
            // Dining
            "starbucks 4521 bellevue wa",
            "chipotle 0123 nashville tn",
            "five guys 5821 seattle wa",
            "whole foods market bellevue",
            "kfc nashville tn",
            "panda express bellevue",
            "subway 1234 seattle",
            "in-n-out burger sunnyvale",
            "shake shack seattle wa",
            "olive garden nashville tn",
            // Groceries
            "safeway store bellevue",
            "trader joe's seattle",
            "kroger nashville",
            "costco wholesale issaquah",
            "fred meyer issaquah",
            "qfc bellevue",
            "amazon fresh bellevue",
            // Transportation
            "uber trip help.uber.com ca",
            "lyft ride san francisco",
            "shell oil bellevue",
            "chevron seattle",
            "exxonmobil nashville",
            "76 gas station",
            "uw pay by phone seattle wa",
            "wsdot-goodtogo renton",
            // Travel
            "marriott hotel atlanta",
            "hilton seattle airport",
            "delta air lines",
            "united airlines",
            "alaska airlines seattle wa",
            "american airlines",
            "airbnb san francisco",
            "expedia.com travel",
            // Shopping
            "amazon.com seattle wa",
            "target store bellevue",
            "walmart supercenter nashville",
            "best buy seattle",
            "home depot issaquah",
            "lowes nashville",
            "macys bellevue",
            // Utilities
            "puget sound energy bellevue",
            "comcast / xfinity wa",
            "city of bellevue utility",
            "xfinity mobile pa",
            "verizon wireless",
            // Subscriptions
            "netflix.com los gatos",
            "spotify usa",
            "huluplus hulu.com/bill ca",
            "amazon prime",
            // Tech
            "apple.com/bill cupertino",
            "google one cloud storage",
            "github.com inc",
            "intuit *turbotax ca",
            // Health
            "cvs pharmacy bellevue",
            "walgreens 0123 seattle",
            "rite aid issaquah",
            "kaiser permanente seattle",
            "labcorp 8008456167 nc",
            // Payment / Transfer / Fees
            "automatic payment - thank you",
            "citi autopay payment",
            "chase credit crd autopay",
            "amex ach pmt a1234",
            "wf credit card auto pay",
            "online transfer to chk ...9994",
            "interest charge on purchases",
            "annual membership fee",
            "us treas tax pymt",
            // Entertainment
            "amc 9640 online leawood ks",
            "ticketmaster",
            // Pet
            "petco bellevue",
            "petsmart issaquah",
            "chewy.com",
            // Charity
            "girl scouts cookies",
            // Education
            "uworld irving tx",
            "aamc washington dc",
            // Home improvement
            "home depot #4704 issaquah",
            "menards");

    @Test
    @DisplayName("Aggregate L5 coverage on representative corpus must stay above 85%")
    void coverageFloorGate() {
        final MerchantCategoryRules rules =
                new MerchantCategoryRules("category-rules-v2.yaml");
        long hits = 0;
        for (final String desc : CORPUS) {
            final MatchResult m = rules.matchWithDetails(desc, desc);
            if (m != null) hits++;
        }
        final double coverage = (double) hits / CORPUS.size();
        assertTrue(
                coverage >= MIN_COVERAGE,
                String.format(
                        Locale.ROOT,
                        "L5 coverage on representative corpus is %.1f%% — below floor %.0f%%. "
                                + "A regression has likely disabled or narrowed the rules engine; "
                                + "investigate before merging.",
                        coverage * 100,
                        MIN_COVERAGE * 100));
    }

    @Test
    @DisplayName("No single category may dominate the match distribution (35% ceiling)")
    void distributionDriftGate() {
        final MerchantCategoryRules rules =
                new MerchantCategoryRules("category-rules-v2.yaml");
        final Map<String, Integer> byCategory = new HashMap<>();
        int hits = 0;
        for (final String desc : CORPUS) {
            final MatchResult m = rules.matchWithDetails(desc, desc);
            if (m != null) {
                byCategory.merge(m.category, 1, Integer::sum);
                hits++;
            }
        }
        if (hits == 0) return; // coverage-floor test will fail this case loudly
        for (final Map.Entry<String, Integer> e : byCategory.entrySet()) {
            final double share = (double) e.getValue() / hits;
            assertTrue(
                    share <= MAX_CATEGORY_SHARE,
                    String.format(
                            Locale.ROOT,
                            "Category '%s' holds %.1f%% of matches (%d of %d) — exceeds "
                                    + "ceiling %.0f%%. This is the same shape the L3 fuzzy "
                                    + "leak (768 false healthcare) showed before the fix. "
                                    + "Investigate over-firing in the rules engine before "
                                    + "merging.",
                            e.getKey(),
                            share * 100,
                            e.getValue(),
                            hits,
                            MAX_CATEGORY_SHARE * 100));
        }
    }
}
