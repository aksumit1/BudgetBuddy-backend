package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Challenge service for PAKE2 challenge-response authentication
 * Generates, stores, and verifies nonces for password authentication
 * 
 * Security features:
 * - Time-limited challenges (5 minutes expiration)
 * - One-time use challenges (consumed after verification)
 * - Thread-safe challenge storage
 * - Automatic cleanup of expired challenges
 */
@Service
public class ChallengeService {
    
    private static final Logger logger = LoggerFactory.getLogger(ChallengeService.class);
    
    // Challenge expiration time: 5 minutes
    private static final long CHALLENGE_EXPIRATION_MINUTES = 5;
    
    // Challenge nonce length: 32 bytes (256 bits) for strong entropy
    private static final int CHALLENGE_NONCE_LENGTH = 32;
    
    private final SecureRandom secureRandom = new SecureRandom();
    
    /**
     * Stores challenges with expiration times
     * Key: challenge nonce (Base64)
     * Value: ChallengeInfo (email, expiration time)
     */
    private final Map<String, ChallengeInfo> challenges = new ConcurrentHashMap<>();
    
    /**
     * Generate a challenge nonce for the given email
     * 
     * @param email User email address
     * @return ChallengeResponse with nonce and expiration time
     */
    public ChallengeResponse generateChallenge(String email) {
        if (email == null || email.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Email is required");
        }
        
        // Generate random nonce
        byte[] nonceBytes = new byte[CHALLENGE_NONCE_LENGTH];
        secureRandom.nextBytes(nonceBytes);
        String nonce = Base64.getEncoder().encodeToString(nonceBytes);
        
        // Set expiration time
        Instant expiresAt = Instant.now().plus(CHALLENGE_EXPIRATION_MINUTES, ChronoUnit.MINUTES);
        
        // Store challenge
        challenges.put(nonce, new ChallengeInfo(email.toLowerCase(), expiresAt));
        
        // Cleanup expired challenges (best-effort, non-blocking)
        cleanupExpiredChallenges();
        
        logger.debug("Generated challenge for email: {} (expires at: {})", email, expiresAt);
        
        return new ChallengeResponse(nonce, expiresAt);
    }
    
    /**
     * Verify and consume a challenge
     * Challenges are one-time use - they are removed after verification
     * 
     * @param challenge Challenge nonce to verify
     * @param email Expected email address (must match challenge email)
     * @throws AppException if challenge is invalid, expired, or email doesn't match
     */
    public void verifyAndConsumeChallenge(String challenge, String email) {
        if (challenge == null || challenge.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Challenge is required");
        }
        if (email == null || email.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Email is required");
        }
        
        ChallengeInfo info = challenges.remove(challenge);
        
        if (info == null) {
            logger.warn("Challenge verification failed: challenge not found or already used");
            throw new AppException(ErrorCode.INVALID_INPUT, "Invalid or expired challenge");
        }
        
        // Check expiration
        if (Instant.now().isAfter(info.getExpiresAt())) {
            logger.warn("Challenge verification failed: challenge expired");
            throw new AppException(ErrorCode.INVALID_INPUT, "Challenge has expired");
        }
        
        // Verify email matches (case-insensitive)
        if (!info.getEmail().equalsIgnoreCase(email)) {
            logger.warn("Challenge verification failed: email mismatch (expected: {}, got: {})", 
                    info.getEmail(), email);
            throw new AppException(ErrorCode.INVALID_INPUT, "Challenge email mismatch");
        }
        
        logger.debug("Challenge verified and consumed for email: {}", email);
    }
    
    /**
     * Clean up expired challenges to prevent memory leaks
     * This is called automatically but can be called manually if needed
     */
    private void cleanupExpiredChallenges() {
        Instant now = Instant.now();
        challenges.entrySet().removeIf(entry -> now.isAfter(entry.getValue().getExpiresAt()));
    }
    
    /**
     * Challenge information
     */
    private static class ChallengeInfo {
        private final String email;
        private final Instant expiresAt;
        
        public ChallengeInfo(String email, Instant expiresAt) {
            this.email = email;
            this.expiresAt = expiresAt;
        }
        
        public String getEmail() {
            return email;
        }
        
        public Instant getExpiresAt() {
            return expiresAt;
        }
    }
    
    /**
     * Challenge response DTO
     */
    public static class ChallengeResponse {
        private final String challenge;
        private final Instant expiresAt;
        
        public ChallengeResponse(String challenge, Instant expiresAt) {
            this.challenge = challenge;
            this.expiresAt = expiresAt;
        }
        
        public String getChallenge() {
            return challenge;
        }
        
        public Instant getExpiresAt() {
            return expiresAt;
        }
    }
}

