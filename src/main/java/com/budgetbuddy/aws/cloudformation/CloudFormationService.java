package com.budgetbuddy.aws.cloudformation;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackEventsRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackEventsResponse;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackResourcesRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackResourcesResponse;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksResponse;
import software.amazon.awssdk.services.cloudformation.model.ListStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.ListStacksResponse;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;
import software.amazon.awssdk.services.cloudformation.model.StackResource;
import software.amazon.awssdk.services.cloudformation.model.StackSummary;

/**
 * AWS CloudFormation Integration Service Provides infrastructure as code capabilities
 *
 * <p>Thread-safe implementation with proper error handling and boundary checks
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Service
public final class CloudFormationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CloudFormationService.class);

    private final CloudFormationClient cloudFormationClient;

    public CloudFormationService(final CloudFormationClient cloudFormationClient) {
        if (cloudFormationClient == null) {
            throw new IllegalArgumentException("CloudFormationClient cannot be null");
        }
        this.cloudFormationClient = cloudFormationClient;
    }

    /** Get stack status Returns stack status or "NOT_FOUND" if stack doesn't exist */
    public String getStackStatus(final String stackName) {
        if (stackName == null || stackName.isEmpty()) {
            LOGGER.warn("Stack name is null or empty");
            return "INVALID";
        }

        try {
            final DescribeStacksResponse response =
                    cloudFormationClient.describeStacks(
                            DescribeStacksRequest.builder().stackName(stackName).build());

            final List<Stack> stacks = response.stacks();
            if (stacks != null && !stacks.isEmpty()) {
                final Stack firstStack = stacks.get(0);
                if (firstStack != null && firstStack.stackStatus() != null) {
                    return firstStack.stackStatusAsString();
                }
            }
            return "NOT_FOUND";
        } catch (Exception e) {
            LOGGER.error("Failed to get stack status for {}: {}", stackName, e.getMessage(), e);
            return "ERROR";
        }
    }

    /** List all stacks Returns list of stack summaries or empty list on error */
    public List<StackSummary> listStacks() {
        try {
            final ListStacksRequest.Builder requestBuilder = ListStacksRequest.builder();
            // Add status filters if needed - stackStatusFilter might not accept list directly
            // Use individual filters or omit to get all stacks
            final ListStacksResponse response = cloudFormationClient.listStacks(requestBuilder.build());

            final List<StackSummary> summaries = response.stackSummaries();
            return summaries != null ? summaries : Collections.emptyList();
        } catch (Exception e) {
            // Log at WARN level - this is a handled failure (returns empty list gracefully)
            // ERROR would be more appropriate for unhandled errors that cause service failure
            LOGGER.warn("Failed to list stacks: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /** Get stack resources Returns list of stack resources or empty list on error */
    public List<StackResource> getStackResources(final String stackName) {
        if (stackName == null || stackName.isEmpty()) {
            LOGGER.warn("Stack name is null or empty");
            return Collections.emptyList();
        }

        try {
            final DescribeStackResourcesResponse response =
                    cloudFormationClient.describeStackResources(
                            DescribeStackResourcesRequest.builder().stackName(stackName).build());

            final List<StackResource> resources = response.stackResources();
            return resources != null ? resources : Collections.emptyList();
        } catch (Exception e) {
            LOGGER.error("Failed to get stack resources for {}: {}", stackName, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /** Get stack events Returns list of stack events or empty list on error */
    public List<StackEvent> getStackEvents(final String stackName) {
        if (stackName == null || stackName.isEmpty()) {
            LOGGER.warn("Stack name is null or empty");
            return Collections.emptyList();
        }

        try {
            final DescribeStackEventsResponse response =
                    cloudFormationClient.describeStackEvents(
                            DescribeStackEventsRequest.builder().stackName(stackName).build());

            final List<StackEvent> events = response.stackEvents();
            return events != null ? events : Collections.emptyList();
        } catch (Exception e) {
            LOGGER.error("Failed to get stack events for {}: {}", stackName, e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
