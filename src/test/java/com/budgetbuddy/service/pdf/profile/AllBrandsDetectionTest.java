package com.budgetbuddy.service.pdf.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * End-to-end verification that EVERY brand listed across every issuer profile is
 * reachable by the registry. This test closes the "are within-issuer brand patterns
 * actually working?" gap that arose when this codebase grew from supporting one card
 * (Chase Marriott Bonvoy) to seven issuers with 50+ brands.
 *
 * <p>The test is intentionally adversarial: for each {@code (issuer, brand,
 * synthetic header)} triple, we:
 *
 * <ol>
 *   <li>Run header text through the registry. The detected profile MUST be the
 *       expected issuer — not the generic fallback, not a different issuer.
 *   <li>Run {@code profile.detectBrand} on the same header. The returned brand MUST
 *       match the expected brand identifier.
 * </ol>
 *
 * <p>A regression in a brand pattern (e.g. someone tightens "Marriott Bonvoy" so it
 * stops matching the "Marriott Bonvoy Bold" sub-product) fails this test loudly.
 * Adding a new brand to a profile's {@code BRANDS} map requires adding a case here
 * so the wiring is exercised — that protocol catches typos and ordering issues.
 */
class AllBrandsDetectionTest {

    private final IssuerProfileRegistry registry = IssuerProfileRegistry.defaultRegistry();

    /**
     * Each case: {issuer-id, brand-id, synthetic header text that should trigger both}.
     *
     * <p>Synthetic header text mirrors what we've seen in real statements but uses no
     * actual cardholder data. The strings are intentionally minimal — the registry
     * only needs the brand-identifying phrase plus the issuer-identifying phrase.
     */
    private static final List<String[]> CASES = Arrays.asList(
            // ---- Chase brands ----
            row("chase", "marriott-bonvoy",
                "Chase Marriott Bonvoy Bold\nwww.chase.com"),
            row("chase", "amazon-prime-visa",
                "Chase Amazon Prime Visa Signature Card\nchase.com/amazon"),
            row("chase", "freedom-flex",
                "Chase Freedom Flex\nwww.chase.com"),
            row("chase", "freedom-unlimited",
                "Chase Freedom Unlimited\nwww.chase.com"),
            row("chase", "freedom",
                "Chase Freedom Visa\nwww.chase.com"),
            row("chase", "sapphire-reserve",
                "Chase Sapphire Reserve\nwww.chase.com"),
            row("chase", "sapphire-preferred",
                "Chase Sapphire Preferred\nwww.chase.com"),
            row("chase", "ink-business",
                "Chase Ink Business Preferred\nwww.chase.com"),
            row("chase", "united-explorer",
                "Chase United Explorer Card\nwww.chase.com"),
            row("chase", "southwest",
                "Chase Southwest Plus\nwww.chase.com"),
            row("chase", "hyatt",
                "Chase World of Hyatt Card\nwww.chase.com"),

            // ---- Wells Fargo brands ----
            row("wells-fargo", "active-cash",
                "Wells Fargo Active Cash Visa Signature\nwellsfargo.com"),
            row("wells-fargo", "autograph",
                "Wells Fargo Autograph Card\nwellsfargo.com"),
            row("wells-fargo", "autograph-journey",
                "Wells Fargo Autograph Journey\nwellsfargo.com"),
            row("wells-fargo", "reflect",
                "Wells Fargo Reflect Card\nwellsfargo.com"),
            row("wells-fargo", "bilt",
                "Wells Fargo Bilt World Elite Mastercard\nwellsfargo.com"),
            row("wells-fargo", "attune",
                "Wells Fargo Attune Card\nwellsfargo.com"),

            // ---- US Bank brands ----
            row("us-bank", "smartly-visa-signature",
                "U.S. Bank Smartly Visa Signature Card\nusbank.com"),
            row("us-bank", "altitude-reserve",
                "U.S. Bank Altitude Reserve Visa Infinite\nusbank.com"),
            row("us-bank", "altitude-connect",
                "U.S. Bank Altitude Connect\nusbank.com"),
            row("us-bank", "altitude-go",
                "U.S. Bank Altitude Go\nusbank.com"),
            row("us-bank", "cash-plus",
                "U.S. Bank Cash+ Visa Signature\nusbank.com"),
            row("us-bank", "reflect",
                "U.S. Bank Reflect Visa\nusbank.com"),
            row("us-bank", "shopper-cash-rewards",
                "U.S. Bank Shopper Cash Rewards\nusbank.com"),

            // ---- Citi brands ----
            row("citi", "double-cash", "Citi Double Cash® Card\nwww.citicards.com"),
            row("citi", "costco-anywhere-visa",
                "Costco Anywhere Visa® Card by Citi\nwww.citicards.com"),
            row("citi", "premier", "Citi Premier® Card\nwww.citicards.com"),
            row("citi", "custom-cash", "Citi Custom Cash® Card\nwww.citicards.com"),
            row("citi", "rewards-plus", "Citi Rewards+® Card\nwww.citicards.com"),
            row("citi", "aadvantage",
                "Citi AAdvantage Platinum Select World Elite\nwww.citicards.com"),
            row("citi", "diamond-preferred",
                "Citi Diamond Preferred® Card\nwww.citicards.com"),
            row("citi", "simplicity", "Citi Simplicity® Card\nwww.citicards.com"),

            // ---- Amex brands ----
            row("amex", "morgan-stanley-platinum",
                "Morgan Stanley Platinum Card\namericanexpress.com"),
            row("amex", "platinum", "The Platinum Card\namericanexpress.com"),
            row("amex", "gold", "American Express Gold Card\namericanexpress.com"),
            row("amex", "green", "American Express Green Card\namericanexpress.com"),
            row("amex", "blue-cash-preferred",
                "Blue Cash Preferred Card\namericanexpress.com"),
            row("amex", "blue-cash-everyday",
                "Blue Cash Everyday Card\namericanexpress.com"),
            row("amex", "blue-business-cash",
                "American Express Blue Business Cash\namericanexpress.com"),
            row("amex", "blue-business-plus",
                "American Express Blue Business Plus\namericanexpress.com"),
            row("amex", "delta-platinum",
                "Delta SkyMiles Platinum American Express\namericanexpress.com"),
            row("amex", "delta-gold",
                "Delta SkyMiles Gold American Express\namericanexpress.com"),
            row("amex", "delta-reserve",
                "Delta SkyMiles Reserve American Express\namericanexpress.com"),
            row("amex", "hilton-aspire",
                "Hilton Honors Aspire Card\namericanexpress.com"),
            row("amex", "hilton-surpass",
                "Hilton Honors Surpass Card\namericanexpress.com"),
            row("amex", "marriott-bonvoy-brilliant",
                "Marriott Bonvoy Brilliant American Express\namericanexpress.com"),

            // ---- Bank of America brands ----
            row("boa", "customized-cash-rewards",
                "Bank of America Customized Cash Rewards Visa\nbankofamerica.com"),
            row("boa", "unlimited-cash-rewards",
                "Bank of America Unlimited Cash Rewards\nbankofamerica.com"),
            row("boa", "travel-rewards",
                "Bank of America Travel Rewards Visa\nbankofamerica.com"),
            row("boa", "premium-rewards",
                "Bank of America Premium Rewards Visa\nbankofamerica.com"),
            row("boa", "premium-rewards-elite",
                "Bank of America Premium Rewards Elite\nbankofamerica.com"),
            row("boa", "alaska-airlines-visa",
                "Alaska Airlines Visa Signature\nbankofamerica.com"),
            row("boa", "amtrak-guest-rewards",
                "Amtrak Guest Rewards Preferred\nbankofamerica.com"),

            // ---- Discover brands ----
            row("discover", "discover-it",
                "Discover it Card\nwww.discover.com"),
            row("discover", "discover-it-miles",
                "Discover it Miles\nwww.discover.com"),
            row("discover", "discover-it-cash-back",
                "Discover it Cash Back\nwww.discover.com"),
            row("discover", "discover-it-chrome",
                "Discover it Chrome\nwww.discover.com"),
            row("discover", "discover-it-student",
                "Discover it Student Cash Back\nwww.discover.com"),
            row("discover", "discover-it-secured",
                "Discover it Secured\nwww.discover.com"),
            row("discover", "discover-it-business",
                "Discover it Business\nwww.discover.com"),
            row("discover", "nhl-discover-it",
                "NHL Discover it Card\nwww.discover.com")
    );

    private static String[] row(final String issuerId, final String brandId, final String header) {
        return new String[] {issuerId, brandId, header};
    }

    @Test
    void allRegisteredBrands_routeToCorrectIssuerAndBrand() {
        final java.util.List<String> failures = new java.util.ArrayList<>();
        for (final String[] c : CASES) {
            final String expectedIssuer = c[0];
            final String expectedBrand = c[1];
            final String header = c[2];

            final IssuerProfile profile = registry.detect(header);
            if (profile == null || !expectedIssuer.equals(profile.issuerId())) {
                failures.add(
                        String.format(
                                "issuer mismatch | expected=%s actual=%s | header='%s'",
                                expectedIssuer,
                                profile == null ? "null" : profile.issuerId(),
                                header.replace("\n", " / ")));
                continue;
            }
            final String brand = profile.detectBrand(header);
            if (!expectedBrand.equals(brand)) {
                failures.add(
                        String.format(
                                "brand mismatch | issuer=%s expected=%s actual=%s | header='%s'",
                                expectedIssuer,
                                expectedBrand,
                                brand,
                                header.replace("\n", " / ")));
            }
        }
        if (!failures.isEmpty()) {
            fail(String.format(
                    "%d brand detections failed:%n  - %s",
                    failures.size(),
                    String.join("%n  - ", failures)));
        }
    }

    @Test
    void everyRegisteredIssuer_hasAtLeastOneCaseInCoverageTable() {
        // Defensive: if someone adds a new IssuerProfile to the registry but forgets
        // to add cases to this file, surface the gap loudly. Exception: profiles that
        // intentionally have no BRANDS map (Apple Card — single product family) only
        // need ISSUER-level detection coverage.
        final java.util.Set<String> issuersWithoutBrands =
                java.util.Set.of("apple-card");
        final var issuerIds = registry.registeredProfiles().stream()
                .map(IssuerProfile::issuerId)
                .toList();
        final var coveredIds = CASES.stream().map(c -> c[0]).distinct().toList();
        for (final String id : issuerIds) {
            if (issuersWithoutBrands.contains(id)) {
                continue;
            }
            assertNotNull(coveredIds.stream().filter(id::equals).findFirst().orElse(null),
                    "IssuerProfile '" + id + "' is registered but has no cases in "
                            + "AllBrandsDetectionTest.CASES — please add at least one "
                            + "brand-detection case for this issuer.");
        }
    }

    @Test
    void totalCaseCount_meetsCoverageFloor() {
        // Today the registry exposes 60+ brand identifiers across issuer-specific
        // profiles. This floor protects against accidental brand deletions from a
        // profile (would silently drop detection). When you add brands legitimately,
        // bump this number.
        assertTrue(CASES.size() >= 60,
                "Brand-coverage test should cover at least 60 brands across all issuers. "
                        + "Current: " + CASES.size());
    }
}
