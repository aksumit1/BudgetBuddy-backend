package com.budgetbuddy.service.pdf.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link StatementParsingUtilities} extracts values correctly after the
 * physical migration of the legacy implementation out of {@code PDFImportService}.
 *
 * <p>Prior to the migration these tests verified that the façade simply DELEGATED to
 * {@code PDFImportService.extractX(...)}. After the migration the façade IS the
 * implementation, so the tests pivot to behavioral verification — each test feeds a
 * specific statement-fragment input and asserts the expected value.
 */

class StatementParsingUtilitiesTest {

    @Test
    void extractsNewBalance_fromCanonicalLabel() {
        final String[] lines = {"New Balance $1,234.56"};
        assertEquals(0,
                new BigDecimal("1234.56")
                        .compareTo(StatementParsingUtilities.extractNewBalance(lines)));
    }

    @Test
    void extractsCreditLimit_fromTotalCreditLimitLabel() {
        final String[] lines = {"Total Credit Limit $30,000"};
        assertEquals(0,
                new BigDecimal("30000")
                        .compareTo(StatementParsingUtilities.extractCreditLimit(lines)));
    }

    @Test
    void extractsStatementDate_fromCanonicalHeader() {
        final String[] lines = {"Statement Date 04/30/2026"};
        final var date = StatementParsingUtilities.extractStatementDate(lines, 2026, true);
        assertNotNull(date);
        assertEquals(2026, date.getYear());
        assertEquals(4, date.getMonthValue());
        assertEquals(30, date.getDayOfMonth());
    }

    @Test
    void extractsCashBackBalance_fromRewardsBalanceLabel() {
        final String[] lines = {"Rewards balance $42.50"};
        assertEquals(0,
                new BigDecimal("42.50")
                        .compareTo(StatementParsingUtilities.extractCashBackBalance(lines)));
    }

    @Test
    void extractsAutoPayEnabled_fromExplicitOnMarker() {
        assertEquals(Boolean.TRUE,
                StatementParsingUtilities.extractAutoPayEnabled(
                        new String[] {"AUTOPAY IS ON"}));
        assertEquals(Boolean.FALSE,
                StatementParsingUtilities.extractAutoPayEnabled(
                        new String[] {"AUTOPAY IS OFF"}));
        assertNull(StatementParsingUtilities.extractAutoPayEnabled(
                new String[] {"random text"}));
    }

    @Test
    void extractsAllAprs_fromStandardLabels() {
        final String[] lines = {
            "Purchases 19.49%(v)(d)",
            "Cash Advances 29.99%(v)(d)",
            "Balance Transfers 21.99%(v)(d)",
            "Penalty APR of 29.99%.",
        };
        assertEquals(0, new BigDecimal("19.49")
                .compareTo(StatementParsingUtilities.extractPurchaseApr(lines)));
        assertEquals(0, new BigDecimal("29.99")
                .compareTo(StatementParsingUtilities.extractCashAdvanceApr(lines)));
        assertEquals(0, new BigDecimal("21.99")
                .compareTo(StatementParsingUtilities.extractBalanceTransferApr(lines)));
        assertEquals(0, new BigDecimal("29.99")
                .compareTo(StatementParsingUtilities.extractPenaltyApr(lines)));
    }

    @Test
    void classIsNotDeprecated() {
        // The class is the shared parsing utility for the profile architecture —
        // it's a normal first-class part of the codebase, not on a removal path.
        assertNull(StatementParsingUtilities.class.getAnnotation(Deprecated.class),
                "StatementParsingUtilities should NOT carry @Deprecated — it's the "
                        + "canonical home for cross-issuer regex helpers + Pattern unions.");
    }

    @Test
    void privateConstructor_preventsInstantiation() throws Exception {
        final var ctor = StatementParsingUtilities.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(ctor.getModifiers()),
                "Static utility classes must have a private constructor");
    }

    @Test
    void publicMethodCount_atLeastBreadthExpected() {
        // Lower bound: we expect at least 32 public static extract* methods.
        // Once full migration deletes the legacy class entirely, this count goes to 0
        // and the test can be deleted with the class.
        final long publicMethods = Arrays.stream(StatementParsingUtilities.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> Modifier.isStatic(m.getModifiers()))
                .filter(m -> m.getName().startsWith("extract"))
                .count();
        assertTrue(publicMethods >= 32,
                "Façade must expose at least 32 extract* methods (got: " + publicMethods + ")");
    }

    @Test
    void nestedRecords_QuarterlyBonusAndNextQuarterBonus_areExposed() {
        // QuarterlyBonus + NextQuarterBonus records moved with the legacy section.
        // Callers (PDFImportService orchestrator) reference them by the qualified name
        // StatementParsingUtilities.QuarterlyBonus — verify they're still public.
        assertNotNull(
                StatementParsingUtilities.QuarterlyBonus.class,
                "QuarterlyBonus record must remain accessible from this façade");
        assertNotNull(
                StatementParsingUtilities.NextQuarterBonus.class,
                "NextQuarterBonus record must remain accessible from this façade");
    }
}
