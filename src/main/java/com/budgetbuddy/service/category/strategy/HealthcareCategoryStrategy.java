package com.budgetbuddy.service.category.strategy;

import org.springframework.stereotype.Component;

/**
 * Strategy for detecting healthcare category
 */
@Component
public class HealthcareCategoryStrategy extends BaseCategoryStrategy {
    
    @Override
    public String detectCategory(String normalizedMerchantName, String descriptionLower, String merchantName) {
        if (normalizedMerchantName == null || normalizedMerchantName.trim().isEmpty()) {
            return null;
        }
        
        String[] healthcareProviders = {
            "cvs", "walgreens", "rite aid", "riteaid", "pharmacy",
            "urgent care", "urgentcare", "hospital", "clinic", "medical center",
            "medicalcenter", "doctor", "physician", "dentist", "dental"
        };
        for (String provider : healthcareProviders) {
            if (normalizedMerchantName.contains(provider) || descriptionLower.contains(provider)) {
                return "healthcare";
            }
        }
        
        
        
        return null; // No match found
    }
}
