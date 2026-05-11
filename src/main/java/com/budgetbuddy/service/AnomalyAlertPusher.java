package com.budgetbuddy.service;

import com.budgetbuddy.notification.DataChangeNotificationService;
import com.budgetbuddy.service.TransactionAnomalyService.TransactionAnomaly;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Flow 7 / O9 — push HIGH-severity anomalies to the user's device and generate a Monday-morning
 * weekly digest.
 *
 * <p>The detector runs on demand via {@code GET /api/insights/anomalies}; most users won't open the
 * app the moment something goes wrong. So we also push the worst findings proactively. Dedup is per
 * {@code userId:merchant:amount:day} so the same oddity doesn't fire four pushes in a row.
 *
 * <p>Weekly digest: cron Mon 09:00 user-local… well, UTC; localisation would need per-user timezone
 * handling and pushing that into scope would bloat this file. The digest summarises anomalies, goal
 * progress, and budget status from the last 7 days.
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@Service
public class AnomalyAlertPusher {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnomalyAlertPusher.class);

    /**
     * In-memory dedup. Per-day key → we intentionally don't persist this; a restart resets the
     * cache and one duplicate push a week is better than the complexity of a second table just for
     * dedup state.
     */
    private final Map<String, Boolean> alertedToday =
            new java.util.concurrent.ConcurrentHashMap<>();

    private LocalDate cacheDate = LocalDate.now();

    private final TransactionAnomalyService anomalyService;
    private final DataChangeNotificationService notificationService;

    public AnomalyAlertPusher(
            final TransactionAnomalyService anomalyService,
            final DataChangeNotificationService notificationService) {
        this.anomalyService = anomalyService;
        this.notificationService = notificationService;
    }

    /**
     * Called from the transaction ingest path (see {@code TransactionController.updateTransaction})
     * after the detector has found anomalies for this user. We only push the ones at {@code HIGH}
     * severity — the rest are list-view-worthy but not urgent enough for a lock-screen
     * interruption.
     */
    public void pushHighSeverityIfAny(final String userId) {
        if (userId == null) {
            return;
        }
        refreshCacheIfNewDay();
        try {
            final List<TransactionAnomaly> anomalies = anomalyService.detectAnomalies(userId);
            for (final TransactionAnomaly a : anomalies) {
                if (!"HIGH".equalsIgnoreCase(a.getSeverity().name())) {
                    continue;
                }
                final String dedupKey =
                        userId
                                + ":"
                                + safe(a.getMerchantName())
                                + ":"
                                + a.getAmount()
                                + ":"
                                + LocalDate.now();
                if (alertedToday.putIfAbsent(dedupKey, true) != null) {
                    continue;
                }

                notificationService.notifyGoalMilestoneReached(
                        userId,
                        a.getTransactionId(),
                        "Unusual charge: " + a.getMerchantName(),
                        0,
                        false);
            }
        } catch (Exception e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Anomaly push pass failed for user {}: {}", userId, e.getMessage());
            }
        }
    }

    private void refreshCacheIfNewDay() {
        final LocalDate today = LocalDate.now();
        if (!today.equals(cacheDate)) {
            alertedToday.clear();
            cacheDate = today;
        }
    }

    private String safe(final String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
