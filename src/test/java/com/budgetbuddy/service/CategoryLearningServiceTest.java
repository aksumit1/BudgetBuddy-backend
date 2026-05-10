package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.CustomMerchantMappingTable;
import com.budgetbuddy.model.dynamodb.UserCorrectionTable;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

/** Unit tests for CategoryLearningService */
@ExtendWith(MockitoExtension.class)
class CategoryLearningServiceTest {

    @Mock private DynamoDbEnhancedClient dynamoDbClient;

    @Mock private DynamoDbTable<UserCorrectionTable> correctionTable;

    @Mock private DynamoDbTable<CustomMerchantMappingTable> customMappingTable;

    private CategoryLearningService learningService;

    @BeforeEach
    void setUp() {
        // Mock table() method to return our mocked tables
        when(dynamoDbClient.table(eq("UserCorrections"), any(TableSchema.class)))
                .thenReturn((DynamoDbTable) correctionTable);
        when(dynamoDbClient.table(eq("CustomMerchantMappings"), any(TableSchema.class)))
                .thenReturn((DynamoDbTable) customMappingTable);

        learningService = new CategoryLearningService(dynamoDbClient);
    }

    @Test
    void testRecordCorrectionNewCorrection() {
        // Test recording a new correction
        final String userId = UUID.randomUUID().toString();
        final String transactionId = UUID.randomUUID().toString();

        // Mock table operations
        doNothing().when(correctionTable).putItem(any(UserCorrectionTable.class));

        // Should not throw
        assertDoesNotThrow(
                () -> {
                    learningService.recordCorrection(
                            userId,
                            transactionId,
                            "Walmart",
                            "other",
                            "other",
                            "groceries",
                            "groceries",
                            "EXPENSE",
                            null,
                            "WMT STORE");
                });

        verify(correctionTable, times(1)).putItem(any(UserCorrectionTable.class));
    }

    @Test
    void testCreateCustomMappingNewMapping() {
        // Test creating a new custom mapping
        final String userId = UUID.randomUUID().toString();

        // Mock table operations
        doNothing().when(customMappingTable).putItem(any(CustomMerchantMappingTable.class));

        final CustomMerchantMappingTable mapping =
                learningService.createOrUpdateCustomMapping(
                        userId,
                        "Test Merchant",
                        List.of("test", "tm"),
                        "groceries",
                        "supermarket",
                        null);

        assertNotNull(mapping);
        assertEquals("Test Merchant", mapping.getMerchantName());
        assertEquals("groceries", mapping.getCategoryPrimary());
        verify(customMappingTable, times(1)).putItem(any(CustomMerchantMappingTable.class));
    }

    @Test
    void testGetCustomMappingNotFound() {
        // Test getting a mapping that doesn't exist
        final String userId = UUID.randomUUID().toString();

        final CustomMerchantMappingTable mapping =
                learningService.getCustomMapping(userId, "Unknown Merchant");

        assertNull(mapping); // Should return null if not found
    }

    @Test
    void testDeleteCustomMapping() {
        // Test deleting a custom mapping
        final String userId = UUID.randomUUID().toString();
        final String mappingId = UUID.randomUUID().toString();

        final CustomMerchantMappingTable mapping = new CustomMerchantMappingTable();
        mapping.setMappingId(mappingId);
        mapping.setUserId(userId);
        mapping.setIsActive(true);

        // Mock getItem with Key parameter - use any() matcher
        when(customMappingTable.getItem(any(Key.class))).thenReturn(mapping);
        when(customMappingTable.updateItem(any(CustomMerchantMappingTable.class)))
                .thenReturn(mapping);

        assertDoesNotThrow(
                () -> {
                    learningService.deleteCustomMapping(userId, mappingId);
                });

        verify(customMappingTable, times(1)).updateItem(any(CustomMerchantMappingTable.class));
        assertFalse(mapping.getIsActive()); // Should be soft-deleted
    }

    @Test
    void testRecordCorrectionHandlesException() {
        // Test that exceptions don't propagate (best effort)
        final String userId = UUID.randomUUID().toString();
        final String transactionId = UUID.randomUUID().toString();

        // Mock exception - use RuntimeException since DynamoDbException is abstract
        // Use any(UserCorrectionTable.class) to avoid ambiguity with Consumer<Builder<T>> overload
        doThrow(new RuntimeException("Database error"))
                .when(correctionTable)
                .putItem(any(UserCorrectionTable.class));

        // Should not throw - learning is best effort
        assertDoesNotThrow(
                () -> {
                    learningService.recordCorrection(
                            userId,
                            transactionId,
                            "Walmart",
                            "other",
                            "other",
                            "groceries",
                            "groceries",
                            "EXPENSE",
                            null,
                            "WMT STORE");
                });
    }
}
