# LocalStack Secrets Setup - Proper Fix

## Problem
The backend was trying to fetch secrets from AWS Secrets Manager in LocalStack, but the secrets didn't exist, causing ERROR logs even though the service correctly fell back to environment variables.

## Solution
Created a proper fix that initializes secrets in LocalStack using LocalStack's init hooks feature. This ensures secrets exist in LocalStack, matching production behavior.

## Implementation

### 1. Init Script (`scripts/init-localstack-secrets.sh`)
- Runs as a LocalStack init hook (in `/etc/localstack/init/ready.d/`)
- Creates secrets in LocalStack when LocalStack starts
- Reads values from environment variables (passed from docker-compose.yml)
- Creates:
  - `budgetbuddy/jwt-secret` - JWT signing secret
  - `budgetbuddy/plaid` - Plaid API credentials (if provided)
  - `budgetbuddy/stripe` - Stripe API credentials (if provided)

### 2. Docker Compose Configuration
- Mounts init script to LocalStack's init hooks directory
- Passes environment variables from `.env` file to LocalStack container
- Secrets are created automatically when LocalStack starts

### 3. SecretsManagerService
- Reverted log suppression changes
- Now properly fetches secrets from LocalStack (they exist!)
- Falls back to environment variables if secret doesn't exist (defensive)

## How It Works

1. **LocalStack starts** → Init hooks run
2. **Init script executes** → Creates secrets in LocalStack
3. **Backend starts** → Fetches secrets from LocalStack
4. **Secrets exist** → No errors, proper production-like behavior

## Verification

Check that secrets exist:
```bash
docker-compose exec -e AWS_ACCESS_KEY_ID=test -e AWS_SECRET_ACCESS_KEY=test localstack \
  aws --endpoint-url=http://localhost:4566 secretsmanager list-secrets --region us-east-1
```

You should see:
- `budgetbuddy/jwt-secret`
- `budgetbuddy/plaid` (if PLAID_CLIENT_ID and PLAID_SECRET are set)
- `budgetbuddy/stripe` (if STRIPE_SECRET_KEY is set)

## Benefits

- ✅ **Production parity**: Secrets exist in LocalStack, matching production
- ✅ **No error logs**: Secrets are found, no fallback needed
- ✅ **Proper initialization**: Secrets created automatically on LocalStack startup
- ✅ **Environment variable support**: Reads from `.env` file via docker-compose
- ✅ **Defensive fallback**: Still falls back to env vars if secret missing

## Configuration

Secrets are created from environment variables:
- `JWT_SECRET` → `budgetbuddy/jwt-secret`
- `PLAID_CLIENT_ID` + `PLAID_SECRET` → `budgetbuddy/plaid` (JSON)
- `STRIPE_SECRET_KEY` → `budgetbuddy/stripe` (JSON)

Set these in your `.env` file or pass them to docker-compose.

## Result

- ✅ Secrets are properly created in LocalStack
- ✅ Backend fetches secrets from Secrets Manager (no errors)
- ✅ Production-like behavior in local development
- ✅ No more ERROR logs about missing secrets

