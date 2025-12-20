package com.budgetbuddy.api;

import com.budgetbuddy.config.DnsCacheConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for SystemManagementController
 */
class SystemManagementControllerTest {

    @Mock
    private DnsCacheConfig dnsCacheConfig;

    private SystemManagementController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new SystemManagementController(dnsCacheConfig);
    }

    @Test
    @DisplayName("Should clear DNS cache successfully")
    void testClearDnsCache_Success() {
        // Given
        doNothing().when(dnsCacheConfig).clearDnsCache();

        // When
        ResponseEntity<Map<String, String>> response = controller.clearDnsCache();

        // Then
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertEquals("success", response.getBody().get("status"));
        assertEquals("DNS cache cleared successfully", response.getBody().get("message"));
        verify(dnsCacheConfig).clearDnsCache();
    }

    @Test
    @DisplayName("Should handle exception when clearing DNS cache fails")
    void testClearDnsCache_Exception() {
        // Given
        doThrow(new RuntimeException("Cache clear failed")).when(dnsCacheConfig).clearDnsCache();

        // When
        ResponseEntity<Map<String, String>> response = controller.clearDnsCache();

        // Then
        assertEquals(500, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertEquals("error", response.getBody().get("status"));
        assertTrue(response.getBody().get("message").contains("Failed to clear DNS cache"));
    }

    @Test
    @DisplayName("Should return health status")
    void testHealth() {
        // When
        ResponseEntity<Map<String, String>> response = controller.health();

        // Then
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertEquals("healthy", response.getBody().get("status"));
        assertEquals("system-management", response.getBody().get("service"));
    }
}

