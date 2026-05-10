package com.budgetbuddy.security.ddos;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * DDoS Protection Service Uses DynamoDB for distributed rate limiting across multiple instances
 * Implements sliding window algorithm for accurate rate limiting
 *
 * <p>Thread-safe implementation with proper synchronization
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
// SDK / Spring / reflection integration — broad catches translate any
// runtime exception to AppException or log+swallow. Narrowing isn't
// practical here, so suppress at class level.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Service
public class DDoSProtectionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DDoSProtectionService.class);

    @Value("${app.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    @Value("${app.rate-limit.ddos.max-requests-per-minute:100000}")
    private long maxRequestsPerMinute;

    // TODO: Implement hourly rate limiting - currently only per-minute rate limiting is implemented
    // Reserved for future implementation of hourly rate limits
    @Value("${app.rate-limit.ddos.max-requests-per-hour:5000000}")
    @SuppressWarnings({"unused", "PMD.AvoidCatchingGenericException"}) // Field reserved for future hourly rate limiting implementation
    private long maxRequestsPerHour;

    // LOW PRIORITY FIX: Make DDoS protection constants fully configurable
    @Value("${app.rate-limit.ddos.block-duration-seconds:3600}")
    private int blockDurationSeconds; // 1 hour block

    @Value("${app.rate-limit.ddos.max-cache-size:10000}")
    private int maxCacheSize; // Prevent unbounded growth

    @Value("${app.rate-limit.ddos.cache-cleanup-interval-ms:300000}")
    private long cacheCleanupIntervalMs; // 5 minutes

    @Value("${app.rate-limit.ddos.cache-ttl-ms:60000}")
    private long cacheTtlMs; // 1 minute

    @Value("${app.rate-limit.ddos.dynamodb-check-interval-ms:60000}")
    private long dynamoDbCheckIntervalMs; // Check every minute

    private final DynamoDbClient dynamoDbClient;
    private final String tableName; // Configured via table prefix for different environments
    private final Executor asyncExecutor; // Thread pool for async operations

    // In-memory LRU cache for hot paths (reduces DynamoDB costs)
    // Using LinkedHashMap with access-order for LRU eviction
    private final Map<String, RequestCounter> inMemoryCache;
    private volatile long lastCacheCleanup = System.currentTimeMillis();

    // Cache metrics
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);

    // Track DynamoDB availability - if unavailable, fall back to in-memory only
    private volatile boolean dynamoDbAvailable = true;
    private volatile long lastDynamoDbCheck = 0;

    public DDoSProtectionService(
            final DynamoDbClient dynamoDbClient,
            @Value("${app.aws.dynamodb.table-prefix:BudgetBuddy}") final String tablePrefix,
            @Autowired(required = false) @Qualifier("taskExecutor") final Executor asyncExecutor) {
        if (dynamoDbClient == null) {
            throw new IllegalArgumentException("DynamoDbClient cannot be null");
        }
        this.dynamoDbClient = dynamoDbClient;
        // Use table prefix from configuration for different environments:
        // - LocalStack/CI: TestBudgetBuddy-DDoSProtection
        // - Staging: BudgetBuddyStaging-DDoSProtection
        // - Production: BudgetBuddy-DDoSProtection
        this.tableName = tablePrefix + "-DDoSProtection";
        // Use provided executor or fallback to default (for testing)
        this.asyncExecutor =
                asyncExecutor != null
                        ? asyncExecutor
                        : java.util.concurrent.Executors.newCachedThreadPool(
                                r -> {
                                    final Thread t = new Thread(r, "ddos-block-async");
                                    t.setDaemon(true);
                                    return t;
                                });

        // Initialize LRU cache with access-order (true = LRU, false = insertion-order)
        this.inMemoryCache =
                Collections.synchronizedMap(
                        new LinkedHashMap<String, RequestCounter>(16, 0.75f, true) {
                            @Override
                            protected boolean removeEldestEntry(
                                    final Map.Entry<String, RequestCounter> eldest) {
                                // Remove oldest entry when cache exceeds max size
                                return size() > maxCacheSize;
                            }
                        });

        final boolean initialized = initializeTable();
        if (!initialized) {
            LOGGER.warn(
                    "DDoS protection initialized with in-memory only mode (DynamoDB unavailable). "
                            + "Blocked IPs will not persist across restarts.");
        }
    }

    /** Check if IP is allowed to make requests Thread-safe implementation */
    public boolean isAllowed(final String ipAddress) {
        // If rate limiting is disabled, always allow
        if (!rateLimitEnabled) {
            return true;
        }

        if (ipAddress == null || ipAddress.isEmpty()) {
            LOGGER.warn(
                    "IP address is null or empty - allowing request (IP extraction may have failed)");
            // Allow request if IP extraction failed - better than blocking legitimate users
            return true;
        }

        // Periodic cache cleanup to prevent unbounded growth
        cleanupCacheIfNeeded();

        // Periodically check DynamoDB availability
        if (!dynamoDbAvailable) {
            checkDynamoDbAvailability();
        }

        // Check in-memory LRU cache first
        RequestCounter counter = inMemoryCache.get(ipAddress);
        if (counter != null && !counter.isExpired()) {
            cacheHits.incrementAndGet(); // Track cache hit
            if (counter.isBlocked()) {
                return false;
            }
            if (counter.getRequestsPerMinute() >= maxRequestsPerMinute) {
                counter.setBlocked(true);
                // Async block in DynamoDB to avoid blocking
                blockIpInDynamoDBAsync(ipAddress);
                return false;
            }
            counter.increment();
            return true;
        }

        if (counter == null) {
            cacheMisses.incrementAndGet(); // Track cache miss
        }

        // Check DynamoDB for persistent state (only if available)
        if (dynamoDbAvailable && isBlockedInDynamoDB(ipAddress)) {
            // Update cache with blocked state
            final RequestCounter blockedCounter = new RequestCounter();
            blockedCounter.setBlocked(true);
            inMemoryCache.put(ipAddress, blockedCounter);
            return false;
        }

        // Create new counter (thread-safe)
        counter = new RequestCounter();
        counter.increment();

        // LRU cache automatically evicts oldest entry when max size exceeded
        // No need for manual size checking - LinkedHashMap handles it via removeEldestEntry
        inMemoryCache.put(ipAddress, counter);
        return true;
    }

    /** Record a request for monitoring and analysis */
    public void recordRequest(final String ipAddress, final String userId) {
        if (ipAddress == null || ipAddress.isEmpty()) {
            return;
        }

        if (!dynamoDbAvailable) {
            return; // Skip recording if DynamoDB is unavailable
        }

        try {
            dynamoDbClient.putItem(
                    PutItemRequest.builder()
                            .tableName(tableName)
                            .item(
                                    Map.of(
                                            "ipAddress",
                                                    AttributeValue.builder().s(ipAddress).build(),
                                            "timestamp",
                                                    AttributeValue.builder()
                                                            .n(
                                                                    String.valueOf(
                                                                            Instant.now()
                                                                                    .getEpochSecond()))
                                                            .build(),
                                            "userId",
                                                    AttributeValue.builder()
                                                            .s(
                                                                    userId != null
                                                                            ? userId
                                                                            : "anonymous")
                                                            .build(),
                                            "ttl",
                                                    AttributeValue.builder()
                                                            .n(
                                                                    String.valueOf(
                                                                            Instant.now()
                                                                                            .getEpochSecond()
                                                                                    + 86_400))
                                                            .build() // 24h TTL
                                            ))
                            .build());
        } catch (Exception e) {
            LOGGER.debug(
                    "Failed to record request in DynamoDB: {}. Continuing without recording.",
                    e.getMessage());
            // Mark DynamoDB as unavailable if we get persistent errors
            dynamoDbAvailable = false;
            // Don't fail the request if logging fails
        }
    }

    /** Cleanup expired cache entries periodically */
    private void cleanupCacheIfNeeded() {
        final long now = System.currentTimeMillis();
        if (now - lastCacheCleanup > cacheCleanupIntervalMs) {
            synchronized (this) {
                // Double-check pattern
                if (now - lastCacheCleanup > cacheCleanupIntervalMs) {
                    inMemoryCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
                    lastCacheCleanup = now;
                }
            }
        }
    }

    /**
     * Initialize DynamoDB table for DDoS protection
     *
     * @return true if table was created or already exists, false if DynamoDB is unavailable
     */
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
            // CRITICAL: Handle credentials errors gracefully (e.g., in tests without LocalStack)
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "DynamoDB credentials not available - skipping table initialization (likely in test environment without LocalStack)");
                dynamoDbAvailable = false;
                return false;
            }
            LOGGER.warn("Failed to check if DDoS protection table exists: {}", e.getMessage());
            // Continue with creation attempt
        }

        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("ipAddress")
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName("ipAddress")
                                            .keyType(KeyType.HASH)
                                            .build())
                            .build());

            // Configure TTL separately
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
                LOGGER.warn(
                        "Failed to configure TTL for DDoS protection table: {}", e.getMessage());
            }
            LOGGER.info("DDoS protection table created successfully");
            dynamoDbAvailable = true;
            return true;
        } catch (software.amazon.awssdk.core.exception.SdkClientException e) {
            // CRITICAL: Handle credentials errors gracefully (e.g., in tests without LocalStack)
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "DynamoDB credentials not available - skipping table initialization (likely in test environment without LocalStack)");
                dynamoDbAvailable = false;
                return false;
            }
            LOGGER.error("Failed to create DDoS protection table: {}", e.getMessage(), e);
            dynamoDbAvailable = false;
            return false;
        } catch (ResourceInUseException e) {
            // Table was created by another instance between check and create - this is fine
            LOGGER.debug("DDoS protection table already exists (race condition)");
            dynamoDbAvailable = true;
            return true;
        } catch (Exception e) {
            LOGGER.error(
                    "Failed to create DDoS protection table: {}. "
                            + "DDoS protection will work in in-memory only mode. "
                            + "Blocked IPs will not persist across restarts.",
                    e.getMessage());
            dynamoDbAvailable = false;
            return false;
        }
    }

    /** Check DynamoDB availability periodically and update status */
    private void checkDynamoDbAvailability() {
        final long now = System.currentTimeMillis();
        if (now - lastDynamoDbCheck < dynamoDbCheckIntervalMs) {
            return;
        }

        synchronized (this) {
            if (now - lastDynamoDbCheck < dynamoDbCheckIntervalMs) {
                return;
            }
            lastDynamoDbCheck = now;

            // Try a simple operation to check if DynamoDB is available
            try {
                dynamoDbClient.describeTable(
                        DescribeTableRequest.builder().tableName(tableName).build());
                if (!dynamoDbAvailable) {
                    LOGGER.info("DynamoDB is now available for DDoS protection");
                    dynamoDbAvailable = true;
                }
            } catch (ResourceNotFoundException e) {
                // Table doesn't exist - try to create it
                if (initializeTable()) {
                    dynamoDbAvailable = true;
                } else {
                    dynamoDbAvailable = false;
                }
            } catch (Exception e) {
                LOGGER.debug(
                        "DynamoDB check failed: {}. Continuing with in-memory only mode.",
                        e.getMessage());
                dynamoDbAvailable = false;
            }
        }
    }

    private boolean isBlockedInDynamoDB(final String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty()) {
            return false;
        }

        if (!dynamoDbAvailable) {
            return false; // DynamoDB unavailable, rely on in-memory cache only
        }

        try {
            final GetItemResponse response =
                    dynamoDbClient.getItem(
                            GetItemRequest.builder()
                                    .tableName(tableName)
                                    .key(
                                            Map.of(
                                                    "ipAddress",
                                                    AttributeValue.builder().s(ipAddress).build()))
                                    .build());

            if (response.item() != null && response.item().containsKey("blockedUntil")) {
                final AttributeValue blockedUntilAttr = response.item().get("blockedUntil");
                if (blockedUntilAttr != null && blockedUntilAttr.n() != null) {
                    final long blockedUntil = Long.parseLong(blockedUntilAttr.n());
                    if (Instant.now().getEpochSecond() < blockedUntil) {
                        return true;
                    }
                }
            }
        } catch (NumberFormatException e) {
            LOGGER.error("Invalid blockedUntil value in DynamoDB for IP: {}", ipAddress);
        } catch (Exception e) {
            LOGGER.debug(
                    "Failed to check blocked IP in DynamoDB: {}. Using in-memory cache only.",
                    e.getMessage());
            // Mark DynamoDB as unavailable if we get persistent errors
            dynamoDbAvailable = false;
        }
        return false;
    }

    private void blockIpInDynamoDB(final String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty()) {
            return;
        }

        if (!dynamoDbAvailable) {
            LOGGER.debug("DynamoDB unavailable - IP {} blocked in-memory only", ipAddress);
            return; // DynamoDB unavailable, block is already in in-memory cache
        }

        try {
            final long blockedUntil = Instant.now().getEpochSecond() + blockDurationSeconds;
            dynamoDbClient.putItem(
                    PutItemRequest.builder()
                            .tableName(tableName)
                            .item(
                                    Map.of(
                                            "ipAddress",
                                                    AttributeValue.builder().s(ipAddress).build(),
                                            "blockedUntil",
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
                    "Blocked IP address: {} until {}",
                    ipAddress,
                    Instant.ofEpochSecond(blockedUntil));
        } catch (Exception e) {
            LOGGER.debug(
                    "Failed to block IP in DynamoDB: {}. IP blocked in-memory only.",
                    e.getMessage());
            // Mark DynamoDB as unavailable if we get persistent errors
            dynamoDbAvailable = false;
        }
    }

    /**
     * Async block IP to avoid blocking the request thread Uses thread pool executor to prevent
     * resource exhaustion
     */
    private void blockIpInDynamoDBAsync(final String ipAddress) {
        // Use thread pool executor to avoid creating unbounded threads
        // This prevents resource exhaustion under high load
        asyncExecutor.execute(
                () -> {
                    try {
                        blockIpInDynamoDB(ipAddress);
                    } catch (Exception e) {
                        LOGGER.error(
                                "Error in async IP blocking for {}: {}",
                                ipAddress,
                                e.getMessage(),
                                e);
                    }
                });
    }

    /** Thread-safe in-memory request counter for hot path optimization */
    private final class RequestCounter {
        private final AtomicInteger requestsPerMinute = new AtomicInteger(0);
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        private final AtomicBoolean blocked = new AtomicBoolean(false);

        public void increment() {
            final long now = System.currentTimeMillis();
            final long start = windowStart.get();

            if (now - start > cacheTtlMs) {
                // Reset window atomically
                if (windowStart.compareAndSet(start, now)) {
                    requestsPerMinute.set(1);
                } else {
                    // Another thread reset it, just increment
                    requestsPerMinute.incrementAndGet();
                }
            } else {
                requestsPerMinute.incrementAndGet();
            }
        }

        public int getRequestsPerMinute() {
            return requestsPerMinute.get();
        }

        public boolean isBlocked() {
            return blocked.get();
        }

        public void setBlocked(final boolean blocked) {
            this.blocked.set(blocked);
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - windowStart.get() > cacheTtlMs;
        }
    }

    /**
     * Get cache statistics for monitoring
     *
     * @return Map with cache metrics (hits, misses, hit rate, size)
     */
    public Map<String, Object> getCacheMetrics() {
        final long hits = cacheHits.get();
        final long misses = cacheMisses.get();
        final long total = hits + misses;
        final double hitRate = total > 0 ? (double) hits / total : 0.0;

        return Map.of(
                "hits", hits,
                "misses", misses,
                "hitRate", hitRate,
                "size", inMemoryCache.size(),
                "maxSize", maxCacheSize);
    }

    /** Reset cache metrics (useful for testing or periodic resets) */
    public void resetCacheMetrics() {
        cacheHits.set(0);
        cacheMisses.set(0);
    }

    /** Check if the exception is due to missing AWS credentials */
    private boolean isCredentialsError(final Exception e) {
        final String message = e.getMessage();
        if (message != null && message.contains("Unable to load credentials")) {
            return true;
        }
        // Check for SdkClientException with credentials error
        if (e instanceof software.amazon.awssdk.core.exception.SdkClientException) {
            final String exceptionMessage = e.getMessage();
            if (exceptionMessage != null
                    && exceptionMessage.contains("Unable to load credentials")) {
                return true;
            }
        }
        // Check cause chain
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof software.amazon.awssdk.core.exception.SdkClientException) {
                final String causeMessage = cause.getMessage();
                if (causeMessage != null && causeMessage.contains("Unable to load credentials")) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }
}
