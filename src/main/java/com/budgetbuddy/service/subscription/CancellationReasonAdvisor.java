package com.budgetbuddy.service.subscription;

import com.budgetbuddy.service.SubscriptionInsightsService.CancellationRecommendation;
import java.util.List;

/**
 * AI-5: optional advisor that personalises the hardcoded "Unused
 * subscription - no recent activity" string on a cancellation
 * recommendation into a one-liner the user can act on directly.
 *
 * <p>Contract: never mutates the deterministic {@code reason} field or
 * any numeric on the recommendation — only sets {@code humanMessage}.
 * Returns the input list unchanged on any failure so the deterministic
 * path stays authoritative.
 */
public interface CancellationReasonAdvisor {
    List<CancellationRecommendation> annotate(List<CancellationRecommendation> recommendations);
}
