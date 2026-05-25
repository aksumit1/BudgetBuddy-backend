package com.budgetbuddy.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the per-user-per-SHA dedup added to {@link FileUploadRateLimiter}.
 * Catches the regression where a re-upload of the same PDF bytes within
 * the rate-limit window is no longer detected (the "scripted retry"
 * abuse pattern). Legitimate "user uploaded the wrong file, then the
 * right one" is unaffected since the hash differs.
 */
class FileUploadRateLimiterHashDedupTest {

    private FileUploadRateLimiter limiter;

    @BeforeEach
    void setUp() throws Exception {
        limiter = new FileUploadRateLimiter();
        // @Value fields aren't populated in unit-test scope; wire defaults.
        setField(limiter, "rateLimitEnabled", true);
        setField(limiter, "maxUploadsPerHour", 500);
        setField(limiter, "maxTotalSizePerHour", 1024L * 1024 * 1024);
        setField(limiter, "minTimeBetweenUploadsMs", 0L);
    }

    @Test
    void firstUploadOfHash_isNotDuplicate() {
        assertFalse(limiter.isDuplicateUpload("user-1", "abc123"),
                "first time a hash is seen must NOT be flagged as duplicate");
    }

    @Test
    void secondUploadOfSameHash_byTheSameUser_isDuplicate() {
        assertFalse(limiter.isDuplicateUpload("user-1", "abc123"));
        assertTrue(limiter.isDuplicateUpload("user-1", "abc123"),
                "re-uploading the same hash must be flagged");
    }

    @Test
    void sameHash_differentUser_notDuplicate() {
        assertFalse(limiter.isDuplicateUpload("user-1", "abc123"));
        assertFalse(limiter.isDuplicateUpload("user-2", "abc123"),
                "user-2's first upload of the same hash is fresh for them");
    }

    @Test
    void differentHash_sameUser_notDuplicate() {
        assertFalse(limiter.isDuplicateUpload("user-1", "abc123"));
        assertFalse(limiter.isDuplicateUpload("user-1", "def456"),
                "different hash = different file, must be allowed");
    }

    @Test
    void recordHash_makesSubsequentCallDuplicate() {
        limiter.recordHash("user-1", "xyz789");
        assertTrue(limiter.isDuplicateUpload("user-1", "xyz789"));
    }

    @Test
    void rateLimitDisabled_neverDedupes() throws Exception {
        setField(limiter, "rateLimitEnabled", false);
        assertFalse(limiter.isDuplicateUpload("user-1", "abc123"));
        assertFalse(limiter.isDuplicateUpload("user-1", "abc123"),
                "with rate-limit disabled, dedup must be a no-op");
    }

    @Test
    void nullHash_alwaysAllowed() {
        assertFalse(limiter.isDuplicateUpload("user-1", null));
        assertFalse(limiter.isDuplicateUpload("user-1", ""));
        assertFalse(limiter.isDuplicateUpload("user-1", "   "));
    }

    @Test
    void nullUserId_alwaysAllowed() {
        assertFalse(limiter.isDuplicateUpload(null, "abc123"));
    }

    private static void setField(final Object target, final String name, final Object value)
            throws IllegalAccessException {
        try {
            final Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }
}
