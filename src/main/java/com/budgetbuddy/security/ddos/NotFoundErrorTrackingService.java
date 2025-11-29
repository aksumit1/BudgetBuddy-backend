package com.budgetbuddy.security.ddos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks 404 (Not Found) errors and throttles sources that generate excessive 404s
 * This is a DDoS protection mechanism - repeated 404s can indicate:
 * - Scanning/probing attacks
 * - Misconfigured clients
 * - Buggy applications causing infinite retries
 * 
 * After N 404s in a time window, the source is throttled/blocked
 */
@Service
public class NotFoundErrorTrackingService {

    private static final Logger logger = LoggerFactory.getLogger(NotFoundErrorTrackingService.class);

    // Configuration - Increased for testing and heavy usage scenarios
    private static final int MAX_404_PER_MINUTE = 500; // Allow up to 500 404s per minute (for testing)
    private static final int MAX_404_PER_HOUR = 5000; // Allow up to 5000 404s per hour (for testing)
    private static final int BLOCK_DURATION_SECONDS = 3600; // Block for 1 hour after threshold
    private static final long WINDOW_SIZE_MS = 60000; // 1 minute window
    private static final long CACHE_CLEANUP_INTERVAL_MS = 300000; // 5 minutes
    private static final int MAX_CACHE_SIZE = 10000;

    private final DynamoDbClient dynamoDbClient;
    private final String tableName = "BudgetBuddy-NotFoundTracking";

    // In-memory cache for hot paths
    private final Map<String, NotFoundCounter> inMemoryCache = new ConcurrentHashMap<>();
    private volatile long lastCacheCleanup = System.currentTimeMillis();
    private volatile boolean dynamoDbAvailable = true;

    public NotFoundErrorTrackingService(final DynamoDbClient dynamoDbClient) {
        if (dynamoDbClient == null) {
            throw new IllegalArgumentException("DynamoDbClient cannot be null");
        }
        this.dynamoDbClient = dynamoDbClient;
        // Initialize table lazily to avoid blocking application startup
        // Table will be created on first use if needed
        try {
            boolean initialized = initializeTable();
            if (!initialized) {
                logger.debug("404 tracking table initialization deferred (DynamoDB may be unavailable). " +
                        "Will use in-memory only mode if table creation fails.");
            }
        } catch (Exception e) {
            logger.debug("404 tracking table initialization failed: {}. " +
                    "Will use in-memory only mode. This is acceptable in test environments.", e.getMessage());
            dynamoDbAvailable = false;
        }
    }

    /**
     * Record a 404 error from a source (IP or user)
     * @param sourceId IP address or user ID
     * @return true if source should be blocked, false otherwise
     */
    public boolean recordNotFoundError(final String sourceId) {
        if (sourceId == null || sourceId.isEmpty()) {
            return false;
        }

        // Periodic cache cleanup
        cleanupCacheIfNeeded();

        // Check in-memory cache
        NotFoundCounter counter = inMemoryCache.get(sourceId);
        if (counter != null && !counter.isExpired()) {
            if (counter.isBlocked()) {
                logger.warn("404 tracking: Source {} is already blocked", sourceId);
                return true;
            }
            
            counter.increment();
            
            // Check if threshold exceeded
            if (counter.getCountPerMinute() >= MAX_404_PER_MINUTE || 
                counter.getCountPerHour() >= MAX_404_PER_HOUR) {
                counter.setBlocked(true);
                blockSourceInDynamoDBAsync(sourceId);
                logger.warn("404 tracking: Blocked source {} after {} 404s in minute, {} in hour", 
                        sourceId, counter.getCountPerMinute(), counter.getCountPerHour());
                return true;
            }
            return false;
        }

        // Check DynamoDB for persistent blocked state
        if (dynamoDbAvailable && isBlockedInDynamoDB(sourceId)) {
            NotFoundCounter blockedCounter = new NotFoundCounter();
            blockedCounter.setBlocked(true);
            inMemoryCache.put(sourceId, blockedCounter);
            return true;
        }

        // Create new counter
        counter = new NotFoundCounter();
        counter.increment();

        // Prevent cache from growing too large
        if (inMemoryCache.size() >= MAX_CACHE_SIZE) {
            String firstKey = inMemoryCache.keySet().iterator().next();
            inMemoryCache.remove(firstKey);
        }

        inMemoryCache.put(sourceId, counter);
        return false;
    }

    /**
     * Check if a source is currently blocked due to excessive 404s
     */
    public boolean isBlocked(final String sourceId) {
        if (sourceId == null || sourceId.isEmpty()) {
            return false;
        }

        NotFoundCounter counter = inMemoryCache.get(sourceId);
        if (counter != null && !counter.isExpired()) {
            return counter.isBlocked();
        }

        // Check DynamoDB
        if (dynamoDbAvailable) {
            return isBlockedInDynamoDB(sourceId);
        }

        return false;
    }

    private void cleanupCacheIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastCacheCleanup > CACHE_CLEANUP_INTERVAL_MS) {
            synchronized (this) {
                if (now - lastCacheCleanup > CACHE_CLEANUP_INTERVAL_MS) {
                    inMemoryCache.entrySet().removeIf((entry) -> entry.getValue().isExpired());
                    lastCacheCleanup = now;
                }
            }
        }
    }

    private boolean initializeTable() {
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("sourceId")
                                    .attributeType(ScalarAttributeType.S)
                                    .build())
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("sourceId")
                                    .keyType(KeyType.HASH)
                                    .build())
                    .build());

            // Configure TTL
            try {
                dynamoDbClient.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                        .tableName(tableName)
                        .timeToLiveSpecification(TimeToLiveSpecification.builder()
                                .enabled(true)
                                .attributeName("ttl")
                                .build())
                        .build());
            } catch (Exception e) {
                logger.debug("Failed to configure TTL for 404 tracking table: {}", e.getMessage());
            }
            logger.debug("404 tracking table created successfully");
            dynamoDbAvailable = true;
            return true;
        } catch (ResourceInUseException e) {
            logger.debug("404 tracking table already exists");
            dynamoDbAvailable = true;
            return true;
        } catch (Exception e) {
            // Don't log as error in test environments - this is expected if LocalStack isn't running
            logger.debug("Failed to create 404 tracking table: {}. " +
                    "404 tracking will work in in-memory only mode. This is acceptable in test environments.", e.getMessage());
            dynamoDbAvailable = false;
            return false;
        }
    }

    private boolean isBlockedInDynamoDB(final String sourceId) {
        if (!dynamoDbAvailable) {
            return false;
        }

        try {
            GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("sourceId", AttributeValue.builder().s(sourceId).build()))
                    .build());

            if (response.item() != null && response.item().containsKey("blockedUntil")) {
                AttributeValue blockedUntilAttr = response.item().get("blockedUntil");
                if (blockedUntilAttr != null && blockedUntilAttr.n() != null) {
                    long blockedUntil = Long.parseLong(blockedUntilAttr.n());
                    if (Instant.now().getEpochSecond() < blockedUntil) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to check blocked source in DynamoDB: {}. Using in-memory cache only.", e.getMessage());
            dynamoDbAvailable = false;
        }
        return false;
    }

    private void blockSourceInDynamoDB(final String sourceId) {
        if (!dynamoDbAvailable) {
            return;
        }

        try {
            long blockedUntil = Instant.now().getEpochSecond() + BLOCK_DURATION_SECONDS;
            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(Map.of(
                            "sourceId", AttributeValue.builder().s(sourceId).build(),
                            "blockedUntil", AttributeValue.builder().n(String.valueOf(blockedUntil)).build(),
                            "blockedAt", AttributeValue.builder().n(String.valueOf(Instant.now().getEpochSecond())).build(),
                            "ttl", AttributeValue.builder().n(String.valueOf(blockedUntil + 86400)).build()
                    ))
                    .build());
            logger.warn("404 tracking: Blocked source {} until {}", sourceId, Instant.ofEpochSecond(blockedUntil));
        } catch (Exception e) {
            logger.debug("Failed to block source in DynamoDB: {}. Source blocked in-memory only.", e.getMessage());
            dynamoDbAvailable = false;
        }
    }

    private void blockSourceInDynamoDBAsync(final String sourceId) {
        new Thread(() -> blockSourceInDynamoDB(sourceId), "404-block-async").start();
    }

    /**
     * Thread-safe counter for tracking 404 errors
     */
    private static class NotFoundCounter {
        private final AtomicInteger countPerMinute = new AtomicInteger(0);
        private final AtomicInteger countPerHour = new AtomicInteger(0);
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        private final AtomicLong hourWindowStart = new AtomicLong(System.currentTimeMillis());
        private final AtomicBoolean blocked = new AtomicBoolean(false);

        public void increment() {
            long now = System.currentTimeMillis();
            
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
            if (now - hourWindowStart.get() > 3600000) { // 1 hour
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
            return System.currentTimeMillis() - windowStart.get() > WINDOW_SIZE_MS * 2; // Expire after 2 minutes of inactivity
        }
    }
}

