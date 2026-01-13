#!/bin/bash
# Docker entrypoint script for BudgetBuddy Backend
# Downloads DistilBERT model from S3 if not present locally
# Includes retry logic, error handling, and race condition protection

set -e

MODEL_DIR="/app/models"
MODEL_FILE="${MODEL_DIR}/distilbert-base-uncased.onnx"
BERT_MODEL_BUCKET="${BERT_MODEL_BUCKET:-}"
BERT_MODEL_S3_KEY="${BERT_MODEL_S3_KEY:-distilbert-base-uncased.onnx}"
BERT_MODEL_S3_ENDPOINT="${BERT_MODEL_S3_ENDPOINT:-}"
MAX_RETRIES="${BERT_DOWNLOAD_MAX_RETRIES:-3}"
RETRY_DELAY="${BERT_DOWNLOAD_RETRY_DELAY:-5}"
DOWNLOAD_TIMEOUT="${BERT_DOWNLOAD_TIMEOUT:-300}"

# Create models directory (with race condition protection)
mkdir -p "${MODEL_DIR}"

# Function to download from S3 with retry logic
download_from_s3() {
    local bucket=$1
    local key=$2
    local endpoint=$3
    local target=$4
    local retry_count=0
    
    local aws_cmd="aws s3 cp"
    if [ -n "${endpoint}" ]; then
        aws_cmd="${aws_cmd} --endpoint-url ${endpoint}"
    fi
    aws_cmd="${aws_cmd} s3://${bucket}/${key} ${target}"
    
    while [ $retry_count -lt $MAX_RETRIES ]; do
        echo "Download attempt $((retry_count + 1))/${MAX_RETRIES}..."
        
        if timeout "${DOWNLOAD_TIMEOUT}" ${aws_cmd} 2>&1; then
            # Verify file was downloaded and is not empty
            if [ -f "${target}" ] && [ -s "${target}" ]; then
                echo "✅ Model downloaded from S3 successfully!"
                echo "   Size: $(du -h "${target}" | cut -f1)"
                return 0
            else
                echo "⚠️  Downloaded file is empty or missing"
                rm -f "${target}"
            fi
        else
            echo "⚠️  Download attempt $((retry_count + 1)) failed"
        fi
        
        retry_count=$((retry_count + 1))
        if [ $retry_count -lt $MAX_RETRIES ]; then
            echo "Waiting ${RETRY_DELAY} seconds before retry..."
            sleep "${RETRY_DELAY}"
        fi
    done
    
    return 1
}

# Function to check if model file is valid (not corrupted)
validate_model_file() {
    local file=$1
    if [ ! -f "${file}" ]; then
        return 1
    fi
    
    # Check file size (model should be at least 1MB)
    local size=$(stat -f%z "${file}" 2>/dev/null || stat -c%s "${file}" 2>/dev/null || echo "0")
    if [ "${size}" -lt 1048576 ]; then
        echo "⚠️  Model file too small (${size} bytes), may be corrupted"
        return 1
    fi
    
    # Check if file is readable
    if [ ! -r "${file}" ]; then
        echo "⚠️  Model file is not readable"
        return 1
    fi
    
    return 0
}

# Download model from S3 if bucket is configured and model doesn't exist or is invalid
if [ -n "${BERT_MODEL_BUCKET}" ]; then
    # Check if model exists and is valid
    if ! validate_model_file "${MODEL_FILE}"; then
        echo "=========================================="
        echo "Downloading DistilBERT model from S3"
        echo "=========================================="
        echo "Bucket: ${BERT_MODEL_BUCKET}"
        echo "Key: ${BERT_MODEL_S3_KEY}"
        echo "Endpoint: ${BERT_MODEL_S3_ENDPOINT:-default}"
        echo "Target: ${MODEL_FILE}"
        echo "Max Retries: ${MAX_RETRIES}"
        echo ""
        
        # Try to download from S3
        if command -v aws &> /dev/null; then
            if download_from_s3 "${BERT_MODEL_BUCKET}" "${BERT_MODEL_S3_KEY}" \
                               "${BERT_MODEL_S3_ENDPOINT}" "${MODEL_FILE}"; then
                # Validate downloaded file
                if validate_model_file "${MODEL_FILE}"; then
                    echo "✅ Model validated successfully"
                else
                    echo "⚠️  Downloaded model file validation failed"
                    rm -f "${MODEL_FILE}"
                fi
            else
                echo "⚠️  Failed to download from S3 after ${MAX_RETRIES} attempts"
                echo "   Trying local download script as fallback..."
                # Fallback to local download script
                if [ -f "/app/scripts/download-bert-model.sh" ]; then
                    /app/scripts/download-bert-model.sh "${MODEL_DIR}" || {
                        echo "⚠️  Model download failed. BERT will be disabled."
                        echo "   Set bert.enabled=false in application.properties"
                    }
                fi
            fi
        else
            echo "⚠️  AWS CLI not available. Trying local download script..."
            if [ -f "/app/scripts/download-bert-model.sh" ]; then
                /app/scripts/download-bert-model.sh "${MODEL_DIR}" || {
                    echo "⚠️  Model download failed. BERT will be disabled."
                }
            fi
        fi
        echo ""
    else
        echo "✅ BERT model already exists and is valid: ${MODEL_FILE}"
        echo "   Size: $(du -h "${MODEL_FILE}" | cut -f1)"
    fi
elif [ ! -f "${MODEL_FILE}" ] || ! validate_model_file "${MODEL_FILE}"; then
    # No S3 bucket configured, try local download
    echo "BERT_MODEL_BUCKET not set, trying local download..."
    if [ -f "/app/scripts/download-bert-model.sh" ]; then
        /app/scripts/download-bert-model.sh "${MODEL_DIR}" || {
            echo "⚠️  Model download failed. BERT will be disabled."
        }
    fi
fi

# Start the application
echo "Starting BudgetBuddy Backend..."
exec java \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseZGC \
  -XX:+UnlockExperimentalVMOptions \
  -XX:+UseTransparentHugePages \
  -Djava.security.egd=file:/dev/./urandom \
  -jar app.jar
