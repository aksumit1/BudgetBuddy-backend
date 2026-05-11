package com.budgetbuddy.observability;

import org.slf4j.MDC;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;

/**
 * AWS SDK execution interceptor that stamps the current correlation ID (from SLF4J MDC) onto every
 * outbound AWS request as the {@code X-Correlation-Id} header.
 *
 * <p>{@link com.budgetbuddy.config.CorrelationIdFilter} already pins the ID to MDC for the duration
 * of each inbound request. Without this interceptor, that ID disappeared the moment code crossed
 * the AWS SDK boundary: DynamoDB / S3 / SNS / SecretsManager / CloudWatch traffic in CloudTrail and
 * VPC flow logs couldn't be stitched back to the customer request that triggered it. With this
 * interceptor an on-call engineer can follow a single correlation ID from the access log all the
 * way through to "which DynamoDB call latency spiked".
 *
 * <p>Registered via {@code
 * META-INF/services/software.amazon.awssdk.core.interceptor.ExecutionInterceptor} so every SDK
 * client picks it up automatically — no per-bean wiring required. AWS SDK skips the request if MDC
 * doesn't have a correlation ID (background / scheduled paths), so outbound calls from a
 * {@code @Scheduled} job won't carry a stale value from a previous request thread.
 */
public class CorrelationIdExecutionInterceptor implements ExecutionInterceptor {

    private static final String MDC_KEY = "correlationId";
    private static final String HEADER_NAME = "X-Correlation-Id";

    @Override
    public SdkHttpRequest modifyHttpRequest(
            final Context.ModifyHttpRequest context, final ExecutionAttributes attributes) {
        final String correlationId = MDC.get(MDC_KEY);
        if (correlationId == null || correlationId.isEmpty()) {
            return context.httpRequest();
        }
        return context.httpRequest().toBuilder().putHeader(HEADER_NAME, correlationId).build();
    }
}
