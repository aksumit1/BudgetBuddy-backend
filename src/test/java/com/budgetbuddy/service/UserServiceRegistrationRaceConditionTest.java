package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.security.PasswordHashingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for User Registration Race Condition
 * Tests the fix for concurrent registration attempts causing duplicate users
 * 
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class UserServiceRegistrationRaceConditionTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Mock
    private PasswordHashingService passwordHashingService;

    private UserService userService;
    private String testEmail;
    private String testPasswordHash;
    private String testClientSalt;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordHashingService);
        testEmail = "test-" + UUID.randomUUID() + "@example.com";
        testPasswordHash = "hashed-password";
        testClientSalt = "client-salt";
    }

    @Test
    void testConcurrentRegistration_ShouldPreventDuplicates() throws Exception {
        // Given - Multiple threads trying to register the same email simultaneously
        PasswordHashingService.PasswordHashResult serverHash =
                new PasswordHashingService.PasswordHashResult("server-hash", "server-salt");
        when(passwordHashingService.hashClientPassword(anyString(), isNull()))
                .thenReturn(serverHash);
        when(userRepository.saveIfNotExists(any(UserTable.class))).thenReturn(true);
        
        // Simulate race condition: After first save, findAllByEmail returns 1 user (the one just created)
        // After second save, findAllByEmail returns 2 users (duplicate detected)
        when(userRepository.findAllByEmail(testEmail))
                .thenReturn(createUserList(1))  // First call: only the new user
                .thenReturn(createUserList(2));  // Second call: duplicate detected
        doNothing().when(userRepository).delete(anyString());

        // When - Multiple concurrent registration attempts
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<CompletableFuture<UserTable>> futures = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            CompletableFuture<UserTable> future = CompletableFuture.supplyAsync(() -> {
                try {
                    // BREAKING CHANGE: Client salt removed - backend handles salt management
                    return userService.createUserSecure(
                            testEmail,
                            testPasswordHash,
                            "Test",
                            "User"
                    );
                } catch (AppException e) {
                    return null;
                }
            }, executor);
            futures.add(future);
        }

        // Wait for all to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(5, TimeUnit.SECONDS);

        // Then - Only one user should be created, others should throw USER_ALREADY_EXISTS
        long successCount = futures.stream()
                .map(f -> {
                    try {
                        return f.get() != null ? 1 : 0;
                    } catch (Exception e) {
                        return 0;
                    }
                })
                .reduce(0, Integer::sum);

        // Verify that duplicate detection works
        verify(userRepository, atLeastOnce()).findAllByEmail(testEmail);
        assertTrue(successCount <= 1, "Only one registration should succeed");
    }

    @Test
    void testRegistration_WithDuplicateEmail_ThrowsException() {
        // Given - User already exists
        PasswordHashingService.PasswordHashResult serverHash =
                new PasswordHashingService.PasswordHashResult("server-hash", "server-salt");
        when(passwordHashingService.hashClientPassword(anyString(), isNull()))
                .thenReturn(serverHash);
        when(userRepository.findByEmail(testEmail)).thenReturn(java.util.Optional.empty());
        when(userRepository.saveIfNotExists(any(UserTable.class))).thenReturn(true);
        when(userRepository.findAllByEmail(testEmail)).thenReturn(createUserList(2)); // Duplicate found

        // When/Then
        assertThrows(AppException.class, () -> {
            // BREAKING CHANGE: Client salt removed
            userService.createUserSecure(
                    testEmail,
                    testPasswordHash,
                    "Test",
                    "User"
            );
        });
    }

    private List<UserTable> createUserList(int count) {
        List<UserTable> users = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UserTable user = new UserTable();
            user.setUserId(UUID.randomUUID().toString());
            user.setEmail(testEmail);
            users.add(user);
        }
        return users;
    }
}

