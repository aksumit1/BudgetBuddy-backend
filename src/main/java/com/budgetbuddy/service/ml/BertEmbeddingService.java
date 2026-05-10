package com.budgetbuddy.service.ml;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Generates sentence embeddings for arbitrary text using a locally-loaded sentence-transformers
 * ONNX model (default target: <code>sentence-transformers/all-MiniLM-L6-v2</code>, 384-dim).
 *
 * <h3>Design</h3>
 *
 * <ul>
 *   <li>Tokenization via DJL's HuggingFace tokenizer (loads a {@code tokenizer.json}).
 *   <li>Inference via Microsoft ONNX Runtime (the {@code .onnx} export).
 *   <li>Mean pooling over the attention-masked token outputs, then L2 normalization — this matches
 *       the standard sentence-transformers pooling and produces embeddings that are drop-in
 *       comparable with cosine similarity.
 *   <li>Service degrades gracefully: if the model/tokenizer paths are unset or the files are
 *       missing, {@link #isAvailable()} returns {@code false} and {@link #embed(String)} returns
 *       {@code null}. No hard failure at startup.
 * </ul>
 *
 * <h3>Configuration</h3>
 *
 * Set these in application.properties / env vars:
 *
 * <pre>
 *   bert.model.path=/opt/models/all-MiniLM-L6-v2/model.onnx
 *   bert.tokenizer.path=/opt/models/all-MiniLM-L6-v2/tokenizer.json
 *   bert.max-tokens=128
 * </pre>
 *
 * <h3>Expected ONNX I/O shape</h3>
 *
 * Assumes the standard sentence-transformers ONNX export with inputs {@code input_ids}, {@code
 * attention_mask} (and optionally {@code token_type_ids}), and a {@code last_hidden_state} output
 * of shape {@code [batch, seq_len, hidden_dim]}. Use <code>optimum-cli export onnx
 * --model sentence-transformers/all-MiniLM-L6-v2 ...</code> to generate files matching this
 * contract (see scripts/download-bert-model.sh).
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@Service
public class BertEmbeddingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BertEmbeddingService.class);

    @Value("${bert.model.path:}")
    private String modelPath;

    @Value("${bert.tokenizer.path:}")
    private String tokenizerPath;

    @Value("${bert.max-tokens:128}")
    private int maxTokens;

    private OrtEnvironment ortEnv;
    private OrtSession ortSession;
    private HuggingFaceTokenizer tokenizer;
    private volatile boolean available = false;
    private int embeddingDim = 0;

    @PostConstruct
    public void init() {
        if (isBlank(modelPath) || isBlank(tokenizerPath)) {
            LOGGER.info(
                    "BertEmbeddingService: bert.model.path / bert.tokenizer.path not set — BERT disabled, pipeline will use keyword+fuzzy+semantic only.");
            return;
        }

        final File modelFile = new File(modelPath);
        final File tokenizerFile = new File(tokenizerPath);
        if (!modelFile.isFile() || !tokenizerFile.isFile()) {
            LOGGER.warn(
                    "BertEmbeddingService: model or tokenizer file not found (model='{}' exists={}, tokenizer='{}' exists={}) — BERT disabled.",
                    modelPath,
                    modelFile.isFile(),
                    tokenizerPath,
                    tokenizerFile.isFile());
            return;
        }

        try {
            final Path tokenizerFilePath = Paths.get(tokenizerPath);
            final Map<String, String> tokenizerOptions = new HashMap<>();
            tokenizerOptions.put("maxLength", String.valueOf(maxTokens));
            tokenizerOptions.put("truncation", "true");
            tokenizerOptions.put("padding", "true");
            this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerFilePath, tokenizerOptions);

            this.ortEnv = OrtEnvironment.getEnvironment();
            final OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            // Default to 1 intra-op thread per session; categorization is called per
            // transaction in a request-scoped thread pool, so we rely on upstream
            // parallelism rather than intra-op threads which would oversubscribe CPU.
            sessionOptions.setIntraOpNumThreads(1);
            this.ortSession = ortEnv.createSession(modelPath, sessionOptions);

            // Probe a single embedding to determine the hidden dim and verify the
            // model actually runs (fail-fast at startup rather than on first request).
            final float[] probe = embedInternal("probe");
            if (probe == null || probe.length == 0) {
                throw new IllegalStateException("BERT probe embedding returned empty array");
            }
            this.embeddingDim = probe.length;
            this.available = true;
            LOGGER.info(
                    "BertEmbeddingService: initialized (model='{}', embeddingDim={}, maxTokens={})",
                    modelFile.getName(),
                    embeddingDim,
                    maxTokens);

        } catch (Exception e) {
            LOGGER.error(
                    "BertEmbeddingService: failed to initialize — BERT disabled. Cause: {}",
                    e.getMessage(),
                    e);
            closeQuietly();
            available = false;
        }
    }

    @PreDestroy
    public void shutdown() {
        closeQuietly();
    }

    public boolean isAvailable() {
        return available;
    }

    public int getEmbeddingDim() {
        return embeddingDim;
    }

    /**
     * Embed a single piece of text. Returns {@code null} if the service is unavailable or the text
     * is empty. The returned vector is L2-normalized, so cosine similarity reduces to a dot
     * product.
     */
    public float[] embed(final String text) {
        if (!available) {
            return null;
        }
        if (text == null) {
            return null;
        }
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return embedInternal(trimmed);
        } catch (Exception e) {
            LOGGER.warn(
                    "BertEmbeddingService: embedding failed for text (len={}): {}",
                    trimmed.length(),
                    e.getMessage());
            return null;
        }
    }

    /** Cosine similarity for two L2-normalized vectors. Returns 0.0 for null/mismatched input. */
    public static double cosineSimilarity(final float[] a, final float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        // Both vectors are normalized in embedInternal, so dot ∈ [-1, 1].
        // Clamp for numerical safety.
        if (dot < -1.0) {
            return -1.0;
        }
        if (dot > 1.0) {
            return 1.0;
        }
        return dot;
    }

    private float[] embedInternal(final String text) throws OrtException {
        final Encoding encoding = tokenizer.encode(text);
        final long[] inputIds = encoding.getIds();
        final long[] attentionMask = encoding.getAttentionMask();
        final long[] tokenTypeIds = encoding.getTypeIds();

        final int seqLen = inputIds.length;

        final Map<String, OnnxTensor> inputs = new HashMap<>();
        OnnxTensor inputIdsTensor = null;
        OnnxTensor attentionMaskTensor = null;
        OnnxTensor tokenTypeIdsTensor = null;
        OrtSession.Result result = null;

        try {
            inputIdsTensor =
                    OnnxTensor.createTensor(
                            ortEnv, LongBuffer.wrap(inputIds), new long[] {1, seqLen});
            attentionMaskTensor =
                    OnnxTensor.createTensor(
                            ortEnv, LongBuffer.wrap(attentionMask), new long[] {1, seqLen});
            inputs.put("input_ids", inputIdsTensor);
            inputs.put("attention_mask", attentionMaskTensor);

            if (ortSession.getInputNames().contains("token_type_ids")) {
                tokenTypeIdsTensor =
                        OnnxTensor.createTensor(
                                ortEnv, LongBuffer.wrap(tokenTypeIds), new long[] {1, seqLen});
                inputs.put("token_type_ids", tokenTypeIdsTensor);
            }

            result = ortSession.run(inputs);

            // Find the last_hidden_state output. Some exports use that literal name; others
            // emit a differently named first output. We accept the first 3-D float output.
            float[][][] hidden = null;
            for (final Map.Entry<String, ai.onnxruntime.OnnxValue> entry : result) {
                final Object value = entry.getValue().getValue();
                if (value instanceof float[][][]) {
                    hidden = (float[][][]) value;
                    break;
                }
            }
            if (hidden == null || hidden.length == 0) {
                throw new IllegalStateException(
                        "BERT ONNX output missing last_hidden_state (expected float[1][seq][hidden])");
            }

            final float[][] tokenOutputs = hidden[0]; // [seq_len, hidden_dim]
            return meanPoolAndNormalize(tokenOutputs, attentionMask);

        } finally {
            if (result != null) {
                result.close();
            }
            closeTensorQuietly(inputIdsTensor);
            closeTensorQuietly(attentionMaskTensor);
            closeTensorQuietly(tokenTypeIdsTensor);
        }
    }

    /** Attention-masked mean pooling + L2 normalization (sentence-transformers standard). */
    private static float[] meanPoolAndNormalize(final float[][] tokenOutputs, final long[] attentionMask) {
        final int seqLen = tokenOutputs.length;
        final int hiddenDim = tokenOutputs[0].length;
        final float[] sum = new float[hiddenDim];
        double maskSum = 0.0;

        for (int t = 0; t < seqLen; t++) {
            if (attentionMask[t] == 0L) {
                continue;
            }
            maskSum += 1.0;
            final float[] tokenVec = tokenOutputs[t];
            for (int h = 0; h < hiddenDim; h++) {
                sum[h] += tokenVec[h];
            }
        }
        if (maskSum == 0.0) {
            // Fallback: mean across all tokens if mask was all zeros (shouldn't happen in
            // practice).
            Arrays.fill(sum, 0f);
            for (int t = 0; t < seqLen; t++) {
                for (int h = 0; h < hiddenDim; h++) {
                    sum[h] += tokenOutputs[t][h];
                }
            }
            maskSum = seqLen;
        }

        final float[] mean = new float[hiddenDim];
        double norm = 0.0;
        for (int h = 0; h < hiddenDim; h++) {
            mean[h] = (float) (sum[h] / maskSum);
            norm += mean[h] * mean[h];
        }
        norm = Math.sqrt(norm);
        if (norm > 0.0) {
            final float invNorm = (float) (1.0 / norm);
            for (int h = 0; h < hiddenDim; h++) {
                mean[h] *= invNorm;
            }
        }
        return mean;
    }

    private static boolean isBlank(final String s) {
        return s == null || s.isBlank();
    }

    private static void closeTensorQuietly(final OnnxTensor t) {
        if (t != null) {
            t.close();
        }
    }

    // HuggingFaceTokenizer.close() declares `throws Exception`, so we can't
    // narrow the catch on that branch. Best-effort cleanup; we don't want a
    // dangling close failure to propagate.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void closeQuietly() {
        if (ortSession != null) {
            try {
                ortSession.close();
            } catch (OrtException ignored) {
                /* no-op */
            }
            ortSession = null;
        }
        if (tokenizer != null) {
            try {
                tokenizer.close();
            } catch (Exception ignored) {
                /* no-op */
            }
            tokenizer = null;
        }
    }
}
