package com.budgetbuddy.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient;
import software.amazon.awssdk.services.appconfigdata.model.GetLatestConfigurationRequest;
import software.amazon.awssdk.services.appconfigdata.model.GetLatestConfigurationResponse;
import software.amazon.awssdk.services.appconfigdata.model.StartConfigurationSessionRequest;
import software.amazon.awssdk.services.appconfigdata.model.StartConfigurationSessionResponse;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AWS AppConfig Integration
 * Provides dynamic configuration management and feature flags
 *
 * Features:
 * - Automatic configuration refresh
 * - JSON configuration parsing
 * - Thread-safe configuration access
 * - Proper resource cleanup
 * - Deadlock prevention
 */
@Configuration
public class AppConfigIntegration {

    private static final Logger logger = LoggerFactory.getLogger(AppConfigIntegration.class);

    @Value("${app.aws.appconfig.application:budgetbuddy-backend}")
    private String applicationName;

    @Value("${app.aws.appconfig.environment:production}")
    private String environment;

    @Value("${app.aws.appconfig.config-profile:default}")
    private String configProfile;

    @Value("${app.aws.appconfig.refresh-interval:60}")
    private long refreshIntervalSeconds;

    private final AppConfigDataClient appConfigDataClient;
    private final AtomicReference<String> configurationToken = new AtomicReference<>();
    private final AtomicReference<String> latestConfiguration = new AtomicReference<>();
    private final AtomicReference<JsonNode> parsedConfiguration = new AtomicReference<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "appconfig-refresh");
                t.setDaemon(true);
                return t;
            });
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Object sessionLock = new Object(); // Prevent deadlocks

    public AppConfigIntegration() {
        this.appConfigDataClient = AppConfigDataClient.builder().build();
    }

    @Bean
    public AppConfigClient appConfigClient() {
        return AppConfigClient.builder().build();
    }

    @Bean
    public AppConfigDataClient appConfigDataClient() {
        return this.appConfigDataClient;
    }

    @jakarta.annotation.PostConstruct
    public void initialize() {
        try {
            startConfigurationSession();
            scheduleConfigurationRefresh();
            logger.info("AWS AppConfig integration initialized for application: {}, environment: {}",
                    applicationName, environment);
        } catch (Exception e) {
            logger.error("Failed to initialize AWS AppConfig", e);
        }
    }

    @PreDestroy
    public void cleanup() {
        logger.info("Shutting down AppConfig integration");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.error("Scheduler did not terminate");
                }
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (appConfigDataClient != null) {
            try {
                appConfigDataClient.close();
            } catch (Exception e) {
                logger.warn("Error closing AppConfig client: {}", e.getMessage());
            }
        }
    }

    /**
     * Start configuration session
     * Synchronized to prevent concurrent session creation
     */
    private void startConfigurationSession() {
        synchronized (sessionLock) {
            try {
                StartConfigurationSessionRequest request = StartConfigurationSessionRequest.builder()
                        .applicationIdentifier(applicationName)
                        .environmentIdentifier(environment)
                        .configurationProfileIdentifier(configProfile)
                        .build();

                StartConfigurationSessionResponse response = appConfigDataClient.startConfigurationSession(request);
                String token = response.initialConfigurationToken();
                if (token != null && !token.isEmpty()) {
                    configurationToken.set(token);
                    logger.info("Configuration session started");
                } else {
                    logger.warn("Configuration session started but token is null or empty");
                }
            } catch (Exception e) {
                logger.error("Failed to start configuration session", e);
                throw new RuntimeException("Failed to start AppConfig session", e);
            }
        }
    }

    /**
     * Get latest configuration
     * Thread-safe implementation
     */
    public String getLatestConfiguration() {
        String token = configurationToken.get();
        if (token == null || token.isEmpty()) {
            startConfigurationSession();
            token = configurationToken.get();
        }

        if (token == null || token.isEmpty()) {
            logger.warn("Configuration token is null or empty, returning cached configuration");
            return latestConfiguration.get();
        }

        try {
            GetLatestConfigurationRequest request = GetLatestConfigurationRequest.builder()
                    .configurationToken(token)
                    .build();

            GetLatestConfigurationResponse response = appConfigDataClient.getLatestConfiguration(request);
            // Get next token for polling - use nextPollingToken() if available
            String nextToken = null;
            try {
                // Try to get next polling token using reflection to handle API differences
                java.lang.reflect.Method method = response.getClass().getMethod("nextPollingToken");
                nextToken = (String) method.invoke(response);
            } catch (NoSuchMethodException e) {
                // Try alternative method name
                try {
                    java.lang.reflect.Method method = response.getClass().getMethod("nextConfigurationToken");
                    nextToken = (String) method.invoke(response);
                } catch (Exception e2) {
                    logger.debug("Could not get next configuration token: {}", e2.getMessage());
                }
            } catch (Exception e) {
                logger.debug("Could not get next polling token: {}", e.getMessage());
            }
            if (nextToken != null && !nextToken.isEmpty()) {
                configurationToken.set(nextToken);
            }

            // response.configuration() returns SdkBytes, not ByteBuffer
            software.amazon.awssdk.core.SdkBytes configBytes = response.configuration();
            if (configBytes != null && configBytes.asByteArray().length > 0) {
                String config = configBytes.asUtf8String();
                latestConfiguration.set(config);

                // Parse JSON configuration
                try {
                    JsonNode jsonNode = objectMapper.readTree(config);
                    parsedConfiguration.set(jsonNode);
                    logger.debug("Configuration updated and parsed from AppConfig");
                } catch (IOException e) {
                    logger.warn("Failed to parse configuration JSON: {}", e.getMessage());
                }
            }

            return latestConfiguration.get();
        } catch (Exception e) {
            logger.error("Failed to get latest configuration from AppConfig", e);
            return latestConfiguration.get(); // Return cached configuration
        }
    }

    /**
     * Schedule periodic configuration refresh
     */
    private void scheduleConfigurationRefresh() {
        scheduler.scheduleAtFixedRate(
                this::getLatestConfiguration,
                refreshIntervalSeconds,
                refreshIntervalSeconds,
                TimeUnit.SECONDS
        );
    }

    /**
     * Get configuration value by key (supports dot notation, e.g., "featureFlags.plaid")
     */
    public Optional<String> getConfigValue(String key) {
        if (key == null || key.isEmpty()) {
            return Optional.empty();
        }

        JsonNode config = parsedConfiguration.get();
        if (config == null) {
            getLatestConfiguration();
            config = parsedConfiguration.get();
        }

        if (config == null) {
            logger.warn("Configuration not available");
            return Optional.empty();
        }

        try {
            String[] keys = key.split("\\.");
            JsonNode node = config;
            for (String k : keys) {
                if (node == null) {
                    return Optional.empty();
                }
                node = node.get(k);
            }

            if (node != null && !node.isNull()) {
                if (node.isTextual()) {
                    return Optional.of(node.asText());
                } else {
                    return Optional.of(node.toString());
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get config value for key: {}", key, e);
        }

        return Optional.empty();
    }

    /**
     * Get boolean configuration value
     */
    public boolean getBooleanConfigValue(final String key, final boolean defaultValue) {
        return getConfigValue(key)
                .map(s -> {
                    try {
                        return Boolean.parseBoolean(s);
                    } catch (Exception e) {
                        logger.warn("Failed to parse boolean for key: {}", key);
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }

    /**
     * Get integer configuration value
     */
    public int getIntConfigValue(final String key, final int defaultValue) {
        return getConfigValue(key)
                .map(s -> {
                    try {
                        return Integer.parseInt(s);
                    } catch (NumberFormatException e) {
                        logger.warn("Failed to parse integer for key: {}", key);
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }
}
