#!/bin/bash

# Comprehensive Test Runner Script
# Runs all test suites for BudgetBuddy Backend

set -e

echo "=========================================="
echo "BudgetBuddy Backend - Comprehensive Test Suite"
echo "=========================================="
echo ""

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Test results
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

# Function to run tests and capture results
run_test_suite() {
    local suite_name=$1
    local command=$2
    
    echo -e "${YELLOW}Running $suite_name...${NC}"
    if eval "$command"; then
        echo -e "${GREEN}✓ $suite_name passed${NC}"
        ((PASSED_TESTS++))
    else
        echo -e "${RED}✗ $suite_name failed${NC}"
        ((FAILED_TESTS++))
    fi
    ((TOTAL_TESTS++))
    echo ""
}

# 1. Unit Tests
run_test_suite "Unit Tests" "mvn test -Dtest='*Test' -DfailIfNoTests=false"

# 2. Integration Tests
run_test_suite "Integration Tests" "mvn verify -Dtest='*IntegrationTest' -DfailIfNoTests=false"

# 3. Functional Tests
run_test_suite "Functional Tests" "mvn test -Dtest='*FunctionalTest' -DfailIfNoTests=false"

# 4. Security Tests
run_test_suite "Security Tests" "mvn test -Dtest='*Security*Test' -DfailIfNoTests=false"

# 5. Localization Tests
run_test_suite "Localization Tests" "mvn test -Dtest='*LocalizationTest' -DfailIfNoTests=false"

# 6. Load Tests (if k6 is installed)
if command -v k6 &> /dev/null; then
    echo -e "${YELLOW}Running Load Tests (k6)...${NC}"
    if k6 run src/test/resources/k6-load-test.js; then
        echo -e "${GREEN}✓ Load Tests passed${NC}"
        ((PASSED_TESTS++))
    else
        echo -e "${RED}✗ Load Tests failed${NC}"
        ((FAILED_TESTS++))
    fi
    ((TOTAL_TESTS++))
    echo ""
else
    echo -e "${YELLOW}⚠ k6 not installed, skipping load tests${NC}"
    echo ""
fi

# 7. Chaos Tests (if k6 is installed)
if command -v k6 &> /dev/null; then
    echo -e "${YELLOW}Running Chaos Tests (k6)...${NC}"
    if k6 run src/test/resources/k6-chaos-test.js; then
        echo -e "${GREEN}✓ Chaos Tests passed${NC}"
        ((PASSED_TESTS++))
    else
        echo -e "${RED}✗ Chaos Tests failed${NC}"
        ((FAILED_TESTS++))
    fi
    ((TOTAL_TESTS++))
    echo ""
else
    echo -e "${YELLOW}⚠ k6 not installed, skipping chaos tests${NC}"
    echo ""
fi

# Summary
echo "=========================================="
echo "Test Summary"
echo "=========================================="
echo "Total Test Suites: $TOTAL_TESTS"
echo -e "${GREEN}Passed: $PASSED_TESTS${NC}"
if [ $FAILED_TESTS -gt 0 ]; then
    echo -e "${RED}Failed: $FAILED_TESTS${NC}"
    exit 1
else
    echo -e "${GREEN}Failed: $FAILED_TESTS${NC}"
    exit 0
fi

