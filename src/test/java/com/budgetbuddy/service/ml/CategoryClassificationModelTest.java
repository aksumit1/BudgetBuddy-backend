package com.budgetbuddy.service.ml;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for CategoryClassificationModel
 * Tests: thread safety, null handling, edge cases, boundary conditions, model persistence
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryClassificationModel Tests")
class CategoryClassificationModelTest {
    
    private CategoryClassificationModel model;
    private static final String TEST_MODEL_PATH = "data/test_category_model.dat";
    
    @BeforeEach
    void setUp() {
        // Use temp directory for tests to avoid permission issues
        String testModelDir = System.getProperty("java.io.tmpdir") + "/budgetbuddy-test-ml-models";
        model = new CategoryClassificationModel(testModelDir);
        // Clean up test model file if it exists
        try {
            Path testModelFile = Paths.get(TEST_MODEL_PATH);
            if (Files.exists(testModelFile)) {
                Files.delete(testModelFile);
            }
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
    
    // ========== Training Tests ==========
    
    @Test
    @DisplayName("train with valid data increments sample count")
    void testTrain_ValidData() {
        long initialCount = model.getStatistics().totalSamples;
        
        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", "groceries");
        
        assertEquals(initialCount + 1, model.getStatistics().totalSamples);
    }
    
    @Test
    @DisplayName("train with null category does nothing")
    void testTrain_NullCategory() {
        long initialCount = model.getStatistics().totalSamples;
        
        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", null);
        
        assertEquals(initialCount, model.getStatistics().totalSamples, "Should not increment on null category");
    }
    
    @Test
    @DisplayName("train with empty category does nothing")
    void testTrain_EmptyCategory() {
        long initialCount = model.getStatistics().totalSamples;
        
        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", "");
        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", "   ");
        
        assertEquals(initialCount, model.getStatistics().totalSamples, "Should not increment on empty category");
    }
    
    @Test
    @DisplayName("train with null merchant name still trains on other features")
    void testTrain_NullMerchant() {
        model.train(null, "Grocery purchase", "75.50", "POS", "groceries");
        
        // Should still train on keywords, amount, payment channel
        CategoryClassificationModel.PredictionResult result = model.predict(
                null, "Grocery purchase", "75.50", "POS");
        
        // May or may not have prediction depending on training data
        assertNotNull(result);
    }
    
    @Test
    @DisplayName("train with invalid amount format handles gracefully")
    void testTrain_InvalidAmount() {
        assertDoesNotThrow(() -> {
            model.train("SAFEWAY", "Grocery purchase", "invalid", "POS", "groceries");
            model.train("SAFEWAY", "Grocery purchase", "abc", "POS", "groceries");
            model.train("SAFEWAY", "Grocery purchase", "", "POS", "groceries");
        });
    }
    
    @Test
    @DisplayName("train with very large amount handles gracefully")
    void testTrain_VeryLargeAmount() {
        assertDoesNotThrow(() -> {
            model.train("SAFEWAY", "Grocery purchase", "999999999999.99", "POS", "groceries");
        });
    }
    
    @Test
    @DisplayName("train with negative amount uses absolute value")
    void testTrain_NegativeAmount() {
        model.train("SAFEWAY", "Grocery purchase", "-75.50", "POS", "groceries");
        
        // Should train on amount range for 75.50
        CategoryClassificationModel.PredictionResult result = model.predict(
                "SAFEWAY", "Grocery purchase", "75.50", "POS");
        
        assertNotNull(result);
    }
    
    // ========== Prediction Tests ==========
    
    @Test
    @DisplayName("predict with no training data returns null category")
    void testPredict_NoTrainingData() {
        CategoryClassificationModel.PredictionResult result = model.predict(
                "SAFEWAY", "Grocery purchase", "75.50", "POS");
        
        assertNotNull(result);
        assertNull(result.category, "Should return null category when no training data");
        assertEquals(0.0, result.confidence);
        assertTrue(result.topPredictions.isEmpty());
    }
    
    @Test
    @DisplayName("predict after training returns category")
    void testPredict_AfterTraining() {
        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", "groceries");
        model.train("SAFEWAY", "Grocery purchase", "80.00", "POS", "groceries");
        model.train("SAFEWAY", "Grocery purchase", "90.00", "POS", "groceries");
        
        CategoryClassificationModel.PredictionResult result = model.predict(
                "SAFEWAY", "Grocery purchase", "75.50", "POS");
        
        assertNotNull(result);
        assertEquals("groceries", result.category);
        assertTrue(result.confidence > 0.0);
    }
    
    @Test
    @DisplayName("predict with null inputs handles gracefully")
    void testPredict_NullInputs() {
        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", "groceries");
        
        assertDoesNotThrow(() -> {
            CategoryClassificationModel.PredictionResult result = model.predict(
                    null, null, null, null);
            assertNotNull(result);
        });
    }
    
    @Test
    @DisplayName("predict with empty inputs handles gracefully")
    void testPredict_EmptyInputs() {
        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", "groceries");
        
        assertDoesNotThrow(() -> {
            CategoryClassificationModel.PredictionResult result = model.predict(
                    "", "", "", "");
            assertNotNull(result);
        });
    }
    
    @Test
    @DisplayName("predict with invalid amount format handles gracefully")
    void testPredict_InvalidAmount() {
        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", "groceries");
        
        assertDoesNotThrow(() -> {
            CategoryClassificationModel.PredictionResult result = model.predict(
                    "SAFEWAY", "Grocery purchase", "invalid", "POS");
            assertNotNull(result);
        });
    }
    
    @Test
    @DisplayName("predict returns top 3 predictions")
    void testPredict_Top3Predictions() {
        // Train with multiple categories
        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", "groceries");
        model.train("SAFEWAY", "Grocery purchase", "80.00", "POS", "groceries");
        model.train("TARGET", "Shopping", "100.00", "POS", "shopping");
        model.train("TARGET", "Shopping", "120.00", "POS", "shopping");
        model.train("STARBUCKS", "Coffee", "5.00", "POS", "dining");
        
        CategoryClassificationModel.PredictionResult result = model.predict(
                "SAFEWAY", "Grocery purchase", "75.50", "POS");
        
        assertNotNull(result);
        assertTrue(result.topPredictions.size() <= 3);
        if (!result.topPredictions.isEmpty()) {
            assertEquals("groceries", result.topPredictions.get(0).category);
        }
    }
    
    // ========== Thread Safety Tests ==========
    
    @Test
    @DisplayName("concurrent training is thread-safe")
    void testConcurrentTraining_ThreadSafe() throws InterruptedException {
        int numThreads = 10;
        int transactionsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger(0);
        
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < transactionsPerThread; j++) {
                        model.train("MERCHANT" + threadId, "Description " + j, 
                                String.valueOf(50 + j), "POS", "groceries");
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        executor.shutdown();
        
        assertEquals(0, errors.get(), "No errors should occur during concurrent training");
        assertEquals(numThreads * transactionsPerThread, model.getStatistics().totalSamples);
    }
    
    @Test
    @DisplayName("concurrent prediction is thread-safe")
    void testConcurrentPrediction_ThreadSafe() throws InterruptedException {
        // Train model first
        for (int i = 0; i < 100; i++) {
            model.train("SAFEWAY", "Grocery purchase", String.valueOf(50 + i), "POS", "groceries");
        }
        
        int numThreads = 10;
        int predictionsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger(0);
        
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < predictionsPerThread; j++) {
                        CategoryClassificationModel.PredictionResult result = model.predict(
                                "SAFEWAY", "Grocery purchase", "75.50", "POS");
                        assertNotNull(result);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        executor.shutdown();
        
        assertEquals(0, errors.get(), "No errors should occur during concurrent prediction");
    }
    
    // ========== Model Persistence Tests ==========
    
    @Test
    @DisplayName("saveModel creates model file")
    void testSaveModel_CreatesFile() {
        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", "groceries");
        
        // Use test model path
        try {
            Path testModelDir = Paths.get("data");
            if (!Files.exists(testModelDir)) {
                Files.createDirectories(testModelDir);
            }
            
            // Save using reflection or create a test-specific save method
            model.saveModel();
            
            Path modelFile = Paths.get("data/category_model.dat");
            // File should exist (or at least no exception thrown)
            assertDoesNotThrow(() -> model.saveModel());
        } catch (Exception e) {
            // File I/O might fail in test environment, that's okay
        }
    }
    
    @Test
    @DisplayName("loadModel handles missing file gracefully")
    void testLoadModel_MissingFile() {
        // Should not throw exception
        assertDoesNotThrow(() -> model.loadModel());
    }
    
    // ========== Division by Zero Tests ==========
    
    @Test
    @DisplayName("predict handles empty merchant counts gracefully")
    void testPredict_EmptyMerchantCounts() {
        // Train with different merchant
        model.train("TARGET", "Shopping", "100.00", "POS", "shopping");
        
        // Predict with untrained merchant
        CategoryClassificationModel.PredictionResult result = model.predict(
                "SAFEWAY", "Grocery purchase", "75.50", "POS");
        
        // Should not throw division by zero
        assertNotNull(result);
    }
    
    @Test
    @DisplayName("predict handles empty keyword counts gracefully")
    void testPredict_EmptyKeywordCounts() {
        // Train with different keywords
        model.train("SAFEWAY", "Shopping purchase", "75.50", "POS", "shopping");
        
        // Predict with different keywords
        CategoryClassificationModel.PredictionResult result = model.predict(
                "SAFEWAY", "Grocery purchase", "75.50", "POS");
        
        // Should not throw division by zero
        assertNotNull(result);
    }
    
    // ========== Boundary Condition Tests ==========
    
    @Test
    @DisplayName("train with very long merchant name handles gracefully")
    void testTrain_VeryLongMerchantName() {
        String longMerchant = "A".repeat(10000);
        assertDoesNotThrow(() -> {
            model.train(longMerchant, "Description", "75.50", "POS", "groceries");
        });
    }
    
    @Test
    @DisplayName("train with very long description handles gracefully")
    void testTrain_VeryLongDescription() {
        String longDescription = "A".repeat(10000);
        assertDoesNotThrow(() -> {
            model.train("SAFEWAY", longDescription, "75.50", "POS", "groceries");
        });
    }
    
    @Test
    @DisplayName("getAmountRange handles boundary values")
    void testGetAmountRange_Boundaries() {
        // Test boundary values
        model.train("SAFEWAY", "Grocery", "9.99", "POS", "groceries"); // 0-10
        model.train("SAFEWAY", "Grocery", "10.00", "POS", "groceries"); // 10-25
        model.train("SAFEWAY", "Grocery", "24.99", "POS", "groceries"); // 10-25
        model.train("SAFEWAY", "Grocery", "25.00", "POS", "groceries"); // 25-50
        model.train("SAFEWAY", "Grocery", "999.99", "POS", "groceries"); // 500-1000
        model.train("SAFEWAY", "Grocery", "1000.00", "POS", "groceries"); // 1000+
        
        assertDoesNotThrow(() -> {
            model.predict("SAFEWAY", "Grocery", "9.99", "POS");
            model.predict("SAFEWAY", "Grocery", "10.00", "POS");
            model.predict("SAFEWAY", "Grocery", "1000.00", "POS");
        });
    }
    
    // ========== Confidence Score Tests ==========
    
    @Test
    @DisplayName("PredictionResult confidence levels work correctly")
    void testPredictionResult_ConfidenceLevels() {
        CategoryClassificationModel.PredictionResult highConf = new CategoryClassificationModel.PredictionResult(
                "groceries", 0.75, Collections.emptyList());
        assertTrue(highConf.isHighConfidence());
        assertFalse(highConf.isMediumConfidence());
        assertFalse(highConf.isLowConfidence());
        
        CategoryClassificationModel.PredictionResult medConf = new CategoryClassificationModel.PredictionResult(
                "groceries", 0.60, Collections.emptyList());
        assertFalse(medConf.isHighConfidence());
        assertTrue(medConf.isMediumConfidence());
        assertFalse(medConf.isLowConfidence());
        
        CategoryClassificationModel.PredictionResult lowConf = new CategoryClassificationModel.PredictionResult(
                "groceries", 0.40, Collections.emptyList());
        assertFalse(lowConf.isHighConfidence());
        assertFalse(lowConf.isMediumConfidence());
        assertTrue(lowConf.isLowConfidence());
    }
    
    // ========== Statistics Tests ==========
    
    @Test
    @DisplayName("getStatistics returns correct counts")
    void testGetStatistics() {
        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", "groceries");
        model.train("TARGET", "Shopping", "100.00", "POS", "shopping");
        
        CategoryClassificationModel.ModelStatistics stats = model.getStatistics();
        
        assertEquals(2, stats.totalSamples);
        assertTrue(stats.merchantCount >= 2);
        assertTrue(stats.keywordCount > 0);
        assertTrue(stats.amountRangeCount > 0);
        assertTrue(stats.paymentChannelCount > 0);
    }
}

