package com.budgetbuddy.api;

import com.budgetbuddy.analytics.AnalyticsService;
import com.budgetbuddy.model.User;
import com.budgetbuddy.service.UserService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticatedPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * Analytics REST Controller
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final UserService userService;

    public AnalyticsController(AnalyticsService analyticsService, UserService userService) {
        this.analyticsService = analyticsService;
        this.userService = userService;
    }

    @GetMapping("/spending-summary")
    public ResponseEntity<AnalyticsService.SpendingSummary> getSpendingSummary(
            @AuthenticatedPrincipal UserDetails userDetails,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        User user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        AnalyticsService.SpendingSummary summary = analyticsService.getSpendingSummary(user, startDate, endDate);
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/spending-by-category")
    public ResponseEntity<Map<String, java.math.BigDecimal>> getSpendingByCategory(
            @AuthenticatedPrincipal UserDetails userDetails,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        User user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Map<String, java.math.BigDecimal> categorySpending = 
                analyticsService.getSpendingByCategory(user, startDate, endDate);
        return ResponseEntity.ok(categorySpending);
    }
}

