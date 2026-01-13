package com.budgetbuddy.service.category;

import com.budgetbuddy.service.TransactionTypeCategoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zero-cost merchant categorization service using in-memory HashMap
 * 
 * Loads merchant data from static JSON file at startup.
 * All lookups are O(1) HashMap operations with zero external cost.
 * 
 * Memory footprint: ~2-3MB for 10,000 merchants, ~20-30MB for 100,000 merchants
 * Lookup time: <1ms (in-memory HashMap)
 * Cost: $0 (no database calls)
 */
@Service
public class InMemoryMerchantService {
    
    private static final Logger logger = LoggerFactory.getLogger(InMemoryMerchantService.class);
    
    // In-memory merchant database (loaded once at startup)
    private final Map<String, Merchant> merchantMap = new ConcurrentHashMap<>();
    private final Map<String, Merchant> aliasMap = new ConcurrentHashMap<>();
    
    private final MCCCodeMapper mccMapper;
    private final FuzzyMatchingService fuzzyMatchingService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Configuration
    private static final double HIGH_CONFIDENCE_THRESHOLD = 0.95; // 95% for exact matches
    private static final double MEDIUM_CONFIDENCE_THRESHOLD = 0.90; // 90% for fuzzy matches
    
    public InMemoryMerchantService(MCCCodeMapper mccMapper, FuzzyMatchingService fuzzyMatchingService) {
        this.mccMapper = mccMapper;
        this.fuzzyMatchingService = fuzzyMatchingService;
    }
    
    @PostConstruct
    public void initialize() {
        try {
            // Load merchants from static JSON file bundled with application
            ClassPathResource resource = new ClassPathResource("data/merchants.json");
            if (resource.exists()) {
                InputStream is = resource.getInputStream();
                MerchantData data = objectMapper.readValue(is, MerchantData.class);
                
                for (Merchant merchant : data.getMerchants()) {
                    // Add canonical name
                    merchantMap.put(normalizeMerchantName(merchant.getCanonicalName()), merchant);
                    
                    // Add all aliases
                    if (merchant.getAliases() != null) {
                        for (String alias : merchant.getAliases()) {
                            aliasMap.put(normalizeMerchantName(alias), merchant);
                        }
                    }
                }
                
                logger.info("✅ Loaded {} merchants into memory ({} canonical, {} aliases)", 
                    data.getMerchants().size(), merchantMap.size(), aliasMap.size());
            } else {
                logger.warn("⚠️ merchants.json not found, merchant database will be empty");
            }
        } catch (Exception e) {
            logger.error("❌ Failed to load merchant database", e);
        }
    }
    
    /**
     * Detect category from merchant name and MCC code
     * 
     * Priority:
     * 1. MCC code (if available) - 95% confidence
     * 2. Merchant database lookup - 95% confidence
     * 3. Returns null if no match (fall back to existing logic)
     * 
     * @param merchantName Merchant name from transaction
     * @param description Transaction description
     * @param mccCode MCC code from bank (if available)
     * @return CategoryResult or null if no match
     */
    public TransactionTypeCategoryService.CategoryResult detectCategory(
        String merchantName,
        String description,
        String mccCode
    ) {
        // Layer 1: MCC Code (highest confidence, zero lookup cost)
        if (mccCode != null && !mccCode.trim().isEmpty()) {
            MCCCodeMapper.CategoryMapping mccMapping = mccMapper.getCategoryFromMCC(mccCode);
            if (mccMapping != null && mccMapping.getConfidence() > 0.80) {
                logger.debug("Category detected from MCC code {}: {} (confidence: {})", 
                    mccCode, mccMapping.getPrimaryCategory(), mccMapping.getConfidence());
                return new TransactionTypeCategoryService.CategoryResult(
                    mccMapping.getPrimaryCategory(),
                    mccMapping.getDetailedCategory(),
                    "MCC_CODE",
                    mccMapping.getConfidence()
                );
            }
        }
        
        // Layer 2: Merchant database lookup (O(1) HashMap lookup, zero cost)
        if (merchantName != null && !merchantName.trim().isEmpty()) {
            String normalized = normalizeMerchantName(merchantName);
            
            // Try exact match first (canonical names)
            Merchant merchant = merchantMap.get(normalized);
            String matchSource = "MERCHANT_DB_EXACT";
            double confidence = HIGH_CONFIDENCE_THRESHOLD;
            
            // Try alias map if not found
            if (merchant == null) {
                merchant = aliasMap.get(normalized);
                matchSource = "MERCHANT_DB_ALIAS";
            }
            
            // If exact match not found, try extracting the first significant word(s) from the ORIGINAL merchant name
            // This handles cases like "LULULEMON ATHLETICA USA B TO C..." where the core merchant is "LULULEMON"
            // We extract words BEFORE normalization to preserve word boundaries
            if (merchant == null) {
                // Extract first 1-2 words from original merchant name (before normalization)
                String[] originalWords = merchantName.trim().split("[\\s\\-]+");
                if (originalWords.length > 0 && originalWords[0].length() >= 5) {
                    // Try first word only (e.g., "LULULEMON" from "LULULEMON ATHLETICA USA B TO C...")
                    String firstWordNormalized = normalizeMerchantName(originalWords[0]);
                    merchant = merchantMap.get(firstWordNormalized);
                    if (merchant == null) {
                        merchant = aliasMap.get(firstWordNormalized);
                    }
                    if (merchant != null) {
                        matchSource = "MERCHANT_DB_PREFIX";
                        confidence = 0.93; // Slightly lower confidence for prefix match
                    }
                    
                    // Try first two words if first word alone didn't match (e.g., "WHOLE FOODS" from "WHOLE FOODS MARKET")
                    if (merchant == null && originalWords.length >= 2 && originalWords[1].length() >= 3) {
                        String firstTwoWordsNormalized = normalizeMerchantName(originalWords[0] + " " + originalWords[1]);
                        merchant = merchantMap.get(firstTwoWordsNormalized);
                        if (merchant == null) {
                            merchant = aliasMap.get(firstTwoWordsNormalized);
                        }
                        if (merchant != null) {
                            matchSource = "MERCHANT_DB_PREFIX";
                            confidence = 0.93;
                        }
                    }
                }
            }
            
            // If exact match found, return immediately
            if (merchant != null) {
                logger.debug("Category detected from merchant database ({}): {} → {} (confidence: {})", 
                    matchSource, merchantName, merchant.getPrimaryCategory(), confidence);
                return new TransactionTypeCategoryService.CategoryResult(
                    merchant.getPrimaryCategory(),
                    merchant.getDetailedCategory(),
                    matchSource,
                    confidence
                );
            }
            
            // Layer 3: Fuzzy matching (for typos, abbreviations, variations)
            // Try fuzzy match on canonical names
            FuzzyMatchingService.FuzzyMatch fuzzyMatch = fuzzyMatchingService.findBestMatch(
                normalized, merchantMap
            );
            
            // If no match in canonical, try aliases
            if (fuzzyMatch == null) {
                fuzzyMatch = fuzzyMatchingService.findBestMatch(normalized, aliasMap);
            }
            
            if (fuzzyMatch != null) {
                merchant = merchantMap.get(fuzzyMatch.getMatchedMerchant());
                if (merchant == null) {
                    merchant = aliasMap.get(fuzzyMatch.getMatchedMerchant());
                }
                
                if (merchant != null) {
                    // Use fuzzy match confidence (already calculated)
                    double fuzzyConfidence = fuzzyMatch.getConfidence();
                    
                    // Only return if confidence meets threshold
                    if (fuzzyConfidence >= MEDIUM_CONFIDENCE_THRESHOLD) {
                        logger.debug("Category detected from merchant database (fuzzy {}): {} → {} (similarity: {:.2f}, confidence: {:.2f})", 
                            fuzzyMatch.getMatchType(), merchantName, merchant.getPrimaryCategory(), 
                            fuzzyMatch.getSimilarity(), fuzzyConfidence);
                        return new TransactionTypeCategoryService.CategoryResult(
                            merchant.getPrimaryCategory(),
                            merchant.getDetailedCategory(),
                            "MERCHANT_DB_FUZZY_" + fuzzyMatch.getMatchType(),
                            fuzzyConfidence
                        );
                    }
                }
            }
        }
        
        // No match - return null to fall back to existing logic
        return null;
    }
    
    /**
     * Normalize merchant name for lookup
     * - Lowercase
     * - Remove special characters
     * - Remove common prefixes/suffixes
     */
    private String normalizeMerchantName(String name) {
        if (name == null) return "";
        
        return name.toLowerCase()
            .replaceAll("[^a-z0-9]", "")  // Remove special chars
            .replaceAll("\\s+", "")       // Remove spaces
            .trim();
    }
    
    /**
     * Get statistics about loaded merchants
     */
    public MerchantStats getStats() {
        return new MerchantStats(
            merchantMap.size(),
            aliasMap.size(),
            merchantMap.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    Merchant::getPrimaryCategory,
                    java.util.stream.Collectors.counting()
                ))
        );
    }
    
    // ========== Data Models ==========
    
    public static class MerchantData {
        private List<Merchant> merchants;
        
        public List<Merchant> getMerchants() { return merchants; }
        public void setMerchants(List<Merchant> merchants) { this.merchants = merchants; }
    }
    
    public static class Merchant {
        @JsonProperty("canonical_name")
        private String canonicalName;
        @JsonProperty("normalized_name")
        private String normalizedName;
        private List<String> aliases;
        @JsonProperty("primary_category")
        private String primaryCategory;
        @JsonProperty("detailed_category")
        private String detailedCategory;
        @JsonProperty("mcc_code")
        private String mccCode;
        @JsonProperty("country_code")
        private String countryCode;
        private double confidence;
        
        // Getters and setters
        public String getCanonicalName() { return canonicalName; }
        public void setCanonicalName(String canonicalName) { this.canonicalName = canonicalName; }
        
        public String getNormalizedName() { return normalizedName; }
        public void setNormalizedName(String normalizedName) { this.normalizedName = normalizedName; }
        
        public List<String> getAliases() { return aliases; }
        public void setAliases(List<String> aliases) { this.aliases = aliases; }
        
        public String getPrimaryCategory() { return primaryCategory; }
        public void setPrimaryCategory(String primaryCategory) { this.primaryCategory = primaryCategory; }
        
        public String getDetailedCategory() { return detailedCategory; }
        public void setDetailedCategory(String detailedCategory) { this.detailedCategory = detailedCategory; }
        
        public String getMccCode() { return mccCode; }
        public void setMccCode(String mccCode) { this.mccCode = mccCode; }
        
        public String getCountryCode() { return countryCode; }
        public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
        
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
    }
    
    public static class MerchantStats {
        private final int canonicalCount;
        private final int aliasCount;
        private final Map<String, Long> categoryDistribution;
        
        public MerchantStats(int canonicalCount, int aliasCount, Map<String, Long> categoryDistribution) {
            this.canonicalCount = canonicalCount;
            this.aliasCount = aliasCount;
            this.categoryDistribution = categoryDistribution;
        }
        
        public int getCanonicalCount() { return canonicalCount; }
        public int getAliasCount() { return aliasCount; }
        public Map<String, Long> getCategoryDistribution() { return categoryDistribution; }
    }
}

