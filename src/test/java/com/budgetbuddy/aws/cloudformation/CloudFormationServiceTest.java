package com.budgetbuddy.aws.cloudformation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for CloudFormationService
 */
class CloudFormationServiceTest {

    @Mock
    private CloudFormationClient cloudFormationClient;

    private CloudFormationService cloudFormationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        cloudFormationService = new CloudFormationService(cloudFormationClient);
    }

    @Test
    @DisplayName("Should get stack status successfully")
    void testGetStackStatus_Success() {
        // Given
        String stackName = "test-stack";
        Stack stack = Stack.builder()
                .stackName(stackName)
                .stackStatus(StackStatus.CREATE_COMPLETE)
                .build();

        DescribeStacksResponse response = DescribeStacksResponse.builder()
                .stacks(Arrays.asList(stack))
                .build();

        when(cloudFormationClient.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(response);

        // When
        String status = cloudFormationService.getStackStatus(stackName);

        // Then
        assertEquals("CREATE_COMPLETE", status);
    }

    @Test
    @DisplayName("Should return NOT_FOUND for non-existent stack")
    void testGetStackStatus_NotFound() {
        // Given
        String stackName = "non-existent-stack";
        DescribeStacksResponse response = DescribeStacksResponse.builder()
                .stacks(Arrays.asList())
                .build();

        when(cloudFormationClient.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(response);

        // When
        String status = cloudFormationService.getStackStatus(stackName);

        // Then
        assertEquals("NOT_FOUND", status);
    }

    @Test
    @DisplayName("Should return INVALID for null stack name")
    void testGetStackStatus_NullStackName() {
        // When
        String status = cloudFormationService.getStackStatus(null);

        // Then
        assertEquals("INVALID", status);
    }

    @Test
    @DisplayName("Should return ERROR on exception")
    void testGetStackStatus_Exception() {
        // Given
        String stackName = "test-stack";
        when(cloudFormationClient.describeStacks(any(DescribeStacksRequest.class)))
                .thenThrow(new RuntimeException("AWS error"));

        // When
        String status = cloudFormationService.getStackStatus(stackName);

        // Then
        assertEquals("ERROR", status);
    }

    @Test
    @DisplayName("Should list stacks successfully")
    void testListStacks_Success() {
        // Given
        StackSummary summary = StackSummary.builder()
                .stackName("test-stack")
                .stackStatus(StackStatus.CREATE_COMPLETE)
                .build();

        ListStacksResponse response = ListStacksResponse.builder()
                .stackSummaries(Arrays.asList(summary))
                .build();

        when(cloudFormationClient.listStacks(any(ListStacksRequest.class)))
                .thenReturn(response);

        // When
        List<StackSummary> stacks = cloudFormationService.listStacks();

        // Then
        assertNotNull(stacks);
        assertEquals(1, stacks.size());
        assertEquals("test-stack", stacks.get(0).stackName());
    }

    @Test
    @DisplayName("Should return empty list on exception")
    void testListStacks_Exception() {
        // Given
        when(cloudFormationClient.listStacks(any(ListStacksRequest.class)))
                .thenThrow(new RuntimeException("AWS error"));

        // When
        List<StackSummary> stacks = cloudFormationService.listStacks();

        // Then
        assertNotNull(stacks);
        assertTrue(stacks.isEmpty());
    }

    @Test
    @DisplayName("Should throw exception for null client")
    void testConstructor_NullClient() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            new CloudFormationService(null);
        });
    }
}
