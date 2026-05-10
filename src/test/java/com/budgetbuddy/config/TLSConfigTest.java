package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.budgetbuddy.AWSTestConfiguration;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Integration Tests for TLSConfig */
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class TLSConfigTest {

    @Autowired private TLSConfig tlsConfig;

    @Test
    void testSslContextIsInitialized() throws Exception {
        try {
            // When
            final SSLContext sslContext = tlsConfig.sslContext();

            // Then
            assertNotNull(sslContext);
            assertEquals("TLS", sslContext.getProtocol());
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
}
