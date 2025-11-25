package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Budget Service
 */
@Service
public class BudgetService {

    private static final Logger logger = LoggerFactory.getLogger(BudgetService.class);

    private final BudgetRepository budgetRepository;
    private final com.budgetbuddy.repository.dynamodb.TransactionRepository transactionRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    public BudgetService(
            final BudgetRepository budgetRepository,
            final com.budgetbuddy.repository.dynamodb.TransactionRepository transactionRepository) {
        this.budgetRepository = budgetRepository;
        this.transactionRepository = transactionRepository;
    }

    public BudgetTable createOrUpdateBudget((final UserTable user, final String category, final BigDecimal monthlyLimit) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (category == null || category.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Category is required");
        }
        if (monthlyLimit == null || monthlyLimit.compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Monthly limit must be positive");
        }

        Optional<BudgetTable> existing = budgetRepository.findByUserIdAndCategory(user.getUserId(), category);

        BudgetTable budget;
        if (existing.isPresent()) {
            budget = existing.get();
            budget.setMonthlyLimit(monthlyLimit);
        } else {
            budget = new BudgetTable();
            budget.setBudgetId(UUID.randomUUID().toString());
            budget.setUserId(user.getUserId());
            budget.setCategory(category);
            budget.setMonthlyLimit(monthlyLimit);
            budget.setCurrentSpent(BigDecimal.ZERO);
            budget.setCurrencyCode(user.getPreferredCurrency() != null && !user.getPreferredCurrency().isEmpty()
                    ? user.getPreferredCurrency() : "USD");
        }

        // Update current spent for current month
        try {
            LocalDate startOfMonth = LocalDate.now().withDayOfMonth(1);
            String startDateStr = startOfMonth.format(DATE_FORMATTER);
            String endDateStr = LocalDate.now().format(DATE_FORMATTER);
            List<TransactionTable> transactions = transactionRepository.findByUserIdAndDateRange(
                    user.getUserId(), startDateStr, endDateStr);

            BigDecimal currentSpent = transactions.stream()
                    .filter(t -> t != null && category.equals(t.getCategory()))
                    .map(TransactionTable::getAmount)
                    .filter(amount -> amount != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            budget.setCurrentSpent(currentSpent);
        } catch (Exception e) {
            logger.warn("Failed to calculate current spent for budget: {}", e.getMessage());
            // Continue with existing currentSpent value
        }

        budgetRepository.save(budget);
        return budget;
    }

    public List<BudgetTable> getBudgets(UserTable user) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        return budgetRepository.findByUserId(user.getUserId());
    }

    public BudgetTable getBudget((final UserTable user, final String budgetId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (budgetId == null || budgetId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Budget ID is required");
        }

        BudgetTable budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new AppException(ErrorCode.BUDGET_NOT_FOUND, "Budget not found"));

        if (budget.getUserId() == null || !budget.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Budget does not belong to user");
        }

        return budget;
    }

    public void deleteBudget((final UserTable user, final String budgetId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (budgetId == null || budgetId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Budget ID is required");
        }

        BudgetTable budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new AppException(ErrorCode.BUDGET_NOT_FOUND, "Budget not found"));

        if (budget.getUserId() == null || !budget.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Budget does not belong to user");
        }

        budgetRepository.delete(budgetId);
    }
}
