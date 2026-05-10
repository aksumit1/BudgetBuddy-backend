package com.budgetbuddy.service.circuitbreaker;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * P2: Circuit breaker service for ML service calls Prevents cascading failures when ML service is
 * down
 */
@Service
public class CircuitBreakerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CircuitBreakerService.class);

    // Circuit breaker state
    private enum State {
        CLOSED, // Normal operation
        OPEN, // Circuit is open (failing)
        HALF_OPEN // Testing if service recovered
    }

    private volatile State state = State.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);

    // Configuration
    private static final int FAILURE_THRESHOLD = 5; // Open circuit after 5 failures
    private static final long TIMEOUT_MS = 60_000; // Keep circuit open for 60 seconds
    private static final int HALF_OPEN_SUCCESS_THRESHOLD =
            2; // Close after 2 successes in half-open

    private final AtomicInteger halfOpenSuccessCount = new AtomicInteger(0);

    /** Executes a call with circuit breaker protection */
    public <T> T execute(
            final String serviceName, final java.util.function.Supplier<T> operation, final T fallbackValue) {
        if (state == State.OPEN) {
            // Check if timeout has passed
            final long timeSinceLastFailure = System.currentTimeMillis() - lastFailureTime.get();
            if (timeSinceLastFailure > TIMEOUT_MS) {
                // Transition to half-open
                state = State.HALF_OPEN;
                halfOpenSuccessCount.set(0);
                LOGGER.info("Circuit breaker for {} transitioning to HALF_OPEN", serviceName);
            } else {
                // Circuit is still open, return fallback
                LOGGER.debug("Circuit breaker for {} is OPEN, returning fallback", serviceName);
                return fallbackValue;
            }
        }

        try {
            final T result = operation.get();

            // Success - reset failure count
            if (state == State.HALF_OPEN) {
                final int successes = halfOpenSuccessCount.incrementAndGet();
                if (successes >= HALF_OPEN_SUCCESS_THRESHOLD) {
                    state = State.CLOSED;
                    failureCount.set(0);
                    LOGGER.info(
                            "Circuit breaker for {} transitioning to CLOSED after {} successes",
                            serviceName,
                            successes);
                }
            } else {
                failureCount.set(0);
            }

            return result;
        } catch (Exception e) {
            // Failure - increment failure count
            final int failures = failureCount.incrementAndGet();
            lastFailureTime.set(System.currentTimeMillis());

            LOGGER.warn(
                    "Circuit breaker for {}: failure {} of {}",
                    serviceName,
                    failures,
                    FAILURE_THRESHOLD);

            if (failures >= FAILURE_THRESHOLD && state != State.OPEN) {
                state = State.OPEN;
                LOGGER.error(
                        "Circuit breaker for {} is now OPEN after {} failures",
                        serviceName,
                        failures);
            }

            if (state == State.HALF_OPEN) {
                // Failed in half-open, go back to open
                state = State.OPEN;
                halfOpenSuccessCount.set(0);
                LOGGER.warn(
                        "Circuit breaker for {} transitioning back to OPEN from HALF_OPEN",
                        serviceName);
            }

            return fallbackValue;
        }
    }

    /** Gets current circuit breaker state */
    public State getState() {
        return state;
    }

    /** Manually reset circuit breaker (for testing/admin) */
    public void reset() {
        state = State.CLOSED;
        failureCount.set(0);
        halfOpenSuccessCount.set(0);
        LOGGER.info("Circuit breaker manually reset");
    }
}
