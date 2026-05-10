package com.budgetbuddy.api;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.budgetbuddy.config.DnsCacheConfig;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * System Management Controller Provides endpoints for system maintenance operations without
 * requiring full restarts
 *
 * <p>Security: These endpoints should be restricted to admin users or internal services only
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@RestController
@RequestMapping("/api/system")
public class SystemManagementController {

    private static final Logger LOGGER = LoggerFactory.getLogger(SystemManagementController.class);

    private final DnsCacheConfig dnsCacheConfig;

    public SystemManagementController(final DnsCacheConfig dnsCacheConfig) {
        this.dnsCacheConfig = dnsCacheConfig;
    }

    /**
     * Clear DNS cache Useful when services restart and DNS lookups are cached as failed
     *
     * <p>POST /api/system/dns/clear
     *
     * @return Success message
     */
    @PostMapping("/dns/clear")
    @PreAuthorize("hasRole('ADMIN')") // Restrict to admin users
    public ResponseEntity<Map<String, String>> clearDnsCache() {
        try {
            LOGGER.info("DNS cache clear requested");
            dnsCacheConfig.clearDnsCache();

            final Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "DNS cache cleared successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LOGGER.error("Failed to clear DNS cache: {}", e.getMessage(), e);
            final Map<String, String> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", "Failed to clear DNS cache: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Health check for system management endpoints
     *
     * <p>GET /api/system/health
     *
     * @return System health status
     */
    @PostMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        final Map<String, String> response = new HashMap<>();
        response.put("status", "healthy");
        response.put("service", "system-management");
        return ResponseEntity.ok(response);
    }
}
