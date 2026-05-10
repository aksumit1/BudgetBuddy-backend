package com.budgetbuddy.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class PasswordStrengthValidatorTest {

    private PasswordStrengthValidator validator;

    @Mock private ConstraintValidatorContext context;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        validator = new PasswordStrengthValidator();
        validator.initialize(null);
    }

    @Test
    void testIsValidValidPasswordShouldReturnTrue() {
        // Given
        final String password = "Password123!";

        // When
        final boolean result = validator.isValid(password, context);

        // Then
        assertTrue(result);
    }

    @Test
    void testIsValidTooShortShouldReturnFalse() {
        // Given
        final String password = "Pass1!";

        // When
        final boolean result = validator.isValid(password, context);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsValidNoUppercaseShouldReturnFalse() {
        // Given
        final String password = "password123!";

        // When
        final boolean result = validator.isValid(password, context);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsValidNoLowercaseShouldReturnFalse() {
        // Given
        final String password = "PASSWORD123!";

        // When
        final boolean result = validator.isValid(password, context);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsValidNoDigitShouldReturnFalse() {
        // Given
        final String password = "Password!";

        // When
        final boolean result = validator.isValid(password, context);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsValidNoSpecialCharacterShouldReturnFalse() {
        // Given
        final String password = "Password123";

        // When
        final boolean result = validator.isValid(password, context);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsValidNullPasswordShouldReturnFalse() {
        // When
        final boolean result = validator.isValid(null, context);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsValidEmptyPasswordShouldReturnFalse() {
        // When
        final boolean result = validator.isValid("", context);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsValidLongPasswordShouldReturnFalse() {
        // Given - Password longer than 128 characters
        final String password = "A".repeat(129) + "1!";

        // When
        final boolean result = validator.isValid(password, context);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsValidValidPasswordWithDifferentSpecialCharsShouldReturnTrue() {
        // Given
        final String[] validPasswords = {
                "Password123@",
                "Password123$",
                "Password123!",
                "Password123%",
                "Password123*",
                "Password123?",
                "Password123&"
        };

        // When/Then
        for (final String password : validPasswords) {
            assertTrue(
                    validator.isValid(password, context), "Password should be valid: " + password);
        }
    }
}
