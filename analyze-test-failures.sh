#!/bin/bash
# Script to analyze test failures

cd /Users/garimaagarwal/Downloads/sum-code/BudgetBuddy-Backend

echo "Running tests and capturing failures..."
mvn test 2>&1 | tee /tmp/mvn_full_output.log

echo ""
echo "Extracting test summary..."
grep -E "Tests run:" /tmp/mvn_full_output.log | tail -1

echo ""
echo "Extracting ERROR lines..."
grep -E "\[ERROR\]" /tmp/mvn_full_output.log | head -120 > /tmp/test_errors.txt

echo "Found $(wc -l < /tmp/test_errors.txt) error lines"
echo ""
echo "First 50 errors:"
head -50 /tmp/test_errors.txt

echo ""
echo "Grouping by test class..."
grep -E "\[ERROR\].*Test" /tmp/mvn_full_output.log | sed 's/.*\[ERROR\] //' | sed 's/:.*//' | sort | uniq -c | sort -rn | head -20

