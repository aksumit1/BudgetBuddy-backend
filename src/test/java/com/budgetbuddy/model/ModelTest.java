package com.budgetbuddy.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for model classes
 * Tests getters, setters, constructors, and validation
 */
class ModelTest {

    @Test
    void testUser_DefaultConstructor_CreatesEmptyUser() {
        // When
        User user = new User();

        // Then
        assertNotNull(user);
        assertNull(user.getId());
        assertNull(user.getEmail());
        assertNull(user.getPassword());
    }

    @Test
    void testUser_ConstructorWithEmailAndPassword_SetsFields() {
        // Given
        String email = "test@example.com";
        String password = "hashedPassword123";

        // When
        User user = new User(email, password);

        // Then
        assertEquals(email, user.getEmail());
        assertEquals(password, user.getPassword());
        assertTrue(user.getRoles().contains(User.Role.USER), "Should have USER role by default");
    }

    @Test
    void testUser_GettersAndSetters_WorkCorrectly() {
        // Given
        User user = new User();
        Long id = 1L;
        String email = "test@example.com";
        String password = "hashedPassword";
        String firstName = "John";
        String lastName = "Doe";
        String phoneNumber = "123-456-7890";
        Boolean enabled = true;
        Boolean emailVerified = true;
        Boolean twoFactorEnabled = true;
        String preferredCurrency = "EUR";
        String timezone = "America/New_York";
        LocalDateTime createdAt = LocalDateTime.now();
        LocalDateTime updatedAt = LocalDateTime.now();
        LocalDateTime lastLoginAt = LocalDateTime.now();
        LocalDateTime passwordChangedAt = LocalDateTime.now();

        // When
        user.setId(id);
        user.setEmail(email);
        user.setPassword(password);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setPhoneNumber(phoneNumber);
        user.setEnabled(enabled);
        user.setEmailVerified(emailVerified);
        user.setTwoFactorEnabled(twoFactorEnabled);
        user.setPreferredCurrency(preferredCurrency);
        user.setTimezone(timezone);
        user.setCreatedAt(createdAt);
        user.setUpdatedAt(updatedAt);
        user.setLastLoginAt(lastLoginAt);
        user.setPasswordChangedAt(passwordChangedAt);

        // Then
        assertEquals(id, user.getId());
        assertEquals(email, user.getEmail());
        assertEquals(password, user.getPassword());
        assertEquals(firstName, user.getFirstName());
        assertEquals(lastName, user.getLastName());
        assertEquals(phoneNumber, user.getPhoneNumber());
        assertEquals(enabled, user.getEnabled());
        assertEquals(emailVerified, user.getEmailVerified());
        assertEquals(twoFactorEnabled, user.getTwoFactorEnabled());
        assertEquals(preferredCurrency, user.getPreferredCurrency());
        assertEquals(timezone, user.getTimezone());
        assertEquals(createdAt, user.getCreatedAt());
        assertEquals(updatedAt, user.getUpdatedAt());
        assertEquals(lastLoginAt, user.getLastLoginAt());
        assertEquals(passwordChangedAt, user.getPasswordChangedAt());
    }

    @Test
    void testUser_Roles_CanBeAddedAndRetrieved() {
        // Given
        User user = new User();

        // When
        user.getRoles().add(User.Role.USER);
        user.getRoles().add(User.Role.ADMIN);

        // Then
        assertTrue(user.getRoles().contains(User.Role.USER));
        assertTrue(user.getRoles().contains(User.Role.ADMIN));
        assertEquals(2, user.getRoles().size());
    }

    @Test
    void testAccount_DefaultConstructor_CreatesEmptyAccount() {
        // When
        Account account = new Account();

        // Then
        assertNotNull(account);
        assertNull(account.getId());
        assertNull(account.getUser());
        assertNull(account.getAccountName());
        assertEquals(BigDecimal.ZERO, account.getBalance());
        assertEquals("USD", account.getCurrencyCode());
        assertTrue(account.getActive());
    }

    @Test
    void testAccount_ConstructorWithUserAndName_SetsFields() {
        // Given
        User user = new User("test@example.com", "password");
        String accountName = "Checking Account";
        Account.AccountType accountType = Account.AccountType.CHECKING;

        // When
        Account account = new Account(user, accountName, accountType);

        // Then
        assertEquals(user, account.getUser());
        assertEquals(accountName, account.getAccountName());
        assertEquals(accountType, account.getAccountType());
        assertNull(account.getLastSyncedAt());
    }

    @Test
    void testAccount_GettersAndSetters_WorkCorrectly() {
        // Given
        Account account = new Account();
        Long id = 1L;
        User user = new User("test@example.com", "password");
        String accountName = "Savings Account";
        String institutionName = "Chase Bank";
        Account.AccountType accountType = Account.AccountType.SAVINGS;
        String accountSubtype = "savings";
        BigDecimal balance = new BigDecimal("1000.50");
        String currencyCode = "EUR";
        String plaidAccountId = "acc_123";
        String plaidItemId = "item_456";
        Boolean active = false;
        LocalDateTime lastSyncedAt = LocalDateTime.now();
        LocalDateTime createdAt = LocalDateTime.now();
        LocalDateTime updatedAt = LocalDateTime.now();

        // When
        account.setId(id);
        account.setUser(user);
        account.setAccountName(accountName);
        account.setInstitutionName(institutionName);
        account.setAccountType(accountType);
        account.setAccountSubtype(accountSubtype);
        account.setBalance(balance);
        account.setCurrencyCode(currencyCode);
        account.setPlaidAccountId(plaidAccountId);
        account.setPlaidItemId(plaidItemId);
        account.setActive(active);
        account.setLastSyncedAt(lastSyncedAt);
        account.setCreatedAt(createdAt);
        account.setUpdatedAt(updatedAt);

        // Then
        assertEquals(id, account.getId());
        assertEquals(user, account.getUser());
        assertEquals(accountName, account.getAccountName());
        assertEquals(institutionName, account.getInstitutionName());
        assertEquals(accountType, account.getAccountType());
        assertEquals(accountSubtype, account.getAccountSubtype());
        assertEquals(balance, account.getBalance());
        assertEquals(currencyCode, account.getCurrencyCode());
        assertEquals(plaidAccountId, account.getPlaidAccountId());
        assertEquals(plaidItemId, account.getPlaidItemId());
        assertEquals(active, account.getActive());
        assertEquals(lastSyncedAt, account.getLastSyncedAt());
        assertEquals(createdAt, account.getCreatedAt());
        assertEquals(updatedAt, account.getUpdatedAt());
    }

    @Test
    void testTransaction_DefaultConstructor_CreatesEmptyTransaction() {
        // When
        Transaction transaction = new Transaction();

        // Then
        assertNotNull(transaction);
        assertNull(transaction.getId());
        assertNull(transaction.getUser());
        assertNull(transaction.getAccount());
        assertNull(transaction.getAmount());
        assertEquals("USD", transaction.getCurrencyCode());
        assertFalse(transaction.getPending());
    }

    @Test
    void testTransaction_ConstructorWithRequiredFields_SetsFields() {
        // Given
        User user = new User("test@example.com", "password");
        Account account = new Account(user, "Checking", Account.AccountType.CHECKING);
        BigDecimal amount = new BigDecimal("-50.00");
        LocalDate transactionDate = LocalDate.now();

        // When
        Transaction transaction = new Transaction(user, account, amount, transactionDate);

        // Then
        assertEquals(user, transaction.getUser());
        assertEquals(account, transaction.getAccount());
        assertEquals(amount, transaction.getAmount());
        assertEquals(transactionDate, transaction.getTransactionDate());
    }

    @Test
    void testTransaction_GettersAndSetters_WorkCorrectly() {
        // Given
        Transaction transaction = new Transaction();
        Long id = 1L;
        User user = new User("test@example.com", "password");
        Account account = new Account(user, "Checking", Account.AccountType.CHECKING);
        BigDecimal amount = new BigDecimal("-50.00");
        String description = "Grocery Store";
        String merchantName = "Whole Foods";
        Transaction.TransactionCategory category = Transaction.TransactionCategory.FOOD_DINING;
        LocalDate transactionDate = LocalDate.now();
        String currencyCode = "EUR";
        String plaidTransactionId = "txn_123";
        Boolean pending = true;
        String location = "{\"lat\": 40.7128, \"lon\": -74.0060}";
        String paymentChannel = "in_store";
        String authorizedDate = "2024-01-15";
        LocalDateTime createdAt = LocalDateTime.now();
        LocalDateTime updatedAt = LocalDateTime.now();

        // When
        transaction.setId(id);
        transaction.setUser(user);
        transaction.setAccount(account);
        transaction.setAmount(amount);
        transaction.setDescription(description);
        transaction.setMerchantName(merchantName);
        transaction.setCategory(category);
        transaction.setTransactionDate(transactionDate);
        transaction.setCurrencyCode(currencyCode);
        transaction.setPlaidTransactionId(plaidTransactionId);
        transaction.setPending(pending);
        transaction.setLocation(location);
        transaction.setPaymentChannel(paymentChannel);
        transaction.setAuthorizedDate(authorizedDate);
        transaction.setCreatedAt(createdAt);
        transaction.setUpdatedAt(updatedAt);

        // Then
        assertEquals(id, transaction.getId());
        assertEquals(user, transaction.getUser());
        assertEquals(account, transaction.getAccount());
        assertEquals(amount, transaction.getAmount());
        assertEquals(description, transaction.getDescription());
        assertEquals(merchantName, transaction.getMerchantName());
        assertEquals(category, transaction.getCategory());
        assertEquals(transactionDate, transaction.getTransactionDate());
        assertEquals(currencyCode, transaction.getCurrencyCode());
        assertEquals(plaidTransactionId, transaction.getPlaidTransactionId());
        assertEquals(pending, transaction.getPending());
        assertEquals(location, transaction.getLocation());
        assertEquals(paymentChannel, transaction.getPaymentChannel());
        assertEquals(authorizedDate, transaction.getAuthorizedDate());
        assertEquals(createdAt, transaction.getCreatedAt());
        assertEquals(updatedAt, transaction.getUpdatedAt());
    }

    @Test
    void testBudget_DefaultConstructor_CreatesEmptyBudget() {
        // When
        Budget budget = new Budget();

        // Then
        assertNotNull(budget);
        assertNull(budget.getId());
        assertNull(budget.getUser());
        assertEquals(BigDecimal.ZERO, budget.getCurrentSpent());
        assertEquals("USD", budget.getCurrencyCode());
    }

    @Test
    void testBudget_ConstructorWithRequiredFields_SetsFields() {
        // Given
        User user = new User("test@example.com", "password");
        Transaction.TransactionCategory category = Transaction.TransactionCategory.FOOD_DINING;
        BigDecimal monthlyLimit = new BigDecimal("500.00");

        // When
        Budget budget = new Budget(user, category, monthlyLimit);

        // Then
        assertEquals(user, budget.getUser());
        assertEquals(category, budget.getCategory());
        assertEquals(monthlyLimit, budget.getMonthlyLimit());
    }

    @Test
    void testBudget_GettersAndSetters_WorkCorrectly() {
        // Given
        Budget budget = new Budget();
        Long id = 1L;
        User user = new User("test@example.com", "password");
        Transaction.TransactionCategory category = Transaction.TransactionCategory.FOOD_DINING;
        BigDecimal monthlyLimit = new BigDecimal("500.00");
        BigDecimal currentSpent = new BigDecimal("250.00");
        String currencyCode = "EUR";
        LocalDateTime createdAt = LocalDateTime.now();
        LocalDateTime updatedAt = LocalDateTime.now();

        // When
        budget.setId(id);
        budget.setUser(user);
        budget.setCategory(category);
        budget.setMonthlyLimit(monthlyLimit);
        budget.setCurrentSpent(currentSpent);
        budget.setCurrencyCode(currencyCode);
        budget.setCreatedAt(createdAt);
        budget.setUpdatedAt(updatedAt);

        // Then
        assertEquals(id, budget.getId());
        assertEquals(user, budget.getUser());
        assertEquals(category, budget.getCategory());
        assertEquals(monthlyLimit, budget.getMonthlyLimit());
        assertEquals(currentSpent, budget.getCurrentSpent());
        assertEquals(currencyCode, budget.getCurrencyCode());
        assertEquals(createdAt, budget.getCreatedAt());
        assertEquals(updatedAt, budget.getUpdatedAt());
    }

    @Test
    void testGoal_DefaultConstructor_CreatesEmptyGoal() {
        // When
        Goal goal = new Goal();

        // Then
        assertNotNull(goal);
        assertNull(goal.getId());
        assertNull(goal.getUser());
        assertEquals(BigDecimal.ZERO, goal.getCurrentAmount());
        assertEquals("USD", goal.getCurrencyCode());
        assertTrue(goal.getActive());
    }

    @Test
    void testGoal_ConstructorWithRequiredFields_SetsFields() {
        // Given
        User user = new User("test@example.com", "password");
        String name = "Vacation Fund";
        BigDecimal targetAmount = new BigDecimal("5000.00");
        LocalDate targetDate = LocalDate.now().plusMonths(6);

        // When
        Goal goal = new Goal(user, name, targetAmount, targetDate);

        // Then
        assertEquals(user, goal.getUser());
        assertEquals(name, goal.getName());
        assertEquals(targetAmount, goal.getTargetAmount());
        assertEquals(targetDate, goal.getTargetDate());
    }

    @Test
    void testGoal_GettersAndSetters_WorkCorrectly() {
        // Given
        Goal goal = new Goal();
        Long id = 1L;
        User user = new User("test@example.com", "password");
        String name = "Emergency Fund";
        String description = "Save for emergencies";
        BigDecimal targetAmount = new BigDecimal("10000.00");
        BigDecimal currentAmount = new BigDecimal("2500.00");
        LocalDate targetDate = LocalDate.now().plusMonths(12);
        BigDecimal monthlyContribution = new BigDecimal("500.00");
        Goal.GoalType goalType = Goal.GoalType.EMERGENCY_FUND;
        String currencyCode = "EUR";
        Boolean active = false;
        LocalDateTime createdAt = LocalDateTime.now();
        LocalDateTime updatedAt = LocalDateTime.now();

        // When
        goal.setId(id);
        goal.setUser(user);
        goal.setName(name);
        goal.setDescription(description);
        goal.setTargetAmount(targetAmount);
        goal.setCurrentAmount(currentAmount);
        goal.setTargetDate(targetDate);
        goal.setMonthlyContribution(monthlyContribution);
        goal.setGoalType(goalType);
        goal.setCurrencyCode(currencyCode);
        goal.setActive(active);
        goal.setCreatedAt(createdAt);
        goal.setUpdatedAt(updatedAt);

        // Then
        assertEquals(id, goal.getId());
        assertEquals(user, goal.getUser());
        assertEquals(name, goal.getName());
        assertEquals(description, goal.getDescription());
        assertEquals(targetAmount, goal.getTargetAmount());
        assertEquals(currentAmount, goal.getCurrentAmount());
        assertEquals(targetDate, goal.getTargetDate());
        assertEquals(monthlyContribution, goal.getMonthlyContribution());
        assertEquals(goalType, goal.getGoalType());
        assertEquals(currencyCode, goal.getCurrencyCode());
        assertEquals(active, goal.getActive());
        assertEquals(createdAt, goal.getCreatedAt());
        assertEquals(updatedAt, goal.getUpdatedAt());
    }
}

