package com.budgetbuddy.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.net.ssl.SSLContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for TLSConfig
 */
@SpringBootTest
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

