package com.budgetbuddy.service.ml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class MerchantLocationSplitterTest {

    @Test
    void splitsUsCityStateNoComma() {
        final MerchantLocationSplitter.Split s =
                MerchantLocationSplitter.split("AMAZON MARKETPLACE SEATTLE WA");
        assertEquals("AMAZON MARKETPLACE", s.merchant());
        assertEquals("Seattle, WA", s.location());
    }

    @Test
    void splitsUsCityStateWithComma() {
        final MerchantLocationSplitter.Split s =
                MerchantLocationSplitter.split("STARBUCKS #1234 SAN FRANCISCO, CA");
        assertEquals("STARBUCKS #1234", s.merchant());
        assertEquals("San Francisco, CA", s.location());
    }

    @Test
    void stripsTrailingZip() {
        final MerchantLocationSplitter.Split s =
                MerchantLocationSplitter.split("WHOLE FOODS MKT #123 SEATTLE WA 98101");
        assertEquals("WHOLE FOODS MKT #123", s.merchant());
        assertEquals("Seattle, WA", s.location());
    }

    @Test
    void stripsTrailingReferenceCode() {
        final MerchantLocationSplitter.Split s =
                MerchantLocationSplitter.split("AMAZON MARKETPLACE SEATTLE WA 98101 *A1B2C3");
        assertEquals("AMAZON MARKETPLACE", s.merchant());
        assertEquals("Seattle, WA", s.location());
    }

    @Test
    void canadianProvinceTail() {
        final MerchantLocationSplitter.Split s =
                MerchantLocationSplitter.split("TIM HORTONS TORONTO ON");
        assertEquals("TIM HORTONS", s.merchant());
        assertEquals("Toronto, ON", s.location());
    }

    @Test
    void internationalIsoCode() {
        final MerchantLocationSplitter.Split s =
                MerchantLocationSplitter.split("PRET A MANGER LONDON GBR");
        assertEquals("PRET A MANGER", s.merchant());
        assertEquals("London, GBR", s.location());
    }

    @Test
    void leavesAloneWhenNoLocationTail() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split("NETFLIX.COM");
        assertEquals("NETFLIX.COM", s.merchant());
        assertNull(s.location());
    }

    @Test
    void recognizesAlpha2CountryTrailer() {
        // Alpha-2 ISO country codes (GB, JP, FR, etc.) are now recognized
        // as country trailers — this lets us extract location on real
        // international Discover/Amex rows like "PRET A MANGER LONDON GB".
        // Tradeoff: ambiguous 3-token strings like "UBER EATS GB" get
        // parsed as merchant="UBER" location="Eats, GB" — semantically
        // wrong for Uber Eats specifically, but the alternative (don't
        // recognize alpha-2 countries at all) costs us many more correct
        // extractions on real-world international rows.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "PRET A MANGER LONDON GB");
        assertEquals("PRET A MANGER", s.merchant());
        assertEquals("London, GB", s.location());
    }

    @Test
    void nullInputIsSafe() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(null);
        assertNull(s.merchant());
        assertNull(s.location());
    }

    @Test
    void multiWordCityTitleCases() {
        final MerchantLocationSplitter.Split s =
                MerchantLocationSplitter.split("SAFEWAY 1234 SAN JOSE CA");
        assertEquals("SAFEWAY 1234", s.merchant());
        assertEquals("San Jose, CA", s.location());
    }

    @Test
    void santaPrefixIsRecognized() {
        // Pre-fix this returned merchant="MARRIOTT SANTA CLARA SANTA"
        // location="Clara, CA" because "SANTA" wasn't in the compound-prefix
        // set. Two-word city "Santa Clara" must be kept together.
        final MerchantLocationSplitter.Split s =
                MerchantLocationSplitter.split("MARRIOTT SANTA CLARA SANTA CLARA CA");
        assertEquals("MARRIOTT SANTA CLARA", s.merchant());
        assertEquals("Santa Clara, CA", s.location());
    }

    @Test
    void discoverCategoryTrailerIsStripped() {
        // Discover statements suffix each row with a category label after
        // the city/state ("Restaurants", "Hotels", "Travel/Entertainment").
        // The trailer must be peeled before state detection runs, otherwise
        // the last token is the category not the state.
        final MerchantLocationSplitter.Split s =
                MerchantLocationSplitter.split("AMZ SJ14 CAFE SUNNYVALE CA Restaurants");
        assertEquals("AMZ SJ14 CAFE", s.merchant());
        assertEquals("Sunnyvale, CA", s.location());
    }

    @Test
    void discoverApplePayTrailerIsStripped() {
        // Discover account-activity statements suffix with "APPLE PAY
        // ENDING IN <digits>". Strip before state detection.
        final MerchantLocationSplitter.Split s =
                MerchantLocationSplitter.split(
                        "TST* SALT & STRAW - TOTE KIRKLAND WA  APPLE PAY ENDING IN 8772");
        assertEquals("TST* SALT & STRAW - TOTE", s.merchant());
        assertEquals("Kirkland, WA", s.location());
    }

    @Test
    void discoverInternationalWithFxAndApplePay() {
        // Combination of trailers — Apple Pay + FX block. Both must strip.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "PRET A MANGER LONDON GBR  APPLE PAY ENDING IN 8772 4.20 @ 00000001.3476190 GBP");
        assertEquals("PRET A MANGER", s.merchant());
        assertEquals("London, GBR", s.location());
    }

    @Test
    void interiorPhoneDoesNotBlockExtraction() {
        // When a phone runs between merchant and city/state we still
        // extract location correctly. The phone may stay attached to the
        // merchant name (downstream normalization handles it).
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "STARBUCKS 800-555-1212 BELLEVUE WA");
        assertEquals("Bellevue, WA", s.location());
    }

    @Test
    void gluedCityStateIsRepaired() {
        // PDF extraction sometimes loses the space between the last city
        // word and the state code ("MOUNTAIN VIEWCA" instead of "MOUNTAIN
        // VIEW CA"). Splitter should re-insert the space when the trailing
        // 2 letters are a known state and the preceding letters look like
        // a city word.
        final MerchantLocationSplitter.Split s =
                MerchantLocationSplitter.split("Cucina Venti MOUNTAIN VIEWCA");
        assertEquals("Cucina Venti", s.merchant());
        assertEquals("Mountain View, CA", s.location());
    }

    @Test
    void gluedCityStateNotFiredOnPureRefcode() {
        // Don't repair "12345CA" — leading digits mean the trailer is a
        // reference code, not a glued state.
        final MerchantLocationSplitter.Split s =
                MerchantLocationSplitter.split("MERCHANT 12345CA");
        // Either result is acceptable; the contract is that we don't
        // hallucinate location="345, CA". State must be null OR
        // properly null (no city extraction).
        assertNull(s.location());
    }

    @Test
    void amexCategoryTrailerStripped() {
        // Amex statements suffix rows with category labels like
        // "MISC/SPECIALTY RETAIL", "GIFT CARD", "RESTAURANT", etc.
        final MerchantLocationSplitter.Split a = MerchantLocationSplitter.split(
                "GOOGLE *YOUTUBE MUSIC G.CO/HELPPAY# CA CABLE & PAY TV");
        assertEquals("CA", a.location() == null ? null : a.location().split(",\\s*")[
                a.location().split(",\\s*").length - 1].trim());
    }

    @Test
    void aplPayHyattWithStoreIdAndZipTrailer() {
        // Trailing "5729 98056 RESTAURANT" — store-id + ZIP + category —
        // all stripped before state-code detection runs.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "AplPay HYATT REG LKE WSHGTN F&B SEARL RENTON WA 5729 98056 RESTAURANT");
        assertEquals("Renton, WA", s.location());
    }

    @Test
    void aplPayInstacartWithIntlPhoneTrailer() {
        // "+18882467822" — leading-plus international phone format — must
        // strip before state-code detection.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "AplPay IC* INSTACART SAN FRANCISCO CA +18882467822");
        assertEquals("San Francisco, CA", s.location());
    }

    @Test
    void stateOnlyFromPhoneStrippedTwoTokens() {
        // After phone strip we have just "STARBUCKS WA" — 2 tokens, no
        // city possible. Should surface state-only location.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "STARBUCKS 800-782-7282 WA GIFT CARD");
        // GIFT CARD trailer strip + phone strip → "STARBUCKS WA". Location
        // is state-only ("WA"), not null.
        assertEquals("WA", s.location());
    }

    @Test
    void straysInteriorStateTokenIsSkipped() {
        // "SUMMIT RTP SNOQUALMIE PA WA" — "PA" is a stray store-code
        // artifact between the city and the real state. Splitter should
        // step over it and pick city="Snoqualmie", state="WA".
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "SUMMIT RTP SNOQUALMIE PA WA");
        assertEquals("Snoqualmie, WA", s.location());
    }

    @Test
    void hotelFolioNoiseTrailerStripped() {
        // Costco Travel + some hotel-booking flows append a "PHONE NUMBER:
        // FOLIO NUMBER: ARRIVE: 00/00/00 DEPART: 00/00/00" block after
        // the city/state. Strip and the row should extract normally.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "HYATT HOTEL AVANTE MOUNTAIN VIEWCA PHONE NUMBER: FOLIO NUMBER: ARRIVE: 00/00/00 DEPART: 00/00/00");
        assertEquals("Mountain View, CA", s.location());
    }

    @Test
    void noFallthroughExpansion_singleWordFailureDoesNotExpand() {
        // Regression: the splitter must NOT yield city="VENDING SA" — that
        // was the historical false positive. State-only "CA" IS acceptable
        // (the state really is there in the source). Pin: when location is
        // populated, it must be state-only, never a hallucinated multi-word
        // city.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "CPI*CANTEEN VENDING SA 800-628-8363 CA");
        if (s.location() != null) {
            assertEquals("CA", s.location(),
                    "if location is set, it must be state-only — not a "
                            + "hallucinated city. Got: " + s.location());
        }
    }
}
