package com.budgetbuddy.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * LocalStack Health Monitor Monitors LocalStack availability and logs warnings if it becomes
 * unavailable This helps detect when LocalStack has been stopped accidentally
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Component
@ConditionalOnExpression("!'${app.aws.dynamodb.endpoint:}'.isEmpty()")
public class LocalStackHealthMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalStackHealthMonitor.class);

    @Value("${app.aws.dynamodb.endpoint:}")
    private String dynamoDbEndpoint;

    private final HttpClient httpClient;
    private int consecutiveFailures = 0;
    private static final int WARNING_THRESHOLD = 3; // Warn after 3 consecutive failures

    public LocalStackHealthMonitor() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Check LocalStack health every 30 seconds Logs warnings if LocalStack becomes unavailable */
    @Scheduled(fixedRate = 30_000) // Every 30 seconds
    public void checkLocalStackHealth() {
        // Only monitor if we're using LocalStack (endpoint is configured)
        if (dynamoDbEndpoint == null || dynamoDbEndpoint.isEmpty()) {
            return; // Not using LocalStack, skip monitoring
        }

        try {
            // Extract host and port from endpoint
            final URI endpointUri = URI.create(dynamoDbEndpoint);
            final String healthCheckUrl =
                    String.format(
                            "%s://%s/_localstack/health",
                            endpointUri.getScheme(), endpointUri.getAuthority());

            final HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(healthCheckUrl))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build();

            final HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                if (consecutiveFailures > 0) {
                    LOGGER.warn(
                            "⚠️ LocalStack recovered after {} consecutive failures",
                            consecutiveFailures);
                    consecutiveFailures = 0;
                }
            } else {
                consecutiveFailures++;
                if (consecutiveFailures >= WARNING_THRESHOLD) {
                    LOGGER.error(
                            "❌ LocalStack health check failed ({} consecutive failures). "
                                    + "Status: {}. LocalStack may be down or unreachable. "
                                    + "Backend operations requiring DynamoDB will fail. "
                                    + "To restart: docker-compose -f docker-compose.yml restart localstack",
                            consecutiveFailures,
                            response.statusCode());
                }
            }
        } catch (java.net.UnknownHostException e) {
            consecutiveFailures++;
            if (consecutiveFailures >= WARNING_THRESHOLD) {
                LOGGER.error(
                        "❌ LocalStack hostname cannot be resolved ({} consecutive failures). "
                                + "LocalStack container may be stopped. "
                                + "Error: {}. "
                                + "To start: docker-compose -f docker-compose.yml up -d localstack",
                        consecutiveFailures,
                        e.getMessage());
            }
        } catch (java.net.ConnectException e) {
            consecutiveFailures++;
            if (consecutiveFailures >= WARNING_THRESHOLD) {
                LOGGER.error(
                        "❌ Cannot connect to LocalStack ({} consecutive failures). "
                                + "LocalStack may be down or not accessible. "
                                + "Error: {}. "
                                + "To restart: docker-compose -f docker-compose.yml restart localstack",
                        consecutiveFailures,
                        e.getMessage());
            }
        } catch (Exception e) {
            consecutiveFailures++;
            if (consecutiveFailures >= WARNING_THRESHOLD) {
                LOGGER.error(
                        "❌ LocalStack health check error ({} consecutive failures): {}",
                        consecutiveFailures,
                        e.getMessage(),
                        e);
            }
        }
    }

    /** Get current health status (for actuator endpoint) */
    public boolean isHealthy() {
        return consecutiveFailures < WARNING_THRESHOLD;
    }
}
