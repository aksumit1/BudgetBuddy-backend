package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.DevicePinTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for DevicePinRepository
 * Tests PIN storage and retrieval logic
 */
@ExtendWith(MockitoExtension.class)
class DevicePinRepositoryTest {

    @Mock
    private DynamoDbEnhancedClient enhancedClient;

    @Mock
    private DynamoDbTable<DevicePinTable> devicePinTable;

    private DevicePinRepository devicePinRepository;

    private String testUserId;
    private String testDeviceId;
    private DevicePinTable testDevicePin;

    @BeforeEach
    void setUp() {
        testUserId = "user-123";
        testDeviceId = "device-456";

        // Create test device PIN
        testDevicePin = new DevicePinTable();
        testDevicePin.setUserId(testUserId);
        testDevicePin.setDeviceId(testDeviceId);
        testDevicePin.setPinHash("hashed-pin-123");
        testDevicePin.setCreatedAt(Instant.now());
        testDevicePin.setUpdatedAt(Instant.now());
        testDevicePin.setFailedAttempts(0);

        // Setup mocks - DevicePinRepository constructor requires enhancedClient
        when(enhancedClient.table(eq("BudgetBuddy-DevicePin"), any(TableSchema.class)))
                .thenReturn(devicePinTable);

        // Construct repository with mocks
        devicePinRepository = new DevicePinRepository(enhancedClient);
    }

    @Test
    void testFindByUserIdAndDeviceId_WithValidInput_ReturnsDevicePin() {
        // Given
        when(devicePinTable.getItem(any(GetItemEnhancedRequest.class)))
                .thenReturn(testDevicePin);

        // When
        Optional<DevicePinTable> result = devicePinRepository.findByUserIdAndDeviceId(testUserId, testDeviceId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(testUserId, result.get().getUserId());
        assertEquals(testDeviceId, result.get().getDeviceId());
        verify(devicePinTable).getItem(any(GetItemEnhancedRequest.class));
    }

    @Test
    void testFindByUserIdAndDeviceId_WithNullUserId_ReturnsEmpty() {
        // When
        Optional<DevicePinTable> result = devicePinRepository.findByUserIdAndDeviceId(null, testDeviceId);

        // Then
        assertFalse(result.isPresent());
        verify(devicePinTable, never()).getItem(any(GetItemEnhancedRequest.class));
    }

    @Test
    void testFindByUserIdAndDeviceId_WithEmptyUserId_ReturnsEmpty() {
        // When
        Optional<DevicePinTable> result = devicePinRepository.findByUserIdAndDeviceId("", testDeviceId);

        // Then
        assertFalse(result.isPresent());
        verify(devicePinTable, never()).getItem(any(GetItemEnhancedRequest.class));
    }

    @Test
    void testFindByUserIdAndDeviceId_WithNullDeviceId_ReturnsEmpty() {
        // When
        Optional<DevicePinTable> result = devicePinRepository.findByUserIdAndDeviceId(testUserId, null);

        // Then
        assertFalse(result.isPresent());
        verify(devicePinTable, never()).getItem(any(GetItemEnhancedRequest.class));
    }

    @Test
    void testFindByUserIdAndDeviceId_WithEmptyDeviceId_ReturnsEmpty() {
        // When
        Optional<DevicePinTable> result = devicePinRepository.findByUserIdAndDeviceId(testUserId, "");

        // Then
        assertFalse(result.isPresent());
        verify(devicePinTable, never()).getItem(any(GetItemEnhancedRequest.class));
    }

    @Test
    void testFindByUserIdAndDeviceId_WhenNotFound_ReturnsEmpty() {
        // Given
        when(devicePinTable.getItem(any(GetItemEnhancedRequest.class)))
                .thenReturn(null);

        // When
        Optional<DevicePinTable> result = devicePinRepository.findByUserIdAndDeviceId(testUserId, testDeviceId);

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testFindByUserIdAndDeviceId_WhenException_ReturnsEmpty() {
        // Given
        when(devicePinTable.getItem(any(GetItemEnhancedRequest.class)))
                .thenThrow(new RuntimeException("DynamoDB error"));

        // When
        Optional<DevicePinTable> result = devicePinRepository.findByUserIdAndDeviceId(testUserId, testDeviceId);

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testSave_WithValidDevicePin_SavesSuccessfully() {
        // When
        devicePinRepository.save(testDevicePin);

        // Then
        verify(devicePinTable).putItem(eq(testDevicePin));
    }

    @Test
    void testSave_WithNullDevicePin_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> devicePinRepository.save(null));
        verify(devicePinTable, never()).putItem(any(DevicePinTable.class));
    }

    @Test
    void testSave_WithNullUserId_ThrowsException() {
        // Given
        testDevicePin.setUserId(null);

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> devicePinRepository.save(testDevicePin));
        verify(devicePinTable, never()).putItem(any(DevicePinTable.class));
    }

    @Test
    void testSave_WithEmptyUserId_ThrowsException() {
        // Given
        testDevicePin.setUserId("");

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> devicePinRepository.save(testDevicePin));
        verify(devicePinTable, never()).putItem(any(DevicePinTable.class));
    }

    @Test
    void testSave_WithNullDeviceId_ThrowsException() {
        // Given
        testDevicePin.setDeviceId(null);

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> devicePinRepository.save(testDevicePin));
        verify(devicePinTable, never()).putItem(any(DevicePinTable.class));
    }

    @Test
    void testSave_WithEmptyDeviceId_ThrowsException() {
        // Given
        testDevicePin.setDeviceId("");

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> devicePinRepository.save(testDevicePin));
        verify(devicePinTable, never()).putItem(any(DevicePinTable.class));
    }

    @Test
    void testSave_WhenException_ThrowsRuntimeException() {
        // Given
        doThrow(new RuntimeException("DynamoDB error")).when(devicePinTable).putItem(any(DevicePinTable.class));

        // When/Then
        assertThrows(RuntimeException.class, () -> devicePinRepository.save(testDevicePin));
    }

    @Test
    void testDelete_WithValidInput_DeletesSuccessfully() {
        // When
        devicePinRepository.delete(testUserId, testDeviceId);

        // Then - deleteItem accepts Key directly
        verify(devicePinTable).deleteItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class));
    }

    @Test
    void testDelete_WithNullUserId_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> devicePinRepository.delete(null, testDeviceId));
        verify(devicePinTable, never()).deleteItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class));
    }

    @Test
    void testDelete_WithEmptyUserId_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> devicePinRepository.delete("", testDeviceId));
        verify(devicePinTable, never()).deleteItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class));
    }

    @Test
    void testDelete_WithNullDeviceId_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> devicePinRepository.delete(testUserId, null));
        verify(devicePinTable, never()).deleteItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class));
    }

    @Test
    void testDelete_WithEmptyDeviceId_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> devicePinRepository.delete(testUserId, ""));
        verify(devicePinTable, never()).deleteItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class));
    }

    @Test
    void testDelete_WhenException_ThrowsRuntimeException() {
        // Given
        doThrow(new RuntimeException("DynamoDB error")).when(devicePinTable).deleteItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class));

        // When/Then
        assertThrows(RuntimeException.class, () -> devicePinRepository.delete(testUserId, testDeviceId));
    }

    @Test
    void testInitializeTable_WhenTableNotFound_LogsWarning() {
        // Given - Create new repository with ResourceNotFoundException
        // The initializeTable() method calls enhancedClient.table() twice:
        // 1. First call in try block throws ResourceNotFoundException
        // 2. Second call in catch block succeeds
        ResourceNotFoundException notFoundException = ResourceNotFoundException.builder()
                .message("Table not found")
                .build();
        
        // Clear invocations from setUp() to only count calls from this test
        clearInvocations(enhancedClient);
        when(enhancedClient.table(eq("BudgetBuddy-DevicePin"), any(TableSchema.class)))
                .thenThrow(notFoundException)
                .thenReturn(devicePinTable);

        // When - Repository should handle the exception gracefully
        DevicePinRepository repo = new DevicePinRepository(enhancedClient);

        // Then - Repository should still be initialized (second call succeeds)
        // Note: The constructor calls initializeTable() which makes 2 calls (try + catch)
        // After clearInvocations, we should only see the 2 calls from this test's repository creation
        assertNotNull(repo);
        verify(enhancedClient, times(2)).table(eq("BudgetBuddy-DevicePin"), any(TableSchema.class));
    }
}

