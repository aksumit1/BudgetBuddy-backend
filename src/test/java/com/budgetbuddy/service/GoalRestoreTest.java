package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** G-OPP-3: restoreGoal undoes a soft-delete. */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class GoalRestoreTest {

    @Test
    void restoreClearsDeletedAt() {
        final GoalRepository goalRepo = mock(GoalRepository.class);
        final GoalTable existing = goal("g1", "u1");
        existing.setDeletedAt(Instant.now());
        when(goalRepo.findById("g1")).thenReturn(Optional.of(existing));
        when(goalRepo.saveWithLock(any(GoalTable.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        final GoalService svc = newService(goalRepo);
        final GoalTable restored = svc.restoreGoal(user("u1"), "g1");
        assertNull(restored.getDeletedAt(), "restore must clear the deletedAt stamp");
    }

    @Test
    void restoringLiveGoalIsNoOp() {
        final GoalRepository goalRepo = mock(GoalRepository.class);
        final GoalTable existing = goal("g1", "u1"); // deletedAt null
        when(goalRepo.findById("g1")).thenReturn(Optional.of(existing));

        final GoalService svc = newService(goalRepo);
        svc.restoreGoal(user("u1"), "g1");
        // Already-live goal must not touch the repo writes again.
        verify(goalRepo, never()).saveWithLock(any(GoalTable.class));
    }

    @Test
    void restoreRejectsOtherUsersGoal() {
        final GoalRepository goalRepo = mock(GoalRepository.class);
        final GoalTable existing = goal("g1", "owner");
        existing.setDeletedAt(Instant.now());
        when(goalRepo.findById("g1")).thenReturn(Optional.of(existing));

        final GoalService svc = newService(goalRepo);
        final AppException ex =
                assertThrows(AppException.class, () -> svc.restoreGoal(user("intruder"), "g1"));
        assertNotNull(ex);
    }

    private static GoalService newService(final GoalRepository goalRepo) {
        return new GoalService(goalRepo, mock(AccountRepository.class));
    }

    private static GoalTable goal(final String id, final String userId) {
        final GoalTable g = new GoalTable();
        g.setGoalId(id);
        g.setUserId(userId);
        g.setActive(true);
        return g;
    }

    private static UserTable user(final String id) {
        final UserTable u = new UserTable();
        u.setUserId(id);
        return u;
    }

}
