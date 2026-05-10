package com.budgetbuddy.service.category.strategy;

import org.springframework.stereotype.Component;

/** Strategy for detecting healthcare category */
@Component
public class HealthcareCategoryStrategy extends BaseCategoryStrategy {

    @Override
    public String detectCategory(
            final String normalizedMerchantName, final String descriptionLower, final String merchantName) {
        if (normalizedMerchantName == null || normalizedMerchantName.isBlank()) {
            return null;
        }

        final String[] healthcareProviders = {
                "cvs", "walgreens", "rite aid", "riteaid", "pharmacy",
                "urgent care", "urgentcare", "hospital", "clinic", "medical center",
                "medicalcenter", "doctor", "physician", "dentist", "dental"
        };
        for (final String provider : healthcareProviders) {
            if (normalizedMerchantName.contains(provider) || descriptionLower.contains(provider)) {
                return "healthcare";
            }
        }

        return null; // No match found
    }
}
