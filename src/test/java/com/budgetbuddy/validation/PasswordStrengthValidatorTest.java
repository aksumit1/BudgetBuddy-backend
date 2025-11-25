package com.budgetbuddy.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for PasswordStrengthValidator
 */
@ExtendWith(MockitoExtension.class)
class PasswordStrengthValidatorTest {

    @Mock
    private ConstraintValidatorContext constraintValidatorContext;

    @Mock
    private ConstraintValidatorContext.ConstraintViolationBuilder constraintViolationBuilder;

    @InjectMocks
    private PasswordStrengthValidator passwordStrengthValidator;

    @BeforeEach
    void setUp() {
        when(constraintValidatorContext.buildConstraintViolationWithTemplate(anyString()))
                .thenReturn(constraintViolationBuilder);
        when(constraintViolationBuilder.addConstraintViolation())
                .thenReturn(constraintValidatorContext);
    }

    @Test
    void testIsValid_WithStrongPassword_ReturnsTrue() {
        // Given
        String strongPassword = "StrongP@ssw0rd123!";

        // When
        boolean isValid = passwordStrengthValidator.isValid(strongPassword, constraintValidatorContext);

        // Then
        assertTrue(isValid);
    }

    @Test
    void testIsValid_WithWeakPassword_ReturnsFalse() {
        // Given
        String weakPassword = "12345678"; // Too short, no letters

        // When
        boolean isValid = passwordStrengthValidator.isValid(weakPassword, constraintValidatorContext);

        // Then
        assertFalse(isValid);
    }

    @Test
    void testIsValid_WithNullPassword_ReturnsFalse() {
        // When
        boolean isValid = passwordStrengthValidator.isValid(null, constraintValidatorContext);

        // Then
        assertFalse(isValid);
    }

    @Test
    void testIsValid_WithShortPassword_ReturnsFalse() {
        // Given
        String shortPassword = "Short1!";

        // When
        boolean isValid = passwordStrengthValidator.isValid(shortPassword, constraintValidatorContext);

        // Then
        assertFalse(isValid);
    }
}

