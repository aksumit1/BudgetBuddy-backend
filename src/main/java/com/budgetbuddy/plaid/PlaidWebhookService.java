package com.budgetbuddy.plaid;

import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.PlaidSyncService;
import com.budgetbuddy.aws.secrets.SecretsManagerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
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
    private static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";
    private static final String WEBHOOK_SECRET_KEY = "plaid/webhook_secret";

    @SuppressWarnings("unused") // Reserved for future implementation
    private final PlaidSyncService plaidSyncService;
    @SuppressWarnings("unused") // Reserved for future implementation
    private final UserRepository userRepository;
    private final SecretsManagerService secretsManagerService;
    private final ObjectMapper objectMapper;

    public PlaidWebhookService(
            final PlaidSyncService plaidSyncService,
            final UserRepository userRepository,
            final SecretsManagerService secretsManagerService,
            final ObjectMapper objectMapper) {
        this.plaidSyncService = plaidSyncService;
        this.userRepository = userRepository;
        this.secretsManagerService = secretsManagerService;
        this.objectMapper = objectMapper;
    }

    /**
     * Verify webhook signature using HMAC SHA256
     * Implements Plaid's webhook verification as per:
     * https://plaid.com/docs/api/webhooks/#webhook-verification
     */
    public boolean verifyWebhookSignature(final Map<String, Object> payload, final String verificationHeader) {
        if (verificationHeader == null || verificationHeader.isEmpty()) {
            logger.warn("Webhook verification header is missing");
            return false;
        }

        try {
            // Get webhook secret from AWS Secrets Manager
            // getSecret requires secretName and defaultValue
            String webhookSecret = secretsManagerService.getSecret(WEBHOOK_SECRET_KEY, "");
            if (webhookSecret == null || webhookSecret.isEmpty()) {
                logger.error("Plaid webhook secret not found in Secrets Manager");
                return false;
            }

            // Convert payload to JSON string (canonical form)
            String payloadJson = objectMapper.writeValueAsString(payload);

            // Calculate HMAC SHA256 signature
            Mac mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    webhookSecret.getBytes(StandardCharsets.UTF_8),
                    HMAC_SHA256_ALGORITHM);
            mac.init(secretKeySpec);
            byte[] signatureBytes = mac.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8));
            String calculatedSignature = Base64.getEncoder().encodeToString(signatureBytes);

            // Compare with provided signature (constant-time comparison to prevent timing attacks)
            if (calculatedSignature.length() != verificationHeader.length()) {
                logger.warn("Webhook signature length mismatch");
                return false;
            }

            int result = 0;
            for (int i = 0; i < calculatedSignature.length(); i++) {
                result |= calculatedSignature.charAt(i) ^ verificationHeader.charAt(i);
            }

            boolean isValid = result == 0;
            if (!isValid) {
                logger.warn("Webhook signature verification failed");
            }
            return isValid;
        } catch (NoSuchAlgorithmException e) {
            logger.error("HMAC SHA256 algorithm not available", e);
            return false;
        } catch (InvalidKeyException e) {
            logger.error("Invalid webhook secret key", e);
            return false;
        } catch (Exception e) {
            logger.error("Error verifying webhook signature: {}", e.getMessage(), e);
            return false;
        }
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
