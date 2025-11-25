#!/bin/bash
set -e

# Load Testing Script for BudgetBuddy Backend
# Uses k6 for load testing

BASE_URL=${1:-http://localhost:8080}
TEST_TYPE=${2:-normal}

echo "=========================================="
echo "BudgetBuddy Backend Load Testing"
echo "Base URL: ${BASE_URL}"
echo "Test Type: ${TEST_TYPE}"
echo "=========================================="

# Check if k6 is installed
if ! command -v k6 &> /dev/null; then
    echo "k6 is not installed. Installing..."
    brew install k6 || {
        echo "Failed to install k6. Please install manually from https://k6.io/docs/getting-started/installation/"
        exit 1
    }
fi

# Run load test based on type
case ${TEST_TYPE} in
    "normal")
        echo "Running normal load test..."
        k6 run --env BASE_URL=${BASE_URL} k6-load-test.js
        ;;
    "stress")
        echo "Running stress test..."
        k6 run --env BASE_URL=${BASE_URL} k6-stress-test.js
        ;;
    "spike")
        echo "Running spike test..."
        k6 run --env BASE_URL=${BASE_URL} k6-spike-test.js
        ;;
    "soak")
        echo "Running soak test..."
        k6 run --env BASE_URL=${BASE_URL} k6-soak-test.js
        ;;
    *)
        echo "Unknown test type: ${TEST_TYPE}"
        echo "Available types: normal, stress, spike, soak"
        exit 1
        ;;
esac

echo "=========================================="
echo "Load test completed!"
echo "=========================================="

