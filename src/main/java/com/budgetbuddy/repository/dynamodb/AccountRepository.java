package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

/** DynamoDB Repository for Accounts */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Repository
public class AccountRepository {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(AccountRepository.class);

    private static final String ACCOUNTS = "accounts";

    private final DynamoDbTable<AccountTable> accountTable;
    private final DynamoDbIndex<AccountTable> userIdIndex;
    private final DynamoDbIndex<AccountTable> plaidAccountIdIndex;
    private final DynamoDbIndex<AccountTable> plaidItemIdIndex;
    private final DynamoDbIndex<AccountTable> userIdUpdatedAtIndex;
    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public AccountRepository(
            final DynamoDbEnhancedClient enhancedClient,
            final DynamoDbClient dynamoDbClient,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.aws.dynamodb.table-prefix:BudgetBuddy}")
                    final String tablePrefix) {
        this.tableName = tablePrefix + "-Accounts";
        this.accountTable =
                enhancedClient.table(this.tableName, TableSchema.fromBean(AccountTable.class));
        this.userIdIndex = accountTable.index("UserIdIndex");
        this.plaidAccountIdIndex = accountTable.index("PlaidAccountIdIndex");
        this.plaidItemIdIndex = accountTable.index("PlaidItemIdIndex");
        this.userIdUpdatedAtIndex = accountTable.index("UserIdUpdatedAtIndex");
        this.dynamoDbClient = dynamoDbClient;
    }

    @CacheEvict(value = ACCOUNTS, key = "#account.userId")
    public void save(final AccountTable account) {
        // CRITICAL FIX: Add retry logic for DynamoDB throttling and transient errors
        com.budgetbuddy.util.RetryHelper.executeDynamoDbWithRetry(
                () -> {
                    accountTable.putItem(account);
                    return null;
                });
    }

    /**
     * Save with optimistic concurrency on the {@code version} column. Use on high-risk paths where
     * concurrent writers can race (user edits vs Plaid sync). Legacy {@link #save(AccountTable)} is
     * retained for low-risk paths; migrate caller-by-caller.
     *
     * @throws OptimisticLockHelper.OptimisticLockException if another writer beat us — caller
     *     should re-read and retry, or surface 409.
     */
    @CacheEvict(value = ACCOUNTS, key = "#account.userId")
    public AccountTable saveWithLock(final AccountTable account) {
        return OptimisticLockHelper.saveWithLock(
                accountTable,
                account,
                AccountTable::getVersion,
                account::setVersion,
                "accountId=" + account.getAccountId());
    }

    /**
     * Get or create a pseudo account for transactions without an account This creates a system
     * account that can be used for manual transactions where the user doesn't select an existing
     * account
     *
     * <p>CRITICAL: Uses conditional write to ensure only one pseudo account is created even if
     * multiple requests come in simultaneously (thread-safe)
     *
     * @param userId User ID to create pseudo account for
     * @return The pseudo account (created if it doesn't exist)
     */
    public AccountTable getOrCreatePseudoAccount(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("User ID is required");
        }

        // Generate deterministic UUID for pseudo account based on userId
        // Use a special namespace for pseudo accounts
        final UUID pseudoAccountNamespace =
                UUID.fromString("6ba7b815-9dad-11d1-80b4-00c04fd430c8"); // Pseudo account namespace
        final String pseudoAccountId =
                com.budgetbuddy.util.IdGenerator.generateDeterministicUUID(
                        pseudoAccountNamespace,
                        "pseudo-account:" + userId.toLowerCase(Locale.ROOT));

        // Try to find existing pseudo account
        final Optional<AccountTable> existing = findById(pseudoAccountId);
        if (existing.isPresent()) {
            final AccountTable account = existing.get();
            // Verify it belongs to the user
            if (userId.equals(account.getUserId())) {
                return account;
            }
        }

        // Create new pseudo account
        final AccountTable pseudoAccount = new AccountTable();
        pseudoAccount.setAccountId(pseudoAccountId);
        pseudoAccount.setUserId(userId);
        pseudoAccount.setAccountName("Manual Transactions");
        pseudoAccount.setInstitutionName("BudgetBuddy");
        pseudoAccount.setAccountType("other");
        pseudoAccount.setAccountSubtype("manual");
        pseudoAccount.setBalance(BigDecimal.ZERO);
        pseudoAccount.setCurrencyCode("USD");
        pseudoAccount.setActive(true);
        pseudoAccount.setPlaidAccountId(null); // No Plaid ID for pseudo accounts
        pseudoAccount.setPlaidItemId(null);
        pseudoAccount.setAccountNumber(null);
        pseudoAccount.setCreatedAt(java.time.Instant.now());
        pseudoAccount.setUpdatedAt(java.time.Instant.now());

        // CRITICAL FIX: Use conditional write to ensure only one pseudo account is created
        // This prevents race conditions when multiple requests try to create the account
        // simultaneously
        try {
            // Use conditional write: only create if account doesn't exist
            final Expression conditionExpression =
                    Expression.builder().expression("attribute_not_exists(accountId)").build();

            accountTable.putItem(
                    software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest.builder(
                                    AccountTable.class)
                            .item(pseudoAccount)
                            .conditionExpression(conditionExpression)
                            .build());

            LOGGER.info("Created pseudo account for user {}: {}", userId, pseudoAccountId);
        } catch (ConditionalCheckFailedException e) {
            // Account was created by another request - fetch and return it
            LOGGER.debug(
                    "Pseudo account already exists (created by another request), fetching existing: {}",
                    pseudoAccountId);
            final Optional<AccountTable> existingAccount = findById(pseudoAccountId);
            if (existingAccount.isPresent()) {
                return existingAccount.get();
            }
            // If still not found, throw exception (shouldn't happen)
            throw new IllegalStateException(
                    "Failed to create or retrieve pseudo account: " + pseudoAccountId);
        }

        return pseudoAccount;
    }

    public Optional<AccountTable> findById(final String accountId) {
        if (accountId == null || accountId.isEmpty()) {
            return Optional.empty();
        }
        // Normalize ID to lowercase for case-insensitive lookup
        final String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(accountId);
        final AccountTable account =
                accountTable.getItem(Key.builder().partitionValue(normalizedId).build());
        return Optional.ofNullable(account);
    }

    @Cacheable(value = ACCOUNTS, key = "#userId", unless = "#result == null || #result.isEmpty()")
    public List<AccountTable> findByUserId(final String userId) {
        // CRITICAL: Return empty list early if userId is null or empty
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }

        final List<AccountTable> results = new ArrayList<>();
        int totalFound = 0;
        int activeCount = 0;
        int inactiveCount = 0;
        int nullActiveCount = 0;
        int duplicateCount = 0;
        int pseudoAccountCount = 0;

        // CRITICAL: Deduplicate accounts by both accountId and plaidAccountId
        // Use Set to track seen accountIds and plaidAccountIds to prevent duplicates
        final java.util.Set<String> seenAccountIds = new java.util.HashSet<>();
        final java.util.Set<String> seenPlaidAccountIds = new java.util.HashSet<>();

        final SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<AccountTable>> pages =
                userIdIndex.query(
                        QueryConditional.keyEqualTo(Key.builder().partitionValue(userId).build()));
        for (final software.amazon.awssdk.enhanced.dynamodb.model.Page<AccountTable> page : pages) {
            for (final AccountTable account : page.items()) {
                totalFound++;

                // CRITICAL FIX: Filter out pseudo accounts (accountSubtype == "manual")
                // Pseudo accounts are system accounts and should not be returned to users
                if ("manual".equalsIgnoreCase(account.getAccountSubtype())) {
                    pseudoAccountCount++;
                    continue; // Skip pseudo accounts
                }

                // Check for duplicates by accountId
                final String accountId = account.getAccountId();
                if (accountId != null && seenAccountIds.contains(accountId)) {
                    duplicateCount++;
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn(
                                "Duplicate account detected by accountId and filtered: accountId={}, plaidAccountId={}, name={}",
                                accountId,
                                account.getPlaidAccountId(),
                                account.getAccountName());
                    }
                    continue;
                }

                // Check for duplicates by plaidAccountId (if present)
                final String plaidAccountId = account.getPlaidAccountId();
                if (plaidAccountId != null && !plaidAccountId.isEmpty()) {
                    if (seenPlaidAccountIds.contains(plaidAccountId)) {
                        duplicateCount++;
                        if (LOGGER.isWarnEnabled()) {
                            LOGGER.warn(
                                    "Duplicate account detected by plaidAccountId and filtered: accountId={}, plaidAccountId={}, name={}",
                                    accountId,
                                    plaidAccountId,
                                    account.getAccountName());
                        }
                        continue;
                    }
                    seenPlaidAccountIds.add(plaidAccountId);
                }

                // Account is unique, add to results
                if (accountId != null) {
                    seenAccountIds.add(accountId);
                }

                if (account.getActive() == null || account.getActive()) {
                    if (account.getActive() == null) {
                        nullActiveCount++;
                    } else {
                        activeCount++;
                    }
                    results.add(account);
                } else {
                    inactiveCount++;
                }
            }
        }

        // Log for debugging
        if (totalFound > 0) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "findByUserId({}): Found {} total accounts ({} active, {} inactive, {} null active, {} duplicates filtered, {} pseudo accounts filtered). Returning {} unique accounts.",
                        userId,
                        totalFound,
                        activeCount,
                        inactiveCount,
                        nullActiveCount,
                        duplicateCount,
                        pseudoAccountCount,
                        results.size());
            }
        }

        return results;
    }

    public Optional<AccountTable> findByPlaidAccountId(final String plaidAccountId) {
        final SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<AccountTable>> pages =
                plaidAccountIdIndex.query(
                        QueryConditional.keyEqualTo(
                                Key.builder().partitionValue(plaidAccountId).build()));
        for (final software.amazon.awssdk.enhanced.dynamodb.model.Page<AccountTable> page : pages) {
            for (final AccountTable item : page.items()) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    /**
     * Find account by account number (mask) and institution name Used for deduplication when
     * plaidAccountId is not available Note: This scans the table, so for production with large
     * datasets, consider adding a GSI
     */
    public Optional<AccountTable> findByAccountNumberAndInstitution(
            final String accountNumber, final String institutionName, final String userId) {
        if (accountNumber == null || accountNumber.isEmpty()) {
            return Optional.empty();
        }

        try {
            // Query by userId first (using GSI) for efficiency
            final List<AccountTable> userAccounts = findByUserId(userId);

            // Filter by account number and institution name (if provided)
            // CRITICAL FIX: Normalize account numbers before comparison (handles hyphens, spaces,
            // etc.)
            // This ensures "8-41007" matches "841007" or "8 41007"
            final String normalizedAccountNumber = normalizeAccountNumber(accountNumber);

            // CRITICAL FIX: If institutionName is null, match by accountNumber only
            // This handles cases where institutionName is missing but accountNumber is available
            if (institutionName == null || institutionName.isEmpty()) {
                // Match by accountNumber only when institutionName is not available
                return userAccounts.stream()
                        .filter(
                                account ->
                                        account.getAccountNumber() != null
                                                && normalizedAccountNumber.equals(
                                                        normalizeAccountNumber(
                                                                account.getAccountNumber())))
                        .findFirst();
            } else {
                // Match by both accountNumber and institutionName when both are available
                return userAccounts.stream()
                        .filter(
                                account ->
                                        account.getAccountNumber() != null
                                                && normalizedAccountNumber.equals(
                                                        normalizeAccountNumber(
                                                                account.getAccountNumber()))
                                                && institutionName.equals(
                                                        account.getInstitutionName()))
                        .findFirst();
            }
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error finding account by account number {} and institution {}: {}",
                        accountNumber,
                        institutionName,
                        e.getMessage(),
                        e);
            }
            return Optional.empty();
        }
    }

    /**
     * Find account by account number only (fallback when institution name is missing) Used for
     * deduplication when plaidAccountId is not available and institutionName is null
     */
    public Optional<AccountTable> findByAccountNumber(
            final String accountNumber, final String userId) {
        if (accountNumber == null || accountNumber.isEmpty()) {
            return Optional.empty();
        }

        try {
            // CRITICAL FIX: Normalize account numbers before comparison (handles hyphens, spaces,
            // etc.)
            final String normalizedAccountNumber = normalizeAccountNumber(accountNumber);
            final List<AccountTable> userAccounts = findByUserId(userId);
            return userAccounts.stream()
                    .filter(
                            account ->
                                    account.getAccountNumber() != null
                                            && normalizedAccountNumber.equals(
                                                    normalizeAccountNumber(
                                                            account.getAccountNumber())))
                    .findFirst();
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error finding account by account number {}: {}",
                        accountNumber,
                        e.getMessage(),
                        e);
            }
            return Optional.empty();
        }
    }

    /**
     * Find all accounts by Plaid item ID using GSI (optimized) Used for webhook processing to find
     * all accounts associated with a Plaid item
     */
    @Cacheable(
            value = ACCOUNTS,
            key = "'plaidItem:' + #plaidItemId",
            unless = "#result == null || #result.isEmpty()")
    public List<AccountTable> findByPlaidItemId(final String plaidItemId) {
        if (plaidItemId == null || plaidItemId.isEmpty()) {
            return List.of();
        }

        final List<AccountTable> results = new ArrayList<>();
        try {
            // Check if index is available (may be null in test environments or if index doesn't
            // exist)
            if (plaidItemIdIndex != null) {
                // Use GSI for efficient query (replaces inefficient scan)
                final SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<AccountTable>>
                        pages =
                                plaidItemIdIndex.query(
                                        QueryConditional.keyEqualTo(
                                                Key.builder().partitionValue(plaidItemId).build()));

                for (final software.amazon.awssdk.enhanced.dynamodb.model.Page<AccountTable> page :
                        pages) {
                    results.addAll(page.items());
                }
            } else {
                // Fallback to scan if index is not available (e.g., in test environments)
                // This is less efficient but ensures the method works even if the index isn't
                // initialized
                LOGGER.warn(
                        "PlaidItemIdIndex is not available, falling back to scan for plaidItemId: {}",
                        plaidItemId);

                final SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<AccountTable>>
                        pages = accountTable.scan();

                for (final software.amazon.awssdk.enhanced.dynamodb.model.Page<AccountTable> page :
                        pages) {
                    for (final AccountTable account : page.items()) {
                        if (plaidItemId.equals(account.getPlaidItemId())) {
                            results.add(account);
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error finding accounts by Plaid item ID {}: {}",
                        plaidItemId,
                        e.getMessage(),
                        e);
            }
        }

        return results;
    }

    /**
     * Find accounts updated after a specific timestamp using GSI Optimized for incremental sync -
     * queries only changed items
     *
     * <p>CRITICAL: Do NOT cache this method - incremental sync queries must always be fresh to
     * handle DynamoDB GSI eventual consistency. Caching empty results would prevent finding updated
     * items until cache expires.
     *
     * <p>FALLBACK: If GSI is not available (e.g., in test environments), falls back to findByUserId
     * and filters in memory. This is less efficient but ensures the method works even when the
     * index hasn't been created yet.
     */
    public List<AccountTable> findByUserIdAndUpdatedAfter(
            final String userId, final Long updatedAfterTimestamp) {
        if (userId == null || userId.isEmpty() || updatedAfterTimestamp == null) {
            return List.of();
        }

        final List<AccountTable> results = new ArrayList<>();
        try {
            // CRITICAL FIX: Cannot use filter expression on sort key (updatedAtTimestamp is GSI
            // sort key)
            // Query all items for user, then filter in application code
            // This is still efficient because we're using the GSI partition key
            final SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<AccountTable>>
                    pages =
                            userIdUpdatedAtIndex.query(
                                    QueryConditional.keyEqualTo(
                                            Key.builder().partitionValue(userId).build()));

            for (final software.amazon.awssdk.enhanced.dynamodb.model.Page<AccountTable> page :
                    pages) {
                for (final AccountTable account : page.items()) {
                    // Filter in application code: updatedAtTimestamp >= updatedAfterTimestamp
                    // Use >= to include items updated exactly at the timestamp
                    if (account.getUpdatedAtTimestamp() != null
                            && account.getUpdatedAtTimestamp() >= updatedAfterTimestamp) {
                        results.add(account);
                    }
                }
            }
        } catch (ResourceNotFoundException e) {
            // GSI not available - fallback to findByUserId and filter in memory
            // This can happen in test environments where the index hasn't been created
            LOGGER.warn(
                    "UserIdUpdatedAtIndex GSI not found for userId {}. Falling back to findByUserId and filtering in memory. "
                            + "This is less efficient but ensures functionality when the index is not available.",
                    userId);
            try {
                final List<AccountTable> allAccounts = findByUserId(userId);
                for (final AccountTable account : allAccounts) {
                    if (account.getUpdatedAtTimestamp() != null
                            && account.getUpdatedAtTimestamp() >= updatedAfterTimestamp) {
                        results.add(account);
                    }
                }
            } catch (Exception fallbackException) {
                if (LOGGER.isErrorEnabled()) {
                    LOGGER.error(
                            "Error in fallback query for userId {}: {}",
                            userId,
                            fallbackException.getMessage(),
                            fallbackException);
                }
            }
        } catch (Exception e) {
            // Log at WARN level since this is a graceful fallback (returns empty list)
            // ERROR level is reserved for errors that prevent the method from completing its
            // contract
            // Here, we gracefully handle the error by returning an empty list, so WARN is
            // appropriate
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Error finding accounts by userId and updatedAfter {}: {}. Returning empty list.",
                        userId,
                        e.getMessage());
            }
        }

        return results;
    }

    @CacheEvict(value = ACCOUNTS, allEntries = true)
    public void delete(final String accountId) {
        if (accountId == null || accountId.isEmpty()) {
            throw new IllegalArgumentException("Account ID cannot be null or empty");
        }
        accountTable.deleteItem(Key.builder().partitionValue(accountId).build());
    }

    /**
     * Save account only if it doesn't exist (conditional write) Prevents duplicate accounts
     * (deduplication)
     */
    public boolean saveIfNotExists(final AccountTable account) {
        if (account == null) {
            throw new IllegalArgumentException("Account cannot be null");
        }
        if (account.getAccountId() == null || account.getAccountId().isEmpty()) {
            throw new IllegalArgumentException("Account ID is required");
        }

        try {
            accountTable.putItem(
                    software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest.builder(
                                    AccountTable.class)
                            .item(account)
                            .conditionExpression(
                                    Expression.builder()
                                            .expression("attribute_not_exists(accountId)")
                                            .build())
                            .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false; // Account already exists
        }
    }

    /**
     * Batch save accounts using BatchWriteItem (cost-optimized) DynamoDB allows up to 25 items per
     * batch
     */
    @CacheEvict(value = ACCOUNTS, allEntries = true)
    public void batchSave(final List<AccountTable> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return;
        }

        final int batchSize = 25;
        final List<List<AccountTable>> batches = new ArrayList<>();
        for (int i = 0; i < accounts.size(); i += batchSize) {
            batches.add(accounts.subList(i, Math.min(i + batchSize, accounts.size())));
        }

        for (final List<AccountTable> batch : batches) {
            final List<WriteRequest> writeRequests =
                    batch.stream()
                            .map(
                                    account -> {
                                        final Map<String, AttributeValue> item = new HashMap<>();
                                        item.put(
                                                "accountId",
                                                AttributeValue.builder()
                                                        .s(account.getAccountId())
                                                        .build());
                                        if (account.getUserId() != null) {
                                            item.put(
                                                    "userId",
                                                    AttributeValue.builder()
                                                            .s(account.getUserId())
                                                            .build());
                                        }
                                        // Add other attributes as needed
                                        return WriteRequest.builder()
                                                .putRequest(PutRequest.builder().item(item).build())
                                                .build();
                                    })
                            .collect(Collectors.toList());

            final Map<String, List<WriteRequest>> requestItems = new HashMap<>();
            requestItems.put(this.tableName, writeRequests);

            final BatchWriteItemRequest batchRequest =
                    BatchWriteItemRequest.builder().requestItems(requestItems).build();

            com.budgetbuddy.util.RetryHelper.executeWithRetry(
                    () -> {
                        final BatchWriteItemResponse resp =
                                dynamoDbClient.batchWriteItem(batchRequest);

                        // Retry if there are unprocessed items
                        if (!resp.unprocessedItems().isEmpty()) {
                            throw new AppException(
                                    ErrorCode.INTERNAL_SERVER_ERROR,
                                    "Unprocessed items in batch write");
                        }

                        return resp;
                    });

            // All items processed successfully
        }
    }

    /** Batch delete accounts using BatchWriteItem */
    @CacheEvict(value = ACCOUNTS, allEntries = true)
    public void batchDelete(final List<String> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return;
        }

        final int batchSize = 25;
        for (int i = 0; i < accountIds.size(); i += batchSize) {
            final List<String> batch =
                    accountIds.subList(i, Math.min(i + batchSize, accountIds.size()));

            final List<WriteRequest> writeRequests =
                    batch.stream()
                            .map(
                                    id -> {
                                        final Map<String, AttributeValue> key = new HashMap<>();
                                        key.put(
                                                "accountId",
                                                AttributeValue.builder().s(id).build());
                                        return WriteRequest.builder()
                                                .deleteRequest(
                                                        software.amazon.awssdk.services.dynamodb
                                                                .model.DeleteRequest.builder()
                                                                .key(key)
                                                                .build())
                                                .build();
                                    })
                            .collect(Collectors.toList());

            final Map<String, List<WriteRequest>> requestItems = new HashMap<>();
            requestItems.put(this.tableName, writeRequests);

            final BatchWriteItemRequest batchRequest =
                    BatchWriteItemRequest.builder().requestItems(requestItems).build();

            dynamoDbClient.batchWriteItem(batchRequest);
        }
    }

    /**
     * Normalize account number for matching - remove hyphens, spaces, and other separators, extract
     * last 4 digits CRITICAL: This ensures consistent comparison regardless of format (e.g.,
     * "8-41007" vs "841007" vs "8 41007")
     *
     * @param accountNumber Account number in any format (may contain hyphens, spaces, etc.)
     * @return Normalized account number (last 4 digits only, digits only)
     */
    private String normalizeAccountNumber(final String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return "";
        }

        // Remove all non-digit characters (hyphens, spaces, masks, etc.)
        final String digitsOnly = accountNumber.replaceAll("[^0-9]", "");

        if (digitsOnly.length() == 0) {
            return "";
        }

        // Extract last 4 digits (for security and consistency)
        if (digitsOnly.length() > 4) {
            return digitsOnly.substring(digitsOnly.length() - 4);
        }

        return digitsOnly;
    }
}
