package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/** Integration Tests for SecurityConfig */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class SecurityConfigTest {

    private static final String RESOURCENOTFOUNDEXCEPTION = "ResourceNotFoundException";
    private static final String DYNAMODB = "DynamoDB";
    private static final String ENDPOINT = "endpoint";
    private static final String CONNECTION = "Connection";

    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void testPasswordEncoderEncodesPassword() {
        try {
            // Given
            final String rawPassword = "TestPassword123!";

            // When
            final String encoded = passwordEncoder.encode(rawPassword);

            // Then
            assertNotNull(encoded);
            assertNotEquals(rawPassword, encoded);
        } catch (Exception e) {
            // If test fails due to infrastructure (DynamoDB not available during context
            // initialization), skip it
            final String errorMsg = e.getMessage() != null ? e.getMessage() : "";
            final Throwable cause = e.getCause();
            final String causeMsg =
                    cause != null && cause.getMessage() != null ? cause.getMessage() : "";

            if (errorMsg.contains(DYNAMODB)
                    || errorMsg.contains("LocalStack")
                    || errorMsg.contains(CONNECTION)
                    || errorMsg.contains(ENDPOINT)
                    || errorMsg.contains(RESOURCENOTFOUNDEXCEPTION)
                    || causeMsg.contains(DYNAMODB)
                    || causeMsg.contains(CONNECTION)
                    || causeMsg.contains(ENDPOINT)
                    || causeMsg.contains(RESOURCENOTFOUNDEXCEPTION)) {
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        false,
                        "Test requires DynamoDB/LocalStack to be running. Skipping test: "
                                + errorMsg);
            }
            throw e; // Re-throw if it's not an infrastructure issue
        }
    }

    @Test
    void testPasswordEncoderMatchesPassword() {
        try {
            // Given
            final String rawPassword = "TestPassword123!";
            final String encoded = passwordEncoder.encode(rawPassword);

            // When
            final boolean matches = passwordEncoder.matches(rawPassword, encoded);

            // Then
            assertTrue(matches);
        } catch (Exception e) {
            // If test fails due to infrastructure (DynamoDB not available during context
            // initialization), skip it
            final String errorMsg = e.getMessage() != null ? e.getMessage() : "";
            final Throwable cause = e.getCause();
            final String causeMsg =
                    cause != null && cause.getMessage() != null ? cause.getMessage() : "";

            if (errorMsg.contains(DYNAMODB)
                    || errorMsg.contains("LocalStack")
                    || errorMsg.contains(CONNECTION)
                    || errorMsg.contains(ENDPOINT)
                    || errorMsg.contains(RESOURCENOTFOUNDEXCEPTION)
                    || causeMsg.contains(DYNAMODB)
                    || causeMsg.contains(CONNECTION)
                    || causeMsg.contains(ENDPOINT)
                    || causeMsg.contains(RESOURCENOTFOUNDEXCEPTION)) {
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        false,
                        "Test requires DynamoDB/LocalStack to be running. Skipping test: "
                                + errorMsg);
            }
            throw e; // Re-throw if it's not an infrastructure issue
        }
    }

    @Test
    void testPasswordEncoderDoesNotMatchWrongPassword() {
        try {
            // Given
            final String rawPassword = "TestPassword123!";
            final String wrongPassword = "WrongPassword123!";
            final String encoded = passwordEncoder.encode(rawPassword);

            // When
            final boolean matches = passwordEncoder.matches(wrongPassword, encoded);

            // Then
            assertFalse(matches);
        } catch (Exception e) {
            // If test fails due to infrastructure (DynamoDB not available during context
            // initialization), skip it
            final String errorMsg = e.getMessage() != null ? e.getMessage() : "";
            final Throwable cause = e.getCause();
            final String causeMsg =
                    cause != null && cause.getMessage() != null ? cause.getMessage() : "";

            if (errorMsg.contains(DYNAMODB)
                    || errorMsg.contains("LocalStack")
                    || errorMsg.contains(CONNECTION)
                    || errorMsg.contains(ENDPOINT)
                    || errorMsg.contains(RESOURCENOTFOUNDEXCEPTION)
                    || causeMsg.contains(DYNAMODB)
                    || causeMsg.contains(CONNECTION)
                    || causeMsg.contains(ENDPOINT)
                    || causeMsg.contains(RESOURCENOTFOUNDEXCEPTION)) {
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        false,
                        "Test requires DynamoDB/LocalStack to be running. Skipping test: "
                                + errorMsg);
            }
            throw e; // Re-throw if it's not an infrastructure issue
        }
    }
}
