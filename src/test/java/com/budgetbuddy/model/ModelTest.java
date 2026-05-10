package com.budgetbuddy.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** Comprehensive tests for model classes Tests getters, setters, constructors, and validation */
class ModelTest {

    private static final String PASSWORD = "password";

    @Test
    void testUserDefaultConstructorCreatesEmptyUser() {
        // When
        final User user = new User();

        // Then
        assertNotNull(user);
        assertNull(user.getId());
        assertNull(user.getEmail());
        assertNull(user.getPassword());
    }

    @Test
    void testUserConstructorWithEmailAndPasswordSetsFields() {
        // Given
        final String email = "test@example.com";
        final String password = "hashedPassword123";

        // When
        final User user = new User(email, password);

        // Then
        assertEquals(email, user.getEmail());
        assertEquals(password, user.getPassword());
        assertTrue(user.getRoles().contains(User.Role.USER), "Should have USER role by default");
    }

    @Test
    void testUserGettersAndSettersWorkCorrectly() {
        // Given
        final User user = new User();
        final Long id = 1L;
        final String email = "test@example.com";
        final String password = "hashedPassword";
        final String firstName = "John";
        final String lastName = "Doe";
        final String phoneNumber = "123-456-7890";
        final Boolean enabled = true;
        final Boolean emailVerified = true;
        final Boolean twoFactorEnabled = true;
        final String preferredCurrency = "EUR";
        final String timezone = "America/New_York";
        final LocalDateTime createdAt = LocalDateTime.now();
        final LocalDateTime updatedAt = LocalDateTime.now();
        final LocalDateTime lastLoginAt = LocalDateTime.now();
        final LocalDateTime passwordChangedAt = LocalDateTime.now();

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
    void testUserRolesCanBeAddedAndRetrieved() {
        // Given
        final User user = new User();

        // When
        user.getRoles().add(User.Role.USER);
        user.getRoles().add(User.Role.ADMIN);

        // Then
        assertTrue(user.getRoles().contains(User.Role.USER));
        assertTrue(user.getRoles().contains(User.Role.ADMIN));
        assertEquals(2, user.getRoles().size());
    }

    @Test
    void testAccountDefaultConstructorCreatesEmptyAccount() {
        // When
        final Account account = new Account();

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
    void testAccountConstructorWithUserAndNameSetsFields() {
        // Given
        final User user = new User("test@example.com", PASSWORD);
        final String accountName = "Checking Account";
        final Account.AccountType accountType = Account.AccountType.CHECKING;

        // When
        final Account account = new Account(user, accountName, accountType);

        // Then
        assertEquals(user, account.getUser());
        assertEquals(accountName, account.getAccountName());
        assertEquals(accountType, account.getAccountType());
        assertNull(account.getLastSyncedAt());
    }

    @Test
    void testAccountGettersAndSettersWorkCorrectly() {
        // Given
        final Account account = new Account();
        final Long id = 1L;
        final User user = new User("test@example.com", PASSWORD);
        final String accountName = "Savings Account";
        final String institutionName = "Chase Bank";
        final Account.AccountType accountType = Account.AccountType.SAVINGS;
        final String accountSubtype = "savings";
        final BigDecimal balance = new BigDecimal("1000.50");
        final String currencyCode = "EUR";
        final String plaidAccountId = "acc_123";
        final String plaidItemId = "item_456";
        final Boolean active = false;
        final LocalDateTime lastSyncedAt = LocalDateTime.now();
        final LocalDateTime createdAt = LocalDateTime.now();
        final LocalDateTime updatedAt = LocalDateTime.now();

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
    void testTransactionDefaultConstructorCreatesEmptyTransaction() {
        // When
        final Transaction transaction = new Transaction();

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
    void testTransactionConstructorWithRequiredFieldsSetsFields() {
        // Given
        final User user = new User("test@example.com", PASSWORD);
        final Account account = new Account(user, "Checking", Account.AccountType.CHECKING);
        final BigDecimal amount = new BigDecimal("-50.00");
        final LocalDate transactionDate = LocalDate.now();

        // When
        final Transaction transaction = new Transaction(user, account, amount, transactionDate);

        // Then
        assertEquals(user, transaction.getUser());
        assertEquals(account, transaction.getAccount());
        assertEquals(amount, transaction.getAmount());
        assertEquals(transactionDate, transaction.getTransactionDate());
    }

    @Test
    void testTransactionGettersAndSettersWorkCorrectly() {
        // Given
        final Transaction transaction = new Transaction();
        final Long id = 1L;
        final User user = new User("test@example.com", PASSWORD);
        final Account account = new Account(user, "Checking", Account.AccountType.CHECKING);
        final BigDecimal amount = new BigDecimal("-50.00");
        final String description = "Grocery Store";
        final String merchantName = "Whole Foods";
        final Transaction.TransactionCategory category =
                Transaction.TransactionCategory.FOOD_DINING;
        final LocalDate transactionDate = LocalDate.now();
        final String currencyCode = "EUR";
        final String plaidTransactionId = "txn_123";
        final Boolean pending = true;
        final String location = "{\"lat\": 40.7128, \"lon\": -74.0060}";
        final String paymentChannel = "in_store";
        final String authorizedDate = "2024-01-15";
        final LocalDateTime createdAt = LocalDateTime.now();
        final LocalDateTime updatedAt = LocalDateTime.now();

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
    void testBudgetDefaultConstructorCreatesEmptyBudget() {
        // When
        final Budget budget = new Budget();

        // Then
        assertNotNull(budget);
        assertNull(budget.getId());
        assertNull(budget.getUser());
        assertEquals(BigDecimal.ZERO, budget.getCurrentSpent());
        assertEquals("USD", budget.getCurrencyCode());
    }

    @Test
    void testBudgetConstructorWithRequiredFieldsSetsFields() {
        // Given
        final User user = new User("test@example.com", PASSWORD);
        final Transaction.TransactionCategory category =
                Transaction.TransactionCategory.FOOD_DINING;
        final BigDecimal monthlyLimit = new BigDecimal("500.00");

        // When
        final Budget budget = new Budget(user, category, monthlyLimit);

        // Then
        assertEquals(user, budget.getUser());
        assertEquals(category, budget.getCategory());
        assertEquals(monthlyLimit, budget.getMonthlyLimit());
    }

    @Test
    void testBudgetGettersAndSettersWorkCorrectly() {
        // Given
        final Budget budget = new Budget();
        final Long id = 1L;
        final User user = new User("test@example.com", PASSWORD);
        final Transaction.TransactionCategory category =
                Transaction.TransactionCategory.FOOD_DINING;
        final BigDecimal monthlyLimit = new BigDecimal("500.00");
        final BigDecimal currentSpent = new BigDecimal("250.00");
        final String currencyCode = "EUR";
        final LocalDateTime createdAt = LocalDateTime.now();
        final LocalDateTime updatedAt = LocalDateTime.now();

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
    void testGoalDefaultConstructorCreatesEmptyGoal() {
        // When
        final Goal goal = new Goal();

        // Then
        assertNotNull(goal);
        assertNull(goal.getId());
        assertNull(goal.getUser());
        assertEquals(BigDecimal.ZERO, goal.getCurrentAmount());
        assertEquals("USD", goal.getCurrencyCode());
        assertTrue(goal.getActive());
    }

    @Test
    void testGoalConstructorWithRequiredFieldsSetsFields() {
        // Given
        final User user = new User("test@example.com", PASSWORD);
        final String name = "Vacation Fund";
        final BigDecimal targetAmount = new BigDecimal("5000.00");
        final LocalDate targetDate = LocalDate.now().plusMonths(6);

        // When
        final Goal goal = new Goal(user, name, targetAmount, targetDate);

        // Then
        assertEquals(user, goal.getUser());
        assertEquals(name, goal.getName());
        assertEquals(targetAmount, goal.getTargetAmount());
        assertEquals(targetDate, goal.getTargetDate());
    }

    @Test
    void testGoalGettersAndSettersWorkCorrectly() {
        // Given
        final Goal goal = new Goal();
        final Long id = 1L;
        final User user = new User("test@example.com", PASSWORD);
        final String name = "Emergency Fund";
        final String description = "Save for emergencies";
        final BigDecimal targetAmount = new BigDecimal("10000.00");
        final BigDecimal currentAmount = new BigDecimal("2500.00");
        final LocalDate targetDate = LocalDate.now().plusMonths(12);
        final BigDecimal monthlyContribution = new BigDecimal("500.00");
        final Goal.GoalType goalType = Goal.GoalType.EMERGENCY_FUND;
        final String currencyCode = "EUR";
        final Boolean active = false;
        final LocalDateTime createdAt = LocalDateTime.now();
        final LocalDateTime updatedAt = LocalDateTime.now();

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
