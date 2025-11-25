# BudgetBuddy Backend - Enterprise Cloud Service

## Overview
BudgetBuddy Backend is an Amazon-class enterprise-ready cloud service built on AWS with:
- **99.99% Availability** with multi-AZ deployment
- **Zero-Downtime Deployments** with blue/green deployments
- **Enterprise Security** with PCI-DSS, SOC2, HIPAA, ISO27001 compliance
- **Cost Optimized** with Graviton2 (ARM64) and on-demand billing
- **Fully Automated** with Infrastructure as Code and CI/CD

## Quick Start

### Prerequisites
- AWS CLI v2 installed and configured
- Docker installed
- Maven 3.9+
- Java 17+
- Git

### Local Development
```bash
# 1. Clone repository
git clone https://github.com/your-org/BudgetBuddy-Backend.git
cd BudgetBuddy-Backend

# 2. Start LocalStack (for local AWS services)
docker-compose up -d

# 3. Build application
mvn clean package

# 4. Run application
java -jar target/budgetbuddy-backend-1.0.0.jar
```

### Production Deployment
```bash
# 1. Set AWS credentials
export AWS_PROFILE=budgetbuddy-production

# 2. Deploy infrastructure
./infrastructure/scripts/deploy.sh production us-east-1

# 3. Verify deployment
aws ecs describe-services \
  --cluster budgetbuddy-cluster \
  --services budgetbuddy-backend \
  --region us-east-1
```

## Architecture

### Technology Stack
- **Runtime**: Java 17 on ARM64/Graviton2
- **Framework**: Spring Boot 3.2
- **Container**: Docker with multi-stage builds
- **Orchestration**: ECS Fargate
- **Database**: DynamoDB (on-demand)
- **Load Balancer**: Application Load Balancer
- **Monitoring**: CloudWatch, CloudTrail
- **CI/CD**: CodePipeline, CodeBuild

### Key Features
- **Multi-AZ Deployment**: 3 availability zones
- **Auto-Scaling**: CPU and memory-based scaling
- **Health Checks**: Application and container health monitoring
- **Circuit Breakers**: Resilience4j for external services
- **Comprehensive Logging**: CloudWatch Logs integration
- **Security**: IAM roles, Secrets Manager, KMS encryption

## Infrastructure Components

### CloudFormation Stacks
1. **main-stack.yaml**: Core infrastructure (VPC, ALB, ECS, IAM)
2. **ecs-service.yaml**: ECS service definition
3. **pipeline.yaml**: CI/CD pipeline configuration

### Key Resources
- **VPC**: Multi-AZ VPC with public/private subnets
- **ECS Cluster**: Fargate cluster with Container Insights
- **ALB**: Application Load Balancer with SSL termination
- **DynamoDB**: On-demand tables for all data
- **CloudWatch**: Metrics, logs, alarms, dashboards
- **CodePipeline**: Automated CI/CD pipeline

## Configuration

### Environment Variables
```bash
AWS_REGION=us-east-1
ENVIRONMENT=production
DYNAMODB_TABLE_PREFIX=BudgetBuddy
AWS_CLOUDWATCH_ENABLED=true
JWT_SECRET=<from-secrets-manager>
PLAID_CLIENT_ID=<from-secrets-manager>
PLAID_SECRET=<from-secrets-manager>
STRIPE_SECRET_KEY=<from-secrets-manager>
```

### Secrets Management
All secrets stored in AWS Secrets Manager:
- `budgetbuddy/jwt-secret`
- `budgetbuddy/plaid:clientId`
- `budgetbuddy/plaid:secret`
- `budgetbuddy/stripe:secretKey`

## Monitoring

### CloudWatch Dashboards
- **Application Dashboard**: Request metrics, error rates, response times
- **Infrastructure Dashboard**: CPU, memory, network metrics
- **Compliance Dashboard**: PCI-DSS, SOC2, HIPAA metrics

### Key Metrics
- CPU Utilization (target: < 70%)
- Memory Utilization (target: < 70%)
- Request Count
- Error Rate (target: < 1%)
- Response Time (p95 target: < 500ms)

### Alarms
- High CPU (> 80%)
- High Memory (> 80%)
- High Error Rate (> 1%)
- DynamoDB Throttling

## Security

### Network Security
- ECS tasks in private subnets
- Security groups with least privilege
- VPC Flow Logs enabled
- ALB with SSL termination

### Application Security
- TLS 1.2+ for all connections
- Certificate pinning
- JWT authentication
- Rate limiting
- Input validation

### Compliance
- **PCI-DSS**: Card data handling
- **SOC 2**: Control activities and monitoring
- **HIPAA**: PHI protection
- **ISO 27001**: Security management
- **GDPR**: Data protection and portability

## Cost Optimization

### Compute
- **Graviton2 (ARM64)**: 20% cost savings
- **Fargate Spot**: 70% savings (optional)
- **Auto-Scaling**: Scale down during low usage

### Storage
- **DynamoDB On-Demand**: Pay per request
- **S3 Lifecycle**: Automatic archival
- **Log Retention**: 30 days, then archive

### Network
- **VPC Endpoints**: Reduce NAT Gateway costs
- **CloudFront**: Reduce data transfer costs

## CI/CD Pipeline

### Pipeline Stages
1. **Source**: GitHub repository
2. **Build**: Docker image build and push to ECR
3. **Deploy**: ECS service update

### Automated Deployments
- Push to `main` branch triggers production deployment
- Push to `staging` branch triggers staging deployment
- Manual approval required for production (optional)

## Documentation

- **ARCHITECTURE.md**: System architecture and design
- **OPERATIONAL_RUNBOOK.md**: Operational procedures
- **COMPLIANCE_IMPLEMENTATION_SUMMARY.md**: Compliance details
- **API Documentation**: OpenAPI/Swagger specs (at `/swagger-ui.html`)

## Support

### Incident Response
- **Critical**: ops@budgetbuddy.com
- **General**: support@budgetbuddy.com
- **Security**: security@budgetbuddy.com

### Resources
- **Runbook**: See OPERATIONAL_RUNBOOK.md
- **Architecture**: See ARCHITECTURE.md
- **Compliance**: See COMPLIANCE_IMPLEMENTATION_SUMMARY.md

## License
Proprietary - All rights reserved
# BudgetBuddy-backend
