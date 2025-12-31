package com.budgetbuddy.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;

class AmountValidatorTest {

    private AmountValidator validator;

    @Mock
    private ConstraintValidatorContext context;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        validator = new AmountValidator();
        validator.initialize(null);
    }

    @Test
    void testIsValid_ValidPositiveAmount_ShouldReturnTrue() {
        // Given
        Double amount = 100.50;
        
        // When
        boolean result = validator.isValid(amount, context);
        
        // Then
        assertTrue(result);
    }

    @Test
    void testIsValid_ValidSmallAmount_ShouldReturnTrue() {
        // Given
        Double amount = 0.01;
        
        // When
        boolean result = validator.isValid(amount, context);
        
        // Then
        assertTrue(result);
    }

    @Test
    void testIsValid_ValidLargeAmount_ShouldReturnTrue() {
        // Given
        Double amount = 999999999.99;
        
        // When
        boolean result = validator.isValid(amount, context);
        
        // Then
        assertTrue(result);
    }

    @Test
    void testIsValid_ZeroAmount_ShouldReturnFalse() {
        // Given
        Double amount = 0.0;
        
        // When
        boolean result = validator.isValid(amount, context);
        
        // Then
        assertFalse(result);
    }

    @Test
    void testIsValid_NegativeAmount_ShouldReturnFalse() {
        // Given
        Double amount = -100.50;
        
        // When
        boolean result = validator.isValid(amount, context);
        
        // Then
        assertFalse(result);
    }

    @Test
    void testIsValid_NullAmount_ShouldReturnFalse() {
        // When
        boolean result = validator.isValid(null, context);
        
        // Then
        assertFalse(result);
    }

    @Test
    void testIsValid_AmountExceedsMax_ShouldReturnFalse() {
        // Given
        Double amount = 1000000000.0; // Exceeds max of 999999999.99
        
        // When
        boolean result = validator.isValid(amount, context);
        
        // Then
        assertFalse(result);
    }

    @Test
    void testIsValid_AmountWithManyDecimals_ShouldRoundAndValidate() {
        // Given - Amount with more than 2 decimal places
        Double amount = 100.999; // Should be rounded to 101.00
        
        // When
        boolean result = validator.isValid(amount, context);
        
        // Then - After rounding, should be valid
        assertTrue(result);
    }

    @Test
    void testIsValid_ValidAmountWithTwoDecimals_ShouldReturnTrue() {
        // Given
        Double amount = 123.45;
        
        // When
        boolean result = validator.isValid(amount, context);
        
        // Then
        assertTrue(result);
    }

    @Test
    void testIsValid_ValidAmountInteger_ShouldReturnTrue() {
        // Given
        Double amount = 1000.0;
        
        // When
        boolean result = validator.isValid(amount, context);
        
        // Then
        assertTrue(result);
    }
}
