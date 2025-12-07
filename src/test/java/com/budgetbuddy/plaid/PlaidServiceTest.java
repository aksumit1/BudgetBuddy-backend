package com.budgetbuddy.plaid;

import com.budgetbuddy.compliance.pcidss.PCIDSSComplianceService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for PlaidService
 * 
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class PlaidServiceTest {

    @Mock
    private PCIDSSComplianceService pciDSSComplianceService;

    private PlaidService plaidService;

    @Test
    void testPlaidService_Constructor_WithNullClientId_UsesPlaceholder() {
        // When/Then - Constructor now allows null/empty and uses placeholders (will fail on actual API calls)
        // This allows service creation for scripts/analysis without credentials
        assertDoesNotThrow(() -> {
            new PlaidService(null, "secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "https://api.budgetbuddy.com/api/plaid/webhooks", true, pciDSSComplianceService);
        }, "Should create PlaidService with null clientId (uses placeholder)");
    }

    @Test
    void testPlaidService_Constructor_WithEmptyClientId_UsesPlaceholder() {
        // When/Then - Constructor now allows null/empty and uses placeholders (will fail on actual API calls)
        assertDoesNotThrow(() -> {
            new PlaidService("", "secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "https://api.budgetbuddy.com/api/plaid/webhooks", true, pciDSSComplianceService);
        }, "Should create PlaidService with empty clientId (uses placeholder)");
    }

    @Test
    void testPlaidService_Constructor_WithNullSecret_UsesPlaceholder() {
        // When/Then - Constructor now allows null/empty and uses placeholders (will fail on actual API calls)
        assertDoesNotThrow(() -> {
            new PlaidService("clientId", null, "sandbox", "https://app.budgetbuddy.com/plaid/callback", "https://api.budgetbuddy.com/api/plaid/webhooks", true, pciDSSComplianceService);
        }, "Should create PlaidService with null secret (uses placeholder)");
    }

    @Test
    void testPlaidService_Constructor_WithEmptySecret_UsesPlaceholder() {
        // When/Then - Constructor now allows null/empty and uses placeholders (will fail on actual API calls)
        assertDoesNotThrow(() -> {
            new PlaidService("clientId", "", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "https://api.budgetbuddy.com/api/plaid/webhooks", true, pciDSSComplianceService);
        }, "Should create PlaidService with empty secret (uses placeholder)");
    }

    @Test
    void testPlaidService_Constructor_WithNullPCIDSSService_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            new PlaidService("clientId", "secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "https://api.budgetbuddy.com/api/plaid/webhooks", true, null);
        });
    }

    @Test
    void testPlaidService_Constructor_WithValidInput_CreatesService() {
        // When/Then - Should not throw exception with valid input
        assertDoesNotThrow(() -> {
            new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "https://api.budgetbuddy.com/api/plaid/webhooks", true, pciDSSComplianceService);
        }, "Should create PlaidService with valid input");
    }

    @Test
    void testPlaidService_Constructor_WithPlaidDisabled_AllowsPlaceholder() {
        // When/Then - Should not throw exception when Plaid is disabled
        assertDoesNotThrow(() -> {
            new PlaidService("placeholder-client-id", "placeholder-secret", "sandbox", "", "", false, pciDSSComplianceService);
        }, "Should create PlaidService with placeholders when Plaid is disabled");
    }

    @Test
    void testCreateLinkToken_WithNullUserId_ThrowsException() {
        // Given
        plaidService = new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "", true, pciDSSComplianceService);

        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> plaidService.createLinkToken(null, "Test Client"));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCreateLinkToken_WithEmptyUserId_ThrowsException() {
        // Given
        plaidService = new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "", true, pciDSSComplianceService);

        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> plaidService.createLinkToken("", "Test Client"));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCreateLinkToken_WithNullClientName_ThrowsException() {
        // Given
        plaidService = new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "", true, pciDSSComplianceService);

        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> plaidService.createLinkToken("user-123", null));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCreateLinkToken_WithEmptyClientName_ThrowsException() {
        // Given
        plaidService = new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "", true, pciDSSComplianceService);

        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> plaidService.createLinkToken("user-123", ""));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testExchangePublicToken_WithNullToken_ThrowsException() {
        // Given
        plaidService = new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "", true, pciDSSComplianceService);

        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> plaidService.exchangePublicToken(null));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testExchangePublicToken_WithEmptyToken_ThrowsException() {
        // Given
        plaidService = new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "", true, pciDSSComplianceService);

        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> plaidService.exchangePublicToken(""));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testGetAccounts_WithNullAccessToken_ThrowsException() {
        // Given
        plaidService = new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "", true, pciDSSComplianceService);

        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> plaidService.getAccounts(null));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testGetAccounts_WithEmptyAccessToken_ThrowsException() {
        // Given
        plaidService = new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "", true, pciDSSComplianceService);

        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> plaidService.getAccounts(""));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testGetTransactions_WithNullAccessToken_ThrowsException() {
        // Given
        plaidService = new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "", true, pciDSSComplianceService);

        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> plaidService.getTransactions(null, "2024-01-01", "2024-01-31"));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testGetTransactions_WithEmptyAccessToken_ThrowsException() {
        // Given
        plaidService = new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "", true, pciDSSComplianceService);

        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> plaidService.getTransactions("", "2024-01-01", "2024-01-31"));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testGetTransactions_WithNullStartDate_ThrowsException() {
        // Given
        plaidService = new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "", true, pciDSSComplianceService);

        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> plaidService.getTransactions("access-token", null, "2024-01-31"));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testGetTransactions_WithNullEndDate_ThrowsException() {
        // Given
        plaidService = new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "", true, pciDSSComplianceService);

        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> plaidService.getTransactions("access-token", "2024-01-01", null));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testGetInstitutions_WithNullQuery_ThrowsException() {
        // Given
        plaidService = new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "", true, pciDSSComplianceService);

        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> plaidService.getInstitutions(null, 10));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testGetInstitutions_WithEmptyQuery_ThrowsException() {
        // Given
        plaidService = new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "", true, pciDSSComplianceService);

        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> plaidService.getInstitutions("", 10));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testRemoveItem_WithNullAccessToken_ThrowsException() {
        // Given
        plaidService = new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "", true, pciDSSComplianceService);

        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> plaidService.removeItem(null));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testRemoveItem_WithEmptyAccessToken_ThrowsException() {
        // Given
        plaidService = new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "", true, pciDSSComplianceService);

        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> plaidService.removeItem(""));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testGetTransactions_WithEmptyStartDate_ThrowsException() {
        // Given
        plaidService = new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "", true, pciDSSComplianceService);

        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> plaidService.getTransactions("access-token", "", "2024-01-31"));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testGetTransactions_WithEmptyEndDate_ThrowsException() {
        // Given
        plaidService = new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "", true, pciDSSComplianceService);

        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> plaidService.getTransactions("access-token", "2024-01-01", ""));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testGetTransactions_WithStartDateAfterEndDate_ThrowsException() {
        // Given
        plaidService = new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "", true, pciDSSComplianceService);

        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> plaidService.getTransactions("access-token", "2024-01-31", "2024-01-01"));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Start date"));
    }

    @Test
    void testGetInstitutions_WithInvalidCount_ThrowsException() {
        // Given
        plaidService = new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "", true, pciDSSComplianceService);

        // When/Then - count <= 0
        AppException exception1 = assertThrows(AppException.class, 
                () -> plaidService.getInstitutions("chase", 0));
        assertEquals(ErrorCode.INVALID_INPUT, exception1.getErrorCode());

        // When/Then - count > 500
        AppException exception2 = assertThrows(AppException.class, 
                () -> plaidService.getInstitutions("chase", 501));
        assertEquals(ErrorCode.INVALID_INPUT, exception2.getErrorCode());
    }

    @Test
    void testPlaidService_Constructor_WithProductionEnvironment_CreatesService() {
        // When/Then
        assertDoesNotThrow(() -> {
            new PlaidService("test-client-id", "test-secret", "production", "https://app.budgetbuddy.com/plaid/callback", "https://api.budgetbuddy.com/api/plaid/webhooks", true, pciDSSComplianceService);
        }, "Should create PlaidService with production environment");
    }

    @Test
    void testPlaidService_Constructor_WithDevelopmentEnvironment_CreatesService() {
        // When/Then
        assertDoesNotThrow(() -> {
            new PlaidService("test-client-id", "test-secret", "development", "https://app.budgetbuddy.com/plaid/callback", "https://api.budgetbuddy.com/api/plaid/webhooks", true, pciDSSComplianceService);
        }, "Should create PlaidService with development environment");
    }

    @Test
    void testPlaidService_Constructor_WithPlaceholderClientId_WhenPlaidDisabled_NoWarning() {
        // When/Then - Should not throw and should use placeholder silently when Plaid is disabled
        assertDoesNotThrow(() -> {
            new PlaidService("placeholder-client-id", "placeholder-secret", "sandbox", "", "", false, pciDSSComplianceService);
        }, "Should create PlaidService with placeholders when Plaid is disabled");
    }

    @Test
    void testPlaidService_Constructor_WithPlaceholderSecret_WhenPlaidDisabled_NoWarning() {
        // When/Then - Should not throw and should use placeholder silently when Plaid is disabled
        assertDoesNotThrow(() -> {
            new PlaidService("test-client-id", "placeholder-secret", "sandbox", "", "", false, pciDSSComplianceService);
        }, "Should create PlaidService with placeholder secret when Plaid is disabled");
    }
}
