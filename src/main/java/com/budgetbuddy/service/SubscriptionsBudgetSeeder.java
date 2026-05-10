package com.budgetbuddy.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Flow 5 / O9 — wires subscription detection into the budget system.
 *
 * <p>Without this, the app detected recurring charges (Netflix, Amazon Prime, Spotify) but never
 * translated that signal into a budget. Users had to manually add a Subscriptions budget and figure
 * out the number themselves. Now: the first time detection runs and finds subscriptions for a user
 * with no Subscriptions budget, we seed one at {@code sum(monthly-equivalent amounts) × 1.10}. The
 * 10% cushion covers yearly price creep without the budget immediately flashing red.
 *
 * <p>One-shot by design — if the user edits or deletes the seeded budget later, we never re-create
 * it. The trigger is "missing", not "different from detected".
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Service
public class SubscriptionsBudgetSeeder {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubscriptionsBudgetSeeder.class);
    private static final String SUBSCRIPTIONS_CATEGORY = "subscriptions";
    private static final BigDecimal CUSHION = new BigDecimal("1.10");

    private final BudgetRepository budgetRepository;
    private final BudgetService budgetService;
    private final UserService userService;

    public SubscriptionsBudgetSeeder(
            final BudgetRepository budgetRepository,
            final BudgetService budgetService,
            final UserService userService) {
        this.budgetRepository = budgetRepository;
        this.budgetService = budgetService;
        this.userService = userService;
    }

    public void seedSubscriptionsBudgetIfMissing(
            final String userId, final List<Subscription> subs) {
        if (userId == null || subs == null || subs.isEmpty()) {
            return;
        }

        final Optional<BudgetTable> existing =
                budgetRepository.findByUserIdAndCategory(userId, SUBSCRIPTIONS_CATEGORY);
        if (existing.isPresent()) {
            return; // User already has a subscriptions budget — don't clobber it.
        }

        BigDecimal suggested = monthlyEquivalent(subs);
        if (suggested.signum() <= 0) {
            return;
        }

        // Round up to the nearest dollar; cushion already applied. Clean numbers feel
        // deliberate to the user, not algorithmic.
        suggested = suggested.setScale(0, RoundingMode.UP);

        final Optional<UserTable> userOpt = userService.findById(userId);
        if (userOpt.isEmpty()) {
            LOGGER.warn("Can't seed Subscriptions budget: user {} not found", userId);
            return;
        }
        budgetService.createOrUpdateBudget(
                userOpt.get(),
                SUBSCRIPTIONS_CATEGORY,
                suggested,
                null, // generate deterministic id
                null, // rolloverEnabled
                null, // carriedAmount
                null, // goalId
                null, // goalAllocation
                "monthly", // period
                null // currencyCode – service falls back to user's preferred
                );
        LOGGER.info(
                "Seeded Subscriptions budget for user {} at {} (from {} detected subscriptions)",
                userId,
                suggested,
                subs.size());
    }

    /** Sum of each subscription's monthly-equivalent amount, with a 10% cushion. */
    private BigDecimal monthlyEquivalent(final List<Subscription> subs) {
        BigDecimal total = BigDecimal.ZERO;
        for (final Subscription s : subs) {
            if (s == null || s.getAmount() == null || s.getFrequency() == null) {
                continue;
            }
            final BigDecimal amount = s.getAmount().abs();
            // Normalise to monthly: divide yearly by 12, multiply weekly by ~4.33.
            final String freq = s.getFrequency().name();
            final BigDecimal monthly =
                    switch (freq) {
                        case "YEARLY" ->
                            amount.divide(new BigDecimal("12"), 2, RoundingMode.HALF_UP);
                        case "QUARTERLY" ->
                            amount.divide(new BigDecimal("3"), 2, RoundingMode.HALF_UP);
                        case "WEEKLY" -> amount.multiply(new BigDecimal("4.33"));
                        case "BIWEEKLY" -> amount.multiply(new BigDecimal("2.17"));
                        default -> amount; // MONTHLY, or anything else → treat as monthly
                    };
            total = total.add(monthly);
        }
        return total.multiply(CUSHION);
    }
}
