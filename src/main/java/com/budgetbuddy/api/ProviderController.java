package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.ProviderService;
import com.budgetbuddy.service.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider REST Controller Manages multiple financial data providers with graceful fallback
 *
 * <p>Features: - Multi-provider support (Plaid, Stripe, Finicity, Teller) - Provider health
 * tracking - Automatic fallback - Stale connection detection - Thread-safe operations
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
// PMD's DataClass fires on Request/Response/Config DTOs by design —
// they're intentionally data-only; behaviour belongs in the controller/service.
@SuppressWarnings("PMD.DataClass")
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@RestController
@RequestMapping("/api/providers")
@Tag(name = "Providers", description = "Financial data provider management")
public class ProviderController {

    private static final String PROVIDER_ID_IS_REQUIRED = "Provider ID is required";

    private static final String USER_NOT_AUTHENTICATED = "User not authenticated";

    private static final String USER_NOT_FOUND_1 = "User not found";

    private static final Logger LOGGER = LoggerFactory.getLogger(ProviderController.class);

    private final ProviderService providerService;
    private final UserService userService;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public ProviderController(
            final ProviderService providerService, final UserService userService) {
        this.providerService = providerService;
        this.userService = userService;
    }

    /**
     * Get All Providers Returns list of all configured providers with their health status
     *
     * @param userDetails Authenticated user details
     * @return List of providers with health status
     */
    @GetMapping
    @Operation(
            summary = "Get All Providers",
            description =
                    "Returns list of all configured financial data providers with their health status")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Providers retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "User not authenticated")
    })
    public ResponseEntity<List<ProviderHealthResponse>> getAllProviders(
            @AuthenticationPrincipal final UserDetails userDetails) {

        if (userDetails == null
                || userDetails.getUsername() == null
                || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        LOGGER.debug("Get providers request for user: {}", user.getUserId());

        lock.readLock().lock();
        try {
            final List<ProviderHealthResponse> providers =
                    providerService.getAllProviders(user.getUserId());
            return ResponseEntity.ok(providers);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get Provider Health Returns health status for a specific provider
     *
     * @param userDetails Authenticated user details
     * @param providerId Provider identifier (e.g., "plaid", "stripe", "finicity", "teller")
     * @return Provider health status
     */
    @GetMapping("/{providerId}/health")
    @Operation(
            summary = "Get Provider Health",
            description = "Returns health status for a specific financial data provider")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Provider health retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "User not authenticated"),
        @ApiResponse(responseCode = "404", description = "Provider not found")
    })
    public ResponseEntity<ProviderHealthResponse> getProviderHealth(
            @AuthenticationPrincipal final UserDetails userDetails,
            @PathVariable final String providerId) {

        if (userDetails == null
                || userDetails.getUsername() == null
                || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        if (providerId == null || providerId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, PROVIDER_ID_IS_REQUIRED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        LOGGER.debug(
                "Get provider health request for user: {}, provider: {}",
                user.getUserId(),
                providerId);

        lock.readLock().lock();
        try {
            final ProviderHealthResponse health =
                    providerService.getProviderHealth(user.getUserId(), providerId);
            if (health == null) {
                throw new AppException(
                        ErrorCode.USER_NOT_FOUND, "Provider not found: " + providerId);
            }
            return ResponseEntity.ok(health);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Update Provider Health Updates health status for a provider (called by iOS after sync
     * attempts) Thread-safe to prevent race conditions
     *
     * @param userDetails Authenticated user details
     * @param providerId Provider identifier
     * @param request Health update request
     * @return Updated provider health
     */
    @PostMapping("/{providerId}/health")
    @Operation(
            summary = "Update Provider Health",
            description =
                    "Updates health status for a provider. Thread-safe to prevent race conditions.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Provider health updated successfully"),
        @ApiResponse(responseCode = "401", description = "User not authenticated"),
        @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    public ResponseEntity<ProviderHealthResponse> updateProviderHealth(
            @AuthenticationPrincipal final UserDetails userDetails,
            @PathVariable final String providerId,
            @Valid @RequestBody final ProviderHealthUpdateRequest request) {

        if (userDetails == null
                || userDetails.getUsername() == null
                || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        if (providerId == null || providerId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, PROVIDER_ID_IS_REQUIRED);
        }

        if (request == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Request body is required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        LOGGER.info(
                "Updating provider health for user: {}, provider: {}, healthy: {}",
                user.getUserId(),
                providerId,
                request.getIsHealthy());

        lock.writeLock().lock();
        try {
            final ProviderHealthResponse health =
                    providerService.updateProviderHealth(
                            user.getUserId(),
                            providerId,
                            request.getIsHealthy(),
                            request.getIsStale(),
                            request.getLastError());

            return ResponseEntity.ok(health);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Mark Provider as Stale Marks a provider connection as stale (needs re-authentication)
     *
     * @param userDetails Authenticated user details
     * @param providerId Provider identifier
     * @return Updated provider health
     */
    @PostMapping("/{providerId}/mark-stale")
    @Operation(
            summary = "Mark Provider as Stale",
            description =
                    "Marks a provider connection as stale, indicating it needs re-authentication")
    public ResponseEntity<ProviderHealthResponse> markProviderAsStale(
            @AuthenticationPrincipal final UserDetails userDetails,
            @PathVariable final String providerId) {

        if (userDetails == null
                || userDetails.getUsername() == null
                || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        if (providerId == null || providerId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, PROVIDER_ID_IS_REQUIRED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        LOGGER.info(
                "Marking provider as stale for user: {}, provider: {}",
                user.getUserId(),
                providerId);

        lock.writeLock().lock();
        try {
            final ProviderHealthResponse health =
                    providerService.markProviderAsStale(user.getUserId(), providerId);
            return ResponseEntity.ok(health);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Clear Stale Status Clears stale status after successful re-authentication
     *
     * @param userDetails Authenticated user details
     * @param providerId Provider identifier
     * @return Updated provider health
     */
    @PostMapping("/{providerId}/clear-stale")
    @Operation(
            summary = "Clear Stale Status",
            description = "Clears stale status after successful re-authentication")
    public ResponseEntity<ProviderHealthResponse> clearStaleStatus(
            @AuthenticationPrincipal final UserDetails userDetails,
            @PathVariable final String providerId) {

        if (userDetails == null
                || userDetails.getUsername() == null
                || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        if (providerId == null || providerId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, PROVIDER_ID_IS_REQUIRED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        LOGGER.info(
                "Clearing stale status for user: {}, provider: {}", user.getUserId(), providerId);

        lock.writeLock().lock();
        try {
            final ProviderHealthResponse health =
                    providerService.clearStaleStatus(user.getUserId(), providerId);
            return ResponseEntity.ok(health);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Request/Response DTOs
    public static class ProviderHealthUpdateRequest {
        private Boolean isHealthy;
        private Boolean isStale;
        private String lastError;

        public Boolean getIsHealthy() {
            return isHealthy;
        }

        public void setIsHealthy(final Boolean isHealthy) {
            this.isHealthy = isHealthy;
        }

        public Boolean getIsStale() {
            return isStale;
        }

        public void setIsStale(final Boolean isStale) {
            this.isStale = isStale;
        }

        public String getLastError() {
            return lastError;
        }

        public void setLastError(final String lastError) {
            this.lastError = lastError;
        }
    }

    public static class ProviderHealthResponse {
        private String providerId;
        private Boolean isHealthy;
        private java.time.Instant lastSuccess;
        private Integer failureCount;
        private Boolean isStale;
        private String lastError;

        public String getProviderId() {
            return providerId;
        }

        public void setProviderId(final String providerId) {
            this.providerId = providerId;
        }

        public Boolean getIsHealthy() {
            return isHealthy;
        }

        public void setIsHealthy(final Boolean isHealthy) {
            this.isHealthy = isHealthy;
        }

        public java.time.Instant getLastSuccess() {
            return lastSuccess;
        }

        public void setLastSuccess(final java.time.Instant lastSuccess) {
            this.lastSuccess = lastSuccess;
        }

        public Integer getFailureCount() {
            return failureCount;
        }

        public void setFailureCount(final Integer failureCount) {
            this.failureCount = failureCount;
        }

        public Boolean getIsStale() {
            return isStale;
        }

        public void setIsStale(final Boolean isStale) {
            this.isStale = isStale;
        }

        public String getLastError() {
            return lastError;
        }

        public void setLastError(final String lastError) {
            this.lastError = lastError;
        }
    }
}
