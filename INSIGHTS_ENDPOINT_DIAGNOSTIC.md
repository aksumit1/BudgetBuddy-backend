# Insights Endpoint Diagnostic Guide

## Issue
The prediction endpoints (`/api/insights/predictions/*`) are returning "No static resource" errors, indicating Spring is treating them as static resources instead of routing to the controller.

## Root Cause Analysis

### 1. Controller Registration
The `FinancialInsightsController` is properly annotated:
- ✅ `@RestController` - Marks it as a REST controller
- ✅ `@RequestMapping("/api/insights")` - Base path is correct
- ✅ `@GetMapping("/predictions/anomalies")` - Endpoint mapping is correct

### 2. Path Structure
- **Controller base**: `/api/insights`
- **Endpoint**: `/predictions/anomalies`
- **Full path**: `/api/insights/predictions/anomalies`
- **iOS request**: `/api/insights/predictions/anomalies?daysAhead=30` ✅ Matches

### 3. Security Configuration
- ✅ Security config requires authentication for `/api/**` (line 120 in SecurityConfig.java)
- ✅ No explicit blocking of `/api/insights/**` paths
- ✅ Should route through JWT filter first, then to controller

### 4. Static Resource Handler
The error "No static resource" suggests Spring's `ResourceHttpRequestHandler` is intercepting the request before it reaches the controller. This happens when:
- Controller isn't registered (needs restart)
- Path doesn't match controller mapping
- Static resource handler has higher priority

## Solution Steps

### Step 1: Verify Backend Restart
**CRITICAL**: The backend MUST be restarted after adding new controller endpoints.

```bash
# Check if backend is running
docker ps | grep budgetbuddy-backend

# Restart if using Docker Compose
docker-compose restart budgetbuddy-backend

# Or rebuild and restart
docker-compose down
docker-compose up -d --build
```

### Step 2: Verify Controller Registration
After restart, check the startup logs for:
```
Mapped "{[/api/insights/predictions/anomalies],methods=[GET]}"
Mapped "{[/api/insights/predictions/expense-reductions],methods=[GET]}"
Mapped "{[/api/insights/predictions/goal-achievements],methods=[GET]}"
Mapped "{[/api/insights/predictions/missed-payments],methods=[GET]}"
Mapped "{[/api/insights/predictions/interest-costs],methods=[GET]}"
Mapped "{[/api/insights/predictions/summary],methods=[GET]}"
```

If these lines are **NOT** in the logs, the controller isn't being registered.

### Step 3: Test Endpoint Directly
```bash
# Get auth token first (from login)
TOKEN="your-jwt-token"

# Test prediction endpoint
curl -v -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/insights/predictions/anomalies?daysAhead=30"

# Should return JSON, not 404
```

### Step 4: Check for Path Conflicts
Verify no other controller or configuration is intercepting `/api/insights/predictions/*`:

```bash
# Search for any other mappings
grep -r "predictions" src/main/java/com/budgetbuddy/api/
grep -r "/api/insights" src/main/java/com/budgetbuddy/config/
```

### Step 5: Verify Package Scanning
Ensure the controller is in a scanned package:
- ✅ Controller is in `com.budgetbuddy.api` package
- ✅ Main app is in `com.budgetbuddy` package
- ✅ `@SpringBootApplication` scans sub-packages by default

## Expected Behavior After Fix

### Working Endpoints
All these should return JSON (not 404):
1. ✅ `GET /api/insights/anomalies` (non-prediction - should work)
2. ✅ `GET /api/insights/expense-reductions` (non-prediction - should work)
3. ✅ `GET /api/insights/goal-recommendations` (non-prediction - should work)
4. ❌ `GET /api/insights/predictions/anomalies?daysAhead=30` (prediction - currently failing)
5. ❌ `GET /api/insights/predictions/expense-reductions` (prediction - currently failing)
6. ❌ `GET /api/insights/predictions/goal-achievements` (prediction - currently failing)
7. ❌ `GET /api/insights/predictions/missed-payments` (prediction - currently failing)
8. ❌ `GET /api/insights/predictions/interest-costs` (prediction - currently failing)
9. ❌ `GET /api/insights/predictions/summary` (prediction - currently failing)

### If Non-Prediction Endpoints Work But Predictions Don't
This suggests:
- Controller IS registered (non-prediction endpoints work)
- Issue is specific to `/predictions/*` path
- Could be a path matching issue or static resource handler priority

## Debugging Commands

### Check Controller Bean Registration
```bash
# In Spring Boot Actuator (if enabled)
curl http://localhost:8080/actuator/beans | grep FinancialInsightsController
```

### Check Request Mapping
```bash
# Enable debug logging
# In application.yml, set:
logging:
  level:
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping: DEBUG
```

### View All Registered Endpoints
Add this to a test or controller:
```java
@Autowired
RequestMappingHandlerMapping mapping;

@GetMapping("/debug/endpoints")
public Map<String, Object> getEndpoints() {
    return mapping.getHandlerMethods().entrySet().stream()
        .collect(Collectors.toMap(
            e -> e.getKey().toString(),
            e -> e.getValue().getMethod().getName()
        ));
}
```

## Most Likely Fix

**99% of the time, this is a restart issue.** The backend needs to be restarted to register new controller endpoints.

1. Stop the backend
2. Rebuild if needed: `mvn clean package`
3. Start the backend
4. Check logs for endpoint registration
5. Test the endpoint

If restart doesn't fix it, then investigate:
- Package scanning issues
- Path conflicts
- Static resource handler configuration
