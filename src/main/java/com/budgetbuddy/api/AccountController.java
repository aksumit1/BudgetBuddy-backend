package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

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
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Access to account denied", null, null, null);
        }

        return ResponseEntity.ok(account);
    }
}
