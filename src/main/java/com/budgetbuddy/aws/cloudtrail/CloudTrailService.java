package com.budgetbuddy.aws.cloudtrail;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.Event;
import software.amazon.awssdk.services.cloudtrail.model.GetTrailStatusRequest;
import software.amazon.awssdk.services.cloudtrail.model.GetTrailStatusResponse;
import software.amazon.awssdk.services.cloudtrail.model.LookupAttribute;
import software.amazon.awssdk.services.cloudtrail.model.LookupAttributeKey;
import software.amazon.awssdk.services.cloudtrail.model.LookupEventsRequest;
import software.amazon.awssdk.services.cloudtrail.model.LookupEventsResponse;

/** AWS CloudTrail Integration Service Provides API activity logging and compliance auditing */
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
public class CloudTrailService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CloudTrailService.class);

    private final CloudTrailClient cloudTrailClient;

    public CloudTrailService(final CloudTrailClient cloudTrailClient) {
        this.cloudTrailClient = cloudTrailClient;
    }

    /**
     * Log API activity to CloudTrail Note: CloudTrail automatically logs AWS API calls This method
     * logs application-level activities
     */
    public void logApplicationActivity(
            final String userId, final String action, final String resource, final String result) {
        // CloudTrail automatically logs AWS API calls
        // For application-level logging, we use CloudWatch Logs
        LOGGER.info(
                "CloudTrail: User={}, Action={}, Resource={}, Result={}",
                userId,
                action,
                resource,
                result);
    }

    /** Lookup CloudTrail events */
    public List<Event> lookupEvents(final String userId, final Instant startTime, final Instant endTime) {
        try {
            final LookupEventsResponse response =
                    cloudTrailClient.lookupEvents(
                            LookupEventsRequest.builder()
                                    .lookupAttributes(
                                            List.of(
                                                    LookupAttribute.builder()
                                                            .attributeKey(
                                                                    LookupAttributeKey.USERNAME)
                                                            .attributeValue(userId)
                                                            .build()))
                                    .startTime(startTime)
                                    .endTime(endTime)
                                    .maxResults(50)
                                    .build());

            return response.events();
        } catch (Exception e) {
            LOGGER.error("Failed to lookup CloudTrail events: {}", e.getMessage());
            return List.of();
        }
    }

    /** Get trail status */
    public GetTrailStatusResponse getTrailStatus(final String trailName) {
        try {
            return cloudTrailClient.getTrailStatus(
                    GetTrailStatusRequest.builder().name(trailName).build());
        } catch (Exception e) {
            LOGGER.error("Failed to get trail status: {}", e.getMessage());
            return null;
        }
    }
}
