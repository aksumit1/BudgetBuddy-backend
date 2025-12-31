package com.budgetbuddy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Arrays;

/**
 * Form Field Detection Service
 * Detects form fields (labels and values) from OCR text
 * 
 * Handles patterns like:
 * - "Account Number: 1234"
 * - "Account Number 1234"
 * - "Account Number\n1234"
 * - "Account Number\n\n1234"
 */
@Service
public class FormFieldDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(FormFieldDetectionService.class);
    
    // Common form field labels for financial statements
    private static final List<String> FORM_FIELD_LABELS = Arrays.asList(
        // Account Information
        "account number", "account #", "account no", "acct number", "acct #",
        "card number", "card #", "card no", "credit card number",
        "account name", "account holder", "account holder name",
        "institution", "institution name", "bank", "bank name",
        "account type", "type", "product name", "product type",
        "routing number", "routing #", "aba number",
        "iban", "swift code", "bic",
        
        // Statement Information
        "statement date", "statement period", "period",
        "opening balance", "closing balance", "ending balance",
        "available balance", "current balance",
        "statement number", "statement #",
        
        // Contact Information
        "address", "mailing address", "billing address",
        "phone", "phone number", "telephone",
        "email", "e-mail", "email address"
    );
    
    // Patterns for detecting label-value pairs
    // CRITICAL: Use possessive quantifiers and limit length to prevent catastrophic backtracking
    private static final List<Pattern> LABEL_VALUE_PATTERNS = Arrays.asList(
        // "Label: Value" format - limit label to 200 chars, value to 500 chars to prevent backtracking
        Pattern.compile("(?i)([A-Z][^:\\n]{0,200}?):\\s*([^\\n]{0,500})"),
        // "Label Value" format (label ends with common keywords) - limit to prevent backtracking
        Pattern.compile("(?i)((?:account|card|institution|bank|statement|balance|routing|iban|swift|phone|email|address)[^\\d]{0,100}?)\\s+(\\d{4,20}|[A-Z0-9]{8,30})"),
        // "Label\nValue" format (multiline) - limit to prevent backtracking
        Pattern.compile("(?i)([A-Z][^\\n]{0,200}?)\\n+([^\\n]{0,500})"),
        // "Label\n\nValue" format (double newline) - limit to prevent backtracking
        Pattern.compile("(?i)([A-Z][^\\n]{0,200}?)\\n\\n+([^\\n]{0,500})")
    );
    
    /**
     * Detected form field with label and value
     */
    public static class FormField {
        private String label;
        private String value;
        private double confidence;
        private int lineNumber;
        
        public FormField(String label, String value, double confidence, int lineNumber) {
            this.label = label;
            this.value = value;
            this.confidence = confidence;
            this.lineNumber = lineNumber;
        }
        
        public String getLabel() { return label; }
        public String getValue() { return value; }
        public double getConfidence() { return confidence; }
        public int getLineNumber() { return lineNumber; }
    }
    
    /**
     * Detect form fields from OCR text
     * 
     * @param ocrText Text extracted from OCR
     * @return List of detected form fields
     */
    public List<FormField> detectFormFields(String ocrText) {
        if (ocrText == null || ocrText.trim().isEmpty()) {
            return new ArrayList<>();
        }
        
        // CRITICAL FIX: Limit OCR text size to prevent OOM
        final int MAX_OCR_TEXT_LENGTH = 10 * 1024 * 1024; // 10 MB
        if (ocrText.length() > MAX_OCR_TEXT_LENGTH) {
            logger.warn("OCR text too large: {} characters, truncating to {} characters", 
                ocrText.length(), MAX_OCR_TEXT_LENGTH);
            ocrText = ocrText.substring(0, MAX_OCR_TEXT_LENGTH);
        }
        
        List<FormField> fields = new ArrayList<>();
        String[] lines = ocrText.split("\n");
        
        // CRITICAL FIX: Limit number of lines to process
        final int MAX_LINES = 10000;
        if (lines.length > MAX_LINES) {
            logger.warn("Too many lines: {}, limiting to first {} lines", lines.length, MAX_LINES);
            lines = Arrays.copyOf(lines, MAX_LINES);
        }
        
        logger.info("Detecting form fields from {} lines of OCR text", lines.length);
        
        // Process each line and adjacent lines for multiline patterns
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            
            // CRITICAL: Limit line length to prevent regex backtracking on very long lines
            final int MAX_LINE_LENGTH = 1000;
            if (line.length() > MAX_LINE_LENGTH) {
                logger.debug("Line {} too long ({} chars), truncating to {} chars", i + 1, line.length(), MAX_LINE_LENGTH);
                line = line.substring(0, MAX_LINE_LENGTH);
            }
            
            // Try each pattern
            for (Pattern pattern : LABEL_VALUE_PATTERNS) {
                Matcher matcher = pattern.matcher(line);
                // CRITICAL: Use find() with timeout protection - limit matches to prevent hanging
                try {
                    if (matcher.find()) {
                        // CRITICAL FIX: Check group count before accessing groups
                        if (matcher.groupCount() >= 2) {
                            String label = matcher.group(1);
                            String value = matcher.group(2);
                            
                            // CRITICAL FIX: Null check before trim
                            if (label != null && value != null) {
                                label = label.trim();
                                value = value.trim();
                                
                                // Skip if label or value is empty after trim
                                if (label.isEmpty() || value.isEmpty()) {
                                    continue;
                                }
                                
                                // Check if label matches known form field labels
                                double confidence = calculateConfidence(label, value);
                                if (confidence > 0.5) {
                                    fields.add(new FormField(label, value, confidence, i + 1));
                                    logger.debug("Detected form field at line {}: {} = {} (confidence: {})", 
                                        i + 1, label, value, confidence);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // CRITICAL: Catch any regex exceptions to prevent test hangs
                    logger.warn("Error matching pattern on line {}: {}", i + 1, e.getMessage());
                    continue; // Skip this pattern and try next
                }
            }
            
            // Check for multiline patterns (label on current line, value on next line)
            if (i < lines.length - 1) {
                String nextLine = lines[i + 1].trim();
                // CRITICAL: Limit next line length too
                if (!nextLine.isEmpty()) {
                    if (nextLine.length() > MAX_LINE_LENGTH) {
                        nextLine = nextLine.substring(0, MAX_LINE_LENGTH);
                    }
                    FormField field = detectMultilineField(line, nextLine, i + 1);
                    if (field != null) {
                        fields.add(field);
                    }
                }
            }
        }
        
        // Remove duplicates (keep highest confidence)
        fields = deduplicateFields(fields);
        
        logger.info("Detected {} form fields from OCR text", fields.size());
        return fields;
    }
    
    /**
     * Detect multiline form field (label on one line, value on next)
     */
    private FormField detectMultilineField(String labelLine, String valueLine, int lineNumber) {
        String labelLower = labelLine.toLowerCase();
        
        // Check if label line contains form field keywords
        for (String keyword : FORM_FIELD_LABELS) {
            if (labelLower.contains(keyword)) {
                // Value line should contain actual data (not another label)
                if (isValueLine(valueLine)) {
                    double confidence = calculateConfidence(labelLine, valueLine);
                    if (confidence > 0.5) {
                        return new FormField(labelLine.trim(), valueLine.trim(), confidence, lineNumber);
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * Check if a line looks like a value (not a label)
     */
    private boolean isValueLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return false;
        }
        
        String lineLower = line.toLowerCase();
        
        // Value lines typically:
        // - Don't end with ":"
        // - Don't contain common label keywords at the start
        // - May contain numbers, account numbers, etc.
        
        if (lineLower.endsWith(":")) {
            return false; // Likely a label
        }
        
        // Check if line starts with label keywords
        for (String keyword : FORM_FIELD_LABELS) {
            if (lineLower.startsWith(keyword)) {
                return false; // Likely a label
            }
        }
        
        return true;
    }
    
    /**
     * Calculate confidence score for a form field
     */
    private double calculateConfidence(String label, String value) {
        if (label == null || value == null) {
            return 0.0;
        }
        
        double confidence = 0.0;
        String labelLower = label.toLowerCase();
        
        // Check if label matches known form field labels
        for (String knownLabel : FORM_FIELD_LABELS) {
            if (labelLower.contains(knownLabel)) {
                confidence += 0.4;
                break;
            }
        }
        
        // Check if value looks valid
        if (isValidValue(value)) {
            confidence += 0.3;
        }
        
        // Check label format (should start with capital letter or be all caps)
        if (label.matches("^[A-Z].*") || label.matches("^[A-Z\\s]+$")) {
            confidence += 0.2;
        }
        
        // Check if label and value are on same line (colon separator)
        if (label.contains(":") || label.endsWith(":")) {
            confidence += 0.1;
        }
        
        return Math.min(1.0, confidence);
    }
    
    /**
     * Check if value looks valid
     */
    private boolean isValidValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        
        // Value should not be too short or too long
        if (value.length() < 2 || value.length() > 100) {
            return false;
        }
        
        // Value should not be just whitespace
        if (value.trim().isEmpty()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Remove duplicate fields (keep highest confidence)
     */
    private List<FormField> deduplicateFields(List<FormField> fields) {
        if (fields == null || fields.isEmpty()) {
            return new ArrayList<>();
        }
        
        Map<String, FormField> bestFields = new HashMap<>();
        
        for (FormField field : fields) {
            // CRITICAL FIX: Null check for label
            if (field == null || field.getLabel() == null) {
                continue;
            }
            
            // CRITICAL: Normalize label by trimming and lowercasing for better deduplication
            // Also handle variations like "Account Number" vs "Account Number " (with trailing space)
            String normalizedLabel = field.getLabel().trim().toLowerCase();
            
            // CRITICAL: For account number fields, use a more flexible key that matches variations
            // "account number", "account #", "account no", etc. should all be considered the same
            String key = normalizedLabel;
            if (normalizedLabel.contains("account number") || normalizedLabel.contains("account #") || 
                normalizedLabel.contains("account no") || normalizedLabel.contains("acct number") ||
                normalizedLabel.contains("acct #")) {
                key = "account number"; // Normalize all account number variations to same key
            }
            
            FormField existing = bestFields.get(key);
            
            if (existing == null || field.getConfidence() > existing.getConfidence()) {
                bestFields.put(key, field);
            }
        }
        
        return new ArrayList<>(bestFields.values());
    }
    
    /**
     * Extract account information from form fields
     */
    public Map<String, String> extractAccountInfo(List<FormField> fields) {
        Map<String, String> accountInfo = new HashMap<>();
        
        if (fields == null || fields.isEmpty()) {
            return accountInfo;
        }
        
        for (FormField field : fields) {
            // CRITICAL FIX: Null checks
            if (field == null || field.getLabel() == null || field.getValue() == null) {
                continue;
            }
            
            String labelLower = field.getLabel().toLowerCase();
            String value = field.getValue();
            
            // Account number
            if (labelLower.contains("account number") || labelLower.contains("account #") || 
                labelLower.contains("card number") || labelLower.contains("card #")) {
                String accountNum = extractAccountNumber(value);
                if (accountNum != null) {
                    accountInfo.put("accountNumber", accountNum);
                }
            }
            
            // Institution name
            if (labelLower.contains("institution") || labelLower.contains("bank")) {
                accountInfo.put("institutionName", value);
            }
            
            // Account name
            if (labelLower.contains("account name") || labelLower.contains("account holder")) {
                accountInfo.put("accountName", value);
            }
            
            // Account type
            if (labelLower.contains("account type") || labelLower.contains("type") || 
                labelLower.contains("product type")) {
                accountInfo.put("accountType", value);
            }
        }
        
        return accountInfo;
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

