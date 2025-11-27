package com.budgetbuddy.plaid;

import com.budgetbuddy.compliance.pcidss.PCIDSSComplianceService;
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

    @Test
    void testPlaidService_Constructor_WithNullClientId_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            new PlaidService(null, "secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "https://api.budgetbuddy.com/api/plaid/webhooks", pciDSSComplianceService);
        });
    }

    @Test
    void testPlaidService_Constructor_WithEmptyClientId_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            new PlaidService("", "secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "https://api.budgetbuddy.com/api/plaid/webhooks", pciDSSComplianceService);
        });
    }

    @Test
    void testPlaidService_Constructor_WithNullSecret_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            new PlaidService("clientId", null, "sandbox", "https://app.budgetbuddy.com/plaid/callback", "https://api.budgetbuddy.com/api/plaid/webhooks", pciDSSComplianceService);
        });
    }

    @Test
    void testPlaidService_Constructor_WithEmptySecret_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            new PlaidService("clientId", "", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "https://api.budgetbuddy.com/api/plaid/webhooks", pciDSSComplianceService);
        });
    }

    @Test
    void testPlaidService_Constructor_WithNullPCIDSSService_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            new PlaidService("clientId", "secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "https://api.budgetbuddy.com/api/plaid/webhooks", null);
        });
    }

    @Test
    void testPlaidService_Constructor_WithValidInput_CreatesService() {
        // When/Then - Should not throw exception with valid input
        assertDoesNotThrow(() -> {
            new PlaidService("test-client-id", "test-secret", "sandbox", "https://app.budgetbuddy.com/plaid/callback", "https://api.budgetbuddy.com/api/plaid/webhooks", pciDSSComplianceService);
        }, "Should create PlaidService with valid input");
    }
}
