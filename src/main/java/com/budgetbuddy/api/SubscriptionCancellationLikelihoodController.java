package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.SubscriptionService;
import com.budgetbuddy.service.SubscriptionUsagePredictor;
import com.budgetbuddy.service.UserService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Surfaces {@link SubscriptionUsagePredictor} as an on-demand REST endpoint.
 * iOS calls this on the Subscriptions screen to render a "likely to
 * cancel" badge. Conditional on the predictor bean — when
 * {@code app.subscription.usage-prediction.enabled=false} this controller
 * doesn't load and the route returns 404.
 */
@RestController
@RequestMapping("/api/subscriptions/insights")
@ConditionalOnBean(SubscriptionUsagePredictor.class)
public class SubscriptionCancellationLikelihoodController {

    private final SubscriptionUsagePredictor predictor;
    private final SubscriptionService subscriptionService;
    private final TransactionRepository transactionRepository;
    private final UserService userService;

    public SubscriptionCancellationLikelihoodController(
            final SubscriptionUsagePredictor predictor,
            final SubscriptionService subscriptionService,
            final TransactionRepository transactionRepository,
            final UserService userService) {
        this.predictor = predictor;
        this.subscriptionService = subscriptionService;
        this.transactionRepository = transactionRepository;
        this.userService = userService;
    }

    /**
     * Returns one entry per active subscription with the predicted
     * cancellation likelihood and a "shouldFlag" boolean. Empty array
     * when the user has no subscriptions or the predictor is unavailable.
     */
    @GetMapping("/cancellation-likelihood")
    public ResponseEntity<List<Map<String, Object>>> cancellationLikelihood(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
        final List<Subscription> subs = subscriptionService.getActiveSubscriptions(user.getUserId());
        final var history = transactionRepository.findByUserId(user.getUserId(), 0, 10_000);

        final List<Map<String, Object>> out = new ArrayList<>(subs.size());
        for (final Subscription s : subs) {
            final double likelihood = predictor.cancellationLikelihood(s, history);
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("subscriptionId", s.getSubscriptionId());
            row.put("merchantName", s.getMerchantName());
            row.put("likelihood", likelihood);
            row.put("shouldFlag",
                    likelihood >= 0 && predictor.shouldFlagForCancellation(likelihood));
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }
}
