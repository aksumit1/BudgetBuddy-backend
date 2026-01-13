# Test Resources

## k6 Load Testing

### Prerequisites
```bash
# Install k6
brew install k6  # macOS
# or
# Download from https://k6.io/docs/getting-started/installation/
```

### Running Load Tests

```bash
# Basic load test
k6 run k6-load-test.js

# With custom base URL
BASE_URL=https://api.budgetbuddy.com k6 run k6-load-test.js

# With custom options
k6 run --vus 200 --duration 5m k6-load-test.js
```

### Running Chaos Tests

```bash
# Chaos test with spikes
k6 run k6-chaos-test.js

# With custom base URL
BASE_URL=https://api.budgetbuddy.com k6 run k6-chaos-test.js
```

## Test Configuration

- `application-test.yml` - Test-specific configuration
- Uses LocalStack for DynamoDB testing
- Disables CloudWatch in tests
- Higher rate limits for testing

