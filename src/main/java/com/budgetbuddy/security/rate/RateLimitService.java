package com.budgetbuddy.security.rate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
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
 * Per-Customer Rate Limiting Service Implements token bucket algorithm for fair rate limiting Uses
 * DynamoDB for distributed rate limiting across instances
 *
 * <p>Thread-safe implementation with proper synchronization
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Service
public final class RateLimitService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitService.class);

    @Value("${app.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    @Value("${app.rate-limit.auth-login:1000}")
    private int authLoginLimit;

    @Value("${app.rate-limit.auth-signup:500}")
    private int authSignupLimit;

    @Value("${app.rate-limit.plaid:50000}")
    private int plaidLimit;

    @Value("${app.rate-limit.transactions:500000}")
    private int transactionsLimit;

    @Value("${app.rate-limit.analytics:100000}")
    private int analyticsLimit;

    @Value("${app.rate-limit.default:500000}")
    private int defaultLimit;

    // Rate limits per endpoint type - now configurable via properties
    private Map<String, RateLimitConfig> getEndpointLimits() {
        return Map.of(
                "/api/auth/login", new RateLimitConfig(authLoginLimit, 60),
                "/api/auth/signup", new RateLimitConfig(authSignupLimit, 60),
                "/api/plaid", new RateLimitConfig(plaidLimit, 60),
                "/api/transactions", new RateLimitConfig(transactionsLimit, 60),
                "/api/transactions/batch-import",
                        new RateLimitConfig(100, 60), // 100 batch requests per minute
                "/api/analytics", new RateLimitConfig(analyticsLimit, 60),
                "default", new RateLimitConfig(defaultLimit, 60));
    }

    private static final int MAX_CACHE_SIZE = 50_000; // Prevent unbounded growth
    private static final long CACHE_CLEANUP_INTERVAL_MS = 300_000; // 5 minutes

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final Executor asyncExecutor; // Thread pool for async operations

    // In-memory cache for hot paths
    private final Map<String, TokenBucket> inMemoryCache = new ConcurrentHashMap<>();

    @SuppressWarnings({"unused", "PMD.AvoidCatchingGenericException"}) // Reserved for future cache TTL implementation
    private static final long CACHE_TTL_MS = 60_000; // 1 minute

    private volatile long lastCacheCleanup = System.currentTimeMillis();

    public RateLimitService(
            final DynamoDbClient dynamoDbClient,
            @Value("${app.aws.dynamodb.table-prefix:BudgetBuddy}") final String tablePrefix,
            @Autowired(required = false) @Qualifier("taskExecutor") final Executor asyncExecutor) {
        if (dynamoDbClient == null) {
            throw new IllegalArgumentException("DynamoDbClient cannot be null");
        }
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tablePrefix + "-RateLimits";
        // Use provided executor or fallback to default (for testing)
        this.asyncExecutor =
                asyncExecutor != null
                        ? asyncExecutor
                        : java.util.concurrent.Executors.newCachedThreadPool(
                                r -> {
                                    final Thread t = new Thread(r, "ratelimit-update-async");
                                    t.setDaemon(true);
                                    return t;
                                });
        initializeTable();
    }

    /** Check if user is allowed to make request to endpoint Thread-safe implementation */
    public boolean isAllowed(final String userId, final String endpoint) {
        // If rate limiting is disabled, always allow
        if (!rateLimitEnabled) {
            return true;
        }

        if (userId == null || userId.isEmpty()) {
            LOGGER.warn("User ID is null or empty");
            return false;
        }
        if (endpoint == null || endpoint.isEmpty()) {
            LOGGER.warn("Endpoint is null or empty");
            return false;
        }

        // Periodic cache cleanup to prevent unbounded growth
        cleanupCacheIfNeeded();

        final String key = userId + ":" + endpoint;
        final RateLimitConfig config = getRateLimitConfig(endpoint);

        // Check in-memory cache first
        TokenBucket bucket = inMemoryCache.get(key);
        if (bucket != null && !bucket.isExpired()) {
            if (bucket.tryConsume()) {
                return true;
            } else {
                // Update DynamoDB with current state (async to avoid blocking)
                updateDynamoDBAsync(key, bucket);
                return false;
            }
        }

        // Load from DynamoDB or create new bucket
        bucket = loadOrCreateBucket(key, config);

        // Prevent cache from growing too large
        if (inMemoryCache.size() >= MAX_CACHE_SIZE) {
            // Remove oldest entries (simple FIFO - in production, use LRU)
            final String firstKey = inMemoryCache.keySet().iterator().next();
            inMemoryCache.remove(firstKey);
        }

        inMemoryCache.put(key, bucket);

        return bucket.tryConsume();
    }

    /** Get retry-after seconds for rate-limited user */
    public int getRetryAfter(final String userId, final String endpoint) {
        if (userId == null || endpoint == null) {
            return 60;
        }

        final String key = userId + ":" + endpoint;
        final TokenBucket bucket = inMemoryCache.get(key);

        if (bucket != null && !bucket.isExpired()) {
            final RateLimitConfig config = getRateLimitConfig(endpoint);
            final long now = System.currentTimeMillis();
            final long nextRefill = bucket.getLastRefill() + (config.windowSeconds * 1000L);
            final long secondsUntilRefill = (nextRefill - now) / 1000;
            return Math.max(1, (int) secondsUntilRefill);
        }

        return 60; // Default 1 minute
    }

    /** Cleanup expired cache entries periodically */
    private void cleanupCacheIfNeeded() {
        final long now = System.currentTimeMillis();
        if (now - lastCacheCleanup > CACHE_CLEANUP_INTERVAL_MS) {
            synchronized (this) {
                // Double-check pattern
                if (now - lastCacheCleanup > CACHE_CLEANUP_INTERVAL_MS) {
                    inMemoryCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
                    lastCacheCleanup = now;
                }
            }
        }
    }

    private RateLimitConfig getRateLimitConfig(final String endpoint) {
        final Map<String, RateLimitConfig> endpointLimits = getEndpointLimits();
        if (endpoint == null) {
            return endpointLimits.get("default");
        }

        return endpointLimits.entrySet().stream()
                .filter(e -> endpoint.startsWith(e.getKey()))
                .findFirst()
                .map(Map.Entry::getValue)
                .orElse(endpointLimits.get("default"));
    }

    private TokenBucket loadOrCreateBucket(final String key, final RateLimitConfig config) {
        if (key == null || config == null) {
            final Map<String, RateLimitConfig> endpointLimits = getEndpointLimits();
            return new TokenBucket(endpointLimits.get("default"));
        }

        try {
            final GetItemResponse response =
                    dynamoDbClient.getItem(
                            GetItemRequest.builder()
                                    .tableName(tableName)
                                    .key(Map.of("key", AttributeValue.builder().s(key).build()))
                                    .build());

            if (response.item() != null
                    && response.item().containsKey("tokens")
                    && response.item().containsKey("lastRefill")) {
                final AttributeValue tokensAttr = response.item().get("tokens");
                final AttributeValue lastRefillAttr = response.item().get("lastRefill");

                if (tokensAttr != null
                        && tokensAttr.n() != null
                        && lastRefillAttr != null
                        && lastRefillAttr.n() != null) {
                    try {
                        final int tokens = Integer.parseInt(tokensAttr.n());
                        final long lastRefill = Long.parseLong(lastRefillAttr.n());
                        return new TokenBucket(config, tokens, lastRefill);
                    } catch (NumberFormatException e) {
                        LOGGER.error("Invalid token bucket values in DynamoDB for key: {}", key);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load rate limit from DynamoDB: {}", e.getMessage());
        }

        return new TokenBucket(config);
    }

    private void updateDynamoDB(final String key, final TokenBucket bucket) {
        if (key == null || bucket == null) {
            return;
        }

        try {
            dynamoDbClient.putItem(
                    PutItemRequest.builder()
                            .tableName(tableName)
                            .item(
                                    Map.of(
                                            "key", AttributeValue.builder().s(key).build(),
                                            "tokens",
                                                    AttributeValue.builder()
                                                            .n(String.valueOf(bucket.getTokens()))
                                                            .build(),
                                            "lastRefill",
                                                    AttributeValue.builder()
                                                            .n(
                                                                    String.valueOf(
                                                                            bucket.getLastRefill()))
                                                            .build(),
                                            "ttl",
                                                    AttributeValue.builder()
                                                            .n(
                                                                    String.valueOf(
                                                                            Instant.now()
                                                                                            .getEpochSecond()
                                                                                    + 3600))
                                                            .build()))
                            .build());
        } catch (Exception e) {
            LOGGER.error("Failed to update rate limit in DynamoDB: {}", e.getMessage());
        }
    }

    /**
     * Async update to avoid blocking the request thread Uses thread pool executor to prevent
     * resource exhaustion
     */
    private void updateDynamoDBAsync(final String key, final TokenBucket bucket) {
        // Use thread pool executor to avoid creating unbounded threads
        asyncExecutor.execute(
                () -> {
                    try {
                        updateDynamoDB(key, bucket);
                    } catch (Exception e) {
                        LOGGER.error(
                                "Error in async rate limit update for {}: {}",
                                key,
                                e.getMessage(),
                                e);
                    }
                });
    }

    private void initializeTable() {
        // Check if table already exists before attempting to create it
        try {
            dynamoDbClient.describeTable(
                    DescribeTableRequest.builder().tableName(tableName).build());
            // Table exists, no need to create it
            return;
        } catch (ResourceNotFoundException e) {
            // Table doesn't exist, proceed with creation
        } catch (Exception e) {
            // CRITICAL: Handle credentials errors gracefully (e.g., in tests without LocalStack)
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "DynamoDB credentials not available - skipping table initialization (likely in test environment without LocalStack)");
                return;
            }
            LOGGER.warn("Failed to check if rate limit table exists: {}", e.getMessage());
            // Continue with creation attempt
        }

        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("key")
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName("key")
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
                LOGGER.warn("Failed to configure TTL for rate limit table: {}", e.getMessage());
            }
            LOGGER.info("Rate limit table created");
        } catch (ResourceInUseException e) {
            // Table was created by another instance between check and create - this is fine
            LOGGER.debug("Rate limit table already exists (race condition)");
        } catch (software.amazon.awssdk.core.exception.SdkClientException e) {
            // CRITICAL: Handle credentials errors gracefully (e.g., in tests without LocalStack)
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "DynamoDB credentials not available - skipping table initialization (likely in test environment without LocalStack)");
                return;
            }
            LOGGER.error("Failed to create rate limit table: {}", e.getMessage());
        } catch (Exception e) {
            // CRITICAL: Handle credentials errors gracefully
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "DynamoDB credentials not available - skipping table initialization (likely in test environment without LocalStack)");
                return;
            }
            LOGGER.error("Failed to create rate limit table: {}", e.getMessage());
        }
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

    /** Thread-safe token bucket implementation */
    private static class TokenBucket {
        private final RateLimitConfig config;
        private final AtomicInteger tokens;
        private final AtomicLong lastRefill;

        TokenBucket(final RateLimitConfig config) {
            if (config == null) {
                throw new IllegalArgumentException("RateLimitConfig cannot be null");
            }
            this.config = config;
            this.tokens = new AtomicInteger(config.maxRequests);
            this.lastRefill = new AtomicLong(System.currentTimeMillis());
        }

        TokenBucket(final RateLimitConfig config, final int tokens, final long lastRefill) {
            if (config == null) {
                throw new IllegalArgumentException("RateLimitConfig cannot be null");
            }
            this.config = config;
            this.tokens = new AtomicInteger(tokens);
            this.lastRefill = new AtomicLong(lastRefill);
            refill();
        }

        public boolean tryConsume() {
            refill();
            final int current = tokens.get();
            if (current > 0) {
                // Try to decrement atomically
                return tokens.compareAndSet(current, current - 1);
            }
            return false;
        }

        private void refill() {
            final long now = System.currentTimeMillis();
            final long lastRefillTime = lastRefill.get();
            final long elapsed = now - lastRefillTime;
            final long refillInterval = config.windowSeconds * 1000L;

            if (elapsed >= refillInterval) {
                // Only one thread should refill
                if (lastRefill.compareAndSet(lastRefillTime, now)) {
                    final int tokensToAdd = (int) (elapsed / refillInterval) * config.maxRequests;
                    final int current = tokens.get();
                    final int newValue = Math.min(config.maxRequests, current + tokensToAdd);
                    tokens.set(newValue);
                }
            }
        }

        public int getTokens() {
            return tokens.get();
        }

        public long getLastRefill() {
            return lastRefill.get();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - lastRefill.get() > config.windowSeconds * 1000L * 2;
        }
    }

    private static class RateLimitConfig {
        final int maxRequests;
        final int windowSeconds;

        RateLimitConfig(final int maxRequests, final int windowSeconds) {
            if (maxRequests <= 0) {
                throw new IllegalArgumentException("maxRequests must be positive");
            }
            if (windowSeconds <= 0) {
                throw new IllegalArgumentException("windowSeconds must be positive");
            }
            this.maxRequests = maxRequests;
            this.windowSeconds = windowSeconds;
        }
    }
}
