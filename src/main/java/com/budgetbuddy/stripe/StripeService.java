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
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private PCIDSSComplianceService pciDSSComplianceService;

    public StripeService(@Value("${app.stripe.secret-key}") String secretKey) {
        Stripe.apiKey = secretKey;
    }

    /**
     * Create Payment Intent
     */
    @CircuitBreaker(name = "stripe")
    @Retry(name = "stripe")
    public PaymentIntent createPaymentIntent(String userId, long amount, String currency, 
                                            String description, Map<String, String> metadata) {
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
    public PaymentIntent confirmPaymentIntent(String paymentIntentId, String paymentMethodId) {
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
    public Customer createCustomer(String userId, String email, String name, Map<String, String> metadata) {
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
    public PaymentMethod createPaymentMethod(String type, Map<String, Object> cardDetails) {
        try {
            PaymentMethodCreateParams params = PaymentMethodCreateParams.builder()
                    .setType(PaymentMethodCreateParams.Type.fromString(type))
                    .setCard(PaymentMethodCreateParams.Card.builder()
                            .setNumber((String) cardDetails.get("number"))
                            .setExpMonth((Long) cardDetails.get("expMonth"))
                            .setExpYear((Long) cardDetails.get("expYear"))
                            .setCvc((String) cardDetails.get("cvc"))
                            .build())
                    .build();

            PaymentMethod paymentMethod = PaymentMethod.create(params);
            
            // PCI-DSS: Mask and validate card number
            String maskedCard = pciDSSComplianceService.maskPAN((String) cardDetails.get("number"));
            pciDSSComplianceService.logCardDataAccess("SYSTEM", maskedCard, true);
            
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
    public Refund createRefund(String chargeId, Long amount, String reason) {
        try {
            RefundCreateParams params = RefundCreateParams.builder()
                    .setCharge(chargeId)
                    .setAmount(amount)
                    .setReason(RefundCreateParams.Reason.fromString(reason))
                    .build();

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
     */
    private void handleStripeException(StripeException e, String operation) {
        logger.error("Stripe API error in {}: {} - {}", operation, e.getCode(), e.getMessage());
        
        // Map Stripe error codes to our error codes
        switch (e.getCode()) {
            case "card_declined":
                throw new AppException(ErrorCode.STRIPE_CARD_DECLINED, e.getMessage());
            case "insufficient_funds":
                throw new AppException(ErrorCode.STRIPE_INSUFFICIENT_FUNDS, e.getMessage());
            case "invalid_card":
                throw new AppException(ErrorCode.STRIPE_INVALID_CARD, e.getMessage());
            case "rate_limit":
                throw new AppException(ErrorCode.STRIPE_RATE_LIMIT_EXCEEDED, e.getMessage());
            case "api_key_expired":
            case "authentication_required":
                throw new AppException(ErrorCode.STRIPE_INVALID_API_KEY, e.getMessage());
            default:
                throw new AppException(ErrorCode.STRIPE_CONNECTION_FAILED, 
                        "Stripe API error: " + e.getMessage(), null, null, e);
        }
    }
}

