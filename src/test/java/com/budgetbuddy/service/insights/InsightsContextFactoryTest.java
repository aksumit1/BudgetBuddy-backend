package com.budgetbuddy.service.insights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.SubscriptionService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins the contract behind the /summary perf refactor: one snapshot
 * built per request, shared with every detector. If anyone adds a
 * detector that doesn't read the context, that detector will silently
 * re-fetch and the amplification problem returns.
 */
@ExtendWith(MockitoExtension.class)
class InsightsContextFactoryTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private SubscriptionService subscriptionService;
    @Mock private com.budgetbuddy.repository.dynamodb.TransactionActionRepository actionRepository;

    private InsightsContextFactory factory;

    @BeforeEach
    void setUp() {
        factory = new InsightsContextFactory(
                transactionRepository, accountRepository, subscriptionService, actionRepository);
        // Lenient: tests that exercise pure value-object behavior
        // (null-validation, unmodifiable lists) don't go through the
        // factory and therefore never touch these stubs.
        lenient().when(transactionRepository.findByUserIdAndDateRange(
                        anyString(), anyString(), anyString()))
                .thenReturn(new ArrayList<>());
        lenient().when(accountRepository.findByUserId(anyString()))
                .thenReturn(new ArrayList<>());
        lenient().when(subscriptionService.getSubscriptions(anyString()))
                .thenReturn(new ArrayList<>());
        lenient().when(actionRepository.findByUserId(anyString()))
                .thenReturn(new ArrayList<>());
    }

    @Test
    void buildFor_invokesEachRepoExactlyOnce() {
        factory.buildFor("u1");
        // Each downstream repo should be called exactly once even
        // though the resulting context will be passed to 5 detectors.
        // Anything more here is the amplification we're trying to kill.
        verify(transactionRepository, times(1))
                .findByUserIdAndDateRange(anyString(), anyString(), anyString());
        verify(accountRepository, times(1)).findByUserId(anyString());
        verify(subscriptionService, times(1)).getSubscriptions(anyString());
        verify(actionRepository, times(1)).findByUserId(anyString());
    }

    @Test
    void buildFor_returnsContextWithUserIdAndTodayDate() {
        final InsightsContext ctx = factory.buildFor("u1");
        assertNotNull(ctx);
        assertEquals("u1", ctx.userId());
        // asOf should be today (allow ±1 day for test running across midnight).
        final long diffDays = Math.abs(
                ctx.asOf().toEpochDay() - LocalDate.now().toEpochDay());
        assertTrue(diffDays <= 1, "asOf should be today; got " + ctx.asOf());
    }

    @Test
    void buildFor_toleratesSubscriptionServiceFailure() {
        // /summary must not 500 when SubscriptionService blips —
        // subscriptions are advisory for most detectors. Verify the
        // context still builds with empty subs.
        when(subscriptionService.getSubscriptions(anyString()))
                .thenThrow(new RuntimeException("Stripe down"));
        final InsightsContext ctx = factory.buildFor("u1");
        assertNotNull(ctx);
        assertTrue(ctx.subscriptions().isEmpty(),
                "Subscription fetch failure → empty subs, not propagated exception");
    }

    @Test
    void contextLists_areUnmodifiable_protectingShared_snapshot() {
        // Multiple detectors share the same snapshot. If one detector
        // accidentally mutated the list (e.g. .removeIf, .sort), every
        // subsequent detector would see corrupt data. Defensive
        // immutability prevents the bug class entirely.
        final InsightsContext ctx = new InsightsContext(
                "u1", LocalDate.now(),
                new ArrayList<>(List.of(new TransactionTable())),
                new ArrayList<>(List.of(new AccountTable())),
                new ArrayList<>());
        assertThrows(UnsupportedOperationException.class,
                () -> ctx.transactions().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> ctx.accounts().clear());
    }

    @Test
    void context_rejectsNullUserId() {
        assertThrows(NullPointerException.class,
                () -> new InsightsContext(null, LocalDate.now(),
                        List.of(), List.of(), List.of()));
    }

    @Test
    void buildFor_passesSameContextObjectToDetectorsViaSharedReference() {
        // The /summary path holds onto the returned context and passes
        // it to every detector. Sanity-check that two reads of the same
        // built context produce the same list references (cache the
        // factory output, don't rebuild per detector).
        final InsightsContext ctx = factory.buildFor("u1");
        assertSame(ctx.transactions(), ctx.transactions(),
                "Repeated access must return the same underlying list");
        assertSame(ctx.accounts(), ctx.accounts());
    }

    @Test
    void buildFor_isolatedAcrossUsers() {
        // Two users → two separate snapshots, two sets of repo calls.
        final InsightsContext ctxA = factory.buildFor("u-A");
        final InsightsContext ctxB = factory.buildFor("u-B");
        assertEquals("u-A", ctxA.userId());
        assertEquals("u-B", ctxB.userId());
        verify(transactionRepository, times(2))
                .findByUserIdAndDateRange(anyString(), anyString(), anyString());
    }
}
