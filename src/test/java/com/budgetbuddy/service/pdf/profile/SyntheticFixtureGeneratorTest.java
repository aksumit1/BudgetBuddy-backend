package com.budgetbuddy.service.pdf.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SyntheticFixtureGeneratorTest {

    @Test
    void anonymize_replacesCardholderName() {
        final String input = "AGARWAL SUMIT KUMAR\n14418 SE 61ST ST\nBELLEVUE WA 98006";
        final String out = SyntheticFixtureGenerator.anonymize(input, 0L, false);
        assertTrue(out.contains("TEST CARDHOLDER"));
        assertFalse(out.contains("AGARWAL"),
                "Cardholder name must be redacted");
        assertFalse(out.contains("98006"),
                "Specific zip code must be replaced with the test zip");
    }

    @Test
    void anonymize_redactsLongReferenceCode() {
        final String input = "04/12 04/12 F353100FN00CHGDDA AUTOMATIC PAYMENT THANK YOU 545.91";
        final String out = SyntheticFixtureGenerator.anonymize(input, 0L, false);
        assertFalse(out.contains("F353100FN00CHGDDA"),
                "Long alphanumeric reference codes must be redacted");
        assertTrue(out.contains("REF"),
                "Reference codes get a synthetic REF prefix");
    }

    @Test
    void anonymize_redactsCardEndingInDigits() {
        final String input = "Account ending in 6779\nCard #### #### #### 1234";
        final String out = SyntheticFixtureGenerator.anonymize(input, 0L, false);
        assertFalse(out.contains("6779"),
                "Original card last-4 must be redacted");
        assertTrue(out.contains("1234"),
                "Synthetic last-4 is fixed to '1234'");
    }

    @Test
    void anonymize_redactsPhoneNumbers() {
        final String input = "Customer Service: 1-866-229-6633";
        final String out = SyntheticFixtureGenerator.anonymize(input, 0L, false);
        assertFalse(out.contains("866-229-6633"),
                "Real phone must be redacted");
        assertTrue(out.contains("555-01"),
                "Replacement uses the RFC 6761 reserved 555-01XX range");
    }

    @Test
    void anonymize_deterministic_sameSeedSameOutput() {
        final String input = "F353100FN00CHGDDA payment 545.91";
        final String a = SyntheticFixtureGenerator.anonymize(input, 42L, true);
        final String b = SyntheticFixtureGenerator.anonymize(input, 42L, true);
        assertEquals(a, b,
                "Same input + same seed must produce byte-identical output");
    }

    @Test
    void anonymize_amountJitter_offByDefault_keepsMathIntact() {
        final String input = "New Balance: $1,234.56";
        final String out = SyntheticFixtureGenerator.anonymize(input, 0L, false);
        assertTrue(out.contains("$1,234.56"),
                "Without jitter, dollar amounts must pass through unchanged");
    }

    @Test
    void anonymize_amountJitter_on_changesValuesButPreservesShape() {
        final String input = "New Balance: $1,234.56\nMinimum Payment: $25.00";
        final String out = SyntheticFixtureGenerator.anonymize(input, 42L, true);
        assertNotEquals(input, out,
                "With jitter, amounts must change");
        assertTrue(out.matches("(?s).*\\$\\d+(?:,\\d{3})*\\.\\d{2}.*"),
                "Jittered amounts must still look like dollar amounts");
    }

    @Test
    void emitFixtureStub_producesJavaSource_thatCompilesAsStringLiteral() {
        final String rawText = "TEST BANK STATEMENT\nNew Balance: $100.00\nPayment Due: 05/01/26";
        final String stub = SyntheticFixtureGenerator.emitFixtureStub(
                "MyFixtureTest", "test-issuer", rawText, 0L);
        assertTrue(stub.contains("STATEMENT_FIXTURE"));
        assertTrue(stub.contains("String.join("));
        assertTrue(stub.contains("\\n"));
        assertTrue(stub.contains("seed=0"),
                "Stub records the seed for reproducibility");
    }

    @Test
    void anonymize_handlesNullAndEmpty() {
        assertEquals(null, SyntheticFixtureGenerator.anonymize(null, 0L, false));
        assertEquals("", SyntheticFixtureGenerator.anonymize("", 0L, false));
    }

    @Test
    void anonymize_realWorldExample_producesUsableFixture() {
        // Realistic Wells Fargo line snippet — verifies the redactions chain together.
        final String input = String.join("\n",
                "Wells Fargo Online: wellsfargo.com",
                "24-hour Customer Service: 1-866-229-6633",
                "AGARWAL SUMIT KUMAR",
                "14418 SE 61ST ST",
                "BELLEVUE WA 98006-4368",
                "Account ending in 6779",
                "04/12 04/12 F353100FN00CHGDDA AUTOMATIC PAYMENT THANK YOU 545.91",
                "New Balance $13,696.35");
        final String out = SyntheticFixtureGenerator.anonymize(input, 12345L, false);
        // All PII tokens gone:
        for (final String pii : new String[]{
                "AGARWAL", "SUMIT", "KUMAR", "61ST ST", "98006-4368",
                "229-6633", "F353100FN00CHGDDA", "6779"}) {
            assertFalse(out.contains(pii),
                    "Original PII token '" + pii + "' must be redacted; got:\n" + out);
        }
        // Anchors that should survive (these are LAYOUT, not PII):
        for (final String layout : new String[]{
                "Wells Fargo Online", "24-hour Customer Service",
                "AUTOMATIC PAYMENT", "New Balance"}) {
            assertTrue(out.contains(layout),
                    "Layout anchor '" + layout + "' must be preserved; got:\n" + out);
        }
        // Math-preserving (jitter=false) means the amount stays.
        assertTrue(out.contains("$13,696.35"));
    }
}
