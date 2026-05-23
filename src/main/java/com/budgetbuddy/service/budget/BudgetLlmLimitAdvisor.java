package com.budgetbuddy.service.budget;

import com.budgetbuddy.service.BudgetSuggestionService.BudgetSuggestion;
import java.util.List;

/**
 * B-AI-1: optional LLM hook that annotates rule-based budget suggestions with
 * a one-sentence reasoning string. Returns the suggestions list with the
 * {@code reasoning} field populated; never re-prices the limit so the
 * rule-based recommendation remains authoritative.
 */
public interface BudgetLlmLimitAdvisor {
    List<BudgetSuggestion> annotate(List<BudgetSuggestion> rulesBased);
}
