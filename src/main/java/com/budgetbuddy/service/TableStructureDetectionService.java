package com.budgetbuddy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Arrays;

/**
 * Table Structure Detection Service
 * Detects table structures from OCR text and extracts structured data
 * 
 * Handles:
 * - Column alignment detection
 * - Row detection
 * - Header row identification
 * - Data row extraction
 */
@Service
public class TableStructureDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(TableStructureDetectionService.class);
    
    // Common table header patterns for financial statements
    private static final List<String> TABLE_HEADER_KEYWORDS = Arrays.asList(
        "date", "transaction date", "posting date", "value date",
        "description", "details", "memo", "narration", "particulars",
        "amount", "debit", "credit", "balance",
        "reference", "ref", "check number", "check #",
        "category", "type", "transaction type"
    );
    
    /**
     * Detected table structure
     */
    public static class TableStructure {
        private List<String> headers;
        private List<List<String>> rows;
        private int headerRowIndex;
        private Map<Integer, String> columnTypes; // Column index -> type (date, amount, description, etc.)
        
        public TableStructure() {
            this.headers = new ArrayList<>();
            this.rows = new ArrayList<>();
            this.columnTypes = new HashMap<>();
        }
        
        public List<String> getHeaders() { return headers; }
        public void setHeaders(List<String> headers) { this.headers = headers; }
        
        public List<List<String>> getRows() { return rows; }
        public void setRows(List<List<String>> rows) { this.rows = rows; }
        
        public int getHeaderRowIndex() { return headerRowIndex; }
        public void setHeaderRowIndex(int headerRowIndex) { this.headerRowIndex = headerRowIndex; }
        
        public Map<Integer, String> getColumnTypes() { return columnTypes; }
        public void setColumnTypes(Map<Integer, String> columnTypes) { this.columnTypes = columnTypes; }
    }
    
    /**
     * Detect table structure from OCR text
     * 
     * @param ocrText Text extracted from OCR
     * @return Detected table structure
     */
    public TableStructure detectTableStructure(String ocrText) {
        if (ocrText == null || ocrText.trim().isEmpty()) {
            return new TableStructure();
        }
        
        // CRITICAL FIX: Limit OCR text size to prevent OOM
        final int MAX_OCR_TEXT_LENGTH = 10 * 1024 * 1024; // 10 MB
        if (ocrText.length() > MAX_OCR_TEXT_LENGTH) {
            logger.warn("OCR text too large: {} characters, truncating to {} characters", 
                ocrText.length(), MAX_OCR_TEXT_LENGTH);
            ocrText = ocrText.substring(0, MAX_OCR_TEXT_LENGTH);
        }
        
        TableStructure table = new TableStructure();
        String[] lines = ocrText.split("\n");
        
        // CRITICAL FIX: Limit number of lines to process
        final int MAX_LINES = 10000;
        if (lines.length > MAX_LINES) {
            logger.warn("Too many lines: {}, limiting to first {} lines", lines.length, MAX_LINES);
            lines = Arrays.copyOf(lines, MAX_LINES);
        }
        
        logger.info("Detecting table structure from {} lines of OCR text", lines.length);
        
        // Find header row
        int headerIndex = findHeaderRow(lines);
        if (headerIndex >= 0 && headerIndex < lines.length) {
            // CRITICAL FIX: Bounds check before array access
            table.setHeaderRowIndex(headerIndex);
            List<String> headers = parseRow(lines[headerIndex]);
            table.setHeaders(headers);
            logger.info("Found header row at line {}: {}", headerIndex + 1, headers);
            
            // Detect column types
            Map<Integer, String> columnTypes = detectColumnTypes(headers);
            table.setColumnTypes(columnTypes);
        }
        
        // Extract data rows
        List<List<String>> dataRows = extractDataRows(lines, headerIndex);
        table.setRows(dataRows);
        
        logger.info("Detected table with {} headers and {} data rows", 
            table.getHeaders().size(), dataRows.size());
        
        return table;
    }
    
    /**
     * Find header row in OCR text
     */
    private int findHeaderRow(String[] lines) {
        // Check first 20 lines for header row
        int maxLines = Math.min(20, lines.length);
        
        for (int i = 0; i < maxLines; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            
            // Check if line contains multiple header keywords
            int keywordCount = countHeaderKeywords(line);
            if (keywordCount >= 2) {
                logger.debug("Found potential header row at line {} with {} keywords: {}", 
                    i + 1, keywordCount, line);
                return i;
            }
        }
        
        // Fallback: look for line with multiple words (likely header)
        for (int i = 0; i < maxLines; i++) {
            String line = lines[i].trim();
            String[] words = line.split("\\s+");
            if (words.length >= 3) {
                logger.debug("Found potential header row at line {} (multiple words): {}", 
                    i + 1, line);
                return i;
            }
        }
        
        return -1; // No header found
    }
    
    /**
     * Count header keywords in a line
     */
    private int countHeaderKeywords(String line) {
        String lineLower = line.toLowerCase();
        int count = 0;
        
        for (String keyword : TABLE_HEADER_KEYWORDS) {
            if (lineLower.contains(keyword)) {
                count++;
            }
        }
        
        return count;
    }
    
    /**
     * Parse a row into columns (handles various separators)
     */
    private List<String> parseRow(String line) {
        if (line == null || line.trim().isEmpty()) {
            return new ArrayList<>();
        }
        
        List<String> columns = new ArrayList<>();
        
        // Try tab-separated first
        if (line.contains("\t")) {
            String[] parts = line.split("\t");
            for (String part : parts) {
                columns.add(part.trim());
            }
            return columns;
        }
        
        // Try multiple spaces (fixed-width columns)
        if (line.matches(".*\\s{3,}.*")) {
            String[] parts = line.split("\\s{3,}");
            for (String part : parts) {
                columns.add(part.trim());
            }
            return columns;
        }
        
        // Try pipe separator
        if (line.contains("|")) {
            String[] parts = line.split("\\|");
            for (String part : parts) {
                columns.add(part.trim());
            }
            return columns;
        }
        
        // Default: split by single space (fallback)
        String[] parts = line.split("\\s+");
        for (String part : parts) {
            if (!part.trim().isEmpty()) {
                columns.add(part.trim());
            }
        }
        
        return columns;
    }
    
    /**
     * Detect column types from headers
     */
    private Map<Integer, String> detectColumnTypes(List<String> headers) {
        Map<Integer, String> columnTypes = new HashMap<>();
        
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i).toLowerCase();
            
            if (header.contains("date")) {
                columnTypes.put(i, "date");
            } else if (header.contains("amount") || header.contains("debit") || 
                      header.contains("credit") || header.contains("balance")) {
                columnTypes.put(i, "amount");
            } else if (header.contains("description") || header.contains("details") || 
                      header.contains("memo") || header.contains("narration")) {
                columnTypes.put(i, "description");
            } else if (header.contains("account") || header.contains("card")) {
                columnTypes.put(i, "account");
            } else if (header.contains("category") || header.contains("type")) {
                columnTypes.put(i, "category");
            } else {
                columnTypes.put(i, "text");
            }
        }
        
        return columnTypes;
    }
    
    /**
     * Extract data rows from OCR text
     */
    private List<List<String>> extractDataRows(String[] lines, int headerIndex) {
        List<List<String>> dataRows = new ArrayList<>();
        
        // Start from line after header (or line 0 if no header)
        int startIndex = headerIndex >= 0 ? headerIndex + 1 : 0;
        
        for (int i = startIndex; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            
            // Skip lines that look like headers (too many keywords)
            if (countHeaderKeywords(line) >= 2 && i > headerIndex + 5) {
                continue; // Likely a repeated header
            }
            
            // Parse row
            List<String> columns = parseRow(line);
            
            // Only add rows with at least 2 columns (likely data)
            if (columns.size() >= 2) {
                dataRows.add(columns);
            }
        }
        
        return dataRows;
    }
    
    /**
     * Extract account information from table structure
     */
    public Map<String, String> extractAccountInfoFromTable(TableStructure table) {
        Map<String, String> accountInfo = new HashMap<>();
        
        // Check headers for account information
        for (int i = 0; i < table.getHeaders().size(); i++) {
            String header = table.getHeaders().get(i).toLowerCase();
            
            if (header.contains("account number") || header.contains("account #") || 
                header.contains("card number") || header.contains("card #")) {
                // Look for account number in first few data rows
                String accountNum = findAccountNumberInColumn(table.getRows(), i);
                if (accountNum != null) {
                    accountInfo.put("accountNumber", accountNum);
                }
            }
            
            if (header.contains("institution") || header.contains("bank")) {
                String institution = findInstitutionInColumn(table.getRows(), i);
                if (institution != null) {
                    accountInfo.put("institutionName", institution);
                }
            }
        }
        
        return accountInfo;
    }
    
    /**
     * Find account number in a specific column
     */
    private String findAccountNumberInColumn(List<List<String>> rows, int columnIndex) {
        if (rows == null || rows.isEmpty() || columnIndex < 0) {
            return null;
        }
        
        for (int i = 0; i < Math.min(5, rows.size()); i++) {
            // CRITICAL FIX: Bounds check
            if (i >= rows.size()) {
                break;
            }
            
            List<String> row = rows.get(i);
            if (row == null) {
                continue;
            }
            
            // CRITICAL FIX: Bounds check for column index
            if (columnIndex < row.size()) {
                String value = row.get(columnIndex);
                String accountNum = extractAccountNumber(value);
                if (accountNum != null) {
                    return accountNum;
                }
            }
        }
        return null;
    }
    
    /**
     * Find institution name in a specific column
     */
    private String findInstitutionInColumn(List<List<String>> rows, int columnIndex) {
        if (rows == null || rows.isEmpty() || columnIndex < 0) {
            return null;
        }
        
        for (int i = 0; i < Math.min(3, rows.size()); i++) {
            // CRITICAL FIX: Bounds check
            if (i >= rows.size()) {
                break;
            }
            
            List<String> row = rows.get(i);
            if (row == null) {
                continue;
            }
            
            // CRITICAL FIX: Bounds check for column index
            if (columnIndex < row.size()) {
                String value = row.get(columnIndex);
                if (value != null && !value.trim().isEmpty() && value.length() > 3) {
                    return value.trim();
                }
            }
        }
        return null;
    }
    
    /**
     * Extract account number from value string
     */
    private String extractAccountNumber(String value) {
        if (value == null) return null;
        
        // Remove non-digit characters except spaces and dashes
        String cleaned = value.replaceAll("[^\\d\\s-]", "");
        
        // Extract last 4 digits
        Pattern pattern = Pattern.compile("(\\d{4})(?:[\\s-]|$)");
        Matcher matcher = pattern.matcher(cleaned);
        
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        // Try to find any 4-digit number
        pattern = Pattern.compile("\\b(\\d{4})\\b");
        matcher = pattern.matcher(cleaned);
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        return null;
    }
}

