#!/bin/bash

echo "=========================================="
echo "Running Plaid Category Tests"
echo "=========================================="

cd "$(dirname "$0")"

echo ""
echo "1. Compiling tests..."
mvn test-compile -q
if [ $? -ne 0 ]; then
    echo "❌ Compilation failed"
    exit 1
fi
echo "✅ Compilation successful"

echo ""
echo "2. Running PlaidCategorySyncTest..."
mvn test -Dtest=PlaidCategorySyncTest 2>&1 | grep -E "(Tests run:|BUILD|FAILURE|ERROR)" | head -10

echo ""
echo "3. Running PlaidCategoryIntegrationTest..."
mvn test -Dtest=PlaidCategoryIntegrationTest 2>&1 | grep -E "(Tests run:|BUILD|FAILURE|ERROR)" | head -10

echo ""
echo "4. Running PlaidSyncServiceTransactionCategorizationTest..."
mvn test -Dtest=PlaidSyncServiceTransactionCategorizationTest 2>&1 | grep -E "(Tests run:|BUILD|FAILURE|ERROR)" | head -10

echo ""
echo "5. Running PlaidSyncServiceBugFixesTest..."
mvn test -Dtest=PlaidSyncServiceBugFixesTest 2>&1 | grep -E "(Tests run:|BUILD|FAILURE|ERROR)" | head -10

echo ""
echo "=========================================="
echo "Test execution complete"
echo "=========================================="

