package com.budgetbuddy.security;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Defensive post-fetch filter that enforces user-ownership on lists of
 * domain entities. The repository layer is supposed to query a userId
 * index, but if a row was written with a wrong/null userId (data
 * migration bug, manual DDB edit, corruption) the filter still drops
 * it before it leaks across users.
 *
 * <p>Logs every dropped row at WARN with the expected vs actual userId
 * so the on-call can spot data-integrity drift quickly. The
 * caller doesn't need to know — they just get a clean list back.
 *
 * <p>Belt-and-suspenders security: the AccountRepository's
 * {@code findByUserId} already filters by GSI; this is the secondary
 * net for the case where the row's userId field disagrees with the
 * GSI key (which is the IDOR vector we worry about).
 */
public final class UserOwnershipGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserOwnershipGuard.class);

    private UserOwnershipGuard() {
        throw new AssertionError("utility class");
    }

    /**
     * Filter {@code rows} to those whose {@code getOwnerId.apply(row)}
     * equals {@code expectedUserId}. Null rows and null owner ids are
     * dropped silently (they shouldn't exist; they probably indicate a
     * separate bug we don't want to mask). Mismatches are logged at
     * WARN with the entity class name so the source is searchable.
     */
    public static <T> List<T> filter(
            final List<T> rows,
            final String expectedUserId,
            final Function<T, String> getOwnerId) {
        if (rows == null || rows.isEmpty() || expectedUserId == null) {
            return rows == null ? List.of() : rows;
        }
        final List<T> out = rows.stream()
                .filter(r -> r != null)
                .filter(r -> matches(r, expectedUserId, getOwnerId))
                .toList();
        return out;
    }

    private static <T> boolean matches(
            final T row, final String expectedUserId, final Function<T, String> getOwnerId) {
        final String actual;
        try {
            actual = getOwnerId.apply(row);
        } catch (final RuntimeException e) {
            LOGGER.warn(
                    "UserOwnershipGuard: getOwnerId threw for {} — dropping row: {}",
                    row.getClass().getSimpleName(), e.getMessage());
            return false;
        }
        if (actual == null) {
            LOGGER.warn(
                    "UserOwnershipGuard: {} row has null userId; "
                            + "expected={}; dropping. Data integrity issue.",
                    row.getClass().getSimpleName(), expectedUserId);
            return false;
        }
        if (!expectedUserId.equals(actual)) {
            LOGGER.warn(
                    "UserOwnershipGuard: {} row carries wrong userId "
                            + "(expected={}, actual={}); dropping. IDOR vector blocked.",
                    row.getClass().getSimpleName(), expectedUserId, actual);
            return false;
        }
        return true;
    }
}
