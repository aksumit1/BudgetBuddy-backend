package com.budgetbuddy.monitoring;

import com.budgetbuddy.service.ml.SemanticMatchingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator for BERT model status
 * Reports whether DistilBERT model is loaded and available
 */
@Component
public class BertHealthIndicator implements HealthIndicator {
    
    private static final Logger logger = LoggerFactory.getLogger(BertHealthIndicator.class);
    
    private final SemanticMatchingService semanticMatchingService;
    
    public BertHealthIndicator(SemanticMatchingService semanticMatchingService) {
        this.semanticMatchingService = semanticMatchingService;
    }
    
    @Override
    public Health health() {
        try {
            // Use reflection to check BERT availability (to avoid exposing internal state)
            boolean bertAvailable = isBertModelAvailable();
            
            if (bertAvailable) {
                return Health.up()
                        .withDetail("service", "DistilBERT")
                        .withDetail("status", "loaded")
                        .withDetail("model", "distilbert-base-uncased")
                        .withDetail("format", "ONNX")
                        .build();
            } else {
                return Health.down()
                        .withDetail("service", "DistilBERT")
                        .withDetail("status", "not_available")
                        .withDetail("fallback", "keyword-based matching")
                        .withDetail("note", "Application continues with keyword-based semantic matching")
                        .build();
            }
        } catch (Exception e) {
            logger.warn("Error checking BERT health: {}", e.getMessage());
            return Health.down()
                    .withDetail("service", "DistilBERT")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
    
    /**
     * Check if BERT model is available
     * Uses public method to check BERT status
     */
    private boolean isBertModelAvailable() {
        try {
            // First check if BERT is enabled and loaded
            if (!semanticMatchingService.isBertModelAvailable()) {
                return false;
            }
            
            // Try to get a test embedding to verify BERT is working
            // This is a lightweight check that verifies the model is actually functional
            String testText = "test";
            float[] embedding = semanticMatchingService.getBertEmbeddingForHealthCheck(testText);
            return embedding != null && embedding.length > 0;
        } catch (Exception e) {
            logger.debug("BERT model not available: {}", e.getMessage());
            return false;
        }
    }
}
