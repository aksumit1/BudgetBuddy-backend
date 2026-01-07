package com.budgetbuddy.service.category.strategy;

import org.springframework.stereotype.Component;

/**
 * Strategy for detecting entertainment category
 */
@Component
public class EntertainmentCategoryStrategy extends BaseCategoryStrategy {
    
    @Override
    public String detectCategory(String normalizedMerchantName, String descriptionLower, String merchantName) {
        if (normalizedMerchantName == null || normalizedMerchantName.trim().isEmpty()) {
            return null;
        }
        
        // CRITICAL: Must come BEFORE tech to prevent streaming services from matching tech patterns
        // Streaming services are entertainment, not subscriptions category
        // Subscriptions are detected separately via SubscriptionService
        String[] streamingServices = {
            "netflix", "hulu", "huluplus", "hulu plus", "disney", "disney+", "disneyplus",
            "hbo", "hbo max", "hbomax", "paramount", "paramount+", "paramount plus",
            "peacock", "nbc peacock", "spotify", "apple music", "applemusic",
            "youtube premium", "youtubepremium", "youtube tv", "youtubetv",
            "amazon prime", "amazonprime", "prime video", "primevideo",
            "showtime", "starz", "crunchyroll", "funimation"
        };
        for (String service : streamingServices) {
            if (normalizedMerchantName.contains(service) || descriptionLower.contains(service)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected streaming service '{}' → 'entertainment'", service);
                return "entertainment";
            }
        }
        
        // ========== SUBSCRIPTIONS (Software/Non-Entertainment) ==========
        // CRITICAL: Must come BEFORE tech to prevent software subscriptions (Adobe, etc.) from matching tech patterns
        // Software subscriptions remain as subscriptions category
        String[] softwareSubscriptions = {
            "adobe", "adobe creative cloud", "microsoft 365", "office 365",
            "dropbox", "icloud", "google drive", "google one",
            "github", "github pro", "canva", "canva pro", "grammarly",
            "nordvpn", "expressvpn", "surfshark", "zoom", "slack"
        };
        for (String service : softwareSubscriptions) {
            if (normalizedMerchantName.contains(service) || descriptionLower.contains(service)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected software subscription '{}' → 'subscriptions'", service);
                return "subscriptions";
            }
        }
        
        
        
        return null; // No match found
    }
}
