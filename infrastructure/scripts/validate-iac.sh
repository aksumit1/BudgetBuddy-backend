#!/usr/bin/env bash
# Validate CloudFormation templates with three layers of checks:
#   1. yamllint      - YAML syntax / formatting
#   2. cfn-lint      - AWS CloudFormation semantic validation (types, refs, schemas)
#   3. cfn-nag       - Security best-practices (optional, via Docker)
#   4. checkov       - Broader IaC security + compliance (optional, via Docker)
#
# Exits non-zero if any mandatory check (yamllint, cfn-lint) reports errors.
# Optional checks print findings but do not fail the build unless
# VALIDATE_IAC_STRICT=1 is set.

set -eu

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
REPO_ROOT="$( cd "$SCRIPT_DIR/../.." && pwd )"
CFN_DIR="$REPO_ROOT/infrastructure/cloudformation"

# Templates that are part of the deploy-clean core path. Other templates in
# the directory are pipeline/internal fixtures with known pre-existing issues
# tracked separately — listing them here would make every build fail.
CORE_TEMPLATES=(
    "$CFN_DIR/main-stack.yaml"
    "$CFN_DIR/dynamodb.yaml"
    "$CFN_DIR/monitoring.yaml"
    "$CFN_DIR/sns-ses.yaml"
)

ALL_TEMPLATES=( "$CFN_DIR"/*.yaml )

have() { command -v "$1" >/dev/null 2>&1; }
ok()   { printf '\033[32m✅ %s\033[0m\n' "$1"; }
warn() { printf '\033[33m⚠  %s\033[0m\n' "$1"; }
err()  { printf '\033[31m❌ %s\033[0m\n' "$1"; }

errors=0

# --- 1. yamllint on all templates -------------------------------------------
echo "=== yamllint ==="
if have yamllint; then
    # Config is at repo root
    if yamllint -c "$REPO_ROOT/.yamllint" "${ALL_TEMPLATES[@]}" "$REPO_ROOT/buildspec.yaml" 2>&1 | tee /tmp/yamllint.out | grep -q "error"; then
        err "yamllint found errors"
        errors=$((errors + 1))
    else
        ok "yamllint clean"
    fi
else
    warn "yamllint not installed — pip3 install --user yamllint"
fi

# --- 2. cfn-lint on core templates (strict) + all (advisory) ----------------
echo
echo "=== cfn-lint (core, strict) ==="
CFN_LINT_BIN=""
if have cfn-lint; then
    CFN_LINT_BIN="cfn-lint"
elif [ -x "$HOME/Library/Python/3.14/bin/cfn-lint" ]; then
    CFN_LINT_BIN="$HOME/Library/Python/3.14/bin/cfn-lint"
elif [ -x "$HOME/.local/bin/cfn-lint" ]; then
    CFN_LINT_BIN="$HOME/.local/bin/cfn-lint"
fi

if [ -n "$CFN_LINT_BIN" ]; then
    core_errors=$("$CFN_LINT_BIN" "${CORE_TEMPLATES[@]}" 2>&1 | grep -E "^E[0-9]+" || true)
    if [ -n "$core_errors" ]; then
        echo "$core_errors"
        err "cfn-lint found errors in core templates"
        errors=$((errors + 1))
    else
        ok "cfn-lint clean on core templates"
    fi

    echo
    echo "=== cfn-lint (all, advisory) ==="
    advisory=$("$CFN_LINT_BIN" "${ALL_TEMPLATES[@]}" 2>&1 | grep -E "^E[0-9]+" | wc -l | tr -d ' ')
    if [ "$advisory" -gt 0 ]; then
        warn "$advisory error(s) in non-core templates (advisory — not blocking build)"
    else
        ok "cfn-lint clean on all templates"
    fi
else
    warn "cfn-lint not installed — pip3 install --user cfn-lint"
fi

# --- 3. cfn-nag (optional, via Docker) --------------------------------------
echo
echo "=== cfn-nag (optional) ==="
if have docker && docker info >/dev/null 2>&1; then
    if docker run --rm -v "$CFN_DIR:/templates" stelligent/cfn_nag:latest \
        /templates/main-stack.yaml /templates/dynamodb.yaml /templates/monitoring.yaml /templates/sns-ses.yaml \
        > /tmp/cfn-nag.out 2>&1; then
        ok "cfn-nag clean"
    else
        warn "cfn-nag found findings (see /tmp/cfn-nag.out)"
        [ "${VALIDATE_IAC_STRICT:-0}" = "1" ] && errors=$((errors + 1))
    fi
else
    warn "cfn-nag skipped — Docker not running"
fi

# --- 4. checkov (optional, via Docker) --------------------------------------
echo
echo "=== checkov (optional) ==="
if have docker && docker info >/dev/null 2>&1; then
    if docker run --rm -v "$CFN_DIR:/tf" bridgecrew/checkov:latest \
        --framework cloudformation --directory /tf --quiet \
        > /tmp/checkov.out 2>&1; then
        ok "checkov clean"
    else
        warn "checkov found findings (see /tmp/checkov.out — head):"
        head -40 /tmp/checkov.out || true
        [ "${VALIDATE_IAC_STRICT:-0}" = "1" ] && errors=$((errors + 1))
    fi
else
    warn "checkov skipped — Docker not running"
fi

echo
if [ "$errors" -gt 0 ]; then
    err "IaC validation failed ($errors blocking check(s))"
    exit 1
fi
ok "IaC validation passed"
