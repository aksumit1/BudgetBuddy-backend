package com.budgetbuddy.service.pdf.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies the issuer-profile registry classifies every supported header style
 * correctly and falls back to the generic profile when nothing matches.
 */
class IssuerProfileRegistryTest {

    private final IssuerProfileRegistry registry = IssuerProfileRegistry.defaultRegistry();

    @Test
    void detectsChaseFromHeaderText() {
        final IssuerProfile p = registry.detect(
                "JPMorgan Chase Bank, N.A.\nwww.chase.com\nCHASE SAPPHIRE PREFERRED");
        assertEquals("chase", p.issuerId());
        assertEquals("sapphire-preferred", p.detectBrand("CHASE SAPPHIRE PREFERRED"));
    }

    @Test
    void detectsWellsFargoActiveCash() {
        final IssuerProfile p = registry.detect(
                "Wells Fargo Online: wellsfargo.com\nWELLS FARGO ACTIVE CASH VISA SIGNATURE");
        assertEquals("wells-fargo", p.issuerId());
        assertEquals("active-cash",
                p.detectBrand("WELLS FARGO ACTIVE CASH VISA SIGNATURE"));
    }

    @Test
    void detectsUSBankReflect() {
        final IssuerProfile p = registry.detect(
                "Open Date: 12/06/2025 Closing Date: 01/07/2026\nU.S. Bank\n"
                        + "U.S. Bank Smartly Visa Signature Card");
        assertEquals("us-bank", p.issuerId());
        assertEquals("smartly-visa-signature",
                p.detectBrand("U.S. Bank Smartly Visa Signature Card"));
    }

    @Test
    void detectsCitiDoubleCash() {
        final IssuerProfile p = registry.detect(
                "Citi Double Cash® Card\nMember Since 2015\nwww.citicards.com");
        assertEquals("citi", p.issuerId());
        assertEquals("double-cash", p.detectBrand("Citi Double Cash® Card"));
    }

    @Test
    void detectsCitiCostco() {
        final IssuerProfile p = registry.detect(
                "Costco Anywhere Visa® Card by Citi\nMember Since 2007\nwww.citicards.com");
        assertEquals("citi", p.issuerId());
        assertEquals("costco-anywhere-visa",
                p.detectBrand("Costco Anywhere Visa® Card by Citi"));
    }

    @Test
    void detectsAmexMorganStanleyPlatinum() {
        final IssuerProfile p = registry.detect(
                "Morgan Stanley Platinum Card®\nClosing Date 05/13/26\namericanexpress.com");
        assertEquals("amex", p.issuerId());
        assertEquals("morgan-stanley-platinum",
                p.detectBrand("Morgan Stanley Platinum Card®"));
    }

    @Test
    void detectsAmexBlueBusinessCash() {
        final IssuerProfile p = registry.detect(
                "American Express Blue Business Cash\nClosing Date 01/19/26\namericanexpress.com");
        assertEquals("amex", p.issuerId());
        assertEquals("blue-business-cash",
                p.detectBrand("American Express Blue Business Cash"));
    }

    @Test
    void detectsBankOfAmericaCashRewards() {
        final IssuerProfile p = registry.detect(
                "Bank of America Customized Cash Rewards\nbankofamerica.com\n"
                        + "Statement Closing Date: 04/15/2026");
        assertEquals("boa", p.issuerId());
        assertEquals("customized-cash-rewards",
                p.detectBrand("Bank of America Customized Cash Rewards"));
    }

    @Test
    void detectsAppleCard() {
        final IssuerProfile p = registry.detect(
                "Apple Card\nGoldman Sachs Bank USA\ncard.apple.com");
        assertEquals("apple-card", p.issuerId());
    }

    @Test
    void unknownHeader_fallsBackToGenericProfile() {
        final IssuerProfile p = registry.detect(
                "Some Mystery Bank Statement\nNo identifying terms\n");
        assertEquals("generic", p.issuerId());
        assertTrue(p instanceof GenericFallbackProfile);
    }

    @Test
    void nullHeader_returnsFallback() {
        final IssuerProfile p = registry.detect(null);
        assertNotNull(p);
        assertEquals("generic", p.issuerId());
    }

    @Test
    void appleCardWinsBeforeBoa_whenBothMentionGoldmanSachs() {
        // Apple Card's processor is Goldman Sachs. If a hypothetical statement mentioned
        // both Bank of America AND Goldman Sachs (unlikely but defensive), Apple Card
        // should win because it's earlier in the registry chain AND its match is more
        // specific.
        final IssuerProfile p = registry.detect("Apple Card · Goldman Sachs Bank USA");
        assertEquals("apple-card", p.issuerId());
    }

    @Test
    void profileFor_returnsRegisteredProfilesById() {
        assertEquals("chase", registry.profileFor("chase").issuerId());
        assertEquals("wells-fargo", registry.profileFor("wells-fargo").issuerId());
        assertEquals("citi", registry.profileFor("citi").issuerId());
        assertEquals("boa", registry.profileFor("boa").issuerId());
        assertEquals("apple-card", registry.profileFor("apple-card").issuerId());
        assertNull(registry.profileFor("nonexistent"));
        assertNull(registry.profileFor(null));
    }

    @Test
    void registeredProfiles_arePresentInPriorityOrder() {
        final var profiles = registry.registeredProfiles();
        // First three in priority order — most-specific issuers come earliest.
        assertEquals("apple-card", profiles.get(0).issuerId());
        assertEquals("boa", profiles.get(1).issuerId());
        assertEquals("discover", profiles.get(2).issuerId());
        assertEquals(8, profiles.size(), "Should have 8 registered issuer-specific profiles");
    }

    @Test
    void detectsDiscoverIt() {
        final IssuerProfile p = registry.detect(
                "Discover it Card\nwww.discover.com\nCardmember Services");
        assertEquals("discover", p.issuerId());
        assertEquals("discover-it", p.detectBrand("Discover it Card"));
    }

    @Test
    void detectsDiscoverItMiles_winsOverGenericDiscoverIt() {
        // Order in BRANDS matters: more-specific "discover it miles" must match before
        // the generic "discover it" brand entry.
        final IssuerProfile p = registry.detect("Discover it Miles\nwww.discover.com");
        assertEquals("discover", p.issuerId());
        assertEquals("discover-it-miles", p.detectBrand("Discover it Miles"));
    }
}
