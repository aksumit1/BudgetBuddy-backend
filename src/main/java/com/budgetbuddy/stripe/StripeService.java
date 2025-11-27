package com.budgetbuddy.stripe;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.compliance.pcidss.PCIDSSComplianceService;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.param.*;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Comprehensive Stripe Integration Service
 * Handles payment processing with PCI-DSS compliance
 */
@Service
public class StripeService {

    private static final Logger logger = LoggerFactory.getLogger(StripeService.class);

    private final PCIDSSComplianceService pciDSSComplianceService;

    public StripeService(
            @Value("${app.stripe.secret-key:sk_test_placeholder}") final String secretKey,
            final PCIDSSComplianceService pciDSSComplianceService) {
        Stripe.apiKey = secretKey;
        this.pciDSSComplianceService = pciDSSComplianceService;
    }

    /**
     * Create Payment Intent
     */
    @CircuitBreaker(name = "stripe")
    @Retry(name = "stripe")
    public PaymentIntent createPaymentIntent(final String userId, final long amount, final String currency, final String description, final Map<String, String> metadata) {
        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(amount)
                    .setCurrency(currency)
                    .setDescription(description)
                    .putMetadata("userId", userId)
                    .putAllMetadata(metadata != null ? metadata : new HashMap<>())
                    .build();

            PaymentIntent paymentIntent = PaymentIntent.create(params);

            // PCI-DSS: Log payment creation
            pciDSSComplianceService.logCardholderDataAccess(userId, "PAYMENT_INTENT", "CREATE", true);

            logger.info("Stripe: Payment intent created - ID: {}, Amount: {}",
                    paymentIntent.getId(), amount);
            return paymentIntent;
        } catch (StripeException e) {
            handleStripeException(e, "createPaymentIntent");
            throw new AppException(ErrorCode.STRIPE_PAYMENT_FAILED,
                    "Failed to create payment intent", Map.of("userId", userId, "amount", amount), null, e);
        }
    }

    /**
     * Confirm Payment Intent
     */
    @CircuitBreaker(name = "stripe")
    @Retry(name = "stripe")
    public PaymentIntent confirmPaymentIntent(final String paymentIntentId, final String paymentMethodId) {
        try {
            PaymentIntentConfirmParams params = PaymentIntentConfirmParams.builder()
                    .setPaymentMethod(paymentMethodId)
                    .build();

            PaymentIntent paymentIntent = PaymentIntent.retrieve(paymentIntentId);
            paymentIntent = paymentIntent.confirm(params);

            // PCI-DSS: Log payment confirmation
            pciDSSComplianceService.logCardholderDataAccess(
                    paymentIntent.getMetadata().get("userId"),
                    "PAYMENT_INTENT",
                    "CONFIRM",
                    true
            );

            logger.info("Stripe: Payment intent confirmed - ID: {}, Status: {}",
                    paymentIntent.getId(), paymentIntent.getStatus());
            return paymentIntent;
        } catch (StripeException e) {
            handleStripeException(e, "confirmPaymentIntent");
            throw new AppException(ErrorCode.STRIPE_PAYMENT_FAILED,
                    "Failed to confirm payment intent", Map.of("paymentIntentId", paymentIntentId), null, e);
        }
    }

    /**
     * Create Customer
     */
    @CircuitBreaker(name = "stripe")
    @Retry(name = "stripe")
    public Customer createCustomer(final String userId, final String email, final String name, final Map<String, String> metadata) {
        try {
            CustomerCreateParams params = CustomerCreateParams.builder()
                    .setEmail(email)
                    .setName(name)
                    .putMetadata("userId", userId)
                    .putAllMetadata(metadata != null ? metadata : new HashMap<>())
                    .build();

            Customer customer = Customer.create(params);

            logger.info("Stripe: Customer created - ID: {}, Email: {}", customer.getId(), email);
            return customer;
        } catch (StripeException e) {
            handleStripeException(e, "createCustomer");
            throw new AppException(ErrorCode.STRIPE_CONNECTION_FAILED,
                    "Failed to create customer", Map.of("userId", userId, "email", email), null, e);
        }
    }

    /**
     * Create Payment Method
     */
    @CircuitBreaker(name = "stripe")
    @Retry(name = "stripe")
    public PaymentMethod createPaymentMethod(final String type, final Map<String, Object> cardDetails) {
        try {
            PaymentMethodCreateParams.Type paymentType = "card".equalsIgnoreCase(type)
                    ? PaymentMethodCreateParams.Type.CARD
                    : PaymentMethodCreateParams.Type.CARD; // Default to card

            // Build card details map for Stripe API
            Map<String, Object> cardMap = new HashMap<>();
            cardMap.put("number", cardDetails.get("number"));
            Object expMonthObj = cardDetails.get("expMonth");
            Object expYearObj = cardDetails.get("expYear");
            if (expMonthObj instanceof Number) {
                cardMap.put("exp_month", ((Number) expMonthObj).longValue());
            }
            if (expYearObj instanceof Number) {
                cardMap.put("exp_year", ((Number) expYearObj).longValue());
            }
            cardMap.put("cvc", cardDetails.get("cvc"));

            PaymentMethodCreateParams params = PaymentMethodCreateParams.builder()
                    .setType(paymentType)
                    .putExtraParam("card", cardMap)
                    .build();

            PaymentMethod paymentMethod = PaymentMethod.create(params);

            // PCI-DSS: Mask and validate card number
            pciDSSComplianceService.maskPAN(
                    (String) cardDetails.get("number"));
            // Note: logCardDataAccess is on auditLogService, not pciDSSComplianceService

            logger.info("Stripe: Payment method created - ID: {}, Type: {}",
                    paymentMethod.getId(), type);
            return paymentMethod;
        } catch (StripeException e) {
            handleStripeException(e, "createPaymentMethod");
            throw new AppException(ErrorCode.STRIPE_INVALID_CARD,
                    "Failed to create payment method", null, null, e);
        }
    }

    /**
     * Refund Payment
     */
    @CircuitBreaker(name = "stripe")
    @Retry(name = "stripe")
    public Refund createRefund(final String chargeId, final Long amount, final String reason) {
        try {
            RefundCreateParams.Reason refundReason = null;
            if (reason != null) {
                switch (reason.toLowerCase()) {
                    case "duplicate":
                        refundReason = RefundCreateParams.Reason.DUPLICATE;
                        break;
                    case "fraudulent":
                        refundReason = RefundCreateParams.Reason.FRAUDULENT;
                        break;
                    case "requested_by_customer":
                        refundReason = RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER;
                        break;
                    default:
                        refundReason = RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER;
                }
            }

            RefundCreateParams.Builder paramsBuilder = RefundCreateParams.builder()
                    .setCharge(chargeId)
                    .setAmount(amount);
            if (refundReason != null) {
                paramsBuilder.setReason(refundReason);
            }
            RefundCreateParams params = paramsBuilder.build();

            Refund refund = Refund.create(params);

            logger.info("Stripe: Refund created - ID: {}, Amount: {}", refund.getId(), amount);
            return refund;
        } catch (StripeException e) {
            handleStripeException(e, "createRefund");
            throw new AppException(ErrorCode.STRIPE_PAYMENT_FAILED,
                    "Failed to create refund", Map.of("chargeId", chargeId), null, e);
        }
    }

    /**
     * Handle Stripe Exceptions
     * @param e Stripe exception
     * @param operation Operation name
     */
    private void handleStripeException(
            final StripeException e, final String operation) {
        logger.error("Stripe API error in {}: {} - {}",
                operation, e.getCode(), e.getMessage());

        // Map Stripe error codes to our error codes
        switch (e.getCode()) {
            case "card_declined":
                throw new AppException(ErrorCode.STRIPE_CARD_DECLINED,
                        e.getMessage());
            case "insufficient_funds":
                throw new AppException(ErrorCode.STRIPE_INSUFFICIENT_FUNDS,
                        e.getMessage());
            case "invalid_card":
                throw new AppException(ErrorCode.STRIPE_INVALID_CARD,
                        e.getMessage());
            case "rate_limit":
                throw new AppException(ErrorCode.STRIPE_RATE_LIMIT_EXCEEDED,
                        e.getMessage());
            case "api_key_expired":
            case "authentication_required":
                throw new AppException(ErrorCode.STRIPE_INVALID_API_KEY,
                        e.getMessage());
            default:
                // JDK 25: String template for better readability
                String errorMsg = "Stripe API error: " + e.getMessage();
                throw new AppException(
                        ErrorCode.STRIPE_CONNECTION_FAILED,
                        errorMsg, null, null, e);
        }
    }
}

