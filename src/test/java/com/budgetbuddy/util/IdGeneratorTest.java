package com.budgetbuddy.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for IdGenerator utility
 * Tests deterministic UUID generation and validation
 */
class IdGeneratorTest {

    @Test
    @DisplayName("Should generate deterministic account ID")
    void testGenerateAccountId_Deterministic() {
        // Given
        String institutionName = "Chase Bank";
        String plaidAccountId = "acc-123";

        // When
        String id1 = IdGenerator.generateAccountId(institutionName, plaidAccountId);
        String id2 = IdGenerator.generateAccountId(institutionName, plaidAccountId);

        // Then
        assertEquals(id1, id2);
        assertTrue(IdGenerator.isValidUUID(id1));
    }

    @Test
    @DisplayName("Should generate different IDs for different accounts")
    void testGenerateAccountId_DifferentAccounts() {
        // Given
        String institutionName = "Chase Bank";
        String plaidAccountId1 = "acc-123";
        String plaidAccountId2 = "acc-456";

        // When
        String id1 = IdGenerator.generateAccountId(institutionName, plaidAccountId1);
        String id2 = IdGenerator.generateAccountId(institutionName, plaidAccountId2);

        // Then
        assertNotEquals(id1, id2);
    }

    @Test
    @DisplayName("Should throw exception for null institution name")
    void testGenerateAccountId_NullInstitutionName() {
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateAccountId(null, "acc-123");
        });
    }

    @Test
    @DisplayName("Should throw exception for empty institution name")
    void testGenerateAccountId_EmptyInstitutionName() {
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateAccountId("", "acc-123");
        });
    }

    @Test
    @DisplayName("Should throw exception for null Plaid account ID")
    void testGenerateAccountId_NullPlaidAccountId() {
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateAccountId("Chase Bank", null);
        });
    }

    @Test
    @DisplayName("Should trim whitespace from inputs")
    void testGenerateAccountId_TrimsWhitespace() {
        // Given
        String id1 = IdGenerator.generateAccountId("Chase Bank", "acc-123");
        String id2 = IdGenerator.generateAccountId("  Chase Bank  ", "  acc-123  ");

        // Then - Should produce same ID
        assertEquals(id1, id2);
    }

    @Test
    @DisplayName("Should generate deterministic transaction ID")
    void testGenerateTransactionId_Deterministic() {
        // Given
        String institutionName = "Chase Bank";
        String accountId = "acc-123";
        String plaidTransactionId = "txn-456";

        // When
        String id1 = IdGenerator.generateTransactionId(institutionName, accountId, plaidTransactionId);
        String id2 = IdGenerator.generateTransactionId(institutionName, accountId, plaidTransactionId);

        // Then
        assertEquals(id1, id2);
        assertTrue(IdGenerator.isValidUUID(id1));
    }

    @Test
    @DisplayName("Should throw exception for null transaction parameters")
    void testGenerateTransactionId_NullParameters() {
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateTransactionId(null, "acc-123", "txn-456");
        });
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateTransactionId("Chase", null, "txn-456");
        });
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateTransactionId("Chase", "acc-123", null);
        });
    }

    @Test
    @DisplayName("Should generate deterministic budget ID")
    void testGenerateBudgetId_Deterministic() {
        // Given
        String userId = "user-123";
        String category = "groceries";

        // When
        String id1 = IdGenerator.generateBudgetId(userId, category);
        String id2 = IdGenerator.generateBudgetId(userId, category);

        // Then
        assertEquals(id1, id2);
        assertTrue(IdGenerator.isValidUUID(id1));
    }

    @Test
    @DisplayName("Should throw exception for null budget parameters")
    void testGenerateBudgetId_NullParameters() {
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateBudgetId(null, "groceries");
        });
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateBudgetId("user-123", null);
        });
    }

    @Test
    @DisplayName("Should generate deterministic goal ID")
    void testGenerateGoalId_Deterministic() {
        // Given
        String userId = "user-123";
        String goalName = "Save for vacation";

        // When
        String id1 = IdGenerator.generateGoalId(userId, goalName);
        String id2 = IdGenerator.generateGoalId(userId, goalName);

        // Then
        assertEquals(id1, id2);
        assertTrue(IdGenerator.isValidUUID(id1));
    }

    @Test
    @DisplayName("Should throw exception for null goal parameters")
    void testGenerateGoalId_NullParameters() {
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateGoalId(null, "Save for vacation");
        });
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateGoalId("user-123", null);
        });
    }

    @Test
    @DisplayName("Should generate deterministic subscription ID")
    void testGenerateSubscriptionId_Deterministic() {
        // Given
        String userId = "user-123";
        String merchantName = "Netflix";
        BigDecimal amount = new BigDecimal("15.99");

        // When
        String id1 = IdGenerator.generateSubscriptionId(userId, merchantName, amount);
        String id2 = IdGenerator.generateSubscriptionId(userId, merchantName, amount);

        // Then
        assertEquals(id1, id2);
        assertTrue(IdGenerator.isValidUUID(id1));
    }

    @Test
    @DisplayName("Should round subscription amount to 2 decimal places")
    void testGenerateSubscriptionId_RoundsAmount() {
        // Given
        String userId = "user-123";
        String merchantName = "Netflix";
        
        // Test 1: Amounts that round to different values should produce different IDs
        BigDecimal amount1 = new BigDecimal("15.994"); // Rounds to 15.99
        BigDecimal amount2 = new BigDecimal("15.995"); // Rounds to 16.00
        
        // When
        String id1 = IdGenerator.generateSubscriptionId(userId, merchantName, amount1);
        String id2 = IdGenerator.generateSubscriptionId(userId, merchantName, amount2);

        // Then - Should be different due to rounding (15.994 -> 15.99, 15.995 -> 16.00)
        assertNotEquals(id1, id2, "IDs should differ when amounts round to different values");
        
        // Test 2: Amounts that round to the same value should produce the same ID
        BigDecimal amount3 = new BigDecimal("15.999"); // Rounds to 16.00
        BigDecimal amount4 = new BigDecimal("16.00"); // Stays 16.00
        
        String id3 = IdGenerator.generateSubscriptionId(userId, merchantName, amount3);
        String id4 = IdGenerator.generateSubscriptionId(userId, merchantName, amount4);
        
        // Then - Should be the same after rounding (both become 16.00)
        assertEquals(id3, id4, "15.999 and 16.00 should produce same ID after rounding");
    }

    @Test
    @DisplayName("Should throw exception for null subscription parameters")
    void testGenerateSubscriptionId_NullParameters() {
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateSubscriptionId(null, "Netflix", new BigDecimal("15.99"));
        });
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateSubscriptionId("user-123", null, new BigDecimal("15.99"));
        });
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateSubscriptionId("user-123", "Netflix", null);
        });
    }

    @Test
    @DisplayName("Should validate UUID format")
    void testIsValidUUID() {
        // Valid UUIDs
        assertTrue(IdGenerator.isValidUUID("550e8400-e29b-41d4-a716-446655440000"));
        assertTrue(IdGenerator.isValidUUID("550E8400-E29B-41D4-A716-446655440000")); // Case insensitive
        assertTrue(IdGenerator.isValidUUID(UUID.randomUUID().toString()));

        // Invalid UUIDs
        assertFalse(IdGenerator.isValidUUID(null));
        assertFalse(IdGenerator.isValidUUID(""));
        assertFalse(IdGenerator.isValidUUID("not-a-uuid"));
        assertFalse(IdGenerator.isValidUUID("550e8400-e29b-41d4-a716")); // Too short
    }

    @Test
    @DisplayName("Should generate random UUID for manual entities")
    void testGenerateManualEntityUUID() {
        // When
        String id1 = IdGenerator.generateManualEntityUUID();
        String id2 = IdGenerator.generateManualEntityUUID();

        // Then
        assertNotEquals(id1, id2);
        assertTrue(IdGenerator.isValidUUID(id1));
        assertTrue(IdGenerator.isValidUUID(id2));
        // Should be lowercase
        assertEquals(id1.toLowerCase(), id1);
        assertEquals(id2.toLowerCase(), id2);
    }

    @Test
    @DisplayName("Should normalize UUID to lowercase")
    void testNormalizeUUID() {
        // Given
        String mixedCase = "550E8400-E29B-41D4-A716-446655440000";

        // When
        String normalized = IdGenerator.normalizeUUID(mixedCase);

        // Then
        assertEquals("550e8400-e29b-41d4-a716-446655440000", normalized);
    }

    @Test
    @DisplayName("Should handle null in normalizeUUID")
    void testNormalizeUUID_Null() {
        assertNull(IdGenerator.normalizeUUID(null));
    }

    @Test
    @DisplayName("Should compare UUIDs case-insensitively")
    void testEqualsIgnoreCase() {
        // Given
        String uuid1 = "550E8400-E29B-41D4-A716-446655440000";
        String uuid2 = "550e8400-e29b-41d4-a716-446655440000";
        String uuid3 = "550e8400-e29b-41d4-a716-446655440001";

        // When/Then
        assertTrue(IdGenerator.equalsIgnoreCase(uuid1, uuid2));
        assertFalse(IdGenerator.equalsIgnoreCase(uuid1, uuid3));
        assertTrue(IdGenerator.equalsIgnoreCase(null, null));
        assertFalse(IdGenerator.equalsIgnoreCase(uuid1, null));
        assertFalse(IdGenerator.equalsIgnoreCase(null, uuid2));
    }

    @Test
    @DisplayName("Should generate deterministic UUID from namespace and name")
    void testGenerateDeterministicUUID() {
        // Given
        UUID namespace = UUID.randomUUID();
        String name = "test-name";

        // When
        String id1 = IdGenerator.generateDeterministicUUID(namespace, name);
        String id2 = IdGenerator.generateDeterministicUUID(namespace, name);

        // Then
        assertEquals(id1, id2);
        assertTrue(IdGenerator.isValidUUID(id1));
    }

    @Test
    @DisplayName("Should throw exception for null namespace or name")
    void testGenerateDeterministicUUID_NullParameters() {
        UUID namespace = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateDeterministicUUID(null, "name");
        });
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateDeterministicUUID(namespace, null);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateDeterministicUUID(namespace, "");
        });
    }

    @Test
    @DisplayName("Should trim name in generateDeterministicUUID")
    void testGenerateDeterministicUUID_TrimsName() {
        // Given
        UUID namespace = UUID.randomUUID();
        String name1 = "test-name";
        String name2 = "  test-name  ";

        // When
        String id1 = IdGenerator.generateDeterministicUUID(namespace, name1);
        String id2 = IdGenerator.generateDeterministicUUID(namespace, name2);

        // Then - Should produce same ID
        assertEquals(id1, id2);
    }
}
