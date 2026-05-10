package com.budgetbuddy.service.category.strategy;

import org.springframework.stereotype.Component;

/** Strategy for detecting tech category */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
@Component
public class TechCategoryStrategy extends BaseCategoryStrategy {

    private static final String TECH = "tech";

    private static final String CURSOR = "cursor";

    private static final String CURSOR_AI = "cursor ai";

    private static final String SOFTWARE = "software";

    @Override
    public String detectCategory(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        if (normalizedMerchantName == null || normalizedMerchantName.isBlank()) {
            return null;
        }

        // AI/Tech Services - Must come after streaming services to prevent false matches
        final String[] aiTechServices = {
            "chatgpt",
            "chat gpt",
            "openai",
            "open ai",
            "anthropic",
            "anthropic ai",
            "claude",
            "claude ai",
            "cohere",
            "hugging face",
            "huggingface",
            CURSOR,
            CURSOR_AI,
            "github copilot",
            "copilot",
            "replicate",
            "together ai",
            "togetherai"
        };
        for (final String service : aiTechServices) {
            if (normalizedMerchantName.contains(service) || descriptionLower.contains(service)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromMerchantName: Detected AI/tech service '{}' → 'tech'",
                        service);
                return TECH;
            }
        }
        if (normalizedMerchantName.contains(CURSOR)
                || descriptionLower.contains(CURSOR)
                || normalizedMerchantName.contains(CURSOR_AI)
                || descriptionLower.contains(CURSOR_AI)) {
            LOGGER.debug("🏷️ detectCategoryFromMerchantName: Detected Cursor → 'tech'");
            return TECH;
        }
        if (normalizedMerchantName.contains("ai powered")
                || descriptionLower.contains("ai powered")
                || normalizedMerchantName.contains("artificial intelligence")
                || descriptionLower.contains("artificial intelligence")) {
            return TECH;
        }
        // Tech Companies
        final String[] techCompanies = {
            "microsoft",
            "apple",
            "google",
            "amazon web services",
            "aws",
            "adobe",
            "oracle",
            "salesforce",
            "servicenow",
            "atlassian",
            "github",
            "gitlab",
            "slack",
            "zoom",
            "dropbox",
            "box",
            "notion",
            "figma",
            "sketch",
            "linear",
            "vercel",
            "netlify"
        };
        for (final String company : techCompanies) {
            if (normalizedMerchantName.contains(company) || descriptionLower.contains(company)) {
                return TECH;
            }
        }

        // Software/Technology Patterns
        if (normalizedMerchantName.contains(SOFTWARE)
                || normalizedMerchantName.contains("saas")
                || normalizedMerchantName.contains("cloud")
                || normalizedMerchantName.contains("api")
                || normalizedMerchantName.contains("developer")
                || normalizedMerchantName.contains("dev tools")
                || descriptionLower.contains(SOFTWARE)
                || (descriptionLower.contains("subscription")
                        && (normalizedMerchantName.contains(SOFTWARE)
                                || normalizedMerchantName.contains("saas")
                                || normalizedMerchantName.contains(TECH)
                                || normalizedMerchantName.contains("cloud")
                                || normalizedMerchantName.contains("api")))) {
            return TECH;
        }

        // ========== HOME IMPROVEMENT ==========
        if (normalizedMerchantName.contains("home depot")
                || normalizedMerchantName.contains("homedepot")
                || descriptionLower.contains("home depot")) {
            return "home improvement";
        }
        final String[] homeImprovementStores = {
            "lowes",
            "lowe's",
            "menards",
            "ace hardware",
            "acehardware",
            "true value",
            "truevalue",
            "harbor freight",
            "harborfreight",
            "northern tool",
            "northerntool",
            "harbor freight tools"
        };
        for (final String store : homeImprovementStores) {
            if (normalizedMerchantName.contains(store) || descriptionLower.contains(store)) {
                return "home improvement";
            }
        }

        // Hardware/Home Improvement Patterns
        if (normalizedMerchantName.contains("hardware")
                || normalizedMerchantName.contains("home improvement")
                || normalizedMerchantName.contains("homeimprovement")
                || normalizedMerchantName.contains("lumber")
                || normalizedMerchantName.contains("building supply")
                || descriptionLower.contains("hardware")) {
            return "home improvement";
        }

        // ========== DINING ==========
        // Bakeries (Hoffmans, Le Panier)
        if (normalizedMerchantName.contains("hoffman")
                || normalizedMerchantName.contains("hoffman's")
                || descriptionLower.contains("hoffman")
                || descriptionLower.contains("hoffman's")) {
            LOGGER.debug("🏷️ detectCategoryFromMerchantName: Detected Hoffmans bakery → 'dining'");
            return "dining"; // Bakeries are dining
        }
        if (normalizedMerchantName.contains("le panier")
                || normalizedMerchantName.contains("lepanier")
                || descriptionLower.contains("le panier")
                || descriptionLower.contains("lepanier")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected Le Panier bakery → 'dining'");
            return "dining";
        }
        if (normalizedMerchantName.contains("bakery")
                || normalizedMerchantName.contains("baker")
                || descriptionLower.contains("bakery")
                || descriptionLower.contains("baker")) {
            return "dining";
        }
        // Tea Lab, Chai, Boba, Mochinut
        if (normalizedMerchantName.contains("tea lab")
                || normalizedMerchantName.contains("tealab")
                || normalizedMerchantName.contains("chai")
                || descriptionLower.contains("chai")
                || normalizedMerchantName.contains("boba")
                || descriptionLower.contains("boba")
                || normalizedMerchantName.contains("mochinut")
                || descriptionLower.contains("mochinut")) {
            LOGGER.debug("🏷️ detectCategoryFromMerchantName: Detected tea/chai/boba → 'dining'");
            return "dining";
        }
        // Ezell's Famous Chicken
        if (normalizedMerchantName.contains("ezell")
                || normalizedMerchantName.contains("ezells")
                || descriptionLower.contains("ezell")
                || descriptionLower.contains("ezells")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected Ezell's Famous Chicken → 'dining'");
            return "dining";
        }
        // honest.bellevue.com
        if (normalizedMerchantName.contains("honest.bellevue")
                || normalizedMerchantName.contains("honest")
                || descriptionLower.contains("honest.bellevue")
                || descriptionLower.contains("honest")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected honest.bellevue.com → 'dining'");
            return "dining";
        }

        return null; // No match found
    }
}
