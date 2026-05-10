package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.sns.SnsClient;

/** Unit Tests for NotificationConfig Tests notification service client configuration */
class NotificationConfigTest {

    private static final String US_EAST_1 = "us-east-1";
    private static final String US_WEST_2 = "us-west-2";

    private NotificationConfig notificationConfig = new NotificationConfig();

    @Test
    void testSnsClientWithDefaultRegion() {
        // When
        final SnsClient client = notificationConfig.snsClient(US_EAST_1);

        // Then
        assertNotNull(client);
        assertEquals(US_EAST_1, client.serviceClientConfiguration().region().id());
    }

    @Test
    void testSnsClientWithDifferentRegion() {
        // When
        final SnsClient client = notificationConfig.snsClient(US_WEST_2);

        // Then
        assertNotNull(client);
        assertEquals(US_WEST_2, client.serviceClientConfiguration().region().id());
    }

    @Test
    void testSesClientWithDefaultRegion() {
        // When
        final SesClient client = notificationConfig.sesClient(US_EAST_1);

        // Then
        assertNotNull(client);
        assertEquals(US_EAST_1, client.serviceClientConfiguration().region().id());
    }

    @Test
    void testSesClientWithDifferentRegion() {
        // When
        final SesClient client = notificationConfig.sesClient(US_WEST_2);

        // Then
        assertNotNull(client);
        assertEquals(US_WEST_2, client.serviceClientConfiguration().region().id());
    }

    @Test
    void testSnsAndSesClientsAreIndependent() {
        // When
        final SnsClient snsClient = notificationConfig.snsClient(US_EAST_1);
        final SesClient sesClient = notificationConfig.sesClient(US_WEST_2);

        // Then
        assertNotNull(snsClient);
        assertNotNull(sesClient);
        assertNotSame(snsClient, sesClient);
        assertEquals(US_EAST_1, snsClient.serviceClientConfiguration().region().id());
        assertEquals(US_WEST_2, sesClient.serviceClientConfiguration().region().id());
    }
}
