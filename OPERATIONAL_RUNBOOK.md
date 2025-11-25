# BudgetBuddy Backend - Operational Runbook

## Table of Contents
1. [Architecture Overview](#architecture-overview)
2. [Deployment Procedures](#deployment-procedures)
3. [Monitoring & Alerting](#monitoring--alerting)
4. [Incident Response](#incident-response)
5. [Scaling Procedures](#scaling-procedures)
6. [Backup & Recovery](#backup--recovery)
7. [Security Procedures](#security-procedures)
8. [Cost Optimization](#cost-optimization)

## Architecture Overview

### Infrastructure Components
- **VPC**: Multi-AZ VPC with public and private subnets
- **ECS Fargate**: Container orchestration (ARM64/Graviton2)
- **Application Load Balancer**: High availability load balancing
- **DynamoDB**: Primary data store (on-demand billing)
- **CloudWatch**: Monitoring, logging, and alerting
- **CloudTrail**: API activity logging
- **CodePipeline**: CI/CD automation
- **S3**: Artifact storage and log archival

### High Availability Design
- **Multi-AZ Deployment**: Services deployed across 3 availability zones
- **Auto-Scaling**: Automatic scaling based on CPU/memory utilization
- **Health Checks**: Application and container health monitoring
- **Circuit Breakers**: Resilience4j for external service calls
- **Load Balancing**: ALB with health check-based routing

## Deployment Procedures

### Initial Deployment
```bash
# 1. Set up AWS credentials
export AWS_PROFILE=budgetbuddy-production

# 2. Deploy infrastructure
./infrastructure/scripts/deploy.sh production us-east-1

# 3. Verify deployment
aws ecs describe-services \
  --cluster budgetbuddy-cluster \
  --services budgetbuddy-backend \
  --region us-east-1
```

### Rolling Updates
- **Zero-Downtime**: ECS blue/green deployments
- **Health Checks**: Automatic rollback on health check failures
- **Canary Deployments**: Gradual traffic shifting (optional)

### Rollback Procedure
```bash
# 1. Identify previous task definition
PREVIOUS_TASK_DEF=$(aws ecs describe-services \
  --cluster budgetbuddy-cluster \
  --services budgetbuddy-backend \
  --query 'services[0].deployments[?status==`PRIMARY`].taskDefinition' \
  --output text)

# 2. Update service to previous task definition
aws ecs update-service \
  --cluster budgetbuddy-cluster \
  --service budgetbuddy-backend \
  --task-definition ${PREVIOUS_TASK_DEF} \
  --force-new-deployment
```

## Monitoring & Alerting

### Key Metrics
- **CPU Utilization**: Target < 70%, Alert > 80%
- **Memory Utilization**: Target < 70%, Alert > 80%
- **Request Count**: Monitor for anomalies
- **Error Rate**: Alert if > 1%
- **Response Time**: Alert if p95 > 1s
- **DynamoDB Throttling**: Alert on any throttling events

### CloudWatch Dashboards
- **Application Dashboard**: `/aws/ecs/budgetbuddy-cluster`
- **Compliance Dashboard**: PCI-DSS, SOC2, HIPAA metrics
- **Cost Dashboard**: Resource utilization and costs

### Alert Channels
- **Email**: ops@budgetbuddy.com
- **SNS**: budgetbuddy-alarms topic
- **PagerDuty**: Critical alerts (optional)

## Incident Response

### Severity Levels
1. **Critical**: Service down, data breach, security incident
2. **High**: Performance degradation, high error rate
3. **Medium**: Non-critical errors, capacity issues
4. **Low**: Minor issues, optimization opportunities

### Incident Response Procedure
1. **Detect**: Monitor alerts and dashboards
2. **Assess**: Determine severity and impact
3. **Contain**: Isolate affected components
4. **Resolve**: Fix root cause
5. **Recover**: Restore normal operations
6. **Post-Mortem**: Document and improve

### Common Issues & Solutions

#### High CPU Utilization
```bash
# 1. Check current CPU usage
aws cloudwatch get-metric-statistics \
  --namespace AWS/ECS \
  --metric-name CPUUtilization \
  --dimensions Name=ServiceName,Value=budgetbuddy-backend \
  --start-time $(date -u -d '1 hour ago' +%Y-%m-%dT%H:%M:%S) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
  --period 300 \
  --statistics Average

# 2. Scale up service
aws application-autoscaling register-scalable-target \
  --service-namespace ecs \
  --scalable-dimension ecs:service:DesiredCount \
  --resource-id service/budgetbuddy-cluster/budgetbuddy-backend \
  --min-capacity 4 \
  --max-capacity 20
```

#### High Error Rate
```bash
# 1. Check error logs
aws logs tail /aws/ecs/budgetbuddy-cluster --follow --filter-pattern "ERROR"

# 2. Check application metrics
aws cloudwatch get-metric-statistics \
  --namespace BudgetBuddy \
  --metric-name ErrorCount \
  --start-time $(date -u -d '1 hour ago' +%Y-%m-%dT%H:%M:%S) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
  --period 300 \
  --statistics Sum
```

#### DynamoDB Throttling
```bash
# 1. Check throttling events
aws cloudwatch get-metric-statistics \
  --namespace AWS/DynamoDB \
  --metric-name UserErrors \
  --dimensions Name=TableName,Value=BudgetBuddy-Transactions \
  --start-time $(date -u -d '1 hour ago' +%Y-%m-%dT%H:%M:%S) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
  --period 300 \
  --statistics Sum

# 2. Enable on-demand billing (if using provisioned)
aws dynamodb update-table \
  --table-name BudgetBuddy-Transactions \
  --billing-mode PAY_PER_REQUEST
```

## Scaling Procedures

### Manual Scaling
```bash
# Scale service to specific count
aws ecs update-service \
  --cluster budgetbuddy-cluster \
  --service budgetbuddy-backend \
  --desired-count 10
```

### Auto-Scaling Configuration
- **Target CPU**: 70%
- **Target Memory**: 70%
- **Min Capacity**: 2 tasks
- **Max Capacity**: 20 tasks (production), 5 tasks (staging)
- **Scale Out Cooldown**: 60 seconds
- **Scale In Cooldown**: 300 seconds

## Backup & Recovery

### DynamoDB Backups
- **Point-in-Time Recovery**: Enabled for all tables
- **On-Demand Backups**: Daily at 2 AM UTC
- **Retention**: 35 days

### Backup Procedure
```bash
# Create on-demand backup
aws dynamodb create-backup \
  --table-name BudgetBuddy-Users \
  --backup-name users-backup-$(date +%Y%m%d)
```

### Recovery Procedure
```bash
# Restore from backup
aws dynamodb restore-table-from-backup \
  --target-table-name BudgetBuddy-Users-Restored \
  --backup-arn arn:aws:dynamodb:us-east-1:ACCOUNT:table/BudgetBuddy-Users/backup/BACKUP_ID
```

## Security Procedures

### Security Monitoring
- **CloudTrail**: All API calls logged
- **VPC Flow Logs**: Network traffic monitoring
- **GuardDuty**: Threat detection (optional)
- **Security Hub**: Centralized security findings (optional)

### Security Incident Response
1. **Isolate**: Disable affected IAM roles/users
2. **Investigate**: Review CloudTrail logs
3. **Contain**: Update security groups, revoke credentials
4. **Remediate**: Fix vulnerabilities
5. **Notify**: Alert security team and stakeholders

### Compliance Audits
- **Monthly**: Review CloudTrail logs
- **Quarterly**: SOC2 compliance review
- **Annually**: Full security audit

## Cost Optimization

### Cost Monitoring
- **AWS Cost Explorer**: Daily cost reports
- **Cost Allocation Tags**: Environment, Service, Team
- **Budget Alerts**: 80% and 100% thresholds

### Optimization Strategies
1. **Graviton2**: 20% cost savings on compute
2. **Fargate Spot**: Up to 70% savings for non-critical workloads
3. **DynamoDB On-Demand**: Pay only for what you use
4. **S3 Lifecycle Policies**: Automatic archival to cheaper storage
5. **Reserved Capacity**: For predictable workloads (optional)

### Cost Reduction Checklist
- [ ] Enable Fargate Spot for non-production
- [ ] Archive old CloudWatch logs to S3
- [ ] Enable S3 Intelligent-Tiering
- [ ] Review and remove unused resources
- [ ] Optimize container resource allocation

## Performance Tuning

### Application Performance
- **JVM Tuning**: `-XX:MaxRAMPercentage=75.0`
- **Connection Pooling**: Optimize database connections
- **Caching**: Use DynamoDB caching for frequently accessed data
- **Batch Operations**: Batch DynamoDB writes

### Infrastructure Performance
- **ALB Idle Timeout**: 60 seconds
- **Container Health Checks**: 30-second intervals
- **Auto-Scaling**: Proactive scaling based on metrics

## Disaster Recovery

### RTO/RPO Targets
- **RTO**: 1 hour (Recovery Time Objective)
- **RPO**: 15 minutes (Recovery Point Objective)

### DR Procedures
1. **Failover**: Switch to secondary region (if configured)
2. **Restore**: Restore from backups
3. **Validate**: Verify data integrity
4. **Communicate**: Notify stakeholders

## Maintenance Windows

### Scheduled Maintenance
- **Weekly**: Security updates, dependency updates
- **Monthly**: Infrastructure updates, capacity planning
- **Quarterly**: Major version upgrades

### Maintenance Procedure
1. **Notify**: Alert users 48 hours in advance
2. **Backup**: Create backups before maintenance
3. **Deploy**: Apply updates in staging first
4. **Validate**: Verify functionality
5. **Monitor**: Watch for issues post-deployment

