package com.budgetbuddy.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Lightweight regression check that every transaction row carries the full complement of fields the
 * product relies on:
 *
 * <p>date • location • userName • merchantName • description • amount • flowDirection •
 * currencyCode • cardLastFour
 *
 * <p>Catches the case where a refactor silently drops a field from {@code ParsedTransaction} — the
 * test fails at compile or fails loudly at runtime with a clear message pointing at the missing
 * field. Deliberately reflection-based rather than constructor-coupled so it doesn't need to be
 * updated when unrelated fields are added or renamed.
 */
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
class PerRowFieldCompletenessTest {

    private static final Set<String> REQUIRED_FIELDS =
            Set.of(
                    "date",
                    "location",
                    "userName",
                    "merchantName",
                    "description",
                    "amount",
                    "flowDirection",
                    "currencyCode",
                    "cardLastFour");

    @Test
    void parsedTransactionDeclaresAllRequiredFields() {
        final Set<String> declared = new HashSet<>();
        for (final Field f : ParsedTransaction.class.getDeclaredFields()) {
            declared.add(f.getName());
        }
        for (final String required : REQUIRED_FIELDS) {
            assertTrue(
                    declared.contains(required),
                    "ParsedTransaction is missing required field: "
                            + required
                            + ". See PerRowFieldCompletenessTest for the canonical list.");
        }
    }

    @Test
    void settersAndGettersForRequiredFieldsRoundTrip() throws Exception {
        final ParsedTransaction tx = new ParsedTransaction();
        tx.setDate(java.time.LocalDate.of(2024, 3, 15));
        tx.setAmount(new java.math.BigDecimal("-42.18"));
        tx.setDescription("AMAZON MARKETPLACE");
        tx.setMerchantName("AMAZON MARKETPLACE");
        tx.setLocation("Seattle, WA");
        tx.setUserName("John Smith");
        tx.setCurrencyCode("USD");
        tx.setCardLastFour("1234");
        tx.setFlowDirection(FlowDirection.DEBIT);

        assertEquals(java.time.LocalDate.of(2024, 3, 15), tx.getDate());
        assertEquals(new java.math.BigDecimal("-42.18"), tx.getAmount());
        assertEquals("AMAZON MARKETPLACE", tx.getDescription());
        assertEquals("AMAZON MARKETPLACE", tx.getMerchantName());
        assertEquals("Seattle, WA", tx.getLocation());
        assertEquals("John Smith", tx.getUserName());
        assertEquals("USD", tx.getCurrencyCode());
        assertEquals("1234", tx.getCardLastFour());
        assertEquals(FlowDirection.DEBIT, tx.getFlowDirection());
    }

    @Test
    void flowDirectionInferenceMatchesSign() {
        assertEquals(
                FlowDirection.DEBIT,
                FlowDirection.fromSignedAmount(new java.math.BigDecimal("-10")));
        assertEquals(
                FlowDirection.CREDIT,
                FlowDirection.fromSignedAmount(new java.math.BigDecimal("10")));
        assertEquals(
                FlowDirection.CREDIT, FlowDirection.fromSignedAmount(java.math.BigDecimal.ZERO));
        assertEquals(FlowDirection.DEBIT, FlowDirection.fromSignedAmount(null));
    }
}
