package com.budgetbuddy.security.ddos;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

/**
 * Tracks 404 (Not Found) errors and throttles sources that generate excessive 404s This is a DDoS
 * protection mechanism - repeated 404s can indicate: - Scanning/probing attacks - Misconfigured
 * clients - Buggy applications causing infinite retries
 *
 * <p>After N 404s in a time window, the source is throttled/blocked
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
@Service
public class NotFoundErrorTrackingService {

    private static final String BLOCKED_UNTIL = "blockedUntil";

    private static final String SOURCE_ID = "sourceId";

    private static final Logger LOGGER =
            LoggerFactory.getLogger(NotFoundErrorTrackingService.class);

    // Configuration - Configurable via properties for different environments
    @Value("${app.ddos.notfound.enabled:true}")
    private boolean notFoundTrackingEnabled; // Allow disabling 404 tracking in tests

    @Value("${app.ddos.notfound.max-per-minute:500}")
    private int
            max404PerMinute; // Allow up to 500 404s per minute (default, configurable for tests)

    @Value("${app.ddos.notfound.max-per-hour:5000}")
    private int max404PerHour; // Allow up to 5000 404s per hour (default, configurable for tests)

    private static final int BLOCK_DURATION_SECONDS = 3600; // Block for 1 hour after threshold
    private static final long WINDOW_SIZE_MS = 60_000; // 1 minute window
    private static final long CACHE_CLEANUP_INTERVAL_MS = 300_000; // 5 minutes
    private static final int MAX_CACHE_SIZE = 10_000;

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final Executor asyncExecutor; // Thread pool for async operations

    // In-memory cache for hot paths
    private final Map<String, NotFoundCounter> inMemoryCache = new ConcurrentHashMap<>();
    private volatile long lastCacheCleanup = System.currentTimeMillis();
    private volatile boolean dynamoDbAvailable = true;

    public NotFoundErrorTrackingService(
            final DynamoDbClient dynamoDbClient,
            @Value("${app.aws.dynamodb.table-prefix:BudgetBuddy}") final String tablePrefix,
            @Autowired(required = false) @Qualifier("taskExecutor") final Executor asyncExecutor) {
        if (dynamoDbClient == null) {
            throw new IllegalArgumentException("DynamoDbClient cannot be null");
        }
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tablePrefix + "-NotFoundTracking";
        // Use provided executor or fallback to default (for testing)
        this.asyncExecutor =
                asyncExecutor != null
                        ? asyncExecutor
                        : java.util.concurrent.Executors.newCachedThreadPool(
                                r -> {
                                    final Thread t = new Thread(r, "404-block-async");
                                    t.setDaemon(true);
                                    return t;
                                });
        // Initialize table lazily to avoid blocking application startup
        // Table will be created on first use if needed
        try {
            final boolean initialized = initializeTable();
            if (!initialized) {
                LOGGER.debug(
                        "404 tracking table initialization deferred (DynamoDB may be unavailable). "
                                + "Will use in-memory only mode if table creation fails.");
            }
        } catch (Exception e) {
            LOGGER.debug(
                    "404 tracking table initialization failed: {}. "
                            + "Will use in-memory only mode. This is acceptable in test environments.",
                    e.getMessage());
            dynamoDbAvailable = false;
        }
    }

    /**
     * Record a 404 error from a source (IP or user)
     *
     * @param sourceId IP address or user ID
     * @return true if source should be blocked, false otherwise
     */
    public boolean recordNotFoundError(final String sourceId) {
        // If 404 tracking is disabled, never block
        if (!notFoundTrackingEnabled) {
            return false;
        }

        if (sourceId == null || sourceId.isEmpty()) {
            return false;
        }

        // Periodic cache cleanup
        cleanupCacheIfNeeded();

        // Check in-memory cache
        NotFoundCounter counter = inMemoryCache.get(sourceId);
        if (counter != null && !counter.isExpired()) {
            if (counter.isBlocked()) {
                LOGGER.warn("404 tracking: Source {} is already blocked", sourceId);
                return true;
            }

            counter.increment();

            // Check if threshold exceeded (use > instead of >= to block only when exceeding, not at
            // threshold)
            if (counter.getCountPerMinute() > max404PerMinute
                    || counter.getCountPerHour() > max404PerHour) {
                counter.setBlocked(true);
                blockSourceInDynamoDBAsync(sourceId);
                LOGGER.warn(
                        "404 tracking: Blocked source {} after {} 404s in minute, {} in hour",
                        sourceId,
                        counter.getCountPerMinute(),
                        counter.getCountPerHour());
                return true;
            }
            return false;
        }

        // Check DynamoDB for persistent blocked state
        if (dynamoDbAvailable && isBlockedInDynamoDB(sourceId)) {
            final NotFoundCounter blockedCounter = new NotFoundCounter();
            blockedCounter.setBlocked(true);
            inMemoryCache.put(sourceId, blockedCounter);
            return true;
        }

        // Create new counter
        counter = new NotFoundCounter();
        counter.increment();

        // Prevent cache from growing too large
        if (inMemoryCache.size() >= MAX_CACHE_SIZE) {
            final String firstKey = inMemoryCache.keySet().iterator().next();
            inMemoryCache.remove(firstKey);
        }

        inMemoryCache.put(sourceId, counter);
        return false;
    }

    /** Check if a source is currently blocked due to excessive 404s */
    public boolean isBlocked(final String sourceId) {
        // If 404 tracking is disabled, never block
        if (!notFoundTrackingEnabled) {
            return false;
        }
        if (sourceId == null || sourceId.isEmpty()) {
            return false;
        }

        final NotFoundCounter counter = inMemoryCache.get(sourceId);
        if (counter != null && !counter.isExpired()) {
            return counter.isBlocked();
        }

        // Check DynamoDB
        if (dynamoDbAvailable) {
            return isBlockedInDynamoDB(sourceId);
        }

        return false;
    }

    /** Clear tracking for a specific source (for testing purposes) */
    public void clearTracking(final String sourceId) {
        if (sourceId != null && !sourceId.isEmpty()) {
            inMemoryCache.remove(sourceId);
        }
    }

    /** Clear all tracking (for testing purposes) */
    public void clearAllTracking() {
        inMemoryCache.clear();
    }

    private void cleanupCacheIfNeeded() {
        final long now = System.currentTimeMillis();
        if (now - lastCacheCleanup > CACHE_CLEANUP_INTERVAL_MS) {
            synchronized (this) {
                if (now - lastCacheCleanup > CACHE_CLEANUP_INTERVAL_MS) {
                    inMemoryCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
                    lastCacheCleanup = now;
                }
            }
        }
    }

    private boolean initializeTable() {
        // Check if table already exists before attempting to create it
        try {
            dynamoDbClient.describeTable(
                    DescribeTableRequest.builder().tableName(tableName).build());
            // Table exists, no need to create it
            dynamoDbAvailable = true;
            return true;
        } catch (ResourceNotFoundException e) {
            // Table doesn't exist, proceed with creation
        } catch (Exception e) {
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "⚠️ AWS credentials not configured for LocalStack or environment. Skipping 404 tracking table check. Error: {}",
                        e.getMessage());
                dynamoDbAvailable = false;
                return false;
            }
            LOGGER.debug("Failed to check if 404 tracking table exists: {}", e.getMessage());
            // Continue with creation attempt
        }

        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName(SOURCE_ID)
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName(SOURCE_ID)
                                            .keyType(KeyType.HASH)
                                            .build())
                            .build());

            // Configure TTL
            try {
                dynamoDbClient.updateTimeToLive(
                        UpdateTimeToLiveRequest.builder()
                                .tableName(tableName)
                                .timeToLiveSpecification(
                                        TimeToLiveSpecification.builder()
                                                .enabled(true)
                                                .attributeName("ttl")
                                                .build())
                                .build());
            } catch (Exception e) {
                LOGGER.debug("Failed to configure TTL for 404 tracking table: {}", e.getMessage());
            }
            LOGGER.debug("404 tracking table created successfully");
            dynamoDbAvailable = true;
            return true;
        } catch (ResourceInUseException e) {
            // Table was created by another instance between check and create - this is fine
            LOGGER.debug("404 tracking table already exists (race condition)");
            dynamoDbAvailable = true;
            return true;
        } catch (Exception e) {
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "⚠️ AWS credentials not configured for LocalStack or environment. 404 tracking will work in in-memory only mode. Error: {}",
                        e.getMessage());
                dynamoDbAvailable = false;
                return false;
            }
            // Log at WARN level - table creation failure indicates configuration issue
            // In test environments, ensure LocalStack is running and auto-create-tables is enabled
            LOGGER.warn(
                    "Failed to create 404 tracking table: {}. "
                            + "404 tracking will work in in-memory only mode. "
                            + "Ensure LocalStack is running and auto-create-tables is enabled.",
                    e.getMessage());
            dynamoDbAvailable = false;
            return false;
        }
    }

    private boolean isBlockedInDynamoDB(final String sourceId) {
        if (!dynamoDbAvailable) {
            return false;
        }

        try {
            final GetItemResponse response =
                    dynamoDbClient.getItem(
                            GetItemRequest.builder()
                                    .tableName(tableName)
                                    .key(
                                            Map.of(
                                                    SOURCE_ID,
                                                    AttributeValue.builder().s(sourceId).build()))
                                    .build());

            if (response.item() != null && response.item().containsKey(BLOCKED_UNTIL)) {
                final AttributeValue blockedUntilAttr = response.item().get(BLOCKED_UNTIL);
                if (blockedUntilAttr != null && blockedUntilAttr.n() != null) {
                    final long blockedUntil = Long.parseLong(blockedUntilAttr.n());
                    if (Instant.now().getEpochSecond() < blockedUntil) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            if (isCredentialsError(e)) {
                LOGGER.debug("AWS credentials not configured. Using in-memory cache only.");
            } else {
                LOGGER.debug(
                        "Failed to check blocked source in DynamoDB: {}. Using in-memory cache only.",
                        e.getMessage());
            }
            dynamoDbAvailable = false;
        }
        return false;
    }

    private void blockSourceInDynamoDB(final String sourceId) {
        if (!dynamoDbAvailable) {
            return;
        }

        try {
            final long blockedUntil = Instant.now().getEpochSecond() + BLOCK_DURATION_SECONDS;
            dynamoDbClient.putItem(
                    PutItemRequest.builder()
                            .tableName(tableName)
                            .item(
                                    Map.of(
                                            SOURCE_ID,
                                                    AttributeValue.builder().s(sourceId).build(),
                                            BLOCKED_UNTIL,
                                                    AttributeValue.builder()
                                                            .n(String.valueOf(blockedUntil))
                                                            .build(),
                                            "blockedAt",
                                                    AttributeValue.builder()
                                                            .n(
                                                                    String.valueOf(
                                                                            Instant.now()
                                                                                    .getEpochSecond()))
                                                            .build(),
                                            "ttl",
                                                    AttributeValue.builder()
                                                            .n(String.valueOf(blockedUntil + 86_400))
                                                            .build()))
                            .build());
            LOGGER.warn(
                    "404 tracking: Blocked source {} until {}",
                    sourceId,
                    Instant.ofEpochSecond(blockedUntil));
        } catch (Exception e) {
            LOGGER.debug(
                    "Failed to block source in DynamoDB: {}. Source blocked in-memory only.",
                    e.getMessage());
            dynamoDbAvailable = false;
        }
    }

    /**
     * Async block source to avoid blocking the request thread Uses thread pool executor to prevent
     * resource exhaustion
     */
    private void blockSourceInDynamoDBAsync(final String sourceId) {
        // Use thread pool executor to avoid creating unbounded threads
        asyncExecutor.execute(
                () -> {
                    try {
                        blockSourceInDynamoDB(sourceId);
                    } catch (Exception e) {
                        LOGGER.error(
                                "Error in async source blocking for {}: {}",
                                sourceId,
                                e.getMessage(),
                                e);
                    }
                });
    }

    /** Helper method to check if an exception is related to AWS credentials */
    private boolean isCredentialsError(final Exception e) {
        if (e instanceof software.amazon.awssdk.core.exception.SdkClientException) {
            final String message = e.getMessage();
            return message != null && message.contains("Unable to load credentials");
        }
        // Check for wrapped SdkClientException
        final Throwable cause = e.getCause();
        if (cause instanceof software.amazon.awssdk.core.exception.SdkClientException) {
            final String causeMessage = cause.getMessage();
            return causeMessage != null && causeMessage.contains("Unable to load credentials");
        }
        return false;
    }

    /** Thread-safe counter for tracking 404 errors */
    private static final class NotFoundCounter {
        private final AtomicInteger countPerMinute = new AtomicInteger(0);
        private final AtomicInteger countPerHour = new AtomicInteger(0);
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        private final AtomicLong hourWindowStart = new AtomicLong(System.currentTimeMillis());
        private final AtomicBoolean blocked = new AtomicBoolean(false);

        public void increment() {
            final long now = System.currentTimeMillis();

            // Reset minute window if expired
            if (now - windowStart.get() > WINDOW_SIZE_MS) {
                if (windowStart.compareAndSet(windowStart.get(), now)) {
                    countPerMinute.set(1);
                } else {
                    countPerMinute.incrementAndGet();
                }
            } else {
                countPerMinute.incrementAndGet();
            }

            // Reset hour window if expired
            if (now - hourWindowStart.get() > 3_600_000) { // 1 hour
                if (hourWindowStart.compareAndSet(hourWindowStart.get(), now)) {
                    countPerHour.set(1);
                } else {
                    countPerHour.incrementAndGet();
                }
            } else {
                countPerHour.incrementAndGet();
            }
        }

        public int getCountPerMinute() {
            return countPerMinute.get();
        }

        public int getCountPerHour() {
            return countPerHour.get();
        }

        public boolean isBlocked() {
            return blocked.get();
        }

        public void setBlocked(final boolean blocked) {
            this.blocked.set(blocked);
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - windowStart.get()
                    > WINDOW_SIZE_MS * 2; // Expire after 2 minutes of inactivity
        }
    }
}
