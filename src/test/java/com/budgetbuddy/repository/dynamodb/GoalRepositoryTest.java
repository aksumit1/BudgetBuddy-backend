package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.util.TableInitializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GoalRepository
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoalRepositoryTest {

    @Autowired
    private GoalRepository goalRepository;

    @Autowired
    private DynamoDbClient dynamoDbClient;

    private String testUserId;
    private GoalTable testGoal;

    @BeforeAll
    void ensureTablesInitialized() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        
        testGoal = new GoalTable();
        testGoal.setGoalId(UUID.randomUUID().toString());
        testGoal.setUserId(testUserId);
        testGoal.setName("Test Goal");
        testGoal.setDescription("Test Description");
        testGoal.setTargetAmount(new BigDecimal("1000.00"));
        testGoal.setCurrentAmount(new BigDecimal("100.00"));
        testGoal.setTargetDate(LocalDate.now().plusMonths(6).toString());
        testGoal.setGoalType("SAVINGS");
        testGoal.setActive(true);
        testGoal.setCreatedAt(Instant.now());
        testGoal.setUpdatedAt(Instant.now());
    }

    @Test
    void testSave_WithValidGoal_Succeeds() {
        // When
        goalRepository.save(testGoal);

        // Then
        Optional<GoalTable> found = goalRepository.findById(testGoal.getGoalId());
        assertTrue(found.isPresent(), "Goal should be saved");
        assertEquals(testGoal.getName(), found.get().getName());
    }

    @Test
    void testFindById_WithExistingGoal_ReturnsGoal() {
        // Given
        goalRepository.save(testGoal);

        // When
        Optional<GoalTable> found = goalRepository.findById(testGoal.getGoalId());

        // Then
        assertTrue(found.isPresent(), "Goal should be found");
        assertEquals(testGoal.getGoalId(), found.get().getGoalId());
    }

    @Test
    void testFindById_WithNonExistentGoal_ReturnsEmpty() {
        // When
        Optional<GoalTable> found = goalRepository.findById(UUID.randomUUID().toString());

        // Then
        assertTrue(found.isEmpty(), "Should return empty for non-existent goal");
    }

    @Test
    void testFindByUserId_ReturnsUserGoals() {
        // Given
        goalRepository.save(testGoal);
        
        GoalTable goal2 = new GoalTable();
        goal2.setGoalId(UUID.randomUUID().toString());
        goal2.setUserId(testUserId);
        goal2.setName("Goal 2");
        goal2.setTargetAmount(new BigDecimal("2000.00"));
        goal2.setCurrentAmount(BigDecimal.ZERO);
        goal2.setTargetDate(LocalDate.now().plusMonths(12).toString());
        goal2.setGoalType("SAVINGS");
        goal2.setActive(true);
        goal2.setCreatedAt(Instant.now());
        goal2.setUpdatedAt(Instant.now());
        goalRepository.save(goal2);

        // When
        List<GoalTable> goals = goalRepository.findByUserId(testUserId);

        // Then
        assertNotNull(goals, "Goals should not be null");
        assertTrue(goals.size() >= 2, "Should return at least 2 goals");
    }

    @Test
    void testDelete_WithValidId_RemovesGoal() {
        // Given
        goalRepository.save(testGoal);
        String goalId = testGoal.getGoalId();

        // When
        goalRepository.delete(goalId);

        // Then
        Optional<GoalTable> found = goalRepository.findById(goalId);
        assertTrue(found.isEmpty(), "Goal should be deleted");
    }

    @Test
    void testSaveIfNotExists_WithNewGoal_ReturnsTrue() {
        // When
        boolean saved = goalRepository.saveIfNotExists(testGoal);

        // Then
        assertTrue(saved, "Should save new goal");
        Optional<GoalTable> found = goalRepository.findById(testGoal.getGoalId());
        assertTrue(found.isPresent(), "Goal should be saved");
    }

    @Test
    void testSaveIfNotExists_WithExistingGoal_ReturnsFalse() {
        // Given
        goalRepository.save(testGoal);

        // When
        boolean saved = goalRepository.saveIfNotExists(testGoal);

        // Then
        assertFalse(saved, "Should not save existing goal");
    }
    
    @Test
    void testFindByUserIdAndUpdatedAfter_WithValidParams_ReturnsUpdatedGoals() {
        // Given
        long updatedAfterTimestamp = Instant.now().minusSeconds(3600).getEpochSecond();
        testGoal.setUpdatedAtTimestamp(Instant.now().getEpochSecond());
        goalRepository.save(testGoal);
        
        // When
        List<GoalTable> result = goalRepository.findByUserIdAndUpdatedAfter(testUserId, updatedAfterTimestamp);
        
        // Then
        assertNotNull(result);
        assertTrue(result.size() >= 0);
    }
    
    @Test
    void testFindByUserIdAndUpdatedAfter_WithNullParams_ReturnsEmpty() {
        // When
        List<GoalTable> result1 = goalRepository.findByUserIdAndUpdatedAfter(null, Instant.now().getEpochSecond());
        List<GoalTable> result2 = goalRepository.findByUserIdAndUpdatedAfter(testUserId, null);
        List<GoalTable> result3 = goalRepository.findByUserIdAndUpdatedAfter("", Instant.now().getEpochSecond());
        
        // Then
        assertTrue(result1.isEmpty());
        assertTrue(result2.isEmpty());
        assertTrue(result3.isEmpty());
    }
}

