# BERT Implementation Status

## Current Status: ⚠️ **NOT IMPLEMENTED** (Placeholder Only)

The BERT model is **not actually implemented** - only the structure/placeholder code exists. The system currently uses **keyword-based semantic matching** as a fallback.

---

## What's Currently Implemented:

1. ✅ **Structure/Placeholder Code**:
   - `initializeBertModel()` method (returns false, doesn't load model)
   - `getBertEmbedding()` method (returns null)
   - `calculateBertSimilarity()` method (falls back to keyword-based)
   - `cosineSimilarity()` helper method (ready to use)

2. ✅ **Dependencies Added**:
   - ONNX Runtime (`com.microsoft.onnxruntime:onnxruntime:1.18.0`)
   - Ready for BERT model integration

3. ❌ **NOT Implemented**:
   - Actual BERT model loading
   - Tokenization
   - Embedding generation
   - Model inference

---

## Recommended BERT Models for CPU-Optimized Inference:

### Option 1: **DistilBERT** (RECOMMENDED for CPU) ⭐
- **Model**: `distilbert-base-uncased`
- **Size**: ~66M parameters (50% smaller than BERT)
- **Speed**: 2x faster than BERT on CPU
- **Accuracy**: ~97% of BERT performance
- **ONNX Model**: Available on HuggingFace
- **Best for**: CPU inference, faster response times

### Option 2: **Sentence-Transformers** (BEST for Embeddings) ⭐⭐
- **Model**: `sentence-transformers/all-MiniLM-L6-v2`
- **Size**: ~22M parameters (very small)
- **Speed**: 5x faster than BERT on CPU
- **Purpose**: Optimized specifically for sentence embeddings
- **ONNX Model**: Available, pre-optimized for embeddings
- **Best for**: Semantic similarity, category matching

### Option 3: **BERT Base** (Full BERT - NOT Recommended for CPU)
- **Model**: `bert-base-uncased`
- **Size**: ~110M parameters
- **Speed**: Slow on CPU (designed for GPU)
- **Accuracy**: Highest, but overkill for this use case
- **Best for**: GPU inference, maximum accuracy

### Option 4: **MobileBERT** (Lightweight)
- **Model**: `google/mobilebert-uncased`
- **Size**: ~25M parameters
- **Speed**: Fast on CPU
- **Accuracy**: Good balance
- **Best for**: Mobile/CPU deployments

---

## Recommended Implementation: Sentence-Transformers

For your use case (merchant name category matching), I recommend **sentence-transformers/all-MiniLM-L6-v2** because:

1. ✅ **Optimized for embeddings** - designed for semantic similarity
2. ✅ **Fast on CPU** - 5x faster than BERT
3. ✅ **Small model** - easy to deploy
4. ✅ **Pre-trained on semantic tasks** - better for category matching
5. ✅ **ONNX models available** - ready to use with ONNX Runtime

---

## Implementation Steps (if you want to add BERT):

1. **Download ONNX Model**:
   ```bash
   # Using HuggingFace transformers
   python -c "from transformers import AutoTokenizer, AutoModel; \
              model = AutoModel.from_pretrained('sentence-transformers/all-MiniLM-L6-v2'); \
              # Convert to ONNX..."
   ```

2. **Update `initializeBertModel()`**:
   ```java
   OrtEnvironment env = OrtEnvironment.getEnvironment();
   OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
   opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
   OrtSession session = env.createSession("path/to/all-MiniLM-L6-v2.onnx", opts);
   bertModelAvailable = true;
   ```

3. **Implement Tokenization**:
   - Use HuggingFace tokenizer or implement WordPiece tokenization
   - Convert text to token IDs

4. **Implement `getBertEmbedding()`**:
   - Tokenize input text
   - Run inference through ONNX session
   - Extract embeddings from output

---

## Current Behavior:

- **BERT Available**: `false`
- **Fallback**: Keyword-based Jaccard similarity
- **Performance**: Works well for exact/partial keyword matches
- **Limitation**: Doesn't understand semantic relationships (e.g., "grocery" vs "supermarket")

---

## Recommendation:

For now, the **keyword-based matching works well** because:
1. ✅ Comprehensive keyword lists (all semantic categories merged)
2. ✅ Fast (no model inference)
3. ✅ No dependencies on large model files
4. ✅ Good accuracy for known merchants

**Consider adding BERT if**:
- You need to match new/unknown merchants semantically
- You want to understand synonyms (e.g., "food store" = "grocery")
- You have GPU resources or can tolerate slower CPU inference

For CPU-only deployment, **sentence-transformers/all-MiniLM-L6-v2** is the best choice.
