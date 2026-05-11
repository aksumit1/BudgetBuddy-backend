package com.budgetbuddy.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Advanced Account Detection Service
 *
 * <p>Uses sophisticated pattern detection techniques for global financial statements: -
 * Multi-source analysis (filename, headers, data rows, metadata) - Context-aware pattern matching -
 * Fuzzy matching for institution names - Statistical analysis of transaction patterns - Confidence
 * scoring - Global format support (US, UK, EU, Asia, etc.)
 *
 * <p>Best Practices Applied: 1. Multi-source evidence aggregation 2. Confidence scoring for each
 * detection 3. Context-aware pattern matching 4. Fuzzy string matching for institution names 5.
 * Statistical analysis of patterns 6. Fallback strategies 7. Global format support
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.DataClass", "PMD.OnlyOneReturn"})
@Service
public class AdvancedAccountDetectionService {

    private static final String DATA = "data";

    private static final String COLUMN = "column";

    private static final String CREDIT = "credit";

    private static final String DEPOSITORY = "depository";

    private static final String EXACT = "exact";

    private static final String FILENAME = "filename";

    private static final String HEADER = "header";

    private static final String INVESTMENT = "investment";

    private static final String METADATA = "metadata";

    private static final String PATTERN = "pattern";
    private static final String B = "\\b";

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AdvancedAccountDetectionService.class);

    private final OCRService ocrService;
    private final FormFieldDetectionService formFieldDetectionService;
    private final TableStructureDetectionService tableStructureDetectionService;

    public AdvancedAccountDetectionService(
            final OCRService ocrService,
            final FormFieldDetectionService formFieldDetectionService,
            final TableStructureDetectionService tableStructureDetectionService) {
        this.ocrService = ocrService;
        this.formFieldDetectionService = formFieldDetectionService;
        this.tableStructureDetectionService = tableStructureDetectionService;
    }

    // Global account number patterns (various formats)
    private static final List<Pattern> ACCOUNT_NUMBER_PATTERNS =
            Arrays.asList(
                    // Standard formats
                    Pattern.compile(
                            "(?:account|acct|card|konto|compte|cuenta|conto|口座|账户|계좌)\\s*(?:number|#|no\\.?|nummer|numéro|número|numero|番号|号码|번호)?\\s*(?:ending\\s*(?:in|with)?\\s*:?\\s*|with\\s*(?:last\\s*)?(?:4\\s*)?(?:digits?|numbers?|ziffern|chiffres|dígitos|cifre|桁|数字|자릿수)?\\s*:?\\s*)?([*xX\\s]{0,4}\\d{4,19})",
                            Pattern.CASE_INSENSITIVE),
                    // IBAN format (International Bank Account Number)
                    Pattern.compile(
                            "\\b([A-Z]{2}\\d{2}[A-Z0-9]{4,30})\\b", Pattern.CASE_INSENSITIVE),
                    // Account number with separators (e.g., 1234-5678-9012)
                    Pattern.compile(
                            "(?:account|acct|card)\\s*(?:number|#|no\\.?)?\\s*:?\\s*([\\d\\s-]{8,19})",
                            Pattern.CASE_INSENSITIVE),
                    // Masked account numbers (e.g., ****1234, XXXX5678)
                    Pattern.compile("([*xX\\s]{4,12}\\d{4})", Pattern.CASE_INSENSITIVE),
                    // 4 digits at end of filename (e.g., "chase_checking_1234.csv" -> "1234")
                    Pattern.compile(
                            "(?:^|[_\\-\\s])(\\d{4})(?:[_\\-\\s]|$|\\.)", Pattern.CASE_INSENSITIVE),
                    // Last 4 digits standalone
                    Pattern.compile(
                            "\\b(\\d{4})\\b(?=.*(?:account|acct|card|ending|last\\s*4))",
                            Pattern.CASE_INSENSITIVE),
                    // Full account number (8-19 digits)
                    Pattern.compile(
                            "\\b(\\d{8,19})\\b(?=.*(?:account|acct|card))",
                            Pattern.CASE_INSENSITIVE));

    // Global institution name patterns and aliases
    private static final Map<String, List<String>> INSTITUTION_ALIASES = new HashMap<>();

    static {
        // US Banks
        INSTITUTION_ALIASES.put(
                "chase", Arrays.asList("jpmorgan chase", "jpm chase", "chase bank", "chase.com"));
        INSTITUTION_ALIASES.put(
                "bank of america", Arrays.asList("bofa", "b of a", "boa", "bankofamerica"));
        INSTITUTION_ALIASES.put("wells fargo", Arrays.asList("wellsfargo", "wf", "wells"));
        INSTITUTION_ALIASES.put("citibank", Arrays.asList("citi", "citibank", "citigroup"));
        INSTITUTION_ALIASES.put(
                "capital one", Arrays.asList("capone", "capitol one", "capitalone"));
        INSTITUTION_ALIASES.put(
                "american express", Arrays.asList("amex", "americanexpress", "am ex"));

        // US Investment/Wealth Management
        INSTITUTION_ALIASES.put(
                "fidelity",
                Arrays.asList("fidelity investments", "fidelity.com", "fidelity netbenefits"));
        INSTITUTION_ALIASES.put("schwab", Arrays.asList("charles schwab", "schwab.com"));
        INSTITUTION_ALIASES.put("vanguard", Arrays.asList("vanguard group", "vanguard.com"));

        // UK Banks
        INSTITUTION_ALIASES.put("hsbc", Arrays.asList("hsbc bank", "hsbc uk", "hongkong shanghai"));
        INSTITUTION_ALIASES.put("barclays", Arrays.asList("barclays bank", "barclaycard"));
        INSTITUTION_ALIASES.put("lloyds", Arrays.asList("lloyds bank", "lloyds banking group"));
        INSTITUTION_ALIASES.put("natwest", Arrays.asList("national westminster", "nat west"));
        INSTITUTION_ALIASES.put(
                "royal bank of scotland", Arrays.asList("rbs", "royal bank scotland"));

        // European Banks
        INSTITUTION_ALIASES.put("deutsche bank", Arrays.asList("db", "deutsche", "db ag"));
        INSTITUTION_ALIASES.put("bnp paribas", Arrays.asList("bnp", "bnpparibas"));
        INSTITUTION_ALIASES.put(
                "credit agricole", Arrays.asList("ca", "credit agricole", "caisse d'epargne"));
        INSTITUTION_ALIASES.put(
                "societe generale", Arrays.asList("socgen", "societe generale", "sg"));

        // Indian Banks
        INSTITUTION_ALIASES.put("state bank of india", Arrays.asList("sbi", "state bank"));
        INSTITUTION_ALIASES.put("icici", Arrays.asList("icici bank", "icicibank"));
        INSTITUTION_ALIASES.put("hdfc", Arrays.asList("hdfc bank", "hdfcbank"));
        INSTITUTION_ALIASES.put("axis bank", Arrays.asList("axis", "axisbank"));

        // Chinese Banks
        INSTITUTION_ALIASES.put(
                "industrial and commercial bank of china", Arrays.asList("icbc", "工商银行"));
        INSTITUTION_ALIASES.put("china construction bank", Arrays.asList("ccb", "建设银行"));
        INSTITUTION_ALIASES.put("bank of china", Arrays.asList("boc", "中国银行"));
        INSTITUTION_ALIASES.put("agricultural bank of china", Arrays.asList("abc", "农业银行"));

        // Japanese Banks
        INSTITUTION_ALIASES.put(
                "mitsubishi ufj financial group", Arrays.asList("mufg", "mitsubishi ufj", "三菱ufj"));
        INSTITUTION_ALIASES.put("mizuho financial group", Arrays.asList("mizuho", "みずほ"));
        INSTITUTION_ALIASES.put(
                "sumitomo mitsui financial group",
                Arrays.asList("smbc", "sumitomo mitsui", "三井住友"));
    }

    // Global account type patterns (multilingual)
    private static final Map<String, String> ACCOUNT_TYPE_PATTERNS = new HashMap<>();

    static {
        // English
        ACCOUNT_TYPE_PATTERNS.put("checking", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("current", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("savings", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("credit card", CREDIT);
        ACCOUNT_TYPE_PATTERNS.put("creditcard", CREDIT);
        ACCOUNT_TYPE_PATTERNS.put("loan", "loan");
        ACCOUNT_TYPE_PATTERNS.put("mortgage", "loan");
        ACCOUNT_TYPE_PATTERNS.put(INVESTMENT, INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("brokerage", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("401k", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("401(k)", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("403b", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("403(b)", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("ira", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("roth", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("roth ira", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("traditional ira", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("sep ira", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("simple ira", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("529", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("529 plan", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("hsa", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("health savings account", INVESTMENT);

        // German
        ACCOUNT_TYPE_PATTERNS.put("girokonto", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("sparkonto", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("kreditkarte", CREDIT);

        // French
        ACCOUNT_TYPE_PATTERNS.put("compte courant", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("compte epargne", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("carte de credit", CREDIT);

        // Spanish
        ACCOUNT_TYPE_PATTERNS.put("cuenta corriente", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("cuenta de ahorros", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("tarjeta de credito", CREDIT);

        // Italian
        ACCOUNT_TYPE_PATTERNS.put("conto corrente", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("conto di risparmio", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("carta di credito", CREDIT);

        // Japanese
        ACCOUNT_TYPE_PATTERNS.put("当座預金", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("普通預金", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("クレジットカード", CREDIT);

        // Chinese
        ACCOUNT_TYPE_PATTERNS.put("活期账户", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("储蓄账户", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("信用卡", CREDIT);

        // Korean
        ACCOUNT_TYPE_PATTERNS.put("당좌예금", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("저축예금", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("신용카드", CREDIT);

        // Portuguese
        ACCOUNT_TYPE_PATTERNS.put("conta corrente", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("conta poupança", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("cartão de crédito", CREDIT);

        // Russian
        ACCOUNT_TYPE_PATTERNS.put("текущий счет", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("сберегательный счет", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("кредитная карта", CREDIT);

        // Arabic
        ACCOUNT_TYPE_PATTERNS.put("حساب جاري", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("حساب توفير", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("بطاقة ائتمان", CREDIT);

        // Hindi
        ACCOUNT_TYPE_PATTERNS.put("चालू खाता", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("बचत खाता", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("क्रेडिट कार्ड", CREDIT);

        // Dutch
        ACCOUNT_TYPE_PATTERNS.put("betaalrekening", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("spaarrekening", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("creditcard", CREDIT);

        // Polish
        ACCOUNT_TYPE_PATTERNS.put("rachunek bieżący", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("konto oszczędnościowe", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("karta kredytowa", CREDIT);

        // Turkish
        ACCOUNT_TYPE_PATTERNS.put("vadesiz hesap", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("tasarruf hesabı", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("kredi kartı", CREDIT);

        // Vietnamese
        ACCOUNT_TYPE_PATTERNS.put("tài khoản vãng lai", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("tài khoản tiết kiệm", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("thẻ tín dụng", CREDIT);

        // Thai
        ACCOUNT_TYPE_PATTERNS.put("บัญชีกระแสรายวัน", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("บัญชีออมทรัพย์", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("บัตรเครดิต", CREDIT);

        // Indonesian
        ACCOUNT_TYPE_PATTERNS.put("rekening giro", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("rekening tabungan", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("kartu kredit", CREDIT);

        // Malay
        ACCOUNT_TYPE_PATTERNS.put("akaun semasa", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("akaun simpanan", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("kad kredit", CREDIT);
    }

    /** Detection result with confidence score */
    public static class DetectionResult {
        private String value;
        private double confidence; // 0.0 to 1.0
        private String source; // "filename", "header", DATA, "metadata"
        private String method; // "pattern", "fuzzy", "statistical", "context"

        public DetectionResult(
                final String value,
                final double confidence,
                final String source,
                final String method) {
            this.value = value;
            this.confidence = confidence;
            this.source = source;
            this.method = method;
        }

        public String getValue() {
            return value;
        }

        public double getConfidence() {
            return confidence;
        }

        public String getSource() {
            return source;
        }

        public String getMethod() {
            return method;
        }
    }

    /** Comprehensive account detection from multiple sources Now supports OCR for scanned PDFs */
    public AccountDetectionService.DetectedAccount detectAccount(
            String filename,
            List<String> headers,
            List<List<String>> dataRows,
            Map<String, String> metadata) {

        if (filename == null) {
            filename = "";
        }
        if (headers == null) {
            headers = new ArrayList<>();
        }
        if (dataRows == null) {
            dataRows = new ArrayList<>();
        }
        if (metadata == null) {
            metadata = new HashMap<>();
        }

        final AccountDetectionService.DetectedAccount detected =
                new AccountDetectionService.DetectedAccount();

        // Multi-source detection with confidence scoring
        final DetectionResult accountNumber =
                detectAccountNumber(filename, headers, dataRows, metadata);
        final DetectionResult institutionName =
                detectInstitutionName(filename, headers, dataRows, metadata);
        final DetectionResult accountType =
                detectAccountType(filename, headers, dataRows, metadata);
        final DetectionResult accountName =
                detectAccountName(
                        filename, headers, dataRows, metadata, institutionName, accountType);

        // Apply results with confidence threshold (0.5)
        if (accountNumber != null && accountNumber.getConfidence() >= 0.5) {
            final String detectedNumber = accountNumber.getValue();
            // Preserve the full form alongside the short display form. Today
            // both fields end up equal (last-4 only) because the extractor
            // truncates; future extractor improvements that preserve more
            // digits will automatically populate the full field.
            detected.setAccountNumber(detectedNumber);
            detected.setFullAccountNumber(detectedNumber);
            // PII-hygiene: log only the mask, never the raw number.
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Detected account number: {} (confidence: {}, source: {}, method: {})",
                        PrivacyRedaction.maskAccountNumber(detectedNumber),
                        accountNumber.getConfidence(),
                        accountNumber.getSource(),
                        accountNumber.getMethod());
            }
        }

        if (institutionName != null && institutionName.getConfidence() >= 0.5) {
            detected.setInstitutionName(institutionName.getValue());
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Detected institution: {} (confidence: {}, source: {}, method: {})",
                        institutionName.getValue(),
                        institutionName.getConfidence(),
                        institutionName.getSource(),
                        institutionName.getMethod());
            }
        }

        if (accountType != null && accountType.getConfidence() >= 0.5) {
            detected.setAccountType(accountType.getValue());
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Detected account type: {} (confidence: {}, source: {}, method: {})",
                        accountType.getValue(),
                        accountType.getConfidence(),
                        accountType.getSource(),
                        accountType.getMethod());
            }
        }

        if (accountName != null && accountName.getConfidence() >= 0.5) {
            detected.setAccountName(accountName.getValue());
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Detected account name: {} (confidence: {}, source: {}, method: {})",
                        accountName.getValue(),
                        accountName.getConfidence(),
                        accountName.getSource(),
                        accountName.getMethod());
            }
        }

        return detected;
    }

    /** Detect account number using multiple techniques */
    private DetectionResult detectAccountNumber(
            final String filename,
            final List<String> headers,
            final List<List<String>> dataRows,
            final Map<String, String> metadata) {
        final List<DetectionResult> candidates = new ArrayList<>();

        // 1. Pattern matching in filename
        String accountNum = extractAccountNumberFromText(filename);
        if (accountNum != null) {
            candidates.add(new DetectionResult(accountNum, 0.7, FILENAME, PATTERN));
        }

        // 2. Pattern matching in headers
        if (headers != null && !headers.isEmpty()) {
            // CRITICAL FIX: Filter null headers before joining
            final List<String> nonNullHeaders = new ArrayList<>();
            for (final String h : headers) {
                if (h != null) {
                    nonNullHeaders.add(h);
                }
            }

            if (!nonNullHeaders.isEmpty()) {
                final String headerText = String.join(" ", nonNullHeaders);
                accountNum = extractAccountNumberFromText(headerText);
                if (accountNum != null) {
                    candidates.add(new DetectionResult(accountNum, 0.8, HEADER, PATTERN));
                }
            }

            // Check for account number columns
            for (int i = 0; i < headers.size(); i++) {
                // CRITICAL FIX: Bounds check
                if (i >= headers.size()) {
                    break;
                }

                final String header = headers.get(i);
                if (header != null && isAccountNumberColumn(header)) {
                    // Extract from first few data rows
                    final String rawAccountNum = extractFromDataColumn(dataRows, i, 5);
                    if (rawAccountNum != null) {
                        // CRITICAL: Process through extractAccountNumberFromText to handle masked
                        // formats (****1234)
                        accountNum = extractAccountNumberFromText(rawAccountNum);
                        if (accountNum != null) {
                            candidates.add(new DetectionResult(accountNum, 0.9, DATA, COLUMN));
                        }
                    }
                }
            }
        }

        // 3. Statistical analysis of data rows
        if (dataRows != null && !dataRows.isEmpty()) {
            accountNum = extractAccountNumberStatistically(dataRows);
            if (accountNum != null) {
                candidates.add(new DetectionResult(accountNum, 0.75, DATA, "statistical"));
            }
        }

        // 4. Metadata analysis
        if (metadata != null && !metadata.isEmpty()) {
            for (final String value : metadata.values()) {
                if (value != null && !value.isBlank()) {
                    accountNum = extractAccountNumberFromText(value);
                    if (accountNum != null) {
                        candidates.add(new DetectionResult(accountNum, 0.6, METADATA, PATTERN));
                    }
                }
            }
        }

        // Return highest confidence result
        return candidates.stream()
                .max(Comparator.comparing(DetectionResult::getConfidence))
                .orElse(null);
    }

    /** Detect institution name using fuzzy matching and aliases */
    private DetectionResult detectInstitutionName(
            final String filename,
            final List<String> headers,
            final List<List<String>> dataRows,
            final Map<String, String> metadata) {
        final List<DetectionResult> candidates = new ArrayList<>();

        // 1. Exact match in filename
        String institution = detectInstitutionFromText(filename);
        if (institution != null) {
            candidates.add(new DetectionResult(institution, 0.8, FILENAME, EXACT));
        }

        // 2. Fuzzy match in filename
        institution = fuzzyMatchInstitution(filename);
        if (institution != null) {
            candidates.add(new DetectionResult(institution, 0.7, FILENAME, "fuzzy"));
        }

        // 3. Header analysis
        if (headers != null) {
            final String headerText = String.join(" ", headers);
            institution = detectInstitutionFromText(headerText);
            if (institution != null) {
                candidates.add(new DetectionResult(institution, 0.85, HEADER, EXACT));
            }

            institution = fuzzyMatchInstitution(headerText);
            if (institution != null) {
                candidates.add(new DetectionResult(institution, 0.75, HEADER, "fuzzy"));
            }

            // Check for institution columns
            for (int i = 0; i < headers.size(); i++) {
                final String header = headers.get(i);
                if (isInstitutionColumn(header)) {
                    final String value = extractFromDataColumn(dataRows, i, 1);
                    if (value != null) {
                        institution = detectInstitutionFromText(value);
                        if (institution != null) {
                            candidates.add(new DetectionResult(institution, 0.9, DATA, COLUMN));
                        }
                    }
                }
            }
        }

        // 4. Metadata analysis
        if (metadata != null) {
            for (final String value : metadata.values()) {
                institution = detectInstitutionFromText(value);
                if (institution != null) {
                    candidates.add(new DetectionResult(institution, 0.7, METADATA, EXACT));
                }
            }
        }

        // Return highest confidence result
        return candidates.stream()
                .max(Comparator.comparing(DetectionResult::getConfidence))
                .orElse(null);
    }

    /** Detect account type using multiple techniques */
    private DetectionResult detectAccountType(
            final String filename,
            final List<String> headers,
            final List<List<String>> dataRows,
            final Map<String, String> metadata) {
        final List<DetectionResult> candidates = new ArrayList<>();

        // 1. Pattern matching in filename
        String accountType = detectAccountTypeFromText(filename);
        if (accountType != null) {
            candidates.add(new DetectionResult(accountType, 0.7, FILENAME, PATTERN));
        }

        // 2. Header analysis
        if (headers != null && !headers.isEmpty()) {
            // CRITICAL FIX: Filter null headers before joining
            final List<String> nonNullHeaders = new ArrayList<>();
            for (final String h : headers) {
                if (h != null) {
                    nonNullHeaders.add(h);
                }
            }

            if (!nonNullHeaders.isEmpty()) {
                final String headerText = String.join(" ", nonNullHeaders);
                accountType = detectAccountTypeFromText(headerText);
                if (accountType != null) {
                    candidates.add(new DetectionResult(accountType, 0.8, HEADER, PATTERN));
                }
            }

            // Check for account type columns
            for (int i = 0; i < headers.size(); i++) {
                // CRITICAL FIX: Bounds check
                if (i >= headers.size()) {
                    break;
                }

                final String header = headers.get(i);
                if (header != null && isAccountTypeColumn(header)) {
                    final String value = extractFromDataColumn(dataRows, i, 1);
                    if (value != null && !value.isBlank()) {
                        accountType = detectAccountTypeFromText(value);
                        if (accountType != null) {
                            candidates.add(new DetectionResult(accountType, 0.9, DATA, COLUMN));
                        }
                    }
                }
            }
        }

        // 3. Transaction pattern analysis
        accountType = inferAccountTypeFromTransactionPatterns(dataRows);
        if (accountType != null) {
            candidates.add(new DetectionResult(accountType, 0.75, DATA, "statistical"));
        }

        // 4. Metadata analysis
        if (metadata != null) {
            for (final String value : metadata.values()) {
                accountType = detectAccountTypeFromText(value);
                if (accountType != null) {
                    candidates.add(new DetectionResult(accountType, 0.6, METADATA, PATTERN));
                }
            }
        }

        // Return highest confidence result
        return candidates.stream()
                .max(Comparator.comparing(DetectionResult::getConfidence))
                .orElse(null);
    }

    /** Detect account name using context from institution and account type */
    private DetectionResult detectAccountName(
            final String filename,
            final List<String> headers,
            final List<List<String>> dataRows,
            Map<String, String> metadata,
            final DetectionResult institutionName,
            final DetectionResult accountType) {
        final List<DetectionResult> candidates = new ArrayList<>();

        // 1. Construct from detected institution and type
        if (institutionName != null && accountType != null) {
            final String accountName =
                    generateAccountName(institutionName.getValue(), accountType.getValue());
            candidates.add(new DetectionResult(accountName, 0.8, "constructed", "context"));
        }

        // 2. Extract from headers
        if (headers != null) {
            for (int i = 0; i < headers.size(); i++) {
                final String header = headers.get(i);
                if (isAccountNameColumn(header)) {
                    final String value = extractFromDataColumn(dataRows, i, 1);
                    if (value != null && !value.isBlank()) {
                        candidates.add(new DetectionResult(value.trim(), 0.9, DATA, COLUMN));
                    }
                }
            }
        }

        // 3. Extract from filename
        final String accountName = extractAccountNameFromFilename(filename);
        if (accountName != null) {
            candidates.add(new DetectionResult(accountName, 0.7, FILENAME, PATTERN));
        }

        // Return highest confidence result
        return candidates.stream()
                .max(Comparator.comparing(DetectionResult::getConfidence))
                .orElse(null);
    }

    // Helper methods

    private String extractAccountNumberFromText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        // CRITICAL FIX: Limit text length to prevent performance issues
        final int MAX_TEXT_LENGTH = 10_000;
        if (text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH);
        }

        for (final Pattern pattern : ACCOUNT_NUMBER_PATTERNS) {
            try {
                final Matcher matcher = pattern.matcher(text);
                if (matcher.find()) {
                    // CRITICAL FIX: Check group count before accessing
                    if (matcher.groupCount() >= 1) {
                        String accountNum = matcher.group(1);
                        if (accountNum != null) {
                            accountNum = accountNum.replaceAll("[*xX\\s-]", "");
                            if (accountNum.length() >= 4) {
                                // Extract last 4 digits
                                final int startIndex = Math.max(0, accountNum.length() - 4);
                                return accountNum.substring(startIndex);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "Error extracting account number with pattern: {}", e.getMessage());
                }
                // Continue with next pattern
            }
        }
        return null;
    }

    private String detectInstitutionFromText(final String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        // CRITICAL: Normalize underscores and hyphens to spaces for better matching
        // e.g., "wells_fargo" should match "wells fargo" keyword
        final String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[_\\-]", " ");
        final String textLower = normalized;

        // CRITICAL: Check aliases first (more specific) before canonical names
        // This prevents false positives like "credit" matching "credit agricole" when "amex" is
        // present
        final Map<String, String> aliasToCanonical = new HashMap<>();
        for (final Map.Entry<String, List<String>> entry : INSTITUTION_ALIASES.entrySet()) {
            final String canonical = entry.getKey();
            for (final String alias : entry.getValue()) {
                aliasToCanonical.put(alias.toLowerCase(Locale.ROOT), canonical);
            }
        }

        // Sort aliases by length (longer/more specific first), then alphabetically for consistency
        final List<String> sortedAliases = new ArrayList<>(aliasToCanonical.keySet());
        sortedAliases.sort(
                (a, b) -> {
                    final int lenDiff = b.length() - a.length();
                    if (lenDiff != 0) {
                        return lenDiff;
                    }
                    return a.compareTo(b);
                });

        // Check aliases first (more specific matches)
        for (final String alias : sortedAliases) {
            final String normalizedAlias = alias.toLowerCase(Locale.ROOT).replaceAll("[_\\-]", " ");
            // Use word boundary matching for short aliases to avoid false positives
            if (normalizedAlias.length() <= 4) {
                // For short aliases like "amex", use word boundary
                final Pattern pattern =
                        Pattern.compile(
                                "\\b" + Pattern.quote(normalizedAlias) + "\\b",
                                Pattern.CASE_INSENSITIVE);
                if (pattern.matcher(textLower).find()) {
                    return aliasToCanonical.get(alias.toLowerCase(Locale.ROOT));
                }
            } else {
                // For longer aliases, use contains
                if (textLower.contains(normalizedAlias)) {
                    return aliasToCanonical.get(alias.toLowerCase(Locale.ROOT));
                }
            }
        }

        // Then check canonical names (less specific, but still valid)
        // Sort by length (longer first) to prioritize specific matches
        final List<Map.Entry<String, List<String>>> sortedEntries =
                new ArrayList<>(INSTITUTION_ALIASES.entrySet());
        sortedEntries.sort((a, b) -> b.getKey().length() - a.getKey().length());

        for (final Map.Entry<String, List<String>> entry : sortedEntries) {
            final String canonical = entry.getKey();
            final String normalizedCanonical =
                    canonical.toLowerCase(Locale.ROOT).replaceAll("[_\\-]", " ");
            // For canonical names, use word boundary if short, contains if long
            if (normalizedCanonical.length() <= 5) {
                final Pattern pattern =
                        Pattern.compile(
                                "\\b" + Pattern.quote(normalizedCanonical) + "\\b",
                                Pattern.CASE_INSENSITIVE);
                if (pattern.matcher(textLower).find()) {
                    return canonical;
                }
            } else {
                if (textLower.contains(normalizedCanonical)) {
                    return canonical;
                }
            }
        }
        return null;
    }

    private String fuzzyMatchInstitution(final String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        final String textLower = text.toLowerCase(Locale.ROOT);
        double bestScore = 0.0;
        String bestMatch = null;

        for (final String institution : INSTITUTION_ALIASES.keySet()) {
            final double score = calculateSimilarity(textLower, institution);
            if (score > 0.7 && score > bestScore) {
                bestScore = score;
                bestMatch = institution;
            }
        }

        return bestMatch;
    }

    private double calculateSimilarity(final String s1, final String s2) {
        // Simple Levenshtein-based similarity
        final int maxLen = Math.max(s1.length(), s2.length());
        if (maxLen == 0) {
            return 1.0;
        }

        final int distance = levenshteinDistance(s1, s2);
        return 1.0 - (double) distance / maxLen;
    }

    private int levenshteinDistance(final String s1, final String s2) {
        final int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) {
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                } else if (j == 0) {
                    dp[i][j] = i;
                } else {
                    dp[i][j] =
                            Math.min(
                                    Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                                    dp[i - 1][j - 1]
                                            + (s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1));
                }
            }
        }

        return dp[s1.length()][s2.length()];
    }

    private String detectAccountTypeFromText(final String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        // CRITICAL: Normalize underscores and hyphens to spaces for better matching
        // e.g., "credit_card" should match "credit card" pattern
        final String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[_\\-]", " ");
        final String textLower = normalized;

        for (final Map.Entry<String, String> entry : ACCOUNT_TYPE_PATTERNS.entrySet()) {
            // Normalize pattern key as well
            final String normalizedKey =
                    entry.getKey().toLowerCase(Locale.ROOT).replaceAll("[_\\-]", " ");
            if (textLower.contains(normalizedKey)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String inferAccountTypeFromTransactionPatterns(final List<List<String>> dataRows) {
        if (dataRows == null || dataRows.isEmpty()) {
            return null;
        }

        int checkCount = 0;
        int debitCount = 0;
        int creditCount = 0;
        int achCount = 0;

        for (final List<String> row : dataRows.subList(0, Math.min(20, dataRows.size()))) {
            if (row == null) {
                continue; // CRITICAL: Skip null rows
                // CRITICAL: Filter out null elements before joining to avoid NullPointerException
            }
            final String rowText =
                    row.stream()
                            .filter(Objects::nonNull)
                            .collect(java.util.stream.Collectors.joining(" "))
                            .toLowerCase(Locale.ROOT);
            if (rowText.contains("check") || rowText.contains("chk")) {
                checkCount++;
            }
            if (rowText.contains("debit") || rowText.contains(" db ")) {
                debitCount++;
            }
            if (rowText.contains(CREDIT) && !rowText.contains("card")) {
                creditCount++;
            }
            if (rowText.contains("ach") || rowText.contains("direct deposit")) {
                achCount++;
            }
        }

        if (checkCount > 0) {
            return "depository";
        }
        if (achCount > 2 || (debitCount > 0 && creditCount > 0)) {
            return "depository";
        }

        return null;
    }

    private boolean isAccountNumberColumn(final String header) {
        if (header == null) {
            return false;
        }
        final String headerLower = header.toLowerCase(Locale.ROOT);
        return headerLower.contains("account number")
                || headerLower.contains("account #")
                || headerLower.contains("card number")
                || headerLower.contains("card #")
                || headerLower.contains("account no")
                || headerLower.contains("acct number");
    }

    private boolean isInstitutionColumn(final String header) {
        if (header == null) {
            return false;
        }
        final String headerLower = header.toLowerCase(Locale.ROOT);
        return headerLower.contains("institution")
                || headerLower.contains("bank")
                || headerLower.contains("issuer")
                || headerLower.contains("financial institution");
    }

    private boolean isAccountTypeColumn(final String header) {
        if (header == null) {
            return false;
        }
        final String headerLower = header.toLowerCase(Locale.ROOT);
        return headerLower.contains("account type")
                || headerLower.contains("type")
                || headerLower.contains("category")
                || headerLower.contains("product type");
    }

    private boolean isAccountNameColumn(final String header) {
        if (header == null) {
            return false;
        }
        final String headerLower = header.toLowerCase(Locale.ROOT);
        return headerLower.contains("account name")
                || headerLower.contains("accountname")
                || headerLower.contains("product name")
                || headerLower.contains("card name");
    }

    private String extractFromDataColumn(
            final List<List<String>> dataRows, final int columnIndex, final int maxRows) {
        if (dataRows == null || dataRows.isEmpty() || columnIndex < 0) {
            return null;
        }

        final int rowsToCheck = Math.min(maxRows, dataRows.size());
        for (int i = 0; i < rowsToCheck; i++) {
            // CRITICAL FIX: Bounds check
            if (i >= dataRows.size()) {
                break;
            }

            final List<String> row = dataRows.get(i);
            if (row == null) {
                continue;
            }

            // CRITICAL FIX: Bounds check for column index
            if (columnIndex < row.size()) {
                final String value = row.get(columnIndex);
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    private String extractAccountNumberStatistically(final List<List<String>> dataRows) {
        if (dataRows == null || dataRows.isEmpty()) {
            return null;
        }

        // Count occurrences of 4-digit numbers that appear consistently
        final Map<String, Integer> numberCounts = new HashMap<>();

        for (final List<String> row : dataRows.subList(0, Math.min(20, dataRows.size()))) {
            if (row == null) {
                continue; // CRITICAL: Skip null rows
            }
            for (final String cell : row) {
                if (cell != null) {
                    final Pattern pattern = Pattern.compile("\\b(\\d{4})\\b");
                    final Matcher matcher = pattern.matcher(cell);
                    while (matcher.find()) {
                        final String num = matcher.group(1);
                        numberCounts.put(num, numberCounts.getOrDefault(num, 0) + 1);
                    }
                }
            }
        }

        // Return number that appears most frequently (likely account number)
        return numberCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .filter(e -> e.getValue() >= 2) // Must appear at least twice
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private String generateAccountName(final String institution, final String accountType) {
        final StringBuilder name = new StringBuilder();
        if (institution != null && !institution.isEmpty()) {
            name.append(institution);
        }
        if (accountType != null && !accountType.isEmpty()) {
            if (name.length() > 0) {
                name.append(' ');
            }
            name.append(accountType);
        }
        return name.toString().trim();
    }

    private String extractAccountNameFromFilename(final String filename) {
        if (filename == null) {
            return null;
        }

        // Remove extension
        String name = filename.replaceAll("\\.[^.]+$", "");
        // Remove common separators
        name = name.replaceAll("[_\\-]", " ");
        return name.trim();
    }
}
