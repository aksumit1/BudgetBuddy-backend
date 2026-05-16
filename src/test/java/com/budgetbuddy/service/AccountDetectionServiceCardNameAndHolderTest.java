package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the card-name + account-holder extractors.
 *
 * <p>Documents three discrete bug fixes from the multi-statement Amex import:
 *
 * <ul>
 *   <li><b>Card name "Events with Amex Benefit Removal"</b> — the product-name
 *       scan walked all 300 header lines and false-positive matched a marketing
 *       footer on page 6. Restricted to first 30 lines.
 *   <li><b>Account holder "s and Centurion" / "Agreement for"</b> — the
 *       cardholder validator accepted partial regex captures from boilerplate
 *       prose ("Card Members and Centurion Cardmembers" → "s and Centurion").
 *       Now rejects connector words and leading single-char non-initials.
 *   <li><b>Real middle initials are preserved</b> — "Roger A Fernandes" must
 *       still extract correctly; the connector-word filter excludes
 *       single-char words because "A" could be an initial.
 * </ul>
 */
class AccountDetectionServiceCardNameAndHolderTest {

    private AccountDetectionService service;
    private Method extractProductName;
    private Method extractHolderName;

    @BeforeEach
    void setUp() throws Exception {
        service = new AccountDetectionService(null, new BalanceExtractor());
        extractProductName =
                AccountDetectionService.class.getDeclaredMethod(
                        "extractProductNameFromPDF", String.class);
        extractProductName.setAccessible(true);
        extractHolderName =
                AccountDetectionService.class.getDeclaredMethod(
                        "extractAccountHolderNameFromPDF", String.class);
        extractHolderName.setAccessible(true);
    }

    private static final List<String> EXCLUDED =
            Arrays.asList(
                    "sale", "post", "date", "description", "amount",
                    "payments", "credits", "adjustments", "summary", "history");

    @Test
    @DisplayName("Product name scan is limited to the first 30 lines — marketing footer ignored")
    void productNameScan_ignoresMarketingFooter() throws Exception {
        // Real bug: the Jan-Feb Amex statement had "Events with Amexº Benefit
        // Removal" as a section header at line ~483. The product-name extractor
        // was scanning the full 300-line header text and matched that marketing
        // line, returning it as the card product.
        final StringBuilder text = new StringBuilder();
        text.append("American Express Blue Business Cash°\n");
        text.append("AGARWAL SUMIT KUMAR\n");
        text.append("Closing Date 02/16/26\n");
        text.append("Account Ending 1-21002\n");
        // Pad with 40+ filler lines.
        for (int i = 0; i < 60; i++) {
            text.append("filler line ").append(i).append("\n");
        }
        // Then the marketing footer that USED TO win:
        text.append("Events with Amexº Benefit Removal\n");
        text.append("Effective June 10th, 2026, Events with Amexº will be removed\n");

        final String result = (String) extractProductName.invoke(service, text.toString());
        assertNotNull(result, "Should find product name in first 30 lines");
        assertEquals("American Express Blue Business Cash°", result,
                "Header card name must win over marketing footer beyond line 30");
    }

    @Test
    @DisplayName("Account holder validator rejects 's and Centurion'-shape boilerplate")
    void accountHolderValidator_rejectsConnectorWordPhrases() throws Exception {
        final Method validate =
                AccountDetectionService.class.getDeclaredMethod(
                        "extractAndValidateName", String.class, List.class);
        validate.setAccessible(true);

        // "Card Members and Centurion Cardmembers" → captured as "s and
        // Centurion Cardmembers" by an over-broad regex. Should be rejected
        // because it contains the connector word "and".
        assertNull(validate.invoke(service, "s and Centurion Cardmembers", EXCLUDED),
                "Names containing the connector 'and' must be rejected");

        // "Agreement for" from "Cardmember Agreement for details" — connector
        // word "for".
        assertNull(validate.invoke(service, "Agreement for", EXCLUDED),
                "Names containing the connector 'for' must be rejected");

        // Leading single-character non-initial. "s and Centurion" without the
        // remainder still has leading "s" — caught by leading-single-char check.
        assertNull(validate.invoke(service, "s Centurion", EXCLUDED),
                "Leading single-char non-initial words must be rejected");
    }

    @Test
    @DisplayName("Account holder validator preserves middle initials (Roger A Fernandes)")
    void accountHolderValidator_preservesMiddleInitial() throws Exception {
        final Method validate =
                AccountDetectionService.class.getDeclaredMethod(
                        "extractAndValidateName", String.class, List.class);
        validate.setAccessible(true);

        // Single-char "A" is a middle initial here, NOT a connector. The
        // filter excludes single-char strings from the connectors set (because
        // "a" and "A" are indistinguishable until you look at context).
        assertEquals("Roger A Fernandes",
                validate.invoke(service, "Roger A Fernandes", EXCLUDED),
                "Middle initials (single capital letter, no period) must survive");

        // All-uppercase three-word names (typical Amex format) survive.
        assertEquals("AGARWAL SUMIT KUMAR",
                validate.invoke(service, "AGARWAL SUMIT KUMAR", EXCLUDED));

        // Suffixes like "Jr" / "III" survive.
        assertEquals("John Smith Jr",
                validate.invoke(service, "John Smith Jr", EXCLUDED));
    }

    @Test
    @DisplayName("Account holder name extracted from 3-line address layout")
    void accountHolderName_threeLineAddressLayout() throws Exception {
        // Three-line shape: name / street / city-state-ZIP.
        final String headerText =
                "Roger A Fernandes\n12345 NE 17ST ST\nSEATTLE  WA  91114-3211";
        final String result = (String) extractHolderName.invoke(service, headerText);
        assertNotNull(result, "Should extract name from previous line above the address");
        assertEquals("Roger A Fernandes", result);
    }
}
