package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for FIDO2Service
 */
@ExtendWith(MockitoExtension.class)
class FIDO2ServiceTest {

    @Mock
    private com.budgetbuddy.repository.dynamodb.FIDO2CredentialRepository credentialRepository;
    
    @Mock
    private com.budgetbuddy.repository.dynamodb.FIDO2ChallengeRepository challengeRepository;

    private FIDO2Service fido2Service;

    @BeforeEach
    void setUp() throws Exception {
        fido2Service = new FIDO2Service(credentialRepository, challengeRepository);
        // Set required properties using reflection
        java.lang.reflect.Field rpIdField = FIDO2Service.class.getDeclaredField("rpId");
        rpIdField.setAccessible(true);
        rpIdField.set(fido2Service, "localhost");
        
        java.lang.reflect.Field rpNameField = FIDO2Service.class.getDeclaredField("rpName");
        rpNameField.setAccessible(true);
        rpNameField.set(fido2Service, "BudgetBuddy Test");
        
        java.lang.reflect.Field originField = FIDO2Service.class.getDeclaredField("origin");
        originField.setAccessible(true);
        originField.set(fido2Service, "http://localhost:8080");
        
        java.lang.reflect.Field timeoutField = FIDO2Service.class.getDeclaredField("challengeExpirationSeconds");
        timeoutField.setAccessible(true);
        timeoutField.setLong(fido2Service, 300L); // 5 minutes
    }

    @Test
    void testGenerateRegistrationChallenge_WithValidInput_ReturnsChallengeAndOptions() {
        // Given
        String userId = "test-user-id";
        String username = "test@example.com";

        // When
        FIDO2Service.RegistrationChallengeResult result = fido2Service.generateRegistrationChallenge(userId, username);

        // Then
        assertNotNull(result);
        assertNotNull(result.getOptions());
        // Challenge is inside options
        assertNotNull(result.getOptions().getChallenge());
        // ByteArray from Yubico library - just verify it exists
        assertNotNull(result.getOptions().getChallenge());
    }

    @Test
    void testGenerateRegistrationChallenge_WithNullUserId_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> {
            fido2Service.generateRegistrationChallenge(null, "test@example.com");
        });
    }

    @Test
    void testGenerateRegistrationChallenge_WithNullUsername_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> {
            fido2Service.generateRegistrationChallenge("test-user-id", null);
        });
    }

    @Test
    void testGenerateAuthenticationChallenge_WithNoPasskeys_ThrowsException() {
        // Given
        String userId = "test-user-id";

        // When/Then
        assertThrows(AppException.class, () -> {
            fido2Service.generateAuthenticationChallenge(userId);
        }, "Should throw if no passkeys registered");
    }

    @Test
    void testListPasskeys_WithNoPasskeys_ReturnsEmptyList() {
        // Given
        String userId = "test-user-id";

        // When
        List<FIDO2Service.PasskeyInfo> passkeys = fido2Service.listPasskeys(userId);

        // Then
        assertNotNull(passkeys);
        assertTrue(passkeys.isEmpty());
    }

    @Test
    void testDeletePasskey_WithNoPasskeys_ThrowsException() {
        // Given
        String userId = "test-user-id";
        String credentialId = "test-credential-id";

        // When/Then
        assertThrows(AppException.class, () -> {
            fido2Service.deletePasskey(userId, credentialId);
        }, "Should throw if passkey not found");
    }
}

