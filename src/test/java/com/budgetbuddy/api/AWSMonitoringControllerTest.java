package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.aws.cloudformation.CloudFormationService;
import com.budgetbuddy.aws.cloudtrail.CloudTrailService;
import com.budgetbuddy.aws.codepipeline.CodePipelineService;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.service.aws.CloudWatchService;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

/** Unit Tests for AWSMonitoringController */
@ExtendWith(MockitoExtension.class)
class AWSMonitoringControllerTest {

    @Mock private CloudWatchService cloudWatchService;

    @Mock private CloudTrailService cloudTrailService;

    @Mock private CloudFormationService cloudFormationService;

    @Mock private CodePipelineService codePipelineService;

    @Mock private UserService userService;

    @Mock private UserDetails userDetails;

    @InjectMocks private AWSMonitoringController controller;

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
    void testGetCloudWatchMetricsWithAdminAccessReturnsMetrics() {
        // Given
        final Instant startTime = Instant.now().minusSeconds(3600);
        final Instant endTime = Instant.now();
        final software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse
                statistics =
                        software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse
                                .builder()
                                .label("CPUUtilization")
                                .datapoints(java.util.Collections.emptyList())
                                .build();

        when(userService.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(userDetails.getUsername()).thenReturn("admin@example.com");
        when(cloudWatchService.getMetricStatistics("CPUUtilization", startTime, endTime))
                .thenReturn(statistics);

        // When
        final ResponseEntity<Map<String, Object>> response =
                controller.getCloudWatchMetrics(userDetails, "CPUUtilization", startTime, endTime);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("statistics"));
    }

    @Test
    void testGetCloudWatchMetricsWithUserAccessReturnsForbidden() {
        // Given
        final Instant startTime = Instant.now().minusSeconds(3600);
        final Instant endTime = Instant.now();
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // When
        final ResponseEntity<Map<String, Object>> response =
                controller.getCloudWatchMetrics(userDetails, "CPUUtilization", startTime, endTime);

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void testGetCloudTrailEventsWithAdminAccessReturnsEvents() {
        // Given
        final Instant startTime = Instant.now().minusSeconds(86_400);
        final Instant endTime = Instant.now();
        final List<software.amazon.awssdk.services.cloudtrail.model.Event> events = Arrays.asList();

        when(userService.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(userDetails.getUsername()).thenReturn("admin@example.com");
        when(cloudTrailService.lookupEvents("admin-123", startTime, endTime)).thenReturn(events);

        // When
        final ResponseEntity<List<software.amazon.awssdk.services.cloudtrail.model.Event>>
                response = controller.getCloudTrailEvents(userDetails, null, startTime, endTime);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetCloudTrailEventsWithUserIdReturnsEventsForUser() {
        // Given
        final Instant startTime = Instant.now().minusSeconds(86_400);
        final Instant endTime = Instant.now();
        final List<software.amazon.awssdk.services.cloudtrail.model.Event> events = Arrays.asList();

        when(userService.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(userDetails.getUsername()).thenReturn("admin@example.com");
        when(cloudTrailService.lookupEvents("user-456", startTime, endTime)).thenReturn(events);

        // When
        final ResponseEntity<List<software.amazon.awssdk.services.cloudtrail.model.Event>>
                response =
                        controller.getCloudTrailEvents(userDetails, "user-456", startTime, endTime);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(cloudTrailService).lookupEvents("user-456", startTime, endTime);
    }

    @Test
    void testGetCloudFormationStacksWithAdminAccessReturnsStacks() {
        // Given
        final List<software.amazon.awssdk.services.cloudformation.model.StackSummary> stacks =
                Arrays.asList();

        when(userService.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(userDetails.getUsername()).thenReturn("admin@example.com");
        when(cloudFormationService.listStacks()).thenReturn(stacks);

        // When
        final ResponseEntity<
                        List<software.amazon.awssdk.services.cloudformation.model.StackSummary>>
                response = controller.getCloudFormationStacks(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetCodePipelineStatusWithAdminAccessReturnsStatus() {
        // Given
        final String pipelineName = "budgetbuddy-pipeline";
        final String status = "SUCCEEDED";

        when(userService.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(userDetails.getUsername()).thenReturn("admin@example.com");
        when(codePipelineService.getPipelineStatus(pipelineName)).thenReturn(status);

        // When
        final ResponseEntity<Map<String, String>> response =
                controller.getCodePipelineStatus(userDetails, pipelineName);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(pipelineName, response.getBody().get("pipelineName"));
        assertEquals(status, response.getBody().get("status"));
    }

    @Test
    void testGetCodePipelineStatusWithUserAccessReturnsForbidden() {
        // Given
        final String pipelineName = "budgetbuddy-pipeline";
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // When
        final ResponseEntity<Map<String, String>> response =
                controller.getCodePipelineStatus(userDetails, pipelineName);

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }
}
