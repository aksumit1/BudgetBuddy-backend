# DistilBERT Infrastructure as Code Setup

This document describes the complete Infrastructure as Code (IaC) setup for automatically downloading, storing, and deploying the DistilBERT ONNX model.

---

## 🏗️ Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    Infrastructure Setup                      │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  1. Local Development                                        │
│     └─> scripts/download-bert-model.sh                     │
│         Downloads model to models/ directory                 │
│                                                              │
│  2. Build Time (Docker/CI)                                   │
│     └─> buildspec.yaml / Dockerfile                          │
│         Downloads model during build (optional)               │
│                                                              │
│  3. Runtime (ECS/Docker)                                    │
│     └─> docker-entrypoint.sh                                │
│         Downloads from S3 if not present locally             │
│                                                              │
│  4. Cloud Storage (AWS S3)                                   │
│     └─> CloudFormation: bert-model-storage.yaml             │
│         S3 bucket with versioning and lifecycle             │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 📋 Setup Steps

### Step 1: Local Development Setup

**Option A: Automatic Download (Recommended)**
```bash
# Make script executable
chmod +x scripts/download-bert-model.sh

# Download model
./scripts/download-bert-model.sh models
```

**Option B: Complete Infrastructure Setup**
```bash
# Setup S3 bucket and upload model
chmod +x scripts/setup-bert-infrastructure.sh
./scripts/setup-bert-infrastructure.sh
```

The model will be downloaded to: `models/distilbert-base-uncased.onnx`

---

### Step 2: Configure Application

Add to `application.properties`:
```properties
# Enable BERT
bert.enabled=true

# Local model path (for development)
bert.model.path=models/distilbert-base-uncased.onnx
```

---

### Step 3: Deploy Cloud Infrastructure

**Deploy BERT Model S3 Bucket:**
```bash
cd infrastructure/cloudformation

aws cloudformation create-stack \
  --stack-name budgetbuddy-bert-models-${ENVIRONMENT} \
  --template-body file://bert-model-storage.yaml \
  --parameters \
    ParameterKey=Environment,ParameterValue=${ENVIRONMENT} \
    ParameterKey=ECSTaskRoleArn,ParameterValue=${ECS_TASK_ROLE_ARN} \
  --capabilities CAPABILITY_NAMED_IAM
```

**Upload Model to S3:**
```bash
# Get bucket name from CloudFormation output
BUCKET_NAME=$(aws cloudformation describe-stacks \
  --stack-name budgetbuddy-bert-models-${ENVIRONMENT} \
  --query 'Stacks[0].Outputs[?OutputKey==`BucketName`].OutputValue' \
  --output text)

# Upload model
aws s3 cp models/distilbert-base-uncased.onnx \
  s3://${BUCKET_NAME}/distilbert-base-uncased.onnx
```

---

### Step 4: Configure ECS Task Definition

Add environment variables to ECS task definition:
```json
{
  "environment": [
    {
      "name": "BERT_MODEL_BUCKET",
      "value": "budgetbuddy-bert-models-production"
    },
    {
      "name": "BERT_MODEL_S3_PATH",
      "value": "distilbert-base-uncased.onnx"
    }
  ]
}
```

Ensure ECS task role has S3 read permissions (configured via CloudFormation).

---

## 🔧 Build Integration

### Maven Build

The model download is integrated into the build process:

**buildspec.yaml** (AWS CodeBuild):
- Downloads model during pre_build phase
- Model is included in Docker image (if download succeeds)

**Dockerfile**:
- Includes download script
- Can download model during build (commented out by default)
- Downloads from S3 at runtime if `BERT_MODEL_BUCKET` is set

---

## 🚀 Deployment Strategies

### Strategy 1: Model in Docker Image (Recommended for Development)

**Pros:**
- ✅ No S3 dependency at runtime
- ✅ Faster container startup
- ✅ Works offline

**Cons:**
- ❌ Larger Docker image (~250MB)
- ❌ Model update requires image rebuild

**Implementation:**
Uncomment in Dockerfile:
```dockerfile
RUN ./scripts/download-bert-model.sh models || echo "Model download skipped"
```

---

### Strategy 2: Model from S3 at Runtime (Recommended for Production)

**Pros:**
- ✅ Smaller Docker image
- ✅ Model updates without image rebuild
- ✅ Version control via S3 versioning

**Cons:**
- ❌ Requires S3 access at startup
- ❌ Slight startup delay (~5-10 seconds)

**Implementation:**
1. Set `BERT_MODEL_BUCKET` environment variable
2. Model downloads automatically via `docker-entrypoint.sh`

---

### Strategy 3: Hybrid (Model in Image + S3 Fallback)

**Pros:**
- ✅ Fast startup (model in image)
- ✅ Fallback to S3 if model missing
- ✅ Best of both worlds

**Implementation:**
- Include model in Docker image
- `docker-entrypoint.sh` checks for model, downloads from S3 if missing

---

## 📦 CloudFormation Resources

### BERT Model Storage Stack

**File:** `infrastructure/cloudformation/bert-model-storage.yaml`

**Resources:**
- **S3 Bucket**: `budgetbuddy-bert-models-${Environment}`
- **Bucket Policy**: Allows ECS task role read access
- **Versioning**: Enabled (for model version management)
- **Lifecycle**: Deletes old versions after 30 days
- **Encryption**: AES256 server-side encryption

**Outputs:**
- `BucketName`: S3 bucket name
- `BucketArn`: S3 bucket ARN
- `ModelS3Path`: Full S3 path to model

---

## 🔐 Security

### IAM Permissions

**ECS Task Role** needs:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:GetObjectVersion"
      ],
      "Resource": "arn:aws:s3:::budgetbuddy-bert-models-*/*"
    },
    {
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::budgetbuddy-bert-models-*"
    }
  ]
}
```

This is automatically configured via CloudFormation bucket policy.

---

## 🧪 Testing

### Test Local Download
```bash
# Test download script
./scripts/download-bert-model.sh models

# Verify model exists
ls -lh models/distilbert-base-uncased.onnx
```

### Test S3 Upload/Download
```bash
# Upload
aws s3 cp models/distilbert-base-uncased.onnx \
  s3://budgetbuddy-bert-models-dev/distilbert-base-uncased.onnx

# Download
aws s3 cp s3://budgetbuddy-bert-models-dev/distilbert-base-uncased.onnx \
  /tmp/test-model.onnx

# Verify
ls -lh /tmp/test-model.onnx
```

### Test Docker Build
```bash
# Build with model download
docker build -t budgetbuddy-backend:test .

# Run with S3 download
docker run -e BERT_MODEL_BUCKET=budgetbuddy-bert-models-dev \
  budgetbuddy-backend:test
```

---

## 📊 Cost Optimization

### S3 Storage Costs

**Model Size:** ~250MB
**Storage Class:** Standard (frequent access needed)

**Monthly Cost:**
- Storage: ~$0.006/month (250MB × $0.023/GB)
- Requests: ~$0.0004/month (1000 GET requests × $0.0004/1000)

**Total:** ~$0.01/month per environment

### Lifecycle Optimization

- **Versioning**: Enabled (for rollback capability)
- **Old Version Deletion**: After 30 days (saves storage costs)
- **Incomplete Upload Cleanup**: After 1 day

---

## 🔄 Model Updates

### Update Process

1. **Download New Model:**
   ```bash
   ./scripts/download-bert-model.sh models
   ```

2. **Upload to S3:**
   ```bash
   aws s3 cp models/distilbert-base-uncased.onnx \
     s3://budgetbuddy-bert-models-${ENVIRONMENT}/distilbert-base-uncased.onnx
   ```

3. **ECS Tasks:**
   - Automatically download new version on next restart
   - Or restart tasks to force download:
     ```bash
     aws ecs update-service --cluster <cluster> --service <service> --force-new-deployment
     ```

---

## 🐛 Troubleshooting

### Model Not Found

**Error:** `DistilBERT ONNX model not found at: models/distilbert-base-uncased.onnx`

**Solutions:**
1. Run download script: `./scripts/download-bert-model.sh models`
2. Check S3 bucket: `aws s3 ls s3://budgetbuddy-bert-models-${ENV}/`
3. Verify `BERT_MODEL_BUCKET` environment variable is set

### S3 Access Denied

**Error:** `Access Denied when downloading from S3`

**Solutions:**
1. Verify ECS task role has S3 permissions
2. Check CloudFormation bucket policy
3. Verify bucket name matches environment variable

### Download Script Fails

**Error:** `Model download failed`

**Solutions:**
1. Install Python dependencies: `pip install optimum[onnxruntime] transformers`
2. Or use huggingface-cli: `pip install huggingface_hub`
3. Or download manually from HuggingFace and place in `models/` directory

---

## 📝 Summary

✅ **Infrastructure as Code**: Complete automation via scripts and CloudFormation
✅ **Multiple Deployment Strategies**: Image-based, S3-based, or hybrid
✅ **Cost Optimized**: Minimal S3 costs (~$0.01/month)
✅ **Secure**: IAM-based access control
✅ **Versioned**: S3 versioning for model rollback
✅ **Automated**: Downloads happen automatically during build/runtime

The infrastructure is **fully automated** - just run the setup scripts and deploy CloudFormation templates!
