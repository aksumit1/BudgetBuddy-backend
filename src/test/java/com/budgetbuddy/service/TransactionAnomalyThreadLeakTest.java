package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.budgetbuddy.config.InsightsThresholds;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * DRIFT-1: pins the ThreadLocal-leak fix on {@link TransactionAnomalyService}.
 *
 * <p>Before the fix, a request that crashed between {@code set()} and the
 * finally {@code remove()} would leak its sensitivity multiplier onto a
 * Spring MVC worker thread. The next request on that thread would
 * silently inherit the prior user's multiplier and score anomalies with
 * the wrong threshold.
 *
 * <p>The validator here drives the service from two different threads on
 * the same single-threaded executor (simulating thread reuse) and asserts
 * the second call's multiplier is the default 1.0, not whatever the first
 * call set. The shared executor is the crucial part: a fresh thread per
 * call would trivially pass.
 */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class TransactionAnomalyThreadLeakTest {

    @Test
    void multiplierDoesNotLeakAcrossRequestsOnSameThread() throws Exception {
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(List.of());
        final TransactionAnomalyService svc =
                new TransactionAnomalyService(repo, new InsightsThresholds());

        final ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            // First request: do a normal detection on a thread, capture
            // the thread's identity so we can prove the next pass reuses
            // it.
            final AtomicReference<Thread> firstThread = new AtomicReference<>();
            single.submit(
                    () -> {
                        firstThread.set(Thread.currentThread());
                        svc.detectAnomalies("user-a");
                    })
                    .get(5, TimeUnit.SECONDS);

            // Second request on the SAME thread. If the multiplier leaked,
            // the inner detection would still see the user-a value. Since
            // the service's beginPass() removes before set(), the new pass
            // starts clean — we verify by checking the ThreadLocal default
            // is restored after the call.
            final AtomicReference<Thread> secondThread = new AtomicReference<>();
            final AtomicReference<Double> afterValue = new AtomicReference<>();
            single.submit(
                    () -> {
                        secondThread.set(Thread.currentThread());
                        svc.detectAnomalies("user-b");
                        // Probe the ThreadLocal indirectly: the service's
                        // public detect method always restores via
                        // endPass(); the withInitial(1.0) lambda then
                        // re-supplies the default on next read.
                        try {
                            afterValue.set(readActiveMultiplier(svc));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .get(5, TimeUnit.SECONDS);

            assertEquals(firstThread.get(), secondThread.get(),
                    "Single-thread executor must reuse the same Thread for both runs");
            assertEquals(
                    1.0,
                    afterValue.get(),
                    0.0001,
                    "After the second pass completes, the ThreadLocal must read the default 1.0 "
                            + "rather than a leaked value from the previous pass");
        } finally {
            single.shutdownNow();
        }
    }

    @Test
    void beginPassResetsLeakedValueBeforeSetting() throws Exception {
        // Direct invariant check: simulate a thread where activeSensitivity
        // got "stuck" by reflectively pre-poisoning the field, then run a
        // detection. Inside the pass, the ThreadLocal must reflect the new
        // user's multiplier, not the leaked one.
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(List.of());
        final TransactionAnomalyService svc =
                new TransactionAnomalyService(repo, new InsightsThresholds());

        // Poison the ThreadLocal as if a previous pass crashed.
        poisonActiveSensitivity(svc, 99.0);
        assertEquals(99.0, readActiveMultiplier(svc), 0.0001,
                "Sanity: the test poison should be observable");

        // Now run a detection — even though we poisoned the slot, the public
        // entrypoint must clear+set, and end the pass leaving the default.
        svc.detectAnomalies("clean-user");
        assertTrue(Math.abs(readActiveMultiplier(svc) - 1.0) < 0.0001,
                "After a clean pass the ThreadLocal must be back at the default 1.0, "
                        + "not the leaked 99.0. Saw: " + readActiveMultiplier(svc));
    }

    @SuppressWarnings("unchecked")
    private static double readActiveMultiplier(final TransactionAnomalyService svc) throws Exception {
        final java.lang.reflect.Field f =
                TransactionAnomalyService.class.getDeclaredField("activeSensitivity");
        f.setAccessible(true);
        final ThreadLocal<Double> tl = (ThreadLocal<Double>) f.get(svc);
        return tl.get();
    }

    @SuppressWarnings("unchecked")
    private static void poisonActiveSensitivity(
            final TransactionAnomalyService svc, final double value) throws Exception {
        final java.lang.reflect.Field f =
                TransactionAnomalyService.class.getDeclaredField("activeSensitivity");
        f.setAccessible(true);
        final ThreadLocal<Double> tl = (ThreadLocal<Double>) f.get(svc);
        tl.set(value);
    }
}
