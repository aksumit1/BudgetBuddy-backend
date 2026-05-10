package com.budgetbuddy.service.category.strategy;


import java.util.Locale;
import org.springframework.stereotype.Component;

/** Strategy for detecting shopping category */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
@Component
public class ShoppingCategoryStrategy extends BaseCategoryStrategy {

    private static final String APPAREL = "apparel";

    private static final String CLOTHING = "clothing";

    private static final String DAISO = "daiso";

    @Override
    public String detectCategory(
            final String normalizedMerchantName, final String descriptionLower, final String merchantName) {
        if (normalizedMerchantName == null || normalizedMerchantName.isBlank()) {
            return null;
        }

        // Sports equipment/gear (ski gear, outdoor equipment, etc.) - Must come BEFORE
        // clothing/apparel
        if (normalizedMerchantName.contains("mini mountain")
                || descriptionLower.contains("mini mountain")
                || normalizedMerchantName.contains("minimountain")
                || descriptionLower.contains("minimountain")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected Mini Mountain (ski-gear) → 'shopping'");
            return "shopping";
        }
        // Sports equipment patterns
        if (normalizedMerchantName.contains("ski gear")
                || normalizedMerchantName.contains("skigear")
                || normalizedMerchantName.contains("ski equipment")
                || normalizedMerchantName.contains("skiequipment")
                || normalizedMerchantName.contains("sports equipment")
                || normalizedMerchantName.contains("sportsequipment")
                || normalizedMerchantName.contains("outdoor gear")
                || normalizedMerchantName.contains("outdoorgear")
                || descriptionLower.contains("ski gear")
                || descriptionLower.contains("sports equipment")
                || descriptionLower.contains("outdoor gear")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected sports equipment/gear → 'shopping'");
            return "shopping";
        }

        // Clothing/Apparel patterns - Must come BEFORE general shopping stores
        final String merchantLowerForShopping = merchantName != null ? merchantName.toLowerCase(Locale.ROOT) : "";
        final String descLowerForShopping = descriptionLower != null ? descriptionLower : "";

        if (normalizedMerchantName.contains(CLOTHING)
                || normalizedMerchantName.contains(APPAREL)
                || normalizedMerchantName.contains("men's clothing")
                || normalizedMerchantName.contains("mens clothing")
                || normalizedMerchantName.contains("women's clothing")
                || normalizedMerchantName.contains("womens clothing")
                || normalizedMerchantName.contains("men's apparel")
                || normalizedMerchantName.contains("mens apparel")
                || normalizedMerchantName.contains("women's apparel")
                || normalizedMerchantName.contains("womens apparel")
                || descriptionLower.contains(CLOTHING)
                || descriptionLower.contains(APPAREL)
                || descriptionLower.contains("men's clothing")
                || descriptionLower.contains("mens clothing")
                || descriptionLower.contains("women's clothing")
                || descriptionLower.contains("womens clothing")
                || merchantLowerForShopping.contains(CLOTHING)
                || merchantLowerForShopping.contains(APPAREL)
                || merchantLowerForShopping.contains("men's")
                || merchantLowerForShopping.contains("mens")
                || merchantLowerForShopping.contains("women's")
                || merchantLowerForShopping.contains("womens")
                || descLowerForShopping.contains(CLOTHING)
                || descLowerForShopping.contains(APPAREL)
                || descLowerForShopping.contains("men's")
                || descLowerForShopping.contains("mens")
                || descLowerForShopping.contains("women's")
                || descLowerForShopping.contains("womens")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromMerchantName: Detected clothing/apparel → 'shopping'");
            return "shopping";
        }

        final String[] shoppingStores = {
                "target",
                "walmart",
                "costco",
                "best buy",
                "bestbuy",
                "macy's",
                "macys",
                "nordstrom",
                "tj maxx",
                "tjmaxx",
                "marshalls",
                "ross",
                "burlington",
                "kohl's",
                "kohls",
                DAISO,
                "michaels",
                "michaels stores",
                "michaelsstores",
                "michael's",
                "michaels",
                "dollar tree",
                "dollartree",
                "dollar tree store",
                "dollartreestore",
                "store newcastle",
                "store new castle",
                "storenewcastle"
        };
        for (final String store : shoppingStores) {
            if (normalizedMerchantName.contains(store) || descriptionLower.contains(store)) {
                return "shopping";
            }
        }
        // Daiso (retail chain)
        if (normalizedMerchantName.contains(DAISO) || descriptionLower.contains(DAISO)) {
            LOGGER.debug("🏷️ detectCategoryFromMerchantName: Detected Daiso → 'shopping'");
            return "shopping";
        }

        return null; // No match found
    }
}
