package com.budgetbuddy.aws.secrets;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

/**
 * AWS Secrets Manager Service Fetches and caches secrets from AWS Secrets Manager Supports
 * automatic refresh and fallback to environment variables
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Service
public class SecretsManagerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecretsManagerService.class);

    private final SecretsManagerClient secretsManagerClient;
    private final Map<String, String> secretCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService refreshExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        final Thread t = new Thread(r, "secrets-refresh");
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
            LOGGER.info(
                    "AWS Secrets Manager enabled. Refresh interval: {} seconds",
                    refreshIntervalSeconds);
            // Schedule periodic refresh
            refreshExecutor.scheduleAtFixedRate(
                    this::refreshAllSecrets,
                    refreshIntervalSeconds,
                    refreshIntervalSeconds,
                    TimeUnit.SECONDS);
        } else {
            LOGGER.info("AWS Secrets Manager disabled. Using environment variables.");
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
     * Get secret value from AWS Secrets Manager or cache Falls back to environment variable if
     * Secrets Manager is disabled
     */
    public String getSecret(final String secretName, final String envVarFallback) {
        if (!secretsManagerEnabled) {
            // Fallback to environment variable
            final String envValue = System.getenv(envVarFallback);
            if (envValue != null) {
                return envValue;
            }
            LOGGER.warn(
                    "Secrets Manager disabled and environment variable {} not set. Using default.",
                    envVarFallback);
            return null;
        }

        // Check cache first
        final String cachedValue = secretCache.get(secretName);
        if (cachedValue != null) {
            return cachedValue;
        }

        // Fetch from Secrets Manager
        try {
            final GetSecretValueRequest request =
                    GetSecretValueRequest.builder().secretId(secretName).build();

            final GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            final String secretValue = response.secretString();

            // Cache the value
            secretCache.put(secretName, secretValue);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Fetched secret {} from AWS Secrets Manager", secretName);
            }

            return secretValue;
        } catch (SecretsManagerException e) {
            // Distinguish "secret not provisioned yet" (expected during bootstrap, in
            // LocalStack dev, and during healthcheck probing) from genuine unreachability
            // (IAM denial, network failure, 5xx). The former is a normal NOT_FOUND
            // response and shouldn't be ERROR-logged on every call — it spams the logs
            // and the HealthCheckConfig probe interprets it as reachable anyway.
            final boolean isNotFound =
                    e
                            instanceof software.amazon.awssdk.services.secretsmanager.model
                                    .ResourceNotFoundException;
            if (isNotFound) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "Secret {} not provisioned in AWS Secrets Manager (will use fallback if available)",
                            secretName);
                }
            } else if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error fetching secret {} from AWS Secrets Manager: {}",
                        secretName,
                        e.getMessage());
            }
            // Fallback to environment variable
            final String envValue = System.getenv(envVarFallback);
            if (envValue != null) {
                LOGGER.info(
                        "Using environment variable {} as fallback for secret {}",
                        envVarFallback,
                        secretName);
                return envValue;
            }
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to fetch secret " + secretName + " and no fallback available",
                    e);
        }
    }

    /** Get JSON secret and parse specific key */
    public String getSecretKey(
            final String secretName, final String key, final String envVarFallback) {
        final String secretJson = getSecret(secretName, envVarFallback);
        if (secretJson == null) {
            return null;
        }

        try {
            // Simple JSON parsing for key-value pairs
            // For production, consider using Jackson ObjectMapper
            if (secretJson.contains("\"" + key + "\"")) {
                final int keyIndex = secretJson.indexOf("\"" + key + "\"");
                final int valueStart = secretJson.indexOf(":", keyIndex) + 1;
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
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error parsing JSON secret {} for key {}: {}",
                        secretName,
                        key,
                        e.getMessage());
            }
        }

        return secretJson; // Return full JSON if parsing fails
    }

    /**
     * Refresh all cached secrets in place.
     *
     * <p>Previously this method just cleared the cache, leaving the next caller to re-fetch — which
     * meant a burst of cold-path calls racing against each other after every scheduled refresh.
     * Worse: if AWS rotated a secret between our fetch-on-demand and the next hit, we'd keep
     * serving the stale value from the cache entry the first caller wrote, because subsequent
     * lookups found a "hit" and skipped the fetch. This method now re-fetches the full set of
     * cached keys, replacing each value atomically. Keys that 404 (secret deleted) are dropped;
     * keys that fail transiently are left on the stale value so we don't take down the app on an
     * AWS hiccup.
     */
    private void refreshAllSecrets() {
        if (!secretsManagerEnabled) {
            return;
        }
        final java.util.Set<String> keys = new java.util.HashSet<>(secretCache.keySet());
        if (keys.isEmpty()) {
            return;
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Refreshing {} cached secrets from AWS Secrets Manager", keys.size());
        }
        for (final String secretName : keys) {
            try {
                final GetSecretValueRequest request =
                        GetSecretValueRequest.builder().secretId(secretName).build();
                final GetSecretValueResponse response =
                        secretsManagerClient.getSecretValue(request);
                final String freshValue = response.secretString();
                if (freshValue != null) {
                    secretCache.put(secretName, freshValue);
                }
            } catch (
                    software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException
                            e) {
                LOGGER.warn("Secret {} no longer exists; dropping from cache", secretName);
                secretCache.remove(secretName);
            } catch (SecretsManagerException e) {
                // Transient — keep the stale value rather than take the app
                // down. Next scheduled run will try again.
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Transient error refreshing secret {}: {} — keeping stale value",
                            secretName,
                            e.getMessage());
                }
            }
        }
    }

    /** Invalidate specific secret from cache */
    public void invalidateSecret(final String secretName) {
        secretCache.remove(secretName);
        LOGGER.debug("Invalidated secret {} from cache", secretName);
    }
}
