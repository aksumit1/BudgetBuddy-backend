package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Account REST Controller
 * Migrated to DynamoDB
 *
 * Thread-safe with proper error handling
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountRepository accountRepository;
    private final UserService userService;

    public AccountController(final AccountRepository accountRepository, final UserService userService) {
        this.accountRepository = accountRepository;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<AccountTable>> getAccounts(@AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is invalid");
        }

        List<AccountTable> accounts = accountRepository.findByUserId(user.getUserId());
        return ResponseEntity.ok(accounts != null ? accounts : List.of());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountTable> getAccount(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String id) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Account ID is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        AccountTable account = accountRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));

        if (account.getUserId() == null || !account.getUserId().equals(user.getUserId())) {
            // Use SECURITY_VIOLATION (8004) which maps to 403 Forbidden via ErrorCode range
            // Preserve error context with user-friendly message and technical details
            java.util.Map<String, Object> context = new java.util.HashMap<>();
            context.put("accountId", account.getAccountId() != null
                    ? account.getAccountId() : "unknown");
            context.put("requestedUserId", account.getUserId() != null
                    ? account.getUserId() : "unknown");
            context.put("authenticatedUserId", user.getUserId() != null
                    ? user.getUserId() : "unknown");
            throw new AppException(ErrorCode.SECURITY_VIOLATION,
                    "Access to account denied: User attempted to access account belonging to another user",
                    context,
                    "You don't have permission to access this account.",
                    null);
        }

        return ResponseEntity.ok(account);
    }

    /**
     * Create account endpoint
     * Allows creating manual accounts for testing and manual entry
     */
    @PostMapping
    public ResponseEntity<AccountTable> createAccount(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody CreateAccountRequest request) {
        if (userDetails == null || userDetails.getUsername() == null || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is invalid");
        }

        // Validate required fields
        if (request == null || request.getAccountName() == null || request.getAccountName().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Account name is required");
        }

        // Create account
        AccountTable account = new AccountTable();
        account.setAccountId(request.getAccountId() != null && !request.getAccountId().isEmpty() 
                ? request.getAccountId() 
                : UUID.randomUUID().toString());
        account.setUserId(user.getUserId());
        account.setAccountName(request.getAccountName());
        account.setInstitutionName(request.getInstitutionName() != null ? request.getInstitutionName() : "Manual");
        account.setAccountType(request.getAccountType() != null ? request.getAccountType() : "OTHER");
        account.setAccountSubtype(request.getAccountSubtype());
        account.setBalance(request.getBalance() != null ? request.getBalance() : BigDecimal.ZERO);
        account.setCurrencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : "USD");
        account.setPlaidAccountId(request.getPlaidAccountId());
        account.setPlaidItemId(request.getPlaidItemId());
        account.setAccountNumber(request.getAccountNumber());
        account.setActive(request.getActive() != null ? request.getActive() : true);
        
        Instant now = Instant.now();
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        account.setLastSyncedAt(now);
        account.setUpdatedAtTimestamp(now.getEpochSecond());

        accountRepository.save(account);
        return ResponseEntity.status(HttpStatus.CREATED).body(account);
    }

    /**
     * Request DTO for creating accounts
     */
    public static class CreateAccountRequest {
        private String accountId;
        private String accountName;
        private String institutionName;
        private String accountType;
        private String accountSubtype;
        private BigDecimal balance;
        private String currencyCode;
        private String plaidAccountId;
        private String plaidItemId;
        private String accountNumber;
        private Boolean active;

        // Getters and setters
        public String getAccountId() { return accountId; }
        public void setAccountId(String accountId) { this.accountId = accountId; }
        
        public String getAccountName() { return accountName; }
        public void setAccountName(String accountName) { this.accountName = accountName; }
        
        public String getInstitutionName() { return institutionName; }
        public void setInstitutionName(String institutionName) { this.institutionName = institutionName; }
        
        public String getAccountType() { return accountType; }
        public void setAccountType(String accountType) { this.accountType = accountType; }
        
        public String getAccountSubtype() { return accountSubtype; }
        public void setAccountSubtype(String accountSubtype) { this.accountSubtype = accountSubtype; }
        
        public BigDecimal getBalance() { return balance; }
        public void setBalance(BigDecimal balance) { this.balance = balance; }
        
        public String getCurrencyCode() { return currencyCode; }
        public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
        
        public String getPlaidAccountId() { return plaidAccountId; }
        public void setPlaidAccountId(String plaidAccountId) { this.plaidAccountId = plaidAccountId; }
        
        public String getPlaidItemId() { return plaidItemId; }
        public void setPlaidItemId(String plaidItemId) { this.plaidItemId = plaidItemId; }
        
        public String getAccountNumber() { return accountNumber; }
        public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
        
        public Boolean getActive() { return active; }
        public void setActive(Boolean active) { this.active = active; }
    }
}
