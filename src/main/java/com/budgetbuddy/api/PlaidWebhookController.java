package com.budgetbuddy.api;

import com.budgetbuddy.compliance.AuditLogService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.plaid.PlaidWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plaid Webhook Controller Handles webhook events from Plaid
 *
 * <p>Webhook Events: - TRANSACTIONS: Transaction updates - ITEM: Item status changes - AUTH:
 * Authentication events - INCOME: Income verification
 *
 * <p>Security: - Webhook signature verification - Rate limiting (handled by WAF) - Audit logging
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@RestController
@RequestMapping("/api/plaid/webhooks")
@Tag(name = "Plaid", description = "Plaid integration endpoints")
public class PlaidWebhookController {

    private static final String STATUS = "status";

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaidWebhookController.class);

    private final PlaidWebhookService webhookService;
    private final AuditLogService auditLogService;

    public PlaidWebhookController(
            final PlaidWebhookService webhookService, final AuditLogService auditLogService) {
        this.webhookService = webhookService;
        this.auditLogService = auditLogService;
    }

    /**
     * Handle Plaid webhook events
     *
     * @param payload Webhook payload from Plaid
     * @param verificationHeader Webhook verification header
     * @return HTTP 200 if successful
     */
    @PostMapping
    @Operation(
            summary = "Handle Plaid webhook",
            description =
                    "Receives and processes webhook events from Plaid including transaction updates, item status changes, and authentication events")
    public ResponseEntity<Map<String, String>> handleWebhook(
            @RequestBody final Map<String, Object> payload,
            @RequestHeader(value = "Plaid-Verification", required = false) final String verificationHeader) {

        if (payload == null || payload.isEmpty()) {
            LOGGER.warn("Received empty webhook payload");
            return ResponseEntity.badRequest()
                    .body(Map.of(STATUS, "error", "message", "Empty payload"));
        }

        try {
            final String webhookType = extractString(payload, "webhook_type");
            final String webhookCode = extractString(payload, "webhook_code");
            final String itemId = extractString(payload, "item_id");

            if (webhookType == null || webhookType.isEmpty()) {
                LOGGER.warn("Webhook type is missing");
                return ResponseEntity.badRequest()
                        .body(Map.of(STATUS, "error", "message", "Missing webhook_type"));
            }

            // Verify webhook signature (in production, verify against Plaid's public key)
            if (!webhookService.verifyWebhookSignature(payload, verificationHeader)) {
                LOGGER.warn("Plaid webhook signature verification failed");
                throw new AppException(ErrorCode.PLAID_WEBHOOK_ERROR, "Invalid webhook signature");
            }

            LOGGER.info(
                    "Received Plaid webhook: type={}, code={}, itemId={}",
                    webhookType,
                    webhookCode,
                    itemId);

            // Process webhook based on type
            processWebhook(webhookType, payload);

            // Log webhook receipt
            try {
                auditLogService.logAction(
                        "SYSTEM",
                        "PLAID_WEBHOOK",
                        "WEBHOOK",
                        itemId != null ? itemId : "unknown",
                        Map.of(
                                "webhookType", webhookType,
                                "webhookCode", webhookCode != null ? webhookCode : "unknown"),
                        null,
                        null);
            } catch (Exception e) {
                LOGGER.warn("Failed to log webhook to audit trail: {}", e.getMessage());
            }

            return ResponseEntity.ok(Map.of(STATUS, "success"));
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("Failed to process Plaid webhook: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.PLAID_WEBHOOK_ERROR, "Failed to process webhook", null, null, e);
        }
    }

    /** Process webhook based on type */
    private void processWebhook(final String webhookType, final Map<String, Object> payload) {
        switch (webhookType) {
            case "TRANSACTIONS":
                webhookService.handleTransactionWebhook(payload);
                break;
            case "ITEM":
                webhookService.handleItemWebhook(payload);
                break;
            case "AUTH":
                webhookService.handleAuthWebhook(payload);
                break;
            case "INCOME":
                webhookService.handleIncomeWebhook(payload);
                break;
            default:
                LOGGER.warn("Unknown webhook type: {}", webhookType);
        }
    }

    /** Extract string value from payload */
    private String extractString(final Map<String, Object> payload, final String key) {
        final Object value = payload.get(key);
        return value != null ? value.toString() : null;
    }
}
