package com.budgetbuddy.stripe;

import com.budgetbuddy.compliance.pcidss.PCIDSSComplianceService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.param.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for Stripe Service
 */
@ExtendWith(MockitoExtension.class)
class StripeServiceTest {

    @Mock
    private PCIDSSComplianceService pciDSSComplianceService;

    @Mock
    private PaymentIntent paymentIntent;

    @Mock
    private Customer customer;

    @Mock
    private PaymentMethod paymentMethod;

    @Mock
    private Refund refund;

    private StripeService service;
    private String secretKey = "sk_test_testkey123";

    @BeforeEach
    void setUp() {
        service = new StripeService(secretKey, pciDSSComplianceService);
    }

    @Test
    void testCreatePaymentIntent_WithValidInput_ReturnsPaymentIntent() throws StripeException {
        // Given
        Map<String, String> metadata = new HashMap<>();
        metadata.put("orderId", "order-123");
        
        PaymentIntent createdIntent = mock(PaymentIntent.class);
        when(createdIntent.getId()).thenReturn("pi_test123");
        // Only stub methods that are actually used in the test
        // getAmount() and getStatus() are not used in this test, so we don't stub them
        
        try (MockedStatic<PaymentIntent> paymentIntentMock = mockStatic(PaymentIntent.class)) {
            paymentIntentMock.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenReturn(createdIntent);
            
            // When
            PaymentIntent result = service.createPaymentIntent("user-123", 1000L, "usd", "Test payment", metadata);
            
            // Then
            assertNotNull(result);
            assertEquals("pi_test123", result.getId());
            verify(pciDSSComplianceService).logCardholderDataAccess("user-123", "PAYMENT_INTENT", "CREATE", true);
        }
    }

    @Test
    void testCreatePaymentIntent_WithStripeException_ThrowsAppException() throws StripeException {
        // Given
        CardException stripeException = mock(CardException.class);
        when(stripeException.getCode()).thenReturn("card_declined");
        when(stripeException.getMessage()).thenReturn("Card was declined");
        
        try (MockedStatic<PaymentIntent> paymentIntentMock = mockStatic(PaymentIntent.class)) {
            paymentIntentMock.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenThrow(stripeException);
            
            // When/Then
            AppException exception = assertThrows(AppException.class, () -> {
                service.createPaymentIntent("user-123", 1000L, "usd", "Test payment", null);
            });
            
            assertEquals(ErrorCode.STRIPE_CARD_DECLINED, exception.getErrorCode());
        }
    }

    @Test
    void testConfirmPaymentIntent_WithValidInput_ReturnsPaymentIntent() throws StripeException {
        // Given
        PaymentIntent existingIntent = mock(PaymentIntent.class);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("userId", "user-123");
        when(existingIntent.getMetadata()).thenReturn(metadata);
        
        PaymentIntent confirmedIntent = mock(PaymentIntent.class);
        when(confirmedIntent.getId()).thenReturn("pi_test123");
        when(confirmedIntent.getStatus()).thenReturn("succeeded");
        
        try (MockedStatic<PaymentIntent> paymentIntentMock = mockStatic(PaymentIntent.class)) {
            paymentIntentMock.when(() -> PaymentIntent.retrieve("pi_test123"))
                    .thenReturn(existingIntent);
            when(existingIntent.confirm(any(PaymentIntentConfirmParams.class)))
                    .thenReturn(confirmedIntent);
            
            // When
            PaymentIntent result = service.confirmPaymentIntent("pi_test123", "pm_test123");
            
            // Then
            assertNotNull(result);
            assertEquals("succeeded", result.getStatus());
            verify(pciDSSComplianceService).logCardholderDataAccess("user-123", "PAYMENT_INTENT", "CONFIRM", true);
        }
    }

    @Test
    void testCreateCustomer_WithValidInput_ReturnsCustomer() throws StripeException {
        // Given
        Map<String, String> metadata = new HashMap<>();
        Customer createdCustomer = mock(Customer.class);
        when(createdCustomer.getId()).thenReturn("cus_test123");
        when(createdCustomer.getEmail()).thenReturn("test@example.com");
        
        try (MockedStatic<Customer> customerMock = mockStatic(Customer.class)) {
            customerMock.when(() -> Customer.create(any(CustomerCreateParams.class)))
                    .thenReturn(createdCustomer);
            
            // When
            Customer result = service.createCustomer("user-123", "test@example.com", "John Doe", metadata);
            
            // Then
            assertNotNull(result);
            assertEquals("cus_test123", result.getId());
            assertEquals("test@example.com", result.getEmail());
        }
    }

    @Test
    void testCreatePaymentMethod_WithValidCard_ReturnsPaymentMethod() throws StripeException {
        // Given
        Map<String, Object> cardDetails = new HashMap<>();
        cardDetails.put("number", "4242424242424242");
        cardDetails.put("expMonth", 12L);
        cardDetails.put("expYear", 2025L);
        cardDetails.put("cvc", "123");
        
        PaymentMethod createdMethod = mock(PaymentMethod.class);
        when(createdMethod.getId()).thenReturn("pm_test123");
        // getType() is not used in this test, so we don't stub it
        
        try (MockedStatic<PaymentMethod> paymentMethodMock = mockStatic(PaymentMethod.class)) {
            paymentMethodMock.when(() -> PaymentMethod.create(any(PaymentMethodCreateParams.class)))
                    .thenReturn(createdMethod);
            
            // When
            PaymentMethod result = service.createPaymentMethod("card", cardDetails);
            
            // Then
            assertNotNull(result);
            assertEquals("pm_test123", result.getId());
            // Verify maskPAN is called with the card number from cardDetails
            verify(pciDSSComplianceService).maskPAN("4242424242424242");
        }
    }

    @Test
    void testCreateRefund_WithValidInput_ReturnsRefund() throws StripeException {
        // Given
        Refund createdRefund = mock(Refund.class);
        when(createdRefund.getId()).thenReturn("re_test123");
        when(createdRefund.getAmount()).thenReturn(1000L);
        
        try (MockedStatic<Refund> refundMock = mockStatic(Refund.class)) {
            refundMock.when(() -> Refund.create(any(RefundCreateParams.class)))
                    .thenReturn(createdRefund);
            
            // When
            Refund result = service.createRefund("ch_test123", 1000L, "duplicate");
            
            // Then
            assertNotNull(result);
            assertEquals("re_test123", result.getId());
            assertEquals(1000L, result.getAmount());
        }
    }

    @Test
    void testCreateRefund_WithDifferentReasons_HandlesCorrectly() throws StripeException {
        // Given
        Refund createdRefund = mock(Refund.class);
        when(createdRefund.getId()).thenReturn("re_test123");
        
        try (MockedStatic<Refund> refundMock = mockStatic(Refund.class)) {
            refundMock.when(() -> Refund.create(any(RefundCreateParams.class)))
                    .thenReturn(createdRefund);
            
            // Test different reasons
            service.createRefund("ch_test123", 1000L, "fraudulent");
            service.createRefund("ch_test123", 1000L, "requested_by_customer");
            service.createRefund("ch_test123", 1000L, "unknown_reason");
            service.createRefund("ch_test123", 1000L, null);
            
            // Then - Should not throw exception
            assertTrue(true);
        }
    }

    @Test
    void testHandleStripeException_WithInsufficientFunds_ThrowsAppException() throws StripeException {
        // Given
        CardException stripeException = mock(CardException.class);
        when(stripeException.getCode()).thenReturn("insufficient_funds");
        when(stripeException.getMessage()).thenReturn("Insufficient funds");
        
        try (MockedStatic<PaymentIntent> paymentIntentMock = mockStatic(PaymentIntent.class)) {
            paymentIntentMock.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenThrow(stripeException);
            
            // When/Then
            AppException exception = assertThrows(AppException.class, () -> {
                service.createPaymentIntent("user-123", 1000L, "usd", "Test payment", null);
            });
            
            assertEquals(ErrorCode.STRIPE_INSUFFICIENT_FUNDS, exception.getErrorCode());
        }
    }

    @Test
    void testHandleStripeException_WithInvalidCard_ThrowsAppException() throws StripeException {
        // Given
        CardException stripeException = mock(CardException.class);
        when(stripeException.getCode()).thenReturn("invalid_card");
        when(stripeException.getMessage()).thenReturn("Invalid card");
        
        try (MockedStatic<PaymentMethod> paymentMethodMock = mockStatic(PaymentMethod.class)) {
            paymentMethodMock.when(() -> PaymentMethod.create(any(PaymentMethodCreateParams.class)))
                    .thenThrow(stripeException);
            
            // When/Then
            AppException exception = assertThrows(AppException.class, () -> {
                Map<String, Object> cardDetails = new HashMap<>();
                cardDetails.put("number", "4242424242424242");
                service.createPaymentMethod("card", cardDetails);
            });
            
            assertEquals(ErrorCode.STRIPE_INVALID_CARD, exception.getErrorCode());
        }
    }

    @Test
    void testHandleStripeException_WithRateLimit_ThrowsAppException() throws StripeException {
        // Given
        RateLimitException stripeException = mock(RateLimitException.class);
        when(stripeException.getCode()).thenReturn("rate_limit");
        when(stripeException.getMessage()).thenReturn("Rate limit exceeded");
        
        try (MockedStatic<PaymentIntent> paymentIntentMock = mockStatic(PaymentIntent.class)) {
            paymentIntentMock.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenThrow(stripeException);
            
            // When/Then
            AppException exception = assertThrows(AppException.class, () -> {
                service.createPaymentIntent("user-123", 1000L, "usd", "Test payment", null);
            });
            
            assertEquals(ErrorCode.STRIPE_RATE_LIMIT_EXCEEDED, exception.getErrorCode());
        }
    }

    @Test
    void testHandleStripeException_WithInvalidApiKey_ThrowsAppException() throws StripeException {
        // Given
        InvalidRequestException stripeException = mock(InvalidRequestException.class);
        when(stripeException.getCode()).thenReturn("api_key_expired");
        when(stripeException.getMessage()).thenReturn("API key expired");
        
        try (MockedStatic<PaymentIntent> paymentIntentMock = mockStatic(PaymentIntent.class)) {
            paymentIntentMock.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenThrow(stripeException);
            
            // When/Then
            AppException exception = assertThrows(AppException.class, () -> {
                service.createPaymentIntent("user-123", 1000L, "usd", "Test payment", null);
            });
            
            assertEquals(ErrorCode.STRIPE_INVALID_API_KEY, exception.getErrorCode());
        }
    }

    @Test
    void testHandleStripeException_WithDefaultError_ThrowsAppException() throws StripeException {
        // Given
        InvalidRequestException stripeException = mock(InvalidRequestException.class);
        when(stripeException.getCode()).thenReturn("unknown_error");
        when(stripeException.getMessage()).thenReturn("Unknown error");
        
        try (MockedStatic<PaymentIntent> paymentIntentMock = mockStatic(PaymentIntent.class)) {
            paymentIntentMock.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenThrow(stripeException);
            
            // When/Then
            AppException exception = assertThrows(AppException.class, () -> {
                service.createPaymentIntent("user-123", 1000L, "usd", "Test payment", null);
            });
            
            assertEquals(ErrorCode.STRIPE_CONNECTION_FAILED, exception.getErrorCode());
        }
    }
}

