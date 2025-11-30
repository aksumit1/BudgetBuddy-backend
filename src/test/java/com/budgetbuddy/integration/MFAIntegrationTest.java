package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.api.MFAController;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.MFAService;
import com.budgetbuddy.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for MFA
 * Tests MFA service and controller integration
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@DisplayName("MFA Integration Tests")
class MFAIntegrationTest {

    @Autowired
    private MFAService mfaService;

    @Autowired
    private UserService userService;

    private String testEmail;
    private String testPasswordHash;
    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testEmail = "test-mfa-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        testPasswordHash = java.util.Base64.getEncoder().encodeToString("test-password-hash".getBytes());
        testUser = userService.createUserSecure(testEmail, testPasswordHash, null, null);
    }

    @Test
    @DisplayName("MFA TOTP Setup Flow")
    void testMFA_TOTPSetupFlow_Succeeds() {
        // Given
        String userId = testUser.getUserId();
        String email = testUser.getEmail();

        // When - Setup TOTP
        MFAService.TOTPSetupResult setup = mfaService.setupTOTP(userId, email);

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
    void testMFA_BackupCodesGeneration_Succeeds() {
        // Given
        String userId = testUser.getUserId();

        // When
        List<String> codes = mfaService.generateBackupCodes(userId);

        // Then
        assertNotNull(codes);
        assertEquals(10, codes.size());
        codes.forEach(code -> {
            assertNotNull(code);
            assertEquals(8, code.length());
            // Codes should be alphanumeric (excluding ambiguous characters)
            assertTrue(code.matches("[A-Z0-9]{8}"));
        });
    }

    @Test
    @DisplayName("MFA Backup Code Single-Use")
    void testMFA_BackupCodeSingleUse_Succeeds() {
        // Given
        String userId = testUser.getUserId();
        List<String> codes = mfaService.generateBackupCodes(userId);
        String codeToVerify = codes.get(0);

        // When - Verify first time
        boolean isValid1 = mfaService.verifyBackupCode(userId, codeToVerify);

        // Then
        assertTrue(isValid1, "First verification should succeed");

        // When - Verify second time (should fail - single-use)
        boolean isValid2 = mfaService.verifyBackupCode(userId, codeToVerify);

        // Then
        assertFalse(isValid2, "Second verification should fail (single-use)");
    }

    @Test
    @DisplayName("MFA OTP Generation and Verification")
    void testMFA_OTPGenerationAndVerification_Succeeds() {
        // Given
        String userId = testUser.getUserId();

        // When - Generate SMS OTP
        String otp = mfaService.generateOTP(userId, MFAService.OTPType.SMS);

        // Then
        assertNotNull(otp);
        assertEquals(6, otp.length());
        assertTrue(otp.matches("\\d{6}"));

        // When - Verify OTP
        boolean isValid = mfaService.verifyOTP(userId, MFAService.OTPType.SMS, otp);

        // Then
        assertTrue(isValid, "OTP should be valid");

        // When - Verify again (should fail - single-use)
        boolean isValidAgain = mfaService.verifyOTP(userId, MFAService.OTPType.SMS, otp);

        // Then
        assertFalse(isValidAgain, "OTP should be single-use");
    }

    @Test
    @DisplayName("MFA Enable and Disable")
    void testMFA_EnableAndDisable_Succeeds() {
        // Given
        String userId = testUser.getUserId();

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

