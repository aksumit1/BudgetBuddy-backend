package com.budgetbuddy.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.sns.SnsClient;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for NotificationConfig
 * Tests notification service client configuration
 */
class NotificationConfigTest {

    private NotificationConfig notificationConfig = new NotificationConfig();

    @Test
    void testSnsClient_WithDefaultRegion() {
        // When
        SnsClient client = notificationConfig.snsClient("us-east-1");

        // Then
        assertNotNull(client);
        assertEquals("us-east-1", client.serviceClientConfiguration().region().id());
    }

    @Test
    void testSnsClient_WithDifferentRegion() {
        // When
        SnsClient client = notificationConfig.snsClient("us-west-2");

        // Then
        assertNotNull(client);
        assertEquals("us-west-2", client.serviceClientConfiguration().region().id());
    }

    @Test
    void testSesClient_WithDefaultRegion() {
        // When
        SesClient client = notificationConfig.sesClient("us-east-1");

        // Then
        assertNotNull(client);
        assertEquals("us-east-1", client.serviceClientConfiguration().region().id());
    }

    @Test
    void testSesClient_WithDifferentRegion() {
        // When
        SesClient client = notificationConfig.sesClient("us-west-2");

        // Then
        assertNotNull(client);
        assertEquals("us-west-2", client.serviceClientConfiguration().region().id());
    }

    @Test
    void testSnsAndSesClients_AreIndependent() {
        // When
        SnsClient snsClient = notificationConfig.snsClient("us-east-1");
        SesClient sesClient = notificationConfig.sesClient("us-west-2");

        // Then
        assertNotNull(snsClient);
        assertNotNull(sesClient);
        assertNotSame(snsClient, sesClient);
        assertEquals("us-east-1", snsClient.serviceClientConfiguration().region().id());
        assertEquals("us-west-2", sesClient.serviceClientConfiguration().region().id());
    }
}

