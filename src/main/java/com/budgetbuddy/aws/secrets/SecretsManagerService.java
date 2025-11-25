package com.budgetbuddy.aws.secrets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AWS Secrets Manager Service
 * Fetches and caches secrets from AWS Secrets Manager
 * Supports automatic refresh and fallback to environment variables
 */
@Service
public class SecretsManagerService {

    private static final Logger logger = LoggerFactory.getLogger(SecretsManagerService.class);

    private final SecretsManagerClient secretsManagerClient;
    private final Map<String, String> secretCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService refreshExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "secrets-refresh");
        t.setDaemon(true);
        return t;
    });

    @Value("${app.aws.secrets-manager.enabled:false}")
    private boolean secretsManagerEnabled;

    @Value("${app.aws.secrets-manager.refresh-interval:3600}")
    private long refreshIntervalSeconds;

    @Value("${app.aws.region:us-east-1}")
    private String awsRegion;

    public SecretsManagerService(final SecretsManagerClient secretsManagerClient) {
        this.secretsManagerClient = secretsManagerClient;
    }

    @PostConstruct
    public void init() {
        if (secretsManagerEnabled) {
            logger.info("AWS Secrets Manager enabled. Refresh interval: {} seconds", refreshIntervalSeconds);
            // Schedule periodic refresh
            refreshExecutor.scheduleAtFixedRate(
                    this::refreshAllSecrets,
                    refreshIntervalSeconds,
                    refreshIntervalSeconds,
                    TimeUnit.SECONDS
            );
        } else {
            logger.info("AWS Secrets Manager disabled. Using environment variables.");
        }
    }

    @PreDestroy
    public void shutdown() {
        refreshExecutor.shutdown();
        try {
            if (!refreshExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                refreshExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            refreshExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (secretsManagerClient != null) {
            secretsManagerClient.close();
        }
    }

    /**
     * Get secret value from AWS Secrets Manager or cache
     * Falls back to environment variable if Secrets Manager is disabled
     */
    public String getSecret((final String secretName, final String envVarFallback) {
        if (!secretsManagerEnabled) {
            // Fallback to environment variable
            String envValue = System.getenv(envVarFallback);
            if (envValue != null) {
                return envValue;
            }
            logger.warn("Secrets Manager disabled and environment variable {} not set. Using default.", envVarFallback);
            return null;
        }

        // Check cache first
        String cachedValue = secretCache.get(secretName);
        if (cachedValue != null) {
            return cachedValue;
        }

        // Fetch from Secrets Manager
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();

            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            String secretValue = response.secretString();

            // Cache the value
            secretCache.put(secretName, secretValue);
            logger.debug("Fetched secret {} from AWS Secrets Manager", secretName);

            return secretValue;
        } catch (SecretsManagerException e) {
            logger.error("Error fetching secret {} from AWS Secrets Manager: {}", secretName, e.getMessage());
            // Fallback to environment variable
            String envValue = System.getenv(envVarFallback);
            if (envValue != null) {
                logger.info("Using environment variable {} as fallback for secret {}", envVarFallback, secretName);
                return envValue;
            }
            throw new RuntimeException("Failed to fetch secret " + secretName + " and no fallback available", e);
        }
    }

    /**
     * Get JSON secret and parse specific key
     */
    public String getSecretKey((final String secretName, final String key, final String envVarFallback) {
        String secretJson = getSecret(secretName, envVarFallback);
        if (secretJson == null) {
            return null;
        }

        try {
            // Simple JSON parsing for key-value pairs
            // For production, consider using Jackson ObjectMapper
            if (secretJson.contains("\"" + key + "\"")) {
                int keyIndex = secretJson.indexOf("\"" + key + "\"");
                int valueStart = secretJson.indexOf(":", keyIndex) + 1;
                int valueEnd = secretJson.indexOf(",", valueStart);
                if (valueEnd == -1) {
                    valueEnd = secretJson.indexOf("}", valueStart);
                }
                String value = secretJson.substring(valueStart, valueEnd).trim();
                // Remove quotes if present
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        } catch (Exception e) {
            logger.error("Error parsing JSON secret {} for key {}: {}", secretName, key, e.getMessage());
        }

        return secretJson; // Return full JSON if parsing fails
    }

    /**
     * Refresh all cached secrets
     */
    private void refreshAllSecrets() {
        logger.debug("Refreshing all cached secrets");
        secretCache.clear();
    }

    /**
     * Invalidate specific secret from cache
     */
    public void invalidateSecret((final String secretName) {
        secretCache.remove(secretName);
        logger.debug("Invalidated secret {} from cache", secretName);
    }
}

