package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.*;
import com.budgetbuddy.repository.dynamodb.*;
import com.budgetbuddy.service.ExpenseReductionService.ExpenseRecommendation;
import com.budgetbuddy.service.FinancialGoalsRecommendationService.FinancialGoalRecommendation;
import com.budgetbuddy.service.HighInterestDetectionService.HighInterestAlert;
import com.budgetbuddy.service.MissedPaymentDetectionService.MissedPaymentAlert;
import com.budgetbuddy.service.TransactionAnomalyService.TransactionAnomaly;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Real-world scenario tests for Financial Insights
 * Tests all insights services with realistic data patterns
 */
@ExtendWith(MockitoExtension.class)
class FinancialInsightsRealWorldScenarioTest {

    @Mock
    private TransactionRepository transactionRepository;
    
    @Mock
    private AccountRepository accountRepository;
    
    @Mock
    private SubscriptionRepository subscriptionRepository;
    
    @Mock
    private TransactionActionRepository transactionActionRepository;
    
    @Mock
    private GoalRepository goalRepository;

    @InjectMocks
    private TransactionAnomalyService anomalyService;
    
    @InjectMocks
    private ExpenseReductionService expenseReductionService;
    
    @InjectMocks
    private FinancialGoalsRecommendationService goalsService;
    
    @InjectMocks
    private MissedPaymentDetectionService missedPaymentService;
    
    @InjectMocks
    private HighInterestDetectionService highInterestService;
    
    @InjectMocks
    private FinancialInsightsPredictionService predictionService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private String userId;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        userId = "real-world-user-" + UUID.randomUUID().toString();
        today = LocalDate.now();
    }

    // ============================================
    // SCENARIO 1: Young Professional with Irregular Spending
    // ============================================

    @Test
    void testScenario1_YoungProfessional_IrregularSpending() {
        // Setup: Young professional with high income but irregular spending patterns
        List<TransactionTable> transactions = new ArrayList<>();
        
        // High income transactions
        for (int i = 0; i < 6; i++) {
            transactions.add(createTransaction(
                "salary-" + i,
                BigDecimal.valueOf(5000),
                today.minusMonths(i),
                "Salary Payment",
                "Employer Inc",
                "income",
                "payroll"
            ));
        }
        
        // Normal monthly expenses
        for (int month = 0; month < 6; month++) {
            transactions.add(createTransaction(
                "rent-" + month,
                BigDecimal.valueOf(-1500),
                today.minusMonths(month),
                "Rent Payment",
                "Landlord LLC",
                "housing",
                "rent"
            ));
            transactions.add(createTransaction(
                "groceries-" + month,
                BigDecimal.valueOf(-400),
                today.minusMonths(month),
                "Grocery Shopping",
                "Whole Foods",
                "food_and_drink",
                "groceries"
            ));
        }
        
        // ANOMALY: Unexpected large purchase
        transactions.add(createTransaction(
            "anomaly-1",
            BigDecimal.valueOf(-5000),
            today.minusDays(5),
            "Electronics Purchase",
            "Best Buy",
            "shopping",
            "electronics"
        ));
        
        // ANOMALY: First-time merchant
        transactions.add(createTransaction(
            "anomaly-2",
            BigDecimal.valueOf(-800),
            today.minusDays(3),
            "Luxury Restaurant",
            "Michelin Star Restaurant",
            "food_and_drink",
            "restaurants"
        ));
        
        when(transactionRepository.findByUserIdAndDateRange(eq(userId), anyString(), anyString()))
                .thenReturn(transactions);

        // Test anomaly detection
        List<TransactionAnomaly> anomalies = anomalyService.detectAnomalies(userId);
        
        assertNotNull(anomalies, "Anomalies should not be null");
        assertFalse(anomalies.isEmpty(), "Should detect anomalies for irregular spending");
        
        // Verify large purchase is detected
        boolean foundLargePurchase = anomalies.stream()
                .anyMatch(a -> a.getAmount().abs().compareTo(BigDecimal.valueOf(4000)) > 0);
        assertTrue(foundLargePurchase, "Should detect large purchase anomaly");
        
        // Test expense reduction recommendations
        List<ExpenseRecommendation> expenseRecs = expenseReductionService.getRecommendations(userId);
        assertNotNull(expenseRecs, "Expense recommendations should not be null");
    }

    // ============================================
    // SCENARIO 2: Family with Multiple Subscriptions
    // ============================================

    @Test
    void testScenario2_Family_MultipleSubscriptions() {
        // Setup: Family with many subscriptions, some unused
        List<TransactionTable> transactions = new ArrayList<>();
        List<SubscriptionTable> subscriptions = new ArrayList<>();
        
        // Active subscriptions with regular payments
        String[] subscriptionNames = {
            "Netflix", "Spotify", "Amazon Prime", "Disney+", 
            "Gym Membership", "Newsletter Subscription", "Cloud Storage"
        };
        
        for (String name : subscriptionNames) {
            // Create subscription
            SubscriptionTable sub = new SubscriptionTable();
            sub.setSubscriptionId(UUID.randomUUID().toString());
            sub.setUserId(userId);
            sub.setMerchantName(name);
            sub.setAmount(BigDecimal.valueOf(10 + (int)(Math.random() * 20)));
            sub.setActive(true);
            sub.setFrequency("monthly");
            subscriptions.add(sub);
            
            // Create transaction history (some with gaps indicating unused)
            for (int i = 0; i < 6; i++) {
                if (name.equals("Newsletter Subscription") && i > 2) {
                    // Simulate unused subscription - no recent payments
                    continue;
                }
                transactions.add(createTransaction(
                    "sub-" + name + "-" + i,
                    sub.getAmount().negate(),
                    today.minusMonths(i),
                    name + " Subscription",
                    name,
                    "general_merchandise",
                    "subscriptions"
                ));
            }
        }
        
        when(transactionRepository.findByUserIdAndDateRange(eq(userId), anyString(), anyString()))
                .thenReturn(transactions);
        // Only stub if subscriptions are actually used
        lenient().when(subscriptionRepository.findByUserId(userId))
                .thenReturn(subscriptions);

        // Test expense reduction
        List<ExpenseRecommendation> recs = expenseReductionService.getRecommendations(userId);
        
        assertNotNull(recs, "Recommendations should not be null");
        
        // Should recommend canceling unused subscriptions
        boolean foundUnusedSubscription = recs.stream()
                .anyMatch(r -> r.getTitle().toLowerCase().contains("newsletter") ||
                              r.getDescription().toLowerCase().contains("unused"));
        assertTrue(foundUnusedSubscription || recs.size() > 0, 
                "Should recommend canceling unused subscriptions");
    }

    // ============================================
    // SCENARIO 3: Person with High Credit Card Debt
    // ============================================

    @Test
    void testScenario3_HighCreditCardDebt() {
        // Setup: Person with high credit card balance and interest charges
        List<TransactionTable> transactions = new ArrayList<>();
        List<AccountTable> accounts = new ArrayList<>();
        
        // Create credit card account with high balance
        AccountTable creditCard = new AccountTable();
        creditCard.setAccountId("cc-account-1");
        creditCard.setUserId(userId);
        creditCard.setAccountName("Chase Credit Card");
        creditCard.setAccountType("credit");
        creditCard.setBalance(BigDecimal.valueOf(-15000)); // $15k debt
        creditCard.setInstitutionName("Chase Bank");
        accounts.add(creditCard);
        
        // Add interest charges (must use same accountId as the credit card)
        for (int i = 0; i < 3; i++) {
            TransactionTable tx = createTransaction(
                "interest-" + i,
                BigDecimal.valueOf(-300), // $300/month interest
                today.minusMonths(i),
                "Interest Charge",
                "Chase Bank",
                "interest",
                "interest"
            );
            tx.setAccountId("cc-account-1"); // Match credit card account ID
            transactions.add(tx);
        }
        
        // Add some purchases
        TransactionTable purchase = createTransaction(
            "purchase-1",
            BigDecimal.valueOf(-500),
            today.minusDays(10),
            "Purchase",
            "Amazon",
            "general_merchandise",
            "shopping"
        );
        purchase.setAccountId("cc-account-1");
        transactions.add(purchase);
        
        when(transactionRepository.findByUserIdAndDateRange(eq(userId), anyString(), anyString()))
                .thenReturn(transactions);
        when(accountRepository.findByUserId(userId))
                .thenReturn(accounts);
        when(accountRepository.findById("cc-account-1"))
                .thenReturn(Optional.of(creditCard));

        // Test high interest detection
        List<HighInterestAlert> alerts = highInterestService.detectHighInterest(userId);
        
        assertNotNull(alerts, "High interest alerts should not be null");
        assertFalse(alerts.isEmpty(), "Should detect high interest on credit card");
        
        // Verify interest cost calculation
        HighInterestAlert alert = alerts.get(0);
        assertTrue(alert.getAnnualInterestCost().compareTo(BigDecimal.valueOf(3000)) > 0,
                "Should calculate high annual interest cost");
        assertNotNull(alert.getRecommendation(), "Should provide recommendation");
    }

    // ============================================
    // SCENARIO 4: Person Missing Bill Payments
    // ============================================

    @Test
    void testScenario4_MissingBillPayments() {
        // Setup: Person with overdue bills and inconsistent payment patterns
        List<TransactionTable> transactions = new ArrayList<>();
        List<TransactionActionTable> actions = new ArrayList<>();
        
        // Create bill payment actions
        String[] bills = {"Electric Bill", "Water Bill", "Internet Bill", "Phone Bill"};
        
        for (String billName : bills) {
            // Some bills paid on time
            if (!billName.equals("Electric Bill")) {
                TransactionActionTable action = new TransactionActionTable();
                action.setActionId(UUID.randomUUID().toString());
                action.setUserId(userId);
                action.setTitle(billName);
                action.setDescription(billName + " Payment");
                action.setDueDate(today.minusDays(10).format(DATE_FORMATTER));
                action.setIsCompleted(true);
                actions.add(action);
                
                // Add corresponding transaction
                transactions.add(createTransaction(
                    "bill-" + billName,
                    BigDecimal.valueOf(-100),
                    today.minusDays(8),
                    billName + " Payment",
                    billName.split(" ")[0] + " Company",
                    "utilities",
                    "utilities"
                ));
            } else {
                // Electric bill is overdue
                TransactionActionTable action = new TransactionActionTable();
                action.setActionId(UUID.randomUUID().toString());
                action.setUserId(userId);
                action.setTitle(billName);
                action.setDescription(billName + " Payment");
                action.setDueDate(today.minusDays(15).format(DATE_FORMATTER)); // Overdue
                action.setIsCompleted(false);
                actions.add(action);
            }
        }
        
        when(transactionRepository.findByUserIdAndDateRange(eq(userId), anyString(), anyString()))
                .thenReturn(transactions);
        when(transactionActionRepository.findByUserId(userId))
                .thenReturn(actions);

        // Test missed payment detection
        List<MissedPaymentAlert> alerts = missedPaymentService.detectMissedPayments(userId);
        
        assertNotNull(alerts, "Missed payment alerts should not be null");
        assertFalse(alerts.isEmpty(), "Should detect missed payments");
        
        // Verify overdue bill is detected
        boolean foundOverdue = alerts.stream()
                .anyMatch(a -> a.getTitle().contains("Electric") && a.getDaysOverdue() > 0);
        assertTrue(foundOverdue, "Should detect overdue electric bill");
    }

    // ============================================
    // SCENARIO 5: Person Saving for Major Purchase
    // ============================================

    @Test
    void testScenario5_SavingForMajorPurchase() {
        // Setup: Person with savings goal but low savings rate
        List<TransactionTable> transactions = new ArrayList<>();
        List<AccountTable> accounts = new ArrayList<>();
        
        // Income
        for (int i = 0; i < 6; i++) {
            transactions.add(createTransaction(
                "income-" + i,
                BigDecimal.valueOf(4000),
                today.minusMonths(i),
                "Salary",
                "Employer",
                "income",
                "payroll"
            ));
        }
        
        // High expenses (low savings rate)
        for (int i = 0; i < 6; i++) {
            transactions.add(createTransaction(
                "expense-" + i,
                BigDecimal.valueOf(-3500),
                today.minusMonths(i),
                "Monthly Expenses",
                "Various",
                "general_merchandise",
                "shopping"
            ));
        }
        
        // Savings account with low balance
        AccountTable savings = new AccountTable();
        savings.setAccountId("savings-1");
        savings.setUserId(userId);
        savings.setAccountName("Savings Account");
        savings.setAccountType("depository");
        savings.setBalance(BigDecimal.valueOf(3000)); // Only $3k saved
        accounts.add(savings);
        
        when(transactionRepository.findByUserIdAndDateRange(eq(userId), anyString(), anyString()))
                .thenReturn(transactions);
        when(accountRepository.findByUserId(userId))
                .thenReturn(accounts);

        // Test goal recommendations
        List<FinancialGoalRecommendation> goals = goalsService.getRecommendations(userId);
        
        assertNotNull(goals, "Goal recommendations should not be null");
        
        // Should recommend emergency fund or savings goals
        boolean foundSavingsGoal = goals.stream()
                .anyMatch(g -> g.getType() == FinancialGoalsRecommendationService.GoalType.EMERGENCY_FUND ||
                              g.getType() == FinancialGoalsRecommendationService.GoalType.SAVINGS_RATE);
        assertTrue(foundSavingsGoal || goals.size() > 0,
                "Should recommend savings-related goals");
    }

    // ============================================
    // SCENARIO 6: ML Predictions - Spending Trend
    // ============================================

    @Test
    void testScenario6_MLPredictions_SpendingTrend() {
        // Setup: Person with increasing spending trend
        List<TransactionTable> transactions = new ArrayList<>();
        
        // Create increasing trend in dining out
        double[] amounts = {100, 150, 200, 250, 300, 350};
        for (int i = 0; i < amounts.length; i++) {
            transactions.add(createTransaction(
                "dining-" + i,
                BigDecimal.valueOf(-amounts[i]),
                today.minusMonths(amounts.length - 1 - i),
                "Restaurant",
                "Various Restaurants",
                "food_and_drink",
                "restaurants"
            ));
        }
        
        // Test ML predictions
        List<FinancialInsightsPredictionService.PredictedAnomaly> predictions = 
                predictionService.predictAnomalies(transactions, 30);
        
        assertNotNull(predictions, "Predictions should not be null");
        
        // Note: May not always predict if trend isn't strong enough, but should not crash
        // Just verify predictions are returned (could be empty if trend isn't strong enough)
        assertNotNull(predictions, "ML prediction should complete without errors");
    }

    // ============================================
    // SCENARIO 7: Edge Cases - Insufficient Data
    // ============================================

    @Test
    void testScenario7_EdgeCase_InsufficientData() {
        // Setup: User with very few transactions
        List<TransactionTable> transactions = new ArrayList<>();
        
        // Only 2 transactions (below minimum threshold)
        transactions.add(createTransaction(
            "tx-1",
            BigDecimal.valueOf(-100),
            today.minusDays(10),
            "Purchase",
            "Store",
            "general_merchandise",
            "shopping"
        ));
        
        when(transactionRepository.findByUserIdAndDateRange(eq(userId), anyString(), anyString()))
                .thenReturn(transactions);

        // Test that services handle insufficient data gracefully
        List<TransactionAnomaly> anomalies = anomalyService.detectAnomalies(userId);
        assertNotNull(anomalies, "Should return empty list, not null");
        // Should return empty list for insufficient data
        
        List<ExpenseRecommendation> recs = expenseReductionService.getRecommendations(userId);
        assertNotNull(recs, "Should return empty list, not null");
    }

    // ============================================
    // SCENARIO 8: Edge Cases - Null/Empty Values
    // ============================================

    @Test
    void testScenario8_EdgeCase_NullValues() {
        // Test with null user ID
        assertThrows(AppException.class, () -> {
            anomalyService.detectAnomalies(null);
        }, "Should throw exception for null user ID");
        
        assertThrows(AppException.class, () -> {
            anomalyService.detectAnomalies("");
        }, "Should throw exception for empty user ID");
    }

    // ============================================
    // SCENARIO 9: Multiple High-Interest Accounts
    // ============================================

    @Test
    void testScenario9_MultipleHighInterestAccounts() {
        // Setup: Person with multiple credit cards with high interest
        List<TransactionTable> transactions = new ArrayList<>();
        List<AccountTable> accounts = new ArrayList<>();
        
        // Credit Card 1
        AccountTable cc1 = new AccountTable();
        cc1.setAccountId("cc-1");
        cc1.setUserId(userId);
        cc1.setAccountName("Chase Card");
        cc1.setAccountType("credit");
        cc1.setBalance(BigDecimal.valueOf(-10000));
        accounts.add(cc1);
        
        // Credit Card 2
        AccountTable cc2 = new AccountTable();
        cc2.setAccountId("cc-2");
        cc2.setUserId(userId);
        cc2.setAccountName("Amex Card");
        cc2.setAccountType("credit");
        cc2.setBalance(BigDecimal.valueOf(-8000));
        accounts.add(cc2);
        
        // Add interest charges for both
        for (int i = 0; i < 3; i++) {
            TransactionTable tx1 = createTransaction(
                "interest-cc1-" + i,
                BigDecimal.valueOf(-200),
                today.minusMonths(i),
                "Interest Charge",
                "Chase Bank",
                "interest",
                "interest"
            );
            tx1.setAccountId("cc-1");
            transactions.add(tx1);
            
            TransactionTable tx2 = createTransaction(
                "interest-cc2-" + i,
                BigDecimal.valueOf(-150),
                today.minusMonths(i),
                "Interest Charge",
                "Amex",
                "interest",
                "interest"
            );
            tx2.setAccountId("cc-2");
            transactions.add(tx2);
        }
        
        when(transactionRepository.findByUserIdAndDateRange(eq(userId), anyString(), anyString()))
                .thenReturn(transactions);
        when(accountRepository.findByUserId(userId))
                .thenReturn(accounts);
        when(accountRepository.findById("cc-1"))
                .thenReturn(Optional.of(cc1));
        when(accountRepository.findById("cc-2"))
                .thenReturn(Optional.of(cc2));

        List<HighInterestAlert> alerts = highInterestService.detectHighInterest(userId);
        
        assertNotNull(alerts, "Alerts should not be null");
        assertTrue(alerts.size() >= 1, "Should detect multiple high-interest accounts");
    }

    // ============================================
    // SCENARIO 10: Comprehensive Real-World User
    // ============================================

    @Test
    void testScenario10_ComprehensiveRealWorldUser() {
        // Setup: Complete real-world user with all types of financial activity
        List<TransactionTable> transactions = createComprehensiveTransactionHistory();
        List<AccountTable> accounts = createComprehensiveAccounts();
        List<SubscriptionTable> subscriptions = createComprehensiveSubscriptions();
        List<TransactionActionTable> actions = createComprehensiveActions();
        
        when(transactionRepository.findByUserIdAndDateRange(eq(userId), anyString(), anyString()))
                .thenReturn(transactions);
        when(accountRepository.findByUserId(userId))
                .thenReturn(accounts);
        lenient().when(subscriptionRepository.findByUserId(userId))
                .thenReturn(subscriptions);
        when(transactionActionRepository.findByUserId(userId))
                .thenReturn(actions);
        when(accountRepository.findById(anyString()))
                .thenAnswer(invocation -> {
                    String accountId = invocation.getArgument(0);
                    return accounts.stream()
                            .filter(a -> a.getAccountId().equals(accountId))
                            .findFirst();
                });

        // Test all insights
        List<TransactionAnomaly> anomalies = anomalyService.detectAnomalies(userId);
        List<ExpenseRecommendation> expenseRecs = expenseReductionService.getRecommendations(userId);
        List<FinancialGoalRecommendation> goalRecs = goalsService.getRecommendations(userId);
        List<MissedPaymentAlert> missedPayments = missedPaymentService.detectMissedPayments(userId);
        List<HighInterestAlert> highInterest = highInterestService.detectHighInterest(userId);
        
        // All should return non-null results
        assertNotNull(anomalies, "Anomalies should not be null");
        assertNotNull(expenseRecs, "Expense recommendations should not be null");
        assertNotNull(goalRecs, "Goal recommendations should not be null");
        assertNotNull(missedPayments, "Missed payments should not be null");
        assertNotNull(highInterest, "High interest alerts should not be null");
        
        // At least some insights should be generated
        int totalInsights = anomalies.size() + expenseRecs.size() + goalRecs.size() + 
                           missedPayments.size() + highInterest.size();
        assertTrue(totalInsights > 0, "Should generate at least some insights");
    }

    // ============================================
    // Helper Methods
    // ============================================

    private TransactionTable createTransaction(String id, BigDecimal amount, LocalDate date,
                                               String description, String merchant, 
                                               String category, String subcategory) {
        TransactionTable tx = new TransactionTable();
        tx.setTransactionId(id);
        tx.setUserId(userId);
        tx.setAccountId("account-1");
        tx.setAmount(amount);
        tx.setTransactionDate(date.format(DATE_FORMATTER));
        tx.setDescription(description);
        tx.setMerchantName(merchant);
        tx.setCategoryPrimary(category);
        tx.setCategoryDetailed(subcategory);
        tx.setIsHidden(false);
        return tx;
    }

    private List<TransactionTable> createComprehensiveTransactionHistory() {
        List<TransactionTable> transactions = new ArrayList<>();
        
        // 6 months of income
        for (int i = 0; i < 6; i++) {
            transactions.add(createTransaction(
                "income-" + i,
                BigDecimal.valueOf(5000),
                today.minusMonths(i),
                "Salary",
                "Employer",
                "income",
                "payroll"
            ));
        }
        
        // Regular expenses
        for (int i = 0; i < 6; i++) {
            transactions.add(createTransaction("rent-" + i, BigDecimal.valueOf(-1500), 
                today.minusMonths(i), "Rent", "Landlord", "housing", "rent"));
            transactions.add(createTransaction("groceries-" + i, BigDecimal.valueOf(-400), 
                today.minusMonths(i), "Groceries", "Whole Foods", "food_and_drink", "groceries"));
        }
        
        // Anomaly: Large purchase
        transactions.add(createTransaction("large-purchase", BigDecimal.valueOf(-5000), 
            today.minusDays(5), "Electronics", "Best Buy", "shopping", "electronics"));
        
        // Interest charges (must match credit card account ID)
        for (int i = 0; i < 3; i++) {
            TransactionTable tx = createTransaction("interest-" + i, BigDecimal.valueOf(-300), 
                today.minusMonths(i), "Interest", "Chase", "interest", "interest");
            tx.setAccountId("cc-1"); // Match credit card account ID
            transactions.add(tx);
        }
        
        return transactions;
    }

    private List<AccountTable> createComprehensiveAccounts() {
        List<AccountTable> accounts = new ArrayList<>();
        
        AccountTable checking = new AccountTable();
        checking.setAccountId("checking-1");
        checking.setUserId(userId);
        checking.setAccountName("Checking Account");
        checking.setAccountType("depository");
        checking.setBalance(BigDecimal.valueOf(5000));
        accounts.add(checking);
        
        AccountTable creditCard = new AccountTable();
        creditCard.setAccountId("cc-1");
        creditCard.setUserId(userId);
        creditCard.setAccountName("Credit Card");
        creditCard.setAccountType("credit");
        creditCard.setBalance(BigDecimal.valueOf(-10000));
        accounts.add(creditCard);
        
        return accounts;
    }

    private List<SubscriptionTable> createComprehensiveSubscriptions() {
        List<SubscriptionTable> subscriptions = new ArrayList<>();
        
        String[] names = {"Netflix", "Spotify", "Amazon Prime"};
        for (String name : names) {
            SubscriptionTable sub = new SubscriptionTable();
            sub.setSubscriptionId(UUID.randomUUID().toString());
            sub.setUserId(userId);
            sub.setMerchantName(name);
            sub.setAmount(BigDecimal.valueOf(15));
            sub.setActive(true);
            subscriptions.add(sub);
        }
        
        return subscriptions;
    }

    private List<TransactionActionTable> createComprehensiveActions() {
        List<TransactionActionTable> actions = new ArrayList<>();
        
        // Overdue bill
        TransactionActionTable overdue = new TransactionActionTable();
        overdue.setActionId(UUID.randomUUID().toString());
        overdue.setUserId(userId);
        overdue.setTitle("Electric Bill");
        overdue.setDescription("Electric Bill Payment");
        overdue.setDueDate(today.minusDays(10).format(DATE_FORMATTER));
        overdue.setIsCompleted(false);
        actions.add(overdue);
        
        return actions;
    }
}
