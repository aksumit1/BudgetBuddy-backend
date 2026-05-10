package com.budgetbuddy.aws.cloudtrail;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.Event;
import software.amazon.awssdk.services.cloudtrail.model.GetTrailStatusRequest;
import software.amazon.awssdk.services.cloudtrail.model.GetTrailStatusResponse;
import software.amazon.awssdk.services.cloudtrail.model.LookupEventsRequest;
import software.amazon.awssdk.services.cloudtrail.model.LookupEventsResponse;

/** Comprehensive tests for CloudTrailService */
class CloudTrailServiceTest {

    @Mock private CloudTrailClient cloudTrailClient;

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
        final String userId = "user-123";
        final String action = "CREATE_TRANSACTION";
        final String resource = "/api/transactions";
        final String result = "SUCCESS";

        // When - Should not throw
        assertDoesNotThrow(
                () -> {
                    cloudTrailService.logApplicationActivity(userId, action, resource, result);
                });
    }

    @Test
    @DisplayName("Should lookup events successfully")
    void testLookupEventsSuccess() {
        // Given
        final String userId = "user-123";
        final Instant startTime = Instant.now().minusSeconds(3600);
        final Instant endTime = Instant.now();

        final Event event =
                Event.builder().eventName("CreateTransaction").eventTime(Instant.now()).build();

        final LookupEventsResponse response =
                LookupEventsResponse.builder().events(Arrays.asList(event)).build();

        when(cloudTrailClient.lookupEvents(any(LookupEventsRequest.class))).thenReturn(response);

        // When
        final List<Event> events = cloudTrailService.lookupEvents(userId, startTime, endTime);

        // Then
        assertNotNull(events);
        assertEquals(1, events.size());
        assertEquals("CreateTransaction", events.get(0).eventName());
    }

    @Test
    @DisplayName("Should return empty list on exception")
    void testLookupEventsException() {
        // Given
        final String userId = "user-123";
        final Instant startTime = Instant.now().minusSeconds(3600);
        final Instant endTime = Instant.now();

        when(cloudTrailClient.lookupEvents(any(LookupEventsRequest.class)))
                .thenThrow(new RuntimeException("AWS error"));

        // When
        final List<Event> events = cloudTrailService.lookupEvents(userId, startTime, endTime);

        // Then
        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("Should get trail status successfully")
    void testGetTrailStatusSuccess() {
        // Given
        final String trailName = "test-trail";
        final GetTrailStatusResponse response = GetTrailStatusResponse.builder().isLogging(true).build();

        when(cloudTrailClient.getTrailStatus(any(GetTrailStatusRequest.class)))
                .thenReturn(response);

        // When
        final GetTrailStatusResponse result = cloudTrailService.getTrailStatus(trailName);

        // Then
        assertNotNull(result);
        assertTrue(result.isLogging());
    }

    @Test
    @DisplayName("Should return null on exception")
    void testGetTrailStatusException() {
        // Given
        final String trailName = "test-trail";
        when(cloudTrailClient.getTrailStatus(any(GetTrailStatusRequest.class)))
                .thenThrow(new RuntimeException("AWS error"));

        // When
        final GetTrailStatusResponse result = cloudTrailService.getTrailStatus(trailName);

        // Then
        assertNull(result);
    }
}
