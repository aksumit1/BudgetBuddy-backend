package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/**
 * Deep-validation coverage for {@link StartupReadinessProbe}. The probe's only job is to
 * gate Spring Boot's readiness state on every {@link
 * StartupReadinessProbe#CRITICAL_TABLES critical DynamoDB table} being reachable. A regression
 * here either:
 *
 * <ul>
 *   <li>Routes traffic to a half-provisioned pod (every request 500s until ALB removes the
 *       task) — caused by the probe always flipping ready=true regardless of DescribeTable
 *       outcome.
 *   <li>Holds traffic off forever after a transient AWS hiccup — caused by the probe not
 *       retrying.
 *   <li>Probes the wrong table names — caused by table-prefix being dropped or doubled.
 * </ul>
 *
 * The retry interval and max-attempt counts are tuned down via reflection so the suite stays
 * fast (the production 5-second sleeps × 5 attempts = 25s otherwise).
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class StartupReadinessProbeTest {

    private static final String PREFIX = "TestBudgetBuddy";

    @Mock private DynamoDbClient dynamoDbClient;
    @Mock private ApplicationContext applicationContext;

    private StartupReadinessProbe probe;

    @BeforeEach
    void setUp() {
        probe = new StartupReadinessProbe(dynamoDbClient, applicationContext, PREFIX);
        // Slash retry sleep so the suite isn't measured in tens of seconds.
        ReflectionTestUtils.setField(probe, "retryDelayMs", 1L);
    }

    // ---------- happy path ----------

    @Test
    void probeAtStartup_flipsReady_andEmitsAcceptingTraffic_whenAllTablesPresent() {
        // Every DescribeTable returns a fake response — the probe doesn't read the body.
        when(dynamoDbClient.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(DescribeTableResponse.builder().build());

        invokeProbe(probe);

        assertTrue(probe.isReady(), "All tables present => ready");
        assertNull(probe.lastFailure(), "No failure recorded on clean pass");

        final ApplicationEvent emitted = captureFirstEvent(applicationContext);
        assertNotNull(emitted, "Probe must publish an availability change event");
        assertTrue(emitted instanceof AvailabilityChangeEvent<?>);
        assertTrue(
                ((AvailabilityChangeEvent<?>) emitted).getState() == ReadinessState.ACCEPTING_TRAFFIC,
                "Successful probe must transition the app to ACCEPTING_TRAFFIC");
    }

    @Test
    void probeAtStartup_concatenatesTablePrefixCorrectly() {
        when(dynamoDbClient.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(DescribeTableResponse.builder().build());

        invokeProbe(probe);

        // Walk every captured DescribeTable request and assert each table name has the
        // configured prefix (with single dash separator). Drift here means a deploy with
        // tablePrefix=Foo silently probes "BudgetBuddy-Users" instead of "Foo-Users".
        final ArgumentCaptor<DescribeTableRequest> captor =
                ArgumentCaptor.forClass(DescribeTableRequest.class);
        verify(dynamoDbClient, atLeastOnce()).describeTable(captor.capture());
        for (final DescribeTableRequest req : captor.getAllValues()) {
            assertTrue(
                    req.tableName().startsWith(PREFIX + "-"),
                    "Probed table name '" + req.tableName() + "' must use prefix '" + PREFIX + "-'");
        }
    }

    @Test
    void probeAtStartup_probesEveryCriticalTable() {
        when(dynamoDbClient.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(DescribeTableResponse.builder().build());

        invokeProbe(probe);

        final ArgumentCaptor<DescribeTableRequest> captor =
                ArgumentCaptor.forClass(DescribeTableRequest.class);
        verify(dynamoDbClient, atLeastOnce()).describeTable(captor.capture());

        final List<String> names = new ArrayList<>();
        for (final DescribeTableRequest req : captor.getAllValues()) {
            names.add(req.tableName());
        }
        // We don't pin the exact list here (that's CRITICAL_TABLES' job) but we do guard
        // that the core auth/account/transaction trio is covered — losing any of them
        // would let the app accept traffic against a missing dependency.
        assertTrue(names.contains(PREFIX + "-Users"), "Users must be probed");
        assertTrue(names.contains(PREFIX + "-Accounts"), "Accounts must be probed");
        assertTrue(names.contains(PREFIX + "-Transactions"), "Transactions must be probed");
    }

    // ---------- retry / failure ----------

    @Test
    void probeAtStartup_retries_thenFails_whenTableNeverAppears() {
        when(dynamoDbClient.describeTable(any(DescribeTableRequest.class)))
                .thenThrow(ResourceNotFoundException.builder().message("not found").build());

        invokeProbe(probe);

        assertFalse(probe.isReady(), "Probe must NOT flip ready when tables are missing");
        assertNotNull(probe.lastFailure());
        assertTrue(
                probe.lastFailure().contains("ResourceNotFoundException"),
                "lastFailure must surface the exception class for ops visibility");

        // 5 attempts × (CRITICAL_TABLES) describe calls each. We don't assert the exact
        // multiplier (it depends on which table fails first per pass) but we do verify
        // ≥ 5 attempts × ≥ 1 call = 5+ calls total.
        verify(dynamoDbClient, atLeast(5)).describeTable(any(DescribeTableRequest.class));

        // And on final failure the readiness state must flip to REFUSING_TRAFFIC.
        final List<ApplicationEvent> events = captureAllEvents(applicationContext);
        boolean refusing = false;
        for (final ApplicationEvent event : events) {
            if (event instanceof AvailabilityChangeEvent<?> avail
                    && avail.getState() == ReadinessState.REFUSING_TRAFFIC) {
                refusing = true;
                break;
            }
        }
        assertTrue(refusing, "After exhausting retries, probe must emit REFUSING_TRAFFIC");
    }

    @Test
    void probeAtStartup_recovers_whenLaterAttemptSucceeds() {
        // First call throws (table not provisioned yet), every subsequent call succeeds.
        // The probe's retry loop should let the second-pass success flip the gate open.
        when(dynamoDbClient.describeTable(any(DescribeTableRequest.class)))
                .thenThrow(ResourceNotFoundException.builder().message("missing").build())
                .thenReturn(DescribeTableResponse.builder().build());

        invokeProbe(probe);

        assertTrue(probe.isReady(), "Probe must recover when a subsequent attempt succeeds");
        assertNull(probe.lastFailure(), "lastFailure cleared on successful recovery");
    }

    // ---------- input shape ----------

    @Test
    void isReady_returnsFalse_beforeProbeRuns() {
        // Cold state — no events processed yet.
        assertFalse(probe.isReady());
        assertNotNull(probe.lastFailure(), "lastFailure must have a non-null default for the HealthIndicator");
    }

    // ---------- helpers ----------

    /** The probe's listener is package-private; this avoids depending on event publication wiring. */
    private static void invokeProbe(final StartupReadinessProbe probe) {
        ReflectionTestUtils.invokeMethod(probe, "probeAtStartup");
    }

    private static ApplicationEvent captureFirstEvent(final ApplicationContext ctx) {
        final ArgumentCaptor<ApplicationEvent> captor =
                ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(ctx, atLeastOnce()).publishEvent(captor.capture());
        return captor.getValue();
    }

    private static List<ApplicationEvent> captureAllEvents(final ApplicationContext ctx) {
        final ArgumentCaptor<ApplicationEvent> captor =
                ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(ctx, atLeastOnce()).publishEvent(captor.capture());
        return captor.getAllValues();
    }
}
