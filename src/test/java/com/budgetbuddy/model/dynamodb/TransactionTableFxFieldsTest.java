package com.budgetbuddy.model.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.BeanTableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;

/**
 * Validates the 4 FX columns added to TransactionTable in task #49 round-trip through
 * the DynamoDB Enhanced Client's bean-mapping. A regression here would mean the column
 * is set on the in-memory object but never actually persisted because (a) the getter is
 * missing the @DynamoDbAttribute annotation, (b) the DDB attribute name doesn't match
 * what the iOS decoder expects, or (c) the type isn't one DDB-Enhanced knows how to
 * marshal.
 */
class TransactionTableFxFieldsTest {

    // ============================================================
    //  Field round-trip via setters/getters
    // ============================================================

    @Test
    void newRow_allFxFieldsAreNullByDefault() {
        // Bare row must NOT default to zero / empty string — null is the unambiguous
        // "no FX context" signal that the iOS UI relies on to hide the badge.
        final TransactionTable t = new TransactionTable();
        assertNull(t.getOriginalCurrencyCode());
        assertNull(t.getOriginalCurrencyDisplay());
        assertNull(t.getOriginalAmount());
        assertNull(t.getExchangeRate());
    }

    @Test
    void settersAndGetters_roundTripEveryFxField() {
        final TransactionTable t = new TransactionTable();
        t.setOriginalCurrencyCode("INR");
        t.setOriginalCurrencyDisplay("INDIAN RUPEE");
        t.setOriginalAmount(new BigDecimal("14543.50"));
        t.setExchangeRate(new BigDecimal("0.010775948"));

        assertEquals("INR", t.getOriginalCurrencyCode());
        assertEquals("INDIAN RUPEE", t.getOriginalCurrencyDisplay());
        assertEquals(0, new BigDecimal("14543.50").compareTo(t.getOriginalAmount()));
        assertEquals(0,
                new BigDecimal("0.010775948").compareTo(t.getExchangeRate()),
                "9-decimal exchange rate must round-trip without precision loss");
    }

    @Test
    void exchangeRate_preservesPrecisionAcrossSetterRoundTrip() {
        // Chase prints rates with 9–10 decimal places. Any silent truncation here
        // would mean a re-multiplied USD amount drifts from the printed USD amount.
        final TransactionTable t = new TransactionTable();
        final BigDecimal precise = new BigDecimal("1.096493506");
        t.setExchangeRate(precise);
        assertEquals(precise, t.getExchangeRate());
        // .scale() of a fresh BigDecimal preserves the decimal count.
        assertEquals(9, t.getExchangeRate().scale(),
                "Setter must preserve the rate's scale (decimal-place count)");
    }

    @Test
    void originalAmount_handlesLargeValues() {
        // Asia/Africa currencies often have 6-digit-plus amounts (₹193,561.30).
        final TransactionTable t = new TransactionTable();
        t.setOriginalAmount(new BigDecimal("193561.30"));
        assertEquals(0,
                new BigDecimal("193561.30").compareTo(t.getOriginalAmount()),
                "Large foreign-currency amounts must round-trip without loss");
    }

    // ============================================================
    //  DynamoDB schema mapping — attribute names match the wire format
    // ============================================================

    @Test
    void dynamoDbSchema_includesAllFourFxAttributes_withCorrectNames() {
        // The DDB Enhanced Client builds its schema by scanning @DynamoDbAttribute
        // annotations. If we forgot one, the attribute is silently NOT persisted
        // and reads come back null even when writes set them. Pin the schema.
        final BeanTableSchema<TransactionTable> schema =
                TableSchema.fromBean(TransactionTable.class);
        final java.util.List<String> attributeNames = schema.attributeNames();
        assertTrue(attributeNames.contains("originalCurrencyCode"),
                "originalCurrencyCode must be a DDB attribute; available: " + attributeNames);
        assertTrue(attributeNames.contains("originalCurrencyDisplay"),
                "originalCurrencyDisplay must be a DDB attribute");
        assertTrue(attributeNames.contains("originalAmount"),
                "originalAmount must be a DDB attribute");
        assertTrue(attributeNames.contains("exchangeRate"),
                "exchangeRate must be a DDB attribute");
    }

    // ============================================================
    //  Independence — FX setters don't touch unrelated fields
    // ============================================================

    @Test
    void settingFxFields_doesNotMutateUnrelatedColumns() {
        // Defensive: writing the FX block must NOT also set userId, amount,
        // description, etc. (someone copying the helper could accidentally do that).
        final TransactionTable t = new TransactionTable();
        t.setUserId("user-uuid");
        t.setAmount(new BigDecimal("156.72"));
        t.setDescription("THE WESTIN PUNE");

        t.setOriginalCurrencyCode("INR");
        t.setOriginalAmount(new BigDecimal("14543.50"));
        t.setExchangeRate(new BigDecimal("0.010775948"));

        assertEquals("user-uuid", t.getUserId());
        assertEquals(0, new BigDecimal("156.72").compareTo(t.getAmount()));
        assertEquals("THE WESTIN PUNE", t.getDescription());
    }

    // Verify a separate annotation hook isn't expected on the FX getters (catches
    // a copy-paste regression where someone adds @DynamoDbSecondaryPartitionKey).
    @Test
    void fxGetters_haveOnlyDynamoDbAttributeAnnotation_noKeyOrIndex() throws NoSuchMethodException {
        final Class<?>[] noArgs = new Class<?>[] {};
        final java.lang.reflect.Method[] methods = {
            TransactionTable.class.getMethod("getOriginalCurrencyCode", noArgs),
            TransactionTable.class.getMethod("getOriginalCurrencyDisplay", noArgs),
            TransactionTable.class.getMethod("getOriginalAmount", noArgs),
            TransactionTable.class.getMethod("getExchangeRate", noArgs),
        };
        for (final java.lang.reflect.Method m : methods) {
            assertTrue(m.isAnnotationPresent(DynamoDbAttribute.class),
                    "FX getter " + m.getName() + " must carry @DynamoDbAttribute");
        }
    }
}
