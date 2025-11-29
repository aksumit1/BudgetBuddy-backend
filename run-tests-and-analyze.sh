#!/bin/bash
# Script to run tests and capture failures

cd /Users/garimaagarwal/Downloads/sum-code/BudgetBuddy-Backend

echo "Running Maven tests..."
mvn test 2>&1 | tee /tmp/mvn_test_output.log

echo ""
echo "Extracting failures..."
grep -E "\[ERROR\]|Tests run:" /tmp/mvn_test_output.log | head -150 > /tmp/test_failures.txt

echo "Test failures saved to /tmp/test_failures.txt"
cat /tmp/test_failures.txt

