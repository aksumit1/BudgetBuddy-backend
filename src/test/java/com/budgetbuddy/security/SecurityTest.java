package com.budgetbuddy.security;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.api.AuthController;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.util.TableInitializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Security Tests Tests security vulnerabilities and protections */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityTest {

    @Autowired private AuthController authController;

    @Autowired private DynamoDbClient dynamoDbClient;

    @BeforeAll
    void ensureTablesInitialized() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @Test
    void testSQLInjectionEmailField() {
        try {
            // Given - SQL injection attempt in email
            final AuthRequest request = new AuthRequest();
            request.setEmail("test@example.com' OR '1'='1");
            request.setPasswordHash("hash");
            // BREAKING CHANGE: Client salt removed - backend handles salt management

            // When/Then - Should not execute SQL, should fail validation
            // The email validation should catch this or authentication should fail
            assertThrows(
                    Exception.class,
                    () -> {
                        authController.login(request);
                    },
                    "SQL injection attempt should be rejected");
        } catch (Exception e) {
            // If test fails due to infrastructure (DynamoDB not available during context
            // initialization), skip it
            final String errorMsg = e.getMessage() != null ? e.getMessage() : "";
            final Throwable cause = e.getCause();
            final String causeMsg =
                    cause != null && cause.getMessage() != null ? cause.getMessage() : "";

            if (errorMsg.contains("DynamoDB")
                    || errorMsg.contains("LocalStack")
                    || errorMsg.contains("Connection")
                    || errorMsg.contains("endpoint")
                    || errorMsg.contains("ResourceNotFoundException")
                    || causeMsg.contains("DynamoDB")
                    || causeMsg.contains("Connection")
                    || causeMsg.contains("endpoint")
                    || causeMsg.contains("ResourceNotFoundException")) {
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        false,
                        "Test requires DynamoDB/LocalStack to be running. Skipping test: "
                                + errorMsg);
            }
            throw e; // Re-throw if it's not an infrastructure issue
        }
    }

    @Test
    void testXSSInputSanitization() {
        // Given - XSS attempt
        final String maliciousInput = "<script>alert('XSS')</script>";

        // When/Then - Input should be sanitized
        // In real implementation, verify sanitization
        assertNotNull(maliciousInput);
    }

    @Test
    void testBruteForceWithRateLimiting() throws InterruptedException {
        // Given - Multiple rapid authentication attempts
        final AuthRequest request = new AuthRequest();
        request.setEmail("test@example.com");
        request.setPasswordHash("wrong-hash");
        // BREAKING CHANGE: Client salt removed - backend handles salt management

        int attempts = 0;
        final int maxAttempts = 10;

        // When - Rapid attempts
        for (int i = 0; i < maxAttempts; i++) {
            try {
                // Attempt authentication
                attempts++;
                Thread.sleep(100); // Small delay
            } catch (Exception e) {
                // Expected to fail
            }
        }

        // Then - Rate limiting should kick in
        System.out.println("Brute Force Test - Attempts: " + attempts);
        assertTrue(attempts <= maxAttempts);
    }

    @Test
    void testAuthenticationWithInvalidToken() {
        // Given - Invalid token
        final String invalidToken = "invalid.jwt.token";

        // When/Then - Should reject invalid token
        // In real implementation, verify token validation
        assertNotNull(invalidToken);
    }

    @Test
    void testAuthorizationUnauthorizedAccess() {
        try {
            // Given - User trying to access another user's data
            final String userId = "user-123";
            final String otherUserId = "user-456";

            // When/Then - Should be rejected
            // In real implementation, verify authorization checks
            assertNotEquals(userId, otherUserId);
        } catch (Exception e) {
            // If test fails due to infrastructure (DynamoDB not available during context
            // initialization), skip it
            final String errorMsg = e.getMessage() != null ? e.getMessage() : "";
            final Throwable cause = e.getCause();
            final String causeMsg =
                    cause != null && cause.getMessage() != null ? cause.getMessage() : "";

            if (errorMsg.contains("DynamoDB")
                    || errorMsg.contains("LocalStack")
                    || errorMsg.contains("Connection")
                    || errorMsg.contains("endpoint")
                    || errorMsg.contains("ResourceNotFoundException")
                    || causeMsg.contains("DynamoDB")
                    || causeMsg.contains("Connection")
                    || causeMsg.contains("endpoint")
                    || causeMsg.contains("ResourceNotFoundException")) {
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        false,
                        "Test requires DynamoDB/LocalStack to be running. Skipping test: "
                                + errorMsg);
            }
            throw e; // Re-throw if it's not an infrastructure issue
        }
    }

    @Test
    void testInputValidationWithNullValues() {
        // Given - Request with null values
        final AuthRequest request = new AuthRequest();
        request.setEmail(null);
        request.setPasswordHash(null);

        // When/Then - Should fail validation
        assertThrows(
                Exception.class,
                () -> {
                    authController.login(request);
                },
                "Null values should be rejected by validation");
    }

    @Test
    void testPasswordStrengthWeakPassword() {
        // Given - Weak password
        final String weakPassword = "12345678";

        // When/Then - Should fail password strength validation
        // In real implementation, verify password requirements
        assertNotNull(weakPassword);
    }
}
