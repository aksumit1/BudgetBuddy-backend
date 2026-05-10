package com.budgetbuddy.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class EmailValidatorTest {

    private EmailValidator validator;

    @Mock private ConstraintValidatorContext context;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        validator = new EmailValidator();
        validator.initialize(null);
    }

    @Test
    void testIsValidValidEmailShouldReturnTrue() {
        // Given
        final String email = "test@example.com";

        // When
        final boolean result = validator.isValid(email, context);

        // Then
        assertTrue(result);
    }

    @Test
    void testIsValidValidEmailWithSubdomainShouldReturnTrue() {
        // Given
        final String email = "user@mail.example.com";

        // When
        final boolean result = validator.isValid(email, context);

        // Then
        assertTrue(result);
    }

    @Test
    void testIsValidValidEmailWithPlusShouldReturnTrue() {
        // Given
        final String email = "user+tag@example.com";

        // When
        final boolean result = validator.isValid(email, context);

        // Then
        assertTrue(result);
    }

    @Test
    void testIsValidValidEmailWithUnderscoreShouldReturnTrue() {
        // Given
        final String email = "user_name@example.com";

        // When
        final boolean result = validator.isValid(email, context);

        // Then
        assertTrue(result);
    }

    @Test
    void testIsValidInvalidEmailNoAtShouldReturnFalse() {
        // Given
        final String email = "invalidemail.com";

        // When
        final boolean result = validator.isValid(email, context);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsValidInvalidEmailNoDomainShouldReturnFalse() {
        // Given
        final String email = "user@";

        // When
        final boolean result = validator.isValid(email, context);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsValidInvalidEmailNoTldShouldReturnFalse() {
        // Given
        final String email = "user@example";

        // When
        final boolean result = validator.isValid(email, context);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsValidNullEmailShouldReturnFalse() {
        // When
        final boolean result = validator.isValid(null, context);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsValidEmptyEmailShouldReturnFalse() {
        // When
        final boolean result = validator.isValid("", context);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsValidEmailTooLongShouldReturnFalse() {
        // Given - Email longer than 254 characters
        final String longLocalPart = "a".repeat(250);
        final String email = longLocalPart + "@example.com";

        // When
        final boolean result = validator.isValid(email, context);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsValidValidEmailAtMaxLengthShouldReturnTrue() {
        // Given - Email at exactly 254 characters (RFC 5321 limit)
        final String localPart = "a".repeat(240);
        final String email = localPart + "@example.com"; // Should be <= 254

        // When
        final boolean result = validator.isValid(email, context);

        // Then - Should pass length check and validation
        assertTrue(result);
    }
}
