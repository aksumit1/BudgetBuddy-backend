#!/bin/bash
# Download DistilBERT ONNX model from HuggingFace
# This script downloads the model automatically during build/deployment

set -e

MODEL_NAME="distilbert-base-uncased"
MODEL_DIR="${1:-models}"
MODEL_FILE="${MODEL_DIR}/${MODEL_NAME}.onnx"
HUGGINGFACE_REPO="optimum/${MODEL_NAME}"

echo "=========================================="
echo "Downloading DistilBERT ONNX Model"
echo "=========================================="
echo "Model: ${MODEL_NAME}"
echo "Target: ${MODEL_FILE}"
echo ""

# Create models directory if it doesn't exist
mkdir -p "${MODEL_DIR}"

# Check if model already exists
if [ -f "${MODEL_FILE}" ]; then
    echo "✅ Model already exists at ${MODEL_FILE}"
    echo "   Size: $(du -h "${MODEL_FILE}" | cut -f1)"
    echo "   Skipping download..."
    exit 0
fi

echo "Downloading model from HuggingFace..."
echo "Repository: ${HUGGINGFACE_REPO}"
echo ""

# Method 1: Try using huggingface-cli (if available)
if command -v huggingface-cli &> /dev/null; then
    echo "Using huggingface-cli..."
    huggingface-cli download "${HUGGINGFACE_REPO}" \
        "model.onnx" \
        --local-dir "${MODEL_DIR}/${MODEL_NAME}" \
        --local-dir-use-symlinks False || {
        echo "⚠️  huggingface-cli download failed, trying alternative method..."
        rm -rf "${MODEL_DIR}/${MODEL_NAME}"
    }
    
    # Move model file to expected location
    if [ -f "${MODEL_DIR}/${MODEL_NAME}/model.onnx" ]; then
        mv "${MODEL_DIR}/${MODEL_NAME}/model.onnx" "${MODEL_FILE}"
        rm -rf "${MODEL_DIR}/${MODEL_NAME}"
        echo "✅ Model downloaded successfully!"
        exit 0
    fi
fi

# Method 2: Try using Python with transformers/optimum
if command -v python3 &> /dev/null; then
    echo "Using Python to download model..."
    python3 << EOF
import os
import sys
from pathlib import Path

try:
    from optimum.onnxruntime import ORTModelForFeatureExtraction
    from transformers import AutoTokenizer
    
    model_dir = Path("${MODEL_DIR}/${MODEL_NAME}")
    model_dir.mkdir(parents=True, exist_ok=True)
    
    print("Downloading model from HuggingFace...")
    model = ORTModelForFeatureExtraction.from_pretrained(
        "${MODEL_NAME}",
        export=False,  # Use pre-exported ONNX model
        cache_dir=str(model_dir)
    )
    
    # Find the ONNX model file
    onnx_files = list(model_dir.rglob("*.onnx"))
    if onnx_files:
        import shutil
        shutil.move(str(onnx_files[0]), "${MODEL_FILE}")
        print("✅ Model downloaded successfully!")
        sys.exit(0)
    else:
        print("⚠️  ONNX model file not found, trying to export...")
        # Try exporting
        from optimum.onnxruntime import ORTModelForFeatureExtraction
        model = ORTModelForFeatureExtraction.from_pretrained(
            "${MODEL_NAME}",
            export=True
        )
        model.save_pretrained(str(model_dir))
        onnx_files = list(model_dir.rglob("*.onnx"))
        if onnx_files:
            import shutil
            shutil.move(str(onnx_files[0]), "${MODEL_FILE}")
            print("✅ Model exported and saved successfully!")
            sys.exit(0)
        else:
            print("❌ Failed to find or export ONNX model")
            sys.exit(1)
except ImportError:
    print("⚠️  Required Python packages not installed")
    print("   Install with: pip install optimum[onnxruntime] transformers")
    sys.exit(1)
except Exception as e:
    print(f"❌ Error: {e}")
    sys.exit(1)
EOF
    
    if [ $? -eq 0 ] && [ -f "${MODEL_FILE}" ]; then
        echo "✅ Model downloaded successfully using Python!"
        exit 0
    fi
fi

# Method 3: Direct download from HuggingFace (if model is publicly available)
echo "Trying direct download from HuggingFace..."
if command -v curl &> /dev/null; then
    # Try to download directly (this may not work for all models)
    curl -L "https://huggingface.co/${HUGGINGFACE_REPO}/resolve/main/model.onnx" \
        -o "${MODEL_FILE}" \
        --fail --silent --show-error || {
        echo "⚠️  Direct download failed"
        rm -f "${MODEL_FILE}"
    }
    
    if [ -f "${MODEL_FILE}" ] && [ -s "${MODEL_FILE}" ]; then
        echo "✅ Model downloaded successfully using curl!"
        exit 0
    fi
fi

# If all methods fail, provide instructions
echo ""
echo "❌ Automatic download failed. Please download manually:"
echo ""
echo "Option 1: Using Python (Recommended)"
echo "  pip install optimum[onnxruntime] transformers"
echo "  python3 -c \"from optimum.onnxruntime import ORTModelForFeatureExtraction; \\"
echo "    model = ORTModelForFeatureExtraction.from_pretrained('${MODEL_NAME}', export=True); \\"
echo "    model.save_pretrained('${MODEL_DIR}/${MODEL_NAME}')\""
echo ""
echo "Option 2: Using huggingface-cli"
echo "  pip install huggingface_hub"
echo "  huggingface-cli download ${HUGGINGFACE_REPO} model.onnx --local-dir ${MODEL_DIR}"
echo ""
echo "Option 3: Manual download"
echo "  Visit: https://huggingface.co/${HUGGINGFACE_REPO}"
echo "  Download model.onnx and place it at: ${MODEL_FILE}"
echo ""
echo "After downloading, the model will be automatically detected."
exit 1
