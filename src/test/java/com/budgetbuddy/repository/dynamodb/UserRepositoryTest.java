package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.UserTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for UserRepository
 * Tests user CRUD operations and email lookup
 */
@ExtendWith(MockitoExtension.class)
class UserRepositoryTest {

    @Mock
    private DynamoDbEnhancedClient enhancedClient;

    @Mock
    private DynamoDbClient dynamoDbClient;

    @Mock
    private DynamoDbTable<UserTable> userTable;

    @Mock
    private DynamoDbIndex<UserTable> emailIndex;

    private UserRepository userRepository;
    private String testUserId;
    private String testEmail;
    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        testEmail = "test@example.com";
        
        when(enhancedClient.table(anyString(), any(software.amazon.awssdk.enhanced.dynamodb.TableSchema.class)))
                .thenReturn(userTable);
        when(userTable.index("EmailIndex")).thenReturn(emailIndex);
        
        userRepository = new UserRepository(enhancedClient, dynamoDbClient, "BudgetBuddy");
        
        testUser = new UserTable();
        testUser.setUserId(testUserId);
        testUser.setEmail(testEmail);
        testUser.setFirstName("John");
        testUser.setLastName("Doe");
    }

    @Test
    void testSave_WithValidUser_SavesSuccessfully() {
        // Given
        doNothing().when(userTable).putItem(any(UserTable.class));

        // When
        userRepository.save(testUser);

        // Then
        verify(userTable, times(1)).putItem(testUser);
    }

    @Test
    void testFindById_WithValidId_ReturnsUser() {
        // Given
        when(userTable.getItem(any(Key.class))).thenReturn(testUser);

        // When
        Optional<UserTable> result = userRepository.findById(testUserId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(testUserId, result.get().getUserId());
        verify(userTable, times(1)).getItem(any(Key.class));
    }

    @Test
    void testFindById_WithNonExistentId_ReturnsEmpty() {
        // Given
        when(userTable.getItem(any(Key.class))).thenReturn(null);

        // When
        Optional<UserTable> result = userRepository.findById(testUserId);

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testFindByEmail_WithValidEmail_ReturnsUser() {
        // Given
        Page<UserTable> page = mock(Page.class);
        when(page.items()).thenReturn(List.of(testUser));
        SdkIterable<Page<UserTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(emailIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        Optional<UserTable> result = userRepository.findByEmail(testEmail);

        // Then
        assertTrue(result.isPresent());
        assertEquals(testEmail, result.get().getEmail());
    }

    @Test
    void testFindByEmail_WithNullEmail_ReturnsEmpty() {
        // When
        Optional<UserTable> result = userRepository.findByEmail(null);

        // Then
        assertFalse(result.isPresent());
        verify(emailIndex, never()).query(any(QueryConditional.class));
    }

    @Test
    void testFindByEmail_WithEmptyEmail_ReturnsEmpty() {
        // When
        Optional<UserTable> result = userRepository.findByEmail("");

        // Then
        assertFalse(result.isPresent());
        verify(emailIndex, never()).query(any(QueryConditional.class));
    }

    @Test
    void testFindByEmail_WithException_ReturnsEmpty() {
        // Given
        when(emailIndex.query(any(QueryConditional.class)))
                .thenThrow(new RuntimeException("DynamoDB error"));

        // When
        Optional<UserTable> result = userRepository.findByEmail(testEmail);

        // Then
        assertFalse(result.isPresent(), "Should return empty on exception");
    }

    @Test
    void testFindByEmail_WithNonExistentEmail_ReturnsEmpty() {
        // Given
        Page<UserTable> page = mock(Page.class);
        when(page.items()).thenReturn(Collections.emptyList());
        SdkIterable<Page<UserTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(emailIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        Optional<UserTable> result = userRepository.findByEmail("nonexistent@example.com");

        // Then
        assertFalse(result.isPresent());
    }
}

