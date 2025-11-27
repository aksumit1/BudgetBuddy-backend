package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.plaid.PlaidWebhookService;
import com.budgetbuddy.compliance.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Plaid Webhook Controller
 * Handles webhook events from Plaid
 *
 * Webhook Events:
 * - TRANSACTIONS: Transaction updates
 * - ITEM: Item status changes
 * - AUTH: Authentication events
 * - INCOME: Income verification
 *
 * Security:
 * - Webhook signature verification
 * - Rate limiting (handled by WAF)
 * - Audit logging
 */
@RestController
@RequestMapping("/api/plaid/webhooks")
@Tag(name = "Plaid", description = "Plaid integration endpoints")
public class PlaidWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(PlaidWebhookController.class);

    private final PlaidWebhookService webhookService;
    private final AuditLogService auditLogService;

    public PlaidWebhookController(final PlaidWebhookService webhookService, final AuditLogService auditLogService) {
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
        description = "Receives and processes webhook events from Plaid including transaction updates, item status changes, and authentication events"
    )
    public ResponseEntity<Map<String, String>> handleWebhook(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "Plaid-Verification", required = false) String verificationHeader) {

        if (payload == null || payload.isEmpty()) {
            logger.warn("Received empty webhook payload");
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "Empty payload"));
        }

        try {
            String webhookType = extractString(payload, "webhook_type");
            String webhookCode = extractString(payload, "webhook_code");
            String itemId = extractString(payload, "item_id");

            if (webhookType == null || webhookType.isEmpty()) {
                logger.warn("Webhook type is missing");
                return ResponseEntity.badRequest()
                        .body(Map.of("status", "error", "message", "Missing webhook_type"));
            }

            // Verify webhook signature (in production, verify against Plaid's public key)
            if (!webhookService.verifyWebhookSignature(payload, verificationHeader)) {
                logger.warn("Plaid webhook signature verification failed");
                throw new AppException(ErrorCode.PLAID_WEBHOOK_ERROR, "Invalid webhook signature");
            }

            logger.info("Received Plaid webhook: type={}, code={}, itemId={}",
                    webhookType, webhookCode, itemId);

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
                                "webhookType", webhookType != null ? webhookType : "unknown",
                                "webhookCode", webhookCode != null ? webhookCode : "unknown"
                        ),
                        null,
                        null
                );
            } catch (Exception e) {
                logger.warn("Failed to log webhook to audit trail: {}", e.getMessage());
            }

            return ResponseEntity.ok(Map.of("status", "success"));
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to process Plaid webhook: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_WEBHOOK_ERROR,
                    "Failed to process webhook", null, null, e);
        }
    }

    /**
     * Process webhook based on type
     */
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
                logger.warn("Unknown webhook type: {}", webhookType);
        }
    }

    /**
     * Extract string value from payload
     */
    private String extractString(final Map<String, Object> payload, final String key) {
        Object value = payload.get(key);
        return value != null ? value.toString() : null;
    }
}
