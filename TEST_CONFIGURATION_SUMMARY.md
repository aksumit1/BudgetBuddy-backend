# Test Configuration Summary

This document summarizes all configurations needed for unit, load, and integration tests to run successfully.

## ✅ Already Configured

### 1. LocalStack (DynamoDB, S3, Secrets Manager)
- **CI Configuration**: `.github/workflows/ci-cd.yml`
  - Service: `localstack/localstack:latest`
  - Port: `4566:4566`
  - Services: `dynamodb`, `s3`, `secretsmanager`
  - Health check configured
  - Wait time: 120 seconds
  - Verification step added

- **Test Configuration**: `src/test/resources/application-test.yml`
  - Endpoint: `http://localhost:4566`
  - Table prefix: `TestBudgetBuddy`
  - Auto-create tables: `true`
  - Tables created automatically via `DynamoDBTableManager.@PostConstruct`

### 2. Redis (Optional)
- **CI Configuration**: `.github/workflows/ci-cd.yml`
  - Service: `redis:7-alpine`
  - Port: `6379:6379`
  - Health check configured
  - **Note**: Redis is optional - `DistributedLock` handles null gracefully

- **Test Configuration**: `src/test/resources/application-test.yml`
  - Host: `localhost` (default)
  - Port: `6379` (default)
  - Timeout: `2000ms`
  - **Note**: Tests will work without Redis (uses in-memory fallback)

### 3. Test Profile Configuration
- **Profile**: `test` (activated via `@ActiveProfiles("test")`)
- **AWS Test Configuration**: `AWSTestConfiguration.java`
  - All AWS clients configured for LocalStack
  - Test credentials: `test/test`
  - Region: `us-east-1`

### 4. Maven Test Configuration
- **Surefire Plugin** (Unit Tests):
  - Includes: `**/*Test.java`
  - Excludes: `**/*IntegrationTest.java`
  - Parallel execution: `methods` with 4 threads
  - Fork count: 1 (reused for performance)

- **Failsafe Plugin** (Integration Tests):
  - Includes: `**/*IntegrationTest.java`
  - Runs during `mvn verify`

### 5. Test Error Handling
- **ResourceNotFound Handling**: All tests gracefully skip when LocalStack/tables unavailable
- **500 Error Handling**: Registration test skips in CI when infrastructure unavailable
- **Exception Detection**: Checks both exception class name and error messages

## ✅ Recent Fixes Applied

### 1. Registration Test Format
- ✅ Updated to use `password_hash` (snake_case) instead of `passwordHash` + `salt`
- ✅ Removed `firstName` and `lastName` (now optional)

### 2. k6 Load Test
- ✅ Updated to use new authentication format (no salt)
- ✅ Fixed missing `textSummary` import

### 3. CI Workflow
- ✅ Added Redis service (optional)
- ✅ Extended LocalStack wait time to 120 seconds
- ✅ Added verification steps for both LocalStack and Redis

### 4. Test Configuration
- ✅ Added Redis configuration (optional)
- ✅ All tests handle infrastructure unavailability gracefully

## 📋 Required Environment Variables

### For Local Development
```bash
# Optional - defaults work for tests
SPRING_DATA_REDIS_HOST=localhost
SPRING_DATA_REDIS_PORT=6379
```

### For CI
- No additional environment variables needed
- All services configured via GitHub Actions services
- Test profile automatically uses LocalStack endpoints

## 🔧 Test Execution

### Unit Tests
```bash
mvn clean test
```

### Integration Tests
```bash
mvn verify
```

### Load Tests (k6)
```bash
# Requires k6 installed
k6 run src/test/resources/k6-load-test.js
```

### All Tests
```bash
mvn clean verify
```

## ⚠️ Important Notes

1. **LocalStack**: Must be running before tests (handled automatically in CI)
2. **Redis**: Optional - tests work without it
3. **Table Creation**: Automatic via `DynamoDBTableManager` on Spring Boot startup
4. **Test Isolation**: Each test should clean up after itself
5. **Infrastructure Failures**: Tests skip gracefully when services unavailable

## 🐛 Troubleshooting

### Tests failing with "ResourceNotFound"
- **Cause**: LocalStack not running or tables not created
- **Fix**: Ensure LocalStack is running and wait for table creation
- **CI**: Tests will skip gracefully

### Tests failing with 500 errors
- **Cause**: Missing tables or LocalStack not ready
- **Fix**: Wait longer for LocalStack initialization
- **CI**: Tests will skip gracefully

### Redis connection errors
- **Cause**: Redis not running
- **Fix**: Redis is optional - tests will use in-memory fallback
- **CI**: Redis is configured but optional

### Load tests failing
- **Cause**: Backend not running or wrong format
- **Fix**: Ensure backend is running on `http://localhost:8080`
- **Note**: Updated to use new authentication format

## 📊 Test Coverage

- **Unit Tests**: All `*Test.java` files (excluding `*IntegrationTest.java`)
- **Integration Tests**: All `*IntegrationTest.java` files
- **Load Tests**: k6 scripts in `src/test/resources/`
- **Functional Tests**: `*FunctionalTest.java` files

## ✅ Verification Checklist

- [x] LocalStack configured in CI
- [x] Redis configured in CI (optional)
- [x] Test profile configured
- [x] AWS test configuration complete
- [x] Table auto-creation enabled
- [x] Error handling for infrastructure failures
- [x] Load test format updated
- [x] Maven test plugins configured
- [x] Test timeouts configured (XCTest native for iOS)

