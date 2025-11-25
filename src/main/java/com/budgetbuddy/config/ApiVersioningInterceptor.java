package com.budgetbuddy.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * API Versioning Interceptor
 * Adds API version headers to all responses
 */
@Component
public class ApiVersioningInterceptor implements HandlerInterceptor {

    @Value("${api.version:1.0.0}")
    private String apiVersion;

    @Value("${api.base-url:}")
    private String apiBaseUrl;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Add API version header
        response.setHeader("X-API-Version", apiVersion);
        
        // Add API base URL if configured
        if (apiBaseUrl != null && !apiBaseUrl.isEmpty()) {
            response.setHeader("X-API-Base-URL", apiBaseUrl);
        }
        
        // Add deprecation notice if needed (can be configured per endpoint)
        // response.setHeader("X-API-Deprecated", "true");
        // response.setHeader("X-API-Sunset", "2024-12-31");
        
        return true;
    }
}

