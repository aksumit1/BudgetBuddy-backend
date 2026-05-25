package com.budgetbuddy.service.insights;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.SubscriptionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds {@link InsightsContext} snapshots. Centralises the choice of
 * "longest window any detector needs" so it can be retuned in one place
 * if a future detector needs more history.
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Service
public class InsightsContextFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(InsightsContextFactory.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Currently TransactionAnomalyService's 180-day historical window
     * is the widest. Subscriptions/accounts are fetched independently
     * and don't have date windows. If you add a detector that needs
     * more history, bump this.
     */
    private static final int LONGEST_WINDOW_DAYS = 365;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final SubscriptionService subscriptionService;
    private final TransactionActionRepository actionRepository;
    /** Setter-injected so old tests don't need to thread a sixth arg. */
    private BudgetRepository budgetRepository;
    /** Setter-injected, same pattern as {@link #budgetRepository}. */
    private GoalRepository goalRepository;

    public InsightsContextFactory(
            final TransactionRepository transactionRepository,
            final AccountRepository accountRepository,
            final SubscriptionService subscriptionService,
            final TransactionActionRepository actionRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.subscriptionService = subscriptionService;
        this.actionRepository = actionRepository;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBudgetRepository(final BudgetRepository budgetRepository) {
        this.budgetRepository = budgetRepository;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setGoalRepository(final GoalRepository goalRepository) {
        this.goalRepository = goalRepository;
    }

    /**
     * Build a context for {@code userId}. Each downstream service
     * receives the same snapshot, eliminating per-service refetches.
     * Failures to fetch subscriptions are tolerated (some users have
     * none; the SubscriptionService can throw on configuration errors
     * that we don't want to fail the whole summary on).
     */
    public InsightsContext buildFor(final String userId) {
        final LocalDate today = LocalDate.now();
        final LocalDate start = today.minusDays(LONGEST_WINDOW_DAYS);

        final List<TransactionTable> txs =
                transactionRepository.findByUserIdAndDateRange(
                        userId, start.format(ISO), today.format(ISO));
        final List<AccountTable> accounts = accountRepository.findByUserId(userId);

        List<Subscription> subs;
        try {
            subs = subscriptionService.getSubscriptions(userId);
        } catch (final RuntimeException e) {
            // Subscriptions are advisory for some detectors; never let
            // a subscription-service blip take down /summary.
            LOGGER.warn("InsightsContext: subscription fetch failed for user {}: {}",
                    userId, e.getMessage());
            subs = List.of();
        }

        List<TransactionActionTable> actions;
        try {
            actions = actionRepository.findByUserId(userId);
        } catch (final RuntimeException e) {
            // Actions are advisory (only MissedPayment uses them); a
            // fetch blip must not fail the whole summary.
            LOGGER.warn("InsightsContext: action fetch failed for user {}: {}",
                    userId, e.getMessage());
            actions = List.of();
        }

        List<BudgetTable> budgets;
        if (budgetRepository == null) {
            budgets = List.of();
        } else {
            try {
                budgets = budgetRepository.findByUserId(userId);
            } catch (final RuntimeException e) {
                LOGGER.warn("InsightsContext: budget fetch failed for user {}: {}",
                        userId, e.getMessage());
                budgets = List.of();
            }
        }

        List<GoalTable> goals;
        if (goalRepository == null) {
            goals = null;  // unavailable — chat snapshot will note this
        } else {
            try {
                goals = goalRepository.findByUserId(userId);
            } catch (final RuntimeException e) {
                LOGGER.warn("InsightsContext: goal fetch failed for user {}: {}",
                        userId, e.getMessage());
                goals = List.of();
            }
        }

        return new InsightsContext(
                userId, today, txs, accounts, subs, actions, budgets, goals);
    }
}
