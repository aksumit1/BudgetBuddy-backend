package com.budgetbuddy.api;

import com.budgetbuddy.compliance.dma.DMAComplianceService;
import com.budgetbuddy.compliance.gdpr.GDPRComplianceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticatedPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * Compliance REST Controller
 * Provides endpoints for GDPR and DMA compliance
 */
@RestController
@RequestMapping("/api/compliance")
public class ComplianceController {

    @Autowired
    private GDPRComplianceService gdprComplianceService;

    @Autowired
    private DMAComplianceService dmaComplianceService;

    @Autowired
    private com.budgetbuddy.service.UserService userService;

    /**
     * GDPR Article 15: Right to access
     * Export all user data
     */
    @GetMapping("/gdpr/export")
    public ResponseEntity<GDPRComplianceService.GDPRDataExport> exportData(
            @AuthenticatedPrincipal UserDetails userDetails) {
        com.budgetbuddy.model.dynamodb.UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        GDPRComplianceService.GDPRDataExport export = gdprComplianceService.exportUserData(user.getUserId());
        return ResponseEntity.ok(export);
    }

    /**
     * GDPR Article 20: Right to data portability
     * Export data in machine-readable format
     */
    @GetMapping(value = "/gdpr/export/portable", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> exportDataPortable(
            @AuthenticatedPrincipal UserDetails userDetails) {
        com.budgetbuddy.model.dynamodb.UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        String json = gdprComplianceService.exportDataPortable(user.getUserId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=user-data.json")
                .body(json);
    }

    /**
     * GDPR Article 17: Right to erasure / Right to be forgotten
     * Delete all user data
     */
    @DeleteMapping("/gdpr/delete")
    public ResponseEntity<Void> deleteData(
            @AuthenticatedPrincipal UserDetails userDetails,
            @RequestParam(required = false, defaultValue = "false") boolean confirm) {
        if (!confirm) {
            return ResponseEntity.badRequest().build();
        }

        com.budgetbuddy.model.dynamodb.UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        gdprComplianceService.deleteUserData(user.getUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * GDPR Article 16: Right to rectification
     * Update user data
     */
    @PutMapping("/gdpr/update")
    public ResponseEntity<Void> updateData(
            @AuthenticatedPrincipal UserDetails userDetails,
            @RequestBody UpdateDataRequest request) {
        com.budgetbuddy.model.dynamodb.UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        com.budgetbuddy.model.dynamodb.UserTable updatedData = new com.budgetbuddy.model.dynamodb.UserTable();
        updatedData.setFirstName(request.getFirstName());
        updatedData.setLastName(request.getLastName());
        updatedData.setEmail(request.getEmail());
        updatedData.setPhoneNumber(request.getPhoneNumber());

        gdprComplianceService.updateUserData(user.getUserId(), updatedData);
        return ResponseEntity.ok().build();
    }

    /**
     * DMA Article 6: Data Portability
     * Export data in standardized format
     */
    @GetMapping(value = "/dma/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> exportDataDMA(
            @AuthenticatedPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "JSON") String format) {
        com.budgetbuddy.model.dynamodb.UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        String data = dmaComplianceService.exportDataPortable(user.getUserId(), format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=user-data." + format.toLowerCase())
                .body(data);
    }

    public static class UpdateDataRequest {
        private String firstName;
        private String lastName;
        private String email;
        private String phoneNumber;

        // Getters and setters
        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }
        public String getLastName() { return lastName; }
        public void setLastName(String lastName) { this.lastName = lastName; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPhoneNumber() { return phoneNumber; }
        public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    }
}
