package com.budgetbuddy.service;

import com.budgetbuddy.api.ProviderController;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Provider Service Manages multiple financial data providers with health tracking
 *
 * <p>Supported Providers: - Plaid (primary) - Stripe - Finicity (future) - Teller (future)
 *
 * <p>Features: - Thread-safe health tracking - Automatic fallback support - Stale connection
 * detection - Provider priority management
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@Service
public class ProviderService {

    private static final String PROVIDER_ID_CANNOT_BE_NULL_OR_EMPTY = "Provider ID cannot be null or empty";

    private static final String USER_ID_CANNOT_BE_NULL_OR_EMPTY = "User ID cannot be null or empty";

    private static final Logger LOGGER = LoggerFactory.getLogger(ProviderService.class);

    // Thread-safe storage for provider health per user
    private final Map<String, Map<String, ProviderHealth>> userProviderHealth =
            new ConcurrentHashMap<>();

    /** Get all providers with health status for a user */
    public List<ProviderController.ProviderHealthResponse> getAllProviders(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException(USER_ID_CANNOT_BE_NULL_OR_EMPTY);
        }

        final Map<String, ProviderHealth> providers =
                userProviderHealth.getOrDefault(userId, new ConcurrentHashMap<>());

        final List<ProviderController.ProviderHealthResponse> response = new ArrayList<>();

        // Always include primary providers
        final String[] primaryProviders = {"plaid", "stripe"};
        for (final String providerId : primaryProviders) {
            final ProviderHealth health =
                    providers.getOrDefault(
                            providerId, new ProviderHealth(providerId, true, null, 0, false, null));
            response.add(toResponse(health));
        }

        // Include any additional providers
        for (final Map.Entry<String, ProviderHealth> entry : providers.entrySet()) {
            final String providerId = entry.getKey();
            if (!response.stream().anyMatch(r -> r.getProviderId().equals(providerId))) {
                response.add(toResponse(entry.getValue()));
            }
        }

        LOGGER.debug("Retrieved {} providers for user: {}", response.size(), userId);
        return response;
    }

    /** Get health status for a specific provider */
    public ProviderController.ProviderHealthResponse getProviderHealth(
            final String userId, final String providerId) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException(USER_ID_CANNOT_BE_NULL_OR_EMPTY);
        }
        if (providerId == null || providerId.isEmpty()) {
            throw new IllegalArgumentException(PROVIDER_ID_CANNOT_BE_NULL_OR_EMPTY);
        }

        final Map<String, ProviderHealth> providers =
                userProviderHealth.getOrDefault(userId, new ConcurrentHashMap<>());
        final ProviderHealth health =
                providers.getOrDefault(
                        providerId, new ProviderHealth(providerId, true, null, 0, false, null));

        return toResponse(health);
    }

    /** Update provider health status */
    public ProviderController.ProviderHealthResponse updateProviderHealth(
            final String userId,
            final String providerId,
            final Boolean isHealthy,
            final Boolean isStale,
            final String lastError) {

        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException(USER_ID_CANNOT_BE_NULL_OR_EMPTY);
        }
        if (providerId == null || providerId.isEmpty()) {
            throw new IllegalArgumentException(PROVIDER_ID_CANNOT_BE_NULL_OR_EMPTY);
        }

        final Map<String, ProviderHealth> providers =
                userProviderHealth.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());

        final ProviderHealth currentHealth =
                providers.getOrDefault(
                        providerId, new ProviderHealth(providerId, true, null, 0, false, null));

        // Update health status based on request or current state
        final boolean healthy = isHealthy != null ? isHealthy : currentHealth.isHealthy();
        final boolean stale = isStale != null ? isStale : currentHealth.isStale();
        final Instant lastSuccess = healthy ? Instant.now() : currentHealth.getLastSuccess();
        // Only increment failure count if explicitly marked as unhealthy
        final int failureCount =
                healthy
                        ? 0
                        : (isHealthy != null && !isHealthy
                        ? (currentHealth.getFailureCount() + 1)
                        : currentHealth.getFailureCount());
        final String errorMessage = lastError != null ? lastError : currentHealth.getLastError();

        final ProviderHealth updatedHealth =
                new ProviderHealth(
                        providerId, healthy, lastSuccess, failureCount, stale, errorMessage);

        providers.put(providerId, updatedHealth);

        LOGGER.info(
                "Updated provider health for user: {}, provider: {}, healthy: {}, stale: {}",
                userId,
                providerId,
                healthy,
                stale);

        return toResponse(updatedHealth);
    }

    /** Mark provider as stale */
    public ProviderController.ProviderHealthResponse markProviderAsStale(
            final String userId, final String providerId) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException(USER_ID_CANNOT_BE_NULL_OR_EMPTY);
        }
        if (providerId == null || providerId.isEmpty()) {
            throw new IllegalArgumentException(PROVIDER_ID_CANNOT_BE_NULL_OR_EMPTY);
        }

        final Map<String, ProviderHealth> providers =
                userProviderHealth.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());

        final ProviderHealth currentHealth =
                providers.getOrDefault(
                        providerId, new ProviderHealth(providerId, true, null, 0, false, null));

        final ProviderHealth staleHealth =
                new ProviderHealth(
                        providerId,
                        currentHealth.isHealthy(),
                        currentHealth.getLastSuccess(),
                        currentHealth.getFailureCount(),
                        true, // Mark as stale
                        currentHealth.getLastError());

        providers.put(providerId, staleHealth);

        LOGGER.info("Marked provider as stale for user: {}, provider: {}", userId, providerId);

        return toResponse(staleHealth);
    }

    /** Clear stale status after successful re-authentication */
    public ProviderController.ProviderHealthResponse clearStaleStatus(
            final String userId, final String providerId) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException(USER_ID_CANNOT_BE_NULL_OR_EMPTY);
        }
        if (providerId == null || providerId.isEmpty()) {
            throw new IllegalArgumentException(PROVIDER_ID_CANNOT_BE_NULL_OR_EMPTY);
        }

        final Map<String, ProviderHealth> providers =
                userProviderHealth.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());

        // Create cleared health status (currentHealth not needed here)
        final ProviderHealth clearedHealth =
                new ProviderHealth(
                        providerId,
                        true, // Mark as healthy
                        Instant.now(), // Update last success
                        0, // Reset failure count
                        false, // Clear stale status
                        null // Clear error
                );

        providers.put(providerId, clearedHealth);

        LOGGER.info("Cleared stale status for user: {}, provider: {}", userId, providerId);

        return toResponse(clearedHealth);
    }

    /** Convert ProviderHealth to ProviderHealthResponse */
    private ProviderController.ProviderHealthResponse toResponse(final ProviderHealth health) {
        final ProviderController.ProviderHealthResponse response =
                new ProviderController.ProviderHealthResponse();
        response.setProviderId(health.getProviderId());
        response.setIsHealthy(health.isHealthy());
        response.setLastSuccess(health.getLastSuccess());
        response.setFailureCount(health.getFailureCount());
        response.setIsStale(health.isStale());
        response.setLastError(health.getLastError());
        return response;
    }

    /** Internal Provider Health data structure */
    private static class ProviderHealth {
        private final String providerId;
        private final boolean isHealthy;
        private final Instant lastSuccess;
        private final int failureCount;
        private final boolean isStale;
        private final String lastError;

        ProviderHealth(
                final String providerId,
                final boolean isHealthy,
                final Instant lastSuccess,
                final int failureCount,
                final boolean isStale,
                final String lastError) {
            this.providerId = providerId;
            this.isHealthy = isHealthy;
            this.lastSuccess = lastSuccess;
            this.failureCount = failureCount;
            this.isStale = isStale;
            this.lastError = lastError;
        }

        public String getProviderId() {
            return providerId;
        }

        public boolean isHealthy() {
            return isHealthy;
        }

        public Instant getLastSuccess() {
            return lastSuccess;
        }

        public int getFailureCount() {
            return failureCount;
        }

        public boolean isStale() {
            return isStale;
        }

        public String getLastError() {
            return lastError;
        }
    }
}
