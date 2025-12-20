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

        // CRITICAL FIX: Check for existing account for idempotency
        String accountId = request.getAccountId() != null && !request.getAccountId().isEmpty() 
                ? request.getAccountId() 
                : UUID.randomUUID().toString();
        
        // Normalize UUID to lowercase for consistency
        if (com.budgetbuddy.util.IdGenerator.isValidUUID(accountId)) {
            accountId = com.budgetbuddy.util.IdGenerator.normalizeUUID(accountId);
            
            // Check if account with this ID already exists
            java.util.Optional<AccountTable> existingById = accountRepository.findById(accountId);
            if (existingById.isPresent()) {
                AccountTable existing = existingById.get();
                // CRITICAL FIX: Verify the existing account belongs to the same user
                // This ensures idempotent behavior - return existing account instead of creating duplicate
                if (existing.getUserId().equals(user.getUserId())) {
                    // Same account (same user) - return existing (idempotent)
                    org.slf4j.LoggerFactory.getLogger(AccountController.class)
                            .info("Account with ID {} already exists for user {}. Returning existing for idempotency.", 
                                    accountId, user.getUserId());
                    return ResponseEntity.status(HttpStatus.OK).body(existing);
                } else {
                    // Account exists but belongs to different user - security issue
                    org.slf4j.LoggerFactory.getLogger(AccountController.class)
                            .warn("Account with ID {} already exists but belongs to different user. Generating new UUID for security.", accountId);
                    // Generate new UUID for security
                    accountId = UUID.randomUUID().toString().toLowerCase();
                }
            }
        } else {
            // Invalid UUID format, generate new one
            accountId = UUID.randomUUID().toString().toLowerCase();
        }

        // Create account
        AccountTable account = new AccountTable();
        account.setAccountId(accountId);
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
     * Update account endpoint
     * Allows updating account properties
     */
    @PutMapping("/{id}")
    public ResponseEntity<AccountTable> updateAccount(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String id,
            @RequestBody UpdateAccountRequest request) {
        if (userDetails == null || userDetails.getUsername() == null || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Account ID is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is invalid");
        }

        // Find existing account
        AccountTable account = accountRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));

        // Verify account belongs to user
        if (account.getUserId() == null || !account.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Account does not belong to user");
        }

        // Update account properties (only update provided fields)
        if (request.getAccountName() != null && !request.getAccountName().isEmpty()) {
            account.setAccountName(request.getAccountName());
        }
        if (request.getInstitutionName() != null) {
            account.setInstitutionName(request.getInstitutionName());
        }
        if (request.getAccountType() != null && !request.getAccountType().isEmpty()) {
            account.setAccountType(request.getAccountType());
        }
        if (request.getAccountSubtype() != null) {
            account.setAccountSubtype(request.getAccountSubtype());
        }
        if (request.getBalance() != null) {
            account.setBalance(request.getBalance());
        }
        if (request.getCurrencyCode() != null) {
            account.setCurrencyCode(request.getCurrencyCode());
        }
        if (request.getPlaidAccountId() != null) {
            account.setPlaidAccountId(request.getPlaidAccountId());
        }
        if (request.getPlaidItemId() != null) {
            account.setPlaidItemId(request.getPlaidItemId());
        }
        if (request.getAccountNumber() != null) {
            account.setAccountNumber(request.getAccountNumber());
        }
        if (request.getActive() != null) {
            account.setActive(request.getActive());
        }

        Instant now = Instant.now();
        account.setUpdatedAt(now);
        account.setUpdatedAtTimestamp(now.getEpochSecond());

        accountRepository.save(account);
        return ResponseEntity.ok(account);
    }

    /**
     * Delete account endpoint
     * Allows deleting an account
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAccount(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String id) {
        if (userDetails == null || userDetails.getUsername() == null || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Account ID is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is invalid");
        }

        // Find existing account
        AccountTable account = accountRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));

        // Verify account belongs to user
        if (account.getUserId() == null || !account.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Account does not belong to user");
        }

        // Delete account
        accountRepository.delete(id);
        return ResponseEntity.noContent().build();
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

    /**
     * Request DTO for updating accounts
     */
    public static class UpdateAccountRequest {
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
