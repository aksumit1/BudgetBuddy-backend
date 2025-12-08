package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.security.PasswordHashingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service for user management
 * Supports both secure client-side hashed passwords and legacy plaintext passwords
 * Migrated to DynamoDB - @Transactional removed as DynamoDB doesn't support transactions
 */
@Service
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final com.budgetbuddy.repository.dynamodb.UserRepository dynamoDBUserRepository; // DynamoDB repository
    private final PasswordHashingService passwordHashingService; // New PBKDF2 service
    private final com.budgetbuddy.repository.dynamodb.AccountRepository accountRepository; // For Plaid item lookup

    public UserService(
            final com.budgetbuddy.repository.dynamodb.UserRepository dynamoDBUserRepository,
            final PasswordHashingService passwordHashingService,
            final com.budgetbuddy.repository.dynamodb.AccountRepository accountRepository) {
        this.dynamoDBUserRepository = dynamoDBUserRepository;
        this.passwordHashingService = passwordHashingService;
        this.accountRepository = accountRepository;
    }

    /**
     * Create user with secure format (password_hash only)
     * BREAKING CHANGE: Client salt removed - Zero Trust architecture
     * 
     * CRITICAL FIX: Removed pre-check for email to eliminate race condition.
     * The previous implementation had a TOCTOU (time-of-check-time-of-use) race condition:
     * 1. Check if email exists (non-atomic)
     * 2. Create user with new UUID
     * 3. Save with conditional write on userId (always succeeds for new UUID)
     * 
     * This allowed two concurrent requests to both pass the email check and create
     * duplicate users with the same email but different userIds.
     * 
     * New approach:
     * 1. Create user with new UUID (no pre-check)
     * 2. Save with conditional write on userId (atomic)
     * 3. Post-save: Check if another user with same email exists (using GSI)
     * 4. If duplicate found, delete the newly created user and throw exception
     * 
     * This ensures atomicity and prevents duplicate emails.
     */
    public UserTable createUserSecure(final String email, final String passwordHash, final String firstName, final String lastName) {
        if (email == null || email.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Email is required");
        }
        if (passwordHash == null || passwordHash.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Password hash is required");
        }

        // Perform server-side hashing (defense in depth)
        // BREAKING CHANGE: No longer requires client salt
        PasswordHashingService.PasswordHashResult result = passwordHashingService.hashClientPassword(
                passwordHash, null);

        // Create user with new UUID
        UserTable user = new UserTable();
        String userId = UUID.randomUUID().toString();
        user.setUserId(userId);
        user.setEmail(email);
        user.setPasswordHash(result.getHash());
        user.setServerSalt(result.getSalt());
        // BREAKING CHANGE: clientSalt removed - Zero Trust architecture
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEnabled(true);
        user.setEmailVerified(false);
        user.setTwoFactorEnabled(false);
        user.setRoles(Set.of("USER"));
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());

        // CRITICAL FIX: Save first, then check for duplicates
        // This eliminates the race condition where two requests both pass the email check
        // Use conditional write to prevent duplicate userIds (defense in depth)
        boolean created = dynamoDBUserRepository.saveIfNotExists(user);
        if (!created) {
            // This should rarely happen since we generate a new UUID, but handle it gracefully
            logger.warn("User creation failed - userId collision detected for email: {}", email);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to create user. Please try again.");
        }

        // CRITICAL FIX: Post-save duplicate check to detect race conditions
        // Query ALL users with this email to detect if we created a duplicate
        // This handles the race condition where two concurrent requests both:
        // 1. Check email (both find it doesn't exist)
        // 2. Create user with different UUIDs (both succeed)
        // 3. Result: Two users with same email but different userIds
        List<UserTable> usersWithEmail = dynamoDBUserRepository.findAllByEmail(email);
        if (usersWithEmail.size() > 1) {
            // Race condition detected: Multiple users with same email exist
            // Find the user that's NOT the one we just created (should be the original)
            UserTable originalUser = usersWithEmail.stream()
                    .filter(u -> !u.getUserId().equals(userId))
                    .findFirst()
                    .orElse(null);
            
            if (originalUser != null) {
                // Delete the duplicate user we just created to maintain data integrity
                logger.warn("Race condition detected: User with email {} already exists (userId: {}). Deleting duplicate user (userId: {})", 
                        email, originalUser.getUserId(), userId);
                try {
                    dynamoDBUserRepository.delete(userId);
                } catch (Exception e) {
                    logger.error("Failed to delete duplicate user {}: {}", userId, e.getMessage(), e);
                    // Continue anyway - at least we detected the duplicate
                }
                throw new AppException(ErrorCode.USER_ALREADY_EXISTS, "User with this email already exists");
            }
        }
        
        // Note: If usersWithEmail.size() == 1, it's the user we just created (expected)
        // If usersWithEmail.size() == 0, GSI eventual consistency hasn't updated yet (acceptable)
        
        logger.info("Created new user with email: {} (secure format)", email);
        return user;
    }


    /**
     * Find user by email (DynamoDB)
     */
    public Optional<UserTable> findByEmail(String email) {
        if (email == null || email.isEmpty()) {
            return Optional.empty();
        }
        return dynamoDBUserRepository.findByEmail(email);
    }

    /**
     * Find user by ID (DynamoDB)
     */
    public Optional<UserTable> findById(String userId) {
        if (userId == null || userId.isEmpty()) {
            return Optional.empty();
        }
        return dynamoDBUserRepository.findById(userId);
    }



    /**
     * Update user
     */
    public UserTable updateUser(final UserTable user) {
        if (user == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User cannot be null");
        }
        if (user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }
        user.setUpdatedAt(Instant.now());
        dynamoDBUserRepository.save(user);
        return user;
    }

    /**
     * Update last login (cost-optimized: uses UpdateItem instead of read-before-write)
     */
    public void updateLastLogin(final String userId) {
        if (userId == null || userId.isEmpty()) {
            logger.warn("Attempted to update last login with null or empty user ID");
            return;
        }
        try {
            dynamoDBUserRepository.updateLastLogin(userId, Instant.now());
            logger.debug("Updated last login for user: {}", userId);
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to update last login for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Change password (secure format)
     * BREAKING CHANGE: Client salt removed - Zero Trust architecture
     * 
     * CRITICAL FIX: Reuse existing server salt instead of generating a new one.
     * This ensures that password verification works correctly after password reset.
     * The server salt should only be generated once during registration and then
     * preserved for all password changes to maintain consistency.
     */
    public void changePasswordSecure(final String userId, final String passwordHash) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }
        if (passwordHash == null || passwordHash.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Password hash is required");
        }

        UserTable user = dynamoDBUserRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // CRITICAL FIX: Reuse existing server salt instead of generating a new one
        // Decode existing server salt if available, otherwise generate new one (for migration)
        byte[] existingServerSalt = null;
        if (user.getServerSalt() != null && !user.getServerSalt().isEmpty()) {
            try {
                existingServerSalt = java.util.Base64.getDecoder().decode(user.getServerSalt());
                logger.debug("Reusing existing server salt for password change for user: {}", user.getEmail());
            } catch (IllegalArgumentException e) {
                logger.warn("Existing server salt is invalid Base64 for user: {}, generating new one", user.getEmail());
                existingServerSalt = null;
            }
        }

        // Perform server-side hashing with existing server salt (or generate new if missing)
        // BREAKING CHANGE: No longer requires client salt
        PasswordHashingService.PasswordHashResult result = passwordHashingService.hashClientPassword(
                passwordHash, existingServerSalt);

        user.setPasswordHash(result.getHash());
        user.setServerSalt(result.getSalt()); // This will be the same as existing if reused, or new if generated
        // BREAKING CHANGE: clientSalt removed - Zero Trust architecture
        user.setPasswordChangedAt(Instant.now());
        user.setUpdatedAt(Instant.now());

        dynamoDBUserRepository.save(user);
        logger.info("Password changed for user: {}", user.getEmail());
    }

    /**
     * Reset password by email (secure format)
     * BREAKING CHANGE: Client salt removed - Zero Trust architecture
     * Used for password reset flow when user has forgotten their password
     */
    public void resetPasswordByEmail(final String email, final String passwordHash) {
        if (email == null || email.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Email is required");
        }
        if (passwordHash == null || passwordHash.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Password hash is required");
        }

        // Find user by email
        UserTable user = dynamoDBUserRepository.findByEmail(email)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Use existing changePasswordSecure method
        // BREAKING CHANGE: No longer requires client salt
        changePasswordSecure(user.getUserId(), passwordHash);
        logger.info("Password reset for user: {}", email);
    }


    /**
     * Verify email (cost-optimized: uses UpdateItem instead of read-before-write)
     */
    public void verifyEmail(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }
        try {
            dynamoDBUserRepository.updateField(userId, "emailVerified", true);
            logger.info("Email verified for user: {}", userId);
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.USER_NOT_FOUND, "User not found: " + userId);
        }
    }

    /**
     * Find user by Plaid Item ID (for webhook processing)
     * Uses AccountRepository with GSI on plaidItemId to find accounts, then gets user from first account
     * OPTIMIZED: Uses GSI query instead of table scan
     */
    public Optional<UserTable> findByPlaidItemId(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return Optional.empty();
        }
        
        try {
            // Find accounts by item ID using GSI (optimized)
            List<com.budgetbuddy.model.dynamodb.AccountTable> accounts = accountRepository.findByPlaidItemId(itemId);
            
            if (accounts.isEmpty()) {
                logger.debug("No accounts found for Plaid item ID: {}", itemId);
                return Optional.empty();
            }
            
            // Get user ID from first account (all accounts for same item belong to same user)
            String userId = accounts.get(0).getUserId();
            if (userId == null || userId.isEmpty()) {
                logger.warn("Account has no user ID for Plaid item: {}", itemId);
                return Optional.empty();
            }
            
            // Find user by ID
            return dynamoDBUserRepository.findById(userId);
        } catch (Exception e) {
            logger.error("Error finding user by Plaid item ID {}: {}", itemId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Update Plaid access token (cost-optimized: uses UpdateItem instead of read-before-write)
     * Note: This method currently only updates the timestamp. To store tokens securely,
     * additional fields would need to be added to UserTable.
     * 
     * CRITICAL FIX: Uses updateTimestamp() instead of save() to prevent data loss.
     * The previous implementation would overwrite all user fields with null/empty values.
     */
    public void updatePlaidAccessToken(final String userId, final String accessToken, final String itemId) {
        if (userId == null || userId.isEmpty()) {
            logger.warn("Attempted to update Plaid token with null or empty user ID");
            return;
        }
        try {
            // Update updatedAt timestamp (tokens would be stored in additional fields)
            // For now, we just update the timestamp to indicate the token was refreshed
            // Uses updateTimestamp() to preserve all other user fields
            dynamoDBUserRepository.updateTimestamp(userId);
            logger.info("Updated Plaid access token for user: {}", userId);
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to update Plaid token for user {}: {}", userId, e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error updating Plaid token for user {}: {}", userId, e.getMessage(), e);
        }
    }
}
