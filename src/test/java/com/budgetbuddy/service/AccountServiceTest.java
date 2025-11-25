package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for AccountRepository (used by AccountController)
 */
@org.junit.jupiter.api.Disabled("Java 25 compatibility: Mockito mocking issues")
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    private AccountTable testAccount;

    @BeforeEach
    void setUp() {
        testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId("user-123");
        testAccount.setAccountName("Test Account");
        testAccount.setBalance(BigDecimal.valueOf(1000.00));
        testAccount.setActive(true);
    }

    @Test
    void testFindById_WithValidId_ReturnsAccount() {
        // Given
        when(accountRepository.findById(testAccount.getAccountId()))
                .thenReturn(Optional.of(testAccount));

        // When
        Optional<AccountTable> result = accountRepository.findById(testAccount.getAccountId());

        // Then
        assertTrue(result.isPresent());
        assertEquals(testAccount.getAccountId(), result.get().getAccountId());
    }

    @Test
    void testFindByUserId_WithValidUserId_ReturnsAccounts() {
        // Given
        List<AccountTable> accounts = List.of(testAccount);
        when(accountRepository.findByUserId("user-123")).thenReturn(accounts);

        // When
        List<AccountTable> result = accountRepository.findByUserId("user-123");

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void testFindActiveAccountsByUserId_FiltersActiveAccounts() {
        // Given
        AccountTable inactiveAccount = new AccountTable();
        inactiveAccount.setAccountId(UUID.randomUUID().toString());
        inactiveAccount.setUserId("user-123");
        inactiveAccount.setActive(false);

        List<AccountTable> accounts = List.of(testAccount, inactiveAccount);
        when(accountRepository.findByUserId("user-123")).thenReturn(accounts);

        // When
        List<AccountTable> allAccounts = accountRepository.findByUserId("user-123");
        List<AccountTable> activeAccounts = allAccounts.stream()
                .filter(AccountTable::getActive)
                .collect(Collectors.toList());

        // Then
        assertEquals(1, activeAccounts.size());
        assertTrue(activeAccounts.get(0).getActive());
    }

    @Test
    void testSave_WithValidAccount_SavesAccount() {
        // Given
        doNothing().when(accountRepository).save(any(AccountTable.class));

        // When
        accountRepository.save(testAccount);

        // Then
        verify(accountRepository, times(1)).save(testAccount);
    }

    @Test
    void testDelete_WithValidId_DeletesAccount() {
        // Given
        doNothing().when(accountRepository).delete(anyString());

        // When
        accountRepository.delete(testAccount.getAccountId());

        // Then
        verify(accountRepository, times(1)).delete(testAccount.getAccountId());
    }
}

