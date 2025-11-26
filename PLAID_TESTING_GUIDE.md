# Plaid Integration Testing Guide

## Overview

This guide explains how to test Plaid integration in the BudgetBuddy backend and what tests are available.

## Quick Start

### Run All Plaid Tests
```bash
cd BudgetBuddy-Backend
./run-plaid-tests.sh
```

### Run Specific Test
```bash
# Integration test
mvn test -Dtest=PlaidControllerIntegrationTest

# Service test
mvn test -Dtest=PlaidServiceTest

# Controller test
mvn test -Dtest=PlaidControllerTest
```

## Test Status

### Currently Disabled Tests

**Reason**: Java 25 compatibility issues with Spring Boot 3.4.1 and Mockito/ByteBuddy

1. **PlaidControllerIntegrationTest** - `@Disabled` due to Spring Boot context loading failures
2. **PlaidServiceTest** - `@Disabled` due to Mockito/ByteBuddy mocking issues
3. **PlaidControllerTest** - `@Disabled` due to Mockito mocking issues

### Test Coverage (When Enabled)

#### PlaidControllerIntegrationTest
- ✅ `testGetAccounts_WithoutAccessToken_ReturnsAccountsFromDatabase()` - Tests database fallback
- ✅ `testGetAccounts_WithAccessToken_ReturnsAccountsFromPlaid()` - Tests Plaid API integration
- ✅ `testGetAccounts_WithEmptyAccessToken_ReturnsAccountsFromDatabase()` - Tests empty token handling
- ✅ `testGetTransactions_WithoutDates_ReturnsTransactionsFromDatabase()` - Tests transactions endpoint (new)
- ✅ `testGetTransactions_WithDateRange_ReturnsTransactionsFromDatabase()` - Tests date range filtering (new)
- ✅ `testGetTransactions_WithInvalidDateFormat_ReturnsBadRequest()` - Tests error handling (new)

#### PlaidServiceTest
- ✅ Constructor validation tests
- ✅ Input validation tests
- ✅ Error handling tests

#### PlaidControllerTest
- ✅ Link token creation tests
- ✅ Public token exchange tests
- ✅ Error handling tests

## Manual Testing

Since automated tests are currently disabled, use manual testing:

### 1. Test Link Token Creation
```bash
curl -X POST http://localhost:8080/api/plaid/link-token \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json"
```

**Expected**: Returns `{"link_token": "..."}`

### 2. Test Token Exchange
```bash
curl -X POST http://localhost:8080/api/plaid/exchange-token \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"public_token": "public-sandbox-..."}'
```

**Expected**: Returns `{"access_token": "...", "plaid_item_id": "..."}`

### 3. Test Get Accounts (Without Access Token)
```bash
curl -X GET http://localhost:8080/api/plaid/accounts \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json"
```

**Expected**: Returns `{"accounts": [...], "item": null}` from database

### 4. Test Get Accounts (With Access Token)
```bash
curl -X GET "http://localhost:8080/api/plaid/accounts?accessToken=access-sandbox-..." \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json"
```

**Expected**: Returns accounts from Plaid API or 5xx if not configured

### 5. Test Get Transactions (New Endpoint)
```bash
# Without dates (defaults to last 30 days)
curl -X GET http://localhost:8080/api/plaid/transactions \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json"

# With date range
curl -X GET "http://localhost:8080/api/plaid/transactions?start=2025-01-01&end=2025-12-31" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json"
```

**Expected**: Returns array of transactions from database

### 6. Test Invalid Date Format
```bash
curl -X GET "http://localhost:8080/api/plaid/transactions?start=invalid-date&end=2025-12-31" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json"
```

**Expected**: Returns 400 Bad Request with error message

## Common Issues and Fixes

### Issue: Tests Skipped
**Solution**: Tests are disabled due to Java 25 compatibility. Use manual testing or wait for Spring Boot/Mockito updates.

### Issue: MissingServletRequestParameterException
**Fix Applied**: Made `accessToken` optional in `/api/plaid/accounts` endpoint

### Issue: NoResourceFoundException for /api/plaid/transactions
**Fix Applied**: Added `/api/plaid/transactions` endpoint to `PlaidController`

### Issue: NullPointerException in Exchange Token
**Fix Applied**: Added null checks for `accessToken` and `itemId` in `exchangePublicToken` method

### Issue: Sync Failures Breaking Token Exchange
**Fix Applied**: Wrapped sync operations in try-catch so failures don't prevent token exchange

## Testing Checklist

- [ ] Link token creation works
- [ ] Public token exchange works
- [ ] Accounts endpoint works without access token (database fallback)
- [ ] Accounts endpoint works with access token (Plaid API)
- [ ] Transactions endpoint works without dates (default range)
- [ ] Transactions endpoint works with date range
- [ ] Transactions endpoint handles invalid dates
- [ ] Error responses are properly formatted
- [ ] Authentication is required for all endpoints
- [ ] Invalid tokens return appropriate errors

## Next Steps

1. **Enable Tests**: When Spring Boot 3.5+ or Java 25 support is available, remove `@Disabled` annotations
2. **Add More Tests**: Add tests for error scenarios, edge cases, and integration flows
3. **Mock Plaid API**: Consider using Plaid's sandbox for more realistic testing
4. **Integration Tests**: Add end-to-end tests that test the full Plaid flow

## Related Files

- `src/main/java/com/budgetbuddy/api/PlaidController.java` - Main controller
- `src/main/java/com/budgetbuddy/plaid/PlaidService.java` - Plaid service
- `src/test/java/com/budgetbuddy/api/PlaidControllerIntegrationTest.java` - Integration tests
- `src/test/java/com/budgetbuddy/plaid/PlaidServiceTest.java` - Service tests
- `src/test/java/com/budgetbuddy/api/PlaidControllerTest.java` - Controller tests

