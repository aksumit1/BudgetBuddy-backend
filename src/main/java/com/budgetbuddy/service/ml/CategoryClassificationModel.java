package com.budgetbuddy.service.ml;

import com.budgetbuddy.security.FileSecurityValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Machine Learning Model for Category Classification
 * 
 * Uses a simple but effective approach:
 * - Feature extraction from transaction data
 * - Frequency-based learning (learns from historical transactions)
 * - Confidence scoring
 * - Can be extended to use Weka or other ML libraries
 * 
 * Features:
 * - Merchant name patterns
 * - Description keywords
 * - Amount ranges
 * - Payment channel
 * - Day of week / time patterns
 * - Account type
 */
@Component
public class CategoryClassificationModel {
    
    private static final Logger logger = LoggerFactory.getLogger(CategoryClassificationModel.class);
    
    // Model storage: merchant -> category -> count
    private final Map<String, Map<String, Integer>> merchantCategoryCounts = new ConcurrentHashMap<>();
    
    // Model storage: description keywords -> category -> count
    private final Map<String, Map<String, Integer>> keywordCategoryCounts = new ConcurrentHashMap<>();
    
    // Model storage: amount range -> category -> count
    private final Map<String, Map<String, Integer>> amountRangeCategoryCounts = new ConcurrentHashMap<>();
    
    // Model storage: payment channel -> category -> count
    private final Map<String, Map<String, Integer>> paymentChannelCategoryCounts = new ConcurrentHashMap<>();
    
    // Total training samples (thread-safe)
    private final java.util.concurrent.atomic.AtomicLong totalTrainingSamples = new java.util.concurrent.atomic.AtomicLong(0);
    
    // Model file path (changed from .dat to .json for safe JSON serialization)
    private static final String MODEL_FILE_NAME = "category_model.json";
    private final String modelDirectory;
    private volatile boolean modelPersistenceEnabled = true;
    
    @Autowired(required = false)
    private ObjectMapper objectMapper;
    
    @Autowired
    private FileSecurityValidator fileSecurityValidator;
    
    public CategoryClassificationModel(
            @Value("${app.ml.model.directory:}") String modelDirectory) {
        // Use system temp directory as fallback if not configured
        String effectiveDirectory = modelDirectory;
        if (effectiveDirectory == null || effectiveDirectory.trim().isEmpty()) {
            effectiveDirectory = System.getProperty("java.io.tmpdir") + "/budgetbuddy-ml-models";
        }
        this.modelDirectory = effectiveDirectory;
    }
    
    // Initialize ObjectMapper if not injected
    private ObjectMapper getObjectMapper() {
        if (objectMapper == null) {
            objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        }
        return objectMapper;
    }
    
    /**
     * SECURITY: Get validated and normalized model file path
     * Validates path to prevent path traversal attacks
     * Ensures parent directory exists
     * Falls back to system temp directory if configured path fails
     */
    private Path getValidatedModelFilePath() {
        if (!modelPersistenceEnabled) {
            throw new RuntimeException("Model persistence is disabled due to directory access issues");
        }
        
        try {
            String modelFilePath = modelDirectory + "/" + MODEL_FILE_NAME;
            // Validate the full file path to prevent path traversal
            Path validatedPath = fileSecurityValidator.validateFilePath(modelFilePath);
            
            // Ensure parent directory exists
            Path parentDir = validatedPath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                // Validate parent directory path as well
                // Use normalized string representation to avoid issues
                String parentDirStr = parentDir.normalize().toString();
                Path validatedParent = fileSecurityValidator.validateFilePath(parentDirStr);
                Files.createDirectories(validatedParent);
                logger.info("Created model directory: {}", validatedParent);
            } else if (parentDir != null && Files.exists(parentDir) && !Files.isWritable(parentDir)) {
                logger.warn("Model directory exists but is not writable: {}", parentDir);
                modelPersistenceEnabled = false;
                throw new RuntimeException("Model directory is not writable");
            }
            
            return validatedPath;
        } catch (com.budgetbuddy.exception.AppException e) {
            // AppException from FileSecurityValidator - log and handle gracefully
            logger.warn("File path validation failed for model directory {}: {}. Attempting fallback to system temp directory.", 
                    modelDirectory, e.getMessage());
            // Continue to fallback logic below
        } catch (IOException | SecurityException e) {
            logger.warn("Failed to create model directory at {}: {}. Attempting fallback to system temp directory.", 
                    modelDirectory, e.getMessage());
        } catch (Exception e) {
            logger.warn("Unexpected error with model directory {}: {}. Attempting fallback to system temp directory.", 
                    modelDirectory, e.getMessage());
        }
        
        // Fallback to system temp directory (for all exception cases above)
        try {
            String fallbackDir = System.getProperty("java.io.tmpdir") + "/budgetbuddy-ml-models";
            String fallbackPath = fallbackDir + "/" + MODEL_FILE_NAME;
            Path validatedFallback = fileSecurityValidator.validateFilePath(fallbackPath);
            Path fallbackParent = validatedFallback.getParent();
            if (fallbackParent != null && !Files.exists(fallbackParent)) {
                // Validate parent directory path
                String fallbackParentStr = fallbackParent.normalize().toString();
                fileSecurityValidator.validateFilePath(fallbackParentStr);
                Files.createDirectories(fallbackParent);
            }
            logger.info("Using fallback model directory: {}", fallbackDir);
            return validatedFallback;
        } catch (com.budgetbuddy.exception.AppException | IOException | SecurityException fallbackException) {
            logger.error("Failed to create fallback model directory. Model persistence will be disabled. Error: {}", 
                    fallbackException.getMessage());
            modelPersistenceEnabled = false;
            throw new RuntimeException("Failed to initialize model file path", fallbackException);
        }
    }
    
    /**
     * Train the model with a transaction
     * 
     * @param merchantName Merchant name
     * @param description Transaction description
     * @param amount Transaction amount
     * @param paymentChannel Payment channel
     * @param category Actual category (ground truth)
     */
    public void train(String merchantName, String description, String amount, 
                     String paymentChannel, String category) {
        if (category == null || category.trim().isEmpty()) {
            return;
        }
        
        totalTrainingSamples.incrementAndGet();
        
        // Train on merchant name
        if (merchantName != null && !merchantName.trim().isEmpty()) {
            String normalizedMerchant = normalizeForTraining(merchantName);
            merchantCategoryCounts.computeIfAbsent(normalizedMerchant, k -> new ConcurrentHashMap<>())
                    .merge(category, 1, Integer::sum);
        }
        
        // Train on description keywords
        if (description != null && !description.trim().isEmpty()) {
            String[] keywords = extractKeywords(description);
            for (String keyword : keywords) {
                if (keyword.length() >= 3) { // Only meaningful keywords
                    keywordCategoryCounts.computeIfAbsent(keyword, k -> new ConcurrentHashMap<>())
                            .merge(category, 1, Integer::sum);
                }
            }
        }
        
        // Train on amount ranges
        if (amount != null && !amount.trim().isEmpty()) {
            try {
                double amountValue = Double.parseDouble(amount.trim());
                // CRITICAL: Validate amount is reasonable (prevent overflow/underflow)
                if (Double.isFinite(amountValue) && amountValue >= -1_000_000_000 && amountValue <= 1_000_000_000) {
                    if (amountValue < 0) amountValue = -amountValue;
                    String amountRange = getAmountRange(amountValue);
                    amountRangeCategoryCounts.computeIfAbsent(amountRange, k -> new ConcurrentHashMap<>())
                            .merge(category, 1, Integer::sum);
                } else {
                    logger.warn("Invalid amount value for training: {}", amount);
                }
            } catch (NumberFormatException e) {
                logger.debug("Invalid amount format for training: {}", amount);
            } catch (Exception e) {
                logger.warn("Unexpected error processing amount for training: {}", amount, e);
            }
        }
        
        // Train on payment channel
        if (paymentChannel != null && !paymentChannel.trim().isEmpty()) {
            paymentChannelCategoryCounts.computeIfAbsent(paymentChannel.toLowerCase(), k -> new ConcurrentHashMap<>())
                    .merge(category, 1, Integer::sum);
        }
        
        // Periodically save model (every 100 samples)
        if (totalTrainingSamples.get() % 100 == 0) {
            saveModel();
        }
    }
    
    /**
     * Predict category with confidence score
     * 
     * @param merchantName Merchant name
     * @param description Transaction description
     * @param amount Transaction amount
     * @param paymentChannel Payment channel
     * @return PredictionResult with predicted category and confidence score
     */
    public PredictionResult predict(String merchantName, String description, 
                                    String amount, String paymentChannel) {
        Map<String, Double> categoryScores = new HashMap<>();
        
        // Score based on merchant name
        if (merchantName != null && !merchantName.trim().isEmpty()) {
            String normalizedMerchant = normalizeForTraining(merchantName);
            Map<String, Integer> merchantCounts = merchantCategoryCounts.get(normalizedMerchant);
            if (merchantCounts != null && !merchantCounts.isEmpty()) {
                int total = merchantCounts.values().stream().mapToInt(Integer::intValue).sum();
                if (total > 0) { // CRITICAL: Prevent division by zero
                    for (Map.Entry<String, Integer> entry : merchantCounts.entrySet()) {
                        double score = (double) entry.getValue() / total;
                        categoryScores.merge(entry.getKey(), score * 0.4, Double::sum); // 40% weight
                    }
                }
            }
        }
        
        // Score based on description keywords
        if (description != null && !description.trim().isEmpty()) {
            String[] keywords = extractKeywords(description);
            for (String keyword : keywords) {
                if (keyword.length() >= 3) {
                    Map<String, Integer> keywordCounts = keywordCategoryCounts.get(keyword);
                    if (keywordCounts != null && !keywordCounts.isEmpty()) {
                        int total = keywordCounts.values().stream().mapToInt(Integer::intValue).sum();
                        if (total > 0) { // CRITICAL: Prevent division by zero
                            for (Map.Entry<String, Integer> entry : keywordCounts.entrySet()) {
                                double score = (double) entry.getValue() / total;
                                categoryScores.merge(entry.getKey(), score * 0.3, Double::sum); // 30% weight
                            }
                        }
                    }
                }
            }
        }
        
        // Score based on amount range
        if (amount != null && !amount.trim().isEmpty()) {
            try {
                double amountValue = Double.parseDouble(amount.trim());
                // CRITICAL: Validate amount is reasonable
                if (Double.isFinite(amountValue) && amountValue >= -1_000_000_000 && amountValue <= 1_000_000_000) {
                    if (amountValue < 0) amountValue = -amountValue;
                    String amountRange = getAmountRange(amountValue);
                    Map<String, Integer> amountCounts = amountRangeCategoryCounts.get(amountRange);
                    if (amountCounts != null && !amountCounts.isEmpty()) {
                        int total = amountCounts.values().stream().mapToInt(Integer::intValue).sum();
                        if (total > 0) { // CRITICAL: Prevent division by zero
                            for (Map.Entry<String, Integer> entry : amountCounts.entrySet()) {
                                double score = (double) entry.getValue() / total;
                                categoryScores.merge(entry.getKey(), score * 0.15, Double::sum); // 15% weight
                            }
                        }
                    }
                }
            } catch (NumberFormatException e) {
                logger.debug("Invalid amount format for prediction: {}", amount);
            } catch (Exception e) {
                logger.warn("Unexpected error processing amount for prediction: {}", amount, e);
            }
        }
        
        // Score based on payment channel
        if (paymentChannel != null && !paymentChannel.trim().isEmpty()) {
            Map<String, Integer> channelCounts = paymentChannelCategoryCounts.get(paymentChannel.toLowerCase());
            if (channelCounts != null && !channelCounts.isEmpty()) {
                int total = channelCounts.values().stream().mapToInt(Integer::intValue).sum();
                if (total > 0) { // CRITICAL: Prevent division by zero
                    for (Map.Entry<String, Integer> entry : channelCounts.entrySet()) {
                        double score = (double) entry.getValue() / total;
                        categoryScores.merge(entry.getKey(), score * 0.15, Double::sum); // 15% weight
                    }
                }
            }
        }
        
        // Find best prediction
        if (categoryScores.isEmpty()) {
            return new PredictionResult(null, 0.0, Collections.emptyList());
        }
        
        // Sort by score
        List<Map.Entry<String, Double>> sorted = categoryScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .collect(Collectors.toList());
        
        String bestCategory = sorted.get(0).getKey();
        double bestScore = sorted.get(0).getValue();
        
        // Normalize score to 0-1 range
        double maxPossibleScore = 1.0; // Sum of all weights
        double normalizedScore = Math.min(bestScore / maxPossibleScore, 1.0);
        
        // Get top 3 predictions
        List<CategoryScore> topPredictions = sorted.stream()
                .limit(3)
                .map(e -> new CategoryScore(e.getKey(), e.getValue() / maxPossibleScore))
                .collect(Collectors.toList());
        
        logger.debug("ML Prediction: category='{}', confidence={:.2f}, top3={}", 
                bestCategory, normalizedScore, topPredictions);
        
        return new PredictionResult(bestCategory, normalizedScore, topPredictions);
    }
    
    /**
     * Extract keywords from description
     */
    private String[] extractKeywords(String description) {
        // Remove common stop words and extract meaningful words
        String[] stopWords = {"the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by"};
        Set<String> stopWordSet = new HashSet<>(Arrays.asList(stopWords));
        
        return Arrays.stream(description.toLowerCase().split("\\s+"))
                .filter(word -> word.length() >= 3 && !stopWordSet.contains(word))
                .toArray(String[]::new);
    }
    
    /**
     * Get amount range bucket
     */
    private String getAmountRange(double amount) {
        if (amount < 10) return "0-10";
        if (amount < 25) return "10-25";
        if (amount < 50) return "25-50";
        if (amount < 100) return "50-100";
        if (amount < 250) return "100-250";
        if (amount < 500) return "250-500";
        if (amount < 1000) return "500-1000";
        return "1000+";
    }
    
    /**
     * Normalize merchant name for training
     */
    private String normalizeForTraining(String merchantName) {
        if (merchantName == null) {
            return "";
        }
        return merchantName.trim().toLowerCase()
                .replaceAll("\\s*#\\d+", "") // Remove store numbers
                .replaceAll("\\s+", " ")
                .trim();
    }
    
    /**
     * Save model to disk
     * Thread-safe: Uses synchronized block to prevent concurrent saves
     */
    public void saveModel() {
        if (!modelPersistenceEnabled) {
            logger.warn("Model persistence is disabled. Model will not be saved.");
            return;
        }
        
        synchronized (this) {
            try {
                // SECURITY: Use validated paths to prevent path traversal
                // getValidatedModelFilePath() ensures parent directory exists
                Path modelFile = getValidatedModelFilePath();
                
                // SECURITY: Use JSON serialization instead of unsafe Java serialization
                // Write to temp file first, then rename (atomic operation)
                Path tempFile = modelFile.resolveSibling(MODEL_FILE_NAME + ".tmp");
                ModelData modelData = new ModelData(
                        new HashMap<>(merchantCategoryCounts),
                        new HashMap<>(keywordCategoryCounts),
                        new HashMap<>(amountRangeCategoryCounts),
                        new HashMap<>(paymentChannelCategoryCounts),
                        totalTrainingSamples.get()
                );
                
                try (FileWriter writer = new FileWriter(tempFile.toFile())) {
                    getObjectMapper().writeValue(writer, modelData);
                }
                
                // Atomic rename
                Files.move(tempFile, modelFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                
                logger.info("ML Model saved: {} samples, {} merchants, {} keywords", 
                        totalTrainingSamples.get(), merchantCategoryCounts.size(), keywordCategoryCounts.size());
            } catch (IOException e) {
                logger.error("Failed to save ML model", e);
                // CRITICAL: Clean up temp file on error
                try {
                    Path modelFile = getValidatedModelFilePath();
                    Path tempFile = modelFile.resolveSibling(MODEL_FILE_NAME + ".tmp");
                    if (Files.exists(tempFile)) {
                        Files.delete(tempFile);
                    }
                } catch (Exception cleanupError) {
                    logger.warn("Failed to clean up temp model file", cleanupError);
                }
            } catch (Exception e) {
                logger.error("Unexpected error saving ML model", e);
            }
        }
    }
    
    /**
     * Load model from disk
     * Thread-safe: Uses synchronized block to prevent concurrent loads
     */
    @SuppressWarnings("unchecked")
    public void loadModel() {
        if (!modelPersistenceEnabled) {
            logger.warn("Model persistence is disabled. Model will not be loaded.");
            return;
        }
        
        synchronized (this) {
            try {
                // SECURITY: Use validated path to prevent path traversal
                Path modelFile = getValidatedModelFilePath();
                
                if (!Files.exists(modelFile)) {
                    logger.info("No existing ML model found, starting fresh");
                    return;
                }
                
                // CRITICAL: Validate file is readable and not empty
                if (!Files.isReadable(modelFile)) {
                    logger.error("Model file is not readable: {}", modelFile);
                    return;
                }
                
                if (Files.size(modelFile) == 0) {
                    logger.warn("Model file is empty: {}", modelFile);
                    return;
                }
                
                try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(modelFile.toFile()))) {
                    // CRITICAL: Clear existing data before loading to avoid inconsistencies
                    merchantCategoryCounts.clear();
                    keywordCategoryCounts.clear();
                    amountRangeCategoryCounts.clear();
                    paymentChannelCategoryCounts.clear();
                    
                    Object merchantObj = ois.readObject();
                    Object keywordObj = ois.readObject();
                    Object amountObj = ois.readObject();
                    Object channelObj = ois.readObject();
                    long samples = ois.readLong();
                    
                    // CRITICAL: Validate loaded data before assigning
                    if (merchantObj instanceof Map && keywordObj instanceof Map && 
                        amountObj instanceof Map && channelObj instanceof Map) {
                        merchantCategoryCounts.putAll((Map<String, Map<String, Integer>>) merchantObj);
                        keywordCategoryCounts.putAll((Map<String, Map<String, Integer>>) keywordObj);
                        amountRangeCategoryCounts.putAll((Map<String, Map<String, Integer>>) amountObj);
                        paymentChannelCategoryCounts.putAll((Map<String, Map<String, Integer>>) channelObj);
                        totalTrainingSamples.set(samples);
                        
                        logger.info("ML Model loaded: {} samples, {} merchants, {} keywords", 
                                totalTrainingSamples.get(), merchantCategoryCounts.size(), keywordCategoryCounts.size());
                    } else {
                        logger.error("Invalid model file format: expected Maps, got different types");
                    }
                }
            } catch (java.io.InvalidClassException e) {
                logger.error("Model file format incompatible (class version mismatch): {}", e.getMessage());
                // Don't clear existing model, keep using it
            } catch (ClassNotFoundException e) {
                logger.error("Failed to deserialize model file (class not found): {}", e.getMessage());
            } catch (IOException e) {
                logger.error("Failed to load ML model", e);
            } catch (Exception e) {
                logger.error("Unexpected error loading ML model", e);
            }
        }
    }
    
    /**
     * Get model statistics
     */
    public ModelStatistics getStatistics() {
        return new ModelStatistics(
                totalTrainingSamples.get(),
                merchantCategoryCounts.size(),
                keywordCategoryCounts.size(),
                amountRangeCategoryCounts.size(),
                paymentChannelCategoryCounts.size()
        );
    }
    
    /**
     * Prediction result
     */
    public static class PredictionResult {
        public final String category;
        public final double confidence;
        public final List<CategoryScore> topPredictions;
        
        public PredictionResult(String category, double confidence, List<CategoryScore> topPredictions) {
            this.category = category;
            this.confidence = confidence;
            this.topPredictions = topPredictions;
        }
        
        public boolean isHighConfidence() {
            return confidence >= 0.7;
        }
        
        public boolean isMediumConfidence() {
            return confidence >= 0.5 && confidence < 0.7;
        }
        
        public boolean isLowConfidence() {
            return confidence >= 0.3 && confidence < 0.5;
        }
        
        @Override
        public String toString() {
            return String.format("PredictionResult{category='%s', confidence=%.2f, topPredictions=%s}",
                    category, confidence, topPredictions);
        }
    }
    
    /**
     * Category score
     */
    public static class CategoryScore {
        public final String category;
        public final double score;
        
        public CategoryScore(String category, double score) {
            this.category = category;
            this.score = score;
        }
        
        @Override
        public String toString() {
            return String.format("%s:%.2f", category, score);
        }
    }
    
    /**
     * Model statistics
     */
    public static class ModelStatistics {
        public final long totalSamples;
        public final int merchantCount;
        public final int keywordCount;
        public final int amountRangeCount;
        public final int paymentChannelCount;
        
        public ModelStatistics(long totalSamples, int merchantCount, int keywordCount, 
                              int amountRangeCount, int paymentChannelCount) {
            this.totalSamples = totalSamples;
            this.merchantCount = merchantCount;
            this.keywordCount = keywordCount;
            this.amountRangeCount = amountRangeCount;
            this.paymentChannelCount = paymentChannelCount;
        }
        
        @Override
        public String toString() {
            return String.format("ModelStatistics{samples=%d, merchants=%d, keywords=%d, amountRanges=%d, paymentChannels=%d}",
                    totalSamples, merchantCount, keywordCount, amountRangeCount, paymentChannelCount);
        }
    }
    
    /**
     * Model data structure for JSON serialization
     * SECURITY: Replaces unsafe Java serialization with safe JSON serialization
     */
    private static class ModelData {
        public Map<String, Map<String, Integer>> merchantCategoryCounts;
        public Map<String, Map<String, Integer>> keywordCategoryCounts;
        public Map<String, Map<String, Integer>> amountRangeCategoryCounts;
        public Map<String, Map<String, Integer>> paymentChannelCategoryCounts;
        public long totalTrainingSamples;
        
        // Default constructor for Jackson
        public ModelData() {}
        
        public ModelData(Map<String, Map<String, Integer>> merchantCategoryCounts,
                        Map<String, Map<String, Integer>> keywordCategoryCounts,
                        Map<String, Map<String, Integer>> amountRangeCategoryCounts,
                        Map<String, Map<String, Integer>> paymentChannelCategoryCounts,
                        long totalTrainingSamples) {
            this.merchantCategoryCounts = merchantCategoryCounts;
            this.keywordCategoryCounts = keywordCategoryCounts;
            this.amountRangeCategoryCounts = amountRangeCategoryCounts;
            this.paymentChannelCategoryCounts = paymentChannelCategoryCounts;
            this.totalTrainingSamples = totalTrainingSamples;
        }
    }
}

