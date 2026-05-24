package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.SubscriptionAdvancedService;
import com.budgetbuddy.service.SubscriptionInsightsService;
import com.budgetbuddy.service.SubscriptionService;
import com.budgetbuddy.service.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Subscription REST Controller Handles subscription detection, retrieval, and management
 *
 * <p>Features: - Thread-safe subscription detection (race condition protection) - Comprehensive
 * error handling - Input validation - Rate limiting (via Spring Security)
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@RestController
@RequestMapping("/api/subscriptions")
@Tag(name = "Subscriptions", description = "Subscription detection and management endpoints")
public class SubscriptionController {

    private static final String USER_NOT_AUTHENTICATED = "User not authenticated";

    private static final String USER_NOT_FOUND_1 = "User not found";

    private static final String CANCELLATION_RECOMMENDATIONS = "cancellationRecommendations";

    private static final String POTENTIAL_SAVINGS = "potentialSavings";

    private static final String REASON = "reason";

    private static final String SUBSCRIPTION = "subscription";
    private static final String INTERNAL_SERVER_ERROR = "Internal server error";
    private static final String UNAUTHORIZED_USER_NOT_AUTHENTICATED =
            "Unauthorized - user not authenticated";

    private static final Logger LOGGER = LoggerFactory.getLogger(SubscriptionController.class);

    // Race condition protection: Prevent concurrent subscription detection for same user
    private final java.util.concurrent.ConcurrentHashMap<String, ReentrantLock> userLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final SubscriptionService subscriptionService;
    private final SubscriptionInsightsService insightsService;
    private final SubscriptionAdvancedService advancedService;
    private final UserService userService;
    /**
     * Forward-looking renewal calendar. Setter-injected so existing
     * test constructors don't grow another required arg, and so the
     * field is null in tests that don't exercise the renewal endpoints.
     */
    private com.budgetbuddy.service.SubscriptionRenewalForecastService renewalForecastService;

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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRenewalForecastService(
            final com.budgetbuddy.service.SubscriptionRenewalForecastService svc) {
        this.renewalForecastService = svc;
    }

    /** Per-merchant spend trend (sparkline data). */
    private com.budgetbuddy.service.MerchantSpendTrendService merchantSpendTrendService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMerchantSpendTrendService(
            final com.budgetbuddy.service.MerchantSpendTrendService svc) {
        this.merchantSpendTrendService = svc;
    }

    /**
     * Detect subscriptions from user's transactions POST /api/subscriptions/detect
     *
     * <p>Thread-safe: Uses per-user locks to prevent concurrent detection
     */
    @PostMapping("/detect")
    @Operation(
            summary = "Detect subscriptions",
            description =
                    "Analyzes user's transactions to detect recurring subscriptions. Thread-safe operation.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Subscriptions detected successfully"),
        @ApiResponse(responseCode = "401", description = UNAUTHORIZED_USER_NOT_AUTHENTICATED),
        @ApiResponse(responseCode = "404", description = USER_NOT_FOUND_1),
        @ApiResponse(responseCode = "500", description = INTERNAL_SERVER_ERROR)
    })
    public ResponseEntity<List<Subscription>> detectSubscriptions(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        // RACE CONDITION PROTECTION: Acquire per-user lock
        final ReentrantLock userLock =
                userLocks.computeIfAbsent(user.getUserId(), k -> new ReentrantLock());
        userLock.lock();
        try {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Detecting subscriptions for user: {}", user.getUserId());
            }

            // Validate user has transactions before detection
            final List<Subscription> subscriptions =
                    subscriptionService.detectSubscriptions(user.getUserId());

            // Save detected subscriptions
            if (!subscriptions.isEmpty()) {
                subscriptionService.saveSubscriptions(user.getUserId(), subscriptions);
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(
                            "Detected and saved {} subscriptions for user: {}",
                            subscriptions.size(),
                            user.getUserId());
                }
            } else {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("No subscriptions detected for user: {}", user.getUserId());
                }
            }

            return ResponseEntity.ok(subscriptions);
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error detecting subscriptions for user {}: {}",
                        user.getUserId(),
                        e.getMessage(),
                        e);
            }
            throw new AppException(
                    ErrorCode.DATABASE_QUERY_FAILED,
                    "Failed to detect subscriptions: " + e.getMessage(),
                    null,
                    null,
                    e);
        } finally {
            userLock.unlock();
            // Clean up lock if no longer needed (optional optimization)
            if (!userLock.hasQueuedThreads()) {
                userLocks.remove(user.getUserId());
            }
        }
    }

    /** Get all subscriptions for the authenticated user GET /api/subscriptions */
    @GetMapping
    @Operation(
            summary = "Get all subscriptions",
            description =
                    "Retrieves all subscriptions (active and inactive) for the authenticated user")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Subscriptions retrieved successfully"),
        @ApiResponse(responseCode = "401", description = UNAUTHORIZED_USER_NOT_AUTHENTICATED),
        @ApiResponse(responseCode = "404", description = USER_NOT_FOUND_1),
        @ApiResponse(responseCode = "500", description = INTERNAL_SERVER_ERROR)
    })
    public ResponseEntity<List<Subscription>> getSubscriptions(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        try {
            final List<Subscription> subscriptions =
                    subscriptionService.getSubscriptions(user.getUserId());
            return ResponseEntity.ok(subscriptions);
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error retrieving subscriptions for user {}: {}",
                        user.getUserId(),
                        e.getMessage(),
                        e);
            }
            throw new AppException(
                    ErrorCode.DATABASE_QUERY_FAILED,
                    "Failed to retrieve subscriptions: " + e.getMessage(),
                    null,
                    null,
                    e);
        }
    }

    /** Get active subscriptions for the authenticated user GET /api/subscriptions/active */
    @GetMapping("/active")
    @Operation(
            summary = "Get active subscriptions",
            description = "Retrieves only active subscriptions for the authenticated user")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Active subscriptions retrieved successfully"),
        @ApiResponse(responseCode = "401", description = UNAUTHORIZED_USER_NOT_AUTHENTICATED),
        @ApiResponse(responseCode = "404", description = USER_NOT_FOUND_1),
        @ApiResponse(responseCode = "500", description = INTERNAL_SERVER_ERROR)
    })
    public ResponseEntity<List<Subscription>> getActiveSubscriptions(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        try {
            final List<Subscription> subscriptions =
                    subscriptionService.getActiveSubscriptions(user.getUserId());
            return ResponseEntity.ok(subscriptions);
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error retrieving active subscriptions for user {}: {}",
                        user.getUserId(),
                        e.getMessage(),
                        e);
            }
            throw new AppException(
                    ErrorCode.DATABASE_QUERY_FAILED,
                    "Failed to retrieve active subscriptions: " + e.getMessage(),
                    null,
                    null,
                    e);
        }
    }

    /**
     * Delete a subscription DELETE /api/subscriptions/{subscriptionId}
     *
     * <p>Validates subscription belongs to user before deletion
     */
    @DeleteMapping("/{subscriptionId}")
    @Operation(
            summary = "Delete subscription",
            description = "Deletes a subscription by ID. Validates ownership before deletion.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Subscription deleted successfully"),
        @ApiResponse(
                responseCode = "401",
                description = "Unauthorized - user not authenticated or subscription not owned"),
        @ApiResponse(responseCode = "404", description = "Subscription not found"),
        @ApiResponse(responseCode = "500", description = INTERNAL_SERVER_ERROR)
    })
    public ResponseEntity<Void> deleteSubscription(
            @AuthenticationPrincipal final UserDetails userDetails,
            @Parameter(description = "Subscription ID to delete", required = true)
                    @PathVariable
                    @NotBlank
                    final String subscriptionId) {

        // Input validation
        if (subscriptionId == null || subscriptionId.isBlank()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Subscription ID is required");
        }

        // Validate UUID format
        try {
            UUID.fromString(subscriptionId);
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.INVALID_FORMAT, "Invalid subscription ID format");
        }

        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        // Verify subscription belongs to user
        final List<Subscription> userSubscriptions =
                subscriptionService.getSubscriptions(user.getUserId());
        final boolean belongsToUser =
                userSubscriptions.stream()
                        .anyMatch(sub -> sub.getSubscriptionId().equals(subscriptionId));

        if (!belongsToUser) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "User {} attempted to delete subscription {} that doesn't belong to them",
                        user.getUserId(),
                        subscriptionId);
            }
            throw new AppException(
                    ErrorCode.UNAUTHORIZED_ACCESS, "Subscription not found or access denied");
        }

        try {
            subscriptionService.deleteSubscription(subscriptionId);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Subscription {} deleted by user {}", subscriptionId, user.getUserId());
            }
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error deleting subscription {} for user {}: {}",
                        subscriptionId,
                        user.getUserId(),
                        e.getMessage(),
                        e);
            }
            throw new AppException(
                    ErrorCode.DATABASE_QUERY_FAILED,
                    "Failed to delete subscription: " + e.getMessage(),
                    null,
                    null,
                    e);
        }
    }

    /** Get unused subscription insights GET /api/subscriptions/insights/unused */
    @GetMapping("/insights/unused")
    @Operation(
            summary = "Get unused subscription insights",
            description =
                    "Identifies subscriptions that appear to be unused based on transaction patterns")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Insights retrieved successfully"),
        @ApiResponse(responseCode = "401", description = UNAUTHORIZED_USER_NOT_AUTHENTICATED),
        @ApiResponse(responseCode = "500", description = INTERNAL_SERVER_ERROR)
    })
    public ResponseEntity<List<Map<String, Object>>> getUnusedSubscriptions(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        try {
            final List<SubscriptionInsightsService.UnusedSubscriptionInsight> insights =
                    insightsService.detectUnusedSubscriptions(user.getUserId());

            final List<Map<String, Object>> response =
                    insights.stream().map(this::toUnusedInsightMap).collect(Collectors.toList());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error retrieving unused subscription insights for user {}: {}",
                        user.getUserId(),
                        e.getMessage(),
                        e);
            }
            throw new AppException(
                    ErrorCode.DATABASE_QUERY_FAILED,
                    "Failed to retrieve insights: " + e.getMessage(),
                    null,
                    null,
                    e);
        }
    }

    /** Get price change alerts GET /api/subscriptions/insights/price-changes */
    @GetMapping("/insights/price-changes")
    @Operation(
            summary = "Get price change alerts",
            description = "Identifies subscriptions with price changes over time")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Price change alerts retrieved successfully"),
        @ApiResponse(responseCode = "401", description = UNAUTHORIZED_USER_NOT_AUTHENTICATED),
        @ApiResponse(responseCode = "500", description = INTERNAL_SERVER_ERROR)
    })
    public ResponseEntity<List<Map<String, Object>>> getPriceChanges(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        try {
            final List<SubscriptionInsightsService.PriceChangeAlert> alerts =
                    insightsService.detectPriceChanges(user.getUserId());

            final List<Map<String, Object>> response =
                    alerts.stream().map(this::toPriceChangeMap).collect(Collectors.toList());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error retrieving price change alerts for user {}: {}",
                        user.getUserId(),
                        e.getMessage(),
                        e);
            }
            throw new AppException(
                    ErrorCode.DATABASE_QUERY_FAILED,
                    "Failed to retrieve price change alerts: " + e.getMessage(),
                    null,
                    null,
                    e);
        }
    }

    /**
     * Flow 7 / O5 — consolidated insights endpoint. iOS was calling three separate URLs; this
     * returns everything the "Review subscriptions" card needs in one round trip. Never throws —
     * failures in a sub-service are reported as empty arrays rather than failing the whole
     * response.
     */
    @GetMapping("/insights")
    public ResponseEntity<Map<String, Object>> getAllSubscriptionInsights(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        final Map<String, Object> out = new HashMap<>();
        try {
            out.put(
                    "unused",
                    insightsService.detectUnusedSubscriptions(user.getUserId()).stream()
                            .map(this::toUnusedInsightMap)
                            .collect(Collectors.toList()));
        } catch (Exception e) {
            out.put("unused", List.of());
        }
        try {
            out.put(
                    "priceChanges",
                    insightsService.detectPriceChanges(user.getUserId()).stream()
                            .map(this::toPriceChangeMap)
                            .collect(Collectors.toList()));
        } catch (Exception e) {
            out.put("priceChanges", List.of());
        }
        try {
            out.put(
                    CANCELLATION_RECOMMENDATIONS,
                    insightsService.getCancellationRecommendations(user.getUserId()).stream()
                            .map(this::toCancellationRecommendationMap)
                            .collect(Collectors.toList()));
        } catch (Exception e) {
            out.put(CANCELLATION_RECOMMENDATIONS, List.of());
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Get cancellation recommendations GET /api/subscriptions/insights/cancellation-recommendations
     */
    @GetMapping("/insights/cancellation-recommendations")
    @Operation(
            summary = "Get cancellation recommendations",
            description =
                    "Provides recommendations for subscriptions that could be cancelled to save money")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Recommendations retrieved successfully"),
        @ApiResponse(responseCode = "401", description = UNAUTHORIZED_USER_NOT_AUTHENTICATED),
        @ApiResponse(responseCode = "500", description = INTERNAL_SERVER_ERROR)
    })
    public ResponseEntity<List<Map<String, Object>>> getCancellationRecommendations(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        try {
            final List<SubscriptionInsightsService.CancellationRecommendation> recommendations =
                    insightsService.getCancellationRecommendations(user.getUserId());

            final List<Map<String, Object>> response =
                    recommendations.stream()
                            .map(this::toCancellationRecommendationMap)
                            .collect(Collectors.toList());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error retrieving cancellation recommendations for user {}: {}",
                        user.getUserId(),
                        e.getMessage(),
                        e);
            }
            throw new AppException(
                    ErrorCode.DATABASE_QUERY_FAILED,
                    "Failed to retrieve recommendations: " + e.getMessage(),
                    null,
                    null,
                    e);
        }
    }

    /** Get subscription health score GET /api/subscriptions/{subscriptionId}/health */
    @GetMapping("/{subscriptionId}/health")
    @Operation(
            summary = "Get subscription health score",
            description =
                    "Calculates health score for a specific subscription based on payment patterns")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Health score retrieved successfully"),
        @ApiResponse(
                responseCode = "401",
                description = "Unauthorized - user not authenticated or subscription not owned"),
        @ApiResponse(responseCode = "404", description = "Subscription not found"),
        @ApiResponse(responseCode = "500", description = INTERNAL_SERVER_ERROR)
    })
    public ResponseEntity<Map<String, Object>> getSubscriptionHealth(
            @AuthenticationPrincipal final UserDetails userDetails,
            @Parameter(description = "Subscription ID", required = true) @PathVariable @NotBlank
                    final String subscriptionId) {

        // Input validation
        if (subscriptionId == null || subscriptionId.isBlank()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Subscription ID is required");
        }

        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        // Verify subscription belongs to user
        final List<Subscription> userSubscriptions =
                subscriptionService.getSubscriptions(user.getUserId());
        final Subscription subscription =
                userSubscriptions.stream()
                        .filter(sub -> sub.getSubscriptionId().equals(subscriptionId))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorCode.UNAUTHORIZED_ACCESS,
                                                "Subscription not found"));

        try {
            final SubscriptionAdvancedService.SubscriptionHealthScore healthScore =
                    advancedService.calculateHealthScore(user.getUserId(), subscription);

            final Map<String, Object> response =
                    Map.of(
                            "subscriptionId", subscriptionId,
                            "score", healthScore.getScore(),
                            "healthLevel", healthScore.getHealthLevel(),
                            "issues", healthScore.getIssues());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error calculating health score for subscription {}: {}",
                        subscriptionId,
                        e.getMessage(),
                        e);
            }
            throw new AppException(
                    ErrorCode.DATABASE_QUERY_FAILED,
                    "Failed to calculate health score: " + e.getMessage(),
                    null,
                    null,
                    e);
        }
    }

    /** Get trial expiration alerts GET /api/subscriptions/insights/trial-expirations */
    @GetMapping("/insights/trial-expirations")
    @Operation(
            summary = "Get trial expiration alerts",
            description = "Identifies subscriptions with upcoming trial expirations")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Trial expiration alerts retrieved successfully"),
        @ApiResponse(responseCode = "401", description = UNAUTHORIZED_USER_NOT_AUTHENTICATED),
        @ApiResponse(responseCode = "500", description = INTERNAL_SERVER_ERROR)
    })
    public ResponseEntity<List<Map<String, Object>>> getTrialExpirations(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        try {
            final List<SubscriptionAdvancedService.TrialExpirationAlert> alerts =
                    advancedService.detectTrialExpirations(user.getUserId());

            final List<Map<String, Object>> response =
                    alerts.stream()
                            .map(
                                    alert ->
                                            Map.of(
                                                    SUBSCRIPTION,
                                                    toSubscriptionMap(alert.getSubscription()),
                                                    "expirationDate",
                                                    alert.getExpirationDate().toString(),
                                                    "daysUntilExpiration",
                                                    alert.getDaysUntilExpiration(),
                                                    "conversionAmount",
                                                    alert.getConversionAmount()))
                            .collect(Collectors.toList());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error retrieving trial expiration alerts for user {}: {}",
                        user.getUserId(),
                        e.getMessage(),
                        e);
            }
            throw new AppException(
                    ErrorCode.DATABASE_QUERY_FAILED,
                    "Failed to retrieve trial expiration alerts: " + e.getMessage(),
                    null,
                    null,
                    e);
        }
    }

    /** Get bundling recommendations GET /api/subscriptions/insights/bundling */
    @GetMapping("/insights/bundling")
    @Operation(
            summary = "Get bundling recommendations",
            description = "Suggests subscription bundles that could save money")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Bundling recommendations retrieved successfully"),
        @ApiResponse(responseCode = "401", description = UNAUTHORIZED_USER_NOT_AUTHENTICATED),
        @ApiResponse(responseCode = "500", description = INTERNAL_SERVER_ERROR)
    })
    public ResponseEntity<List<Map<String, Object>>> getBundlingRecommendations(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        try {
            final List<SubscriptionAdvancedService.BundlingRecommendation> recommendations =
                    advancedService.suggestBundling(user.getUserId());

            final List<Map<String, Object>> response =
                    recommendations.stream()
                            .map(
                                    rec ->
                                            Map.of(
                                                    "bundleType",
                                                    rec.getBundleType(),
                                                    "subscriptions",
                                                    rec.getSubscriptions().stream()
                                                            .map(this::toSubscriptionMap)
                                                            .collect(Collectors.toList()),
                                                    POTENTIAL_SAVINGS,
                                                    rec.getPotentialSavings(),
                                                    "estimatedBundlePrice",
                                                    rec.getEstimatedBundlePrice(),
                                                    "description",
                                                    rec.getDescription()))
                            .collect(Collectors.toList());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error retrieving bundling recommendations for user {}: {}",
                        user.getUserId(),
                        e.getMessage(),
                        e);
            }
            throw new AppException(
                    ErrorCode.DATABASE_QUERY_FAILED,
                    "Failed to retrieve bundling recommendations: " + e.getMessage(),
                    null,
                    null,
                    e);
        }
    }

    /** Get alternative recommendations GET /api/subscriptions/insights/alternatives */
    @GetMapping("/insights/alternatives")
    @Operation(
            summary = "Get alternative recommendations",
            description = "Suggests cheaper alternatives to current subscriptions")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Alternative recommendations retrieved successfully"),
        @ApiResponse(responseCode = "401", description = UNAUTHORIZED_USER_NOT_AUTHENTICATED),
        @ApiResponse(responseCode = "500", description = INTERNAL_SERVER_ERROR)
    })
    public ResponseEntity<List<Map<String, Object>>> getAlternativeRecommendations(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        try {
            final List<SubscriptionAdvancedService.AlternativeRecommendation> recommendations =
                    advancedService.suggestAlternatives(user.getUserId());

            final List<Map<String, Object>> response =
                    recommendations.stream()
                            .map(
                                    rec ->
                                            Map.of(
                                                    "currentSubscription",
                                                    toSubscriptionMap(rec.getCurrentSubscription()),
                                                    "alternativeName",
                                                    rec.getAlternativeName(),
                                                    "alternativePrice",
                                                    rec.getAlternativePrice(),
                                                    POTENTIAL_SAVINGS,
                                                    rec.getPotentialSavings(),
                                                    REASON,
                                                    rec.getReason()))
                            .collect(Collectors.toList());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error retrieving alternative recommendations for user {}: {}",
                        user.getUserId(),
                        e.getMessage(),
                        e);
            }
            throw new AppException(
                    ErrorCode.DATABASE_QUERY_FAILED,
                    "Failed to retrieve alternative recommendations: " + e.getMessage(),
                    null,
                    null,
                    e);
        }
    }

    /** Get predictive cancellations GET /api/subscriptions/insights/predictive-cancellations */
    @GetMapping("/insights/predictive-cancellations")
    @Operation(
            summary = "Get predictive cancellations",
            description =
                    "Predicts which subscriptions are likely to be cancelled based on usage patterns")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Predictive cancellations retrieved successfully"),
        @ApiResponse(responseCode = "401", description = UNAUTHORIZED_USER_NOT_AUTHENTICATED),
        @ApiResponse(responseCode = "500", description = INTERNAL_SERVER_ERROR)
    })
    public ResponseEntity<List<Map<String, Object>>> getPredictiveCancellations(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        try {
            final List<SubscriptionAdvancedService.PredictiveCancellation> predictions =
                    advancedService.predictCancellations(user.getUserId());

            final List<Map<String, Object>> response =
                    predictions.stream()
                            .map(
                                    pred ->
                                            Map.of(
                                                    SUBSCRIPTION,
                                                    toSubscriptionMap(pred.getSubscription()),
                                                    "cancellationProbability",
                                                    pred.getCancellationProbability(),
                                                    REASON,
                                                    pred.getReason(),
                                                    "healthScore",
                                                    pred.getHealthScore()))
                            .collect(Collectors.toList());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error retrieving predictive cancellations for user {}: {}",
                        user.getUserId(),
                        e.getMessage(),
                        e);
            }
            throw new AppException(
                    ErrorCode.DATABASE_QUERY_FAILED,
                    "Failed to retrieve predictive cancellations: " + e.getMessage(),
                    null,
                    null,
                    e);
        }
    }

    /**
     * Per-category density summary — "you have 5 streaming subs totaling
     * $87/mo". Lightweight surface for iOS to render concentration
     * insights without re-doing the math client-side.
     */
    @GetMapping("/insights/category-density")
    @Operation(
            summary = "Per-category subscription density",
            description =
                    "Returns the count and combined monthly spend of subscriptions grouped by category, sorted by spend desc")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Density summary retrieved"),
        @ApiResponse(responseCode = "401", description = UNAUTHORIZED_USER_NOT_AUTHENTICATED),
        @ApiResponse(responseCode = "500", description = INTERNAL_SERVER_ERROR)
    })
    public ResponseEntity<List<Map<String, Object>>> getCategoryDensity(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));
        try {
            final List<Map<String, Object>> response =
                    advancedService.getCategoryDensity(user.getUserId()).stream()
                            .map(d -> {
                                final Map<String, Object> row = new java.util.LinkedHashMap<>();
                                row.put("category", d.getCategory());
                                row.put("count", d.getCount());
                                row.put("monthlySpend", d.getMonthlySpend());
                                return row;
                            })
                            .collect(Collectors.toList());
            return ResponseEntity.ok(response);
        } catch (RuntimeException ex) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to compute density: " + ex.getMessage());
        }
    }

    /**
     * Forward-looking renewal calendar. Returns the active subscriptions
     * whose next billing date falls within {@code windowDays} of today,
     * sorted by daysUntilRenewal ascending.
     */
    @GetMapping("/insights/renewals")
    @Operation(
            summary = "Upcoming subscription renewals",
            description = "Lists active subscriptions billing within the next N days")
    public ResponseEntity<?> getUpcomingRenewals(
            @AuthenticationPrincipal final UserDetails userDetails,
            @org.springframework.web.bind.annotation.RequestParam(
                            value = "windowDays",
                            defaultValue = "30")
                    final int windowDays) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));
        if (renewalForecastService == null) {
            return ResponseEntity.ok(java.util.List.of());
        }
        return ResponseEntity.ok(
                renewalForecastService.renewalCalendar(
                        user.getUserId(), windowDays, java.time.LocalDate.now()));
    }

    /**
     * "Review before renewal" — long-cycle (annual + semi-annual)
     * subscriptions about to renew within the next 14 days. These are
     * the ones users forget about and get surprised by; the endpoint
     * exists so iOS can drive a proactive reminder banner.
     */
    @GetMapping("/insights/renewals/review-window")
    @Operation(
            summary = "Annual subscriptions renewing soon",
            description = "Annual + semi-annual subs renewing within the next 14 days")
    public ResponseEntity<?> getAnnualReviewWindow(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));
        if (renewalForecastService == null) {
            return ResponseEntity.ok(java.util.List.of());
        }
        return ResponseEntity.ok(renewalForecastService.annualReviewWindow(user.getUserId()));
    }

    /**
     * Per-merchant spend trend (sparkline data). Returns a contiguous
     * weekly series suitable for rendering a sparkline next to a
     * subscription or merchant card. Empty series when the service
     * isn't wired or the merchant has no transactions in the window.
     */
    @GetMapping("/insights/merchant-trend")
    @Operation(
            summary = "Per-merchant spend trend",
            description = "Weekly spend series for a merchant over the last N weeks")
    public ResponseEntity<?> getMerchantSpendTrend(
            @AuthenticationPrincipal final UserDetails userDetails,
            @org.springframework.web.bind.annotation.RequestParam("merchant") final String merchant,
            @org.springframework.web.bind.annotation.RequestParam(
                            value = "weeks",
                            defaultValue = "52")
                    final int weeks) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));
        if (merchantSpendTrendService == null) {
            return ResponseEntity.ok(
                    new com.budgetbuddy.service.MerchantSpendTrendService.TrendResult());
        }
        return ResponseEntity.ok(
                merchantSpendTrendService.trend(user.getUserId(), merchant, weeks));
    }

    /** Get comprehensive subscription optimization GET /api/subscriptions/insights/optimization */
    @GetMapping("/insights/optimization")
    @Operation(
            summary = "Get subscription optimization",
            description =
                    "Provides comprehensive subscription optimization recommendations including cancellations, bundling, and alternatives")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Optimization recommendations retrieved successfully"),
        @ApiResponse(responseCode = "401", description = UNAUTHORIZED_USER_NOT_AUTHENTICATED),
        @ApiResponse(responseCode = "500", description = INTERNAL_SERVER_ERROR)
    })
    public ResponseEntity<Map<String, Object>> getOptimization(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        try {
            final SubscriptionAdvancedService.SubscriptionOptimization optimization =
                    advancedService.optimizePortfolio(user.getUserId());

            final Map<String, Object> response =
                    Map.of(
                            "totalSubscriptions",
                            optimization.getTotalSubscriptions(),
                            "currentMonthlySpend",
                            optimization.getCurrentMonthlySpend(),
                            "totalPotentialSavings",
                            optimization.getTotalPotentialSavings(),
                            "cancellationSavings",
                            optimization.getCancellationSavings(),
                            "bundlingSavings",
                            optimization.getBundlingSavings(),
                            "alternativeSavings",
                            optimization.getAlternativeSavings(),
                            CANCELLATION_RECOMMENDATIONS,
                            optimization.getCancellationRecommendations().stream()
                                    .map(this::toCancellationRecommendationMap)
                                    .collect(Collectors.toList()),
                            "bundlingRecommendations",
                            optimization.getBundlingRecommendations().stream()
                                    .map(
                                            rec ->
                                                    Map.of(
                                                            "bundleType",
                                                            rec.getBundleType(),
                                                            POTENTIAL_SAVINGS,
                                                            rec.getPotentialSavings(),
                                                            "description",
                                                            rec.getDescription()))
                                    .collect(Collectors.toList()),
                            "alternativeRecommendations",
                            optimization.getAlternativeRecommendations().stream()
                                    .map(
                                            rec ->
                                                    Map.of(
                                                            "currentSubscription",
                                                            rec.getCurrentSubscription()
                                                                    .getMerchantName(),
                                                            "alternativeName",
                                                            rec.getAlternativeName(),
                                                            POTENTIAL_SAVINGS,
                                                            rec.getPotentialSavings()))
                                    .collect(Collectors.toList()),
                            "trialAlerts",
                            optimization.getTrialAlerts().stream()
                                    .map(
                                            alert ->
                                                    Map.of(
                                                            SUBSCRIPTION,
                                                            alert.getSubscription()
                                                                    .getMerchantName(),
                                                            "daysUntilExpiration",
                                                            alert.getDaysUntilExpiration()))
                                    .collect(Collectors.toList()));

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error retrieving optimization recommendations for user {}: {}",
                        user.getUserId(),
                        e.getMessage(),
                        e);
            }
            throw new AppException(
                    ErrorCode.DATABASE_QUERY_FAILED,
                    "Failed to retrieve optimization recommendations: " + e.getMessage(),
                    null,
                    null,
                    e);
        }
    }

    // Helper methods to convert insights to maps
    private Map<String, Object> toUnusedInsightMap(
            final SubscriptionInsightsService.UnusedSubscriptionInsight insight) {
        return Map.of(
                SUBSCRIPTION,
                toSubscriptionMap(insight.getSubscription()),
                REASON,
                insight.getReason(),
                POTENTIAL_SAVINGS,
                insight.getPotentialSavings(),
                "severity",
                insight.getSeverity().name());
    }

    private Map<String, Object> toPriceChangeMap(
            final SubscriptionInsightsService.PriceChangeAlert alert) {
        return Map.of(
                SUBSCRIPTION,
                toSubscriptionMap(alert.getSubscription()),
                "newAmount",
                alert.getNewAmount(),
                "oldAmount",
                alert.getOldAmount(),
                "percentChange",
                alert.getPercentChange(),
                "detectedDate",
                alert.getDetectedDate());
    }

    private Map<String, Object> toCancellationRecommendationMap(
            final SubscriptionInsightsService.CancellationRecommendation recommendation) {
        return Map.of(
                SUBSCRIPTION,
                toSubscriptionMap(recommendation.getSubscription()),
                REASON,
                recommendation.getReason(),
                POTENTIAL_SAVINGS,
                recommendation.getPotentialSavings(),
                "priority",
                recommendation.getPriority().name());
    }

    private Map<String, Object> toSubscriptionMap(final Subscription subscription) {
        return Map.of(
                "subscriptionId", subscription.getSubscriptionId(),
                "merchantName", subscription.getMerchantName(),
                "amount", subscription.getAmount(),
                "frequency", subscription.getFrequency().name(),
                "subscriptionType",
                        subscription.getSubscriptionType() != null
                                ? subscription.getSubscriptionType()
                                : "other");
    }
}
