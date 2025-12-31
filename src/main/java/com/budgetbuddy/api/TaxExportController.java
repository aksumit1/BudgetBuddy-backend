package com.budgetbuddy.api;

import com.budgetbuddy.service.TaxExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.Year;
import java.util.Arrays;
import java.util.List;

/**
 * Tax Export Controller
 * Provides endpoints for exporting tax-related transaction data
 * Supports CSV and JSON formats suitable for tax filing software
 */
@RestController
@RequestMapping("/api/tax")
public class TaxExportController {

    private static final Logger logger = LoggerFactory.getLogger(TaxExportController.class);
    
    private final TaxExportService taxExportService;
    
    public TaxExportController(TaxExportService taxExportService) {
        this.taxExportService = taxExportService;
    }
    
    /**
     * Export tax data for a specific year in CSV format
     * Suitable for importing into tax software (TurboTax, H&R Block, etc.)
     * 
     * @param year Tax year (defaults to current year)
     * @param categories Optional: Filter by tax categories (comma-separated, e.g., "SALARY,INTEREST")
     * @param accountIds Optional: Filter by account IDs (comma-separated)
     * @param startDate Optional: Start date within year (YYYY-MM-DD)
     * @param endDate Optional: End date within year (YYYY-MM-DD)
     * @param userDetails Authenticated user
     * @return CSV file with tax data
     */
    @GetMapping(value = "/export/csv", produces = {MediaType.TEXT_PLAIN_VALUE, "text/csv", MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<String> exportTaxDataCSV(
            @RequestParam(required = false, defaultValue = "0") int year,
            @RequestParam(required = false) String categories,
            @RequestParam(required = false) String accountIds,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        try {
            if (userDetails == null || userDetails.getUsername() == null) {
                logger.error("Export request with null userDetails");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Authentication required");
            }
            
            String userId = userDetails.getUsername();
            int taxYear = year > 0 ? year : Year.now().getValue();
            
            // Validate year range
            if (taxYear < 1900 || taxYear > 2100) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Invalid year. Must be between 1900 and 2100");
            }
            
            // Parse filter parameters
            List<String> categoryList = parseCommaSeparated(categories);
            List<String> accountIdList = parseCommaSeparated(accountIds);
            
            logger.info("Exporting tax data as CSV for user {} for year {} (categories: {}, accountIds: {}, dateRange: {} to {})", 
                userId, taxYear, categoryList, accountIdList, startDate, endDate);
            
            TaxExportService.TaxExportResult result = taxExportService.generateTaxExport(
                userId, taxYear, categoryList, accountIdList, startDate, endDate);
            
            // Log helpful message if no data found
            boolean hasData = result != null && 
                             result.getTransactionsByCategory() != null &&
                             !result.getTransactionsByCategory().isEmpty() &&
                             result.getTransactionsByCategory().values().stream()
                                 .anyMatch(list -> list != null && !list.isEmpty());
            
            if (!hasData) {
                logger.info("No tax data found for user {} for year {}. Exporting empty CSV with helpful message.", 
                    userId, taxYear);
            }
            
            String csv = taxExportService.exportToCSV(result, taxYear);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("text/csv"));
            headers.setContentDispositionFormData("attachment", 
                String.format("tax_export_%d.csv", taxYear));
            
            return new ResponseEntity<>(csv, headers, HttpStatus.OK);
            
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid request parameters: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Invalid request: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error exporting tax data as CSV: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error generating tax export: " + e.getMessage());
        }
    }
    
    /**
     * Export tax data for multiple years in CSV format
     * Useful for users filing taxes for multiple years
     * 
     * @param years Comma-separated list of years (e.g., "2022,2023,2024")
     * @param categories Optional: Filter by tax categories
     * @param accountIds Optional: Filter by account IDs
     * @param userDetails Authenticated user
     * @return CSV file with multi-year tax data
     */
    @GetMapping(value = "/export/csv/multi-year", produces = {MediaType.TEXT_PLAIN_VALUE, "text/csv", MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<String> exportMultiYearTaxDataCSV(
            @RequestParam String years,
            @RequestParam(required = false) String categories,
            @RequestParam(required = false) String accountIds,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        try {
            if (userDetails == null || userDetails.getUsername() == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Authentication required");
            }
            
            String userId = userDetails.getUsername();
            List<String> yearStrings = parseCommaSeparated(years);
            List<String> categoryList = parseCommaSeparated(categories);
            List<String> accountIdList = parseCommaSeparated(accountIds);
            
            // Validate and parse years
            int[] yearArray = yearStrings.stream()
                .mapToInt(Integer::parseInt)
                .toArray();
            
            for (int y : yearArray) {
                if (y < 1900 || y > 2100) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Invalid year: " + y + ". Must be between 1900 and 2100");
                }
            }
            
            logger.info("Exporting multi-year tax data as CSV for user {} for years {}", userId, Arrays.toString(yearArray));
            
            TaxExportService.TaxExportResult result = taxExportService.generateMultiYearTaxExport(
                userId, yearArray, categoryList, accountIdList);
            String csv = taxExportService.exportToCSVMultiYear(result, yearArray);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("text/csv"));
            headers.setContentDispositionFormData("attachment", 
                String.format("tax_export_%s.csv", years.replace(",", "_")));
            
            return new ResponseEntity<>(csv, headers, HttpStatus.OK);
            
        } catch (NumberFormatException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Invalid year format. Expected comma-separated years (e.g., 2022,2023,2024)");
        } catch (Exception e) {
            logger.error("Error exporting multi-year tax data as CSV: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error generating multi-year tax export: " + e.getMessage());
        }
    }
    
    /**
     * Export Schedule A (Itemized Deductions) data
     * Includes: Charity, Medical, Property Tax, State Tax, Local Tax, Mortgage Interest, DMV, CPA
     * 
     * @param year Tax year
     * @param userDetails Authenticated user
     * @return CSV formatted for Schedule A
     */
    @GetMapping(value = "/export/schedule-a", produces = {MediaType.TEXT_PLAIN_VALUE, "text/csv", MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<String> exportScheduleA(
            @RequestParam(required = false, defaultValue = "0") int year,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        try {
            if (userDetails == null || userDetails.getUsername() == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Authentication required");
            }
            
            String userId = userDetails.getUsername();
            int taxYear = year > 0 ? year : Year.now().getValue();
            
            List<String> scheduleACategories = Arrays.asList(
                "CHARITY", "MEDICAL", "PROPERTY_TAX", "STATE_TAX", "LOCAL_TAX", 
                "MORTGAGE_INTEREST", "DMV", "CPA"
            );
            
            TaxExportService.TaxExportResult result = taxExportService.generateTaxExport(
                userId, taxYear, scheduleACategories, null, null, null);
            String csv = taxExportService.exportToScheduleA(result, taxYear);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("text/csv"));
            headers.setContentDispositionFormData("attachment", 
                String.format("schedule_a_%d.csv", taxYear));
            
            return new ResponseEntity<>(csv, headers, HttpStatus.OK);
            
        } catch (Exception e) {
            logger.error("Error exporting Schedule A: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error generating Schedule A export: " + e.getMessage());
        }
    }
    
    /**
     * Export Schedule B (Interest and Dividends) data
     * 
     * @param year Tax year
     * @param userDetails Authenticated user
     * @return CSV formatted for Schedule B
     */
    @GetMapping(value = "/export/schedule-b", produces = {MediaType.TEXT_PLAIN_VALUE, "text/csv", MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<String> exportScheduleB(
            @RequestParam(required = false, defaultValue = "0") int year,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        try {
            if (userDetails == null || userDetails.getUsername() == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Authentication required");
            }
            
            String userId = userDetails.getUsername();
            int taxYear = year > 0 ? year : Year.now().getValue();
            
            List<String> scheduleBCategories = Arrays.asList("INTEREST", "DIVIDEND");
            
            TaxExportService.TaxExportResult result = taxExportService.generateTaxExport(
                userId, taxYear, scheduleBCategories, null, null, null);
            String csv = taxExportService.exportToScheduleB(result, taxYear);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("text/csv"));
            headers.setContentDispositionFormData("attachment", 
                String.format("schedule_b_%d.csv", taxYear));
            
            return new ResponseEntity<>(csv, headers, HttpStatus.OK);
            
        } catch (Exception e) {
            logger.error("Error exporting Schedule B: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error generating Schedule B export: " + e.getMessage());
        }
    }
    
    /**
     * Export Schedule D (Capital Gains and Losses) data
     * 
     * @param year Tax year
     * @param userDetails Authenticated user
     * @return CSV formatted for Schedule D
     */
    @GetMapping(value = "/export/schedule-d", produces = {MediaType.TEXT_PLAIN_VALUE, "text/csv", MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<String> exportScheduleD(
            @RequestParam(required = false, defaultValue = "0") int year,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        try {
            if (userDetails == null || userDetails.getUsername() == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Authentication required");
            }
            
            String userId = userDetails.getUsername();
            int taxYear = year > 0 ? year : Year.now().getValue();
            
            List<String> scheduleDCategories = Arrays.asList("CAPITAL_GAIN", "CAPITAL_LOSS");
            
            TaxExportService.TaxExportResult result = taxExportService.generateTaxExport(
                userId, taxYear, scheduleDCategories, null, null, null);
            String csv = taxExportService.exportToScheduleD(result, taxYear);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("text/csv"));
            headers.setContentDispositionFormData("attachment", 
                String.format("schedule_d_%d.csv", taxYear));
            
            return new ResponseEntity<>(csv, headers, HttpStatus.OK);
            
        } catch (Exception e) {
            logger.error("Error exporting Schedule D: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error generating Schedule D export: " + e.getMessage());
        }
    }
    
    /**
     * Export tax data for a specific year in JSON format
     * Suitable for programmatic consumption or API integration
     * 
     * @param year Tax year (defaults to current year)
     * @param categories Optional: Filter by tax categories
     * @param accountIds Optional: Filter by account IDs
     * @param startDate Optional: Start date within year
     * @param endDate Optional: End date within year
     * @param userDetails Authenticated user
     * @return JSON object with tax data
     */
    @GetMapping(value = "/export/json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> exportTaxDataJSON(
            @RequestParam(required = false, defaultValue = "0") int year,
            @RequestParam(required = false) String categories,
            @RequestParam(required = false) String accountIds,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        try {
            if (userDetails == null || userDetails.getUsername() == null) {
                logger.error("Export request with null userDetails");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("{\"error\": \"Authentication required\"}");
            }
            
            String userId = userDetails.getUsername();
            int taxYear = year > 0 ? year : Year.now().getValue();
            
            // Validate year range
            if (taxYear < 1900 || taxYear > 2100) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("{\"error\": \"Invalid year. Must be between 1900 and 2100\"}");
            }
            
            List<String> categoryList = parseCommaSeparated(categories);
            List<String> accountIdList = parseCommaSeparated(accountIds);
            
            logger.info("Exporting tax data as JSON for user {} for year {} (categories: {}, accountIds: {}, dateRange: {} to {})", 
                userId, taxYear, categoryList, accountIdList, startDate, endDate);
            
            TaxExportService.TaxExportResult result = taxExportService.generateTaxExport(
                userId, taxYear, categoryList, accountIdList, startDate, endDate);
            String json = taxExportService.exportToJSON(result, taxYear);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            return new ResponseEntity<>(json, headers, HttpStatus.OK);
            
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid request parameters: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("{\"error\": \"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            logger.error("Error exporting tax data as JSON: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("{\"error\": \"Error generating tax export: " + e.getMessage() + "\"}");
        }
    }
    
    /**
     * Get tax summary for a specific year
     * Returns summary totals without detailed transactions
     * 
     * @param year Tax year (defaults to current year)
     * @param categories Optional: Filter by tax categories
     * @param accountIds Optional: Filter by account IDs
     * @param startDate Optional: Start date within year
     * @param endDate Optional: End date within year
     * @param userDetails Authenticated user
     * @return Tax summary
     */
    @GetMapping(value = "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TaxExportService.TaxSummary> getTaxSummary(
            @RequestParam(required = false, defaultValue = "0") int year,
            @RequestParam(required = false) String categories,
            @RequestParam(required = false) String accountIds,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        try {
            if (userDetails == null || userDetails.getUsername() == null) {
                logger.error("Summary request with null userDetails");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            
            String userId = userDetails.getUsername();
            int taxYear = year > 0 ? year : Year.now().getValue();
            
            // Validate year range
            if (taxYear < 1900 || taxYear > 2100) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }
            
            List<String> categoryList = parseCommaSeparated(categories);
            List<String> accountIdList = parseCommaSeparated(accountIds);
            
            logger.info("Getting tax summary for user {} for year {} (categories: {}, accountIds: {}, dateRange: {} to {})", 
                userId, taxYear, categoryList, accountIdList, startDate, endDate);
            
            TaxExportService.TaxExportResult result = taxExportService.generateTaxExport(
                userId, taxYear, categoryList, accountIdList, startDate, endDate);
            
            return ResponseEntity.ok(result.getSummary());
            
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid request parameters: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            logger.error("Error getting tax summary: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Parse comma-separated string into list
     */
    private List<String> parseCommaSeparated(String input) {
        if (input == null || input.trim().isEmpty()) {
            return null;
        }
        return Arrays.asList(input.split("\\s*,\\s*"));
    }
}
