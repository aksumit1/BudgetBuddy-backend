package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.DevicePinTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

import java.util.Optional;

/**
 * Repository for DevicePinTable
 * Handles PIN storage and retrieval by userId and deviceId
 */
@Repository
public class DevicePinRepository {

    private static final Logger logger = LoggerFactory.getLogger(DevicePinRepository.class);
    private static final String TABLE_NAME = "BudgetBuddy-DevicePin";

    private final DynamoDbEnhancedClient enhancedClient;
    private DynamoDbTable<DevicePinTable> devicePinTable;

    @Autowired
    public DevicePinRepository(final DynamoDbEnhancedClient enhancedClient) {
        this.enhancedClient = enhancedClient;
        initializeTable();
    }

    private void initializeTable() {
        try {
            devicePinTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(DevicePinTable.class));
            logger.debug("DevicePin table initialized: {}", TABLE_NAME);
        } catch (ResourceNotFoundException e) {
            logger.warn("DevicePin table not found: {}. It will be created on first write.", TABLE_NAME);
            // Don't call table() again - it will be created on first write operation
            // Set to null to indicate table needs to be initialized on first use
            devicePinTable = null;
        }
    }

    /**
     * Find PIN by userId and deviceId
     */
    @Cacheable(value = "devicePins", key = "#userId + ':' + #deviceId")
    public Optional<DevicePinTable> findByUserIdAndDeviceId(final String userId, final String deviceId) {
        if (userId == null || userId.isEmpty() || deviceId == null || deviceId.isEmpty()) {
            return Optional.empty();
        }

        // Initialize table if it was null (table not found during construction)
        if (devicePinTable == null) {
            try {
                devicePinTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(DevicePinTable.class));
            } catch (ResourceNotFoundException e) {
                logger.warn("DevicePin table still not found: {}", TABLE_NAME);
                return Optional.empty();
            }
        }

        try {
            Key key = Key.builder()
                    .partitionValue(userId)
                    .sortValue(deviceId)
                    .build();

            DevicePinTable devicePin = devicePinTable.getItem(
                    GetItemEnhancedRequest.builder()
                            .key(key)
                            .build()
            );

            return Optional.ofNullable(devicePin);
        } catch (Exception e) {
            logger.error("Error finding device PIN for userId: {}, deviceId: {}", userId, deviceId, e);
            return Optional.empty();
        }
    }

    /**
     * Save or update device PIN
     */
    @CacheEvict(value = "devicePins", key = "#devicePin.userId + ':' + #devicePin.deviceId")
    public void save(final DevicePinTable devicePin) {
        if (devicePin == null) {
            throw new IllegalArgumentException("DevicePin cannot be null");
        }
        if (devicePin.getUserId() == null || devicePin.getUserId().isEmpty()) {
            throw new IllegalArgumentException("UserId is required");
        }
        if (devicePin.getDeviceId() == null || devicePin.getDeviceId().isEmpty()) {
            throw new IllegalArgumentException("DeviceId is required");
        }

        // Initialize table if it was null (table not found during construction)
        if (devicePinTable == null) {
            try {
                devicePinTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(DevicePinTable.class));
            } catch (ResourceNotFoundException e) {
                logger.warn("DevicePin table still not found: {}", TABLE_NAME);
                throw new RuntimeException("DevicePin table not available", e);
            }
        }

        try {
            devicePinTable.putItem(devicePin);
            logger.debug("Saved device PIN for userId: {}, deviceId: {}", devicePin.getUserId(), devicePin.getDeviceId());
        } catch (Exception e) {
            logger.error("Error saving device PIN for userId: {}, deviceId: {}", 
                    devicePin.getUserId(), devicePin.getDeviceId(), e);
            throw new RuntimeException("Failed to save device PIN", e);
        }
    }

    /**
     * Delete device PIN
     */
    @CacheEvict(value = "devicePins", key = "#userId + ':' + #deviceId")
    public void delete(final String userId, final String deviceId) {
        if (userId == null || userId.isEmpty() || deviceId == null || deviceId.isEmpty()) {
            throw new IllegalArgumentException("UserId and deviceId are required");
        }

        // Initialize table if it was null (table not found during construction)
        if (devicePinTable == null) {
            try {
                devicePinTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(DevicePinTable.class));
            } catch (ResourceNotFoundException e) {
                logger.warn("DevicePin table still not found: {}", TABLE_NAME);
                throw new RuntimeException("DevicePin table not available", e);
            }
        }

        try {
            Key key = Key.builder()
                    .partitionValue(userId)
                    .sortValue(deviceId)
                    .build();

            devicePinTable.deleteItem(key);
            logger.debug("Deleted device PIN for userId: {}, deviceId: {}", userId, deviceId);
        } catch (Exception e) {
            logger.error("Error deleting device PIN for userId: {}, deviceId: {}", userId, deviceId, e);
            throw new RuntimeException("Failed to delete device PIN", e);
        }
    }
}

