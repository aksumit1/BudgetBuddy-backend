package com.budgetbuddy.api;

import com.budgetbuddy.analytics.AnalyticsService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.service.UserService;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Analytics REST Controller */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final UserService userService;

    public AnalyticsController(
            final AnalyticsService analyticsService, final UserService userService) {
        this.analyticsService = analyticsService;
        this.userService = userService;
    }

    @GetMapping("/spending-summary")
    public ResponseEntity<AnalyticsService.SpendingSummary> getSpendingSummary(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate endDate) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final com.budgetbuddy.model.dynamodb.UserTable userTable =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        final AnalyticsService.SpendingSummary summary =
                analyticsService.getSpendingSummary(userTable, startDate, endDate);
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/spending-by-category")
    public ResponseEntity<Map<String, java.math.BigDecimal>> getSpendingByCategory(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate endDate) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final com.budgetbuddy.model.dynamodb.UserTable userTable =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        final Map<String, java.math.BigDecimal> categorySpending =
                analyticsService.getSpendingByCategory(userTable, startDate, endDate);
        return ResponseEntity.ok(categorySpending);
    }
}
