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
}

