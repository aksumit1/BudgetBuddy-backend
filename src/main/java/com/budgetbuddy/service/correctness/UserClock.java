package com.budgetbuddy.service.correctness;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves "what day / hour / week is it for this user right now" using the user's stored timezone
 * preference.
 *
 * <p>Most places in the backend historically used {@code LocalDate.now()} or {@code
 * Instant.now().atZone(ZoneOffset.UTC)} which silently assumes the server clock is the user clock.
 * For any user outside UTC this pushes budget period boundaries, recurring-income cadence, and
 * daily-streak logic by up to a day. For a PT user it means budgets reset at 5pm local instead of
 * midnight; for a user who adds a purchase at 11pm local it means the purchase lands on yesterday's
 * budget.
 *
 * <p>This helper centralises the lookup so we can't reintroduce the bug by calling {@code
 * LocalDate.now()} directly. It also exposes a no-lookup overload that accepts a timezone string
 * when the caller already has the user row loaded — important for hot paths like the threshold
 * evaluator.
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Service
public class UserClock {

    private static final Logger LOG = LoggerFactory.getLogger(UserClock.class);
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Los_Angeles");

    private final UserRepository userRepository;

    public UserClock(final UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Resolve zone from a raw string; fall back to {@link #DEFAULT_ZONE} on null / blank / unknown
     * values.
     */
    public static ZoneId resolveZone(final String zoneString) {
        if (zoneString == null || zoneString.isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(zoneString.trim());
        } catch (Exception e) {
            LOG.debug("Unknown timezone '{}', defaulting to {}", zoneString, DEFAULT_ZONE);
            return DEFAULT_ZONE;
        }
    }

    /** Today in the user's local timezone (or {@link #DEFAULT_ZONE} if unknown). */
    public LocalDate today(final String userId) {
        return LocalDate.now(zoneFor(userId));
    }

    /** Today given a pre-loaded user (skips the repo fetch on hot paths). */
    public LocalDate today(final UserTable user) {
        return LocalDate.now(user == null ? DEFAULT_ZONE : resolveZone(user.getTimezone()));
    }

    /** User's current {@link ZoneId}. */
    public ZoneId zoneFor(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return DEFAULT_ZONE;
        }
        try {
            return userRepository
                    .findById(userId)
                    .map(u -> resolveZone(u.getTimezone()))
                    .orElse(DEFAULT_ZONE);
        } catch (Exception e) {
            LOG.debug("UserClock lookup failed for {}: {}", userId, e.getMessage());
            return DEFAULT_ZONE;
        }
    }
}
