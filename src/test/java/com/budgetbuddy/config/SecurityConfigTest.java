package com.budgetbuddy.config;

import com.budgetbuddy.AWSTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for SecurityConfig
 * 
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class SecurityConfigTest {

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void testPasswordEncoder_EncodesPassword() {
        try {
            // Given
            String rawPassword = "TestPassword123!";

            // When
            String encoded = passwordEncoder.encode(rawPassword);

            // Then
            assertNotNull(encoded);
            assertNotEquals(rawPassword, encoded);
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

    @Test
    void testPasswordEncoder_MatchesPassword() {
        try {
            // Given
            String rawPassword = "TestPassword123!";
            String encoded = passwordEncoder.encode(rawPassword);

            // When
            boolean matches = passwordEncoder.matches(rawPassword, encoded);

            // Then
            assertTrue(matches);
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

    @Test
    void testPasswordEncoder_DoesNotMatchWrongPassword() {
        try {
            // Given
            String rawPassword = "TestPassword123!";
            String wrongPassword = "WrongPassword123!";
            String encoded = passwordEncoder.encode(rawPassword);

            // When
            boolean matches = passwordEncoder.matches(wrongPassword, encoded);

            // Then
            assertFalse(matches);
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

