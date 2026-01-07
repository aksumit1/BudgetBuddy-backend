package com.budgetbuddy.service.category.strategy;

import org.springframework.stereotype.Component;

/**
 * Strategy for detecting shopping category
 */
@Component
public class ShoppingCategoryStrategy extends BaseCategoryStrategy {
    
    @Override
    public String detectCategory(String normalizedMerchantName, String descriptionLower, String merchantName) {
        if (normalizedMerchantName == null || normalizedMerchantName.trim().isEmpty()) {
            return null;
        }
        
        // Sports equipment/gear (ski gear, outdoor equipment, etc.) - Must come BEFORE clothing/apparel
        if (normalizedMerchantName.contains("mini mountain") || descriptionLower.contains("mini mountain") ||
            normalizedMerchantName.contains("minimountain") || descriptionLower.contains("minimountain")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Mini Mountain (ski-gear) → 'shopping'");
            return "shopping";
        }
        // Sports equipment patterns
        if (normalizedMerchantName.contains("ski gear") || normalizedMerchantName.contains("skigear") ||
            normalizedMerchantName.contains("ski equipment") || normalizedMerchantName.contains("skiequipment") ||
            normalizedMerchantName.contains("sports equipment") || normalizedMerchantName.contains("sportsequipment") ||
            normalizedMerchantName.contains("outdoor gear") || normalizedMerchantName.contains("outdoorgear") ||
            descriptionLower.contains("ski gear") || descriptionLower.contains("sports equipment") ||
            descriptionLower.contains("outdoor gear")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected sports equipment/gear → 'shopping'");
            return "shopping";
        }
        
        // Clothing/Apparel patterns - Must come BEFORE general shopping stores
        String merchantLowerForShopping = (merchantName != null ? merchantName.toLowerCase() : "");
        String descLowerForShopping = descriptionLower != null ? descriptionLower : "";
        
        if (normalizedMerchantName.contains("clothing") || normalizedMerchantName.contains("apparel") ||
            normalizedMerchantName.contains("men's clothing") || normalizedMerchantName.contains("mens clothing") ||
            normalizedMerchantName.contains("women's clothing") || normalizedMerchantName.contains("womens clothing") ||
            normalizedMerchantName.contains("men's apparel") || normalizedMerchantName.contains("mens apparel") ||
            normalizedMerchantName.contains("women's apparel") || normalizedMerchantName.contains("womens apparel") ||
            descriptionLower.contains("clothing") || descriptionLower.contains("apparel") ||
            descriptionLower.contains("men's clothing") || descriptionLower.contains("mens clothing") ||
            descriptionLower.contains("women's clothing") || descriptionLower.contains("womens clothing") ||
            merchantLowerForShopping.contains("clothing") || merchantLowerForShopping.contains("apparel") ||
            merchantLowerForShopping.contains("men's") || merchantLowerForShopping.contains("mens") ||
            merchantLowerForShopping.contains("women's") || merchantLowerForShopping.contains("womens") ||
            descLowerForShopping.contains("clothing") || descLowerForShopping.contains("apparel") ||
            descLowerForShopping.contains("men's") || descLowerForShopping.contains("mens") ||
            descLowerForShopping.contains("women's") || descLowerForShopping.contains("womens")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected clothing/apparel → 'shopping'");
            return "shopping";
        }
        
        String[] shoppingStores = {
            "target", "walmart", "costco", "best buy", "bestbuy",
            "macy's", "macys", "nordstrom", "tj maxx", "tjmaxx",
            "marshalls", "ross", "burlington", "kohl's", "kohls",
            "daiso", "michaels", "michaels stores", "michaelsstores", "michael's", "michaels",
            "dollar tree", "dollartree", "dollar tree store", "dollartreestore",
            "store newcastle", "store new castle", "storenewcastle"
        };
        for (String store : shoppingStores) {
            if (normalizedMerchantName.contains(store) || descriptionLower.contains(store)) {
                return "shopping";
            }
        }
        // Daiso (retail chain)
        if (normalizedMerchantName.contains("daiso") || descriptionLower.contains("daiso")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Daiso → 'shopping'");
            return "shopping";
        }
        
        
        
        
        return null; // No match found
    }
}
