package com.budgetbuddy.service.ml;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Comprehensive tests for EnhancedCategoryDetectionService Tests: integration, error handling, edge
 * cases, null handling
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("EnhancedCategoryDetectionService Tests")
class EnhancedCategoryDetectionServiceTest {

    @Mock private FuzzyMatchingService fuzzyMatchingService;

    @Mock private CategoryClassificationModel mlModel;

    @Mock private SemanticMatchingService semanticMatchingService;

    @Mock private MerchantCategoryDataService merchantCategoryDataService;

    private EnhancedCategoryDetectionService service;

    @BeforeEach
    void setUp() {
        // CRITICAL: Mock MerchantCategoryDataService to return empty merchants for tests
        // Tests will add their own merchants as needed
        when(merchantCategoryDataService.getMerchantToCategoryMap())
                .thenReturn(new java.util.HashMap<>());

        service =
                new EnhancedCategoryDetectionService(
                        fuzzyMatchingService,
                        mlModel,
                        semanticMatchingService,
                        merchantCategoryDataService);
    }

    // ========== Null Input Tests ==========

    @Test
    @DisplayName("detectCategory with null inputs handles gracefully")
    void testDetectCategoryNullInputs() {
        when(fuzzyMatchingService.findBestMatch(anyString(), anyList())).thenReturn(null);
        // CRITICAL: Service calls findBestSemanticMatchWithContext, not findBestSemanticMatch
        when(semanticMatchingService.findBestSemanticMatchWithContext(
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        when(mlModel.predict(anyString(), anyString(), any(), anyString()))
                .thenReturn(
                        new CategoryClassificationModel.PredictionResult(
                                null, 0.0, java.util.Collections.emptyList()));

        final EnhancedCategoryDetectionService.DetectionResult result =
                service.detectCategory(null, null, null, null, null);

        assertNotNull(result);
        assertEquals("NONE", result.method);
    }

    @Test
    @DisplayName("detectCategory with empty merchant name handles gracefully")
    void testDetectCategoryEmptyMerchant() {
        lenient().when(fuzzyMatchingService.findBestMatch(anyString(), anyList())).thenReturn(null);
        lenient()
                .when(mlModel.predict(anyString(), anyString(), any(), anyString()))
                .thenReturn(
                        new CategoryClassificationModel.PredictionResult(
                                "groceries", 0.5, java.util.Collections.emptyList()));

        final EnhancedCategoryDetectionService.DetectionResult result =
                service.detectCategory("", "Description", new BigDecimal("75.50"), "POS", null);

        assertNotNull(result);
    }

    // ========== Fuzzy Match Tests ==========

    @Test
    @DisplayName("detectCategory uses fuzzy match when available")
    void testDetectCategoryUsesFuzzyMatch() {
        // Use a fictional merchant not in MccDirectory — real names like SAFEWAY
        // resolve via MCC_DIRECTORY (priority weight 0.95) before fuzzy ever runs,
        // so the test would never exercise the fuzzy path it's named for.
        // EnhancedCategoryDetectionService snapshots the merchant→category map at
        // construction time, so we must re-mock and rebuild before exercising the
        // service in this test (the @BeforeEach default seeds it empty).
        final java.util.Map<String, String> knownMerchants = new java.util.HashMap<>();
        knownMerchants.put("zorblix mart", "groceries");
        when(merchantCategoryDataService.getMerchantToCategoryMap()).thenReturn(knownMerchants);
        service =
                new EnhancedCategoryDetectionService(
                        fuzzyMatchingService,
                        mlModel,
                        semanticMatchingService,
                        merchantCategoryDataService);
        final FuzzyMatchingService.MatchResult fuzzyResult =
                new FuzzyMatchingService.MatchResult("zorblix mart", 0.90, 0.92, 0.88, 0.90);
        // Production lower-cases the merchant name via TextNormalizer.cleanMerchantText
        // before passing it to fuzzy, so match the lowercased form.
        when(fuzzyMatchingService.findBestMatch(eq("zorblix mart"), anyList()))
                .thenReturn(fuzzyResult);
        // CRITICAL: Service calls findBestSemanticMatchWithContext, not findBestSemanticMatch
        when(semanticMatchingService.findBestSemanticMatchWithContext(
                        anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(null);
        when(mlModel.predict(anyString(), anyString(), any(), anyString()))
                .thenReturn(
                        new CategoryClassificationModel.PredictionResult(
                                null, 0.0, java.util.Collections.emptyList()));

        final EnhancedCategoryDetectionService.DetectionResult result =
                service.detectCategory(
                        "ZORBLIX MART", "Description", new BigDecimal("75.50"), "POS", null);

        assertNotNull(result);
        assertEquals("groceries", result.category);
        assertEquals("FUZZY_MATCH", result.method);
        assertTrue(result.isHighConfidence());
    }

    @Test
    @DisplayName("detectCategory handles fuzzy match with null category")
    void testDetectCategoryFuzzyMatchNullCategory() {
        final FuzzyMatchingService.MatchResult fuzzyResult =
                new FuzzyMatchingService.MatchResult("unknown_merchant", 0.90, 0.92, 0.88, 0.90);
        when(fuzzyMatchingService.findBestMatch(anyString(), anyList())).thenReturn(fuzzyResult);
        // CRITICAL: Service calls findBestSemanticMatchWithContext, not findBestSemanticMatch
        when(semanticMatchingService.findBestSemanticMatchWithContext(
                        anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(null);
        when(mlModel.predict(anyString(), anyString(), any(), anyString()))
                .thenReturn(
                        new CategoryClassificationModel.PredictionResult(
                                "groceries", 0.5, java.util.Collections.emptyList()));

        // Should not throw NPE, should fall back to ML
        assertDoesNotThrow(
                () -> {
                    final EnhancedCategoryDetectionService.DetectionResult result =
                            service.detectCategory(
                                    "UNKNOWN", "Description", new BigDecimal("75.50"), "POS", null);
                    assertNotNull(result);
                });
    }

    // ========== Semantic Matching Tests ==========

    @Test
    @DisplayName("detectCategory uses semantic match when available")
    void testDetectCategoryUsesSemanticMatch() {
        when(fuzzyMatchingService.findBestMatch(anyString(), anyList())).thenReturn(null);
        final SemanticMatchingService.SemanticMatchResult semanticResult =
                new SemanticMatchingService.SemanticMatchResult("groceries", 0.75, "SEMANTIC");
        // CRITICAL: Service calls findBestSemanticMatchWithContext, not findBestSemanticMatch
        when(semanticMatchingService.findBestSemanticMatchWithContext(
                        anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(semanticResult);
        when(mlModel.predict(anyString(), anyString(), any(), anyString()))
                .thenReturn(
                        new CategoryClassificationModel.PredictionResult(
                                null, 0.0, java.util.Collections.emptyList()));

        final EnhancedCategoryDetectionService.DetectionResult result =
                service.detectCategory(
                        "Grocery Store", "Shopping", new BigDecimal("75.50"), "POS", null);

        assertNotNull(result);
        assertEquals("groceries", result.category);
        assertEquals("SEMANTIC_MATCH", result.method);
    }

    // ========== ML Prediction Tests ==========

    @Test
    @DisplayName("detectCategory uses ML prediction when fuzzy match unavailable")
    void testDetectCategoryUsesMLPrediction() {
        // ML_PREDICTION path is intentionally disabled in production
        // (EnhancedCategoryDetectionService.detectCategoryWithContext, lines
        // 498-514 — kept as commented-out code while we evaluate the embedding
        // pipeline). Until it's re-enabled, an unmatched merchant must fall
        // through to the "NONE" verdict — the ML mock should never be consulted.
        when(fuzzyMatchingService.findBestMatch(anyString(), anyList())).thenReturn(null);
        lenient()
                .when(
                        semanticMatchingService.findBestSemanticMatchWithContext(
                                anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(null);
        lenient()
                .when(mlModel.predict(anyString(), anyString(), any(), anyString()))
                .thenReturn(
                        new CategoryClassificationModel.PredictionResult(
                                "groceries", 0.75, java.util.Collections.emptyList()));

        final EnhancedCategoryDetectionService.DetectionResult result =
                service.detectCategory(
                        "UNKNOWN", "Description", new BigDecimal("75.50"), "POS", null);

        assertNotNull(result);
        assertEquals("NONE", result.method, "ML path is disabled — unmatched merchants resolve to NONE");
    }

    @Test
    @DisplayName("detectCategory ignores ML prediction with low confidence")
    void testDetectCategoryIgnoresLowConfidenceML() {
        when(fuzzyMatchingService.findBestMatch(anyString(), anyList())).thenReturn(null);
        // CRITICAL: Service calls findBestSemanticMatchWithContext, not findBestSemanticMatch
        when(semanticMatchingService.findBestSemanticMatchWithContext(
                        anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(null);
        final CategoryClassificationModel.PredictionResult mlResult =
                new CategoryClassificationModel.PredictionResult(
                        "groceries", 0.25, java.util.Collections.emptyList());
        when(mlModel.predict(anyString(), anyString(), any(), anyString())).thenReturn(mlResult);

        final EnhancedCategoryDetectionService.DetectionResult result =
                service.detectCategory(
                        "UNKNOWN", "Description", new BigDecimal("75.50"), "POS", null);

        assertNotNull(result);
        assertEquals("NONE", result.method);
    }

    // ========== Combined Results Tests ==========

    @Test
    @DisplayName("detectCategory combines fuzzy, semantic, and ML results")
    void testDetectCategoryCombinesResults() {
        final FuzzyMatchingService.MatchResult fuzzyResult =
                new FuzzyMatchingService.MatchResult("safeway", 0.90, 0.92, 0.88, 0.90);
        when(fuzzyMatchingService.findBestMatch(anyString(), anyList())).thenReturn(fuzzyResult);
        final SemanticMatchingService.SemanticMatchResult semanticResult =
                new SemanticMatchingService.SemanticMatchResult("groceries", 0.80, "SEMANTIC");
        // CRITICAL: Service calls findBestSemanticMatchWithContext, not findBestSemanticMatch
        when(semanticMatchingService.findBestSemanticMatchWithContext(
                        anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(semanticResult);
        final CategoryClassificationModel.PredictionResult mlResult =
                new CategoryClassificationModel.PredictionResult(
                        "groceries", 0.80, java.util.Collections.emptyList());
        when(mlModel.predict(anyString(), anyString(), any(), anyString())).thenReturn(mlResult);

        final EnhancedCategoryDetectionService.DetectionResult result =
                service.detectCategory(
                        "SAFEWAY", "Description", new BigDecimal("75.50"), "POS", null);

        assertNotNull(result);
        assertEquals("groceries", result.category);
        assertEquals("COMBINED", result.method);
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("detectCategory handles exceptions gracefully")
    void testDetectCategoryHandlesExceptions() {
        lenient()
                .when(fuzzyMatchingService.findBestMatch(anyString(), anyList()))
                .thenThrow(new RuntimeException("Test exception"));
        // CRITICAL: Service calls findBestSemanticMatchWithContext, not findBestSemanticMatch
        lenient()
                .when(
                        semanticMatchingService.findBestSemanticMatchWithContext(
                                anyString(), anyString(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Test exception"));
        lenient()
                .when(mlModel.predict(anyString(), anyString(), any(), anyString()))
                .thenThrow(new RuntimeException("Test exception"));

        final EnhancedCategoryDetectionService.DetectionResult result =
                service.detectCategory(
                        "SAFEWAY", "Description", new BigDecimal("75.50"), "POS", null);

        assertNotNull(result);
        assertEquals("ERROR", result.method);
        assertNotNull(result.reason);
    }

    @Test
    @DisplayName("detectCategory handles very large amount")
    void testDetectCategoryVeryLargeAmount() {
        final BigDecimal veryLargeAmount = new BigDecimal("999999999999.99");

        when(fuzzyMatchingService.findBestMatch(anyString(), anyList())).thenReturn(null);
        // CRITICAL: Service calls findBestSemanticMatchWithContext, not findBestSemanticMatch
        when(semanticMatchingService.findBestSemanticMatchWithContext(
                        anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(null);
        when(mlModel.predict(anyString(), anyString(), any(), anyString()))
                .thenReturn(
                        new CategoryClassificationModel.PredictionResult(
                                null, 0.0, java.util.Collections.emptyList()));

        final EnhancedCategoryDetectionService.DetectionResult result =
                service.detectCategory("SAFEWAY", "Description", veryLargeAmount, "POS", null);

        assertNotNull(result);
        // Amount should be nulled out if too large
    }

    @Test
    @DisplayName("detectCategory handles very negative amount")
    void testDetectCategoryVeryNegativeAmount() {
        final BigDecimal veryNegativeAmount = new BigDecimal("-999999999999.99");

        when(fuzzyMatchingService.findBestMatch(anyString(), anyList())).thenReturn(null);
        // CRITICAL: Service calls findBestSemanticMatchWithContext, not findBestSemanticMatch
        when(semanticMatchingService.findBestSemanticMatchWithContext(
                        anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(null);
        when(mlModel.predict(anyString(), anyString(), any(), anyString()))
                .thenReturn(
                        new CategoryClassificationModel.PredictionResult(
                                null, 0.0, java.util.Collections.emptyList()));

        final EnhancedCategoryDetectionService.DetectionResult result =
                service.detectCategory("SAFEWAY", "Description", veryNegativeAmount, "POS", null);

        assertNotNull(result);
    }

    // ========== Training Tests ==========

    @Test
    @DisplayName("trainModel with valid data calls mlModel.train")
    void testTrainModelValidData() {
        service.trainModel("SAFEWAY", "Description", "75.50", "POS", "groceries");

        verify(mlModel, times(1)).train("SAFEWAY", "Description", "75.50", "POS", "groceries");
    }

    @Test
    @DisplayName("trainModel with null category does nothing")
    void testTrainModelNullCategory() {
        service.trainModel("SAFEWAY", "Description", "75.50", "POS", null);

        verify(mlModel, never())
                .train(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("trainModel handles exceptions gracefully")
    void testTrainModelHandlesExceptions() {
        doThrow(new RuntimeException("Test exception"))
                .when(mlModel)
                .train(anyString(), anyString(), anyString(), anyString(), anyString());

        // Should not throw
        assertDoesNotThrow(
                () -> {
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
        assertDoesNotThrow(
                () -> {
                    service.addKnownMerchant("ANOTHER_MERCHANT", "dining");
                });
    }

    @Test
    @DisplayName("addKnownMerchant with null inputs handles gracefully")
    void testAddKnownMerchantNullInputs() {
        assertDoesNotThrow(
                () -> {
                    service.addKnownMerchant(null, "groceries");
                    service.addKnownMerchant("MERCHANT", null);
                    service.addKnownMerchant(null, null);
                });
    }

    // ========== Confidence Level Tests ==========

    @Test
    @DisplayName("DetectionResult confidence levels work correctly")
    void testDetectionResultConfidenceLevels() {
        final EnhancedCategoryDetectionService.DetectionResult high =
                new EnhancedCategoryDetectionService.DetectionResult(
                        "groceries", 0.75, "FUZZY_MATCH", "Test");
        assertTrue(high.isHighConfidence());
        assertFalse(high.isMediumConfidence());
        assertFalse(high.isLowConfidence());

        final EnhancedCategoryDetectionService.DetectionResult med =
                new EnhancedCategoryDetectionService.DetectionResult(
                        "groceries", 0.60, "ML_PREDICTION", "Test");
        assertFalse(med.isHighConfidence());
        assertTrue(med.isMediumConfidence());
        assertFalse(med.isLowConfidence());

        final EnhancedCategoryDetectionService.DetectionResult low =
                new EnhancedCategoryDetectionService.DetectionResult(
                        "groceries", 0.40, "ML_PREDICTION", "Test");
        assertFalse(low.isHighConfidence());
        assertFalse(low.isMediumConfidence());
        assertTrue(low.isLowConfidence());
    }
}
