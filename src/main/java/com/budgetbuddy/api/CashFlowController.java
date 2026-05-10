package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.RecurringIncomeDetector;
import com.budgetbuddy.service.UserService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Flow 7 / O10 — recurring income + 30-day cash-flow projection. Two endpoints: - {@code GET
 * /api/cashflow/recurring-income} for the "next paycheck" chip. - {@code GET
 * /api/cashflow/projection} for the 30-day line chart.
 */
@RestController
@RequestMapping("/api/cashflow")
public class CashFlowController {

    private final UserService userService;
    private final RecurringIncomeDetector detector;

    public CashFlowController(
            final UserService userService, final RecurringIncomeDetector detector) {
        this.userService = userService;
        this.detector = detector;
    }

    @GetMapping("/recurring-income")
    public ResponseEntity<List<RecurringIncomeDetector.RecurringIncome>> recurringIncome(
            @AuthenticationPrincipal final UserDetails userDetails) {
        final UserTable user = authenticate(userDetails);
        return ResponseEntity.ok(detector.detect(user.getUserId()));
    }

    @GetMapping("/projection")
    public ResponseEntity<List<RecurringIncomeDetector.CashFlowPoint>> projection(
            @AuthenticationPrincipal final UserDetails userDetails) {
        final UserTable user = authenticate(userDetails);
        return ResponseEntity.ok(detector.projectThirtyDays(user.getUserId()));
    }

    /**
     * Flow 8 / O5 — upcoming bills within the next N days. iOS used to compute this client-side for
     * a fixed 14-day window; now the window is variable and the computation is shared so web /
     * watch clients agree. Sources: - recurring income detector's outbound counterpart
     * (subscriptions), - account payment due dates (credit card payments), - known recurring
     * merchants with monthly cadence.
     */
    @GetMapping("/upcoming-bills")
    public ResponseEntity<List<java.util.Map<String, Object>>> upcomingBills(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam(defaultValue = "30") final int days) {
        final UserTable user = authenticate(userDetails);
        final int window = Math.max(1, Math.min(days, 90));
        return ResponseEntity.ok(detector.upcomingBillsNextDays(user.getUserId(), window));
    }

    private UserTable authenticate(final UserDetails userDetails) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        return userService
                .findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
    }
}
