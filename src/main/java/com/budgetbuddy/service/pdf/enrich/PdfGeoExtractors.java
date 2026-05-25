package com.budgetbuddy.service.pdf.enrich;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure descriptor-text geo extractors extracted from PDFImportService.
 * Phase 2 of the monolith split (see docs/pdf-importer-split-design.md).
 *
 * <p>Each method is pure: takes a String, returns a String or null. No
 * collaborator dependencies. The originals remain in PDFImportService
 * for now and delegate here — a follow-up pass will remove the originals
 * once all call sites have migrated. The duplication is intentional
 * during the split so a single PR doesn't break the world.
 */
public final class PdfGeoExtractors {

    private PdfGeoExtractors() { }

    // US phone with optional area-code separators. Anchored on word
    // boundaries so it doesn't fire on bare 10-digit transaction ids.
    private static final Pattern PHONE_US = Pattern.compile(
            "\\b(\\d{3}[\\-.\\s]?\\d{3}[\\-.\\s]?\\d{4})\\b");

    // Bare 10-digit phone (e.g. "6046424286"). Looser; we only consult
    // this when PHONE_US doesn't fire, to avoid eating order-numbers.
    private static final Pattern PHONE_BARE10 = Pattern.compile("\\b(\\d{10})\\b");

    // ACH / banking-system identifier markers. Rows containing any of
    // these tokens have a 10-digit RUN that LOOKS like a phone but is
    // actually a PPD ID, Web ID, Tel ID, ACH trace number, or similar.
    private static final Pattern ACH_IDENTIFIER_TOKEN = Pattern.compile(
            "(?i)\\b(PPD\\s+ID|WEB\\s+ID|TEL\\s+ID|CCD\\s+ID|"
                    + "ACH\\s+(?:Credit|Debit|Pmt|Trace)|"
                    + "TRACE\\s+ID|REFERENCE\\s+ID|TRANSACTION\\s+ID|"
                    + "CONFIRMATION\\s+(?:NUMBER|#))");

    private static final Pattern US_ZIP = Pattern.compile("\\b(\\d{5}(?:-\\d{4})?)\\b");

    // Canonical US-state codes — used by extractZipFromDescription's
    // "trust ZIP only when there's a state earlier in the line" rule.
    private static final Set<String> US_STATE_CODES = Set.of(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", "HI", "ID", "IL",
            "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT",
            "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI",
            "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY",
            "DC", "AS", "GU", "MP", "PR", "VI");

    private static final Set<String> CA_PROVINCE_CODES = Set.of(
            "AB", "BC", "MB", "NB", "NL", "NS", "NT", "NU", "ON", "PE", "QC", "SK", "YT");

    private static final Set<String> ISO_ALPHA2_COUNTRIES = Set.of(
            "US", "GB", "UK", "CA", "AU", "NZ", "DE", "FR", "IT", "ES", "NL", "BE",
            "PT", "IE", "CH", "AT", "SE", "NO", "DK", "FI", "PL", "CZ", "GR", "TR",
            "IN", "JP", "CN", "KR", "HK", "TW", "TH", "VN", "ID", "MY", "PH", "SG",
            "AE", "SA", "QA", "IL", "ZA", "BR", "MX", "AR", "CL", "CO", "PE");

    /**
     * USPS-style street-address pattern. Matches "<number> <words>
     * <SUFFIX> [direction] [STE/APT/UNIT N]". Used to capture street
     * addresses from descriptors like "AplPay 3670 150TH AVE SE BELLEVUE WA".
     */
    private static final Pattern STREET_ADDRESS = Pattern.compile(
            "(?i)\\b(\\d{1,6}\\s+(?:[A-Z0-9][A-Z0-9 .'\\-]*?\\s+)?"
                    + "(?:ST|STREET|AVE|AVENUE|BLVD|BOULEVARD|RD|ROAD|DR|DRIVE|"
                    + "LN|LANE|WAY|CT|COURT|PL|PLACE|PKWY|PARKWAY|HWY|HIGHWAY|"
                    + "TER|TERRACE|TRL|TRAIL|SQ|SQUARE|CIR|CIRCLE|PLZ|PLAZA|"
                    + "ALY|ALLEY|RTE|ROUTE|XING|CROSSING|FWY|FREEWAY)"
                    + "(?:\\s+\\d+)?"
                    + "(?:\\s+(?:N|S|E|W|NE|NW|SE|SW|NORTH|SOUTH|EAST|WEST))?"
                    + "(?:\\s+(?:STE|SUITE|APT|APARTMENT|UNIT|#)\\s*[A-Z0-9\\-]+)?)\\b");

    /**
     * Extract a phone number from {@code text} as a digits-only string.
     * Returns null when no plausible phone is present. PPD/Web/Tel/ACH
     * identifier rows are explicitly rejected — those 10-digit runs are
     * banking-system IDs, not phones.
     */
    public static String extractPhone(final String text) {
        if (text == null || text.isBlank()) return null;
        // Hard skip on ACH-rail rows — these are non-merchant flows
        // with no merchant phone by definition.
        if (ACH_IDENTIFIER_TOKEN.matcher(text).find()) return null;
        final Matcher m = PHONE_US.matcher(text);
        if (m.find()) {
            return m.group(1).replaceAll("[^0-9]", "");
        }
        final Matcher m2 = PHONE_BARE10.matcher(text);
        if (m2.find()) {
            final String digits = m2.group(1);
            if (!digits.startsWith("0")) {
                return digits;
            }
        }
        return null;
    }

    /**
     * Extract a ZIP from a transaction description — only when there's a
     * recognized US-state/CA-province/ISO-country code earlier in the
     * line. Filters out the "SUBWAY 16245 NASHVILLE TN" trap where the
     * bare 5-digit run is a merchant store number, not a postal code.
     */
    public static String extractZipFromDescription(final String description) {
        if (description == null) return null;
        final Matcher zip = US_ZIP.matcher(description);
        while (zip.find()) {
            final int zipStart = zip.start(1);
            final int from = Math.max(0, zipStart - 80);
            final String prefix = description.substring(from, zipStart);
            final Matcher state = Pattern.compile("\\b([A-Z]{2})\\b").matcher(prefix);
            boolean foundState = false;
            while (state.find()) {
                final String tok = state.group(1);
                if (US_STATE_CODES.contains(tok)
                        || CA_PROVINCE_CODES.contains(tok)
                        || ISO_ALPHA2_COUNTRIES.contains(tok)) {
                    foundState = true;
                }
            }
            if (foundState) {
                return zip.group(1);
            }
        }
        return null;
    }

    /**
     * Pull the first street-address run out of {@code text}. Returns the
     * captured substring verbatim (caller should title-case before
     * persisting). Null when no candidate is present or the captured
     * span is clearly bogus (starts with 0000 or longer than 80 chars).
     */
    public static String extractStreetAddress(final String text) {
        if (text == null || text.isBlank()) return null;
        final Matcher m = STREET_ADDRESS.matcher(text);
        if (!m.find()) return null;
        final String captured = m.group(1).trim().replaceAll("\\s+", " ");
        if (captured.startsWith("0000") || captured.length() > 80) return null;
        return captured;
    }
}
