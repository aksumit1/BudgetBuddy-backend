package com.budgetbuddy.api;

import com.budgetbuddy.config.DnsCacheConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * System Management Controller
 * Provides endpoints for system maintenance operations without requiring full restarts
 * 
 * Security: These endpoints should be restricted to admin users or internal services only
 */
@RestController
@RequestMapping("/api/system")
public class SystemManagementController {

    private static final Logger logger = LoggerFactory.getLogger(SystemManagementController.class);

    private final DnsCacheConfig dnsCacheConfig;

    public SystemManagementController(final DnsCacheConfig dnsCacheConfig) {
        this.dnsCacheConfig = dnsCacheConfig;
    }

    /**
     * Clear DNS cache
     * Useful when services restart and DNS lookups are cached as failed
     * 
     * POST /api/system/dns/clear
     * 
     * @return Success message
     */
    @PostMapping("/dns/clear")
    @PreAuthorize("hasRole('ADMIN')") // Restrict to admin users
    public ResponseEntity<Map<String, String>> clearDnsCache() {
        try {
            logger.info("DNS cache clear requested");
            dnsCacheConfig.clearDnsCache();
            
            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "DNS cache cleared successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to clear DNS cache: {}", e.getMessage(), e);
            Map<String, String> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", "Failed to clear DNS cache: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Health check for system management endpoints
     * 
     * GET /api/system/health
     * 
     * @return System health status
     */
    @PostMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "healthy");
        response.put("service", "system-management");
        return ResponseEntity.ok(response);
    }
}

