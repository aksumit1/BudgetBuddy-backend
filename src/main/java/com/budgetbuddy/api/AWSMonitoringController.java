package com.budgetbuddy.api;

import com.budgetbuddy.aws.cloudformation.CloudFormationService;
import com.budgetbuddy.aws.cloudtrail.CloudTrailService;
import com.budgetbuddy.service.aws.CloudWatchService;
import com.budgetbuddy.aws.codepipeline.CodePipelineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * AWS Monitoring REST Controller
 * Provides endpoints for AWS service monitoring
 */
@RestController
@RequestMapping("/api/aws/monitoring")
public class AWSMonitoringController {

    private final CloudWatchService cloudWatchService;
    private final CloudTrailService cloudTrailService;
    private final CloudFormationService cloudFormationService;
    private final CodePipelineService codePipelineService;
    private final com.budgetbuddy.service.UserService userService;

    public AWSMonitoringController(
            final CloudWatchService cloudWatchService,
            final CloudTrailService cloudTrailService,
            final CloudFormationService cloudFormationService,
            final CodePipelineService codePipelineService,
            final com.budgetbuddy.service.UserService userService) {
        this.cloudWatchService = cloudWatchService;
        this.cloudTrailService = cloudTrailService;
        this.cloudFormationService = cloudFormationService;
        this.codePipelineService = codePipelineService;
        this.userService = userService;
    }

    /**
     * Get CloudWatch metrics
     */
    @GetMapping("/cloudwatch/metrics")
    public ResponseEntity<Map<String, Object>> getCloudWatchMetrics(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam String metricName,
            @RequestParam Instant startTime,
            @RequestParam Instant endTime) {
        com.budgetbuddy.model.dynamodb.UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!hasMonitoringAccess(user)) {
            return ResponseEntity.status(403).build();
        }

        var statistics = cloudWatchService.getMetricStatistics(metricName, startTime, endTime);
        return ResponseEntity.ok(Map.of("statistics", statistics));
    }

    /**
     * Get CloudTrail events
     */
    @GetMapping("/cloudtrail/events")
    public ResponseEntity<List<software.amazon.awssdk.services.cloudtrail.model.Event>> getCloudTrailEvents(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) String userId,
            @RequestParam Instant startTime,
            @RequestParam Instant endTime) {
        com.budgetbuddy.model.dynamodb.UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!hasMonitoringAccess(user)) {
            return ResponseEntity.status(403).build();
        }

        String lookupUserId = userId != null ? userId : user.getUserId();
        List<software.amazon.awssdk.services.cloudtrail.model.Event> events =
                cloudTrailService.lookupEvents(lookupUserId, startTime, endTime);
        return ResponseEntity.ok(events);
    }

    /**
     * Get CloudFormation stack status
     */
    @GetMapping("/cloudformation/stacks")
    public ResponseEntity<List<software.amazon.awssdk.services.cloudformation.model.StackSummary>> getCloudFormationStacks(
            @AuthenticationPrincipal UserDetails userDetails) {
        com.budgetbuddy.model.dynamodb.UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!hasMonitoringAccess(user)) {
            return ResponseEntity.status(403).build();
        }

        List<software.amazon.awssdk.services.cloudformation.model.StackSummary> stacks =
                cloudFormationService.listStacks();
        return ResponseEntity.ok(stacks);
    }

    /**
     * Get CodePipeline status
     */
    @GetMapping("/codepipeline/status")
    public ResponseEntity<Map<String, String>> getCodePipelineStatus(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam String pipelineName) {
        com.budgetbuddy.model.dynamodb.UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!hasMonitoringAccess(user)) {
            return ResponseEntity.status(403).build();
        }

        String status = codePipelineService.getPipelineStatus(pipelineName);
        return ResponseEntity.ok(Map.of("pipelineName", pipelineName, "status", status));
    }

    private boolean hasMonitoringAccess(final com.budgetbuddy.model.dynamodb.UserTable user) {
        return user.getRoles() != null &&
               (user.getRoles().contains("ADMIN") || user.getRoles().contains("MONITORING"));
    }
}

