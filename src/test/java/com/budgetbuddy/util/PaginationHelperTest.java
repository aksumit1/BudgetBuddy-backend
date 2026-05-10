package com.budgetbuddy.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit Tests for PaginationHelper */
class PaginationHelperTest {

    private static final String ITEM2 = "item2";
    private static final String ITEM1 = "item1";

    @BeforeEach
    void setUp() {
        // Reset static fields to defaults
        ReflectionTestUtils.setField(PaginationHelper.class, "defaultPageSize", 50);
        ReflectionTestUtils.setField(PaginationHelper.class, "maxPageSize", 1000);
    }

    @Test
    void testGetDefaultPageSizeReturnsConfiguredValue() {
        // When
        final int defaultSize = PaginationHelper.getDefaultPageSize();

        // Then
        assertEquals(50, defaultSize);
    }

    @Test
    void testGetMaxPageSizeReturnsConfiguredValue() {
        // When
        final int maxSize = PaginationHelper.getMaxPageSize();

        // Then
        assertEquals(1000, maxSize);
    }

    @Test
    void testCalculateSkipWithValidPageReturnsCorrectSkip() {
        // When/Then
        assertEquals(0, PaginationHelper.calculateSkip(1, 20));
        assertEquals(20, PaginationHelper.calculateSkip(2, 20));
        assertEquals(40, PaginationHelper.calculateSkip(3, 20));
    }

    @Test
    void testCalculateSkipWithPageLessThanOneReturnsZero() {
        // When/Then
        assertEquals(0, PaginationHelper.calculateSkip(0, 20));
        assertEquals(0, PaginationHelper.calculateSkip(-1, 20));
    }

    @Test
    void testNormalizePageSizeWithValidSizeReturnsSameSize() {
        // When
        final int normalized = PaginationHelper.normalizePageSize(25, null, null);

        // Then
        assertEquals(25, normalized);
    }

    @Test
    void testNormalizePageSizeWithZeroReturnsDefault() {
        // When
        final int normalized = PaginationHelper.normalizePageSize(0, null, null);

        // Then
        assertEquals(50, normalized);
    }

    @Test
    void testNormalizePageSizeWithNegativeReturnsDefault() {
        // When
        final int normalized = PaginationHelper.normalizePageSize(-5, null, null);

        // Then
        assertEquals(50, normalized);
    }

    @Test
    void testNormalizePageSizeWithSizeExceedingMaxReturnsMax() {
        // When
        final int normalized = PaginationHelper.normalizePageSize(2000, null, null);

        // Then
        assertEquals(1000, normalized);
    }

    @Test
    void testNormalizePageSizeWithCustomDefaultsUsesCustomValues() {
        // When
        final int normalized = PaginationHelper.normalizePageSize(0, 100, 500);

        // Then
        assertEquals(100, normalized);
    }

    @Test
    void testNormalizePageSizeWithCustomMaxUsesCustomMax() {
        // When
        final int normalized = PaginationHelper.normalizePageSize(1000, null, 500);

        // Then
        assertEquals(500, normalized);
    }

    @Test
    void testNormalizePageSizeWithoutParametersUsesDefaults() {
        // When
        final int normalized = PaginationHelper.normalizePageSize(25);

        // Then
        assertEquals(25, normalized);
    }

    @Test
    void testCreateResultWithValidInputsCreatesCorrectResult() {
        // Given
        final List<String> items = Arrays.asList(ITEM1, ITEM2, "item3");
        final int page = 1;
        final int pageSize = 20;
        final int totalItems = 50;

        // When
        final PaginationHelper.PaginationResult<String> result =
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
    void testCreateResultWithLastPageHasNoNext() {
        // Given
        final List<String> items = Arrays.asList(ITEM1, ITEM2);
        final int page = 3;
        final int pageSize = 20;
        final int totalItems = 50;

        // When
        final PaginationHelper.PaginationResult<String> result =
                PaginationHelper.createResult(items, page, pageSize, totalItems);

        // Then
        assertFalse(result.hasNext());
        assertTrue(result.hasPrevious());
    }

    @Test
    void testCreateResultWithFirstPageHasNoPrevious() {
        // Given
        final List<String> items = Arrays.asList(ITEM1, ITEM2);
        final int page = 1;
        final int pageSize = 20;
        final int totalItems = 50;

        // When
        final PaginationHelper.PaginationResult<String> result =
                PaginationHelper.createResult(items, page, pageSize, totalItems);

        // Then
        assertTrue(result.hasNext());
        assertFalse(result.hasPrevious());
    }

    @Test
    void testCreateResultWithEmptyItemsCreatesResult() {
        // Given
        final List<String> items = Collections.emptyList();
        final int page = 1;
        final int pageSize = 20;
        final int totalItems = 0;

        // When
        final PaginationHelper.PaginationResult<String> result =
                PaginationHelper.createResult(items, page, pageSize, totalItems);

        // Then
        assertNotNull(result);
        assertTrue(result.getItems().isEmpty());
        assertFalse(result.hasNext());
        assertFalse(result.hasPrevious());
    }

    @Test
    void testPaginationResultGetTotalPagesCalculatesCorrectly() {
        // Given
        final List<String> items = Arrays.asList(ITEM1, ITEM2);
        final int page = 1;
        final int pageSize = 20;
        final int totalItems = 50;

        // When
        final PaginationHelper.PaginationResult<String> result =
                PaginationHelper.createResult(items, page, pageSize, totalItems);

        // Then
        assertEquals(3, result.getTotalPages());
    }

    @Test
    void testPaginationResultGetTotalPagesWithExactDivisionCalculatesCorrectly() {
        // Given
        final List<String> items = Arrays.asList(ITEM1, ITEM2);
        final int page = 1;
        final int pageSize = 20;
        final int totalItems = 40;

        // When
        final PaginationHelper.PaginationResult<String> result =
                PaginationHelper.createResult(items, page, pageSize, totalItems);

        // Then
        assertEquals(2, result.getTotalPages());
    }

    @Test
    void testPaginationResultGetTotalPagesWithRemainderRoundsUp() {
        // Given
        final List<String> items = Arrays.asList(ITEM1, ITEM2);
        final int page = 1;
        final int pageSize = 20;
        final int totalItems = 45;

        // When
        final PaginationHelper.PaginationResult<String> result =
                PaginationHelper.createResult(items, page, pageSize, totalItems);

        // Then
        assertEquals(3, result.getTotalPages());
    }
}
