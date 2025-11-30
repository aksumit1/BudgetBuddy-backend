package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.notification.EmailNotificationService;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for password reset with email verification
 * Uses 6-digit codes sent via email
 */
@Service
public class PasswordResetService {

    private static final Logger logger = LoggerFactory.getLogger(PasswordResetService.class);
    private static final int CODE_LENGTH = 6;
    private static final int CODE_EXPIRY_MINUTES = 15; // Codes expire after 15 minutes
    private static final int MAX_ATTEMPTS = 3; // Max verification attempts per code

    private final UserRepository userRepository;
    private final EmailNotificationService emailService;
    private final SecureRandom random = new SecureRandom();

    // In-memory storage for reset codes (in production, use Redis or DynamoDB)
    // Format: email -> ResetCodeInfo
    private final Map<String, ResetCodeInfo> resetCodes = new ConcurrentHashMap<>();

    @Value("${app.notifications.email.from:noreply@budgetbuddy.com}")
    private String fromEmail;

    public PasswordResetService(final UserRepository userRepository, final EmailNotificationService emailService) {
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    /**
     * Request password reset - generates and sends 6-digit code via email
     */
    public void requestPasswordReset(final String email) {
        if (email == null || email.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Email is required");
        }

        // Check if user exists
        UserTable user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Generate 6-digit code
        String code = generateCode();
        Instant expiresAt = Instant.now().plusSeconds(CODE_EXPIRY_MINUTES * 60);

        // Store code info
        resetCodes.put(email.toLowerCase(), new ResetCodeInfo(code, expiresAt, 0));

        // Send email with code
        String subject = "BudgetBuddy Password Reset Code";
        String body = String.format(
                "<html><body>" +
                "<h2>Password Reset Request</h2>" +
                "<p>You requested to reset your password. Use the following code to verify your identity:</p>" +
                "<h1 style='color: #007AFF; font-size: 32px; letter-spacing: 4px;'>%s</h1>" +
                "<p>This code will expire in %d minutes.</p>" +
                "<p>If you didn't request this, please ignore this email.</p>" +
                "</body></html>",
                code, CODE_EXPIRY_MINUTES
        );

        try {
            // Get userId safely - use email if userId is null
            String userId = (user.getUserId() != null && !user.getUserId().isEmpty()) 
                    ? user.getUserId() 
                    : email;

            boolean emailSent = emailService.sendEmail(
                    userId,
                    email,
                    subject,
                    body,
                    null,
                    Map.of("code", code, "expiresIn", CODE_EXPIRY_MINUTES)
            );

            if (!emailSent) {
                logger.error("Failed to send password reset email to: {}. Email service returned false.", email);
                throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to send verification email. Please try again later.");
            }

            logger.info("Password reset code sent successfully to: {}", email);
        } catch (AppException e) {
            // Re-throw AppException as-is
            throw e;
        } catch (Exception e) {
            logger.error("Exception while sending password reset email to: {}", email, e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, 
                    "Failed to send verification email. Please try again later.");
        }
    }

    /**
     * Verify reset code
     */
    public void verifyResetCode(final String email, final String code) {
        if (email == null || email.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Email is required");
        }
        if (code == null || code.isEmpty() || code.length() != CODE_LENGTH) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Invalid verification code format");
        }

        String emailKey = email.toLowerCase();
        ResetCodeInfo codeInfo = resetCodes.get(emailKey);

        if (codeInfo == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "No reset code found for this email. Please request a new code.");
        }

        // Check if code expired
        if (Instant.now().isAfter(codeInfo.getExpiresAt())) {
            resetCodes.remove(emailKey);
            throw new AppException(ErrorCode.INVALID_INPUT, "Verification code has expired. Please request a new code.");
        }

        // Check max attempts
        if (codeInfo.getAttempts() >= MAX_ATTEMPTS) {
            resetCodes.remove(emailKey);
            throw new AppException(ErrorCode.INVALID_INPUT, "Too many failed attempts. Please request a new code.");
        }

        // Verify code
        if (!codeInfo.getCode().equals(code)) {
            codeInfo.incrementAttempts();
            throw new AppException(ErrorCode.INVALID_INPUT, "Invalid verification code. Attempts remaining: " + (MAX_ATTEMPTS - codeInfo.getAttempts()));
        }

        // Code verified - mark as verified (don't remove yet, need it for password reset)
        codeInfo.setVerified(true);
        logger.info("Password reset code verified for: {}", email);
    }

    /**
     * Reset password with verified code
     * BREAKING CHANGE: Client salt removed - Zero Trust architecture
     */
    public void resetPassword(final String email, final String code, final String passwordHash) {
        if (email == null || email.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Email is required");
        }
        if (code == null || code.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Verification code is required");
        }
        if (passwordHash == null || passwordHash.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Password hash is required");
        }

        // Verify code again (double-check)
        String emailKey = email.toLowerCase();
        ResetCodeInfo codeInfo = resetCodes.get(emailKey);

        if (codeInfo == null || !codeInfo.isVerified() || !codeInfo.getCode().equals(code)) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Invalid or unverified code. Please start the reset process again.");
        }

        // Verify user exists (code already verified above)
        userRepository.findByEmail(email)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Code is verified and user exists - password reset will be handled by AuthController
        // Remove code after successful reset
        resetCodes.remove(emailKey);
        logger.info("Password reset code validated for: {}", email);
    }

    /**
     * Generate 6-digit code
     */
    private String generateCode() {
        int code = 100000 + random.nextInt(900000); // 100000 to 999999
        return String.valueOf(code);
    }

    /**
     * Reset code information
     */
    private static class ResetCodeInfo {
        private final String code;
        private final Instant expiresAt;
        private int attempts;
        private boolean verified;

        public ResetCodeInfo(final String code, final Instant expiresAt, final int attempts) {
            this.code = code;
            this.expiresAt = expiresAt;
            this.attempts = attempts;
            this.verified = false;
        }

        public String getCode() {
            return code;
        }

        public Instant getExpiresAt() {
            return expiresAt;
        }

        public int getAttempts() {
            return attempts;
        }

        public void incrementAttempts() {
            this.attempts++;
        }

        public boolean isVerified() {
            return verified;
        }

        public void setVerified(final boolean verified) {
            this.verified = verified;
        }
    }
}

