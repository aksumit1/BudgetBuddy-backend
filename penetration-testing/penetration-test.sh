#!/bin/bash
set -e

# Penetration Testing Script for BudgetBuddy Backend
# Tests for common security vulnerabilities

BASE_URL=${1:-http://localhost:8080}
OUTPUT_DIR=${2:-./penetration-results}

echo "=========================================="
echo "BudgetBuddy Backend Penetration Testing"
echo "Base URL: ${BASE_URL}"
echo "Output Directory: ${OUTPUT_DIR}"
echo "=========================================="

mkdir -p ${OUTPUT_DIR}

# Test 1: SQL Injection
echo "Testing SQL Injection vulnerabilities..."
sqlmap -u "${BASE_URL}/api/transactions?id=1" \
    --batch \
    --level=3 \
    --risk=2 \
    --output-dir=${OUTPUT_DIR}/sqlmap \
    --log=${OUTPUT_DIR}/sqlmap.log || echo "SQL Injection test completed (may require manual review)"

# Test 2: XSS (Cross-Site Scripting)
echo "Testing XSS vulnerabilities..."
curl -X POST "${BASE_URL}/api/auth/register" \
    -H "Content-Type: application/json" \
    -d '{"email":"<script>alert(1)</script>@test.com","password":"test123"}' \
    -o ${OUTPUT_DIR}/xss-test.json

# Test 3: CSRF (Cross-Site Request Forgery)
echo "Testing CSRF protection..."
curl -X POST "${BASE_URL}/api/transactions" \
    -H "Content-Type: application/json" \
    -H "Origin: https://evil.com" \
    -d '{"amount":1000,"description":"CSRF Test"}' \
    -o ${OUTPUT_DIR}/csrf-test.json

# Test 4: Authentication Bypass
echo "Testing authentication bypass..."
curl -X GET "${BASE_URL}/api/transactions" \
    -H "Authorization: Bearer invalid-token" \
    -o ${OUTPUT_DIR}/auth-bypass-test.json

# Test 5: Rate Limiting
echo "Testing rate limiting..."
for i in {1..100}; do
    curl -X POST "${BASE_URL}/api/auth/login" \
        -H "Content-Type: application/json" \
        -d '{"email":"test@test.com","password":"test"}' \
        -o ${OUTPUT_DIR}/rate-limit-test-${i}.json
    sleep 0.1
done

# Test 6: Input Validation
echo "Testing input validation..."
curl -X POST "${BASE_URL}/api/auth/register" \
    -H "Content-Type: application/json" \
    -d '{"email":"a"*1000,"password":"","firstName":null,"lastName":""}' \
    -o ${OUTPUT_DIR}/input-validation-test.json

# Test 7: Path Traversal
echo "Testing path traversal..."
curl -X GET "${BASE_URL}/api/files/../../../etc/passwd" \
    -o ${OUTPUT_DIR}/path-traversal-test.json

# Test 8: Command Injection
echo "Testing command injection..."
curl -X POST "${BASE_URL}/api/admin/execute" \
    -H "Content-Type: application/json" \
    -d '{"command":"; ls -la"}' \
    -o ${OUTPUT_DIR}/command-injection-test.json

# Test 9: Sensitive Data Exposure
echo "Testing sensitive data exposure..."
curl -X GET "${BASE_URL}/api/users" \
    -o ${OUTPUT_DIR}/sensitive-data-test.json

# Test 10: Security Headers
echo "Testing security headers..."
curl -I "${BASE_URL}/api/auth/login" \
    -o ${OUTPUT_DIR}/security-headers.txt

# Generate report
echo "Generating penetration test report..."
cat > ${OUTPUT_DIR}/penetration-test-report.md << EOF
# Penetration Test Report

## Test Date
$(date)

## Base URL
${BASE_URL}

## Tests Performed
1. SQL Injection
2. XSS (Cross-Site Scripting)
3. CSRF (Cross-Site Request Forgery)
4. Authentication Bypass
5. Rate Limiting
6. Input Validation
7. Path Traversal
8. Command Injection
9. Sensitive Data Exposure
10. Security Headers

## Results
See individual test output files in this directory.

## Recommendations
- Review all test results
- Address any vulnerabilities found
- Re-test after fixes
EOF

echo "=========================================="
echo "Penetration testing completed!"
echo "Results saved to: ${OUTPUT_DIR}"
echo "=========================================="

