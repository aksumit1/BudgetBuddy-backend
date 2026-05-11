package com.budgetbuddy.util;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

/**
 * Batch Operations Helper Provides support for DynamoDB BatchWriteItem operations
 *
 * <p>LOW PRIORITY: Improves performance and cost efficiency for bulk operations
 *
 * <p>Features: - Handles 25-item limit per batch - Automatic retry for unprocessed items -
 * Exponential backoff for throttling
 */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
public final class BatchOperationsHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchOperationsHelper.class);
    private static final int MAX_BATCH_SIZE = 25; // DynamoDB limit

    private BatchOperationsHelper() {
        // Utility class
    }

    /**
     * Batch write items to DynamoDB Automatically handles batching, retries, and unprocessed items
     *
     * @param dynamoDbClient DynamoDB client
     * @param tableName Table name
     * @param items List of items to write (as AttributeValue maps)
     * @return Number of items successfully written
     */
    public static int batchWriteItems(
            final DynamoDbClient dynamoDbClient,
            final String tableName,
            final List<Map<String, AttributeValue>> items) {

        if (items == null || items.isEmpty()) {
            return 0;
        }

        int totalWritten = 0;
        int batchNumber = 0;

        // Process in batches of 25
        for (int i = 0; i < items.size(); i += MAX_BATCH_SIZE) {
            batchNumber++;
            final int endIndex = Math.min(i + MAX_BATCH_SIZE, items.size());
            final List<Map<String, AttributeValue>> batch = items.subList(i, endIndex);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Processing batch {}: {} items", batchNumber, batch.size());
            }

            // Build write requests
            final List<WriteRequest> writeRequests = new ArrayList<>();
            for (final Map<String, AttributeValue> item : batch) {
                writeRequests.add(
                        WriteRequest.builder()
                                .putRequest(PutRequest.builder().item(item).build())
                                .build());
            }

            // Execute batch write with retry
            final int written =
                    executeBatchWriteWithRetry(dynamoDbClient, tableName, writeRequests);
            totalWritten += written;

            LOGGER.debug("Batch {} completed: {} items written", batchNumber, written);
        }

        LOGGER.info(
                "Batch write completed: {} total items written to table {}",
                totalWritten,
                tableName);
        return totalWritten;
    }

    /** Execute batch write with retry for unprocessed items */
    private static int executeBatchWriteWithRetry(
            final DynamoDbClient dynamoDbClient,
            final String tableName,
            final List<WriteRequest> writeRequests) {

        Map<String, List<WriteRequest>> requestItems = new HashMap<>();
        requestItems.put(tableName, writeRequests);

        int totalWritten = 0;
        final int maxRetries = 5;
        int retryCount = 0;

        while (retryCount <= maxRetries && !requestItems.isEmpty()) {
            final BatchWriteItemRequest request =
                    BatchWriteItemRequest.builder().requestItems(requestItems).build();

            final BatchWriteItemResponse response =
                    RetryHelper.executeDynamoDbWithRetry(
                            () -> {
                                return dynamoDbClient.batchWriteItem(request);
                            });

            // Count successfully processed items
            final int processed =
                    writeRequests.size()
                            - (response.unprocessedItems().containsKey(tableName)
                                    ? response.unprocessedItems().get(tableName).size()
                                    : 0);
            totalWritten += processed;

            // Check for unprocessed items
            if (response.unprocessedItems() != null
                    && response.unprocessedItems().containsKey(tableName)
                    && !response.unprocessedItems().get(tableName).isEmpty()) {

                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Batch write has {} unprocessed items, retrying (attempt {}/{})",
                            response.unprocessedItems().get(tableName).size(),
                            retryCount + 1,
                            maxRetries);
                }

                requestItems = response.unprocessedItems();
                retryCount++;

                // Exponential backoff
                try {
                    Thread.sleep((long) Math.pow(2, retryCount) * 100); // 200ms, 400ms, 800ms, etc.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AppException(
                            ErrorCode.INTERNAL_SERVER_ERROR, "Batch write retry interrupted", e);
                }
            } else {
                // All items processed
                break;
            }
        }

        if (!requestItems.isEmpty() && retryCount > maxRetries) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Batch write failed to process {} items after {} retries",
                        requestItems.get(tableName).size(),
                        maxRetries);
            }
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Batch write failed: "
                            + requestItems.get(tableName).size()
                            + " items unprocessed after "
                            + maxRetries
                            + " retries");
        }

        return totalWritten;
    }

    /** Batch delete items from DynamoDB */
    public static int batchDeleteItems(
            final DynamoDbClient dynamoDbClient,
            final String tableName,
            final List<Map<String, AttributeValue>> keys) {

        if (keys == null || keys.isEmpty()) {
            return 0;
        }

        int totalDeleted = 0;
        int batchNumber = 0;

        // Process in batches of 25
        for (int i = 0; i < keys.size(); i += MAX_BATCH_SIZE) {
            batchNumber++;
            final int endIndex = Math.min(i + MAX_BATCH_SIZE, keys.size());
            final List<Map<String, AttributeValue>> batch = keys.subList(i, endIndex);

            // Build delete requests
            final List<WriteRequest> writeRequests = new ArrayList<>();
            for (final Map<String, AttributeValue> key : batch) {
                writeRequests.add(
                        WriteRequest.builder()
                                .deleteRequest(
                                        software.amazon.awssdk.services.dynamodb.model.DeleteRequest
                                                .builder()
                                                .key(key)
                                                .build())
                                .build());
            }

            final Map<String, List<WriteRequest>> requestItems = new HashMap<>();
            requestItems.put(tableName, writeRequests);

            // Execute with retry (similar to batch write)
            final BatchWriteItemRequest request =
                    BatchWriteItemRequest.builder().requestItems(requestItems).build();

            final BatchWriteItemResponse response =
                    RetryHelper.executeDynamoDbWithRetry(
                            () -> {
                                return dynamoDbClient.batchWriteItem(request);
                            });

            final int deleted =
                    batch.size()
                            - (response.unprocessedItems() != null
                                            && response.unprocessedItems().containsKey(tableName)
                                    ? response.unprocessedItems().get(tableName).size()
                                    : 0);
            totalDeleted += deleted;

            LOGGER.debug("Batch delete {} completed: {} items deleted", batchNumber, deleted);
        }

        LOGGER.info(
                "Batch delete completed: {} total items deleted from table {}",
                totalDeleted,
                tableName);
        return totalDeleted;
    }
}
