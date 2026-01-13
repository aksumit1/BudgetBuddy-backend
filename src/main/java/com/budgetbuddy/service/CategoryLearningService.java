package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.UserCorrectionTable;
import com.budgetbuddy.model.dynamodb.CustomMerchantMappingTable;
import com.budgetbuddy.service.category.InMemoryMerchantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for learning from user corrections and managing custom merchant mappings
 * 
 * Features:
 * - Track user corrections for learning
 * - Manage custom merchant/category mappings
 * - Auto-learn from repeated corrections
 * - Batch updates for performance
 */
@Service
public class CategoryLearningService {
    
    private static final Logger logger = LoggerFactory.getLogger(CategoryLearningService.class);
    
    private final DynamoDbTable<UserCorrectionTable> correctionTable;
    private final DynamoDbTable<CustomMerchantMappingTable> customMappingTable;
    
    // Threshold for auto-learning (if same correction made N times, auto-apply)
    private static final int AUTO_LEARN_THRESHOLD = 3;
    
    public CategoryLearningService(DynamoDbEnhancedClient dynamoDbClient) {
        this.correctionTable = dynamoDbClient.table("UserCorrections", 
            TableSchema.fromBean(UserCorrectionTable.class));
        this.customMappingTable = dynamoDbClient.table("CustomMerchantMappings",
            TableSchema.fromBean(CustomMerchantMappingTable.class));
    }
    
    /**
     * Record a user correction for learning
     * 
     * @param userId User who made the correction
     * @param transactionId Transaction that was corrected
     * @param merchantName Merchant name (normalized)
     * @param originalCategoryPrimary Original category
     * @param originalCategoryDetailed Original detailed category
     * @param correctedCategoryPrimary User's correction
     * @param correctedCategoryDetailed User's detailed correction
     * @param originalTransactionType Original transaction type
     * @param correctedTransactionType User's transaction type correction (if any)
     * @param description Transaction description for context
     */
    public void recordCorrection(
            String userId,
            String transactionId,
            String merchantName,
            String originalCategoryPrimary,
            String originalCategoryDetailed,
            String correctedCategoryPrimary,
            String correctedCategoryDetailed,
            String originalTransactionType,
            String correctedTransactionType,
            String description) {
        
        // Edge case: Validate inputs
        if (userId == null || userId.trim().isEmpty()) {
            logger.warn("Cannot record correction: userId is null or empty");
            return;
        }
        if (transactionId == null || transactionId.trim().isEmpty()) {
            logger.warn("Cannot record correction: transactionId is null or empty");
            return;
        }
        if (correctedCategoryPrimary == null || correctedCategoryPrimary.trim().isEmpty()) {
            logger.warn("Cannot record correction: correctedCategoryPrimary is null or empty");
            return;
        }
        
        try {
            // Check if similar correction exists
            String normalizedMerchant = normalizeMerchantName(merchantName);
            UserCorrectionTable existing = findExistingCorrection(
                userId, normalizedMerchant, correctedCategoryPrimary);
            
            Instant now = Instant.now();
            
            if (existing != null) {
                // Update existing correction count
                Integer currentCount = existing.getCorrectionCount();
                existing.setCorrectionCount((currentCount != null ? currentCount : 0) + 1);
                existing.setUpdatedAt(now);
                existing.setCorrectedAt(now);
                correctionTable.updateItem(existing);
                
                logger.info("Updated correction count for merchant '{}' → '{}': {} times", 
                    normalizedMerchant, correctedCategoryPrimary, existing.getCorrectionCount());
                
                // Auto-learn if threshold reached
                if (existing.getCorrectionCount() >= AUTO_LEARN_THRESHOLD) {
                    autoLearnFromCorrection(userId, normalizedMerchant, correctedCategoryPrimary, 
                        correctedCategoryDetailed, correctedTransactionType);
                }
            } else {
                // Create new correction record
                UserCorrectionTable correction = new UserCorrectionTable();
                correction.setCorrectionId(UUID.randomUUID().toString());
                correction.setUserId(userId);
                correction.setTransactionId(transactionId);
                correction.setMerchantName(normalizedMerchant);
                correction.setOriginalCategoryPrimary(originalCategoryPrimary);
                correction.setOriginalCategoryDetailed(originalCategoryDetailed);
                correction.setCorrectedCategoryPrimary(correctedCategoryPrimary);
                correction.setCorrectedCategoryDetailed(correctedCategoryDetailed);
                correction.setOriginalTransactionType(originalTransactionType);
                correction.setCorrectedTransactionType(correctedTransactionType);
                correction.setDescription(description);
                correction.setCorrectionCount(1);
                correction.setCorrectedAt(now);
                correction.setCreatedAt(now);
                correction.setUpdatedAt(now);
                
                correctionTable.putItem(correction);
                
                logger.info("Recorded new correction: merchant '{}' → '{}'", 
                    normalizedMerchant, correctedCategoryPrimary);
            }
        } catch (DynamoDbException e) {
            logger.error("Failed to record correction (DynamoDB error): {}", e.getMessage(), e);
            // Don't throw - learning is best effort
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid input for correction recording: {}", e.getMessage());
            // Don't throw - learning is best effort
        } catch (Exception e) {
            logger.error("Unexpected error recording correction: {}", e.getMessage(), e);
            // Don't throw - learning is best effort
        }
    }
    
    /**
     * Create or update a custom merchant mapping
     * 
     * @param userId User creating the mapping
     * @param merchantName Merchant name
     * @param aliases Alternative names/patterns
     * @param categoryPrimary Primary category
     * @param categoryDetailed Detailed category
     * @param transactionType Optional transaction type
     * @return Created/updated mapping
     */
    public CustomMerchantMappingTable createOrUpdateCustomMapping(
            String userId,
            String merchantName,
            List<String> aliases,
            String categoryPrimary,
            String categoryDetailed,
            String transactionType) {
        
        // Edge case: Validate inputs
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("userId cannot be null or empty");
        }
        if (merchantName == null || merchantName.trim().isEmpty()) {
            throw new IllegalArgumentException("merchantName cannot be null or empty");
        }
        if (categoryPrimary == null || categoryPrimary.trim().isEmpty()) {
            throw new IllegalArgumentException("categoryPrimary cannot be null or empty");
        }
        
        try {
            String normalizedMerchant = normalizeMerchantName(merchantName);
            
            // Edge case: Handle empty normalized merchant
            if (normalizedMerchant.isEmpty()) {
                throw new IllegalArgumentException("Merchant name cannot be normalized to empty string");
            }
            
            // Check if mapping exists
            CustomMerchantMappingTable existing = findCustomMapping(userId, normalizedMerchant);
            
            Instant now = Instant.now();
            
            if (existing != null) {
                // Update existing
                existing.setCategoryPrimary(categoryPrimary);
                existing.setCategoryDetailed(categoryDetailed != null ? categoryDetailed : categoryPrimary);
                existing.setTransactionType(transactionType);
                existing.setAliases(aliases != null ? aliases : List.of());
                existing.setIsActive(true);
                existing.setUpdatedAt(now);
                customMappingTable.updateItem(existing);
                
                logger.info("Updated custom mapping for merchant '{}'", normalizedMerchant);
                return existing;
            } else {
                // Create new
                CustomMerchantMappingTable mapping = new CustomMerchantMappingTable();
                mapping.setMappingId(UUID.randomUUID().toString());
                mapping.setUserId(userId);
                mapping.setMerchantName(merchantName); // Store original name for display (normalization done on lookup)
                mapping.setAliases(aliases != null ? aliases : List.of());
                mapping.setCategoryPrimary(categoryPrimary);
                mapping.setCategoryDetailed(categoryDetailed != null ? categoryDetailed : categoryPrimary);
                mapping.setTransactionType(transactionType);
                mapping.setIsActive(true);
                mapping.setUsageCount(0);
                mapping.setCreatedAt(now);
                mapping.setUpdatedAt(now);
                
                customMappingTable.putItem(mapping);
                
                logger.info("Created custom mapping for merchant '{}' → '{}'", 
                    normalizedMerchant, categoryPrimary);
                return mapping;
            }
        } catch (IllegalArgumentException e) {
            logger.error("Invalid input for custom mapping: {}", e.getMessage());
            throw e; // Re-throw validation errors
        } catch (DynamoDbException e) {
            logger.error("Failed to create/update custom mapping (DynamoDB error): {}", e.getMessage(), e);
            throw new RuntimeException("Failed to save custom mapping due to database error", e);
        } catch (Exception e) {
            logger.error("Failed to create/update custom mapping: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to save custom mapping", e);
        }
    }
    
    /**
     * Get custom mapping for a merchant (if exists)
     */
    public CustomMerchantMappingTable getCustomMapping(String userId, String merchantName) {
        try {
            String normalized = normalizeMerchantName(merchantName);
            return findCustomMapping(userId, normalized);
        } catch (Exception e) {
            logger.debug("Error getting custom mapping: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Get all custom mappings for a user
     */
    public List<CustomMerchantMappingTable> getUserCustomMappings(String userId) {
        try {
            QueryConditional queryConditional = QueryConditional
                .keyEqualTo(Key.builder()
                    .partitionValue(userId)
                    .build());
            
            return customMappingTable.query(queryConditional)
                .items()
                .stream()
                .filter(m -> m.getIsActive() != null && m.getIsActive())
                .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Failed to get user custom mappings: {}", e.getMessage(), e);
            return List.of();
        }
    }
    
    /**
     * Delete a custom mapping
     */
    public void deleteCustomMapping(String userId, String mappingId) {
        // Edge case: Validate inputs
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("userId cannot be null or empty");
        }
        if (mappingId == null || mappingId.trim().isEmpty()) {
            throw new IllegalArgumentException("mappingId cannot be null or empty");
        }
        
        try {
            Key key = Key.builder()
                .partitionValue(mappingId)
                .build();
            
            CustomMerchantMappingTable mapping = customMappingTable.getItem(key);
            if (mapping == null) {
                logger.warn("Custom mapping not found: {}", mappingId);
                throw new IllegalArgumentException("Custom mapping not found: " + mappingId);
            }
            
            // Edge case: Verify ownership
            if (!mapping.getUserId().equals(userId)) {
                logger.warn("User {} attempted to delete mapping {} owned by {}", userId, mappingId, mapping.getUserId());
                throw new SecurityException("Cannot delete mapping owned by another user");
            }
            
            // Soft delete by setting isActive = false
            mapping.setIsActive(false);
            mapping.setUpdatedAt(Instant.now());
            customMappingTable.updateItem(mapping);
            
            logger.info("Deleted custom mapping: {}", mappingId);
        } catch (IllegalArgumentException | SecurityException e) {
            logger.error("Invalid delete request: {}", e.getMessage());
            throw e; // Re-throw validation/security errors
        } catch (DynamoDbException e) {
            logger.error("Failed to delete custom mapping (DynamoDB error): {}", e.getMessage(), e);
            throw new RuntimeException("Failed to delete custom mapping due to database error", e);
        } catch (Exception e) {
            logger.error("Failed to delete custom mapping: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to delete custom mapping", e);
        }
    }
    
    /**
     * Auto-learn from repeated corrections
     * Creates or updates merchant in static database (for next deployment)
     */
    private void autoLearnFromCorrection(
            String userId,
            String merchantName,
            String categoryPrimary,
            String categoryDetailed,
            String transactionType) {
        
        logger.info("Auto-learning: merchant '{}' → '{}' (threshold reached)", 
            merchantName, categoryPrimary);
        
        // Note: This would update the static merchants.json file
        // For now, we log it. In production, this could trigger a CI/CD job
        // to update the static file, or we could have a separate learning table
        // that gets merged into the static file periodically.
        
        // TODO: Implement auto-learning mechanism (e.g., update merchants.json via CI/CD)
    }
    
    /**
     * Find existing correction for same merchant + category
     */
    private UserCorrectionTable findExistingCorrection(
            String userId,
            String merchantName,
            String categoryPrimary) {
        
        try {
            QueryConditional queryConditional = QueryConditional
                .keyEqualTo(Key.builder()
                    .partitionValue(userId)
                    .sortValue(merchantName)
                    .build());
            
            return correctionTable.query(queryConditional)
                .items()
                .stream()
                .filter(c -> categoryPrimary.equals(c.getCorrectedCategoryPrimary()))
                .findFirst()
                .orElse(null);
        } catch (Exception e) {
            logger.debug("Error finding existing correction: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Find custom mapping for merchant
     */
    private CustomMerchantMappingTable findCustomMapping(String userId, String merchantName) {
        try {
            QueryConditional queryConditional = QueryConditional
                .keyEqualTo(Key.builder()
                    .partitionValue(userId)
                    .sortValue(merchantName)
                    .build());
            
            return customMappingTable.query(queryConditional)
                .items()
                .stream()
                .filter(m -> m.getIsActive() != null && m.getIsActive())
                .findFirst()
                .orElse(null);
        } catch (Exception e) {
            logger.debug("Error finding custom mapping: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Normalize merchant name for lookup
     */
    private String normalizeMerchantName(String name) {
        if (name == null) return "";
        return name.toLowerCase()
            .replaceAll("[^a-z0-9]", "")
            .replaceAll("\\s+", "")
            .trim();
    }
}

