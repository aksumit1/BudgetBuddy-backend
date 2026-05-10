package com.budgetbuddy.stripe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.compliance.pcidss.PCIDSSComplianceService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Refund;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentConfirmParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentMethodCreateParams;
import com.stripe.param.RefundCreateParams;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit Tests for Stripe Service */
@ExtendWith(MockitoExtension.class)
class StripeServiceTest {

    @Mock private PCIDSSComplianceService pciDSSComplianceService;

    @Mock private PaymentIntent paymentIntent;

    @Mock private Customer customer;

    @Mock private PaymentMethod paymentMethod;

    @Mock private Refund refund;

    private StripeService service;
    private String secretKey = "sk_test_testkey123";

    @BeforeEach
    void setUp() {
        service = new StripeService(secretKey, true, pciDSSComplianceService);
    }

    @Test
    void testCreatePaymentIntentWithValidInputReturnsPaymentIntent() throws StripeException {
        // Given
        final Map<String, String> metadata = new HashMap<>();
        metadata.put("orderId", "order-123");

        final PaymentIntent createdIntent = mock(PaymentIntent.class);
        when(createdIntent.getId()).thenReturn("pi_test123");
        // Only stub methods that are actually used in the test
        // getAmount() and getStatus() are not used in this test, so we don't stub them

        try (MockedStatic<PaymentIntent> paymentIntentMock = mockStatic(PaymentIntent.class)) {
            paymentIntentMock
                    .when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenReturn(createdIntent);

            // When
            final PaymentIntent result =
                    service.createPaymentIntent("user-123", 1000L, "usd", "Test payment", metadata);

            // Then
            assertNotNull(result);
            assertEquals("pi_test123", result.getId());
            verify(pciDSSComplianceService)
                    .logCardholderDataAccess("user-123", "PAYMENT_INTENT", "CREATE", true);
        }
    }

    @Test
    void testCreatePaymentIntentWithStripeExceptionThrowsAppException() throws StripeException {
        // Given
        final CardException stripeException = mock(CardException.class);
        when(stripeException.getCode()).thenReturn("card_declined");
        when(stripeException.getMessage()).thenReturn("Card was declined");

        try (MockedStatic<PaymentIntent> paymentIntentMock = mockStatic(PaymentIntent.class)) {
            paymentIntentMock
                    .when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenThrow(stripeException);

            // When/Then
            final AppException exception =
                    assertThrows(
                            AppException.class,
                            () -> {
                                service.createPaymentIntent(
                                        "user-123", 1000L, "usd", "Test payment", null);
                            });

            assertEquals(ErrorCode.STRIPE_CARD_DECLINED, exception.getErrorCode());
        }
    }

    @Test
    void testConfirmPaymentIntentWithValidInputReturnsPaymentIntent() throws StripeException {
        // Given
        final PaymentIntent existingIntent = mock(PaymentIntent.class);
        final Map<String, String> metadata = new HashMap<>();
        metadata.put("userId", "user-123");
        when(existingIntent.getMetadata()).thenReturn(metadata);

        final PaymentIntent confirmedIntent = mock(PaymentIntent.class);
        when(confirmedIntent.getId()).thenReturn("pi_test123");
        when(confirmedIntent.getStatus()).thenReturn("succeeded");

        try (MockedStatic<PaymentIntent> paymentIntentMock = mockStatic(PaymentIntent.class)) {
            paymentIntentMock
                    .when(() -> PaymentIntent.retrieve("pi_test123"))
                    .thenReturn(existingIntent);
            when(existingIntent.confirm(any(PaymentIntentConfirmParams.class)))
                    .thenReturn(confirmedIntent);

            // When
            final PaymentIntent result = service.confirmPaymentIntent("pi_test123", "pm_test123");

            // Then
            assertNotNull(result);
            assertEquals("succeeded", result.getStatus());
            verify(pciDSSComplianceService)
                    .logCardholderDataAccess("user-123", "PAYMENT_INTENT", "CONFIRM", true);
        }
    }

    @Test
    void testCreateCustomerWithValidInputReturnsCustomer() throws StripeException {
        // Given
        final Map<String, String> metadata = new HashMap<>();
        final Customer createdCustomer = mock(Customer.class);
        when(createdCustomer.getId()).thenReturn("cus_test123");
        when(createdCustomer.getEmail()).thenReturn("test@example.com");

        try (MockedStatic<Customer> customerMock = mockStatic(Customer.class)) {
            customerMock
                    .when(() -> Customer.create(any(CustomerCreateParams.class)))
                    .thenReturn(createdCustomer);

            // When
            final Customer result =
                    service.createCustomer("user-123", "test@example.com", "John Doe", metadata);

            // Then
            assertNotNull(result);
            assertEquals("cus_test123", result.getId());
            assertEquals("test@example.com", result.getEmail());
        }
    }

    @Test
    void testCreatePaymentMethodWithValidCardReturnsPaymentMethod() throws StripeException {
        // Given
        final Map<String, Object> cardDetails = new HashMap<>();
        cardDetails.put("number", "4242424242424242");
        cardDetails.put("expMonth", 12L);
        cardDetails.put("expYear", 2025L);
        cardDetails.put("cvc", "123");

        final PaymentMethod createdMethod = mock(PaymentMethod.class);
        when(createdMethod.getId()).thenReturn("pm_test123");
        // getType() is not used in this test, so we don't stub it

        try (MockedStatic<PaymentMethod> paymentMethodMock = mockStatic(PaymentMethod.class)) {
            paymentMethodMock
                    .when(() -> PaymentMethod.create(any(PaymentMethodCreateParams.class)))
                    .thenReturn(createdMethod);

            // When
            final PaymentMethod result = service.createPaymentMethod("card", cardDetails);

            // Then
            assertNotNull(result);
            assertEquals("pm_test123", result.getId());
            // Verify maskPAN is called with the card number from cardDetails
            verify(pciDSSComplianceService).maskPAN("4242424242424242");
        }
    }

    @Test
    void testCreateRefundWithValidInputReturnsRefund() throws StripeException {
        // Given
        final Refund createdRefund = mock(Refund.class);
        when(createdRefund.getId()).thenReturn("re_test123");
        when(createdRefund.getAmount()).thenReturn(1000L);

        try (MockedStatic<Refund> refundMock = mockStatic(Refund.class)) {
            refundMock
                    .when(() -> Refund.create(any(RefundCreateParams.class)))
                    .thenReturn(createdRefund);

            // When
            final Refund result = service.createRefund("ch_test123", 1000L, "duplicate");

            // Then
            assertNotNull(result);
            assertEquals("re_test123", result.getId());
            assertEquals(1000L, result.getAmount());
        }
    }

    @Test
    void testCreateRefundWithDifferentReasonsHandlesCorrectly() throws StripeException {
        // Given
        final Refund createdRefund = mock(Refund.class);
        when(createdRefund.getId()).thenReturn("re_test123");

        try (MockedStatic<Refund> refundMock = mockStatic(Refund.class)) {
            refundMock
                    .when(() -> Refund.create(any(RefundCreateParams.class)))
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
    void testHandleStripeExceptionWithInsufficientFundsThrowsAppException()
            throws StripeException {
        // Given
        final CardException stripeException = mock(CardException.class);
        when(stripeException.getCode()).thenReturn("insufficient_funds");
        when(stripeException.getMessage()).thenReturn("Insufficient funds");

        try (MockedStatic<PaymentIntent> paymentIntentMock = mockStatic(PaymentIntent.class)) {
            paymentIntentMock
                    .when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenThrow(stripeException);

            // When/Then
            final AppException exception =
                    assertThrows(
                            AppException.class,
                            () -> {
                                service.createPaymentIntent(
                                        "user-123", 1000L, "usd", "Test payment", null);
                            });

            assertEquals(ErrorCode.STRIPE_INSUFFICIENT_FUNDS, exception.getErrorCode());
        }
    }

    @Test
    void testHandleStripeExceptionWithInvalidCardThrowsAppException() throws StripeException {
        // Given
        final CardException stripeException = mock(CardException.class);
        when(stripeException.getCode()).thenReturn("invalid_card");
        when(stripeException.getMessage()).thenReturn("Invalid card");

        try (MockedStatic<PaymentMethod> paymentMethodMock = mockStatic(PaymentMethod.class)) {
            paymentMethodMock
                    .when(() -> PaymentMethod.create(any(PaymentMethodCreateParams.class)))
                    .thenThrow(stripeException);

            // When/Then
            final AppException exception =
                    assertThrows(
                            AppException.class,
                            () -> {
                                final Map<String, Object> cardDetails = new HashMap<>();
                                cardDetails.put("number", "4242424242424242");
                                service.createPaymentMethod("card", cardDetails);
                            });

            assertEquals(ErrorCode.STRIPE_INVALID_CARD, exception.getErrorCode());
        }
    }

    @Test
    void testHandleStripeExceptionWithRateLimitThrowsAppException() throws StripeException {
        // Given
        final RateLimitException stripeException = mock(RateLimitException.class);
        when(stripeException.getCode()).thenReturn("rate_limit");
        when(stripeException.getMessage()).thenReturn("Rate limit exceeded");

        try (MockedStatic<PaymentIntent> paymentIntentMock = mockStatic(PaymentIntent.class)) {
            paymentIntentMock
                    .when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenThrow(stripeException);

            // When/Then
            final AppException exception =
                    assertThrows(
                            AppException.class,
                            () -> {
                                service.createPaymentIntent(
                                        "user-123", 1000L, "usd", "Test payment", null);
                            });

            assertEquals(ErrorCode.STRIPE_RATE_LIMIT_EXCEEDED, exception.getErrorCode());
        }
    }

    @Test
    void testHandleStripeExceptionWithInvalidApiKeyThrowsAppException() throws StripeException {
        // Given
        final InvalidRequestException stripeException = mock(InvalidRequestException.class);
        when(stripeException.getCode()).thenReturn("api_key_expired");
        when(stripeException.getMessage()).thenReturn("API key expired");

        try (MockedStatic<PaymentIntent> paymentIntentMock = mockStatic(PaymentIntent.class)) {
            paymentIntentMock
                    .when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenThrow(stripeException);

            // When/Then
            final AppException exception =
                    assertThrows(
                            AppException.class,
                            () -> {
                                service.createPaymentIntent(
                                        "user-123", 1000L, "usd", "Test payment", null);
                            });

            assertEquals(ErrorCode.STRIPE_INVALID_API_KEY, exception.getErrorCode());
        }
    }

    @Test
    void testHandleStripeExceptionWithDefaultErrorThrowsAppException() throws StripeException {
        // Given
        final InvalidRequestException stripeException = mock(InvalidRequestException.class);
        when(stripeException.getCode()).thenReturn("unknown_error");
        when(stripeException.getMessage()).thenReturn("Unknown error");

        try (MockedStatic<PaymentIntent> paymentIntentMock = mockStatic(PaymentIntent.class)) {
            paymentIntentMock
                    .when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenThrow(stripeException);

            // When/Then
            final AppException exception =
                    assertThrows(
                            AppException.class,
                            () -> {
                                service.createPaymentIntent(
                                        "user-123", 1000L, "usd", "Test payment", null);
                            });

            assertEquals(ErrorCode.STRIPE_CONNECTION_FAILED, exception.getErrorCode());
        }
    }
}
