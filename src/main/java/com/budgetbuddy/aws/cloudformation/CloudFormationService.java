package com.budgetbuddy.aws.cloudformation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;

import java.util.Collections;
import java.util.List;

/**
 * AWS CloudFormation Integration Service
 * Provides infrastructure as code capabilities
 *
 * Thread-safe implementation with proper error handling and boundary checks
 */
@Service
public class CloudFormationService {

    private static final Logger logger = LoggerFactory.getLogger(CloudFormationService.class);

    private final CloudFormationClient cloudFormationClient;

    public CloudFormationService(final CloudFormationClient cloudFormationClient) {
        if (cloudFormationClient == null) {
            throw new IllegalArgumentException("CloudFormationClient cannot be null");
        }
        this.cloudFormationClient = cloudFormationClient;
    }

    /**
     * Get stack status
     * Returns stack status or "NOT_FOUND" if stack doesn't exist
     */
    public String getStackStatus((final String stackName) {
        if (stackName == null || stackName.isEmpty()) {
            logger.warn("Stack name is null or empty");
            return "INVALID";
        }

        try {
            DescribeStacksResponse response = cloudFormationClient.describeStacks(
                    DescribeStacksRequest.builder()
                            .stackName(stackName)
                            .build());

            List<Stack> stacks = response.stacks();
            if (stacks != null && !stacks.isEmpty()) {
                Stack firstStack = stacks.get(0);
                if (firstStack != null && firstStack.stackStatus() != null) {
                    return firstStack.stackStatusAsString();
                }
            }
            return "NOT_FOUND";
        } catch (Exception e) {
            logger.error("Failed to get stack status for {}: {}", stackName, e.getMessage(), e);
            return "ERROR";
        }
    }

    /**
     * List all stacks
     * Returns list of stack summaries or empty list on error
     */
    public List<StackSummary> listStacks() {
        try {
            ListStacksRequest.Builder requestBuilder = ListStacksRequest.builder();
            // Add status filters if needed - stackStatusFilter might not accept list directly
            // Use individual filters or omit to get all stacks
            ListStacksResponse response = cloudFormationClient.listStacks(requestBuilder.build());

            List<StackSummary> summaries = response.stackSummaries();
            return summaries != null ? summaries : Collections.emptyList();
        } catch (Exception e) {
            logger.error("Failed to list stacks: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Get stack resources
     * Returns list of stack resources or empty list on error
     */
    public List<StackResource> getStackResources(String stackName) {
        if (stackName == null || stackName.isEmpty()) {
            logger.warn("Stack name is null or empty");
            return Collections.emptyList();
        }

        try {
            DescribeStackResourcesResponse response = cloudFormationClient.describeStackResources(
                    DescribeStackResourcesRequest.builder()
                            .stackName(stackName)
                            .build());

            List<StackResource> resources = response.stackResources();
            return resources != null ? resources : Collections.emptyList();
        } catch (Exception e) {
            logger.error("Failed to get stack resources for {}: {}", stackName, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Get stack events
     * Returns list of stack events or empty list on error
     */
    public List<StackEvent> getStackEvents(String stackName) {
        if (stackName == null || stackName.isEmpty()) {
            logger.warn("Stack name is null or empty");
            return Collections.emptyList();
        }

        try {
            DescribeStackEventsResponse response = cloudFormationClient.describeStackEvents(
                    DescribeStackEventsRequest.builder()
                            .stackName(stackName)
                            .build());

            List<StackEvent> events = response.stackEvents();
            return events != null ? events : Collections.emptyList();
        } catch (Exception e) {
            logger.error("Failed to get stack events for {}: {}", stackName, e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
