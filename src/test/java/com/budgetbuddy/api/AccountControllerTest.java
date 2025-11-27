package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for AccountController
 * 
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AccountControllerTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private UserService userService;

    @Mock
    private UserDetails userDetails;

    @InjectMocks
    private AccountController accountController;

    private UserTable testUser;
    private AccountTable testAccount;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");

        testAccount = new AccountTable();
        testAccount.setAccountId("account-123");
        testAccount.setUserId("user-123");
        testAccount.setAccountName("Test Account");
        testAccount.setBalance(BigDecimal.valueOf(1000.00));
        testAccount.setActive(true);

        when(userDetails.getUsername()).thenReturn("test@example.com");
    }

    @Test
    void testGetAccounts_WithValidUser_ReturnsAccounts() {
        // Given
        List<AccountTable> accounts = Arrays.asList(testAccount);
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(accountRepository.findByUserId("user-123")).thenReturn(accounts);

        // When
        ResponseEntity<List<AccountTable>> response = accountController.getAccounts(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void testGetAccounts_WithNullUserDetails_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> {
            accountController.getAccounts(null);
        });
    }

    @Test
    void testGetAccount_WithValidId_ReturnsAccount() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(accountRepository.findById("account-123")).thenReturn(Optional.of(testAccount));

        // When
        ResponseEntity<AccountTable> response = accountController.getAccount(userDetails, "account-123");

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("account-123", response.getBody().getAccountId());
    }

    @Test
    void testGetAccount_WithUnauthorizedAccount_ThrowsException() {
        // Given
        AccountTable otherUserAccount = new AccountTable();
        otherUserAccount.setAccountId("account-456");
        otherUserAccount.setUserId("other-user");

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(accountRepository.findById("account-456")).thenReturn(Optional.of(otherUserAccount));

        // When/Then
        assertThrows(AppException.class, () -> {
            accountController.getAccount(userDetails, "account-456");
        });
    }

    @Test
    void testGetAccount_WithNullId_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> {
            accountController.getAccount(userDetails, null);
        });
    }
}

