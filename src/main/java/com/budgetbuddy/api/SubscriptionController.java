package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.SubscriptionService;
import com.budgetbuddy.service.SubscriptionInsightsService;
import com.budgetbuddy.service.SubscriptionAdvancedService;
import com.budgetbuddy.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Subscription REST Controller
 * Handles subscription detection, retrieval, and management
 * 
 * Features:
 * - Thread-safe subscription detection (race condition protection)
 * - Comprehensive error handling
 * - Input validation
 * - Rate limiting (via Spring Security)
 */
@RestController
@RequestMapping("/api/subscriptions")
@Tag(name = "Subscriptions", description = "Subscription detection and management endpoints")
public class SubscriptionController {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionController.class);
    
    // Race condition protection: Prevent concurrent subscription detection for same user
    private final java.util.concurrent.ConcurrentHashMap<String, ReentrantLock> userLocks = new java.util.concurrent.ConcurrentHashMap<>();

    private final SubscriptionService subscriptionService;
    private final SubscriptionInsightsService insightsService;
    private final SubscriptionAdvancedService advancedService;
    private final UserService userService;

    public SubscriptionController(
            final SubscriptionService subscriptionService,
            final SubscriptionInsightsService insightsService,
            final SubscriptionAdvancedService advancedService,
            final UserService userService) {
        this.subscriptionService = subscriptionService;
        this.insightsService = insightsService;
        this.advancedService = advancedService;
        this.userService = userService;
    }

    /**
     * Detect subscriptions from user's transactions
     * POST /api/subscriptions/detect
     * 
     * Thread-safe: Uses per-user locks to prevent concurrent detection
     */
    @PostMapping("/detect")
    @Operation(
        summary = "Detect subscriptions",
        description = "Analyzes user's transactions to detect recurring subscriptions. Thread-safe operation."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Subscriptions detected successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - user not authenticated"),
        @ApiResponse(responseCode = "404", description = "User not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<List<Subscription>> detectSubscriptions(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // RACE CONDITION PROTECTION: Acquire per-user lock
        ReentrantLock userLock = userLocks.computeIfAbsent(user.getUserId(), k -> new ReentrantLock());
        userLock.lock();
        try {
            logger.info("Detecting subscriptions for user: {}", user.getUserId());
            
            // Validate user has transactions before detection
            List<Subscription> subscriptions = subscriptionService.detectSubscriptions(user.getUserId());
            
            // Save detected subscriptions
            if (!subscriptions.isEmpty()) {
                subscriptionService.saveSubscriptions(user.getUserId(), subscriptions);
                logger.info("Detected and saved {} subscriptions for user: {}", subscriptions.size(), user.getUserId());
            } else {
                logger.info("No subscriptions detected for user: {}", user.getUserId());
            }

            return ResponseEntity.ok(subscriptions);
        } catch (Exception e) {
            logger.error("Error detecting subscriptions for user {}: {}", user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.DATABASE_QUERY_FAILED, 
                "Failed to detect subscriptions: " + e.getMessage(), null, null, e);
        } finally {
            userLock.unlock();
            // Clean up lock if no longer needed (optional optimization)
            if (!userLock.hasQueuedThreads()) {
                userLocks.remove(user.getUserId());
            }
        }
    }

    /**
     * Get all subscriptions for the authenticated user
     * GET /api/subscriptions
     */
    @GetMapping
    @Operation(
        summary = "Get all subscriptions",
        description = "Retrieves all subscriptions (active and inactive) for the authenticated user"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Subscriptions retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - user not authenticated"),
        @ApiResponse(responseCode = "404", description = "User not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<List<Subscription>> getSubscriptions(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            List<Subscription> subscriptions = subscriptionService.getSubscriptions(user.getUserId());
            return ResponseEntity.ok(subscriptions);
        } catch (Exception e) {
            logger.error("Error retrieving subscriptions for user {}: {}", user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.DATABASE_QUERY_FAILED, 
                "Failed to retrieve subscriptions: " + e.getMessage(), null, null, e);
        }
    }

    /**
     * Get active subscriptions for the authenticated user
     * GET /api/subscriptions/active
     */
    @GetMapping("/active")
    @Operation(
        summary = "Get active subscriptions",
        description = "Retrieves only active subscriptions for the authenticated user"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Active subscriptions retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - user not authenticated"),
        @ApiResponse(responseCode = "404", description = "User not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<List<Subscription>> getActiveSubscriptions(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            List<Subscription> subscriptions = subscriptionService.getActiveSubscriptions(user.getUserId());
            return ResponseEntity.ok(subscriptions);
        } catch (Exception e) {
            logger.error("Error retrieving active subscriptions for user {}: {}", user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.DATABASE_QUERY_FAILED, 
                "Failed to retrieve active subscriptions: " + e.getMessage(), null, null, e);
        }
    }

    /**
     * Delete a subscription
     * DELETE /api/subscriptions/{subscriptionId}
     * 
     * Validates subscription belongs to user before deletion
     */
    @DeleteMapping("/{subscriptionId}")
    @Operation(
        summary = "Delete subscription",
        description = "Deletes a subscription by ID. Validates ownership before deletion."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Subscription deleted successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - user not authenticated or subscription not owned"),
        @ApiResponse(responseCode = "404", description = "Subscription not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<Void> deleteSubscription(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Subscription ID to delete", required = true)
            @PathVariable @NotBlank String subscriptionId) {
        
        // Input validation
        if (subscriptionId == null || subscriptionId.trim().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Subscription ID is required");
        }
        
        // Validate UUID format
        try {
            UUID.fromString(subscriptionId);
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.INVALID_FORMAT, "Invalid subscription ID format");
        }
        
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Verify subscription belongs to user
        List<Subscription> userSubscriptions = subscriptionService.getSubscriptions(user.getUserId());
        boolean belongsToUser = userSubscriptions.stream()
                .anyMatch(sub -> sub.getSubscriptionId().equals(subscriptionId));

        if (!belongsToUser) {
            logger.warn("User {} attempted to delete subscription {} that doesn't belong to them", 
                user.getUserId(), subscriptionId);
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Subscription not found or access denied");
        }

        try {
            subscriptionService.deleteSubscription(subscriptionId);
            logger.info("Subscription {} deleted by user {}", subscriptionId, user.getUserId());
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            logger.error("Error deleting subscription {} for user {}: {}", 
                subscriptionId, user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.DATABASE_QUERY_FAILED, 
                "Failed to delete subscription: " + e.getMessage(), null, null, e);
        }
    }

    /**
     * Get unused subscription insights
     * GET /api/subscriptions/insights/unused
     */
    @GetMapping("/insights/unused")
    @Operation(
        summary = "Get unused subscription insights",
        description = "Identifies subscriptions that appear to be unused based on transaction patterns"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Insights retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - user not authenticated"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<List<Map<String, Object>>> getUnusedSubscriptions(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            List<SubscriptionInsightsService.UnusedSubscriptionInsight> insights = 
                insightsService.detectUnusedSubscriptions(user.getUserId());
            
            List<Map<String, Object>> response = insights.stream()
                .map(this::toUnusedInsightMap)
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error retrieving unused subscription insights for user {}: {}", 
                user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.DATABASE_QUERY_FAILED, 
                "Failed to retrieve insights: " + e.getMessage(), null, null, e);
        }
    }

    /**
     * Get price change alerts
     * GET /api/subscriptions/insights/price-changes
     */
    @GetMapping("/insights/price-changes")
    @Operation(
        summary = "Get price change alerts",
        description = "Identifies subscriptions with price changes over time"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Price change alerts retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - user not authenticated"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<List<Map<String, Object>>> getPriceChanges(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            List<SubscriptionInsightsService.PriceChangeAlert> alerts = 
                insightsService.detectPriceChanges(user.getUserId());
            
            List<Map<String, Object>> response = alerts.stream()
                .map(this::toPriceChangeMap)
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error retrieving price change alerts for user {}: {}", 
                user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.DATABASE_QUERY_FAILED, 
                "Failed to retrieve price change alerts: " + e.getMessage(), null, null, e);
        }
    }

    /**
     * Get cancellation recommendations
     * GET /api/subscriptions/insights/cancellation-recommendations
     */
    @GetMapping("/insights/cancellation-recommendations")
    @Operation(
        summary = "Get cancellation recommendations",
        description = "Provides recommendations for subscriptions that could be cancelled to save money"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Recommendations retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - user not authenticated"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<List<Map<String, Object>>> getCancellationRecommendations(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            List<SubscriptionInsightsService.CancellationRecommendation> recommendations = 
                insightsService.getCancellationRecommendations(user.getUserId());
            
            List<Map<String, Object>> response = recommendations.stream()
                .map(this::toCancellationRecommendationMap)
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error retrieving cancellation recommendations for user {}: {}", 
                user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.DATABASE_QUERY_FAILED, 
                "Failed to retrieve recommendations: " + e.getMessage(), null, null, e);
        }
    }

    /**
     * Get subscription health score
     * GET /api/subscriptions/{subscriptionId}/health
     */
    @GetMapping("/{subscriptionId}/health")
    @Operation(
        summary = "Get subscription health score",
        description = "Calculates health score for a specific subscription based on payment patterns"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Health score retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - user not authenticated or subscription not owned"),
        @ApiResponse(responseCode = "404", description = "Subscription not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<Map<String, Object>> getSubscriptionHealth(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Subscription ID", required = true)
            @PathVariable @NotBlank String subscriptionId) {
        
        // Input validation
        if (subscriptionId == null || subscriptionId.trim().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Subscription ID is required");
        }
        
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Verify subscription belongs to user
        List<Subscription> userSubscriptions = subscriptionService.getSubscriptions(user.getUserId());
        Subscription subscription = userSubscriptions.stream()
                .filter(sub -> sub.getSubscriptionId().equals(subscriptionId))
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Subscription not found"));

        try {
            SubscriptionAdvancedService.SubscriptionHealthScore healthScore = 
                advancedService.calculateHealthScore(user.getUserId(), subscription);
            
            Map<String, Object> response = Map.of(
                "subscriptionId", subscriptionId,
                "score", healthScore.getScore(),
                "healthLevel", healthScore.getHealthLevel(),
                "issues", healthScore.getIssues()
            );
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error calculating health score for subscription {}: {}", 
                subscriptionId, e.getMessage(), e);
            throw new AppException(ErrorCode.DATABASE_QUERY_FAILED, 
                "Failed to calculate health score: " + e.getMessage(), null, null, e);
        }
    }

    /**
     * Get trial expiration alerts
     * GET /api/subscriptions/insights/trial-expirations
     */
    @GetMapping("/insights/trial-expirations")
    @Operation(
        summary = "Get trial expiration alerts",
        description = "Identifies subscriptions with upcoming trial expirations"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Trial expiration alerts retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - user not authenticated"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<List<Map<String, Object>>> getTrialExpirations(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            List<SubscriptionAdvancedService.TrialExpirationAlert> alerts = 
                advancedService.detectTrialExpirations(user.getUserId());
            
            List<Map<String, Object>> response = alerts.stream()
                .map(alert -> Map.of(
                    "subscription", toSubscriptionMap(alert.getSubscription()),
                    "expirationDate", alert.getExpirationDate().toString(),
                    "daysUntilExpiration", alert.getDaysUntilExpiration(),
                    "conversionAmount", alert.getConversionAmount()
                ))
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error retrieving trial expiration alerts for user {}: {}", 
                user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.DATABASE_QUERY_FAILED, 
                "Failed to retrieve trial expiration alerts: " + e.getMessage(), null, null, e);
        }
    }

    /**
     * Get bundling recommendations
     * GET /api/subscriptions/insights/bundling
     */
    @GetMapping("/insights/bundling")
    @Operation(
        summary = "Get bundling recommendations",
        description = "Suggests subscription bundles that could save money"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Bundling recommendations retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - user not authenticated"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<List<Map<String, Object>>> getBundlingRecommendations(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            List<SubscriptionAdvancedService.BundlingRecommendation> recommendations = 
                advancedService.suggestBundling(user.getUserId());
            
            List<Map<String, Object>> response = recommendations.stream()
                .map(rec -> Map.of(
                    "bundleType", rec.getBundleType(),
                    "subscriptions", rec.getSubscriptions().stream()
                        .map(this::toSubscriptionMap)
                        .collect(Collectors.toList()),
                    "potentialSavings", rec.getPotentialSavings(),
                    "estimatedBundlePrice", rec.getEstimatedBundlePrice(),
                    "description", rec.getDescription()
                ))
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error retrieving bundling recommendations for user {}: {}", 
                user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.DATABASE_QUERY_FAILED, 
                "Failed to retrieve bundling recommendations: " + e.getMessage(), null, null, e);
        }
    }

    /**
     * Get alternative recommendations
     * GET /api/subscriptions/insights/alternatives
     */
    @GetMapping("/insights/alternatives")
    @Operation(
        summary = "Get alternative recommendations",
        description = "Suggests cheaper alternatives to current subscriptions"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Alternative recommendations retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - user not authenticated"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<List<Map<String, Object>>> getAlternativeRecommendations(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            List<SubscriptionAdvancedService.AlternativeRecommendation> recommendations = 
                advancedService.suggestAlternatives(user.getUserId());
            
            List<Map<String, Object>> response = recommendations.stream()
                .map(rec -> Map.of(
                    "currentSubscription", toSubscriptionMap(rec.getCurrentSubscription()),
                    "alternativeName", rec.getAlternativeName(),
                    "alternativePrice", rec.getAlternativePrice(),
                    "potentialSavings", rec.getPotentialSavings(),
                    "reason", rec.getReason()
                ))
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error retrieving alternative recommendations for user {}: {}", 
                user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.DATABASE_QUERY_FAILED, 
                "Failed to retrieve alternative recommendations: " + e.getMessage(), null, null, e);
        }
    }

    /**
     * Get predictive cancellations
     * GET /api/subscriptions/insights/predictive-cancellations
     */
    @GetMapping("/insights/predictive-cancellations")
    @Operation(
        summary = "Get predictive cancellations",
        description = "Predicts which subscriptions are likely to be cancelled based on usage patterns"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Predictive cancellations retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - user not authenticated"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<List<Map<String, Object>>> getPredictiveCancellations(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            List<SubscriptionAdvancedService.PredictiveCancellation> predictions = 
                advancedService.predictCancellations(user.getUserId());
            
            List<Map<String, Object>> response = predictions.stream()
                .map(pred -> Map.of(
                    "subscription", toSubscriptionMap(pred.getSubscription()),
                    "cancellationProbability", pred.getCancellationProbability(),
                    "reason", pred.getReason(),
                    "healthScore", pred.getHealthScore()
                ))
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error retrieving predictive cancellations for user {}: {}", 
                user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.DATABASE_QUERY_FAILED, 
                "Failed to retrieve predictive cancellations: " + e.getMessage(), null, null, e);
        }
    }

    /**
     * Get comprehensive subscription optimization
     * GET /api/subscriptions/insights/optimization
     */
    @GetMapping("/insights/optimization")
    @Operation(
        summary = "Get subscription optimization",
        description = "Provides comprehensive subscription optimization recommendations including cancellations, bundling, and alternatives"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Optimization recommendations retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - user not authenticated"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<Map<String, Object>> getOptimization(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            SubscriptionAdvancedService.SubscriptionOptimization optimization = 
                advancedService.optimizePortfolio(user.getUserId());
            
            Map<String, Object> response = Map.of(
                "totalSubscriptions", optimization.getTotalSubscriptions(),
                "currentMonthlySpend", optimization.getCurrentMonthlySpend(),
                "totalPotentialSavings", optimization.getTotalPotentialSavings(),
                "cancellationSavings", optimization.getCancellationSavings(),
                "bundlingSavings", optimization.getBundlingSavings(),
                "alternativeSavings", optimization.getAlternativeSavings(),
                "cancellationRecommendations", optimization.getCancellationRecommendations().stream()
                    .map(this::toCancellationRecommendationMap)
                    .collect(Collectors.toList()),
                "bundlingRecommendations", optimization.getBundlingRecommendations().stream()
                    .map(rec -> Map.of(
                        "bundleType", rec.getBundleType(),
                        "potentialSavings", rec.getPotentialSavings(),
                        "description", rec.getDescription()
                    ))
                    .collect(Collectors.toList()),
                "alternativeRecommendations", optimization.getAlternativeRecommendations().stream()
                    .map(rec -> Map.of(
                        "currentSubscription", rec.getCurrentSubscription().getMerchantName(),
                        "alternativeName", rec.getAlternativeName(),
                        "potentialSavings", rec.getPotentialSavings()
                    ))
                    .collect(Collectors.toList()),
                "trialAlerts", optimization.getTrialAlerts().stream()
                    .map(alert -> Map.of(
                        "subscription", alert.getSubscription().getMerchantName(),
                        "daysUntilExpiration", alert.getDaysUntilExpiration()
                    ))
                    .collect(Collectors.toList())
            );
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error retrieving optimization recommendations for user {}: {}", 
                user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.DATABASE_QUERY_FAILED, 
                "Failed to retrieve optimization recommendations: " + e.getMessage(), null, null, e);
        }
    }

    // Helper methods to convert insights to maps
    private Map<String, Object> toUnusedInsightMap(SubscriptionInsightsService.UnusedSubscriptionInsight insight) {
        return Map.of(
            "subscription", toSubscriptionMap(insight.getSubscription()),
            "reason", insight.getReason(),
            "potentialSavings", insight.getPotentialSavings(),
            "severity", insight.getSeverity().name()
        );
    }

    private Map<String, Object> toPriceChangeMap(SubscriptionInsightsService.PriceChangeAlert alert) {
        return Map.of(
            "subscription", toSubscriptionMap(alert.getSubscription()),
            "newAmount", alert.getNewAmount(),
            "oldAmount", alert.getOldAmount(),
            "percentChange", alert.getPercentChange(),
            "detectedDate", alert.getDetectedDate()
        );
    }

    private Map<String, Object> toCancellationRecommendationMap(
            SubscriptionInsightsService.CancellationRecommendation recommendation) {
        return Map.of(
            "subscription", toSubscriptionMap(recommendation.getSubscription()),
            "reason", recommendation.getReason(),
            "potentialSavings", recommendation.getPotentialSavings(),
            "priority", recommendation.getPriority().name()
        );
    }

    private Map<String, Object> toSubscriptionMap(Subscription subscription) {
        return Map.of(
            "subscriptionId", subscription.getSubscriptionId(),
            "merchantName", subscription.getMerchantName(),
            "amount", subscription.getAmount(),
            "frequency", subscription.getFrequency().name(),
            "subscriptionType", subscription.getSubscriptionType() != null ? subscription.getSubscriptionType() : "other"
        );
    }
}
