package com.budgetbuddy.plaid;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.PlaidSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Plaid Webhook Service
 * Handles webhook events from Plaid
 *
 * Features:
 * - Webhook signature verification
 * - Event type handling
 * - Error handling
 * - Logging
 */
@Service
public class PlaidWebhookService {

    private static final Logger logger = LoggerFactory.getLogger(PlaidWebhookService.class);

    @Autowired
    private PlaidSyncService plaidSyncService;

    @Autowired
    private UserRepository userRepository;

    /**
     * Verify webhook signature
     * In production, verify against Plaid's public key
     */
    public boolean verifyWebhookSignature(final Map<String, Object> payload, final String verificationHeader) {
        // In production, implement proper signature verification using Plaid's webhook verification
        // For now, return true (implement actual verification)
        if (verificationHeader == null || verificationHeader.isEmpty()) {
            logger.warn("Webhook verification header is missing");
            return false;
        }

        // TODO: Implement actual Plaid webhook signature verification
        // See: https://plaid.com/docs/api/webhooks/#webhook-verification
        return true;
    }

    /**
     * Handle TRANSACTIONS webhook
     * Processes transaction updates from Plaid
     */
    public void handleTransactionWebhook(final Map<String, Object> payload) {
        String webhookCode = extractString(payload, "webhook_code");
        String itemId = extractString(payload, "item_id");

        if (itemId == null || itemId.isEmpty()) {
            logger.error("Item ID is missing in TRANSACTIONS webhook");
            return;
        }

        logger.info("Processing TRANSACTIONS webhook: code={}, itemId={}", webhookCode, itemId);

        switch (webhookCode) {
            case "INITIAL_UPDATE":
                logger.info("Initial transaction update for item: {}", itemId);
                syncTransactionsForItem(itemId);
                break;
            case "HISTORICAL_UPDATE":
                logger.info("Historical transaction update for item: {}", itemId);
                syncTransactionsForItem(itemId);
                break;
            case "DEFAULT_UPDATE":
                logger.info("Default transaction update for item: {}", itemId);
                syncNewTransactionsForItem(itemId);
                break;
            case "TRANSACTIONS_REMOVED":
                logger.info("Transactions removed for item: {}", itemId);
                handleTransactionsRemoved(payload);
                break;
            default:
                logger.warn("Unknown TRANSACTIONS webhook code: {}", webhookCode);
        }
    }

    /**
     * Handle ITEM webhook
     * Processes item status changes
     */
    public void handleItemWebhook(final Map<String, Object> payload) {
        String webhookCode = extractString(payload, "webhook_code");
        String itemId = extractString(payload, "item_id");

        if (itemId == null || itemId.isEmpty()) {
            logger.error("Item ID is missing in ITEM webhook");
            return;
        }

        logger.info("Processing ITEM webhook: code={}, itemId={}", webhookCode, itemId);

        switch (webhookCode) {
            case "ERROR":
                logger.error("Item error for item: {}", itemId);
                handleItemError(payload);
                break;
            case "PENDING_EXPIRATION":
                logger.warn("Credentials expiring soon for item: {}", itemId);
                handlePendingExpiration(payload);
                break;
            case "USER_PERMISSION_REVOKED":
                logger.info("User revoked access for item: {}", itemId);
                handlePermissionRevoked(payload);
                break;
            default:
                logger.warn("Unknown ITEM webhook code: {}", webhookCode);
        }
    }

    /**
     * Handle AUTH webhook
     * Processes authentication events
     */
    public void handleAuthWebhook(final Map<String, Object> payload) {
        String webhookCode = extractString(payload, "webhook_code");
        String itemId = extractString(payload, "item_id");

        logger.info("Processing AUTH webhook: code={}, itemId={}", webhookCode, itemId);
        // Handle authentication events
    }

    /**
     * Handle INCOME webhook
     * Processes income verification events
     */
    public void handleIncomeWebhook(final Map<String, Object> payload) {
        String webhookCode = extractString(payload, "webhook_code");
        String itemId = extractString(payload, "item_id");

        logger.info("Processing INCOME webhook: code={}, itemId={}", webhookCode, itemId);
        // Handle income verification events
    }

    /**
     * Extract string value from payload
     */
    private String extractString(final Map<String, Object> payload, final String key) {
        Object value = payload.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * Extract list from payload
     */
    @SuppressWarnings("unchecked")
    private List<String> extractList(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof List) {
            return (List<String>) value;
        }
        return null;
    }

    private void syncTransactionsForItem(final String itemId) {
        // Find user by item ID and sync all transactions
        // In production, maintain itemId -> userId mapping
        logger.debug("Syncing all transactions for item: {}", itemId);
        // TODO: Implement actual sync logic
    }

    private void syncNewTransactionsForItem(final String itemId) {
        // Sync only new transactions since last sync
        logger.debug("Syncing new transactions for item: {}", itemId);
        // TODO: Implement actual sync logic
    }

    private void handleTransactionsRemoved(final Map<String, Object> payload) {
        List<String> removedTransactionIds = extractList(payload, "removed_transactions");
        if (removedTransactionIds != null) {
            logger.info("Removing {} transactions", removedTransactionIds.size());
            // TODO: Implement transaction removal logic
        } else {
            logger.warn("No removed_transactions found in payload");
        }
    }

    private void handleItemError(final Map<String, Object> payload) {
        String errorCode = extractString(payload, "error_code");
        String errorMessage = extractString(payload, "error_message");
        logger.error("Item error: code={}, message={}", errorCode, errorMessage);
        // TODO: Notify user and handle error
    }

    private void handlePendingExpiration(final Map<String, Object> payload) {
        logger.warn("Credentials expiring soon - user action required");
        // TODO: Notify user to reconnect
    }

    private void handlePermissionRevoked(final Map<String, Object> payload) {
        logger.info("User revoked permissions - disconnecting item");
        // TODO: Disconnect item and notify user
    }
}
