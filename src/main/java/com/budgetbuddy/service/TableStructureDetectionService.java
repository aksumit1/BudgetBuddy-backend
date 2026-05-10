package com.budgetbuddy.service;


import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Table Structure Detection Service Detects table structures from OCR text and extracts structured
 * data
 *
 * <p>Handles: - Column alignment detection - Row detection - Header row identification - Data row
 * extraction
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
// PMD's DataClass fires on Request/Response/Config DTOs by design —
// they're intentionally data-only; behaviour belongs in the controller/service.
@SuppressWarnings({"PMD.DataClass", "PMD.OnlyOneReturn"})
@SuppressFBWarnings(
        value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
        justification = "JSON DTO / DynamoDB entity getters expose lists by reference; "
                        + "the design is value-semantic and Jackson creates fresh instances; Spring constructor injection — beans are shared by design")
@Service
public class TableStructureDetectionService {

    private static final String AMOUNT = "amount";

    private static final String CATEGORY = "category";

    private static final String DESCRIPTION = "description";

    private static final Logger LOGGER =
            LoggerFactory.getLogger(TableStructureDetectionService.class);

    // Common table header patterns for financial statements
    private static final List<String> TABLE_HEADER_KEYWORDS =
            Arrays.asList(
                    "date",
                    "transaction date",
                    "posting date",
                    "value date",
                    DESCRIPTION,
                    "details",
                    "memo",
                    "narration",
                    "particulars",
                    AMOUNT,
                    "debit",
                    "credit",
                    "balance",
                    "reference",
                    "ref",
                    "check number",
                    "check #",
                    CATEGORY,
                    "type",
                    "transaction type");

    /** Detected table structure */
    public static class TableStructure {
        private List<String> headers;
        private List<List<String>> rows;
        private int headerRowIndex;
        private Map<Integer, String>
                columnTypes; // Column index -> type (date, amount, description, etc.)

        public TableStructure() {
            this.headers = new ArrayList<>();
            this.rows = new ArrayList<>();
            this.columnTypes = new HashMap<>();
        }

        public List<String> getHeaders() {
            return headers;
        }

        public void setHeaders(final List<String> headers) {
            this.headers = headers;
        }

        public List<List<String>> getRows() {
            return rows;
        }

        public void setRows(final List<List<String>> rows) {
            this.rows = rows;
        }

        public int getHeaderRowIndex() {
            return headerRowIndex;
        }

        public void setHeaderRowIndex(final int headerRowIndex) {
            this.headerRowIndex = headerRowIndex;
        }

        public Map<Integer, String> getColumnTypes() {
            return columnTypes;
        }

        public void setColumnTypes(final Map<Integer, String> columnTypes) {
            this.columnTypes = columnTypes;
        }
    }

    /**
     * Detect table structure from OCR text
     *
     * @param ocrText Text extracted from OCR
     * @return Detected table structure
     */
    public TableStructure detectTableStructure(String ocrText) {
        if (ocrText == null || ocrText.isBlank()) {
            return new TableStructure();
        }

        // CRITICAL FIX: Limit OCR text size to prevent OOM
        final int MAX_OCR_TEXT_LENGTH = 10 * 1024 * 1024; // 10 MB
        if (ocrText.length() > MAX_OCR_TEXT_LENGTH) {
            LOGGER.warn(
                    "OCR text too large: {} characters, truncating to {} characters",
                    ocrText.length(),
                    MAX_OCR_TEXT_LENGTH);
            ocrText = ocrText.substring(0, MAX_OCR_TEXT_LENGTH);
        }

        final TableStructure table = new TableStructure();
        String[] lines = ocrText.split("\n");

        // CRITICAL FIX: Limit number of lines to process
        final int MAX_LINES = 10_000;
        if (lines.length > MAX_LINES) {
            LOGGER.warn("Too many lines: {}, limiting to first {} lines", lines.length, MAX_LINES);
            lines = Arrays.copyOf(lines, MAX_LINES);
        }

        LOGGER.info("Detecting table structure from {} lines of OCR text", lines.length);

        // Find header row
        final int headerIndex = findHeaderRow(lines);
        if (headerIndex >= 0 && headerIndex < lines.length) {
            // CRITICAL FIX: Bounds check before array access
            table.setHeaderRowIndex(headerIndex);
            final List<String> headers = parseRow(lines[headerIndex]);
            table.setHeaders(headers);
            LOGGER.info("Found header row at line {}: {}", headerIndex + 1, headers);

            // Detect column types
            final Map<Integer, String> columnTypes = detectColumnTypes(headers);
            table.setColumnTypes(columnTypes);
        }

        // Extract data rows
        final List<List<String>> dataRows = extractDataRows(lines, headerIndex);
        table.setRows(dataRows);

        LOGGER.info(
                "Detected table with {} headers and {} data rows",
                table.getHeaders().size(),
                dataRows.size());

        return table;
    }

    /** Find header row in OCR text */
    private int findHeaderRow(final String[] lines) {
        // Check first 20 lines for header row
        final int maxLines = Math.min(20, lines.length);

        for (int i = 0; i < maxLines; i++) {
            final String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }

            // Check if line contains multiple header keywords
            final int keywordCount = countHeaderKeywords(line);
            if (keywordCount >= 2) {
                LOGGER.debug(
                        "Found potential header row at line {} with {} keywords: {}",
                        i + 1,
                        keywordCount,
                        line);
                return i;
            }
        }

        // Fallback: look for line with multiple words (likely header)
        for (int i = 0; i < maxLines; i++) {
            final String line = lines[i].trim();
            final String[] words = line.split("\\s+");
            if (words.length >= 3) {
                LOGGER.debug(
                        "Found potential header row at line {} (multiple words): {}", i + 1, line);
                return i;
            }
        }

        return -1; // No header found
    }

    /** Count header keywords in a line */
    private int countHeaderKeywords(final String line) {
        final String lineLower = line.toLowerCase(Locale.ROOT);
        int count = 0;

        for (final String keyword : TABLE_HEADER_KEYWORDS) {
            if (lineLower.contains(keyword)) {
                count++;
            }
        }

        return count;
    }

    /** Parse a row into columns (handles various separators) */
    private List<String> parseRow(final String line) {
        if (line == null || line.isBlank()) {
            return new ArrayList<>();
        }

        final List<String> columns = new ArrayList<>();

        // Try tab-separated first
        if (line.contains("\t")) {
            final String[] parts = line.split("\t");
            for (final String part : parts) {
                columns.add(part.trim());
            }
            return columns;
        }

        // Try multiple spaces (fixed-width columns)
        if (line.matches(".*\\s{3,}.*")) {
            final String[] parts = line.split("\\s{3,}");
            for (final String part : parts) {
                columns.add(part.trim());
            }
            return columns;
        }

        // Try pipe separator
        if (line.contains("|")) {
            final String[] parts = line.split("\\|");
            for (final String part : parts) {
                columns.add(part.trim());
            }
            return columns;
        }

        // Default: split by single space (fallback)
        final String[] parts = line.split("\\s+");
        for (final String part : parts) {
            if (!part.isBlank()) {
                columns.add(part.trim());
            }
        }

        return columns;
    }

    /** Detect column types from headers */
    private Map<Integer, String> detectColumnTypes(final List<String> headers) {
        final Map<Integer, String> columnTypes = new HashMap<>();

        for (int i = 0; i < headers.size(); i++) {
            final String header = headers.get(i).toLowerCase(Locale.ROOT);

            if (header.contains("date")) {
                columnTypes.put(i, "date");
            } else if (header.contains(AMOUNT)
                    || header.contains("debit")
                    || header.contains("credit")
                    || header.contains("balance")) {
                columnTypes.put(i, AMOUNT);
            } else if (header.contains(DESCRIPTION)
                    || header.contains("details")
                    || header.contains("memo")
                    || header.contains("narration")) {
                columnTypes.put(i, DESCRIPTION);
            } else if (header.contains("account") || header.contains("card")) {
                columnTypes.put(i, "account");
            } else if (header.contains(CATEGORY) || header.contains("type")) {
                columnTypes.put(i, CATEGORY);
            } else {
                columnTypes.put(i, "text");
            }
        }

        return columnTypes;
    }

    /** Extract data rows from OCR text */
    private List<List<String>> extractDataRows(final String[] lines, final int headerIndex) {
        final List<List<String>> dataRows = new ArrayList<>();

        // Start from line after header (or line 0 if no header)
        final int startIndex = headerIndex >= 0 ? headerIndex + 1 : 0;

        for (int i = startIndex; i < lines.length; i++) {
            final String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }

            // Skip lines that look like headers (too many keywords)
            if (countHeaderKeywords(line) >= 2 && i > headerIndex + 5) {
                continue; // Likely a repeated header
            }

            // Parse row
            final List<String> columns = parseRow(line);

            // Only add rows with at least 2 columns (likely data)
            if (columns.size() >= 2) {
                dataRows.add(columns);
            }
        }

        return dataRows;
    }

    /** Extract account information from table structure */
    public Map<String, String> extractAccountInfoFromTable(final TableStructure table) {
        final Map<String, String> accountInfo = new HashMap<>();

        // Check headers for account information
        for (int i = 0; i < table.getHeaders().size(); i++) {
            final String header = table.getHeaders().get(i).toLowerCase(Locale.ROOT);

            if (header.contains("account number")
                    || header.contains("account #")
                    || header.contains("card number")
                    || header.contains("card #")) {
                // Look for account number in first few data rows
                final String accountNum = findAccountNumberInColumn(table.getRows(), i);
                if (accountNum != null) {
                    accountInfo.put("accountNumber", accountNum);
                }
            }

            if (header.contains("institution") || header.contains("bank")) {
                final String institution = findInstitutionInColumn(table.getRows(), i);
                if (institution != null) {
                    accountInfo.put("institutionName", institution);
                }
            }
        }

        return accountInfo;
    }

    /** Find account number in a specific column */
    private String findAccountNumberInColumn(final List<List<String>> rows, final int columnIndex) {
        if (rows == null || rows.isEmpty() || columnIndex < 0) {
            return null;
        }

        for (int i = 0; i < Math.min(5, rows.size()); i++) {
            // CRITICAL FIX: Bounds check
            if (i >= rows.size()) {
                break;
            }

            final List<String> row = rows.get(i);
            if (row == null) {
                continue;
            }

            // CRITICAL FIX: Bounds check for column index
            if (columnIndex < row.size()) {
                final String value = row.get(columnIndex);
                final String accountNum = extractAccountNumber(value);
                if (accountNum != null) {
                    return accountNum;
                }
            }
        }
        return null;
    }

    /** Find institution name in a specific column */
    private String findInstitutionInColumn(final List<List<String>> rows, final int columnIndex) {
        if (rows == null || rows.isEmpty() || columnIndex < 0) {
            return null;
        }

        for (int i = 0; i < Math.min(3, rows.size()); i++) {
            // CRITICAL FIX: Bounds check
            if (i >= rows.size()) {
                break;
            }

            final List<String> row = rows.get(i);
            if (row == null) {
                continue;
            }

            // CRITICAL FIX: Bounds check for column index
            if (columnIndex < row.size()) {
                final String value = row.get(columnIndex);
                if (value != null && !value.isBlank() && value.length() > 3) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    /** Extract account number from value string */
    private String extractAccountNumber(final String value) {
        if (value == null) {
            return null;
        }

        // Remove non-digit characters except spaces and dashes
        final String cleaned = value.replaceAll("[^\\d\\s-]", "");

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
