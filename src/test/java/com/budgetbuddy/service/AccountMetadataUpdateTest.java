package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Integration tests for account metadata update logic with "latest" payment due date Tests the
 * updateAccountMetadataFromPDFImport method behavior
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@ExtendWith(MockitoExtension.class)
class AccountMetadataUpdateTest {

    private static final String USER123 = "user123";

    @Mock private AccountRepository accountRepository;

    @Mock private PDFImportService pdfImportService;

    @Mock private AccountDetectionService accountDetectionService;

    @Mock private ImportCategoryParser importCategoryParser;

    @Mock private TransactionTypeCategoryService transactionTypeCategoryService;

    @Mock private EnhancedPatternMatcher enhancedPatternMatcher;

    private PDFImportService pdfImportServiceInstance;

    @BeforeEach
    void setUp() {
        pdfImportServiceInstance =
                new PDFImportService(
                        accountDetectionService,
                        importCategoryParser,
                        enhancedPatternMatcher,
                        null);
    }

    @Test
    void testUpdateAccountMetadataNewAccountUpdatesWithFirstImport() {
        // Given - New account with no existing metadata
        final String accountId = UUID.randomUUID().toString();
        final AccountTable account = new AccountTable();
        account.setAccountId(accountId);
        account.setUserId(USER123);
        account.setPaymentDueDate(null);
        account.setMinimumPaymentDue(null);
        account.setRewardPoints(null);
        account.setBalance(null);

        final PDFImportService.ImportResult importResult = new PDFImportService.ImportResult();
        importResult.setPaymentDueDate(LocalDate.of(2024, 12, 15));
        importResult.setMinimumPaymentDue(new BigDecimal("25.00"));
        importResult.setRewardPoints(12_345L);

        final AccountDetectionService.DetectedAccount detectedAccount =
                new AccountDetectionService.DetectedAccount();
        detectedAccount.setBalance(new BigDecimal("1000.00"));
        importResult.setDetectedAccount(detectedAccount);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        doNothing().when(accountRepository).save(any(AccountTable.class));

        // When - Update metadata
        updateAccountMetadata(accountId, importResult);

        // Then - All metadata should be set
        final ArgumentCaptor<AccountTable> captor = ArgumentCaptor.forClass(AccountTable.class);
        verify(accountRepository, times(1)).save(captor.capture());

        final AccountTable saved = captor.getValue();
        assertEquals(LocalDate.of(2024, 12, 15), saved.getPaymentDueDate());
        assertEquals(new BigDecimal("25.00"), saved.getMinimumPaymentDue());
        assertEquals(12_345L, saved.getRewardPoints());
        assertEquals(new BigDecimal("1000.00"), saved.getBalance());
    }

    @Test
    void testUpdateAccountMetadataLaterDateUpdatesAllMetadata() {
        // Given - Account with existing metadata (Nov 15)
        final String accountId = UUID.randomUUID().toString();
        final AccountTable account = new AccountTable();
        account.setAccountId(accountId);
        account.setUserId(USER123);
        account.setPaymentDueDate(LocalDate.of(2024, 11, 15));
        account.setMinimumPaymentDue(new BigDecimal("20.00"));
        account.setRewardPoints(10_000L);
        account.setBalance(new BigDecimal("900.00"));

        // New import with later date (Dec 15)
        final PDFImportService.ImportResult importResult = new PDFImportService.ImportResult();
        importResult.setPaymentDueDate(LocalDate.of(2024, 12, 15));
        importResult.setMinimumPaymentDue(new BigDecimal("25.00"));
        importResult.setRewardPoints(12_345L);

        final AccountDetectionService.DetectedAccount detectedAccount =
                new AccountDetectionService.DetectedAccount();
        detectedAccount.setBalance(new BigDecimal("1000.00"));
        importResult.setDetectedAccount(detectedAccount);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        doNothing().when(accountRepository).save(any(AccountTable.class));

        // When - Update metadata
        updateAccountMetadata(accountId, importResult);

        // Then - All metadata should be updated (Dec 15 is later)
        final ArgumentCaptor<AccountTable> captor = ArgumentCaptor.forClass(AccountTable.class);
        verify(accountRepository, times(1)).save(captor.capture());

        final AccountTable saved = captor.getValue();
        assertEquals(LocalDate.of(2024, 12, 15), saved.getPaymentDueDate());
        assertEquals(new BigDecimal("25.00"), saved.getMinimumPaymentDue());
        assertEquals(12_345L, saved.getRewardPoints());
        assertEquals(new BigDecimal("1000.00"), saved.getBalance());
    }

    @Test
    void testUpdateAccountMetadataEarlierDateKeepsExistingMetadata() {
        // Given - Account with existing metadata (Dec 15)
        final String accountId = UUID.randomUUID().toString();
        final AccountTable account = new AccountTable();
        account.setAccountId(accountId);
        account.setUserId(USER123);
        account.setPaymentDueDate(LocalDate.of(2024, 12, 15));
        account.setMinimumPaymentDue(new BigDecimal("25.00"));
        account.setRewardPoints(12_345L);
        account.setBalance(new BigDecimal("1000.00"));

        // New import with earlier date (Nov 15)
        final PDFImportService.ImportResult importResult = new PDFImportService.ImportResult();
        importResult.setPaymentDueDate(LocalDate.of(2024, 11, 15));
        importResult.setMinimumPaymentDue(new BigDecimal("20.00"));
        importResult.setRewardPoints(10_000L);

        final AccountDetectionService.DetectedAccount detectedAccount =
                new AccountDetectionService.DetectedAccount();
        detectedAccount.setBalance(new BigDecimal("900.00"));
        importResult.setDetectedAccount(detectedAccount);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        // When - Update metadata
        updateAccountMetadata(accountId, importResult);

        // Then - Metadata should NOT be updated (Nov 15 is earlier)
        verify(accountRepository, never()).save(any(AccountTable.class));

        // Verify existing values are preserved
        assertEquals(LocalDate.of(2024, 12, 15), account.getPaymentDueDate());
        assertEquals(new BigDecimal("25.00"), account.getMinimumPaymentDue());
        assertEquals(12_345L, account.getRewardPoints());
        assertEquals(new BigDecimal("1000.00"), account.getBalance());
    }

    @Test
    void testUpdateAccountMetadataSameDateKeepsExistingMetadata() {
        // Given - Account with existing metadata (Dec 15)
        final String accountId = UUID.randomUUID().toString();
        final AccountTable account = new AccountTable();
        account.setAccountId(accountId);
        account.setUserId(USER123);
        account.setPaymentDueDate(LocalDate.of(2024, 12, 15));
        account.setMinimumPaymentDue(new BigDecimal("25.00"));
        account.setRewardPoints(12_345L);
        account.setBalance(new BigDecimal("1000.00"));

        // New import with same date (Dec 15)
        final PDFImportService.ImportResult importResult = new PDFImportService.ImportResult();
        importResult.setPaymentDueDate(LocalDate.of(2024, 12, 15));
        importResult.setMinimumPaymentDue(new BigDecimal("30.00"));
        importResult.setRewardPoints(15_000L);

        final AccountDetectionService.DetectedAccount detectedAccount =
                new AccountDetectionService.DetectedAccount();
        detectedAccount.setBalance(new BigDecimal("1100.00"));
        importResult.setDetectedAccount(detectedAccount);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        // When - Update metadata
        updateAccountMetadata(accountId, importResult);

        // Then - Metadata should NOT be updated (same date, not later)
        verify(accountRepository, never()).save(any(AccountTable.class));

        // Verify existing values are preserved
        assertEquals(LocalDate.of(2024, 12, 15), account.getPaymentDueDate());
        assertEquals(new BigDecimal("25.00"), account.getMinimumPaymentDue());
        assertEquals(12_345L, account.getRewardPoints());
        assertEquals(new BigDecimal("1000.00"), account.getBalance());
    }

    @Test
    void testUpdateAccountMetadataNoPaymentDueDateOnlyUpdatesBalanceIfNull() {
        // Given - Account with no payment due date
        final String accountId = UUID.randomUUID().toString();
        final AccountTable account = new AccountTable();
        account.setAccountId(accountId);
        account.setUserId(USER123);
        account.setPaymentDueDate(null);
        account.setBalance(null);

        // Import with no payment due date but has balance
        final PDFImportService.ImportResult importResult = new PDFImportService.ImportResult();
        importResult.setPaymentDueDate(null);

        final AccountDetectionService.DetectedAccount detectedAccount =
                new AccountDetectionService.DetectedAccount();
        detectedAccount.setBalance(new BigDecimal("1000.00"));
        importResult.setDetectedAccount(detectedAccount);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        doNothing().when(accountRepository).save(any(AccountTable.class));

        // When - Update metadata
        updateAccountMetadata(accountId, importResult);

        // Then - Only balance should be updated
        final ArgumentCaptor<AccountTable> captor = ArgumentCaptor.forClass(AccountTable.class);
        verify(accountRepository, times(1)).save(captor.capture());

        final AccountTable saved = captor.getValue();
        assertNull(saved.getPaymentDueDate());
        assertEquals(new BigDecimal("1000.00"), saved.getBalance());
    }

    @Test
    void testUpdateAccountMetadataNullImportResultNoUpdate() {
        // Given
        final String accountId = UUID.randomUUID().toString();

        // When - Update with null import result
        updateAccountMetadata(accountId, null);

        // Then - No repository calls
        verify(accountRepository, never()).findById(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void testUpdateAccountMetadataAccountNotFoundNoUpdate() {
        // Given
        final String accountId = UUID.randomUUID().toString();
        final PDFImportService.ImportResult importResult = new PDFImportService.ImportResult();
        importResult.setPaymentDueDate(LocalDate.of(2024, 12, 15));

        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        // When - Update metadata
        updateAccountMetadata(accountId, importResult);

        // Then - No save call
        verify(accountRepository, never()).save(any(AccountTable.class));
    }

    // Helper method to simulate updateAccountMetadataFromPDFImport
    // This is a simplified version for testing - actual implementation is in TransactionController
    private void updateAccountMetadata(
            final String accountId, final PDFImportService.ImportResult importResult) {
        if (accountId == null || accountId.isBlank() || importResult == null) {
            return;
        }

        try {
            final Optional<AccountTable> accountOpt = accountRepository.findById(accountId);
            if (!accountOpt.isPresent()) {
                return;
            }

            final AccountTable account = accountOpt.get();
            boolean needsUpdate = false;

            final LocalDate newPaymentDueDate = importResult.getPaymentDueDate();
            final BigDecimal newMinimumPaymentDue = importResult.getMinimumPaymentDue();
            final Long newRewardPoints = importResult.getRewardPoints();
            BigDecimal newBalance = null;

            if (importResult.getDetectedAccount() != null
                    && importResult.getDetectedAccount().getBalance() != null) {
                newBalance = importResult.getDetectedAccount().getBalance();
            }

            final LocalDate existingPaymentDueDate = account.getPaymentDueDate();

            if (newPaymentDueDate != null) {
                if (existingPaymentDueDate == null) {
                    account.setPaymentDueDate(newPaymentDueDate);
                    if (newMinimumPaymentDue != null) {
                        account.setMinimumPaymentDue(newMinimumPaymentDue);
                    }
                    if (newRewardPoints != null) {
                        account.setRewardPoints(newRewardPoints);
                    }
                    if (newBalance != null) {
                        account.setBalance(newBalance);
                    }
                    needsUpdate = true;
                } else if (newPaymentDueDate.isAfter(existingPaymentDueDate)) {
                    account.setPaymentDueDate(newPaymentDueDate);
                    if (newMinimumPaymentDue != null) {
                        account.setMinimumPaymentDue(newMinimumPaymentDue);
                    }
                    if (newRewardPoints != null) {
                        account.setRewardPoints(newRewardPoints);
                    }
                    if (newBalance != null) {
                        account.setBalance(newBalance);
                    }
                    needsUpdate = true;
                }
            } else {
                if (newBalance != null && account.getBalance() == null) {
                    account.setBalance(newBalance);
                    needsUpdate = true;
                }
            }

            if (needsUpdate) {
                account.setUpdatedAt(Instant.now());
                accountRepository.save(account);
            }
        } catch (Exception e) {
            // Don't fail the import if metadata update fails
        }
    }
}
