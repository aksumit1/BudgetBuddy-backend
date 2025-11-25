package com.budgetbuddy.aws.cloudtrail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.*;

import java.time.Instant;
import java.util.List;

/**
 * AWS CloudTrail Integration Service
 * Provides API activity logging and compliance auditing
 */
@Service
public class CloudTrailService {

    private static final Logger logger = LoggerFactory.getLogger(CloudTrailService.class);

    private final CloudTrailClient cloudTrailClient;

    public CloudTrailService(final CloudTrailClient cloudTrailClient) {
        this.cloudTrailClient = cloudTrailClient;
    }

    /**
     * Log API activity to CloudTrail
     * Note: CloudTrail automatically logs AWS API calls
     * This method logs application-level activities
     */
    public void logApplicationActivity(final String userId, final String action, final String resource, final String result) {
        // CloudTrail automatically logs AWS API calls
        // For application-level logging, we use CloudWatch Logs
        logger.info("CloudTrail: User={}, Action={}, Resource={}, Result={}", userId, action, resource, result);
    }

    /**
     * Lookup CloudTrail events
     */
    public List<Event> lookupEvents(String userId, Instant startTime, Instant endTime) {
        try {
            LookupEventsResponse response = cloudTrailClient.lookupEvents(LookupEventsRequest.builder()
                    .lookupAttributes(List.of(
                            LookupAttribute.builder()
                                    .attributeKey(LookupAttributeKey.USERNAME)
                                    .attributeValue(userId)
                                    .build()
                    ))
                    .startTime(startTime)
                    .endTime(endTime)
                    .maxResults(50)
                    .build());

            return response.events();
        } catch (Exception e) {
            logger.error("Failed to lookup CloudTrail events: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Get trail status
     */
    public GetTrailStatusResponse getTrailStatus(final String trailName) {
        try {
            return cloudTrailClient.getTrailStatus(GetTrailStatusRequest.builder()
                    .name(trailName)
                    .build());
        } catch (Exception e) {
            logger.error("Failed to get trail status: {}", e.getMessage());
            return null;
        }
    }
}

