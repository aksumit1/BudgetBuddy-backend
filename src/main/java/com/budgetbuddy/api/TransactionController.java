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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Transaction REST Controller
 * Optimized with pagination to minimize data transfer
 *
 * Thread-safe with proper error handling
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final UserService userService;

    public TransactionController(final TransactionService transactionService, final UserService userService) {
        this.transactionService = transactionService;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<TransactionTable>> getTransactions(
            @AuthenticationPrincipal UserDetails userDetails,
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
            @AuthenticationPrincipal UserDetails userDetails,
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
            @AuthenticationPrincipal UserDetails userDetails,
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

    @GetMapping("/{id}")
    public ResponseEntity<TransactionTable> getTransaction(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String id,
            @RequestParam(required = false) String plaidTransactionId) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Pass plaidTransactionId for fallback lookup if UUID doesn't match
        TransactionTable transaction = transactionService.getTransaction(user, id, plaidTransactionId);
        return ResponseEntity.ok(transaction);
    }

    @PostMapping
    public ResponseEntity<TransactionTable> createTransaction(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody CreateTransactionRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (request == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction request is required");
        }

        // CRITICAL FIX: Account ID is now optional - if not provided, backend will use pseudo account
        // No validation needed here - TransactionService will handle it
        if (request.getAmount() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Amount is required");
        }
        if (request.getTransactionDate() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction date is required");
        }
        if (request.getCategoryPrimary() == null || request.getCategoryPrimary().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Category primary is required");
        }

        TransactionTable transaction = transactionService.createTransaction(
                user,
                request.getAccountId(),
                request.getAmount(),
                request.getTransactionDate(),
                request.getDescription(),
                request.getCategoryPrimary(),
                request.getCategoryDetailed(),
                request.getTransactionId(), // Pass optional transactionId from app
                request.getNotes(), // Pass optional notes
                request.getPlaidAccountId(), // Pass optional Plaid account ID for fallback lookup
                request.getPlaidTransactionId(), // Pass optional Plaid transaction ID for fallback lookup and ID consistency
                request.getTransactionType() // Pass optional user-selected transaction type
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(transaction);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TransactionTable> updateTransaction(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String id,
            @RequestBody UpdateTransactionRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }
        if (request == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Update request is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        TransactionTable transaction = transactionService.updateTransaction(
                user,
                id,
                request.getPlaidTransactionId(), // Pass Plaid ID for fallback lookup
                request.getAmount(), // Pass amount (for type changes)
                request.getNotes(),
                request.getCategoryPrimary(),
                request.getCategoryDetailed(),
                request.getIsAudited(), // Pass audit state
                request.getIsHidden(), // Pass hidden state
                request.getTransactionType(), // Pass optional user-selected transaction type
                false // Don't clear notes if null - preserve existing when doing partial updates
        );

        return ResponseEntity.ok(transaction);
    }

    @PostMapping("/verify")
    public ResponseEntity<TransactionTable> verifyTransaction(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody VerifyTransactionRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (request == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Verify request is required");
        }
        if (request.getTransactionId() == null || request.getTransactionId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Use plaidTransactionId from request body for fallback lookup if UUID doesn't match
        TransactionTable transaction = transactionService.getTransaction(
                user,
                request.getTransactionId(),
                request.getPlaidTransactionId()
        );
        return ResponseEntity.ok(transaction);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTransaction(
            @AuthenticationPrincipal UserDetails userDetails,
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
        private String transactionId; // Optional: If provided, use this ID (for app-backend ID consistency)
        private String accountId;
        private BigDecimal amount;
        private LocalDate transactionDate;
        private String description;
        private String categoryPrimary; // Primary category (required)
        private String categoryDetailed; // Detailed category (optional, defaults to primary if not provided)
        private String notes; // Optional: User notes for the transaction
        private String plaidAccountId; // Optional: Plaid account ID for fallback lookup if accountId not found
        private String plaidTransactionId; // Optional: Plaid transaction ID for fallback lookup and ID consistency
        private String transactionType; // Optional: User-selected transaction type (INCOME, INVESTMENT, LOAN, EXPENSE). If not provided, backend will calculate it.

        // Getters and setters
        public String getTransactionId() { return transactionId; }
        public void setTransactionId(final String transactionId) { this.transactionId = transactionId; }
        public String getAccountId() { return accountId; }
        public void setAccountId(final String accountId) { this.accountId = accountId; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(final BigDecimal amount) { this.amount = amount; }
        public LocalDate getTransactionDate() { return transactionDate; }
        public void setTransactionDate(final LocalDate transactionDate) { this.transactionDate = transactionDate; }
        public String getDescription() { return description; }
        public void setDescription(final String description) { this.description = description; }
        public String getCategoryPrimary() { return categoryPrimary; }
        public void setCategoryPrimary(final String categoryPrimary) { this.categoryPrimary = categoryPrimary; }
        public String getCategoryDetailed() { return categoryDetailed; }
        public void setCategoryDetailed(final String categoryDetailed) { this.categoryDetailed = categoryDetailed; }
        public String getNotes() { return notes; }
        public void setNotes(final String notes) { this.notes = notes; }
        
        public String getPlaidAccountId() { return plaidAccountId; }
        public void setPlaidAccountId(final String plaidAccountId) { this.plaidAccountId = plaidAccountId; }
        
        public String getPlaidTransactionId() { return plaidTransactionId; }
        public void setPlaidTransactionId(final String plaidTransactionId) { this.plaidTransactionId = plaidTransactionId; }
        
        public String getTransactionType() { return transactionType; }
        public void setTransactionType(final String transactionType) { this.transactionType = transactionType; }
    }

    public static class TotalSpendingResponse {
        private BigDecimal total;

        public TotalSpendingResponse(final BigDecimal total) {
            this.total = total;
        }

        public BigDecimal getTotal() { return total; }
        public void setTotal(final BigDecimal total) { this.total = total; }
    }

    public static class UpdateTransactionRequest {
        private BigDecimal amount; // Optional: transaction amount (for type changes)
        private String notes;
        private String categoryPrimary; // Optional: override primary category
        private String categoryDetailed; // Optional: override detailed category
        private String plaidTransactionId; // Optional: for fallback lookup if transactionId not found
        private Boolean isAudited; // Optional: audit checkmark state
        private Boolean isHidden; // Optional: whether transaction is hidden from view
        private String transactionType; // Optional: User-selected transaction type (INCOME, INVESTMENT, LOAN, EXPENSE). If not provided, backend will calculate it.

        public BigDecimal getAmount() { return amount; }
        public void setAmount(final BigDecimal amount) { this.amount = amount; }
        
        public String getNotes() { return notes; }
        public void setNotes(final String notes) { this.notes = notes; }
        
        public String getCategoryPrimary() { return categoryPrimary; }
        public void setCategoryPrimary(final String categoryPrimary) { this.categoryPrimary = categoryPrimary; }
        
        public String getCategoryDetailed() { return categoryDetailed; }
        public void setCategoryDetailed(final String categoryDetailed) { this.categoryDetailed = categoryDetailed; }
        
        public String getPlaidTransactionId() { return plaidTransactionId; }
        public void setPlaidTransactionId(final String plaidTransactionId) { this.plaidTransactionId = plaidTransactionId; }
        
        public Boolean getIsAudited() { return isAudited; }
        public void setIsAudited(final Boolean isAudited) { this.isAudited = isAudited; }
        
        public Boolean getIsHidden() { return isHidden; }
        public void setIsHidden(final Boolean isHidden) { this.isHidden = isHidden; }
        
        public String getTransactionType() { return transactionType; }
        public void setTransactionType(final String transactionType) { this.transactionType = transactionType; }
    }

    public static class VerifyTransactionRequest {
        private String transactionId;
        private String plaidTransactionId; // Optional: Plaid transaction ID in body for fallback lookup

        public String getTransactionId() { return transactionId; }
        public void setTransactionId(final String transactionId) { this.transactionId = transactionId; }
        
        public String getPlaidTransactionId() { return plaidTransactionId; }
        public void setPlaidTransactionId(final String plaidTransactionId) { this.plaidTransactionId = plaidTransactionId; }
    }
}
