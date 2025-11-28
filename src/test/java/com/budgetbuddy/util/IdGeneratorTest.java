package com.budgetbuddy.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

/**
 * Unit tests for IdGenerator utility
 * Tests deterministic UUID generation for accounts, transactions, budgets, and goals
 */
class IdGeneratorTest {

    @Test
    void testGenerateAccountId_Deterministic() {
        // Given
        String institutionName = "Chase Bank";
        String plaidAccountId = "acc_123456789";

        // When
        String accountId1 = IdGenerator.generateAccountId(institutionName, plaidAccountId);
        String accountId2 = IdGenerator.generateAccountId(institutionName, plaidAccountId);

        // Then - Should generate the same ID for the same inputs
        assertEquals(accountId1, accountId2, "Account IDs should be deterministic");
        assertTrue(IdGenerator.isValidUUID(accountId1), "Account ID should be a valid UUID");
    }

    @Test
    void testGenerateAccountId_DifferentInstitutions_DifferentIds() {
        // Given
        String plaidAccountId = "acc_123456789";
        String institution1 = "Chase Bank";
        String institution2 = "Bank of America";

        // When
        String accountId1 = IdGenerator.generateAccountId(institution1, plaidAccountId);
        String accountId2 = IdGenerator.generateAccountId(institution2, plaidAccountId);

        // Then - Different institutions should produce different IDs
        assertNotEquals(accountId1, accountId2, "Different institutions should produce different account IDs");
    }

    @Test
    void testGenerateAccountId_DifferentPlaidIds_DifferentIds() {
        // Given
        String institutionName = "Chase Bank";
        String plaidAccountId1 = "acc_123456789";
        String plaidAccountId2 = "acc_987654321";

        // When
        String accountId1 = IdGenerator.generateAccountId(institutionName, plaidAccountId1);
        String accountId2 = IdGenerator.generateAccountId(institutionName, plaidAccountId2);

        // Then - Different Plaid account IDs should produce different IDs
        assertNotEquals(accountId1, accountId2, "Different Plaid account IDs should produce different account IDs");
    }

    @Test
    void testGenerateAccountId_HandlesSpecialCharacters() {
        // Given - Institution name with special characters
        String institutionName = "JPMorgan Chase & Co.";
        String plaidAccountId = "acc_123-456_789";

        // When
        String accountId = IdGenerator.generateAccountId(institutionName, plaidAccountId);

        // Then - Should handle special characters without errors
        assertNotNull(accountId, "Should generate ID even with special characters");
        assertTrue(IdGenerator.isValidUUID(accountId), "Generated ID should be valid UUID");
    }

    @Test
    void testGenerateAccountId_ThrowsException_WhenInstitutionNameIsNull() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateAccountId(null, "acc_123");
        }, "Should throw exception when institution name is null");
    }

    @Test
    void testGenerateAccountId_ThrowsException_WhenPlaidAccountIdIsNull() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            IdGenerator.generateAccountId("Chase Bank", null);
        }, "Should throw exception when Plaid account ID is null");
    }

    @Test
    void testGenerateTransactionId_Deterministic() {
        // Given
        String institutionName = "Chase Bank";
        String accountId = "account-uuid-123";
        String plaidTransactionId = "txn_123456789";

        // When
        String transactionId1 = IdGenerator.generateTransactionId(institutionName, accountId, plaidTransactionId);
        String transactionId2 = IdGenerator.generateTransactionId(institutionName, accountId, plaidTransactionId);

        // Then - Should generate the same ID for the same inputs
        assertEquals(transactionId1, transactionId2, "Transaction IDs should be deterministic");
        assertTrue(IdGenerator.isValidUUID(transactionId1), "Transaction ID should be a valid UUID");
    }

    @Test
    void testGenerateTransactionId_DifferentAccounts_DifferentIds() {
        // Given
        String institutionName = "Chase Bank";
        String accountId1 = "account-uuid-123";
        String accountId2 = "account-uuid-456";
        String plaidTransactionId = "txn_123456789";

        // When
        String transactionId1 = IdGenerator.generateTransactionId(institutionName, accountId1, plaidTransactionId);
        String transactionId2 = IdGenerator.generateTransactionId(institutionName, accountId2, plaidTransactionId);

        // Then - Different accounts should produce different transaction IDs
        assertNotEquals(transactionId1, transactionId2, "Different accounts should produce different transaction IDs");
    }

    @Test
    void testGenerateTransactionId_DifferentPlaidTransactionIds_DifferentIds() {
        // Given
        String institutionName = "Chase Bank";
        String accountId = "account-uuid-123";
        String plaidTransactionId1 = "txn_123456789";
        String plaidTransactionId2 = "txn_987654321";

        // When
        String transactionId1 = IdGenerator.generateTransactionId(institutionName, accountId, plaidTransactionId1);
        String transactionId2 = IdGenerator.generateTransactionId(institutionName, accountId, plaidTransactionId2);

        // Then - Different Plaid transaction IDs should produce different IDs
        assertNotEquals(transactionId1, transactionId2, "Different Plaid transaction IDs should produce different transaction IDs");
    }

    @Test
    void testGenerateBudgetId_Deterministic() {
        // Given
        String userId = "user-123";
        String category = "FOOD";

        // When
        String budgetId1 = IdGenerator.generateBudgetId(userId, category);
        String budgetId2 = IdGenerator.generateBudgetId(userId, category);

        // Then - Should generate the same ID for the same inputs
        assertEquals(budgetId1, budgetId2, "Budget IDs should be deterministic");
        assertTrue(IdGenerator.isValidUUID(budgetId1), "Budget ID should be a valid UUID");
    }

    @Test
    void testGenerateBudgetId_DifferentUsers_DifferentIds() {
        // Given
        String userId1 = "user-123";
        String userId2 = "user-456";
        String category = "FOOD";

        // When
        String budgetId1 = IdGenerator.generateBudgetId(userId1, category);
        String budgetId2 = IdGenerator.generateBudgetId(userId2, category);

        // Then - Different users should produce different budget IDs
        assertNotEquals(budgetId1, budgetId2, "Different users should produce different budget IDs");
    }

    @Test
    void testGenerateGoalId_Deterministic() {
        // Given
        String userId = "user-123";
        String goalName = "Emergency Fund";

        // When
        String goalId1 = IdGenerator.generateGoalId(userId, goalName);
        String goalId2 = IdGenerator.generateGoalId(userId, goalName);

        // Then - Should generate the same ID for the same inputs
        assertEquals(goalId1, goalId2, "Goal IDs should be deterministic");
        assertTrue(IdGenerator.isValidUUID(goalId1), "Goal ID should be a valid UUID");
    }

    @Test
    void testGenerateGoalId_DifferentNames_DifferentIds() {
        // Given
        String userId = "user-123";
        String goalName1 = "Emergency Fund";
        String goalName2 = "Vacation Fund";

        // When
        String goalId1 = IdGenerator.generateGoalId(userId, goalName1);
        String goalId2 = IdGenerator.generateGoalId(userId, goalName2);

        // Then - Different goal names should produce different IDs
        assertNotEquals(goalId1, goalId2, "Different goal names should produce different goal IDs");
    }

    @Test
    void testIsValidUUID_ValidUUID_ReturnsTrue() {
        // Given
        String validUUID = UUID.randomUUID().toString();

        // When
        boolean isValid = IdGenerator.isValidUUID(validUUID);

        // Then
        assertTrue(isValid, "Valid UUID should return true");
    }

    @Test
    void testIsValidUUID_InvalidUUID_ReturnsFalse() {
        // Given
        String invalidUUID = "not-a-uuid";

        // When
        boolean isValid = IdGenerator.isValidUUID(invalidUUID);

        // Then
        assertFalse(isValid, "Invalid UUID should return false");
    }

    @Test
    void testIsValidUUID_Null_ReturnsFalse() {
        // When
        boolean isValid = IdGenerator.isValidUUID(null);

        // Then
        assertFalse(isValid, "Null should return false");
    }

    @Test
    void testIsValidUUID_EmptyString_ReturnsFalse() {
        // When
        boolean isValid = IdGenerator.isValidUUID("");

        // Then
        assertFalse(isValid, "Empty string should return false");
    }

    @Test
    void testGenerateDeterministicUUID_ConsistentResults() {
        // Given
        UUID namespace = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
        String name = "test-name";

        // When
        String uuid1 = IdGenerator.generateDeterministicUUID(namespace, name);
        String uuid2 = IdGenerator.generateDeterministicUUID(namespace, name);

        // Then - Should generate the same UUID for the same inputs
        assertEquals(uuid1, uuid2, "Deterministic UUIDs should be consistent");
        assertTrue(IdGenerator.isValidUUID(uuid1), "Generated UUID should be valid");
    }

    @Test
    void testGenerateDeterministicUUID_DifferentNames_DifferentUUIDs() {
        // Given
        UUID namespace = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
        String name1 = "test-name-1";
        String name2 = "test-name-2";

        // When
        String uuid1 = IdGenerator.generateDeterministicUUID(namespace, name1);
        String uuid2 = IdGenerator.generateDeterministicUUID(namespace, name2);

        // Then - Different names should produce different UUIDs
        assertNotEquals(uuid1, uuid2, "Different names should produce different UUIDs");
    }
}

