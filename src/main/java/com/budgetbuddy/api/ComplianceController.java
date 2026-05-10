package com.budgetbuddy.api;

import com.budgetbuddy.compliance.gdpr.GDPRComplianceService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compliance REST Controller Provides endpoints for GDPR and DMA compliance
 *
 * <p>Note: DMA-specific endpoints are now in DMAController This controller maintains GDPR endpoints
 * for backward compatibility
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
// PMD's DataClass fires on Request/Response/Config DTOs by design —
// they're intentionally data-only; behaviour belongs in the controller/service.
@SuppressWarnings({"PMD.DataClass", "PMD.OnlyOneReturn"})
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@RestController
@RequestMapping("/api/compliance")
public class ComplianceController {

    private static final String USER_NOT_FOUND_1 = "User not found";

    private final GDPRComplianceService gdprComplianceService;
    private final com.budgetbuddy.service.UserService userService;

    public ComplianceController(
            final GDPRComplianceService gdprComplianceService,
            final com.budgetbuddy.service.UserService userService) {
        this.gdprComplianceService = gdprComplianceService;
        this.userService = userService;
    }

    /** GDPR Article 15: Right to access Export all user data */
    @GetMapping("/gdpr/export")
    public ResponseEntity<GDPRComplianceService.GDPRDataExport> exportData(
            @AuthenticationPrincipal final UserDetails userDetails) {
        final com.budgetbuddy.model.dynamodb.UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        final GDPRComplianceService.GDPRDataExport export =
                gdprComplianceService.exportUserData(user.getUserId());
        return ResponseEntity.ok(export);
    }

    /** GDPR Article 20: Right to data portability Export data in machine-readable format */
    @GetMapping(value = "/gdpr/export/portable", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> exportDataPortable(
            @AuthenticationPrincipal final UserDetails userDetails) {
        final com.budgetbuddy.model.dynamodb.UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        final String json = gdprComplianceService.exportDataPortable(user.getUserId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=user-data.json")
                .body(json);
    }

    /** GDPR Article 17: Right to erasure / Right to be forgotten Delete all user data */
    @DeleteMapping("/gdpr/delete")
    public ResponseEntity<Void> deleteData(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam(required = false, defaultValue = "false") final boolean confirm) {
        if (!confirm) {
            return ResponseEntity.badRequest().build();
        }

        final com.budgetbuddy.model.dynamodb.UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        gdprComplianceService.deleteUserData(user.getUserId());
        return ResponseEntity.noContent().build();
    }

    /** GDPR Article 16: Right to rectification Update user data */
    @PutMapping("/gdpr/update")
    public ResponseEntity<Void> updateData(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestBody final UpdateDataRequest request) {
        final com.budgetbuddy.model.dynamodb.UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        final com.budgetbuddy.model.dynamodb.UserTable updatedData =
                new com.budgetbuddy.model.dynamodb.UserTable();
        updatedData.setFirstName(request.getFirstName());
        updatedData.setLastName(request.getLastName());
        updatedData.setEmail(request.getEmail());
        updatedData.setPhoneNumber(request.getPhoneNumber());

        gdprComplianceService.updateUserData(user.getUserId(), updatedData);
        return ResponseEntity.ok().build();
    }

    public static class UpdateDataRequest {
        private String firstName;
        private String lastName;
        private String email;
        private String phoneNumber;

        // Getters and setters
        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(final String firstName) {
            this.firstName = firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public void setLastName(final String lastName) {
            this.lastName = lastName;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(final String email) {
            this.email = email;
        }

        public String getPhoneNumber() {
            return phoneNumber;
        }

        public void setPhoneNumber(final String phoneNumber) {
            this.phoneNumber = phoneNumber;
        }
    }
}
