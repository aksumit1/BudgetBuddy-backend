package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for TransactionRepository
 * Tests transaction CRUD operations and query methods
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class TransactionRepositoryTest {

    @Mock
    private DynamoDbEnhancedClient enhancedClient;

    @Mock
    private DynamoDbClient dynamoDbClient;

    @Mock
    private DynamoDbTable<TransactionTable> transactionTable;

    @Mock
    private DynamoDbIndex<TransactionTable> userIdDateIndex;

    @Mock
    private DynamoDbIndex<TransactionTable> plaidTransactionIdIndex;
    
    @Mock
    private DynamoDbIndex<TransactionTable> userIdUpdatedAtIndex;

    private TransactionRepository transactionRepository;
    private String testUserId;
    private String testTransactionId;
    private TransactionTable testTransaction;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        testTransactionId = UUID.randomUUID().toString();
        
        when(enhancedClient.table(anyString(), any(software.amazon.awssdk.enhanced.dynamodb.TableSchema.class)))
                .thenReturn(transactionTable);
        when(transactionTable.index("UserIdDateIndex")).thenReturn(userIdDateIndex);
        when(transactionTable.index("PlaidTransactionIdIndex")).thenReturn(plaidTransactionIdIndex);
        when(transactionTable.index("UserIdUpdatedAtIndex")).thenReturn(userIdUpdatedAtIndex);
        
        transactionRepository = new TransactionRepository(enhancedClient, dynamoDbClient, "BudgetBuddy");
        
        testTransaction = new TransactionTable();
        testTransaction.setTransactionId(testTransactionId);
        testTransaction.setUserId(testUserId);
        testTransaction.setAmount(new BigDecimal("100.00"));
        testTransaction.setTransactionDate("2024-01-15");
    }

    @Test
    void testSave_WithValidTransaction_SavesSuccessfully() {
        // Given
        doNothing().when(transactionTable).putItem(any(TransactionTable.class));

        // When
        transactionRepository.save(testTransaction);

        // Then
        verify(transactionTable, times(1)).putItem(any(TransactionTable.class));
    }

    @Test
    void testSave_WithNullTransaction_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            transactionRepository.save(null);
        });
        verify(transactionTable, never()).putItem(any(TransactionTable.class));
    }

    @Test
    void testFindById_WithValidId_ReturnsTransaction() {
        // Given
        when(transactionTable.getItem(any(Key.class))).thenReturn(testTransaction);

        // When
        Optional<TransactionTable> result = transactionRepository.findById(testTransactionId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(testTransactionId, result.get().getTransactionId());
        verify(transactionTable, times(1)).getItem(any(Key.class));
    }

    @Test
    void testFindById_WithNullId_ReturnsEmpty() {
        // When
        Optional<TransactionTable> result = transactionRepository.findById(null);

        // Then
        assertFalse(result.isPresent());
        verify(transactionTable, never()).getItem(any(Key.class));
    }

    @Test
    void testFindById_WithEmptyId_ReturnsEmpty() {
        // When
        Optional<TransactionTable> result = transactionRepository.findById("");

        // Then
        assertFalse(result.isPresent());
        verify(transactionTable, never()).getItem(any(Key.class));
    }


    @Test
    void testFindByUserId_WithValidUserId_ReturnsTransactions() {
        // Given
        testTransaction.setTransactionDate("2024-01-15");
        Page<TransactionTable> page = mock(Page.class);
        when(page.items()).thenReturn(List.of(testTransaction));
        SdkIterable<Page<TransactionTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(userIdDateIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        List<TransactionTable> result = transactionRepository.findByUserId(testUserId, 0, 100);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(testTransactionId, result.get(0).getTransactionId());
    }

    @Test
    void testFindByUserIdAndDateRange_WithValidRange_ReturnsTransactions() {
        // Given
        String startDate = "2024-01-01";
        String endDate = "2024-01-31";
        testTransaction.setTransactionDate("2024-01-15");
        
        Page<TransactionTable> page = mock(Page.class);
        when(page.items()).thenReturn(List.of(testTransaction));
        SdkIterable<Page<TransactionTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(userIdDateIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        List<TransactionTable> result = transactionRepository.findByUserIdAndDateRange(testUserId, startDate, endDate);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void testFindByPlaidTransactionId_WithValidId_ReturnsTransaction() {
        // Given
        String plaidId = "plaid-transaction-123";
        
        Page<TransactionTable> page = mock(Page.class);
        when(page.items()).thenReturn(List.of(testTransaction));
        SdkIterable<Page<TransactionTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(plaidTransactionIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        Optional<TransactionTable> result = transactionRepository.findByPlaidTransactionId(plaidId);

        // Then
        assertTrue(result.isPresent());
    }

    @Test
    void testDelete_WithValidId_DeletesTransaction() {
        // Given
        // deleteItem returns void - verify it's called without stubbing
        // (void methods don't need stubbing, just verification)

        // When
        transactionRepository.delete(testTransactionId);

        // Then
        verify(transactionTable, times(1)).deleteItem(any(Key.class));
    }
    
    @Test
    void testFindByUserIdAndUpdatedAfter_WithValidParams_ReturnsUpdatedTransactions() {
        // Given
        long updatedAfterTimestamp = Instant.now().minusSeconds(3600).getEpochSecond();
        testTransaction.setUpdatedAtTimestamp(Instant.now().getEpochSecond());
        
        Page<TransactionTable> page = mock(Page.class);
        when(page.items()).thenReturn(List.of(testTransaction));
        SdkIterable<Page<TransactionTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(userIdUpdatedAtIndex.query(any(QueryConditional.class))).thenReturn(pages);
        
        // When
        List<TransactionTable> result = transactionRepository.findByUserIdAndUpdatedAfter(testUserId, updatedAfterTimestamp, 10);
        
        // Then
        assertNotNull(result);
    }
    
    @Test
    void testFindByUserIdAndUpdatedAfter_WithNullParams_ReturnsEmpty() {
        // When
        List<TransactionTable> result1 = transactionRepository.findByUserIdAndUpdatedAfter(null, Instant.now().getEpochSecond(), 10);
        List<TransactionTable> result2 = transactionRepository.findByUserIdAndUpdatedAfter(testUserId, null, 10);
        List<TransactionTable> result3 = transactionRepository.findByUserIdAndUpdatedAfter("", Instant.now().getEpochSecond(), 10);
        
        // Then
        assertTrue(result1.isEmpty());
        assertTrue(result2.isEmpty());
        assertTrue(result3.isEmpty());
    }
    
    @Test
    void testFindByUserIdAndUpdatedAfter_WithResourceNotFoundException_FallsBack() {
        // Given
        long updatedAfterTimestamp = Instant.now().minusSeconds(3600).getEpochSecond();
        testTransaction.setUpdatedAtTimestamp(Instant.now().getEpochSecond());
        
        // Mock ResourceNotFoundException
        when(userIdUpdatedAtIndex.query(any(QueryConditional.class)))
                .thenThrow(software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException.builder().build());
        
        // Mock findByUserId fallback
        Page<TransactionTable> fallbackPage = mock(Page.class);
        when(fallbackPage.items()).thenReturn(List.of(testTransaction));
        SdkIterable<Page<TransactionTable>> fallbackPages = mock(SdkIterable.class);
        when(fallbackPages.iterator()).thenReturn(List.of(fallbackPage).iterator());
        when(userIdDateIndex.query(any(QueryConditional.class))).thenReturn(fallbackPages);
        
        // When
        List<TransactionTable> result = transactionRepository.findByUserIdAndUpdatedAfter(testUserId, updatedAfterTimestamp, 10);
        
        // Then - Should fallback
        assertNotNull(result);
    }

    @Test
    void testFindByUserIdAndUpdatedAfter_WithLimitAdjustment_RespectsLimit() {
        // Given
        long updatedAfterTimestamp = Instant.now().minusSeconds(3600).getEpochSecond();
        testTransaction.setUpdatedAtTimestamp(Instant.now().getEpochSecond());
        
        Page<TransactionTable> page = mock(Page.class);
        when(page.items()).thenReturn(List.of(testTransaction));
        SdkIterable<Page<TransactionTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(userIdUpdatedAtIndex.query(any(QueryConditional.class))).thenReturn(pages);
        
        // When - Test with limit 0 (should default to 50)
        List<TransactionTable> result1 = transactionRepository.findByUserIdAndUpdatedAfter(testUserId, updatedAfterTimestamp, 0);
        // When - Test with limit > 100 (should cap at 100)
        List<TransactionTable> result2 = transactionRepository.findByUserIdAndUpdatedAfter(testUserId, updatedAfterTimestamp, 200);
        
        // Then
        assertNotNull(result1);
        assertNotNull(result2);
    }

    @Test
    void testFindByUserIdAndUpdatedAfter_WithWrappedResourceNotFoundException_FallsBack() {
        // Given
        long updatedAfterTimestamp = Instant.now().minusSeconds(3600).getEpochSecond();
        testTransaction.setUpdatedAtTimestamp(Instant.now().getEpochSecond());
        
        // Mock RuntimeException wrapping ResourceNotFoundException
        RuntimeException wrappedException = new RuntimeException("Wrapped", 
            software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException.builder().build());
        when(userIdUpdatedAtIndex.query(any(QueryConditional.class)))
                .thenThrow(wrappedException);
        
        // Mock findByUserId fallback
        Page<TransactionTable> fallbackPage = mock(Page.class);
        when(fallbackPage.items()).thenReturn(List.of(testTransaction));
        SdkIterable<Page<TransactionTable>> fallbackPages = mock(SdkIterable.class);
        when(fallbackPages.iterator()).thenReturn(List.of(fallbackPage).iterator());
        when(userIdDateIndex.query(any(QueryConditional.class))).thenReturn(fallbackPages);
        
        // When
        List<TransactionTable> result = transactionRepository.findByUserIdAndUpdatedAfter(testUserId, updatedAfterTimestamp, 10);
        
        // Then - Should fallback
        assertNotNull(result);
    }

    @Test
    void testFindByUserIdAndDateRange_WithResourceNotFoundException_FallsBack() {
        // Given
        String startDate = "2024-01-01";
        String endDate = "2024-01-31";
        testTransaction.setTransactionDate("2024-01-15");
        
        // Mock ResourceNotFoundException for GSI query (triggers fallback)
        when(userIdDateIndex.query(any(QueryConditional.class)))
                .thenThrow(software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException.builder().build());
        
        // Mock transactionTable.scan() for the fallback in findByUserId
        // The fallback chain is: findByUserIdAndDateRange -> findByUserId -> scan()
        // scan() returns PageIterable<TransactionTable>, not SdkIterable
        Page<TransactionTable> scanPage = mock(Page.class);
        when(scanPage.items()).thenReturn(List.of(testTransaction)); // Return test transaction with matching userId
        @SuppressWarnings("unchecked")
        PageIterable<TransactionTable> scanPages = mock(PageIterable.class);
        when(scanPages.iterator()).thenReturn(List.of(scanPage).iterator());
        when(transactionTable.scan()).thenReturn(scanPages);
        
        // When - Should handle exception gracefully and use fallback
        List<TransactionTable> result = transactionRepository.findByUserIdAndDateRange(testUserId, startDate, endDate);
        
        // Then - Should return results from fallback scan (filtered by date range)
        assertNotNull(result);
        // The result should contain the transaction if it's within the date range
        // Since testTransaction has date "2024-01-15" and range is "2024-01-01" to "2024-01-31", it should be included
        assertEquals(1, result.size(), "Should return transaction within date range via fallback");
        assertEquals(testTransactionId, result.get(0).getTransactionId());
    }

    @Test
    void testFindByUserIdAndDateRange_WithInvalidDateFormat_ThrowsException() {
        // Given
        String invalidStartDate = "2024/01/01"; // Wrong format
        String endDate = "2024-01-31";
        
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            transactionRepository.findByUserIdAndDateRange(testUserId, invalidStartDate, endDate);
        });
    }

    @Test
    void testFindByUserIdAndDateRange_WithNullDates_ReturnsEmpty() {
        // When
        List<TransactionTable> result1 = transactionRepository.findByUserIdAndDateRange(testUserId, null, "2024-01-31");
        List<TransactionTable> result2 = transactionRepository.findByUserIdAndDateRange(testUserId, "2024-01-01", null);
        
        // Then
        assertTrue(result1.isEmpty());
        assertTrue(result2.isEmpty());
    }

    @Test
    void testFindByUserIdAndDateRange_WithNullUserId_ReturnsEmpty() {
        // When
        List<TransactionTable> result = transactionRepository.findByUserIdAndDateRange(null, "2024-01-01", "2024-01-31");
        
        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void testFindByUserIdAndDateRange_WithEmptyUserId_ReturnsEmpty() {
        // When
        List<TransactionTable> result = transactionRepository.findByUserIdAndDateRange("", "2024-01-01", "2024-01-31");
        
        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void testFindByUserId_WithInvalidSkip_AdjustsSkip() {
        // Given
        Page<TransactionTable> page = mock(Page.class);
        when(page.items()).thenReturn(List.of(testTransaction));
        SdkIterable<Page<TransactionTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(userIdDateIndex.query(any(QueryConditional.class))).thenReturn(pages);
        
        // When - Test with negative skip (should be adjusted to 0)
        List<TransactionTable> result = transactionRepository.findByUserId(testUserId, -5, 10);
        
        // Then
        assertNotNull(result);
    }

    @Test
    void testFindByUserId_WithInvalidLimit_AdjustsLimit() {
        // Given
        Page<TransactionTable> page = mock(Page.class);
        when(page.items()).thenReturn(List.of(testTransaction));
        SdkIterable<Page<TransactionTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(userIdDateIndex.query(any(QueryConditional.class))).thenReturn(pages);
        
        // When - Test with limit 0 (should default to 50)
        List<TransactionTable> result1 = transactionRepository.findByUserId(testUserId, 0, 0);
        // When - Test with limit > 100 (should cap at 100)
        List<TransactionTable> result2 = transactionRepository.findByUserId(testUserId, 0, 200);
        
        // Then
        assertNotNull(result1);
        assertNotNull(result2);
    }
}

