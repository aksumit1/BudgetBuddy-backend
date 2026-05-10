package com.budgetbuddy.monitoring;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.budgetbuddy.service.ml.SemanticMatchingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator for BERT model status Reports whether DistilBERT model is loaded and available
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Component
public class BertHealthIndicator implements HealthIndicator {

    private static final String DISTIL_BERT = "DistilBERT";

    private static final String SERVICE = "service";

    private static final String STATUS = "status";

    private static final Logger LOGGER = LoggerFactory.getLogger(BertHealthIndicator.class);

    private final SemanticMatchingService semanticMatchingService;

    public BertHealthIndicator(final SemanticMatchingService semanticMatchingService) {
        this.semanticMatchingService = semanticMatchingService;
    }

    @Override
    public Health health() {
        try {
            // Use reflection to check BERT availability (to avoid exposing internal state)
            final boolean bertAvailable = isBertModelAvailable();

            if (bertAvailable) {
                return Health.up()
                        .withDetail(SERVICE, DISTIL_BERT)
                        .withDetail(STATUS, "loaded")
                        .withDetail("model", "distilbert-base-uncased")
                        .withDetail("format", "ONNX")
                        .build();
            } else {
                // BERT is an optional enrichment; absence degrades gracefully to keyword
                // matching and must not drag aggregate health DOWN. Report UP with context.
                return Health.up()
                        .withDetail(SERVICE, DISTIL_BERT)
                        .withDetail(STATUS, "not_available")
                        .withDetail("fallback", "keyword-based matching")
                        .withDetail(
                                "note",
                                "Application continues with keyword-based semantic matching")
                        .build();
            }
        } catch (Exception e) {
            LOGGER.warn("Error checking BERT health: {}", e.getMessage());
            return Health.up()
                    .withDetail(SERVICE, DISTIL_BERT)
                    .withDetail(STATUS, "error-checked")
                    .withDetail("fallback", "keyword-based matching")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }

    /**
     * Check if BERT model is available CRITICAL: BERT functionality is currently not implemented in
     * SemanticMatchingService This method checks if BERT methods exist and are functional
     */
    private boolean isBertModelAvailable() {
        try {
            // CRITICAL: Use reflection to check if BERT methods exist (backward compatibility)
            // If methods don't exist, BERT is not available
            try {
                final java.lang.reflect.Method isBertAvailableMethod =
                        semanticMatchingService.getClass().getMethod("isBertModelAvailable");
                final Object result = isBertAvailableMethod.invoke(semanticMatchingService);
                if (result instanceof Boolean && !(Boolean) result) {
                    return false;
                }

                // Try to get a test embedding to verify BERT is working
                final java.lang.reflect.Method getEmbeddingMethod =
                        semanticMatchingService
                                .getClass()
                                .getMethod("getBertEmbeddingForHealthCheck", String.class);
                final String testText = "test";
                final Object embedding = getEmbeddingMethod.invoke(semanticMatchingService, testText);

                if (embedding == null) {
                    return false;
                }

                // Check if embedding is a float array with valid length
                if (embedding instanceof float[]) {
                    final float[] embeddingArray = (float[]) embedding;
                    return embeddingArray.length > 0;
                }

                return false;
            } catch (NoSuchMethodException e) {
                // BERT methods don't exist - BERT is not implemented
                LOGGER.debug(
                        "BERT methods not found in SemanticMatchingService. BERT is not available.");
                return false;
            }
        } catch (Exception e) {
            LOGGER.debug("BERT model not available: {}", e.getMessage());
            return false;
        }
    }
}
