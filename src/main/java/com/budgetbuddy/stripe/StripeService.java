package com.budgetbuddy.stripe;


import java.util.Locale;
import com.budgetbuddy.compliance.pcidss.PCIDSSComplianceService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.stripe.Stripe;
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
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Comprehensive Stripe Integration Service Handles payment processing with PCI-DSS compliance */
@Service
public class StripeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StripeService.class);

    private final PCIDSSComplianceService pciDSSComplianceService;
    private final String secretKey; // Store for validation

    public StripeService(
            @Value("${app.stripe.secret-key:sk_test_placeholder}") final String secretKey,
            @Value("${app.features.enable-stripe:true}") final boolean stripeEnabled,
            final PCIDSSComplianceService pciDSSComplianceService) {
        this.secretKey = secretKey;
        Stripe.apiKey = secretKey;
        this.pciDSSComplianceService = pciDSSComplianceService;

        // Warn if using placeholder (but allow service creation for scripts/analysis)
        if (secretKey == null || secretKey.isEmpty() || "sk_test_placeholder".equals(secretKey)) {
            if (stripeEnabled) {
                LOGGER.warn(
                        "⚠️ Stripe secret key is not configured. Stripe API calls will fail. "
                                + "Set STRIPE_SECRET_KEY environment variable or app.stripe.secret-key property.");
            } else {
                LOGGER.debug("Stripe secret key not configured (Stripe feature is disabled).");
            }
        }
    }

    /** Create Payment Intent */
    @CircuitBreaker(name = "stripe")
    @Retry(name = "stripe")
    public PaymentIntent createPaymentIntent(
            final String userId,
            final long amount,
            final String currency,
            final String description,
            final Map<String, String> metadata) {
        // Validate Stripe credentials before making API call
        if (secretKey == null || secretKey.isEmpty() || "sk_test_placeholder".equals(secretKey)) {
            throw new AppException(
                    ErrorCode.STRIPE_PAYMENT_FAILED,
                    "Stripe secret key is not configured. Please set app.stripe.secret-key property or STRIPE_SECRET_KEY environment variable. "
                            + "Get your Stripe credentials from https://dashboard.stripe.com/apikeys");
        }

        try {
            final PaymentIntentCreateParams params =
                    PaymentIntentCreateParams.builder()
                            .setAmount(amount)
                            .setCurrency(currency)
                            .setDescription(description)
                            .putMetadata("userId", userId)
                            .putAllMetadata(metadata != null ? metadata : new HashMap<>())
                            .build();

            final PaymentIntent paymentIntent = PaymentIntent.create(params);

            // PCI-DSS: Log payment creation
            pciDSSComplianceService.logCardholderDataAccess(
                    userId, "PAYMENT_INTENT", "CREATE", true);

            LOGGER.info(
                    "Stripe: Payment intent created - ID: {}, Amount: {}",
                    paymentIntent.getId(),
                    amount);
            return paymentIntent;
        } catch (StripeException e) {
            handleStripeException(e, "createPaymentIntent");
            throw new AppException(
                    ErrorCode.STRIPE_PAYMENT_FAILED,
                    "Failed to create payment intent",
                    Map.of("userId", userId, "amount", amount),
                    null,
                    e);
        }
    }

    /** Confirm Payment Intent */
    @CircuitBreaker(name = "stripe")
    @Retry(name = "stripe")
    public PaymentIntent confirmPaymentIntent(
            final String paymentIntentId, final String paymentMethodId) {
        try {
            final PaymentIntentConfirmParams params =
                    PaymentIntentConfirmParams.builder().setPaymentMethod(paymentMethodId).build();

            PaymentIntent paymentIntent = PaymentIntent.retrieve(paymentIntentId);
            // Get userId from the original payment intent before confirming
            String userId =
                    paymentIntent.getMetadata() != null
                            ? paymentIntent.getMetadata().get("userId")
                            : null;
            paymentIntent = paymentIntent.confirm(params);

            // PCI-DSS: Log payment confirmation
            // Use userId from original intent, or try to get it from confirmed intent if not
            // available
            if (userId == null && paymentIntent.getMetadata() != null) {
                userId = paymentIntent.getMetadata().get("userId");
            }
            pciDSSComplianceService.logCardholderDataAccess(
                    userId, "PAYMENT_INTENT", "CONFIRM", true);

            LOGGER.info(
                    "Stripe: Payment intent confirmed - ID: {}, Status: {}",
                    paymentIntent.getId(),
                    paymentIntent.getStatus());
            return paymentIntent;
        } catch (StripeException e) {
            handleStripeException(e, "confirmPaymentIntent");
            throw new AppException(
                    ErrorCode.STRIPE_PAYMENT_FAILED,
                    "Failed to confirm payment intent",
                    Map.of("paymentIntentId", paymentIntentId),
                    null,
                    e);
        }
    }

    /** Create Customer */
    @CircuitBreaker(name = "stripe")
    @Retry(name = "stripe")
    public Customer createCustomer(
            final String userId,
            final String email,
            final String name,
            final Map<String, String> metadata) {
        try {
            final CustomerCreateParams params =
                    CustomerCreateParams.builder()
                            .setEmail(email)
                            .setName(name)
                            .putMetadata("userId", userId)
                            .putAllMetadata(metadata != null ? metadata : new HashMap<>())
                            .build();

            final Customer customer = Customer.create(params);

            LOGGER.info("Stripe: Customer created - ID: {}, Email: {}", customer.getId(), email);
            return customer;
        } catch (StripeException e) {
            handleStripeException(e, "createCustomer");
            throw new AppException(
                    ErrorCode.STRIPE_CONNECTION_FAILED,
                    "Failed to create customer",
                    Map.of("userId", userId, "email", email),
                    null,
                    e);
        }
    }

    /** Create Payment Method */
    @CircuitBreaker(name = "stripe")
    @Retry(name = "stripe")
    public PaymentMethod createPaymentMethod(
            final String type, final Map<String, Object> cardDetails) {
        try {
            final PaymentMethodCreateParams.Type paymentType =
                    "card".equalsIgnoreCase(type)
                            ? PaymentMethodCreateParams.Type.CARD
                            : PaymentMethodCreateParams.Type.CARD; // Default to card

            // Build card details map for Stripe API
            final Map<String, Object> cardMap = new HashMap<>();
            cardMap.put("number", cardDetails.get("number"));
            final Object expMonthObj = cardDetails.get("expMonth");
            final Object expYearObj = cardDetails.get("expYear");
            if (expMonthObj instanceof Number) {
                cardMap.put("exp_month", ((Number) expMonthObj).longValue());
            }
            if (expYearObj instanceof Number) {
                cardMap.put("exp_year", ((Number) expYearObj).longValue());
            }
            cardMap.put("cvc", cardDetails.get("cvc"));

            final PaymentMethodCreateParams params =
                    PaymentMethodCreateParams.builder()
                            .setType(paymentType)
                            .putExtraParam("card", cardMap)
                            .build();

            final PaymentMethod paymentMethod = PaymentMethod.create(params);

            // PCI-DSS: Mask and validate card number
            pciDSSComplianceService.maskPAN((String) cardDetails.get("number"));
            // Note: logCardDataAccess is on auditLogService, not pciDSSComplianceService

            LOGGER.info(
                    "Stripe: Payment method created - ID: {}, Type: {}",
                    paymentMethod.getId(),
                    type);
            return paymentMethod;
        } catch (StripeException e) {
            handleStripeException(e, "createPaymentMethod");
            throw new AppException(
                    ErrorCode.STRIPE_INVALID_CARD,
                    "Failed to create payment method",
                    null,
                    null,
                    e);
        }
    }

    /** Refund Payment */
    @CircuitBreaker(name = "stripe")
    @Retry(name = "stripe")
    public Refund createRefund(final String chargeId, final Long amount, final String reason) {
        try {
            RefundCreateParams.Reason refundReason = null;
            if (reason != null) {
                switch (reason.toLowerCase(Locale.ROOT)) {
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

            final RefundCreateParams.Builder paramsBuilder =
                    RefundCreateParams.builder().setCharge(chargeId).setAmount(amount);
            if (refundReason != null) {
                paramsBuilder.setReason(refundReason);
            }
            final RefundCreateParams params = paramsBuilder.build();

            final Refund refund = Refund.create(params);

            LOGGER.info("Stripe: Refund created - ID: {}, Amount: {}", refund.getId(), amount);
            return refund;
        } catch (StripeException e) {
            handleStripeException(e, "createRefund");
            throw new AppException(
                    ErrorCode.STRIPE_PAYMENT_FAILED,
                    "Failed to create refund",
                    Map.of("chargeId", chargeId),
                    null,
                    e);
        }
    }

    /**
     * Handle Stripe Exceptions
     *
     * @param e Stripe exception
     * @param operation Operation name
     */
    private void handleStripeException(final StripeException e, final String operation) {
        LOGGER.error("Stripe API error in {}: {} - {}", operation, e.getCode(), e.getMessage());

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
                // JDK 25: String template for better readability
                final String errorMsg = "Stripe API error: " + e.getMessage();
                throw new AppException(ErrorCode.STRIPE_CONNECTION_FAILED, errorMsg, null, null, e);
        }
    }
}
