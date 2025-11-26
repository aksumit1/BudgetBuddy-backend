package com.budgetbuddy.config;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for SecurityConfig
 * 
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class SecurityConfigTest {

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SecurityConfig securityConfig;

    @Test
    void testPasswordEncoder_EncodesPassword() {
        // Given
        String rawPassword = "TestPassword123!";

        // When
        String encoded = passwordEncoder.encode(rawPassword);

        // Then
        assertNotNull(encoded);
        assertNotEquals(rawPassword, encoded);
    }

    @Test
    void testPasswordEncoder_MatchesPassword() {
        // Given
        String rawPassword = "TestPassword123!";
        String encoded = passwordEncoder.encode(rawPassword);

        // When
        boolean matches = passwordEncoder.matches(rawPassword, encoded);

        // Then
        assertTrue(matches);
    }

    @Test
    void testPasswordEncoder_DoesNotMatchWrongPassword() {
        // Given
        String rawPassword = "TestPassword123!";
        String wrongPassword = "WrongPassword123!";
        String encoded = passwordEncoder.encode(rawPassword);

        // When
        boolean matches = passwordEncoder.matches(wrongPassword, encoded);

        // Then
        assertFalse(matches);
    }
}

