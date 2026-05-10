package com.budgetbuddy.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.api.SyncHealthController;
import com.budgetbuddy.service.SyncHealthService.SyncHealthResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for SyncHealthService Tests health calculation, error handling, and time formatting */
// Tests intentionally pass null to verify graceful handling /
// AppException paths; SpotBugs's NP_LOAD_OF_KNOWN_NULL_VALUE is expected.
@SuppressFBWarnings(
        value = "NP_LOAD_OF_KNOWN_NULL_VALUE",
        justification = "Tests deliberately exercise null-input paths")
class SyncHealthServiceTest {

    private SyncHealthService service;

    @BeforeEach
    void setUp() {
        service = new SyncHealthService();
    }

    @Test
    void testGetSyncHealthSuccess() {
        // Given
        final String userId = "user-123";
        final SyncHealthController.SyncStatus status =
                new SyncHealthController.SyncStatus("success", Instant.now(), 0, "healthy", null);

        // When
        final SyncHealthResponse response = service.getSyncHealth(userId, status);

        // Then
        assertNotNull(response);
        assertEquals("success", response.getStatus());
        assertEquals("healthy", response.getConnectionHealth());
        assertFalse(response.getIsStale());
        assertEquals(0, response.getConsecutiveFailures());
    }

    @Test
    void testGetSyncHealthStaleConnection() {
        // Given
        final String userId = "user-123";
        final SyncHealthController.SyncStatus status =
                new SyncHealthController.SyncStatus(
                        "stale",
                        Instant.now().minus(1, ChronoUnit.HOURS),
                        0,
                        "stale",
                        "login required");

        // When
        final SyncHealthResponse response = service.getSyncHealth(userId, status);

        // Then
        assertNotNull(response);
        assertTrue(response.getIsStale());
        assertEquals("stale", response.getConnectionHealth());
    }

    @Test
    void testGetSyncHealthUnhealthyAfterMultipleFailures() {
        // Given
        final String userId = "user-123";
        final SyncHealthController.SyncStatus status =
                new SyncHealthController.SyncStatus(
                        "failure",
                        Instant.now().minus(1, ChronoUnit.HOURS),
                        3,
                        "unhealthy",
                        "Network error");

        // When
        final SyncHealthResponse response = service.getSyncHealth(userId, status);

        // Then
        assertNotNull(response);
        assertEquals("unhealthy", response.getConnectionHealth());
        assertEquals(3, response.getConsecutiveFailures());
    }

    @Test
    void testGetSyncHealthDegradedAfterOneFailure() {
        // Given
        final String userId = "user-123";
        final SyncHealthController.SyncStatus status =
                new SyncHealthController.SyncStatus(
                        "failure",
                        Instant.now().minus(30, ChronoUnit.MINUTES),
                        1,
                        "degraded",
                        "Timeout");

        // When
        final SyncHealthResponse response = service.getSyncHealth(userId, status);

        // Then
        assertNotNull(response);
        assertEquals("degraded", response.getConnectionHealth());
        assertEquals(1, response.getConsecutiveFailures());
    }

    @Test
    void testGetSyncHealthOldSyncDate() {
        // Given
        final String userId = "user-123";
        final SyncHealthController.SyncStatus status =
                new SyncHealthController.SyncStatus(
                        "idle",
                        Instant.now().minus(25, ChronoUnit.HOURS), // 25 hours ago
                        0,
                        "healthy",
                        null);

        // When
        final SyncHealthResponse response = service.getSyncHealth(userId, status);

        // Then
        assertNotNull(response);
        // Should be degraded if last sync was > 24 hours ago
        assertEquals("degraded", response.getConnectionHealth());
    }

    @Test
    void testGetSyncHealthNullStatus() {
        // Given
        final String userId = "user-123";
        final SyncHealthController.SyncStatus nullStatus = null;

        // When
        final SyncHealthResponse response = service.getSyncHealth(userId, nullStatus);

        // Then
        assertNotNull(response);
        assertEquals("idle", response.getStatus());
        assertEquals("unknown", response.getConnectionHealth());
    }

    @Test
    void testGetSyncHealthStaleErrorDetection() {
        // Given
        final String userId = "user-123";
        final SyncHealthController.SyncStatus status =
                new SyncHealthController.SyncStatus(
                        "failure", Instant.now(), 1, "healthy", "login required" // Stale indicator
                );

        // When
        final SyncHealthResponse response = service.getSyncHealth(userId, status);

        // Then
        assertNotNull(response);
        assertTrue(response.getIsStale(), "Should detect stale connection from error message");
    }

    @Test
    void testGetSyncHealthTimeAgoFormatting() {
        // Given
        final String userId = "user-123";

        // Test "just now"
        final SyncHealthController.SyncStatus status1 =
                new SyncHealthController.SyncStatus(
                        "success", Instant.now().minus(30, ChronoUnit.SECONDS), 0, "healthy", null);

        // Test "minutes ago"
        final SyncHealthController.SyncStatus status2 =
                new SyncHealthController.SyncStatus(
                        "success", Instant.now().minus(5, ChronoUnit.MINUTES), 0, "healthy", null);

        // Test "hours ago"
        final SyncHealthController.SyncStatus status3 =
                new SyncHealthController.SyncStatus(
                        "success", Instant.now().minus(2, ChronoUnit.HOURS), 0, "healthy", null);

        // Test "days ago"
        final SyncHealthController.SyncStatus status4 =
                new SyncHealthController.SyncStatus(
                        "success", Instant.now().minus(3, ChronoUnit.DAYS), 0, "healthy", null);

        // When
        final SyncHealthResponse response1 = service.getSyncHealth(userId, status1);
        final SyncHealthResponse response2 = service.getSyncHealth(userId, status2);
        final SyncHealthResponse response3 = service.getSyncHealth(userId, status3);
        final SyncHealthResponse response4 = service.getSyncHealth(userId, status4);

        // Then
        assertTrue(
                response1.getTimeAgo().contains("just now")
                        || response1.getTimeAgo().contains("m ago"));
        assertTrue(response2.getTimeAgo().contains("m ago"));
        assertTrue(response3.getTimeAgo().contains("h ago"));
        assertTrue(response4.getTimeAgo().contains("d ago"));
    }

    @Test
    void testGetSyncHealthUserFriendlyErrorMessage() {
        // Given
        final String userId = "user-123";
        // Test with "timeout" in the error message (case-insensitive matching)
        final SyncHealthController.SyncStatus status =
                new SyncHealthController.SyncStatus(
                        "failure",
                        Instant.now(),
                        1,
                        "degraded",
                        "timeout" // Use just "timeout" to match the contains check
                );

        // When
        final SyncHealthResponse response = service.getSyncHealth(userId, status);

        // Then
        assertNotNull(response);
        assertNotNull(response.getMessage());
        // Message should contain user-friendly error message for timeout
        // getUserFriendlyErrorMessage should convert any error containing "timeout" to "Connection
        // timed out"
        final String message = response.getMessage();
        // The error containing "timeout" should be converted to "Connection timed out" by
        // getUserFriendlyErrorMessage
        assertEquals(
                "Connection timed out",
                message,
                "Message should be 'Connection timed out' for timeout error. Got: " + message);
    }

    @Test
    void testGetSyncHealthNullUserId() {
        // Given
        final String nullUserId = null;
        final SyncHealthController.SyncStatus status =
                new SyncHealthController.SyncStatus("idle", null, 0, "unknown", null);

        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    service.getSyncHealth(nullUserId, status);
                });
    }

    @Test
    void testGetSyncHealthEmptyUserId() {
        // Given
        final String emptyUserId = "";
        final SyncHealthController.SyncStatus status =
                new SyncHealthController.SyncStatus("idle", null, 0, "unknown", null);

        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    service.getSyncHealth(emptyUserId, status);
                });
    }
}
