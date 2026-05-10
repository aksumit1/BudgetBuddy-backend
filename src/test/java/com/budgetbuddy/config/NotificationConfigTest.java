package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.sns.SnsClient;

/** Unit Tests for NotificationConfig Tests notification service client configuration */
class NotificationConfigTest {

    private NotificationConfig notificationConfig = new NotificationConfig();

    @Test
    void testSnsClientWithDefaultRegion() {
        // When
        final SnsClient client = notificationConfig.snsClient("us-east-1");

        // Then
        assertNotNull(client);
        assertEquals("us-east-1", client.serviceClientConfiguration().region().id());
    }

    @Test
    void testSnsClientWithDifferentRegion() {
        // When
        final SnsClient client = notificationConfig.snsClient("us-west-2");

        // Then
        assertNotNull(client);
        assertEquals("us-west-2", client.serviceClientConfiguration().region().id());
    }

    @Test
    void testSesClientWithDefaultRegion() {
        // When
        final SesClient client = notificationConfig.sesClient("us-east-1");

        // Then
        assertNotNull(client);
        assertEquals("us-east-1", client.serviceClientConfiguration().region().id());
    }

    @Test
    void testSesClientWithDifferentRegion() {
        // When
        final SesClient client = notificationConfig.sesClient("us-west-2");

        // Then
        assertNotNull(client);
        assertEquals("us-west-2", client.serviceClientConfiguration().region().id());
    }

    @Test
    void testSnsAndSesClientsAreIndependent() {
        // When
        final SnsClient snsClient = notificationConfig.snsClient("us-east-1");
        final SesClient sesClient = notificationConfig.sesClient("us-west-2");

        // Then
        assertNotNull(snsClient);
        assertNotNull(sesClient);
        assertNotSame(snsClient, sesClient);
        assertEquals("us-east-1", snsClient.serviceClientConfiguration().region().id());
        assertEquals("us-west-2", sesClient.serviceClientConfiguration().region().id());
    }
}
