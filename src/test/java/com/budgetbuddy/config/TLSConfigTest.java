package com.budgetbuddy.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.net.ssl.SSLContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for TLSConfig
 * 
 * DISABLED: Java 25 compatibility issue - Spring Boot context fails to load
 * due to Java 25 class format (major version 69) incompatibility with Spring Boot 3.4.1.
 * Will be re-enabled when Spring Boot fully supports Java 25.
 */
@org.junit.jupiter.api.Disabled("Java 25 compatibility: Spring Boot context loading fails")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
class TLSConfigTest {

    @Autowired
    private TLSConfig tlsConfig;

    @Test
    void testSslContext_IsInitialized() throws Exception {
        // When
        SSLContext sslContext = tlsConfig.sslContext();

        // Then
        assertNotNull(sslContext);
        assertEquals("TLS", sslContext.getProtocol());
    }
}

