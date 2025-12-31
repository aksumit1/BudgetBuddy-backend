#!/bin/bash
# Quick script to check test progress

LOG_FILE="/tmp/test_run_output.log"
FAILED_FILE=$(ls -t /tmp/failed_tests_*.txt 2>/dev/null | head -1)

echo "=========================================="
echo "Test Progress Summary"
echo "=========================================="
echo ""

# Count completed tests
PASSED=$(grep -c "✅ PASSED" "$LOG_FILE" 2>/dev/null || echo "0")
FAILED=$(grep -c "❌ FAILED" "$LOG_FILE" 2>/dev/null || echo "0")
TIMED_OUT=$(grep -c "⏱️  TIMEOUT" "$LOG_FILE" 2>/dev/null || echo "0")
SKIPPED=$(grep -c "⏭️  SKIPPED" "$LOG_FILE" 2>/dev/null || echo "0")
PASSED=${PASSED:-0}
FAILED=${FAILED:-0}
TIMED_OUT=${TIMED_OUT:-0}
SKIPPED=${SKIPPED:-0}
TOTAL_COMPLETED=$((PASSED + FAILED + TIMED_OUT + SKIPPED))

echo "Completed: $TOTAL_COMPLETED tests"
echo "  ✅ Passed:  $PASSED"
echo "  ❌ Failed:  $FAILED"
echo "  ⏱️  Timeout: $TIMED_OUT"
echo "  ⏭️  Skipped: $SKIPPED"
echo ""

# Show current test
CURRENT=$(tail -100 "$LOG_FILE" 2>/dev/null | grep "Running:" | tail -1)
if [ -n "$CURRENT" ]; then
    echo "Currently running:"
    echo "  $CURRENT"
    echo ""
fi

# Show recent failures
if [ -n "$FAILED_FILE" ] && [ -f "$FAILED_FILE" ]; then
    echo "Failed/Timeout tests:"
    cat "$FAILED_FILE"
    echo ""
fi

# Check if script is still running
if ps aux | grep -q "[r]un_tests_one_by_one_with_timeout.sh"; then
    echo "Status: ✅ Script is running"
else
    echo "Status: ⏹️  Script has completed"
    echo ""
    echo "Final summary:"
    tail -20 "$LOG_FILE" | grep -E "Test Summary|Passed:|Failed:|Timeout:"
fi

