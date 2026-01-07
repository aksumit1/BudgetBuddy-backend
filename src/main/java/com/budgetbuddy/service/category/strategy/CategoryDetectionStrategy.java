package com.budgetbuddy.service.category.strategy;

/**
 * Strategy interface for category detection
 */
public interface CategoryDetectionStrategy {
    /**
     * Detect category from merchant name and description
     * @param normalizedMerchantName Normalized (lowercase) merchant name
     * @param descriptionLower Lowercase description
     * @param merchantName Original merchant name (for logging)
     * @return Category string if detected, null otherwise
     */
    String detectCategory(String normalizedMerchantName, String descriptionLower, String merchantName);
}
