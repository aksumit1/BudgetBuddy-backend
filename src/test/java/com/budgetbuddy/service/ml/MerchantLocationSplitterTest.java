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
    void leavesAloneWhenTrailingTwoLettersAreNotAState() {
        // "GB" not a US state / Canadian province → don't amputate.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split("UBER EATS GB");
        assertEquals("UBER EATS GB", s.merchant());
        assertNull(s.location());
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
}
