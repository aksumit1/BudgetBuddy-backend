package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountBalance;
import com.plaid.client.model.AccountType;
import com.plaid.client.model.AccountsGetResponse;
import com.plaid.client.model.AccountSubtype;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Tests for PlaidSyncService account number extraction and deduplication
 */
@ExtendWith(MockitoExtension.class)
class PlaidSyncServiceAccountNumberTest {

    @Mock
    private PlaidService plaidService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private PlaidSyncService plaidSyncService;

    private UserTable testUser;
    private AccountBase mockPlaidAccount;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail("test@example.com");

        // Create mock Plaid account with account number (mask)
        mockPlaidAccount = mock(AccountBase.class);
        when(mockPlaidAccount.getAccountId()).thenReturn("plaid-account-123");
        when(mockPlaidAccount.getName()).thenReturn("Checking Account");
        when(mockPlaidAccount.getOfficialName()).thenReturn(null);
        when(mockPlaidAccount.getMask()).thenReturn("1234"); // Account number/mask
        when(mockPlaidAccount.getType()).thenReturn(AccountType.DEPOSITORY);
        when(mockPlaidAccount.getSubtype()).thenReturn(AccountSubtype.CHECKING);
        
        AccountBalance balance = new AccountBalance();
        balance.setAvailable(1000.0);
        balance.setCurrent(1000.0);
        balance.setIsoCurrencyCode("USD");
        when(mockPlaidAccount.getBalances()).thenReturn(balance);
    }

    @Test
    void testSyncAccounts_ExtractsAndStoresAccountNumber() {
        // Given - No existing account, Plaid returns account with mask
        AccountsGetResponse accountsResponse = new AccountsGetResponse();
        accountsResponse.setAccounts(Collections.singletonList(mockPlaidAccount));
        
        // Create Item with institution name
        com.plaid.client.model.Item item = new com.plaid.client.model.Item();
        item.setInstitutionId("ins_test_bank");
        accountsResponse.setItem(item);

        when(plaidService.getAccounts(anyString())).thenReturn(accountsResponse);
        when(accountRepository.findByPlaidAccountId(anyString())).thenReturn(Optional.empty());
        when(accountRepository.saveIfNotExists(any(AccountTable.class))).thenReturn(true);

        // When - Sync accounts
        plaidSyncService.syncAccounts(testUser, "test-access-token", null);

        // Then - Account number should be extracted and stored
        verify(accountRepository).saveIfNotExists(argThat(account -> {
            return "1234".equals(account.getAccountNumber());
        }));
    }

    @Test
    void testSyncAccounts_WithAccountNumber_DeduplicatesByAccountNumber() {
        // Given - Existing account with same account number and institution
        String accountNumber = "1234";
        String institutionName = "ins_test_bank"; // Use institution ID format
        String plaidAccountId = "plaid-account-123";
        
        AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setAccountNumber(accountNumber);
        existingAccount.setInstitutionName(institutionName);
        existingAccount.setPlaidAccountId(null); // Missing Plaid ID (old account)
        
        AccountsGetResponse accountsResponse = new AccountsGetResponse();
        accountsResponse.setAccounts(Collections.singletonList(mockPlaidAccount));
        
        // Create Item with institution name matching existing account
        com.plaid.client.model.Item item = new com.plaid.client.model.Item();
        item.setInstitutionId(institutionName);
        accountsResponse.setItem(item);

        when(plaidService.getAccounts(anyString())).thenReturn(accountsResponse);
        when(accountRepository.findByPlaidAccountId(plaidAccountId)).thenReturn(Optional.empty());
        when(accountRepository.findByAccountNumberAndInstitution(accountNumber, institutionName, testUser.getUserId()))
                .thenReturn(Optional.of(existingAccount));

        // When - Sync accounts
        plaidSyncService.syncAccounts(testUser, "test-access-token", null);

        // Then - Should update existing account instead of creating duplicate
        verify(accountRepository, never()).saveIfNotExists(any(AccountTable.class));
        verify(accountRepository).save(argThat(account -> 
                account.getAccountId().equals(existingAccount.getAccountId()) &&
                accountNumber.equals(account.getAccountNumber())
        ));
    }

    @Test
    void testSyncAccounts_WithAccountNumber_UpdatesAccountNumberIfMissing() {
        // Given - Existing account without account number
        String plaidAccountId = "plaid-account-123";
        
        AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setPlaidAccountId(plaidAccountId);
        existingAccount.setAccountNumber(null); // Missing account number
        
        AccountsGetResponse accountsResponse = new AccountsGetResponse();
        accountsResponse.setAccounts(Collections.singletonList(mockPlaidAccount));
        
        // Create Item with institution ID
        com.plaid.client.model.Item item = new com.plaid.client.model.Item();
        item.setInstitutionId("ins_test_bank");
        accountsResponse.setItem(item);

        when(plaidService.getAccounts(anyString())).thenReturn(accountsResponse);
        when(accountRepository.findByPlaidAccountId(plaidAccountId)).thenReturn(Optional.of(existingAccount));

        // When - Sync accounts
        plaidSyncService.syncAccounts(testUser, "test-access-token", null);

        // Then - Account number should be updated
        verify(accountRepository).save(argThat(account -> 
                "1234".equals(account.getAccountNumber())
        ));
    }
}

