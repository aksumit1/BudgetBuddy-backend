package com.budgetbuddy.api;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.GoalAnalyticsService;
import com.budgetbuddy.service.GoalMilestoneService;
import com.budgetbuddy.service.GoalRoundUpService;
import com.budgetbuddy.service.GoalService;
import com.budgetbuddy.service.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Enhanced Goal Controller for delightful features Provides milestones, analytics, round-up, and
 * other customer delight features
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@RestController
@RequestMapping("/api/goals")
public class GoalEnhancementController {

    private static final String USER_NOT_FOUND = "User not found";

    private static final String ERROR = "error";

    private static final Logger LOGGER = LoggerFactory.getLogger(GoalEnhancementController.class);

    private final GoalService goalService;
    private final GoalMilestoneService milestoneService;
    private final GoalAnalyticsService analyticsService;
    private final GoalRoundUpService roundUpService;
    private final UserService userService;

    public GoalEnhancementController(
            final GoalService goalService,
            final GoalMilestoneService milestoneService,
            final GoalAnalyticsService analyticsService,
            final GoalRoundUpService roundUpService,
            final UserService userService) {
        this.goalService = goalService;
        this.milestoneService = milestoneService;
        this.analyticsService = analyticsService;
        this.roundUpService = roundUpService;
        this.userService = userService;
    }

    /** Get milestones for a goal GET /api/goals/{id}/milestones */
    @GetMapping("/{id}/milestones")
    public ResponseEntity<?> getMilestones(
            @AuthenticationPrincipal final UserDetails userDetails, @PathVariable final String id) {
        try {
            final UserTable user =
                    userService
                            .findByEmail(userDetails.getUsername())
                            .orElseThrow(() -> new RuntimeException(USER_NOT_FOUND));

            final var goal = goalService.getGoal(user, id);
            final List<GoalMilestoneService.Milestone> milestones =
                    milestoneService.getMilestones(goal);

            final Map<String, Object> response = new HashMap<>();
            response.put("milestones", milestones);
            response.put("progressPercentage", milestoneService.getProgressPercentage(goal));
            response.put("nextMilestone", milestoneService.getNextMilestone(goal));

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LOGGER.error("Error getting milestones for goal {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(ERROR, e.getMessage()));
        }
    }

    /** Get goal projections and analytics GET /api/goals/{id}/projections */
    @GetMapping("/{id}/projections")
    public ResponseEntity<?> getProjections(
            @AuthenticationPrincipal final UserDetails userDetails, @PathVariable final String id) {
        try {
            final UserTable user =
                    userService
                            .findByEmail(userDetails.getUsername())
                            .orElseThrow(() -> new RuntimeException(USER_NOT_FOUND));

            final var goal = goalService.getGoal(user, id);
            final GoalAnalyticsService.GoalProjection projection =
                    analyticsService.calculateProjection(goal, user.getUserId());

            if (projection == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of(ERROR, "Goal projection not available"));
            }

            return ResponseEntity.ok(projection);
        } catch (Exception e) {
            LOGGER.error("Error getting projections for goal {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(ERROR, e.getMessage()));
        }
    }

    /** Get contribution insights GET /api/goals/{id}/insights */
    @GetMapping("/{id}/insights")
    public ResponseEntity<?> getInsights(
            @AuthenticationPrincipal final UserDetails userDetails, @PathVariable final String id) {
        try {
            final UserTable user =
                    userService
                            .findByEmail(userDetails.getUsername())
                            .orElseThrow(() -> new RuntimeException(USER_NOT_FOUND));

            final var goal = goalService.getGoal(user, id);
            final GoalAnalyticsService.ContributionInsights insights =
                    analyticsService.getContributionInsights(goal, user.getUserId());

            return ResponseEntity.ok(insights);
        } catch (Exception e) {
            LOGGER.error("Error getting insights for goal {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(ERROR, e.getMessage()));
        }
    }

    /** Enable round-up for a goal POST /api/goals/{id}/round-up/enable */
    @PostMapping("/{id}/round-up/enable")
    public ResponseEntity<?> enableRoundUp(
            @AuthenticationPrincipal final UserDetails userDetails, @PathVariable final String id) {
        try {
            final UserTable user =
                    userService
                            .findByEmail(userDetails.getUsername())
                            .orElseThrow(() -> new RuntimeException(USER_NOT_FOUND));

            // Verify goal ownership
            goalService.getGoal(user, id);

            roundUpService.enableRoundUp(id);
            return ResponseEntity.ok(Map.of("message", "Round-up enabled for goal", "goalId", id));
        } catch (Exception e) {
            LOGGER.error("Error enabling round-up for goal {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(ERROR, e.getMessage()));
        }
    }

    /** Disable round-up for a goal POST /api/goals/{id}/round-up/disable */
    @PostMapping("/{id}/round-up/disable")
    public ResponseEntity<?> disableRoundUp(
            @AuthenticationPrincipal final UserDetails userDetails, @PathVariable final String id) {
        try {
            final UserTable user =
                    userService
                            .findByEmail(userDetails.getUsername())
                            .orElseThrow(() -> new RuntimeException(USER_NOT_FOUND));

            // Verify goal ownership
            goalService.getGoal(user, id);

            roundUpService.disableRoundUp(id);
            return ResponseEntity.ok(Map.of("message", "Round-up disabled for goal", "goalId", id));
        } catch (Exception e) {
            LOGGER.error("Error disabling round-up for goal {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(ERROR, e.getMessage()));
        }
    }

    /** Get round-up total for a goal GET /api/goals/{id}/round-up/total */
    @GetMapping("/{id}/round-up/total")
    public ResponseEntity<?> getRoundUpTotal(
            @AuthenticationPrincipal final UserDetails userDetails,
            @PathVariable final String id,
            @RequestParam(defaultValue = "30") final int days) {
        try {
            final UserTable user =
                    userService
                            .findByEmail(userDetails.getUsername())
                            .orElseThrow(() -> new RuntimeException(USER_NOT_FOUND));

            final var goal = goalService.getGoal(user, id);
            final var total = roundUpService.getRoundUpTotal(goal, user.getUserId(), days);

            return ResponseEntity.ok(Map.of("total", total, "days", days));
        } catch (Exception e) {
            LOGGER.error("Error getting round-up total for goal {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(ERROR, e.getMessage()));
        }
    }
}
