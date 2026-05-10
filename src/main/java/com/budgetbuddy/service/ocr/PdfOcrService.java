package com.budgetbuddy.service.ocr;

import jakarta.annotation.PostConstruct;
import java.awt.image.BufferedImage;
import java.io.File;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * OCR fallback for PDFs that have no extractable text (scanned or image-only statements). Runs
 * Tesseract over a rendered raster of each page and returns the concatenated text.
 *
 * <h3>Opt-in by design</h3>
 *
 * Tesseract requires a {@code tessdata} directory shipped alongside the binary. Deployments that
 * don't have one (e.g. local dev, quick container images) should NOT pay the OCR price or risk a
 * runtime NPE. The service reports {@link #isAvailable()} {@code = false} when unconfigured, and
 * callers skip invocation in that case.
 *
 * <h3>Configuration</h3>
 *
 * <pre>
 *   ocr.enabled=true                # master switch
 *   ocr.tessdata.path=/opt/tessdata # directory with eng.traineddata etc.
 *   ocr.language=eng                # ISO language code; defaults to "eng"
 *   ocr.max-pages=20                # cap per PDF to keep CPU bounded
 *   ocr.render-dpi=200              # image DPI; 200 is a good speed/accuracy balance
 * </pre>
 *
 * <h3>Cost model</h3>
 *
 * Tesseract on CPU runs ~1-3 seconds per page at 200 DPI on modern x86. We cap at {@code
 * ocr.max-pages} (default 20) so a 400-page merged statement can't monopolise a worker thread.
 * Anything bigger returns what we have with a warning — the user still gets a useful parse for the
 * first 20 pages.
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Service
public class PdfOcrService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfOcrService.class);

    @Value("${ocr.enabled:false}")
    private boolean enabled;

    @Value("${ocr.tessdata.path:}")
    private String tessdataPath;

    @Value("${ocr.language:eng}")
    private String language;

    @Value("${ocr.max-pages:20}")
    private int maxPages;

    @Value("${ocr.render-dpi:200}")
    private int renderDpi;

    private volatile boolean available = false;

    @PostConstruct
    public void init() {
        if (!enabled) {
            LOGGER.info(
                    "PdfOcrService: disabled (ocr.enabled=false). Scanned PDFs will return the non-OCR diagnostic.");
            return;
        }
        if (tessdataPath == null || tessdataPath.isBlank()) {
            LOGGER.warn(
                    "PdfOcrService: ocr.enabled=true but ocr.tessdata.path is empty — OCR will stay disabled.");
            return;
        }
        final File tessdataDir = new File(tessdataPath);
        if (!tessdataDir.isDirectory()) {
            LOGGER.warn(
                    "PdfOcrService: tessdata.path \"{}\" is not a directory — OCR will stay disabled.",
                    tessdataPath);
            return;
        }
        // Probe: instantiating ITesseract is cheap; a doOCR against a small
        // blank image would be heavier, so we just verify the dir exists and
        // eagerly confirm at first real call.
        available = true;
        LOGGER.info(
                "PdfOcrService: ready (tessdata=\"{}\", language=\"{}\", maxPages={}, dpi={})",
                tessdataPath,
                language,
                maxPages,
                renderDpi);
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Run OCR across the pages of an already-loaded {@link PDDocument} and return the concatenated
     * text. Returns an empty string (never null) when unavailable or on failure — callers can then
     * decide whether to surface an error or fall back further.
     */
    public String extractText(final PDDocument document) {
        if (!available || document == null) {
            return "";
        }
        final PDFRenderer renderer = new PDFRenderer(document);
        final int pageCount = document.getNumberOfPages();
        final int pagesToScan = Math.min(pageCount, maxPages);
        if (pagesToScan < pageCount) {
            LOGGER.warn(
                    "PdfOcrService: scanning first {} of {} pages (ocr.max-pages cap)",
                    pagesToScan,
                    pageCount);
        }

        final ITesseract tesseract = newEngine();
        final StringBuilder out = new StringBuilder(4096);
        final long startedAt = System.currentTimeMillis();

        for (int i = 0; i < pagesToScan; i++) {
            try {
                final BufferedImage image =
                        renderer.renderImageWithDPI(i, renderDpi, ImageType.GRAY);
                final String pageText = tesseract.doOCR(image);
                if (pageText != null && !pageText.isBlank()) {
                    out.append(pageText);
                    if (!pageText.endsWith("\n")) {
                        out.append('\n');
                    }
                }
            } catch (TesseractException e) {
                LOGGER.warn("PdfOcrService: OCR failed on page {} — {}", i + 1, e.getMessage());
            } catch (Exception e) {
                // Rendering failures (corrupt PDF page, out-of-memory) — don't
                // let one bad page kill the whole pass.
                LOGGER.warn("PdfOcrService: render failed on page {} — {}", i + 1, e.getMessage());
            }
        }

        final long elapsedMs = System.currentTimeMillis() - startedAt;
        LOGGER.info(
                "PdfOcrService: OCR produced {} chars across {} page(s) in {} ms",
                out.length(),
                pagesToScan,
                elapsedMs);
        return out.toString();
    }

    private ITesseract newEngine() {
        final Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(tessdataPath);
        tesseract.setLanguage(language);
        // LSTM only — the legacy engine is slower and less accurate on modern
        // tessdata builds. Falls back gracefully if the engine isn't compiled in.
        tesseract.setOcrEngineMode(1); // OEM_LSTM_ONLY
        // Page segmentation mode 4 = "Assume a single column of text of
        // variable sizes" — fits most statement layouts better than the
        // default "fully automatic" which over-segments account/header blocks.
        tesseract.setPageSegMode(4);
        return tesseract;
    }
}
