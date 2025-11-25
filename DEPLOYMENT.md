# BudgetBuddy Backend - Deployment Guide

## AWS Deployment

### Prerequisites
- AWS Account
- AWS CLI configured
- Docker installed
- Terraform (optional, for infrastructure as code)

### Infrastructure Setup

#### 1. RDS PostgreSQL
```bash
# Create RDS instance (use AWS Console or CLI)
aws rds create-db-instance \
  --db-instance-identifier budgetbuddy-db \
  --db-instance-class db.t3.micro \
  --engine postgres \
  --master-username budgetbuddy \
  --master-user-password <password> \
  --allocated-storage 20 \
  --storage-type gp2
```

#### 2. ElastiCache Redis
```bash
# Create Redis cluster
aws elasticache create-cache-cluster \
  --cache-cluster-id budgetbuddy-redis \
  --cache-node-type cache.t3.micro \
  --engine redis \
  --num-cache-nodes 1
```

#### 3. S3 Bucket
```bash
# Create S3 bucket
aws s3 mb s3://budgetbuddy-storage

# Configure lifecycle policy for cost optimization
aws s3api put-bucket-lifecycle-configuration \
  --bucket budgetbuddy-storage \
  --lifecycle-configuration file://s3-lifecycle.json
```

#### 4. Secrets Manager
```bash
# Store Plaid credentials
aws secretsmanager create-secret \
  --name budgetbuddy/plaid \
  --secret-string '{"clientId":"...","secret":"..."}'

# Store JWT secret
aws secretsmanager create-secret \
  --name budgetbuddy/jwt-secret \
  --secret-string "<your-256-bit-secret>"
```

### Application Deployment

#### Option 1: ECS (Elastic Container Service)
```bash
# Build and push Docker image
docker build -t budgetbuddy-backend .
docker tag budgetbuddy-backend:latest <account>.dkr.ecr.<region>.amazonaws.com/budgetbuddy-backend:latest
docker push <account>.dkr.ecr.<region>.amazonaws.com/budgetbuddy-backend:latest

# Deploy to ECS
aws ecs update-service --cluster budgetbuddy-cluster --service budgetbuddy-backend --force-new-deployment
```

#### Option 2: EC2
```bash
# SSH into EC2 instance
ssh -i key.pem ec2-user@<instance-ip>

# Pull and run Docker container
docker pull <account>.dkr.ecr.<region>.amazonaws.com/budgetbuddy-backend:latest
docker run -d -p 8080:8080 \
  -e DB_HOST=<rds-endpoint> \
  -e DB_PASSWORD=<password> \
  -e REDIS_HOST=<redis-endpoint> \
  -e JWT_SECRET=<secret> \
  <image>
```

#### Option 3: Elastic Beanstalk
```bash
# Create application
eb init -p docker budgetbuddy-backend

# Create environment
eb create budgetbuddy-env

# Deploy
eb deploy
```

### Environment Variables

Set these in your deployment environment:

```bash
# Database
DB_HOST=<rds-endpoint>
DB_PORT=5432
DB_USERNAME=budgetbuddy
DB_PASSWORD=<password>

# Redis
REDIS_HOST=<redis-endpoint>
REDIS_PORT=6379

# JWT
JWT_SECRET=<256-bit-secret>

# Plaid
PLAID_CLIENT_ID=<client-id>
PLAID_SECRET=<secret>
PLAID_ENVIRONMENT=production

# AWS
AWS_REGION=us-east-1
AWS_S3_BUCKET=budgetbuddy-storage
AWS_CLOUDWATCH_ENABLED=true
```

### Cost Optimization Checklist

- [ ] Use RDS Reserved Instances (40% savings)
- [ ] Configure S3 lifecycle policies
- [ ] Use ElastiCache Reserved Nodes
- [ ] Enable CloudWatch log retention (30 days)
- [ ] Set up auto-scaling for ECS/EC2
- [ ] Use Spot Instances for non-critical workloads
- [ ] Configure CloudFront for static assets
- [ ] Enable S3 Intelligent-Tiering
- [ ] Set up billing alerts

### Monitoring

1. **CloudWatch Dashboard**: Monitor application metrics
2. **RDS Monitoring**: Database performance insights
3. **ElastiCache Metrics**: Cache hit rates
4. **S3 Metrics**: Storage usage and costs
5. **Cost Explorer**: Track spending trends

### Security

1. **VPC**: Deploy in private subnets
2. **Security Groups**: Restrict access
3. **IAM Roles**: Least privilege access
4. **Secrets Manager**: Encrypted secrets
5. **WAF**: Web Application Firewall
6. **SSL/TLS**: HTTPS only

### Backup & Recovery

1. **RDS Automated Backups**: 7-day retention
2. **S3 Versioning**: Enable for critical data
3. **Cross-Region Replication**: For disaster recovery
4. **Database Snapshots**: Before major changes

