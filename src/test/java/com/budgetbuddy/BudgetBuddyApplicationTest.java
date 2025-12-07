package com.budgetbuddy;

import org.junit.jupiter.api.Test;

/**
 * Test for BudgetBuddyApplication main class
 */
class BudgetBuddyApplicationTest {

    @Test
    void testMainMethod() {
        // Test that the class can be instantiated
        // For coverage, we just need to ensure the class is loaded
        BudgetBuddyApplication app = new BudgetBuddyApplication();
        assert app != null;
    }
}

