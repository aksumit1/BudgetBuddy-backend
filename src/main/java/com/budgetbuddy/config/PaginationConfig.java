package com.budgetbuddy.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Pagination Configuration
 * Centralizes pagination limits and defaults
 */
@Configuration
public class PaginationConfig {

    @Value("${app.pagination.default-page-size:20}")
    private int defaultPageSize;

    @Value("${app.pagination.max-page-size:100}")
    private int maxPageSize;

    @Value("${app.pagination.max-preview-page-size:1000}")
    private int maxPreviewPageSize;

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public int getMaxPreviewPageSize() {
        return maxPreviewPageSize;
    }
}

