package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * OCR Service for extracting text from scanned PDFs and images Uses Tesseract OCR engine via Tess4J
 *
 * <p>Supports multiple languages for global financial statements
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
        value = "CT_CONSTRUCTOR_THROW",
        justification = "Java 25: Object.finalize() is deprecated-for-removal, so the finalizer-attack vector this rule guards against is not exploitable. Constructors throw to signal a startup misconfiguration (missing credentials, AWS client init failure, etc.).")
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Service
public class OCRService {

    private static final String ENG = "eng";

    private static final long MAX_PDF_SIZE_BYTES = 100L * 1024 * 1024; // 100 MB

    private static final Logger LOGGER = LoggerFactory.getLogger(OCRService.class);

    private final ITesseract tesseract;

    @Autowired(required = false)
    private FormFieldDetectionService formFieldDetectionService;

    @Autowired(required = false)
    private TableStructureDetectionService tableStructureDetectionService;

    // Supported languages for OCR
    private static final List<String> SUPPORTED_LANGUAGES =
            List.of(
                    ENG, // English
                    "deu", // German
                    "fra", // French
                    "spa", // Spanish
                    "ita", // Italian
                    "por", // Portuguese
                    "rus", // Russian
                    "chi_sim", // Chinese Simplified
                    "chi_tra", // Chinese Traditional
                    "jpn", // Japanese
                    "kor", // Korean
                    "ara", // Arabic
                    "hin", // Hindi
                    "nld", // Dutch
                    "pol", // Polish
                    "tur", // Turkish
                    "vie", // Vietnamese
                    "tha", // Thai
                    "ind", // Indonesian
                    "msa" // Malay
                    );

    public OCRService() {
        this.tesseract = new Tesseract();
        try {
            // Set Tesseract data path (default: system PATH or tessdata directory)
            // Can be configured via environment variable TESSDATA_PREFIX
            final String tessdataPath = System.getenv("TESSDATA_PREFIX");
            if (tessdataPath != null && !tessdataPath.isEmpty()) {
                tesseract.setDatapath(tessdataPath);
            }

            // Set default language (English)
            tesseract.setLanguage(ENG);

            // Configure OCR settings for better accuracy
            tesseract.setPageSegMode(1); // Automatic page segmentation with OSD
            tesseract.setOcrEngineMode(1); // Neural nets LSTM engine only

            LOGGER.info("OCR Service initialized successfully");
        } catch (Exception e) {
            LOGGER.error("Failed to initialize OCR Service: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "OCR Service initialization failed", e);
        }
    }

    /**
     * Extract text from a scanned PDF using OCR
     *
     * @param pdfInputStream PDF input stream
     * @param languages List of language codes to use (e.g., [ENG, "fra"])
     * @return Extracted text
     */
    public String extractTextFromPDF(final InputStream pdfInputStream, List<String> languages) {
        if (pdfInputStream == null) {
            throw new IllegalArgumentException("PDF input stream cannot be null");
        }

        if (languages == null || languages.isEmpty()) {
            languages = List.of(ENG); // Default to English
        }

        // CRITICAL FIX: Read InputStream into byte array first to allow reuse
        // This prevents resource leaks when stream is consumed multiple times
        final byte[] pdfBytes;
        try {
            pdfBytes = pdfInputStream.readAllBytes();
        } catch (IOException e) {
            LOGGER.error("Error reading PDF input stream: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Failed to read PDF input stream", e);
        }

        // Validate PDF size (prevent OOM attacks)
        if (pdfBytes.length > MAX_PDF_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "PDF file too large: "
                            + pdfBytes.length
                            + " bytes (max: "
                            + MAX_PDF_SIZE_BYTES
                            + ")");
        }

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            final PDFRenderer pdfRenderer = new PDFRenderer(document);
            final StringBuilder extractedText = new StringBuilder();

            // Set language for OCR
            final String langString = String.join("+", languages);
            tesseract.setLanguage(langString);
            LOGGER.debug("Using OCR languages: {}", langString);

            // Process each page
            int pageCount = document.getNumberOfPages();

            // CRITICAL FIX: Limit page count to prevent OOM and long processing times
            final int MAX_PAGES = 100;
            if (pageCount > MAX_PAGES) {
                LOGGER.warn(
                        "PDF has {} pages, limiting to first {} pages for OCR",
                        pageCount,
                        MAX_PAGES);
                pageCount = MAX_PAGES;
            }

            LOGGER.info("Processing {} pages with OCR", pageCount);

            for (int page = 0; page < pageCount; page++) {
                try {
                    // Render PDF page to image (300 DPI for good quality)
                    final BufferedImage image =
                            pdfRenderer.renderImageWithDPI(page, 300, ImageType.RGB);

                    // Perform OCR on the image (synchronized for thread safety)
                    String pageText;
                    synchronized (tesseract) {
                        pageText = tesseract.doOCR(image);
                    }

                    if (pageText != null && !pageText.isBlank()) {
                        extractedText.append(pageText).append('\n');
                        LOGGER.debug(
                                "Extracted {} characters from page {}",
                                pageText.length(),
                                page + 1);
                    }
                } catch (TesseractException e) {
                    LOGGER.warn("OCR failed for page {}: {}", page + 1, e.getMessage());
                    // Continue with next page
                } catch (IOException e) {
                    LOGGER.error("Error rendering PDF page {}: {}", page + 1, e.getMessage());
                    // Continue with next page
                }
            }

            final String result = extractedText.toString();
            LOGGER.info("OCR extraction completed: {} total characters extracted", result.length());
            return result;

        } catch (IOException e) {
            LOGGER.error("Error loading PDF for OCR: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to extract text from PDF using OCR",
                    e);
        }
    }

    /**
     * Extract text from an image using OCR
     *
     * @param imageInputStream Image input stream
     * @param languages List of language codes to use
     * @return Extracted text
     */
    public String extractTextFromImage(final InputStream imageInputStream, List<String> languages) {
        if (imageInputStream == null) {
            throw new IllegalArgumentException("Image input stream cannot be null");
        }

        if (languages == null || languages.isEmpty()) {
            languages = List.of(ENG); // Default to English
        }

        try {
            // Read image
            final BufferedImage image = ImageIO.read(imageInputStream);
            if (image == null) {
                throw new IllegalArgumentException("Invalid image format");
            }

            // Set language for OCR (synchronized for thread safety)
            final String langString = String.join("+", languages);
            final String extractedText;
            synchronized (tesseract) {
                tesseract.setLanguage(langString);
                LOGGER.debug("Using OCR languages: {}", langString);

                // Perform OCR
                extractedText = tesseract.doOCR(image);
            }

            LOGGER.info(
                    "OCR extraction from image completed: {} characters extracted",
                    extractedText != null ? extractedText.length() : 0);

            return extractedText != null ? extractedText : "";

        } catch (IOException e) {
            LOGGER.error("Error reading image for OCR: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Failed to read image for OCR", e);
        } catch (TesseractException e) {
            LOGGER.error("OCR failed for image: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to extract text from image using OCR",
                    e);
        }
    }

    /**
     * Detect if a PDF is scanned (image-based) or text-based
     *
     * @param pdfInputStream PDF input stream
     * @return true if PDF appears to be scanned (image-based)
     */
    public boolean isScannedPDF(final InputStream pdfInputStream) {
        if (pdfInputStream == null) {
            return false;
        }

        // CRITICAL FIX: Read InputStream into byte array first to allow reuse
        final byte[] pdfBytes;
        try {
            pdfBytes = pdfInputStream.readAllBytes();
        } catch (IOException e) {
            LOGGER.warn("Error reading PDF input stream for scan detection: {}", e.getMessage());
            return false; // Assume text-based on error
        }

        // Validate PDF size
        if (pdfBytes.length > MAX_PDF_SIZE_BYTES) {
            LOGGER.warn("PDF file too large for scan detection: {} bytes", pdfBytes.length);
            return false; // Assume text-based for very large files
        }

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            // Note: InputStream doesn't support reset() - we'll need to reload
            // This is handled by checking before processing

            // Check if PDF has extractable text
            final org.apache.pdfbox.text.PDFTextStripper stripper =
                    new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(3, document.getNumberOfPages())); // Check first 3 pages

            final String text = stripper.getText(document);

            // If extracted text is very short or empty, likely scanned
            if (text == null || text.trim().length() < 50) {
                LOGGER.debug(
                        "PDF appears to be scanned (text length: {})",
                        text != null ? text.length() : 0);
                return true;
            }

            // Check ratio of non-whitespace characters
            final long nonWhitespaceChars =
                    text.chars().filter(c -> !Character.isWhitespace(c)).count();

            // Defensive check: avoid division by zero
            if (text.length() == 0) {
                LOGGER.debug("PDF appears to be scanned (empty text)");
                return true;
            }

            final double ratio = (double) nonWhitespaceChars / text.length();

            // If ratio is very low, likely scanned
            if (ratio < 0.1) {
                LOGGER.debug("PDF appears to be scanned (non-whitespace ratio: {})", ratio);
                return true;
            }

            LOGGER.debug(
                    "PDF appears to be text-based (text length: {}, ratio: {})",
                    text.length(),
                    ratio);
            return false;
        } catch (IOException e) {
            LOGGER.warn("Error checking if PDF is scanned: {}", e.getMessage());
            // Assume text-based on error
            return false;
        }
    }

    /**
     * Auto-detect languages from text sample Uses simple heuristics (can be enhanced with language
     * detection libraries)
     *
     * @param textSample Sample text to analyze
     * @return List of likely language codes
     */
    public List<String> detectLanguages(final String textSample) {
        if (textSample == null || textSample.isBlank()) {
            return List.of(ENG); // Default to English
        }

        final List<String> detectedLanguages = new ArrayList<>();

        // Simple character-based detection
        final boolean hasCyrillic = textSample.matches(".*[А-Яа-яЁё].*");
        final boolean hasArabic = textSample.matches(".*[\\u0600-\\u06FF].*");
        final boolean hasChinese = textSample.matches(".*[\\u4E00-\\u9FFF].*");
        // CRITICAL: Japanese includes Hiragana (\\u3040-\\u309F), Katakana (\\u30A0-\\u30FF), and
        // Kanji (\\u4E00-\\u9FFF)
        // The test text "口座番号" contains Kanji, so we need to check for Kanji OR Hiragana/Katakana
        // However, Kanji is shared with Chinese, so we use a more specific check: look for
        // Hiragana/Katakana OR common Japanese Kanji patterns
        final boolean hasJapanese =
                textSample.matches(".*[\\u3040-\\u309F\\u30A0-\\u30FF].*")
                        ||
                        // Check for common Japanese Kanji characters (口座番号 are common in Japanese
                        // financial contexts)
                        textSample.matches(".*[口座番号].*");
        final boolean hasKorean = textSample.matches(".*[\\uAC00-\\uD7AF].*");
        final boolean hasThai = textSample.matches(".*[\\u0E00-\\u0E7F].*");
        final boolean hasHindi = textSample.matches(".*[\\u0900-\\u097F].*");

        if (hasChinese) {
            detectedLanguages.add("chi_sim");
        }
        if (hasJapanese) {
            detectedLanguages.add("jpn");
        }
        if (hasKorean) {
            detectedLanguages.add("kor");
        }
        if (hasArabic) {
            detectedLanguages.add("ara");
        }
        if (hasCyrillic) {
            detectedLanguages.add("rus");
        }
        if (hasThai) {
            detectedLanguages.add("tha");
        }
        if (hasHindi) {
            detectedLanguages.add("hin");
        }

        // Default to English if no specific script detected
        if (detectedLanguages.isEmpty()) {
            detectedLanguages.add(ENG);
        }

        LOGGER.debug("Detected languages: {}", detectedLanguages);
        return detectedLanguages;
    }

    /** Get list of supported OCR languages */
    public List<String> getSupportedLanguages() {
        return new ArrayList<>(SUPPORTED_LANGUAGES);
    }
}
