package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.matches;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit Tests for DuplicateDetectionService */
@ExtendWith(MockitoExtension.class)
class DuplicateDetectionServiceTest {

    @Mock private TransactionRepository transactionRepository;

    @InjectMocks private DuplicateDetectionService duplicateDetectionService;

    private String testUserId;

    @BeforeEach
    void setUp() {
        testUserId = "user-123";
    }

    @Test
    void testDetectDuplicatesWithExactMatchDetectsDuplicate() {
        // Given
        final DuplicateDetectionService.ParsedTransaction newTx =
                new DuplicateDetectionService.ParsedTransaction(
                        LocalDate.now(),
                        new BigDecimal("100.00"),
                        "Test Transaction",
                        "Test Merchant");

        final TransactionTable existingTx = new TransactionTable();
        existingTx.setTransactionId("existing-tx-1");
        existingTx.setAmount(new BigDecimal("100.00"));
        existingTx.setTransactionDate(LocalDate.now().toString());
        existingTx.setDescription("Test Transaction");

        when(transactionRepository.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(Arrays.asList(existingTx));

        // When
        final Map<Integer, List<DuplicateDetectionService.DuplicateMatch>> duplicates =
                duplicateDetectionService.detectDuplicates(testUserId, Arrays.asList(newTx));

        // Then - Exact matches are now marked in the map with empty list (for skipping during
        // import)
        // Check that the transaction is in the map but with empty list (indicating it should be
        // skipped)
        assertTrue(
                duplicates.containsKey(0) && duplicates.get(0).isEmpty(),
                "Exact matches should be marked for skipping (empty list in map)");
    }

    @Test
    void testDetectDuplicatesWithHighSimilarityDetectsDuplicate() {
        // Given - Same description, same date, very similar amount (within 1%)
        final LocalDate today = LocalDate.now();
        final DuplicateDetectionService.ParsedTransaction newTx =
                new DuplicateDetectionService.ParsedTransaction(
                        today, new BigDecimal("100.00"), "Test Transaction", "Test Merchant");

        final TransactionTable existingTx = new TransactionTable();
        existingTx.setTransactionId("existing-tx-1");
        existingTx.setAmount(
                new BigDecimal(
                        "100.10")); // Similar amount but not exact (difference > 0.01 to avoid
        // exact match)
        existingTx.setTransactionDate(today.toString()); // Same date
        existingTx.setDescription("Test Transaction"); // Same description
        existingTx.setMerchantName("Test Merchant"); // Same merchant

        when(transactionRepository.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(Arrays.asList(existingTx));

        // When
        final Map<Integer, List<DuplicateDetectionService.DuplicateMatch>> duplicates =
                duplicateDetectionService.detectDuplicates(testUserId, Arrays.asList(newTx));

        // Then - Should detect duplicate with high similarity (same description, date, merchant,
        // very similar amount)
        assertTrue(duplicates.containsKey(0), "Should detect duplicate with high similarity");
        assertFalse(duplicates.get(0).isEmpty());
        assertTrue(
                duplicates.get(0).get(0).getSimilarity() >= 0.85,
                "Similarity should be >= 0.85 for same description, date, merchant, and very similar amount");
    }

    @Test
    void testDetectDuplicatesWithRecurringTransactionDoesNotDetectAsDuplicate() {
        // Given - Recurring transaction (same description, amount, but different date)
        final LocalDate today = LocalDate.now();
        final DuplicateDetectionService.ParsedTransaction newTx =
                new DuplicateDetectionService.ParsedTransaction(
                        today, new BigDecimal("1000.00"), "Monthly Rent", "Landlord");

        final TransactionTable existingTx = new TransactionTable();
        existingTx.setTransactionId("existing-tx-1");
        existingTx.setAmount(new BigDecimal("1000.00")); // Same amount
        existingTx.setTransactionDate(
                today.minusDays(30).toString()); // 30 days ago (monthly recurring)
        existingTx.setDescription("Monthly Rent"); // Same description

        when(transactionRepository.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(Arrays.asList(existingTx));

        // When
        final Map<Integer, List<DuplicateDetectionService.DuplicateMatch>> duplicates =
                duplicateDetectionService.detectDuplicates(testUserId, Arrays.asList(newTx));

        // Then - Should not detect as duplicate (similarity < 0.85 for recurring transactions)
        assertTrue(
                duplicates.isEmpty()
                        || (duplicates.containsKey(0)
                                && duplicates.get(0).get(0).getSimilarity() < 0.85),
                "Recurring transactions should not be detected as duplicates");
    }

    @Test
    void testDetectDuplicatesWithNoExistingTransactionsReturnsEmpty() {
        // Given
        final DuplicateDetectionService.ParsedTransaction newTx =
                new DuplicateDetectionService.ParsedTransaction(
                        LocalDate.now(), new BigDecimal("100.00"), "Test Transaction", null);

        when(transactionRepository.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(Collections.emptyList());

        // When
        final Map<Integer, List<DuplicateDetectionService.DuplicateMatch>> duplicates =
                duplicateDetectionService.detectDuplicates(testUserId, Arrays.asList(newTx));

        // Then
        assertTrue(duplicates.isEmpty());
    }

    @Test
    void testDetectDuplicatesWithEmptyNewTransactionsReturnsEmpty() {
        // When
        final Map<Integer, List<DuplicateDetectionService.DuplicateMatch>> duplicates =
                duplicateDetectionService.detectDuplicates(testUserId, Collections.emptyList());

        // Then
        assertTrue(duplicates.isEmpty());
    }

    @Test
    void testDetectDuplicatesWithNullNewTransactionsReturnsEmpty() {
        // When
        final Map<Integer, List<DuplicateDetectionService.DuplicateMatch>> duplicates =
                duplicateDetectionService.detectDuplicates(testUserId, null);

        // Then
        assertTrue(duplicates.isEmpty());
    }

    @Test
    void testDetectDuplicatesWithSameTransactionIdSkipsDuplicate() {
        // Given
        final DuplicateDetectionService.ParsedTransaction newTx =
                new DuplicateDetectionService.ParsedTransaction(
                        LocalDate.now(), new BigDecimal("100.00"), "Test Transaction", null);
        newTx.setTransactionId("tx-123");

        final TransactionTable existingTx = new TransactionTable();
        existingTx.setTransactionId("tx-123"); // Same ID
        existingTx.setAmount(new BigDecimal("100.00"));
        existingTx.setTransactionDate(LocalDate.now().toString());
        existingTx.setDescription("Test Transaction");

        when(transactionRepository.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(Arrays.asList(existingTx));

        // When
        final Map<Integer, List<DuplicateDetectionService.DuplicateMatch>> duplicates =
                duplicateDetectionService.detectDuplicates(testUserId, Arrays.asList(newTx));

        // Then - Transactions with same ID are now marked in the map with empty list (for skipping
        // during import)
        assertTrue(
                duplicates.containsKey(0) && duplicates.get(0).isEmpty(),
                "Transactions with same ID should be marked for skipping (empty list in map)");
    }

    @Test
    void testDetectDuplicatesWithSamePlaidTransactionIdSkipsDuplicate() {
        // Given
        final DuplicateDetectionService.ParsedTransaction newTx =
                new DuplicateDetectionService.ParsedTransaction(
                        LocalDate.now(), new BigDecimal("100.00"), "Test Transaction", null);
        newTx.setPlaidTransactionId("plaid-123");

        final TransactionTable existingTx = new TransactionTable();
        existingTx.setTransactionId("tx-456"); // Different transaction ID
        existingTx.setPlaidTransactionId("plaid-123"); // Same Plaid ID
        existingTx.setAmount(new BigDecimal("100.00"));
        existingTx.setTransactionDate(LocalDate.now().toString());
        existingTx.setDescription("Test Transaction");

        when(transactionRepository.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(Arrays.asList(existingTx));

        // When
        final Map<Integer, List<DuplicateDetectionService.DuplicateMatch>> duplicates =
                duplicateDetectionService.detectDuplicates(testUserId, Arrays.asList(newTx));

        // Then - Transactions with same Plaid ID are now marked in the map with empty list (for
        // skipping during import)
        assertTrue(
                duplicates.containsKey(0) && duplicates.get(0).isEmpty(),
                "Transactions with same Plaid ID should be marked for skipping (empty list in map)");
    }

    @Test
    void testDetectDuplicatesWithLowSimilarityDoesNotDetectDuplicate() {
        // Given
        final DuplicateDetectionService.ParsedTransaction newTx =
                new DuplicateDetectionService.ParsedTransaction(
                        LocalDate.now(),
                        new BigDecimal("100.00"),
                        "Completely Different Transaction",
                        "Different Merchant");

        final TransactionTable existingTx = new TransactionTable();
        existingTx.setTransactionId("existing-tx-1");
        existingTx.setAmount(new BigDecimal("500.00")); // Very different amount
        existingTx.setTransactionDate(
                LocalDate.now().minusDays(60).toString()); // Very different date
        existingTx.setDescription("Different Description"); // Different description

        when(transactionRepository.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(Arrays.asList(existingTx));

        // When
        final Map<Integer, List<DuplicateDetectionService.DuplicateMatch>> duplicates =
                duplicateDetectionService.detectDuplicates(testUserId, Arrays.asList(newTx));

        // Then
        assertTrue(
                duplicates.isEmpty()
                        || (duplicates.containsKey(0)
                                && duplicates.get(0).get(0).getSimilarity() < 0.85),
                "Low similarity transactions should not be detected as duplicates");
    }

    @Test
    void testDetectDuplicatesWithMultipleMatchesReturnsSortedBySimilarity() {
        // Given
        final DuplicateDetectionService.ParsedTransaction newTx =
                new DuplicateDetectionService.ParsedTransaction(
                        LocalDate.now(), new BigDecimal("100.00"), "Test Transaction", "Merchant");

        final TransactionTable highSimilarityTx = new TransactionTable();
        highSimilarityTx.setTransactionId("tx-1");
        highSimilarityTx.setAmount(new BigDecimal("100.10"));
        highSimilarityTx.setTransactionDate(LocalDate.now().toString());
        highSimilarityTx.setDescription("Test Transaction");

        final TransactionTable mediumSimilarityTx = new TransactionTable();
        mediumSimilarityTx.setTransactionId("tx-2");
        mediumSimilarityTx.setAmount(new BigDecimal("105.00"));
        mediumSimilarityTx.setTransactionDate(LocalDate.now().minusDays(2).toString());
        mediumSimilarityTx.setDescription("Test Transaction");

        when(transactionRepository.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(Arrays.asList(highSimilarityTx, mediumSimilarityTx));

        // When
        final Map<Integer, List<DuplicateDetectionService.DuplicateMatch>> duplicates =
                duplicateDetectionService.detectDuplicates(testUserId, Arrays.asList(newTx));

        // Then
        if (duplicates.containsKey(0) && !duplicates.get(0).isEmpty()) {
            final List<DuplicateDetectionService.DuplicateMatch> matches = duplicates.get(0);
            // Should be sorted by similarity (highest first)
            for (int i = 0; i < matches.size() - 1; i++) {
                assertTrue(
                        matches.get(i).getSimilarity() >= matches.get(i + 1).getSimilarity(),
                        "Matches should be sorted by similarity (highest first)");
            }
        }
    }
}
