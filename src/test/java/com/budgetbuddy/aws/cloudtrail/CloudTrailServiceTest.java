package com.budgetbuddy.aws.cloudtrail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.*;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for CloudTrail Service
 */
@ExtendWith(MockitoExtension.class)
class CloudTrailServiceTest {

    @Mock
    private CloudTrailClient cloudTrailClient;

    private CloudTrailService service;

    @BeforeEach
    void setUp() {
        service = new CloudTrailService(cloudTrailClient);
    }

    @Test
    void testLogApplicationActivity_LogsActivity() {
        // When
        service.logApplicationActivity("user-123", "CREATE", "/api/users", "SUCCESS");
        
        // Then - Should not throw exception
        assertDoesNotThrow(() -> {
            service.logApplicationActivity("user-123", "CREATE", "/api/users", "SUCCESS");
        });
    }

    @Test
    void testLookupEvents_WithValidResponse_ReturnsEvents() {
        // Given
        Event event = Event.builder()
                .eventId("event-123")
                .eventName("CreateUser")
                .build();
        
        LookupEventsResponse response = LookupEventsResponse.builder()
                .events(List.of(event))
                .build();
        
        when(cloudTrailClient.lookupEvents(any(LookupEventsRequest.class)))
                .thenReturn(response);
        
        // When
        List<Event> events = service.lookupEvents("user-123", 
                Instant.now().minusSeconds(3600), Instant.now());
        
        // Then
        assertNotNull(events);
        assertEquals(1, events.size());
        assertEquals("event-123", events.get(0).eventId());
        verify(cloudTrailClient).lookupEvents(any(LookupEventsRequest.class));
    }

    @Test
    void testLookupEvents_WithException_ReturnsEmptyList() {
        // Given
        when(cloudTrailClient.lookupEvents(any(LookupEventsRequest.class)))
                .thenThrow(new RuntimeException("Test exception"));
        
        // When
        List<Event> events = service.lookupEvents("user-123", 
                Instant.now().minusSeconds(3600), Instant.now());
        
        // Then
        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    @Test
    void testGetTrailStatus_WithValidResponse_ReturnsStatus() {
        // Given
        GetTrailStatusResponse response = GetTrailStatusResponse.builder()
                .isLogging(true)
                .build();
        
        when(cloudTrailClient.getTrailStatus(any(GetTrailStatusRequest.class)))
                .thenReturn(response);
        
        // When
        GetTrailStatusResponse status = service.getTrailStatus("test-trail");
        
        // Then
        assertNotNull(status);
        assertTrue(status.isLogging());
        verify(cloudTrailClient).getTrailStatus(any(GetTrailStatusRequest.class));
    }

    @Test
    void testGetTrailStatus_WithException_ReturnsNull() {
        // Given
        when(cloudTrailClient.getTrailStatus(any(GetTrailStatusRequest.class)))
                .thenThrow(new RuntimeException("Test exception"));
        
        // When
        GetTrailStatusResponse status = service.getTrailStatus("test-trail");
        
        // Then
        assertNull(status);
    }
}

