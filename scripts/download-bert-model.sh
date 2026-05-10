#!/bin/bash
# Download a sentence-transformers ONNX model and tokenizer for BudgetBuddy's
# BERT-based category matcher.
#
# Default target: sentence-transformers/all-MiniLM-L6-v2 (384-dim, ~23 MB, CPU-fast).
# Produces:
#   ${MODEL_DIR}/all-MiniLM-L6-v2/model.onnx
#   ${MODEL_DIR}/all-MiniLM-L6-v2/tokenizer.json
#
# Wire these into the app by setting (application.yml or env):
#   bert.model.path=/abs/path/to/model.onnx
#   bert.tokenizer.path=/abs/path/to/tokenizer.json
#
# Usage: ./scripts/download-bert-model.sh [target-dir]

set -euo pipefail

MODEL_ID="${MODEL_ID:-sentence-transformers/all-MiniLM-L6-v2}"
MODEL_NAME="$(basename "${MODEL_ID}")"
MODEL_DIR="${1:-models}"
TARGET_DIR="${MODEL_DIR}/${MODEL_NAME}"
MODEL_FILE="${TARGET_DIR}/model.onnx"
TOKENIZER_FILE="${TARGET_DIR}/tokenizer.json"

echo "=========================================="
echo "Downloading sentence-transformers ONNX + tokenizer"
echo "  model id:    ${MODEL_ID}"
echo "  target dir:  ${TARGET_DIR}"
echo "=========================================="

mkdir -p "${TARGET_DIR}"

if [[ -f "${MODEL_FILE}" && -f "${TOKENIZER_FILE}" ]]; then
    echo "Already present:"
    echo "  ${MODEL_FILE}   ($(du -h "${MODEL_FILE}" | cut -f1))"
    echo "  ${TOKENIZER_FILE} ($(du -h "${TOKENIZER_FILE}" | cut -f1))"
    echo "Skipping download."
    exit 0
fi

# Preferred path: use the 🤗 optimum CLI to export the model to ONNX. This is
# the supported way to get a sentence-transformers ONNX build that works with
# ai.djl.huggingface:tokenizers + Microsoft ONNX Runtime.
if command -v python3 >/dev/null 2>&1 && python3 -c "import optimum.onnxruntime" >/dev/null 2>&1; then
    echo "Exporting via optimum-cli …"
    python3 -m optimum.exporters.onnx \
        --model "${MODEL_ID}" \
        --task feature-extraction \
        "${TARGET_DIR}"

    # optimum writes model.onnx + tokenizer.json + config into the target dir.
    if [[ -f "${MODEL_FILE}" && -f "${TOKENIZER_FILE}" ]]; then
        echo "Done: exported sentence-transformers ONNX to ${TARGET_DIR}"
        exit 0
    fi
fi

# Fallback: use the huggingface CLI to download a pre-built ONNX revision (if
# the model repo has one) plus the tokenizer.
if command -v huggingface-cli >/dev/null 2>&1; then
    echo "Trying huggingface-cli download …"
    huggingface-cli download "${MODEL_ID}" \
        onnx/model.onnx tokenizer.json \
        --local-dir "${TARGET_DIR}" \
        --local-dir-use-symlinks False || true

    # The CLI puts model.onnx under onnx/ — flatten it.
    if [[ -f "${TARGET_DIR}/onnx/model.onnx" ]]; then
        mv "${TARGET_DIR}/onnx/model.onnx" "${MODEL_FILE}"
        rmdir "${TARGET_DIR}/onnx" 2>/dev/null || true
    fi

    if [[ -f "${MODEL_FILE}" && -f "${TOKENIZER_FILE}" ]]; then
        echo "Done: downloaded ONNX + tokenizer to ${TARGET_DIR}"
        exit 0
    fi
fi

cat <<EOF

Automatic download failed. Install one of:
  - Python 3 with: pip install 'optimum[onnxruntime]' transformers sentence-transformers
  - huggingface_hub: pip install huggingface_hub

Then rerun:  $0 ${MODEL_DIR}

Or download manually from: https://huggingface.co/${MODEL_ID}
Required files: model.onnx, tokenizer.json
Drop them into ${TARGET_DIR}/ and restart the backend.
EOF
exit 1
