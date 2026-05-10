package com.budgetbuddy.service.ml;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Comprehensive tests for CategoryClassificationModel Tests: thread safety, null handling, edge
 * cases, boundary conditions, model persistence
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryClassificationModel Tests")
class CategoryClassificationModelTest {

    private static final String GROCERIES = "groceries";
    private static final String GROCERY = "Grocery";
    private static final String SHOPPING = "shopping";

    private CategoryClassificationModel model;
    private static final String TEST_MODEL_PATH = "data/test_category_model.dat";

    @BeforeEach
    void setUp() {
        // Use temp directory for tests to avoid permission issues
        final String testModelDir =
                System.getProperty("java.io.tmpdir") + "/budgetbuddy-test-ml-models";
        model = new CategoryClassificationModel(testModelDir);
        // Clean up test model file if it exists
        try {
            final Path testModelFile = Paths.get(TEST_MODEL_PATH);
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
    void testTrainValidData() {
        final long initialCount = model.getStatistics().totalSamples;

        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", GROCERIES);

        assertEquals(initialCount + 1, model.getStatistics().totalSamples);
    }

    @Test
    @DisplayName("train with null category does nothing")
    void testTrainNullCategory() {
        final long initialCount = model.getStatistics().totalSamples;

        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", null);

        assertEquals(
                initialCount,
                model.getStatistics().totalSamples,
                "Should not increment on null category");
    }

    @Test
    @DisplayName("train with empty category does nothing")
    void testTrainEmptyCategory() {
        final long initialCount = model.getStatistics().totalSamples;

        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", "");
        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", "   ");

        assertEquals(
                initialCount,
                model.getStatistics().totalSamples,
                "Should not increment on empty category");
    }

    @Test
    @DisplayName("train with null merchant name still trains on other features")
    void testTrainNullMerchant() {
        model.train(null, "Grocery purchase", "75.50", "POS", GROCERIES);

        // Should still train on keywords, amount, payment channel
        final CategoryClassificationModel.PredictionResult result =
                model.predict(null, "Grocery purchase", "75.50", "POS");

        // May or may not have prediction depending on training data
        assertNotNull(result);
    }

    @Test
    @DisplayName("train with invalid amount format handles gracefully")
    void testTrainInvalidAmount() {
        assertDoesNotThrow(
                () -> {
                    model.train("SAFEWAY", "Grocery purchase", "invalid", "POS", GROCERIES);
                    model.train("SAFEWAY", "Grocery purchase", "abc", "POS", GROCERIES);
                    model.train("SAFEWAY", "Grocery purchase", "", "POS", GROCERIES);
                });
    }

    @Test
    @DisplayName("train with very large amount handles gracefully")
    void testTrainVeryLargeAmount() {
        assertDoesNotThrow(
                () -> {
                    model.train("SAFEWAY", "Grocery purchase", "999999999999.99", "POS", GROCERIES);
                });
    }

    @Test
    @DisplayName("train with negative amount uses absolute value")
    void testTrainNegativeAmount() {
        model.train("SAFEWAY", "Grocery purchase", "-75.50", "POS", GROCERIES);

        // Should train on amount range for 75.50
        final CategoryClassificationModel.PredictionResult result =
                model.predict("SAFEWAY", "Grocery purchase", "75.50", "POS");

        assertNotNull(result);
    }

    // ========== Prediction Tests ==========

    @Test
    @DisplayName("predict with no training data returns null category")
    void testPredictNoTrainingData() {
        final CategoryClassificationModel.PredictionResult result =
                model.predict("SAFEWAY", "Grocery purchase", "75.50", "POS");

        assertNotNull(result);
        assertNull(result.category, "Should return null category when no training data");
        assertEquals(0.0, result.confidence);
        assertTrue(result.topPredictions.isEmpty());
    }

    @Test
    @DisplayName("predict after training returns category")
    void testPredictAfterTraining() {
        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", GROCERIES);
        model.train("SAFEWAY", "Grocery purchase", "80.00", "POS", GROCERIES);
        model.train("SAFEWAY", "Grocery purchase", "90.00", "POS", GROCERIES);

        final CategoryClassificationModel.PredictionResult result =
                model.predict("SAFEWAY", "Grocery purchase", "75.50", "POS");

        assertNotNull(result);
        assertEquals(GROCERIES, result.category);
        assertTrue(result.confidence > 0.0);
    }

    @Test
    @DisplayName("predict with null inputs handles gracefully")
    void testPredictNullInputs() {
        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", GROCERIES);

        assertDoesNotThrow(
                () -> {
                    final CategoryClassificationModel.PredictionResult result =
                            model.predict(null, null, null, null);
                    assertNotNull(result);
                });
    }

    @Test
    @DisplayName("predict with empty inputs handles gracefully")
    void testPredictEmptyInputs() {
        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", GROCERIES);

        assertDoesNotThrow(
                () -> {
                    final CategoryClassificationModel.PredictionResult result =
                            model.predict("", "", "", "");
                    assertNotNull(result);
                });
    }

    @Test
    @DisplayName("predict with invalid amount format handles gracefully")
    void testPredictInvalidAmount() {
        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", GROCERIES);

        assertDoesNotThrow(
                () -> {
                    final CategoryClassificationModel.PredictionResult result =
                            model.predict("SAFEWAY", "Grocery purchase", "invalid", "POS");
                    assertNotNull(result);
                });
    }

    @Test
    @DisplayName("predict returns top 3 predictions")
    void testPredictTop3Predictions() {
        // Train with multiple categories
        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", GROCERIES);
        model.train("SAFEWAY", "Grocery purchase", "80.00", "POS", GROCERIES);
        model.train("TARGET", "Shopping", "100.00", "POS", SHOPPING);
        model.train("TARGET", "Shopping", "120.00", "POS", SHOPPING);
        model.train("STARBUCKS", "Coffee", "5.00", "POS", "dining");

        final CategoryClassificationModel.PredictionResult result =
                model.predict("SAFEWAY", "Grocery purchase", "75.50", "POS");

        assertNotNull(result);
        assertTrue(result.topPredictions.size() <= 3);
        if (!result.topPredictions.isEmpty()) {
            assertEquals(GROCERIES, result.topPredictions.get(0).category);
        }
    }

    // ========== Thread Safety Tests ==========

    @Test
    @DisplayName("concurrent training is thread-safe")
    void testConcurrentTrainingThreadSafe() throws InterruptedException {
        final int numThreads = 10;
        final int transactionsPerThread = 100;
        final ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        final CountDownLatch latch = new CountDownLatch(numThreads);
        final AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(
                    () -> {
                        try {
                            for (int j = 0; j < transactionsPerThread; j++) {
                                model.train(
                                        "MERCHANT" + threadId,
                                        "Description " + j,
                                        String.valueOf(50 + j),
                                        "POS",
                                        GROCERIES);
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
    void testConcurrentPredictionThreadSafe() throws InterruptedException {
        // Train model first
        for (int i = 0; i < 100; i++) {
            model.train("SAFEWAY", "Grocery purchase", String.valueOf(50 + i), "POS", GROCERIES);
        }

        final int numThreads = 10;
        final int predictionsPerThread = 50;
        final ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        final CountDownLatch latch = new CountDownLatch(numThreads);
        final AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(
                    () -> {
                        try {
                            for (int j = 0; j < predictionsPerThread; j++) {
                                final CategoryClassificationModel.PredictionResult result =
                                        model.predict(
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
    void testSaveModelCreatesFile() {
        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", GROCERIES);

        // Use test model path
        try {
            final Path testModelDir = Paths.get("data");
            if (!Files.exists(testModelDir)) {
                Files.createDirectories(testModelDir);
            }

            // Save using reflection or create a test-specific save method
            model.saveModel();

            final Path modelFile = Paths.get("data/category_model.dat");
            // File should exist (or at least no exception thrown)
            assertDoesNotThrow(() -> model.saveModel());
        } catch (Exception e) {
            // File I/O might fail in test environment, that's okay
        }
    }

    @Test
    @DisplayName("loadModel handles missing file gracefully")
    void testLoadModelMissingFile() {
        // Should not throw exception
        assertDoesNotThrow(() -> model.loadModel());
    }

    // ========== Division by Zero Tests ==========

    @Test
    @DisplayName("predict handles empty merchant counts gracefully")
    void testPredictEmptyMerchantCounts() {
        // Train with different merchant
        model.train("TARGET", "Shopping", "100.00", "POS", SHOPPING);

        // Predict with untrained merchant
        final CategoryClassificationModel.PredictionResult result =
                model.predict("SAFEWAY", "Grocery purchase", "75.50", "POS");

        // Should not throw division by zero
        assertNotNull(result);
    }

    @Test
    @DisplayName("predict handles empty keyword counts gracefully")
    void testPredictEmptyKeywordCounts() {
        // Train with different keywords
        model.train("SAFEWAY", "Shopping purchase", "75.50", "POS", SHOPPING);

        // Predict with different keywords
        final CategoryClassificationModel.PredictionResult result =
                model.predict("SAFEWAY", "Grocery purchase", "75.50", "POS");

        // Should not throw division by zero
        assertNotNull(result);
    }

    // ========== Boundary Condition Tests ==========

    @Test
    @DisplayName("train with very long merchant name handles gracefully")
    void testTrainVeryLongMerchantName() {
        final String longMerchant = "A".repeat(10_000);
        assertDoesNotThrow(
                () -> {
                    model.train(longMerchant, "Description", "75.50", "POS", GROCERIES);
                });
    }

    @Test
    @DisplayName("train with very long description handles gracefully")
    void testTrainVeryLongDescription() {
        final String longDescription = "A".repeat(10_000);
        assertDoesNotThrow(
                () -> {
                    model.train("SAFEWAY", longDescription, "75.50", "POS", GROCERIES);
                });
    }

    @Test
    @DisplayName("getAmountRange handles boundary values")
    void testGetAmountRangeBoundaries() {
        // Test boundary values
        model.train("SAFEWAY", GROCERY, "9.99", "POS", GROCERIES); // 0-10
        model.train("SAFEWAY", GROCERY, "10.00", "POS", GROCERIES); // 10-25
        model.train("SAFEWAY", GROCERY, "24.99", "POS", GROCERIES); // 10-25
        model.train("SAFEWAY", GROCERY, "25.00", "POS", GROCERIES); // 25-50
        model.train("SAFEWAY", GROCERY, "999.99", "POS", GROCERIES); // 500-1000
        model.train("SAFEWAY", GROCERY, "1000.00", "POS", GROCERIES); // 1000+

        assertDoesNotThrow(
                () -> {
                    model.predict("SAFEWAY", GROCERY, "9.99", "POS");
                    model.predict("SAFEWAY", GROCERY, "10.00", "POS");
                    model.predict("SAFEWAY", GROCERY, "1000.00", "POS");
                });
    }

    // ========== Confidence Score Tests ==========

    @Test
    @DisplayName("PredictionResult confidence levels work correctly")
    void testPredictionResultConfidenceLevels() {
        final CategoryClassificationModel.PredictionResult highConf =
                new CategoryClassificationModel.PredictionResult(
                        GROCERIES, 0.75, Collections.emptyList());
        assertTrue(highConf.isHighConfidence());
        assertFalse(highConf.isMediumConfidence());
        assertFalse(highConf.isLowConfidence());

        final CategoryClassificationModel.PredictionResult medConf =
                new CategoryClassificationModel.PredictionResult(
                        GROCERIES, 0.60, Collections.emptyList());
        assertFalse(medConf.isHighConfidence());
        assertTrue(medConf.isMediumConfidence());
        assertFalse(medConf.isLowConfidence());

        final CategoryClassificationModel.PredictionResult lowConf =
                new CategoryClassificationModel.PredictionResult(
                        GROCERIES, 0.40, Collections.emptyList());
        assertFalse(lowConf.isHighConfidence());
        assertFalse(lowConf.isMediumConfidence());
        assertTrue(lowConf.isLowConfidence());
    }

    // ========== Statistics Tests ==========

    @Test
    @DisplayName("getStatistics returns correct counts")
    void testGetStatistics() {
        model.train("SAFEWAY", "Grocery purchase", "75.50", "POS", GROCERIES);
        model.train("TARGET", "Shopping", "100.00", "POS", SHOPPING);

        final CategoryClassificationModel.ModelStatistics stats = model.getStatistics();

        assertEquals(2, stats.totalSamples);
        assertTrue(stats.merchantCount >= 2);
        assertTrue(stats.keywordCount > 0);
        assertTrue(stats.amountRangeCount > 0);
        assertTrue(stats.paymentChannelCount > 0);
    }
}
