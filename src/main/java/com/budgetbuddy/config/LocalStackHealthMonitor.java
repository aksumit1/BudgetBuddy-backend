package com.budgetbuddy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * LocalStack Health Monitor
 * Monitors LocalStack availability and logs warnings if it becomes unavailable
 * This helps detect when LocalStack has been stopped accidentally
 */
@Component
@ConditionalOnExpression("!'${app.aws.dynamodb.endpoint:}'.isEmpty()")
public class LocalStackHealthMonitor {

    private static final Logger logger = LoggerFactory.getLogger(LocalStackHealthMonitor.class);
    
    @Value("${app.aws.dynamodb.endpoint:}")
    private String dynamoDbEndpoint;
    
    private final HttpClient httpClient;
    private int consecutiveFailures = 0;
    private static final int WARNING_THRESHOLD = 3; // Warn after 3 consecutive failures
    
    public LocalStackHealthMonitor() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }
    
    /**
     * Check LocalStack health every 30 seconds
     * Logs warnings if LocalStack becomes unavailable
     */
    @Scheduled(fixedRate = 30000) // Every 30 seconds
    public void checkLocalStackHealth() {
        // Only monitor if we're using LocalStack (endpoint is configured)
        if (dynamoDbEndpoint == null || dynamoDbEndpoint.isEmpty()) {
            return; // Not using LocalStack, skip monitoring
        }
        
        try {
            // Extract host and port from endpoint
            URI endpointUri = URI.create(dynamoDbEndpoint);
            String healthCheckUrl = String.format("%s://%s/_localstack/health", 
                    endpointUri.getScheme(), 
                    endpointUri.getAuthority());
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(healthCheckUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                    HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                if (consecutiveFailures > 0) {
                    logger.warn("⚠️ LocalStack recovered after {} consecutive failures", consecutiveFailures);
                    consecutiveFailures = 0;
                }
            } else {
                consecutiveFailures++;
                if (consecutiveFailures >= WARNING_THRESHOLD) {
                    logger.error("❌ LocalStack health check failed ({} consecutive failures). " +
                            "Status: {}. LocalStack may be down or unreachable. " +
                            "Backend operations requiring DynamoDB will fail. " +
                            "To restart: docker-compose -f docker-compose.yml restart localstack",
                            consecutiveFailures, response.statusCode());
                }
            }
        } catch (java.net.UnknownHostException e) {
            consecutiveFailures++;
            if (consecutiveFailures >= WARNING_THRESHOLD) {
                logger.error("❌ LocalStack hostname cannot be resolved ({} consecutive failures). " +
                        "LocalStack container may be stopped. " +
                        "Error: {}. " +
                        "To start: docker-compose -f docker-compose.yml up -d localstack",
                        consecutiveFailures, e.getMessage());
            }
        } catch (java.net.ConnectException e) {
            consecutiveFailures++;
            if (consecutiveFailures >= WARNING_THRESHOLD) {
                logger.error("❌ Cannot connect to LocalStack ({} consecutive failures). " +
                        "LocalStack may be down or not accessible. " +
                        "Error: {}. " +
                        "To restart: docker-compose -f docker-compose.yml restart localstack",
                        consecutiveFailures, e.getMessage());
            }
        } catch (Exception e) {
            consecutiveFailures++;
            if (consecutiveFailures >= WARNING_THRESHOLD) {
                logger.error("❌ LocalStack health check error ({} consecutive failures): {}",
                        consecutiveFailures, e.getMessage(), e);
            }
        }
    }
    
    /**
     * Get current health status (for actuator endpoint)
     */
    public boolean isHealthy() {
        return consecutiveFailures < WARNING_THRESHOLD;
    }
}

