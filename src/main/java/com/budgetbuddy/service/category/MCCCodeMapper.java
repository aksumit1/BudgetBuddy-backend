package com.budgetbuddy.service.category;

import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps ISO 18245 Merchant Category Codes (MCC) to transaction categories
 * 
 * MCC codes are 4-digit codes assigned by credit card networks to classify
 * merchants by the type of goods or services they provide.
 * 
 * This provides a standardized, global way to categorize transactions when
 * MCC codes are available from the bank or payment processor.
 */
@Component
public class MCCCodeMapper {
    
    public static class CategoryMapping {
        private final String primaryCategory;
        private final String detailedCategory;
        private final double confidence;
        
        public CategoryMapping(String primaryCategory, String detailedCategory, double confidence) {
            this.primaryCategory = primaryCategory;
            this.detailedCategory = detailedCategory;
            this.confidence = confidence;
        }
        
        public String getPrimaryCategory() { return primaryCategory; }
        public String getDetailedCategory() { return detailedCategory; }
        public double getConfidence() { return confidence; }
    }
    
    // ISO 18245 Standard MCC Codes to Category Mapping
    // Confidence scores: 0.95 = very reliable, 0.90 = reliable, 0.85 = mostly reliable, 0.80 = somewhat reliable
    private static final Map<String, CategoryMapping> MCC_TO_CATEGORY = new HashMap<>();
    
    static {
        // ========== GROCERIES ==========
        MCC_TO_CATEGORY.put("5411", new CategoryMapping("groceries", "supermarket", 0.95));
        MCC_TO_CATEGORY.put("5422", new CategoryMapping("groceries", "meat", 0.95));
        MCC_TO_CATEGORY.put("5441", new CategoryMapping("groceries", "candy", 0.95));
        MCC_TO_CATEGORY.put("5451", new CategoryMapping("groceries", "dairy", 0.95));
        MCC_TO_CATEGORY.put("5462", new CategoryMapping("groceries", "bakeries", 0.95));
        MCC_TO_CATEGORY.put("5499", new CategoryMapping("groceries", "misc_food", 0.90));
        
        // ========== DINING ==========
        MCC_TO_CATEGORY.put("5811", new CategoryMapping("dining", "restaurant", 0.95));
        MCC_TO_CATEGORY.put("5812", new CategoryMapping("dining", "fast_food", 0.95));
        MCC_TO_CATEGORY.put("5813", new CategoryMapping("dining", "bar", 0.95));
        MCC_TO_CATEGORY.put("5814", new CategoryMapping("dining", "cafe", 0.95));
        MCC_TO_CATEGORY.put("5970", new CategoryMapping("dining", "catering", 0.90));
        
        // ========== TRANSPORTATION ==========
        MCC_TO_CATEGORY.put("5541", new CategoryMapping("transportation", "gas", 0.95));
        MCC_TO_CATEGORY.put("5542", new CategoryMapping("transportation", "gas", 0.95));
        MCC_TO_CATEGORY.put("4111", new CategoryMapping("transportation", "transit", 0.90));
        MCC_TO_CATEGORY.put("4112", new CategoryMapping("transportation", "parking", 0.90));
        MCC_TO_CATEGORY.put("4121", new CategoryMapping("transportation", "taxi", 0.90));
        MCC_TO_CATEGORY.put("7512", new CategoryMapping("transportation", "car_rental", 0.95));
        MCC_TO_CATEGORY.put("7513", new CategoryMapping("transportation", "truck_rental", 0.90));
        MCC_TO_CATEGORY.put("7519", new CategoryMapping("transportation", "motor_home_rental", 0.85));
        MCC_TO_CATEGORY.put("4784", new CategoryMapping("transportation", "toll", 0.95));
        
        // ========== TRAVEL ==========
        MCC_TO_CATEGORY.put("3000", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3001", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3002", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3003", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3004", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3005", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3006", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3007", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3008", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3009", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3010", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3011", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3012", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3013", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3014", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3015", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3016", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3017", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3018", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3019", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3020", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3021", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3022", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3023", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3024", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3025", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3026", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3027", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3028", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3029", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3030", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3031", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3032", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3033", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3034", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3035", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3036", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3037", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3038", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3039", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3040", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3041", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3042", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3043", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3044", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3045", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3046", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3047", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3048", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3049", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3050", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3051", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3052", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3053", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3054", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3055", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3056", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3057", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3058", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3059", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3060", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3061", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3062", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3063", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3064", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3065", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3066", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3067", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3068", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3069", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3070", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3071", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3072", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3073", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3074", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3075", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3076", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3077", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3078", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3079", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3080", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3081", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3082", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3083", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3084", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3085", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3086", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3087", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3088", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3089", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3090", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3091", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3092", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3093", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3094", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3095", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3096", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3097", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3098", new CategoryMapping("travel", "airline", 0.95));
        MCC_TO_CATEGORY.put("3099", new CategoryMapping("travel", "airline", 0.95));
        
        // Hotels (3501-3799)
        for (int i = 3501; i <= 3799; i++) {
            MCC_TO_CATEGORY.put(String.valueOf(i), new CategoryMapping("travel", "hotel", 0.95));
        }
        
        // ========== EDUCATION ==========
        MCC_TO_CATEGORY.put("5192", new CategoryMapping("education", "books", 0.90));
        MCC_TO_CATEGORY.put("5193", new CategoryMapping("education", "school_supplies", 0.90));
        MCC_TO_CATEGORY.put("5942", new CategoryMapping("education", "books", 0.90));
        MCC_TO_CATEGORY.put("5943", new CategoryMapping("education", "office_supplies", 0.90));
        MCC_TO_CATEGORY.put("5970", new CategoryMapping("education", "art_supplies", 0.90));
        MCC_TO_CATEGORY.put("5971", new CategoryMapping("education", "art_supplies", 0.90));
        MCC_TO_CATEGORY.put("5973", new CategoryMapping("education", "stationery", 0.90));
        MCC_TO_CATEGORY.put("5976", new CategoryMapping("education", "printer_supplies", 0.90));
        MCC_TO_CATEGORY.put("8211", new CategoryMapping("education", "elementary_school", 0.95));
        MCC_TO_CATEGORY.put("8220", new CategoryMapping("education", "university", 0.95));
        MCC_TO_CATEGORY.put("8241", new CategoryMapping("education", "correspondence_school", 0.90));
        MCC_TO_CATEGORY.put("8244", new CategoryMapping("education", "business_school", 0.90));
        MCC_TO_CATEGORY.put("8249", new CategoryMapping("education", "vocational_school", 0.90));
        MCC_TO_CATEGORY.put("8299", new CategoryMapping("education", "schools", 0.90));
        
        // ========== HEALTHCARE ==========
        MCC_TO_CATEGORY.put("5912", new CategoryMapping("healthcare", "pharmacy", 0.95));
        MCC_TO_CATEGORY.put("8011", new CategoryMapping("healthcare", "doctor", 0.95));
        MCC_TO_CATEGORY.put("8021", new CategoryMapping("healthcare", "dentist", 0.95));
        MCC_TO_CATEGORY.put("8031", new CategoryMapping("healthcare", "ophthalmologist", 0.95));
        MCC_TO_CATEGORY.put("8041", new CategoryMapping("healthcare", "chiropractor", 0.95));
        MCC_TO_CATEGORY.put("8042", new CategoryMapping("healthcare", "optometrist", 0.95));
        MCC_TO_CATEGORY.put("8043", new CategoryMapping("healthcare", "optician", 0.95));
        MCC_TO_CATEGORY.put("8049", new CategoryMapping("healthcare", "podiatrist", 0.95));
        MCC_TO_CATEGORY.put("8050", new CategoryMapping("healthcare", "nursing", 0.95));
        MCC_TO_CATEGORY.put("8062", new CategoryMapping("healthcare", "hospital", 0.95));
        MCC_TO_CATEGORY.put("8071", new CategoryMapping("healthcare", "medical_lab", 0.95));
        MCC_TO_CATEGORY.put("8099", new CategoryMapping("healthcare", "medical_service", 0.90));
        
        // ========== HEALTH/FITNESS ==========
        MCC_TO_CATEGORY.put("7911", new CategoryMapping("health", "dance_hall", 0.90));
        MCC_TO_CATEGORY.put("7932", new CategoryMapping("health", "billiards", 0.85));
        MCC_TO_CATEGORY.put("7933", new CategoryMapping("health", "bowling", 0.90));
        MCC_TO_CATEGORY.put("7941", new CategoryMapping("health", "sports", 0.90));
        MCC_TO_CATEGORY.put("7992", new CategoryMapping("health", "golf", 0.90));
        MCC_TO_CATEGORY.put("7997", new CategoryMapping("health", "recreation", 0.85));
        MCC_TO_CATEGORY.put("7832", new CategoryMapping("health", "gym", 0.90));
        
        // ========== UTILITIES ==========
        MCC_TO_CATEGORY.put("4900", new CategoryMapping("utilities", "electric", 0.95));
        MCC_TO_CATEGORY.put("4814", new CategoryMapping("utilities", "telecom", 0.95));
        MCC_TO_CATEGORY.put("4899", new CategoryMapping("utilities", "cable", 0.95));
        MCC_TO_CATEGORY.put("5983", new CategoryMapping("utilities", "fuel", 0.95));
        
        // ========== ENTERTAINMENT ==========
        MCC_TO_CATEGORY.put("7832", new CategoryMapping("entertainment", "movie", 0.95));
        MCC_TO_CATEGORY.put("7841", new CategoryMapping("entertainment", "video_rental", 0.90));
        MCC_TO_CATEGORY.put("7922", new CategoryMapping("entertainment", "theater", 0.95));
        MCC_TO_CATEGORY.put("7929", new CategoryMapping("entertainment", "band", 0.85));
        MCC_TO_CATEGORY.put("7991", new CategoryMapping("entertainment", "tourist_attraction", 0.90));
        MCC_TO_CATEGORY.put("7993", new CategoryMapping("entertainment", "video_game", 0.90));
        MCC_TO_CATEGORY.put("7994", new CategoryMapping("entertainment", "video_game_arcade", 0.90));
        MCC_TO_CATEGORY.put("7995", new CategoryMapping("entertainment", "betting", 0.85));
        MCC_TO_CATEGORY.put("7996", new CategoryMapping("entertainment", "amusement_park", 0.95));
        MCC_TO_CATEGORY.put("7997", new CategoryMapping("entertainment", "aquarium", 0.90));
        MCC_TO_CATEGORY.put("7998", new CategoryMapping("entertainment", "aquarium", 0.90));
        MCC_TO_CATEGORY.put("7999", new CategoryMapping("entertainment", "recreation", 0.85));
        
        // ========== SHOPPING ==========
        MCC_TO_CATEGORY.put("5310", new CategoryMapping("shopping", "department_store", 0.95));
        MCC_TO_CATEGORY.put("5311", new CategoryMapping("shopping", "department_store", 0.95));
        MCC_TO_CATEGORY.put("5331", new CategoryMapping("shopping", "variety_store", 0.90));
        MCC_TO_CATEGORY.put("5399", new CategoryMapping("shopping", "misc_general", 0.85));
        MCC_TO_CATEGORY.put("5611", new CategoryMapping("shopping", "mens_clothing", 0.95));
        MCC_TO_CATEGORY.put("5621", new CategoryMapping("shopping", "womens_clothing", 0.95));
        MCC_TO_CATEGORY.put("5631", new CategoryMapping("shopping", "womens_clothing", 0.95));
        MCC_TO_CATEGORY.put("5641", new CategoryMapping("shopping", "childrens_clothing", 0.95));
        MCC_TO_CATEGORY.put("5651", new CategoryMapping("shopping", "family_clothing", 0.95));
        MCC_TO_CATEGORY.put("5655", new CategoryMapping("shopping", "sports_clothing", 0.95));
        MCC_TO_CATEGORY.put("5661", new CategoryMapping("shopping", "shoe_store", 0.95));
        MCC_TO_CATEGORY.put("5712", new CategoryMapping("shopping", "furniture", 0.95));
        MCC_TO_CATEGORY.put("5713", new CategoryMapping("shopping", "floor_covering", 0.95));
        MCC_TO_CATEGORY.put("5714", new CategoryMapping("shopping", "drapery", 0.90));
        MCC_TO_CATEGORY.put("5718", new CategoryMapping("shopping", "fireplace", 0.90));
        MCC_TO_CATEGORY.put("5719", new CategoryMapping("shopping", "misc_home", 0.90));
        MCC_TO_CATEGORY.put("5722", new CategoryMapping("shopping", "household_appliance", 0.95));
        MCC_TO_CATEGORY.put("5732", new CategoryMapping("shopping", "electronics", 0.95));
        MCC_TO_CATEGORY.put("5733", new CategoryMapping("shopping", "office_supplies", 0.90));
        MCC_TO_CATEGORY.put("5734", new CategoryMapping("shopping", "computer", 0.95));
        MCC_TO_CATEGORY.put("5735", new CategoryMapping("shopping", "record_store", 0.90));
        MCC_TO_CATEGORY.put("5944", new CategoryMapping("shopping", "jewelry", 0.95));
        MCC_TO_CATEGORY.put("5945", new CategoryMapping("shopping", "toy", 0.95));
        MCC_TO_CATEGORY.put("5946", new CategoryMapping("shopping", "camera", 0.95));
        MCC_TO_CATEGORY.put("5947", new CategoryMapping("shopping", "gift", 0.90));
        MCC_TO_CATEGORY.put("5948", new CategoryMapping("shopping", "luggage", 0.95));
        MCC_TO_CATEGORY.put("5949", new CategoryMapping("shopping", "sewing", 0.90));
        
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
     * @param mccCode 4-digit MCC code (e.g., "5411")
     * @return CategoryMapping with primary, detailed category, and confidence, or default "other" if not found
     */
    public CategoryMapping getCategoryFromMCC(String mccCode) {
        if (mccCode == null || mccCode.trim().isEmpty()) {
            return new CategoryMapping("other", "other", 0.50);
        }
        
        // Normalize MCC code (remove leading zeros, ensure 4 digits)
        String normalized = mccCode.trim();
        if (normalized.length() < 4) {
            normalized = String.format("%04d", Integer.parseInt(normalized));
        }
        
        return MCC_TO_CATEGORY.getOrDefault(normalized, 
            new CategoryMapping("other", "other", 0.50));
    }
    
    /**
     * Check if MCC code is available and mapped
     */
    public boolean hasMapping(String mccCode) {
        if (mccCode == null || mccCode.trim().isEmpty()) {
            return false;
        }
        String normalized = mccCode.trim();
        if (normalized.length() < 4) {
            normalized = String.format("%04d", Integer.parseInt(normalized));
        }
        return MCC_TO_CATEGORY.containsKey(normalized);
    }
    
    /**
     * Get all MCC codes for a given category (useful for reverse lookup)
     */
    public java.util.List<String> getMCCCodesForCategory(String primaryCategory) {
        return MCC_TO_CATEGORY.entrySet().stream()
            .filter(entry -> entry.getValue().getPrimaryCategory().equals(primaryCategory))
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toList());
    }
}

