package com.budgetbuddy.service.category;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps issuer-provided MCC-style category trailers to BudgetBuddy
 * internal categories. The issuer (Amex, Discover, etc.) already
 * categorised the transaction for us — we'd be foolish to ignore that
 * signal. This is L2.5 of the cascade: trusted higher than the
 * merchant DB (L3) because it comes directly from the card network,
 * but lower than L0 user overrides and explicit MCC codes (L2).
 *
 * <h3>Lookup shape</h3>
 *
 * The trailer arrives in one of two places:
 * <ul>
 *   <li>{@code ParsedTransaction.importerCategoryPrimary} — when the
 *       PDF parser successfully extracted it into a dedicated field.
 *   <li>Inline in {@code description} — common with Amex statements
 *       where the trailer follows the merchant location.
 * </ul>
 *
 * {@link #map(String)} handles a single trailer string;
 * {@link #scan(String)} scans a longer description for any known
 * trailer substring.
 *
 * <h3>Mapping table</h3>
 *
 * Entries cover the trailer phrases observed across Amex / Discover /
 * Chase / Citi statements. The mapping is conservative — vague
 * trailers like "GOODS/SERVICES" deliberately have NO entry so the
 * cascade can fall through to a more specific signal.
 */
public final class IssuerCategoryMapper {

    private IssuerCategoryMapper() { /* utility */ }

    /** Confidence to attach when an issuer trailer is matched. */
    public static final double TRAILER_CONFIDENCE = 0.92;

    /**
     * Lowercased trailer phrase → internal category. Phrases longer
     * than one word win specificity-wise; the scan iterates entries in
     * descending length to favour the most specific match.
     */
    private static final Map<String, String> TRAILER_TO_CATEGORY = Map.ofEntries(
            // --- Dining ---
            Map.entry("restaurant", "dining"),
            Map.entry("restaurants", "dining"),
            Map.entry("misc eating places", "dining"),
            Map.entry("eating places", "dining"),
            Map.entry("bar/nightclub", "dining"),
            Map.entry("bars & taverns", "dining"),
            Map.entry("fast food", "dining"),
            Map.entry("caterers", "dining"),
            // Common MCC-style descriptors (US + intl English)
            Map.entry("pizza restaurants", "dining"),
            Map.entry("coffee shops", "dining"),
            Map.entry("tea houses", "dining"),
            Map.entry("ice cream parlors", "dining"),
            Map.entry("bakeries", "dining"),
            Map.entry("delicatessen", "dining"),
            Map.entry("wine bars", "dining"),
            Map.entry("public houses", "dining"),   // UK pubs
            Map.entry("takeaway food shops", "dining"),
            Map.entry("dining places", "dining"),

            // --- Groceries ---
            Map.entry("grocery", "groceries"),
            Map.entry("grocery stores", "groceries"),
            Map.entry("supermarket", "groceries"),
            Map.entry("supermarkets", "groceries"),
            Map.entry("convenience stores", "groceries"),
            Map.entry("liquor stores", "groceries"),
            Map.entry("off licence", "groceries"),       // UK
            Map.entry("bottle shops", "groceries"),      // AU
            Map.entry("farmers market", "groceries"),
            Map.entry("food stores", "groceries"),

            // --- Transportation ---
            Map.entry("parking", "transportation"),
            Map.entry("parking lots", "transportation"),
            Map.entry("parking lots & garages", "transportation"),
            Map.entry("taxicab", "transportation"),
            Map.entry("taxicab & limousine", "transportation"),
            Map.entry("taxicabs", "transportation"),
            Map.entry("bus lines", "transportation"),
            Map.entry("passenger railways", "transportation"),
            Map.entry("service stations", "transportation"),
            Map.entry("service stn", "transportation"),
            Map.entry("automotive fuel", "transportation"),
            Map.entry("petrol stations", "transportation"),  // AU/UK
            Map.entry("tolls and bridge fees", "transportation"),
            Map.entry("toll bridge", "transportation"),
            Map.entry("bicycle shops", "transportation"),

            // --- Travel ---
            // (auto/car rental lives here, not transportation — rental
            // cars are vacation spending on a typical user's statement)
            Map.entry("airport", "travel"),
            Map.entry("airport & terminal", "travel"),
            Map.entry("airport terminals", "travel"),
            Map.entry("airlines", "travel"),
            Map.entry("airlines & air carriers", "travel"),
            Map.entry("lodging", "travel"),
            Map.entry("lodging - hotels motels", "travel"),
            Map.entry("hotels & motels", "travel"),
            Map.entry("cruise lines", "travel"),
            Map.entry("travel agencies", "travel"),
            Map.entry("auto rental", "travel"),
            Map.entry("car rental", "travel"),
            Map.entry("rental car", "travel"),

            // --- Utilities ---
            Map.entry("cable & pay tv", "utilities"),
            Map.entry("cable services", "utilities"),
            Map.entry("telecommunication services", "utilities"),
            Map.entry("telephone services", "utilities"),
            Map.entry("telephone equipment", "utilities"),
            Map.entry("connectivity", "utilities"),
            Map.entry("utilities - electric", "utilities"),
            Map.entry("utilities - gas", "utilities"),

            // --- Tech ---
            Map.entry("computer network/info", "tech"),
            Map.entry("computer network", "tech"),
            Map.entry("information retrieval", "tech"),
            Map.entry("computer services", "tech"),
            Map.entry("electronics stores", "tech"),

            // --- Subscriptions ---
            Map.entry("subscription", "subscriptions"),
            Map.entry("digital goods", "subscriptions"),

            // --- Shopping ---
            Map.entry("misc/specialty retail", "shopping"),
            Map.entry("gift card", "shopping"),
            Map.entry("department store", "shopping"),
            Map.entry("discount store", "shopping"),
            Map.entry("jewelry", "shopping"),
            Map.entry("clothing stores", "shopping"),
            Map.entry("apparel & accessories", "shopping"),
            Map.entry("bookstores", "shopping"),
            Map.entry("florists", "shopping"),

            // --- Home improvement ---
            Map.entry("hardware stores", "home improvement"),
            Map.entry("hardware equipment", "home improvement"),
            Map.entry("home improvement", "home improvement"),

            // --- Health ---
            Map.entry("health care", "health"),
            Map.entry("drug stores & pharmacies", "health"),
            Map.entry("pharmacies", "health"),
            Map.entry("medical services", "health"),
            Map.entry("medical & dental", "health"),
            Map.entry("beauty shops", "health"),
            Map.entry("barber shops", "health"),
            Map.entry("hair salons", "health"),

            // --- Fees / government / taxes / banking ---
            Map.entry("government services", "fees"),
            Map.entry("tax payments", "fees"),
            Map.entry("federal tax payments", "fees"),
            Map.entry("professional services", "fees"),
            Map.entry("legal services", "fees"),
            Map.entry("real estate agents", "fees"),
            Map.entry("postal services", "fees"),
            Map.entry("court costs", "fees"),
            Map.entry("fines", "fees"),
            Map.entry("council tax", "fees"),               // UK
            // Banking-specific MCC trailers — explicit so they don't
            // accidentally fall to OTHER or land in "payment"
            Map.entry("financial institutions", "fees"),
            Map.entry("financial services", "fees"),
            Map.entry("brokerage fees", "fees"),
            Map.entry("management fees", "fees"),
            Map.entry("advisory fees", "fees"),
            Map.entry("wire transfer fee", "fees"),
            Map.entry("foreign transaction fee", "fees"),
            Map.entry("nsf fee", "fees"),
            Map.entry("overdraft fee", "fees"),
            Map.entry("atm fee", "fees"),
            Map.entry("stop payment fee", "fees"),
            Map.entry("returned check fee", "fees"),
            Map.entry("money order", "fees"),
            Map.entry("cashier's check", "fees"),
            Map.entry("cashiers check", "fees"),

            // --- Insurance ---
            Map.entry("insurance sales", "insurance"),
            Map.entry("insurance premiums", "insurance"),
            Map.entry("insurance services", "insurance"),

            // --- Education ---
            Map.entry("colleges & universities", "education"),
            Map.entry("schools - elementary", "education"),
            Map.entry("educational services", "education"),

            // --- Entertainment ---
            Map.entry("entertainment", "entertainment"),
            Map.entry("amusement parks", "entertainment"),
            Map.entry("motion picture theaters", "entertainment"),
            Map.entry("theatrical producers", "entertainment"),
            Map.entry("membership clubs", "entertainment"),
            Map.entry("country clubs", "entertainment"),
            Map.entry("sporting events", "entertainment"),

            // --- Pet ---
            Map.entry("veterinary services", "pet"),
            Map.entry("pet shops", "pet"),
            Map.entry("pet stores", "pet"),
            Map.entry("animal hospitals", "pet"),

            // --- Charity ---
            Map.entry("charitable organizations", "charity"),
            Map.entry("religious organizations", "charity"),
            Map.entry("non-profit organizations", "charity"),

            // --- Childcare ---
            Map.entry("child care services", "childcare"),
            Map.entry("daycare services", "childcare"),
            Map.entry("nursery schools", "childcare"),

            // --- Housing ---
            Map.entry("real estate", "housing"),
            Map.entry("property management", "housing"),

            // --- Auto-repair (still transportation) ---
            Map.entry("automotive repair shops", "transportation"),
            Map.entry("tire retreading & repair", "transportation"),
            Map.entry("auto body repair", "transportation"));

    /** Phrases sorted by descending length so longer phrases match first. */
    private static final List<String> TRAILERS_BY_LENGTH = TRAILER_TO_CATEGORY.keySet()
            .stream()
            .sorted((a, b) -> Integer.compare(b.length(), a.length()))
            .toList();

    /**
     * Map an exact trailer phrase to a category. Case-insensitive,
     * leading/trailing whitespace tolerated. Returns null when the
     * phrase isn't in the table or input is empty.
     */
    public static String map(final String trailer) {
        if (trailer == null) return null;
        final String key = trailer.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return null;
        return TRAILER_TO_CATEGORY.get(key);
    }

    /**
     * Scan a free-text description for a known issuer-trailer phrase.
     * Returns the first (longest-prefix) hit, or null if none match.
     * This is the fallback path for transactions where the trailer is
     * embedded in the description string rather than in a dedicated
     * {@code importerCategory} field.
     */
    public static String scan(final String descriptionLower) {
        if (descriptionLower == null || descriptionLower.isEmpty()) return null;
        for (final String trailer : TRAILERS_BY_LENGTH) {
            if (descriptionLower.contains(trailer)) {
                return TRAILER_TO_CATEGORY.get(trailer);
            }
        }
        return null;
    }

    /** Visible for tests that want to enumerate known trailers. */
    public static List<String> knownTrailers() {
        return Arrays.asList(TRAILERS_BY_LENGTH.toArray(new String[0]));
    }
}
