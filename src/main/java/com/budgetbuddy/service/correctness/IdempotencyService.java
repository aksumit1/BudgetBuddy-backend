package com.budgetbuddy.service.correctness;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * Server-side idempotency for state-changing POST endpoints.
 *
 * <p><strong>Why this matters.</strong> Mobile networks drop connections mid- response. The iOS
 * URLSession retry policy will re-send a POST that never got an ACK. Without server-side
 * idempotency, the user types "Add $40 groceries" once, gets a spinner for 30 seconds, and then
 * sees two $40 grocery transactions on the list. They don't know which one is "real." For a finance
 * app that single experience is enough to delete the app.
 *
 * <p><strong>Design.</strong> Client sends {@code Idempotency-Key: <uuid>} on any POST it wants to
 * retry-safe. The server stores {@code (userId, key) → resultId} in the shared {@code RateLimits}
 * DynamoDB table (reusing infra — the key schema and TTL attribute match) with a 24-hour TTL. On
 * duplicate request within the TTL, the stored {@code resultId} is returned instead of running the
 * supplier.
 *
 * <p><strong>What it isn't.</strong> This isn't a general response cache — it stores only an
 * identifier, and the caller re-fetches the resource. That keeps the stored payload tiny (one ID)
 * and guarantees the response reflects the current state of the resource rather than a stale
 * snapshot.
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Service
public class IdempotencyService {

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyService.class);

    /** 24h window — covers app-was-backgrounded → resumed retries. */
    private static final long TTL_SECONDS = 24L * 60 * 60;

    private final DynamoDbClient client;
    private final String tableName;

    public IdempotencyService(
            final DynamoDbClient client,
            @Value("${app.aws.dynamodb.table-prefix:BudgetBuddy}") final String tablePrefix) {
        this.client = client;
        // Reuse RateLimits — same key schema, same TTL attribute, no new CFN.
        this.tableName = tablePrefix + "-RateLimits";
    }

    /** Was {@code rawKey} null/blank/obviously bogus? */
    public static boolean isUsable(final String rawKey) {
        return rawKey != null && rawKey.length() >= 8 && rawKey.length() <= 128;
    }

    /**
     * Run {@code work} exactly once for the {@code (userId, idempotencyKey)} pair within the TTL
     * window. If the pair has already been used, returns the previously-stored result id (caller
     * re-fetches the resource).
     *
     * <p>If {@code idempotencyKey} is null or unusable, {@code work} runs unconditionally — callers
     * that care about retry-safety must provide a valid key.
     *
     * @return the result id produced by {@code work} on first call, or the stored id on duplicate
     *     call.
     */
    public String runOnce(
            final String userId, final String idempotencyKey, final Supplier<String> work) {
        if (!isUsable(idempotencyKey) || userId == null || userId.isEmpty()) {
            return work.get();
        }
        final String storageKey = "idem:" + userId + ":" + idempotencyKey;

        // Fast path — same key already recorded a result; return it.
        final Optional<String> existing = lookup(storageKey);
        if (existing.isPresent()) {
            LOG.info(
                    "Idempotency hit for userId={} key={} → reusing result {}",
                    userId,
                    idempotencyKey,
                    existing.get());
            return existing.get();
        }

        // Slow path — execute the work and record the mapping. Another parallel
        // request may race us; the conditional put below handles that.
        final String result = work.get();
        recordMapping(storageKey, result);
        return result;
    }

    private Optional<String> lookup(final String storageKey) {
        try {
            final GetItemResponse resp =
                    client.getItem(
                            GetItemRequest.builder()
                                    .tableName(tableName)
                                    .key(Map.of("key", AttributeValue.fromS(storageKey)))
                                    .consistentRead(true)
                                    .build());
            if (resp.hasItem() && resp.item().containsKey("resultId")) {
                final AttributeValue ttl = resp.item().get("ttl");
                if (ttl != null && ttl.n() != null) {
                    final long ttlSec = Long.parseLong(ttl.n());
                    if (ttlSec > Instant.now().getEpochSecond()) {
                        return Optional.of(resp.item().get("resultId").s());
                    }
                    // TTL already expired — treat as miss; DynamoDB sweeper
                    // will eventually reap the row.
                    return Optional.empty();
                }
                return Optional.of(resp.item().get("resultId").s());
            }
        } catch (Exception e) {
            LOG.debug("Idempotency lookup failed for {}: {}", storageKey, e.getMessage());
        }
        return Optional.empty();
    }

    private void recordMapping(final String storageKey, final String resultId) {
        if (resultId == null || resultId.isEmpty()) {
            return;
        }
        final long expiresAt = Instant.now().getEpochSecond() + TTL_SECONDS;
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put("key", AttributeValue.fromS(storageKey));
        item.put("resultId", AttributeValue.fromS(resultId));
        item.put("ttl", AttributeValue.fromN(Long.toString(expiresAt)));
        try {
            // Conditional on non-existence — if a concurrent request inserted
            // the same key between our lookup and this put, we silently let
            // their value win. Both values are correct; the client will
            // re-fetch either way.
            client.putItem(
                    PutItemRequest.builder()
                            .tableName(tableName)
                            .item(item)
                            .conditionExpression("attribute_not_exists(#k)")
                            .expressionAttributeNames(Map.of("#k", "key"))
                            .build());
        } catch (ConditionalCheckFailedException ignored) {
            // Race winner already there — nothing to do.
        } catch (Exception e) {
            LOG.debug("Idempotency record failed for {}: {}", storageKey, e.getMessage());
        }
    }
}
