package com.budgetbuddy.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for PaginationHelper
 */
class PaginationHelperTest {

    @BeforeEach
    void setUp() {
        // Reset static fields to defaults
        ReflectionTestUtils.setField(PaginationHelper.class, "defaultPageSize", 50);
        ReflectionTestUtils.setField(PaginationHelper.class, "maxPageSize", 1000);
    }

    @Test
    void testGetDefaultPageSize_ReturnsConfiguredValue() {
        // When
        int defaultSize = PaginationHelper.getDefaultPageSize();

        // Then
        assertEquals(50, defaultSize);
    }

    @Test
    void testGetMaxPageSize_ReturnsConfiguredValue() {
        // When
        int maxSize = PaginationHelper.getMaxPageSize();

        // Then
        assertEquals(1000, maxSize);
    }

    @Test
    void testCalculateSkip_WithValidPage_ReturnsCorrectSkip() {
        // When/Then
        assertEquals(0, PaginationHelper.calculateSkip(1, 20));
        assertEquals(20, PaginationHelper.calculateSkip(2, 20));
        assertEquals(40, PaginationHelper.calculateSkip(3, 20));
    }

    @Test
    void testCalculateSkip_WithPageLessThanOne_ReturnsZero() {
        // When/Then
        assertEquals(0, PaginationHelper.calculateSkip(0, 20));
        assertEquals(0, PaginationHelper.calculateSkip(-1, 20));
    }

    @Test
    void testNormalizePageSize_WithValidSize_ReturnsSameSize() {
        // When
        int normalized = PaginationHelper.normalizePageSize(25, null, null);

        // Then
        assertEquals(25, normalized);
    }

    @Test
    void testNormalizePageSize_WithZero_ReturnsDefault() {
        // When
        int normalized = PaginationHelper.normalizePageSize(0, null, null);

        // Then
        assertEquals(50, normalized);
    }

    @Test
    void testNormalizePageSize_WithNegative_ReturnsDefault() {
        // When
        int normalized = PaginationHelper.normalizePageSize(-5, null, null);

        // Then
        assertEquals(50, normalized);
    }

    @Test
    void testNormalizePageSize_WithSizeExceedingMax_ReturnsMax() {
        // When
        int normalized = PaginationHelper.normalizePageSize(2000, null, null);

        // Then
        assertEquals(1000, normalized);
    }

    @Test
    void testNormalizePageSize_WithCustomDefaults_UsesCustomValues() {
        // When
        int normalized = PaginationHelper.normalizePageSize(0, 100, 500);

        // Then
        assertEquals(100, normalized);
    }

    @Test
    void testNormalizePageSize_WithCustomMax_UsesCustomMax() {
        // When
        int normalized = PaginationHelper.normalizePageSize(1000, null, 500);

        // Then
        assertEquals(500, normalized);
    }

    @Test
    void testNormalizePageSize_WithoutParameters_UsesDefaults() {
        // When
        int normalized = PaginationHelper.normalizePageSize(25);

        // Then
        assertEquals(25, normalized);
    }

    @Test
    void testCreateResult_WithValidInputs_CreatesCorrectResult() {
        // Given
        List<String> items = Arrays.asList("item1", "item2", "item3");
        int page = 1;
        int pageSize = 20;
        int totalItems = 50;

        // When
        PaginationHelper.PaginationResult<String> result = 
                PaginationHelper.createResult(items, page, pageSize, totalItems);

        // Then
        assertNotNull(result);
        assertEquals(items, result.getItems());
        assertEquals(page, result.getPage());
        assertEquals(pageSize, result.getPageSize());
        assertEquals(totalItems, result.getTotalItems());
        assertTrue(result.hasNext());
        assertFalse(result.hasPrevious());
    }

    @Test
    void testCreateResult_WithLastPage_HasNoNext() {
        // Given
        List<String> items = Arrays.asList("item1", "item2");
        int page = 3;
        int pageSize = 20;
        int totalItems = 50;

        // When
        PaginationHelper.PaginationResult<String> result = 
                PaginationHelper.createResult(items, page, pageSize, totalItems);

        // Then
        assertFalse(result.hasNext());
        assertTrue(result.hasPrevious());
    }

    @Test
    void testCreateResult_WithFirstPage_HasNoPrevious() {
        // Given
        List<String> items = Arrays.asList("item1", "item2");
        int page = 1;
        int pageSize = 20;
        int totalItems = 50;

        // When
        PaginationHelper.PaginationResult<String> result = 
                PaginationHelper.createResult(items, page, pageSize, totalItems);

        // Then
        assertTrue(result.hasNext());
        assertFalse(result.hasPrevious());
    }

    @Test
    void testCreateResult_WithEmptyItems_CreatesResult() {
        // Given
        List<String> items = Collections.emptyList();
        int page = 1;
        int pageSize = 20;
        int totalItems = 0;

        // When
        PaginationHelper.PaginationResult<String> result = 
                PaginationHelper.createResult(items, page, pageSize, totalItems);

        // Then
        assertNotNull(result);
        assertTrue(result.getItems().isEmpty());
        assertFalse(result.hasNext());
        assertFalse(result.hasPrevious());
    }

    @Test
    void testPaginationResult_GetTotalPages_CalculatesCorrectly() {
        // Given
        List<String> items = Arrays.asList("item1", "item2");
        int page = 1;
        int pageSize = 20;
        int totalItems = 50;

        // When
        PaginationHelper.PaginationResult<String> result = 
                PaginationHelper.createResult(items, page, pageSize, totalItems);

        // Then
        assertEquals(3, result.getTotalPages());
    }

    @Test
    void testPaginationResult_GetTotalPages_WithExactDivision_CalculatesCorrectly() {
        // Given
        List<String> items = Arrays.asList("item1", "item2");
        int page = 1;
        int pageSize = 20;
        int totalItems = 40;

        // When
        PaginationHelper.PaginationResult<String> result = 
                PaginationHelper.createResult(items, page, pageSize, totalItems);

        // Then
        assertEquals(2, result.getTotalPages());
    }

    @Test
    void testPaginationResult_GetTotalPages_WithRemainder_RoundsUp() {
        // Given
        List<String> items = Arrays.asList("item1", "item2");
        int page = 1;
        int pageSize = 20;
        int totalItems = 45;

        // When
        PaginationHelper.PaginationResult<String> result = 
                PaginationHelper.createResult(items, page, pageSize, totalItems);

        // Then
        assertEquals(3, result.getTotalPages());
    }
}
