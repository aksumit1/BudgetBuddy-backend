package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.model.dynamodb.CustomMerchantMappingTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.CategoryLearningService;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.UserService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

/** Unit tests for CategoryController */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
class CategoryControllerTest {

    @Mock private CategoryLearningService learningService;

    @Mock private TransactionService transactionService;

    @Mock private UserService userService;

    @Mock private TransactionRepository transactionRepository;

    @Mock private UserDetails userDetails;

    private CategoryController controller;
    private UserTable testUser;
    private TransactionTable testTransaction;

    @BeforeEach
    void setUp() {
        controller =
                new CategoryController(
                        learningService, transactionService, userService, transactionRepository);

        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail("test@example.com");

        testTransaction = new TransactionTable();
        testTransaction.setTransactionId(UUID.randomUUID().toString());
        testTransaction.setUserId(testUser.getUserId());
        testTransaction.setMerchantName("Walmart");
        testTransaction.setCategoryPrimary("other");
        testTransaction.setCategoryDetailed("other");
        testTransaction.setTransactionType("EXPENSE");
        testTransaction.setDescription("WMT STORE");
        testTransaction.setAmount(new BigDecimal("-50.00"));
    }

    @Test
    void testRecordCorrectionSuccess() {
        // Setup
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(transactionRepository.findById(anyString())).thenReturn(Optional.of(testTransaction));
        when(transactionService.updateTransaction(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        anyBoolean(),
                        any(),
                        any()))
                .thenReturn(testTransaction);

        final CategoryController.CorrectionRequest request = new CategoryController.CorrectionRequest();
        request.setTransactionId(testTransaction.getTransactionId());
        request.setCategoryPrimary("groceries");
        request.setCategoryDetailed("groceries");
        request.setTransactionType(null);

        // Execute
        final ResponseEntity<Map<String, Object>> response =
                controller.recordCorrection(userDetails, request);

        // Verify
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
        // Verify recordCorrection was called with correct parameters
        // Signature: userId, transactionId, merchantName, originalCategoryPrimary,
        // originalCategoryDetailed,
        //            correctedCategoryPrimary, correctedCategoryDetailed, originalTransactionType,
        //            correctedTransactionType, description
        verify(learningService, times(1))
                .recordCorrection(
                        eq(testUser.getUserId()),
                        eq(testTransaction.getTransactionId()),
                        anyString(), // merchantName
                        anyString(), // originalCategoryPrimary
                        anyString(), // originalCategoryDetailed
                        eq("groceries"), // correctedCategoryPrimary
                        anyString(), // correctedCategoryDetailed
                        anyString(), // originalTransactionType
                        isNull(), // correctedTransactionType (null in request)
                        anyString() // description
                        );
    }

    @Test
    void testRecordCorrectionUnauthorized() {
        // Setup
        when(userDetails.getUsername()).thenReturn(null);

        final CategoryController.CorrectionRequest request = new CategoryController.CorrectionRequest();
        request.setTransactionId(UUID.randomUUID().toString());
        request.setCategoryPrimary("groceries");
        request.setCategoryDetailed("groceries");

        // Execute & Verify
        assertThrows(
                AppException.class,
                () -> {
                    controller.recordCorrection(userDetails, request);
                });
    }

    @Test
    void testCreateCustomMappingSuccess() {
        // Setup
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        final CustomMerchantMappingTable mapping = new CustomMerchantMappingTable();
        mapping.setMappingId(UUID.randomUUID().toString());
        mapping.setMerchantName("Test Merchant");
        mapping.setCategoryPrimary("groceries");

        when(learningService.createOrUpdateCustomMapping(
                        anyString(), anyString(), anyList(), anyString(), anyString(), any()))
                .thenReturn(mapping);

        final CategoryController.CustomMappingRequest request =
                new CategoryController.CustomMappingRequest();
        request.setMerchantName("Test Merchant");
        request.setAliases(List.of("tm", "test"));
        request.setCategoryPrimary("groceries");
        request.setCategoryDetailed("supermarket");
        request.setTransactionType(null);

        // Execute
        final ResponseEntity<CustomMerchantMappingTable> response =
                controller.createOrUpdateCustomMapping(userDetails, request);

        // Verify
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("groceries", response.getBody().getCategoryPrimary());
    }

    @Test
    void testGetCustomMappingsSuccess() {
        // Setup
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        final CustomMerchantMappingTable mapping = new CustomMerchantMappingTable();
        mapping.setMappingId(UUID.randomUUID().toString());
        mapping.setMerchantName("Test Merchant");
        mapping.setIsActive(true);

        when(learningService.getUserCustomMappings(anyString())).thenReturn(List.of(mapping));

        // Execute
        final ResponseEntity<List<CustomMerchantMappingTable>> response =
                controller.getCustomMappings(userDetails);

        // Verify
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void testDeleteCustomMappingSuccess() {
        // Setup
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        doNothing().when(learningService).deleteCustomMapping(anyString(), anyString());

        final String mappingId = UUID.randomUUID().toString();

        // Execute
        final ResponseEntity<Map<String, Object>> response =
                controller.deleteCustomMapping(userDetails, mappingId);

        // Verify
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
        verify(learningService, times(1)).deleteCustomMapping(testUser.getUserId(), mappingId);
    }
}
