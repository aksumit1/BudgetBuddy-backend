package com.budgetbuddy.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.GetConfigurationRequest;
import software.amazon.awssdk.services.appconfig.model.GetConfigurationResponse;
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient;
import software.amazon.awssdk.services.appconfigdata.model.GetLatestConfigurationRequest;
import software.amazon.awssdk.services.appconfigdata.model.GetLatestConfigurationResponse;
import software.amazon.awssdk.services.appconfigdata.model.StartConfigurationSessionRequest;
import software.amazon.awssdk.services.appconfigdata.model.StartConfigurationSessionResponse;
import software.amazon.awssdk.core.exception.SdkServiceException;
import org.springframework.core.env.Environment;

import java.io.IOException;
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
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "app.aws.appconfig.enabled", 
        havingValue = "true", 
        matchIfMissing = true)
public class AppConfigIntegration {

    private static final Logger logger = LoggerFactory.getLogger(AppConfigIntegration.class);

    @Value("${app.aws.appconfig.application:budgetbuddy-backend}")
    private String applicationName;

    @Value("${app.aws.appconfig.environment:production}")
    private String appConfigEnvironment;

    @Value("${app.aws.appconfig.config-profile:default}")
    private String configProfile;

    @Value("${app.aws.appconfig.refresh-interval:60}")
    private long refreshIntervalSeconds;

    @Value("${app.aws.appconfig.enabled:true}")
    private boolean enabled;

    private AppConfigClient appConfigClient;
    private AppConfigDataClient appConfigDataClient;
    private final AtomicReference<String> configurationToken = new AtomicReference<>();
    private final AtomicReference<String> latestConfiguration = new AtomicReference<>();
    private final AtomicReference<JsonNode> parsedConfiguration = new AtomicReference<>();
    private boolean useAppConfigDataApi = true; // Use AppConfigData API by default, fallback to AppConfig API for LocalStack
    private boolean useFallbackConfig = false; // Use application.yml fallback when AppConfig is unavailable
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "appconfig-refresh");
                t.setDaemon(true);
                return t;
            });
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Object sessionLock = new Object(); // Prevent deadlocks
    private final Environment springEnvironment;

    public AppConfigIntegration(Environment springEnvironment) {
        this.springEnvironment = springEnvironment;
    }

    @Value("${AWS_APPCONFIG_ENDPOINT:}")
    private String appConfigEndpoint;

    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    @Value("${AWS_ACCESS_KEY_ID:}")
    private String accessKeyId;

    @Value("${AWS_SECRET_ACCESS_KEY:}")
    private String secretAccessKey;

    /**
     * Credentials provider that uses IAM role in ECS/EKS, or static credentials for LocalStack
     */
    private AwsCredentialsProvider getCredentialsProvider() {
        // For LocalStack, use static credentials if provided
        if (!accessKeyId.isEmpty() && !secretAccessKey.isEmpty()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)
            );
        }
        // For production, use IAM role
        try {
            return InstanceProfileCredentialsProvider.create();
        } catch (Exception e) {
            return DefaultCredentialsProvider.create();
        }
    }

    @Bean(destroyMethod = "close")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "app.aws.appconfig.enabled", 
            havingValue = "true", 
            matchIfMissing = true)
    public AppConfigClient appConfigClient() {
        if (!enabled) {
            return null;
        }
        try {
            var builder = AppConfigClient.builder()
                    .region(software.amazon.awssdk.regions.Region.of(awsRegion))
                    .credentialsProvider(getCredentialsProvider());
            
            // Use LocalStack endpoint if configured
            if (!appConfigEndpoint.isEmpty()) {
                builder.endpointOverride(java.net.URI.create(appConfigEndpoint));
            }
            
            AppConfigClient client = builder.build();
            this.appConfigClient = client; // Store for use in initialize()
            return client;
        } catch (Exception e) {
            logger.warn("Failed to create AppConfigClient (this is expected in tests without AWS credentials): {}", e.getMessage());
            return null;
        }
    }

    @Bean(destroyMethod = "close")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "app.aws.appconfig.enabled", 
            havingValue = "true", 
            matchIfMissing = true)
    public AppConfigDataClient appConfigDataClient() {
        if (!enabled || this.appConfigDataClient == null) {
            return null;
        }
        return this.appConfigDataClient;
    }

    @jakarta.annotation.PostConstruct
    public void initialize() {
        if (!enabled) {
            logger.info("AWS AppConfig integration is disabled (app.aws.appconfig.enabled=false)");
            initializeFallbackConfiguration();
            return;
        }
        
        // Check if we're in local development (no AWS credentials)
        if (isLocalDevelopment()) {
            logger.info("Local development detected (no AWS credentials) - using fallback configuration from application.yml");
            initializeFallbackConfiguration();
            return;
        }
        
        // Check if we're using LocalStack (which doesn't support AppConfigData API)
        if (isLocalStack()) {
            logger.info("LocalStack detected - using AppConfig API (not AppConfigData API) for configuration");
            useAppConfigDataApi = false;
            initializeAppConfigApi();
        } else {
            // Try AppConfigData API first (production/preferred)
            try {
                var builder = AppConfigDataClient.builder()
                        .region(software.amazon.awssdk.regions.Region.of(awsRegion))
                        .credentialsProvider(getCredentialsProvider());
                
                // Use LocalStack endpoint if configured
                if (!appConfigEndpoint.isEmpty()) {
                    builder.endpointOverride(java.net.URI.create(appConfigEndpoint));
                }
                
                this.appConfigDataClient = builder.build();
                useAppConfigDataApi = true;
                startConfigurationSession();
                scheduleConfigurationRefresh();
                logger.info("AWS AppConfig integration initialized (AppConfigData API) for application: {}, environment: {}",
                        applicationName, appConfigEnvironment);
            } catch (Exception e) {
                // Check if this is a credentials issue (local development)
                if (isLocalDevelopment() || e.getMessage() != null && 
                    (e.getMessage().contains("Unable to load credentials") || 
                     e.getMessage().contains("Failed to load credentials from IMDS"))) {
                    logger.info("AWS credentials not available (local development) - using fallback configuration from application.yml");
                    initializeFallbackConfiguration();
                } else {
                    logger.warn("Failed to initialize AppConfigData API, falling back to AppConfig API: {}", e.getMessage());
                    logger.debug("AppConfigData initialization error details", e);
                    useAppConfigDataApi = false;
                    initializeAppConfigApi();
                }
            }
        }
    }

    /**
     * Initialize using regular AppConfig API (supported by LocalStack)
     */
    private void initializeAppConfigApi() {
        if (appConfigClient == null) {
            logger.info("AppConfigClient not available, using fallback configuration from application.yml (matches production structure)");
            initializeFallbackConfiguration();
            return;
        }
        
        try {
            // Try to get configuration using AppConfig API
            getConfigurationFromAppConfigApi();
            scheduleConfigurationRefresh();
            logger.info("AWS AppConfig integration initialized (AppConfig API) for application: {}, environment: {}",
                    applicationName, appConfigEnvironment);
        } catch (Exception e) {
            // LocalStack Community Edition doesn't support AppConfig API - this is expected
            if (isLocalStackNotImplemented(e)) {
                logger.info("AppConfig API not supported by LocalStack (expected for Community Edition), " +
                        "using fallback configuration that matches production structure");
            } else {
                logger.warn("Failed to initialize AppConfig API, using fallback configuration: {}", e.getMessage());
            }
            initializeFallbackConfiguration();
        }
    }

    /**
     * Initialize fallback configuration from application.yml
     */
    private void initializeFallbackConfiguration() {
        useFallbackConfig = true;
        try {
            // Build configuration JSON from application.yml values
            JsonNode fallbackConfig = buildFallbackConfiguration();
            parsedConfiguration.set(fallbackConfig);
            latestConfiguration.set(fallbackConfig.toString());
            logger.info("Using fallback configuration from application.yml (LocalStack doesn't support AppConfig API). " +
                    "Configuration structure matches production AppConfig template. All configuration values available.");
        } catch (Exception e) {
            logger.error("Failed to initialize fallback configuration", e);
        }
    }

    /**
     * Build fallback configuration JSON from application.yml
     * This matches the CloudFormation template structure exactly for production parity
     * Note: LocalStack Community Edition doesn't support AppConfig API, so this fallback
     * ensures all configuration values are available and matches production structure
     */
    private JsonNode buildFallbackConfiguration() throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode config = objectMapper.createObjectNode();
        
        // Feature Flags - matches CloudFormation template
        com.fasterxml.jackson.databind.node.ObjectNode featureFlags = objectMapper.createObjectNode();
        featureFlags.put("plaid", springEnvironment.getProperty("app.features.enable-plaid", Boolean.class, true));
        featureFlags.put("stripe", springEnvironment.getProperty("app.features.enable-stripe", Boolean.class, true));
        featureFlags.put("oauth2", springEnvironment.getProperty("app.features.enable-oauth2", Boolean.class, false));
        featureFlags.put("advancedAnalytics", springEnvironment.getProperty("app.features.enable-advanced-analytics", Boolean.class, false));
        featureFlags.put("notifications", springEnvironment.getProperty("app.notifications.enabled", Boolean.class, true));
        config.set("featureFlags", featureFlags);
        
        // Rate Limits - matches CloudFormation template (uses values from application.yml which match production)
        com.fasterxml.jackson.databind.node.ObjectNode rateLimits = objectMapper.createObjectNode();
        rateLimits.put("perUser", springEnvironment.getProperty("app.rate-limit.requests-per-minute", Integer.class, 10000));
        rateLimits.put("perIp", springEnvironment.getProperty("app.rate-limit.ddos.max-requests-per-minute", Integer.class, 100000));
        rateLimits.put("windowSeconds", 60);
        config.set("rateLimits", rateLimits);
        
        // Cache Settings - matches CloudFormation template
        com.fasterxml.jackson.databind.node.ObjectNode cacheSettings = objectMapper.createObjectNode();
        cacheSettings.put("defaultTtl", springEnvironment.getProperty("app.performance.cache.default-ttl", Integer.class, 1800));
        cacheSettings.put("maxSize", springEnvironment.getProperty("app.performance.cache.max-size", Integer.class, 10000));
        config.set("cacheSettings", cacheSettings);
        
        return config;
    }

    /**
     * Get configuration using AppConfig API (not AppConfigData)
     * Note: Using deprecated getConfiguration() for LocalStack compatibility
     */
    @SuppressWarnings("deprecation")
    private void getConfigurationFromAppConfigApi() {
        if (appConfigClient == null) {
            throw new IllegalStateException("AppConfigClient is not initialized");
        }
        
        try {
            GetConfigurationRequest request = GetConfigurationRequest.builder()
                    .application(applicationName)
                    .environment(appConfigEnvironment)
                    .configuration(configProfile)
                    .clientId("budgetbuddy-backend-client")
                    .build();
            
            GetConfigurationResponse response = appConfigClient.getConfiguration(request);
            software.amazon.awssdk.core.SdkBytes configBytes = response.content();
            
            if (configBytes != null && configBytes.asByteArray().length > 0) {
                String config = configBytes.asUtf8String();
                latestConfiguration.set(config);
                
                // Parse JSON configuration
                try {
                    JsonNode jsonNode = objectMapper.readTree(config);
                    parsedConfiguration.set(jsonNode);
                    logger.debug("Configuration loaded from AppConfig API");
                } catch (IOException e) {
                    logger.warn("Failed to parse configuration JSON: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get configuration from AppConfig API", e);
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
                logger.warn("Error closing AppConfigData client: {}", e.getMessage());
            }
        }
        if (appConfigClient != null) {
            try {
                appConfigClient.close();
            } catch (Exception e) {
                logger.warn("Error closing AppConfig client: {}", e.getMessage());
            }
        }
    }

    /**
     * Check if we're using LocalStack (which doesn't support AppConfigData API)
     */
    private boolean isLocalStack() {
        return appConfigEndpoint != null && 
               (appConfigEndpoint.contains("localstack") || 
                appConfigEndpoint.contains(":4566") ||
                appConfigEndpoint.contains("localhost:4566"));
    }

    /**
     * Check if we're in local development (no AWS credentials available)
     */
    private boolean isLocalDevelopment() {
        // Check if we have AWS credentials configured via environment variables
        boolean hasStaticCredentials = (accessKeyId != null && !accessKeyId.trim().isEmpty()) &&
                                       (secretAccessKey != null && !secretAccessKey.trim().isEmpty());
        
        // Check if we're using LocalStack
        boolean usingLocalStack = isLocalStack();
        
        // If no static credentials and no LocalStack, likely local development
        // (IMDS won't be available in local development)
        return !hasStaticCredentials && !usingLocalStack;
    }

    /**
     * Check if exception is a 501 Not Implemented from LocalStack
     */
    private boolean isLocalStackNotImplemented(Exception e) {
        if (e instanceof SdkServiceException) {
            SdkServiceException sdkException = (SdkServiceException) e;
            return sdkException.statusCode() == 501;
        }
        // Check error message for LocalStack 501 pattern
        String message = e.getMessage();
        return message != null && 
               (message.contains("501") || 
                message.contains("not yet been emulated") ||
                message.contains("not included in your current license plan"));
    }

    /**
     * Start configuration session
     * Synchronized to prevent concurrent session creation
     */
    private void startConfigurationSession() {
        if (!enabled || appConfigDataClient == null) {
            return;
        }
        
        synchronized (sessionLock) {
            try {
                StartConfigurationSessionRequest request = StartConfigurationSessionRequest.builder()
                        .applicationIdentifier(applicationName)
                        .environmentIdentifier(appConfigEnvironment)
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
                // LocalStack doesn't support AppConfigData API (returns 501)
                // Log at appropriate level based on environment
                if (isLocalStack() || isLocalStackNotImplemented(e)) {
                    logger.debug("AppConfigData API not supported by LocalStack (this is expected). " +
                            "AppConfig integration will use cached/default configuration. Error: {}", e.getMessage());
                } else if (isLocalDevelopment()) {
                    // In local development, use DEBUG level to avoid noise
                    logger.debug("Failed to start configuration session (this is expected in local development without AWS credentials): {}", e.getMessage());
                } else {
                    logger.warn("Failed to start configuration session: {}", e.getMessage());
                    logger.debug("AppConfig session error details", e);
                }
                // Don't throw exception - allow application to continue without AppConfig
            }
        }
    }

    /**
     * Get latest configuration
     * Thread-safe implementation
     */
    public String getLatestConfiguration() {
        if (!enabled) {
            return latestConfiguration.get();
        }
        
        // If using fallback config, return it (no refresh needed)
        if (useFallbackConfig) {
            return latestConfiguration.get();
        }
        
        // If using AppConfig API (not AppConfigData), use that
        if (!useAppConfigDataApi && appConfigClient != null) {
            try {
                getConfigurationFromAppConfigApi();
                return latestConfiguration.get();
            } catch (Exception e) {
                logger.debug("Failed to refresh configuration from AppConfig API: {}", e.getMessage());
                return latestConfiguration.get(); // Return cached
            }
        }
        
        // Use AppConfigData API
        if (appConfigDataClient == null) {
            return latestConfiguration.get();
        }
        
        String token = configurationToken.get();
        if (token == null || token.isEmpty()) {
            startConfigurationSession();
            token = configurationToken.get();
        }

        if (token == null || token.isEmpty()) {
            // Log at appropriate level based on environment
            if (isLocalStack()) {
                logger.debug("Configuration token is null (LocalStack doesn't support AppConfigData), returning cached configuration");
            } else if (isLocalDevelopment()) {
                logger.debug("Configuration token is null or empty (expected in local development), returning cached configuration");
            } else {
                logger.warn("Configuration token is null or empty, returning cached configuration");
            }
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
            // LocalStack doesn't support AppConfigData API (returns 501)
            // Log at appropriate level based on whether it's LocalStack
            if (isLocalStack() || isLocalStackNotImplemented(e)) {
                logger.debug("AppConfigData API not supported by LocalStack (this is expected). " +
                        "Using cached/default configuration. Error: {}", e.getMessage());
            } else {
                logger.error("Failed to get latest configuration from AppConfig", e);
            }
            return latestConfiguration.get(); // Return cached configuration
        }
    }

    /**
     * Schedule periodic configuration refresh
     */
    private void scheduleConfigurationRefresh() {
        if (!enabled) {
            return;
        }
        
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
            // Try to load configuration
            getLatestConfiguration();
            config = parsedConfiguration.get();
        }

        if (config == null) {
            // If still null, initialize fallback
            if (useFallbackConfig || isLocalStack()) {
                initializeFallbackConfiguration();
                config = parsedConfiguration.get();
            }
            
            if (config == null) {
                logger.debug("Configuration not available for key: {}, using application.yml defaults", key);
                return Optional.empty();
            }
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
