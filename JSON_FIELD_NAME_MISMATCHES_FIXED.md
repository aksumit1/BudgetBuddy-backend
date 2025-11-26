# JSON Field Name Mismatches - Fixed

## Summary
This document lists all JSON field name mismatches between backend and iOS app that have been identified and fixed.

## Jackson Configuration
The backend uses default Jackson naming strategy (camelCase). No global PropertyNamingStrategy is configured, so field names are serialized as-is unless explicitly overridden with `@JsonProperty`.

## Fixed Mismatches

### 1. PlaidController - LinkTokenResponse ✅
**Issue**: Backend returned `linkToken` (camelCase), iOS expected `link_token` (snake_case)

**Backend (Before)**:
```java
public static class LinkTokenResponse {
    private String linkToken;  // ❌ Serialized as "linkToken"
    private String expiration;
}
```

**Backend (After)**:
```java
public static class LinkTokenResponse {
    @JsonProperty("link_token")
    private String linkToken;  // ✅ Serialized as "link_token"
    private String expiration;
}
```

**iOS Expected**:
```swift
private enum CodingKeys: String, CodingKey {
    case linkToken = "link_token"  // ✅ Matches backend
}
```

---

### 2. PlaidController - ExchangeTokenResponse ✅
**Issue**: Backend returned `accessToken` and `itemId` (camelCase), iOS expected `access_token` and `plaid_item_id` (snake_case)

**Backend (Before)**:
```java
public static class ExchangeTokenResponse {
    private String accessToken;  // ❌ Serialized as "accessToken"
    private String itemId;       // ❌ Serialized as "itemId"
}
```

**Backend (After)**:
```java
public static class ExchangeTokenResponse {
    @JsonProperty("access_token")
    private String accessToken;  // ✅ Serialized as "access_token"
    @JsonProperty("plaid_item_id")
    private String itemId;       // ✅ Serialized as "plaid_item_id"
}
```

**iOS Expected**:
```swift
enum CodingKeys: String, CodingKey {
    case plaidItemId = "plaid_item_id"  // ✅ Matches backend
    case accessToken = "access_token"   // ✅ Matches backend
}
```

---

### 3. PlaidController - ExchangeTokenRequest ✅
**Issue**: iOS sends `public_token` (snake_case), backend expected `publicToken` (camelCase)

**Backend (Before)**:
```java
public static class ExchangeTokenRequest {
    private String publicToken;  // ❌ Expected "publicToken" in JSON
}
```

**Backend (After)**:
```java
public static class ExchangeTokenRequest {
    @JsonProperty("public_token")
    private String publicToken;  // ✅ Accepts "public_token" in JSON
}
```

**iOS Sends**:
```swift
let body = ["public_token": publicToken]  // ✅ Matches backend
```

---

### 4. PlaidController - SyncRequest ✅
**Issue**: iOS may send `access_token` (snake_case), backend expected `accessToken` (camelCase)

**Backend (Before)**:
```java
public static class SyncRequest {
    private String accessToken;  // ❌ Expected "accessToken" in JSON
}
```

**Backend (After)**:
```java
public static class SyncRequest {
    @JsonProperty("access_token")
    private String accessToken;  // ✅ Accepts "access_token" in JSON
}
```

---

## Verified Compatible (No Changes Needed)

### 1. AuthResponse ✅
**Status**: Already compatible via iOS `CodingKeys` mapping

**Backend**:
```java
public class AuthResponse {
    private String accessToken;  // camelCase
    private String refreshToken;
    private String tokenType;
    private LocalDateTime expiresAt;
    private UserInfo user;
}
```

**iOS**:
```swift
enum CodingKeys: String, CodingKey {
    case token = "accessToken"  // ✅ Maps accessToken → token
    case refreshToken
    case tokenType
    case expiresAt
    case user
}
```

---

### 2. TransactionTable / BackendTransaction ✅
**Status**: Compatible - Both use camelCase

**Backend**:
```java
public class TransactionTable {
    private String transactionId;  // camelCase
    private String accountId;
    private BigDecimal amount;
    private String transactionDate;
    // ...
}
```

**iOS**:
```swift
struct BackendTransaction: Decodable {
    let transactionId: String  // camelCase ✅
    let accountId: String
    let amount: Decimal
    let transactionDate: String
    // ...
}
```

---

### 3. AccountTable / BackendAccount ✅
**Status**: Compatible - Both use camelCase

**Backend**:
```java
public class AccountTable {
    private String accountId;  // camelCase
    private String accountName;
    private String institutionName;
    // ...
}
```

**iOS**:
```swift
struct BackendAccount: Decodable {
    let accountId: String  // camelCase ✅
    let accountName: String
    let institutionName: String
    // ...
}
```

---

### 4. BudgetTable / BackendBudget ✅
**Status**: Compatible - Both use camelCase

**Backend**:
```java
public class BudgetTable {
    private String budgetId;  // camelCase
    private String category;
    private BigDecimal monthlyLimit;
    // ...
}
```

**iOS**:
```swift
struct BackendBudget: Decodable {
    let budgetId: String  // camelCase ✅
    let category: String
    let monthlyLimit: Decimal
    // ...
}
```

---

### 5. GoalTable / BackendGoal ✅
**Status**: Compatible - Both use camelCase

**Backend**:
```java
public class GoalTable {
    private String goalId;  // camelCase
    private String name;
    private BigDecimal targetAmount;
    // ...
}
```

**iOS**:
```swift
struct BackendGoal: Decodable {
    let goalId: String  // camelCase ✅
    let name: String
    let targetAmount: Decimal
    // ...
}
```

---

### 6. AuthRequest (Login/Register) ✅
**Status**: Compatible - iOS sends snake_case, backend accepts via `@JsonAlias`

**Backend**:
```java
public class AuthRequest {
    private String email;
    @JsonAlias({"password_hash", "passwordHash"})
    private String passwordHash;  // ✅ Accepts both formats
    private String salt;
}
```

**iOS Sends**:
```swift
struct LoginBody: Codable {
    enum CodingKeys: String, CodingKey {
        case email
        case passwordHash = "password_hash"  // ✅ Matches backend
        case salt
    }
}
```

---

## Testing

All fixes have been verified:
1. ✅ Backend compiles successfully
2. ✅ Docker container rebuilds successfully
3. ✅ JSON field names match iOS expectations

## Notes

- The backend uses default Jackson naming (camelCase) for most DTOs
- Plaid-related endpoints use snake_case to match Plaid API conventions
- iOS `BackendModels.swift` uses camelCase to match backend Table classes
- iOS uses `CodingKeys` to map between different naming conventions where needed

## Future Considerations

If adding new endpoints:
1. **Plaid-related endpoints**: Use snake_case (`link_token`, `access_token`, etc.)
2. **Standard endpoints**: Use camelCase (`transactionId`, `accountId`, etc.)
3. **Always verify** with iOS app's expected field names before deployment

