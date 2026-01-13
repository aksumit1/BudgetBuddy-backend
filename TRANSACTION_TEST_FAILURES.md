# Transaction Type Test Failures Analysis

**Date:** 2026-01-11  
**Test Execution:** TransactionTypeCategoryServiceTest, TransactionTypeFromAccountTypeTest, TransactionTypeDeterminerTest  
**Total Tests:** 69  
**Failures:** 4  
**Errors:** 0  

---

## Summary

After running the transaction type tests, we have **4 test failures** across 2 test classes. All failures are related to the recent changes in transaction type determination logic. Let's analyze each failure in detail.

---

## Failure 1: TransactionTypeCategoryServiceTest.testDetermineTransactionType_WithDebitIndicator

**File:** `TransactionTypeCategoryServiceTest.java` (line 154-173)  
**Expected:** `EXPENSE`  
**Actual:** `INCOME`  
**Status:** ❌ **FAILING**

### Test Details
```java
@Test
void testDetermineTransactionType_WithDebitIndicator() {
    // Given: Debit indicator
    when(transactionTypeDeterminer.determineTransactionType(
        eq(checkingAccount), anyString(), anyString(), any(BigDecimal.class)))
        .thenReturn(TransactionType.EXPENSE);

    // When: Determine type with DEBIT indicator
    TransactionTypeCategoryService.TypeResult result = service.determineTransactionType(
        checkingAccount,
        "other",
        "other",
        BigDecimal.valueOf(-100),  // Negative amount
        "DEBIT",                    // Debit indicator
        "Purchase",
        null
    );

    // Then: Should be EXPENSE
    assertNotNull(result);
    assertEquals(TransactionType.EXPENSE, result.getTransactionType());  // ❌ FAILS: Got INCOME
}
```

### Analysis
- **Input:** Checking account, negative amount (-100), `transactionTypeIndicator = "DEBIT"`
- **Expected:** EXPENSE (because DEBIT indicator suggests expense)
- **Actual:** INCOME
- **Root Cause:** The new logic's Priority 0 (account type-based inference) for checking accounts with positive amounts returns INCOME, but this test uses a negative amount. However, the account type logic for checking accounts might be incorrectly handling negative amounts, or the transaction type indicator ("DEBIT") is not being processed correctly in Priority 1.

### Impact
- **Severity:** Medium
- **Behavior Change:** The test expects that `transactionTypeIndicator = "DEBIT"` should result in EXPENSE, but the new logic appears to be prioritizing account type logic over the indicator.

---

## Failure 2: TransactionTypeCategoryServiceTest.testDetermineTransactionType_WithCreditIndicator

**File:** `TransactionTypeCategoryServiceTest.java` (line 180-201)  
**Expected:** `INCOME`  
**Actual:** `EXPENSE`  
**Status:** ❌ **FAILING**

### Test Details
```java
@Test
void testDetermineTransactionType_WithCreditIndicator() {
    // Given: Credit indicator (income)
    when(transactionTypeDeterminer.determineTransactionType(
        eq(checkingAccount), anyString(), anyString(), any(BigDecimal.class)))
        .thenReturn(TransactionType.EXPENSE);

    // When: Determine type with CREDIT indicator
    TransactionTypeCategoryService.TypeResult result = service.determineTransactionType(
        checkingAccount,
        "other",
        "other",
        BigDecimal.valueOf(100),   // Positive amount
        "CREDIT",                   // Credit indicator
        "Salary deposit",
        null
    );

    // Then: Should be INCOME
    assertNotNull(result);
    assertEquals(TransactionType.INCOME, result.getTransactionType());  // ❌ FAILS: Got EXPENSE
}
```

### Analysis
- **Input:** Checking account, positive amount (+100), `transactionTypeIndicator = "CREDIT"`
- **Expected:** INCOME (because CREDIT indicator suggests income)
- **Actual:** EXPENSE
- **Root Cause:** Similar to Failure 1, the transaction type indicator ("CREDIT") is not being processed correctly. The test expects Priority 1 (transaction type indicator) to take precedence, but it seems the logic is not handling the indicator properly, or Priority 0 (account type) is overriding it incorrectly.

### Impact
- **Severity:** Medium
- **Behavior Change:** The test expects that `transactionTypeIndicator = "CREDIT"` should result in INCOME, but the new logic appears to be ignoring the indicator or processing it incorrectly.

---

## Failure 3: TransactionTypeCategoryServiceTest.testDetermineTransactionType_CreditCardPayment

**File:** `TransactionTypeCategoryServiceTest.java` (line 425-447)  
**Expected:** `true` (PAYMENT or EXPENSE)  
**Actual:** `false` (neither PAYMENT nor EXPENSE)  
**Status:** ❌ **FAILING**

### Test Details
```java
@Test
void testDetermineTransactionType_CreditCardPayment() {
    // Given: Credit card payment description
    when(transactionTypeDeterminer.determineTransactionType(
        eq(checkingAccount), anyString(), anyString(), any(BigDecimal.class)))
        .thenReturn(TransactionType.EXPENSE);

    // When: Determine type for credit card payment
    TransactionTypeCategoryService.TypeResult result = service.determineTransactionType(
        checkingAccount,           // ⚠️ Note: This is a CHECKING account, not credit card
        "payment",
        "payment",
        BigDecimal.valueOf(-500),  // Negative amount
        "DEBIT",
        "CITI AUTOPAY PAYMENT",    // Payment description
        null
    );

    // Then: Should be PAYMENT (credit card payment)
    assertNotNull(result);
    // Note: Credit card payment detection is in the service, should be PAYMENT
    assertTrue(result.getTransactionType() == TransactionType.PAYMENT || 
               result.getTransactionType() == TransactionType.EXPENSE);  // ❌ FAILS: Got neither
}
```

### Analysis
- **Input:** Checking account, negative amount (-500), category="payment", description="CITI AUTOPAY PAYMENT"
- **Expected:** PAYMENT or EXPENSE (test accepts either)
- **Actual:** Neither PAYMENT nor EXPENSE (likely INCOME based on pattern from other failures)
- **Root Cause:** The test uses a **checking account** (not a credit card account), but expects credit card payment detection logic to work. The new logic for checking accounts might be incorrectly classifying this as INCOME due to the positive amount logic, or the credit card payment detection (`isCreditCardPayment`) is not working correctly for checking accounts.

### Impact
- **Severity:** Medium-High
- **Behavior Change:** The test expects credit card payment detection to work on checking accounts (which makes sense - when you pay a credit card from your checking account, it should be PAYMENT). The new logic might be incorrectly handling this scenario.

---

## Failure 4: TransactionTypeFromAccountTypeTest.testCreditCard_NegativeAmount_ReturnsPayment

**File:** `TransactionTypeFromAccountTypeTest.java` (line ~175-186)  
**Expected:** `PAYMENT`  
**Actual:** `EXPENSE`  
**Status:** ❌ **FAILING**

### Test Details
```java
@Test
void testCreditCard_NegativeAmount_ReturnsPayment() {
    // When: Credit card account, negative amount
    TransactionTypeCategoryService.TypeResult result = 
        service.determineTransactionTypeFromAccountType(
            "credit",              // Account type
            "credit_card",         // Account subtype
            BigDecimal.valueOf(-100.00),  // Negative amount
            "Payment",             // Description
            null                   // Payment channel
        );

    // Then: Should be PAYMENT
    assertEquals(TransactionType.PAYMENT, result.getTransactionType());  // ❌ FAILS: Got EXPENSE
}
```

### Analysis
- **Input:** Credit card account type, negative amount (-100), description="Payment"
- **Expected:** PAYMENT (because negative amounts on credit cards typically represent payments)
- **Actual:** EXPENSE
- **Root Cause:** This is **directly related to your changes**. In `determineTransactionTypeFromAccountType()`, you changed the logic for credit card negative amounts from PAYMENT to EXPENSE (line 159-161 in TransactionTypeCategoryService.java). The test expects the old behavior (negative = PAYMENT), but the new logic returns EXPENSE.

### Impact
- **Severity:** High
- **Behavior Change:** This is an **expected failure** due to your changes. The new logic intentionally changes negative amounts on credit cards to EXPENSE (unless category/keywords indicate payment). However, this test case expects PAYMENT. This test might need to be updated to reflect the new behavior, OR the new logic might need adjustment if this behavior is incorrect.

---

## Summary of Root Causes

### 1. Transaction Type Indicator Handling (Failures 1 & 2)
- **Issue:** Transaction type indicators ("DEBIT", "CREDIT") are not being processed correctly
- **Expected:** Priority 1 should handle transaction type indicators
- **Actual:** Indicators appear to be ignored or overridden by Priority 0 logic

### 2. Credit Card Payment Detection (Failure 3)
- **Issue:** Credit card payment detection on checking accounts is not working
- **Expected:** Checking account transactions with credit card payment descriptions should be PAYMENT
- **Actual:** Result is neither PAYMENT nor EXPENSE (likely INCOME)

### 3. Credit Card Negative Amount Logic (Failure 4)
- **Issue:** Intentional behavior change - negative amounts on credit cards now return EXPENSE instead of PAYMENT
- **Expected (by test):** PAYMENT for negative amounts
- **Actual (new logic):** EXPENSE (unless category/keywords indicate payment)
- **Note:** This is an **expected failure** due to your changes. The test needs updating, OR the logic needs review if this behavior is incorrect.

---

## Recommendations

### 1. Review Transaction Type Indicator Logic
- Check if Priority 1 (transaction type indicator) is being executed correctly
- Verify that indicators ("DEBIT", "CREDIT") are being parsed and applied
- Ensure Priority 0 doesn't incorrectly override Priority 1

### 2. Review Credit Card Payment Detection
- Verify `isCreditCardPayment()` is working correctly for checking accounts
- Check if the logic in `determineTransactionType()` for checking accounts correctly handles credit card payments
- The test uses a checking account but expects credit card payment detection - verify this is the intended behavior

### 3. Review Credit Card Negative Amount Logic
- **Decision Point:** Is the new behavior (negative = EXPENSE by default) correct?
- If YES: Update the test to expect EXPENSE (or PAYMENT only when payment keywords are present)
- If NO: Review the logic in `determineTransactionTypeFromAccountType()` line 159-161

### 4. Test Updates Needed
- `testCreditCard_NegativeAmount_ReturnsPayment`: Update expectation OR adjust logic
- `testDetermineTransactionType_WithDebitIndicator`: Fix indicator handling
- `testDetermineTransactionType_WithCreditIndicator`: Fix indicator handling
- `testDetermineTransactionType_CreditCardPayment`: Fix credit card payment detection on checking accounts

---

## Next Steps

1. **Review the new logic** in `TransactionTypeCategoryService.determineTransactionType()` to understand why indicators are not being processed
2. **Review `determineTransactionTypeFromAccountType()`** to confirm if the credit card negative amount change is intentional
3. **Review credit card payment detection** logic for checking accounts
4. **Update tests** to match new expected behavior OR **adjust logic** if behavior is incorrect

---

## Test Results Summary

| Test Class | Tests Run | Failures | Errors |
|------------|-----------|----------|--------|
| TransactionTypeCategoryServiceTest | 17 | 3 | 0 |
| TransactionTypeFromAccountTypeTest | 33 | 1 | 0 |
| TransactionTypeDeterminerTest | 19 | 0 | 0 |
| **Total** | **69** | **4** | **0** |
