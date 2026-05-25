#!/usr/bin/env bash
# LocalStack-ready init script. Runs once after LocalStack starts.
#
# Sets up the AWS resources the backend expects to exist:
#   - Secrets Manager entries for JWT / Plaid / Stripe
#   - S3 buckets for app storage, BERT model, PDF archive, PDF diagnostics
#
# Idempotent: every `create-bucket` / `create-secret` is guarded so a re-run
# is a no-op. Failure modes (LocalStack still booting, transient errors)
# are logged but don't abort — the backend retries on first use anyway.

set -u  # undefined vars fail; intentionally NOT -e so a single failure
        # doesn't skip downstream resources.

LOCALSTACK_ENDPOINT="${LOCALSTACK_ENDPOINT:-http://localhost:4566}"
AWS_REGION="${AWS_DEFAULT_REGION:-us-east-1}"

echo "[init-localstack] Starting init at $(date -u +%Y-%m-%dT%H:%M:%SZ)"

# --- S3 buckets ------------------------------------------------------------

create_bucket() {
    local bucket="$1"
    if awslocal s3api head-bucket --bucket "$bucket" 2>/dev/null; then
        echo "[init-localstack] bucket already exists: $bucket"
    else
        if awslocal s3 mb "s3://$bucket" --region "$AWS_REGION" 2>&1; then
            echo "[init-localstack] created bucket: $bucket"
        else
            echo "[init-localstack] WARN: failed to create bucket: $bucket"
        fi
    fi
}

# App-storage (CSV imports / exports / generic blobs).
create_bucket "budgetbuddy-storage"

# BERT model bucket (downloaded by download-bert-model.sh).
create_bucket "${BERT_MODEL_BUCKET:-budgetbuddy-bert-models}"

# PDF raw archive. The backend writes every uploaded statement here for
# parser-issue forensics. Matches PDF_ARCHIVE_S3_BUCKET in docker-compose.
create_bucket "budgetbuddy-pdf-archive-local"

# --- Secrets Manager -------------------------------------------------------

create_secret() {
    local name="$1"
    local value="$2"
    if awslocal secretsmanager describe-secret --secret-id "$name" >/dev/null 2>&1; then
        echo "[init-localstack] secret already exists: $name"
    else
        if awslocal secretsmanager create-secret \
                --name "$name" \
                --secret-string "$value" >/dev/null 2>&1; then
            echo "[init-localstack] created secret: $name"
        else
            echo "[init-localstack] WARN: failed to create secret: $name"
        fi
    fi
}

# JWT secret — passed in from docker-compose .env (with a long default
# fallback to satisfy the HS512 algorithm's 64-byte minimum).
JWT_SECRET="${JWT_SECRET:-test-secret-change-in-production-this-must-be-at-least-64-characters-long-for-hs512-algorithm-to-work-properly}"
create_secret "budgetbuddy/local/jwt-secret" "$JWT_SECRET"

# Plaid credentials. These come from the host .env file; if empty we
# still create the secret so the lookup doesn't 404 (the app handles
# empty values gracefully).
PLAID_JSON="{\"clientId\":\"${PLAID_CLIENT_ID:-}\",\"secret\":\"${PLAID_SECRET:-}\",\"environment\":\"${PLAID_ENVIRONMENT:-sandbox}\"}"
create_secret "budgetbuddy/local/plaid" "$PLAID_JSON"

# Stripe — placeholder by default. Real keys come from host .env.
STRIPE_JSON="{\"secretKey\":\"${STRIPE_SECRET_KEY:-sk_test_placeholder}\",\"publishableKey\":\"${STRIPE_PUBLISHABLE_KEY:-}\"}"
create_secret "budgetbuddy/local/stripe" "$STRIPE_JSON"

echo "[init-localstack] Init complete at $(date -u +%Y-%m-%dT%H:%M:%SZ)"
