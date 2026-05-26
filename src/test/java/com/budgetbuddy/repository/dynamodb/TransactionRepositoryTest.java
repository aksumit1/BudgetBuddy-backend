package com.budgetbuddy.repository.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Unit Tests for TransactionRepository Tests transaction CRUD operations and query methods */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class TransactionRepositoryTest {

    @Mock private DynamoDbEnhancedClient enhancedClient;

    @Mock private DynamoDbClient dynamoDbClient;

    @Mock private DynamoDbTable<TransactionTable> transactionTable;

    @Mock private DynamoDbIndex<TransactionTable> userIdDateIndex;

    @Mock private DynamoDbIndex<TransactionTable> plaidTransactionIdIndex;

    @Mock private DynamoDbIndex<TransactionTable> userIdUpdatedAtIndex;

    private TransactionRepository transactionRepository;
    private String testUserId;
    private String testTransactionId;
    private TransactionTable testTransaction;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        testTransactionId = UUID.randomUUID().toString();

        when(enhancedClient.table(
                        anyString(),
                        any(software.amazon.awssdk.enhanced.dynamodb.TableSchema.class)))
                .thenReturn(transactionTable);
        when(transactionTable.index("UserIdDateIndex")).thenReturn(userIdDateIndex);
        when(transactionTable.index("PlaidTransactionIdIndex")).thenReturn(plaidTransactionIdIndex);
        when(transactionTable.index("UserIdUpdatedAtIndex")).thenReturn(userIdUpdatedAtIndex);

        transactionRepository =
                new TransactionRepository(enhancedClient, dynamoDbClient, "BudgetBuddy", 50_000);

        testTransaction = new TransactionTable();
        testTransaction.setTransactionId(testTransactionId);
        testTransaction.setUserId(testUserId);
        testTransaction.setAmount(new BigDecimal("100.00"));
        testTransaction.setTransactionDate("2024-01-15");
    }

    @Test
    void testSaveWithValidTransactionSavesSuccessfully() {
        // Given
        doNothing().when(transactionTable).putItem(any(TransactionTable.class));

        // When
        transactionRepository.save(testTransaction);

        // Then
        verify(transactionTable, times(1)).putItem(any(TransactionTable.class));
    }

    @Test
    void testSaveWithNullTransactionThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    transactionRepository.save(null);
                });
        verify(transactionTable, never()).putItem(any(TransactionTable.class));
    }

    @Test
    void testFindByIdWithValidIdReturnsTransaction() {
        // Given
        when(transactionTable.getItem(any(Key.class))).thenReturn(testTransaction);

        // When
        final Optional<TransactionTable> result = transactionRepository.findById(testTransactionId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(testTransactionId, result.get().getTransactionId());
        verify(transactionTable, times(1)).getItem(any(Key.class));
    }

    @Test
    void testFindByIdWithNullIdReturnsEmpty() {
        // When
        final Optional<TransactionTable> result = transactionRepository.findById(null);

        // Then
        assertFalse(result.isPresent());
        verify(transactionTable, never()).getItem(any(Key.class));
    }

    @Test
    void testFindByIdWithEmptyIdReturnsEmpty() {
        // When
        final Optional<TransactionTable> result = transactionRepository.findById("");

        // Then
        assertFalse(result.isPresent());
        verify(transactionTable, never()).getItem(any(Key.class));
    }

    @Test
    void testFindByUserIdWithValidUserIdReturnsTransactions() {
        // Given
        testTransaction.setTransactionDate("2024-01-15");
        final Page<TransactionTable> page = mock(Page.class);
        when(page.items()).thenReturn(List.of(testTransaction));
        final SdkIterable<Page<TransactionTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(userIdDateIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        final List<TransactionTable> result =
                transactionRepository.findByUserId(testUserId, 0, 100);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(testTransactionId, result.getFirst().getTransactionId());
    }

    @Test
    void testFindByUserIdAndDateRangeWithValidRangeReturnsTransactions() {
        // Given
        final String startDate = "2024-01-01";
        final String endDate = "2024-01-31";
        testTransaction.setTransactionDate("2024-01-15");

        final Page<TransactionTable> page = mock(Page.class);
        when(page.items()).thenReturn(List.of(testTransaction));
        final SdkIterable<Page<TransactionTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(userIdDateIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        final List<TransactionTable> result =
                transactionRepository.findByUserIdAndDateRange(testUserId, startDate, endDate);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void testFindByPlaidTransactionIdWithValidIdReturnsTransaction() {
        // Given
        final String plaidId = "plaid-transaction-123";

        final Page<TransactionTable> page = mock(Page.class);
        when(page.items()).thenReturn(List.of(testTransaction));
        final SdkIterable<Page<TransactionTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(plaidTransactionIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        final Optional<TransactionTable> result =
                transactionRepository.findByPlaidTransactionId(plaidId);

        // Then
        assertTrue(result.isPresent());
    }

    @Test
    void testDeleteWithValidIdDeletesTransaction() {
        // Given
        // deleteItem returns void - verify it's called without stubbing
        // (void methods don't need stubbing, just verification)

        // When
        transactionRepository.delete(testTransactionId);

        // Then
        verify(transactionTable, times(1)).deleteItem(any(Key.class));
    }

    @Test
    void testFindByUserIdAndUpdatedAfterWithValidParamsReturnsUpdatedTransactions() {
        // Given
        final long updatedAfterTimestamp = Instant.now().minusSeconds(3600).getEpochSecond();
        testTransaction.setUpdatedAtTimestamp(Instant.now().getEpochSecond());

        final Page<TransactionTable> page = mock(Page.class);
        when(page.items()).thenReturn(List.of(testTransaction));
        final SdkIterable<Page<TransactionTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(userIdUpdatedAtIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        final List<TransactionTable> result =
                transactionRepository.findByUserIdAndUpdatedAfter(
                        testUserId, updatedAfterTimestamp, 10);

        // Then
        assertNotNull(result);
    }

    @Test
    void testFindByUserIdAndUpdatedAfterWithNullParamsReturnsEmpty() {
        // When
        final List<TransactionTable> result1 =
                transactionRepository.findByUserIdAndUpdatedAfter(
                        null, Instant.now().getEpochSecond(), 10);
        final List<TransactionTable> result2 =
                transactionRepository.findByUserIdAndUpdatedAfter(testUserId, null, 10);
        final List<TransactionTable> result3 =
                transactionRepository.findByUserIdAndUpdatedAfter(
                        "", Instant.now().getEpochSecond(), 10);

        // Then
        assertTrue(result1.isEmpty());
        assertTrue(result2.isEmpty());
        assertTrue(result3.isEmpty());
    }

    @Test
    void testFindByUserIdAndUpdatedAfterWithResourceNotFoundExceptionFallsBack() {
        // Given
        final long updatedAfterTimestamp = Instant.now().minusSeconds(3600).getEpochSecond();
        testTransaction.setUpdatedAtTimestamp(Instant.now().getEpochSecond());

        // Mock ResourceNotFoundException
        when(userIdUpdatedAtIndex.query(any(QueryConditional.class)))
                .thenThrow(
                        software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
                                .builder()
                                .build());

        // Mock findByUserId fallback
        final Page<TransactionTable> fallbackPage = mock(Page.class);
        when(fallbackPage.items()).thenReturn(List.of(testTransaction));
        final SdkIterable<Page<TransactionTable>> fallbackPages = mock(SdkIterable.class);
        when(fallbackPages.iterator()).thenReturn(List.of(fallbackPage).iterator());
        when(userIdDateIndex.query(any(QueryConditional.class))).thenReturn(fallbackPages);

        // When
        final List<TransactionTable> result =
                transactionRepository.findByUserIdAndUpdatedAfter(
                        testUserId, updatedAfterTimestamp, 10);

        // Then - Should fallback
        assertNotNull(result);
    }

    @Test
    void testFindByUserIdAndUpdatedAfterWithLimitAdjustmentRespectsLimit() {
        // Given
        final long updatedAfterTimestamp = Instant.now().minusSeconds(3600).getEpochSecond();
        testTransaction.setUpdatedAtTimestamp(Instant.now().getEpochSecond());

        final Page<TransactionTable> page = mock(Page.class);
        when(page.items()).thenReturn(List.of(testTransaction));
        final SdkIterable<Page<TransactionTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(userIdUpdatedAtIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When - Test with limit 0 (should default to 50)
        final List<TransactionTable> result1 =
                transactionRepository.findByUserIdAndUpdatedAfter(
                        testUserId, updatedAfterTimestamp, 0);
        // When - Test with limit > 100 (should cap at 100)
        final List<TransactionTable> result2 =
                transactionRepository.findByUserIdAndUpdatedAfter(
                        testUserId, updatedAfterTimestamp, 200);

        // Then
        assertNotNull(result1);
        assertNotNull(result2);
    }

    @Test
    void testFindByUserIdAndUpdatedAfterWithWrappedResourceNotFoundExceptionFallsBack() {
        // Given
        final long updatedAfterTimestamp = Instant.now().minusSeconds(3600).getEpochSecond();
        testTransaction.setUpdatedAtTimestamp(Instant.now().getEpochSecond());

        // Mock RuntimeException wrapping ResourceNotFoundException
        final RuntimeException wrappedException =
                new RuntimeException(
                        "Wrapped",
                        software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
                                .builder()
                                .build());
        when(userIdUpdatedAtIndex.query(any(QueryConditional.class))).thenThrow(wrappedException);

        // Mock findByUserId fallback
        final Page<TransactionTable> fallbackPage = mock(Page.class);
        when(fallbackPage.items()).thenReturn(List.of(testTransaction));
        final SdkIterable<Page<TransactionTable>> fallbackPages = mock(SdkIterable.class);
        when(fallbackPages.iterator()).thenReturn(List.of(fallbackPage).iterator());
        when(userIdDateIndex.query(any(QueryConditional.class))).thenReturn(fallbackPages);

        // When
        final List<TransactionTable> result =
                transactionRepository.findByUserIdAndUpdatedAfter(
                        testUserId, updatedAfterTimestamp, 10);

        // Then - Should fallback
        assertNotNull(result);
    }

    @Test
    void testFindByUserIdAndDateRangeWithResourceNotFoundExceptionFallsBack() {
        // Given
        final String startDate = "2024-01-01";
        final String endDate = "2024-01-31";
        testTransaction.setTransactionDate("2024-01-15");

        // Mock ResourceNotFoundException for GSI query (triggers fallback)
        when(userIdDateIndex.query(any(QueryConditional.class)))
                .thenThrow(
                        software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
                                .builder()
                                .build());

        // Mock transactionTable.scan() for the fallback in findByUserId
        // The fallback chain is: findByUserIdAndDateRange -> findByUserId -> scan()
        // scan() returns PageIterable<TransactionTable>, not SdkIterable
        final Page<TransactionTable> scanPage = mock(Page.class);
        when(scanPage.items())
                .thenReturn(
                        List.of(testTransaction)); // Return test transaction with matching userId
        @SuppressWarnings("unchecked")
        final PageIterable<TransactionTable> scanPages = mock(PageIterable.class);
        when(scanPages.iterator()).thenReturn(List.of(scanPage).iterator());
        when(transactionTable.scan()).thenReturn(scanPages);

        // When - Should handle exception gracefully and use fallback
        final List<TransactionTable> result =
                transactionRepository.findByUserIdAndDateRange(testUserId, startDate, endDate);

        // Then - Should return results from fallback scan (filtered by date range)
        assertNotNull(result);
        // The result should contain the transaction if it's within the date range
        // Since testTransaction has date "2024-01-15" and range is "2024-01-01" to "2024-01-31", it
        // should be included
        assertEquals(1, result.size(), "Should return transaction within date range via fallback");
        assertEquals(testTransactionId, result.getFirst().getTransactionId());
    }

    @Test
    void testFindByUserIdAndDateRangeWithInvalidDateFormatThrowsException() {
        // Given
        final String invalidStartDate = "2024/01/01"; // Wrong format
        final String endDate = "2024-01-31";

        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    transactionRepository.findByUserIdAndDateRange(
                            testUserId, invalidStartDate, endDate);
                });
    }

    @Test
    void testFindByUserIdAndDateRangeWithNullDatesReturnsEmpty() {
        // When
        final List<TransactionTable> result1 =
                transactionRepository.findByUserIdAndDateRange(testUserId, null, "2024-01-31");
        final List<TransactionTable> result2 =
                transactionRepository.findByUserIdAndDateRange(testUserId, "2024-01-01", null);

        // Then
        assertTrue(result1.isEmpty());
        assertTrue(result2.isEmpty());
    }

    @Test
    void testFindByUserIdAndDateRangeWithNullUserIdReturnsEmpty() {
        // When
        final List<TransactionTable> result =
                transactionRepository.findByUserIdAndDateRange(null, "2024-01-01", "2024-01-31");

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void testFindByUserIdAndDateRangeWithEmptyUserIdReturnsEmpty() {
        // When
        final List<TransactionTable> result =
                transactionRepository.findByUserIdAndDateRange("", "2024-01-01", "2024-01-31");

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void testFindByUserIdWithInvalidSkipAdjustsSkip() {
        // Given
        final Page<TransactionTable> page = mock(Page.class);
        when(page.items()).thenReturn(List.of(testTransaction));
        final SdkIterable<Page<TransactionTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(userIdDateIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When - Test with negative skip (should be adjusted to 0)
        final List<TransactionTable> result =
                transactionRepository.findByUserId(testUserId, -5, 10);

        // Then
        assertNotNull(result);
    }

    @Test
    void testFindByUserIdWithInvalidLimitAdjustsLimit() {
        // Given
        final Page<TransactionTable> page = mock(Page.class);
        when(page.items()).thenReturn(List.of(testTransaction));
        final SdkIterable<Page<TransactionTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(userIdDateIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When - Test with limit 0 (should default to 50)
        final List<TransactionTable> result1 = transactionRepository.findByUserId(testUserId, 0, 0);
        // When - Test with limit > 100 (should cap at 100)
        final List<TransactionTable> result2 =
                transactionRepository.findByUserId(testUserId, 0, 200);

        // Then
        assertNotNull(result1);
        assertNotNull(result2);
    }
}
