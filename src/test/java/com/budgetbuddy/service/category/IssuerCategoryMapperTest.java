package com.budgetbuddy.service.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link IssuerCategoryMapper} which maps the issuer-provided
 * MCC-style category trailers (Amex / Discover / etc.) to internal
 * categories. This is the lookup-table that drives the new L2.5
 * cascade layer.
 */
class IssuerCategoryMapperTest {

    @Test
    @DisplayName("Direct map: exact Amex trailer → internal category")
    void mapExactAmexTrailers() {
        assertEquals("dining", IssuerCategoryMapper.map("RESTAURANT"));
        assertEquals("dining", IssuerCategoryMapper.map("Restaurant"));
        assertEquals("dining", IssuerCategoryMapper.map("  restaurant  "));
        assertEquals("transportation", IssuerCategoryMapper.map("PARKING"));
        assertEquals("travel", IssuerCategoryMapper.map("LODGING"));
        assertEquals("utilities", IssuerCategoryMapper.map("CABLE & PAY TV"));
        assertEquals("shopping", IssuerCategoryMapper.map("MISC/SPECIALTY RETAIL"));
        assertEquals("subscriptions", IssuerCategoryMapper.map("SUBSCRIPTION"));
        assertEquals("tech", IssuerCategoryMapper.map("COMPUTER NETWORK/INFO"));
        assertEquals("health", IssuerCategoryMapper.map("DRUG STORES & PHARMACIES"));
    }

    @Test
    @DisplayName("Unknown trailer → null (no fallback hallucinations)")
    void unknownTrailerReturnsNull() {
        assertNull(IssuerCategoryMapper.map("GOODS/SERVICES"),
                "GOODS/SERVICES is intentionally NOT mapped — too vague");
        assertNull(IssuerCategoryMapper.map("INVENTED CATEGORY"));
        assertNull(IssuerCategoryMapper.map(""));
        assertNull(IssuerCategoryMapper.map(null));
    }

    @Test
    @DisplayName("Scan: embedded trailer in long Amex description")
    void scanFindsEmbeddedTrailer() {
        // Real Amex shape: merchant name + city + state + trailer
        final String desc =
                "aplpay molly tea (bellevue) bellevue wa restaurant".toLowerCase();
        assertEquals("dining", IssuerCategoryMapper.scan(desc));
    }

    @Test
    @DisplayName("Scan: prefers longest matching trailer (specificity)")
    void scanPrefersLongestMatch() {
        // Description contains both "restaurant" and "misc eating places".
        // The longer phrase should win.
        final String desc =
                "some merchant city wa misc eating places".toLowerCase();
        assertEquals("dining", IssuerCategoryMapper.scan(desc));
    }

    @Test
    @DisplayName("Scan: empty / null inputs return null")
    void scanSafeOnEmpty() {
        assertNull(IssuerCategoryMapper.scan(""));
        assertNull(IssuerCategoryMapper.scan(null));
    }

    @Test
    @DisplayName("Scan: description with no known trailer returns null")
    void scanNoMatch() {
        assertNull(IssuerCategoryMapper.scan("some random merchant string with no mcc text"));
    }

    @Test
    @DisplayName("knownTrailers exposes the lookup table — at least 70 entries")
    void knownTrailersIsReasonablyComplete() {
        assertNotNull(IssuerCategoryMapper.knownTrailers());
        assertTrue(IssuerCategoryMapper.knownTrailers().size() >= 70,
                "Mapper should cover at least 70 trailer phrases across the major categories");
    }

    @Test
    @DisplayName("Car/auto rental → travel (NOT transportation — vacation spend)")
    void carRentalIsTravelNotTransportation() {
        assertEquals("travel", IssuerCategoryMapper.map("CAR RENTAL"));
        assertEquals("travel", IssuerCategoryMapper.map("AUTO RENTAL"));
        assertEquals("travel", IssuerCategoryMapper.map("RENTAL CAR"));
    }

    @Test
    @DisplayName("Salon / barber / beauty trailers → health (not other or shopping)")
    void personalCareTrailersMapToHealth() {
        assertEquals("health", IssuerCategoryMapper.map("BEAUTY SHOPS"));
        assertEquals("health", IssuerCategoryMapper.map("BARBER SHOPS"));
        assertEquals("health", IssuerCategoryMapper.map("HAIR SALONS"));
    }

    @Test
    @DisplayName("Hardware / home-improvement trailers map to home improvement")
    void hardwareMapsToHomeImprovement() {
        assertEquals("home improvement", IssuerCategoryMapper.map("HARDWARE STORES"));
        assertEquals("home improvement", IssuerCategoryMapper.map("HOME IMPROVEMENT"));
    }

    @Test
    @DisplayName("Government / tax / professional services trailers → fees")
    void governmentAndTaxesMapToFees() {
        assertEquals("fees", IssuerCategoryMapper.map("GOVERNMENT SERVICES"));
        assertEquals("fees", IssuerCategoryMapper.map("TAX PAYMENTS"));
        assertEquals("fees", IssuerCategoryMapper.map("PROFESSIONAL SERVICES"));
    }

    @Test
    @DisplayName("Insurance trailers → insurance")
    void insuranceMaps() {
        assertEquals("insurance", IssuerCategoryMapper.map("INSURANCE SALES"));
        assertEquals("insurance", IssuerCategoryMapper.map("INSURANCE PREMIUMS"));
    }

    @Test
    @DisplayName("Airport (alone, without '& terminal') → travel")
    void airportAloneMapsToTravel() {
        assertEquals("travel", IssuerCategoryMapper.map("AIRPORT"));
    }

    @Test
    @DisplayName("Membership clubs / country clubs / sporting events → entertainment")
    void membershipAndSportsMapToEntertainment() {
        assertEquals("entertainment", IssuerCategoryMapper.map("MEMBERSHIP CLUBS"));
        assertEquals("entertainment", IssuerCategoryMapper.map("COUNTRY CLUBS"));
        assertEquals("entertainment", IssuerCategoryMapper.map("SPORTING EVENTS"));
    }

    @Test
    @DisplayName("Bookstores → shopping (not education)")
    void bookstoresMapToShopping() {
        assertEquals("shopping", IssuerCategoryMapper.map("BOOKSTORES"));
    }

    @Test
    @DisplayName("International English trailers (UK/AU) map correctly")
    void internationalTrailersMap() {
        assertEquals("dining", IssuerCategoryMapper.map("PUBLIC HOUSES"));        // UK pubs
        assertEquals("dining", IssuerCategoryMapper.map("TAKEAWAY FOOD SHOPS"));
        assertEquals("groceries", IssuerCategoryMapper.map("OFF LICENCE"));       // UK liquor
        assertEquals("groceries", IssuerCategoryMapper.map("BOTTLE SHOPS"));      // AU
        assertEquals("transportation", IssuerCategoryMapper.map("PETROL STATIONS"));
        assertEquals("fees", IssuerCategoryMapper.map("COUNCIL TAX"));            // UK
    }

    @Test
    @DisplayName("Common dining sub-types — pizza/coffee/bakery/ice cream all map to dining")
    void diningSubTypesMap() {
        assertEquals("dining", IssuerCategoryMapper.map("PIZZA RESTAURANTS"));
        assertEquals("dining", IssuerCategoryMapper.map("COFFEE SHOPS"));
        assertEquals("dining", IssuerCategoryMapper.map("BAKERIES"));
        assertEquals("dining", IssuerCategoryMapper.map("ICE CREAM PARLORS"));
        assertEquals("dining", IssuerCategoryMapper.map("DELICATESSEN"));
        assertEquals("dining", IssuerCategoryMapper.map("WINE BARS"));
    }

    @Test
    @DisplayName("Banking-specific trailers (NSF/overdraft/wire/atm fees) map to fees")
    void bankingFeesMap() {
        assertEquals("fees", IssuerCategoryMapper.map("NSF FEE"));
        assertEquals("fees", IssuerCategoryMapper.map("OVERDRAFT FEE"));
        assertEquals("fees", IssuerCategoryMapper.map("ATM FEE"));
        assertEquals("fees", IssuerCategoryMapper.map("WIRE TRANSFER FEE"));
        assertEquals("fees", IssuerCategoryMapper.map("STOP PAYMENT FEE"));
        assertEquals("fees", IssuerCategoryMapper.map("RETURNED CHECK FEE"));
        assertEquals("fees", IssuerCategoryMapper.map("FOREIGN TRANSACTION FEE"));
        assertEquals("fees", IssuerCategoryMapper.map("CASHIERS CHECK"));
        assertEquals("fees", IssuerCategoryMapper.map("MONEY ORDER"));
        // Brokerage / advisory fees on investment statements
        assertEquals("fees", IssuerCategoryMapper.map("BROKERAGE FEES"));
        assertEquals("fees", IssuerCategoryMapper.map("MANAGEMENT FEES"));
        assertEquals("fees", IssuerCategoryMapper.map("ADVISORY FEES"));
    }

    @Test
    @DisplayName("Tolls + bicycle shops → transportation")
    void miscTransportTrailers() {
        assertEquals("transportation", IssuerCategoryMapper.map("TOLLS AND BRIDGE FEES"));
        assertEquals("transportation", IssuerCategoryMapper.map("TOLL BRIDGE"));
        assertEquals("transportation", IssuerCategoryMapper.map("BICYCLE SHOPS"));
    }

    @Test
    @DisplayName("Postal services → fees (government services)")
    void postalServicesMapToFees() {
        assertEquals("fees", IssuerCategoryMapper.map("POSTAL SERVICES"));
    }
}
