package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Cross-platform parity test for {@link BudgetSummaryService}. Loads the canonical JSON fixture at
 * {@code parity-fixtures/BudgetEngineParityFixture.json} and asserts that the Java service produces
 * the same outputs the iOS side asserts in {@code BudgetEngineParityTests}.
 *
 * <p>When math changes, update both implementations AND the fixture in the same PR.
 */
final class BudgetSummaryServiceParityTest {

    private static final BigDecimal EPS = new BigDecimal("0.01");

    @Test
    void fixtureScenariosMatchExpectedOutputs() throws Exception {
        final File fixture = locateFixture();
        Assumptions.assumeTrue(
                fixture != null,
                "parity-fixtures/BudgetEngineParityFixture.json not found — skipping");

        final ObjectMapper mapper = new ObjectMapper();
        final JsonNode root = mapper.readTree(fixture);
        final JsonNode scenarios = root.path("scenarios");
        assertNotNull(scenarios);

        // Build a service that never hits the repository — we pass transaction rows
        // directly into the test-only `buildOneAt(...)` entrypoint.
        final BudgetSummaryService service = new BudgetSummaryService(null, null);
        final UserTable user = new UserTable();
        user.setUserId("parity-user");

        for (final JsonNode scenario : scenarios) {
            final String name = scenario.path("name").asText();
            final LocalDate now = LocalDate.parse(scenario.path("now").asText());
            final JsonNode bNode = scenario.path("budget");
            final BudgetTable b = budget(bNode);
            final List<TransactionTable> rows = new ArrayList<>();
            for (final JsonNode tx : scenario.path("transactions")) {
                rows.add(tx(tx, bNode.path("category").asText()));
            }

            final BudgetSummaryService.BudgetSummaryDto dto = service.buildOneAt(user, b, rows, now);

            final BigDecimal expSpent = scenario.path("expected").path("spent").decimalValue();
            final BigDecimal expEff = scenario.path("expected").path("effectiveLimit").decimalValue();
            final BigDecimal expRem = scenario.path("expected").path("remaining").decimalValue();
            final BigDecimal expGoal =
                    scenario.path("expected").path("goalContributedSoFar").decimalValue();

            assertClose(expSpent, dto.spent, "scenario=" + name + " — spent");
            assertClose(expEff, dto.effectiveLimit, "scenario=" + name + " — effectiveLimit");
            assertClose(expRem, dto.remaining, "scenario=" + name + " — remaining");
            assertClose(
                    expGoal,
                    dto.goalContributedSoFar,
                    "scenario=" + name + " — goalContributedSoFar");
        }
    }

    // MARK - helpers

    private static BudgetTable budget(final JsonNode n) {
        final BudgetTable b = new BudgetTable();
        b.setBudgetId("parity-budget");
        b.setUserId("parity-user");
        b.setCategory(n.path("category").asText());
        b.setMonthlyLimit(n.path("amount").decimalValue());
        b.setCurrencyCode(n.path("currencyCode").asText("USD"));
        b.setPeriod(n.path("period").asText("monthly"));
        b.setRolloverEnabled(n.path("rolloverEnabled").asBoolean(false));
        b.setCarriedAmount(n.path("carriedAmount").decimalValue());
        if (n.hasNonNull("goalId")) {
            b.setGoalId(n.path("goalId").asText());
        }
        if (n.hasNonNull("goalAllocation")) {
            b.setGoalAllocation(n.path("goalAllocation").decimalValue());
        }
        return b;
    }

    private static TransactionTable tx(final JsonNode n, final String defaultCategory) {
        final TransactionTable t = new TransactionTable();
        // Use a fresh transaction id so deduplication logic in the real repo never
        // applies; the test drives `buildOneAt` directly.
        t.setTransactionId(java.util.UUID.randomUUID().toString());
        t.setUserId("parity-user");
        t.setTransactionDate(n.path("date").asText());
        t.setAmount(BigDecimal.valueOf(n.path("amount").asDouble()));
        final String cat = n.path("category").asText(defaultCategory);
        t.setCategoryPrimary(cat);
        if (n.hasNonNull("goalId")) {
            t.setGoalId(n.path("goalId").asText());
        }
        if (n.path("deleted").asBoolean(false)) {
            t.setDeletedAt(Instant.now());
        }
        return t;
    }

    private static void assertClose(final BigDecimal expected, final BigDecimal actual, final String message) {
        final BigDecimal e = expected == null ? BigDecimal.ZERO : expected;
        final BigDecimal a = actual == null ? BigDecimal.ZERO : actual;
        final BigDecimal diff = e.subtract(a).abs().setScale(6, RoundingMode.HALF_UP);
        assertEquals(
                true,
                diff.compareTo(EPS) <= 0,
                message + " (expected " + e + ", actual " + a + ", diff " + diff + ")");
    }

    /**
     * Walks up from the working directory to locate the shared fixture. The backend module's
     * working dir is the repo-relative backend folder; the fixture lives a level above it in
     * `../parity-fixtures/`.
     */
    private static File locateFixture() {
        File dir = new File(".").getAbsoluteFile();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParentFile()) {
            final File candidate = new File(dir, "parity-fixtures/BudgetEngineParityFixture.json");
            if (candidate.exists()) {
                return candidate;
            }
        }
        return null;
    }
}
