package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins the bounded-cache + invalidation behaviour on
 * {@link SubscriptionInsightsService}. The cache is an optimization so
 * tests focus on three contracts that matter under load:
 * <ul>
 *   <li>Same user, two consecutive calls → repo is hit once (cache works)</li>
 *   <li>{@code invalidateUser(id)} forces the next call to re-fetch</li>
 *   <li>The cache cannot grow unbounded — once {@code TX_CACHE_MAX_USERS}
 *       is reached, eviction reclaims slots</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionInsightsServiceCacheTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private SubscriptionService subscriptionService;
    private SubscriptionInsightsService svc;

    @BeforeEach
    void setUp() {
        svc = new SubscriptionInsightsService(transactionRepository, subscriptionService, 5.0);
        // lenient: the no-op invalidate tests don't hit the repo at all
        lenient().when(transactionRepository.findByUserId(anyString(), anyInt(), anyInt()))
                .thenAnswer(inv -> new ArrayList<TransactionTable>());
    }

    @Test
    void invalidateUser_forcesRepoHitOnNextRequest() {
        // Two back-to-back insights calls for the same user must share
        // one repo fetch (cache hit). After invalidate, the next call
        // must hit the repo again.
        svc.detectUnusedSubscriptions("u1");
        svc.detectUnusedSubscriptions("u1");
        verify(transactionRepository, times(1)).findByUserId(anyString(), anyInt(), anyInt());

        svc.invalidateUser("u1");
        svc.detectUnusedSubscriptions("u1");
        verify(transactionRepository, times(2)).findByUserId(anyString(), anyInt(), anyInt());
    }

    @Test
    void invalidateUser_isSafeWhenNoCacheEntry() {
        // Must not throw on first-time invalidation (e.g. user logs out
        // before their first insights request).
        svc.invalidateUser("never-cached");
    }

    @Test
    void invalidateUser_withNullId_isNoOp() {
        // Defensive: caller may have lost the userId; silently no-op
        // rather than NPE.
        svc.invalidateUser(null);
    }

    @Test
    void cache_neverGrowsAboveBound_underManyDistinctUsers() {
        // Hammer the cache with way more user IDs than the cap. We
        // can't directly read the map size, but we can assert
        // eviction is happening by verifying repo gets called
        // significantly more times than the cap, indicating the
        // earliest entries got evicted and re-fetched.
        final int cap = 1_000;
        final int users = cap * 3;
        for (int i = 0; i < users; i++) {
            svc.detectUnusedSubscriptions("u-" + i);
        }
        // Each unique user is a guaranteed miss → 3000 repo calls minimum.
        verify(transactionRepository, atLeast(users))
                .findByUserId(anyString(), anyInt(), anyInt());

        // Now revisit user-0. If the cache had been unbounded, this
        // would hit. If eviction worked, user-0 was kicked out long ago
        // and we re-fetch.
        svc.detectUnusedSubscriptions("u-0");
        verify(transactionRepository, atLeast(users + 1))
                .findByUserId(anyString(), anyInt(), anyInt());
        // The exact number depends on eviction order, so we use
        // atLeast. Lower bound: the user-0 re-fetch must have happened
        // (otherwise the assertion would fail).
        assertTrue(true);
    }

    @Test
    void cache_isHitForRepeatedFetchesOfSameUser() {
        // Smoke: 10 calls for the same user → 1 repo fetch.
        for (int i = 0; i < 10; i++) {
            svc.detectUnusedSubscriptions("hot-user");
        }
        verify(transactionRepository, times(1)).findByUserId(anyString(), anyInt(), anyInt());
        assertEquals(1, 1); // explicit pass marker
    }
}
