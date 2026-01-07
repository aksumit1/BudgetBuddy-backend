package com.budgetbuddy.api;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.GoalAnalyticsService;
import com.budgetbuddy.service.GoalMilestoneService;
import com.budgetbuddy.service.GoalRoundUpService;
import com.budgetbuddy.service.GoalService;
import com.budgetbuddy.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Enhanced Goal Controller for delightful features
 * Provides milestones, analytics, round-up, and other customer delight features
 */
@RestController
@RequestMapping("/api/goals")
public class GoalEnhancementController {

    private static final Logger logger = LoggerFactory.getLogger(GoalEnhancementController.class);

    private final GoalService goalService;
    private final GoalMilestoneService milestoneService;
    private final GoalAnalyticsService analyticsService;
    private final GoalRoundUpService roundUpService;
    private final UserService userService;

    public GoalEnhancementController(GoalService goalService, GoalMilestoneService milestoneService,
                                     GoalAnalyticsService analyticsService, GoalRoundUpService roundUpService,
                                     UserService userService) {
        this.goalService = goalService;
        this.milestoneService = milestoneService;
        this.analyticsService = analyticsService;
        this.roundUpService = roundUpService;
        this.userService = userService;
    }

    /**
     * Get milestones for a goal
     * GET /api/goals/{id}/milestones
     */
    @GetMapping("/{id}/milestones")
    public ResponseEntity<?> getMilestones(@AuthenticationPrincipal UserDetails userDetails,
                                          @PathVariable String id) {
        try {
            UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

            var goal = goalService.getGoal(user, id);
            List<GoalMilestoneService.Milestone> milestones = milestoneService.getMilestones(goal);

            Map<String, Object> response = new HashMap<>();
            response.put("milestones", milestones);
            response.put("progressPercentage", milestoneService.getProgressPercentage(goal));
            response.put("nextMilestone", milestoneService.getNextMilestone(goal));

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error getting milestones for goal {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get goal projections and analytics
     * GET /api/goals/{id}/projections
     */
    @GetMapping("/{id}/projections")
    public ResponseEntity<?> getProjections(@AuthenticationPrincipal UserDetails userDetails,
                                           @PathVariable String id) {
        try {
            UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

            var goal = goalService.getGoal(user, id);
            GoalAnalyticsService.GoalProjection projection = analyticsService.calculateProjection(goal, user.getUserId());

            if (projection == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Goal projection not available"));
            }

            return ResponseEntity.ok(projection);
        } catch (Exception e) {
            logger.error("Error getting projections for goal {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get contribution insights
     * GET /api/goals/{id}/insights
     */
    @GetMapping("/{id}/insights")
    public ResponseEntity<?> getInsights(@AuthenticationPrincipal UserDetails userDetails,
                                        @PathVariable String id) {
        try {
            UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

            var goal = goalService.getGoal(user, id);
            GoalAnalyticsService.ContributionInsights insights = analyticsService.getContributionInsights(goal, user.getUserId());

            return ResponseEntity.ok(insights);
        } catch (Exception e) {
            logger.error("Error getting insights for goal {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Enable round-up for a goal
     * POST /api/goals/{id}/round-up/enable
     */
    @PostMapping("/{id}/round-up/enable")
    public ResponseEntity<?> enableRoundUp(@AuthenticationPrincipal UserDetails userDetails,
                                           @PathVariable String id) {
        try {
            UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

            // Verify goal ownership
            goalService.getGoal(user, id);

            roundUpService.enableRoundUp(id);
            return ResponseEntity.ok(Map.of("message", "Round-up enabled for goal", "goalId", id));
        } catch (Exception e) {
            logger.error("Error enabling round-up for goal {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Disable round-up for a goal
     * POST /api/goals/{id}/round-up/disable
     */
    @PostMapping("/{id}/round-up/disable")
    public ResponseEntity<?> disableRoundUp(@AuthenticationPrincipal UserDetails userDetails,
                                            @PathVariable String id) {
        try {
            UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

            // Verify goal ownership
            goalService.getGoal(user, id);

            roundUpService.disableRoundUp(id);
            return ResponseEntity.ok(Map.of("message", "Round-up disabled for goal", "goalId", id));
        } catch (Exception e) {
            logger.error("Error disabling round-up for goal {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get round-up total for a goal
     * GET /api/goals/{id}/round-up/total
     */
    @GetMapping("/{id}/round-up/total")
    public ResponseEntity<?> getRoundUpTotal(@AuthenticationPrincipal UserDetails userDetails,
                                            @PathVariable String id,
                                            @RequestParam(defaultValue = "30") int days) {
        try {
            UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

            var goal = goalService.getGoal(user, id);
            var total = roundUpService.getRoundUpTotal(goal, user.getUserId(), days);

            return ResponseEntity.ok(Map.of("total", total, "days", days));
        } catch (Exception e) {
            logger.error("Error getting round-up total for goal {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}

