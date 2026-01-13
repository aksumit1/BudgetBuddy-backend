# Global Categorization Implementation Roadmap

## Quick Start: Phase 1 Implementation

### Step 1: Create Merchant Database Service

```java
// MerchantRepository.java
public interface MerchantRepository {
    Optional<Merchant> findByNormalizedName(String normalizedName);
    Optional<Merchant> findByAlias(String alias);
    Optional<Merchant> findByMCC(String mccCode);
    List<Merchant> findByPattern(String pattern);
    void save(Merchant merchant);
    void updateConfidence(String merchantId, double confidence);
}

// MerchantService.java
@Service
public class MerchantService {
    private final MerchantRepository merchantRepository;
    private final MCCCodeMapper mccMapper;
    
    public CategoryResult detectCategory(
        String merchantName,
        String description,
        String mccCode,
        String countryCode
    ) {
        // Layer 1: Exact merchant match
        String normalized = normalizeMerchantName(merchantName);
        Optional<Merchant> merchant = merchantRepository.findByNormalizedName(normalized);
        
        if (merchant.isPresent()) {
            return new CategoryResult(
                merchant.get().getPrimaryCategory(),
                merchant.get().getDetailedCategory(),
                "MERCHANT_DB",
                0.95
            );
        }
        
        // Layer 2: MCC code lookup
        if (mccCode != null) {
            CategoryMapping mccMapping = mccMapper.getCategoryFromMCC(mccCode);
            if (mccMapping.getConfidence() > 0.80) {
                return new CategoryResult(
                    mccMapping.getPrimaryCategory(),
                    mccMapping.getDetailedCategory(),
                    "MCC_CODE",
                    mccMapping.getConfidence()
                );
            }
        }
        
        // Continue to other layers...
        return null;
    }
}
```

### Step 2: Seed Initial Merchant Database

Create a CSV file with top merchants:

```csv
canonical_name,normalized_name,primary_category,detailed_category,mcc_code,country_code
Walmart,walmart,groceries,supermarket,5411,US
Target,target,shopping,department_store,5311,US
Starbucks,starbucks,dining,cafe,5814,US
McDonald's,mcdonalds,dining,fast_food,5812,US
Amazon,amazon,shopping,online_shopping,5999,US
...
```

### Step 3: Integrate with Existing System

Modify `TransactionTypeCategoryService` to use merchant database first:

```java
public CategoryResult determineCategory(...) {
    // NEW: Try merchant database first
    CategoryResult merchantResult = merchantService.detectCategory(
        merchantName, description, mccCode, countryCode
    );
    
    if (merchantResult != null && merchantResult.getConfidence() > 0.90) {
        return merchantResult; // High confidence, use it
    }
    
    // Fall back to existing logic...
    return existingCategoryDetection(...);
}
```

## Migration Path

### Week 1-2: Foundation
- [ ] Create DynamoDB tables for merchants, aliases, patterns
- [ ] Implement MerchantRepository
- [ ] Implement MCCCodeMapper with full ISO 18245 mapping
- [ ] Seed database with top 1,000 US merchants

### Week 3-4: Integration
- [ ] Integrate MerchantService into TransactionTypeCategoryService
- [ ] Add merchant lookup as first layer
- [ ] Add logging/metrics for merchant database hits
- [ ] A/B test: 10% of transactions use new system

### Week 5-6: Learning System
- [ ] Track user corrections
- [ ] Update merchant confidence scores
- [ ] Auto-create merchant entries from corrections
- [ ] Build feedback loop

### Week 7-8: Expansion
- [ ] Add international merchants (top 500 per major country)
- [ ] Regional name variations
- [ ] Multi-language support
- [ ] Increase A/B test to 50%

### Week 9-10: Optimization
- [ ] Performance tuning
- [ ] Caching layer (Redis)
- [ ] Batch merchant updates
- [ ] Full rollout (100%)

## Success Criteria

- **Week 4**: 60% of transactions matched to merchant database
- **Week 8**: 80% matched, 90% accuracy
- **Week 10**: 85% matched, 95% accuracy, <50ms average detection time

## Data Collection Strategy

1. **User Corrections**: Every time user changes category, log it
2. **Auto-Learning**: When same merchant consistently gets same correction, update database
3. **Crowdsourcing**: Allow users to suggest merchant aliases
4. **External APIs**: Periodically sync with Google Places, Foursquare for new merchants

## Cost Estimation

- **Database Storage**: ~$50/month for 1M merchants
- **External APIs**: ~$200/month for Google Places/Foursquare
- **ML Training**: ~$100/month for model updates
- **Total**: ~$350/month for global coverage

## ROI

- **Reduced Support**: Fewer categorization questions = less support load
- **User Satisfaction**: Better categorization = happier users = retention
- **Development Time**: No more one-off fixes = faster feature development
- **Scalability**: System improves automatically = less manual work

