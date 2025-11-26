package com.budgetbuddy.plaid;

import com.budgetbuddy.compliance.pcidss.PCIDSSComplianceService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.plaid.client.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import retrofit2.Call;
import retrofit2.Response;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    private String testUserId;
    private String testClientName;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        testClientName = "Test Client";
    }

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
