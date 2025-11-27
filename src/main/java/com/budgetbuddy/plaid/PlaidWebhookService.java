package com.budgetbuddy.plaid;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.notification.NotificationService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
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
import java.util.Optional;
import java.util.Set;

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

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final NotificationService notificationService;
    private final SecretsManagerService secretsManagerService;
    private final ObjectMapper objectMapper;

    public PlaidWebhookService(
            final UserRepository userRepository,
            final AccountRepository accountRepository,
            final TransactionRepository transactionRepository,
            final NotificationService notificationService,
            final SecretsManagerService secretsManagerService,
            final ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.notificationService = notificationService;
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
        logger.info("Syncing all transactions for item: {}", itemId);
        
        try {
            // Find user by item ID
            Optional<UserTable> userOpt = findUserByItemId(itemId);
            if (userOpt.isEmpty()) {
                logger.warn("User not found for item: {}", itemId);
                return;
            }
            
            UserTable user = userOpt.get();
            
            // Find accounts for this item to get access token
            List<AccountTable> accounts = accountRepository.findByPlaidItemId(itemId);
            if (accounts.isEmpty()) {
                logger.warn("No accounts found for item: {}", itemId);
                return;
            }
            
            // Get access token from first account (all accounts for same item share same access token)
            // Note: In production, you might want to store access token separately
            // For now, we'll need to retrieve it from Plaid or store it with accounts
            // This is a simplified implementation - assumes access token is available
            logger.info("Found {} accounts for item: {}", accounts.size(), itemId);
            
            // Sync all transactions for the user
            // Note: This requires access token which should be stored securely
            // For now, log that sync is needed
            logger.info("Transaction sync triggered for user: {} via item: {}", user.getUserId(), itemId);
            // In production, retrieve access token from secure storage and call:
            // plaidSyncService.syncTransactions(user, accessToken);
            
        } catch (Exception e) {
            logger.error("Error syncing transactions for item {}: {}", itemId, e.getMessage(), e);
        }
    }

    private void syncNewTransactionsForItem(final String itemId) {
        logger.info("Syncing new transactions for item: {}", itemId);
        
        try {
            // Find user by item ID
            Optional<UserTable> userOpt = findUserByItemId(itemId);
            if (userOpt.isEmpty()) {
                logger.warn("User not found for item: {}", itemId);
                return;
            }
            
            UserTable user = userOpt.get();
            
            // Find accounts for this item
            List<AccountTable> accounts = accountRepository.findByPlaidItemId(itemId);
            if (accounts.isEmpty()) {
                logger.warn("No accounts found for item: {}", itemId);
                return;
            }
            
            logger.info("Incremental transaction sync triggered for user: {} via item: {}", user.getUserId(), itemId);
            // In production, retrieve access token from secure storage and call:
            // plaidSyncService.syncTransactions(user, accessToken);
            // This will automatically do incremental sync based on lastSyncedAt
            
        } catch (Exception e) {
            logger.error("Error syncing new transactions for item {}: {}", itemId, e.getMessage(), e);
        }
    }

    private void handleTransactionsRemoved(final Map<String, Object> payload) {
        String itemId = extractString(payload, "item_id");
        List<String> removedTransactionIds = extractList(payload, "removed_transactions");
        
        if (removedTransactionIds == null || removedTransactionIds.isEmpty()) {
            logger.warn("No removed_transactions found in payload");
            return;
        }
        
        logger.info("Removing {} transactions for item: {}", removedTransactionIds.size(), itemId);
        
        try {
            // Find user by item ID
            Optional<UserTable> userOpt = findUserByItemId(itemId);
            if (userOpt.isEmpty()) {
                logger.warn("User not found for item: {}", itemId);
                return;
            }
            
            UserTable user = userOpt.get();
            int deletedCount = 0;
            int errorCount = 0;
            
            // Delete transactions by Plaid transaction ID
            for (String plaidTransactionId : removedTransactionIds) {
                try {
                    // Find transaction by Plaid transaction ID
                    transactionRepository.findByPlaidTransactionId(plaidTransactionId)
                            .ifPresent(transaction -> {
                                // Verify transaction belongs to user
                                if (transaction.getUserId() != null && 
                                    transaction.getUserId().equals(user.getUserId())) {
                                    transactionRepository.delete(transaction.getTransactionId());
                                    logger.debug("Deleted transaction {} for user {}", 
                                            transaction.getTransactionId(), user.getUserId());
                                } else {
                                    logger.warn("Transaction {} does not belong to user {}", 
                                            transaction.getTransactionId(), user.getUserId());
                                }
                            });
                    deletedCount++;
                } catch (Exception e) {
                    logger.error("Failed to delete transaction {}: {}", plaidTransactionId, e.getMessage());
                    errorCount++;
                }
            }
            
            logger.info("Transaction removal completed: {} deleted, {} errors for item: {}", 
                    deletedCount, errorCount, itemId);
        } catch (Exception e) {
            logger.error("Error handling transactions removed: {}", e.getMessage(), e);
        }
    }

    private void handleItemError(final Map<String, Object> payload) {
        String itemId = extractString(payload, "item_id");
        String errorCode = extractString(payload, "error_code");
        String errorMessage = extractString(payload, "error_message");
        
        logger.error("Item error for item {}: code={}, message={}", itemId, errorCode, errorMessage);
        
        try {
            // Find user by item ID
            Optional<UserTable> userOpt = findUserByItemId(itemId);
            if (userOpt.isEmpty()) {
                logger.warn("User not found for item: {}", itemId);
                return;
            }
            
            UserTable user = userOpt.get();
            
            // Notify user about the error
            NotificationService.NotificationRequest notificationRequest = 
                    new NotificationService.NotificationRequest();
            notificationRequest.setUserId(user.getUserId());
            notificationRequest.setType(NotificationService.NotificationType.SECURITY_ALERT);
            notificationRequest.setTitle("Bank Connection Error");
            notificationRequest.setSubject("Bank Connection Error");
            notificationRequest.setBody(String.format(
                    "There was an error with your bank connection. Error code: %s. %s. " +
                    "Please check your account connection in settings.",
                    errorCode, errorMessage != null ? errorMessage : "Unknown error"));
            notificationRequest.setRecipientEmail(user.getEmail());
            notificationRequest.setChannels(Set.of(NotificationService.NotificationChannel.EMAIL, 
                    NotificationService.NotificationChannel.IN_APP));
            
            NotificationService.NotificationResult result = notificationService.sendNotification(notificationRequest);
            
            if (result.isSuccess()) {
                logger.info("User notified about item error: {}", user.getUserId());
            } else {
                logger.warn("Failed to notify user about item error: {}", user.getUserId());
            }
        } catch (Exception e) {
            logger.error("Error handling item error: {}", e.getMessage(), e);
        }
    }

    private void handlePendingExpiration(final Map<String, Object> payload) {
        String itemId = extractString(payload, "item_id");
        
        logger.warn("Credentials expiring soon for item: {} - user action required", itemId);
        
        try {
            // Find user by item ID
            Optional<UserTable> userOpt = findUserByItemId(itemId);
            if (userOpt.isEmpty()) {
                logger.warn("User not found for item: {}", itemId);
                return;
            }
            
            UserTable user = userOpt.get();
            
            // Notify user to reconnect
            NotificationService.NotificationRequest notificationRequest = 
                    new NotificationService.NotificationRequest();
            notificationRequest.setUserId(user.getUserId());
            notificationRequest.setType(NotificationService.NotificationType.SECURITY_ALERT);
            notificationRequest.setTitle("Bank Connection Expiring Soon");
            notificationRequest.setSubject("Bank Connection Expiring Soon");
            notificationRequest.setBody(
                    "Your bank connection credentials are expiring soon. " +
                    "Please reconnect your account in settings to continue syncing transactions.");
            notificationRequest.setRecipientEmail(user.getEmail());
            notificationRequest.setChannels(Set.of(NotificationService.NotificationChannel.EMAIL, 
                    NotificationService.NotificationChannel.IN_APP));
            
            NotificationService.NotificationResult result = notificationService.sendNotification(notificationRequest);
            
            if (result.isSuccess()) {
                logger.info("User notified about pending expiration: {}", user.getUserId());
            } else {
                logger.warn("Failed to notify user about pending expiration: {}", user.getUserId());
            }
        } catch (Exception e) {
            logger.error("Error handling pending expiration: {}", e.getMessage(), e);
        }
    }

    private void handlePermissionRevoked(final Map<String, Object> payload) {
        String itemId = extractString(payload, "item_id");
        
        logger.info("User revoked permissions for item: {} - disconnecting item", itemId);
        
        try {
            // Find user by item ID
            Optional<UserTable> userOpt = findUserByItemId(itemId);
            if (userOpt.isEmpty()) {
                logger.warn("User not found for item: {}", itemId);
                return;
            }
            
            UserTable user = userOpt.get();
            
            // Find all accounts for this item
            List<AccountTable> accounts = accountRepository.findByPlaidItemId(itemId);
            
            // Mark accounts as inactive
            int deactivatedCount = 0;
            for (AccountTable account : accounts) {
                try {
                    account.setActive(false);
                    account.setUpdatedAt(java.time.Instant.now());
                    accountRepository.save(account);
                    deactivatedCount++;
                    logger.debug("Deactivated account {} for item {}", account.getAccountId(), itemId);
                } catch (Exception e) {
                    logger.error("Failed to deactivate account {}: {}", account.getAccountId(), e.getMessage());
                }
            }
            
            logger.info("Deactivated {} accounts for item: {}", deactivatedCount, itemId);
            
            // Notify user about disconnection
            NotificationService.NotificationRequest notificationRequest = 
                    new NotificationService.NotificationRequest();
            notificationRequest.setUserId(user.getUserId());
            notificationRequest.setType(NotificationService.NotificationType.ACCOUNT_DISCONNECTED);
            notificationRequest.setTitle("Bank Account Disconnected");
            notificationRequest.setSubject("Bank Account Disconnected");
            notificationRequest.setBody(
                    "Your bank account connection has been disconnected. " +
                    "You can reconnect it in settings if needed.");
            notificationRequest.setRecipientEmail(user.getEmail());
            notificationRequest.setChannels(Set.of(NotificationService.NotificationChannel.EMAIL, 
                    NotificationService.NotificationChannel.IN_APP));
            
            NotificationService.NotificationResult result = notificationService.sendNotification(notificationRequest);
            
            if (result.isSuccess()) {
                logger.info("User notified about account disconnection: {}", user.getUserId());
            } else {
                logger.warn("Failed to notify user about account disconnection: {}", user.getUserId());
            }
        } catch (Exception e) {
            logger.error("Error handling permission revoked: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Find user by Plaid item ID
     * Looks up accounts by item ID, then finds the user from the first account
     */
    private Optional<UserTable> findUserByItemId(final String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return Optional.empty();
        }
        
        try {
            // Find accounts by item ID
            List<AccountTable> accounts = accountRepository.findByPlaidItemId(itemId);
            
            if (accounts.isEmpty()) {
                logger.warn("No accounts found for item ID: {}", itemId);
                return Optional.empty();
            }
            
            // Get user ID from first account
            String userId = accounts.get(0).getUserId();
            if (userId == null || userId.isEmpty()) {
                logger.warn("Account has no user ID for item: {}", itemId);
                return Optional.empty();
            }
            
            // Find user
            return userRepository.findById(userId);
        } catch (Exception e) {
            logger.error("Error finding user by item ID {}: {}", itemId, e.getMessage(), e);
            return Optional.empty();
        }
    }
}
