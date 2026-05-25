package com.budgetbuddy.service.pdf.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import com.budgetbuddy.service.TransactionService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Pins the per-row idempotency key contract:
 *
 * <ol>
 *   <li>Two same-day, same-amount, same-description rows produce DIFFERENT
 *       keys because rowIndex differs — both persist.</li>
 *   <li>Re-importing the SAME statement (same rowIndices) produces the
 *       SAME keys — dedup still works.</li>
 *   <li>Different users importing identical statements produce DIFFERENT
 *       keys — cross-user collision impossible.</li>
 *   <li>Same row from different filenames → DIFFERENT keys — re-naming
 *       the file is treated as a fresh import.</li>
 *   <li>rowIndex==null falls back to legacy keying (Plaid/manual paths
 *       which don't have row ordering).</li>
 * </ol>
 *
 * <p>Catches the regression where the rowIndex salt is dropped and two
 * Uber trips on the same day collapse to one record again.
 */
class V2IdempotencyKeyTest {

    private static String computeKey(final UserTable user, final ParsedTransaction p,
                                      final String fileName) throws Exception {
        // Build via reflection so we don't need a Spring context — the
        // method has no collaborator dependencies, just static IdGenerator.
        final TransactionService svc = newSvcUnitOnly();
        final Method m = TransactionService.class.getDeclaredMethod(
                "computeIdempotencyKey", UserTable.class, ParsedTransaction.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(svc, user, p, fileName);
    }

    private static TransactionService newSvcUnitOnly() {
        // computeIdempotencyKey is pure (uses static IdGenerator only) but
        // it's a private instance method, so we need an instance. The 6-arg
        // constructor is the canonical one.
        return new TransactionService(null, null, null, null, null, null);
    }

    private static UserTable user(final String id) {
        final UserTable u = new UserTable();
        u.setUserId(id);
        u.setEmail(id + "@test");
        return u;
    }

    private static ParsedTransaction tx(final String desc, final String amt, final int row) {
        final ParsedTransaction p = new ParsedTransaction();
        p.setDate(LocalDate.of(2026, 5, 1));
        p.setDescription(desc);
        p.setAmount(new BigDecimal(amt).negate());
        p.setAccountId("acct-1");
        p.setRowIndex(row);
        return p;
    }

    @Test
    void twoIdenticalRows_differentRowIndex_produceDifferentKeys() throws Exception {
        final UserTable u = user("user-1");
        final ParsedTransaction a = tx("UBER TRIP HELP.UBER.COM", "15.50", 7);
        final ParsedTransaction b = tx("UBER TRIP HELP.UBER.COM", "15.50", 8);
        final String keyA = computeKey(u, a, "march.pdf");
        final String keyB = computeKey(u, b, "march.pdf");
        assertNotEquals(keyA, keyB,
                "rows with different rowIndex must produce different keys — "
                        + "otherwise back-to-back same-amount Uber trips collapse");
    }

    @Test
    void reimport_sameStatement_sameRowIndices_produceSameKeys() throws Exception {
        // Same statement uploaded twice → same rowIndices → same UUIDs →
        // server-side dedup catches the re-import.
        final UserTable u = user("user-1");
        final ParsedTransaction first = tx("STARBUCKS", "5.75", 3);
        final ParsedTransaction second = tx("STARBUCKS", "5.75", 3);
        final String keyA = computeKey(u, first, "april.pdf");
        final String keyB = computeKey(u, second, "april.pdf");
        assertEquals(keyA, keyB, "re-import must be idempotent at the key layer");
    }

    @Test
    void differentUser_sameRow_differentKeys() throws Exception {
        // Cross-user collision impossible: user-1's Starbucks tx and
        // user-2's identical Starbucks tx must NOT collide.
        final ParsedTransaction sameRow = tx("STARBUCKS", "5.75", 3);
        final String keyA = computeKey(user("user-1"), sameRow, "april.pdf");
        final String keyB = computeKey(user("user-2"), sameRow, "april.pdf");
        assertNotEquals(keyA, keyB,
                "different users must produce different keys for identical rows");
    }

    @Test
    void differentFileName_sameRow_differentKeys() throws Exception {
        // Same statement saved with two different names is treated as
        // two fresh imports (user intent: "I renamed it, must be different").
        final UserTable u = user("user-1");
        final ParsedTransaction sameRow = tx("STARBUCKS", "5.75", 3);
        final String keyA = computeKey(u, sameRow, "march.pdf");
        final String keyB = computeKey(u, sameRow, "march-renamed.pdf");
        assertNotEquals(keyA, keyB);
    }

    @Test
    void nullRowIndex_stillProducesKey_doesNotThrow() throws Exception {
        final UserTable u = user("user-1");
        final ParsedTransaction noRow = tx("STARBUCKS", "5.75", -1);
        noRow.setRowIndex(null);
        final String key = computeKey(u, noRow, "march.pdf");
        assertNotNull(key, "null rowIndex must fall back to legacy keying");
    }

    @Test
    void keysAreValidUuids() throws Exception {
        final UserTable u = user("user-1");
        final ParsedTransaction p = tx("STARBUCKS", "5.75", 3);
        final String key = computeKey(u, p, "april.pdf");
        // Must parse as a UUID — fail loudly if the format is broken.
        java.util.UUID.fromString(key);
    }
}
