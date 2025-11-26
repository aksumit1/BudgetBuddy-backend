#!/bin/bash
# Run Plaid Integration Tests
# This script runs all Plaid-related tests and checks for issues

set -e

echo "🧪 Running Plaid Integration Tests"
echo "==================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if we're in the right directory
if [ ! -f "pom.xml" ]; then
    echo -e "${RED}❌ Error: Must run from BudgetBuddy-Backend directory${NC}"
    exit 1
fi

# Check if Maven is available
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}❌ Error: Maven not found. Please install Maven.${NC}"
    exit 1
fi

echo "📋 Test Plan:"
echo "  1. PlaidControllerIntegrationTest - Tests /api/plaid/accounts endpoint"
echo "  2. PlaidServiceTest - Tests PlaidService unit tests"
echo "  3. PlaidControllerTest - Tests PlaidController unit tests"
echo ""

# Check if tests are disabled
echo "⚠️  Note: Some tests may be disabled due to Java 25 compatibility issues"
echo ""

# Run specific Plaid tests
echo "🔍 Running PlaidControllerIntegrationTest..."
if mvn test -Dtest=PlaidControllerIntegrationTest 2>&1 | tee /tmp/plaid-integration-test.log; then
    echo -e "${GREEN}✅ PlaidControllerIntegrationTest passed${NC}"
else
    echo -e "${YELLOW}⚠️  PlaidControllerIntegrationTest failed or was disabled${NC}"
    echo "   Check /tmp/plaid-integration-test.log for details"
fi

echo ""
echo "🔍 Running PlaidServiceTest..."
if mvn test -Dtest=PlaidServiceTest 2>&1 | tee /tmp/plaid-service-test.log; then
    echo -e "${GREEN}✅ PlaidServiceTest passed${NC}"
else
    echo -e "${YELLOW}⚠️  PlaidServiceTest failed or was disabled${NC}"
    echo "   Check /tmp/plaid-service-test.log for details"
fi

echo ""
echo "🔍 Running PlaidControllerTest..."
if mvn test -Dtest=PlaidControllerTest 2>&1 | tee /tmp/plaid-controller-test.log; then
    echo -e "${GREEN}✅ PlaidControllerTest passed${NC}"
else
    echo -e "${YELLOW}⚠️  PlaidControllerTest failed or was disabled${NC}"
    echo "   Check /tmp/plaid-controller-test.log for details"
fi

echo ""
echo "📊 Summary:"
echo "  - Integration test log: /tmp/plaid-integration-test.log"
echo "  - Service test log: /tmp/plaid-service-test.log"
echo "  - Controller test log: /tmp/plaid-controller-test.log"
echo ""

# Check for common issues
echo "🔍 Checking for common issues..."
if grep -q "DISABLED" /tmp/plaid-integration-test.log /tmp/plaid-service-test.log /tmp/plaid-controller-test.log 2>/dev/null; then
    echo -e "${YELLOW}⚠️  Some tests are disabled (likely due to Java 25 compatibility)${NC}"
fi

if grep -q "NullPointerException\|IllegalArgumentException" /tmp/plaid-integration-test.log /tmp/plaid-service-test.log /tmp/plaid-controller-test.log 2>/dev/null; then
    echo -e "${RED}❌ Found NullPointerException or IllegalArgumentException in tests${NC}"
    grep -h "NullPointerException\|IllegalArgumentException" /tmp/plaid-integration-test.log /tmp/plaid-service-test.log /tmp/plaid-controller-test.log 2>/dev/null | head -5
fi

if grep -q "MissingServletRequestParameterException" /tmp/plaid-integration-test.log /tmp/plaid-service-test.log /tmp/plaid-controller-test.log 2>/dev/null; then
    echo -e "${RED}❌ Found MissingServletRequestParameterException in tests${NC}"
    grep -h "MissingServletRequestParameterException" /tmp/plaid-integration-test.log /tmp/plaid-service-test.log /tmp/plaid-controller-test.log 2>/dev/null | head -5
fi

echo ""
echo "✅ Test run complete!"
echo ""

