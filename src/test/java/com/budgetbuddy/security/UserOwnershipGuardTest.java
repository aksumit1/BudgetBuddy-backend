package com.budgetbuddy.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the IDOR guard's filtering rules. The guard is a defensive
 * layer below the GSI-based repository queries — if any test here
 * fails, an attacker with a corrupted row could read another user's
 * data.
 */
class UserOwnershipGuardTest {

    private record Row(String userId, String label) {}

    @Test
    void allowsMatchingRows() {
        final List<Row> input = List.of(
                new Row("u1", "a"), new Row("u1", "b"), new Row("u1", "c"));
        assertEquals(3, UserOwnershipGuard.filter(input, "u1", Row::userId).size());
    }

    @Test
    void dropsRowsWithMismatchedUserId() {
        final List<Row> input = List.of(
                new Row("u1", "mine"),
                new Row("u2", "NOT-mine"),
                new Row("u1", "also-mine"));
        final List<Row> out = UserOwnershipGuard.filter(input, "u1", Row::userId);
        assertEquals(2, out.size());
        assertTrue(out.stream().noneMatch(r -> "NOT-mine".equals(r.label())),
                "Cross-user row must be filtered out");
    }

    @Test
    void dropsRowsWithNullUserId() {
        final List<Row> input = Arrays.asList(
                new Row("u1", "mine"),
                new Row(null, "null-id"),
                new Row("u1", "also-mine"));
        final List<Row> out = UserOwnershipGuard.filter(input, "u1", Row::userId);
        assertEquals(2, out.size());
    }

    @Test
    void dropsNullRows_silently() {
        final List<Row> input = Arrays.asList(new Row("u1", "real"), null);
        final List<Row> out = UserOwnershipGuard.filter(input, "u1", Row::userId);
        assertEquals(1, out.size());
    }

    @Test
    void emptyInput_returnsEmpty() {
        assertTrue(UserOwnershipGuard.filter(List.<Row>of(), "u1", Row::userId).isEmpty());
    }

    @Test
    void nullInput_returnsEmpty() {
        assertTrue(UserOwnershipGuard.filter((List<Row>) null, "u1", Row::userId).isEmpty());
    }

    @Test
    void nullExpectedUserId_passesThroughUnchanged() {
        // When the caller can't supply an expected userId (auth not
        // wired), we return the input as-is rather than dropping
        // everything — the caller is the wrong place to enforce auth.
        final List<Row> input = List.of(new Row("u1", "a"), new Row("u2", "b"));
        assertEquals(2, UserOwnershipGuard.filter(input, null, Row::userId).size());
    }

    @Test
    void getOwnerIdThrowing_dropsRow_doesNotPropagate() {
        // If the accessor throws (e.g. detached entity, lazy-loading
        // failure), the row gets dropped instead of failing the whole
        // request.
        final List<Row> input = List.of(new Row("u1", "ok"), new Row("u1", "throws"));
        final List<Row> out = UserOwnershipGuard.filter(input, "u1", r -> {
            if ("throws".equals(r.label())) {
                throw new RuntimeException("simulated detach");
            }
            return r.userId();
        });
        assertEquals(1, out.size());
        assertEquals("ok", out.get(0).label());
    }
}
