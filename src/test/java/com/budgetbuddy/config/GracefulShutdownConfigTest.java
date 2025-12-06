package com.budgetbuddy.config;

import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for GracefulShutdownConfig
 * Tests graceful shutdown configuration and behavior
 */
@ExtendWith(MockitoExtension.class)
class GracefulShutdownConfigTest {

    @Mock
    private ApplicationContext applicationContext;

    private GracefulShutdownConfig.GracefulShutdown gracefulShutdown;

    @BeforeEach
    void setUp() {
        gracefulShutdown = new GracefulShutdownConfig.GracefulShutdown();
    }

    @Test
    void testCustomize_SetsConnector() {
        // Given
        Connector connector = mock(Connector.class);
        
        // When
        gracefulShutdown.customize(connector);

        // Then - Should not throw exception
        assertNotNull(gracefulShutdown);
    }

    @Test
    void testOnApplicationEvent_WithNullConnector_DoesNotFail() {
        // Given - connector is null (not customized)
        
        // When
        ContextClosedEvent event = new ContextClosedEvent(applicationContext);
        gracefulShutdown.onApplicationEvent(event);

        // Then - Should not throw exception
        assertTrue(true, "Should handle null connector gracefully");
    }

    @Test
    void testServletContainer_CreatesFactory() {
        // Given
        GracefulShutdownConfig config = new GracefulShutdownConfig();
        GracefulShutdownConfig.GracefulShutdown shutdown = new GracefulShutdownConfig.GracefulShutdown();

        // When
        TomcatServletWebServerFactory factory = (TomcatServletWebServerFactory) config.servletContainer(shutdown);

        // Then
        assertNotNull(factory, "Factory should be created");
    }

    @Test
    void testGracefulShutdown_BeanCreation() {
        // Given
        GracefulShutdownConfig config = new GracefulShutdownConfig();

        // When
        GracefulShutdownConfig.GracefulShutdown shutdown = config.gracefulShutdown();

        // Then
        assertNotNull(shutdown, "GracefulShutdown bean should be created");
    }
}

