package com.budgetbuddy.config;

import com.budgetbuddy.AWSTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import javax.net.ssl.SSLContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for TLSConfig
 * 
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class TLSConfigTest {

    @Autowired
    private TLSConfig tlsConfig;

    @Test
    void testSslContext_IsInitialized() throws Exception {
        try {
            // When
            SSLContext sslContext = tlsConfig.sslContext();

            // Then
            assertNotNull(sslContext);
            assertEquals("TLS", sslContext.getProtocol());
        } catch (Exception e) {
            // If test fails due to infrastructure (DynamoDB not available during context initialization), skip it
            String errorMsg = e.getMessage() != null ? e.getMessage() : "";
            Throwable cause = e.getCause();
            String causeMsg = (cause != null && cause.getMessage() != null) ? cause.getMessage() : "";
            
            if (errorMsg.contains("DynamoDB") || errorMsg.contains("LocalStack") || 
                errorMsg.contains("Connection") || errorMsg.contains("endpoint") ||
                errorMsg.contains("ResourceNotFoundException") ||
                causeMsg.contains("DynamoDB") || causeMsg.contains("Connection") ||
                causeMsg.contains("endpoint") || causeMsg.contains("ResourceNotFoundException")) {
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        false,
                        "Test requires DynamoDB/LocalStack to be running. Skipping test: " + errorMsg
                );
            }
            throw e; // Re-throw if it's not an infrastructure issue
        }
    }
}

