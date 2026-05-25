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
    private static final String S = "\\s+";

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

    /**
     * Two-letter ISO-3166 alpha-2 country codes Discover/Amex use on
     * international rows ("PRET A MANGER LONDON GB"). We can't simply add
     * them to US_STATES — the codes overlap heavily (IN=Indiana vs India,
     * AL=Alabama vs Albania, etc.) — so we only consult this set when the
     * trailer ISN'T a US state and the row otherwise has no recognized
     * location. The codes here are deliberately a SUBSET of ISO alpha-2:
     * only those that don't conflict with a US state abbreviation.
     */
    private static final Set<String> ALPHA2_COUNTRIES_SAFE = Set.of(
            "GB", "JP", "CN", "KR", "FR", "ES", "PT", "NL", "BE", "AT",
            "CH", "SE", "NO", "DK", "FI", "PL", "CZ", "GR", "TR", "AE",
            "SA", "QA", "ZA", "AR", "CL", "CO", "PE", "BR", "MX", "AU",
            "NZ", "TH", "VN", "SG", "HK", "TW", "PH", "MY", "ID", "IE",
            "EG", "MA", "KE", "NG");

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
    private static final Set<String> CITY_COMPOUND_PREFIXES = Set.of(
            "SAN", "SANTA", "LOS", "NEW", "SAINT", "SAINTE", "STE", "STE.", "ST", "ST.",
            "FORT", "PORT", "MOUNT", "MT", "MT.", "LAKE", "NORTH", "SOUTH", "EAST", "WEST",
            "LITTLE", "UPPER", "LOWER", "EL", "LA", "LAS", "LE", "VAN", "WHITE", "GRAND",
            "OAK", "PINE", "ROCK", "RIO", "DE", "DEL", "DU",
            "PUERTO", "RANCHO", "PALM", "BEVERLY", "JERSEY", "ATLANTIC", "GREEN", "CORAL",
            "PALO", "RED", "ROUND", "CEDAR", "EAGLE", "BUFFALO", "BATON", "COLORADO",
            "OVERLAND", "WASHINGTON", "VIRGINIA", "MONTEREY", "MIAMI", "OKLAHOMA", "KANSAS",
            "SALT", "OLD", "BIG", "ORANGE", "MISSION", "BROKEN", "DALY", "COLLEGE",
            "UNIVERSITY", "GROVE", "GARDEN", "VALLEY", "CASTRO", "MORGAN", "HUNTINGTON",
            "REDWOOD", "SANTANA", "DAYTONA", "DAYTON", "SACRAMENTO", "ALBANY", "CHESTER",
            "WORCESTER", "PROVIDENCE", "MANCHESTER", "GLEN", "STONE", "BLUE", "BROOK",
            "FALLS", "GREAT", "WALNUT", "MAPLE", "HOLLY", "WHEAT", "INDIAN", "ROYAL",
            "CRYSTAL", "FOX", "HUDSON", "MOSS", "BOULDER", "MOUNTAIN", "GOLDEN", "SILVER",
            "OYSTER", "FAIR", "MEDINA", "SHORT", "TWIN", "THREE", "BOCA", "LADERA", "PALOS");

    /**
     * Each pattern is applied (anchored at end of string) until it matches nothing new. Matches
     * must be non-empty — no vacuous zero-length matches.
     */
    // Reference-code trailer: must contain at least one digit OR be preceded
    // by an asterisk. Without that requirement, pure-letter all-caps tokens
    // (e.g. "VIEWCA" — the glued city-state "View" + "CA") get eaten before
    // glue-repair gets a chance to fix them.
    private static final Pattern TRAIL_REFERENCE_CODE = Pattern.compile(
            "\\s+(?:\\*[A-Z0-9]{4,}|[A-Z0-9]*\\d[A-Z0-9]{4,}|[A-Z0-9]{4,}\\d[A-Z0-9]*)$");

    private static final Pattern TRAIL_ZIP = Pattern.compile("\\s+\\d{5}(?:-\\d{4})?$");

    /**
     * Discover-style category trailer ("Restaurants", "Hotels", "Merchandise",
     * "Supermarkets", "Services", "Travel/Entertainment", etc.). Discover
     * appends one of these as a category-summary label after the city/state,
     * which breaks the right-to-left state-detection: the LAST token becomes
     * the category instead of the state. We strip when the trailer matches a
     * known category word so the next pass sees the state at the tail.
     */
    private static final Pattern TRAIL_DISCOVER_CATEGORY = Pattern.compile(
            "(?i)\\s+(Restaurants?|Hotels?|Merchandise|Supermarkets?|Services|Travel|"
                    + "Travel/Entertainment|Entertainment|Gas|Gasoline|Department\\s+Stores?|"
                    + "Drug\\s+Stores?|Grocery|Groceries|Education|Government|Medical|"
                    + "Health|Insurance|Utilities|Telecommunications|Transportation|"
                    + "Discount\\s+Stores?|Specialty\\s+Stores?|Wholesale|Auto|"
                    + "Membership\\s+Warehouse|Membership\\s+Clubs?|"
                    // Amex-specific category trailers (same role as Discover's
                    // "Restaurants" — appended after the location):
                    + "MISC/SPECIALTY\\s+RETAIL|RESTAURANT|RESTAURANTS|"
                    + "GIFT\\s+CARD|GIFT\\s+CARDS|CABLE\\s+&\\s+PAY\\s+TV|"
                    + "SERVICE\\s+STN|SERVICE\\s+STATION|GAS\\s+STATION|"
                    + "FAST\\s+FOOD\\s+RESTAURANT|FAST\\s+FOOD|"
                    + "PHARMACY|PHARMACIES|RETAIL|FOOD|GROCERY\\s+STORE|"
                    + "PARKING\\s+LOTS?|AIRLINES?|HOTELS?/MOTELS?|"
                    + "TAXI|RIDESHARE|CAR\\s+RENTAL)$");

    /**
     * "APPLE PAY ENDING IN 8772" wallet-card trailer — Discover and a couple
     * of others print it after the location. Strip so location detection
     * isn't blocked by the digits.
     */
    private static final Pattern TRAIL_APPLE_PAY = Pattern.compile(
            "(?i)\\s+APPLE\\s+PAY\\s+ENDING\\s+IN\\s+\\d{4,}\\s*$");

    /**
     * Trailing FX block ("4.20 @ 00000001.3476190 GBP") that Discover and
     * Chase sometimes append to international purchases AFTER the location.
     */
    private static final Pattern TRAIL_FX_BLOCK = Pattern.compile(
            "(?i)\\s+[\\d.]+\\s*@\\s*[\\d.]+\\s*[A-Z]{3}\\s*$");

    /**
     * Trailing "+<10-15 digits>" — AplPay rows like "AplPay IC* INSTACART
     * SAN FRANCISCO CA +18882467822" carry an international-format phone
     * number AFTER the location, blocking state detection.
     */
    private static final Pattern TRAIL_INTL_PHONE = Pattern.compile(
            "\\s+\\+\\d{10,15}\\s*$");

    /**
     * Multi-digit store-ID + ZIP trailer: "RENTON WA 5729 98056". A 4-6
     * digit store-id followed by a 5-digit ZIP after the state code — Amex
     * AplPay rows print this combination. Strip the trailing digits so the
     * state becomes the last token.
     */
    private static final Pattern TRAIL_STOREID_ZIP = Pattern.compile(
            "\\s+\\d{3,6}\\s+\\d{5}(?:-\\d{4})?\\s*$");

    /**
     * Hotel-folio noise trailer (Costco Travel + some hotel-booking flows).
     * Strips the "PHONE NUMBER: FOLIO NUMBER: ARRIVE: 00/00/00 DEPART:
     * 00/00/00" block that follows the city/state on bookings.
     */
    private static final Pattern TRAIL_HOTEL_FOLIO = Pattern.compile(
            "(?i)\\s+(?:PHONE\\s+NUMBER:|FOLIO\\s+NUMBER:|ARRIVE:|DEPART:|"
                    + "CHECK[\\s\\-]?IN:|CHECK[\\s\\-]?OUT:)\\s*(?:[0-9/:\\-\\s]*)$");

    /**
     * Phone-shaped token mid-description. Doesn't run at the end (state code
     * may follow it). We do an interior phone-strip when a state code IS
     * present further in the string but a 10-digit phone run sits between
     * the merchant name and the city/state pair. Pattern needs to be applied
     * with caution — only when we'd otherwise fail to extract location.
     */
    private static final Pattern PHONE_INTERIOR = Pattern.compile(
            "\\s+\\d{3}[\\-.\\s]?\\d{3}[\\-.\\s]?\\d{4}\\b");

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

        // Some PDFs lose the space between city and state ("MOUNTAIN VIEWCA"
        // → "MOUNTAIN VIEW" + "CA"). Detect by looking for an uppercase
        // 2-letter state code glued onto the end of the final word and
        // re-insert the space. Only fire when the recovered last token
        // would be a recognized state — limits false positives on merchant
        // names that happen to end with two uppercase letters.
        cleaned = repairGluedStateTrailer(cleaned);

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
            // peelCity rejected the city candidate (URL chars, too short,
            // etc.) but the state is real. Surface state-only so the row
            // at least keeps its country context.
            final Split sStateOnly = stateOnlyFallback(tokens, lastToken, false);
            if (sStateOnly != null) {
                return sStateOnly;
            }
        }

        if (lastToken.length() == 3 && COUNTRY_CODES.contains(lastToken)) {
            final Split s = peelCity(tokens, tokens.length - 1, lastToken, true);
            if (s != null) {
                return s;
            }
            final Split sStateOnly = stateOnlyFallback(tokens, lastToken, true);
            if (sStateOnly != null) {
                return sStateOnly;
            }
        }

        // 2-letter ISO alpha-2 country trailer ("LONDON GB", "TOKYO JP").
        // Only consulted when the trailer isn't a US state — see
        // ALPHA2_COUNTRIES_SAFE for the conflict-free subset.
        if (lastToken.length() == 2 && ALPHA2_COUNTRIES_SAFE.contains(lastToken)) {
            final Split s = peelCity(tokens, tokens.length - 1, lastToken, true);
            if (s != null) {
                return s;
            }
            final Split sStateOnly = stateOnlyFallback(tokens, lastToken, true);
            if (sStateOnly != null) {
                return sStateOnly;
            }
        }

        // Second-chance pass: when the first attempt failed because a phone
        // number sits between the merchant name and the city/state pair
        // ("CPI*CANTEEN VENDING 800-628-8363 CA"), strip the phone and try
        // again. Also handle the state-only case ("STARBUCKS 800-782-7282 WA"
        // → location="WA") which the main path can't because the first city
        // candidate is the phone digits.
        final String phoneStripped = stripInteriorPhone(cleaned);
        if (!phoneStripped.equals(cleaned)) {
            final String[] retoks = phoneStripped.split("\\s+");
            if (retoks.length >= 2) {
                final String retLast = retoks[retoks.length - 1].toUpperCase(Locale.ROOT);
                final boolean isState = US_STATES.contains(retLast)
                        || CA_PROVINCES.contains(retLast);
                final boolean isCountry = retLast.length() == 3
                        && COUNTRY_CODES.contains(retLast);
                if (isState || isCountry) {
                    if (retoks.length >= 3) {
                        final Split s = peelCity(retoks, retoks.length - 1, retLast, isCountry);
                        if (s != null) {
                            return s;
                        }
                    } else if (retoks.length == 2) {
                        // Exactly 2 tokens left after phone strip: just
                        // merchant + state ("STARBUCKS 800-... WA" →
                        // "STARBUCKS WA"). No city possible. Surface the
                        // state-only location so callers know the country.
                        return new Split(
                                normaliseMerchant(retoks[0]),
                                normaliseLocation(null,
                                        isCountry ? null : retLast,
                                        isCountry ? retLast : null));
                    }
                }
            }
        }

        return new Split(cleaned, null);
    }

    /**
     * Peel preceding city tokens off {@code tokens[0..stateIdx]}. Default is 1-word city; if the
     * 2nd-from-last token is a known compound prefix ("SAN", "NEW", "LOS", "FORT", …) we include
     * it. Same for 3-word compounds like "NORTH LITTLE ROCK".
     */
    /**
     * Some PDFs print a stray 2-letter token between the city and the
     * trailing state code ("SUMMIT RTP SNOQUALMIE PA WA" — the actual
     * city is Snoqualmie, WA; "PA" is a store-code artifact). When the
     * second-to-last token is a US state code AND the third-to-last token
     * looks like a city word (not a digit, not 2-letter), treat the stray
     * 2-letter token as noise and shift the city window left.
     */
    private static int adjustForStrayInteriorState(
            final String[] tokens, final int stateIdx) {
        if (stateIdx < 2) return 0;
        final String beforeState = tokens[stateIdx - 1].toUpperCase(Locale.ROOT);
        // Is the token before the real state ALSO a 2-letter state? Then
        // it's likely a stray artifact.
        if (beforeState.length() != 2 || !US_STATES.contains(beforeState)) {
            return 0;
        }
        // Confirm there's a plausible city word further back (a 3+ letter
        // alphabetic token at stateIdx-2).
        if (stateIdx < 3) return 0;
        final String maybeCityTail = tokens[stateIdx - 2];
        if (maybeCityTail.length() < 3) return 0;
        for (final char ch : maybeCityTail.toCharArray()) {
            if (Character.isDigit(ch)) return 0;
        }
        return 1; // shift city window left by 1 to skip stray state
    }

    private static Split peelCity(
            final String[] tokens,
            final int stateIdx,
            final String stateOrCountry,
            final boolean isCountry) {
        // Determine cityWordCount strictly from the compound-prefix detector.
        // Don't fall through to wider widths when the chosen width fails — a
        // failed 1-word city means there is no recognizable city, NOT that we
        // should try a 2-word one ("CPI*CANTEEN VENDING SA CA" should not
        // become city="VENDING SA").
        // Detect a stray interior state token ("SNOQUALMIE PA WA") and
        // shift the city window left by 1 to step over it.
        final int strayShift = adjustForStrayInteriorState(tokens, stateIdx);
        final int effectiveStateIdx = stateIdx - strayShift;
        if (effectiveStateIdx < 2) return null;

        int cityWordCount = 1;
        final int candidatePrev = effectiveStateIdx - 2;
        if (candidatePrev >= 1
                && CITY_COMPOUND_PREFIXES.contains(
                        tokens[candidatePrev].toUpperCase(Locale.ROOT))) {
            cityWordCount = 2;
            final int candidatePrevPrev = effectiveStateIdx - 3;
            if (candidatePrevPrev >= 1
                    && CITY_COMPOUND_PREFIXES.contains(
                            tokens[candidatePrevPrev].toUpperCase(Locale.ROOT))) {
                cityWordCount = 3;
            }
        }
        final int cityStart = effectiveStateIdx - cityWordCount;
        if (cityStart < 1) {
            return null; // would leave no merchant
        }
        final String city = joinRange(tokens, cityStart, effectiveStateIdx);
        if (!looksLikeCity(city)) {
            return null;
        }
        final String merchant = joinRange(tokens, 0, cityStart);
        return new Split(
                normaliseMerchant(merchant),
                normaliseLocation(
                        city,
                        isCountry ? null : stateOrCountry,
                        isCountry ? stateOrCountry : null));
    }

    private static String stripTrailingNoise(final String input) {
        String current = input;
        boolean changed = true;
        while (changed) {
            changed = false;
            // Strip in priority order. The Discover/Apple-Pay/FX trailers run
            // BEFORE the generic ref-code stripper because the ref-code regex
            // could otherwise eat part of the FX number block.
            String after = TRAIL_DISCOVER_CATEGORY.matcher(current).replaceFirst("");
            if (!after.equals(current)) {
                current = after.trim();
                changed = true;
                continue;
            }
            after = TRAIL_APPLE_PAY.matcher(current).replaceFirst("");
            if (!after.equals(current)) {
                current = after.trim();
                changed = true;
                continue;
            }
            after = TRAIL_FX_BLOCK.matcher(current).replaceFirst("");
            if (!after.equals(current)) {
                current = after.trim();
                changed = true;
                continue;
            }
            after = TRAIL_INTL_PHONE.matcher(current).replaceFirst("");
            if (!after.equals(current)) {
                current = after.trim();
                changed = true;
                continue;
            }
            after = TRAIL_STOREID_ZIP.matcher(current).replaceFirst("");
            if (!after.equals(current)) {
                current = after.trim();
                changed = true;
                continue;
            }
            after = TRAIL_HOTEL_FOLIO.matcher(current).replaceFirst("");
            if (!after.equals(current)) {
                current = after.trim();
                changed = true;
                continue;
            }
            after = TRAIL_REFERENCE_CODE.matcher(current).replaceFirst("");
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
     * Strip interior phone numbers from the description. Only invoked as a
     * second-chance pass when the first split returns no location — phone
     * digits in the middle of "MERCHANT 800-555-1212 CITY ST" otherwise look
     * like a non-city token to the tokenizer.
     */
    private static String stripInteriorPhone(final String input) {
        return PHONE_INTERIOR.matcher(input).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    /**
     * Split a glued city-state trailer ("MOUNTAIN VIEWCA" → "MOUNTAIN VIEW
     * CA"). PDFBox's text extraction occasionally loses the kerning-space
     * between the last city word and the state code; this helper detects
     * the case and re-inserts the space.
     *
     * <p>Safety: only fires when the 2-letter suffix is a known US state /
     * CA province / ISO country code, so a merchant whose name happens to
     * end in two uppercase letters (e.g. "QUICK MART NJ") still works
     * correctly (the space was already there). Returns the input unchanged
     * when no glue is detected.
     */
    private static String repairGluedStateTrailer(final String input) {
        if (input == null || input.isEmpty()) return input;
        final int lastSpace = input.lastIndexOf(' ');
        if (lastSpace < 0) return input;
        final String lastWord = input.substring(lastSpace + 1);
        // Already a clean trailer or too short to be glued.
        if (lastWord.length() < 4) return input;
        // Only repair when the trailing 2 letters (the state-code part) are
        // uppercase in the source. PDF-glued city-states are emitted with
        // an uppercase state code regardless of city casing ("Mountain
        // VIEWCA"). Mixed-case real merchant words ("Amazon Prime") end
        // with lowercase letters and must NOT be split even though "ME" is
        // a US state. This check is what stops the false-positive.
        final char tA = lastWord.charAt(lastWord.length() - 2);
        final char tB = lastWord.charAt(lastWord.length() - 1);
        if (!Character.isUpperCase(tA) || !Character.isUpperCase(tB)) {
            return input;
        }
        final String trail2 = lastWord.substring(lastWord.length() - 2).toUpperCase(Locale.ROOT);
        if (!US_STATES.contains(trail2) && !CA_PROVINCES.contains(trail2)) {
            // Try ISO alpha-3 country glued onto a city (rare but possible).
            if (lastWord.length() < 5) return input;
            final String trail3 = lastWord.substring(lastWord.length() - 3)
                    .toUpperCase(Locale.ROOT);
            if (!COUNTRY_CODES.contains(trail3)) return input;
            final String head = lastWord.substring(0, lastWord.length() - 3);
            if (head.isEmpty() || !Character.isLetter(head.charAt(head.length() - 1))) {
                return input;
            }
            return input.substring(0, lastSpace + 1) + head + " " + trail3;
        }
        final String head = lastWord.substring(0, lastWord.length() - 2);
        // Only repair when the leading portion ends with a letter — avoids
        // splitting "12345CA" (digit prefix means the trailing letters are
        // probably part of a reference code, not a city-state glue).
        if (head.isEmpty() || !Character.isLetter(head.charAt(head.length() - 1))) {
            return input;
        }
        return input.substring(0, lastSpace + 1) + head + " " + trail2;
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
        // Strict cityWordCount choice — no width fall-through. See peelCity.
        int cityWordCount = 1;
        final int candidatePrev = headTokens.length - 2;
        if (candidatePrev >= 1
                && CITY_COMPOUND_PREFIXES.contains(
                        headTokens[candidatePrev].toUpperCase(Locale.ROOT))) {
            cityWordCount = 2;
            final int candidatePrevPrev = headTokens.length - 3;
            if (candidatePrevPrev >= 1
                    && CITY_COMPOUND_PREFIXES.contains(
                            headTokens[candidatePrevPrev].toUpperCase(Locale.ROOT))) {
                cityWordCount = 3;
            }
        }
        final int cityStart = headTokens.length - cityWordCount;
        if (cityStart >= 1) {
            final String city = joinRange(headTokens, cityStart, headTokens.length);
            if (looksLikeCity(city)) {
                final String merchant = joinRange(headTokens, 0, cityStart);
                return new Split(
                        normaliseMerchant(merchant),
                        normaliseLocation(
                                city, isCountry ? null : upper, isCountry ? upper : null));
            }
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
        // Min 3 chars: 2-letter tokens are almost always state codes or
        // merchant abbreviations ("SA" in "VENDING SA CA"), not city names.
        // The few legit 2-char cities (e.g. "Ai" in Hawaii) are too rare to
        // justify the false-positive rate.
        if (c.length() < 3 || c.length() > 35) {
            return false;
        }
        for (final char ch : c.toCharArray()) {
            if (Character.isDigit(ch)) {
                return false;
            }
            // Reject URL / email / path characters — real city names never
            // contain "." "/" ":" "@" "#". Catches "Help.uber.com",
            // "https://example.com", "G.co/helppay#", "support@x.com".
            if (ch == '.' || ch == '/' || ch == ':' || ch == '@' || ch == '#') {
                return false;
            }
        }
        return true;
    }

    /**
     * State-only fallback. Used when {@link #peelCity} returns null but the
     * trailer is a known state/country — typically because the city
     * candidate failed {@code looksLikeCity} (URL chars, all-digits store
     * id, etc.). Returns a Split with the merchant being everything up to
     * the state token (sans the bogus city candidate stripped) and the
     * location being state-only. Returns null if the merchant would be
     * empty.
     */
    private static Split stateOnlyFallback(
            final String[] tokens, final String stateOrCountry, final boolean isCountry) {
        // Need at least merchant + one filler + state.
        if (tokens.length < 2) return null;
        final int stateIdx = tokens.length - 1;
        // Merchant is everything before the state token — let the city
        // candidate fall through naturally as part of merchant noise.
        final String merchant = joinRange(tokens, 0, stateIdx);
        if (merchant.isBlank()) return null;
        return new Split(
                normaliseMerchant(merchant),
                normaliseLocation(null,
                        isCountry ? null : stateOrCountry,
                        isCountry ? stateOrCountry : null));
    }

    private static boolean isAllUpperLetters(final String s) {
        for (int i = 0; i < s.length(); i++) {
            final char ch = s.charAt(i);
            if (!Character.isLetter(ch) || !Character.isUpperCase(ch)) {
                return false;
            }
        }
        return s.length() > 0;
    }

    private static String joinRange(
            final String[] tokens, final int fromInclusive, final int toExclusive) {
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

    private static String normaliseLocation(
            final String city, final String state, final String country) {
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
