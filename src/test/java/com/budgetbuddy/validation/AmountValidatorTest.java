package com.budgetbuddy.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class AmountValidatorTest {

    private AmountValidator validator;

    @Mock private ConstraintValidatorContext context;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        validator = new AmountValidator();
        validator.initialize(null);
    }

    @Test
    void testIsValidValidPositiveAmountShouldReturnTrue() {
        // Given
        final Double amount = 100.50;

        // When
        final boolean result = validator.isValid(amount, context);

        // Then
        assertTrue(result);
    }

    @Test
    void testIsValidValidSmallAmountShouldReturnTrue() {
        // Given
        final Double amount = 0.01;

        // When
        final boolean result = validator.isValid(amount, context);

        // Then
        assertTrue(result);
    }

    @Test
    void testIsValidValidLargeAmountShouldReturnTrue() {
        // Given
        final Double amount = 999999999.99;

        // When
        final boolean result = validator.isValid(amount, context);

        // Then
        assertTrue(result);
    }

    @Test
    void testIsValidZeroAmountShouldReturnFalse() {
        // Given
        final Double amount = 0.0;

        // When
        final boolean result = validator.isValid(amount, context);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsValidNegativeAmountShouldReturnFalse() {
        // Given
        final Double amount = -100.50;

        // When
        final boolean result = validator.isValid(amount, context);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsValidNullAmountShouldReturnFalse() {
        // When
        final boolean result = validator.isValid(null, context);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsValidAmountExceedsMaxShouldReturnFalse() {
        // Given
        final Double amount = 1000000000.0; // Exceeds max of 999999999.99

        // When
        final boolean result = validator.isValid(amount, context);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsValidAmountWithManyDecimalsShouldRoundAndValidate() {
        // Given - Amount with more than 2 decimal places
        final Double amount = 100.999; // Should be rounded to 101.00

        // When
        final boolean result = validator.isValid(amount, context);

        // Then - After rounding, should be valid
        assertTrue(result);
    }

    @Test
    void testIsValidValidAmountWithTwoDecimalsShouldReturnTrue() {
        // Given
        final Double amount = 123.45;

        // When
        final boolean result = validator.isValid(amount, context);

        // Then
        assertTrue(result);
    }

    @Test
    void testIsValidValidAmountIntegerShouldReturnTrue() {
        // Given
        final Double amount = 1000.0;

        // When
        final boolean result = validator.isValid(amount, context);

        // Then
        assertTrue(result);
    }
}
