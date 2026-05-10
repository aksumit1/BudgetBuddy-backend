package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.budgetbuddy.config.DnsCacheConfig;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;

/** Comprehensive tests for SystemManagementController */
class SystemManagementControllerTest {

    @Mock private DnsCacheConfig dnsCacheConfig;

    private SystemManagementController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new SystemManagementController(dnsCacheConfig);
    }

    @Test
    @DisplayName("Should clear DNS cache successfully")
    void testClearDnsCacheSuccess() {
        // Given
        doNothing().when(dnsCacheConfig).clearDnsCache();

        // When
        final ResponseEntity<Map<String, String>> response = controller.clearDnsCache();

        // Then
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("success", response.getBody().get("status"));
        assertEquals("DNS cache cleared successfully", response.getBody().get("message"));
        verify(dnsCacheConfig).clearDnsCache();
    }

    @Test
    @DisplayName("Should handle exception when clearing DNS cache fails")
    void testClearDnsCacheException() {
        // Given
        doThrow(new RuntimeException("Cache clear failed")).when(dnsCacheConfig).clearDnsCache();

        // When
        final ResponseEntity<Map<String, String>> response = controller.clearDnsCache();

        // Then
        assertEquals(500, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("error", response.getBody().get("status"));
        assertTrue(response.getBody().get("message").contains("Failed to clear DNS cache"));
    }

    @Test
    @DisplayName("Should return health status")
    void testHealth() {
        // When
        final ResponseEntity<Map<String, String>> response = controller.health();

        // Then
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("healthy", response.getBody().get("status"));
        assertEquals("system-management", response.getBody().get("service"));
    }
}
