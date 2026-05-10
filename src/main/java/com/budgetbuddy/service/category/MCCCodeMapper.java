package com.budgetbuddy.service.category;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Maps ISO 18245 Merchant Category Codes (MCC) to transaction categories
 *
 * <p>MCC codes are 4-digit codes assigned by credit card networks to classify merchants by the type
 * of goods or services they provide.
 *
 * <p>This provides a standardized, global way to categorize transactions when MCC codes are
 * available from the bank or payment processor.
 */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
@Component
public class MCCCodeMapper {

    private static final String AIRLINE = "airline";

    private static final String DINING = "dining";

    private static final String EDUCATION = "education";

    private static final String ENTERTAINMENT = "entertainment";

    private static final String GROCERIES = "groceries";

    private static final String HEALTH = "health";

    private static final String HEALTHCARE = "healthcare";

    private static final String OTHER = "other";

    private static final String SHOPPING = "shopping";

    private static final String TRANSPORTATION = "transportation";

    private static final String TRAVEL = "travel";

    private static final String UTILITIES = "utilities";

    public static class CategoryMapping {
        private final String primaryCategory;
        private final String detailedCategory;
        private final double confidence;

        public CategoryMapping(
                final String primaryCategory,
                final String detailedCategory,
                final double confidence) {
            this.primaryCategory = primaryCategory;
            this.detailedCategory = detailedCategory;
            this.confidence = confidence;
        }

        public String getPrimaryCategory() {
            return primaryCategory;
        }

        public String getDetailedCategory() {
            return detailedCategory;
        }

        public double getConfidence() {
            return confidence;
        }
    }

    // ISO 18245 Standard MCC Codes to Category Mapping
    // Confidence scores: 0.95 = very reliable, 0.90 = reliable, 0.85 = mostly reliable, 0.80 =
    // somewhat reliable
    private static final Map<String, CategoryMapping> MCC_TO_CATEGORY = new HashMap<>();

    static {
        // ========== GROCERIES ==========
        MCC_TO_CATEGORY.put("5411", new CategoryMapping(GROCERIES, "supermarket", 0.95));
        MCC_TO_CATEGORY.put("5422", new CategoryMapping(GROCERIES, "meat", 0.95));
        MCC_TO_CATEGORY.put("5441", new CategoryMapping(GROCERIES, "candy", 0.95));
        MCC_TO_CATEGORY.put("5451", new CategoryMapping(GROCERIES, "dairy", 0.95));
        MCC_TO_CATEGORY.put("5462", new CategoryMapping(GROCERIES, "bakeries", 0.95));
        MCC_TO_CATEGORY.put("5499", new CategoryMapping(GROCERIES, "misc_food", 0.90));

        // ========== DINING ==========
        MCC_TO_CATEGORY.put("5811", new CategoryMapping(DINING, "restaurant", 0.95));
        MCC_TO_CATEGORY.put("5812", new CategoryMapping(DINING, "fast_food", 0.95));
        MCC_TO_CATEGORY.put("5813", new CategoryMapping(DINING, "bar", 0.95));
        MCC_TO_CATEGORY.put("5814", new CategoryMapping(DINING, "cafe", 0.95));
        MCC_TO_CATEGORY.put("5970", new CategoryMapping(DINING, "catering", 0.90));

        // ========== TRANSPORTATION ==========
        MCC_TO_CATEGORY.put("5541", new CategoryMapping(TRANSPORTATION, "gas", 0.95));
        MCC_TO_CATEGORY.put("5542", new CategoryMapping(TRANSPORTATION, "gas", 0.95));
        MCC_TO_CATEGORY.put("4111", new CategoryMapping(TRANSPORTATION, "transit", 0.90));
        MCC_TO_CATEGORY.put("4112", new CategoryMapping(TRANSPORTATION, "parking", 0.90));
        MCC_TO_CATEGORY.put("4121", new CategoryMapping(TRANSPORTATION, "taxi", 0.90));
        MCC_TO_CATEGORY.put("7512", new CategoryMapping(TRANSPORTATION, "car_rental", 0.95));
        MCC_TO_CATEGORY.put("7513", new CategoryMapping(TRANSPORTATION, "truck_rental", 0.90));
        MCC_TO_CATEGORY.put("7519", new CategoryMapping(TRANSPORTATION, "motor_home_rental", 0.85));
        MCC_TO_CATEGORY.put("4784", new CategoryMapping(TRANSPORTATION, "toll", 0.95));

        // ========== TRAVEL ==========
        MCC_TO_CATEGORY.put("3000", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3001", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3002", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3003", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3004", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3005", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3006", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3007", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3008", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3009", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3010", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3011", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3012", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3013", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3014", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3015", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3016", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3017", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3018", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3019", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3020", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3021", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3022", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3023", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3024", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3025", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3026", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3027", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3028", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3029", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3030", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3031", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3032", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3033", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3034", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3035", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3036", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3037", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3038", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3039", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3040", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3041", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3042", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3043", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3044", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3045", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3046", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3047", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3048", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3049", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3050", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3051", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3052", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3053", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3054", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3055", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3056", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3057", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3058", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3059", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3060", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3061", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3062", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3063", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3064", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3065", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3066", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3067", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3068", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3069", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3070", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3071", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3072", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3073", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3074", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3075", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3076", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3077", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3078", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3079", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3080", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3081", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3082", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3083", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3084", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3085", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3086", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3087", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3088", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3089", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3090", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3091", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3092", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3093", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3094", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3095", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3096", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3097", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3098", new CategoryMapping(TRAVEL, AIRLINE, 0.95));
        MCC_TO_CATEGORY.put("3099", new CategoryMapping(TRAVEL, AIRLINE, 0.95));

        // Hotels (3501-3799)
        for (int i = 3501; i <= 3799; i++) {
            MCC_TO_CATEGORY.put(String.valueOf(i), new CategoryMapping(TRAVEL, "hotel", 0.95));
        }

        // ========== EDUCATION ==========
        MCC_TO_CATEGORY.put("5192", new CategoryMapping(EDUCATION, "books", 0.90));
        MCC_TO_CATEGORY.put("5193", new CategoryMapping(EDUCATION, "school_supplies", 0.90));
        MCC_TO_CATEGORY.put("5942", new CategoryMapping(EDUCATION, "books", 0.90));
        MCC_TO_CATEGORY.put("5943", new CategoryMapping(EDUCATION, "office_supplies", 0.90));
        MCC_TO_CATEGORY.put("5970", new CategoryMapping(EDUCATION, "art_supplies", 0.90));
        MCC_TO_CATEGORY.put("5971", new CategoryMapping(EDUCATION, "art_supplies", 0.90));
        MCC_TO_CATEGORY.put("5973", new CategoryMapping(EDUCATION, "stationery", 0.90));
        MCC_TO_CATEGORY.put("5976", new CategoryMapping(EDUCATION, "printer_supplies", 0.90));
        MCC_TO_CATEGORY.put("8211", new CategoryMapping(EDUCATION, "elementary_school", 0.95));
        MCC_TO_CATEGORY.put("8220", new CategoryMapping(EDUCATION, "university", 0.95));
        MCC_TO_CATEGORY.put("8241", new CategoryMapping(EDUCATION, "correspondence_school", 0.90));
        MCC_TO_CATEGORY.put("8244", new CategoryMapping(EDUCATION, "business_school", 0.90));
        MCC_TO_CATEGORY.put("8249", new CategoryMapping(EDUCATION, "vocational_school", 0.90));
        MCC_TO_CATEGORY.put("8299", new CategoryMapping(EDUCATION, "schools", 0.90));

        // ========== HEALTHCARE ==========
        MCC_TO_CATEGORY.put("5912", new CategoryMapping(HEALTHCARE, "pharmacy", 0.95));
        MCC_TO_CATEGORY.put("8011", new CategoryMapping(HEALTHCARE, "doctor", 0.95));
        MCC_TO_CATEGORY.put("8021", new CategoryMapping(HEALTHCARE, "dentist", 0.95));
        MCC_TO_CATEGORY.put("8031", new CategoryMapping(HEALTHCARE, "ophthalmologist", 0.95));
        MCC_TO_CATEGORY.put("8041", new CategoryMapping(HEALTHCARE, "chiropractor", 0.95));
        MCC_TO_CATEGORY.put("8042", new CategoryMapping(HEALTHCARE, "optometrist", 0.95));
        MCC_TO_CATEGORY.put("8043", new CategoryMapping(HEALTHCARE, "optician", 0.95));
        MCC_TO_CATEGORY.put("8049", new CategoryMapping(HEALTHCARE, "podiatrist", 0.95));
        MCC_TO_CATEGORY.put("8050", new CategoryMapping(HEALTHCARE, "nursing", 0.95));
        MCC_TO_CATEGORY.put("8062", new CategoryMapping(HEALTHCARE, "hospital", 0.95));
        MCC_TO_CATEGORY.put("8071", new CategoryMapping(HEALTHCARE, "medical_lab", 0.95));
        MCC_TO_CATEGORY.put("8099", new CategoryMapping(HEALTHCARE, "medical_service", 0.90));

        // ========== HEALTH/FITNESS ==========
        MCC_TO_CATEGORY.put("7911", new CategoryMapping(HEALTH, "dance_hall", 0.90));
        MCC_TO_CATEGORY.put("7932", new CategoryMapping(HEALTH, "billiards", 0.85));
        MCC_TO_CATEGORY.put("7933", new CategoryMapping(HEALTH, "bowling", 0.90));
        MCC_TO_CATEGORY.put("7941", new CategoryMapping(HEALTH, "sports", 0.90));
        MCC_TO_CATEGORY.put("7992", new CategoryMapping(HEALTH, "golf", 0.90));
        MCC_TO_CATEGORY.put("7997", new CategoryMapping(HEALTH, "recreation", 0.85));
        MCC_TO_CATEGORY.put("7832", new CategoryMapping(HEALTH, "gym", 0.90));

        // ========== UTILITIES ==========
        MCC_TO_CATEGORY.put("4900", new CategoryMapping(UTILITIES, "electric", 0.95));
        MCC_TO_CATEGORY.put("4814", new CategoryMapping(UTILITIES, "telecom", 0.95));
        MCC_TO_CATEGORY.put("4899", new CategoryMapping(UTILITIES, "cable", 0.95));
        MCC_TO_CATEGORY.put("5983", new CategoryMapping(UTILITIES, "fuel", 0.95));

        // ========== ENTERTAINMENT ==========
        MCC_TO_CATEGORY.put("7832", new CategoryMapping(ENTERTAINMENT, "movie", 0.95));
        MCC_TO_CATEGORY.put("7841", new CategoryMapping(ENTERTAINMENT, "video_rental", 0.90));
        MCC_TO_CATEGORY.put("7922", new CategoryMapping(ENTERTAINMENT, "theater", 0.95));
        MCC_TO_CATEGORY.put("7929", new CategoryMapping(ENTERTAINMENT, "band", 0.85));
        MCC_TO_CATEGORY.put("7991", new CategoryMapping(ENTERTAINMENT, "tourist_attraction", 0.90));
        MCC_TO_CATEGORY.put("7993", new CategoryMapping(ENTERTAINMENT, "video_game", 0.90));
        MCC_TO_CATEGORY.put("7994", new CategoryMapping(ENTERTAINMENT, "video_game_arcade", 0.90));
        MCC_TO_CATEGORY.put("7995", new CategoryMapping(ENTERTAINMENT, "betting", 0.85));
        MCC_TO_CATEGORY.put("7996", new CategoryMapping(ENTERTAINMENT, "amusement_park", 0.95));
        MCC_TO_CATEGORY.put("7997", new CategoryMapping(ENTERTAINMENT, "aquarium", 0.90));
        MCC_TO_CATEGORY.put("7998", new CategoryMapping(ENTERTAINMENT, "aquarium", 0.90));
        MCC_TO_CATEGORY.put("7999", new CategoryMapping(ENTERTAINMENT, "recreation", 0.85));

        // ========== SHOPPING ==========
        MCC_TO_CATEGORY.put("5310", new CategoryMapping(SHOPPING, "department_store", 0.95));
        MCC_TO_CATEGORY.put("5311", new CategoryMapping(SHOPPING, "department_store", 0.95));
        MCC_TO_CATEGORY.put("5331", new CategoryMapping(SHOPPING, "variety_store", 0.90));
        MCC_TO_CATEGORY.put("5399", new CategoryMapping(SHOPPING, "misc_general", 0.85));
        MCC_TO_CATEGORY.put("5611", new CategoryMapping(SHOPPING, "mens_clothing", 0.95));
        MCC_TO_CATEGORY.put("5621", new CategoryMapping(SHOPPING, "womens_clothing", 0.95));
        MCC_TO_CATEGORY.put("5631", new CategoryMapping(SHOPPING, "womens_clothing", 0.95));
        MCC_TO_CATEGORY.put("5641", new CategoryMapping(SHOPPING, "childrens_clothing", 0.95));
        MCC_TO_CATEGORY.put("5651", new CategoryMapping(SHOPPING, "family_clothing", 0.95));
        MCC_TO_CATEGORY.put("5655", new CategoryMapping(SHOPPING, "sports_clothing", 0.95));
        MCC_TO_CATEGORY.put("5661", new CategoryMapping(SHOPPING, "shoe_store", 0.95));
        MCC_TO_CATEGORY.put("5712", new CategoryMapping(SHOPPING, "furniture", 0.95));
        MCC_TO_CATEGORY.put("5713", new CategoryMapping(SHOPPING, "floor_covering", 0.95));
        MCC_TO_CATEGORY.put("5714", new CategoryMapping(SHOPPING, "drapery", 0.90));
        MCC_TO_CATEGORY.put("5718", new CategoryMapping(SHOPPING, "fireplace", 0.90));
        MCC_TO_CATEGORY.put("5719", new CategoryMapping(SHOPPING, "misc_home", 0.90));
        MCC_TO_CATEGORY.put("5722", new CategoryMapping(SHOPPING, "household_appliance", 0.95));
        MCC_TO_CATEGORY.put("5732", new CategoryMapping(SHOPPING, "electronics", 0.95));
        MCC_TO_CATEGORY.put("5733", new CategoryMapping(SHOPPING, "office_supplies", 0.90));
        MCC_TO_CATEGORY.put("5734", new CategoryMapping(SHOPPING, "computer", 0.95));
        MCC_TO_CATEGORY.put("5735", new CategoryMapping(SHOPPING, "record_store", 0.90));
        MCC_TO_CATEGORY.put("5944", new CategoryMapping(SHOPPING, "jewelry", 0.95));
        MCC_TO_CATEGORY.put("5945", new CategoryMapping(SHOPPING, "toy", 0.95));
        MCC_TO_CATEGORY.put("5946", new CategoryMapping(SHOPPING, "camera", 0.95));
        MCC_TO_CATEGORY.put("5947", new CategoryMapping(SHOPPING, "gift", 0.90));
        MCC_TO_CATEGORY.put("5948", new CategoryMapping(SHOPPING, "luggage", 0.95));
        MCC_TO_CATEGORY.put("5949", new CategoryMapping(SHOPPING, "sewing", 0.90));

        // ========== PET ==========
        MCC_TO_CATEGORY.put("5995", new CategoryMapping("pet", "pet_shop", 0.90));

        // ========== SUBSCRIPTIONS ==========
        // Note: Many subscription services use generic MCC codes, so we rely more on merchant name
        MCC_TO_CATEGORY.put("4814", new CategoryMapping("subscriptions", "telecom", 0.90));
        MCC_TO_CATEGORY.put("4899", new CategoryMapping("subscriptions", "cable", 0.90));

        // ========== CHARITY ==========
        MCC_TO_CATEGORY.put("8398", new CategoryMapping("charity", "nonprofit", 0.95));
        MCC_TO_CATEGORY.put("8661", new CategoryMapping("charity", "religious", 0.90));
    }

    /**
     * Get category mapping from MCC code
     *
     * @param mccCode 4-digit MCC code (e.g., "5411")
     * @return CategoryMapping with primary, detailed category, and confidence, or default "other"
     *     if not found
     */
    public CategoryMapping getCategoryFromMCC(final String mccCode) {
        if (mccCode == null || mccCode.isBlank()) {
            return new CategoryMapping(OTHER, OTHER, 0.50);
        }

        // Normalize MCC code (remove leading zeros, ensure 4 digits)
        String normalized = mccCode.trim();
        if (normalized.length() < 4) {
            normalized = String.format("%04d", Integer.parseInt(normalized));
        }

        return MCC_TO_CATEGORY.getOrDefault(normalized, new CategoryMapping(OTHER, OTHER, 0.50));
    }

    /** Check if MCC code is available and mapped */
    public boolean hasMapping(final String mccCode) {
        if (mccCode == null || mccCode.isBlank()) {
            return false;
        }
        String normalized = mccCode.trim();
        if (normalized.length() < 4) {
            normalized = String.format("%04d", Integer.parseInt(normalized));
        }
        return MCC_TO_CATEGORY.containsKey(normalized);
    }

    /** Get all MCC codes for a given category (useful for reverse lookup) */
    public java.util.List<String> getMCCCodesForCategory(final String primaryCategory) {
        return MCC_TO_CATEGORY.entrySet().stream()
                .filter(entry -> entry.getValue().getPrimaryCategory().equals(primaryCategory))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());
    }
}
