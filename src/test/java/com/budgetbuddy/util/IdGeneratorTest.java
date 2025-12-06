package com.budgetbuddy.util;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for IdGenerator utility class
 */
class IdGeneratorTest {

    @Test
    void testGenerateAccountId_WithValidInputs_ReturnsDeterministicUUID() {
        // Given
        String institutionName = "Chase Bank";
        String plaidAccountId = "acc_123456";

        // When
        String id1 = IdGenerator.generateAccountId(institutionName, plaidAccountId);
        String id2 = IdGenerator.generateAccountId(institutionName, plaidAccountId);

        // Then
        assertNotNull(id1, "Should generate an ID");
        assertTrue(IdGenerator.isValidUUID(id1), "Should be a valid UUID");
        assertEquals(id1, id2, "Should generate the same ID for same inputs");
    }

    @Test
    void testGenerateAccountId_WithWhitespace_TrimsAndNormalizes() {
        // Given
        String institutionName = "  Chase Bank  ";
        String plaidAccountId = "  acc_123456  ";

        // When
        String id1 = IdGenerator.generateAccountId(institutionName, plaidAccountId);
        String id2 = IdGenerator.generateAccountId("Chase Bank", "acc_123456");

        // Then
        assertEquals(id1, id2, "Should handle whitespace and normalize");
    }

    @Test
    void testGenerateAccountId_WithCaseVariations_ReturnsSameId() {
        // Given
        String institutionName1 = "Chase Bank";
        String institutionName2 = "CHASE BANK";
        String plaidAccountId = "acc_123456";

        // When
        String id1 = IdGenerator.generateAccountId(institutionName1, plaidAccountId);
        String id2 = IdGenerator.generateAccountId(institutionName2, plaidAccountId);

        // Then
        assertEquals(id1, id2, "Should be case-insensitive");
    }

    @Test
    void testGenerateAccountId_WithNullInstitutionName_ThrowsException() {
        // Given
        String institutionName = null;
        String plaidAccountId = "acc_123456";

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateAccountId(institutionName, plaidAccountId);
        }, "Should throw exception for null institution name");
    }

    @Test
    void testGenerateAccountId_WithEmptyInstitutionName_ThrowsException() {
        // Given
        String institutionName = "";
        String plaidAccountId = "acc_123456";

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateAccountId(institutionName, plaidAccountId);
        }, "Should throw exception for empty institution name");
    }

    @Test
    void testGenerateAccountId_WithNullPlaidAccountId_ThrowsException() {
        // Given
        String institutionName = "Chase Bank";
        String plaidAccountId = null;

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateAccountId(institutionName, plaidAccountId);
        }, "Should throw exception for null Plaid account ID");
    }

    @Test
    void testGenerateTransactionId_WithValidInputs_ReturnsDeterministicUUID() {
        // Given
        String institutionName = "Chase Bank";
        String accountId = "acc_123456";
        String plaidTransactionId = "txn_789012";

        // When
        String id1 = IdGenerator.generateTransactionId(institutionName, accountId, plaidTransactionId);
        String id2 = IdGenerator.generateTransactionId(institutionName, accountId, plaidTransactionId);

        // Then
        assertNotNull(id1, "Should generate an ID");
        assertTrue(IdGenerator.isValidUUID(id1), "Should be a valid UUID");
        assertEquals(id1, id2, "Should generate the same ID for same inputs");
    }

    @Test
    void testGenerateTransactionId_WithNullInputs_ThrowsException() {
        // Given
        String institutionName = "Chase Bank";
        String accountId = "acc_123456";
        String plaidTransactionId = "txn_789012";

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateTransactionId(null, accountId, plaidTransactionId);
        }, "Should throw exception for null institution name");

        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateTransactionId(institutionName, null, plaidTransactionId);
        }, "Should throw exception for null account ID");

        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateTransactionId(institutionName, accountId, null);
        }, "Should throw exception for null Plaid transaction ID");
    }

    @Test
    void testGenerateBudgetId_WithValidInputs_ReturnsDeterministicUUID() {
        // Given
        String userId = "user_123";
        String category = "FOOD_AND_DRINK";

        // When
        String id1 = IdGenerator.generateBudgetId(userId, category);
        String id2 = IdGenerator.generateBudgetId(userId, category);

        // Then
        assertNotNull(id1, "Should generate an ID");
        assertTrue(IdGenerator.isValidUUID(id1), "Should be a valid UUID");
        assertEquals(id1, id2, "Should generate the same ID for same inputs");
    }

    @Test
    void testGenerateBudgetId_WithNullInputs_ThrowsException() {
        // Given
        String userId = "user_123";
        String category = "FOOD_AND_DRINK";

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateBudgetId(null, category);
        }, "Should throw exception for null user ID");

        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateBudgetId(userId, null);
        }, "Should throw exception for null category");
    }

    @Test
    void testGenerateGoalId_WithValidInputs_ReturnsDeterministicUUID() {
        // Given
        String userId = "user_123";
        String goalName = "Vacation Fund";

        // When
        String id1 = IdGenerator.generateGoalId(userId, goalName);
        String id2 = IdGenerator.generateGoalId(userId, goalName);

        // Then
        assertNotNull(id1, "Should generate an ID");
        assertTrue(IdGenerator.isValidUUID(id1), "Should be a valid UUID");
        assertEquals(id1, id2, "Should generate the same ID for same inputs");
    }

    @Test
    void testGenerateGoalId_WithNullInputs_ThrowsException() {
        // Given
        String userId = "user_123";
        String goalName = "Vacation Fund";

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateGoalId(null, goalName);
        }, "Should throw exception for null user ID");

        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateGoalId(userId, null);
        }, "Should throw exception for null goal name");
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
        assertNotNull(id1, "Should generate an ID");
        assertTrue(IdGenerator.isValidUUID(id1), "Should be a valid UUID");
        assertEquals(id1, id2, "Should generate the same ID for same inputs");
        assertTrue(id1.equals(id1.toLowerCase()), "Should be lowercase");
    }

    @Test
    void testGenerateDeterministicUUID_WithNullNamespace_ThrowsException() {
        // Given
        UUID namespace = null;
        String name = "test-name";

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateDeterministicUUID(namespace, name);
        }, "Should throw exception for null namespace");
    }

    @Test
    void testGenerateDeterministicUUID_WithNullName_ThrowsException() {
        // Given
        UUID namespace = UUID.randomUUID();
        String name = null;

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateDeterministicUUID(namespace, name);
        }, "Should throw exception for null name");
    }

    @Test
    void testIsValidUUID_WithValidUUID_ReturnsTrue() {
        // Given
        String validUUID = UUID.randomUUID().toString();

        // When
        boolean isValid = IdGenerator.isValidUUID(validUUID);

        // Then
        assertTrue(isValid, "Should return true for valid UUID");
    }

    @Test
    void testIsValidUUID_WithInvalidUUID_ReturnsFalse() {
        // Given
        String invalidUUID = "not-a-uuid";

        // When
        boolean isValid = IdGenerator.isValidUUID(invalidUUID);

        // Then
        assertFalse(isValid, "Should return false for invalid UUID");
    }

    @Test
    void testIsValidUUID_WithNull_ReturnsFalse() {
        // Given
        String uuid = null;

        // When
        boolean isValid = IdGenerator.isValidUUID(uuid);

        // Then
        assertFalse(isValid, "Should return false for null");
    }

    @Test
    void testIsValidUUID_WithEmptyString_ReturnsFalse() {
        // Given
        String uuid = "";

        // When
        boolean isValid = IdGenerator.isValidUUID(uuid);

        // Then
        assertFalse(isValid, "Should return false for empty string");
    }

    @Test
    void testIsValidUUID_WithWhitespace_ReturnsFalse() {
        // Given
        String uuid = "   ";

        // When
        boolean isValid = IdGenerator.isValidUUID(uuid);

        // Then
        assertFalse(isValid, "Should return false for whitespace");
    }

    @Test
    void testGenerateManualEntityUUID_ReturnsValidUUID() {
        // When
        String uuid = IdGenerator.generateManualEntityUUID();

        // Then
        assertNotNull(uuid, "Should generate a UUID");
        assertTrue(IdGenerator.isValidUUID(uuid), "Should be a valid UUID");
        assertTrue(uuid.equals(uuid.toLowerCase()), "Should be lowercase");
    }

    @Test
    void testGenerateManualEntityUUID_ReturnsDifferentUUIDs() {
        // When
        String uuid1 = IdGenerator.generateManualEntityUUID();
        String uuid2 = IdGenerator.generateManualEntityUUID();

        // Then
        assertNotEquals(uuid1, uuid2, "Should generate different UUIDs");
    }

    @Test
    void testNormalizeUUID_WithValidUUID_ReturnsLowercase() {
        // Given
        String uuid = "550E8400-E29B-41D4-A716-446655440000";

        // When
        String normalized = IdGenerator.normalizeUUID(uuid);

        // Then
        assertEquals("550e8400-e29b-41d4-a716-446655440000", normalized, "Should convert to lowercase");
    }

    @Test
    void testNormalizeUUID_WithWhitespace_TrimsAndLowercases() {
        // Given
        String uuid = "  550E8400-E29B-41D4-A716-446655440000  ";

        // When
        String normalized = IdGenerator.normalizeUUID(uuid);

        // Then
        assertEquals("550e8400-e29b-41d4-a716-446655440000", normalized, "Should trim and convert to lowercase");
    }

    @Test
    void testNormalizeUUID_WithNull_ReturnsNull() {
        // Given
        String uuid = null;

        // When
        String normalized = IdGenerator.normalizeUUID(uuid);

        // Then
        assertNull(normalized, "Should return null for null input");
    }

    @Test
    void testNormalizeUUID_WithEmptyString_ReturnsEmptyString() {
        // Given
        String uuid = "";

        // When
        String normalized = IdGenerator.normalizeUUID(uuid);

        // Then
        assertEquals("", normalized, "Should return empty string for empty input");
    }

    @Test
    void testEqualsIgnoreCase_WithSameUUID_ReturnsTrue() {
        // Given
        String uuid1 = "550E8400-E29B-41D4-A716-446655440000";
        String uuid2 = "550e8400-e29b-41d4-a716-446655440000";

        // When
        boolean equals = IdGenerator.equalsIgnoreCase(uuid1, uuid2);

        // Then
        assertTrue(equals, "Should return true for same UUID with different case");
    }

    @Test
    void testEqualsIgnoreCase_WithDifferentUUIDs_ReturnsFalse() {
        // Given
        String uuid1 = UUID.randomUUID().toString();
        String uuid2 = UUID.randomUUID().toString();

        // When
        boolean equals = IdGenerator.equalsIgnoreCase(uuid1, uuid2);

        // Then
        assertFalse(equals, "Should return false for different UUIDs");
    }

    @Test
    void testEqualsIgnoreCase_WithBothNull_ReturnsTrue() {
        // Given
        String uuid1 = null;
        String uuid2 = null;

        // When
        boolean equals = IdGenerator.equalsIgnoreCase(uuid1, uuid2);

        // Then
        assertTrue(equals, "Should return true when both are null");
    }

    @Test
    void testEqualsIgnoreCase_WithOneNull_ReturnsFalse() {
        // Given
        String uuid1 = UUID.randomUUID().toString();
        String uuid2 = null;

        // When
        boolean equals = IdGenerator.equalsIgnoreCase(uuid1, uuid2);

        // Then
        assertFalse(equals, "Should return false when one is null");
    }

    @Test
    void testEqualsIgnoreCase_WithWhitespace_HandlesCorrectly() {
        // Given
        String uuid1 = "  550E8400-E29B-41D4-A716-446655440000  ";
        String uuid2 = "550e8400-e29b-41d4-a716-446655440000";

        // When
        boolean equals = IdGenerator.equalsIgnoreCase(uuid1, uuid2);

        // Then
        assertTrue(equals, "Should handle whitespace and case differences");
    }
}
