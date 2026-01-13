#!/bin/bash
# Run subscription tests

echo "Running Subscription Tests..."
echo "=============================="

cd "$(dirname "$0")/.."

# Run all subscription-related tests
mvn test -Dtest="*Subscription*Test" 2>&1 | tee subscription-test-results.log

echo ""
echo "Test results saved to subscription-test-results.log"
echo "Check the log file for detailed results"
