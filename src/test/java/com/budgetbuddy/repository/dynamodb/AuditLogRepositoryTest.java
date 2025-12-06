package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.compliance.AuditLogTable;
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
import software.amazon.awssdk.core.pagination.sync.SdkIterable;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for AuditLogRepository
 * Tests audit log storage and retrieval
 */
@ExtendWith(MockitoExtension.class)
class AuditLogRepositoryTest {

    @Mock
    private DynamoDbEnhancedClient enhancedClient;

    @Mock
    private DynamoDbTable<AuditLogTable> auditLogTable;

    @Mock
    private DynamoDbIndex<AuditLogTable> userIdCreatedAtIndex;

    private AuditLogRepository auditLogRepository;
    private String testUserId;
    private AuditLogTable testAuditLog;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        
        when(enhancedClient.table(anyString(), any(software.amazon.awssdk.enhanced.dynamodb.TableSchema.class)))
                .thenReturn(auditLogTable);
        when(auditLogTable.index("UserIdCreatedAtIndex")).thenReturn(userIdCreatedAtIndex);
        
        auditLogRepository = new AuditLogRepository(enhancedClient, "BudgetBuddy");
        
        testAuditLog = new AuditLogTable();
        testAuditLog.setUserId(testUserId);
        testAuditLog.setAction("USER_LOGIN");
        testAuditLog.setCreatedAt(System.currentTimeMillis());
    }

    @Test
    void testSave_WithValidAuditLog_SavesSuccessfully() {
        // Given
        doNothing().when(auditLogTable).putItem(any(AuditLogTable.class));

        // When
        auditLogRepository.save(testAuditLog);

        // Then
        verify(auditLogTable, times(1)).putItem(testAuditLog);
    }

    @Test
    void testFindByUserIdAndDateRange_WithValidRange_ReturnsLogs() {
        // Given
        Long startTimestamp = System.currentTimeMillis() - 86400000L; // 1 day ago
        Long endTimestamp = System.currentTimeMillis();
        
        Page<AuditLogTable> page = mock(Page.class);
        when(page.items()).thenReturn(List.of(testAuditLog));
        SdkIterable<Page<AuditLogTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(userIdCreatedAtIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        List<AuditLogTable> result = auditLogRepository.findByUserIdAndDateRange(testUserId, startTimestamp, endTimestamp);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(testUserId, result.get(0).getUserId());
    }

    @Test
    void testFindByUserIdAndDateRange_WithOutOfRangeLogs_FiltersCorrectly() {
        // Given
        Long startTimestamp = System.currentTimeMillis() - 86400000L; // 1 day ago
        Long endTimestamp = System.currentTimeMillis();
        
        // Create log outside range
        AuditLogTable oldLog = new AuditLogTable();
        oldLog.setUserId(testUserId);
        oldLog.setCreatedAt(startTimestamp - 1000); // Before start
        
        Page<AuditLogTable> page = mock(Page.class);
        when(page.items()).thenReturn(List.of(testAuditLog, oldLog));
        SdkIterable<Page<AuditLogTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(userIdCreatedAtIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        List<AuditLogTable> result = auditLogRepository.findByUserIdAndDateRange(testUserId, startTimestamp, endTimestamp);

        // Then
        assertNotNull(result);
        // Should only include logs within range
        assertEquals(1, result.size());
        assertTrue(result.get(0).getCreatedAt() >= startTimestamp);
        assertTrue(result.get(0).getCreatedAt() <= endTimestamp);
    }

    @Test
    void testFindByUserIdAndDateRange_WithNoLogs_ReturnsEmptyList() {
        // Given
        Long startTimestamp = System.currentTimeMillis() - 86400000L;
        Long endTimestamp = System.currentTimeMillis();
        
        Page<AuditLogTable> page = mock(Page.class);
        when(page.items()).thenReturn(Collections.emptyList());
        SdkIterable<Page<AuditLogTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(userIdCreatedAtIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        List<AuditLogTable> result = auditLogRepository.findByUserIdAndDateRange(testUserId, startTimestamp, endTimestamp);

        // Then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}

