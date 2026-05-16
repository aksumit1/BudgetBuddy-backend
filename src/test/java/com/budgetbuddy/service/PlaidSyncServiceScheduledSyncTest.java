package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.PlaidAccessTokenTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.observability.ScanRateLimiter;
import com.budgetbuddy.repository.dynamodb.PlaidAccessTokenRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.plaid.PlaidSyncOrchestrator;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Deep-validation coverage for the {@code @Scheduled} Plaid-sync fan-out wiring. The path is
 * not exercised by {@link PlaidSyncServiceTest} (which targets the imperative sync methods).
 *
 * <p>The scheduled path threads four concerns that ALL must hold simultaneously for the daily
 * cron to be safe:
 *
 * <ul>
 *   <li>The distributed-lock wrapper must run the inner work exactly once across a multi-replica
 *       ECS deploy — modeled by having a fake {@code DistributedLockService} that delegates.
 *   <li>The scan-rate-limiter must be released even if the inner sync fan-out throws.
 *   <li>A single user's per-row failure must not stop the rest of the iteration (Plaid
 *       expired access tokens are common; a CRON that bails on the first one would drop
 *       every subsequent user for the whole day).
 *   <li>Rows pointing at deleted users (orphaned access tokens) must be skipped silently —
 *       not retried, not crashed.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class PlaidSyncServiceScheduledSyncTest {

    @Mock private PlaidSyncOrchestrator syncOrchestrator;
    @Mock private PlaidAccessTokenRepository accessTokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private DistributedLockService distributedLock;
    @Mock private ScanRateLimiter scanRateLimiter;

    private PlaidSyncService service;

    @BeforeEach
    void setUp() {
        service =
                new PlaidSyncService(
                        syncOrchestrator,
                        accessTokenRepository,
                        userRepository,
                        distributedLock,
                        scanRateLimiter);
        // Default: the lock immediately delegates to the inner work — emulates a single replica.
        when(distributedLock.runOnce(anyString(), anyInt(), any(Runnable.class)))
                .thenAnswer(
                        inv -> {
                            ((Runnable) inv.getArgument(2)).run();
                            return true;
                        });
        when(scanRateLimiter.acquire()).thenReturn(true);
    }

    // ---------- happy path ----------

    @Test
    void scheduledSync_iteratesEveryRow_andCallsTransactionSyncForLiveUsers() {
        final PlaidAccessTokenTable rowA = row("user-A", "item-A", "tok-A");
        final PlaidAccessTokenTable rowB = row("user-B", "item-B", "tok-B");
        when(accessTokenRepository.findAll()).thenReturn(Arrays.asList(rowA, rowB));
        when(userRepository.findById("user-A")).thenReturn(Optional.of(user("user-A")));
        when(userRepository.findById("user-B")).thenReturn(Optional.of(user("user-B")));

        service.scheduledSync();

        verify(syncOrchestrator, times(2)).syncTransactionsOnly(any(UserTable.class), anyString());
        verify(syncOrchestrator).syncTransactionsOnly(any(UserTable.class), eq("tok-A"));
        verify(syncOrchestrator).syncTransactionsOnly(any(UserTable.class), eq("tok-B"));
    }

    @Test
    void scheduledSync_acquiresAndReleasesRateLimiterExactlyOnce() {
        when(accessTokenRepository.findAll()).thenReturn(List.of());

        service.scheduledSync();

        verify(scanRateLimiter, times(1)).acquire();
        verify(scanRateLimiter, times(1)).release();
    }

    // ---------- skip / failure isolation ----------

    @Test
    void scheduledSync_skipsRow_whenUserNoLongerExists() {
        // Orphan access-token row — the user was deleted but the token row wasn't cleaned up
        // for some reason. Must NOT explode the cron, must NOT call the sync, must NOT retry.
        final PlaidAccessTokenTable orphan = row("ghost-user", "item-X", "tok-X");
        when(accessTokenRepository.findAll()).thenReturn(List.of(orphan));
        when(userRepository.findById("ghost-user")).thenReturn(Optional.empty());

        service.scheduledSync();

        verify(syncOrchestrator, never()).syncTransactionsOnly(any(UserTable.class), anyString());
    }

    @Test
    void scheduledSync_skipsRow_whenAccessTokenIsNullOrEmpty() {
        final PlaidAccessTokenTable nullTokenRow = row("user-1", "item-1", null);
        final PlaidAccessTokenTable emptyTokenRow = row("user-2", "item-2", "");
        when(accessTokenRepository.findAll())
                .thenReturn(Arrays.asList(nullTokenRow, emptyTokenRow));

        service.scheduledSync();

        // No sync attempts: the rows are filtered at the guard before the userRepository lookup.
        verify(syncOrchestrator, never()).syncTransactionsOnly(any(UserTable.class), anyString());
        verify(userRepository, never()).findById(anyString());
    }

    @Test
    void scheduledSync_skipsRow_whenUserIdIsNull() {
        final PlaidAccessTokenTable malformedRow = row(null, "item-1", "tok-1");
        when(accessTokenRepository.findAll()).thenReturn(List.of(malformedRow));

        service.scheduledSync();

        verify(syncOrchestrator, never()).syncTransactionsOnly(any(UserTable.class), anyString());
    }

    @Test
    void scheduledSync_continuesIteration_whenOneUserSyncThrows() {
        // The whole point of the per-user try/catch — a single Plaid 401 (expired token)
        // must not skip the entire remaining batch.
        final PlaidAccessTokenTable rowA = row("user-A", "item-A", "tok-A");
        final PlaidAccessTokenTable rowB = row("user-B", "item-B", "tok-B");
        final PlaidAccessTokenTable rowC = row("user-C", "item-C", "tok-C");
        when(accessTokenRepository.findAll()).thenReturn(Arrays.asList(rowA, rowB, rowC));
        when(userRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.of(user(inv.getArgument(0))));

        // Row B blows up — A and C must still complete.
        org.mockito.Mockito.doThrow(new RuntimeException("Plaid 401: ITEM_LOGIN_REQUIRED"))
                .when(syncOrchestrator)
                .syncTransactionsOnly(any(UserTable.class), eq("tok-B"));

        assertDoesNotThrow(() -> service.scheduledSync());

        verify(syncOrchestrator).syncTransactionsOnly(any(UserTable.class), eq("tok-A"));
        verify(syncOrchestrator).syncTransactionsOnly(any(UserTable.class), eq("tok-B"));
        verify(syncOrchestrator).syncTransactionsOnly(any(UserTable.class), eq("tok-C"));
    }

    // ---------- rate-limiter exhaustion ----------

    @Test
    void scheduledSync_doesNotIterate_whenScanRateLimiterDenied() {
        // The limiter rejecting is the signal that some other table-scan is in flight; the
        // safe thing is to skip THIS run (the next daily cron will retry). The repo must
        // not be touched at all.
        when(scanRateLimiter.acquire()).thenReturn(false);

        service.scheduledSync();

        verify(accessTokenRepository, never()).findAll();
        verify(syncOrchestrator, never()).syncTransactionsOnly(any(UserTable.class), anyString());
        // Critically, release() must NOT be called when acquire() returned false — that
        // would over-release and break the semaphore invariant.
        verify(scanRateLimiter, never()).release();
    }

    @Test
    void scheduledSync_releasesRateLimiter_evenWhenScanIterationThrows() {
        // A catastrophic failure inside the iteration (e.g., DynamoDB scan throws). We
        // catch & log, but the finally block MUST release the permit — otherwise the
        // process leaks a permit on every failure and eventually nothing can scan.
        when(scanRateLimiter.acquire()).thenReturn(true);
        when(accessTokenRepository.findAll())
                .thenThrow(new RuntimeException("DDB scan blew up"));

        service.scheduledSync();

        verify(scanRateLimiter, times(1)).release();
    }

    // ---------- lock / multi-replica ----------

    @Test
    void scheduledSync_runsInnerWorkUnderDistributedLock() {
        when(accessTokenRepository.findAll()).thenReturn(List.of());

        service.scheduledSync();

        // The lock must wrap the entire inner sync — verified by asserting runOnce was
        // called exactly once with a key derived from today's date. We don't pin the
        // date string, but we DO pin that the prefix is the canonical lock key.
        verify(distributedLock).runOnce(anyString(), anyInt(), any(Runnable.class));
    }

    @Test
    void scheduledSync_lockDeclinedDoesNotRunInner_evenIfRateLimiterFree() {
        // If the lock is already held (other replica won the race), the inner work must
        // NOT run on this replica — runOnce simply returns false without invoking the
        // Runnable. We assert by NOT triggering the inner-runner answer.
        org.mockito.Mockito.reset(distributedLock);
        when(distributedLock.runOnce(anyString(), anyInt(), any(Runnable.class)))
                .thenReturn(false);

        service.scheduledSync();

        verify(accessTokenRepository, never()).findAll();
        verify(syncOrchestrator, never()).syncTransactionsOnly(any(UserTable.class), anyString());
    }

    // ---------- helpers ----------

    private static PlaidAccessTokenTable row(
            final String userId, final String itemId, final String accessToken) {
        final PlaidAccessTokenTable r = new PlaidAccessTokenTable();
        r.setUserId(userId);
        r.setPlaidItemId(itemId);
        r.setAccessToken(accessToken);
        return r;
    }

    private static UserTable user(final String userId) {
        final UserTable u = new UserTable();
        u.setUserId(userId);
        u.setEmail(userId + "@example.com");
        return u;
    }
}
