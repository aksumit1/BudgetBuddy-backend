package com.budgetbuddy.service.pdf.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.pdf.profile.WalletProviderDetector.WalletProvider;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WalletProviderDetectorTest {

    @Test
    void detect_applePay_fromMultiplePrefixForms() {
        assertEquals(Optional.of(WalletProvider.APPLE_PAY),
                WalletProviderDetector.detect("APL*STARBUCKS #00331 SEATTLE WA"));
        assertEquals(Optional.of(WalletProvider.APPLE_PAY),
                WalletProviderDetector.detect("Apple Pay - Whole Foods Market"));
        assertEquals(Optional.of(WalletProvider.APPLE_PAY),
                WalletProviderDetector.detect("Apple Pay Whole Foods Market"));
    }

    @Test
    void detect_googlePay() {
        assertEquals(Optional.of(WalletProvider.GOOGLE_PAY),
                WalletProviderDetector.detect("GOOGLE *YOUTUBE PREMIUM"));
        assertEquals(Optional.of(WalletProvider.GOOGLE_PAY),
                WalletProviderDetector.detect("GPAY*SUPERMARKET"));
    }

    @Test
    void detect_samsungPay() {
        assertEquals(Optional.of(WalletProvider.SAMSUNG_PAY),
                WalletProviderDetector.detect("SAMSUNG *MEMBERS"));
    }

    @Test
    void detect_payPal() {
        assertEquals(Optional.of(WalletProvider.PAYPAL),
                WalletProviderDetector.detect("PYPL *EBAY US"));
        assertEquals(Optional.of(WalletProvider.PAYPAL),
                WalletProviderDetector.detect("PayPal *Etsy"));
        assertEquals(Optional.of(WalletProvider.PAYPAL),
                WalletProviderDetector.detect("PAYPAL DES:INST XFER ID:..."));
    }

    @Test
    void detect_square() {
        assertEquals(Optional.of(WalletProvider.SQUARE),
                WalletProviderDetector.detect("SQ *LOCAL CAFE BELLEVUE"));
        assertEquals(Optional.of(WalletProvider.SQUARE),
                WalletProviderDetector.detect("TST* RESTAURANT SEATTLE"));
    }

    @Test
    void detect_venmo() {
        assertEquals(Optional.of(WalletProvider.VENMO),
                WalletProviderDetector.detect("VENMO *USER PAY"));
    }

    @Test
    void detect_zelle() {
        assertEquals(Optional.of(WalletProvider.ZELLE),
                WalletProviderDetector.detect("ZELLE TO LANDLORD"));
        assertEquals(Optional.of(WalletProvider.ZELLE),
                WalletProviderDetector.detect("ZELLE FROM ROOMMATE"));
    }

    @Test
    void detect_cashApp() {
        assertEquals(Optional.of(WalletProvider.CASH_APP),
                WalletProviderDetector.detect("CASH APP*FRIEND PAYMENT"));
    }

    @Test
    void detect_returnsEmpty_forNormalMerchantDescriptions() {
        assertTrue(WalletProviderDetector.detect("SAFEWAY #1444 BELLEVUE WA").isEmpty());
        assertTrue(WalletProviderDetector.detect("STARBUCKS STORE 00331").isEmpty());
        assertTrue(WalletProviderDetector.detect("AUTOMATIC PAYMENT - THANK YOU").isEmpty());
    }

    @Test
    void detect_handlesNullAndBlank() {
        assertTrue(WalletProviderDetector.detect(null).isEmpty());
        assertTrue(WalletProviderDetector.detect("").isEmpty());
        assertTrue(WalletProviderDetector.detect("   ").isEmpty());
    }

    @Test
    void detect_doesNotFalsePositive_onMerchantsContainingPaymentNetworkInName() {
        // "PAYMENT" appears in many merchant descriptions; that alone shouldn't flag PayPal.
        assertTrue(WalletProviderDetector.detect("AUTOMATIC PAYMENT BANK").isEmpty());
        // "APPLE" alone (e.g. "APPLE STORE") is NOT Apple Pay — physical store purchase.
        assertTrue(WalletProviderDetector.detect("APPLE STORE BELLEVUE WA").isEmpty());
        // "GOOGLE" alone could be Google Cloud charge — generic, not GPay.
        assertTrue(WalletProviderDetector.detect("GOOGLE CLOUD US SUBSCRIPTION").isEmpty());
    }

    @Test
    void detectName_returnsWireString() {
        assertEquals("apple-pay", WalletProviderDetector.detectName("APL*STARBUCKS"));
        assertEquals("google-pay", WalletProviderDetector.detectName("GOOGLE *YOUTUBE"));
        assertEquals("paypal", WalletProviderDetector.detectName("PYPL *EBAY"));
        assertEquals("square", WalletProviderDetector.detectName("SQ *CAFE"));
        assertNull(WalletProviderDetector.detectName("SAFEWAY #1444"));
        assertNull(WalletProviderDetector.detectName(null));
    }

    @Test
    void detect_randomized_acrossKnownPrefixes() {
        // Verify every prefix shape detects to the right provider with random merchant
        // tails appended.
        final java.util.Random rng = new java.util.Random(0xCABA11E7L);
        final Object[][] cases = {
            {WalletProvider.APPLE_PAY, "APL*"},
            {WalletProvider.APPLE_PAY, "Apple Pay - "},
            {WalletProvider.GOOGLE_PAY, "GOOGLE *"},
            {WalletProvider.GOOGLE_PAY, "GPAY*"},
            {WalletProvider.SAMSUNG_PAY, "SAMSUNG *"},
            {WalletProvider.PAYPAL, "PYPL *"},
            {WalletProvider.PAYPAL, "PayPal *"},
            {WalletProvider.SQUARE, "SQ *"},
            {WalletProvider.VENMO, "VENMO *"},
            {WalletProvider.CASH_APP, "CASH APP*"},
        };
        for (int i = 0; i < 200; i++) {
            final Object[] c = cases[rng.nextInt(cases.length)];
            final WalletProvider expected = (WalletProvider) c[0];
            final String prefix = (String) c[1];
            // Random merchant tail
            final StringBuilder tail = new StringBuilder();
            for (int j = 0; j < 8 + rng.nextInt(20); j++) {
                tail.append((char) ('A' + rng.nextInt(26)));
            }
            final String description = prefix + tail;
            assertEquals(Optional.of(expected),
                    WalletProviderDetector.detect(description),
                    "Iter " + i + " desc='" + description + "'");
        }
    }
}
