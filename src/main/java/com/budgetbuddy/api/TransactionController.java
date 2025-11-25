package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.UserService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticatedPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Transaction REST Controller
 * Migrated to DynamoDB
 * Optimized with pagination to minimize data transfer
 * 
 * Thread-safe with proper error handling
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final UserService userService;

    public TransactionController(TransactionService transactionService, UserService userService) {
        this.transactionService = transactionService;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<TransactionTable>> getTransactions(
            @AuthenticatedPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Validate pagination parameters
        if (page < 0) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Page number must be non-negative");
        }
        if (size < 1 || size > 100) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Page size must be between 1 and 100");
        }

        int skip = page * size;
        List<TransactionTable> transactions = transactionService.getTransactions(user, skip, size);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/range")
    public ResponseEntity<List<TransactionTable>> getTransactionsInRange(
            @AuthenticatedPrincipal UserDetails userDetails,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (startDate == null || endDate == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Start date and end date are required");
        }
        if (startDate.isAfter(endDate)) {
            throw new AppException(ErrorCode.INVALID_DATE_RANGE, "Start date must be before end date");
        }

        List<TransactionTable> transactions = transactionService.getTransactionsInRange(user, startDate, endDate);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/total")
    public ResponseEntity<TotalSpendingResponse> getTotalSpending(
            @AuthenticatedPrincipal UserDetails userDetails,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (startDate == null || endDate == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Start date and end date are required");
        }
        if (startDate.isAfter(endDate)) {
            throw new AppException(ErrorCode.INVALID_DATE_RANGE, "Start date must be before end date");
        }

        BigDecimal total = transactionService.getTotalSpending(user, startDate, endDate);
        return ResponseEntity.ok(new TotalSpendingResponse(total));
    }

    @PostMapping
    public ResponseEntity<TransactionTable> createTransaction(
            @AuthenticatedPrincipal UserDetails userDetails,
            @RequestBody CreateTransactionRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (request == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction request is required");
        }

        if (request.getAccountId() == null || request.getAccountId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Account ID is required");
        }
        if (request.getAmount() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Amount is required");
        }
        if (request.getTransactionDate() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction date is required");
        }
        if (request.getCategory() == null || request.getCategory().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Category is required");
        }

        TransactionTable transaction = transactionService.createTransaction(
                user,
                request.getAccountId(),
                request.getAmount(),
                request.getTransactionDate(),
                request.getDescription(),
                request.getCategory()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(transaction);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTransaction(
            @AuthenticatedPrincipal UserDetails userDetails,
            @PathVariable String id) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        transactionService.deleteTransaction(user, id);
        return ResponseEntity.noContent().build();
    }

    // DTOs
    public static class CreateTransactionRequest {
        private String accountId;
        private BigDecimal amount;
        private LocalDate transactionDate;
        private String description;
        private String category;

        // Getters and setters
        public String getAccountId() { return accountId; }
        public void setAccountId(String accountId) { this.accountId = accountId; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public LocalDate getTransactionDate() { return transactionDate; }
        public void setTransactionDate(LocalDate transactionDate) { this.transactionDate = transactionDate; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
    }

    public static class TotalSpendingResponse {
        private BigDecimal total;

        public TotalSpendingResponse(BigDecimal total) {
            this.total = total;
        }

        public BigDecimal getTotal() { return total; }
        public void setTotal(BigDecimal total) { this.total = total; }
    }
}
