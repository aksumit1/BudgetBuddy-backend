package com.budgetbuddy.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API Versioning Configuration
 * Supports multiple API versions for backward compatibility
 */
@Configuration
public class ApiVersioningConfig implements WebMvcConfigurer {

    // API versioning is handled via:
    // 1. URL path: /api/v1/, /api/v2/
    // 2. Header: X-API-Version
    // 3. Query parameter: ?version=1

    // Controllers should use @RequestMapping("/api/v1/...") for versioning
}

