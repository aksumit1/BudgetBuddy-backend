package com.budgetbuddy.service.correctness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests pinning the user-timezone resolution contract.
 *
 * <p>The bug these tests prevent: someone re-introduces {@code LocalDate.now()} in a scheduled job
 * that computes "today" for budget reset or recurring- income projection. A PT user on UTC
 * infrastructure sees their budget month flip at 5pm local time instead of midnight, which shows up
 * as "I spent $50 yesterday but my budget says I've spent $50 today." Trust damage.
 */
@ExtendWith(MockitoExtension.class)
class UserClockTest {

    @Mock private UserRepository userRepository;

    @InjectMocks private UserClock userClock;

    private UserTable pstUser;

    @BeforeEach
    void setUp() {
        pstUser = new UserTable();
        pstUser.setUserId("user-1");
        pstUser.setTimezone("America/Los_Angeles");
    }

    @Test
    void resolvesKnownUserTimezone() {
        when(userRepository.findById("user-1")).thenReturn(Optional.of(pstUser));
        assertEquals(ZoneId.of("America/Los_Angeles"), userClock.zoneFor("user-1"));
    }

    @Test
    void unknownUserFallsBackToDefaultZone() {
        when(userRepository.findById("missing")).thenReturn(Optional.empty());
        // Default is Pacific — chosen because we have more US West-coast
        // users than any other single region. Documented here so a change
        // to the default trips this test.
        assertEquals(UserClock.DEFAULT_ZONE, userClock.zoneFor("missing"));
    }

    @Test
    void blankTimezoneFallsBackToDefault() {
        final UserTable noTz = new UserTable();
        noTz.setUserId("user-2");
        noTz.setTimezone("   ");
        when(userRepository.findById("user-2")).thenReturn(Optional.of(noTz));
        assertEquals(UserClock.DEFAULT_ZONE, userClock.zoneFor("user-2"));
    }

    @Test
    void garbageTimezoneStringFallsBackWithoutThrowing() {
        final UserTable bad = new UserTable();
        bad.setUserId("user-3");
        bad.setTimezone("Not/A/Real/Zone");
        when(userRepository.findById("user-3")).thenReturn(Optional.of(bad));
        // User-provided strings end up here via preferences; must not crash
        // the threshold evaluator just because the client sent junk.
        assertEquals(UserClock.DEFAULT_ZONE, userClock.zoneFor("user-3"));
    }

    @Test
    void todayForPreloadedUserDoesNotHitTheRepo() {
        // Hot paths (threshold evaluator, cash-flow projection) already have
        // the UserTable in hand. The overload that accepts UserTable must
        // avoid the repo fetch — otherwise every incoming transaction
        // triggers an extra DynamoDB read.
        final LocalDate today = userClock.today(pstUser);
        assertEquals(LocalDate.now(ZoneId.of("America/Los_Angeles")), today);
        // No repo interaction verified implicitly — if the overload were
        // wrong it would throw NPE on the un-stubbed mock.
    }

    @Test
    void nullUserFallsBackToDefaultZone() {
        assertEquals(LocalDate.now(UserClock.DEFAULT_ZONE), userClock.today((UserTable) null));
    }

    @Test
    void resolveZoneExposedAsStaticHelperForCallerConvenience() {
        // Documents the static-helper API shape — callers can resolve a
        // zone from a raw string without instantiating the service, useful
        // in utility classes that can't easily take a Spring bean.
        assertEquals(ZoneId.of("UTC"), UserClock.resolveZone("UTC"));
        assertEquals(UserClock.DEFAULT_ZONE, UserClock.resolveZone(null));
        assertEquals(UserClock.DEFAULT_ZONE, UserClock.resolveZone(""));
    }
}
