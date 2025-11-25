# BudgetBuddy Backend - Test Suite Documentation

## Overview

Comprehensive test suite covering unit, integration, functional, load, chaos, security, and localization tests.

## Test Structure

```
src/test/java/com/budgetbuddy/
├── service/              # Unit tests for services
├── api/                  # Unit tests for controllers
├── integration/          # Integration tests
├── functional/           # Functional/end-to-end tests
├── load/                 # Load/performance tests
├── chaos/                # Chaos engineering tests
├── security/             # Security and penetration tests
└── localization/         # Localization tests

src/test/resources/
├── application-test.yml  # Test configuration
├── k6-load-test.js      # k6 load test script
└── k6-chaos-test.js     # k6 chaos test script
```

## Running Tests

### All Tests
```bash
./run-all-tests.sh
```

### Unit Tests Only
```bash
mvn test
```

### Integration Tests
```bash
mvn verify
```

### Specific Test Suite
```bash
mvn test -Dtest=TransactionServiceTest
```

### Load Tests (k6)
```bash
# Install k6 first: brew install k6
k6 run src/test/resources/k6-load-test.js
```

### Chaos Tests (k6)
```bash
k6 run src/test/resources/k6-chaos-test.js
```

## Test Coverage

Run with coverage:
```bash
mvn clean test jacoco:report
```

View coverage report:
```bash
open target/site/jacoco/index.html
```

## Prerequisites

- Java 21
- Maven 3.8+
- LocalStack (for DynamoDB integration tests)
- k6 (optional, for load/chaos tests)

## Test Configuration

Test configuration is in `src/test/resources/application-test.yml`:
- Uses LocalStack for DynamoDB
- Disables CloudWatch
- Higher rate limits for testing
- Test-specific JWT secrets

