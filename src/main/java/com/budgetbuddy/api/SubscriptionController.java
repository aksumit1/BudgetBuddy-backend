package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.SubscriptionService;
import com.budgetbuddy.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Subscription REST Controller
 * Handles subscription detection, retrieval, and management
 */
@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final UserService userService;

    public SubscriptionController(
            final SubscriptionService subscriptionService,
            final UserService userService) {
        this.subscriptionService = subscriptionService;
        this.userService = userService;
    }

    /**
     * Detect subscriptions from user's transactions
     * POST /api/subscriptions/detect
     */
    @PostMapping("/detect")
    public ResponseEntity<List<Subscription>> detectSubscriptions(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        List<Subscription> subscriptions = subscriptionService.detectSubscriptions(user.getUserId());
        
        // Save detected subscriptions
        subscriptionService.saveSubscriptions(user.getUserId(), subscriptions);

        return ResponseEntity.ok(subscriptions);
    }

    /**
     * Get all subscriptions for the authenticated user
     * GET /api/subscriptions
     */
    @GetMapping
    public ResponseEntity<List<Subscription>> getSubscriptions(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        List<Subscription> subscriptions = subscriptionService.getSubscriptions(user.getUserId());
        return ResponseEntity.ok(subscriptions);
    }

    /**
     * Get active subscriptions for the authenticated user
     * GET /api/subscriptions/active
     */
    @GetMapping("/active")
    public ResponseEntity<List<Subscription>> getActiveSubscriptions(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        List<Subscription> subscriptions = subscriptionService.getActiveSubscriptions(user.getUserId());
        return ResponseEntity.ok(subscriptions);
    }

    /**
     * Delete a subscription
     * DELETE /api/subscriptions/{subscriptionId}
     */
    @DeleteMapping("/{subscriptionId}")
    public ResponseEntity<Void> deleteSubscription(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String subscriptionId) {
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
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Subscription not found or access denied");
        }

        subscriptionService.deleteSubscription(subscriptionId);
        return ResponseEntity.noContent().build();
    }
}

