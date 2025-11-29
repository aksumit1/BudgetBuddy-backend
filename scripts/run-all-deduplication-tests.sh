#!/bin/bash

# Script to run all deduplication tests (backend and iOS app)
# This ensures the deduplication logic is consistent across both

set -e

echo "=== Running All Deduplication Tests ==="
echo ""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Backend tests
echo -e "${YELLOW}Running Backend Deduplication Tests...${NC}"
cd "$(dirname "$0")/.."

echo "1. AccountDeduplicationNullInstitutionIntegrationTest"
mvn test -Dtest=AccountDeduplicationNullInstitutionIntegrationTest 2>&1 | tee /tmp/backend-null-institution-tests.log
BACKEND_NULL_RESULT=$?

echo ""
echo "2. PlaidDeduplicationIntegrationTest (with null institution tests)"
mvn test -Dtest=PlaidDeduplicationIntegrationTest 2>&1 | tee /tmp/backend-plaid-dedup-tests.log
BACKEND_PLAID_RESULT=$?

echo ""
echo "3. AccountNumberDeduplicationTest"
mvn test -Dtest=AccountNumberDeduplicationTest 2>&1 | tee /tmp/backend-account-number-tests.log
BACKEND_ACCOUNT_NUMBER_RESULT=$?

# iOS app tests
echo ""
echo -e "${YELLOW}Running iOS App Deduplication Tests...${NC}"
cd ../BudgetBuddy

echo "4. DeduplicationTests (including null institution tests)"
xcodebuild test \
  -scheme BudgetBuddy \
  -destination 'platform=iOS Simulator,name=iPhone 15,OS=latest' \
  -only-testing:BudgetBuddyTests/DeduplicationTests \
  2>&1 | tee /tmp/ios-dedup-tests.log
IOS_RESULT=$?

# Summary
echo ""
echo "=== Test Results Summary ==="
echo ""

if [ $BACKEND_NULL_RESULT -eq 0 ]; then
    echo -e "${GREEN}✓ Backend Null Institution Tests: PASSED${NC}"
else
    echo -e "${RED}✗ Backend Null Institution Tests: FAILED${NC}"
fi

if [ $BACKEND_PLAID_RESULT -eq 0 ]; then
    echo -e "${GREEN}✓ Backend Plaid Deduplication Tests: PASSED${NC}"
else
    echo -e "${RED}✗ Backend Plaid Deduplication Tests: FAILED${NC}"
fi

if [ $BACKEND_ACCOUNT_NUMBER_RESULT -eq 0 ]; then
    echo -e "${GREEN}✓ Backend Account Number Tests: PASSED${NC}"
else
    echo -e "${RED}✗ Backend Account Number Tests: FAILED${NC}"
fi

if [ $IOS_RESULT -eq 0 ]; then
    echo -e "${GREEN}✓ iOS App Deduplication Tests: PASSED${NC}"
else
    echo -e "${RED}✗ iOS App Deduplication Tests: FAILED${NC}"
fi

echo ""
if [ $BACKEND_NULL_RESULT -eq 0 ] && [ $BACKEND_PLAID_RESULT -eq 0 ] && [ $BACKEND_ACCOUNT_NUMBER_RESULT -eq 0 ] && [ $IOS_RESULT -eq 0 ]; then
    echo -e "${GREEN}All tests passed! ✓${NC}"
    exit 0
else
    echo -e "${RED}Some tests failed. Check logs above.${NC}"
    exit 1
fi

