package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Debug test to understand why integration tests are failing
 */
@DisplayName("PDFImportService Username Detection - Debug Tests")
public class PDFImportServiceUsernameDetectionDebugTest {

    private PDFImportService pdfImportService;
    private Method findUsernameCandidates;
    private Method isValidNameFormat;
    private Method detectUsernameBeforeHeader;

    @BeforeEach
    void setUp() throws Exception {
        AccountDetectionService mockAccountDetectionService = org.mockito.Mockito.mock(AccountDetectionService.class);
        ImportCategoryParser mockImportCategoryParser = org.mockito.Mockito.mock(ImportCategoryParser.class);
        TransactionTypeCategoryService mockTransactionTypeCategoryService = 
            org.mockito.Mockito.mock(TransactionTypeCategoryService.class);
        EnhancedPatternMatcher enhancedPatternMatcher = new EnhancedPatternMatcher();
        
        pdfImportService = new PDFImportService(
            mockAccountDetectionService, 
            mockImportCategoryParser, 
            mockTransactionTypeCategoryService,
            enhancedPatternMatcher,
            null
        );
        
        findUsernameCandidates = PDFImportService.class.getDeclaredMethod("findUsernameCandidates", 
            String[].class, int.class, int.class, int.class);
        findUsernameCandidates.setAccessible(true);
        
        isValidNameFormat = PDFImportService.class.getDeclaredMethod("isValidNameFormat", String.class);
        isValidNameFormat.setAccessible(true);
        
        detectUsernameBeforeHeader = PDFImportService.class.getDeclaredMethod("detectUsernameBeforeHeader", 
            String[].class, int.class, AccountDetectionService.DetectedAccount.class);
        detectUsernameBeforeHeader.setAccessible(true);
    }

    @Test
    @DisplayName("Debug: Check what candidates are found for realistic scenario")
    void debugRealisticScenario() throws Exception {
        String[] lines = {
            "Card Member: JOHN DOE",  // Index 0
            "123 Main Street",  // Index 1
            "New York, NY 10001",  // Index 2
            "Date Description Amount",  // Index 3
            "01/15/2024 LULULEMON ATHLETICA $123.45",  // Index 4 - transaction line
            "",
            "JANE SMITH",  // Index 6
            "Date Description Amount",  // Index 7
            "01/17/2024 AMAZON $50.00"  // Index 8 - transaction line
        };
        
        // Check if "JOHN DOE" is valid
        boolean johnDoeValid = (Boolean) isValidNameFormat.invoke(pdfImportService, "JOHN DOE");
        System.out.println("'JOHN DOE' is valid: " + johnDoeValid);
        
        // Check if "JANE SMITH" is valid
        boolean janeSmithValid = (Boolean) isValidNameFormat.invoke(pdfImportService, "JANE SMITH");
        System.out.println("'JANE SMITH' is valid: " + janeSmithValid);
        
        // Check candidates for first transaction (index 4)
        @SuppressWarnings("unchecked")
        List<String> candidates1 = (List<String>) findUsernameCandidates.invoke(
            pdfImportService, lines, 4, 1, 6);
        System.out.println("Candidates for index 4: " + candidates1);
        System.out.println("Range checked: indices " + Math.max(0, 4-6) + " to " + Math.max(0, 4-1));
        
        // Check candidates for second transaction (index 8)
        @SuppressWarnings("unchecked")
        List<String> candidates2 = (List<String>) findUsernameCandidates.invoke(
            pdfImportService, lines, 8, 1, 6);
        System.out.println("Candidates for index 8: " + candidates2);
        System.out.println("Range checked: indices " + Math.max(0, 8-6) + " to " + Math.max(0, 8-1));
        
        // Check what detectUsernameBeforeHeader returns
        AccountDetectionService.DetectedAccount detectedAccount = 
            new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountHolderName("John Doe");
        
        String username1 = (String) detectUsernameBeforeHeader.invoke(
            pdfImportService, lines, 4, detectedAccount);
        System.out.println("Username1 detected: " + username1);
        
        String username2 = (String) detectUsernameBeforeHeader.invoke(
            pdfImportService, lines, 8, detectedAccount);
        System.out.println("Username2 detected: " + username2);
    }

    @Test
    @DisplayName("Debug: Check what candidates are found for multi-user scenario")
    void debugMultiUserScenario() throws Exception {
        String[] lines = {
            "JOHN DOE",  // Index 0
            "123 Main St",  // Index 1
            "New York, NY 10001",  // Index 2
            "01/15/2024 MERCHANT $100.00",  // Index 3 - transaction line
            "",
            "JANE SMITH",  // Index 5
            "456 Oak Ave",  // Index 6
            "Los Angeles, CA 90001",  // Index 7
            "01/16/2024 STORE $200.00"  // Index 8 - transaction line
        };
        
        // Check candidates for first transaction (index 3)
        @SuppressWarnings("unchecked")
        List<String> candidates1 = (List<String>) findUsernameCandidates.invoke(
            pdfImportService, lines, 3, 1, 6);
        System.out.println("Candidates for index 3: " + candidates1);
        System.out.println("Range checked: indices " + Math.max(0, 3-6) + " to " + Math.max(0, 3-1));
        
        // Check candidates for second transaction (index 8)
        @SuppressWarnings("unchecked")
        List<String> candidates2 = (List<String>) findUsernameCandidates.invoke(
            pdfImportService, lines, 8, 1, 6);
        System.out.println("Candidates for index 8: " + candidates2);
        System.out.println("Range checked: indices " + Math.max(0, 8-6) + " to " + Math.max(0, 8-1));
        
        // Check what detectUsernameBeforeHeader returns
        AccountDetectionService.DetectedAccount detectedAccount = 
            new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountHolderName("John Doe");
        
        String username1 = (String) detectUsernameBeforeHeader.invoke(
            pdfImportService, lines, 3, detectedAccount);
        System.out.println("Username1 detected: " + username1);
        
        String username2 = (String) detectUsernameBeforeHeader.invoke(
            pdfImportService, lines, 8, detectedAccount);
        System.out.println("Username2 detected: " + username2);
    }
}

