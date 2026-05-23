package com.budgetbuddy.service.goal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * G-AI-2: turns a free-text goal description ("save $3000 for a
 * honeymoon next June") into structured goal fields. Returns an
 * Optional so callers can decide whether to surface the parsed result
 * for user confirmation or fall back to the manual creation form.
 *
 * <p>Implementations must never call {@code createGoal} on the user's
 * behalf — the user must explicitly confirm. This service is a parse
 * layer only.
 */
public interface GoalNaturalLanguageParser {

    Optional<ParsedGoal> parse(String text);

    final class ParsedGoal {
        public String name;
        public String goalType;
        public BigDecimal targetAmount;
        public LocalDate targetDate;
        public String currencyCode;
        public String reasoning;

        public ParsedGoal() {}

        public ParsedGoal(
                final String name,
                final String goalType,
                final BigDecimal targetAmount,
                final LocalDate targetDate,
                final String currencyCode,
                final String reasoning) {
            this.name = name;
            this.goalType = goalType;
            this.targetAmount = targetAmount;
            this.targetDate = targetDate;
            this.currencyCode = currencyCode;
            this.reasoning = reasoning;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof ParsedGoal that)) return false;
            return Objects.equals(name, that.name)
                    && Objects.equals(goalType, that.goalType)
                    && Objects.equals(targetAmount, that.targetAmount)
                    && Objects.equals(targetDate, that.targetDate)
                    && Objects.equals(currencyCode, that.currencyCode)
                    && Objects.equals(reasoning, that.reasoning);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, goalType, targetAmount, targetDate, currencyCode, reasoning);
        }
    }
}
