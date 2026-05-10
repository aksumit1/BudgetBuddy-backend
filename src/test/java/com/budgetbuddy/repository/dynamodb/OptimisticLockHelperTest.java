package com.budgetbuddy.repository.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Tests pinning the optimistic-concurrency primitive used by every versioned repository (Accounts,
 * Budgets, Goals, Household, Transactions).
 *
 * <p>If this test ever fails, the lost-update protection on every user-facing mutation silently
 * breaks — these invariants are load- bearing across the whole service layer.
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
class OptimisticLockHelperTest {

    /**
     * Minimal bean for lock tests — avoids taking a DynamoDB Table schema dependency and lets tests
     * exercise the helper in isolation.
     */
    static class Row {
        private Long version;

        Long getVersion() {
            return version;
        }

        void setVersion(final Long v) {
            this.version = v;
        }
    }

    @Mock private DynamoDbTable<Row> table;

    @Test
    void firstWriteConditionsOnAttributeNotExistsAndSetsVersionToOne() {
        // Brand-new row: version is null. Helper must set it to 1L and
        // condition the put on attribute_not_exists(version) so two racing
        // creates can't both succeed.
        final Row row = new Row();
        doNothing().when(table).putItem(any(PutItemEnhancedRequest.class));

        OptimisticLockHelper.saveWithLock(table, row, Row::getVersion, row::setVersion, "new-row");

        assertEquals(1L, row.getVersion());
        final ArgumentCaptor<PutItemEnhancedRequest> captor =
                ArgumentCaptor.forClass(PutItemEnhancedRequest.class);
        verify(table).putItem(captor.capture());
        final String expr = captor.getValue().conditionExpression().expression();
        assertEquals("attribute_not_exists(version)", expr);
    }

    @Test
    void subsequentWriteConditionsOnVersionEqualsAndIncrementsByOne() {
        // Row was read at version 4. Next write must condition on
        // version == 4 and bump to 5. This is the entire point of the
        // lock: the put fails if anyone else wrote in between.
        final Row row = new Row();
        row.setVersion(4L);
        doNothing().when(table).putItem(any(PutItemEnhancedRequest.class));

        OptimisticLockHelper.saveWithLock(table, row, Row::getVersion, row::setVersion, "existing");

        assertEquals(5L, row.getVersion());
        final ArgumentCaptor<PutItemEnhancedRequest> captor =
                ArgumentCaptor.forClass(PutItemEnhancedRequest.class);
        verify(table).putItem(captor.capture());
        assertEquals(
                "version = :expectedVersion", captor.getValue().conditionExpression().expression());
        // And the bound :expectedVersion is the *original* 4, not the bumped 5.
        assertEquals(
                "4",
                captor.getValue()
                        .conditionExpression()
                        .expressionValues()
                        .get(":expectedVersion")
                        .n());
    }

    @Test
    void conflictRestoresOriginalVersionSoRetryCanReadIt() {
        // When DynamoDB rejects the put (someone else beat us), the helper
        // must put `version` back to the pre-write value. If it left the
        // row at version+1, a caller that retries (re-read then save)
        // would send a wrong :expectedVersion on the second attempt.
        final Row row = new Row();
        row.setVersion(7L);
        doThrow(ConditionalCheckFailedException.builder().message("lost race").build())
                .when(table)
                .putItem(any(PutItemEnhancedRequest.class));

        assertThrows(
                OptimisticLockHelper.OptimisticLockException.class,
                () ->
                        OptimisticLockHelper.saveWithLock(
                                table, row, Row::getVersion, row::setVersion, "conflict"));

        // Version rolled back to 7 — NOT 8.
        assertEquals(7L, row.getVersion());
    }

    @Test
    void firstWriteConflictAlsoRollsBackToNull() {
        // Parallel creates — both see version == null, both try to set
        // version = 1, one wins the attribute_not_exists. The loser must
        // roll back to null so the caller knows this was a new-create
        // race, not an update-race.
        final Row row = new Row(); // version == null
        doThrow(ConditionalCheckFailedException.builder().message("create race").build())
                .when(table)
                .putItem(any(PutItemEnhancedRequest.class));

        assertThrows(
                OptimisticLockHelper.OptimisticLockException.class,
                () ->
                        OptimisticLockHelper.saveWithLock(
                                table, row, Row::getVersion, row::setVersion, "create-race"));

        assertNull(row.getVersion());
    }

    @Test
    void exceptionMessageIncludesContextForDebugging() {
        // Production debugging depends on knowing which row conflicted.
        // The context string (e.g. "budgetId=123") is injected into the
        // exception so CloudWatch logs point at the right entity.
        final Row row = new Row();
        row.setVersion(2L);
        doThrow(ConditionalCheckFailedException.builder().message("x").build())
                .when(table)
                .putItem(any(PutItemEnhancedRequest.class));

        final OptimisticLockHelper.OptimisticLockException thrown =
                assertThrows(
                        OptimisticLockHelper.OptimisticLockException.class,
                        () ->
                                OptimisticLockHelper.saveWithLock(
                                        table,
                                        row,
                                        Row::getVersion,
                                        row::setVersion,
                                        "budgetId=abc-123"));

        org.junit.jupiter.api.Assertions.assertTrue(
                thrown.getMessage().contains("budgetId=abc-123"),
                "Context should appear in the exception message");
    }
}
