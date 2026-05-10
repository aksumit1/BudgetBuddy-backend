package com.budgetbuddy.service.category.strategy;

import org.springframework.stereotype.Component;

/** Strategy for detecting entertainment category */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
@Component
public class EntertainmentCategoryStrategy extends BaseCategoryStrategy {

    @Override
    public String detectCategory(
            final String normalizedMerchantName, final String descriptionLower, final String merchantName) {
        if (normalizedMerchantName == null || normalizedMerchantName.isBlank()) {
            return null;
        }

        // CRITICAL: Must come BEFORE tech to prevent streaming services from matching tech patterns
        // Streaming services are entertainment, not subscriptions category
        // Subscriptions are detected separately via SubscriptionService
        final String[] streamingServices = {
                "netflix",
                "hulu",
                "huluplus",
                "hulu plus",
                "disney",
                "disney+",
                "disneyplus",
                "hbo",
                "hbo max",
                "hbomax",
                "paramount",
                "paramount+",
                "paramount plus",
                "peacock",
                "nbc peacock",
                "spotify",
                "apple music",
                "applemusic",
                "youtube premium",
                "youtubepremium",
                "youtube tv",
                "youtubetv",
                "amazon prime",
                "amazonprime",
                "prime video",
                "primevideo",
                "showtime",
                "starz",
                "crunchyroll",
                "funimation"
        };
        for (final String service : streamingServices) {
            if (normalizedMerchantName.contains(service) || descriptionLower.contains(service)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromMerchantName: Detected streaming service '{}' → 'entertainment'",
                        service);
                return "entertainment";
            }
        }

        // ========== SUBSCRIPTIONS (Software/Non-Entertainment) ==========
        // CRITICAL: Must come BEFORE tech to prevent software subscriptions (Adobe, etc.) from
        // matching tech patterns
        // Software subscriptions remain as subscriptions category
        final String[] softwareSubscriptions = {
                "adobe",
                "adobe creative cloud",
                "microsoft 365",
                "office 365",
                "dropbox",
                "icloud",
                "google drive",
                "google one",
                "github",
                "github pro",
                "canva",
                "canva pro",
                "grammarly",
                "nordvpn",
                "expressvpn",
                "surfshark",
                "zoom",
                "slack"
        };
        for (final String service : softwareSubscriptions) {
            if (normalizedMerchantName.contains(service) || descriptionLower.contains(service)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromMerchantName: Detected software subscription '{}' → 'subscriptions'",
                        service);
                return "subscriptions";
            }
        }

        return null; // No match found
    }
}
