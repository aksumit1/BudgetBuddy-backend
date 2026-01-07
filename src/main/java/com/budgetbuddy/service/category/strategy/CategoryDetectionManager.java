package com.budgetbuddy.service.category.strategy;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

/**
 * Category detection strategy manager
 * Orchestrates all category detection strategies
 */
@Component
public class CategoryDetectionManager {
    private final List<CategoryDetectionStrategy> strategies;
    
    public CategoryDetectionManager(List<CategoryDetectionStrategy> strategies) {
        this.strategies = strategies != null ? new ArrayList<>(strategies) : new ArrayList<>();
    }
    
    /**
     * Detect category using all registered strategies
     * @param normalizedMerchantName Normalized merchant name
     * @param descriptionLower Lowercase description  
     * @param merchantName Original merchant name
     * @return Detected category or null
     */
    public String detectCategory(String normalizedMerchantName, String descriptionLower, String merchantName) {
        for (CategoryDetectionStrategy strategy : strategies) {
            String category = strategy.detectCategory(normalizedMerchantName, descriptionLower, merchantName);
            if (category != null) {
                return category;
            }
        }
        return null;
    }
}
