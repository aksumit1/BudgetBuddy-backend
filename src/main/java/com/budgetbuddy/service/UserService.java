package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.User;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.security.PasswordHashingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
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
    private final PasswordEncoder passwordEncoder; // Legacy BCrypt encoder (kept for backward compatibility)
    private final PasswordHashingService passwordHashingService; // New PBKDF2 service

    public UserService(
            com.budgetbuddy.repository.dynamodb.UserRepository dynamoDBUserRepository,
            PasswordEncoder passwordEncoder,
            PasswordHashingService passwordHashingService) {
        this.dynamoDBUserRepository = dynamoDBUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordHashingService = passwordHashingService;
    }

    /**
     * Create user with secure format (password_hash + salt)
     */
    public UserTable createUserSecure(String email, String passwordHash, String clientSalt, 
                                      String firstName, String lastName) {
        if (email == null || email.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Email is required");
        }
        if (passwordHash == null || passwordHash.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Password hash is required");
        }
        if (clientSalt == null || clientSalt.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Salt is required");
        }

        // Check if user already exists
        if (dynamoDBUserRepository.existsByEmail(email)) {
            throw new AppException(ErrorCode.USER_ALREADY_EXISTS, "User with email " + email + " already exists");
        }

        // Perform server-side hashing (defense in depth)
        PasswordHashingService.PasswordHashResult result = passwordHashingService.hashClientPassword(
                passwordHash, clientSalt, null);

        // Create user
        UserTable user = new UserTable();
        user.setUserId(UUID.randomUUID().toString());
        user.setEmail(email);
        user.setPasswordHash(result.getHash());
        user.setServerSalt(result.getSalt());
        user.setClientSalt(clientSalt); // Store client salt for reference
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEnabled(true);
        user.setEmailVerified(false);
        user.setTwoFactorEnabled(false);
        user.setRoles(Set.of("USER"));
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());

        dynamoDBUserRepository.save(user);
        logger.info("Created new user with email: {} (secure format)", email);
        return user;
    }

    /**
     * Create user with legacy format (plaintext password) - for backward compatibility
     * @deprecated Use createUserSecure instead - Legacy JPA method removed, migration complete
     */
    @Deprecated
    public void createUser(String email, String password, String firstName, String lastName) {
        logger.warn("Legacy createUser method called - use createUserSecure instead");
        throw new UnsupportedOperationException("Legacy JPA method no longer supported. Use createUserSecure instead.");
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
     * Find user by email (Legacy JPA)
     * @deprecated Legacy JPA method removed, migration complete
     */
    @Deprecated
    public Optional<com.budgetbuddy.model.User> findByEmailLegacy(String email) {
        logger.warn("Legacy findByEmailLegacy method called - use findByEmail instead");
        // Convert DynamoDB UserTable to legacy User model if needed
        Optional<UserTable> userTable = findByEmail(email);
        return userTable.map(this::convertToLegacyUser);
    }

    /**
     * Find user by ID (Legacy JPA)
     * @deprecated Legacy JPA method removed, migration complete
     */
    @Deprecated
    public Optional<com.budgetbuddy.model.User> findByIdLegacy(String id) {
        logger.warn("Legacy findByIdLegacy method called - use findById instead");
        Optional<UserTable> userTable = findById(id);
        return userTable.map(this::convertToLegacyUser);
    }

    /**
     * Convert UserTable to legacy User model (for backward compatibility)
     */
    private com.budgetbuddy.model.User convertToLegacyUser(UserTable userTable) {
        // This is a placeholder - would need to create User entity from UserTable
        // For now, throw exception to force migration
        throw new UnsupportedOperationException("Legacy User model no longer supported. Use UserTable instead.");
    }

    /**
     * Update user
     */
    public UserTable updateUser(UserTable user) {
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
     * Update last login
     */
    public void updateLastLogin(String userId) {
        if (userId == null || userId.isEmpty()) {
            logger.warn("Attempted to update last login with null or empty user ID");
            return;
        }
        dynamoDBUserRepository.findById(userId).ifPresent(user -> {
            user.setLastLoginAt(Instant.now());
            dynamoDBUserRepository.save(user);
        });
    }

    /**
     * Change password (secure format)
     */
    public void changePasswordSecure(String userId, String passwordHash, String clientSalt) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }
        if (passwordHash == null || passwordHash.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Password hash is required");
        }
        if (clientSalt == null || clientSalt.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Salt is required");
        }

        UserTable user = dynamoDBUserRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
        
        // Perform server-side hashing
        PasswordHashingService.PasswordHashResult result = passwordHashingService.hashClientPassword(
                passwordHash, clientSalt, null);
        
        user.setPasswordHash(result.getHash());
        user.setServerSalt(result.getSalt());
        user.setClientSalt(clientSalt);
        user.setPasswordChangedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        
        dynamoDBUserRepository.save(user);
        logger.info("Password changed for user: {}", user.getEmail());
    }

    /**
     * Change password (legacy format)
     * @deprecated Use changePasswordSecure instead - Legacy JPA method removed
     */
    @Deprecated
    public void changePassword(String userId, String newPassword) {
        logger.warn("Legacy changePassword method called - use changePasswordSecure instead");
        throw new UnsupportedOperationException("Legacy JPA method no longer supported. Use changePasswordSecure instead.");
    }

    /**
     * Verify email
     */
    public void verifyEmail(String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }
        UserTable user = dynamoDBUserRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
        user.setEmailVerified(true);
        user.setUpdatedAt(Instant.now());
        dynamoDBUserRepository.save(user);
    }

    /**
     * Find user by Plaid Item ID (for webhook processing)
     */
    public Optional<UserTable> findByPlaidItemId(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return Optional.empty();
        }
        // This would require a GSI on plaidItemId
        // For now, return empty - implement when needed
        return Optional.empty();
    }

    /**
     * Update Plaid access token
     */
    public void updatePlaidAccessToken(String userId, String accessToken, String itemId) {
        dynamoDBUserRepository.findById(userId).ifPresent(user -> {
            // Store Plaid tokens securely (would need additional fields in UserTable)
            user.setUpdatedAt(Instant.now());
            dynamoDBUserRepository.save(user);
            logger.info("Updated Plaid access token for user: {}", userId);
        });
    }
}
