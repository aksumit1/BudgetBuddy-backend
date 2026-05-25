package com.budgetbuddy.service.ml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Real-world bank-statement scenario tests. Each case is drawn from a known
 * pattern banks actually emit (not invented). Together these document the
 * common shapes the splitter must handle, so a future "fix" that breaks one
 * of these patterns gets caught immediately.
 *
 * <p>Sources for each scenario are noted inline. Most patterns came from the
 * local corpus audit; a few are well-known industry shapes (Plaid descriptor
 * docs, USPS suffix conventions, ISO-3166 codes).
 */
class MerchantLocationRealWorldScenariosTest {

    // ====================================================================
    //  US RETAIL — single-word cities, common chains
    // ====================================================================

    @Test
    void costcoWholesalePrintsCityStateInline() {
        // Costco's PDF: merchant + many spaces + city + WS + state.
        // Spaces get collapsed by the splitter's tokenizer.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "COSTCO WHSE #0006        TUKWILA      WA");
        assertEquals("Tukwila, WA", s.location());
    }

    @Test
    void costcoGasPrintsAbbreviatedDescriptor() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "COSTCO GAS #0110         ISSAQUAH     WA");
        assertEquals("Issaquah, WA", s.location());
    }

    @Test
    void targetWithStorePrefix() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "TARGET 00027143 BELLEVUE WA");
        assertEquals("Bellevue, WA", s.location());
    }

    @Test
    void walmartFamilyMembersWithStorePrefix() {
        // Walmart prints "WMT" or "WAL-MART SUPERCENTER #1234"
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "WAL-MART SUPERCENTER #1234 BENTONVILLE AR");
        assertEquals("Bentonville, AR", s.location());
    }

    // ====================================================================
    //  US RESTAURANTS — TST*, AplPay, SQ*, square chain prefixes
    // ====================================================================

    @Test
    void toastDescriptorWithMerchantName() {
        // Toast POS prepends "TST*" and may glue tokens together.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "TST* SALT & STRAW - TOTE KIRKLAND WA");
        assertEquals("Kirkland, WA", s.location());
    }

    @Test
    void squareCashAppDescriptorWithCity() {
        // Square: "SQ *MERCHANT NAME"
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "SQ *BLUE BOTTLE COFFEE SAN FRANCISCO CA");
        assertEquals("San Francisco, CA", s.location());
    }

    @Test
    void applePayMerchantWithAplPayPrefix() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "AplPay STARBUCKS BELLEVUE WA");
        assertEquals("Bellevue, WA", s.location());
    }

    // ====================================================================
    //  MULTI-WORD CITIES — compound prefixes
    // ====================================================================

    @Test
    void sanFranciscoCompoundPrefix() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "WHOLE FOODS 12345 SAN FRANCISCO CA");
        assertEquals("San Francisco, CA", s.location());
    }

    @Test
    void losAngelesCompoundPrefix() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "AMC THEATERS LOS ANGELES CA");
        assertEquals("Los Angeles, CA", s.location());
    }

    @Test
    void newYorkCompoundPrefix() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "MOMA STORE NEW YORK NY");
        assertEquals("New York, NY", s.location());
    }

    @Test
    void santaClaraCompoundPrefix() {
        // The bug that exposed CITY_COMPOUND_PREFIXES needed "SANTA".
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "MARRIOTT SANTA CLARA SANTA CLARA CA");
        assertEquals("Santa Clara, CA", s.location());
    }

    @Test
    void fortLauderdaleCompoundPrefix() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "HILTON FORT LAUDERDALE FL");
        assertEquals("Fort Lauderdale, FL", s.location());
    }

    // ====================================================================
    //  INTERNATIONAL — ISO alpha-2 and alpha-3 country codes
    // ====================================================================

    @Test
    void londonGBAlpha2Trailer() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "PRET A MANGER LONDON GB");
        assertEquals("London, GB", s.location());
    }

    @Test
    void parisFRAlpha2Trailer() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "PRINTEMPS PARIS FR");
        assertEquals("Paris, FR", s.location());
    }

    @Test
    void tokyoJPAlpha2Trailer() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "BLUE BOTTLE COFFEE TOKYO JP");
        assertEquals("Tokyo, JP", s.location());
    }

    @Test
    void sydneyAUAlpha2Trailer() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "QANTAS AIRWAYS SYDNEY AU");
        assertEquals("Sydney, AU", s.location());
    }

    @Test
    void puneINAlpha2Trailer() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "THE WESTIN PUNE PUNE IN");
        assertEquals("Pune, IN", s.location());
    }

    @Test
    void torontoONCanadianProvince() {
        // Canadian provinces are detected as state with country=CA.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "TIM HORTONS TORONTO ON");
        assertEquals("Toronto, ON", s.location());
    }

    @Test
    void londonGBRAlpha3Trailer() {
        // Discover sometimes uses alpha-3 instead of alpha-2.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "PRET A MANGER LONDON GBR");
        assertEquals("London, GBR", s.location());
    }

    // ====================================================================
    //  DISCOVER — category trailers
    // ====================================================================

    @Test
    void discoverRestaurantTrailer() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "STARBUCKS BELLEVUE WA Restaurants");
        assertEquals("Bellevue, WA", s.location());
    }

    @Test
    void discoverHotelsTrailer() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "HYATT REGENCY SEATTLE WA Hotels");
        assertEquals("Seattle, WA", s.location());
    }

    @Test
    void discoverTravelEntertainmentTrailer() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "DELTA AIR LINES ATLANTA GA Travel/Entertainment");
        assertEquals("Atlanta, GA", s.location());
    }

    @Test
    void discoverWithApplePayAndFxTrailer() {
        // International with Apple Pay + FX block trailer.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "PRET A MANGER LONDON GBR  APPLE PAY ENDING IN 8772 4.20 @ 00000001.3476190 GBP");
        assertEquals("London, GBR", s.location());
    }

    // ====================================================================
    //  AMEX — category trailers + Platinum credit-row formats
    // ====================================================================

    @Test
    void amexMiscSpecialtyRetailTrailer() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "BLUE SKY NARITA 82 GATE TOKYO JP MISC/SPECIALTY RETAIL");
        // Tokyo can be parsed as city; JP as country.
        assertEquals("Tokyo, JP", s.location());
    }

    @Test
    void amexCableAndPayTVTrailer() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "GOOGLE *YOUTUBE MUSIC SAN FRANCISCO CA CABLE & PAY TV");
        assertEquals("San Francisco, CA", s.location());
    }

    @Test
    void amexGiftCardTrailer() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "STARBUCKS GIFT CARDS SEATTLE WA GIFT CARD");
        assertEquals("Seattle, WA", s.location());
    }

    @Test
    void amexServiceStnTrailer() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "CHEVRON SUNNYVALE CA SERVICE STN");
        assertEquals("Sunnyvale, CA", s.location());
    }

    // ====================================================================
    //  PDF EXTRACTION QUIRKS
    // ====================================================================

    @Test
    void gluedCityStateMountainView() {
        // PDFBox sometimes drops the space between city and state code.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "CUCINA VENTI MOUNTAIN VIEWCA");
        assertEquals("Mountain View, CA", s.location());
    }

    @Test
    void gluedCityStateBellevue() {
        // Same pattern — Bellevue, WA glued as "BELLEVUEWA".
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "STARBUCKS #1234 BELLEVUEWA");
        assertEquals("Bellevue, WA", s.location());
    }

    @Test
    void zipPlusFourTrailerStrips() {
        // ZIP+4 form is stripped along with regular ZIP.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "WHOLE FOODS MKT #123 SEATTLE WA 98101-1234");
        assertEquals("Seattle, WA", s.location());
    }

    // ====================================================================
    //  PHONE NOISE
    // ====================================================================

    @Test
    void interiorPhoneDoesntBlockExtraction() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "EXAMFX 8005862253 KANSAS CITY KS");
        // Phone is stripped, "Kansas City" extracted via compound prefix.
        assertEquals("Kansas City, KS", s.location());
    }

    @Test
    void aplPayInternationalPhoneTrailer() {
        // "+18882467822" form.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "AplPay IC* INSTACART SAN FRANCISCO CA +18882467822");
        assertEquals("San Francisco, CA", s.location());
    }

    @Test
    void stateOnlyAfterPhoneStrip() {
        // STARBUCKS 800-... WA GIFT CARD → state-only "WA".
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "STARBUCKS 800-782-7282 WA GIFT CARD");
        assertEquals("WA", s.location());
    }

    // ====================================================================
    //  RIDE-SHARE & DELIVERY
    // ====================================================================

    @Test
    void uberTripPhoneTrailer() {
        // Uber: "UBER TRIP HELP.UBER.COM CA 800-XXX-XXXX". After URL/phone
        // stripping, we get state CA only.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "AplPay Uber Trip CA 8005928996");
        // Phone stripped, "CA" trailer.
        assertNotNull(s.location(),
                "Uber trip with phone should yield at least state");
    }

    @Test
    void doorDashWithCity() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "DD *DOORDASH BLUE BOTTLE SAN FRANCISCO CA");
        assertEquals("San Francisco, CA", s.location());
    }

    // ====================================================================
    //  EDGE CASES — should NOT extract
    // ====================================================================

    @Test
    void noLocationForACHRow() {
        // ACH rows have no city/state.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "Morgan Stanley   ACH Credit                 PPD ID: 6427014001");
        assertNull(s.location(),
                "ACH rows must NOT have a fabricated location");
    }

    @Test
    void noLocationForAutoPay() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "AUTOMATIC PAYMENT - THANK YOU");
        assertNull(s.location());
    }

    @Test
    void noLocationForCheckRow() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "Check                         # 185");
        assertNull(s.location());
    }

    @Test
    void noLocationForOnlineTransfer() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "Online Transfer To Chk ...9994");
        assertNull(s.location());
    }

    // ====================================================================
    //  AMBIGUOUS — document expected behavior
    // ====================================================================

    @Test
    void cityNameThatHappensToContainStateCode_doesNotMisparse() {
        // "ALABASTER AL" — "Alabaster" is a real city in AL. Splitter must
        // not eat letters from "Alabaster" thinking they're a state code.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "PUBLIX 12345 ALABASTER AL");
        assertEquals("Alabaster, AL", s.location());
    }

    @Test
    void merchantEndingWithStatelikeLetters() {
        // "BJ" isn't a US state. Don't split.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "BJ'S WHOLESALE CLUB");
        assertNull(s.location());
    }

    @Test
    void shortDescriptionsNeverHallucinateLocation() {
        // 1- and 2-token strings can't have a location.
        assertNull(MerchantLocationSplitter.split("CA").location());
        assertNull(MerchantLocationSplitter.split("STARBUCKS").location());
        assertNull(MerchantLocationSplitter.split("X Y").location());
    }
}
