package com.budgetbuddy.service.category.strategy;

import org.springframework.stereotype.Component;

/**
 * Strategy for detecting charity category
 */
@Component
public class CharityCategoryStrategy extends BaseCategoryStrategy {
    
    @Override
    public String detectCategory(String normalizedMerchantName, String descriptionLower, String merchantName) {
        if (normalizedMerchantName == null || normalizedMerchantName.trim().isEmpty()) {
            return null;
        }
        
        // CRITICAL FIX: Schools are now EDUCATION, not charity
        // Only detect actual charity/donation organizations, NOT schools
        if (normalizedMerchantName.contains("go fund me") || normalizedMerchantName.contains("gofundme") ||
            descriptionLower.contains("go fund me") || descriptionLower.contains("gofundme")) {
            // Check if it's actually a school - if so, skip charity detection
            String[] schoolTypes = {"middle school", "middleschool", "high school", "highschool",
                                    "elementary school", "elementaryschool", "elementary",
                                    "secondary school", "secondaryschool", "senior secondary school",
                                    "college", "university", "phd", "ph.d", "school district",
                                    "schooldistrict"};
            boolean isSchool = false;
            for (String school : schoolTypes) {
                if (normalizedMerchantName.contains(school) || descriptionLower.contains(school)) {
                    isSchool = true;
                    break;
                }
            }
            if (!isSchool) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected Go Fund Me → 'charity'");
                return "charity";
            }
        }
        // Charity/donation patterns
        if (normalizedMerchantName.contains("charity") || normalizedMerchantName.contains("donation") ||
            normalizedMerchantName.contains("non-profit") || normalizedMerchantName.contains("nonprofit") ||
            descriptionLower.contains("charity") || descriptionLower.contains("donation")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected charity/donation → 'charity'");
            return "charity";
        }
        
        
        
        return null; // No match found
    }
}
