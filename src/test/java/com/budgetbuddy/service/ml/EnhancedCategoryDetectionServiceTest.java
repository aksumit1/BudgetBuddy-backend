package com.budgetbuddy.service.ml;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for EnhancedCategoryDetectionService
 * Tests: integration, error handling, edge cases, null handling
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EnhancedCategoryDetectionService Tests")
class EnhancedCategoryDetectionServiceTest {
    
    @Mock
    private FuzzyMatchingService fuzzyMatchingService;
    
    @Mock
    private CategoryClassificationModel mlModel;
    
    @Mock
    private SemanticMatchingService semanticMatchingService;
    
    private EnhancedCategoryDetectionService service;
    
    @BeforeEach
    void setUp() {
        service = new EnhancedCategoryDetectionService(fuzzyMatchingService, mlModel, semanticMatchingService);
    }
    
    // ========== Null Input Tests ==========
    
    @Test
    @DisplayName("detectCategory with null inputs handles gracefully")
    void testDetectCategory_NullInputs() {
        when(fuzzyMatchingService.findBestMatch(anyString(), anyList())).thenReturn(null);
        // CRITICAL: Service calls findBestSemanticMatchWithContext, not findBestSemanticMatch
        when(semanticMatchingService.findBestSemanticMatchWithContext(
                any(), any(), any(), any(), any(), any())).thenReturn(null);
        when(mlModel.predict(anyString(), anyString(), any(), anyString()))
                .thenReturn(new CategoryClassificationModel.PredictionResult(null, 0.0, java.util.Collections.emptyList()));
        
        EnhancedCategoryDetectionService.DetectionResult result = service.detectCategory(
                null, null, null, null, null);
        
        assertNotNull(result);
        assertEquals("NONE", result.method);
    }
    
    @Test
    @DisplayName("detectCategory with empty merchant name handles gracefully")
    void testDetectCategory_EmptyMerchant() {
        lenient().when(fuzzyMatchingService.findBestMatch(anyString(), anyList())).thenReturn(null);
        lenient().when(mlModel.predict(anyString(), anyString(), any(), anyString()))
                .thenReturn(new CategoryClassificationModel.PredictionResult("groceries", 0.5, java.util.Collections.emptyList()));
        
        EnhancedCategoryDetectionService.DetectionResult result = service.detectCategory(
                "", "Description", new BigDecimal("75.50"), "POS", null);
        
        assertNotNull(result);
    }
    
    // ========== Fuzzy Match Tests ==========
    
    @Test
    @DisplayName("detectCategory uses fuzzy match when available")
    void testDetectCategory_UsesFuzzyMatch() {
        FuzzyMatchingService.MatchResult fuzzyResult = new FuzzyMatchingService.MatchResult(
                "safeway", 0.90, 0.92, 0.88, 0.90);
        when(fuzzyMatchingService.findBestMatch(eq("SAFEWAY"), anyList())).thenReturn(fuzzyResult);
        // CRITICAL: Service calls findBestSemanticMatchWithContext, not findBestSemanticMatch
        when(semanticMatchingService.findBestSemanticMatchWithContext(
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(null);
        when(mlModel.predict(anyString(), anyString(), any(), anyString()))
                .thenReturn(new CategoryClassificationModel.PredictionResult(null, 0.0, java.util.Collections.emptyList()));
        
        EnhancedCategoryDetectionService.DetectionResult result = service.detectCategory(
                "SAFEWAY", "Description", new BigDecimal("75.50"), "POS", null);
        
        assertNotNull(result);
        assertEquals("groceries", result.category);
        assertEquals("FUZZY_MATCH", result.method);
        assertTrue(result.isHighConfidence());
    }
    
    @Test
    @DisplayName("detectCategory handles fuzzy match with null category")
    void testDetectCategory_FuzzyMatchNullCategory() {
        FuzzyMatchingService.MatchResult fuzzyResult = new FuzzyMatchingService.MatchResult(
                "unknown_merchant", 0.90, 0.92, 0.88, 0.90);
        when(fuzzyMatchingService.findBestMatch(anyString(), anyList())).thenReturn(fuzzyResult);
        // CRITICAL: Service calls findBestSemanticMatchWithContext, not findBestSemanticMatch
        when(semanticMatchingService.findBestSemanticMatchWithContext(
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(null);
        when(mlModel.predict(anyString(), anyString(), any(), anyString()))
                .thenReturn(new CategoryClassificationModel.PredictionResult("groceries", 0.5, java.util.Collections.emptyList()));
        
        // Should not throw NPE, should fall back to ML
        assertDoesNotThrow(() -> {
            EnhancedCategoryDetectionService.DetectionResult result = service.detectCategory(
                    "UNKNOWN", "Description", new BigDecimal("75.50"), "POS", null);
            assertNotNull(result);
        });
    }
    
    // ========== Semantic Matching Tests ==========
    
    @Test
    @DisplayName("detectCategory uses semantic match when available")
    void testDetectCategory_UsesSemanticMatch() {
        when(fuzzyMatchingService.findBestMatch(anyString(), anyList())).thenReturn(null);
        SemanticMatchingService.SemanticMatchResult semanticResult = 
            new SemanticMatchingService.SemanticMatchResult("groceries", 0.75, "SEMANTIC");
        // CRITICAL: Service calls findBestSemanticMatchWithContext, not findBestSemanticMatch
        when(semanticMatchingService.findBestSemanticMatchWithContext(
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(semanticResult);
        when(mlModel.predict(anyString(), anyString(), any(), anyString()))
                .thenReturn(new CategoryClassificationModel.PredictionResult(null, 0.0, java.util.Collections.emptyList()));
        
        EnhancedCategoryDetectionService.DetectionResult result = service.detectCategory(
                "Grocery Store", "Shopping", new BigDecimal("75.50"), "POS", null);
        
        assertNotNull(result);
        assertEquals("groceries", result.category);
        assertEquals("SEMANTIC_MATCH", result.method);
    }
    
    // ========== ML Prediction Tests ==========
    
    @Test
    @DisplayName("detectCategory uses ML prediction when fuzzy match unavailable")
    void testDetectCategory_UsesMLPrediction() {
        when(fuzzyMatchingService.findBestMatch(anyString(), anyList())).thenReturn(null);
        // CRITICAL: Service calls findBestSemanticMatchWithContext, not findBestSemanticMatch
        when(semanticMatchingService.findBestSemanticMatchWithContext(
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(null);
        CategoryClassificationModel.PredictionResult mlResult = new CategoryClassificationModel.PredictionResult(
                "groceries", 0.75, java.util.Collections.emptyList());
        when(mlModel.predict(anyString(), anyString(), any(), anyString())).thenReturn(mlResult);
        
        EnhancedCategoryDetectionService.DetectionResult result = service.detectCategory(
                "UNKNOWN", "Description", new BigDecimal("75.50"), "POS", null);
        
        assertNotNull(result);
        assertEquals("groceries", result.category);
        assertEquals("ML_PREDICTION", result.method);
    }
    
    @Test
    @DisplayName("detectCategory ignores ML prediction with low confidence")
    void testDetectCategory_IgnoresLowConfidenceML() {
        when(fuzzyMatchingService.findBestMatch(anyString(), anyList())).thenReturn(null);
        // CRITICAL: Service calls findBestSemanticMatchWithContext, not findBestSemanticMatch
        when(semanticMatchingService.findBestSemanticMatchWithContext(
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(null);
        CategoryClassificationModel.PredictionResult mlResult = new CategoryClassificationModel.PredictionResult(
                "groceries", 0.25, java.util.Collections.emptyList());
        when(mlModel.predict(anyString(), anyString(), any(), anyString())).thenReturn(mlResult);
        
        EnhancedCategoryDetectionService.DetectionResult result = service.detectCategory(
                "UNKNOWN", "Description", new BigDecimal("75.50"), "POS", null);
        
        assertNotNull(result);
        assertEquals("NONE", result.method);
    }
    
    // ========== Combined Results Tests ==========
    
    @Test
    @DisplayName("detectCategory combines fuzzy, semantic, and ML results")
    void testDetectCategory_CombinesResults() {
        FuzzyMatchingService.MatchResult fuzzyResult = new FuzzyMatchingService.MatchResult(
                "safeway", 0.90, 0.92, 0.88, 0.90);
        when(fuzzyMatchingService.findBestMatch(anyString(), anyList())).thenReturn(fuzzyResult);
        SemanticMatchingService.SemanticMatchResult semanticResult = 
            new SemanticMatchingService.SemanticMatchResult("groceries", 0.80, "SEMANTIC");
        // CRITICAL: Service calls findBestSemanticMatchWithContext, not findBestSemanticMatch
        when(semanticMatchingService.findBestSemanticMatchWithContext(
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(semanticResult);
        CategoryClassificationModel.PredictionResult mlResult = new CategoryClassificationModel.PredictionResult(
                "groceries", 0.80, java.util.Collections.emptyList());
        when(mlModel.predict(anyString(), anyString(), any(), anyString())).thenReturn(mlResult);
        
        EnhancedCategoryDetectionService.DetectionResult result = service.detectCategory(
                "SAFEWAY", "Description", new BigDecimal("75.50"), "POS", null);
        
        assertNotNull(result);
        assertEquals("groceries", result.category);
        assertEquals("COMBINED", result.method);
    }
    
    // ========== Error Handling Tests ==========
    
    @Test
    @DisplayName("detectCategory handles exceptions gracefully")
    void testDetectCategory_HandlesExceptions() {
        lenient().when(fuzzyMatchingService.findBestMatch(anyString(), anyList()))
                .thenThrow(new RuntimeException("Test exception"));
        // CRITICAL: Service calls findBestSemanticMatchWithContext, not findBestSemanticMatch
        lenient().when(semanticMatchingService.findBestSemanticMatchWithContext(
                anyString(), anyString(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Test exception"));
        lenient().when(mlModel.predict(anyString(), anyString(), any(), anyString()))
                .thenThrow(new RuntimeException("Test exception"));
        
        EnhancedCategoryDetectionService.DetectionResult result = service.detectCategory(
                "SAFEWAY", "Description", new BigDecimal("75.50"), "POS", null);
        
        assertNotNull(result);
        assertEquals("ERROR", result.method);
        assertNotNull(result.reason);
    }
    
    @Test
    @DisplayName("detectCategory handles very large amount")
    void testDetectCategory_VeryLargeAmount() {
        BigDecimal veryLargeAmount = new BigDecimal("999999999999.99");
        
        when(fuzzyMatchingService.findBestMatch(anyString(), anyList())).thenReturn(null);
        // CRITICAL: Service calls findBestSemanticMatchWithContext, not findBestSemanticMatch
        when(semanticMatchingService.findBestSemanticMatchWithContext(
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(null);
        when(mlModel.predict(anyString(), anyString(), any(), anyString()))
                .thenReturn(new CategoryClassificationModel.PredictionResult(null, 0.0, java.util.Collections.emptyList()));
        
        EnhancedCategoryDetectionService.DetectionResult result = service.detectCategory(
                "SAFEWAY", "Description", veryLargeAmount, "POS", null);
        
        assertNotNull(result);
        // Amount should be nulled out if too large
    }
    
    @Test
    @DisplayName("detectCategory handles very negative amount")
    void testDetectCategory_VeryNegativeAmount() {
        BigDecimal veryNegativeAmount = new BigDecimal("-999999999999.99");
        
        when(fuzzyMatchingService.findBestMatch(anyString(), anyList())).thenReturn(null);
        // CRITICAL: Service calls findBestSemanticMatchWithContext, not findBestSemanticMatch
        when(semanticMatchingService.findBestSemanticMatchWithContext(
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(null);
        when(mlModel.predict(anyString(), anyString(), any(), anyString()))
                .thenReturn(new CategoryClassificationModel.PredictionResult(null, 0.0, java.util.Collections.emptyList()));
        
        EnhancedCategoryDetectionService.DetectionResult result = service.detectCategory(
                "SAFEWAY", "Description", veryNegativeAmount, "POS", null);
        
        assertNotNull(result);
    }
    
    // ========== Training Tests ==========
    
    @Test
    @DisplayName("trainModel with valid data calls mlModel.train")
    void testTrainModel_ValidData() {
        service.trainModel("SAFEWAY", "Description", "75.50", "POS", "groceries");
        
        verify(mlModel, times(1)).train("SAFEWAY", "Description", "75.50", "POS", "groceries");
    }
    
    @Test
    @DisplayName("trainModel with null category does nothing")
    void testTrainModel_NullCategory() {
        service.trainModel("SAFEWAY", "Description", "75.50", "POS", null);
        
        verify(mlModel, never()).train(anyString(), anyString(), anyString(), anyString(), anyString());
    }
    
    @Test
    @DisplayName("trainModel handles exceptions gracefully")
    void testTrainModel_HandlesExceptions() {
        doThrow(new RuntimeException("Test exception")).when(mlModel)
                .train(anyString(), anyString(), anyString(), anyString(), anyString());
        
        // Should not throw
        assertDoesNotThrow(() -> {
            service.trainModel("SAFEWAY", "Description", "75.50", "POS", "groceries");
        });
    }
    
    // ========== Known Merchants Tests ==========
    
    @Test
    @DisplayName("addKnownMerchant adds merchant to database")
    void testAddKnownMerchant() {
        service.addKnownMerchant("NEW_MERCHANT", "groceries");
        
        // Verify it's in the known merchants (would need getter or test via detectCategory)
        // For now, just verify no exception
        assertDoesNotThrow(() -> {
            service.addKnownMerchant("ANOTHER_MERCHANT", "dining");
        });
    }
    
    @Test
    @DisplayName("addKnownMerchant with null inputs handles gracefully")
    void testAddKnownMerchant_NullInputs() {
        assertDoesNotThrow(() -> {
            service.addKnownMerchant(null, "groceries");
            service.addKnownMerchant("MERCHANT", null);
            service.addKnownMerchant(null, null);
        });
    }
    
    // ========== Confidence Level Tests ==========
    
    @Test
    @DisplayName("DetectionResult confidence levels work correctly")
    void testDetectionResult_ConfidenceLevels() {
        EnhancedCategoryDetectionService.DetectionResult high = new EnhancedCategoryDetectionService.DetectionResult(
                "groceries", 0.75, "FUZZY_MATCH", "Test");
        assertTrue(high.isHighConfidence());
        assertFalse(high.isMediumConfidence());
        assertFalse(high.isLowConfidence());
        
        EnhancedCategoryDetectionService.DetectionResult med = new EnhancedCategoryDetectionService.DetectionResult(
                "groceries", 0.60, "ML_PREDICTION", "Test");
        assertFalse(med.isHighConfidence());
        assertTrue(med.isMediumConfidence());
        assertFalse(med.isLowConfidence());
        
        EnhancedCategoryDetectionService.DetectionResult low = new EnhancedCategoryDetectionService.DetectionResult(
                "groceries", 0.40, "ML_PREDICTION", "Test");
        assertFalse(low.isHighConfidence());
        assertFalse(low.isMediumConfidence());
        assertTrue(low.isLowConfidence());
    }
}

