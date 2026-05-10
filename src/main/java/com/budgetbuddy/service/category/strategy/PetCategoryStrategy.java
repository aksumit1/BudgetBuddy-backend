package com.budgetbuddy.service.category.strategy;

import java.util.Locale;
import org.springframework.stereotype.Component;

/** Strategy for detecting pet category */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
@Component
public class PetCategoryStrategy extends BaseCategoryStrategy {

    private static final String PET = "pet";

    private static final String BARRON = "barron";

    private static final String BARRONS = "barrons";

    @Override
    public String detectCategory(
            final String normalizedMerchantName,
            final String descriptionLower,
            final String merchantName) {
        if (normalizedMerchantName == null || normalizedMerchantName.isBlank()) {
            return null;
        }

        if (normalizedMerchantName.contains("petsmart") || descriptionLower.contains("petsmart")) {
            return PET;
        }
        if (normalizedMerchantName.contains("petco") || descriptionLower.contains("petco")) {
            return PET;
        }
        // SP Farmers Fetch Bones
        if (normalizedMerchantName.contains("sp farmers")
                || normalizedMerchantName.contains("fetch bones")
                || normalizedMerchantName.contains("fetchbones")
                || descriptionLower.contains("sp farmers")
                || descriptionLower.contains("fetch bones")
                || descriptionLower.contains("fetchbones")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected SP Farmers Fetch Bones → 'pet'");
            return PET;
        }
        if (normalizedMerchantName.contains("pet supplies")
                || normalizedMerchantName.contains("petsupplies")
                || normalizedMerchantName.contains("pet supply")
                || descriptionLower.contains("pet supplies")) {
            return PET;
        }
        if (normalizedMerchantName.contains("petcare")
                || normalizedMerchantName.contains("pet care")
                || descriptionLower.contains("petcare")
                || descriptionLower.contains("pet care")) {
            return PET;
        }
        if (normalizedMerchantName.contains("pet clinic")
                || normalizedMerchantName.contains("petclinic")
                || normalizedMerchantName.contains("veterinary")
                || normalizedMerchantName.contains("vet ")
                || normalizedMerchantName.contains("animal hospital")
                || descriptionLower.contains("pet clinic")
                || descriptionLower.contains("veterinary")) {
            return PET;
        }

        // Pet insurance (PETS BEST INSURANCE)
        if (normalizedMerchantName.contains("pets best insurance")
                || normalizedMerchantName.contains("petsbestinsurance")
                || normalizedMerchantName.contains("pets best")
                || normalizedMerchantName.contains("petsbest")
                || descriptionLower.contains("pets best insurance")
                || descriptionLower.contains("pets best")
                || (merchantName != null
                        && merchantName.toUpperCase(Locale.ROOT).contains("PETS BEST"))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected Pets Best Insurance → 'pet'");
            return PET;
        }

        // ========== SUBSCRIPTIONS (News/Investment Journals) ==========
        // CRITICAL: Must come BEFORE tech to prevent "J*Barrons" from matching tech patterns
        // Check both normalizedMerchantName and original strings for asterisks and special
        // characters
        final String merchantLower =
                merchantName != null ? merchantName.toLowerCase(Locale.ROOT) : "";
        final String descLowerForJournals = descriptionLower != null ? descriptionLower : "";

        final String[] newsJournals = {
            BARRONS,
            "barron's",
            BARRON,
            "new york times",
            "nytimes",
            "ny times",
            "the new york times",
            "wall street journal",
            "wsj",
            "the wall street journal",
            "financial times",
            "ft.com",
            "the financial times",
            "economist",
            "the economist",
            "bloomberg",
            "bloomberg news",
            "reuters",
            "reuters news",
            "cnn",
            "cnn news",
            "bbc",
            "bbc news",
            "washington post",
            "wapo",
            "the washington post",
            "usa today",
            "usatoday",
            "los angeles times",
            "latimes",
            "chicago tribune",
            "boston globe",
            "the boston globe"
        };
        for (final String journal : newsJournals) {
            if (normalizedMerchantName.contains(journal)
                    || descriptionLower.contains(journal)
                    || merchantLower.contains(journal)
                    || descLowerForJournals.contains(journal)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromMerchantName: Detected news/investment journal '{}' → 'subscriptions'",
                        journal);
                return "subscriptions";
            }
        }
        // Special handling for patterns with asterisks (e.g., "J*Barrons")
        if ((merchantLower.contains(BARRONS) || merchantLower.contains(BARRON))
                || (descLowerForJournals.contains(BARRONS)
                        || descLowerForJournals.contains(BARRON))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected Barrons (with special chars) → 'subscriptions'");
            return "subscriptions";
        }

        return null; // No match found
    }
}
