package com.budgetbuddy.service.category.strategy;

import org.springframework.stereotype.Component;

/**
 * Strategy for detecting pet category
 */
@Component
public class PetCategoryStrategy extends BaseCategoryStrategy {
    
    @Override
    public String detectCategory(String normalizedMerchantName, String descriptionLower, String merchantName) {
        if (normalizedMerchantName == null || normalizedMerchantName.trim().isEmpty()) {
            return null;
        }
        
        if (normalizedMerchantName.contains("petsmart") || descriptionLower.contains("petsmart")) {
            return "pet";
        }
        if (normalizedMerchantName.contains("petco") || descriptionLower.contains("petco")) {
            return "pet";
        }
        // SP Farmers Fetch Bones
        if (normalizedMerchantName.contains("sp farmers") || normalizedMerchantName.contains("fetch bones") ||
            normalizedMerchantName.contains("fetchbones") || descriptionLower.contains("sp farmers") ||
            descriptionLower.contains("fetch bones") || descriptionLower.contains("fetchbones")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected SP Farmers Fetch Bones → 'pet'");
            return "pet";
        }
        if (normalizedMerchantName.contains("pet supplies") || normalizedMerchantName.contains("petsupplies") ||
            normalizedMerchantName.contains("pet supply") || descriptionLower.contains("pet supplies")) {
            return "pet";
        }
        if (normalizedMerchantName.contains("petcare") || normalizedMerchantName.contains("pet care") ||
            descriptionLower.contains("petcare") || descriptionLower.contains("pet care")) {
            return "pet";
        }
        if (normalizedMerchantName.contains("pet clinic") || normalizedMerchantName.contains("petclinic") ||
            normalizedMerchantName.contains("veterinary") || normalizedMerchantName.contains("vet ") ||
            normalizedMerchantName.contains("animal hospital") || descriptionLower.contains("pet clinic") ||
            descriptionLower.contains("veterinary")) {
            return "pet";
        }
        
        // Pet insurance (PETS BEST INSURANCE)
        if (normalizedMerchantName.contains("pets best insurance") || normalizedMerchantName.contains("petsbestinsurance") ||
            normalizedMerchantName.contains("pets best") || normalizedMerchantName.contains("petsbest") ||
            descriptionLower.contains("pets best insurance") || descriptionLower.contains("pets best") ||
            (merchantName != null && merchantName.toUpperCase().contains("PETS BEST"))) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Pets Best Insurance → 'pet'");
            return "pet";
        }
        
        // ========== SUBSCRIPTIONS (News/Investment Journals) ==========
        // CRITICAL: Must come BEFORE tech to prevent "J*Barrons" from matching tech patterns
        // Check both normalizedMerchantName and original strings for asterisks and special characters
        String merchantLower = (merchantName != null ? merchantName.toLowerCase() : "");
        String descLowerForJournals = descriptionLower != null ? descriptionLower : "";
        
        String[] newsJournals = {
            "barrons", "barron's", "barron",
            "new york times", "nytimes", "ny times", "the new york times",
            "wall street journal", "wsj", "the wall street journal",
            "financial times", "ft.com", "the financial times",
            "economist", "the economist", "bloomberg", "bloomberg news",
            "reuters", "reuters news", "cnn", "cnn news", "bbc", "bbc news",
            "washington post", "wapo", "the washington post",
            "usa today", "usatoday", "los angeles times", "latimes",
            "chicago tribune", "boston globe", "the boston globe"
        };
        for (String journal : newsJournals) {
            if (normalizedMerchantName.contains(journal) || descriptionLower.contains(journal) || 
                merchantLower.contains(journal) || descLowerForJournals.contains(journal)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected news/investment journal '{}' → 'subscriptions'", journal);
                return "subscriptions";
            }
        }
        // Special handling for patterns with asterisks (e.g., "J*Barrons")
        if ((merchantLower.contains("barrons") || merchantLower.contains("barron")) ||
            (descLowerForJournals.contains("barrons") || descLowerForJournals.contains("barron"))) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Barrons (with special chars) → 'subscriptions'");
            return "subscriptions";
        }
        
        
        
        return null; // No match found
    }
}
