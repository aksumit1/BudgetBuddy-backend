package com.budgetbuddy.util;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Utility class for generating consistent IDs across backend and app Uses UUID v5 (SHA-1 based
 * deterministic UUID) for all ID generation This ensures IDs are deterministic, consistent, and
 * handle special characters robustly
 */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
public final class IdGenerator {

    private static final String USER_ID_IS_REQUIRED = "User ID is required";

    // Namespace UUIDs for different entity types (using DNS namespace pattern)
    private static final UUID ACCOUNT_NAMESPACE =
            UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
    private static final UUID TRANSACTION_NAMESPACE =
            UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    private static final UUID BUDGET_NAMESPACE =
            UUID.fromString("6ba7b812-9dad-11d1-80b4-00c04fd430c8");
    private static final UUID GOAL_NAMESPACE =
            UUID.fromString("6ba7b813-9dad-11d1-80b4-00c04fd430c8");
    private static final UUID SUBSCRIPTION_NAMESPACE =
            UUID.fromString("6ba7b814-9dad-11d1-80b4-00c04fd430c8");

    /**
     * Generate account ID from bank name and Plaid account ID using UUID v5 This ensures the same
     * bank + account always gets the same UUID
     *
     * @param institutionName Bank/institution name
     * @param plaidAccountId Plaid account ID
     * @return Deterministic UUID as string
     */
    public static String generateAccountId(
            final String institutionName, final String plaidAccountId) {
        if (institutionName == null || institutionName.isBlank()) {
            throw new IllegalArgumentException("Institution name is required");
        }
        if (plaidAccountId == null || plaidAccountId.isBlank()) {
            throw new IllegalArgumentException("Plaid account ID is required");
        }

        // Create composite key: institutionName:plaidAccountId
        // This ensures uniqueness and determinism
        final String compositeKey =
                institutionName.trim().toLowerCase(Locale.ROOT)
                        + ":"
                        + plaidAccountId.trim().toLowerCase(Locale.ROOT);
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
    public static String generateTransactionId(
            final String institutionName, final String accountId, final String plaidTransactionId) {
        if (institutionName == null || institutionName.isBlank()) {
            throw new IllegalArgumentException("Institution name is required");
        }
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("Account ID is required");
        }
        if (plaidTransactionId == null || plaidTransactionId.isBlank()) {
            throw new IllegalArgumentException("Plaid transaction ID is required");
        }

        // Create composite key: institutionName:accountId:plaidTransactionId
        // This ensures uniqueness and determinism
        final String compositeKey =
                institutionName.trim().toLowerCase(Locale.ROOT)
                        + ":"
                        + accountId.trim().toLowerCase(Locale.ROOT)
                        + ":"
                        + plaidTransactionId.trim().toLowerCase(Locale.ROOT);
        return generateDeterministicUUID(TRANSACTION_NAMESPACE, compositeKey);
    }

    /**
     * Generate budget ID from user ID and category using UUID v5 This ensures the same user +
     * category always gets the same UUID
     *
     * @param userId User ID
     * @param category Budget category
     * @return Deterministic UUID as string
     */
    public static String generateBudgetId(final String userId, final String category) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException(USER_ID_IS_REQUIRED);
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("Category is required");
        }

        // Create composite key: userId:category
        final String compositeKey =
                userId.trim().toLowerCase(Locale.ROOT)
                        + ":"
                        + category.trim().toLowerCase(Locale.ROOT);
        return generateDeterministicUUID(BUDGET_NAMESPACE, compositeKey);
    }

    /**
     * Generate goal ID from user ID and goal name using UUID v5 Note: For goals, we use name
     * instead of a fixed identifier because goals are user-created and names might change. If you
     * need more determinism, consider adding a creation timestamp.
     *
     * @param userId User ID
     * @param goalName Goal name
     * @return Deterministic UUID as string
     */
    public static String generateGoalId(final String userId, final String goalName) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException(USER_ID_IS_REQUIRED);
        }
        if (goalName == null || goalName.isBlank()) {
            throw new IllegalArgumentException("Goal name is required");
        }

        // Create composite key: userId:goalName
        // Note: If goal names can change, this will generate different IDs
        // For true consistency, consider including a creation timestamp or unique identifier
        final String compositeKey =
                userId.trim().toLowerCase(Locale.ROOT)
                        + ":"
                        + goalName.trim().toLowerCase(Locale.ROOT);
        return generateDeterministicUUID(GOAL_NAMESPACE, compositeKey);
    }

    /**
     * Generate subscription ID from user ID, merchant name, and amount using UUID v5 This ensures
     * the same user + merchant + amount always gets the same UUID
     *
     * @param userId User ID
     * @param merchantName Merchant/company name
     * @param amount Subscription amount
     * @return Deterministic UUID as string
     */
    public static String generateSubscriptionId(
            final String userId, final String merchantName, final java.math.BigDecimal amount) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException(USER_ID_IS_REQUIRED);
        }
        if (merchantName == null || merchantName.isBlank()) {
            throw new IllegalArgumentException("Merchant name is required");
        }
        if (amount == null) {
            throw new IllegalArgumentException("Amount is required");
        }

        // Create composite key: userId:merchantName:amount
        // Round amount to 2 decimal places for consistency
        final String amountStr = amount.setScale(2, java.math.RoundingMode.HALF_UP).toString();
        final String compositeKey =
                userId.trim().toLowerCase(Locale.ROOT)
                        + ":"
                        + merchantName.trim().toLowerCase(Locale.ROOT)
                        + ":"
                        + amountStr;
        return generateDeterministicUUID(SUBSCRIPTION_NAMESPACE, compositeKey);
    }

    /**
     * Generate a deterministic UUID from a string using UUID v5 (SHA-1 based) This ensures the same
     * input always produces the same UUID
     *
     * @param namespace UUID namespace (use a fixed UUID for consistency)
     * @param name String to generate UUID from
     * @return Deterministic UUID as string (normalized to lowercase for case-insensitive matching)
     */
    public static String generateDeterministicUUID(final UUID namespace, final String name) {
        if (namespace == null) {
            throw new IllegalArgumentException("Namespace UUID is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }

        // CRITICAL: Trim the name to ensure consistency with iOS app
        // iOS app trims plaidTransactionId before passing to generateDeterministicUUID
        // This prevents UUID mismatches when input has leading/trailing whitespace
        final String trimmedName = name.trim();

        // UUID v5 uses SHA-1 hash of namespace + name
        // This ensures deterministic UUIDs that handle special characters robustly
        // Normalize to lowercase for case-insensitive matching
        return UUID.nameUUIDFromBytes(
                        (namespace.toString() + ":" + trimmedName).getBytes(StandardCharsets.UTF_8))
                .toString()
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Check if a string is a valid UUID format
     *
     * @param uuidString String to check
     * @return true if valid UUID format, false otherwise
     */
    public static boolean isValidUUID(final String uuidString) {
        if (uuidString == null || uuidString.isBlank()) {
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
     * Generate UUID for manual entities (transactions, budgets, goals) For app-generated entities,
     * the app will generate UUID and send to backend
     *
     * @return Random UUID as string (normalized to lowercase for case-insensitive matching)
     */
    public static String generateManualEntityUUID() {
        return UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
    }

    /**
     * Normalize UUID string to lowercase for case-insensitive comparison
     *
     * @param uuidString UUID string to normalize
     * @return Lowercase UUID string, or null if input is null
     */
    public static String normalizeUUID(final String uuidString) {
        if (uuidString == null || uuidString.isBlank()) {
            return uuidString;
        }
        return uuidString.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Compare two UUID strings case-insensitively
     *
     * @param uuid1 First UUID string
     * @param uuid2 Second UUID string
     * @return true if UUIDs match (case-insensitive), false otherwise
     */
    public static boolean equalsIgnoreCase(final String uuid1, final String uuid2) {
        if (uuid1 == null && uuid2 == null) {
            return true;
        }
        if (uuid1 == null || uuid2 == null) {
            return false;
        }
        return normalizeUUID(uuid1).equals(normalizeUUID(uuid2));
    }

    private IdGenerator() {}
}
