package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.CustomMerchantMappingTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.CategoryLearningService;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for Category Corrections and Custom Merchant Mappings
 * 
 * Features:
 * - Record user corrections for learning
 * - Create/update/delete custom merchant mappings
 * - Get user's custom mappings
 */
@RestController
@RequestMapping("/api/categories")
public class CategoryController {
    
    private static final Logger logger = LoggerFactory.getLogger(CategoryController.class);
    
    private final CategoryLearningService learningService;
    private final TransactionService transactionService;
    private final UserService userService;
    private final TransactionRepository transactionRepository;
    
    public CategoryController(
            CategoryLearningService learningService,
            TransactionService transactionService,
            UserService userService,
            TransactionRepository transactionRepository) {
        this.learningService = learningService;
        this.transactionService = transactionService;
        this.userService = userService;
        this.transactionRepository = transactionRepository;
    }
    
    /**
     * Record a category correction for learning
     * POST /api/categories/corrections
     */
    @PostMapping("/corrections")
    public ResponseEntity<Map<String, Object>> recordCorrection(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody CorrectionRequest request) {
        
        // Edge case: Validate authentication
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        
        // Edge case: Validate request
        if (request == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Request body is required");
        }
        if (request.getTransactionId() == null || request.getTransactionId().trim().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }
        if (request.getCategoryPrimary() == null || request.getCategoryPrimary().trim().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Category is required");
        }
        
        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
        
        // Get original transaction to capture original category
        TransactionTable transaction = transactionRepository.findById(request.getTransactionId())
                .orElseThrow(() -> new AppException(ErrorCode.TRANSACTION_NOT_FOUND, 
                    "Transaction not found: " + request.getTransactionId()));
        
        // Edge case: Verify transaction belongs to user
        if (!transaction.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Transaction does not belong to user");
        }
        
        // Edge case: Prevent recording correction if category hasn't changed
        if (request.getCategoryPrimary().equals(transaction.getCategoryPrimary()) &&
            (request.getCategoryDetailed() == null || 
             request.getCategoryDetailed().equals(transaction.getCategoryDetailed()))) {
            logger.debug("Skipping correction recording - category unchanged for transaction {}", request.getTransactionId());
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Category unchanged, no correction recorded"
            ));
        }
        
        // Record correction
        learningService.recordCorrection(
            user.getUserId(),
            request.getTransactionId(),
            transaction.getMerchantName(),
            transaction.getCategoryPrimary(), // Original category
            transaction.getCategoryDetailed(), // Original detailed category
            request.getCategoryPrimary(), // Corrected category
            request.getCategoryDetailed(), // Corrected detailed category
            transaction.getTransactionType(), // Original transaction type
            request.getTransactionType(), // Corrected transaction type (if provided)
            transaction.getDescription()
        );
        
        // Update transaction with corrected category
        transactionService.updateTransaction(
            user,
            request.getTransactionId(),
            transaction.getPlaidTransactionId(),
            transaction.getAmount(),
            transaction.getNotes(),
            request.getCategoryPrimary(),
            request.getCategoryDetailed(),
            transaction.getReviewStatus(),
            transaction.getIsHidden(),
            request.getTransactionType() != null ? request.getTransactionType() : transaction.getTransactionType(),
            false,
            transaction.getGoalId(),
            transaction.getLinkedTransactionId()
        );
        
        logger.info("Recorded category correction for transaction {}: {} → {}", 
            request.getTransactionId(), transaction.getCategoryPrimary(), request.getCategoryPrimary());
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Correction recorded successfully"
        ));
    }
    
    /**
     * Create or update a custom merchant mapping
     * POST /api/categories/custom-mappings
     */
    @PostMapping("/custom-mappings")
    public ResponseEntity<CustomMerchantMappingTable> createOrUpdateCustomMapping(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody CustomMappingRequest request) {
        
        // Edge case: Validate authentication
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        
        // Edge case: Validate request
        if (request == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Request body is required");
        }
        if (request.getMerchantName() == null || request.getMerchantName().trim().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Merchant name is required");
        }
        if (request.getCategoryPrimary() == null || request.getCategoryPrimary().trim().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Category is required");
        }
        
        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
        
        CustomMerchantMappingTable mapping = learningService.createOrUpdateCustomMapping(
            user.getUserId(),
            request.getMerchantName(),
            request.getAliases(),
            request.getCategoryPrimary(),
            request.getCategoryDetailed(),
            request.getTransactionType()
        );
        
        logger.info("Created/updated custom mapping for user {}: merchant '{}' → '{}'", 
            user.getUserId(), request.getMerchantName(), request.getCategoryPrimary());
        
        return ResponseEntity.ok(mapping);
    }
    
    /**
     * Get all custom mappings for the authenticated user
     * GET /api/categories/custom-mappings
     */
    @GetMapping("/custom-mappings")
    public ResponseEntity<List<CustomMerchantMappingTable>> getCustomMappings(
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        
        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
        
        List<CustomMerchantMappingTable> mappings = learningService.getUserCustomMappings(user.getUserId());
        
        return ResponseEntity.ok(mappings);
    }
    
    /**
     * Delete a custom mapping
     * DELETE /api/categories/custom-mappings/{mappingId}
     */
    @DeleteMapping("/custom-mappings/{mappingId}")
    public ResponseEntity<Map<String, Object>> deleteCustomMapping(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String mappingId) {
        
        // Edge case: Validate authentication
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        
        // Edge case: Validate path parameter
        if (mappingId == null || mappingId.trim().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Mapping ID is required");
        }
        
        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
        
        try {
            learningService.deleteCustomMapping(user.getUserId(), mappingId);
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.INVALID_INPUT, e.getMessage());
        } catch (SecurityException e) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, e.getMessage());
        }
        
        logger.info("Deleted custom mapping {} for user {}", mappingId, user.getUserId());
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Custom mapping deleted successfully"
        ));
    }
    
    // ========== Request DTOs ==========
    
    public static class CorrectionRequest {
        private String transactionId;
        private String categoryPrimary;
        private String categoryDetailed;
        private String transactionType; // Optional
        
        // Getters and setters
        public String getTransactionId() { return transactionId; }
        public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
        
        public String getCategoryPrimary() { return categoryPrimary; }
        public void setCategoryPrimary(String categoryPrimary) { this.categoryPrimary = categoryPrimary; }
        
        public String getCategoryDetailed() { return categoryDetailed; }
        public void setCategoryDetailed(String categoryDetailed) { this.categoryDetailed = categoryDetailed; }
        
        public String getTransactionType() { return transactionType; }
        public void setTransactionType(String transactionType) { this.transactionType = transactionType; }
    }
    
    public static class CustomMappingRequest {
        private String merchantName;
        private List<String> aliases;
        private String categoryPrimary;
        private String categoryDetailed;
        private String transactionType; // Optional
        
        // Getters and setters
        public String getMerchantName() { return merchantName; }
        public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
        
        public List<String> getAliases() { return aliases; }
        public void setAliases(List<String> aliases) { this.aliases = aliases; }
        
        public String getCategoryPrimary() { return categoryPrimary; }
        public void setCategoryPrimary(String categoryPrimary) { this.categoryPrimary = categoryPrimary; }
        
        public String getCategoryDetailed() { return categoryDetailed; }
        public void setCategoryDetailed(String categoryDetailed) { this.categoryDetailed = categoryDetailed; }
        
        public String getTransactionType() { return transactionType; }
        public void setTransactionType(String transactionType) { this.transactionType = transactionType; }
    }
}

