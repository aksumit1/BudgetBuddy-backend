package com.budgetbuddy.service;

import com.budgetbuddy.api.ProviderController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider Service
 * Manages multiple financial data providers with health tracking
 * 
 * Supported Providers:
 * - Plaid (primary)
 * - Stripe
 * - Finicity (future)
 * - Teller (future)
 * 
 * Features:
 * - Thread-safe health tracking
 * - Automatic fallback support
 * - Stale connection detection
 * - Provider priority management
 */
@Service
public class ProviderService {

    private static final Logger logger = LoggerFactory.getLogger(ProviderService.class);

    // Thread-safe storage for provider health per user
    private final Map<String, Map<String, ProviderHealth>> userProviderHealth = new ConcurrentHashMap<>();

    /**
     * Get all providers with health status for a user
     */
    public List<ProviderController.ProviderHealthResponse> getAllProviders(String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("User ID cannot be null or empty");
        }

        Map<String, ProviderHealth> providers = userProviderHealth.getOrDefault(userId, new ConcurrentHashMap<>());
        
        List<ProviderController.ProviderHealthResponse> response = new ArrayList<>();
        
        // Always include primary providers
        String[] primaryProviders = {"plaid", "stripe"};
        for (String providerId : primaryProviders) {
            ProviderHealth health = providers.getOrDefault(providerId, 
                    new ProviderHealth(providerId, true, null, 0, false, null));
            response.add(toResponse(health));
        }
        
        // Include any additional providers
        for (Map.Entry<String, ProviderHealth> entry : providers.entrySet()) {
            String providerId = entry.getKey();
            if (!response.stream().anyMatch(r -> r.getProviderId().equals(providerId))) {
                response.add(toResponse(entry.getValue()));
            }
        }
        
        logger.debug("Retrieved {} providers for user: {}", response.size(), userId);
        return response;
    }

    /**
     * Get health status for a specific provider
     */
    public ProviderController.ProviderHealthResponse getProviderHealth(String userId, String providerId) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("User ID cannot be null or empty");
        }
        if (providerId == null || providerId.isEmpty()) {
            throw new IllegalArgumentException("Provider ID cannot be null or empty");
        }

        Map<String, ProviderHealth> providers = userProviderHealth.getOrDefault(userId, new ConcurrentHashMap<>());
        ProviderHealth health = providers.getOrDefault(providerId, 
                new ProviderHealth(providerId, true, null, 0, false, null));
        
        return toResponse(health);
    }

    /**
     * Update provider health status
     */
    public ProviderController.ProviderHealthResponse updateProviderHealth(
            String userId, 
            String providerId, 
            Boolean isHealthy, 
            Boolean isStale,
            String lastError) {
        
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("User ID cannot be null or empty");
        }
        if (providerId == null || providerId.isEmpty()) {
            throw new IllegalArgumentException("Provider ID cannot be null or empty");
        }

        Map<String, ProviderHealth> providers = userProviderHealth.computeIfAbsent(userId, 
                k -> new ConcurrentHashMap<>());
        
        ProviderHealth currentHealth = providers.getOrDefault(providerId, 
                new ProviderHealth(providerId, true, null, 0, false, null));
        
        // Update health status based on request or current state
        boolean healthy = isHealthy != null ? isHealthy : currentHealth.isHealthy();
        boolean stale = isStale != null ? isStale : currentHealth.isStale();
        Instant lastSuccess = healthy ? Instant.now() : currentHealth.getLastSuccess();
        // Only increment failure count if explicitly marked as unhealthy
        int failureCount = healthy ? 0 : (isHealthy != null && !isHealthy ? 
                (currentHealth.getFailureCount() + 1) : currentHealth.getFailureCount());
        String errorMessage = lastError != null ? lastError : currentHealth.getLastError();
        
        ProviderHealth updatedHealth = new ProviderHealth(
                providerId,
                healthy,
                lastSuccess,
                failureCount,
                stale,
                errorMessage
        );
        
        providers.put(providerId, updatedHealth);
        
        logger.info("Updated provider health for user: {}, provider: {}, healthy: {}, stale: {}", 
                userId, providerId, healthy, stale);
        
        return toResponse(updatedHealth);
    }

    /**
     * Mark provider as stale
     */
    public ProviderController.ProviderHealthResponse markProviderAsStale(String userId, String providerId) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("User ID cannot be null or empty");
        }
        if (providerId == null || providerId.isEmpty()) {
            throw new IllegalArgumentException("Provider ID cannot be null or empty");
        }

        Map<String, ProviderHealth> providers = userProviderHealth.computeIfAbsent(userId, 
                k -> new ConcurrentHashMap<>());
        
        ProviderHealth currentHealth = providers.getOrDefault(providerId, 
                new ProviderHealth(providerId, true, null, 0, false, null));
        
        ProviderHealth staleHealth = new ProviderHealth(
                providerId,
                currentHealth.isHealthy(),
                currentHealth.getLastSuccess(),
                currentHealth.getFailureCount(),
                true, // Mark as stale
                currentHealth.getLastError()
        );
        
        providers.put(providerId, staleHealth);
        
        logger.info("Marked provider as stale for user: {}, provider: {}", userId, providerId);
        
        return toResponse(staleHealth);
    }

    /**
     * Clear stale status after successful re-authentication
     */
    public ProviderController.ProviderHealthResponse clearStaleStatus(String userId, String providerId) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("User ID cannot be null or empty");
        }
        if (providerId == null || providerId.isEmpty()) {
            throw new IllegalArgumentException("Provider ID cannot be null or empty");
        }

        Map<String, ProviderHealth> providers = userProviderHealth.computeIfAbsent(userId, 
                k -> new ConcurrentHashMap<>());
        
        // Create cleared health status (currentHealth not needed here)
        ProviderHealth clearedHealth = new ProviderHealth(
                providerId,
                true, // Mark as healthy
                Instant.now(), // Update last success
                0, // Reset failure count
                false, // Clear stale status
                null // Clear error
        );
        
        providers.put(providerId, clearedHealth);
        
        logger.info("Cleared stale status for user: {}, provider: {}", userId, providerId);
        
        return toResponse(clearedHealth);
    }

    /**
     * Convert ProviderHealth to ProviderHealthResponse
     */
    private ProviderController.ProviderHealthResponse toResponse(ProviderHealth health) {
        ProviderController.ProviderHealthResponse response = new ProviderController.ProviderHealthResponse();
        response.setProviderId(health.getProviderId());
        response.setIsHealthy(health.isHealthy());
        response.setLastSuccess(health.getLastSuccess());
        response.setFailureCount(health.getFailureCount());
        response.setIsStale(health.isStale());
        response.setLastError(health.getLastError());
        return response;
    }

    /**
     * Internal Provider Health data structure
     */
    private static class ProviderHealth {
        private final String providerId;
        private final boolean isHealthy;
        private final Instant lastSuccess;
        private final int failureCount;
        private final boolean isStale;
        private final String lastError;

        public ProviderHealth(String providerId, boolean isHealthy, Instant lastSuccess, 
                             int failureCount, boolean isStale, String lastError) {
            this.providerId = providerId;
            this.isHealthy = isHealthy;
            this.lastSuccess = lastSuccess;
            this.failureCount = failureCount;
            this.isStale = isStale;
            this.lastError = lastError;
        }

        public String getProviderId() { return providerId; }
        public boolean isHealthy() { return isHealthy; }
        public Instant getLastSuccess() { return lastSuccess; }
        public int getFailureCount() { return failureCount; }
        public boolean isStale() { return isStale; }
        public String getLastError() { return lastError; }
    }
}

