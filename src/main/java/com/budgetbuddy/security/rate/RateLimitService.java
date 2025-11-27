package com.budgetbuddy.security.rate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-Customer Rate Limiting Service
 * Implements token bucket algorithm for fair rate limiting
 * Uses DynamoDB for distributed rate limiting across instances
 *
 * Thread-safe implementation with proper synchronization
 */
@Service
public class RateLimitService {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitService.class);

    // Rate limits per endpoint type
    private static final Map<String, RateLimitConfig> ENDPOINT_LIMITS = Map.of(
            "/api/auth/login", new RateLimitConfig(5, 60), // 5 requests per minute
            "/api/auth/signup", new RateLimitConfig(3, 60), // 3 requests per minute
            "/api/plaid", new RateLimitConfig(10, 60), // 10 requests per minute
            "/api/transactions", new RateLimitConfig(100, 60), // 100 requests per minute
            "/api/analytics", new RateLimitConfig(20, 60), // 20 requests per minute
            "default", new RateLimitConfig(50, 60) // Default: 50 requests per minute
    );

    private static final int MAX_CACHE_SIZE = 50000; // Prevent unbounded growth
    private static final long CACHE_CLEANUP_INTERVAL_MS = 300000; // 5 minutes

    private final DynamoDbClient dynamoDbClient;
    private final String tableName = "BudgetBuddy-RateLimits";

    // In-memory cache for hot paths
    private final Map<String, TokenBucket> inMemoryCache = new ConcurrentHashMap<>();
    @SuppressWarnings("unused") // Reserved for future cache TTL implementation
    private static final long CACHE_TTL_MS = 60000; // 1 minute
    private volatile long lastCacheCleanup = System.currentTimeMillis();

    public RateLimitService(final DynamoDbClient dynamoDbClient) {
        if (dynamoDbClient == null) {
            throw new IllegalArgumentException("DynamoDbClient cannot be null");
        }
        this.dynamoDbClient = dynamoDbClient;
        initializeTable();
    }

    /**
     * Check if user is allowed to make request to endpoint
     * Thread-safe implementation
     */
    public boolean isAllowed(final String userId, final String endpoint) {
        if (userId == null || userId.isEmpty()) {
            logger.warn("User ID is null or empty");
            return false;
        }
        if (endpoint == null || endpoint.isEmpty()) {
            logger.warn("Endpoint is null or empty");
            return false;
        }

        // Periodic cache cleanup to prevent unbounded growth
        cleanupCacheIfNeeded();

        String key = userId + ":" + endpoint;
        RateLimitConfig config = getRateLimitConfig(endpoint);

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
            String firstKey = inMemoryCache.keySet().iterator().next();
            inMemoryCache.remove(firstKey);
        }

        inMemoryCache.put(key, bucket);

        return bucket.tryConsume();
    }

    /**
     * Get retry-after seconds for rate-limited user
     */
    public int getRetryAfter(final String userId, final String endpoint) {
        if (userId == null || endpoint == null) {
            return 60;
        }

        String key = userId + ":" + endpoint;
        TokenBucket bucket = inMemoryCache.get(key);

        if (bucket != null && !bucket.isExpired()) {
            RateLimitConfig config = getRateLimitConfig(endpoint);
            long now = System.currentTimeMillis();
            long nextRefill = bucket.getLastRefill() + (config.windowSeconds * 1000L);
            long secondsUntilRefill = (nextRefill - now) / 1000;
            return Math.max(1, (int) secondsUntilRefill);
        }

        return 60; // Default 1 minute
    }

    /**
     * Cleanup expired cache entries periodically
     */
    private void cleanupCacheIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastCacheCleanup > CACHE_CLEANUP_INTERVAL_MS) {
            synchronized (this) {
                // Double-check pattern
                if (now - lastCacheCleanup > CACHE_CLEANUP_INTERVAL_MS) {
                    inMemoryCache.entrySet().removeIf((entry) -> entry.getValue().isExpired());
                    lastCacheCleanup = now;
                }
            }
        }
    }

    private RateLimitConfig getRateLimitConfig(final String endpoint) {
        if (endpoint == null) {
            return ENDPOINT_LIMITS.get("default");
        }

        return ENDPOINT_LIMITS.entrySet().stream()
                .filter(e -> endpoint.startsWith(e.getKey()))
                .findFirst()
                .map(Map.Entry::getValue)
                .orElse(ENDPOINT_LIMITS.get("default"));
    }

    private TokenBucket loadOrCreateBucket(final String key, final RateLimitConfig config) {
        if (key == null || config == null) {
            return new TokenBucket(ENDPOINT_LIMITS.get("default"));
        }

        try {
            GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("key", AttributeValue.builder().s(key).build()))
                    .build());

            if (response.item() != null && response.item().containsKey("tokens") && response.item().containsKey("lastRefill")) {
                AttributeValue tokensAttr = response.item().get("tokens");
                AttributeValue lastRefillAttr = response.item().get("lastRefill");

                if (tokensAttr != null && tokensAttr.n() != null &&
                    lastRefillAttr != null && lastRefillAttr.n() != null) {
                    try {
                        int tokens = Integer.parseInt(tokensAttr.n());
                        long lastRefill = Long.parseLong(lastRefillAttr.n());
                        return new TokenBucket(config, tokens, lastRefill);
                    } catch (NumberFormatException e) {
                        logger.error("Invalid token bucket values in DynamoDB for key: {}", key);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to load rate limit from DynamoDB: {}", e.getMessage());
        }

        return new TokenBucket(config);
    }

    private void updateDynamoDB(final String key, final TokenBucket bucket) {
        if (key == null || bucket == null) {
            return;
        }

        try {
            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(Map.of(
                            "key", AttributeValue.builder().s(key).build(),
                            "tokens", AttributeValue.builder().n(String.valueOf(bucket.getTokens())).build(),
                            "lastRefill", AttributeValue.builder().n(String.valueOf(bucket.getLastRefill())).build(),
                            "ttl", AttributeValue.builder().n(String.valueOf(Instant.now().getEpochSecond() + 3600)).build()
                    ))
                    .build());
        } catch (Exception e) {
            logger.error("Failed to update rate limit in DynamoDB: {}", e.getMessage());
        }
    }

    /**
     * Async update to avoid blocking the request thread
     */
    private void updateDynamoDBAsync(final String key, final TokenBucket bucket) {
        new Thread(() -> updateDynamoDB(key, bucket), "ratelimit-update-async").start();
    }

    private void initializeTable() {
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
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
                dynamoDbClient.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                        .tableName("BudgetBuddy-RateLimits")
                        .timeToLiveSpecification(TimeToLiveSpecification.builder()
                                .enabled(true)
                                .attributeName("ttl")
                                .build())
                        .build());
            } catch (Exception e) {
                logger.warn("Failed to configure TTL for rate limit table: {}", e.getMessage());
            }
            logger.info("Rate limit table created");
        } catch (ResourceInUseException e) {
            logger.debug("Rate limit table already exists");
        } catch (Exception e) {
            logger.error("Failed to create rate limit table: {}", e.getMessage());
        }
    }

    /**
     * Thread-safe token bucket implementation
     */
    private static class TokenBucket {
        private final RateLimitConfig config;
        private final AtomicInteger tokens;
        private final AtomicLong lastRefill;

        public TokenBucket(final RateLimitConfig config) {
            if (config == null) {
                throw new IllegalArgumentException("RateLimitConfig cannot be null");
            }
            this.config = config;
            this.tokens = new AtomicInteger(config.maxRequests);
            this.lastRefill = new AtomicLong(System.currentTimeMillis());
        }

        public TokenBucket(final RateLimitConfig config, final int tokens, final long lastRefill) {
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
            int current = tokens.get();
            if (current > 0) {
                // Try to decrement atomically
                return tokens.compareAndSet(current, current - 1);
            }
            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long lastRefillTime = lastRefill.get();
            long elapsed = now - lastRefillTime;
            long refillInterval = config.windowSeconds * 1000L;

            if (elapsed >= refillInterval) {
                // Only one thread should refill
                if (lastRefill.compareAndSet(lastRefillTime, now)) {
                    int tokensToAdd = (int) (elapsed / refillInterval) * config.maxRequests;
                    int current = tokens.get();
                    int newValue = Math.min(config.maxRequests, current + tokensToAdd);
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

        RateLimitConfig(int maxRequests, int windowSeconds) {
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
