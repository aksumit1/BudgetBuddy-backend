package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.*;
import com.budgetbuddy.service.ExpenseReductionService.ExpenseRecommendation;
import com.budgetbuddy.service.FinancialGoalsRecommendationService.FinancialGoalRecommendation;
import com.budgetbuddy.service.HighInterestDetectionService.HighInterestAlert;
import com.budgetbuddy.service.MissedPaymentDetectionService.MissedPaymentAlert;
import com.budgetbuddy.service.TransactionAnomalyService.TransactionAnomaly;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.SubscriptionRepository;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.SubscriptionTable;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Financial Insights REST Controller
 * 
 * Provides advanced financial intelligence including:
 * - Transaction anomaly detection
 * - Expense reduction recommendations
 * - Financial goals recommendations
 * - Missed payment detection
 * - High interest detection
 */
@RestController
@RequestMapping("/api/insights")
public class FinancialInsightsController {

    private final TransactionAnomalyService anomalyService;
    private final ExpenseReductionService expenseReductionService;
    private final FinancialGoalsRecommendationService goalsService;
    private final MissedPaymentDetectionService missedPaymentService;
    private final HighInterestDetectionService highInterestService;
    private final UserService userService;
    private final FinancialInsightsPredictionService predictionService;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final SubscriptionRepository subscriptionRepository;

    public FinancialInsightsController(
            final TransactionAnomalyService anomalyService,
            final ExpenseReductionService expenseReductionService,
            final FinancialGoalsRecommendationService goalsService,
            final MissedPaymentDetectionService missedPaymentService,
            final HighInterestDetectionService highInterestService,
            final UserService userService,
            final FinancialInsightsPredictionService predictionService,
            final TransactionRepository transactionRepository,
            final AccountRepository accountRepository,
            final SubscriptionRepository subscriptionRepository) {
        this.anomalyService = anomalyService;
        this.expenseReductionService = expenseReductionService;
        this.goalsService = goalsService;
        this.missedPaymentService = missedPaymentService;
        this.highInterestService = highInterestService;
        this.userService = userService;
        this.predictionService = predictionService;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    /**
     * Get transaction anomalies
     * GET /api/insights/anomalies
     */
    @GetMapping("/anomalies")
    public ResponseEntity<List<Map<String, Object>>> getAnomalies(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        List<TransactionAnomaly> anomalies = anomalyService.detectAnomalies(user.getUserId());
        
        List<Map<String, Object>> response = anomalies.stream()
                .map(this::toAnomalyMap)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Get expense reduction recommendations
     * GET /api/insights/expense-reductions
     */
    @GetMapping("/expense-reductions")
    public ResponseEntity<List<Map<String, Object>>> getExpenseReductions(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        List<ExpenseRecommendation> recommendations = expenseReductionService.getRecommendations(user.getUserId());
        
        List<Map<String, Object>> response = recommendations.stream()
                .map(this::toExpenseRecommendationMap)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Get financial goal recommendations
     * GET /api/insights/goal-recommendations
     */
    @GetMapping("/goal-recommendations")
    public ResponseEntity<List<Map<String, Object>>> getGoalRecommendations(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        List<FinancialGoalRecommendation> recommendations = goalsService.getRecommendations(user.getUserId());
        
        List<Map<String, Object>> response = recommendations.stream()
                .map(this::toGoalRecommendationMap)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Get missed payment alerts
     * GET /api/insights/missed-payments
     */
    @GetMapping("/missed-payments")
    public ResponseEntity<List<Map<String, Object>>> getMissedPayments(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        List<MissedPaymentAlert> alerts = missedPaymentService.detectMissedPayments(user.getUserId());
        
        List<Map<String, Object>> response = alerts.stream()
                .map(this::toMissedPaymentMap)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Get high interest alerts
     * GET /api/insights/high-interest
     */
    @GetMapping("/high-interest")
    public ResponseEntity<List<Map<String, Object>>> getHighInterest(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        List<HighInterestAlert> alerts = highInterestService.detectHighInterest(user.getUserId());
        
        List<Map<String, Object>> response = alerts.stream()
                .map(this::toHighInterestMap)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Get all insights summary
     * GET /api/insights/summary
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getInsightsSummary(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        Map<String, Object> summary = new HashMap<>();
        
        // Get counts for each insight type
        List<TransactionAnomaly> anomalies = anomalyService.detectAnomalies(user.getUserId());
        List<ExpenseRecommendation> expenseReductions = expenseReductionService.getRecommendations(user.getUserId());
        List<FinancialGoalRecommendation> goalRecommendations = goalsService.getRecommendations(user.getUserId());
        List<MissedPaymentAlert> missedPayments = missedPaymentService.detectMissedPayments(user.getUserId());
        List<HighInterestAlert> highInterest = highInterestService.detectHighInterest(user.getUserId());

        summary.put("anomaliesCount", anomalies.size());
        summary.put("expenseReductionsCount", expenseReductions.size());
        summary.put("goalRecommendationsCount", goalRecommendations.size());
        summary.put("missedPaymentsCount", missedPayments.size());
        summary.put("highInterestCount", highInterest.size());

        // Calculate total potential savings
        double totalSavings = expenseReductions.stream()
                .mapToDouble(r -> r.getAnnualSavings().doubleValue())
                .sum();
        summary.put("totalPotentialSavings", totalSavings);

        // Count high priority items
        long highPriorityAnomalies = anomalies.stream()
                .filter(a -> a.getSeverity() == TransactionAnomalyService.Severity.HIGH)
                .count();
        long highPriorityMissedPayments = missedPayments.stream()
                .filter(a -> a.getSeverity() == MissedPaymentDetectionService.Severity.HIGH)
                .count();
        long highPriorityHighInterest = highInterest.stream()
                .filter(a -> a.getSeverity() == HighInterestDetectionService.Severity.HIGH)
                .count();

        summary.put("highPriorityCount", highPriorityAnomalies + highPriorityMissedPayments + highPriorityHighInterest);

        return ResponseEntity.ok(summary);
    }

    /**
     * Get ML-based predictions for transaction anomalies
     * GET /api/insights/predictions/anomalies?daysAhead=30
     */
    @GetMapping("/predictions/anomalies")
    public ResponseEntity<List<Map<String, Object>>> getPredictedAnomalies(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "30") final int daysAhead) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Get historical transactions
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusMonths(6); // 6 months of history
        String startStr = startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        String endStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        
        List<TransactionTable> historicalTransactions = transactionRepository
                .findByUserIdAndDateRange(user.getUserId(), startStr, endStr);

        List<FinancialInsightsPredictionService.PredictedAnomaly> predictions = 
                predictionService.predictAnomalies(historicalTransactions, daysAhead);
        
        List<Map<String, Object>> response = predictions.stream()
                .map(this::toPredictedAnomalyMap)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Get ML-based predictions for expense reductions
     * GET /api/insights/predictions/expense-reductions
     */
    @GetMapping("/predictions/expense-reductions")
    public ResponseEntity<List<Map<String, Object>>> getPredictedExpenseReductions(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Get historical transactions
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusMonths(6);
        String startStr = startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        String endStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        
        List<TransactionTable> historicalTransactions = transactionRepository
                .findByUserIdAndDateRange(user.getUserId(), startStr, endStr);

        // Get current subscriptions
        List<SubscriptionTable> subscriptions = subscriptionRepository.findByUserId(user.getUserId());
        Map<String, BigDecimal> subscriptionMap = new HashMap<>();
        for (SubscriptionTable sub : subscriptions) {
            if (sub.getActive() != null && sub.getActive() && sub.getAmount() != null) {
                String name = sub.getMerchantName() != null ? sub.getMerchantName() : "Unknown";
                subscriptionMap.put(name, sub.getAmount());
            }
        }

        List<FinancialInsightsPredictionService.PredictedExpenseReduction> predictions = 
                predictionService.predictExpenseReductions(historicalTransactions, subscriptionMap);
        
        List<Map<String, Object>> response = predictions.stream()
                .map(this::toPredictedExpenseReductionMap)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Get ML-based predictions for goal achievements
     * GET /api/insights/predictions/goal-achievements
     */
    @GetMapping("/predictions/goal-achievements")
    public ResponseEntity<List<Map<String, Object>>> getPredictedGoalAchievements(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Get historical transactions
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusMonths(6);
        String startStr = startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        String endStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        
        List<TransactionTable> historicalTransactions = transactionRepository
                .findByUserIdAndDateRange(user.getUserId(), startStr, endStr);

        // Get goals (simplified - in production, fetch from GoalRepository)
        Map<String, FinancialInsightsPredictionService.GoalData> goals = new HashMap<>();
        // TODO: Fetch actual goals from repository
        // For now, return empty predictions if no goals

        List<FinancialInsightsPredictionService.PredictedGoalAchievement> predictions = 
                predictionService.predictGoalAchievements(goals, historicalTransactions);
        
        List<Map<String, Object>> response = predictions.stream()
                .map(this::toPredictedGoalAchievementMap)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Get ML-based predictions for missed payments
     * GET /api/insights/predictions/missed-payments
     */
    @GetMapping("/predictions/missed-payments")
    public ResponseEntity<List<Map<String, Object>>> getPredictedMissedPayments(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Get historical transactions
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusMonths(6);
        String startStr = startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        String endStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        
        List<TransactionTable> historicalTransactions = transactionRepository
                .findByUserIdAndDateRange(user.getUserId(), startStr, endStr);

        // Build payment patterns from recurring transactions
        Map<String, FinancialInsightsPredictionService.PaymentPattern> patterns = new HashMap<>();
        // TODO: Extract payment patterns from TransactionActionTable or recurring transactions

        List<FinancialInsightsPredictionService.PredictedMissedPayment> predictions = 
                predictionService.predictMissedPayments(historicalTransactions, patterns);
        
        List<Map<String, Object>> response = predictions.stream()
                .map(this::toPredictedMissedPaymentMap)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Get ML-based predictions for interest costs
     * GET /api/insights/predictions/interest-costs
     */
    @GetMapping("/predictions/interest-costs")
    public ResponseEntity<List<Map<String, Object>>> getPredictedInterestCosts(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Get historical transactions
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusMonths(6);
        String startStr = startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        String endStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        
        List<TransactionTable> historicalTransactions = transactionRepository
                .findByUserIdAndDateRange(user.getUserId(), startStr, endStr);

        // Get accounts and calculate interest rates from transactions
        List<AccountTable> accounts = accountRepository.findByUserId(user.getUserId());
        Map<String, FinancialInsightsPredictionService.AccountData> accountMap = new HashMap<>();
        for (AccountTable account : accounts) {
            BigDecimal balance = account.getBalance() != null ? account.getBalance().abs() : BigDecimal.ZERO;
            if (balance.compareTo(BigDecimal.ZERO) <= 0) continue;
            
            // Calculate interest rate from interest charges in transactions
            List<TransactionTable> accountTx = historicalTransactions.stream()
                    .filter(tx -> account.getAccountId().equals(tx.getAccountId()))
                    .collect(Collectors.toList());
            
            List<TransactionTable> interestCharges = accountTx.stream()
                    .filter(tx -> {
                        String desc = tx.getDescription() != null ? tx.getDescription().toLowerCase() : "";
                        String category = tx.getCategoryPrimary() != null ? tx.getCategoryPrimary().toLowerCase() : "";
                        return (desc.contains("interest") || desc.contains("finance charge") || category.contains("interest"))
                                && tx.getAmount() != null && tx.getAmount().compareTo(BigDecimal.ZERO) < 0;
                    })
                    .collect(Collectors.toList());
            
            if (!interestCharges.isEmpty()) {
                BigDecimal totalInterest = interestCharges.stream()
                        .map(tx -> tx.getAmount().abs())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                
                // Estimate monthly interest (assuming 3 months of data)
                BigDecimal monthlyInterest = totalInterest.divide(BigDecimal.valueOf(Math.max(1, interestCharges.size() / 3.0)), 2, java.math.RoundingMode.HALF_UP);
                BigDecimal annualInterest = monthlyInterest.multiply(BigDecimal.valueOf(12));
                
                // Calculate interest rate
                double rate = balance.compareTo(BigDecimal.ZERO) > 0
                        ? annualInterest.divide(balance, 4, java.math.RoundingMode.HALF_UP).doubleValue()
                        : 0.0;
                
                if (rate > 0.01) { // At least 1% interest rate
                    String name = account.getAccountName() != null ? account.getAccountName() : "Unknown";
                    accountMap.put(account.getAccountId(), 
                            new FinancialInsightsPredictionService.AccountData(name, balance, rate));
                }
            }
        }

        List<FinancialInsightsPredictionService.PredictedInterestCost> predictions = 
                predictionService.predictInterestCosts(accountMap, historicalTransactions);
        
        List<Map<String, Object>> response = predictions.stream()
                .map(this::toPredictedInterestCostMap)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Get all ML predictions summary
     * GET /api/insights/predictions/summary
     */
    @GetMapping("/predictions/summary")
    public ResponseEntity<Map<String, Object>> getPredictionsSummary(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        Map<String, Object> summary = new HashMap<>();
        
        // Get all predictions
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusMonths(6);
        String startStr = startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        String endStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        
        List<TransactionTable> historicalTransactions = transactionRepository
                .findByUserIdAndDateRange(user.getUserId(), startStr, endStr);

        List<FinancialInsightsPredictionService.PredictedAnomaly> predictedAnomalies = 
                predictionService.predictAnomalies(historicalTransactions, 30);
        
        List<SubscriptionTable> subscriptions = subscriptionRepository.findByUserId(user.getUserId());
        Map<String, BigDecimal> subscriptionMap = new HashMap<>();
        for (SubscriptionTable sub : subscriptions) {
            if (sub.getActive() != null && sub.getActive() && sub.getAmount() != null) {
                String name = sub.getMerchantName() != null ? sub.getMerchantName() : "Unknown";
                subscriptionMap.put(name, sub.getAmount());
            }
        }
        List<FinancialInsightsPredictionService.PredictedExpenseReduction> predictedExpenseReductions = 
                predictionService.predictExpenseReductions(historicalTransactions, subscriptionMap);

        List<AccountTable> accounts = accountRepository.findByUserId(user.getUserId());
        Map<String, FinancialInsightsPredictionService.AccountData> accountMap = new HashMap<>();
        for (AccountTable account : accounts) {
            BigDecimal balance = account.getBalance() != null ? account.getBalance().abs() : BigDecimal.ZERO;
            if (balance.compareTo(BigDecimal.ZERO) <= 0) continue;
            
            // Calculate interest rate from interest charges in transactions
            List<TransactionTable> accountTx = historicalTransactions.stream()
                    .filter(tx -> account.getAccountId().equals(tx.getAccountId()))
                    .collect(Collectors.toList());
            
            List<TransactionTable> interestCharges = accountTx.stream()
                    .filter(tx -> {
                        String desc = tx.getDescription() != null ? tx.getDescription().toLowerCase() : "";
                        String category = tx.getCategoryPrimary() != null ? tx.getCategoryPrimary().toLowerCase() : "";
                        return (desc.contains("interest") || desc.contains("finance charge") || category.contains("interest"))
                                && tx.getAmount() != null && tx.getAmount().compareTo(BigDecimal.ZERO) < 0;
                    })
                    .collect(Collectors.toList());
            
            if (!interestCharges.isEmpty()) {
                BigDecimal totalInterest = interestCharges.stream()
                        .map(tx -> tx.getAmount().abs())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                
                // Estimate monthly interest
                BigDecimal monthlyInterest = totalInterest.divide(BigDecimal.valueOf(Math.max(1, interestCharges.size() / 3.0)), 2, java.math.RoundingMode.HALF_UP);
                BigDecimal annualInterest = monthlyInterest.multiply(BigDecimal.valueOf(12));
                
                // Calculate interest rate
                double rate = balance.compareTo(BigDecimal.ZERO) > 0
                        ? annualInterest.divide(balance, 4, java.math.RoundingMode.HALF_UP).doubleValue()
                        : 0.0;
                
                if (rate > 0.01) { // At least 1% interest rate
                    String name = account.getAccountName() != null ? account.getAccountName() : "Unknown";
                    accountMap.put(account.getAccountId(), 
                            new FinancialInsightsPredictionService.AccountData(name, balance, rate));
                }
            }
        }
        List<FinancialInsightsPredictionService.PredictedInterestCost> predictedInterestCosts = 
                predictionService.predictInterestCosts(accountMap, historicalTransactions);

        summary.put("predictedAnomaliesCount", predictedAnomalies.size());
        summary.put("predictedExpenseReductionsCount", predictedExpenseReductions.size());
        summary.put("predictedInterestCostsCount", predictedInterestCosts.size());
        
        // Calculate total predicted savings
        double totalPredictedSavings = predictedExpenseReductions.stream()
                .mapToDouble(r -> r.getAnnualSavings().doubleValue())
                .sum();
        summary.put("totalPredictedSavings", totalPredictedSavings);
        
        // Calculate total predicted interest costs
        double totalPredictedInterest = predictedInterestCosts.stream()
                .mapToDouble(c -> c.getAnnualInterest().doubleValue())
                .sum();
        summary.put("totalPredictedInterest", totalPredictedInterest);

        return ResponseEntity.ok(summary);
    }

    // Helper methods to convert to maps

    private Map<String, Object> toPredictedAnomalyMap(final FinancialInsightsPredictionService.PredictedAnomaly prediction) {
        Map<String, Object> map = new HashMap<>();
        map.put("category", prediction.getCategory());
        map.put("predictedAmount", prediction.getPredictedAmount());
        map.put("historicalAverage", prediction.getHistoricalAverage());
        map.put("confidence", prediction.getConfidence());
        map.put("reason", prediction.getReason());
        map.put("predictedDate", prediction.getPredictedDate().toString());
        return map;
    }

    private Map<String, Object> toPredictedExpenseReductionMap(final FinancialInsightsPredictionService.PredictedExpenseReduction prediction) {
        Map<String, Object> map = new HashMap<>();
        map.put("expenseName", prediction.getExpenseName());
        map.put("monthlySavings", prediction.getMonthlySavings());
        map.put("annualSavings", prediction.getAnnualSavings());
        map.put("probability", prediction.getProbability());
        map.put("reason", prediction.getReason());
        map.put("predictedDate", prediction.getPredictedDate().toString());
        return map;
    }

    private Map<String, Object> toPredictedGoalAchievementMap(final FinancialInsightsPredictionService.PredictedGoalAchievement prediction) {
        Map<String, Object> map = new HashMap<>();
        map.put("goalId", prediction.getGoalId());
        map.put("goalName", prediction.getGoalName());
        map.put("currentAmount", prediction.getCurrentAmount());
        map.put("targetAmount", prediction.getTargetAmount());
        map.put("predictedDate", prediction.getPredictedDate().toString());
        map.put("achievementProbability", prediction.getAchievementProbability());
        map.put("monthlySavings", prediction.getMonthlySavings());
        map.put("remaining", prediction.getRemaining());
        map.put("savingsRate", prediction.getSavingsRate());
        return map;
    }

    private Map<String, Object> toPredictedMissedPaymentMap(final FinancialInsightsPredictionService.PredictedMissedPayment prediction) {
        Map<String, Object> map = new HashMap<>();
        map.put("paymentName", prediction.getPaymentName());
        map.put("dueDate", prediction.getDueDate().toString());
        map.put("amount", prediction.getAmount());
        map.put("riskProbability", prediction.getRiskProbability());
        map.put("reason", prediction.getReason());
        map.put("daysUntilDue", prediction.getDaysUntilDue());
        return map;
    }

    private Map<String, Object> toPredictedInterestCostMap(final FinancialInsightsPredictionService.PredictedInterestCost prediction) {
        Map<String, Object> map = new HashMap<>();
        map.put("accountId", prediction.getAccountId());
        map.put("accountName", prediction.getAccountName());
        map.put("interestRate", prediction.getInterestRate());
        map.put("currentBalance", prediction.getCurrentBalance());
        map.put("predictedBalance", prediction.getPredictedBalance());
        map.put("monthlyInterest", prediction.getMonthlyInterest());
        map.put("annualInterest", prediction.getAnnualInterest());
        map.put("potentialSavings", prediction.getPotentialSavings());
        map.put("confidence", prediction.getConfidence());
        map.put("trend", prediction.getTrend());
        return map;
    }

    private Map<String, Object> toAnomalyMap(final TransactionAnomaly anomaly) {
        Map<String, Object> map = new HashMap<>();
        map.put("transactionId", anomaly.getTransactionId());
        map.put("amount", anomaly.getAmount());
        map.put("description", anomaly.getDescription());
        map.put("merchantName", anomaly.getMerchantName());
        map.put("transactionDate", anomaly.getTransactionDate());
        map.put("category", anomaly.getCategory());
        map.put("type", anomaly.getType().name());
        map.put("severity", anomaly.getSeverity().name());
        map.put("reason", anomaly.getReason());
        return map;
    }

    private Map<String, Object> toExpenseRecommendationMap(final ExpenseRecommendation rec) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", rec.getType().name());
        map.put("title", rec.getTitle());
        map.put("monthlySavings", rec.getMonthlySavings());
        map.put("annualSavings", rec.getAnnualSavings());
        map.put("description", rec.getDescription());
        map.put("priority", rec.getPriority().name());
        map.put("category", rec.getCategory());
        map.put("entityId", rec.getEntityId());
        return map;
    }

    private Map<String, Object> toGoalRecommendationMap(final FinancialGoalRecommendation rec) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", rec.getType().name());
        map.put("title", rec.getTitle());
        map.put("description", rec.getDescription());
        map.put("currentAmount", rec.getCurrentAmount());
        map.put("targetAmount", rec.getTargetAmount());
        map.put("targetDate", rec.getTargetDate().toString());
        map.put("priority", rec.getPriority().name());
        map.put("actionPlan", rec.getActionPlan());
        map.put("gap", rec.getGap());
        return map;
    }

    private Map<String, Object> toMissedPaymentMap(final MissedPaymentAlert alert) {
        Map<String, Object> map = new HashMap<>();
        map.put("actionId", alert.getActionId());
        map.put("title", alert.getTitle());
        map.put("description", alert.getDescription());
        map.put("dueDate", alert.getDueDate().toString());
        map.put("daysOverdue", alert.getDaysOverdue());
        map.put("type", alert.getType().name());
        map.put("severity", alert.getSeverity().name());
        map.put("message", alert.getMessage());
        map.put("amount", alert.getAmount());
        return map;
    }

    private Map<String, Object> toHighInterestMap(final HighInterestAlert alert) {
        Map<String, Object> map = new HashMap<>();
        map.put("accountId", alert.getAccountId());
        map.put("accountName", alert.getAccountName());
        map.put("institutionName", alert.getInstitutionName());
        map.put("accountType", alert.getAccountType());
        map.put("balance", alert.getBalance());
        map.put("interestRate", alert.getInterestRate());
        map.put("monthlyInterest", alert.getMonthlyInterest());
        map.put("annualInterestCost", alert.getAnnualInterestCost());
        map.put("severity", alert.getSeverity().name());
        map.put("recommendation", alert.getRecommendation());
        return map;
    }
}
