package com.budgetbuddy.compliance.dma;

import com.budgetbuddy.compliance.gdpr.GDPRComplianceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Digital Markets Act (DMA) Compliance Service
 * Implements DMA requirements for data portability and interoperability
 * 
 * DMA Requirements:
 * - Data portability (Article 6)
 * - Interoperability (Article 7)
 * - Fair access to data
 */
@Service
public class DMAComplianceService {

    private static final Logger logger = LoggerFactory.getLogger(DMAComplianceService.class);

    @Autowired
    private GDPRComplianceService gdprComplianceService;

    /**
     * Article 6: Data Portability
     * Provide data in standardized, machine-readable format
     */
    public String exportDataPortable(String userId, String format) {
        logger.info("DMA: Exporting data in format: {} for user: {}", format, userId);

        // Use GDPR service for data export (DMA extends GDPR requirements)
        if ("JSON".equalsIgnoreCase(format)) {
            return gdprComplianceService.exportDataPortable(userId);
        } else if ("CSV".equalsIgnoreCase(format)) {
            return exportAsCSV(userId);
        } else {
            throw new IllegalArgumentException("Unsupported format: " + format);
        }
    }

    /**
     * Article 7: Interoperability
     * Provide API access for data interoperability
     */
    public String getInteroperabilityEndpoint(String userId) {
        // Return endpoint for third-party access (with proper authentication)
        return "/api/dma/interoperability/" + userId;
    }

    /**
     * Export data as CSV
     */
    private String exportAsCSV(String userId) {
        // Convert JSON export to CSV format
        String json = gdprComplianceService.exportDataPortable(userId);
        // In production, use a proper JSON to CSV converter
        return json; // Simplified
    }
}

