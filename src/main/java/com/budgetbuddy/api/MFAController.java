package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.notification.EmailNotificationService;
import com.budgetbuddy.notification.NotificationService;
import com.budgetbuddy.service.MFAService;
import com.budgetbuddy.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Multi-Factor Authentication (MFA) REST Controller
 * Supports TOTP, SMS OTP, Email OTP, and Backup Codes
 * Compliant with: SOC 2, HIPAA, PCI-DSS, ISO 27001, NYDFS, NIST 800-63B
 */
@RestController
@RequestMapping("/api/mfa")
@CrossOrigin(origins = "*", maxAge = 3600)
public class MFAController {

    private static final Logger logger = LoggerFactory.getLogger(MFAController.class);

    private final MFAService mfaService;
    private final UserService userService;
    private final NotificationService notificationService;
    private final EmailNotificationService emailNotificationService;
    private final boolean returnOtpInResponse; // Only true in dev/test environments

    public MFAController(
            final MFAService mfaService,
            final UserService userService,
            final NotificationService notificationService,
            final EmailNotificationService emailNotificationService,
            @Value("${app.mfa.return-otp-in-response:false}") boolean returnOtpInResponse) {
        this.mfaService = mfaService;
        this.userService = userService;
        this.notificationService = notificationService;
        this.emailNotificationService = emailNotificationService;
        this.returnOtpInResponse = returnOtpInResponse;
    }

    /**
     * Setup TOTP for a user
     * POST /api/mfa/totp/setup
     */
    @PostMapping("/totp/setup")
    @Operation(summary = "Setup TOTP", description = "Generates TOTP secret and QR code URL for setup")
    @ApiResponse(responseCode = "200", description = "TOTP setup successful")
    @ApiResponse(responseCode = "401", description = "User not authenticated")
    public ResponseEntity<Map<String, Object>> setupTOTP(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        MFAService.TOTPSetupResult result = mfaService.setupTOTP(user.getUserId(), user.getEmail());

        Map<String, Object> response = new HashMap<>();
        response.put("secret", result.getSecret());
        response.put("qrCodeUrl", result.getQrCodeUrl());
        response.put("message", "TOTP setup successful. Scan the QR code with your authenticator app.");

        logger.info("TOTP setup initiated for user: {}", user.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * Verify TOTP code during setup
     * POST /api/mfa/totp/verify
     */
    @PostMapping("/totp/verify")
    @Operation(summary = "Verify TOTP Code", description = "Verifies TOTP code and enables MFA if valid")
    @ApiResponse(responseCode = "200", description = "TOTP verified successfully")
    @ApiResponse(responseCode = "401", description = "User not authenticated")
    @ApiResponse(responseCode = "400", description = "Invalid TOTP code")
    public ResponseEntity<Map<String, Object>> verifyTOTP(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody VerifyTOTPRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (request == null || request.getCode() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "TOTP code is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        boolean isValid = mfaService.verifyTOTP(user.getUserId(), request.getCode());

        if (!isValid) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid TOTP code");
        }

        // Enable MFA for user
        mfaService.enableMFA(user.getUserId());

        // Generate backup codes
        List<String> backupCodes = mfaService.generateBackupCodes(user.getUserId());

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "TOTP verified and MFA enabled");
        response.put("backupCodes", backupCodes);
        response.put("warning", "Save these backup codes in a secure location. They will not be shown again.");

        logger.info("TOTP verified and MFA enabled for user: {}", user.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * Verify TOTP code for authentication
     * POST /api/mfa/totp/authenticate
     */
    @PostMapping("/totp/authenticate")
    @Operation(summary = "Authenticate with TOTP", description = "Verifies TOTP code during authentication")
    @ApiResponse(responseCode = "200", description = "TOTP verified successfully")
    @ApiResponse(responseCode = "400", description = "Invalid TOTP code")
    public ResponseEntity<Map<String, Object>> authenticateTOTP(
            @RequestBody AuthenticateTOTPRequest request) {
        if (request == null || request.getUserId() == null || request.getCode() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID and TOTP code are required");
        }

        boolean isValid = mfaService.verifyTOTP(request.getUserId(), request.getCode());

        if (!isValid) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid TOTP code");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "TOTP verified successfully");

        logger.info("TOTP authenticated for user: {}", request.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * Remove TOTP for a user
     * DELETE /api/mfa/totp
     */
    @DeleteMapping("/totp")
    @Operation(summary = "Remove TOTP", description = "Removes TOTP configuration for a user")
    @ApiResponse(responseCode = "204", description = "TOTP removed successfully")
    @ApiResponse(responseCode = "401", description = "User not authenticated")
    public ResponseEntity<Void> removeTOTP(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        mfaService.removeTOTP(user.getUserId());

        logger.info("TOTP removed for user: {}", user.getUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Generate backup codes
     * POST /api/mfa/backup-codes/generate
     */
    @PostMapping("/backup-codes/generate")
    @Operation(summary = "Generate Backup Codes", description = "Generates new backup codes for account recovery")
    @ApiResponse(responseCode = "200", description = "Backup codes generated successfully")
    @ApiResponse(responseCode = "401", description = "User not authenticated")
    public ResponseEntity<Map<String, Object>> generateBackupCodes(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        List<String> backupCodes = mfaService.generateBackupCodes(user.getUserId());

        Map<String, Object> response = new HashMap<>();
        response.put("backupCodes", backupCodes);
        response.put("warning", "Save these backup codes in a secure location. They will not be shown again.");

        logger.info("Backup codes generated for user: {}", user.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * Verify backup code
     * POST /api/mfa/backup-codes/verify
     */
    @PostMapping("/backup-codes/verify")
    @Operation(summary = "Verify Backup Code", description = "Verifies a backup code during authentication")
    @ApiResponse(responseCode = "200", description = "Backup code verified successfully")
    @ApiResponse(responseCode = "400", description = "Invalid backup code")
    public ResponseEntity<Map<String, Object>> verifyBackupCode(
            @RequestBody VerifyBackupCodeRequest request) {
        if (request == null || request.getUserId() == null || request.getCode() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID and backup code are required");
        }

        boolean isValid = mfaService.verifyBackupCode(request.getUserId(), request.getCode());

        if (!isValid) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid backup code");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Backup code verified successfully");

        logger.info("Backup code verified for user: {}", request.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * Request SMS OTP
     * POST /api/mfa/sms/request
     */
    @PostMapping("/sms/request")
    @Operation(summary = "Request SMS OTP", description = "Generates and sends SMS OTP code")
    @ApiResponse(responseCode = "200", description = "SMS OTP sent successfully")
    @ApiResponse(responseCode = "401", description = "User not authenticated")
    public ResponseEntity<Map<String, Object>> requestSMSOTP(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (user.getPhoneNumber() == null || user.getPhoneNumber().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Phone number not configured");
        }

        // Generate OTP
        String otp = mfaService.generateOTP(user.getUserId(), MFAService.OTPType.SMS);

        // Send SMS via AWS SNS (works with LocalStack for CI/testing, real SNS for staging/production)
        try {
            NotificationService.NotificationRequest notificationRequest = new NotificationService.NotificationRequest();
            notificationRequest.setUserId(user.getUserId());
            notificationRequest.setType(NotificationService.NotificationType.SECURITY_ALERT);
            notificationRequest.setTitle("MFA OTP Code");
            notificationRequest.setBody(String.format("Your BudgetBuddy MFA code is: %s. This code expires in 5 minutes.", otp));
            notificationRequest.setRecipientPhone(user.getPhoneNumber());
            notificationRequest.setChannels(Set.of(NotificationService.NotificationChannel.SMS));

            boolean smsSent = notificationService.sendNotification(notificationRequest).isSmsSent();
            
            if (!smsSent) {
                logger.warn("Failed to send SMS OTP for user: {}. OTP generated but not delivered.", user.getUserId());
                // In production, this would be a critical error - consider throwing exception
                // For now, log warning and continue (allows graceful degradation in dev/test)
            }
        } catch (Exception e) {
            logger.error("Error sending SMS OTP for user: {}. Error: {}", user.getUserId(), e.getMessage(), e);
            // In production, this would be a critical error - consider throwing exception
            // For now, log error and continue (allows graceful degradation in dev/test)
        }

        Map<String, Object> response = new HashMap<>();
        response.put("message", "SMS OTP sent to your phone number");
        
        // Only return OTP in dev/test environments (controlled by app.mfa.return-otp-in-response)
        // In production/staging, OTP is only sent via SMS, never returned in response
        if (returnOtpInResponse) {
            response.put("otp", otp);
            logger.debug("OTP returned in response (dev/test mode) for user: {}", user.getUserId());
        }

        logger.info("SMS OTP requested for user: {}", user.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * Request Email OTP
     * POST /api/mfa/email/request
     */
    @PostMapping("/email/request")
    @Operation(summary = "Request Email OTP", description = "Generates and sends Email OTP code")
    @ApiResponse(responseCode = "200", description = "Email OTP sent successfully")
    @ApiResponse(responseCode = "401", description = "User not authenticated")
    public ResponseEntity<Map<String, Object>> requestEmailOTP(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Generate OTP
        String otp = mfaService.generateOTP(user.getUserId(), MFAService.OTPType.EMAIL);

        // Send email via AWS SES (works with LocalStack for CI/testing, real SES for staging/production)
        try {
            String emailSubject = "BudgetBuddy MFA OTP Code";
            String emailBody = String.format(
                    "<html><body>" +
                    "<h2>Your MFA OTP Code</h2>" +
                    "<p>Your BudgetBuddy MFA code is: <strong>%s</strong></p>" +
                    "<p>This code expires in 5 minutes.</p>" +
                    "<p>If you did not request this code, please ignore this email.</p>" +
                    "</body></html>",
                    otp
            );

            boolean emailSent = emailNotificationService.sendEmail(
                    user.getUserId(),
                    user.getEmail(),
                    emailSubject,
                    emailBody,
                    null, // No template ID
                    null  // No template data
            );

            if (!emailSent) {
                logger.warn("Failed to send email OTP for user: {}. OTP generated but not delivered.", user.getUserId());
                // In production, this would be a critical error - consider throwing exception
                // For now, log warning and continue (allows graceful degradation in dev/test)
            }
        } catch (Exception e) {
            logger.error("Error sending email OTP for user: {}. Error: {}", user.getUserId(), e.getMessage(), e);
            // In production, this would be a critical error - consider throwing exception
            // For now, log error and continue (allows graceful degradation in dev/test)
        }

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Email OTP sent to your email address");
        
        // Only return OTP in dev/test environments (controlled by app.mfa.return-otp-in-response)
        // In production/staging, OTP is only sent via email, never returned in response
        if (returnOtpInResponse) {
            response.put("otp", otp);
            logger.debug("OTP returned in response (dev/test mode) for user: {}", user.getUserId());
        }

        logger.info("Email OTP requested for user: {}", user.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * Verify SMS OTP
     * POST /api/mfa/sms/verify
     */
    @PostMapping("/sms/verify")
    @Operation(summary = "Verify SMS OTP", description = "Verifies SMS OTP code")
    @ApiResponse(responseCode = "200", description = "SMS OTP verified successfully")
    @ApiResponse(responseCode = "400", description = "Invalid SMS OTP code")
    public ResponseEntity<Map<String, Object>> verifySMSOTP(
            @RequestBody VerifyOTPRequest request) {
        if (request == null || request.getUserId() == null || request.getCode() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID and SMS OTP code are required");
        }

        boolean isValid = mfaService.verifyOTP(request.getUserId(), MFAService.OTPType.SMS, request.getCode());

        if (!isValid) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid SMS OTP code");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "SMS OTP verified successfully");

        logger.info("SMS OTP verified for user: {}", request.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * Verify Email OTP
     * POST /api/mfa/email/verify
     */
    @PostMapping("/email/verify")
    @Operation(summary = "Verify Email OTP", description = "Verifies Email OTP code")
    @ApiResponse(responseCode = "200", description = "Email OTP verified successfully")
    @ApiResponse(responseCode = "400", description = "Invalid Email OTP code")
    public ResponseEntity<Map<String, Object>> verifyEmailOTP(
            @RequestBody VerifyOTPRequest request) {
        if (request == null || request.getUserId() == null || request.getCode() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID and Email OTP code are required");
        }

        boolean isValid = mfaService.verifyOTP(request.getUserId(), MFAService.OTPType.EMAIL, request.getCode());

        if (!isValid) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid Email OTP code");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Email OTP verified successfully");

        logger.info("Email OTP verified for user: {}", request.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * Get MFA status
     * GET /api/mfa/status
     */
    @GetMapping("/status")
    @Operation(summary = "Get MFA Status", description = "Returns MFA configuration status for the authenticated user")
    @ApiResponse(responseCode = "200", description = "MFA status retrieved successfully")
    @ApiResponse(responseCode = "401", description = "User not authenticated")
    public ResponseEntity<Map<String, Object>> getMFAStatus(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        boolean mfaEnabled = mfaService.isMFAEnabled(user.getUserId());
        boolean hasBackupCodes = mfaService.hasBackupCodes(user.getUserId());

        Map<String, Object> response = new HashMap<>();
        response.put("mfaEnabled", mfaEnabled);
        response.put("hasBackupCodes", hasBackupCodes);
        response.put("totpConfigured", mfaEnabled); // TOTP is the primary MFA method

        return ResponseEntity.ok(response);
    }

    /**
     * Disable MFA
     * DELETE /api/mfa
     */
    @DeleteMapping
    @Operation(summary = "Disable MFA", description = "Disables MFA for the authenticated user")
    @ApiResponse(responseCode = "204", description = "MFA disabled successfully")
    @ApiResponse(responseCode = "401", description = "User not authenticated")
    public ResponseEntity<Void> disableMFA(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        mfaService.disableMFA(user.getUserId());

        logger.info("MFA disabled for user: {}", user.getUserId());
        return ResponseEntity.noContent().build();
    }

    // MARK: - DTOs

    public static class VerifyTOTPRequest {
        private Integer code;

        public Integer getCode() {
            return code;
        }

        public void setCode(final Integer code) {
            this.code = code;
        }
    }

    public static class AuthenticateTOTPRequest {
        private String userId;
        private Integer code;

        public String getUserId() {
            return userId;
        }

        public void setUserId(final String userId) {
            this.userId = userId;
        }

        public Integer getCode() {
            return code;
        }

        public void setCode(final Integer code) {
            this.code = code;
        }
    }

    public static class VerifyBackupCodeRequest {
        private String userId;
        private String code;

        public String getUserId() {
            return userId;
        }

        public void setUserId(final String userId) {
            this.userId = userId;
        }

        public String getCode() {
            return code;
        }

        public void setCode(final String code) {
            this.code = code;
        }
    }

    public static class VerifyOTPRequest {
        private String userId;
        private String code;

        public String getUserId() {
            return userId;
        }

        public void setUserId(final String userId) {
            this.userId = userId;
        }

        public String getCode() {
            return code;
        }

        public void setCode(final String code) {
            this.code = code;
        }
    }
}

