package com.budgetbuddy.service.category.strategy;

import java.util.Locale;
import org.springframework.stereotype.Component;

/** Strategy for detecting travel category */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
@Component
public class TravelCategoryStrategy extends BaseCategoryStrategy {

    @Override
    public String detectCategory(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        if (normalizedMerchantName == null || normalizedMerchantName.isBlank()) {
            return null;
        }

        // Airport lounges (Centurion Lounge, Priority Pass, Admirals Club, Delta Sky Club, United
        // Club, etc.)
        final String[] airportLounges = {
            "centurion lounge",
            "centurionlounge",
            "axp centurion",
            "priority pass",
            "prioritypass",
            "admirals club",
            "admiralsclub",
            "delta sky club",
            "deltaskyclub",
            "united club",
            "unitedclub",
            "american express lounge",
            "amex lounge",
            "plaza premium lounge",
            "plazapremiumlounge",
            "airport lounge",
            "airportlounge",
            "encalm lounge",
            "encalmlounge",
            "encalm"
        };
        for (final String lounge : airportLounges) {
            if (normalizedMerchantName.contains(lounge)
                    || descriptionLower.contains(lounge)
                    || (merchantName != null
                            && merchantName
                                    .toUpperCase(Locale.ROOT)
                                    .contains(lounge.toUpperCase(Locale.ROOT)))) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromMerchantName: Detected airport lounge '{}' → 'travel'",
                        lounge);
                return "travel";
            }
        }

        // Airline WiFi services (WI-FI ONBOARD)
        if (normalizedMerchantName.contains("wi-fi onboard")
                || normalizedMerchantName.contains("wifi onboard")
                || normalizedMerchantName.contains("wi-fi on board")
                || normalizedMerchantName.contains("wifi on board")
                || normalizedMerchantName.contains("onboard wifi")
                || normalizedMerchantName.contains("onboard wi-fi")
                || descriptionLower.contains("wi-fi onboard")
                || descriptionLower.contains("wifi onboard")
                || (merchantName != null
                        && merchantName.toUpperCase(Locale.ROOT).contains("WI-FI ONBOARD"))) {
            LOGGER.debug("🏷️ detectCategoryFromMerchantName: Detected airline WiFi → 'travel'");
            return "travel";
        }

        // VIASAT - satellite internet for travel/aircraft
        if (normalizedMerchantName.contains("viasat")
                || descriptionLower.contains("viasat")
                || (merchantName != null
                        && merchantName.toUpperCase(Locale.ROOT).contains("VIASAT"))) {
            LOGGER.debug("🏷️ detectCategoryFromMerchantName: Detected VIASAT → 'travel'");
            return "travel";
        }

        final String[] travelServices = {
            "airline", "airlines", "delta", "united", "american airlines",
            "southwest", "jetblue", "alaska", "hotel", "marriott",
            "hilton", "hyatt", "holiday inn", "holidayinn", "airbnb",
            "priority pass", "prioritypass", "airport lounge", "airportlounge"
        };
        for (final String service : travelServices) {
            if (normalizedMerchantName.contains(service) || descriptionLower.contains(service)) {
                return "travel";
            }
        }

        LOGGER.debug(
                "detectCategoryFromMerchantName: No match found for merchant '{}'", merchantName);
        return null; // No match found
    }
}
