package com.budgetbuddy.service.ml;


import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Splits the raw description of a transaction into a clean merchant name and a separate location.
 * Operates on strings that have already been through {@link
 * TextNormalizer#cleanMerchantText(String)}, so payment- processor prefixes (SQ*, PAYPAL*) and
 * common transaction-rail noise are already gone.
 *
 * <h3>Why this matters</h3>
 *
 * Before this service, a raw row like "AMAZON MARKETPLACE SEATTLE WA 98101 *ABC123" landed in
 * {@code Transaction.merchantName} as the full string. User-visible consequences: "Amazon" search
 * missed most Amazon rows; merchant-level budgets saw thousands of distinct "Amazon" variants;
 * category inference was noisier.
 *
 * <h3>Algorithm</h3>
 *
 * Parse from the <em>end</em> of the string, not with a lazy regex. Lazy regex produces ambiguous
 * matches ("AMAZON" + "MARKETPLACE SEATTLE" + "WA" is a valid lazy split of "AMAZON MARKETPLACE
 * SEATTLE WA"). Walking backward and peeling 1..3 city tokens off a known state/country suffix is
 * deterministic.
 *
 * <p>Steps: 1. Strip trailing noise (reference codes, ZIP codes). 2. Comma form first: "MERCHANT
 * CITY, ST". 3. No-comma form: last token must be a known state/province/country code; peel
 * preceding 1..3 tokens as the city. 4. Fail soft — when no tail matches, return raw string as
 * merchant with null location (callers tolerate null).
 */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
public final class MerchantLocationSplitter {

    /** Canonical US state + territory abbreviations. Uppercased set. */
    private static final Set<String> US_STATES =
            Set.of(
                    "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", "HI", "ID", "IL",
                    "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT",
                    "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI",
                    "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "DC", "AS",
                    "GU", "MP", "PR", "VI");

    /** ISO-3166 alpha-3 country code trailers we recognise on international rows. */
    private static final Set<String> COUNTRY_CODES =
            Set.of(
                    "USA", "GBR", "UK", "CAN", "AUS", "DEU", "FRA", "ITA", "ESP", "NLD", "IND",
                    "SGP", "JPN", "CHN", "KOR", "BRA", "MEX", "NZL", "IRL", "CHE", "SWE", "NOR",
                    "DNK", "FIN", "BEL", "AUT", "POL", "CZE", "PRT", "GRC", "ZAF", "ARE", "SAU",
                    "QAT", "ISR", "TUR", "HKG", "TWN", "THA", "VNM", "IDN", "MYS", "PHL", "ARG",
                    "CHL", "COL", "PER");

    /** Canadian provinces — Canadian card statements show "TORONTO ON". */
    private static final Set<String> CA_PROVINCES =
            Set.of("AB", "BC", "MB", "NB", "NL", "NS", "NT", "NU", "ON", "PE", "QC", "SK", "YT");

    /**
     * How many trailing tokens we'll consider as "city words" — covers "SAN JOSE", "NEW YORK CITY",
     * etc. Beyond 3 we stop (too ambiguous).
     */
    private static final int MAX_CITY_TOKENS = 3;

    /**
     * US-city compound prefixes that tell us "the preceding token belongs with this one" (i.e.
     * should be part of the city, not the merchant). When the 2nd-from-end token belongs to this
     * set we treat the city as at least 2 words. Extended when 3-word prefixes compose ("NORTH
     * LITTLE ROCK").
     */
    private static final Set<String> CITY_COMPOUND_PREFIXES =
            Set.of(
                    "SAN", "LOS", "NEW", "SAINT", "ST", "ST.", "FORT", "PORT", "MOUNT", "MT", "MT.",
                    "LAKE", "NORTH", "SOUTH", "EAST", "WEST", "LITTLE", "UPPER", "LOWER", "EL",
                    "LA", "LAS", "LE", "VAN", "WHITE", "GRAND", "OAK", "PINE", "ROCK", "RIO", "DE",
                    "DEL", "DU");

    /**
     * Each pattern is applied (anchored at end of string) until it matches nothing new. Matches
     * must be non-empty — no vacuous zero-length matches.
     */
    private static final Pattern TRAIL_REFERENCE_CODE = Pattern.compile("\\s+\\*?[A-Z0-9]{6,}$");

    private static final Pattern TRAIL_ZIP = Pattern.compile("\\s+\\d{5}(?:-\\d{4})?$");

    private MerchantLocationSplitter() {
        /* utility class */
    }

    /**
     * Attempt to split. Returns the original {@code raw} as merchant and {@code null} location when
     * no recognisable location tail is found.
     */
    public static Split split(final String raw) {
        if (raw == null) {
            return new Split(null, null);
        }
        String cleaned = raw.trim();
        if (cleaned.isEmpty()) {
            return new Split(cleaned, null);
        }

        // Peel trailing noise (reference codes, ZIPs) in a loop — each
        // pattern is anchored at end-of-string and only matches non-empty
        // suffixes, so we loop until no pattern fires. This avoids the
        // single-regex trap where a vacuous zero-length match at EOS is
        // "successful" but strips nothing.
        cleaned = stripTrailingNoise(cleaned);

        // Comma form first: "... SAN FRANCISCO, CA".
        final int commaIdx = cleaned.lastIndexOf(',');
        if (commaIdx > 0 && commaIdx < cleaned.length() - 1) {
            final String head = cleaned.substring(0, commaIdx).trim();
            final String tail = cleaned.substring(commaIdx + 1).trim();
            final Split s = tryTailAsLocation(head, tail);
            if (s != null) {
                return s;
            }
        }

        // No-comma form: work right-to-left. Pop last token; if it's a state
        // or country code, peel 1..MAX_CITY_TOKENS preceding words as city.
        final String[] tokens = cleaned.split("\\s+");
        if (tokens.length < 3) {
            return new Split(cleaned, null); // minimum "merchant city state"
        }
        final String lastToken = tokens[tokens.length - 1].toUpperCase(Locale.ROOT);

        if (US_STATES.contains(lastToken) || CA_PROVINCES.contains(lastToken)) {
            final Split s = peelCity(tokens, tokens.length - 1, lastToken, false);
            if (s != null) {
                return s;
            }
        }

        if (lastToken.length() == 3 && COUNTRY_CODES.contains(lastToken)) {
            final Split s = peelCity(tokens, tokens.length - 1, lastToken, true);
            if (s != null) {
                return s;
            }
        }

        return new Split(cleaned, null);
    }

    /**
     * Peel preceding city tokens off {@code tokens[0..stateIdx]}. Default is 1-word city; if the
     * 2nd-from-last token is a known compound prefix ("SAN", "NEW", "LOS", "FORT", …) we include
     * it. Same for 3-word compounds like "NORTH LITTLE ROCK".
     */
    private static Split peelCity(
            final String[] tokens, final int stateIdx, final String stateOrCountry, final boolean isCountry) {
        int cityWordCount = 1;
        final int candidatePrev = stateIdx - 2;
        if (candidatePrev >= 1
                && CITY_COMPOUND_PREFIXES.contains(tokens[candidatePrev].toUpperCase(Locale.ROOT))) {
            cityWordCount = 2;
            // Check for 3-word compounds ("NORTH LITTLE ROCK", "WEST SOUTH ...").
            final int candidatePrevPrev = stateIdx - 3;
            if (candidatePrevPrev >= 1
                    && CITY_COMPOUND_PREFIXES.contains(tokens[candidatePrevPrev].toUpperCase(Locale.ROOT))) {
                cityWordCount = 3;
            }
        }
        while (cityWordCount <= MAX_CITY_TOKENS) {
            final int cityStart = stateIdx - cityWordCount;
            if (cityStart < 1) {
                break; // would leave no merchant
            }
            final String city = joinRange(tokens, cityStart, stateIdx);
            if (looksLikeCity(city)) {
                final String merchant = joinRange(tokens, 0, cityStart);
                return new Split(
                        normaliseMerchant(merchant),
                        normaliseLocation(
                                city,
                                isCountry ? null : stateOrCountry,
                                isCountry ? stateOrCountry : null));
            }
            cityWordCount++;
        }
        return null;
    }

    private static String stripTrailingNoise(final String input) {
        String current = input;
        boolean changed = true;
        while (changed) {
            changed = false;
            String after = TRAIL_REFERENCE_CODE.matcher(current).replaceFirst("");
            if (!after.equals(current)) {
                current = after.trim();
                changed = true;
                continue;
            }
            after = TRAIL_ZIP.matcher(current).replaceFirst("");
            if (!after.equals(current)) {
                current = after.trim();
                changed = true;
            }
        }
        return current;
    }

    /**
     * Comma form: "head, tail". {@code tail} should be a state/country token; the city (if any)
     * lives at the end of {@code head}.
     */
    private static Split tryTailAsLocation(final String head, final String stateOrCountryToken) {
        final String upper = stateOrCountryToken.toUpperCase(Locale.ROOT);
        final boolean isCountry = COUNTRY_CODES.contains(upper);
        if (!(US_STATES.contains(upper) || CA_PROVINCES.contains(upper) || isCountry)) {
            return null;
        }
        final String[] headTokens = head.split("\\s+");
        // Mirror peelCity: if the 2nd-from-last head token is a compound
        // prefix ("SAN", "NEW", etc.), start with cityWordCount=2 so we
        // don't split "SAN FRANCISCO" into merchant-ending-in-SAN + city-FRANCISCO.
        int cityWordCount = 1;
        final int candidatePrev = headTokens.length - 2;
        if (candidatePrev >= 1
                && CITY_COMPOUND_PREFIXES.contains(headTokens[candidatePrev].toUpperCase(Locale.ROOT))) {
            cityWordCount = 2;
            final int candidatePrevPrev = headTokens.length - 3;
            if (candidatePrevPrev >= 1
                    && CITY_COMPOUND_PREFIXES.contains(
                            headTokens[candidatePrevPrev].toUpperCase(Locale.ROOT))) {
                cityWordCount = 3;
            }
        }
        while (cityWordCount <= MAX_CITY_TOKENS) {
            final int cityStart = headTokens.length - cityWordCount;
            if (cityStart < 1) {
                break;
            }
            final String city = joinRange(headTokens, cityStart, headTokens.length);
            if (looksLikeCity(city)) {
                final String merchant = joinRange(headTokens, 0, cityStart);
                return new Split(
                        normaliseMerchant(merchant),
                        normaliseLocation(
                                city, isCountry ? null : upper, isCountry ? upper : null));
            }
            cityWordCount++;
        }
        // No city words in head — head IS the merchant, state/country stands alone.
        return new Split(
                normaliseMerchant(head),
                normaliseLocation(null, isCountry ? null : upper, isCountry ? upper : null));
    }

    /**
     * City looks legitimate when: length 2-35, letters only (plus ' - .), and doesn't contain
     * digits. A sanity filter so we don't peel a merchant's last word as the city just because the
     * state letters followed it.
     */
    private static boolean looksLikeCity(final String candidate) {
        if (candidate == null) {
            return false;
        }
        final String c = candidate.trim();
        if (c.length() < 2 || c.length() > 35) {
            return false;
        }
        for (final char ch : c.toCharArray()) {
            if (Character.isDigit(ch)) {
                return false;
            }
        }
        return true;
    }

    private static String joinRange(final String[] tokens, final int fromInclusive, final int toExclusive) {
        final StringBuilder sb = new StringBuilder();
        for (int i = fromInclusive; i < toExclusive; i++) {
            if (i > fromInclusive) {
                sb.append(' ');
            }
            sb.append(tokens[i]);
        }
        return sb.toString();
    }

    private static String normaliseMerchant(final String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replaceAll("\\s+", " ").replaceAll("[,\\s]+$", "").trim();
    }

    private static String normaliseLocation(final String city, final String state, final String country) {
        final String cityTitled = city == null ? "" : titleCase(city.trim());
        if (state != null && !state.isBlank()) {
            return cityTitled.isEmpty()
                    ? state.toUpperCase(Locale.ROOT)
                    : cityTitled + ", " + state.toUpperCase(Locale.ROOT);
        }
        if (country != null && !country.isBlank()) {
            return cityTitled.isEmpty()
                    ? country.toUpperCase(Locale.ROOT)
                    : cityTitled + ", " + country.toUpperCase(Locale.ROOT);
        }
        return cityTitled.isEmpty() ? null : cityTitled;
    }

    private static String titleCase(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        final String[] parts = raw.toLowerCase(Locale.ROOT).split("\\s+");
        final StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            final String p = parts[i];
            if (p.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) {
                sb.append(p.substring(1));
            }
        }
        return sb.toString();
    }

    /** Result of a split attempt. {@code location} may be null when no tail matched. */
    public record Split(String merchant, String location) {}
}
