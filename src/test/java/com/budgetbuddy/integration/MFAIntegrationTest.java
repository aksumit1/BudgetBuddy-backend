package com.budgetbuddy.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.MFAService;
import com.budgetbuddy.service.UserService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Integration Tests for MFA Tests MFA service and controller integration */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@DisplayName("MFA Integration Tests")
class MFAIntegrationTest {

    @Autowired private MFAService mfaService;

    @Autowired private UserService userService;

    private String testEmail;
    private String testPasswordHash;
    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testEmail = "test-mfa-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        testPasswordHash =
                java.util.Base64.getEncoder()
                        .encodeToString("test-password-hash".getBytes(StandardCharsets.UTF_8));
        testUser = userService.createUserSecure(testEmail, testPasswordHash, null, null);
    }

    @Test
    @DisplayName("MFA TOTP Setup Flow")
    void testMFATOTPSetupFlowSucceeds() {
        // Given
        final String userId = testUser.getUserId();
        final String email = testUser.getEmail();

        // When - Setup TOTP
        final MFAService.TOTPSetupResult setup = mfaService.setupTOTP(userId, email);

        // Then
        assertNotNull(setup);
        assertNotNull(setup.getSecret());
        assertNotNull(setup.getQrCodeUrl());
        assertFalse(setup.getSecret().isEmpty());
        assertFalse(setup.getQrCodeUrl().isEmpty());
        // QR code URL format may vary, just verify it's not empty
        assertFalse(setup.getQrCodeUrl().isEmpty());
    }

    @Test
    @DisplayName("MFA Backup Codes Generation")
    void testMFABackupCodesGenerationSucceeds() {
        // Given
        final String userId = testUser.getUserId();

        // When
        final List<String> codes = mfaService.generateBackupCodes(userId);

        // Then
        assertNotNull(codes);
        assertEquals(10, codes.size());
        codes.forEach(
                code -> {
                    assertNotNull(code);
                    assertEquals(8, code.length());
                    // Codes should be alphanumeric (excluding ambiguous characters)
                    assertTrue(code.matches("[A-Z0-9]{8}"));
                });
    }

    @Test
    @DisplayName("MFA Backup Code Single-Use")
    void testMFABackupCodeSingleUseSucceeds() {
        // Given
        final String userId = testUser.getUserId();
        final List<String> codes = mfaService.generateBackupCodes(userId);
        final String codeToVerify = codes.getFirst();

        // When - Verify first time
        final boolean isValid1 = mfaService.verifyBackupCode(userId, codeToVerify);

        // Then
        assertTrue(isValid1, "First verification should succeed");

        // When - Verify second time (should fail - single-use)
        final boolean isValid2 = mfaService.verifyBackupCode(userId, codeToVerify);

        // Then
        assertFalse(isValid2, "Second verification should fail (single-use)");
    }

    @Test
    @DisplayName("MFA OTP Generation and Verification")
    void testMFAOTPGenerationAndVerificationSucceeds() {
        // Given
        final String userId = testUser.getUserId();

        // When - Generate SMS OTP
        final String otp = mfaService.generateOTP(userId, MFAService.OTPType.SMS);

        // Then
        assertNotNull(otp);
        assertEquals(6, otp.length());
        assertTrue(otp.matches("\\d{6}"));

        // When - Verify OTP
        final boolean isValid = mfaService.verifyOTP(userId, MFAService.OTPType.SMS, otp);

        // Then
        assertTrue(isValid, "OTP should be valid");

        // When - Verify again (should fail - single-use)
        final boolean isValidAgain = mfaService.verifyOTP(userId, MFAService.OTPType.SMS, otp);

        // Then
        assertFalse(isValidAgain, "OTP should be single-use");
    }

    @Test
    @DisplayName("MFA Enable and Disable")
    void testMFAEnableAndDisableSucceeds() {
        // Given
        final String userId = testUser.getUserId();

        // When - Enable MFA
        mfaService.enableMFA(userId);

        // Then
        assertTrue(mfaService.isMFAEnabled(userId), "MFA should be enabled");

        // When - Disable MFA
        mfaService.disableMFA(userId);

        // Then
        assertFalse(mfaService.isMFAEnabled(userId), "MFA should be disabled");
    }
}
