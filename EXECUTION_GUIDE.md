# Test Execution Guide

## Quick Start

### Backend Tests
```bash
cd BudgetBuddy-Backend
./run-all-tests.sh
```

### iOS Tests
```bash
cd BudgetBuddy
xcodebuild test -scheme BudgetBuddy -destination 'platform=iOS Simulator,name=iPhone 15'
```

## Detailed Execution

### 1. Unit Tests

**Backend:**
```bash
mvn test
```

**iOS:**
```bash
xcodebuild test -scheme BudgetBuddy -destination 'platform=iOS Simulator,name=iPhone 15' -only-testing:BudgetBuddyTests
```

### 2. Integration Tests

**Backend:**
```bash
# Requires LocalStack running
docker run -d -p 4566:4566 localstack/localstack
mvn verify
```

**iOS:**
```bash
# Integration tests are part of unit test suite
xcodebuild test -scheme BudgetBuddy -destination 'platform=iOS Simulator,name=iPhone 15' -only-testing:BudgetBuddyTests
```

### 3. Functional Tests

**Backend:**
```bash
mvn test -Dtest='*FunctionalTest'
```

**iOS:**
```bash
xcodebuild test -scheme BudgetBuddy -destination 'platform=iOS Simulator,name=iPhone 15' -only-testing:BudgetBuddyUITests
```

### 4. Load Tests

**Backend:**
```bash
# Install k6: brew install k6
k6 run src/test/resources/k6-load-test.js
```

**iOS:**
```bash
xcodebuild test -scheme BudgetBuddy -destination 'platform=iOS Simulator,name=iPhone 15' -only-testing:BudgetBuddyPerformanceTests
```

### 5. Chaos Tests

**Backend:**
```bash
k6 run src/test/resources/k6-chaos-test.js
```

**iOS:**
```bash
xcodebuild test -scheme BudgetBuddy -destination 'platform=iOS Simulator,name=iPhone 15' -only-testing:BudgetBuddyChaosTests
```

### 6. Security Tests

**Backend:**
```bash
mvn test -Dtest='*Security*Test'
```

**iOS:**
```bash
xcodebuild test -scheme BudgetBuddy -destination 'platform=iOS Simulator,name=iPhone 15' -only-testing:BudgetBuddyTests/SecurityTests
```

### 7. Localization Tests

**Backend:**
```bash
mvn test -Dtest='*LocalizationTest'
```

**iOS:**
```bash
xcodebuild test -scheme BudgetBuddy -destination 'platform=iOS Simulator,name=iPhone 15' -only-testing:BudgetBuddyTests/LocalizationTests
```

## Test Reports

### Backend
- Coverage: `target/site/jacoco/index.html`
- Surefire reports: `target/surefire-reports/`
- Failsafe reports: `target/failsafe-reports/`

### iOS
- Coverage: Xcode Report Navigator
- Test results: Xcode Test Navigator

## Troubleshooting

### Backend
- **LocalStack not running**: Start with `docker run -d -p 4566:4566 localstack/localstack`
- **Port conflicts**: Change port in `application-test.yml`
- **k6 not found**: Install with `brew install k6`

### iOS
- **Simulator not available**: List with `xcrun simctl list devices`
- **Scheme not found**: Open project in Xcode first
- **Tests not running**: Check test target membership

