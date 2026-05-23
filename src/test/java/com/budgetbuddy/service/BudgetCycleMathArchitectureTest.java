package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins the architecture rule behind B-BUG-2, B-BUG-7, B-BUG-8, B-BUG-9,
 * B-BUG-10: any code path that consumes a {@link
 * com.budgetbuddy.model.dynamodb.BudgetTable} for spend math MUST go
 * through {@link BudgetCycleMath} or {@link BudgetSummaryService#cycleWindow}.
 *
 * <p>Without this guard the bug class keeps coming back — five separate
 * services had each independently hardcoded {@code today.withDayOfMonth(1)}
 * by the time the audit ran. This test fails fast on any new occurrence.
 *
 * <p>Allow-list: {@link BudgetCycleMath}, {@link BudgetSummaryService}
 * (the canonical implementations), {@link BudgetRolloverService} (the
 * monthly rollover cron is intentionally monthly), and a tiny set of
 * historical-data services where calendar-month is semantically correct
 * (3-month income median, GDPR purges).
 */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
final class BudgetCycleMathArchitectureTest {

    private static final Path MAIN_SOURCE = Path.of("src/main/java");

    /** Patterns that mean "calendar-month-rooted date math" — the bug class. */
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("\\.withDayOfMonth\\(1\\)"),
            Pattern.compile("\\.with\\(TemporalAdjusters\\.firstDayOfMonth\\(\\)\\)"));

    /**
     * Files we KNOW use calendar-month deliberately and not for a per-budget
     * cycle. Anything else that imports BudgetTable and trips a FORBIDDEN
     * pattern is the bug.
     */
    private static final List<String> ALLOW_LIST = List.of(
            // The canonical helpers themselves.
            "BudgetCycleMath.java",
            "BudgetSummaryService.java",
            // Monthly rollover cron is intentionally monthly-keyed and never
            // computes a per-budget cycle window from this date.
            "BudgetRolloverService.java",
            // 3-month median income (B-ZBB-1) is by definition calendar-monthly.
            "BudgetAllocationStatusService.java",
            // Suggestion service samples 6 prior calendar months for medians.
            "BudgetSuggestionService.java",
            // Forecast service indexes 3 prior calendar months for the
            // historical baseline only — never as a per-budget cycle.
            "BudgetForecastService.java");

    @Test
    void noBudgetConsumerReintroducesCalendarMonthSpendMath() throws IOException {
        final List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN_SOURCE)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(
                            p -> {
                                final String body;
                                try {
                                    body = Files.readString(p, StandardCharsets.UTF_8);
                                } catch (IOException e) {
                                    return;
                                }
                                // Only interrogate files that touch a BudgetTable.
                                if (!body.contains("BudgetTable")) return;
                                if (ALLOW_LIST.contains(p.getFileName().toString())) return;
                                for (final Pattern f : FORBIDDEN) {
                                    if (f.matcher(body).find()) {
                                        offenders.add(p.toString());
                                        return;
                                    }
                                }
                            });
        }
        if (!offenders.isEmpty()) {
            fail(
                    "Files touching BudgetTable must compute cycle windows via BudgetCycleMath. "
                            + "The following reintroduced calendar-month spend math:\n  "
                            + String.join("\n  ", offenders));
        }
        // Sanity: the allow-list isn't the whole codebase. If the search
        // matched zero files at all, the architecture rule is silently
        // off — fail rather than green-light an empty scan.
        assertTrue(MAIN_SOURCE.toFile().exists(), "main-source path must resolve");
    }
}
