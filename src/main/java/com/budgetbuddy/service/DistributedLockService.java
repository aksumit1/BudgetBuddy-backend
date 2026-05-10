package com.budgetbuddy.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * Thin distributed-lock primitive backed by a DynamoDB conditional write.
 *
 * <p>When ECS auto-scales to multiple tasks, any {@code @Scheduled} method fires on <em>every</em>
 * task — which means the same daily-read email could get sent N times, the benchmark aggregation
 * could run concurrently, etc. This service provides a "run once per window" guard.
 *
 * <p>Usage: {@code distributedLock.runOnce("cronName:yyyy-MM-dd", 55, () -> ...)} — the first task
 * to acquire the lock for that key runs the block; others no-op. The lock auto-expires after {@code
 * ttlMinutes} so a crashed job doesn't permanently block the next scheduled run.
 *
 * <p>Uses the existing {@code BudgetBuddy-RateLimits} table (which has a matching schema) so we
 * don't need a new CloudFormation resource for this bounded coordination primitive.
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Service
public class DistributedLockService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistributedLockService.class);

    private final DynamoDbClient client;
    private final String lockTableName;
    private final String instanceId = UUID.randomUUID().toString();

    public DistributedLockService(
            final DynamoDbClient client,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.aws.dynamodb.table-prefix:BudgetBuddy}")
                    final String tablePrefix) {
        this.client = client;
        // Reuse RateLimits table — key schema is compatible, TTL attribute present,
        // and avoids a new CFN resource for a low-volume coordination primitive.
        this.lockTableName = tablePrefix + "-RateLimits";
    }

    /**
     * Acquire the lock for {@code key}, run {@code work}, and release on exit. Returns {@code true}
     * iff this caller acquired the lock and ran the work. Callers that lose the race get {@code
     * false} with no side effects.
     */
    public boolean runOnce(final String key, final int ttlMinutes, final Runnable work) {
        if (!tryAcquire(key, ttlMinutes)) {
            LOGGER.debug("Lock {} already held; skipping this instance's run.", key);
            return false;
        }
        try {
            work.run();
            return true;
        } finally {
            release(key);
        }
    }

    /** Non-blocking attempt. Returns a Supplier so generic return values can flow. */
    public <T> T runOnceOrDefault(
            final String key,
            final int ttlMinutes,
            final Supplier<T> work,
            final T defaultIfLocked) {
        if (!tryAcquire(key, ttlMinutes)) {
            return defaultIfLocked;
        }
        try {
            return work.get();
        } finally {
            release(key);
        }
    }

    private boolean tryAcquire(final String key, final int ttlMinutes) {
        final long expiresAt = Instant.now().getEpochSecond() + (ttlMinutes * 60L);
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put("key", AttributeValue.fromS("lock:" + key));
        item.put("owner", AttributeValue.fromS(instanceId));
        item.put("ttl", AttributeValue.fromN(Long.toString(expiresAt)));

        // Conditional: row doesn't exist, or existing row has expired via TTL.
        final Map<String, AttributeValue> exprValues = new HashMap<>();
        exprValues.put(":now", AttributeValue.fromN(Long.toString(Instant.now().getEpochSecond())));

        try {
            client.putItem(
                    PutItemRequest.builder()
                            .tableName(lockTableName)
                            .item(item)
                            .conditionExpression("attribute_not_exists(#k) OR #ttl < :now")
                            .expressionAttributeNames(Map.of("#k", "key", "#ttl", "ttl"))
                            .expressionAttributeValues(exprValues)
                            .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        } catch (Exception e) {
            // If the lock table itself is broken we'd rather miss one cron run
            // than hammer DynamoDB — fail safe by not running.
            LOGGER.warn("Distributed-lock acquire failed for key={}: {}", key, e.getMessage());
            return false;
        }
    }

    private void release(final String key) {
        try {
            final Map<String, AttributeValue> exprValues = new HashMap<>();
            exprValues.put(":owner", AttributeValue.fromS(instanceId));

            client.deleteItem(
                    DeleteItemRequest.builder()
                            .tableName(lockTableName)
                            .key(Map.of("key", AttributeValue.fromS("lock:" + key)))
                            .conditionExpression("#o = :owner")
                            .expressionAttributeNames(Map.of("#o", "owner"))
                            .expressionAttributeValues(exprValues)
                            .build());
        } catch (ConditionalCheckFailedException ignored) {
            // Another instance took over after TTL — let them release.
        } catch (Exception e) {
            LOGGER.warn("Distributed-lock release failed for key={}: {}", key, e.getMessage());
        }
    }
}
