package com.budgetbuddy.integration;

import com.budgetbuddy.config.JacksonConfig;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to verify that all date fields in API responses are serialized in ISO 8601 format.
 * 
 * This ensures consistency with iOS app expectations and eliminates the need for date conversion logic in iOS.
 */
@DisplayName("ISO 8601 Date Format Integration Tests")
public class ISODateFormatIntegrationTest {

    private ObjectMapper objectMapper;

    // ISO 8601 pattern for Instant (with timezone): "2025-12-29T17:20:44.123Z"
    private static final Pattern ISO_INSTANT_PATTERN = Pattern.compile(
        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$"
    );

    // ISO 8601 pattern for date-only: "2025-12-29"
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile(
        "^\\d{4}-\\d{2}-\\d{2}$"
    );

    @BeforeEach
    void setUp() {
        // Use the same ObjectMapper configuration as the application
        JacksonConfig config = new JacksonConfig();
        objectMapper = config.objectMapper();
    }

    @Test
    @DisplayName("TransactionTable createdAt and updatedAt should be in ISO 8601 format")
    void testTransactionTable_ISODateFormat() throws Exception {
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("test-transaction-id");
        transaction.setUserId("test-user-id");
        transaction.setAccountId("test-account-id");
        transaction.setAmount(BigDecimal.valueOf(100.50));
        transaction.setDescription("Test transaction");
        transaction.setTransactionDate("2025-12-29");
        transaction.setCreatedAt(Instant.now());
        transaction.setUpdatedAt(Instant.now());

        String json = objectMapper.writeValueAsString(transaction);
        
        // Verify createdAt is in ISO format
        assertTrue(json.contains("\"createdAt\""), "JSON should contain createdAt field");
        String createdAtValue = extractFieldValue(json, "createdAt");
        assertNotNull(createdAtValue, "createdAt should not be null");
        assertTrue(ISO_INSTANT_PATTERN.matcher(createdAtValue).matches(), 
            "createdAt should be in ISO 8601 format (e.g., '2025-12-29T17:20:44.123Z'), but was: " + createdAtValue);
        
        // Verify updatedAt is in ISO format
        assertTrue(json.contains("\"updatedAt\""), "JSON should contain updatedAt field");
        String updatedAtValue = extractFieldValue(json, "updatedAt");
        assertNotNull(updatedAtValue, "updatedAt should not be null");
        assertTrue(ISO_INSTANT_PATTERN.matcher(updatedAtValue).matches(), 
            "updatedAt should be in ISO 8601 format (e.g., '2025-12-29T17:20:44.123Z'), but was: " + updatedAtValue);
        
        // Verify transactionDate is in ISO date format
        assertTrue(json.contains("\"transactionDate\""), "JSON should contain transactionDate field");
        String transactionDateValue = extractFieldValue(json, "transactionDate");
        assertNotNull(transactionDateValue, "transactionDate should not be null");
        assertTrue(ISO_DATE_PATTERN.matcher(transactionDateValue).matches(), 
            "transactionDate should be in ISO 8601 date format (e.g., '2025-12-29'), but was: " + transactionDateValue);
    }

    @Test
    @DisplayName("AccountTable createdAt, updatedAt, and lastSyncedAt should be in ISO 8601 format")
    void testAccountTable_ISODateFormat() throws Exception {
        AccountTable account = new AccountTable();
        account.setAccountId("test-account-id");
        account.setUserId("test-user-id");
        account.setAccountName("Test Account");
        account.setInstitutionName("Test Bank");
        account.setAccountType("depository");
        account.setAccountSubtype("checking");
        account.setBalance(BigDecimal.valueOf(1000.00));
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        account.setLastSyncedAt(Instant.now());

        String json = objectMapper.writeValueAsString(account);
        
        // Verify createdAt is in ISO format
        String createdAtValue = extractFieldValue(json, "createdAt");
        assertNotNull(createdAtValue, "createdAt should not be null");
        assertTrue(ISO_INSTANT_PATTERN.matcher(createdAtValue).matches(), 
            "createdAt should be in ISO 8601 format, but was: " + createdAtValue);
        
        // Verify updatedAt is in ISO format
        String updatedAtValue = extractFieldValue(json, "updatedAt");
        assertNotNull(updatedAtValue, "updatedAt should not be null");
        assertTrue(ISO_INSTANT_PATTERN.matcher(updatedAtValue).matches(), 
            "updatedAt should be in ISO 8601 format, but was: " + updatedAtValue);
        
        // Verify lastSyncedAt is in ISO format
        String lastSyncedAtValue = extractFieldValue(json, "lastSyncedAt");
        assertNotNull(lastSyncedAtValue, "lastSyncedAt should not be null");
        assertTrue(ISO_INSTANT_PATTERN.matcher(lastSyncedAtValue).matches(), 
            "lastSyncedAt should be in ISO 8601 format, but was: " + lastSyncedAtValue);
    }

    @Test
    @DisplayName("TransactionActionTable createdAt and updatedAt should be in ISO 8601 format")
    void testTransactionActionTable_ISODateFormat() throws Exception {
        TransactionActionTable action = new TransactionActionTable();
        action.setActionId("test-action-id");
        action.setTransactionId("test-transaction-id");
        action.setUserId("test-user-id");
        action.setTitle("Test Action");
        action.setDescription("Test description");
        action.setDueDate("2025-12-30");
        action.setReminderDate("2025-12-30T10:00:00Z");
        action.setIsCompleted(false);
        action.setPriority("MEDIUM");
        action.setCreatedAt(Instant.now());
        action.setUpdatedAt(Instant.now());

        String json = objectMapper.writeValueAsString(action);
        
        // Verify createdAt is in ISO format
        String createdAtValue = extractFieldValue(json, "createdAt");
        assertNotNull(createdAtValue, "createdAt should not be null");
        assertTrue(ISO_INSTANT_PATTERN.matcher(createdAtValue).matches(), 
            "createdAt should be in ISO 8601 format, but was: " + createdAtValue);
        
        // Verify updatedAt is in ISO format
        String updatedAtValue = extractFieldValue(json, "updatedAt");
        assertNotNull(updatedAtValue, "updatedAt should not be null");
        assertTrue(ISO_INSTANT_PATTERN.matcher(updatedAtValue).matches(), 
            "updatedAt should be in ISO 8601 format, but was: " + updatedAtValue);
    }

    @Test
    @DisplayName("Dates should NOT be serialized as timestamps (epoch seconds)")
    void testDates_NotAsTimestamps() throws Exception {
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("test-transaction-id");
        transaction.setUserId("test-user-id");
        transaction.setAccountId("test-account-id");
        transaction.setAmount(BigDecimal.valueOf(100.50));
        transaction.setDescription("Test transaction");
        transaction.setTransactionDate("2025-12-29");
        transaction.setCreatedAt(Instant.now());
        transaction.setUpdatedAt(Instant.now());

        String json = objectMapper.writeValueAsString(transaction);
        
        // Verify dates are NOT numeric timestamps
        assertFalse(json.contains("\"createdAt\":1"), 
            "createdAt should not be a numeric timestamp");
        assertFalse(json.contains("\"updatedAt\":1"), 
            "updatedAt should not be a numeric timestamp");
        
        // Verify dates are strings
        assertTrue(json.contains("\"createdAt\":\""), 
            "createdAt should be a string, not a number");
        assertTrue(json.contains("\"updatedAt\":\""), 
            "updatedAt should be a string, not a number");
    }

    /**
     * Helper method to extract field value from JSON string
     */
    private String extractFieldValue(String json, String fieldName) {
        String searchPattern = "\"" + fieldName + "\":\"";
        int startIndex = json.indexOf(searchPattern);
        if (startIndex == -1) {
            // Try without quotes (for null values)
            searchPattern = "\"" + fieldName + "\":";
            startIndex = json.indexOf(searchPattern);
            if (startIndex == -1) {
                return null;
            }
            startIndex += searchPattern.length();
            int endIndex = json.indexOf(",", startIndex);
            if (endIndex == -1) {
                endIndex = json.indexOf("}", startIndex);
            }
            if (endIndex == -1) {
                return null;
            }
            String value = json.substring(startIndex, endIndex).trim();
            return value.equals("null") ? null : value;
        }
        
        startIndex += searchPattern.length();
        int endIndex = json.indexOf("\"", startIndex);
        if (endIndex == -1) {
            return null;
        }
        return json.substring(startIndex, endIndex);
    }
}

