package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account REST Controller
 *
 * <p>Thread-safe with proper error handling
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountRepository accountRepository;
    private final UserService userService;
    private final TransactionService transactionService;
    private final com.budgetbuddy.notification.DataChangeNotificationService
            dataChangeNotificationService;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification =
                    "Spring dependency injection - services are singleton beans safe to share")
    public AccountController(
            final AccountRepository accountRepository,
            final UserService userService,
            final TransactionService transactionService,
            final com.budgetbuddy.notification.DataChangeNotificationService
                    dataChangeNotificationService) {
        this.accountRepository = accountRepository;
        this.userService = userService;
        this.transactionService = transactionService;
        this.dataChangeNotificationService = dataChangeNotificationService;
    }

    @GetMapping
    public ResponseEntity<List<AccountTable>> getAccounts(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null
                || userDetails.getUsername() == null
                || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is invalid");
        }

        final List<AccountTable> accounts = accountRepository.findByUserId(user.getUserId());
        // TO DO: Check for each account in the repository for valid User Id, otherwise remove it

        return ResponseEntity.ok(accounts != null ? accounts : List.of());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountTable> getAccount(
            @AuthenticationPrincipal final UserDetails userDetails, @PathVariable final String id) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Account ID is required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        final AccountTable account =
                accountRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));

        if (account.getUserId() == null || !account.getUserId().equals(user.getUserId())) {
            // Use SECURITY_VIOLATION (8004) which maps to 403 Forbidden via ErrorCode range
            // Preserve error context with user-friendly message and technical details
            final java.util.Map<String, Object> context = new java.util.HashMap<>();
            context.put(
                    "accountId",
                    account.getAccountId() != null ? account.getAccountId() : "unknown");
            context.put(
                    "requestedUserId",
                    account.getUserId() != null ? account.getUserId() : "unknown");
            context.put(
                    "authenticatedUserId", user.getUserId() != null ? user.getUserId() : "unknown");
            throw new AppException(
                    ErrorCode.SECURITY_VIOLATION,
                    "Access to account denied: User attempted to access account belonging to another user",
                    context,
                    "You don't have permission to access this account.",
                    null);
        }

        return ResponseEntity.ok(account);
    }

    /** Create account endpoint Allows creating manual accounts for testing and manual entry */
    @PostMapping
    public ResponseEntity<AccountTable> createAccount(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestBody final CreateAccountRequest request) {
        if (userDetails == null
                || userDetails.getUsername() == null
                || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is invalid");
        }

        // TO DO: validate the len of each fields to prevent any buffere overrun scenarios

        // Validate required fields
        if (request == null
                || request.getAccountName() == null
                || request.getAccountName().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Account name is required");
        }

        // TO Do check for length and format of each paramater

        // CRITICAL FIX: Check for existing account for idempotency
        String accountId =
                request.getAccountId() != null && !request.getAccountId().isEmpty()
                        ? request.getAccountId()
                        : UUID.randomUUID().toString();

        // Normalize UUID to lowercase for consistency
        if (com.budgetbuddy.util.IdGenerator.isValidUUID(accountId)) {
            accountId = com.budgetbuddy.util.IdGenerator.normalizeUUID(accountId);

            // Check if account with this ID already exists
            final java.util.Optional<AccountTable> existingById = accountRepository.findById(accountId);
            if (existingById.isPresent()) {
                final AccountTable existing = existingById.get();
                // CRITICAL FIX: Verify the existing account belongs to the same user
                // This ensures idempotent behavior - return existing account instead of creating
                // duplicate
                if (existing.getUserId().equals(user.getUserId())) {
                    // Same account (same user) - return existing (idempotent)
                    org.slf4j.LoggerFactory.getLogger(AccountController.class)
                            .info(
                                    "Account with ID {} already exists for user {}. Returning existing for idempotency.",
                                    accountId,
                                    user.getUserId());
                    return ResponseEntity.status(HttpStatus.OK).body(existing);
                } else {
                    // Account exists but belongs to different user - security issue
                    org.slf4j.LoggerFactory.getLogger(AccountController.class)
                            .warn(
                                    "Account with ID {} already exists but belongs to different user. Generating new UUID for security.",
                                    accountId);
                    // Generate new UUID for security
                    accountId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
                }
            }
        } else {
            // Invalid UUID format, generate new one
            accountId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        }

        // Create account
        final AccountTable account = new AccountTable();
        account.setAccountId(accountId);
        account.setUserId(user.getUserId());
        account.setAccountName(request.getAccountName());
        account.setInstitutionName(
                request.getInstitutionName() != null ? request.getInstitutionName() : "Manual");
        account.setAccountType(
                request.getAccountType() != null ? request.getAccountType() : "OTHER");
        account.setAccountSubtype(request.getAccountSubtype());
        account.setBalance(request.getBalance() != null ? request.getBalance() : BigDecimal.ZERO);
        account.setCurrencyCode(
                request.getCurrencyCode() != null ? request.getCurrencyCode() : "USD");
        account.setPlaidAccountId(request.getPlaidAccountId());
        account.setPlaidItemId(request.getPlaidItemId());
        account.setAccountNumber(request.getAccountNumber());
        account.setActive(request.getActive() != null ? request.getActive() : true);

        final Instant now = Instant.now();
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        account.setLastSyncedAt(now);
        account.setUpdatedAtTimestamp(now.getEpochSecond());

        accountRepository.save(account);

        // Send push notification for real-time sync on other devices
        try {
            dataChangeNotificationService.notifyAccountChanged(
                    user.getUserId(), account.getAccountId());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(AccountController.class)
                    .warn(
                            "Failed to send data change notification for account creation: {}",
                            e.getMessage());
            // Don't fail the request if notification fails
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(account);
    }

    /** Update account endpoint Allows updating account properties */
    @PutMapping("/{id}")
    public ResponseEntity<AccountTable> updateAccount(
            @AuthenticationPrincipal final UserDetails userDetails,
            @PathVariable final String id,
            @RequestBody final UpdateAccountRequest request) {
        if (userDetails == null
                || userDetails.getUsername() == null
                || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Account ID is required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is invalid");
        }

        // Find existing account
        final AccountTable account =
                accountRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));

        // Verify account belongs to user
        if (account.getUserId() == null || !account.getUserId().equals(user.getUserId())) {
            throw new AppException(
                    ErrorCode.UNAUTHORIZED_ACCESS, "Account does not belong to user");
        }

        // TO DO: validate the len of each fields to prevent any buffere overrun scenarios

        // Update account properties (only update provided fields)
        if (request.getAccountName() != null && !request.getAccountName().isEmpty()) {
            // Record that the user owns this account's name so Plaid sync
            // won't silently revert the rename on the next pull.
            if (!request.getAccountName().equals(account.getAccountName())) {
                account.setAccountNameOverridden(Boolean.TRUE);
            }
            account.setAccountName(request.getAccountName());
        }
        if (request.getInstitutionName() != null && !request.getInstitutionName().isEmpty()) {
            account.setInstitutionName(request.getInstitutionName());
        }
        if (request.getAccountType() != null && !request.getAccountType().isEmpty()) {
            account.setAccountType(request.getAccountType());
        }
        if (request.getAccountSubtype() != null && !request.getAccountSubtype().isEmpty()) {
            account.setAccountSubtype(request.getAccountSubtype());
        }
        if (request.getBalance() != null) {
            account.setBalance(request.getBalance());
        }
        if (request.getCurrencyCode() != null && !request.getCurrencyCode().isEmpty()) {
            account.setCurrencyCode(request.getCurrencyCode());
        }
        if (request.getPlaidAccountId() != null && !request.getPlaidAccountId().isEmpty()) {
            account.setPlaidAccountId(request.getPlaidAccountId());
        }
        if (request.getPlaidItemId() != null && !request.getPlaidItemId().isEmpty()) {
            account.setPlaidItemId(request.getPlaidItemId());
        }
        if (request.getAccountNumber() != null && !request.getAccountNumber().isEmpty()) {
            account.setAccountNumber(request.getAccountNumber());
        }
        if (request.getActive() != null) {
            account.setActive(request.getActive());
        }
        if (request.getIsHidden() != null) {
            account.setIsHidden(request.getIsHidden());
        }

        final Instant now = Instant.now();
        account.setUpdatedAt(now);
        account.setUpdatedAtTimestamp(now.getEpochSecond());

        try {
            accountRepository.saveWithLock(account);
        } catch (
                com.budgetbuddy.repository.dynamodb.OptimisticLockHelper.OptimisticLockException
                        e) {
            // A concurrent Plaid sync won the race. Return 409 so the client
            // can re-read and re-submit — editing a stale view would overwrite
            // Plaid's new balance. iOS surfaces this as "Refresh and try again".
            throw new AppException(
                    ErrorCode.CONFLICT,
                    "Account was updated concurrently — please refresh and retry");
        }

        // Send push notification for real-time sync on other devices
        try {
            dataChangeNotificationService.notifyAccountChanged(
                    user.getUserId(), account.getAccountId());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(AccountController.class)
                    .warn(
                            "Failed to send data change notification for account update: {}",
                            e.getMessage());
            // Don't fail the request if notification fails
        }

        return ResponseEntity.ok(account);
    }

    /** Delete account endpoint Allows deleting an account */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAccount(
            @AuthenticationPrincipal final UserDetails userDetails, @PathVariable final String id) {
        if (userDetails == null
                || userDetails.getUsername() == null
                || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Account ID is required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is invalid");
        }

        // TO DO: Check for invalid IDs

        // Find existing account
        final AccountTable account =
                accountRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));

        // Verify account belongs to user
        if (account.getUserId() == null || !account.getUserId().equals(user.getUserId())) {
            throw new AppException(
                    ErrorCode.UNAUTHORIZED_ACCESS, "Account does not belong to user");
        }

        // Cascade-delete: remove every transaction belonging to this
        // account before dropping the account itself. Without this the
        // transaction rows are left orphaned (they survive in DynamoDB
        // but their accountId points to a row that's gone), and they
        // continue to surface in spending totals / insights / exports
        // until the user clears the cache. iOS expects "delete account"
        // to mean "no trace of it" — match that contract here.
        try {
            // Pull a large page — 10 000 covers a heavy multi-year history
            // for a single user without paginating. If a user has more
            // than this we'll still delete the account; the residue is
            // just orphaned rows that won't be referenced by any account
            // anymore.
            final List<TransactionTable> userTransactions =
                    transactionService.getTransactions(user, 0, 10_000);
            final List<String> txIdsToDelete =
                    userTransactions.stream()
                            .filter(t -> t != null && id.equals(t.getAccountId()))
                            .map(TransactionTable::getTransactionId)
                            .filter(txId -> txId != null && !txId.isEmpty())
                            .collect(java.util.stream.Collectors.toList());
            if (!txIdsToDelete.isEmpty()) {
                transactionService.batchDeleteTransactions(txIdsToDelete);
                org.slf4j.LoggerFactory.getLogger(AccountController.class)
                        .info(
                                "Cascade-deleted {} transactions for account {}",
                                txIdsToDelete.size(),
                                id);
            }
        } catch (Exception e) {
            // Don't block the account delete on a partial transaction
            // sweep — log and continue. The account row is the source of
            // truth for "is this account live", so once it's gone the
            // orphaned transactions stop being referenced even if a few
            // remain in the table.
            org.slf4j.LoggerFactory.getLogger(AccountController.class)
                    .warn(
                            "Cascade-delete of transactions failed for account {}: {}",
                            id,
                            e.getMessage());
        }

        // Delete account
        accountRepository.delete(id);

        // Send push notification for real-time sync on other devices
        try {
            dataChangeNotificationService.notifyAccountChanged(user.getUserId(), id);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(AccountController.class)
                    .warn(
                            "Failed to send data change notification for account deletion: {}",
                            e.getMessage());
            // Don't fail the request if notification fails
        }

        return ResponseEntity.noContent().build();
    }

    /**
     * Get loan/credit card payments for a specific account Returns payments made TO loan or credit
     * card accounts For loan accounts: returns negative amounts (payments) For credit card
     * accounts: returns positive amounts (payments) and payment category transactions
     */
    @GetMapping("/{id}/loan-payments")
    public ResponseEntity<List<TransactionTable>> getLoanOrCreditCardPayments(
            @AuthenticationPrincipal final UserDetails userDetails,
            @PathVariable final String id,
            @RequestParam(required = false)
                    @org.springframework.format.annotation.DateTimeFormat(
                            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) final java.time.LocalDate startDate,
            @RequestParam(required = false)
                    @org.springframework.format.annotation.DateTimeFormat(
                            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) final java.time.LocalDate endDate) {
        if (userDetails == null
                || userDetails.getUsername() == null
                || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Account ID is required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is invalid");
        }

        // Validate date range if both dates provided
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new AppException(
                    ErrorCode.INVALID_DATE_RANGE, "Start date must be before or equal to end date");
        }

        // TO DO: Validate if Start date is not after the current date and not 7 years before
        // today's date

        final List<TransactionTable> payments =
                transactionService.getLoanOrCreditCardPayments(user, id, startDate, endDate);
        return ResponseEntity.ok(payments);
    }

    /** Request DTO for creating accounts */
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
        public String getAccountId() {
            return accountId;
        }

        public void setAccountId(final String accountId) {
            this.accountId = accountId;
        }

        public String getAccountName() {
            return accountName;
        }

        public void setAccountName(final String accountName) {
            this.accountName = accountName;
        }

        public String getInstitutionName() {
            return institutionName;
        }

        public void setInstitutionName(final String institutionName) {
            this.institutionName = institutionName;
        }

        public String getAccountType() {
            return accountType;
        }

        public void setAccountType(final String accountType) {
            this.accountType = accountType;
        }

        public String getAccountSubtype() {
            return accountSubtype;
        }

        public void setAccountSubtype(final String accountSubtype) {
            this.accountSubtype = accountSubtype;
        }

        public BigDecimal getBalance() {
            return balance;
        }

        public void setBalance(final BigDecimal balance) {
            this.balance = balance;
        }

        public String getCurrencyCode() {
            return currencyCode;
        }

        public void setCurrencyCode(final String currencyCode) {
            this.currencyCode = currencyCode;
        }

        public String getPlaidAccountId() {
            return plaidAccountId;
        }

        public void setPlaidAccountId(final String plaidAccountId) {
            this.plaidAccountId = plaidAccountId;
        }

        public String getPlaidItemId() {
            return plaidItemId;
        }

        public void setPlaidItemId(final String plaidItemId) {
            this.plaidItemId = plaidItemId;
        }

        public String getAccountNumber() {
            return accountNumber;
        }

        public void setAccountNumber(final String accountNumber) {
            this.accountNumber = accountNumber;
        }

        public Boolean getActive() {
            return active;
        }

        public void setActive(final Boolean active) {
            this.active = active;
        }
    }

    /** Request DTO for updating accounts */
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
        public String getAccountName() {
            return accountName;
        }

        public void setAccountName(final String accountName) {
            this.accountName = accountName;
        }

        public String getInstitutionName() {
            return institutionName;
        }

        public void setInstitutionName(final String institutionName) {
            this.institutionName = institutionName;
        }

        public String getAccountType() {
            return accountType;
        }

        public void setAccountType(final String accountType) {
            this.accountType = accountType;
        }

        public String getAccountSubtype() {
            return accountSubtype;
        }

        public void setAccountSubtype(final String accountSubtype) {
            this.accountSubtype = accountSubtype;
        }

        public BigDecimal getBalance() {
            return balance;
        }

        public void setBalance(final BigDecimal balance) {
            this.balance = balance;
        }

        public String getCurrencyCode() {
            return currencyCode;
        }

        public void setCurrencyCode(final String currencyCode) {
            this.currencyCode = currencyCode;
        }

        public String getPlaidAccountId() {
            return plaidAccountId;
        }

        public void setPlaidAccountId(final String plaidAccountId) {
            this.plaidAccountId = plaidAccountId;
        }

        public String getPlaidItemId() {
            return plaidItemId;
        }

        public void setPlaidItemId(final String plaidItemId) {
            this.plaidItemId = plaidItemId;
        }

        public String getAccountNumber() {
            return accountNumber;
        }

        public void setAccountNumber(final String accountNumber) {
            this.accountNumber = accountNumber;
        }

        public Boolean getActive() {
            return active;
        }

        public void setActive(final Boolean active) {
            this.active = active;
        }

        // Hide flag is the only field that maps from iOS swipe-to-hide.
        // Tri-state semantics on the wire: null = "don't change", true =
        // hide, false = unhide. Same Boolean+null pattern as `active`.
        private Boolean isHidden;

        public Boolean getIsHidden() {
            return isHidden;
        }

        public void setIsHidden(final Boolean isHidden) {
            this.isHidden = isHidden;
        }
    }
}
