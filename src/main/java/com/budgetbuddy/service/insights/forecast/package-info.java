/**
 * Phase 1 of the {@code FinancialInsightsPredictionService} monolith
 * split. The original 1,205-LOC god-class has been carved into five
 * domain-specific Spring beans, each owning a single prediction
 * surface, plus a shared {@link
 * com.budgetbuddy.service.insights.forecast.ForecastMath} utility for
 * the statistical helpers they all need.
 *
 * <ul>
 *   <li>{@link com.budgetbuddy.service.insights.forecast.AnomalyForecaster}
 *       — predicts category spikes + amount-threshold breaches.</li>
 *   <li>{@link com.budgetbuddy.service.insights.forecast.ExpenseReductionForecaster}
 *       — predicts which subscriptions / categories will become
 *       cuttable.</li>
 *   <li>{@link com.budgetbuddy.service.insights.forecast.GoalAchievementForecaster}
 *       — estimates probability + timeline per goal.</li>
 *   <li>{@link com.budgetbuddy.service.insights.forecast.MissedPaymentForecaster}
 *       — projects which recurring payments are likely to slip.</li>
 *   <li>{@link com.budgetbuddy.service.insights.forecast.InterestCostForecaster}
 *       — projects 12-month interest-cost trajectory per account.</li>
 * </ul>
 *
 * <p>The original {@code FinancialInsightsPredictionService} is kept
 * as a thin façade that delegates each {@code predictXxx} call to the
 * corresponding bean in this package, so existing callers (controller,
 * tests) keep working unchanged until they migrate to the smaller
 * beans directly.
 *
 * <p>Result types (PredictedAnomaly, PredictedExpenseReduction, etc.)
 * stay on the original class as nested types. Moving them would have
 * forced every controller/test to update imports for no behavioural
 * gain.
 */
package com.budgetbuddy.service.insights.forecast;
