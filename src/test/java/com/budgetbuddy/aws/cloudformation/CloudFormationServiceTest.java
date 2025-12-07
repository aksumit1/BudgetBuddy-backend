package com.budgetbuddy.aws.cloudformation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for CloudFormation Service
 */
@ExtendWith(MockitoExtension.class)
class CloudFormationServiceTest {

    @Mock
    private CloudFormationClient cloudFormationClient;

    private CloudFormationService service;

    @BeforeEach
    void setUp() {
        service = new CloudFormationService(cloudFormationClient);
    }

    @Test
    void testGetStackStatus_WithValidStack_ReturnsStatus() {
        // Given
        Stack stack = Stack.builder()
                .stackStatus(StackStatus.CREATE_COMPLETE)
                .build();
        
        DescribeStacksResponse response = DescribeStacksResponse.builder()
                .stacks(List.of(stack))
                .build();
        
        when(cloudFormationClient.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(response);
        
        // When
        String status = service.getStackStatus("test-stack");
        
        // Then
        assertEquals("CREATE_COMPLETE", status);
        verify(cloudFormationClient).describeStacks(any(DescribeStacksRequest.class));
    }

    @Test
    void testGetStackStatus_WithEmptyStackName_ReturnsInvalid() {
        // When
        String status = service.getStackStatus("");
        
        // Then
        assertEquals("INVALID", status);
    }

    @Test
    void testGetStackStatus_WithNullStackName_ReturnsInvalid() {
        // When
        String status = service.getStackStatus(null);
        
        // Then
        assertEquals("INVALID", status);
    }

    @Test
    void testGetStackStatus_WithException_ReturnsError() {
        // Given
        when(cloudFormationClient.describeStacks(any(DescribeStacksRequest.class)))
                .thenThrow(new RuntimeException("Test exception"));
        
        // When
        String status = service.getStackStatus("test-stack");
        
        // Then
        assertEquals("ERROR", status);
    }

    @Test
    void testGetStackStatus_WithNotFound_ReturnsNotFound() {
        // Given
        DescribeStacksResponse response = DescribeStacksResponse.builder()
                .stacks(Collections.emptyList())
                .build();
        
        when(cloudFormationClient.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(response);
        
        // When
        String status = service.getStackStatus("test-stack");
        
        // Then
        assertEquals("NOT_FOUND", status);
    }

    @Test
    void testListStacks_WithValidResponse_ReturnsStacks() {
        // Given
        StackSummary summary = StackSummary.builder()
                .stackName("test-stack")
                .stackStatus(StackStatus.CREATE_COMPLETE)
                .build();
        
        ListStacksResponse response = ListStacksResponse.builder()
                .stackSummaries(List.of(summary))
                .build();
        
        when(cloudFormationClient.listStacks(any(ListStacksRequest.class)))
                .thenReturn(response);
        
        // When
        List<StackSummary> stacks = service.listStacks();
        
        // Then
        assertNotNull(stacks);
        assertEquals(1, stacks.size());
        assertEquals("test-stack", stacks.get(0).stackName());
        verify(cloudFormationClient).listStacks(any(ListStacksRequest.class));
    }

    @Test
    void testListStacks_WithException_ReturnsEmptyList() {
        // Given
        when(cloudFormationClient.listStacks(any(ListStacksRequest.class)))
                .thenThrow(new RuntimeException("Test exception"));
        
        // When
        List<StackSummary> stacks = service.listStacks();
        
        // Then
        assertNotNull(stacks);
        assertTrue(stacks.isEmpty());
    }

    @Test
    void testGetStackResources_WithValidResponse_ReturnsResources() {
        // Given
        StackResource resource = StackResource.builder()
                .resourceType("AWS::S3::Bucket")
                .build();
        
        DescribeStackResourcesResponse response = DescribeStackResourcesResponse.builder()
                .stackResources(List.of(resource))
                .build();
        
        when(cloudFormationClient.describeStackResources(any(DescribeStackResourcesRequest.class)))
                .thenReturn(response);
        
        // When
        List<StackResource> resources = service.getStackResources("test-stack");
        
        // Then
        assertNotNull(resources);
        assertEquals(1, resources.size());
        verify(cloudFormationClient).describeStackResources(any(DescribeStackResourcesRequest.class));
    }

    @Test
    void testGetStackResources_WithEmptyStackName_ReturnsEmptyList() {
        // When
        List<StackResource> resources = service.getStackResources("");
        
        // Then
        assertNotNull(resources);
        assertTrue(resources.isEmpty());
    }

    @Test
    void testGetStackEvents_WithValidResponse_ReturnsEvents() {
        // Given
        StackEvent event = StackEvent.builder()
                .eventId("event-123")
                .build();
        
        DescribeStackEventsResponse response = DescribeStackEventsResponse.builder()
                .stackEvents(List.of(event))
                .build();
        
        when(cloudFormationClient.describeStackEvents(any(DescribeStackEventsRequest.class)))
                .thenReturn(response);
        
        // When
        List<StackEvent> events = service.getStackEvents("test-stack");
        
        // Then
        assertNotNull(events);
        assertEquals(1, events.size());
        verify(cloudFormationClient).describeStackEvents(any(DescribeStackEventsRequest.class));
    }

    @Test
    void testGetStackEvents_WithEmptyStackName_ReturnsEmptyList() {
        // When
        List<StackEvent> events = service.getStackEvents("");
        
        // Then
        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    @Test
    void testConstructor_WithNullClient_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            new CloudFormationService(null);
        });
    }
}

