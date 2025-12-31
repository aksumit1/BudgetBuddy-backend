#!/bin/bash
# Script to compile and run backend tests one by one with timeouts
# Fixes any compilation or test failures

set -e

PROJECT_DIR="/Users/garimaagarwal/Downloads/sum-code/BudgetBuddy-Backend"
cd "$PROJECT_DIR"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Timeout configuration (in seconds)
DEFAULT_TEST_TIMEOUT=300  # 5 minutes per test
COMPILATION_TIMEOUT=600   # 10 minutes for compilation

# Counters
PASSED=0
FAILED=0
TIMED_OUT=0
SKIPPED=0
TOTAL=0

# Log file
LOG_FILE="/tmp/test_run_$(date +%Y%m%d_%H%M%S).log"
FAILED_TESTS_FILE="/tmp/failed_tests_$(date +%Y%m%d_%H%M%S).txt"

echo "=========================================="
echo "BudgetBuddy Backend Test Runner"
echo "=========================================="
echo "Log file: $LOG_FILE"
echo "Failed tests: $FAILED_TESTS_FILE"
echo ""

# Function to run command with timeout
run_with_timeout() {
    local timeout=$1
    shift
    local cmd="$@"
    
    # Use gtimeout if available (macOS: brew install coreutils)
    # Otherwise use perl-based timeout
    if command -v gtimeout &> /dev/null; then
        gtimeout $timeout $cmd
    elif command -v timeout &> /dev/null; then
        timeout $timeout $cmd
    else
        # Perl-based timeout for macOS
        perl -e "alarm $timeout; exec @ARGV" $cmd
    fi
}

# Function to compile the project
compile_project() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}Step 1: Compiling project...${NC}"
    echo -e "${BLUE}========================================${NC}"
    
    if run_with_timeout $COMPILATION_TIMEOUT mvn clean compile test-compile -DskipTests 2>&1 | tee -a "$LOG_FILE"; then
        echo -e "${GREEN}✅ Compilation successful${NC}"
        return 0
    else
        echo -e "${RED}❌ Compilation failed${NC}"
        return 1
    fi
}

# Function to discover all test classes
# Returns array via global variable TEST_CLASSES_ARRAY
discover_test_classes() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}Step 2: Discovering test classes...${NC}"
    echo -e "${BLUE}========================================${NC}"
    
    # Find all test classes (files ending in Test.java)
    local test_files=$(find src/test/java -name "*Test.java" -type f | sort)
    
    # Extract class names from file paths
    TEST_CLASSES_ARRAY=()
    while IFS= read -r test_file; do
        [ -z "$test_file" ] && continue
        # Convert path to class name
        # e.g., src/test/java/com/budgetbuddy/service/MyTest.java -> com.budgetbuddy.service.MyTest
        local class_name=$(echo "$test_file" | sed 's|src/test/java/||' | sed 's|\.java$||' | tr '/' '.')
        TEST_CLASSES_ARRAY+=("$class_name")
    done <<< "$test_files"
    
    echo "Found ${#TEST_CLASSES_ARRAY[@]} test classes"
    echo ""
    
    # Print first 10 for verification
    echo "Sample test classes:"
    for i in "${!TEST_CLASSES_ARRAY[@]}"; do
        if [ $i -lt 10 ]; then
            echo "  - ${TEST_CLASSES_ARRAY[$i]}"
        fi
    done
    if [ ${#TEST_CLASSES_ARRAY[@]} -gt 10 ]; then
        echo "  ... and $(( ${#TEST_CLASSES_ARRAY[@]} - 10 )) more"
    fi
    echo ""
}

# Function to run a single test class
run_test_class() {
    local test_class=$1
    local timeout=${2:-$DEFAULT_TEST_TIMEOUT}
    
    echo -e "${YELLOW}========================================${NC}"
    echo -e "${YELLOW}Running: $test_class${NC}"
    echo -e "${YELLOW}Timeout: ${timeout}s${NC}"
    echo -e "${YELLOW}========================================${NC}"
    
    local start_time=$(date +%s)
    local test_log="/tmp/test_$(echo $test_class | tr '.' '_').log"
    
    # Run test with timeout
    local exit_code=0
    if run_with_timeout $timeout mvn test -Dtest="$test_class" -DfailIfNoTests=false 2>&1 | tee "$test_log"; then
        exit_code=0
    else
        exit_code=${PIPESTATUS[0]}
    fi
    
    local end_time=$(date +%s)
    local duration=$((end_time - start_time))
    
    # Append to main log
    echo "--- Test: $test_class (${duration}s) ---" >> "$LOG_FILE"
    cat "$test_log" >> "$LOG_FILE"
    echo "" >> "$LOG_FILE"
    
    # Check results
    if [ $exit_code -eq 124 ] || [ $duration -ge $timeout ]; then
        echo -e "${RED}⏱️  TIMEOUT: $test_class exceeded ${timeout}s (actual: ${duration}s)${NC}"
        echo "$test_class - TIMEOUT (${duration}s)" >> "$FAILED_TESTS_FILE"
        TIMED_OUT=$((TIMED_OUT + 1))
        return 1
    elif grep -q "Tests run:.*Failures: 0.*Errors: 0" "$test_log" 2>/dev/null; then
        echo -e "${GREEN}✅ PASSED: $test_class (${duration}s)${NC}"
        PASSED=$((PASSED + 1))
        return 0
    elif grep -q "No tests were found" "$test_log" 2>/dev/null; then
        echo -e "${YELLOW}⏭️  SKIPPED: $test_class (no tests found)${NC}"
        SKIPPED=$((SKIPPED + 1))
        return 0
    else
        echo -e "${RED}❌ FAILED: $test_class (${duration}s)${NC}"
        echo "$test_class - FAILED (${duration}s)" >> "$FAILED_TESTS_FILE"
        # Show error summary
        grep -E "\[ERROR\]|FAILURE|Tests run:" "$test_log" | head -5
        FAILED=$((FAILED + 1))
        return 1
    fi
}

# Main execution
main() {
    # Step 1: Compile
    if ! compile_project; then
        echo -e "${RED}Compilation failed. Please fix compilation errors first.${NC}"
        exit 1
    fi
    
    # Step 2: Discover tests
    discover_test_classes
    test_classes=("${TEST_CLASSES_ARRAY[@]}")
    TOTAL=${#test_classes[@]}
    
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}Step 3: Running tests one by one...${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""
    
    # Step 3: Run each test
    for test_class in "${test_classes[@]}"; do
        run_test_class "$test_class" "$DEFAULT_TEST_TIMEOUT" || true
        echo ""
        sleep 1  # Small delay between tests
    done
    
    # Summary
    echo ""
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}Test Summary${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo -e "  ${GREEN}✅ Passed:  $PASSED${NC}"
    echo -e "  ${RED}❌ Failed:  $FAILED${NC}"
    echo -e "  ${RED}⏱️  Timeout: $TIMED_OUT${NC}"
    echo -e "  ${YELLOW}⏭️  Skipped: $SKIPPED${NC}"
    echo -e "  ${BLUE}📊 Total:   $TOTAL${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""
    echo "Log file: $LOG_FILE"
    
    if [ $FAILED -gt 0 ] || [ $TIMED_OUT -gt 0 ]; then
        echo "Failed tests saved to: $FAILED_TESTS_FILE"
        echo ""
        echo "Failed/Timeout tests:"
        cat "$FAILED_TESTS_FILE" 2>/dev/null || echo "None"
        return 1
    else
        echo -e "${GREEN}All tests passed!${NC}"
        return 0
    fi
}

# Run main function
main "$@"

