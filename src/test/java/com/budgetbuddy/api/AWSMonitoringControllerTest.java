package com.budgetbuddy.api;

import com.budgetbuddy.aws.cloudformation.CloudFormationService;
import com.budgetbuddy.aws.cloudtrail.CloudTrailService;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.service.aws.CloudWatchService;
import com.budgetbuddy.aws.codepipeline.CodePipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for AWSMonitoringController
 */
@ExtendWith(MockitoExtension.class)
class AWSMonitoringControllerTest {

    @Mock
    private CloudWatchService cloudWatchService;

    @Mock
    private CloudTrailService cloudTrailService;

    @Mock
    private CloudFormationService cloudFormationService;

    @Mock
    private CodePipelineService codePipelineService;

    @Mock
    private UserService userService;

    @Mock
    private UserDetails userDetails;

    @InjectMocks
    private AWSMonitoringController controller;

    private UserTable testUser;
    private UserTable adminUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");
        testUser.setRoles(java.util.Set.of("USER"));

        adminUser = new UserTable();
        adminUser.setUserId("admin-123");
        adminUser.setEmail("admin@example.com");
        adminUser.setRoles(java.util.Set.of("ADMIN", "MONITORING"));

        when(userDetails.getUsername()).thenReturn("test@example.com");
    }

    @Test
    void testGetCloudWatchMetrics_WithAdminAccess_ReturnsMetrics() {
        // Given
        Instant startTime = Instant.now().minusSeconds(3600);
        Instant endTime = Instant.now();
        software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse statistics = 
                software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse.builder()
                        .label("CPUUtilization")
                        .datapoints(java.util.Collections.emptyList())
                        .build();

        when(userService.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(userDetails.getUsername()).thenReturn("admin@example.com");
        when(cloudWatchService.getMetricStatistics("CPUUtilization", startTime, endTime))
                .thenReturn(statistics);

        // When
        ResponseEntity<Map<String, Object>> response = 
                controller.getCloudWatchMetrics(userDetails, "CPUUtilization", startTime, endTime);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("statistics"));
    }

    @Test
    void testGetCloudWatchMetrics_WithUserAccess_ReturnsForbidden() {
        // Given
        Instant startTime = Instant.now().minusSeconds(3600);
        Instant endTime = Instant.now();
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // When
        ResponseEntity<Map<String, Object>> response = 
                controller.getCloudWatchMetrics(userDetails, "CPUUtilization", startTime, endTime);

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void testGetCloudTrailEvents_WithAdminAccess_ReturnsEvents() {
        // Given
        Instant startTime = Instant.now().minusSeconds(86400);
        Instant endTime = Instant.now();
        List<software.amazon.awssdk.services.cloudtrail.model.Event> events = Arrays.asList();

        when(userService.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(userDetails.getUsername()).thenReturn("admin@example.com");
        when(cloudTrailService.lookupEvents("admin-123", startTime, endTime)).thenReturn(events);

        // When
        ResponseEntity<List<software.amazon.awssdk.services.cloudtrail.model.Event>> response = 
                controller.getCloudTrailEvents(userDetails, null, startTime, endTime);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetCloudTrailEvents_WithUserId_ReturnsEventsForUser() {
        // Given
        Instant startTime = Instant.now().minusSeconds(86400);
        Instant endTime = Instant.now();
        List<software.amazon.awssdk.services.cloudtrail.model.Event> events = Arrays.asList();

        when(userService.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(userDetails.getUsername()).thenReturn("admin@example.com");
        when(cloudTrailService.lookupEvents("user-456", startTime, endTime)).thenReturn(events);

        // When
        ResponseEntity<List<software.amazon.awssdk.services.cloudtrail.model.Event>> response = 
                controller.getCloudTrailEvents(userDetails, "user-456", startTime, endTime);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(cloudTrailService).lookupEvents("user-456", startTime, endTime);
    }

    @Test
    void testGetCloudFormationStacks_WithAdminAccess_ReturnsStacks() {
        // Given
        List<software.amazon.awssdk.services.cloudformation.model.StackSummary> stacks = Arrays.asList();

        when(userService.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(userDetails.getUsername()).thenReturn("admin@example.com");
        when(cloudFormationService.listStacks()).thenReturn(stacks);

        // When
        ResponseEntity<List<software.amazon.awssdk.services.cloudformation.model.StackSummary>> response = 
                controller.getCloudFormationStacks(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetCodePipelineStatus_WithAdminAccess_ReturnsStatus() {
        // Given
        String pipelineName = "budgetbuddy-pipeline";
        String status = "SUCCEEDED";

        when(userService.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(userDetails.getUsername()).thenReturn("admin@example.com");
        when(codePipelineService.getPipelineStatus(pipelineName)).thenReturn(status);

        // When
        ResponseEntity<Map<String, String>> response = 
                controller.getCodePipelineStatus(userDetails, pipelineName);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(pipelineName, response.getBody().get("pipelineName"));
        assertEquals(status, response.getBody().get("status"));
    }

    @Test
    void testGetCodePipelineStatus_WithUserAccess_ReturnsForbidden() {
        // Given
        String pipelineName = "budgetbuddy-pipeline";
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // When
        ResponseEntity<Map<String, String>> response = 
                controller.getCodePipelineStatus(userDetails, pipelineName);

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }
}

