package com.budgetbuddy.service.category.strategy;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Category detection strategy manager Orchestrates all category detection strategies */
@Component
public class CategoryDetectionManager {
    private final List<CategoryDetectionStrategy> strategies;

    public CategoryDetectionManager(final List<CategoryDetectionStrategy> strategies) {
        this.strategies = strategies != null ? new ArrayList<>(strategies) : new ArrayList<>();
    }

    /**
     * Detect category using all registered strategies
     *
     * @param normalizedMerchantName Normalized merchant name
     * @param descriptionLower Lowercase description
     * @param merchantName Original merchant name
     * @return Detected category or null
     */
    public String detectCategory(
            final String normalizedMerchantName, final String descriptionLower, final String merchantName) {
        for (final CategoryDetectionStrategy strategy : strategies) {
            final String category =
                    strategy.detectCategory(normalizedMerchantName, descriptionLower, merchantName);
            if (category != null) {
                return category;
            }
        }
        return null;
    }
}
