package com.budgetbuddy.service;

import java.util.Locale;
import java.util.Set;

/**
 * Single source of truth for the "is this category income/savings (additive,
 * non-expense)?" classification used across the budget services.
 *
 * <p>Before this class existed, {@link BudgetSummaryService} had a private
 * {@code INCOME_OR_SAVINGS} set and {@link BudgetRolloverService} had its
 * own {@code isIncomeOrSavingsCategory} helper — identical content,
 * different declaration. Adding a category to one and forgetting the other
 * silently broke the rollover skip rule, the summary's additive-spend
 * branch, or both.
 *
 * <p>Add new income-or-savings category labels here, only.
 */
public final class BudgetCategoryClassifier {

    private static final Set<String> INCOME_OR_SAVINGS = Set.of(
            "income", "salary", "investment", "savings", "interest");

    private BudgetCategoryClassifier() {}

    /** True if the category is additive (income / inflow) rather than a spending cap. */
    public static boolean isIncomeOrSavings(final String category) {
        if (category == null) return false;
        return INCOME_OR_SAVINGS.contains(category.toLowerCase(Locale.ROOT));
    }
}
