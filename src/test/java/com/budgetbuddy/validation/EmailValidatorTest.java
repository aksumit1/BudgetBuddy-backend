package com.budgetbuddy.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;

class EmailValidatorTest {

    private EmailValidator validator;

    @Mock
    private ConstraintValidatorContext context;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        validator = new EmailValidator();
        validator.initialize(null);
    }

    @Test
    void testIsValid_ValidEmail_ShouldReturnTrue() {
        // Given
        String email = "test@example.com";
        
        // When
        boolean result = validator.isValid(email, context);
        
        // Then
        assertTrue(result);
    }

    @Test
    void testIsValid_ValidEmailWithSubdomain_ShouldReturnTrue() {
        // Given
        String email = "user@mail.example.com";
        
        // When
        boolean result = validator.isValid(email, context);
        
        // Then
        assertTrue(result);
    }

    @Test
    void testIsValid_ValidEmailWithPlus_ShouldReturnTrue() {
        // Given
        String email = "user+tag@example.com";
        
        // When
        boolean result = validator.isValid(email, context);
        
        // Then
        assertTrue(result);
    }

    @Test
    void testIsValid_ValidEmailWithUnderscore_ShouldReturnTrue() {
        // Given
        String email = "user_name@example.com";
        
        // When
        boolean result = validator.isValid(email, context);
        
        // Then
        assertTrue(result);
    }

    @Test
    void testIsValid_InvalidEmailNoAt_ShouldReturnFalse() {
        // Given
        String email = "invalidemail.com";
        
        // When
        boolean result = validator.isValid(email, context);
        
        // Then
        assertFalse(result);
    }

    @Test
    void testIsValid_InvalidEmailNoDomain_ShouldReturnFalse() {
        // Given
        String email = "user@";
        
        // When
        boolean result = validator.isValid(email, context);
        
        // Then
        assertFalse(result);
    }

    @Test
    void testIsValid_InvalidEmailNoTld_ShouldReturnFalse() {
        // Given
        String email = "user@example";
        
        // When
        boolean result = validator.isValid(email, context);
        
        // Then
        assertFalse(result);
    }

    @Test
    void testIsValid_NullEmail_ShouldReturnFalse() {
        // When
        boolean result = validator.isValid(null, context);
        
        // Then
        assertFalse(result);
    }

    @Test
    void testIsValid_EmptyEmail_ShouldReturnFalse() {
        // When
        boolean result = validator.isValid("", context);
        
        // Then
        assertFalse(result);
    }

    @Test
    void testIsValid_EmailTooLong_ShouldReturnFalse() {
        // Given - Email longer than 254 characters
        String longLocalPart = "a".repeat(250);
        String email = longLocalPart + "@example.com";
        
        // When
        boolean result = validator.isValid(email, context);
        
        // Then
        assertFalse(result);
    }

    @Test
    void testIsValid_ValidEmailAtMaxLength_ShouldReturnTrue() {
        // Given - Email at exactly 254 characters (RFC 5321 limit)
        String localPart = "a".repeat(240);
        String email = localPart + "@example.com"; // Should be <= 254
        
        // When
        boolean result = validator.isValid(email, context);
        
        // Then - Should pass length check and validation
        assertTrue(result);
    }
}
