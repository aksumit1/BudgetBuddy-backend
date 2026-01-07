package com.budgetbuddy.service.category.strategy;

import org.springframework.stereotype.Component;

/**
 * Strategy for detecting tech category
 */
@Component
public class TechCategoryStrategy extends BaseCategoryStrategy {
    
    @Override
    public String detectCategory(String normalizedMerchantName, String descriptionLower, String merchantName) {
        if (normalizedMerchantName == null || normalizedMerchantName.trim().isEmpty()) {
            return null;
        }
        
        // AI/Tech Services - Must come after streaming services to prevent false matches
        String[] aiTechServices = {
            "chatgpt", "chat gpt", "openai", "open ai",
            "anthropic", "anthropic ai", "claude", "claude ai",
            "cohere", "hugging face", "huggingface",
            "cursor", "cursor ai", "github copilot", "copilot",
            "replicate", "together ai", "togetherai"
        };
        for (String service : aiTechServices) {
            if (normalizedMerchantName.contains(service) || descriptionLower.contains(service)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected AI/tech service '{}' → 'tech'", service);
                return "tech";
            }
        }
        if (normalizedMerchantName.contains("cursor") || descriptionLower.contains("cursor") ||
            normalizedMerchantName.contains("cursor ai") || descriptionLower.contains("cursor ai")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Cursor → 'tech'");
            return "tech";
        }
        if (normalizedMerchantName.contains("ai powered") || descriptionLower.contains("ai powered") ||
            normalizedMerchantName.contains("artificial intelligence") || descriptionLower.contains("artificial intelligence")) {
            return "tech";
        }
        // Tech Companies
        String[] techCompanies = {
            "microsoft", "apple", "google", "amazon web services", "aws",
            "adobe", "oracle", "salesforce", "servicenow", "atlassian",
            "github", "gitlab", "slack", "zoom", "dropbox", "box",
            "notion", "figma", "sketch", "linear", "vercel", "netlify"
        };
        for (String company : techCompanies) {
            if (normalizedMerchantName.contains(company) || descriptionLower.contains(company)) {
                return "tech";
            }
        }
        
        // Software/Technology Patterns
        if (normalizedMerchantName.contains("software") || normalizedMerchantName.contains("saas") ||
            normalizedMerchantName.contains("cloud") || normalizedMerchantName.contains("api") ||
            normalizedMerchantName.contains("developer") || normalizedMerchantName.contains("dev tools") ||
            descriptionLower.contains("software") || 
            (descriptionLower.contains("subscription") && (normalizedMerchantName.contains("software") || 
             normalizedMerchantName.contains("saas") || normalizedMerchantName.contains("tech") ||
             normalizedMerchantName.contains("cloud") || normalizedMerchantName.contains("api")))) {
            return "tech";
        }
        
        // ========== HOME IMPROVEMENT ==========
        if (normalizedMerchantName.contains("home depot") || normalizedMerchantName.contains("homedepot") ||
            descriptionLower.contains("home depot")) {
            return "home improvement";
        }
        String[] homeImprovementStores = {
            "lowes", "lowe's", "menards", "ace hardware", "acehardware",
            "true value", "truevalue", "harbor freight", "harborfreight",
            "northern tool", "northerntool", "harbor freight tools"
        };
        for (String store : homeImprovementStores) {
            if (normalizedMerchantName.contains(store) || descriptionLower.contains(store)) {
                return "home improvement";
            }
        }
        
        // Hardware/Home Improvement Patterns
        if (normalizedMerchantName.contains("hardware") || normalizedMerchantName.contains("home improvement") ||
            normalizedMerchantName.contains("homeimprovement") || normalizedMerchantName.contains("lumber") ||
            normalizedMerchantName.contains("building supply") || descriptionLower.contains("hardware")) {
            return "home improvement";
        }
        
        // ========== DINING ==========
        // Bakeries (Hoffmans, Le Panier)
        if (normalizedMerchantName.contains("hoffman") || normalizedMerchantName.contains("hoffman's") ||
            descriptionLower.contains("hoffman") || descriptionLower.contains("hoffman's")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Hoffmans bakery → 'dining'");
            return "dining"; // Bakeries are dining
        }
        if (normalizedMerchantName.contains("le panier") || normalizedMerchantName.contains("lepanier") ||
            descriptionLower.contains("le panier") || descriptionLower.contains("lepanier")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Le Panier bakery → 'dining'");
            return "dining";
        }
        if (normalizedMerchantName.contains("bakery") || normalizedMerchantName.contains("baker") ||
            descriptionLower.contains("bakery") || descriptionLower.contains("baker")) {
            return "dining";
        }
        // Tea Lab, Chai, Boba, Mochinut
        if (normalizedMerchantName.contains("tea lab") || normalizedMerchantName.contains("tealab") ||
            normalizedMerchantName.contains("chai") || descriptionLower.contains("chai") ||
            normalizedMerchantName.contains("boba") || descriptionLower.contains("boba") ||
            normalizedMerchantName.contains("mochinut") || descriptionLower.contains("mochinut")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected tea/chai/boba → 'dining'");
            return "dining";
        }
        // Ezell's Famous Chicken
        if (normalizedMerchantName.contains("ezell") || normalizedMerchantName.contains("ezells") ||
            descriptionLower.contains("ezell") || descriptionLower.contains("ezells")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Ezell's Famous Chicken → 'dining'");
            return "dining";
        }
        // honest.bellevue.com
        if (normalizedMerchantName.contains("honest.bellevue") || normalizedMerchantName.contains("honest") ||
            descriptionLower.contains("honest.bellevue") || descriptionLower.contains("honest")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected honest.bellevue.com → 'dining'");
            return "dining";
        }
        
        
        
        return null; // No match found
    }
}
