package com.budgetbuddy.aws.cloudformation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksResponse;
import software.amazon.awssdk.services.cloudformation.model.ListStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.ListStacksResponse;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;
import software.amazon.awssdk.services.cloudformation.model.StackSummary;

/** Comprehensive tests for CloudFormationService */
class CloudFormationServiceTest {

    @Mock private CloudFormationClient cloudFormationClient;

    private CloudFormationService cloudFormationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        cloudFormationService = new CloudFormationService(cloudFormationClient);
    }

    @Test
    @DisplayName("Should get stack status successfully")
    void testGetStackStatusSuccess() {
        // Given
        final String stackName = "test-stack";
        final Stack stack =
                Stack.builder()
                        .stackName(stackName)
                        .stackStatus(StackStatus.CREATE_COMPLETE)
                        .build();

        final DescribeStacksResponse response =
                DescribeStacksResponse.builder().stacks(Arrays.asList(stack)).build();

        when(cloudFormationClient.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(response);

        // When
        final String status = cloudFormationService.getStackStatus(stackName);

        // Then
        assertEquals("CREATE_COMPLETE", status);
    }

    @Test
    @DisplayName("Should return NOT_FOUND for non-existent stack")
    void testGetStackStatusNotFound() {
        // Given
        final String stackName = "non-existent-stack";
        final DescribeStacksResponse response =
                DescribeStacksResponse.builder().stacks(Arrays.asList()).build();

        when(cloudFormationClient.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(response);

        // When
        final String status = cloudFormationService.getStackStatus(stackName);

        // Then
        assertEquals("NOT_FOUND", status);
    }

    @Test
    @DisplayName("Should return INVALID for null stack name")
    void testGetStackStatusNullStackName() {
        // When
        final String status = cloudFormationService.getStackStatus(null);

        // Then
        assertEquals("INVALID", status);
    }

    @Test
    @DisplayName("Should return ERROR on exception")
    void testGetStackStatusException() {
        // Given
        final String stackName = "test-stack";
        when(cloudFormationClient.describeStacks(any(DescribeStacksRequest.class)))
                .thenThrow(new RuntimeException("AWS error"));

        // When
        final String status = cloudFormationService.getStackStatus(stackName);

        // Then
        assertEquals("ERROR", status);
    }

    @Test
    @DisplayName("Should list stacks successfully")
    void testListStacksSuccess() {
        // Given
        final StackSummary summary =
                StackSummary.builder()
                        .stackName("test-stack")
                        .stackStatus(StackStatus.CREATE_COMPLETE)
                        .build();

        final ListStacksResponse response =
                ListStacksResponse.builder().stackSummaries(Arrays.asList(summary)).build();

        when(cloudFormationClient.listStacks(any(ListStacksRequest.class))).thenReturn(response);

        // When
        final List<StackSummary> stacks = cloudFormationService.listStacks();

        // Then
        assertNotNull(stacks);
        assertEquals(1, stacks.size());
        assertEquals("test-stack", stacks.get(0).stackName());
    }

    @Test
    @DisplayName("Should return empty list on exception")
    void testListStacksException() {
        // Given
        when(cloudFormationClient.listStacks(any(ListStacksRequest.class)))
                .thenThrow(new RuntimeException("AWS error"));

        // When
        final List<StackSummary> stacks = cloudFormationService.listStacks();

        // Then
        assertNotNull(stacks);
        assertTrue(stacks.isEmpty());
    }

    @Test
    @DisplayName("Should throw exception for null client")
    void testConstructorNullClient() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    new CloudFormationService(null);
                });
    }
}
