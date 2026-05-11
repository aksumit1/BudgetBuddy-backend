package com.budgetbuddy.service.notification;

import com.budgetbuddy.model.dynamodb.UserPreferencesTable;
import com.budgetbuddy.notification.EmailNotificationService;
import com.budgetbuddy.repository.dynamodb.UserPreferencesRepository;
import com.budgetbuddy.service.DistributedLockService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Daily-read email delivery.
 *
 * <p>Persistence-backed (preferences + per-user synthesis). The cron runs every hour on the hour,
 * checks each opted-in user's preferred send-hour (UTC), and emails only those whose hour matches.
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Service
public class DailyReadEmailService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DailyReadEmailService.class);

    private final EmailNotificationService emailService;
    private final UserPreferencesRepository prefsRepo;
    private final DailyReadSynthesis synthesis;
    private final DistributedLockService distributedLock;

    /**
     * In-process dedupe fast path. Across-instance dedupe is enforced via the hourly distributed
     * lock so only one ECS task processes a given hour.
     */
    private final Map<String, String> lastSentFingerprint = new ConcurrentHashMap<>();

    public DailyReadEmailService(
            final EmailNotificationService emailService,
            final UserPreferencesRepository prefsRepo,
            final DailyReadSynthesis synthesis,
            final DistributedLockService distributedLock) {
        this.emailService = emailService;
        this.prefsRepo = prefsRepo;
        this.synthesis = synthesis;
        this.distributedLock = distributedLock;
    }

    public void setOptedIn(final String userId, final boolean optIn) {
        final UserPreferencesTable row =
                prefsRepo.findByUserId(userId).orElseGet(UserPreferencesTable::new);
        row.setUserId(userId);
        row.setDailyReadEmailEnabled(optIn);
        if (optIn && row.getDailyReadEmailHourUtc() == null) {
            row.setDailyReadEmailHourUtc(14); // Default 2 pm UTC ≈ morning US/Europe.
        }
        prefsRepo.save(row);
    }

    public boolean isOptedIn(final String userId) {
        return prefsRepo
                .findByUserId(userId)
                .map(UserPreferencesTable::getDailyReadEmailEnabled)
                .map(Boolean::booleanValue)
                .orElse(false);
    }

    public void setHourUtc(final String userId, final int hour) {
        final UserPreferencesTable row =
                prefsRepo.findByUserId(userId).orElseGet(UserPreferencesTable::new);
        row.setUserId(userId);
        row.setDailyReadEmailHourUtc(Math.max(0, Math.min(23, hour)));
        prefsRepo.save(row);
    }

    /**
     * Fires every hour on the hour. Filters opted-in users to those whose preferred UTC hour
     * matches the current hour, synthesises their daily read, and emails it.
     */
    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    public void hourlyTick() {
        // Distributed lock ensures only one ECS task processes a given hour
        // when autoscaling. TTL is 55 min so a crashed task doesn't block the
        // next hour's run.
        final int currentHour = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).getHour();
        final String lockKey =
                "dailyReadEmail:"
                        + java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                        + ":"
                        + currentHour;
        distributedLock.runOnce(lockKey, 55, () -> processHour(currentHour));
    }

    private void processHour(final int currentHour) {
        final List<UserPreferencesTable> optIns = prefsRepo.findDailyReadOptIns();
        if (optIns.isEmpty()) {
            return;
        }

        int sent = 0;
        for (final UserPreferencesTable p : optIns) {
            Integer hour = p.getDailyReadEmailHourUtc();
            if (hour == null) {
                hour = 14;
            }
            if (hour != currentHour) {
                continue;
            }
            try {
                if (sendOne(p.getUserId())) {
                    sent++;
                }
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Daily-read send failed for user {}: {}",
                            p.getUserId(),
                            e.getMessage());
                }
            }
        }
        if (sent > 0) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Daily-read hourly tick: {} emails sent ({} opt-ins considered).",
                        sent,
                        optIns.size());
            }
        }
    }

    private boolean sendOne(final String userId) {
        final DailyReadSynthesis.Read read = synthesis.synthesise(userId);
        final String fingerprint = today() + "|" + read.headline;
        if (fingerprint.equals(lastSentFingerprint.get(userId))) {
            return false; // already sent today's content
        }
        lastSentFingerprint.put(userId, fingerprint);
        emailService.sendEmail(
                userId,
                null,
                "Today's read",
                read.headline,
                "dailyRead",
                Map.of("headline", read.headline, "mood", read.mood.name()));
        return true;
    }

    private String today() {
        return java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
    }
}
