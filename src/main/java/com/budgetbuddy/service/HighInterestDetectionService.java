package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * High Interest Detection Service
 * 
 * Identifies accounts with high interest rates and recommends actions to reduce interest costs.
 */
@Service
public class HighInterestDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(HighInterestDetectionService.class);
    
    // Interest rate thresholds
    private static final double HIGH_INTEREST_RATE_THRESHOLD = 0.15; // 15% APR
    private static final double VERY_HIGH_INTEREST_RATE_THRESHOLD = 0.25; // 25% APR
    private static final BigDecimal MIN_INTEREST_PAYMENT = BigDecimal.valueOf(50); // Minimum $50/month interest
    
    private static final int ANALYSIS_WINDOW_DAYS = 90; // Analyze last 90 days
    
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public HighInterestDetectionService(
            final AccountRepository accountRepository,
            final TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Detect high interest payments and provide recommendations
     */
    public List<HighInterestAlert> detectHighInterest(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }

        logger.info("Detecting high interest payments for user: {}", userId);

        List<HighInterestAlert> alerts = new ArrayList<>();

        // 1. Detect interest charges in transactions
        alerts.addAll(detectInterestCharges(userId));

        // 2. Analyze credit card accounts
        alerts.addAll(analyzeCreditCards(userId));

        // 3. Analyze loan accounts
        alerts.addAll(analyzeLoans(userId));

        // Sort by annual interest cost (descending)
        return alerts.stream()
                .sorted(Comparator.comparing(HighInterestAlert::getAnnualInterestCost, Comparator.reverseOrder()))
                .collect(Collectors.toList());
    }

    /**
     * Detect interest charges from transactions
     */
    private List<HighInterestAlert> detectInterestCharges(final String userId) {
        List<HighInterestAlert> alerts = new ArrayList<>();

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(ANALYSIS_WINDOW_DAYS);

        String startDateStr = startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        String endDateStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);

        List<TransactionTable> transactions = transactionRepository
                .findByUserIdAndDateRange(userId, startDateStr, endDateStr);

        // Filter to interest charges
        List<TransactionTable> interestCharges = transactions.stream()
                .filter(tx -> {
                    String desc = tx.getDescription() != null ? tx.getDescription().toLowerCase() : "";
                    String category = tx.getCategoryPrimary() != null ? tx.getCategoryPrimary().toLowerCase() : "";
                    return desc.contains("interest") || 
                           desc.contains("finance charge") ||
                           category.contains("interest");
                })
                .filter(tx -> tx.getAmount() != null && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                .collect(Collectors.toList());

        // Group by account
        Map<String, List<TransactionTable>> byAccount = interestCharges.stream()
                .filter(tx -> tx.getAccountId() != null)
                .collect(Collectors.groupingBy(TransactionTable::getAccountId));

        for (Map.Entry<String, List<TransactionTable>> entry : byAccount.entrySet()) {
            String accountId = entry.getKey();
            List<TransactionTable> charges = entry.getValue();

            BigDecimal totalInterest = charges.stream()
                    .map(tx -> tx.getAmount().abs())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal monthlyInterest = totalInterest.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP); // 3 months
            BigDecimal annualInterest = monthlyInterest.multiply(BigDecimal.valueOf(12));

            if (monthlyInterest.compareTo(MIN_INTEREST_PAYMENT) > 0) {
                // Get account details
                Optional<AccountTable> accountOpt = accountRepository.findById(accountId);
                if (accountOpt.isPresent()) {
                    AccountTable account = accountOpt.get();
                    BigDecimal balance = account.getBalance() != null ? account.getBalance().abs() : BigDecimal.ZERO;

                    // Estimate interest rate
                    double estimatedRate = 0.0;
                    if (balance.compareTo(BigDecimal.ZERO) > 0) {
                        estimatedRate = annualInterest.divide(balance, 4, RoundingMode.HALF_UP).doubleValue();
                    }

                    Severity severity = estimatedRate >= VERY_HIGH_INTEREST_RATE_THRESHOLD 
                            ? Severity.HIGH 
                            : estimatedRate >= HIGH_INTEREST_RATE_THRESHOLD 
                                ? Severity.MEDIUM 
                                : Severity.LOW;

                    if (estimatedRate >= HIGH_INTEREST_RATE_THRESHOLD) {
                        alerts.add(new HighInterestAlert(
                                accountId,
                                account.getAccountName(),
                                account.getInstitutionName(),
                                account.getAccountType(),
                                balance,
                                estimatedRate,
                                monthlyInterest,
                                annualInterest,
                                severity,
                                generateRecommendation(account, balance, estimatedRate, annualInterest)
                        ));
                    }
                }
            }
        }

        return alerts;
    }

    /**
     * Analyze credit card accounts
     */
    private List<HighInterestAlert> analyzeCreditCards(final String userId) {
        List<HighInterestAlert> alerts = new ArrayList<>();

        List<AccountTable> accounts = accountRepository.findByUserId(userId);

        for (AccountTable account : accounts) {
            if (account.getAccountType() == null || !account.getAccountType().equals("creditCard")) {
                continue;
            }

            BigDecimal balance = account.getBalance() != null ? account.getBalance().abs() : BigDecimal.ZERO;
            if (balance.compareTo(BigDecimal.ZERO) <= 0) {
                continue; // No balance, no interest
            }

            // Estimate interest rate (default to 20% for credit cards if not available)
            double interestRate = 0.20; // Default assumption

            // Calculate interest if only making minimum payments
            BigDecimal monthlyInterest = balance.multiply(BigDecimal.valueOf(interestRate / 12));
            BigDecimal annualInterest = monthlyInterest.multiply(BigDecimal.valueOf(12));

            if (interestRate >= HIGH_INTEREST_RATE_THRESHOLD && 
                annualInterest.compareTo(BigDecimal.valueOf(500)) > 0) {
                
                Severity severity = interestRate >= VERY_HIGH_INTEREST_RATE_THRESHOLD 
                        ? Severity.HIGH 
                        : Severity.MEDIUM;

                alerts.add(new HighInterestAlert(
                        account.getAccountId(),
                        account.getAccountName(),
                        account.getInstitutionName(),
                        account.getAccountType(),
                        balance,
                        interestRate,
                        monthlyInterest,
                        annualInterest,
                        severity,
                        generateRecommendation(account, balance, interestRate, annualInterest)
                ));
            }
        }

        return alerts;
    }

    /**
     * Analyze loan accounts
     */
    private List<HighInterestAlert> analyzeLoans(final String userId) {
        List<HighInterestAlert> alerts = new ArrayList<>();

        List<AccountTable> accounts = accountRepository.findByUserId(userId);

        Set<String> loanTypes = Set.of("autoLoan", "personalLoan", "studentLoan");

        for (AccountTable account : accounts) {
            if (account.getAccountType() == null || !loanTypes.contains(account.getAccountType())) {
                continue;
            }

            BigDecimal balance = account.getBalance() != null ? account.getBalance().abs() : BigDecimal.ZERO;
            if (balance.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            // Estimate interest rate based on loan type
            double interestRate = estimateLoanInterestRate(account.getAccountType());
            BigDecimal monthlyInterest = balance.multiply(BigDecimal.valueOf(interestRate / 12));
            BigDecimal annualInterest = monthlyInterest.multiply(BigDecimal.valueOf(12));

            if (interestRate >= HIGH_INTEREST_RATE_THRESHOLD && 
                annualInterest.compareTo(BigDecimal.valueOf(1000)) > 0) {
                
                Severity severity = interestRate >= VERY_HIGH_INTEREST_RATE_THRESHOLD 
                        ? Severity.HIGH 
                        : Severity.MEDIUM;

                alerts.add(new HighInterestAlert(
                        account.getAccountId(),
                        account.getAccountName(),
                        account.getInstitutionName(),
                        account.getAccountType(),
                        balance,
                        interestRate,
                        monthlyInterest,
                        annualInterest,
                        severity,
                        generateRecommendation(account, balance, interestRate, annualInterest)
                ));
            }
        }

        return alerts;
    }

    /**
     * Estimate loan interest rate based on type
     */
    private double estimateLoanInterestRate(final String loanType) {
        if (loanType == null) {
            return 0.10; // Default 10%
        }

        switch (loanType.toLowerCase()) {
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

    /**
     * Generate recommendation based on account and interest
     */
    private String generateRecommendation(
            final AccountTable account,
            final BigDecimal balance,
            final double interestRate,
            final BigDecimal annualInterest) {
        
        String accountType = account.getAccountType();
        StringBuilder recommendation = new StringBuilder();

        if ("creditCard".equals(accountType)) {
            recommendation.append(String.format(
                    "Credit card with %.1f%% APR costing $%.2f/year in interest. ",
                    interestRate * 100, annualInterest.doubleValue()));
            
            if (interestRate >= VERY_HIGH_INTEREST_RATE_THRESHOLD) {
                recommendation.append("Consider balance transfer to lower-rate card or debt consolidation loan.");
            } else {
                recommendation.append("Pay more than minimum to reduce interest costs.");
            }
        } else if (accountType != null && accountType.contains("Loan")) {
            recommendation.append(String.format(
                    "%s with %.1f%% APR costing $%.2f/year. ",
                    accountType, interestRate * 100, annualInterest.doubleValue()));
            
            if (interestRate >= VERY_HIGH_INTEREST_RATE_THRESHOLD) {
                recommendation.append("Consider refinancing to lower rate or accelerating payoff.");
            } else {
                recommendation.append("Consider making extra payments to reduce total interest paid.");
            }
        } else {
            recommendation.append(String.format(
                    "High interest rate of %.1f%% costing $%.2f/year. Consider reducing balance or refinancing.",
                    interestRate * 100, annualInterest.doubleValue()));
        }

        return recommendation.toString();
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

        public String getAccountId() { return accountId; }
        public String getAccountName() { return accountName; }
        public String getInstitutionName() { return institutionName; }
        public String getAccountType() { return accountType; }
        public BigDecimal getBalance() { return balance; }
        public double getInterestRate() { return interestRate; }
        public BigDecimal getMonthlyInterest() { return monthlyInterest; }
        public BigDecimal getAnnualInterestCost() { return annualInterest; }
        public Severity getSeverity() { return severity; }
        public String getRecommendation() { return recommendation; }
    }

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH
    }
}
