package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.repository.dynamodb.UserRepository;
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
    private UserRepository userRepository;

    private FIDO2Service fido2Service;

    @BeforeEach
    void setUp() {
        fido2Service = new FIDO2Service(userRepository);
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
        assertNotNull(result.getChallenge());
        assertNotNull(result.getOptions());
        assertNotNull(result.getChallenge().getValue());
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

