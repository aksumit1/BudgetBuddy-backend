#!/bin/bash

# Script to run the new tests for account number deduplication and circuit breaker configuration

echo "Running Account Number Deduplication Tests..."
mvn test -Dtest=AccountNumberDeduplicationTest

echo ""
echo "Running PlaidSyncService Account Number Tests..."
mvn test -Dtest=PlaidSyncServiceAccountNumberTest

echo ""
echo "Running Circuit Breaker Configuration Tests..."
mvn test -Dtest=CircuitBreakerConfigurationTest

echo ""
echo "Running Updated Plaid Deduplication Integration Tests..."
mvn test -Dtest=PlaidDeduplicationIntegrationTest

echo ""
echo "All tests completed!"

