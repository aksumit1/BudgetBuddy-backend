package com.budgetbuddy.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;

/**
 * Coverage for the AWS SDK interceptor that propagates the SLF4J MDC {@code correlationId}
 * onto outbound AWS requests. Registered via META-INF/services so every SDK client
 * (DynamoDB, S3, SecretsManager, etc.) picks it up automatically — these tests verify the
 * interceptor adds the header when set, and leaves the request alone when not.
 */
class CorrelationIdExecutionInterceptorTest {

    private static final String MDC_KEY = "correlationId";
    private static final String HEADER_NAME = "X-Correlation-Id";

    private CorrelationIdExecutionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new CorrelationIdExecutionInterceptor();
        MDC.remove(MDC_KEY);
    }

    @AfterEach
    void tearDown() {
        MDC.remove(MDC_KEY);
    }

    @Test
    void modifyHttpRequest_addsHeaderWhenMdcCorrelationIdIsSet() {
        MDC.put(MDC_KEY, "cid-42");
        final SdkHttpRequest original =
                SdkHttpRequest.builder()
                        .method(SdkHttpMethod.GET)
                        .protocol("https")
                        .host("dynamodb.us-east-1.amazonaws.com")
                        .build();
        final Context.ModifyHttpRequest ctx = mockContext(original);

        final SdkHttpRequest modified =
                interceptor.modifyHttpRequest(ctx, new ExecutionAttributes());

        final List<String> headerValues = modified.headers().getOrDefault(HEADER_NAME, List.of());
        assertEquals(List.of("cid-42"), headerValues);
    }

    @Test
    void modifyHttpRequest_returnsRequestUnchangedWhenMdcEmpty() {
        // No MDC set — common for background / scheduled paths that aren't request-scoped.
        final SdkHttpRequest original =
                SdkHttpRequest.builder()
                        .method(SdkHttpMethod.POST)
                        .protocol("https")
                        .host("secretsmanager.us-east-1.amazonaws.com")
                        .build();
        final Context.ModifyHttpRequest ctx = mockContext(original);

        final SdkHttpRequest modified =
                interceptor.modifyHttpRequest(ctx, new ExecutionAttributes());

        assertTrue(
                modified.headers().getOrDefault(HEADER_NAME, List.of()).isEmpty(),
                "Interceptor must NOT add the header when MDC is empty — otherwise a stale "
                        + "value from a previous request could leak into a scheduled-job call.");
    }

    @Test
    void modifyHttpRequest_returnsRequestUnchangedWhenMdcValueIsEmptyString() {
        MDC.put(MDC_KEY, "");
        final SdkHttpRequest original =
                SdkHttpRequest.builder()
                        .method(SdkHttpMethod.GET)
                        .protocol("https")
                        .host("s3.amazonaws.com")
                        .build();
        final Context.ModifyHttpRequest ctx = mockContext(original);

        final SdkHttpRequest modified =
                interceptor.modifyHttpRequest(ctx, new ExecutionAttributes());

        assertTrue(modified.headers().getOrDefault(HEADER_NAME, List.of()).isEmpty());
    }

    @Test
    void modifyHttpRequest_preservesExistingHeaders() {
        MDC.put(MDC_KEY, "cid-7");
        final SdkHttpRequest original =
                SdkHttpRequest.builder()
                        .method(SdkHttpMethod.GET)
                        .protocol("https")
                        .host("dynamodb.us-east-1.amazonaws.com")
                        .putHeader("User-Agent", "BudgetBuddy/1.0")
                        .build();
        final Context.ModifyHttpRequest ctx = mockContext(original);

        final SdkHttpRequest modified =
                interceptor.modifyHttpRequest(ctx, new ExecutionAttributes());

        assertEquals(List.of("cid-7"), modified.headers().get(HEADER_NAME));
        assertEquals(List.of("BudgetBuddy/1.0"), modified.headers().get("User-Agent"));
    }

    // ---- helpers ----

    private static Context.ModifyHttpRequest mockContext(final SdkHttpRequest request) {
        final Context.ModifyHttpRequest ctx = mock(Context.ModifyHttpRequest.class);
        when(ctx.httpRequest()).thenReturn(request);
        return ctx;
    }
}
