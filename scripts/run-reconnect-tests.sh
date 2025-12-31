#!/bin/bash
# Script to run Plaid Reconnect Sync Integration Tests

echo "=========================================="
echo "Running Plaid Reconnect Sync Integration Tests"
echo "=========================================="

cd "$(dirname "$0")"

echo "Running Maven tests..."
mvn test -Dtest=PlaidReconnectSyncIntegrationTest

echo ""
echo "=========================================="
echo "Test execution complete"
echo "=========================================="

