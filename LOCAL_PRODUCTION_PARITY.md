# Local Stack Production Parity Configuration

## Overview
The local Docker Compose stack is now configured to match production as closely as possible, enabling all production features and services.

## Enabled Production Features

### 1. **Cache Warming Service** ✅
- **Status**: Enabled (`CACHE_WARMING_ENABLED=true`)
- **Purpose**: Pre-loads frequently accessed data into cache
- **Scheduled Tasks**:
  - User cache warming: Daily at 2 AM
  - Account cache warming: Every 6 hours
  - Transaction cache warming: Every 4 hours
- **LocalStack Impact**: Uses DynamoDB (via LocalStack) to find active users

### 2. **AWS AppConfig** ✅
- **Status**: Enabled (`APP_CONFIG_ENABLED=true`)
- **Purpose**: Dynamic configuration management and feature flags
- **LocalStack**: Configured to use `http://localstack:4566`
- **Fallback**: Gracefully handles failures (logs warning, continues without AppConfig)

### 3. **AWS Secrets Manager** ✅
- **Status**: Enabled (`AWS_SECRETS_MANAGER_ENABLED=true`)
- **Purpose**: Secure secret storage and retrieval
- **LocalStack**: Configured to use `http://localstack:4566`
- **Fallback**: Falls back to environment variables if unavailable

### 4. **AWS CloudWatch** ✅
- **Status**: Enabled (`AWS_CLOUDWATCH_ENABLED=true`)
- **Purpose**: Metrics and logging
- **LocalStack**: Configured to use `http://localstack:4566`
- **Note**: Metrics are sent to LocalStack (can be viewed via LocalStack dashboard)

### 5. **Rate Limiting** ✅
- **Status**: Enabled (`app.rate-limit.enabled=true`)
- **Purpose**: Per-user and DDoS protection
- **Storage**: Uses DynamoDB (via LocalStack)

### 6. **Compliance & Audit Logging** ✅
- **Status**: Enabled
- **Purpose**: Audit logs and data retention
- **Storage**: Uses DynamoDB (via LocalStack)

### 7. **Analytics** ✅
- **Status**: Enabled
- **Purpose**: User analytics and insights
- **Storage**: Uses DynamoDB (via LocalStack)

## LocalStack Services Enabled

```yaml
SERVICES=dynamodb,s3,secretsmanager,cloudwatch,iam,sts,appconfig
```

All production AWS services are available locally via LocalStack.

## Configuration Differences (Local vs Production)

### Only Differences (Necessary for Local Development):

1. **SSL/TLS** (`SERVER_SSL_ENABLED=false`)
   - **Reason**: No SSL certificates in local development
   - **Production**: Enabled with proper certificates

2. **Tracing** (`MANAGEMENT_TRACING_ENABLED=false`)
   - **Reason**: Reduces overhead in local development
   - **Production**: Enabled for distributed tracing

3. **AWS Endpoints**
   - **Local**: `http://localstack:4566` (LocalStack)
   - **Production**: Real AWS endpoints (no endpoint override)

4. **Credentials**
   - **Local**: Static test credentials (`test`/`test`)
   - **Production**: IAM roles (ECS Task Role)

## Environment Variables (docker-compose.yml)

```yaml
# Production features enabled
APP_CONFIG_ENABLED: true
AWS_SECRETS_MANAGER_ENABLED: true
AWS_CLOUDWATCH_ENABLED: true
CACHE_WARMING_ENABLED: true

# LocalStack endpoints
DYNAMODB_ENDPOINT: http://localstack:4566
AWS_S3_ENDPOINT: http://localstack:4566
AWS_SECRETS_MANAGER_ENDPOINT: http://localstack:4566
AWS_APPCONFIG_ENDPOINT: http://localstack:4566
AWS_CLOUDWATCH_ENDPOINT: http://localstack:4566
```

## Benefits of Production Parity

1. **Early Bug Detection**: Catch production issues locally
2. **Realistic Testing**: Test with same services as production
3. **Feature Validation**: Verify all features work together
4. **Performance Testing**: Test cache warming and scheduled tasks locally
5. **Configuration Testing**: Test AppConfig and Secrets Manager integration

## Monitoring Local Services

### LocalStack Dashboard
- Access: `http://localhost:4566/_localstack/health`
- View services: Check which AWS services are running

### Redis
- Connection: `redis-cli -h localhost -p 6379`
- Monitor: `redis-cli INFO`

### Backend Logs
- Cache warming: Look for "Starting user/account/transaction cache warming"
- AppConfig: Look for "AWS AppConfig integration initialized"
- Secrets Manager: Look for "AWS Secrets Manager enabled"

## Troubleshooting

### If AppConfig fails to initialize:
- **Expected**: AppConfig may fail if not configured in LocalStack
- **Impact**: Application continues with fallback to application.yml
- **Fix**: Configure AppConfig in LocalStack or disable if not needed

### If Secrets Manager fails:
- **Expected**: Falls back to environment variables
- **Impact**: Application uses env vars (works fine)
- **Fix**: Create secrets in LocalStack or use env vars

### If Cache Warming is slow:
- **Check**: DynamoDB connection to LocalStack
- **Check**: Number of active users (may be slow with many users)
- **Fix**: Reduce cache warming limits or disable if needed

## Next Steps

1. **Create LocalStack Secrets** (optional):
   ```bash
   aws --endpoint-url=http://localhost:4566 secretsmanager create-secret \
     --name budgetbuddy/jwt-secret \
     --secret-string "your-secret-value"
   ```

2. **Create LocalStack AppConfig** (optional):
   - Use LocalStack dashboard or AWS CLI
   - Configure application, environment, and profile

3. **Monitor Cache Warming**:
   - Check logs for cache warming execution
   - Verify it's not causing performance issues

4. **Test Production Features**:
   - Verify AppConfig integration
   - Test Secrets Manager retrieval
   - Monitor CloudWatch metrics

