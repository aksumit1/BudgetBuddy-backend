package com.budgetbuddy.service.category.strategy;

import org.springframework.stereotype.Component;

/**
 * Strategy for detecting groceries category
 */
@Component
public class GroceriesCategoryStrategy extends BaseCategoryStrategy {
    
    @Override
    public String detectCategory(String normalizedMerchantName, String descriptionLower, String merchantName) {
        if (normalizedMerchantName == null || normalizedMerchantName.trim().isEmpty()) {
            return null;
        }
        
        // US Grocery Chains
        // CRITICAL FIX: Check for safeway with more lenient matching (handles variations like "SAFEWAY #1444")
        if (normalizedMerchantName.contains("safeway") || normalizedMerchantName.contains("safeway.com") || 
            normalizedMerchantName.startsWith("safeway") || normalizedMerchantName.endsWith("safeway")) {
            logger.debug("detectCategoryFromMerchantName: Detected Safeway in normalizedMerchantName='{}' → 'groceries'", normalizedMerchantName);
            return "groceries";
        }
        if (normalizedMerchantName.contains("pcc") || normalizedMerchantName.contains("pcc natural markets") || normalizedMerchantName.contains("pcc community markets")) {
            return "groceries";
        }
        if (normalizedMerchantName.contains("amazon fresh") || normalizedMerchantName.contains("amazonfresh") || 
            (normalizedMerchantName.contains("amazon") && (descriptionLower.contains("fresh") || descriptionLower.contains("grocery")))) {
            return "groceries";
        }
        if (normalizedMerchantName.contains("qfc") || normalizedMerchantName.contains("quality food centers") ||
            descriptionLower.contains("qfc") || descriptionLower.contains("quality food centers")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected QFC → 'groceries'");
            return "groceries";
        }
        // Chefstore (grocery store)
        if (normalizedMerchantName.contains("chefstore") || normalizedMerchantName.contains("chef store") ||
            descriptionLower.contains("chefstore") || descriptionLower.contains("chef store")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Chefstore → 'groceries'");
            return "groceries";
        }
        // Town & Country market (pantry/market patterns are typically grocery stores)
        if (normalizedMerchantName.contains("town & country") || normalizedMerchantName.contains("town and country") ||
            normalizedMerchantName.contains("town&country") || descriptionLower.contains("town & country") ||
            descriptionLower.contains("town and country") || descriptionLower.contains("town&country")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Town & Country market → 'groceries'");
            return "groceries";
        }
        // Mayuri Foods (grocery store)
        if (normalizedMerchantName.contains("mayuri") || descriptionLower.contains("mayuri")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Mayuri Foods → 'groceries'");
            return "groceries";
        }
        // Meet Fresh (grocery store)
        if (normalizedMerchantName.contains("meet fresh") || normalizedMerchantName.contains("meetfresh") ||
            descriptionLower.contains("meet fresh") || descriptionLower.contains("meetfresh")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Meet Fresh → 'groceries'");
            return "groceries";
        }
        // Pantry/market patterns (typically grocery stores)
        if ((normalizedMerchantName.contains("pantry") || normalizedMerchantName.contains("market")) &&
            !normalizedMerchantName.contains("parking") && !descriptionLower.contains("parking")) {
            logger.debug("detectCategoryFromMerchantName: Detected pantry/market pattern → 'groceries'");
            return "groceries";
        }
        if (normalizedMerchantName.contains("sunny honey") || normalizedMerchantName.contains("sunnyhoney") ||
            descriptionLower.contains("sunny honey") || descriptionLower.contains("sunnyhoney")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Sunny Honey Company → 'groceries'");
            return "groceries";
        }
        if (normalizedMerchantName.contains("dk market") || normalizedMerchantName.contains("dkmarket")) {
            return "groceries";
        }
        // CRITICAL: COSTCO GAS must be checked BEFORE general COSTCO (groceries)
        if (normalizedMerchantName.contains("costco gas") || normalizedMerchantName.contains("costcogas") ||
            descriptionLower.contains("costco gas") || descriptionLower.contains("costcogas")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Costco Gas → 'transportation'");
            return "transportation";
        }
        
        // CRITICAL: COSTCO WHSE (Costco Warehouse) must be checked before general "costco"
        // "COSTCO WHSE" is a store location, not "costco wholesale corporation" (payroll)
        if (normalizedMerchantName.contains("costco whse") || normalizedMerchantName.contains("costcowhse") ||
            normalizedMerchantName.contains("costco warehouse") || normalizedMerchantName.contains("costcowarehouse") ||
            descriptionLower.contains("costco whse") || descriptionLower.contains("costcowhse") ||
            descriptionLower.contains("costco warehouse") || descriptionLower.contains("costcowarehouse")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Costco Warehouse (COSTCO WHSE) → 'groceries'");
            return "groceries";
        }
        
        // Walmart Plus subscription (WMT PLUS)
        if (normalizedMerchantName.contains("wmt plus") || normalizedMerchantName.contains("wmtplus") ||
            normalizedMerchantName.contains("walmart plus") || normalizedMerchantName.contains("walmartplus") ||
            descriptionLower.contains("wmt plus") || descriptionLower.contains("walmart plus")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Walmart Plus subscription → 'groceries'");
            return "groceries";
        }
        
        // Additional US grocery chains
        String[] usGroceryStores = {
            "whole foods", "wholefoods", "wf ", "trader joe", "traderjoe", "kroger",
            "albertsons", "publix", "wegmans", "h-e-b", "heb", "stop & shop", "stopandshop",
            "giant eagle", "gianteagle", "meijer", "food lion", "foodlion",
            "ralphs", "vons", "fred meyer", "fredmeyer", "fred-meyer", "smiths", "king soopers",
            "harris teeter", "harristeeter", "sprouts", "sprouts farmers market",
            "aldi", "lidl", "costco", "sam's club", "samsclub", "bj's", "bjs",
            "target", "walmart", "walmart supercenter", "supercenter"
        };
        for (String store : usGroceryStores) {
            if (normalizedMerchantName.contains(store)) {
                return "groceries";
            }
        }
        
        // Indian Grocery Stores
        String[] indianGroceryStores = {
            "indian supermarket", "indian store", "indian market", "indian grocery",
            "patel brothers", "patelbrothers", "apna bazaar", "apnabazaar",
            "namaste plaza", "namasteplaza", "bombay bazaar", "bombaybazaar",
            "india gate", "indiagate", "spice bazaar", "spicebazaar"
        };
        for (String store : indianGroceryStores) {
            if (normalizedMerchantName.contains(store) || descriptionLower.contains(store)) {
                return "groceries";
            }
        }
        
        // Global Grocery Patterns
        if (normalizedMerchantName.contains("supermarket") || normalizedMerchantName.contains("super market") ||
            normalizedMerchantName.contains("grocery") || normalizedMerchantName.contains("grocery store") ||
            normalizedMerchantName.contains("food market") || normalizedMerchantName.contains("foodmart") ||
            normalizedMerchantName.contains("hypermarket") || normalizedMerchantName.contains("hyper market")) {
            return "groceries";
        }
        
        
        
        return null; // No match found
    }
}
