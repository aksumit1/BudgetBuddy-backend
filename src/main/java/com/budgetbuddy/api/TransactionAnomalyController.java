package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.service.ml.TransactionAnomalyDetector;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Surfaces {@link TransactionAnomalyDetector} as an on-demand REST endpoint.
 * iOS calls this when displaying a transaction detail to render an
 * "unusual for you" badge. Conditional on the detector bean — when
 * anomaly detection is disabled, this controller doesn't load at all so
 * the route returns 404.
 */
@RestController
@RequestMapping("/api/transactions/anomaly")
@ConditionalOnBean(TransactionAnomalyDetector.class)
public class TransactionAnomalyController {

    private final TransactionAnomalyDetector detector;
    private final TransactionRepository transactionRepository;
    private final UserService userService;

    public TransactionAnomalyController(
            final TransactionAnomalyDetector detector,
            final TransactionRepository transactionRepository,
            final UserService userService) {
        this.detector = detector;
        this.transactionRepository = transactionRepository;
        this.userService = userService;
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<Map<String, Object>> scoreTransaction(
            @AuthenticationPrincipal final UserDetails userDetails,
            @PathVariable final String transactionId) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
        final TransactionTable candidate = transactionRepository.findById(transactionId).orElseThrow(
                () -> new AppException(ErrorCode.TRANSACTION_NOT_FOUND, "Transaction not found"));
        if (!candidate.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Transaction does not belong to user");
        }
        // Pull recent history (last N read serves the detector's window).
        // 10K cap matches the rest of the codebase; detector trims to its
        // configured day window internally.
        final var history = transactionRepository.findByUserId(user.getUserId(), 0, 10_000);
        final double score = detector.scoreUnusualness(candidate, history);

        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("transactionId", transactionId);
        body.put("score", score);              // -1 sentinel = couldn't score
        body.put("anomalous", score >= 0 && detector.isAnomalous(score));
        return ResponseEntity.ok(body);
    }
}
