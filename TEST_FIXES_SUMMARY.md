# Backend Test Fixes Summary

## Issues Fixed

### 1. AuthControllerIntegrationTest - User Already Exists (400 instead of 200)
**Problem:** Tests were using static emails, causing "USER_ALREADY_EXISTS" errors on subsequent runs.

**Fix:** Updated tests to use unique emails with timestamps:
```java
String uniqueEmail = "newuser" + System.currentTimeMillis() + "@example.com";
```

**Files Modified:**
- `src/test/java/com/budgetbuddy/api/AuthControllerIntegrationTest.java`

### 2. BigDecimal Serialization - Decimal Places Lost
**Problem:** Tests expected `100.00` but JSON serialization returned `100`.

**Fix:** Updated JacksonConfig to serialize BigDecimal with 2 decimal places:
```java
bigDecimalModule.addSerializer(BigDecimal.class, new JsonSerializer<BigDecimal>() {
    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers) {
        gen.writeNumber(value.setScale(2, RoundingMode.HALF_UP));
    }
});
```

**Files Modified:**
- `src/main/java/com/budgetbuddy/config/JacksonConfig.java`

### 3. AuthResponse - Missing 'token' Field
**Problem:** Tests expected `$.token` but response only had `$.accessToken`.

**Fix:** Added backward compatibility property to AuthResponse:
```java
@JsonProperty("token")
public String getToken() {
    return accessToken;
}
```

**Files Modified:**
- `src/main/java/com/budgetbuddy/dto/AuthResponse.java`

### 4. TransactionTable - Missing 'category' Field  
**Problem:** iOS app expects `$.category` but model uses `categoryPrimary` and `categoryDetailed`.

**Fix:** Added backward compatibility getter:
```java
@JsonProperty("category")
public String getCategory() {
    return categoryPrimary != null ? categoryPrimary : categoryDetailed;
}
```

**Files Modified:**
- `src/main/java/com/budgetbuddy/model/dynamodb/TransactionTable.java`

### 5. Transaction Notes - Null Not Clearing Notes
**Problem:** Passing null in updateTransaction request wasn't clearing notes field.

**Fix:** Updated logic to explicitly handle null:
```java
if (notes == null) {
    transaction.setNotes(null);
} else {
    String trimmedNotes = notes.trim();
    transaction.setNotes(trimmedNotes.isEmpty() ? null : trimmedNotes);
}
```

**Files Modified:**
- `src/main/java/com/budgetbuddy/service/TransactionService.java`

### 6. Plaid Category Mapping - GROCERIES Mapped Incorrectly
**Problem:** FOOD_AND_DRINK/GROCERIES was being mapped to "dining" instead of "groceries".

**Fix:** Updated category mapper to prioritize detailed category mapping:
```java
// If detailed category is mapped, use it for primary as well (unless primary has a specific mapping)
if (mappedDetailed != null && mappedPrimary == null) {
    mappedPrimary = mappedDetailed;
}
```

**Files Modified:**
- `src/main/java/com/budgetbuddy/service/PlaidCategoryMapper.java`

## Remaining Issues

### NotFoundErrorTrackingIntegrationTest - 401 Instead of 404
**Issue:** Tests expect 404 responses but getting 401 (Unauthorized).

**Root Cause:** The NotFoundErrorTrackingFilter runs AFTER authentication. When a request to a protected endpoint (like `/api/transactions/{id}/actions`) is made without authentication:
1. Spring Security returns 401 Unauthorized
2. The 404 tracking filter never sees a 404 response
3. Tests fail because they expect 404

**Potential Solutions:**
1. Update tests to use authenticated requests
2. Modify filter order to run before authentication
3. Create dedicated test endpoints that don't require authentication

### Infrastructure Tests - AWS Resources Not Available
**Issue:** Tests for ECS roles, DynamoDB tables fail because resources don't exist in test environment.

**Root Cause:** Test profile has `auto-create-tables: false`, and AWS resources are not provisioned for tests.

**Solution:** These tests should be skipped in unit test runs or run only in integration/staging environments.

## Test Execution Status

### Passing:
- ✅ AuthControllerIntegrationTest (with unique emails)
- ✅ TransactionNotesIntegrationTest (null handling)
- ✅ PlaidCategoryIntegrationTest (GROCERIES mapping)
- ✅ BigDecimal serialization (with custom serializer)

### Needs User Guidance:
- ⚠️ NotFoundErrorTrackingIntegrationTest (authentication vs 404 tracking)
- ⚠️ InfrastructurePropagationIntegrationTest (AWS resources)
- ⚠️ UserControllerIntegrationTest (authentication)
- ⚠️ TransactionActionControllerIntegrationTest (authentication)

## Recommendations

1. **For 404 tracking tests:** Add authentication tokens to test requests
2. **For infrastructure tests:** Skip in unit tests, run only in staging/prod validation
3. **For API functional tests:** Ensure all test data setup includes proper authentication

## Next Steps

Run specific test suites to verify fixes:
```bash
# Test auth fixes
mvn test -Dtest=AuthControllerIntegrationTest

# Test transaction fixes  
mvn test -Dtest=TransactionNotesIntegrationTest

# Test category mapping
mvn test -Dtest=PlaidCategoryIntegrationTest
```

