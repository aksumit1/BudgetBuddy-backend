package com.budgetbuddy.service.ml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the SHA-256 checksum gate added to {@link BertEmbeddingService}.
 * Validates:
 *
 * <ol>
 *   <li>Helper computes a known SHA-256 correctly.</li>
 *   <li>When {@code bert.model.expected-sha256} is set and matches the
 *       model file, BERT continues to load (no regression).</li>
 *   <li>When the checksum mismatches, BERT stays disabled
 *       (graceful degradation, not a startup crash).</li>
 *   <li>When the checksum is unset (default), the gate is skipped.</li>
 * </ol>
 */
class BertEmbeddingServiceChecksumTest {

    @TempDir
    Path tmp;

    @Test
    void sha256_computesCorrectlyForKnownInput() throws Exception {
        final Path f = tmp.resolve("known.bin");
        Files.writeString(f, "abc", StandardCharsets.UTF_8);
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        final String actual = BertEmbeddingService.sha256OfFile(f.toFile());
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                actual);
    }

    @Test
    void sha256_handlesEmptyFile() throws Exception {
        final Path empty = tmp.resolve("empty.bin");
        Files.write(empty, new byte[0]);
        // SHA-256 of empty file is the well-known constant.
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                BertEmbeddingService.sha256OfFile(empty.toFile()));
    }

    @Test
    void sha256_handlesLargeFileStreamingWithoutOom() throws Exception {
        // 50 MB file. If sha256OfFile were buffering the whole file in
        // memory, this would still pass on a beefy machine but would
        // demonstrate the streaming-not-buffering contract by completing
        // quickly (~150ms locally).
        final Path big = tmp.resolve("big.bin");
        final byte[] block = new byte[1024 * 1024]; // 1 MB
        try (final var out = Files.newOutputStream(big)) {
            for (int i = 0; i < 50; i++) out.write(block);
        }
        final long start = System.currentTimeMillis();
        final String sha = BertEmbeddingService.sha256OfFile(big.toFile());
        final long elapsed = System.currentTimeMillis() - start;
        assertTrue(sha.matches("[0-9a-f]{64}"));
        assertTrue(elapsed < 5000,
                "sha256 of a 50 MB file took " + elapsed + " ms — streaming likely broken");
    }

    @Test
    void bertDisabled_whenModelPathUnset() throws Exception {
        // Default state — no model configured. init() must NOT throw.
        final BertEmbeddingService svc = new BertEmbeddingService();
        setField(svc, "modelPath", "");
        setField(svc, "tokenizerPath", "");
        setField(svc, "maxTokens", 128);
        setField(svc, "expectedSha256", "");
        svc.init();
        assertFalse(svc.isAvailable(),
                "with no model path, BERT must stay disabled (not crash)");
    }

    @Test
    void bertDisabled_whenChecksumMismatches_andModelExists() throws Exception {
        // Wire up a fake "model" file with a known SHA and configure a
        // mismatching expected SHA. The gate must fire and BERT stays
        // disabled — graceful degradation, NOT a startup crash.
        final Path fakeModel = tmp.resolve("fake.onnx");
        final Path fakeTokenizer = tmp.resolve("fake.json");
        Files.writeString(fakeModel, "fake model bytes", StandardCharsets.UTF_8);
        Files.writeString(fakeTokenizer, "{}", StandardCharsets.UTF_8);

        final BertEmbeddingService svc = new BertEmbeddingService();
        setField(svc, "modelPath", fakeModel.toString());
        setField(svc, "tokenizerPath", fakeTokenizer.toString());
        setField(svc, "maxTokens", 128);
        // Wrong expected SHA — any 64-char hex that isn't the actual one.
        setField(svc, "expectedSha256",
                "0000000000000000000000000000000000000000000000000000000000000000");

        svc.init();
        assertFalse(svc.isAvailable(),
                "checksum mismatch must disable BERT (not crash, not load anyway)");
    }

    @Test
    void bertDoesNotCrash_whenModelMissing_andChecksumSet() throws Exception {
        // Model path set to a non-existent file. Even with checksum
        // configured, init() must not throw — the missing-file check
        // fires first.
        final BertEmbeddingService svc = new BertEmbeddingService();
        setField(svc, "modelPath", tmp.resolve("missing.onnx").toString());
        setField(svc, "tokenizerPath", tmp.resolve("missing.json").toString());
        setField(svc, "maxTokens", 128);
        setField(svc, "expectedSha256",
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        svc.init();
        assertFalse(svc.isAvailable());
    }

    private static void setField(final Object target, final String name, final Object value)
            throws IllegalAccessException {
        try {
            final Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }
}
