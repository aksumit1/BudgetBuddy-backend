package com.budgetbuddy.service;

import com.budgetbuddy.api.SyncHealthController;
import com.budgetbuddy.service.SyncHealthService.SyncHealthResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SyncHealthService
 * Tests health calculation, error handling, and time formatting
 */
class SyncHealthServiceTest {

    private SyncHealthService service;

    @BeforeEach
    void setUp() {
        service = new SyncHealthService();
    }

    @Test
    void testGetSyncHealth_Success() {
        // Given
        String userId = "user-123";
        SyncHealthController.SyncStatus status = new SyncHealthController.SyncStatus(
                "success",
                Instant.now(),
                0,
                "healthy",
                null
        );

        // When
        SyncHealthResponse response = service.getSyncHealth(userId, status);

        // Then
        assertNotNull(response);
        assertEquals("success", response.getStatus());
        assertEquals("healthy", response.getConnectionHealth());
        assertFalse(response.getIsStale());
        assertEquals(0, response.getConsecutiveFailures());
    }

    @Test
    void testGetSyncHealth_StaleConnection() {
        // Given
        String userId = "user-123";
        SyncHealthController.SyncStatus status = new SyncHealthController.SyncStatus(
                "stale",
                Instant.now().minus(1, ChronoUnit.HOURS),
                0,
                "stale",
                "login required"
        );

        // When
        SyncHealthResponse response = service.getSyncHealth(userId, status);

        // Then
        assertNotNull(response);
        assertTrue(response.getIsStale());
        assertEquals("stale", response.getConnectionHealth());
    }

    @Test
    void testGetSyncHealth_UnhealthyAfterMultipleFailures() {
        // Given
        String userId = "user-123";
        SyncHealthController.SyncStatus status = new SyncHealthController.SyncStatus(
                "failure",
                Instant.now().minus(1, ChronoUnit.HOURS),
                3,
                "unhealthy",
                "Network error"
        );

        // When
        SyncHealthResponse response = service.getSyncHealth(userId, status);

        // Then
        assertNotNull(response);
        assertEquals("unhealthy", response.getConnectionHealth());
        assertEquals(3, response.getConsecutiveFailures());
    }

    @Test
    void testGetSyncHealth_DegradedAfterOneFailure() {
        // Given
        String userId = "user-123";
        SyncHealthController.SyncStatus status = new SyncHealthController.SyncStatus(
                "failure",
                Instant.now().minus(30, ChronoUnit.MINUTES),
                1,
                "degraded",
                "Timeout"
        );

        // When
        SyncHealthResponse response = service.getSyncHealth(userId, status);

        // Then
        assertNotNull(response);
        assertEquals("degraded", response.getConnectionHealth());
        assertEquals(1, response.getConsecutiveFailures());
    }

    @Test
    void testGetSyncHealth_OldSyncDate() {
        // Given
        String userId = "user-123";
        SyncHealthController.SyncStatus status = new SyncHealthController.SyncStatus(
                "idle",
                Instant.now().minus(25, ChronoUnit.HOURS), // 25 hours ago
                0,
                "healthy",
                null
        );

        // When
        SyncHealthResponse response = service.getSyncHealth(userId, status);

        // Then
        assertNotNull(response);
        // Should be degraded if last sync was > 24 hours ago
        assertEquals("degraded", response.getConnectionHealth());
    }

    @Test
    void testGetSyncHealth_NullStatus() {
        // Given
        String userId = "user-123";
        SyncHealthController.SyncStatus nullStatus = null;

        // When
        SyncHealthResponse response = service.getSyncHealth(userId, nullStatus);

        // Then
        assertNotNull(response);
        assertEquals("idle", response.getStatus());
        assertEquals("unknown", response.getConnectionHealth());
    }

    @Test
    void testGetSyncHealth_StaleErrorDetection() {
        // Given
        String userId = "user-123";
        SyncHealthController.SyncStatus status = new SyncHealthController.SyncStatus(
                "failure",
                Instant.now(),
                1,
                "healthy",
                "login required" // Stale indicator
        );

        // When
        SyncHealthResponse response = service.getSyncHealth(userId, status);

        // Then
        assertNotNull(response);
        assertTrue(response.getIsStale(), "Should detect stale connection from error message");
    }

    @Test
    void testGetSyncHealth_TimeAgoFormatting() {
        // Given
        String userId = "user-123";
        
        // Test "just now"
        SyncHealthController.SyncStatus status1 = new SyncHealthController.SyncStatus(
                "success",
                Instant.now().minus(30, ChronoUnit.SECONDS),
                0,
                "healthy",
                null
        );
        
        // Test "minutes ago"
        SyncHealthController.SyncStatus status2 = new SyncHealthController.SyncStatus(
                "success",
                Instant.now().minus(5, ChronoUnit.MINUTES),
                0,
                "healthy",
                null
        );
        
        // Test "hours ago"
        SyncHealthController.SyncStatus status3 = new SyncHealthController.SyncStatus(
                "success",
                Instant.now().minus(2, ChronoUnit.HOURS),
                0,
                "healthy",
                null
        );
        
        // Test "days ago"
        SyncHealthController.SyncStatus status4 = new SyncHealthController.SyncStatus(
                "success",
                Instant.now().minus(3, ChronoUnit.DAYS),
                0,
                "healthy",
                null
        );

        // When
        SyncHealthResponse response1 = service.getSyncHealth(userId, status1);
        SyncHealthResponse response2 = service.getSyncHealth(userId, status2);
        SyncHealthResponse response3 = service.getSyncHealth(userId, status3);
        SyncHealthResponse response4 = service.getSyncHealth(userId, status4);

        // Then
        assertTrue(response1.getTimeAgo().contains("just now") || 
                  response1.getTimeAgo().contains("m ago"));
        assertTrue(response2.getTimeAgo().contains("m ago"));
        assertTrue(response3.getTimeAgo().contains("h ago"));
        assertTrue(response4.getTimeAgo().contains("d ago"));
    }

    @Test
    void testGetSyncHealth_UserFriendlyErrorMessage() {
        // Given
        String userId = "user-123";
        // Test with "timeout" in the error message (case-insensitive matching)
        SyncHealthController.SyncStatus status = new SyncHealthController.SyncStatus(
                "failure",
                Instant.now(),
                1,
                "degraded",
                "timeout"  // Use just "timeout" to match the contains check
        );

        // When
        SyncHealthResponse response = service.getSyncHealth(userId, status);

        // Then
        assertNotNull(response);
        assertNotNull(response.getMessage());
        // Message should contain user-friendly error message for timeout
        // getUserFriendlyErrorMessage should convert any error containing "timeout" to "Connection timed out"
        String message = response.getMessage();
        // The error containing "timeout" should be converted to "Connection timed out" by getUserFriendlyErrorMessage
        assertEquals("Connection timed out", message, 
                "Message should be 'Connection timed out' for timeout error. Got: " + message);
    }

    @Test
    void testGetSyncHealth_NullUserId() {
        // Given
        String nullUserId = null;
        SyncHealthController.SyncStatus status = new SyncHealthController.SyncStatus(
                "idle", null, 0, "unknown", null
        );

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            service.getSyncHealth(nullUserId, status);
        });
    }

    @Test
    void testGetSyncHealth_EmptyUserId() {
        // Given
        String emptyUserId = "";
        SyncHealthController.SyncStatus status = new SyncHealthController.SyncStatus(
                "idle", null, 0, "unknown", null
        );

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            service.getSyncHealth(emptyUserId, status);
        });
    }
}

