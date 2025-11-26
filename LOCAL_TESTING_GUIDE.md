# Local Backend Testing Guide

Complete guide for running and testing the BudgetBuddy backend locally.

---

## 📋 Prerequisites

Before you begin, ensure you have the following installed:

- **Java 25** (or JDK 25)
- **Maven 3.9+**
- **Docker** and **Docker Compose**
- **Git**

### Verify Installation

```bash
# Check Java version
java -version  # Should show Java 25

# Check Maven version
mvn -version  # Should show Maven 3.9+

# Check Docker
docker --version
docker-compose --version
```

---

## 🚀 Quick Start (3 Methods)

### Method 1: Using Docker Compose (Recommended)

This is the easiest way to run everything locally with LocalStack for AWS services.

```bash
# 1. Navigate to backend directory
cd BudgetBuddy-Backend

# 2. Start LocalStack and Backend
docker-compose up -d

# 3. Check logs
docker-compose logs -f backend

# 4. Test health endpoint
curl http://localhost:8080/actuator/health

# 5. Stop services
docker-compose down
```

**What this does:**
- Starts LocalStack (local AWS services: DynamoDB, S3, Secrets Manager)
- Builds and runs the backend in a Docker container
- Backend connects to LocalStack automatically
- Accessible at `http://localhost:8080`

---

### Method 2: Using Maven (Direct Java)

Run the application directly with Maven, connecting to LocalStack.

```bash
# 1. Start LocalStack only
docker-compose up -d localstack

# 2. Wait for LocalStack to be ready (about 10-15 seconds)
sleep 15

# 3. Build the application
mvn clean package -DskipTests

# 4. Run with LocalStack endpoint
export DYNAMODB_ENDPOINT=http://localhost:4566
export AWS_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export JWT_SECRET=test-secret-change-in-production
export PLAID_CLIENT_ID=your-plaid-client-id
export PLAID_SECRET=your-plaid-secret
export PLAID_ENVIRONMENT=sandbox

# 5. Run the application
mvn spring-boot:run

# Or run the JAR directly
java -jar target/budgetbuddy-backend-1.0.0.jar
```

**Access the application:**
- API: `http://localhost:8080`
- Health: `http://localhost:8080/actuator/health`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

---

### Method 3: Using IDE (IntelliJ IDEA / Eclipse)

Run directly from your IDE with proper configuration.

**IntelliJ IDEA:**
1. Open `BudgetBuddy-Backend` as a Maven project
2. Start LocalStack: `docker-compose up -d localstack`
3. Create a Run Configuration:
   - **Main class**: `com.budgetbuddy.BudgetBuddyApplication`
   - **VM options**: (none needed)
   - **Environment variables**:
     ```
     DYNAMODB_ENDPOINT=http://localhost:4566
     AWS_REGION=us-east-1
     AWS_ACCESS_KEY_ID=test
     AWS_SECRET_ACCESS_KEY=test
     JWT_SECRET=test-secret-change-in-production
     PLAID_CLIENT_ID=your-plaid-client-id
     PLAID_SECRET=your-plaid-secret
     PLAID_ENVIRONMENT=sandbox
     ```
4. Run the application

**Eclipse:**
1. Import as Maven project
2. Start LocalStack: `docker-compose up -d localstack`
3. Right-click project → Run As → Spring Boot App
4. Configure environment variables in Run Configuration

---

## 🧪 Testing the Backend

### 1. Health Check

```bash
# Check if backend is running
curl http://localhost:8080/actuator/health

# Expected response:
# {"status":"UP"}
```

### 2. API Documentation (Swagger)

```bash
# Open in browser
open http://localhost:8080/swagger-ui.html

# Or view API docs
curl http://localhost:8080/v3/api-docs
```

### 3. Test Authentication Endpoints

```bash
# Register a new user
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "passwordHash": "hashed_password_here",
    "salt": "salt_here",
    "firstName": "Test",
    "lastName": "User"
  }'

# Login
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "passwordHash": "hashed_password_here",
    "salt": "salt_here"
  }'

# Expected response includes JWT token
# {"token":"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...","expiresAt":"2024-..."}
```

### 4. Test Protected Endpoints

```bash
# Get user profile (requires authentication)
TOKEN="your-jwt-token-here"

curl -X GET http://localhost:8080/api/users/profile \
  -H "Authorization: Bearer $TOKEN"
```

### 5. Test Plaid Integration

```bash
# Create Plaid link token
curl -X POST http://localhost:8080/api/plaid/link-token \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user-id-here"
  }'
```

---

## 🗄️ LocalStack Setup

LocalStack provides local AWS services for development.

### Services Available

- **DynamoDB**: `http://localhost:4566`
- **S3**: `http://localhost:4566`
- **Secrets Manager**: `http://localhost:4566`
- **CloudWatch**: `http://localhost:4566`
- **IAM**: `http://localhost:4566`
- **STS**: `http://localhost:4566`

### Create DynamoDB Tables

```bash
# Using AWS CLI with LocalStack endpoint
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1

# Create Users table
aws dynamodb create-table \
  --endpoint-url $AWS_ENDPOINT_URL \
  --table-name BudgetBuddy-Users \
  --attribute-definitions AttributeName=userId,AttributeType=S \
  --key-schema AttributeName=userId,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST

# Create Transactions table
aws dynamodb create-table \
  --endpoint-url $AWS_ENDPOINT_URL \
  --table-name BudgetBuddy-Transactions \
  --attribute-definitions \
    AttributeName=transactionId,AttributeType=S \
    AttributeName=userId,AttributeType=S \
  --key-schema \
    AttributeName=transactionId,KeyType=HASH \
  --global-secondary-indexes \
    IndexName=userId-index,KeySchema=[{AttributeName=userId,KeyType=HASH}],Projection={ProjectionType=ALL} \
  --billing-mode PAY_PER_REQUEST

# Create Accounts table
aws dynamodb create-table \
  --endpoint-url $AWS_ENDPOINT_URL \
  --table-name BudgetBuddy-Accounts \
  --attribute-definitions \
    AttributeName=accountId,AttributeType=S \
    AttributeName=userId,AttributeType=S \
  --key-schema \
    AttributeName=accountId,KeyType=HASH \
  --global-secondary-indexes \
    IndexName=userId-index,KeySchema=[{AttributeName=userId,KeyType=HASH}],Projection={ProjectionType=ALL} \
  --billing-mode PAY_PER_REQUEST

# Create Budgets table
aws dynamodb create-table \
  --endpoint-url $AWS_ENDPOINT_URL \
  --table-name BudgetBuddy-Budgets \
  --attribute-definitions \
    AttributeName=budgetId,AttributeType=S \
    AttributeName=userId,AttributeType=S \
  --key-schema \
    AttributeName=budgetId,KeyType=HASH \
  --global-secondary-indexes \
    IndexName=userId-index,KeySchema=[{AttributeName=userId,KeyType=HASH}],Projection={ProjectionType=ALL} \
  --billing-mode PAY_PER_REQUEST

# Create Goals table
aws dynamodb create-table \
  --endpoint-url $AWS_ENDPOINT_URL \
  --table-name BudgetBuddy-Goals \
  --attribute-definitions \
    AttributeName=goalId,AttributeType=S \
    AttributeName=userId,AttributeType=S \
  --key-schema \
    AttributeName=goalId,KeyType=HASH \
  --global-secondary-indexes \
    IndexName=userId-index,KeySchema=[{AttributeName=userId,KeyType=HASH}],Projection={ProjectionType=ALL} \
  --billing-mode PAY_PER_REQUEST
```

**Note**: The application will automatically create tables on startup if `DynamoDBTableManager` is enabled.

### Verify LocalStack is Running

```bash
# Check LocalStack health
curl http://localhost:4566/_localstack/health

# List DynamoDB tables
aws dynamodb list-tables \
  --endpoint-url http://localhost:4566
```

---

## 🔧 Configuration

### Environment Variables

Create a `.env` file in `BudgetBuddy-Backend/` directory:

```bash
# AWS Configuration
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
DYNAMODB_ENDPOINT=http://localhost:4566

# Application Configuration
SERVER_PORT=8080
ENVIRONMENT=development

# JWT Configuration
JWT_SECRET=test-secret-change-in-production-min-256-bits

# Plaid Configuration (Get from Plaid Dashboard)
PLAID_CLIENT_ID=your-plaid-client-id
PLAID_SECRET=your-plaid-secret
PLAID_ENVIRONMENT=sandbox

# Stripe Configuration (Optional)
STRIPE_SECRET_KEY=sk_test_your_stripe_secret_key

# CORS Configuration
CORS_ALLOWED_ORIGINS=http://localhost:3000,http://localhost:8080

# Logging
AWS_CLOUDWATCH_ENABLED=false  # Disable CloudWatch in local development
```

### Application Properties

The application uses `application.yml` with environment variable overrides. Key settings:

- **DynamoDB Endpoint**: Set via `DYNAMODB_ENDPOINT` (empty for production)
- **JWT Secret**: Set via `JWT_SECRET` (use strong secret in production)
- **Plaid Environment**: Set via `PLAID_ENVIRONMENT` (sandbox/development/production)

---

## 🧪 Running Tests

### Unit Tests

```bash
# Run all unit tests
mvn test

# Run specific test class
mvn test -Dtest=UserServiceTest

# Run tests with coverage
mvn test jacoco:report
```

### Integration Tests

```bash
# Start LocalStack first
docker-compose up -d localstack

# Wait for LocalStack to be ready
sleep 15

# Run integration tests
mvn verify

# Run specific integration test
mvn test -Dtest=*IntegrationTest
```

### All Tests

```bash
# Run the test script
./run-all-tests.sh

# Or manually
mvn clean test verify
```

---

## 🐛 Debugging

### View Logs

```bash
# Docker Compose logs
docker-compose logs -f backend

# LocalStack logs
docker-compose logs -f localstack

# Application logs (if running with Maven)
# Logs appear in console
```

### Common Issues

**1. Port 8080 already in use:**
```bash
# Find process using port 8080
lsof -i :8080

# Kill the process
kill -9 <PID>

# Or change port
export SERVER_PORT=8081
```

**2. LocalStack not ready:**
```bash
# Check LocalStack health
curl http://localhost:4566/_localstack/health

# Restart LocalStack
docker-compose restart localstack

# Wait for it to be ready
sleep 15
```

**3. DynamoDB tables not created:**
```bash
# Check if tables exist
aws dynamodb list-tables --endpoint-url http://localhost:4566

# Application should create tables on startup
# Check application logs for table creation messages
```

**4. Connection refused:**
```bash
# Verify LocalStack is running
docker ps | grep localstack

# Check LocalStack logs
docker-compose logs localstack

# Restart services
docker-compose down
docker-compose up -d
```

---

## 📊 Monitoring

### Health Endpoints

```bash
# Application health
curl http://localhost:8080/actuator/health

# Application info
curl http://localhost:8080/actuator/info

# Metrics (if enabled)
curl http://localhost:8080/actuator/metrics
```

### Swagger UI

Access API documentation at:
- **Swagger UI**: `http://localhost:8080/swagger-ui.html`
- **OpenAPI JSON**: `http://localhost:8080/v3/api-docs`

---

## 🔐 Security Notes

### Local Development

- **JWT Secret**: Use a test secret (not production secret)
- **Plaid**: Use sandbox environment
- **Stripe**: Use test keys
- **CORS**: Allow localhost origins
- **CloudWatch**: Disabled in local development

### Production Differences

- No `DYNAMODB_ENDPOINT` (uses real AWS DynamoDB)
- Secrets from AWS Secrets Manager
- Real Plaid/Stripe credentials
- Restricted CORS origins
- CloudWatch enabled

---

## 🚀 Next Steps

1. **Test API Endpoints**: Use Swagger UI or curl commands
2. **Run Tests**: Execute unit and integration tests
3. **Connect iOS App**: Point iOS app to `http://localhost:8080`
4. **Debug Issues**: Check logs and health endpoints
5. **Deploy to AWS**: Follow `AWS_BACKEND_DEPLOYMENT_GUIDE.md`

---

## 📚 Additional Resources

- **API Documentation**: `http://localhost:8080/swagger-ui.html`
- **Architecture**: See `ARCHITECTURE.md`
- **Deployment Guide**: See `AWS_BACKEND_DEPLOYMENT_GUIDE.md`
- **Testing Guide**: See `EXECUTION_GUIDE.md`

---

## ✅ Quick Reference

```bash
# Start everything
docker-compose up -d

# Check health
curl http://localhost:8080/actuator/health

# View logs
docker-compose logs -f backend

# Stop everything
docker-compose down

# Run tests
mvn test

# Run application with Maven
mvn spring-boot:run
```

---

**Happy Testing! 🎉**

