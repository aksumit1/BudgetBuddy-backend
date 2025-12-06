package com.budgetbuddy.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for PaginationHelper utility class
 */
class PaginationHelperTest {

    @Test
    void testCalculateSkip_WithValidPage_ReturnsCorrectSkip() {
        // Given
        int page = 3;
        int pageSize = 20;

        // When
        int skip = PaginationHelper.calculateSkip(page, pageSize);

        // Then
        assertEquals(40, skip, "Skip should be (page - 1) * pageSize");
    }

    @Test
    void testCalculateSkip_WithPageOne_ReturnsZero() {
        // Given
        int page = 1;
        int pageSize = 20;

        // When
        int skip = PaginationHelper.calculateSkip(page, pageSize);

        // Then
        assertEquals(0, skip, "First page should have skip of 0");
    }

    @Test
    void testCalculateSkip_WithPageZero_ReturnsZero() {
        // Given
        int page = 0;
        int pageSize = 20;

        // When
        int skip = PaginationHelper.calculateSkip(page, pageSize);

        // Then
        assertEquals(0, skip, "Page 0 should return skip of 0");
    }

    @Test
    void testCalculateSkip_WithNegativePage_ReturnsZero() {
        // Given
        int page = -1;
        int pageSize = 20;

        // When
        int skip = PaginationHelper.calculateSkip(page, pageSize);

        // Then
        assertEquals(0, skip, "Negative page should return skip of 0");
    }

    @Test
    void testNormalizePageSize_WithValidSize_ReturnsSize() {
        // Given
        int pageSize = 50;
        int defaultSize = 20;
        int maxSize = 100;

        // When
        int normalized = PaginationHelper.normalizePageSize(pageSize, defaultSize, maxSize);

        // Then
        assertEquals(50, normalized, "Valid page size should be returned as-is");
    }

    @Test
    void testNormalizePageSize_WithZero_ReturnsDefault() {
        // Given
        int pageSize = 0;
        int defaultSize = 20;
        int maxSize = 100;

        // When
        int normalized = PaginationHelper.normalizePageSize(pageSize, defaultSize, maxSize);

        // Then
        assertEquals(defaultSize, normalized, "Zero page size should return default");
    }

    @Test
    void testNormalizePageSize_WithNegative_ReturnsDefault() {
        // Given
        int pageSize = -10;
        int defaultSize = 20;
        int maxSize = 100;

        // When
        int normalized = PaginationHelper.normalizePageSize(pageSize, defaultSize, maxSize);

        // Then
        assertEquals(defaultSize, normalized, "Negative page size should return default");
    }

    @Test
    void testNormalizePageSize_ExceedingMax_ReturnsMax() {
        // Given
        int pageSize = 200;
        int defaultSize = 20;
        int maxSize = 100;

        // When
        int normalized = PaginationHelper.normalizePageSize(pageSize, defaultSize, maxSize);

        // Then
        assertEquals(maxSize, normalized, "Page size exceeding max should return max");
    }

    @Test
    void testNormalizePageSize_AtMax_ReturnsMax() {
        // Given
        int pageSize = 100;
        int defaultSize = 20;
        int maxSize = 100;

        // When
        int normalized = PaginationHelper.normalizePageSize(pageSize, defaultSize, maxSize);

        // Then
        assertEquals(maxSize, normalized, "Page size at max should return max");
    }

    @Test
    void testCreateResult_WithFirstPage_ReturnsCorrectResult() {
        // Given
        List<String> items = Arrays.asList("item1", "item2", "item3");
        int page = 1;
        int pageSize = 10;
        int totalItems = 25;

        // When
        PaginationHelper.PaginationResult<String> result = PaginationHelper.createResult(items, page, pageSize, totalItems);

        // Then
        assertEquals(items, result.getItems());
        assertEquals(page, result.getPage());
        assertEquals(pageSize, result.getPageSize());
        assertEquals(totalItems, result.getTotalItems());
        assertTrue(result.hasNext(), "Should have next page");
        assertFalse(result.hasPrevious(), "First page should not have previous");
        assertEquals(3, result.getTotalPages(), "Should calculate total pages correctly");
    }

    @Test
    void testCreateResult_WithMiddlePage_ReturnsCorrectResult() {
        // Given
        List<String> items = Arrays.asList("item11", "item12", "item13");
        int page = 2;
        int pageSize = 10;
        int totalItems = 25;

        // When
        PaginationHelper.PaginationResult<String> result = PaginationHelper.createResult(items, page, pageSize, totalItems);

        // Then
        assertEquals(items, result.getItems());
        assertEquals(page, result.getPage());
        assertEquals(pageSize, result.getPageSize());
        assertEquals(totalItems, result.getTotalItems());
        assertTrue(result.hasNext(), "Should have next page");
        assertTrue(result.hasPrevious(), "Middle page should have previous");
        assertEquals(3, result.getTotalPages(), "Should calculate total pages correctly");
    }

    @Test
    void testCreateResult_WithLastPage_ReturnsCorrectResult() {
        // Given
        List<String> items = Arrays.asList("item21", "item22", "item23", "item24", "item25");
        int page = 3;
        int pageSize = 10;
        int totalItems = 25;

        // When
        PaginationHelper.PaginationResult<String> result = PaginationHelper.createResult(items, page, pageSize, totalItems);

        // Then
        assertEquals(items, result.getItems());
        assertEquals(page, result.getPage());
        assertEquals(pageSize, result.getPageSize());
        assertEquals(totalItems, result.getTotalItems());
        assertFalse(result.hasNext(), "Last page should not have next");
        assertTrue(result.hasPrevious(), "Last page should have previous");
        assertEquals(3, result.getTotalPages(), "Should calculate total pages correctly");
    }

    @Test
    void testCreateResult_WithEmptyItems_ReturnsCorrectResult() {
        // Given
        List<String> items = new ArrayList<>();
        int page = 1;
        int pageSize = 10;
        int totalItems = 0;

        // When
        PaginationHelper.PaginationResult<String> result = PaginationHelper.createResult(items, page, pageSize, totalItems);

        // Then
        assertTrue(result.getItems().isEmpty());
        assertEquals(page, result.getPage());
        assertEquals(pageSize, result.getPageSize());
        assertEquals(0, result.getTotalItems());
        assertFalse(result.hasNext(), "Empty result should not have next");
        assertFalse(result.hasPrevious(), "First page should not have previous");
        assertEquals(0, result.getTotalPages(), "Should calculate total pages correctly");
    }

    @Test
    void testCreateResult_WithExactPageSize_ReturnsCorrectResult() {
        // Given
        List<String> items = Arrays.asList("item1", "item2", "item3", "item4", "item5");
        int page = 1;
        int pageSize = 5;
        int totalItems = 5;

        // When
        PaginationHelper.PaginationResult<String> result = PaginationHelper.createResult(items, page, pageSize, totalItems);

        // Then
        assertEquals(items, result.getItems());
        assertFalse(result.hasNext(), "Exact page size should not have next");
        assertFalse(result.hasPrevious(), "First page should not have previous");
        assertEquals(1, result.getTotalPages(), "Should have exactly one page");
    }

    @Test
    void testPaginationResult_GetTotalPages_WithRemainder_CalculatesCorrectly() {
        // Given
        List<String> items = Arrays.asList("item1", "item2");
        int page = 1;
        int pageSize = 10;
        int totalItems = 22; // 22 items with page size 10 = 3 pages

        // When
        PaginationHelper.PaginationResult<String> result = PaginationHelper.createResult(items, page, pageSize, totalItems);

        // Then
        assertEquals(3, result.getTotalPages(), "Should round up to 3 pages for 22 items with page size 10");
    }

    @Test
    void testPaginationResult_GetTotalPages_WithNoRemainder_CalculatesCorrectly() {
        // Given
        List<String> items = Arrays.asList("item1", "item2");
        int page = 1;
        int pageSize = 10;
        int totalItems = 20; // 20 items with page size 10 = 2 pages

        // When
        PaginationHelper.PaginationResult<String> result = PaginationHelper.createResult(items, page, pageSize, totalItems);

        // Then
        assertEquals(2, result.getTotalPages(), "Should have exactly 2 pages for 20 items with page size 10");
    }
}

