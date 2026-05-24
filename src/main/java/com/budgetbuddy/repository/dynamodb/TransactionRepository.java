package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

/**
 * DynamoDB Repository for Transactions Uses GSI for efficient user queries with date range
 * filtering
 *
 * <p>Fixed: Proper date range queries using GSI sort key
 */
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
public class TransactionRepository {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(TransactionRepository.class);

    private static final String TRANSACTIONS = "transactions";
    private static final String TRANSACTIONID = "transactionId";
    private static final String ERROR_IN_FALLBACK_QUERY_FOR_USERID =
            "Error in fallback query for userId {}: {}";
    private static final String TRANSACTION_CANNOT_BE_NULL = "Transaction cannot be null";
    private static final String INDEX_NOT_FOUND = "index not found";
    private static final String RESOURCE_NOT_FOUND = "resource not found";

    /**
     * Upper bound for GSI-fallback paths that previously asked for {@link Integer#MAX_VALUE}
     * results in one fetch. When a GSI is missing in prod the fallbacks walk the user's full
     * transaction history into JVM heap — at this ceiling a user with 50k+ transactions stops
     * silently OOM'ing the worker and instead surfaces a WARN log so the GSI gap is visible.
     */
    /**
     * Default ceiling for the GSI-fallback path. Externalised via
     * {@code app.dynamodb.max-fallback-fetch} so ops can tighten or
     * loosen it without redeploying. See the constructor for the
     * binding logic.
     */
    private static final int MAX_FALLBACK_FETCH_DEFAULT = 50_000;

    private final DynamoDbTable<TransactionTable> transactionTable;
    private final DynamoDbIndex<TransactionTable> userIdDateIndex;
    private final DynamoDbIndex<TransactionTable> plaidTransactionIdIndex;
    private final DynamoDbIndex<TransactionTable> userIdUpdatedAtIndex;
    private final DynamoDbIndex<TransactionTable> userIdGoalIdIndex;
    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final int maxFallbackFetch;

    @org.springframework.beans.factory.annotation.Autowired
    public TransactionRepository(
            final DynamoDbEnhancedClient enhancedClient,
            final DynamoDbClient dynamoDbClient,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.aws.dynamodb.table-prefix:BudgetBuddy}")
                    final String tablePrefix,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.dynamodb.max-fallback-fetch:50000}")
                    final int maxFallbackFetch) {
        this.tableName = tablePrefix + "-Transactions";
        this.maxFallbackFetch = maxFallbackFetch > 0
                ? maxFallbackFetch
                : MAX_FALLBACK_FETCH_DEFAULT;
        this.transactionTable =
                enhancedClient.table(this.tableName, TableSchema.fromBean(TransactionTable.class));
        this.userIdDateIndex = transactionTable.index("UserIdDateIndex");
        this.plaidTransactionIdIndex = transactionTable.index("PlaidTransactionIdIndex");
        this.userIdUpdatedAtIndex = transactionTable.index("UserIdUpdatedAtIndex");
        this.userIdGoalIdIndex = transactionTable.index("UserIdGoalIdIndex");
        this.dynamoDbClient = dynamoDbClient;
    }

    /**
     * Backwards-compat constructor for tests that pre-date the
     * configurable fallback ceiling. Uses {@link #MAX_FALLBACK_FETCH_DEFAULT}.
     */
    public TransactionRepository(
            final DynamoDbEnhancedClient enhancedClient,
            final DynamoDbClient dynamoDbClient,
            final String tablePrefix) {
        this(enhancedClient, dynamoDbClient, tablePrefix, MAX_FALLBACK_FETCH_DEFAULT);
    }

    @CacheEvict(value = TRANSACTIONS, allEntries = true)
    public void save(final TransactionTable transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException(TRANSACTION_CANNOT_BE_NULL);
        }
        // CRITICAL FIX: Add retry logic for DynamoDB throttling and transient errors
        // This improves data durability during transient failures
        com.budgetbuddy.util.RetryHelper.executeDynamoDbWithRetry(
                () -> {
                    transactionTable.putItem(transaction);
                    return null;
                });
    }

    /**
     * Save with optimistic concurrency on the {@code version} column. Use on user-facing mutation
     * paths (category edit, notes edit, soft-delete) that race with Plaid sync re-ingestion of the
     * same row. Legacy {@link #save(TransactionTable)} remains for bulk ingest paths.
     *
     * @throws OptimisticLockHelper.OptimisticLockException on conflict.
     */
    @CacheEvict(value = TRANSACTIONS, allEntries = true)
    public TransactionTable saveWithLock(final TransactionTable transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException(TRANSACTION_CANNOT_BE_NULL);
        }
        return OptimisticLockHelper.saveWithLock(
                transactionTable,
                transaction,
                TransactionTable::getVersion,
                transaction::setVersion,
                "transactionId=" + transaction.getTransactionId());
    }

    public Optional<TransactionTable> findById(final String transactionId) {
        if (transactionId == null || transactionId.isEmpty()) {
            return Optional.empty();
        }
        // Normalize ID to lowercase for case-insensitive lookup
        final String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(transactionId);
        final TransactionTable transaction =
                transactionTable.getItem(Key.builder().partitionValue(normalizedId).build());
        return Optional.ofNullable(transaction);
    }

    /**
     * Disaster-fallback fetch used when a GSI is missing and the caller can't paginate. Returns up
     * to {@link #MAX_FALLBACK_FETCH} transactions for {@code userId} (walks the {@code
     * UserIdDateIndex} GSI directly, bypassing the 100-row cap on {@link #findByUserId(String, int,
     * int)}). Logs a WARN if the cap is hit — that means either a power user with 50k+ transactions
     * or, more likely, a misconfigured prod GSI that should be fixed at the infra layer rather than
     * papered over in code.
     */
    public List<TransactionTable> findByUserIdCapped(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }
        final List<TransactionTable> out = new ArrayList<>();
        try {
            final SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>>
                    pages =
                            userIdDateIndex.query(
                                    QueryConditional.keyEqualTo(
                                            Key.builder().partitionValue(userId).build()));
            for (final software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable> page :
                    pages) {
                for (final TransactionTable item : page.items()) {
                    if (out.size() >= maxFallbackFetch) {
                        LOGGER.warn(
                                "findByUserIdCapped hit ceiling of {} rows for userId {} —"
                                        + " result is truncated. Provision the missing GSI"
                                        + " or call a paginating method instead.",
                                maxFallbackFetch,
                                userId);
                        return out;
                    }
                    out.add(item);
                }
            }
            return out;
        } catch (ResourceNotFoundException e) {
            return fallbackScanForUserCapped(userId);
        } catch (RuntimeException e) {
            final Throwable cause = e.getCause();
            final String msg =
                    e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";
            if (cause instanceof ResourceNotFoundException
                    || msg.contains(INDEX_NOT_FOUND)
                    || msg.contains(RESOURCE_NOT_FOUND)) {
                return fallbackScanForUserCapped(userId);
            }
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "findByUserIdCapped failed for userId {}: {}", userId, e.getMessage(), e);
            }
            return out;
        }
    }

    /**
     * Last-resort fallback when even the {@code UserIdDateIndex} GSI is missing — table-scan and
     * filter by userId in memory. Capped at {@link #MAX_FALLBACK_FETCH} rows just like the GSI
     * path. WARN-logged so the GSI gap is visible in CloudWatch.
     */
    private List<TransactionTable> fallbackScanForUserCapped(final String userId) {
        LOGGER.warn(
                "UserIdDateIndex GSI not found for userId {}; falling back to base-table"
                        + " scan in findByUserIdCapped.",
                userId);
        final List<TransactionTable> out = new ArrayList<>();
        try {
            final SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>>
                    scanPages = transactionTable.scan();
            for (final software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable> page :
                    scanPages) {
                for (final TransactionTable item : page.items()) {
                    if (item == null || !userId.equals(item.getUserId())) {
                        continue;
                    }
                    if (out.size() >= maxFallbackFetch) {
                        return out;
                    }
                    out.add(item);
                }
            }
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Fallback scan failed for userId {}: {}", userId, e.getMessage(), e);
            }
        }
        return out;
    }

    @Cacheable(
            value = TRANSACTIONS,
            key = "'user:' + #userId + ':skip:' + #skip + ':limit:' + #limit",
            unless = "#result == null || #result.isEmpty()")
    public List<TransactionTable> findByUserId(
            final String userId, final int skip, final int limit) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }
        int adjustedSkip = skip;
        if (adjustedSkip < 0) {
            adjustedSkip = 0;
        }
        final int defaultLimit = 50;
        final int maxLimit = 100;
        int adjustedLimit = limit;
        if (adjustedLimit <= 0) {
            adjustedLimit = defaultLimit;
        }
        if (adjustedLimit > maxLimit) {
            adjustedLimit = maxLimit; // Max limit for performance
        }

        final List<TransactionTable> results = new ArrayList<>();
        // CRITICAL: Deduplicate by both transactionId and plaidTransactionId
        // Use Set to track seen transactionIds and plaidTransactionIds to prevent duplicates
        // CRITICAL FIX: Deduplication must happen BEFORE skip check to ensure all items are tracked
        final Set<String> seenTransactionIds = new HashSet<>();
        final Set<String> seenPlaidTransactionIds = new HashSet<>();
        int duplicateCount = 0;
        int uniqueItemCount = 0; // Track unique items (after deduplication) for skip logic

        try {
            final SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>>
                    pages =
                            userIdDateIndex.query(
                                    QueryConditional.keyEqualTo(
                                            Key.builder().partitionValue(userId).build()));
            for (final software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable> page :
                    pages) {
                for (final TransactionTable item : page.items()) {
                    // CRITICAL FIX: Check for duplicates FIRST, before skip logic
                    // This ensures all items are tracked in seen sets, preventing duplicates
                    // from appearing after skip point
                    final String transactionId = item.getTransactionId();
                    final String plaidTransactionId = item.getPlaidTransactionId();
                    boolean isDuplicate = false;

                    // Check for duplicates by transactionId
                    if (transactionId != null && seenTransactionIds.contains(transactionId)) {
                        duplicateCount++;
                        if (LOGGER.isWarnEnabled()) {
                            LOGGER.warn(
                                    "Duplicate transaction detected by transactionId and filtered: transactionId={}, plaidTransactionId={}, description={}",
                                    transactionId,
                                    plaidTransactionId,
                                    item.getDescription());
                        }
                        isDuplicate = true;
                    }

                    // Check for duplicates by plaidTransactionId (if present)
                    if (!isDuplicate
                            && plaidTransactionId != null
                            && !plaidTransactionId.isEmpty()) {
                        if (seenPlaidTransactionIds.contains(plaidTransactionId)) {
                            duplicateCount++;
                            if (LOGGER.isWarnEnabled()) {
                                LOGGER.warn(
                                        "Duplicate transaction detected by plaidTransactionId and filtered: transactionId={}, plaidTransactionId={}, description={}",
                                        transactionId,
                                        plaidTransactionId,
                                        item.getDescription());
                            }
                            isDuplicate = true;
                        }
                    }

                    // If duplicate, skip to next item (but continue tracking in seen sets if
                    // needed)
                    if (isDuplicate) {
                        continue;
                    }

                    // Transaction is unique - track it in seen sets
                    if (transactionId != null) {
                        seenTransactionIds.add(transactionId);
                    }
                    if (plaidTransactionId != null && !plaidTransactionId.isEmpty()) {
                        seenPlaidTransactionIds.add(plaidTransactionId);
                    }

                    // Now apply skip logic after deduplication
                    if (uniqueItemCount >= adjustedSkip) {
                        // Add to results (this is after skip point and unique)
                        results.add(item);
                        if (results.size() >= adjustedLimit) {
                            if (duplicateCount > 0) {
                                if (LOGGER.isInfoEnabled()) {
                                    LOGGER.info(
                                            "findByUserId({}): Filtered {} duplicate transactions, returning {} unique transactions",
                                            userId,
                                            duplicateCount,
                                            results.size());
                                }
                            }
                            return results;
                        }
                    }
                    uniqueItemCount++; // Count unique items for skip logic
                }
            }
        } catch (ResourceNotFoundException e) {
            // GSI not available - fallback to scan and filter in memory
            LOGGER.warn(
                    "UserIdDateIndex GSI not found for userId {}. Falling back to scan and filtering in memory.",
                    userId);
            try {
                // Fallback: scan table and filter by userId
                final SdkIterable<
                                software.amazon.awssdk.enhanced.dynamodb.model.Page<
                                        TransactionTable>>
                        scanPages = transactionTable.scan();
                for (final software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>
                        page : scanPages) {
                    for (final TransactionTable item : page.items()) {
                        if (item == null || !userId.equals(item.getUserId())) {
                            continue;
                        }

                        // Deduplicate
                        final String transactionId = item.getTransactionId();
                        final String plaidTransactionId = item.getPlaidTransactionId();
                        boolean isDuplicate = false;

                        if (transactionId != null && seenTransactionIds.contains(transactionId)) {
                            duplicateCount++;
                            isDuplicate = true;
                        }
                        if (!isDuplicate
                                && plaidTransactionId != null
                                && !plaidTransactionId.isEmpty()
                                && seenPlaidTransactionIds.contains(plaidTransactionId)) {
                            duplicateCount++;
                            isDuplicate = true;
                        }

                        if (!isDuplicate) {
                            if (transactionId != null) {
                                seenTransactionIds.add(transactionId);
                            }
                            if (plaidTransactionId != null && !plaidTransactionId.isEmpty()) {
                                seenPlaidTransactionIds.add(plaidTransactionId);
                            }

                            // Apply skip logic
                            if (uniqueItemCount >= adjustedSkip) {
                                results.add(item);
                                if (results.size() >= adjustedLimit) {
                                    if (duplicateCount > 0) {
                                        if (LOGGER.isInfoEnabled()) {
                                            LOGGER.info(
                                                    "findByUserId({}): Filtered {} duplicate transactions, returning {} unique transactions (fallback scan)",
                                                    userId,
                                                    duplicateCount,
                                                    results.size());
                                        }
                                    }
                                    return results;
                                }
                            }
                            uniqueItemCount++;
                        }
                    }
                }
            } catch (Exception fallbackException) {
                if (LOGGER.isErrorEnabled()) {
                    LOGGER.error(
                            "Error in fallback scan for userId {}: {}",
                            userId,
                            fallbackException.getMessage(),
                            fallbackException);
                }
                // Return empty list if fallback also fails
                return List.of();
            }
        } catch (RuntimeException e) {
            // Check if the RuntimeException wraps a ResourceNotFoundException
            final Throwable cause = e.getCause();
            final String errorMessage =
                    e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";
            if (cause instanceof ResourceNotFoundException
                    || errorMessage.contains(INDEX_NOT_FOUND)
                    || errorMessage.contains(RESOURCE_NOT_FOUND)) {
                // GSI not available - fallback to scan and filter in memory
                LOGGER.warn(
                        "UserIdDateIndex GSI not found for userId {} (wrapped exception). Falling back to scan and filtering in memory.",
                        userId);
                try {
                    // Fallback: scan table and filter by userId
                    final SdkIterable<
                                    software.amazon.awssdk.enhanced.dynamodb.model.Page<
                                            TransactionTable>>
                            scanPages = transactionTable.scan();
                    for (software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>
                            page : scanPages) {
                        for (final TransactionTable item : page.items()) {
                            if (item == null || !userId.equals(item.getUserId())) {
                                continue;
                            }

                            // Deduplicate
                            final String transactionId = item.getTransactionId();
                            final String plaidTransactionId = item.getPlaidTransactionId();
                            boolean isDuplicate = false;

                            if (transactionId != null
                                    && seenTransactionIds.contains(transactionId)) {
                                duplicateCount++;
                                isDuplicate = true;
                            }
                            if (!isDuplicate
                                    && plaidTransactionId != null
                                    && !plaidTransactionId.isEmpty()
                                    && seenPlaidTransactionIds.contains(plaidTransactionId)) {
                                duplicateCount++;
                                isDuplicate = true;
                            }

                            if (!isDuplicate) {
                                if (transactionId != null) {
                                    seenTransactionIds.add(transactionId);
                                }
                                if (plaidTransactionId != null && !plaidTransactionId.isEmpty()) {
                                    seenPlaidTransactionIds.add(plaidTransactionId);
                                }

                                // Apply skip logic
                                if (uniqueItemCount >= adjustedSkip) {
                                    results.add(item);
                                    if (results.size() >= adjustedLimit) {
                                        if (duplicateCount > 0) {
                                            if (LOGGER.isInfoEnabled()) {
                                                LOGGER.info(
                                                        "findByUserId({}): Filtered {} duplicate transactions, returning {} unique transactions (fallback scan)",
                                                        userId,
                                                        duplicateCount,
                                                        results.size());
                                            }
                                        }
                                        return results;
                                    }
                                }
                                uniqueItemCount++;
                            }
                        }
                    }
                } catch (Exception fallbackException) {
                    if (LOGGER.isErrorEnabled()) {
                        LOGGER.error(
                                "Error in fallback scan for userId {}: {}",
                                userId,
                                fallbackException.getMessage(),
                                fallbackException);
                    }
                    // Return empty list if fallback also fails
                    return List.of();
                }
            } else {
                // Re-throw if it's not a ResourceNotFoundException
                throw e;
            }
        }

        if (duplicateCount > 0) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "findByUserId({}): Filtered {} duplicate transactions, returning {} unique transactions",
                        userId,
                        duplicateCount,
                        results.size());
            }
        }
        return results;
    }

    /**
     * Find all transactions for a user that are assigned to a specific goal Uses UserIdGoalIdIndex
     * GSI for efficient querying
     */
    public List<TransactionTable> findByUserIdAndGoalId(final String userId, final String goalId) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }
        if (goalId == null || goalId.isEmpty()) {
            return List.of();
        }

        try {
            final List<TransactionTable> results = new ArrayList<>();
            final QueryConditional queryConditional =
                    QueryConditional.keyEqualTo(
                            Key.builder().partitionValue(userId).sortValue(goalId).build());

            final SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>>
                    pages = userIdGoalIdIndex.query(queryConditional);
            for (final software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable> page :
                    pages) {
                results.addAll(page.items());
            }
            return results;
        } catch (ResourceNotFoundException e) {
            // GSI not available - fallback to filtering in memory
            LOGGER.warn("UserIdGoalIdIndex GSI not found. Falling back to filtering in memory.");
            return findByUserIdCapped(userId).stream()
                    .filter(tx -> goalId.equals(tx.getGoalId()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error querying transactions by userId and goalId: {}", e.getMessage(), e);
            }
            // Fallback to filtering in memory
            return findByUserIdCapped(userId).stream()
                    .filter(tx -> goalId.equals(tx.getGoalId()))
                    .collect(Collectors.toList());
        }
    }

    private static final java.util.regex.Pattern ISO_DATE =
            java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final int DATE_RANGE_SAFETY_LIMIT = 1000;
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(TransactionRepository.class);

    /**
     * Find unique transactions for a user within [startDate, endDate] inclusive.
     *
     * <p>Primary path pushes the date range into the {@code UserIdDateIndex} GSI sort key ({@code
     * transactionDate}) via {@code sortBetween}, so DynamoDB returns only matching items instead of
     * a full per-user scan. Deduplication across both {@code transactionId} and {@code
     * plaidTransactionId} is applied because the rebuild-from-Plaid pipeline has historically been
     * able to produce duplicates on the write path.
     *
     * <p>Fallback path (GSI missing during migration / local dev) queries the base table for the
     * user and filters in memory.
     */
    public List<TransactionTable> findByUserIdAndDateRange(
            final String userId, final String startDate, final String endDate) {
        if (userId == null || userId.isEmpty() || startDate == null || endDate == null) {
            return List.of();
        }
        if (!ISO_DATE.matcher(startDate).matches() || !ISO_DATE.matcher(endDate).matches()) {
            throw new IllegalArgumentException("Invalid date format. Expected YYYY-MM-DD");
        }

        try {
            return queryGsiForDateRange(userId, startDate, endDate);
        } catch (ResourceNotFoundException e) {
            return fallbackScanForDateRange(userId, startDate, endDate);
        } catch (RuntimeException e) {
            if (isMissingIndex(e)) {
                return fallbackScanForDateRange(userId, startDate, endDate);
            }
            throw e;
        }
    }

    /** Pushes the date range into the GSI sort key via {@code sortBetween}. */
    private List<TransactionTable> queryGsiForDateRange(
            final String userId, final String startDate, final String endDate) {
        final QueryConditional range =
                QueryConditional.sortBetween(
                        Key.builder().partitionValue(userId).sortValue(startDate).build(),
                        Key.builder().partitionValue(userId).sortValue(endDate).build());

        final TransactionDeduper deduper = new TransactionDeduper();
        final List<TransactionTable> results = new ArrayList<>();

        final SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>>
                pages = userIdDateIndex.query(range);
        for (final software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable> page :
                pages) {
            for (final TransactionTable t : page.items()) {
                if (t == null || !deduper.admit(t)) {
                    continue;
                }
                results.add(t);
                if (results.size() >= DATE_RANGE_SAFETY_LIMIT) {
                    if (LOG.isWarnEnabled()) {
                        LOG.warn(
                                "Reached safety limit of {} transactions for user {} in {}..{} ({} duplicates filtered)",
                                DATE_RANGE_SAFETY_LIMIT,
                                userId,
                                startDate,
                                endDate,
                                deduper.duplicateCount());
                    }
                    return results;
                }
            }
        }
        if (deduper.duplicateCount() > 0) {
            if (LOG.isInfoEnabled()) {
                LOG.info(
                        "findByUserIdAndDateRange({}, {}..{}): filtered {} duplicates, returning {}",
                        userId,
                        startDate,
                        endDate,
                        deduper.duplicateCount(),
                        results.size());
            }
        }
        return results;
    }

    /** Fallback when the GSI is unavailable — reads all user transactions and filters. */
    private List<TransactionTable> fallbackScanForDateRange(
            final String userId, final String startDate, final String endDate) {
        LOG.warn(
                "UserIdDateIndex GSI not found for userId {}; falling back to base-table scan.",
                userId);
        try {
            final List<TransactionTable> all = findByUserIdCapped(userId);
            final TransactionDeduper deduper = new TransactionDeduper();
            final List<TransactionTable> results = new ArrayList<>();
            for (final TransactionTable t : all) {
                if (t == null || !deduper.admit(t)) {
                    continue;
                }
                final String d = t.getTransactionDate();
                if (d != null && d.compareTo(startDate) >= 0 && d.compareTo(endDate) <= 0) {
                    results.add(t);
                }
            }
            return results;
        } catch (Exception fallbackException) {
            if (LOG.isErrorEnabled()) {
                LOG.error(
                        ERROR_IN_FALLBACK_QUERY_FOR_USERID,
                        userId,
                        fallbackException.getMessage(),
                        fallbackException);
            }
            return List.of();
        }
    }

    private static boolean isMissingIndex(final RuntimeException e) {
        if (e.getCause() instanceof ResourceNotFoundException) {
            return true;
        }
        final String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        final String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains(INDEX_NOT_FOUND) || lower.contains(RESOURCE_NOT_FOUND);
    }

    /**
     * Tracks seen {@code transactionId} and {@code plaidTransactionId} values so a transaction
     * surfaced twice (GSI projection lag, double-write races) is only emitted once per query. Kept
     * package-private for unit testing.
     */
    /* default */ static final class TransactionDeduper {
        private final Set<String> seenTxIds = new HashSet<>();
        private final Set<String> seenPlaidIds = new HashSet<>();
        private int duplicates;

        /** Returns {@code true} if the transaction is new, {@code false} if already seen. */
        /* default */ boolean admit(final TransactionTable t) {
            final String txId = t.getTransactionId();
            final String plaidId = t.getPlaidTransactionId();
            if (txId != null && !seenTxIds.add(txId)) {
                duplicates++;
                if (LOG.isWarnEnabled()) {
                    LOG.warn(
                            "Duplicate transaction by transactionId={} (plaidTxId={}, date={})",
                            txId,
                            plaidId,
                            t.getTransactionDate());
                }
                return false;
            }
            if (plaidId != null && !plaidId.isEmpty() && !seenPlaidIds.add(plaidId)) {
                duplicates++;
                if (LOG.isWarnEnabled()) {
                    LOG.warn(
                            "Duplicate transaction by plaidTransactionId={} (txId={}, date={})",
                            plaidId,
                            txId,
                            t.getTransactionDate());
                }
                return false;
            }
            return true;
        }

        /* default */ int duplicateCount() {
            return duplicates;
        }
    }

    /*
     * Find transactions updated after a specific timestamp using GSI Optimized for incremental sync
     * - queries only changed items
     */
    /**
     * CRITICAL: Do NOT cache this method - incremental sync queries must always be fresh to handle
     * DynamoDB GSI eventual consistency. Caching empty results would prevent finding updated items
     * until cache expires.
     */
    public List<TransactionTable> findByUserIdAndUpdatedAfter(
            final String userId, final Long updatedAfterTimestamp, final int limit) {
        if (userId == null || userId.isEmpty() || updatedAfterTimestamp == null) {
            return List.of();
        }

        int adjustedLimit = limit;
        if (adjustedLimit <= 0) {
            adjustedLimit = 50; // Default limit
        }
        if (adjustedLimit > 100) {
            adjustedLimit = 100; // Max limit
        }

        final List<TransactionTable> results = new ArrayList<>();
        try {
            // CRITICAL FIX: Cannot use filter expression on sort key (updatedAtTimestamp is GSI
            // sort key)
            // Query all items for user, then filter in application code
            // This is still efficient because we're using the GSI partition key
            final SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>>
                    pages =
                            userIdUpdatedAtIndex.query(
                                    QueryConditional.keyEqualTo(
                                            Key.builder().partitionValue(userId).build()));

            int count = 0;
            for (final software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable> page :
                    pages) {
                for (final TransactionTable item : page.items()) {
                    // Filter in application code: updatedAtTimestamp >= updatedAfterTimestamp
                    // Use >= to include items updated exactly at the timestamp
                    if (item.getUpdatedAtTimestamp() != null
                            && item.getUpdatedAtTimestamp() >= updatedAfterTimestamp) {
                        if (count >= adjustedLimit) {
                            break;
                        }
                        results.add(item);
                        count++;
                    }
                }
                if (count >= adjustedLimit) {
                    break;
                }
            }
        } catch (ResourceNotFoundException e) {
            // GSI not available - fallback to findByUserId and filter in memory
            LOGGER.warn(
                    "UserIdUpdatedAtIndex GSI not found for userId {}. Falling back to findByUserId and filtering in memory.",
                    userId);
            try {
                final List<TransactionTable> allTransactions = findByUserIdCapped(userId);
                int count = 0;
                for (final TransactionTable transaction : allTransactions) {
                    if (transaction.getUpdatedAtTimestamp() != null
                            && transaction.getUpdatedAtTimestamp() >= updatedAfterTimestamp) {
                        if (count >= adjustedLimit) {
                            break;
                        }
                        results.add(transaction);
                        count++;
                    }
                }
            } catch (Exception fallbackException) {
                if (LOGGER.isErrorEnabled()) {
                    LOGGER.error(
                            ERROR_IN_FALLBACK_QUERY_FOR_USERID,
                            userId,
                            fallbackException.getMessage(),
                            fallbackException);
                }
            }
        } catch (RuntimeException e) {
            // Check if the RuntimeException wraps a ResourceNotFoundException or indicates missing
            // index
            final Throwable cause = e.getCause();
            final String errorMessage =
                    e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";
            if (cause instanceof ResourceNotFoundException
                    || errorMessage.contains(INDEX_NOT_FOUND)
                    || errorMessage.contains(RESOURCE_NOT_FOUND)) {
                // GSI not available - fallback to findByUserId and filter in memory
                LOGGER.warn(
                        "UserIdUpdatedAtIndex GSI not found for userId {} (wrapped exception). Falling back to findByUserId and filtering in memory.",
                        userId);
                try {
                    final List<TransactionTable> allTransactions = findByUserIdCapped(userId);
                    int count = 0;
                    for (final TransactionTable transaction : allTransactions) {
                        if (transaction.getUpdatedAtTimestamp() != null
                                && transaction.getUpdatedAtTimestamp() >= updatedAfterTimestamp) {
                            if (count >= adjustedLimit) {
                                break;
                            }
                            results.add(transaction);
                            count++;
                        }
                    }
                } catch (Exception fallbackException) {
                    if (LOGGER.isErrorEnabled()) {
                        LOGGER.error(
                                ERROR_IN_FALLBACK_QUERY_FOR_USERID,
                                userId,
                                fallbackException.getMessage(),
                                fallbackException);
                    }
                    // Return empty list if fallback fails
                    return results;
                }
            } else {
                // Not a ResourceNotFoundException - log as error
                if (LOGGER.isErrorEnabled()) {
                    LOGGER.error(
                            "Error finding transactions by userId and updatedAfter {}: {}",
                            userId,
                            e.getMessage(),
                            e);
                }
            }
        } catch (Exception e) {
            // Check if the exception message indicates missing index
            final String errorMessage =
                    e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";
            if (errorMessage.contains(INDEX_NOT_FOUND)
                    || errorMessage.contains(RESOURCE_NOT_FOUND)) {
                // GSI not available - fallback to findByUserId and filter in memory
                LOGGER.warn(
                        "UserIdUpdatedAtIndex GSI not found for userId {} (detected from error message). Falling back to findByUserId and filtering in memory.",
                        userId);
                try {
                    final List<TransactionTable> allTransactions = findByUserIdCapped(userId);
                    int count = 0;
                    for (final TransactionTable transaction : allTransactions) {
                        if (transaction.getUpdatedAtTimestamp() != null
                                && transaction.getUpdatedAtTimestamp() >= updatedAfterTimestamp) {
                            if (count >= adjustedLimit) {
                                break;
                            }
                            results.add(transaction);
                            count++;
                        }
                    }
                } catch (Exception fallbackException) {
                    if (LOGGER.isErrorEnabled()) {
                        LOGGER.error(
                                ERROR_IN_FALLBACK_QUERY_FOR_USERID,
                                userId,
                                fallbackException.getMessage(),
                                fallbackException);
                    }
                    // Return empty list if fallback fails
                    return results;
                }
            } else {
                if (LOGGER.isErrorEnabled()) {
                    LOGGER.error(
                            "Error finding transactions by userId and updatedAfter {}: {}",
                            userId,
                            e.getMessage(),
                            e);
                }
            }
        }

        return results;
    }

    public Optional<TransactionTable> findByPlaidTransactionId(final String plaidTransactionId) {
        if (plaidTransactionId == null || plaidTransactionId.isEmpty()) {
            return Optional.empty();
        }
        final SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>>
                pages =
                        plaidTransactionIdIndex.query(
                                QueryConditional.keyEqualTo(
                                        Key.builder().partitionValue(plaidTransactionId).build()));
        for (final software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable> page :
                pages) {
            for (final TransactionTable item : page.items()) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    @CacheEvict(value = TRANSACTIONS, allEntries = true)
    public void delete(final String transactionId) {
        if (transactionId == null || transactionId.isEmpty()) {
            throw new IllegalArgumentException("Transaction ID cannot be null or empty");
        }
        transactionTable.deleteItem(Key.builder().partitionValue(transactionId).build());
    }

    /**
     * Save transaction only if it doesn't exist (conditional write) Prevents duplicate transactions
     * (deduplication)
     */
    public boolean saveIfNotExists(final TransactionTable transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException(TRANSACTION_CANNOT_BE_NULL);
        }
        if (transaction.getTransactionId() == null || transaction.getTransactionId().isEmpty()) {
            throw new IllegalArgumentException("Transaction ID is required");
        }

        try {
            transactionTable.putItem(
                    software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest.builder(
                                    TransactionTable.class)
                            .item(transaction)
                            .conditionExpression(
                                    Expression.builder()
                                            .expression("attribute_not_exists(transactionId)")
                                            .build())
                            .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            // MEDIUM PRIORITY: Conditional check failure indicates transaction already exists
            // Note: Full monitoring integration should be done at service layer with dependency
            // injection
            // This is expected behavior for idempotent operations, so no logging needed here
            return false; // Transaction already exists
        }
    }

    /**
     * Find transaction by composite key (accountId + amount + date + description/merchantName) Used
     * as fallback when plaidTransactionId changes due to reconnection/relinking PERFORMANCE FIX:
     * Uses date range query to limit search scope instead of loading all transactions
     *
     * @param accountId Account ID
     * @param amount Transaction amount
     * @param transactionDate Transaction date (YYYY-MM-DD)
     * @param description Transaction description (or merchantName if description is null)
     * @param userId User ID (for scoping)
     * @return Optional transaction if found
     */
    public Optional<TransactionTable> findByCompositeKey(
            final String accountId,
            final java.math.BigDecimal amount,
            final String transactionDate,
            final String description,
            final String userId) {
        if (accountId == null
                || accountId.isEmpty()
                || amount == null
                || transactionDate == null
                || transactionDate.isEmpty()
                || userId == null
                || userId.isEmpty()) {
            return Optional.empty();
        }

        // PERFORMANCE FIX: Use date range query to limit search scope
        // Search within ±1 day of the transaction date to handle timezone differences
        // This is much more efficient than loading all transactions for the user
        try {
            final LocalDate date =
                    LocalDate.parse(
                            transactionDate, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
            final String startDate =
                    date.minusDays(1).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
            final String endDate =
                    date.plusDays(1).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);

            // Query transactions in date range (much more efficient than loading all)
            final List<TransactionTable> dateRangeTransactions =
                    findByUserIdAndDateRange(userId, startDate, endDate);

            // Filter by accountId, amount, exact date, and description/merchantName
            return dateRangeTransactions.stream()
                    .filter(t -> t != null && accountId.equals(t.getAccountId()))
                    .filter(t -> amount.compareTo(t.getAmount()) == 0)
                    .filter(t -> transactionDate.equals(t.getTransactionDate())) // Exact date match
                    .filter(
                            t -> {
                                // Match by description or merchantName
                                if (description != null && !description.isEmpty()) {
                                    final String txDescription = t.getDescription();
                                    final String txMerchant = t.getMerchantName();
                                    // Match if description matches or merchantName matches
                                    return (txDescription != null
                                                    && txDescription.equals(description))
                                            || (txMerchant != null
                                                    && txMerchant.equals(description));
                                }
                                return true; // If description is null, don't filter by it
                            })
                    .findFirst();
        } catch (Exception e) {
            // Fallback to original method if date parsing fails
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Failed to parse date for composite key search, falling back to full user query: {}",
                        e.getMessage());
            }

            // Get all transactions for this user and account (fallback)
            final List<TransactionTable> userTransactions = findByUserIdCapped(userId);

            return userTransactions.stream()
                    .filter(t -> t != null && accountId.equals(t.getAccountId()))
                    .filter(t -> amount.compareTo(t.getAmount()) == 0)
                    .filter(t -> transactionDate.equals(t.getTransactionDate()))
                    .filter(
                            t -> {
                                // Match by description or merchantName
                                if (description != null && !description.isEmpty()) {
                                    final String txDescription = t.getDescription();
                                    final String txMerchant = t.getMerchantName();
                                    // Match if description matches or merchantName matches
                                    return (txDescription != null
                                                    && txDescription.equals(description))
                                            || (txMerchant != null
                                                    && txMerchant.equals(description));
                                }
                                return true; // If description is null, don't filter by it
                            })
                    .findFirst();
        }
    }

    /**
     * Save transaction only if Plaid transaction ID doesn't exist (conditional write) Prevents
     * duplicate Plaid transactions
     *
     * <p>CRITICAL FIX: DynamoDB condition expressions only check attributes on the specific item
     * being written (keyed by transactionId). Since each new transaction has a unique
     * transactionId, attribute_not_exists(plaidTransactionId) will always pass for new items, even
     * if another item with a different transactionId already has the same plaidTransactionId.
     *
     * <p>Solution: First check the GSI to see if a transaction with the same plaidTransactionId
     * exists. If it does, return false. If it doesn't, use conditional write with
     * attribute_not_exists(transactionId) to prevent overwrites.
     *
     * <p>Note: There's still a small TOCTOU window between the GSI check and the write, but it's
     * much smaller than before. For true atomicity, use DynamoDB Transactions (TransactWriteItems).
     */
    public boolean saveIfPlaidTransactionNotExists(final TransactionTable transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException(TRANSACTION_CANNOT_BE_NULL);
        }

        // Ensure transactionId is set (required for primary key)
        if (transaction.getTransactionId() == null || transaction.getTransactionId().isEmpty()) {
            throw new IllegalArgumentException("Transaction ID is required");
        }

        if (transaction.getPlaidTransactionId() == null
                || transaction.getPlaidTransactionId().isEmpty()) {
            // If no Plaid ID, use regular save with transactionId check only
            return saveIfNotExists(transaction);
        }

        // CRITICAL FIX: Check GSI first to see if plaidTransactionId already exists
        // DynamoDB condition expressions can't check across items, so we must check the GSI
        final Optional<TransactionTable> existing =
                findByPlaidTransactionId(transaction.getPlaidTransactionId());
        if (existing.isPresent()) {
            // Plaid transaction ID already exists
            return false;
        }

        // Plaid transaction ID doesn't exist, use conditional write to prevent overwrites
        // and minimize TOCTOU window
        try {
            // Use conditional write to prevent overwriting existing transaction with same
            // transactionId
            transactionTable.putItem(
                    software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest.builder(
                                    TransactionTable.class)
                            .item(transaction)
                            .conditionExpression(
                                    Expression.builder()
                                            .expression("attribute_not_exists(transactionId)")
                                            .build())
                            .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            // Transaction with same transactionId already exists
            return false;
        }
    }

    /**
     * Batch save transactions using BatchWriteItem (cost-optimized). DynamoDB
     * allows up to 25 items per batch.
     *
     * <p>Persists EVERY {@code @DynamoDbAttribute}-annotated field via the
     * enhanced-client schema mapping — including FX, wallet, geo, and all
     * other side fields added after the original {@code batchSave} was
     * written. Pre-fix this method silently persisted only
     * {transactionId, userId, transactionDate, amount} which is exactly
     * the bug the per-product audit surfaced for the PDF import path.
     *
     * <p>Used by {@code TransactionController.processPDFBatchImport} to
     * drop a 100-tx import from ~200 DDB calls (createTransaction +
     * updateTransaction per row) to 8 calls (25-tx batches). Retries
     * unprocessed items with exponential backoff via {@code RetryHelper}.
     */
    @CacheEvict(value = TRANSACTIONS, allEntries = true)
    public void batchSave(final List<TransactionTable> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return;
        }

        // DynamoDB batch write limit is 25 items per request.
        final int batchSize = 25;
        final software.amazon.awssdk.enhanced.dynamodb.TableSchema<TransactionTable> schema =
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(
                        TransactionTable.class);

        for (int i = 0; i < transactions.size(); i += batchSize) {
            final List<TransactionTable> batch =
                    transactions.subList(i, Math.min(i + batchSize, transactions.size()));
            final List<WriteRequest> writeRequests =
                    batch.stream()
                            .map(t -> WriteRequest.builder()
                                    .putRequest(PutRequest.builder()
                                            // itemToMap(t, true) = ignore null
                                            // attributes. Optional fields
                                            // (FX context, geo, wallet) that
                                            // are unset on this tx won't get
                                            // written, which preserves any
                                            // prior value if a re-import
                                            // ever races (today's main path
                                            // is first-write-only so this is
                                            // mostly cosmetic).
                                            .item(schema.itemToMap(t, true))
                                            .build())
                                    .build())
                            .collect(Collectors.toList());

            final Map<String, List<WriteRequest>> requestItems = new HashMap<>();
            requestItems.put(this.tableName, writeRequests);

            final BatchWriteItemRequest batchRequest =
                    BatchWriteItemRequest.builder().requestItems(requestItems).build();

            com.budgetbuddy.util.RetryHelper.executeWithRetry(
                    () -> {
                        final BatchWriteItemResponse resp =
                                dynamoDbClient.batchWriteItem(batchRequest);
                        if (!resp.unprocessedItems().isEmpty()) {
                            // BatchWriteItem returns unprocessed items on
                            // throttling. Throwing here triggers RetryHelper's
                            // exponential backoff. Anything still unprocessed
                            // after the retry budget is a real failure.
                            throw new AppException(
                                    ErrorCode.INTERNAL_SERVER_ERROR,
                                    "Unprocessed items in batch write: "
                                            + resp.unprocessedItems().size());
                        }
                        return resp;
                    });
        }
    }

    /**
     * Batch read transactions by IDs using BatchGetItem (cost-optimized) DynamoDB allows up to 100
     * items per batch
     */
    public List<TransactionTable> batchFindByIds(final List<String> transactionIds) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            return List.of();
        }

        // DynamoDB batch get limit is 100 items per request
        final int batchSize = 100;
        final List<TransactionTable> results = new ArrayList<>();

        for (int i = 0; i < transactionIds.size(); i += batchSize) {
            final List<String> batch =
                    transactionIds.subList(i, Math.min(i + batchSize, transactionIds.size()));

            final List<Map<String, AttributeValue>> keys =
                    batch.stream()
                            .map(
                                    id -> {
                                        final Map<String, AttributeValue> key = new HashMap<>();
                                        key.put(
                                                TRANSACTIONID,
                                                AttributeValue.builder().s(id).build());
                                        return key;
                                    })
                            .collect(Collectors.toList());

            final KeysAndAttributes keysAndAttributes =
                    KeysAndAttributes.builder().keys(keys).build();

            final Map<String, KeysAndAttributes> requestItems = new HashMap<>();
            requestItems.put(this.tableName, keysAndAttributes);

            final BatchGetItemRequest batchRequest =
                    BatchGetItemRequest.builder().requestItems(requestItems).build();

            final BatchGetItemResponse response = dynamoDbClient.batchGetItem(batchRequest);

            // Convert response items to TransactionTable objects
            if (response.responses() != null && response.responses().containsKey(this.tableName)) {
                final List<Map<String, AttributeValue>> items =
                        response.responses().get(this.tableName);
                for (final Map<String, AttributeValue> item : items) {
                    // Use proper conversion method to fully populate all fields
                    final TransactionTable transaction =
                            convertAttributeValueMapToTransaction(item);
                    results.add(transaction);
                }
            }

            // Retry if there are unprocessed keys
            if (!response.unprocessedKeys().isEmpty()) {
                // Retry unprocessed keys with exponential backoff
                final Map<String, KeysAndAttributes> unprocessed = response.unprocessedKeys();
                final BatchGetItemRequest retryRequest =
                        BatchGetItemRequest.builder().requestItems(unprocessed).build();

                final BatchGetItemResponse retryResponse =
                        com.budgetbuddy.util.RetryHelper.executeWithRetry(
                                () -> {
                                    final BatchGetItemResponse resp =
                                            dynamoDbClient.batchGetItem(retryRequest);
                                    if (!resp.unprocessedKeys().isEmpty()) {
                                        throw new AppException(
                                                ErrorCode.INTERNAL_SERVER_ERROR,
                                                "Unprocessed keys in batch read");
                                    }
                                    return resp;
                                });

                // Process retry response items
                if (retryResponse.responses() != null
                        && retryResponse.responses().containsKey(this.tableName)) {
                    final List<Map<String, AttributeValue>> retryItems =
                            retryResponse.responses().get(this.tableName);
                    for (final Map<String, AttributeValue> item : retryItems) {
                        final TransactionTable transaction =
                                convertAttributeValueMapToTransaction(item);
                        results.add(transaction);
                    }
                }
            }
        }

        return results;
    }

    /** Batch delete transactions using BatchWriteItem */
    @CacheEvict(value = TRANSACTIONS, allEntries = true)
    public void batchDelete(final List<String> transactionIds) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            return;
        }

        final int batchSize = 25;
        for (int i = 0; i < transactionIds.size(); i += batchSize) {
            final List<String> batch =
                    transactionIds.subList(i, Math.min(i + batchSize, transactionIds.size()));

            final List<WriteRequest> writeRequests =
                    batch.stream()
                            .map(
                                    id -> {
                                        final Map<String, AttributeValue> key = new HashMap<>();
                                        key.put(
                                                TRANSACTIONID,
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
     * Convert AttributeValue map to TransactionTable Optimized conversion from DynamoDB
     * AttributeValue maps to domain objects
     */
    private TransactionTable convertAttributeValueMapToTransaction(
            final Map<String, AttributeValue> item) {
        final TransactionTable transaction = new TransactionTable();

        if (item.containsKey(TRANSACTIONID)) {
            transaction.setTransactionId(item.get(TRANSACTIONID).s());
        }
        if (item.containsKey("userId")) {
            transaction.setUserId(item.get("userId").s());
        }
        if (item.containsKey("accountId")) {
            transaction.setAccountId(item.get("accountId").s());
        }
        if (item.containsKey("amount")) {
            final String amountStr = item.get("amount").n();
            if (amountStr != null) {
                transaction.setAmount(new java.math.BigDecimal(amountStr));
            }
        }
        if (item.containsKey("description")) {
            transaction.setDescription(item.get("description").s());
        }
        if (item.containsKey("merchantName")) {
            transaction.setMerchantName(item.get("merchantName").s());
        }
        // Handle category structure (categoryPrimary, categoryDetailed)
        if (item.containsKey("categoryPrimary")) {
            transaction.setCategoryPrimary(item.get("categoryPrimary").s());
        }
        if (item.containsKey("categoryDetailed")) {
            transaction.setCategoryDetailed(item.get("categoryDetailed").s());
        }
        // Support both old field names (for backward compatibility during migration) and new field
        // names
        if (item.containsKey("importerCategoryPrimary")) {
            transaction.setImporterCategoryPrimary(item.get("importerCategoryPrimary").s());
        } else if (item.containsKey("plaidCategoryPrimary")) {
            // Backward compatibility: migrate old field name to new field name
            transaction.setImporterCategoryPrimary(item.get("plaidCategoryPrimary").s());
        }
        if (item.containsKey("importerCategoryDetailed")) {
            transaction.setImporterCategoryDetailed(item.get("importerCategoryDetailed").s());
        } else if (item.containsKey("plaidCategoryDetailed")) {
            // Backward compatibility: migrate old field name to new field name
            transaction.setImporterCategoryDetailed(item.get("plaidCategoryDetailed").s());
        }
        if (item.containsKey("categoryOverridden")) {
            transaction.setCategoryOverridden(item.get("categoryOverridden").bool());
        }
        if (item.containsKey("transactionDate")) {
            transaction.setTransactionDate(item.get("transactionDate").s());
        }
        if (item.containsKey("currencyCode")) {
            transaction.setCurrencyCode(item.get("currencyCode").s());
        }
        if (item.containsKey("plaidTransactionId")) {
            transaction.setPlaidTransactionId(item.get("plaidTransactionId").s());
        }
        if (item.containsKey("pending")) {
            transaction.setPending(item.get("pending").bool());
        }
        if (item.containsKey("createdAt")) {
            final String timestamp = item.get("createdAt").n();
            if (timestamp != null) {
                transaction.setCreatedAt(Instant.ofEpochSecond(Long.parseLong(timestamp)));
            }
        }
        if (item.containsKey("updatedAt")) {
            final String timestamp = item.get("updatedAt").n();
            if (timestamp != null) {
                transaction.setUpdatedAt(Instant.ofEpochSecond(Long.parseLong(timestamp)));
            }
        }

        return transaction;
    }
}
