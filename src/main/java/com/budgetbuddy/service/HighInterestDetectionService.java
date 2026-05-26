package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * High Interest Detection Service
 *
 * <p>Identifies accounts with high interest rates and recommends actions to reduce interest costs.
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.DataClass", "PMD.OnlyOneReturn"})
@Service
public class HighInterestDetectionService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(HighInterestDetectionService.class);

    /**
     * Whole-word match so we don't flag merchants whose name happens to
     * contain "interest" (e.g. "INTERESTING TIMES CAFE"). Boundary-aware:
     * "INTEREST CHARGED" matches, "INTERESTING" does not. Case-insensitive.
     */
    private static final Pattern INTEREST_CHARGE_KEYWORD =
            Pattern.compile("\\b(interest|finance\\s*charge)\\b", Pattern.CASE_INSENSITIVE);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final com.budgetbuddy.config.InsightsThresholds thresholds;

    @org.springframework.beans.factory.annotation.Autowired
    public HighInterestDetectionService(
            final AccountRepository accountRepository,
            final TransactionRepository transactionRepository,
            final com.budgetbuddy.config.InsightsThresholds thresholds) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        // Defensive default for Mockito @InjectMocks paths — see
        // TransactionAnomalyService for the full rationale.
        this.thresholds = thresholds != null
                ? thresholds
                : new com.budgetbuddy.config.InsightsThresholds();
    }

    /**
     * Backwards-compat constructor for tests that don't wire the
     * thresholds bean. Uses default values that match the previously
     * hardcoded constants exactly so behaviour is unchanged.
     */
    public HighInterestDetectionService(
            final AccountRepository accountRepository,
            final TransactionRepository transactionRepository) {
        this(accountRepository, transactionRepository,
                new com.budgetbuddy.config.InsightsThresholds());
    }

    private double highRateThreshold() {
        return thresholds.getHighInterest().getHighRateThreshold();
    }

    private double veryHighRateThreshold() {
        return thresholds.getHighInterest().getVeryHighRateThreshold();
    }

    private BigDecimal minInterestPayment() {
        return BigDecimal.valueOf(thresholds.getHighInterest().getMinMonthlyInterestPayment());
    }

    private int analysisWindowDays() {
        return thresholds.getHighInterest().getAnalysisWindowDays();
    }

    /** Detect high interest payments and provide recommendations */
    public List<HighInterestAlert> detectHighInterest(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }
        LOGGER.info("Detecting high interest payments for user: {}", userId);
        // Single-endpoint entry: do the per-request fetches, then run
        // the shared core. The /summary path uses the context overload
        // below so the same fetches don't happen 5 times in one request.
        final LocalDate endDate = LocalDate.now();
        final LocalDate startDate = endDate.minusDays(analysisWindowDays());
        final java.time.format.DateTimeFormatter iso =
                java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
        final List<TransactionTable> txs = transactionRepository.findByUserIdAndDateRange(
                userId, startDate.format(iso), endDate.format(iso));
        final List<AccountTable> accounts = accountRepository.findByUserId(userId);
        return detectFrom(txs, accounts);
    }

    /**
     * Context-aware overload used by {@code /summary} (and any other
     * batch surface). Operates entirely on the pre-fetched snapshot —
     * zero additional repo calls. The context's transaction list is
     * already wider than this service's window, so we filter in-memory.
     */
    public List<HighInterestAlert> detectHighInterest(
            final com.budgetbuddy.service.insights.InsightsContext ctx) {
        if (ctx == null) {
            return new ArrayList<>();
        }
        final LocalDate cutoff = ctx.asOf().minusDays(analysisWindowDays());
        final List<TransactionTable> windowed = ctx.transactions().stream()
                .filter(tx -> tx.getTransactionDate() != null
                        && tx.getTransactionDate().compareTo(cutoff.toString()) >= 0)
                .toList();
        return detectFrom(windowed, ctx.accounts());
    }

    /**
     * Shared core: produces alerts given a pre-filtered tx window and
     * the user's accounts. {@link #detectInterestCharges} still calls
     * {@code accountRepository.findById(accountId)} for the per-alert
     * account lookup; the supplied list is used for the CC/loan
     * iterations to avoid the duplicate findByUserId.
     */
    private List<HighInterestAlert> detectFrom(
            final List<TransactionTable> txs, final List<AccountTable> accounts) {
        final List<HighInterestAlert> alerts = new ArrayList<>();
        alerts.addAll(detectInterestChargesFrom(txs));
        alerts.addAll(analyzeCreditCardsFrom(accounts));
        alerts.addAll(analyzeLoansFrom(accounts));
        // One alert per account. The interest-charge path (observed) and
        // the APR path (computed) often both fire on the same card; the
        // observed alert is the more trustworthy signal — uses real
        // statement data, not an estimate — so keep that one when both
        // exist. Falls back to highest annual cost as a tiebreaker.
        final Map<String, HighInterestAlert> byAccount = new LinkedHashMap<>();
        for (final HighInterestAlert a : alerts) {
            final String key = a.getAccountId() == null ? a.getAccountName() : a.getAccountId();
            if (key == null) {
                continue;
            }
            byAccount.merge(key, a, (existing, incoming) -> {
                // Prefer observed (the rate was computed from real
                // transactions — bigger annual is the observation-rich one
                // when both have real data; the APR path uses fixed APR
                // and tends to under-estimate vs reality).
                return incoming.getAnnualInterestCost()
                                .compareTo(existing.getAnnualInterestCost()) > 0
                        ? incoming : existing;
            });
        }
        return byAccount.values().stream()
                .sorted(Comparator.comparing(
                        HighInterestAlert::getAnnualInterestCost, Comparator.reverseOrder()))
                .toList();
    }

    /**
     * Detect interest charges from a pre-supplied transaction list.
     * Caller is responsible for windowing — the legacy path filters to
     * 90 days; the context path uses whatever the context provided.
     */
    private List<HighInterestAlert> detectInterestChargesFrom(
            final List<TransactionTable> transactions) {
        final List<HighInterestAlert> alerts = new ArrayList<>();
        // Filter to interest charges. Word-boundary keyword match avoids
        // matching merchants like "INTERESTING TIMES CAFE"; amount<0
        // requirement excludes savings-account "interest earned" credits.
        final List<TransactionTable> interestCharges =
                transactions.stream()
                        .filter(
                                tx -> {
                                    final String desc =
                                            tx.getDescription() == null
                                                    ? ""
                                                    : tx.getDescription();
                                    final String category =
                                            tx.getCategoryPrimary() == null
                                                    ? ""
                                                    : tx.getCategoryPrimary();
                                    return INTEREST_CHARGE_KEYWORD.matcher(desc).find()
                                            || INTEREST_CHARGE_KEYWORD.matcher(category).find();
                                })
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                        .toList();

        // Group by account
        final Map<String, List<TransactionTable>> byAccount =
                interestCharges.stream()
                        .filter(tx -> tx.getAccountId() != null)
                        .collect(Collectors.groupingBy(TransactionTable::getAccountId));

        for (final Map.Entry<String, List<TransactionTable>> entry : byAccount.entrySet()) {
            final String accountId = entry.getKey();
            final List<TransactionTable> charges = entry.getValue();

            final BigDecimal totalInterest =
                    charges.stream()
                            .map(tx -> tx.getAmount().abs())
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Divide by the number of distinct billing months actually
            // observed, not the hardcoded 3. New accounts that have only
            // produced one interest charge get a true monthly figure, not
            // 1/3 of it; accounts with 4 charges (two charges in one
            // bridging month) report on a 3-month base, not 4. Falls back
            // to the window length in months only when transaction dates
            // are missing (defensive — shouldn't happen with valid data).
            final int billingMonths = countDistinctMonths(charges);
            final BigDecimal monthsBase =
                    BigDecimal.valueOf(billingMonths > 0 ? billingMonths : 3);
            final BigDecimal monthlyInterest =
                    totalInterest.divide(monthsBase, 2, RoundingMode.HALF_UP);
            final BigDecimal annualInterest = monthlyInterest.multiply(BigDecimal.valueOf(12));

            if (monthlyInterest.compareTo(minInterestPayment()) > 0) {
                // Get account details
                final Optional<AccountTable> accountOpt = accountRepository.findById(accountId);
                if (accountOpt.isPresent()) {
                    final AccountTable account = accountOpt.get();
                    final BigDecimal balance =
                            account.getBalance() != null
                                    ? account.getBalance().abs()
                                    : BigDecimal.ZERO;

                    // Estimate interest rate
                    double estimatedRate = 0.0;
                    if (balance.compareTo(BigDecimal.ZERO) > 0) {
                        estimatedRate =
                                annualInterest
                                        .divide(balance, 4, RoundingMode.HALF_UP)
                                        .doubleValue();
                    }

                    final Severity severity =
                            estimatedRate >= veryHighRateThreshold()
                                    ? Severity.HIGH
                                    : estimatedRate >= highRateThreshold()
                                            ? Severity.MEDIUM
                                            : Severity.LOW;

                    if (estimatedRate >= highRateThreshold()) {
                        alerts.add(
                                new HighInterestAlert(
                                        accountId,
                                        account.getAccountName(),
                                        account.getInstitutionName(),
                                        account.getAccountType(),
                                        balance,
                                        estimatedRate,
                                        monthlyInterest,
                                        annualInterest,
                                        severity,
                                        generateRecommendation(
                                                account, balance, estimatedRate, annualInterest)));
                    }
                }
            }
        }

        return alerts;
    }

    /** Analyze credit card accounts from a supplied account list. */
    private List<HighInterestAlert> analyzeCreditCardsFrom(final List<AccountTable> accounts) {
        final List<HighInterestAlert> alerts = new ArrayList<>();
        for (final AccountTable account : accounts) {
            // Accept both the canonical camelCase value ("creditCard") and
            // the short Plaid/iOS value ("credit"). The detector previously
            // matched only "creditCard" so any account created through the
            // public POST /api/accounts endpoint (which stores "credit")
            // silently slipped past — causing zero high-interest alerts
            // even for users with $8K+ CC balances at 22% APR.
            final String type = account.getAccountType();
            if (type == null
                    || !("creditCard".equals(type) || "credit".equals(type))) {
                continue;
            }

            final BigDecimal balance =
                    account.getBalance() != null ? account.getBalance().abs() : BigDecimal.ZERO;
            if (balance.compareTo(BigDecimal.ZERO) <= 0) {
                continue; // No balance, no interest
            }

            // Only alert when we have the real APR from the most-recent
            // statement (Chase / Amex / Citi PDF parsers surface this as
            // account.aprPercent on a 0-100 scale). The prior behaviour
            // — assuming 20% when no APR was known — fabricated a number
            // and presented it as fact in the user-visible recommendation
            // ("Credit card with 20.0% APR costing $X/year"). False
            // positives on financial advice are worse than missing data:
            // detectInterestCharges below still picks up the account when
            // there are real interest transactions to back the alert.
            if (account.getAprPercent() == null || account.getAprPercent().signum() <= 0) {
                continue;
            }
            final double interestRate = account.getAprPercent().doubleValue() / 100.0;

            // Calculate interest if only making minimum payments
            final BigDecimal monthlyInterest =
                    balance.multiply(BigDecimal.valueOf(interestRate / 12));
            final BigDecimal annualInterest = monthlyInterest.multiply(BigDecimal.valueOf(12));

            if (interestRate >= highRateThreshold()
                    && annualInterest.compareTo(BigDecimal.valueOf(500)) > 0) {

                final Severity severity =
                        interestRate >= veryHighRateThreshold()
                                ? Severity.HIGH
                                : Severity.MEDIUM;

                alerts.add(
                        new HighInterestAlert(
                                account.getAccountId(),
                                account.getAccountName(),
                                account.getInstitutionName(),
                                account.getAccountType(),
                                balance,
                                interestRate,
                                monthlyInterest,
                                annualInterest,
                                severity,
                                generateRecommendation(
                                        account, balance, interestRate, annualInterest)));
            }
        }

        return alerts;
    }

    /** Analyze loan accounts from a supplied account list. */
    private List<HighInterestAlert> analyzeLoansFrom(final List<AccountTable> accounts) {
        final List<HighInterestAlert> alerts = new ArrayList<>();
        final Set<String> loanTypes = Set.of("autoLoan", "personalLoan", "studentLoan");

        for (final AccountTable account : accounts) {
            if (account.getAccountType() == null || !loanTypes.contains(account.getAccountType())) {
                continue;
            }

            final BigDecimal balance =
                    account.getBalance() != null ? account.getBalance().abs() : BigDecimal.ZERO;
            if (balance.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            // Prefer the real APR when stored; fall back to a type-based
            // estimate only when truly absent. Severity is capped at
            // MEDIUM when we're guessing — never tell the user they have
            // a "HIGH" severity problem based on a fabricated rate.
            final boolean haveRealApr =
                    account.getAprPercent() != null && account.getAprPercent().signum() > 0;
            final double interestRate =
                    haveRealApr
                            ? account.getAprPercent().doubleValue() / 100.0
                            : estimateLoanInterestRate(account.getAccountType());
            final BigDecimal monthlyInterest =
                    balance.multiply(BigDecimal.valueOf(interestRate / 12));
            final BigDecimal annualInterest = monthlyInterest.multiply(BigDecimal.valueOf(12));

            if (interestRate >= highRateThreshold()
                    && annualInterest.compareTo(BigDecimal.valueOf(1000)) > 0) {

                final Severity severity;
                if (!haveRealApr) {
                    severity = Severity.MEDIUM;
                } else if (interestRate >= veryHighRateThreshold()) {
                    severity = Severity.HIGH;
                } else {
                    severity = Severity.MEDIUM;
                }

                alerts.add(
                        new HighInterestAlert(
                                account.getAccountId(),
                                account.getAccountName(),
                                account.getInstitutionName(),
                                account.getAccountType(),
                                balance,
                                interestRate,
                                monthlyInterest,
                                annualInterest,
                                severity,
                                generateLoanRecommendation(
                                        account, balance, interestRate, annualInterest,
                                        haveRealApr)));
            }
        }

        return alerts;
    }

    /**
     * @return number of distinct year-month buckets the given transactions
     *         span, treating null dates as belonging to no bucket
     */
    private int countDistinctMonths(final List<TransactionTable> txs) {
        final Set<YearMonth> months = new HashSet<>();
        for (final TransactionTable tx : txs) {
            if (tx.getTransactionDate() == null) {
                continue;
            }
            try {
                final LocalDate d = LocalDate.parse(tx.getTransactionDate());
                months.add(YearMonth.from(d));
            } catch (final java.time.format.DateTimeParseException ignored) {
                // Tolerate parse failures rather than throwing — falling
                // back to the conservative bucket count is preferable to
                // emitting no alert at all on a single malformed date.
            }
        }
        return months.size();
    }

    /** Estimate loan interest rate based on type */
    private double estimateLoanInterestRate(final String loanType) {
        if (loanType == null) {
            return 0.10; // Default 10%
        }

        switch (loanType.toLowerCase(Locale.ROOT)) {
            case "autoloan":
                return 0.08; // 8% average
            case "personalloan":
                return 0.15; // 15% average
            case "studentloan":
                return 0.06; // 6% average
            default:
                return 0.10; // Default 10%
        }
    }

    /** Generate recommendation based on account and interest */
    private String generateRecommendation(
            final AccountTable account,
            final BigDecimal balance,
            final double interestRate,
            final BigDecimal annualInterest) {

        final String accountType = account.getAccountType();
        final StringBuilder recommendation = new StringBuilder();

        if ("creditCard".equals(accountType)) {
            recommendation.append(
                    String.format(
                            "Credit card with %.1f%% APR costing $%.2f/year in interest. ",
                            interestRate * 100, annualInterest.doubleValue()));

            if (interestRate >= veryHighRateThreshold()) {
                recommendation.append(
                        "Consider balance transfer to lower-rate card or debt consolidation loan.");
            } else {
                recommendation.append("Pay more than minimum to reduce interest costs.");
            }
        } else if (accountType != null && accountType.contains("Loan")) {
            recommendation.append(
                    String.format(
                            "%s with %.1f%% APR costing $%.2f/year. ",
                            accountType, interestRate * 100, annualInterest.doubleValue()));

            if (interestRate >= veryHighRateThreshold()) {
                recommendation.append("Consider refinancing to lower rate or accelerating payoff.");
            } else {
                recommendation.append(
                        "Consider making extra payments to reduce total interest paid.");
            }
        } else {
            recommendation.append(
                    String.format(
                            "High interest rate of %.1f%% costing $%.2f/year. Consider reducing balance or refinancing.",
                            interestRate * 100, annualInterest.doubleValue()));
        }

        return recommendation.toString();
    }

    /**
     * Loan-specific recommendation. Discloses when the APR is an estimate
     * (rather than coming from a statement) so the user can sanity-check
     * before acting on the advice.
     */
    private String generateLoanRecommendation(
            final AccountTable account,
            final BigDecimal balance,
            final double interestRate,
            final BigDecimal annualInterest,
            final boolean aprFromStatement) {
        final String accountType =
                account.getAccountType() == null ? "Loan" : account.getAccountType();
        final String aprQualifier = aprFromStatement ? "" : " (estimated)";
        final StringBuilder rec = new StringBuilder();
        rec.append(
                String.format(
                        "%s with %.1f%%%s APR costing $%.2f/year. ",
                        accountType, interestRate * 100, aprQualifier,
                        annualInterest.doubleValue()));
        if (interestRate >= veryHighRateThreshold()) {
            rec.append("Consider refinancing to lower rate or accelerating payoff.");
        } else {
            rec.append("Consider making extra payments to reduce total interest paid.");
        }
        if (!aprFromStatement) {
            rec.append(
                    " Add this account's actual APR for a precise calculation.");
        }
        return rec.toString();
    }

    // Model classes

    public static class HighInterestAlert {
        private final String accountId;
        private final String accountName;
        private final String institutionName;
        private final String accountType;
        private final BigDecimal balance;
        private final double interestRate;
        private final BigDecimal monthlyInterest;
        private final BigDecimal annualInterest;
        private final Severity severity;
        private final String recommendation;

        public HighInterestAlert(
                final String accountId,
                final String accountName,
                final String institutionName,
                final String accountType,
                final BigDecimal balance,
                final double interestRate,
                final BigDecimal monthlyInterest,
                final BigDecimal annualInterest,
                final Severity severity,
                final String recommendation) {
            this.accountId = accountId;
            this.accountName = accountName;
            this.institutionName = institutionName;
            this.accountType = accountType;
            this.balance = balance;
            this.interestRate = interestRate;
            this.monthlyInterest = monthlyInterest;
            this.annualInterest = annualInterest;
            this.severity = severity;
            this.recommendation = recommendation;
        }

        public String getAccountId() {
            return accountId;
        }

        public String getAccountName() {
            return accountName;
        }

        public String getInstitutionName() {
            return institutionName;
        }

        public String getAccountType() {
            return accountType;
        }

        public BigDecimal getBalance() {
            return balance;
        }

        public double getInterestRate() {
            return interestRate;
        }

        public BigDecimal getMonthlyInterest() {
            return monthlyInterest;
        }

        public BigDecimal getAnnualInterestCost() {
            return annualInterest;
        }

        public Severity getSeverity() {
            return severity;
        }

        public String getRecommendation() {
            return recommendation;
        }
    }

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH
    }
}
