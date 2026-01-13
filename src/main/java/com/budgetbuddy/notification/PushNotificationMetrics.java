package com.budgetbuddy.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Metrics for push notification service
 * Tracks delivery rates, failures, and performance
 */
@Component
public class PushNotificationMetrics {

    private static final Logger logger = LoggerFactory.getLogger(PushNotificationMetrics.class);

    // Counters
    private final AtomicLong totalNotificationsSent = new AtomicLong(0);
    private final AtomicLong totalNotificationsDelivered = new AtomicLong(0);
    private final AtomicLong totalNotificationsFailed = new AtomicLong(0);
    private final AtomicLong totalInvalidEndpoints = new AtomicLong(0);
    private final AtomicInteger activeDevices = new AtomicInteger(0);
    private final AtomicInteger disabledDevices = new AtomicInteger(0);

    // Performance metrics
    private final AtomicLong totalDeliveryTime = new AtomicLong(0);
    private final AtomicLong minDeliveryTime = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxDeliveryTime = new AtomicLong(0);

    /**
     * Record notification sent
     */
    public void recordNotificationSent(int count) {
        totalNotificationsSent.addAndGet(count);
        logger.debug("Notification sent: {} (total: {})", count, totalNotificationsSent.get());
    }

    /**
     * Record notification delivered
     */
    public void recordNotificationDelivered(int count, long deliveryTimeMs) {
        totalNotificationsDelivered.addAndGet(count);
        totalDeliveryTime.addAndGet(deliveryTimeMs);
        
        // Update min/max
        long currentMin = minDeliveryTime.get();
        while (deliveryTimeMs < currentMin && !minDeliveryTime.compareAndSet(currentMin, deliveryTimeMs)) {
            currentMin = minDeliveryTime.get();
        }
        
        long currentMax = maxDeliveryTime.get();
        while (deliveryTimeMs > currentMax && !maxDeliveryTime.compareAndSet(currentMax, deliveryTimeMs)) {
            currentMax = maxDeliveryTime.get();
        }
        
        logger.debug("Notification delivered: {} (total: {})", count, totalNotificationsDelivered.get());
    }

    /**
     * Record notification failure
     */
    public void recordNotificationFailed(int count) {
        totalNotificationsFailed.addAndGet(count);
        logger.warn("Notification failed: {} (total: {})", count, totalNotificationsFailed.get());
    }

    /**
     * Record invalid endpoint
     */
    public void recordInvalidEndpoint() {
        totalInvalidEndpoints.incrementAndGet();
        logger.warn("Invalid endpoint detected (total: {})", totalInvalidEndpoints.get());
    }

    /**
     * Update active devices count
     */
    public void updateActiveDevices(int count) {
        activeDevices.set(count);
    }

    /**
     * Record device disabled
     */
    public void recordDeviceDisabled() {
        disabledDevices.incrementAndGet();
        logger.info("Device disabled (total: {})", disabledDevices.get());
    }

    /**
     * Get delivery success rate
     */
    public double getDeliverySuccessRate() {
        long total = totalNotificationsSent.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) totalNotificationsDelivered.get() / total;
    }

    /**
     * Get average delivery time in milliseconds
     */
    public double getAverageDeliveryTime() {
        long delivered = totalNotificationsDelivered.get();
        if (delivered == 0) {
            return 0.0;
        }
        return (double) totalDeliveryTime.get() / delivered;
    }

    /**
     * Get metrics snapshot
     */
    public MetricsSnapshot getSnapshot() {
        return new MetricsSnapshot(
                totalNotificationsSent.get(),
                totalNotificationsDelivered.get(),
                totalNotificationsFailed.get(),
                totalInvalidEndpoints.get(),
                activeDevices.get(),
                disabledDevices.get(),
                getDeliverySuccessRate(),
                getAverageDeliveryTime(),
                minDeliveryTime.get() == Long.MAX_VALUE ? 0 : minDeliveryTime.get(),
                maxDeliveryTime.get()
        );
    }

    /**
     * Reset all metrics
     */
    public void reset() {
        totalNotificationsSent.set(0);
        totalNotificationsDelivered.set(0);
        totalNotificationsFailed.set(0);
        totalInvalidEndpoints.set(0);
        totalDeliveryTime.set(0);
        minDeliveryTime.set(Long.MAX_VALUE);
        maxDeliveryTime.set(0);
        logger.info("Push notification metrics reset");
    }

    /**
     * Metrics snapshot
     */
    public static class MetricsSnapshot {
        private final long totalSent;
        private final long totalDelivered;
        private final long totalFailed;
        private final long totalInvalidEndpoints;
        private final int activeDevices;
        private final int disabledDevices;
        private final double successRate;
        private final double avgDeliveryTime;
        private final long minDeliveryTime;
        private final long maxDeliveryTime;

        public MetricsSnapshot(long totalSent, long totalDelivered, long totalFailed,
                              long totalInvalidEndpoints, int activeDevices, int disabledDevices,
                              double successRate, double avgDeliveryTime, long minDeliveryTime, long maxDeliveryTime) {
            this.totalSent = totalSent;
            this.totalDelivered = totalDelivered;
            this.totalFailed = totalFailed;
            this.totalInvalidEndpoints = totalInvalidEndpoints;
            this.activeDevices = activeDevices;
            this.disabledDevices = disabledDevices;
            this.successRate = successRate;
            this.avgDeliveryTime = avgDeliveryTime;
            this.minDeliveryTime = minDeliveryTime;
            this.maxDeliveryTime = maxDeliveryTime;
        }

        // Getters
        public long getTotalSent() { return totalSent; }
        public long getTotalDelivered() { return totalDelivered; }
        public long getTotalFailed() { return totalFailed; }
        public long getTotalInvalidEndpoints() { return totalInvalidEndpoints; }
        public int getActiveDevices() { return activeDevices; }
        public int getDisabledDevices() { return disabledDevices; }
        public double getSuccessRate() { return successRate; }
        public double getAvgDeliveryTime() { return avgDeliveryTime; }
        public long getMinDeliveryTime() { return minDeliveryTime; }
        public long getMaxDeliveryTime() { return maxDeliveryTime; }

        @Override
        public String toString() {
            return String.format(
                    "PushNotificationMetrics{totalSent=%d, totalDelivered=%d, totalFailed=%d, " +
                    "successRate=%.2f%%, avgDeliveryTime=%.2fms, activeDevices=%d, disabledDevices=%d}",
                    totalSent, totalDelivered, totalFailed, successRate * 100, avgDeliveryTime,
                    activeDevices, disabledDevices);
        }
    }
}
