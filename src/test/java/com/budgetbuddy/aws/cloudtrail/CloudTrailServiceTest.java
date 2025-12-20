package com.budgetbuddy.aws.cloudtrail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for CloudTrailService
 */
class CloudTrailServiceTest {

    @Mock
    private CloudTrailClient cloudTrailClient;

    private CloudTrailService cloudTrailService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        cloudTrailService = new CloudTrailService(cloudTrailClient);
    }

    @Test
    @DisplayName("Should log application activity")
    void testLogApplicationActivity() {
        // Given
        String userId = "user-123";
        String action = "CREATE_TRANSACTION";
        String resource = "/api/transactions";
        String result = "SUCCESS";

        // When - Should not throw
        assertDoesNotThrow(() -> {
            cloudTrailService.logApplicationActivity(userId, action, resource, result);
        });
    }

    @Test
    @DisplayName("Should lookup events successfully")
    void testLookupEvents_Success() {
        // Given
        String userId = "user-123";
        Instant startTime = Instant.now().minusSeconds(3600);
        Instant endTime = Instant.now();

        Event event = Event.builder()
                .eventName("CreateTransaction")
                .eventTime(Instant.now())
                .build();

        LookupEventsResponse response = LookupEventsResponse.builder()
                .events(Arrays.asList(event))
                .build();

        when(cloudTrailClient.lookupEvents(any(LookupEventsRequest.class)))
                .thenReturn(response);

        // When
        List<Event> events = cloudTrailService.lookupEvents(userId, startTime, endTime);

        // Then
        assertNotNull(events);
        assertEquals(1, events.size());
        assertEquals("CreateTransaction", events.get(0).eventName());
    }

    @Test
    @DisplayName("Should return empty list on exception")
    void testLookupEvents_Exception() {
        // Given
        String userId = "user-123";
        Instant startTime = Instant.now().minusSeconds(3600);
        Instant endTime = Instant.now();

        when(cloudTrailClient.lookupEvents(any(LookupEventsRequest.class)))
                .thenThrow(new RuntimeException("AWS error"));

        // When
        List<Event> events = cloudTrailService.lookupEvents(userId, startTime, endTime);

        // Then
        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("Should get trail status successfully")
    void testGetTrailStatus_Success() {
        // Given
        String trailName = "test-trail";
        GetTrailStatusResponse response = GetTrailStatusResponse.builder()
                .isLogging(true)
                .build();

        when(cloudTrailClient.getTrailStatus(any(GetTrailStatusRequest.class)))
                .thenReturn(response);

        // When
        GetTrailStatusResponse result = cloudTrailService.getTrailStatus(trailName);

        // Then
        assertNotNull(result);
        assertTrue(result.isLogging());
    }

    @Test
    @DisplayName("Should return null on exception")
    void testGetTrailStatus_Exception() {
        // Given
        String trailName = "test-trail";
        when(cloudTrailClient.getTrailStatus(any(GetTrailStatusRequest.class)))
                .thenThrow(new RuntimeException("AWS error"));

        // When
        GetTrailStatusResponse result = cloudTrailService.getTrailStatus(trailName);

        // Then
        assertNull(result);
    }
}
