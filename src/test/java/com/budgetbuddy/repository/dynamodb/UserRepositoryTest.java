package com.budgetbuddy.repository.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.UserTable;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Unit Tests for UserRepository Tests user CRUD operations and email lookup */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class UserRepositoryTest {

    @Mock private DynamoDbEnhancedClient enhancedClient;

    @Mock private DynamoDbClient dynamoDbClient;

    @Mock private DynamoDbTable<UserTable> userTable;

    @Mock private DynamoDbIndex<UserTable> emailIndex;

    private UserRepository userRepository;
    private String testUserId;
    private String testEmail;
    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        testEmail = "test@example.com";

        // Use lenient stubbing to avoid unnecessary stubbing errors for tests that don't use these
        org.mockito.Mockito.lenient()
                .when(
                        enhancedClient.table(
                                anyString(),
                                any(software.amazon.awssdk.enhanced.dynamodb.TableSchema.class)))
                .thenReturn(userTable);
        org.mockito.Mockito.lenient().when(userTable.index("EmailIndex")).thenReturn(emailIndex);

        userRepository = new UserRepository(enhancedClient, dynamoDbClient, "BudgetBuddy");

        testUser = new UserTable();
        testUser.setUserId(testUserId);
        testUser.setEmail(testEmail);
        testUser.setFirstName("John");
        testUser.setLastName("Doe");
    }

    @Test
    void testSaveWithValidUserSavesSuccessfully() {
        // Given
        doNothing().when(userTable).putItem(any(UserTable.class));

        // When
        userRepository.save(testUser);

        // Then
        verify(userTable, times(1)).putItem(testUser);
    }

    @Test
    void testFindByIdWithValidIdReturnsUser() {
        // Given
        when(userTable.getItem(any(Key.class))).thenReturn(testUser);

        // When
        final Optional<UserTable> result = userRepository.findById(testUserId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(testUserId, result.get().getUserId());
        verify(userTable, times(1)).getItem(any(Key.class));
    }

    @Test
    void testFindByIdWithNonExistentIdReturnsEmpty() {
        // Given
        when(userTable.getItem(any(Key.class))).thenReturn(null);

        // When
        final Optional<UserTable> result = userRepository.findById(testUserId);

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testFindByEmailWithValidEmailReturnsUser() {
        // Given
        final Page<UserTable> page = mock(Page.class);
        when(page.items()).thenReturn(List.of(testUser));
        final SdkIterable<Page<UserTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(emailIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        final Optional<UserTable> result = userRepository.findByEmail(testEmail);

        // Then
        assertTrue(result.isPresent());
        assertEquals(testEmail, result.get().getEmail());
    }

    @Test
    void testFindByEmailWithNullEmailReturnsEmpty() {
        // When
        final Optional<UserTable> result = userRepository.findByEmail(null);

        // Then
        assertFalse(result.isPresent());
        verify(emailIndex, never()).query(any(QueryConditional.class));
    }

    @Test
    void testFindByEmailWithEmptyEmailReturnsEmpty() {
        // When
        final Optional<UserTable> result = userRepository.findByEmail("");

        // Then
        assertFalse(result.isPresent());
        verify(emailIndex, never()).query(any(QueryConditional.class));
    }

    @Test
    void testFindByEmailWithExceptionReturnsEmpty() {
        // Given
        when(emailIndex.query(any(QueryConditional.class)))
                .thenThrow(new RuntimeException("DynamoDB error"));

        // When
        final Optional<UserTable> result = userRepository.findByEmail(testEmail);

        // Then
        assertFalse(result.isPresent(), "Should return empty on exception");
    }

    @Test
    void testFindByEmailWithNonExistentEmailReturnsEmpty() {
        // Given
        final Page<UserTable> page = mock(Page.class);
        when(page.items()).thenReturn(Collections.emptyList());
        final SdkIterable<Page<UserTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(List.of(page).iterator());
        when(emailIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        final Optional<UserTable> result = userRepository.findByEmail("nonexistent@example.com");

        // Then
        assertFalse(result.isPresent());
    }
}
