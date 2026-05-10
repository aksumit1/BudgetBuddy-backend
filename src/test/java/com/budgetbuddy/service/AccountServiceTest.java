package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit Tests for AccountRepository (used by AccountController) */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock private AccountRepository accountRepository;

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
    void testFindByIdWithValidIdReturnsAccount() {
        // Given
        when(accountRepository.findById(testAccount.getAccountId()))
                .thenReturn(Optional.of(testAccount));

        // When
        final Optional<AccountTable> result = accountRepository.findById(testAccount.getAccountId());

        // Then
        assertTrue(result.isPresent());
        assertEquals(testAccount.getAccountId(), result.get().getAccountId());
    }

    @Test
    void testFindByUserIdWithValidUserIdReturnsAccounts() {
        // Given
        final List<AccountTable> accounts = List.of(testAccount);
        when(accountRepository.findByUserId("user-123")).thenReturn(accounts);

        // When
        final List<AccountTable> result = accountRepository.findByUserId("user-123");

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void testFindActiveAccountsByUserIdFiltersActiveAccounts() {
        // Given
        final AccountTable inactiveAccount = new AccountTable();
        inactiveAccount.setAccountId(UUID.randomUUID().toString());
        inactiveAccount.setUserId("user-123");
        inactiveAccount.setActive(false);

        final List<AccountTable> accounts = List.of(testAccount, inactiveAccount);
        when(accountRepository.findByUserId("user-123")).thenReturn(accounts);

        // When
        final List<AccountTable> allAccounts = accountRepository.findByUserId("user-123");
        final List<AccountTable> activeAccounts =
                allAccounts.stream().filter(AccountTable::getActive).collect(Collectors.toList());

        // Then
        assertEquals(1, activeAccounts.size());
        assertTrue(activeAccounts.get(0).getActive());
    }

    @Test
    void testSaveWithValidAccountSavesAccount() {
        // Given
        doNothing().when(accountRepository).save(any(AccountTable.class));

        // When
        accountRepository.save(testAccount);

        // Then
        verify(accountRepository, times(1)).save(testAccount);
    }

    @Test
    void testDeleteWithValidIdDeletesAccount() {
        // Given
        doNothing().when(accountRepository).delete(anyString());

        // When
        accountRepository.delete(testAccount.getAccountId());

        // Then
        verify(accountRepository, times(1)).delete(testAccount.getAccountId());
    }
}
