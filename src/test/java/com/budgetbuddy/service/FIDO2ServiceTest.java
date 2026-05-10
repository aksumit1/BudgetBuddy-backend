package com.budgetbuddy.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.exception.AppException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit Tests for FIDO2Service */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
class FIDO2ServiceTest {

    @Mock
    private com.budgetbuddy.repository.dynamodb.FIDO2CredentialRepository credentialRepository;

    @Mock private com.budgetbuddy.repository.dynamodb.FIDO2ChallengeRepository challengeRepository;

    private FIDO2Service fido2Service;

    @BeforeEach
    void setUp() throws Exception {
        fido2Service = new FIDO2Service(credentialRepository, challengeRepository);
        // Set required properties using reflection
        final java.lang.reflect.Field rpIdField = FIDO2Service.class.getDeclaredField("rpId");
        rpIdField.setAccessible(true);
        rpIdField.set(fido2Service, "localhost");

        final java.lang.reflect.Field rpNameField = FIDO2Service.class.getDeclaredField("rpName");
        rpNameField.setAccessible(true);
        rpNameField.set(fido2Service, "BudgetBuddy Test");

        final java.lang.reflect.Field originField = FIDO2Service.class.getDeclaredField("origin");
        originField.setAccessible(true);
        originField.set(fido2Service, "http://localhost:8080");

        final java.lang.reflect.Field timeoutField =
                FIDO2Service.class.getDeclaredField("challengeExpirationSeconds");
        timeoutField.setAccessible(true);
        timeoutField.setLong(fido2Service, 300L); // 5 minutes
    }

    @Test
    void testGenerateRegistrationChallengeWithValidInputReturnsChallengeAndOptions() {
        // Given
        final String userId = "test-user-id";
        final String username = "test@example.com";

        // When
        final FIDO2Service.RegistrationChallengeResult result =
                fido2Service.generateRegistrationChallenge(userId, username);

        // Then
        assertNotNull(result);
        assertNotNull(result.getOptions());
        // Challenge is inside options
        assertNotNull(result.getOptions().getChallenge());
        // ByteArray from Yubico library - just verify it exists
        assertNotNull(result.getOptions().getChallenge());
    }

    @Test
    void testGenerateRegistrationChallengeWithNullUserIdThrowsException() {
        // When/Then
        assertThrows(
                AppException.class,
                () -> {
                    fido2Service.generateRegistrationChallenge(null, "test@example.com");
                });
    }

    @Test
    void testGenerateRegistrationChallengeWithNullUsernameThrowsException() {
        // When/Then
        assertThrows(
                AppException.class,
                () -> {
                    fido2Service.generateRegistrationChallenge("test-user-id", null);
                });
    }

    @Test
    void testGenerateAuthenticationChallengeWithNoPasskeysThrowsException() {
        // Given
        final String userId = "test-user-id";

        // When/Then
        assertThrows(
                AppException.class,
                () -> {
                    fido2Service.generateAuthenticationChallenge(userId);
                },
                "Should throw if no passkeys registered");
    }

    @Test
    void testListPasskeysWithNoPasskeysReturnsEmptyList() {
        // Given
        final String userId = "test-user-id";

        // When
        final List<FIDO2Service.PasskeyInfo> passkeys = fido2Service.listPasskeys(userId);

        // Then
        assertNotNull(passkeys);
        assertTrue(passkeys.isEmpty());
    }

    @Test
    void testDeletePasskeyWithNoPasskeysThrowsException() {
        // Given
        final String userId = "test-user-id";
        final String credentialId = "test-credential-id";

        // When/Then
        assertThrows(
                AppException.class,
                () -> {
                    fido2Service.deletePasskey(userId, credentialId);
                },
                "Should throw if passkey not found");
    }
}
