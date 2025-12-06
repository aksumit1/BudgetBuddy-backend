package com.budgetbuddy.config;

import com.budgetbuddy.AWSTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PerformanceConfig
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class PerformanceConfigTest {

    @Autowired(required = false)
    @Qualifier("taskExecutor")
    private Executor taskExecutor;

    @Autowired(required = false)
    @Qualifier("highPriorityExecutor")
    private Executor highPriorityExecutor;

    @Test
    void testTaskExecutor_IsCreated() {
        // Then
        assertNotNull(taskExecutor, "TaskExecutor should be created");
    }

    @Test
    void testTaskExecutor_CanExecuteTasks() {
        // Given
        if (taskExecutor == null) {
            return;
        }

        // When
        assertDoesNotThrow(() -> {
            taskExecutor.execute(() -> {
                // Simple task
            });
        }, "Should be able to execute tasks");
    }

    @Test
    void testHighPriorityExecutor_IsCreated() {
        // Then
        assertNotNull(highPriorityExecutor, "HighPriorityExecutor should be created");
    }

    @Test
    void testHighPriorityExecutor_CanExecuteTasks() {
        // Given
        if (highPriorityExecutor == null) {
            return;
        }

        // When
        assertDoesNotThrow(() -> {
            highPriorityExecutor.execute(() -> {
                // Simple task
            });
        }, "Should be able to execute tasks");
    }
}

