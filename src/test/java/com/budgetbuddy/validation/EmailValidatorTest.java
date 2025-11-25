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
 * Unit Tests for EmailValidator
 */
@ExtendWith(MockitoExtension.class)
class EmailValidatorTest {

    @Mock
    private ConstraintValidatorContext constraintValidatorContext;

    @Mock
    private ConstraintValidatorContext.ConstraintViolationBuilder constraintViolationBuilder;

    @InjectMocks
    private EmailValidator emailValidator;

    @BeforeEach
    void setUp() {
        when(constraintValidatorContext.buildConstraintViolationWithTemplate(anyString()))
                .thenReturn(constraintViolationBuilder);
        when(constraintViolationBuilder.addConstraintViolation())
                .thenReturn(constraintValidatorContext);
    }

    @Test
    void testIsValid_WithValidEmail_ReturnsTrue() {
        // Given
        String validEmail = "test@example.com";

        // When
        boolean isValid = emailValidator.isValid(validEmail, constraintValidatorContext);

        // Then
        assertTrue(isValid);
    }

    @Test
    void testIsValid_WithInvalidEmail_ReturnsFalse() {
        // Given
        String invalidEmail = "not-an-email";

        // When
        boolean isValid = emailValidator.isValid(invalidEmail, constraintValidatorContext);

        // Then
        assertFalse(isValid);
    }

    @Test
    void testIsValid_WithNullEmail_ReturnsFalse() {
        // When
        boolean isValid = emailValidator.isValid(null, constraintValidatorContext);

        // Then
        assertFalse(isValid);
    }

    @Test
    void testIsValid_WithEmptyEmail_ReturnsFalse() {
        // When
        boolean isValid = emailValidator.isValid("", constraintValidatorContext);

        // Then
        assertFalse(isValid);
    }
}

