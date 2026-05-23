package com.budgetbuddy.service.goal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * G-AI-1: optional LLM hook that turns a user's spend snapshot into a
 * shortlist of recommended financial goals (emergency fund, vacation,
 * down payment) with target amounts and time horizons.
 *
 * <p>Implementations must:
 * <ul>
 *   <li>Never invent goal types the user can't realistically pursue
 *       given the inputs (e.g. don't propose "down payment" when
 *       monthly disposable income is zero).
 *   <li>Always fall back gracefully — returning an empty list rather
 *       than throwing — so a degraded LLM never blocks the deterministic
 *       goal-recommendation path that ships today.
 * </ul>
 */
public interface GoalLlmSuggestionAdvisor {

    List<SuggestedGoal> suggest(SpendSnapshot snapshot);

    /** Aggregated user financial state fed to the advisor. */
    record SpendSnapshot(
            String userId,
            BigDecimal monthlyIncome,
            BigDecimal monthlyExpenses,
            BigDecimal liquidAssets,
            BigDecimal totalDebt,
            BigDecimal estimatedDisposable) {}

    /** Suggested goal — limit + horizon + one-sentence rationale. */
    final class SuggestedGoal {
        public String goalType;
        public String name;
        public BigDecimal targetAmount;
        public int targetMonths;
        public String reasoning;

        public SuggestedGoal() {}

        public SuggestedGoal(
                final String goalType,
                final String name,
                final BigDecimal targetAmount,
                final int targetMonths,
                final String reasoning) {
            this.goalType = goalType;
            this.name = name;
            this.targetAmount = targetAmount;
            this.targetMonths = targetMonths;
            this.reasoning = reasoning;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof SuggestedGoal that)) return false;
            return targetMonths == that.targetMonths
                    && Objects.equals(goalType, that.goalType)
                    && Objects.equals(name, that.name)
                    && Objects.equals(targetAmount, that.targetAmount)
                    && Objects.equals(reasoning, that.reasoning);
        }

        @Override
        public int hashCode() {
            return Objects.hash(goalType, name, targetAmount, targetMonths, reasoning);
        }
    }
}
