package com.budgetbuddy.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;

class PasswordStrengthValidatorTest {

    private PasswordStrengthValidator validator;

    @Mock
    private ConstraintValidatorContext context;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        validator = new PasswordStrengthValidator();
        validator.initialize(null);
    }

    @Test
    void testIsValid_ValidPassword_ShouldReturnTrue() {
        // Given
        String password = "Password123!";
        
        // When
        boolean result = validator.isValid(password, context);
        
        // Then
        assertTrue(result);
    }

    @Test
    void testIsValid_TooShort_ShouldReturnFalse() {
        // Given
        String password = "Pass1!";
        
        // When
        boolean result = validator.isValid(password, context);
        
        // Then
        assertFalse(result);
    }

    @Test
    void testIsValid_NoUppercase_ShouldReturnFalse() {
        // Given
        String password = "password123!";
        
        // When
        boolean result = validator.isValid(password, context);
        
        // Then
        assertFalse(result);
    }

    @Test
    void testIsValid_NoLowercase_ShouldReturnFalse() {
        // Given
        String password = "PASSWORD123!";
        
        // When
        boolean result = validator.isValid(password, context);
        
        // Then
        assertFalse(result);
    }

    @Test
    void testIsValid_NoDigit_ShouldReturnFalse() {
        // Given
        String password = "Password!";
        
        // When
        boolean result = validator.isValid(password, context);
        
        // Then
        assertFalse(result);
    }

    @Test
    void testIsValid_NoSpecialCharacter_ShouldReturnFalse() {
        // Given
        String password = "Password123";
        
        // When
        boolean result = validator.isValid(password, context);
        
        // Then
        assertFalse(result);
    }

    @Test
    void testIsValid_NullPassword_ShouldReturnFalse() {
        // When
        boolean result = validator.isValid(null, context);
        
        // Then
        assertFalse(result);
    }

    @Test
    void testIsValid_EmptyPassword_ShouldReturnFalse() {
        // When
        boolean result = validator.isValid("", context);
        
        // Then
        assertFalse(result);
    }

    @Test
    void testIsValid_LongPassword_ShouldReturnFalse() {
        // Given - Password longer than 128 characters
        String password = "A".repeat(129) + "1!";
        
        // When
        boolean result = validator.isValid(password, context);
        
        // Then
        assertFalse(result);
    }

    @Test
    void testIsValid_ValidPasswordWithDifferentSpecialChars_ShouldReturnTrue() {
        // Given
        String[] validPasswords = {
            "Password123@",
            "Password123$",
            "Password123!",
            "Password123%",
            "Password123*",
            "Password123?",
            "Password123&"
        };
        
        // When/Then
        for (String password : validPasswords) {
            assertTrue(validator.isValid(password, context), 
                    "Password should be valid: " + password);
        }
    }
}
