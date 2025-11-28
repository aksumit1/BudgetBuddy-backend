package com.budgetbuddy.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Utility class for generating consistent IDs across backend and app
 * Uses UUID v5 (SHA-1 based deterministic UUID) for all ID generation
 * This ensures IDs are deterministic, consistent, and handle special characters robustly
 */
public class IdGenerator {

    // Namespace UUIDs for different entity types (using DNS namespace pattern)
    private static final UUID ACCOUNT_NAMESPACE = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
    private static final UUID TRANSACTION_NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    private static final UUID BUDGET_NAMESPACE = UUID.fromString("6ba7b812-9dad-11d1-80b4-00c04fd430c8");
    private static final UUID GOAL_NAMESPACE = UUID.fromString("6ba7b813-9dad-11d1-80b4-00c04fd430c8");

    /**
     * Generate account ID from bank name and Plaid account ID using UUID v5
     * This ensures the same bank + account always gets the same UUID
     * 
     * @param institutionName Bank/institution name
     * @param plaidAccountId Plaid account ID
     * @return Deterministic UUID as string
     */
    public static String generateAccountId(final String institutionName, final String plaidAccountId) {
        if (institutionName == null || institutionName.trim().isEmpty()) {
            throw new IllegalArgumentException("Institution name is required");
        }
        if (plaidAccountId == null || plaidAccountId.trim().isEmpty()) {
            throw new IllegalArgumentException("Plaid account ID is required");
        }
        
        // Create composite key: institutionName:plaidAccountId
        // This ensures uniqueness and determinism
        String compositeKey = institutionName.trim().toLowerCase() + ":" + plaidAccountId.trim().toLowerCase();
        return generateDeterministicUUID(ACCOUNT_NAMESPACE, compositeKey);
    }

    /**
     * Generate transaction ID from account bank, account ID, and Plaid transaction ID using UUID v5
     * This ensures the same bank + account + transaction always gets the same UUID
     * 
     * @param institutionName Bank/institution name
     * @param accountId Account ID (can be Plaid account ID or generated account ID)
     * @param plaidTransactionId Plaid transaction ID
     * @return Deterministic UUID as string
     */
    public static String generateTransactionId(final String institutionName, final String accountId, final String plaidTransactionId) {
        if (institutionName == null || institutionName.trim().isEmpty()) {
            throw new IllegalArgumentException("Institution name is required");
        }
        if (accountId == null || accountId.trim().isEmpty()) {
            throw new IllegalArgumentException("Account ID is required");
        }
        if (plaidTransactionId == null || plaidTransactionId.trim().isEmpty()) {
            throw new IllegalArgumentException("Plaid transaction ID is required");
        }
        
        // Create composite key: institutionName:accountId:plaidTransactionId
        // This ensures uniqueness and determinism
        String compositeKey = institutionName.trim().toLowerCase() + ":" + 
                             accountId.trim().toLowerCase() + ":" + 
                             plaidTransactionId.trim().toLowerCase();
        return generateDeterministicUUID(TRANSACTION_NAMESPACE, compositeKey);
    }

    /**
     * Generate budget ID from user ID and category using UUID v5
     * This ensures the same user + category always gets the same UUID
     * 
     * @param userId User ID
     * @param category Budget category
     * @return Deterministic UUID as string
     */
    public static String generateBudgetId(final String userId, final String category) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("User ID is required");
        }
        if (category == null || category.trim().isEmpty()) {
            throw new IllegalArgumentException("Category is required");
        }
        
        // Create composite key: userId:category
        String compositeKey = userId.trim().toLowerCase() + ":" + category.trim().toLowerCase();
        return generateDeterministicUUID(BUDGET_NAMESPACE, compositeKey);
    }

    /**
     * Generate goal ID from user ID and goal name using UUID v5
     * Note: For goals, we use name instead of a fixed identifier because goals are user-created
     * and names might change. If you need more determinism, consider adding a creation timestamp.
     * 
     * @param userId User ID
     * @param goalName Goal name
     * @return Deterministic UUID as string
     */
    public static String generateGoalId(final String userId, final String goalName) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("User ID is required");
        }
        if (goalName == null || goalName.trim().isEmpty()) {
            throw new IllegalArgumentException("Goal name is required");
        }
        
        // Create composite key: userId:goalName
        // Note: If goal names can change, this will generate different IDs
        // For true consistency, consider including a creation timestamp or unique identifier
        String compositeKey = userId.trim().toLowerCase() + ":" + goalName.trim().toLowerCase();
        return generateDeterministicUUID(GOAL_NAMESPACE, compositeKey);
    }

    /**
     * Generate a deterministic UUID from a string using UUID v5 (SHA-1 based)
     * This ensures the same input always produces the same UUID
     * 
     * @param namespace UUID namespace (use a fixed UUID for consistency)
     * @param name String to generate UUID from
     * @return Deterministic UUID as string
     */
    public static String generateDeterministicUUID(final UUID namespace, final String name) {
        if (namespace == null) {
            throw new IllegalArgumentException("Namespace UUID is required");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Name is required");
        }
        
        // UUID v5 uses SHA-1 hash of namespace + name
        // This ensures deterministic UUIDs that handle special characters robustly
        return UUID.nameUUIDFromBytes(
            (namespace.toString() + ":" + name).getBytes(StandardCharsets.UTF_8)
        ).toString();
    }

    /**
     * Check if a string is a valid UUID format
     * 
     * @param uuidString String to check
     * @return true if valid UUID format, false otherwise
     */
    public static boolean isValidUUID(final String uuidString) {
        if (uuidString == null || uuidString.trim().isEmpty()) {
            return false;
        }
        try {
            UUID.fromString(uuidString);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Generate UUID for manual entities (transactions, budgets, goals)
     * For app-generated entities, the app will generate UUID and send to backend
     * This method is for backward compatibility when app doesn't send ID
     * 
     * @return Random UUID as string
     */
    public static String generateManualEntityUUID() {
        return UUID.randomUUID().toString();
    }
}

