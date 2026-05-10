package com.budgetbuddy.util;


import java.util.Locale;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit Tests for IdGenerator */
class IdGeneratorTest {

    @Test
    void testGenerateAccountIdWithValidInputsReturnsDeterministicUUID() {
        // Given
        final String institutionName = "Chase Bank";
        final String plaidAccountId = "acc-123";

        // When
        final String id1 = IdGenerator.generateAccountId(institutionName, plaidAccountId);
        final String id2 = IdGenerator.generateAccountId(institutionName, plaidAccountId);

        // Then
        assertNotNull(id1);
        assertNotNull(id2);
        assertEquals(id1, id2, "Should generate same UUID for same inputs");
        assertTrue(IdGenerator.isValidUUID(id1));
    }

    @Test
    void testGenerateAccountIdWithNullInstitutionNameThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> IdGenerator.generateAccountId(null, "acc-123"));
    }

    @Test
    void testGenerateAccountIdWithEmptyInstitutionNameThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class, () -> IdGenerator.generateAccountId("", "acc-123"));
        assertThrows(
                IllegalArgumentException.class,
                () -> IdGenerator.generateAccountId("   ", "acc-123"));
    }

    @Test
    void testGenerateAccountIdWithNullPlaidAccountIdThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class, () -> IdGenerator.generateAccountId("Chase", null));
    }

    @Test
    void testGenerateAccountIdWithWhitespaceTrimsInput() {
        // Given
        final String id1 = IdGenerator.generateAccountId("  Chase  ", "  acc-123  ");
        final String id2 = IdGenerator.generateAccountId("Chase", "acc-123");

        // Then
        assertEquals(id1, id2, "Should handle whitespace by trimming");
    }

    @Test
    void testGenerateTransactionIdWithValidInputsReturnsDeterministicUUID() {
        // Given
        final String institutionName = "Bank of America";
        final String accountId = "acc-456";
        final String plaidTransactionId = "txn-789";

        // When
        final String id1 =
                IdGenerator.generateTransactionId(institutionName, accountId, plaidTransactionId);
        final String id2 =
                IdGenerator.generateTransactionId(institutionName, accountId, plaidTransactionId);

        // Then
        assertNotNull(id1);
        assertEquals(id1, id2, "Should generate same UUID for same inputs");
        assertTrue(IdGenerator.isValidUUID(id1));
    }

    @Test
    void testGenerateTransactionIdWithNullInputsThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> IdGenerator.generateTransactionId(null, "acc", "txn"));
        assertThrows(
                IllegalArgumentException.class,
                () -> IdGenerator.generateTransactionId("Bank", null, "txn"));
        assertThrows(
                IllegalArgumentException.class,
                () -> IdGenerator.generateTransactionId("Bank", "acc", null));
    }

    @Test
    void testGenerateBudgetIdWithValidInputsReturnsDeterministicUUID() {
        // Given
        final String userId = "user-123";
        final String category = "FOOD_AND_DRINK";

        // When
        final String id1 = IdGenerator.generateBudgetId(userId, category);
        final String id2 = IdGenerator.generateBudgetId(userId, category);

        // Then
        assertNotNull(id1);
        assertEquals(id1, id2);
        assertTrue(IdGenerator.isValidUUID(id1));
    }

    @Test
    void testGenerateBudgetIdWithNullInputsThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> IdGenerator.generateBudgetId(null, "category"));
        assertThrows(
                IllegalArgumentException.class, () -> IdGenerator.generateBudgetId("user", null));
    }

    @Test
    void testGenerateGoalIdWithValidInputsReturnsDeterministicUUID() {
        // Given
        final String userId = "user-123";
        final String goalName = "Save for Vacation";

        // When
        final String id1 = IdGenerator.generateGoalId(userId, goalName);
        final String id2 = IdGenerator.generateGoalId(userId, goalName);

        // Then
        assertNotNull(id1);
        assertEquals(id1, id2);
        assertTrue(IdGenerator.isValidUUID(id1));
    }

    @Test
    void testGenerateGoalIdWithNullInputsThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class, () -> IdGenerator.generateGoalId(null, "goal"));
        assertThrows(
                IllegalArgumentException.class, () -> IdGenerator.generateGoalId("user", null));
    }

    @Test
    void testGenerateSubscriptionIdWithValidInputsReturnsDeterministicUUID() {
        // Given
        final String userId = "user-123";
        final String merchantName = "Netflix";
        final BigDecimal amount = new BigDecimal("9.99");

        // When
        final String id1 = IdGenerator.generateSubscriptionId(userId, merchantName, amount);
        final String id2 = IdGenerator.generateSubscriptionId(userId, merchantName, amount);

        // Then
        assertNotNull(id1);
        assertEquals(id1, id2);
        assertTrue(IdGenerator.isValidUUID(id1));
    }

    @Test
    void testGenerateSubscriptionIdWithNullInputsThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        IdGenerator.generateSubscriptionId(
                                null, "merchant", new BigDecimal("10.00")));
        assertThrows(
                IllegalArgumentException.class,
                () -> IdGenerator.generateSubscriptionId("user", null, new BigDecimal("10.00")));
        assertThrows(
                IllegalArgumentException.class,
                () -> IdGenerator.generateSubscriptionId("user", "merchant", null));
    }

    @Test
    void testGenerateSubscriptionIdWithDifferentAmountPrecisionHandlesCorrectly() {
        // Given
        final String userId = "user-123";
        final String merchantName = "Netflix";
        final BigDecimal amount1 = new BigDecimal("9.99");
        final BigDecimal amount2 = new BigDecimal("10.00");

        // When
        final String id1 = IdGenerator.generateSubscriptionId(userId, merchantName, amount1);
        final String id2 = IdGenerator.generateSubscriptionId(userId, merchantName, amount2);

        // Then
        assertNotEquals(id1, id2, "Different amounts should generate different IDs");
    }

    @Test
    void testGenerateSubscriptionIdWithSameAmountAfterRoundingGeneratesSameId() {
        // Given - These will round to the same value (10.00)
        final String userId = "user-123";
        final String merchantName = "Netflix";
        final BigDecimal amount1 = new BigDecimal("9.999");
        final BigDecimal amount2 = new BigDecimal("10.001");

        // When
        final String id1 = IdGenerator.generateSubscriptionId(userId, merchantName, amount1);
        final String id2 = IdGenerator.generateSubscriptionId(userId, merchantName, amount2);

        // Then - Should be the same because both round to 10.00
        assertEquals(id1, id2, "Amounts that round to the same value should generate same ID");
    }

    @Test
    void testGenerateDeterministicUUIDWithValidInputsReturnsDeterministicUUID() {
        // Given
        final UUID namespace = UUID.randomUUID();
        final String name = "test-name";

        // When
        final String id1 = IdGenerator.generateDeterministicUUID(namespace, name);
        final String id2 = IdGenerator.generateDeterministicUUID(namespace, name);

        // Then
        assertNotNull(id1);
        assertEquals(id1, id2);
        assertTrue(IdGenerator.isValidUUID(id1));
        assertTrue(id1.equals(id1.toLowerCase(Locale.ROOT)), "Should be lowercase");
    }

    @Test
    void testGenerateDeterministicUUIDWithNullNamespaceThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> IdGenerator.generateDeterministicUUID(null, "name"));
    }

    @Test
    void testGenerateDeterministicUUIDWithNullNameThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> IdGenerator.generateDeterministicUUID(UUID.randomUUID(), null));
    }

    @Test
    void testGenerateDeterministicUUIDWithWhitespaceTrimsInput() {
        // Given
        final UUID namespace = UUID.randomUUID();
        final String id1 = IdGenerator.generateDeterministicUUID(namespace, "  test  ");
        final String id2 = IdGenerator.generateDeterministicUUID(namespace, "test");

        // Then
        assertEquals(id1, id2, "Should trim whitespace");
    }

    @Test
    void testIsValidUUIDWithValidUUIDReturnsTrue() {
        // Given
        final String validUUID = UUID.randomUUID().toString();

        // When
        final boolean isValid = IdGenerator.isValidUUID(validUUID);

        // Then
        assertTrue(isValid);
    }

    @Test
    void testIsValidUUIDWithInvalidUUIDReturnsFalse() {
        // When/Then
        assertFalse(IdGenerator.isValidUUID("not-a-uuid"));
        assertFalse(IdGenerator.isValidUUID("123"));
        assertFalse(IdGenerator.isValidUUID(""));
    }

    @Test
    void testIsValidUUIDWithNullReturnsFalse() {
        // When
        final boolean isValid = IdGenerator.isValidUUID(null);

        // Then
        assertFalse(isValid);
    }

    @Test
    void testGenerateManualEntityUUIDReturnsValidUUID() {
        // When
        final String uuid = IdGenerator.generateManualEntityUUID();

        // Then
        assertNotNull(uuid);
        assertTrue(IdGenerator.isValidUUID(uuid));
        assertTrue(uuid.equals(uuid.toLowerCase(Locale.ROOT)), "Should be lowercase");
    }

    @Test
    void testNormalizeUUIDWithValidUUIDReturnsLowercase() {
        // Given
        final String upperCaseUUID = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);

        // When
        final String normalized = IdGenerator.normalizeUUID(upperCaseUUID);

        // Then
        assertNotNull(normalized);
        assertEquals(normalized.toLowerCase(Locale.ROOT), normalized);
    }

    @Test
    void testNormalizeUUIDWithNullReturnsNull() {
        // When
        final String normalized = IdGenerator.normalizeUUID(null);

        // Then
        assertNull(normalized);
    }

    @Test
    void testNormalizeUUIDWithWhitespaceTrimsInput() {
        // Given
        final String uuid = "  " + UUID.randomUUID().toString() + "  ";

        // When
        final String normalized = IdGenerator.normalizeUUID(uuid);

        // Then
        assertNotNull(normalized);
        assertFalse(normalized.startsWith(" "));
        assertFalse(normalized.endsWith(" "));
    }

    @Test
    void testEqualsIgnoreCaseWithSameUUIDsReturnsTrue() {
        // Given
        final String uuid1 = UUID.randomUUID().toString();
        final String uuid2 = uuid1;

        // When
        final boolean equals = IdGenerator.equalsIgnoreCase(uuid1, uuid2);

        // Then
        assertTrue(equals);
    }

    @Test
    void testEqualsIgnoreCaseWithDifferentCaseReturnsTrue() {
        // Given
        final String uuid1 = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        final String uuid2 = uuid1.toUpperCase(Locale.ROOT);

        // When
        final boolean equals = IdGenerator.equalsIgnoreCase(uuid1, uuid2);

        // Then
        assertTrue(equals);
    }

    @Test
    void testEqualsIgnoreCaseWithDifferentUUIDsReturnsFalse() {
        // Given
        final String uuid1 = UUID.randomUUID().toString();
        final String uuid2 = UUID.randomUUID().toString();

        // When
        final boolean equals = IdGenerator.equalsIgnoreCase(uuid1, uuid2);

        // Then
        assertFalse(equals);
    }

    @Test
    void testEqualsIgnoreCaseWithNullsReturnsTrue() {
        // When
        final boolean equals = IdGenerator.equalsIgnoreCase(null, null);

        // Then
        assertTrue(equals);
    }

    @Test
    void testEqualsIgnoreCaseWithOneNullReturnsFalse() {
        // When/Then
        assertFalse(IdGenerator.equalsIgnoreCase(null, UUID.randomUUID().toString()));
        assertFalse(IdGenerator.equalsIgnoreCase(UUID.randomUUID().toString(), null));
    }
}
