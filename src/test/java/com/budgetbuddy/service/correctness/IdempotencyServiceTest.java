package com.budgetbuddy.service.correctness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * Tests pinning the retry-safety contract of {@link IdempotencyService}.
 *
 * <p>The bug these tests prevent: a mobile client drops a POST response, retries with the same
 * {@code Idempotency-Key}, and the server creates a second transaction / budget / goal row. The
 * user sees the duplicate on the list. In a finance app, that specific experience is the one users
 * don't forgive.
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    private static final String USER_1 = "user-1";
    private static final String KEY_ABCDEF_123456 = "key-abcdef-123456";

    @Mock private DynamoDbClient client;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(client, "BudgetBuddy");
    }

    @Test
    void firstCallRunsTheSupplierAndRecordsTheMapping() {
        // Lookup returns no existing row → we run the supplier and then
        // PUT the mapping under attribute_not_exists so a parallel caller
        // can find us.
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        final AtomicInteger invocations = new AtomicInteger();
        final String result =
                service.runOnce(
                        USER_1,
                        KEY_ABCDEF_123456,
                        () -> {
                            invocations.incrementAndGet();
                            return "tx-42";
                        });

        assertEquals("tx-42", result);
        assertEquals(1, invocations.get());
        final ArgumentCaptor<PutItemRequest> put = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client).putItem(put.capture());
        // Stored result id is what we just produced, and TTL is in the
        // future — not a past timestamp that would make the row expire on
        // arrival.
        assertEquals("tx-42", put.getValue().item().get("resultId").s());
        final long ttl = Long.parseLong(put.getValue().item().get("ttl").n());
        org.junit.jupiter.api.Assertions.assertTrue(
                ttl > Instant.now().getEpochSecond(), "TTL must be in the future");
    }

    @Test
    void duplicateCallReturnsStoredResultWithoutRunningSupplier() {
        // Lookup finds a previous mapping for the same (user, key). The
        // supplier MUST NOT run — this is the whole point of idempotency.
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put("resultId", AttributeValue.fromS("tx-original"));
        item.put("ttl", AttributeValue.fromN(Long.toString(Instant.now().getEpochSecond() + 3600)));
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());

        final AtomicInteger invocations = new AtomicInteger();
        final String result =
                service.runOnce(
                        USER_1,
                        KEY_ABCDEF_123456,
                        () -> {
                            invocations.incrementAndGet();
                            return "tx-duplicate";
                        });

        assertEquals("tx-original", result);
        assertEquals(0, invocations.get());
        // And we do NOT write a new mapping — would be a waste and could
        // bump the TTL unexpectedly.
        verify(client, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void expiredMappingIsTreatedAsMiss() {
        // Edge case: the row still exists in DynamoDB (the TTL sweeper has
        // a delay of up to 48h), but its TTL is in the past. We must treat
        // it as if it weren't there — the user's retry window is over and
        // they expect a fresh attempt to proceed.
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put("resultId", AttributeValue.fromS("tx-stale"));
        item.put("ttl", AttributeValue.fromN(Long.toString(Instant.now().getEpochSecond() - 3600)));
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());

        final AtomicInteger invocations = new AtomicInteger();
        final String result =
                service.runOnce(
                        USER_1,
                        KEY_ABCDEF_123456,
                        () -> {
                            invocations.incrementAndGet();
                            return "tx-new";
                        });

        assertEquals("tx-new", result);
        assertEquals(1, invocations.get());
    }

    @Test
    void parallelWinnerIsAcceptedGracefullyOnConditionalCheckFail() {
        // Two concurrent requests arrive. Both lookups miss. Both run their
        // suppliers (this is the correctness gap we accept — idempotency
        // is about duplicate CLIENT retries, not racing concurrent server-
        // side requests). Only one PUT wins the conditional write; the
        // loser must not throw into the caller.
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());
        when(client.putItem(any(PutItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("parallel").build());

        final String result = service.runOnce(USER_1, KEY_ABCDEF_123456, () -> "tx-loser");
        assertEquals("tx-loser", result); // We return our work — client re-fetches by id.
    }

    @Test
    void missingOrTooShortKeySkipsIdempotencyEntirely() {
        // Key must be 8–128 chars to be accepted. Shorter keys or null
        // are treated as "client doesn't care about retry safety" — the
        // supplier runs without touching DynamoDB at all (avoids cost on
        // every POST in the common case where clients haven't adopted
        // idempotency yet).
        final AtomicInteger invocations = new AtomicInteger();
        service.runOnce(
                USER_1,
                null,
                () -> {
                    invocations.incrementAndGet();
                    return "x";
                });
        service.runOnce(
                USER_1,
                "short",
                () -> {
                    invocations.incrementAndGet();
                    return "y";
                });
        service.runOnce(
                null,
                KEY_ABCDEF_123456,
                () -> {
                    invocations.incrementAndGet();
                    return "z";
                });

        assertEquals(3, invocations.get());
        verify(client, never()).getItem(any(GetItemRequest.class));
        verify(client, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void lookupReadsConsistentlyToAvoidStaleMiss() {
        // Strongly-consistent read matters: a non-consistent lookup could
        // miss a mapping the previous request just wrote on the same item,
        // causing the supplier to run twice. Verify we ask for consistency.
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());
        service.runOnce(USER_1, KEY_ABCDEF_123456, () -> "tx-1");

        final ArgumentCaptor<GetItemRequest> getCaptor =
                ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client, times(1)).getItem(getCaptor.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                Boolean.TRUE.equals(getCaptor.getValue().consistentRead()),
                "idempotency lookup must be consistent read");
    }
}
