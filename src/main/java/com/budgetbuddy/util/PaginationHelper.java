package com.budgetbuddy.util;

import java.util.List;

/**
 * Pagination Helper Utility
 * Provides utilities for pagination of large result sets
 */
public class PaginationHelper {

    private PaginationHelper() {
        // Utility class
    }

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
     */
    public static int normalizePageSize(final int pageSize, final int defaultSize, final int maxSize) {
        if (pageSize <= 0) {
            return defaultSize;
        }
        return Math.min(pageSize, maxSize);
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

