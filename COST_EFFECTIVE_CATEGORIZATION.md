# Cost-Effective Transaction Categorization Architecture

## The Cost Problem

Database lookups for every transaction = 💸💸💸
- DynamoDB: $0.25 per million reads
- At 1M transactions/month = $0.25/month (not bad, but scales)
- At 10M transactions/month = $2.50/month
- At 100M transactions/month = $25/month
- Plus write costs for learning updates

## Solution: In-Memory + Smart Caching

### Architecture: Zero-Cost Lookups

```
┌─────────────────────────────────────────────────────────┐
│         Transaction Input                               │
└─────────────────────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────────────┐
│  Layer 1: In-Memory Merchant Map (0 cost, <1ms)          │
│  - Loaded at application startup                        │
│  - HashMap<String, Merchant> lookup                     │
│  - Covers 95% of transactions                           │
└─────────────────────────────────────────────────────────┘
                    │ (if no match)
                    ▼
┌─────────────────────────────────────────────────────────┐
│  Layer 2: Local Cache (Redis free tier or local)        │
│  - Recently seen merchants                              │
│  - User corrections                                     │
│  - TTL: 7 days                                          │
└─────────────────────────────────────────────────────────┘
                    │ (if no match)
                    ▼
┌─────────────────────────────────────────────────────────┐
│  Layer 3: Database (only for new/unknown merchants)     │
│  - DynamoDB for persistence                             │
│  - Only ~5% of transactions hit this                    │
│  - Batch writes for learning updates                   │
└─────────────────────────────────────────────────────────┘
```

## Implementation Strategy

### Option 1: In-Memory HashMap (Recommended - $0 cost)

```java
@Service
public class InMemoryMerchantService {
    // Loaded once at startup, stays in memory
    private final Map<String, Merchant> merchantMap = new ConcurrentHashMap<>();
    private final Map<String, Merchant> aliasMap = new ConcurrentHashMap<>();
    private final MCCCodeMapper mccMapper;
    
    @PostConstruct
    public void initialize() {
        // Load from static file or database ONCE at startup
        loadMerchantsFromFile(); // or load from DB once
        logger.info("Loaded {} merchants into memory", merchantMap.size());
    }
    
    public CategoryResult detectCategory(
        String merchantName,
        String description,
        String mccCode
    ) {
        // Layer 1: MCC Code (no lookup needed, just map lookup)
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
        
        // Layer 2: In-memory merchant lookup (O(1), zero cost)
        String normalized = normalizeMerchantName(merchantName);
        Merchant merchant = merchantMap.get(normalized);
        
        if (merchant == null) {
            // Try alias map
            merchant = aliasMap.get(normalized);
        }
        
        if (merchant != null) {
            return new CategoryResult(
                merchant.getPrimaryCategory(),
                merchant.getDetailedCategory(),
                "MERCHANT_DB",
                0.95
            );
        }
        
        // No match - fall back to existing logic
        return null;
    }
    
    private void loadMerchantsFromFile() {
        // Load from JSON/CSV file bundled with application
        // Or load from database ONCE at startup
        // File size: ~1MB for 10,000 merchants = negligible memory
    }
}
```

**Cost**: $0 (just application memory, ~10-50MB for 100K merchants)

### Option 2: Embedded SQLite Database

```java
@Service
public class EmbeddedMerchantService {
    // SQLite database file bundled with application
    private final String DB_PATH = "classpath:merchants.db";
    private Connection connection;
    
    @PostConstruct
    public void initialize() {
        // Load SQLite database into memory
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        // Copy from file to memory
        loadDatabaseIntoMemory();
    }
    
    public CategoryResult detectCategory(...) {
        // In-memory SQLite query (very fast, zero external cost)
        String sql = "SELECT * FROM merchants WHERE normalized_name = ?";
        // Query in-memory database
    }
}
```

**Cost**: $0 (SQLite is file-based, can be loaded into memory)

### Option 3: Hybrid Approach (Best of Both Worlds)

```java
@Service
public class HybridMerchantService {
    // In-memory for common merchants (95% of transactions)
    private final Map<String, Merchant> hotMerchants = new ConcurrentHashMap<>();
    
    // Local cache for recently seen (4% of transactions)
    private final Cache<String, Merchant> recentCache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(7, TimeUnit.DAYS)
        .build();
    
    // Database only for new/unknown (1% of transactions)
    private final MerchantRepository merchantRepository;
    
    public CategoryResult detectCategory(...) {
        // 1. Check hot merchants (in-memory, 95% hit rate)
        Merchant merchant = hotMerchants.get(normalized);
        if (merchant != null) {
            return createResult(merchant);
        }
        
        // 2. Check recent cache (4% hit rate)
        merchant = recentCache.getIfPresent(normalized);
        if (merchant != null) {
            return createResult(merchant);
        }
        
        // 3. Database lookup (only 1% of transactions)
        merchant = merchantRepository.findByNormalizedName(normalized);
        if (merchant != null) {
            // Add to cache for next time
            recentCache.put(normalized, merchant);
            return createResult(merchant);
        }
        
        return null;
    }
}
```

**Cost**: 
- Hot merchants: $0 (in-memory)
- Recent cache: $0 (local cache like Caffeine)
- Database: $0.01/month (only 1% of transactions)

## Recommended Approach: Static File + In-Memory

### Step 1: Create Merchant Data File

```json
// merchants.json (bundled with application, ~1-2MB for 10K merchants)
{
  "merchants": [
    {
      "canonical_name": "Walmart",
      "normalized_name": "walmart",
      "aliases": ["wmt", "wal-mart", "walmart.com"],
      "primary_category": "groceries",
      "detailed_category": "supermarket",
      "mcc_code": "5411",
      "confidence": 0.95
    },
    {
      "canonical_name": "Target",
      "normalized_name": "target",
      "aliases": ["tgt"],
      "primary_category": "shopping",
      "detailed_category": "department_store",
      "mcc_code": "5311",
      "confidence": 0.95
    }
    // ... 10,000 more merchants
  ]
}
```

### Step 2: Load at Startup

```java
@Service
public class StaticMerchantService {
    private final Map<String, Merchant> merchantMap = new ConcurrentHashMap<>();
    private final Map<String, Merchant> aliasMap = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void loadMerchants() {
        // Load from bundled JSON file
        InputStream is = getClass().getResourceAsStream("/merchants.json");
        MerchantData data = objectMapper.readValue(is, MerchantData.class);
        
        for (Merchant merchant : data.getMerchants()) {
            merchantMap.put(merchant.getNormalizedName(), merchant);
            for (String alias : merchant.getAliases()) {
                aliasMap.put(normalizeMerchantName(alias), merchant);
            }
        }
        
        logger.info("Loaded {} merchants from static file", merchantMap.size());
    }
    
    public CategoryResult detectCategory(String merchantName, String mccCode) {
        // O(1) HashMap lookup - zero cost, <1ms
        String normalized = normalizeMerchantName(merchantName);
        Merchant merchant = merchantMap.get(normalized);
        
        if (merchant == null) {
            merchant = aliasMap.get(normalized);
        }
        
        if (merchant != null) {
            return new CategoryResult(
                merchant.getPrimaryCategory(),
                merchant.getDetailedCategory(),
                "STATIC_MERCHANT_DB",
                0.95
            );
        }
        
        // Fall back to MCC or existing logic
        return null;
    }
}
```

### Step 3: Update Strategy

**For Learning/Updates**:
- User corrections → Write to database (batch writes, once per hour)
- New merchants → Add to database, update static file in next deployment
- Static file updated weekly/monthly via CI/CD

**Cost**: 
- Runtime: $0 (all in-memory)
- Updates: $0.10/month (batch writes for learning)
- Storage: $0 (file bundled with app, DB only for new merchants)

## Memory Footprint

### 10,000 Merchants
- JSON file: ~500KB
- In-memory: ~2-3MB
- **Cost**: $0

### 100,000 Merchants
- JSON file: ~5MB
- In-memory: ~20-30MB
- **Cost**: $0 (still negligible)

### 1,000,000 Merchants
- JSON file: ~50MB
- In-memory: ~200-300MB
- **Cost**: $0 (acceptable for most servers)

## Performance Comparison

| Approach | Lookup Time | Cost/Month | Scalability |
|----------|-------------|------------|-------------|
| **DynamoDB per transaction** | 10-50ms | $25-250 | ❌ Expensive |
| **In-Memory HashMap** | <1ms | $0 | ✅ Excellent |
| **Embedded SQLite** | 1-5ms | $0 | ✅ Good |
| **Hybrid (Hot + Cache + DB)** | <1ms (95%), 5ms (4%), 20ms (1%) | $0.01 | ✅ Best |

## Recommended Implementation

### Phase 1: Static File (Week 1)
1. Create `merchants.json` with top 10,000 merchants
2. Load into HashMap at startup
3. Zero runtime cost, <1ms lookups

### Phase 2: Add Caching (Week 2)
1. Add Caffeine cache for recently seen merchants
2. Reduces need for database lookups
3. Still $0 cost (local cache)

### Phase 3: Database for Learning (Week 3)
1. Use DynamoDB only for:
   - Storing user corrections
   - New merchants discovered
   - Learning updates
2. Batch writes (once per hour)
3. Cost: ~$0.10/month

### Phase 4: Periodic Updates (Week 4)
1. Update static file weekly via CI/CD
2. Include new merchants from database
3. Zero runtime impact

## Cost Breakdown

### Static File Approach
- **Runtime**: $0 (in-memory lookups)
- **Storage**: $0 (file bundled with app)
- **Updates**: $0 (CI/CD updates file)
- **Learning DB**: $0.10/month (batch writes only)
- **Total**: **$0.10/month** 🎉

### Hybrid Approach
- **Hot merchants (95%)**: $0 (in-memory)
- **Recent cache (4%)**: $0 (local cache)
- **Database (1%)**: $0.01/month
- **Learning updates**: $0.10/month
- **Total**: **$0.11/month** 🎉

## Conclusion

**Best Approach**: Static file loaded into in-memory HashMap
- ✅ Zero runtime cost
- ✅ <1ms lookup time
- ✅ Scales to millions of merchants
- ✅ Simple to implement
- ✅ Easy to update (just update file)

**For Learning**: Use database only for:
- Storing user corrections (batch writes)
- New merchant discovery
- Analytics

**Total Cost**: ~$0.10-0.50/month even at scale! 🚀

