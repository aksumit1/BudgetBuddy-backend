package com.budgetbuddy.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Fails startup when production secrets are unset. Replaces the previous
 * behaviour where {@code application.yml} carried hard-coded weak
 * defaults — silently booting with a known JWT secret means every token
 * issued is forgeable by anyone who has read the public repo.
 *
 * <p>Active outside the {@code test} profile so unit tests, which
 * provide their own values via {@code @TestPropertySource}, still work.
 * Production deployments must set {@code JWT_SECRET} and
 * {@code ENCRYPTION_KEY} as environment variables (or wire AWS Secrets
 * Manager, which overrides the env-var fallback).
 */
@Configuration
@Profile("!test")
public class SecretsValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecretsValidator.class);

    /** Minimum bytes a JWT signing key needs (HS256 = 32 bytes). */
    private static final int JWT_SECRET_MIN_BYTES = 32;
    /** AES-256 wants a 32-byte key. */
    private static final int ENCRYPTION_KEY_MIN_BYTES = 32;

    // Property name must match the one JwtTokenProvider reads
    // ({@code app.jwt.secret}). An earlier draft read
    // {@code spring.jwt.secret}, which doesn't exist anywhere — so
    // the validator failed every startup even when JWT_SECRET was
    // correctly mapped to {@code app.jwt.secret} via application.yml.
    @Value("${app.jwt.secret:}")
    private String jwtSecret;

    @Value("${app.security.encryption.key:}")
    private String encryptionKey;

    @PostConstruct
    void validate() {
        final List<String> errors = new ArrayList<>();
        if (jwtSecret == null || jwtSecret.isBlank()) {
            errors.add(
                    "JWT_SECRET environment variable is empty. Set it to a "
                            + "random 32+ byte string (e.g. `openssl rand -base64 32`).");
        } else if (jwtSecret.length() < JWT_SECRET_MIN_BYTES) {
            errors.add(
                    "JWT_SECRET is shorter than " + JWT_SECRET_MIN_BYTES
                            + " bytes — token signatures will be weak.");
        }
        if (encryptionKey == null || encryptionKey.isBlank()) {
            errors.add(
                    "ENCRYPTION_KEY environment variable is empty. Set it to "
                            + "a 32-byte key for AES-256-GCM.");
        } else if (encryptionKey.length() < ENCRYPTION_KEY_MIN_BYTES) {
            errors.add(
                    "ENCRYPTION_KEY is shorter than " + ENCRYPTION_KEY_MIN_BYTES
                            + " bytes — at-rest encryption is below AES-256 strength.");
        }
        if (!errors.isEmpty()) {
            final String msg = "Missing or weak production secrets:\n  - "
                    + String.join("\n  - ", errors)
                    + "\nDeployment must set these via environment variables or "
                    + "AWS Secrets Manager. Refusing to boot with placeholder values.";
            LOGGER.error(msg);
            throw new IllegalStateException(msg);
        }
        LOGGER.info("SecretsValidator: production secrets present and sufficient length.");
    }
}
