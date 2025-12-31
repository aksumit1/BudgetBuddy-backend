package com.budgetbuddy.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Pagination Helper Utility
 * Provides utilities for pagination of large result sets
 * MEDIUM PRIORITY FIX: Now uses configurable default and max page sizes
 * 
 * Note: This is a Spring component but maintains static methods for backward compatibility
 */
@Component
public class PaginationHelper {

    private static int defaultPageSize = 50;
    private static int maxPageSize = 1000;

    // Inject configuration values (called by Spring)
    public PaginationHelper(
            @Value("${app.pagination.default-page-size:50}") final int defaultPageSize,
            @Value("${app.pagination.max-page-size:1000}") final int maxPageSize) {
        // Set static values for backward compatibility with existing static method calls
        PaginationHelper.defaultPageSize = defaultPageSize;
        PaginationHelper.maxPageSize = maxPageSize;
    }

    // Static utility methods for backward compatibility
    public static int getDefaultPageSize() {
        return defaultPageSize;
    }

    public static int getMaxPageSize() {
        return maxPageSize;
    }

    // Note: Spring will use the public constructor above for dependency injection
    // Static methods are available for backward compatibility

    /**
     * Pagination result wrapper
     */
    public static class PaginationResult<T> {
        private final List<T> items;
        private final int page;
        private final int pageSize;
        private final int totalItems;
        private final boolean hasNext;
        private final boolean hasPrevious;

        public PaginationResult(
                final List<T> items,
                final int page,
                final int pageSize,
                final int totalItems,
                final boolean hasNext,
                final boolean hasPrevious) {
            this.items = items;
            this.page = page;
            this.pageSize = pageSize;
            this.totalItems = totalItems;
            this.hasNext = hasNext;
            this.hasPrevious = hasPrevious;
        }

        public List<T> getItems() {
            return items;
        }

        public int getPage() {
            return page;
        }

        public int getPageSize() {
            return pageSize;
        }

        public int getTotalItems() {
            return totalItems;
        }

        public boolean hasNext() {
            return hasNext;
        }

        public boolean hasPrevious() {
            return hasPrevious;
        }

        public int getTotalPages() {
            return (int) Math.ceil((double) totalItems / pageSize);
        }
    }

    /**
     * Calculate skip value from page and page size
     */
    public static int calculateSkip(final int page, final int pageSize) {
        if (page < 1) {
            return 0;
        }
        return (page - 1) * pageSize;
    }

    /**
     * Validate and normalize page size
     * Uses configured default and max page sizes if not provided
     */
    public static int normalizePageSize(final int pageSize, final Integer defaultSize, final Integer maxSize) {
        int effectiveDefault = defaultSize != null ? defaultSize : getDefaultPageSize();
        int effectiveMax = maxSize != null ? maxSize : getMaxPageSize();
        
        if (pageSize <= 0) {
            return effectiveDefault;
        }
        return Math.min(pageSize, effectiveMax);
    }
    
    /**
     * Validate and normalize page size using configured defaults
     */
    public static int normalizePageSize(final int pageSize) {
        return normalizePageSize(pageSize, null, null);
    }

    /**
     * Create pagination result
     */
    public static <T> PaginationResult<T> createResult(
            final List<T> items,
            final int page,
            final int pageSize,
            final int totalItems) {
        boolean hasNext = (page * pageSize) < totalItems;
        boolean hasPrevious = page > 1;
        return new PaginationResult<>(items, page, pageSize, totalItems, hasNext, hasPrevious);
    }
}

