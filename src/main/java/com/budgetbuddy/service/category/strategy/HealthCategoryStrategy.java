package com.budgetbuddy.service.category.strategy;

import org.springframework.stereotype.Component;

/**
 * Strategy for detecting health category
 */
@Component
public class HealthCategoryStrategy extends BaseCategoryStrategy {
    
    @Override
    public String detectCategory(String normalizedMerchantName, String descriptionLower, String merchantName) {
        if (normalizedMerchantName == null || normalizedMerchantName.trim().isEmpty()) {
            return null;
        }
        
        // Health, fitness, gyms, beauty salons, hair cuts, golf, tennis, soccer, ski resorts, etc.
        String[] gymsAndFitness = {
            "proclub", "pro club", "24 hour fitness", "24hour fitness", "24hr fitness",
            "24-hour fitness", "24-hourfitness", "gold's gym", "golds gym", "goldsgym",
            "planet fitness", "planetfitness", "equinox", "lifetime fitness", "lifetimefitness",
            "ymca", "ymca fitness", "la fitness", "lafitness", "crunch fitness", "crunchfitness",
            "anytime fitness", "anytimefitness", "orange theory", "orangetheory", "crossfit",
            "fitness", "gym", "health club", "healthclub", "athletic club", "athleticclub",
            "fitness center", "fitnesscenter", "workout", "personal trainer", "personaltrainer",
            "seattle badminton club", "seattlebadminton club", "seattlebadmintonclub", "badminton club", "badmintonclub", "badminton"
        };
        for (String gym : gymsAndFitness) {
            if (normalizedMerchantName.contains(gym) || descriptionLower.contains(gym)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected gym/fitness '{}' → 'health'", gym);
                return "health";
            }
        }
        
        // Beauty salons, hair cuts, makeup, body waxing, nails, spa, massages, toes, skin
        String[] beautyServices = {
            "beauty salon", "beautysalon", "beauty parlor", "beautyparlor",
            "hair salon", "hairsalon", "hair cut", "haircut", "hair cuts", "haircuts",
            "hair color", "haircolor", "hair coloring", "haircoloring",
            "body waxing", "bodywaxing", "waxing", "makeup", "make up",
            "beauty studio", "beautystudio", "salon", "saloon",
            "supercuts", "super cuts", "great clips", "greatclips",
            "lucky hair salon", "lucky hair salin", "luckyhair", "luckyhairsalin",
            "nails", "nail salon", "nailsalon", "nail", "manicure", "pedicure",
            "spa", "massage", "massages", "toes", "skin", "skin care", "skincare",
            "stop 4 nails", "stop4nails", "stop four nails", "stopfournails",
            "cosmetic store", "cosmeticstore", "cosmetics", "makeup store", "makeupstore"
        };
        for (String beauty : beautyServices) {
            if (normalizedMerchantName.contains(beauty) || descriptionLower.contains(beauty)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected beauty service '{}' → 'health'", beauty);
                return "health";
            }
        }
        
        // Golf (indoor and outdoor)
        if (normalizedMerchantName.contains("golf") || descriptionLower.contains("golf") ||
            normalizedMerchantName.contains("golf course") || descriptionLower.contains("golf course") ||
            normalizedMerchantName.contains("driving range") || descriptionLower.contains("driving range") ||
            normalizedMerchantName.contains("golf club") || descriptionLower.contains("golf club")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected golf → 'health'");
            return "health";
        }
        
        // Tennis
        if (normalizedMerchantName.contains("tennis") || descriptionLower.contains("tennis") ||
            normalizedMerchantName.contains("tennis court") || descriptionLower.contains("tennis court") ||
            normalizedMerchantName.contains("tennis club") || descriptionLower.contains("tennis club")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected tennis → 'health'");
            return "health";
        }
        
        // Soccer and other sports activities
        if (normalizedMerchantName.contains("soccer") || descriptionLower.contains("soccer") ||
            normalizedMerchantName.contains("football") || descriptionLower.contains("football") ||
            normalizedMerchantName.contains("basketball") || descriptionLower.contains("basketball") ||
            normalizedMerchantName.contains("baseball") || descriptionLower.contains("baseball") ||
            normalizedMerchantName.contains("swimming") || descriptionLower.contains("swimming") ||
            normalizedMerchantName.contains("yoga") || descriptionLower.contains("yoga") ||
            normalizedMerchantName.contains("pilates") || descriptionLower.contains("pilates") ||
            normalizedMerchantName.contains("martial arts") || descriptionLower.contains("martial arts")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected sports activity → 'health'");
            return "health";
        }
        
        // Ski resorts (Summit at Snoqualmie, etc.)
        // Note: Mini Mountain is ski-gear/equipment, not a resort - handled in shopping section
        if (normalizedMerchantName.contains("ski resort") || descriptionLower.contains("ski resort") ||
            normalizedMerchantName.contains("summit at snoqualmie") || descriptionLower.contains("summit at snoqualmie")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected ski resort → 'health'");
            return "health";
        }
        // Ski activities (skiing, ski lessons, etc.) - but not ski gear/equipment
        if ((normalizedMerchantName.contains("ski") || descriptionLower.contains("ski")) &&
            !normalizedMerchantName.contains("mini mountain") && !descriptionLower.contains("mini mountain") &&
            !normalizedMerchantName.contains("ski gear") && !descriptionLower.contains("ski gear") &&
            !normalizedMerchantName.contains("ski equipment") && !descriptionLower.contains("ski equipment")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected ski activity → 'health'");
            return "health";
        }
        
        
        
        return null; // No match found
    }
}
