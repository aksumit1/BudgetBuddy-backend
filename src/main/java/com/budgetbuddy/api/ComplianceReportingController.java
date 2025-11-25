package com.budgetbuddy.api;

import com.budgetbuddy.compliance.financial.FinancialComplianceService;
import com.budgetbuddy.compliance.hipaa.HIPAAComplianceService;
import com.budgetbuddy.compliance.iso27001.ISO27001ComplianceService;
import com.budgetbuddy.compliance.soc2.SOC2ComplianceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticatedPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Compliance Reporting REST Controller
 * Provides endpoints for compliance reporting and monitoring
 */
@RestController
@RequestMapping("/api/compliance/reporting")
public class ComplianceReportingController {

    @Autowired
    private SOC2ComplianceService soc2ComplianceService;

    @Autowired
    private HIPAAComplianceService hipaaComplianceService;

    @Autowired
    private ISO27001ComplianceService iso27001ComplianceService;

    @Autowired
    private FinancialComplianceService financialComplianceService;

    @Autowired
    private com.budgetbuddy.service.UserService userService;

    /**
     * SOC2 Compliance Report
     */
    @GetMapping("/soc2")
    public ResponseEntity<SOC2ComplianceService.SystemHealth> getSOC2Report(
            @AuthenticatedPrincipal UserDetails userDetails) {
        com.budgetbuddy.model.dynamodb.UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Check permissions
        if (!hasComplianceAccess(user)) {
            return ResponseEntity.status(403).build();
        }

        SOC2ComplianceService.SystemHealth health = soc2ComplianceService.checkSystemHealth();
        return ResponseEntity.ok(health);
    }

    /**
     * HIPAA Compliance Report
     */
    @GetMapping("/hipaa/breaches")
    public ResponseEntity<HIPAAComplianceService.BreachReport> getHIPAABreaches(
            @AuthenticatedPrincipal UserDetails userDetails,
            @RequestParam(required = false) String userId) {
        com.budgetbuddy.model.dynamodb.UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Check permissions
        if (!hasComplianceAccess(user)) {
            return ResponseEntity.status(403).build();
        }

        // In production, this would query breach reports from database
        return ResponseEntity.ok().build();
    }

    /**
     * ISO27001 Compliance Report
     */
    @GetMapping("/iso27001/incidents")
    public ResponseEntity<ISO27001ComplianceService.SecurityIncident> getISO27001Incidents(
            @AuthenticatedPrincipal UserDetails userDetails) {
        com.budgetbuddy.model.dynamodb.UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Check permissions
        if (!hasComplianceAccess(user)) {
            return ResponseEntity.status(403).build();
        }

        // In production, this would query security incidents from database
        return ResponseEntity.ok().build();
    }

    /**
     * Financial Compliance Report
     */
    @GetMapping("/financial/transactions")
    public ResponseEntity<FinancialComplianceReport> getFinancialComplianceReport(
            @AuthenticatedPrincipal UserDetails userDetails,
            @RequestParam Instant startDate,
            @RequestParam Instant endDate) {
        com.budgetbuddy.model.dynamodb.UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Check permissions
        if (!hasComplianceAccess(user)) {
            return ResponseEntity.status(403).build();
        }

        FinancialComplianceReport report = new FinancialComplianceReport();
        report.setStartDate(startDate);
        report.setEndDate(endDate);
        report.setCompliant(true);
        report.setTimestamp(Instant.now());

        return ResponseEntity.ok(report);
    }

    private boolean hasComplianceAccess(com.budgetbuddy.model.dynamodb.UserTable user) {
        // Check if user has compliance/admin role
        return user.getRoles() != null && 
               (user.getRoles().contains("ADMIN") || user.getRoles().contains("COMPLIANCE"));
    }

    /**
     * Financial Compliance Report DTO
     */
    public static class FinancialComplianceReport {
        private Instant startDate;
        private Instant endDate;
        private boolean compliant;
        private Instant timestamp;

        // Getters and setters
        public Instant getStartDate() { return startDate; }
        public void setStartDate(Instant startDate) { this.startDate = startDate; }
        public Instant getEndDate() { return endDate; }
        public void setEndDate(Instant endDate) { this.endDate = endDate; }
        public boolean isCompliant() { return compliant; }
        public void setCompliant(boolean compliant) { this.compliant = compliant; }
        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    }
}

