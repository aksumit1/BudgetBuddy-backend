# BudgetBuddy Backend - Cost Optimization Guide

## Current Cost Structure

### Monthly Cost Estimates (Production)
- **ECS Fargate**: ~$150/month (2 tasks, 0.25 vCPU, 512MB each)
- **DynamoDB**: ~$50/month (on-demand, ~1M requests)
- **ALB**: ~$20/month (standard ALB)
- **NAT Gateway**: ~$35/month (2 NAT gateways)
- **CloudWatch**: ~$30/month (logs, metrics, alarms)
- **S3**: ~$10/month (storage, archival)
- **Data Transfer**: ~$20/month
- **Total**: ~$315/month

### Cost Optimization Strategies

## 1. Compute Optimization

### Graviton2 (ARM64)
- **Savings**: 20% on compute costs
- **Implementation**: Already implemented in Dockerfile
- **Monthly Savings**: ~$30

### Fargate Spot (Optional)
- **Savings**: Up to 70% on compute
- **Use Case**: Non-critical workloads, staging environment
- **Risk**: Tasks can be interrupted
- **Monthly Savings**: ~$105 (if used for 50% of workload)

### Right-Sizing
- **Current**: 256 CPU units, 512MB memory
- **Optimization**: Monitor and adjust based on actual usage
- **Monthly Savings**: ~$20 (if can reduce to 128 CPU, 256MB)

## 2. Storage Optimization

### DynamoDB On-Demand
- **Current**: On-demand billing (already optimized)
- **Alternative**: Provisioned capacity (only if predictable workload)
- **Savings**: None (on-demand is optimal for variable workloads)

### S3 Lifecycle Policies
- **Current**: 30-day retention, then archive
- **Optimization**: 
  - Move to Standard-IA after 30 days
  - Move to Glacier after 90 days
- **Monthly Savings**: ~$5

### ECR Lifecycle
- **Current**: Keep last 10 images
- **Optimization**: Already optimized
- **Savings**: Minimal

## 3. Network Optimization

### VPC Endpoints
- **Current**: NAT Gateway for AWS service access
- **Optimization**: Use VPC endpoints for DynamoDB, S3
- **Cost**: VPC endpoints cost ~$7/month per endpoint
- **Savings**: ~$28/month (eliminate NAT Gateway usage for AWS services)

### CloudFront (Optional)
- **Use Case**: Static content, API caching
- **Savings**: Reduce data transfer costs
- **Monthly Savings**: ~$10

## 4. Monitoring Optimization

### CloudWatch Logs Retention
- **Current**: 30-day retention
- **Optimization**: 
  - Reduce to 7 days for non-critical logs
  - Archive to S3 after 7 days
- **Monthly Savings**: ~$15

### Metric Filtering
- **Current**: All metrics sent to CloudWatch
- **Optimization**: Filter unnecessary metrics
- **Monthly Savings**: ~$5

## 5. Reserved Capacity (Future)

### Reserved Instances
- **Use Case**: Predictable, steady-state workloads
- **Savings**: Up to 75% discount
- **Commitment**: 1-3 years
- **Recommendation**: Only consider after 6 months of stable usage

## Cost Optimization Checklist

### Immediate Actions
- [x] Use Graviton2 (ARM64) processors
- [x] Use DynamoDB on-demand billing
- [x] Enable S3 lifecycle policies
- [x] Set CloudWatch log retention to 30 days
- [x] Use ECR lifecycle policies

### Short-Term (1-3 months)
- [ ] Implement VPC endpoints for DynamoDB and S3
- [ ] Reduce CloudWatch log retention to 7 days
- [ ] Optimize container resource allocation
- [ ] Enable Fargate Spot for staging environment
- [ ] Implement CloudFront for API caching

### Long-Term (3-6 months)
- [ ] Analyze usage patterns
- [ ] Consider Reserved Instances if workload is predictable
- [ ] Implement cost allocation tags
- [ ] Set up AWS Budgets with alerts
- [ ] Regular cost reviews

## Cost Monitoring

### AWS Cost Explorer
- **Dashboard**: Daily cost reports
- **Alerts**: 80% and 100% of budget
- **Tags**: Environment, Service, Team

### Cost Allocation Tags
- **Environment**: production, staging, development
- **Service**: budgetbuddy-backend
- **Team**: engineering, operations
- **Cost Center**: finance, engineering

### Budget Alerts
```bash
# Create budget
aws budgets create-budget \
  --account-id $(aws sts get-caller-identity --query Account --output text) \
  --budget file://budget.json \
  --notifications-with-subscribers file://notifications.json
```

## Cost Optimization Targets

### Current Monthly Cost
- **Target**: < $400/month
- **Current**: ~$315/month
- **Status**: ✅ On target

### After Optimizations
- **Target**: < $250/month
- **Potential Savings**: ~$65/month (20% reduction)

## Cost Breakdown by Service

| Service | Current Cost | Optimized Cost | Savings |
|---------|-------------|----------------|---------|
| ECS Fargate | $150 | $100 | $50 |
| DynamoDB | $50 | $50 | $0 |
| ALB | $20 | $20 | $0 |
| NAT Gateway | $35 | $7 | $28 |
| CloudWatch | $30 | $15 | $15 |
| S3 | $10 | $5 | $5 |
| Data Transfer | $20 | $10 | $10 |
| **Total** | **$315** | **$207** | **$108** |

## Recommendations

### High Priority
1. **Implement VPC Endpoints**: $28/month savings
2. **Reduce CloudWatch Log Retention**: $15/month savings
3. **Optimize Container Sizing**: $20/month savings

### Medium Priority
1. **Enable Fargate Spot for Staging**: $50/month savings
2. **Implement CloudFront**: $10/month savings
3. **Filter Unnecessary Metrics**: $5/month savings

### Low Priority
1. **Reserved Instances**: Evaluate after 6 months
2. **Cost Allocation Tags**: For better visibility
3. **AWS Budgets**: For proactive cost management

## Cost Optimization Scripts

### Monitor Costs
```bash
# Get current month costs
aws ce get-cost-and-usage \
  --time-period Start=$(date -u -d '1 month ago' +%Y-%m-01),End=$(date -u +%Y-%m-01) \
  --granularity MONTHLY \
  --metrics BlendedCost \
  --group-by Type=DIMENSION,Key=SERVICE
```

### Analyze DynamoDB Costs
```bash
# Get DynamoDB costs
aws ce get-cost-and-usage \
  --time-period Start=$(date -u -d '1 month ago' +%Y-%m-01),End=$(date -u +%Y-%m-01) \
  --granularity MONTHLY \
  --metrics BlendedCost \
  --filter file://dynamodb-filter.json
```

