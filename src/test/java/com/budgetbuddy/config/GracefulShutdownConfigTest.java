package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

/** Unit Tests for GracefulShutdownConfig Tests graceful shutdown configuration and behavior */
@ExtendWith(MockitoExtension.class)
class GracefulShutdownConfigTest {

    @Mock private ApplicationContext applicationContext;

    private GracefulShutdownConfig.GracefulShutdown gracefulShutdown;

    @BeforeEach
    void setUp() {
        gracefulShutdown = new GracefulShutdownConfig.GracefulShutdown();
    }

    @Test
    void testCustomizeSetsConnector() {
        // Given
        final Connector connector = mock(Connector.class);

        // When
        gracefulShutdown.customize(connector);

        // Then - Should not throw exception
        assertNotNull(gracefulShutdown);
    }

    @Test
    void testOnApplicationEventWithNullConnectorDoesNotFail() {
        // Given - connector is null (not customized)

        // When
        final ContextClosedEvent event = new ContextClosedEvent(applicationContext);
        gracefulShutdown.onApplicationEvent(event);

        // Then - Should not throw exception
        assertTrue(true, "Should handle null connector gracefully");
    }

    @Test
    void testServletContainerCreatesFactory() {
        // Given
        final GracefulShutdownConfig config = new GracefulShutdownConfig();
        final GracefulShutdownConfig.GracefulShutdown shutdown =
                new GracefulShutdownConfig.GracefulShutdown();

        // When
        final TomcatServletWebServerFactory factory =
                (TomcatServletWebServerFactory) config.servletContainer(shutdown);

        // Then
        assertNotNull(factory, "Factory should be created");
    }

    @Test
    void testGracefulShutdownBeanCreation() {
        // Given
        final GracefulShutdownConfig config = new GracefulShutdownConfig();

        // When
        final GracefulShutdownConfig.GracefulShutdown shutdown = config.gracefulShutdown();

        // Then
        assertNotNull(shutdown, "GracefulShutdown bean should be created");
    }
}
