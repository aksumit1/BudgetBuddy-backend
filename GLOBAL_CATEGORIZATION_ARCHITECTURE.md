# Global Transaction Categorization Architecture

## Executive Summary

This document outlines a comprehensive, extensible, and globally-aware transaction categorization system designed to achieve near-100% accuracy through a multi-layered approach combining merchant databases, industry standards (MCC codes), machine learning, and user feedback loops.

## Current State Analysis

### Problems with Current Approach
1. **One-off fixes**: Hard-coded merchant names scattered across multiple files
2. **No central database**: Merchant information duplicated and inconsistent
3. **Limited global support**: Primarily US-centric, lacks international merchant recognition
4. **No learning mechanism**: User corrections not fed back into system
5. **Strategy pattern limitations**: Each category strategy is isolated, no cross-category intelligence
6. **No industry standards**: Missing MCC (Merchant Category Code) integration
7. **Regional variations**: No support for different naming conventions globally

## Proposed Architecture

### 1. Multi-Layer Detection Pipeline

```
┌─────────────────────────────────────────────────────────────┐
│                    Transaction Input                          │
│  (merchantName, description, amount, account, paymentChannel) │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 1: Exact Merchant Database Match (100% confidence)    │
│  - Global merchant database with aliases                     │
│  - Regional name variations                                  │
│  - Historical merchant data                                  │
└─────────────────────────────────────────────────────────────┘
                            │ (if no match)
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 2: MCC Code Lookup (95% confidence)                   │
│  - ISO 18245 Merchant Category Codes                         │
│  - Bank-provided MCC codes                                   │
│  - MCC to category mapping                                   │
└─────────────────────────────────────────────────────────────┘
                            │ (if no MCC)
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 3: Pattern Matching (80-90% confidence)               │
│  - Regex patterns for transaction types                      │
│  - Keyword-based detection                                   │
│  - Context-aware patterns (amount, date, frequency)          │
└─────────────────────────────────────────────────────────────┘
                            │ (if no match)
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 4: Machine Learning (70-85% confidence)                │
│  - Semantic matching                                         │
│  - Fuzzy merchant matching                                   │
│  - Context-aware classification                             │
└─────────────────────────────────────────────────────────────┘
                            │ (if low confidence)
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 5: Rule-Based Fallback (60-70% confidence)            │
│  - Account type rules                                        │
│  - Amount-based heuristics                                   │
│  - Default category assignment                               │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
                    Final Category + Confidence
```

### 2. Global Merchant Database

#### Database Schema

```sql
CREATE TABLE merchants (
    id UUID PRIMARY KEY,
    canonical_name VARCHAR(255) NOT NULL,  -- "Walmart"
    normalized_name VARCHAR(255) NOT NULL, -- "walmart" (lowercase, normalized)
    primary_category VARCHAR(50) NOT NULL,  -- "groceries"
    detailed_category VARCHAR(50),          -- "supermarket"
    mcc_code VARCHAR(4),                   -- "5411"
    country_code VARCHAR(2),               -- "US", "IN", "GB"
    region VARCHAR(50),                     -- "North America", "Asia"
    
    -- Metadata
    confidence_score DECIMAL(3,2),         -- 0.00 to 1.00
    transaction_count BIGINT,              -- How many times we've seen this
    last_seen TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    
    -- Learning
    user_corrections INT DEFAULT 0,         -- Times users corrected this
    auto_corrected INT DEFAULT 0,          -- Times system auto-corrected
    
    INDEX idx_normalized_name (normalized_name),
    INDEX idx_mcc_code (mcc_code),
    INDEX idx_country_category (country_code, primary_category)
);

CREATE TABLE merchant_aliases (
    id UUID PRIMARY KEY,
    merchant_id UUID REFERENCES merchants(id),
    alias VARCHAR(255) NOT NULL,           -- "WMT", "WALMART", "WAL-MART"
    alias_type VARCHAR(20),                -- "abbreviation", "misspelling", "regional"
    country_code VARCHAR(2),
    confidence DECIMAL(3,2),
    created_at TIMESTAMP,
    
    INDEX idx_alias (alias),
    INDEX idx_merchant_id (merchant_id)
);

CREATE TABLE merchant_patterns (
    id UUID PRIMARY KEY,
    merchant_id UUID REFERENCES merchants(id),
    pattern_type VARCHAR(20),              -- "prefix", "suffix", "contains", "regex"
    pattern VARCHAR(255) NOT NULL,         -- "WMT*", "*WALMART*", "^WMT\\s"
    confidence DECIMAL(3,2),
    created_at TIMESTAMP,
    
    INDEX idx_pattern (pattern)
);
```

#### Data Sources

1. **Initial Seed Data**:
   - OpenStreetMap POI data (global merchant locations)
   - Wikipedia lists of major retailers by country
   - Industry databases (retail chains, restaurant chains)
   - Government business registries (where available)

2. **User-Generated**:
   - Learn from user corrections
   - Crowdsource merchant aliases
   - Regional variations from user data

3. **Third-Party APIs**:
   - Google Places API (for local businesses)
   - Foursquare API (venue categorization)
   - Yelp API (business categories)

4. **Bank/Provider Data**:
   - Plaid merchant database
   - Bank-specific merchant codes
   - Credit card network data

### 3. MCC Code Integration

#### MCC Code Mapping

```java
public class MCCCodeMapper {
    // ISO 18245 Standard MCC Codes
    private static final Map<String, CategoryMapping> MCC_TO_CATEGORY = Map.of(
        // Groceries
        "5411", new CategoryMapping("groceries", "supermarket", 0.95),
        "5422", new CategoryMapping("groceries", "meat", 0.95),
        "5441", new CategoryMapping("groceries", "candy", 0.95),
        "5451", new CategoryMapping("groceries", "dairy", 0.95),
        
        // Restaurants
        "5811", new CategoryMapping("dining", "restaurant", 0.95),
        "5812", new CategoryMapping("dining", "fast_food", 0.95),
        "5813", new CategoryMapping("dining", "bar", 0.95),
        "5814", new CategoryMapping("dining", "cafe", 0.95),
        
        // Gas Stations
        "5541", new CategoryMapping("transportation", "gas", 0.95),
        "5542", new CategoryMapping("transportation", "gas", 0.95),
        
        // Transportation
        "4111", new CategoryMapping("transportation", "transit", 0.90),
        "4112", new CategoryMapping("transportation", "parking", 0.90),
        "4121", new CategoryMapping("transportation", "taxi", 0.90),
        
        // Travel
        "3000", new CategoryMapping("travel", "airline", 0.95),
        "3501", new CategoryMapping("travel", "hotel", 0.95),
        "3502", new CategoryMapping("travel", "hotel", 0.95),
        "3503", new CategoryMapping("travel", "hotel", 0.95),
        "3504", new CategoryMapping("travel", "hotel", 0.95),
        "3505", new CategoryMapping("travel", "hotel", 0.95),
        "3506", new CategoryMapping("travel", "hotel", 0.95),
        "3507", new CategoryMapping("travel", "hotel", 0.95),
        "3508", new CategoryMapping("travel", "hotel", 0.95),
        "3509", new CategoryMapping("travel", "hotel", 0.95),
        "3510", new CategoryMapping("travel", "hotel", 0.95),
        "3511", new CategoryMapping("travel", "hotel", 0.95),
        "3512", new CategoryMapping("travel", "hotel", 0.95),
        "3513", new CategoryMapping("travel", "hotel", 0.95),
        "3514", new CategoryMapping("travel", "hotel", 0.95),
        "3515", new CategoryMapping("travel", "hotel", 0.95),
        "3516", new CategoryMapping("travel", "hotel", 0.95),
        "3517", new CategoryMapping("travel", "hotel", 0.95),
        "3518", new CategoryMapping("travel", "hotel", 0.95),
        "3519", new CategoryMapping("travel", "hotel", 0.95),
        "3520", new CategoryMapping("travel", "hotel", 0.95),
        "3521", new CategoryMapping("travel", "hotel", 0.95),
        "3522", new CategoryMapping("travel", "hotel", 0.95),
        "3523", new CategoryMapping("travel", "hotel", 0.95),
        "3524", new CategoryMapping("travel", "hotel", 0.95),
        "3525", new CategoryMapping("travel", "hotel", 0.95),
        "3526", new CategoryMapping("travel", "hotel", 0.95),
        "3527", new CategoryMapping("travel", "hotel", 0.95),
        "3528", new CategoryMapping("travel", "hotel", 0.95),
        "3529", new CategoryMapping("travel", "hotel", 0.95),
        "3530", new CategoryMapping("travel", "hotel", 0.95),
        
        // Education
        "5192", new CategoryMapping("education", "books", 0.90),
        "5193", new CategoryMapping("education", "school_supplies", 0.90),
        "5942", new CategoryMapping("education", "books", 0.90),
        "5943", new CategoryMapping("education", "office_supplies", 0.90),
        "5970", new CategoryMapping("education", "art_supplies", 0.90),
        "5971", new CategoryMapping("education", "art_supplies", 0.90),
        "5972", new CategoryMapping("education", "stamps", 0.85),
        "5973", new CategoryMapping("education", "stationery", 0.90),
        "5975", new CategoryMapping("education", "hobby_shop", 0.85),
        "5976", new CategoryMapping("education", "printer_supplies", 0.90),
        "5977", new CategoryMapping("education", "cosmetics", 0.80),
        "5978", new CategoryMapping("education", "typewriter", 0.85),
        "5992", new CategoryMapping("education", "florist", 0.80),
        "5993", new CategoryMapping("education", "cigar", 0.80),
        "5994", new CategoryMapping("education", "newsstand", 0.85),
        "5995", new CategoryMapping("education", "pet_shop", 0.90),
        "5996", new CategoryMapping("education", "swimming_pool", 0.85),
        "5997", new CategoryMapping("education", "electric_razor", 0.80),
        "5998", new CategoryMapping("education", "tent", 0.85),
        "5999", new CategoryMapping("education", "misc", 0.70),
        
        // Healthcare
        "5912", new CategoryMapping("healthcare", "pharmacy", 0.95),
        "5975", new CategoryMapping("healthcare", "medical", 0.90),
        "8011", new CategoryMapping("healthcare", "doctor", 0.95),
        "8021", new CategoryMapping("healthcare", "dentist", 0.95),
        "8031", new CategoryMapping("healthcare", "ophthalmologist", 0.95),
        "8041", new CategoryMapping("healthcare", "chiropractor", 0.95),
        "8042", new CategoryMapping("healthcare", "optometrist", 0.95),
        "8043", new CategoryMapping("healthcare", "optician", 0.95),
        "8049", new CategoryMapping("healthcare", "podiatrist", 0.95),
        "8050", new CategoryMapping("healthcare", "nursing", 0.95),
        "8062", new CategoryMapping("healthcare", "hospital", 0.95),
        "8071", new CategoryMapping("healthcare", "medical_lab", 0.95),
        "8099", new CategoryMapping("healthcare", "medical_service", 0.90),
        
        // Utilities
        "4900", new CategoryMapping("utilities", "electric", 0.95),
        "4814", new CategoryMapping("utilities", "telecom", 0.95),
        "4899", new CategoryMapping("utilities", "cable", 0.95),
        
        // Entertainment
        "7832", new CategoryMapping("entertainment", "movie", 0.95),
        "7841", new CategoryMapping("entertainment", "video_rental", 0.90),
        "7911", new CategoryMapping("entertainment", "dance_hall", 0.90),
        "7922", new CategoryMapping("entertainment", "theater", 0.95),
        "7929", new CategoryMapping("entertainment", "band", 0.85),
        "7932", new CategoryMapping("entertainment", "billiards", 0.85),
        "7933", new CategoryMapping("entertainment", "bowling", 0.90),
        "7941", new CategoryMapping("entertainment", "sports", 0.90),
        "7991", new CategoryMapping("entertainment", "tourist_attraction", 0.90),
        "7992", new CategoryMapping("entertainment", "golf", 0.90),
        "7993", new CategoryMapping("entertainment", "video_game", 0.90),
        "7994", new CategoryMapping("entertainment", "video_game_arcade", 0.90),
        "7995", new CategoryMapping("entertainment", "betting", 0.85),
        "7996", new CategoryMapping("entertainment", "amusement_park", 0.95),
        "7997", new CategoryMapping("entertainment", "aquarium", 0.90),
        "7998", new CategoryMapping("entertainment", "aquarium", 0.90),
        "7999", new CategoryMapping("entertainment", "recreation", 0.85),
        
        // Shopping
        "5310", new CategoryMapping("shopping", "department_store", 0.95),
        "5311", new CategoryMapping("shopping", "department_store", 0.95),
        "5331", new CategoryMapping("shopping", "variety_store", 0.90),
        "5399", new CategoryMapping("shopping", "misc_general", 0.85),
        "5411", new CategoryMapping("groceries", "supermarket", 0.95),
        "5422", new CategoryMapping("groceries", "meat", 0.95),
        "5441", new CategoryMapping("groceries", "candy", 0.95),
        "5451", new CategoryMapping("groceries", "dairy", 0.95),
        "5462", new CategoryMapping("groceries", "bakeries", 0.95),
        "5499", new CategoryMapping("groceries", "misc_food", 0.90),
        "5611", new CategoryMapping("shopping", "mens_clothing", 0.95),
        "5621", new CategoryMapping("shopping", "womens_clothing", 0.95),
        "5631", new CategoryMapping("shopping", "womens_clothing", 0.95),
        "5641", new CategoryMapping("shopping", "childrens_clothing", 0.95),
        "5651", new CategoryMapping("shopping", "family_clothing", 0.95),
        "5655", new CategoryMapping("shopping", "sports_clothing", 0.95),
        "5661", new CategoryMapping("shopping", "shoe_store", 0.95),
        "5681", new CategoryMapping("shopping", "furrier", 0.90),
        "5691", new CategoryMapping("shopping", "mens_clothing", 0.95),
        "5697", new CategoryMapping("shopping", "tailor", 0.90),
        "5698", new CategoryMapping("shopping", "wig", 0.85),
        "5699", new CategoryMapping("shopping", "misc_clothing", 0.90),
        "5712", new CategoryMapping("shopping", "furniture", 0.95),
        "5713", new CategoryMapping("shopping", "floor_covering", 0.95),
        "5714", new CategoryMapping("shopping", "drapery", 0.90),
        "5718", new CategoryMapping("shopping", "fireplace", 0.90),
        "5719", new CategoryMapping("shopping", "misc_home", 0.90),
        "5722", new CategoryMapping("shopping", "household_appliance", 0.95),
        "5732", new CategoryMapping("shopping", "electronics", 0.95),
        "5733", new CategoryMapping("shopping", "office_supplies", 0.90),
        "5734", new CategoryMapping("shopping", "computer", 0.95),
        "5735", new CategoryMapping("shopping", "record_store", 0.90),
        "5811", new CategoryMapping("dining", "restaurant", 0.95),
        "5812", new CategoryMapping("dining", "fast_food", 0.95),
        "5813", new CategoryMapping("dining", "bar", 0.95),
        "5814", new CategoryMapping("dining", "cafe", 0.95),
        "5912", new CategoryMapping("healthcare", "pharmacy", 0.95),
        "5921", new CategoryMapping("groceries", "package_store", 0.90),
        "5931", new CategoryMapping("shopping", "used_merchandise", 0.90),
        "5932", new CategoryMapping("shopping", "antique", 0.90),
        "5933", new CategoryMapping("shopping", "pawn_shop", 0.85),
        "5935", new CategoryMapping("shopping", "wrecking", 0.85),
        "5937", new CategoryMapping("shopping", "antique_reproduction", 0.90),
        "5940", new CategoryMapping("shopping", "bicycle", 0.90),
        "5941", new CategoryMapping("shopping", "sporting_goods", 0.95),
        "5942", new CategoryMapping("education", "books", 0.90),
        "5943", new CategoryMapping("education", "office_supplies", 0.90),
        "5944", new CategoryMapping("shopping", "jewelry", 0.95),
        "5945", new CategoryMapping("shopping", "toy", 0.95),
        "5946", new CategoryMapping("shopping", "camera", 0.95),
        "5947", new CategoryMapping("shopping", "gift", 0.90),
        "5948", new CategoryMapping("shopping", "luggage", 0.95),
        "5949", new CategoryMapping("shopping", "sewing", 0.90),
        "5960", new CategoryMapping("shopping", "direct_marketing", 0.85),
        "5962", new CategoryMapping("shopping", "telemarketing", 0.85),
        "5963", new CategoryMapping("shopping", "door_to_door", 0.85),
        "5964", new CategoryMapping("shopping", "direct_marketing", 0.85),
        "5965", new CategoryMapping("shopping", "direct_marketing", 0.85),
        "5966", new CategoryMapping("shopping", "direct_marketing", 0.85),
        "5967", new CategoryMapping("shopping", "direct_marketing", 0.85),
        "5968", new CategoryMapping("shopping", "direct_marketing", 0.85),
        "5969", new CategoryMapping("shopping", "direct_marketing", 0.85),
        "5970", new CategoryMapping("education", "art_supplies", 0.90),
        "5971", new CategoryMapping("education", "art_supplies", 0.90),
        "5972", new CategoryMapping("education", "stamps", 0.85),
        "5973", new CategoryMapping("education", "stationery", 0.90),
        "5975", new CategoryMapping("education", "hobby_shop", 0.85),
        "5976", new CategoryMapping("education", "printer_supplies", 0.90),
        "5977", new CategoryMapping("education", "cosmetics", 0.80),
        "5978", new CategoryMapping("education", "typewriter", 0.85),
        "5983", new CategoryMapping("utilities", "fuel", 0.95),
        "5992", new CategoryMapping("education", "florist", 0.80),
        "5993", new CategoryMapping("education", "cigar", 0.80),
        "5994", new CategoryMapping("education", "newsstand", 0.85),
        "5995", new CategoryMapping("education", "pet_shop", 0.90),
        "5996", new CategoryMapping("education", "swimming_pool", 0.85),
        "5997", new CategoryMapping("education", "electric_razor", 0.80),
        "5998", new CategoryMapping("education", "tent", 0.85),
        "5999", new CategoryMapping("education", "misc", 0.70)
    );
    
    public CategoryMapping getCategoryFromMCC(String mccCode) {
        return MCC_TO_CATEGORY.getOrDefault(mccCode, 
            new CategoryMapping("other", "other", 0.50));
    }
}
```

### 4. Hierarchical Category System

```java
public class CategoryHierarchy {
    // Primary categories with subcategories
    public enum PrimaryCategory {
        GROCERIES("groceries", List.of(
            "supermarket", "convenience_store", "wholesale", 
            "organic", "specialty_food", "liquor_store"
        )),
        DINING("dining", List.of(
            "restaurant", "fast_food", "cafe", "bar", "food_delivery",
            "catering", "bakery", "ice_cream"
        )),
        TRANSPORTATION("transportation", List.of(
            "gas", "parking", "transit", "taxi", "ride_share",
            "toll", "car_rental", "car_service", "airport_shuttle"
        )),
        TRAVEL("travel", List.of(
            "airline", "hotel", "car_rental", "cruise", "tour",
            "travel_agency", "airport_lounge", "travel_insurance"
        )),
        HEALTHCARE("healthcare", List.of(
            "doctor", "dentist", "hospital", "pharmacy", "medical_lab",
            "vision", "mental_health", "physical_therapy"
        )),
        HEALTH("health", List.of(
            "gym", "fitness", "yoga", "sports_club", "spa",
            "massage", "hair_salon", "beauty"
        )),
        EDUCATION("education", List.of(
            "tuition", "books", "school_supplies", "online_course",
            "exam_fee", "library", "museum"
        )),
        ENTERTAINMENT("entertainment", List.of(
            "movie", "theater", "concert", "sports_event", "amusement_park",
            "gaming", "streaming", "music"
        )),
        SHOPPING("shopping", List.of(
            "department_store", "clothing", "electronics", "home_goods",
            "online_shopping", "marketplace"
        )),
        UTILITIES("utilities", List.of(
            "electric", "gas", "water", "internet", "phone", "cable",
            "trash", "sewer"
        )),
        SUBSCRIPTIONS("subscriptions", List.of(
            "streaming", "software", "membership", "magazine", "newspaper"
        )),
        PET("pet", List.of(
            "pet_food", "pet_supplies", "veterinarian", "pet_grooming",
            "pet_boarding"
        )),
        CHARITY("charity", List.of(
            "donation", "nonprofit", "religious", "political"
        )),
        RENT("rent", List.of(
            "rent", "mortgage", "hoa", "property_tax"
        )),
        OTHER("other", List.of(
            "general", "uncategorized", "transfer", "fee"
        ));
        
        private final String value;
        private final List<String> subcategories;
        
        PrimaryCategory(String value, List<String> subcategories) {
            this.value = value;
            this.subcategories = subcategories;
        }
    }
}
```

### 5. Regional & Language Support

```java
public class RegionalCategoryMapper {
    // Regional merchant name variations
    private static final Map<String, Map<String, String>> REGIONAL_MERCHANTS = Map.of(
        // US -> India
        "US_IN", Map.of(
            "Walmart", "Big Bazaar",
            "Target", "Reliance Fresh",
            "Starbucks", "Cafe Coffee Day",
            "McDonald's", "McDonald's India"
        ),
        // US -> UK
        "US_GB", Map.of(
            "Walmart", "Asda",
            "Target", "Tesco",
            "CVS", "Boots",
            "7-Eleven", "Spar"
        )
    );
    
    // Regional category names
    private static final Map<String, Map<String, String>> REGIONAL_CATEGORIES = Map.of(
        "en_US", Map.of("groceries", "groceries", "dining", "dining"),
        "en_GB", Map.of("groceries", "groceries", "dining", "eating_out"),
        "es_ES", Map.of("groceries", "comestibles", "dining", "restaurante"),
        "fr_FR", Map.of("groceries", "épicerie", "dining", "restaurant"),
        "de_DE", Map.of("groceries", "lebensmittel", "dining", "restaurant"),
        "ja_JP", Map.of("groceries", "食料品", "dining", "レストラン"),
        "zh_CN", Map.of("groceries", "杂货", "dining", "餐厅"),
        "hi_IN", Map.of("groceries", "किराना", "dining", "रेस्तरां")
    );
}
```

### 6. Learning & Feedback System

```java
public class CategoryLearningService {
    /**
     * Learn from user corrections
     */
    public void learnFromCorrection(
        String merchantName,
        String originalCategory,
        String correctedCategory,
        String userId
    ) {
        // Update merchant database
        Merchant merchant = merchantRepository.findByNormalizedName(
            normalizeMerchantName(merchantName)
        );
        
        if (merchant != null) {
            // If user corrects, increase confidence in correction
            if (merchant.getPrimaryCategory().equals(correctedCategory)) {
                merchant.incrementUserCorrections();
            } else {
                // User corrected to different category - update merchant
                merchant.setPrimaryCategory(correctedCategory);
                merchant.incrementUserCorrections();
                merchant.decrementConfidence();
            }
        } else {
            // New merchant - create entry
            createMerchantFromCorrection(merchantName, correctedCategory);
        }
        
        // Update ML model
        mlService.addTrainingExample(
            merchantName, correctedCategory, userId
        );
    }
    
    /**
     * Auto-correct based on patterns
     */
    public void autoCorrectIfConfident(
        Transaction transaction,
        double confidenceThreshold
    ) {
        if (transaction.getCategoryConfidence() < confidenceThreshold) {
            CategoryResult suggested = suggestCategory(transaction);
            if (suggested.getConfidence() > 0.90) {
                // Auto-correct with high confidence
                updateTransactionCategory(transaction, suggested);
                logAutoCorrection(transaction, suggested);
            }
        }
    }
}
```

### 7. Extensible Plugin Architecture

```java
public interface CategoryDetectionPlugin {
    /**
     * Plugin priority (lower = higher priority)
     */
    int getPriority();
    
    /**
     * Detect category from transaction
     */
    CategoryResult detectCategory(TransactionContext context);
    
    /**
     * Whether this plugin can handle this transaction
     */
    boolean canHandle(TransactionContext context);
}

// Example: Custom bank-specific plugin
@Component
public class WellsFargoCategoryPlugin implements CategoryDetectionPlugin {
    @Override
    public int getPriority() {
        return 1; // High priority for Wells Fargo transactions
    }
    
    @Override
    public CategoryResult detectCategory(TransactionContext context) {
        if (isWellsFargoAccount(context.getAccount())) {
            // Apply Wells Fargo-specific rules
            return applyWellsFargoRules(context);
        }
        return null; // Let other plugins handle
    }
    
    @Override
    public boolean canHandle(TransactionContext context) {
        return isWellsFargoAccount(context.getAccount());
    }
}
```

### 8. Implementation Plan

#### Phase 1: Foundation (Weeks 1-4)
1. Create merchant database schema
2. Seed with top 10,000 global merchants
3. Implement MCC code mapper
4. Build basic merchant lookup service

#### Phase 2: Enhanced Detection (Weeks 5-8)
1. Implement pattern matching system
2. Enhance ML model with merchant data
3. Add regional support
4. Build feedback loop

#### Phase 3: Learning System (Weeks 9-12)
1. Implement user correction learning
2. Auto-correction system
3. Confidence scoring
4. Analytics dashboard

#### Phase 4: Global Expansion (Weeks 13-16)
1. Add international merchant databases
2. Multi-language support
3. Regional category mappings
4. Currency-aware categorization

#### Phase 5: Optimization (Weeks 17-20)
1. Performance optimization
2. Caching layer
3. Batch processing
4. Real-time updates

### 9. Success Metrics

- **Accuracy**: Target 95%+ correct categorization
- **Coverage**: 90%+ of transactions matched to merchant database
- **Confidence**: 80%+ of transactions with >0.85 confidence
- **Learning Rate**: System improves 2-3% per month from user feedback
- **Global Coverage**: Support for 50+ countries, 20+ languages

### 10. Data Sources & Partnerships

1. **Open Data**:
   - OpenStreetMap POI database
   - Wikipedia merchant lists
   - Government business registries

2. **Commercial APIs**:
   - Google Places API
   - Foursquare Places API
   - Yelp Fusion API

3. **Financial Data Providers**:
   - Plaid (already integrated)
   - Yodlee
   - Finicity

4. **Industry Standards**:
   - ISO 18245 (MCC codes)
   - ISO 3166 (Country codes)
   - ISO 639 (Language codes)

### 11. Technical Architecture

```
┌─────────────────────────────────────────────────────────┐
│              Category Detection Service                  │
│  (Orchestrates all detection layers)                    │
└─────────────────────────────────────────────────────────┘
                            │
        ┌───────────────────┼───────────────────┐
        │                   │                   │
        ▼                   ▼                   ▼
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│   Merchant   │   │     MCC      │   │      ML      │
│   Database   │   │    Mapper     │   │   Service    │
└──────────────┘   └──────────────┘   └──────────────┘
        │                   │                   │
        ▼                   ▼                   ▼
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│   Pattern    │   │  Regional    │   │   Learning   │
│   Matcher    │   │   Mapper     │   │   Service    │
└──────────────┘   └──────────────┘   └──────────────┘
```

### 12. Migration Strategy

1. **Dual Mode**: Run old and new system in parallel
2. **Gradual Rollout**: Start with 10% of transactions
3. **A/B Testing**: Compare accuracy between systems
4. **User Feedback**: Collect corrections for learning
5. **Full Migration**: Switch 100% once confidence >95%

## Conclusion

This architecture provides:
- ✅ **Scalability**: Handles millions of merchants globally
- ✅ **Accuracy**: Multi-layer detection with confidence scoring
- ✅ **Extensibility**: Plugin architecture for custom rules
- ✅ **Learning**: Improves over time from user feedback
- ✅ **Global**: Supports international merchants and languages
- ✅ **Standards-Based**: Uses industry standards (MCC codes)
- ✅ **Maintainable**: Centralized database, no one-off fixes

The system evolves from reactive fixes to proactive, data-driven categorization.

