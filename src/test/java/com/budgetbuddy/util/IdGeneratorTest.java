package com.budgetbuddy.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for IdGenerator
 */
class IdGeneratorTest {

    @Test
    void testGenerateAccountId_WithValidInputs_ReturnsDeterministicUUID() {
        // Given
        String institutionName = "Chase Bank";
        String plaidAccountId = "acc-123";

        // When
        String id1 = IdGenerator.generateAccountId(institutionName, plaidAccountId);
        String id2 = IdGenerator.generateAccountId(institutionName, plaidAccountId);

        // Then
        assertNotNull(id1);
        assertNotNull(id2);
        assertEquals(id1, id2, "Should generate same UUID for same inputs");
        assertTrue(IdGenerator.isValidUUID(id1));
    }

    @Test
    void testGenerateAccountId_WithNullInstitutionName_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> 
                IdGenerator.generateAccountId(null, "acc-123"));
    }

    @Test
    void testGenerateAccountId_WithEmptyInstitutionName_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> 
                IdGenerator.generateAccountId("", "acc-123"));
        assertThrows(IllegalArgumentException.class, () -> 
                IdGenerator.generateAccountId("   ", "acc-123"));
    }

    @Test
    void testGenerateAccountId_WithNullPlaidAccountId_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> 
                IdGenerator.generateAccountId("Chase", null));
    }

    @Test
    void testGenerateAccountId_WithWhitespace_TrimsInput() {
        // Given
        String id1 = IdGenerator.generateAccountId("  Chase  ", "  acc-123  ");
        String id2 = IdGenerator.generateAccountId("Chase", "acc-123");

        // Then
        assertEquals(id1, id2, "Should handle whitespace by trimming");
    }

    @Test
    void testGenerateTransactionId_WithValidInputs_ReturnsDeterministicUUID() {
        // Given
        String institutionName = "Bank of America";
        String accountId = "acc-456";
        String plaidTransactionId = "txn-789";

        // When
        String id1 = IdGenerator.generateTransactionId(institutionName, accountId, plaidTransactionId);
        String id2 = IdGenerator.generateTransactionId(institutionName, accountId, plaidTransactionId);

        // Then
        assertNotNull(id1);
        assertEquals(id1, id2, "Should generate same UUID for same inputs");
        assertTrue(IdGenerator.isValidUUID(id1));
    }

    @Test
    void testGenerateTransactionId_WithNullInputs_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> 
                IdGenerator.generateTransactionId(null, "acc", "txn"));
        assertThrows(IllegalArgumentException.class, () -> 
                IdGenerator.generateTransactionId("Bank", null, "txn"));
        assertThrows(IllegalArgumentException.class, () -> 
                IdGenerator.generateTransactionId("Bank", "acc", null));
    }

    @Test
    void testGenerateBudgetId_WithValidInputs_ReturnsDeterministicUUID() {
        // Given
        String userId = "user-123";
        String category = "FOOD_AND_DRINK";

        // When
        String id1 = IdGenerator.generateBudgetId(userId, category);
        String id2 = IdGenerator.generateBudgetId(userId, category);

        // Then
        assertNotNull(id1);
        assertEquals(id1, id2);
        assertTrue(IdGenerator.isValidUUID(id1));
    }

    @Test
    void testGenerateBudgetId_WithNullInputs_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> 
                IdGenerator.generateBudgetId(null, "category"));
        assertThrows(IllegalArgumentException.class, () -> 
                IdGenerator.generateBudgetId("user", null));
    }

    @Test
    void testGenerateGoalId_WithValidInputs_ReturnsDeterministicUUID() {
        // Given
        String userId = "user-123";
        String goalName = "Save for Vacation";

        // When
        String id1 = IdGenerator.generateGoalId(userId, goalName);
        String id2 = IdGenerator.generateGoalId(userId, goalName);

        // Then
        assertNotNull(id1);
        assertEquals(id1, id2);
        assertTrue(IdGenerator.isValidUUID(id1));
    }

    @Test
    void testGenerateGoalId_WithNullInputs_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> 
                IdGenerator.generateGoalId(null, "goal"));
        assertThrows(IllegalArgumentException.class, () -> 
                IdGenerator.generateGoalId("user", null));
    }

    @Test
    void testGenerateSubscriptionId_WithValidInputs_ReturnsDeterministicUUID() {
        // Given
        String userId = "user-123";
        String merchantName = "Netflix";
        BigDecimal amount = new BigDecimal("9.99");

        // When
        String id1 = IdGenerator.generateSubscriptionId(userId, merchantName, amount);
        String id2 = IdGenerator.generateSubscriptionId(userId, merchantName, amount);

        // Then
        assertNotNull(id1);
        assertEquals(id1, id2);
        assertTrue(IdGenerator.isValidUUID(id1));
    }

    @Test
    void testGenerateSubscriptionId_WithNullInputs_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> 
                IdGenerator.generateSubscriptionId(null, "merchant", new BigDecimal("10.00")));
        assertThrows(IllegalArgumentException.class, () -> 
                IdGenerator.generateSubscriptionId("user", null, new BigDecimal("10.00")));
        assertThrows(IllegalArgumentException.class, () -> 
                IdGenerator.generateSubscriptionId("user", "merchant", null));
    }

    @Test
    void testGenerateSubscriptionId_WithDifferentAmountPrecision_HandlesCorrectly() {
        // Given
        String userId = "user-123";
        String merchantName = "Netflix";
        BigDecimal amount1 = new BigDecimal("9.99");
        BigDecimal amount2 = new BigDecimal("10.00");

        // When
        String id1 = IdGenerator.generateSubscriptionId(userId, merchantName, amount1);
        String id2 = IdGenerator.generateSubscriptionId(userId, merchantName, amount2);

        // Then
        assertNotEquals(id1, id2, "Different amounts should generate different IDs");
    }

    @Test
    void testGenerateSubscriptionId_WithSameAmountAfterRounding_GeneratesSameId() {
        // Given - These will round to the same value (10.00)
        String userId = "user-123";
        String merchantName = "Netflix";
        BigDecimal amount1 = new BigDecimal("9.999");
        BigDecimal amount2 = new BigDecimal("10.001");

        // When
        String id1 = IdGenerator.generateSubscriptionId(userId, merchantName, amount1);
        String id2 = IdGenerator.generateSubscriptionId(userId, merchantName, amount2);

        // Then - Should be the same because both round to 10.00
        assertEquals(id1, id2, "Amounts that round to the same value should generate same ID");
    }

    @Test
    void testGenerateDeterministicUUID_WithValidInputs_ReturnsDeterministicUUID() {
        // Given
        UUID namespace = UUID.randomUUID();
        String name = "test-name";

        // When
        String id1 = IdGenerator.generateDeterministicUUID(namespace, name);
        String id2 = IdGenerator.generateDeterministicUUID(namespace, name);

        // Then
        assertNotNull(id1);
        assertEquals(id1, id2);
        assertTrue(IdGenerator.isValidUUID(id1));
        assertTrue(id1.equals(id1.toLowerCase()), "Should be lowercase");
    }

    @Test
    void testGenerateDeterministicUUID_WithNullNamespace_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> 
                IdGenerator.generateDeterministicUUID(null, "name"));
    }

    @Test
    void testGenerateDeterministicUUID_WithNullName_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> 
                IdGenerator.generateDeterministicUUID(UUID.randomUUID(), null));
    }

    @Test
    void testGenerateDeterministicUUID_WithWhitespace_TrimsInput() {
        // Given
        UUID namespace = UUID.randomUUID();
        String id1 = IdGenerator.generateDeterministicUUID(namespace, "  test  ");
        String id2 = IdGenerator.generateDeterministicUUID(namespace, "test");

        // Then
        assertEquals(id1, id2, "Should trim whitespace");
    }

    @Test
    void testIsValidUUID_WithValidUUID_ReturnsTrue() {
        // Given
        String validUUID = UUID.randomUUID().toString();

        // When
        boolean isValid = IdGenerator.isValidUUID(validUUID);

        // Then
        assertTrue(isValid);
    }

    @Test
    void testIsValidUUID_WithInvalidUUID_ReturnsFalse() {
        // When/Then
        assertFalse(IdGenerator.isValidUUID("not-a-uuid"));
        assertFalse(IdGenerator.isValidUUID("123"));
        assertFalse(IdGenerator.isValidUUID(""));
    }

    @Test
    void testIsValidUUID_WithNull_ReturnsFalse() {
        // When
        boolean isValid = IdGenerator.isValidUUID(null);

        // Then
        assertFalse(isValid);
    }

    @Test
    void testGenerateManualEntityUUID_ReturnsValidUUID() {
        // When
        String uuid = IdGenerator.generateManualEntityUUID();

        // Then
        assertNotNull(uuid);
        assertTrue(IdGenerator.isValidUUID(uuid));
        assertTrue(uuid.equals(uuid.toLowerCase()), "Should be lowercase");
    }

    @Test
    void testNormalizeUUID_WithValidUUID_ReturnsLowercase() {
        // Given
        String upperCaseUUID = UUID.randomUUID().toString().toUpperCase();

        // When
        String normalized = IdGenerator.normalizeUUID(upperCaseUUID);

        // Then
        assertNotNull(normalized);
        assertEquals(normalized.toLowerCase(), normalized);
    }

    @Test
    void testNormalizeUUID_WithNull_ReturnsNull() {
        // When
        String normalized = IdGenerator.normalizeUUID(null);

        // Then
        assertNull(normalized);
    }

    @Test
    void testNormalizeUUID_WithWhitespace_TrimsInput() {
        // Given
        String uuid = "  " + UUID.randomUUID().toString() + "  ";

        // When
        String normalized = IdGenerator.normalizeUUID(uuid);

        // Then
        assertNotNull(normalized);
        assertFalse(normalized.startsWith(" "));
        assertFalse(normalized.endsWith(" "));
    }

    @Test
    void testEqualsIgnoreCase_WithSameUUIDs_ReturnsTrue() {
        // Given
        String uuid1 = UUID.randomUUID().toString();
        String uuid2 = uuid1;

        // When
        boolean equals = IdGenerator.equalsIgnoreCase(uuid1, uuid2);

        // Then
        assertTrue(equals);
    }

    @Test
    void testEqualsIgnoreCase_WithDifferentCase_ReturnsTrue() {
        // Given
        String uuid1 = UUID.randomUUID().toString().toLowerCase();
        String uuid2 = uuid1.toUpperCase();

        // When
        boolean equals = IdGenerator.equalsIgnoreCase(uuid1, uuid2);

        // Then
        assertTrue(equals);
    }

    @Test
    void testEqualsIgnoreCase_WithDifferentUUIDs_ReturnsFalse() {
        // Given
        String uuid1 = UUID.randomUUID().toString();
        String uuid2 = UUID.randomUUID().toString();

        // When
        boolean equals = IdGenerator.equalsIgnoreCase(uuid1, uuid2);

        // Then
        assertFalse(equals);
    }

    @Test
    void testEqualsIgnoreCase_WithNulls_ReturnsTrue() {
        // When
        boolean equals = IdGenerator.equalsIgnoreCase(null, null);

        // Then
        assertTrue(equals);
    }

    @Test
    void testEqualsIgnoreCase_WithOneNull_ReturnsFalse() {
        // When/Then
        assertFalse(IdGenerator.equalsIgnoreCase(null, UUID.randomUUID().toString()));
        assertFalse(IdGenerator.equalsIgnoreCase(UUID.randomUUID().toString(), null));
    }
}
