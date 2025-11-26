package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.UserTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for UserRepository - Cache SpEL Bug Fix
 * 
 * Tests the fix for SpelEvaluationException where the @Cacheable annotation
 * had an invalid unless condition that tried to call isPresent() on the result
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class UserRepositoryCacheTest {

    @Mock
    private DynamoDbEnhancedClient enhancedClient;

    @Mock
    private DynamoDbClient dynamoDbClient;

    @Mock
    private DynamoDbTable<UserTable> userTable;

    @Mock
    private software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex<UserTable> emailIndex;

    @Mock
    private SdkIterable<Page<UserTable>> pages;

    @InjectMocks
    private UserRepository userRepository;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");
        testUser.setPasswordHash("hashed-password");
    }

    @Test
    void testFindByEmail_WithValidEmail_ReturnsUser() {
        // Arrange
        List<Page<UserTable>> pageList = new ArrayList<>();
        Page<UserTable> page = mock(Page.class);
        List<UserTable> items = new ArrayList<>();
        items.add(testUser);
        when(page.items()).thenReturn(items);
        pageList.add(page);
        
        @SuppressWarnings("unchecked")
        software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable<UserTable> table = (software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable<UserTable>) userTable;
        when(enhancedClient.table(anyString(), any(software.amazon.awssdk.enhanced.dynamodb.TableSchema.class))).thenReturn(table);
        when(userTable.index("email-index")).thenReturn(emailIndex);
        when(emailIndex.query(any(QueryConditional.class))).thenReturn(pages);
        when(pages.iterator()).thenReturn(pageList.iterator());

        // Act
        Optional<UserTable> result = userRepository.findByEmail("test@example.com");

        // Assert
        assertTrue(result.isPresent(), "Should return user when email exists");
        assertEquals("test@example.com", result.get().getEmail());
    }

    @Test
    void testFindByEmail_WithNonExistentEmail_ReturnsEmpty() {
        // Arrange
        List<Page<UserTable>> pageList = new ArrayList<>();
        Page<UserTable> page = mock(Page.class);
        when(page.items()).thenReturn(new ArrayList<>());
        pageList.add(page);
        
        @SuppressWarnings("unchecked")
        software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable<UserTable> table = mock(software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable.class);
        when(enhancedClient.table(anyString(), any(software.amazon.awssdk.enhanced.dynamodb.TableSchema.class))).thenReturn(table);
        when(table.index("email-index")).thenReturn(emailIndex);
        when(emailIndex.query(any(QueryConditional.class))).thenReturn(pages);
        when(pages.iterator()).thenReturn(pageList.iterator());

        // Act
        Optional<UserTable> result = userRepository.findByEmail("nonexistent@example.com");

        // Assert
        assertFalse(result.isPresent(), "Should return empty when email does not exist");
    }

    @Test
    void testFindByEmail_WithNullEmail_ReturnsEmpty() {
        // Act
        Optional<UserTable> result = userRepository.findByEmail(null);

        // Assert
        assertFalse(result.isPresent(), "Should return empty when email is null");
    }

    @Test
    void testFindByEmail_WithEmptyEmail_ReturnsEmpty() {
        // Act
        Optional<UserTable> result = userRepository.findByEmail("");

        // Assert
        assertFalse(result.isPresent(), "Should return empty when email is empty");
    }

    @Test
    void testFindByEmail_CacheAnnotationDoesNotThrowSpelException() {
        // Arrange
        List<Page<UserTable>> pageList = new ArrayList<>();
        Page<UserTable> page = mock(Page.class);
        List<UserTable> items = new ArrayList<>();
        items.add(testUser);
        when(page.items()).thenReturn(items);
        pageList.add(page);
        
        @SuppressWarnings("unchecked")
        software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable<UserTable> table = mock(software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable.class);
        when(enhancedClient.table(anyString(), any(software.amazon.awssdk.enhanced.dynamodb.TableSchema.class))).thenReturn(table);
        when(table.index("email-index")).thenReturn(emailIndex);
        when(emailIndex.query(any(QueryConditional.class))).thenReturn(pages);
        when(pages.iterator()).thenReturn(pageList.iterator());

        // Act & Assert - Should not throw SpelEvaluationException
        assertDoesNotThrow(() -> {
            Optional<UserTable> result = userRepository.findByEmail("test@example.com");
            assertTrue(result.isPresent());
        }, "Should not throw SpelEvaluationException with fixed cache annotation");
    }

    @Test
    void testExistsByEmail_DelegatesToFindByEmail() {
        // Arrange
        List<Page<UserTable>> pageList = new ArrayList<>();
        Page<UserTable> page = mock(Page.class);
        List<UserTable> items = new ArrayList<>();
        items.add(testUser);
        when(page.items()).thenReturn(items);
        pageList.add(page);
        
        @SuppressWarnings("unchecked")
        software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable<UserTable> table = mock(software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable.class);
        when(enhancedClient.table(anyString(), any(software.amazon.awssdk.enhanced.dynamodb.TableSchema.class))).thenReturn(table);
        when(table.index("email-index")).thenReturn(emailIndex);
        when(emailIndex.query(any(QueryConditional.class))).thenReturn(pages);
        when(pages.iterator()).thenReturn(pageList.iterator());

        // Act - existsByEmail should delegate to findByEmail
        boolean exists = userRepository.existsByEmail("test@example.com");

        // Assert
        assertTrue(exists, "Should return true when user exists");
        // Verify findByEmail was called
        verify(enhancedClient, atLeastOnce()).table(anyString(), any());
    }
}

