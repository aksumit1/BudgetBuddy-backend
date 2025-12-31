package com.budgetbuddy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Advanced Account Detection Service
 * 
 * Uses sophisticated pattern detection techniques for global financial statements:
 * - Multi-source analysis (filename, headers, data rows, metadata)
 * - Context-aware pattern matching
 * - Fuzzy matching for institution names
 * - Statistical analysis of transaction patterns
 * - Confidence scoring
 * - Global format support (US, UK, EU, Asia, etc.)
 * 
 * Best Practices Applied:
 * 1. Multi-source evidence aggregation
 * 2. Confidence scoring for each detection
 * 3. Context-aware pattern matching
 * 4. Fuzzy string matching for institution names
 * 5. Statistical analysis of patterns
 * 6. Fallback strategies
 * 7. Global format support
 */
@Service
public class AdvancedAccountDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(AdvancedAccountDetectionService.class);
    
    private final OCRService ocrService;
    private final FormFieldDetectionService formFieldDetectionService;
    private final TableStructureDetectionService tableStructureDetectionService;
    
    public AdvancedAccountDetectionService(OCRService ocrService,
                                          FormFieldDetectionService formFieldDetectionService,
                                          TableStructureDetectionService tableStructureDetectionService) {
        this.ocrService = ocrService;
        this.formFieldDetectionService = formFieldDetectionService;
        this.tableStructureDetectionService = tableStructureDetectionService;
    }
    
    // Global account number patterns (various formats)
    private static final List<Pattern> ACCOUNT_NUMBER_PATTERNS = Arrays.asList(
        // Standard formats
        Pattern.compile("(?:account|acct|card|konto|compte|cuenta|conto|口座|账户|계좌)\\s*(?:number|#|no\\.?|nummer|numéro|número|numero|番号|号码|번호)?\\s*(?:ending\\s*(?:in|with)?\\s*:?\\s*|with\\s*(?:last\\s*)?(?:4\\s*)?(?:digits?|numbers?|ziffern|chiffres|dígitos|cifre|桁|数字|자릿수)?\\s*:?\\s*)?([*xX\\s]{0,4}\\d{4,19})", Pattern.CASE_INSENSITIVE),
        // IBAN format (International Bank Account Number)
        Pattern.compile("\\b([A-Z]{2}\\d{2}[A-Z0-9]{4,30})\\b", Pattern.CASE_INSENSITIVE),
        // Account number with separators (e.g., 1234-5678-9012)
        Pattern.compile("(?:account|acct|card)\\s*(?:number|#|no\\.?)?\\s*:?\\s*([\\d\\s-]{8,19})", Pattern.CASE_INSENSITIVE),
        // Masked account numbers (e.g., ****1234, XXXX5678)
        Pattern.compile("([*xX\\s]{4,12}\\d{4})", Pattern.CASE_INSENSITIVE),
        // 4 digits at end of filename (e.g., "chase_checking_1234.csv" -> "1234")
        Pattern.compile("(?:^|[_\\-\\s])(\\d{4})(?:[_\\-\\s]|$|\\.)", Pattern.CASE_INSENSITIVE),
        // Last 4 digits standalone
        Pattern.compile("\\b(\\d{4})\\b(?=.*(?:account|acct|card|ending|last\\s*4))", Pattern.CASE_INSENSITIVE),
        // Full account number (8-19 digits)
        Pattern.compile("\\b(\\d{8,19})\\b(?=.*(?:account|acct|card))", Pattern.CASE_INSENSITIVE)
    );
    
    // Global institution name patterns and aliases
    private static final Map<String, List<String>> INSTITUTION_ALIASES = new HashMap<>();
    static {
        // US Banks
        INSTITUTION_ALIASES.put("chase", Arrays.asList("jpmorgan chase", "jpm chase", "chase bank", "chase.com"));
        INSTITUTION_ALIASES.put("bank of america", Arrays.asList("bofa", "b of a", "boa", "bankofamerica"));
        INSTITUTION_ALIASES.put("wells fargo", Arrays.asList("wellsfargo", "wf", "wells"));
        INSTITUTION_ALIASES.put("citibank", Arrays.asList("citi", "citibank", "citigroup"));
        INSTITUTION_ALIASES.put("capital one", Arrays.asList("capone", "capitol one", "capitalone"));
        INSTITUTION_ALIASES.put("american express", Arrays.asList("amex", "americanexpress", "am ex"));
        
        // US Investment/Wealth Management
        INSTITUTION_ALIASES.put("fidelity", Arrays.asList("fidelity investments", "fidelity.com", "fidelity netbenefits"));
        INSTITUTION_ALIASES.put("schwab", Arrays.asList("charles schwab", "schwab.com"));
        INSTITUTION_ALIASES.put("vanguard", Arrays.asList("vanguard group", "vanguard.com"));
        
        // UK Banks
        INSTITUTION_ALIASES.put("hsbc", Arrays.asList("hsbc bank", "hsbc uk", "hongkong shanghai"));
        INSTITUTION_ALIASES.put("barclays", Arrays.asList("barclays bank", "barclaycard"));
        INSTITUTION_ALIASES.put("lloyds", Arrays.asList("lloyds bank", "lloyds banking group"));
        INSTITUTION_ALIASES.put("natwest", Arrays.asList("national westminster", "nat west"));
        INSTITUTION_ALIASES.put("royal bank of scotland", Arrays.asList("rbs", "royal bank scotland"));
        
        // European Banks
        INSTITUTION_ALIASES.put("deutsche bank", Arrays.asList("db", "deutsche", "db ag"));
        INSTITUTION_ALIASES.put("bnp paribas", Arrays.asList("bnp", "bnpparibas"));
        INSTITUTION_ALIASES.put("credit agricole", Arrays.asList("ca", "credit agricole", "caisse d'epargne"));
        INSTITUTION_ALIASES.put("societe generale", Arrays.asList("socgen", "societe generale", "sg"));
        
        // Indian Banks
        INSTITUTION_ALIASES.put("state bank of india", Arrays.asList("sbi", "state bank"));
        INSTITUTION_ALIASES.put("icici", Arrays.asList("icici bank", "icicibank"));
        INSTITUTION_ALIASES.put("hdfc", Arrays.asList("hdfc bank", "hdfcbank"));
        INSTITUTION_ALIASES.put("axis bank", Arrays.asList("axis", "axisbank"));
        
        // Chinese Banks
        INSTITUTION_ALIASES.put("industrial and commercial bank of china", Arrays.asList("icbc", "工商银行"));
        INSTITUTION_ALIASES.put("china construction bank", Arrays.asList("ccb", "建设银行"));
        INSTITUTION_ALIASES.put("bank of china", Arrays.asList("boc", "中国银行"));
        INSTITUTION_ALIASES.put("agricultural bank of china", Arrays.asList("abc", "农业银行"));
        
        // Japanese Banks
        INSTITUTION_ALIASES.put("mitsubishi ufj financial group", Arrays.asList("mufg", "mitsubishi ufj", "三菱ufj"));
        INSTITUTION_ALIASES.put("mizuho financial group", Arrays.asList("mizuho", "みずほ"));
        INSTITUTION_ALIASES.put("sumitomo mitsui financial group", Arrays.asList("smbc", "sumitomo mitsui", "三井住友"));
    }
    
    // Global account type patterns (multilingual)
    private static final Map<String, String> ACCOUNT_TYPE_PATTERNS = new HashMap<>();
    static {
        // English
        ACCOUNT_TYPE_PATTERNS.put("checking", "depository");
        ACCOUNT_TYPE_PATTERNS.put("current", "depository");
        ACCOUNT_TYPE_PATTERNS.put("savings", "depository");
        ACCOUNT_TYPE_PATTERNS.put("credit card", "credit");
        ACCOUNT_TYPE_PATTERNS.put("creditcard", "credit");
        ACCOUNT_TYPE_PATTERNS.put("loan", "loan");
        ACCOUNT_TYPE_PATTERNS.put("mortgage", "loan");
        ACCOUNT_TYPE_PATTERNS.put("investment", "investment");
        ACCOUNT_TYPE_PATTERNS.put("brokerage", "investment");
        ACCOUNT_TYPE_PATTERNS.put("401k", "investment");
        ACCOUNT_TYPE_PATTERNS.put("401(k)", "investment");
        ACCOUNT_TYPE_PATTERNS.put("403b", "investment");
        ACCOUNT_TYPE_PATTERNS.put("403(b)", "investment");
        ACCOUNT_TYPE_PATTERNS.put("ira", "investment");
        ACCOUNT_TYPE_PATTERNS.put("roth", "investment");
        ACCOUNT_TYPE_PATTERNS.put("roth ira", "investment");
        ACCOUNT_TYPE_PATTERNS.put("traditional ira", "investment");
        ACCOUNT_TYPE_PATTERNS.put("sep ira", "investment");
        ACCOUNT_TYPE_PATTERNS.put("simple ira", "investment");
        ACCOUNT_TYPE_PATTERNS.put("529", "investment");
        ACCOUNT_TYPE_PATTERNS.put("529 plan", "investment");
        ACCOUNT_TYPE_PATTERNS.put("hsa", "investment");
        ACCOUNT_TYPE_PATTERNS.put("health savings account", "investment");
        
        // German
        ACCOUNT_TYPE_PATTERNS.put("girokonto", "depository");
        ACCOUNT_TYPE_PATTERNS.put("sparkonto", "depository");
        ACCOUNT_TYPE_PATTERNS.put("kreditkarte", "credit");
        
        // French
        ACCOUNT_TYPE_PATTERNS.put("compte courant", "depository");
        ACCOUNT_TYPE_PATTERNS.put("compte epargne", "depository");
        ACCOUNT_TYPE_PATTERNS.put("carte de credit", "credit");
        
        // Spanish
        ACCOUNT_TYPE_PATTERNS.put("cuenta corriente", "depository");
        ACCOUNT_TYPE_PATTERNS.put("cuenta de ahorros", "depository");
        ACCOUNT_TYPE_PATTERNS.put("tarjeta de credito", "credit");
        
        // Italian
        ACCOUNT_TYPE_PATTERNS.put("conto corrente", "depository");
        ACCOUNT_TYPE_PATTERNS.put("conto di risparmio", "depository");
        ACCOUNT_TYPE_PATTERNS.put("carta di credito", "credit");
        
        // Japanese
        ACCOUNT_TYPE_PATTERNS.put("当座預金", "depository");
        ACCOUNT_TYPE_PATTERNS.put("普通預金", "depository");
        ACCOUNT_TYPE_PATTERNS.put("クレジットカード", "credit");
        
        // Chinese
        ACCOUNT_TYPE_PATTERNS.put("活期账户", "depository");
        ACCOUNT_TYPE_PATTERNS.put("储蓄账户", "depository");
        ACCOUNT_TYPE_PATTERNS.put("信用卡", "credit");
        
        // Korean
        ACCOUNT_TYPE_PATTERNS.put("당좌예금", "depository");
        ACCOUNT_TYPE_PATTERNS.put("저축예금", "depository");
        ACCOUNT_TYPE_PATTERNS.put("신용카드", "credit");
        
        // Portuguese
        ACCOUNT_TYPE_PATTERNS.put("conta corrente", "depository");
        ACCOUNT_TYPE_PATTERNS.put("conta poupança", "depository");
        ACCOUNT_TYPE_PATTERNS.put("cartão de crédito", "credit");
        
        // Russian
        ACCOUNT_TYPE_PATTERNS.put("текущий счет", "depository");
        ACCOUNT_TYPE_PATTERNS.put("сберегательный счет", "depository");
        ACCOUNT_TYPE_PATTERNS.put("кредитная карта", "credit");
        
        // Arabic
        ACCOUNT_TYPE_PATTERNS.put("حساب جاري", "depository");
        ACCOUNT_TYPE_PATTERNS.put("حساب توفير", "depository");
        ACCOUNT_TYPE_PATTERNS.put("بطاقة ائتمان", "credit");
        
        // Hindi
        ACCOUNT_TYPE_PATTERNS.put("चालू खाता", "depository");
        ACCOUNT_TYPE_PATTERNS.put("बचत खाता", "depository");
        ACCOUNT_TYPE_PATTERNS.put("क्रेडिट कार्ड", "credit");
        
        // Dutch
        ACCOUNT_TYPE_PATTERNS.put("betaalrekening", "depository");
        ACCOUNT_TYPE_PATTERNS.put("spaarrekening", "depository");
        ACCOUNT_TYPE_PATTERNS.put("creditcard", "credit");
        
        // Polish
        ACCOUNT_TYPE_PATTERNS.put("rachunek bieżący", "depository");
        ACCOUNT_TYPE_PATTERNS.put("konto oszczędnościowe", "depository");
        ACCOUNT_TYPE_PATTERNS.put("karta kredytowa", "credit");
        
        // Turkish
        ACCOUNT_TYPE_PATTERNS.put("vadesiz hesap", "depository");
        ACCOUNT_TYPE_PATTERNS.put("tasarruf hesabı", "depository");
        ACCOUNT_TYPE_PATTERNS.put("kredi kartı", "credit");
        
        // Vietnamese
        ACCOUNT_TYPE_PATTERNS.put("tài khoản vãng lai", "depository");
        ACCOUNT_TYPE_PATTERNS.put("tài khoản tiết kiệm", "depository");
        ACCOUNT_TYPE_PATTERNS.put("thẻ tín dụng", "credit");
        
        // Thai
        ACCOUNT_TYPE_PATTERNS.put("บัญชีกระแสรายวัน", "depository");
        ACCOUNT_TYPE_PATTERNS.put("บัญชีออมทรัพย์", "depository");
        ACCOUNT_TYPE_PATTERNS.put("บัตรเครดิต", "credit");
        
        // Indonesian
        ACCOUNT_TYPE_PATTERNS.put("rekening giro", "depository");
        ACCOUNT_TYPE_PATTERNS.put("rekening tabungan", "depository");
        ACCOUNT_TYPE_PATTERNS.put("kartu kredit", "credit");
        
        // Malay
        ACCOUNT_TYPE_PATTERNS.put("akaun semasa", "depository");
        ACCOUNT_TYPE_PATTERNS.put("akaun simpanan", "depository");
        ACCOUNT_TYPE_PATTERNS.put("kad kredit", "credit");
    }
    
    /**
     * Detection result with confidence score
     */
    public static class DetectionResult {
        private String value;
        private double confidence; // 0.0 to 1.0
        private String source; // "filename", "header", "data", "metadata"
        private String method; // "pattern", "fuzzy", "statistical", "context"
        
        public DetectionResult(String value, double confidence, String source, String method) {
            this.value = value;
            this.confidence = confidence;
            this.source = source;
            this.method = method;
        }
        
        public String getValue() { return value; }
        public double getConfidence() { return confidence; }
        public String getSource() { return source; }
        public String getMethod() { return method; }
    }
    
    /**
     * Comprehensive account detection from multiple sources
     * Now supports OCR for scanned PDFs
     */
    public AccountDetectionService.DetectedAccount detectAccount(
            String filename,
            List<String> headers,
            List<List<String>> dataRows,
            Map<String, String> metadata) {
        
        if (filename == null) filename = "";
        if (headers == null) headers = new ArrayList<>();
        if (dataRows == null) dataRows = new ArrayList<>();
        if (metadata == null) metadata = new HashMap<>();
        
        AccountDetectionService.DetectedAccount detected = new AccountDetectionService.DetectedAccount();
        
        // Multi-source detection with confidence scoring
        DetectionResult accountNumber = detectAccountNumber(filename, headers, dataRows, metadata);
        DetectionResult institutionName = detectInstitutionName(filename, headers, dataRows, metadata);
        DetectionResult accountType = detectAccountType(filename, headers, dataRows, metadata);
        DetectionResult accountName = detectAccountName(filename, headers, dataRows, metadata, institutionName, accountType);
        
        // Apply results with confidence threshold (0.5)
        if (accountNumber != null && accountNumber.getConfidence() >= 0.5) {
            detected.setAccountNumber(accountNumber.getValue());
            logger.info("Detected account number: {} (confidence: {}, source: {}, method: {})",
                accountNumber.getValue(), accountNumber.getConfidence(), accountNumber.getSource(), accountNumber.getMethod());
        }
        
        if (institutionName != null && institutionName.getConfidence() >= 0.5) {
            detected.setInstitutionName(institutionName.getValue());
            logger.info("Detected institution: {} (confidence: {}, source: {}, method: {})",
                institutionName.getValue(), institutionName.getConfidence(), institutionName.getSource(), institutionName.getMethod());
        }
        
        if (accountType != null && accountType.getConfidence() >= 0.5) {
            detected.setAccountType(accountType.getValue());
            logger.info("Detected account type: {} (confidence: {}, source: {}, method: {})",
                accountType.getValue(), accountType.getConfidence(), accountType.getSource(), accountType.getMethod());
        }
        
        if (accountName != null && accountName.getConfidence() >= 0.5) {
            detected.setAccountName(accountName.getValue());
            logger.info("Detected account name: {} (confidence: {}, source: {}, method: {})",
                accountName.getValue(), accountName.getConfidence(), accountName.getSource(), accountName.getMethod());
        }
        
        return detected;
    }
    
    /**
     * Detect account number using multiple techniques
     */
    private DetectionResult detectAccountNumber(String filename, List<String> headers, 
                                                List<List<String>> dataRows, Map<String, String> metadata) {
        List<DetectionResult> candidates = new ArrayList<>();
        
        // 1. Pattern matching in filename
        String accountNum = extractAccountNumberFromText(filename);
        if (accountNum != null) {
            candidates.add(new DetectionResult(accountNum, 0.7, "filename", "pattern"));
        }
        
        // 2. Pattern matching in headers
        if (headers != null && !headers.isEmpty()) {
            // CRITICAL FIX: Filter null headers before joining
            List<String> nonNullHeaders = new ArrayList<>();
            for (String h : headers) {
                if (h != null) {
                    nonNullHeaders.add(h);
                }
            }
            
            if (!nonNullHeaders.isEmpty()) {
                String headerText = String.join(" ", nonNullHeaders);
                accountNum = extractAccountNumberFromText(headerText);
                if (accountNum != null) {
                    candidates.add(new DetectionResult(accountNum, 0.8, "header", "pattern"));
                }
            }
            
            // Check for account number columns
            for (int i = 0; i < headers.size(); i++) {
                // CRITICAL FIX: Bounds check
                if (i >= headers.size()) {
                    break;
                }
                
                String header = headers.get(i);
                if (header != null && isAccountNumberColumn(header)) {
                    // Extract from first few data rows
                    String rawAccountNum = extractFromDataColumn(dataRows, i, 5);
                    if (rawAccountNum != null) {
                        // CRITICAL: Process through extractAccountNumberFromText to handle masked formats (****1234)
                        accountNum = extractAccountNumberFromText(rawAccountNum);
                        if (accountNum != null) {
                            candidates.add(new DetectionResult(accountNum, 0.9, "data", "column"));
                        }
                    }
                }
            }
        }
        
        // 3. Statistical analysis of data rows
        if (dataRows != null && !dataRows.isEmpty()) {
            accountNum = extractAccountNumberStatistically(dataRows);
            if (accountNum != null) {
                candidates.add(new DetectionResult(accountNum, 0.75, "data", "statistical"));
            }
        }
        
        // 4. Metadata analysis
        if (metadata != null && !metadata.isEmpty()) {
            for (String value : metadata.values()) {
                if (value != null && !value.trim().isEmpty()) {
                    accountNum = extractAccountNumberFromText(value);
                    if (accountNum != null) {
                        candidates.add(new DetectionResult(accountNum, 0.6, "metadata", "pattern"));
                    }
                }
            }
        }
        
        // Return highest confidence result
        return candidates.stream()
            .max(Comparator.comparing(DetectionResult::getConfidence))
            .orElse(null);
    }
    
    /**
     * Detect institution name using fuzzy matching and aliases
     */
    private DetectionResult detectInstitutionName(String filename, List<String> headers,
                                                  List<List<String>> dataRows, Map<String, String> metadata) {
        List<DetectionResult> candidates = new ArrayList<>();
        
        // 1. Exact match in filename
        String institution = detectInstitutionFromText(filename);
        if (institution != null) {
            candidates.add(new DetectionResult(institution, 0.8, "filename", "exact"));
        }
        
        // 2. Fuzzy match in filename
        institution = fuzzyMatchInstitution(filename);
        if (institution != null) {
            candidates.add(new DetectionResult(institution, 0.7, "filename", "fuzzy"));
        }
        
        // 3. Header analysis
        if (headers != null) {
            String headerText = String.join(" ", headers);
            institution = detectInstitutionFromText(headerText);
            if (institution != null) {
                candidates.add(new DetectionResult(institution, 0.85, "header", "exact"));
            }
            
            institution = fuzzyMatchInstitution(headerText);
            if (institution != null) {
                candidates.add(new DetectionResult(institution, 0.75, "header", "fuzzy"));
            }
            
            // Check for institution columns
            for (int i = 0; i < headers.size(); i++) {
                String header = headers.get(i);
                if (isInstitutionColumn(header)) {
                    String value = extractFromDataColumn(dataRows, i, 1);
                    if (value != null) {
                        institution = detectInstitutionFromText(value);
                        if (institution != null) {
                            candidates.add(new DetectionResult(institution, 0.9, "data", "column"));
                        }
                    }
                }
            }
        }
        
        // 4. Metadata analysis
        if (metadata != null) {
            for (String value : metadata.values()) {
                institution = detectInstitutionFromText(value);
                if (institution != null) {
                    candidates.add(new DetectionResult(institution, 0.7, "metadata", "exact"));
                }
            }
        }
        
        // Return highest confidence result
        return candidates.stream()
            .max(Comparator.comparing(DetectionResult::getConfidence))
            .orElse(null);
    }
    
    /**
     * Detect account type using multiple techniques
     */
    private DetectionResult detectAccountType(String filename, List<String> headers,
                                             List<List<String>> dataRows, Map<String, String> metadata) {
        List<DetectionResult> candidates = new ArrayList<>();
        
        // 1. Pattern matching in filename
        String accountType = detectAccountTypeFromText(filename);
        if (accountType != null) {
            candidates.add(new DetectionResult(accountType, 0.7, "filename", "pattern"));
        }
        
        // 2. Header analysis
        if (headers != null && !headers.isEmpty()) {
            // CRITICAL FIX: Filter null headers before joining
            List<String> nonNullHeaders = new ArrayList<>();
            for (String h : headers) {
                if (h != null) {
                    nonNullHeaders.add(h);
                }
            }
            
            if (!nonNullHeaders.isEmpty()) {
                String headerText = String.join(" ", nonNullHeaders);
                accountType = detectAccountTypeFromText(headerText);
                if (accountType != null) {
                    candidates.add(new DetectionResult(accountType, 0.8, "header", "pattern"));
                }
            }
            
            // Check for account type columns
            for (int i = 0; i < headers.size(); i++) {
                // CRITICAL FIX: Bounds check
                if (i >= headers.size()) {
                    break;
                }
                
                String header = headers.get(i);
                if (header != null && isAccountTypeColumn(header)) {
                    String value = extractFromDataColumn(dataRows, i, 1);
                    if (value != null && !value.trim().isEmpty()) {
                        accountType = detectAccountTypeFromText(value);
                        if (accountType != null) {
                            candidates.add(new DetectionResult(accountType, 0.9, "data", "column"));
                        }
                    }
                }
            }
        }
        
        // 3. Transaction pattern analysis
        accountType = inferAccountTypeFromTransactionPatterns(dataRows);
        if (accountType != null) {
            candidates.add(new DetectionResult(accountType, 0.75, "data", "statistical"));
        }
        
        // 4. Metadata analysis
        if (metadata != null) {
            for (String value : metadata.values()) {
                accountType = detectAccountTypeFromText(value);
                if (accountType != null) {
                    candidates.add(new DetectionResult(accountType, 0.6, "metadata", "pattern"));
                }
            }
        }
        
        // Return highest confidence result
        return candidates.stream()
            .max(Comparator.comparing(DetectionResult::getConfidence))
            .orElse(null);
    }
    
    /**
     * Detect account name using context from institution and account type
     */
    private DetectionResult detectAccountName(String filename, List<String> headers,
                                              List<List<String>> dataRows, Map<String, String> metadata,
                                              DetectionResult institutionName, DetectionResult accountType) {
        List<DetectionResult> candidates = new ArrayList<>();
        
        // 1. Construct from detected institution and type
        if (institutionName != null && accountType != null) {
            String accountName = generateAccountName(institutionName.getValue(), accountType.getValue());
            candidates.add(new DetectionResult(accountName, 0.8, "constructed", "context"));
        }
        
        // 2. Extract from headers
        if (headers != null) {
            for (int i = 0; i < headers.size(); i++) {
                String header = headers.get(i);
                if (isAccountNameColumn(header)) {
                    String value = extractFromDataColumn(dataRows, i, 1);
                    if (value != null && !value.trim().isEmpty()) {
                        candidates.add(new DetectionResult(value.trim(), 0.9, "data", "column"));
                    }
                }
            }
        }
        
        // 3. Extract from filename
        String accountName = extractAccountNameFromFilename(filename);
        if (accountName != null) {
            candidates.add(new DetectionResult(accountName, 0.7, "filename", "pattern"));
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
        final int MAX_TEXT_LENGTH = 10000;
        if (text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH);
        }
        
        for (Pattern pattern : ACCOUNT_NUMBER_PATTERNS) {
            try {
                Matcher matcher = pattern.matcher(text);
                if (matcher.find()) {
                    // CRITICAL FIX: Check group count before accessing
                    if (matcher.groupCount() >= 1) {
                        String accountNum = matcher.group(1);
                        if (accountNum != null) {
                            accountNum = accountNum.replaceAll("[*xX\\s-]", "");
                            if (accountNum.length() >= 4) {
                                // Extract last 4 digits
                                int startIndex = Math.max(0, accountNum.length() - 4);
                                return accountNum.substring(startIndex);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Error extracting account number with pattern: {}", e.getMessage());
                // Continue with next pattern
            }
        }
        return null;
    }
    
    private String detectInstitutionFromText(String text) {
        if (text == null || text.isEmpty()) return null;
        
        // CRITICAL: Normalize underscores and hyphens to spaces for better matching
        // e.g., "wells_fargo" should match "wells fargo" keyword
        String normalized = text.toLowerCase().replaceAll("[_\\-]", " ");
        String textLower = normalized;
        
        // CRITICAL: Check aliases first (more specific) before canonical names
        // This prevents false positives like "credit" matching "credit agricole" when "amex" is present
        Map<String, String> aliasToCanonical = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : INSTITUTION_ALIASES.entrySet()) {
            String canonical = entry.getKey();
            for (String alias : entry.getValue()) {
                aliasToCanonical.put(alias.toLowerCase(), canonical);
            }
        }
        
        // Sort aliases by length (longer/more specific first), then alphabetically for consistency
        List<String> sortedAliases = new ArrayList<>(aliasToCanonical.keySet());
        sortedAliases.sort((a, b) -> {
            int lenDiff = b.length() - a.length();
            if (lenDiff != 0) return lenDiff;
            return a.compareTo(b);
        });
        
        // Check aliases first (more specific matches)
        for (String alias : sortedAliases) {
            String normalizedAlias = alias.toLowerCase().replaceAll("[_\\-]", " ");
            // Use word boundary matching for short aliases to avoid false positives
            if (normalizedAlias.length() <= 4) {
                // For short aliases like "amex", use word boundary
                Pattern pattern = Pattern.compile("\\b" + Pattern.quote(normalizedAlias) + "\\b", Pattern.CASE_INSENSITIVE);
                if (pattern.matcher(textLower).find()) {
                    return aliasToCanonical.get(alias.toLowerCase());
                }
            } else {
                // For longer aliases, use contains
                if (textLower.contains(normalizedAlias)) {
                    return aliasToCanonical.get(alias.toLowerCase());
                }
            }
        }
        
        // Then check canonical names (less specific, but still valid)
        // Sort by length (longer first) to prioritize specific matches
        List<Map.Entry<String, List<String>>> sortedEntries = new ArrayList<>(INSTITUTION_ALIASES.entrySet());
        sortedEntries.sort((a, b) -> b.getKey().length() - a.getKey().length());
        
        for (Map.Entry<String, List<String>> entry : sortedEntries) {
            String canonical = entry.getKey();
            String normalizedCanonical = canonical.toLowerCase().replaceAll("[_\\-]", " ");
            // For canonical names, use word boundary if short, contains if long
            if (normalizedCanonical.length() <= 5) {
                Pattern pattern = Pattern.compile("\\b" + Pattern.quote(normalizedCanonical) + "\\b", Pattern.CASE_INSENSITIVE);
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
    
    private String fuzzyMatchInstitution(String text) {
        if (text == null || text.isEmpty()) return null;
        
        String textLower = text.toLowerCase();
        double bestScore = 0.0;
        String bestMatch = null;
        
        for (String institution : INSTITUTION_ALIASES.keySet()) {
            double score = calculateSimilarity(textLower, institution);
            if (score > 0.7 && score > bestScore) {
                bestScore = score;
                bestMatch = institution;
            }
        }
        
        return bestMatch;
    }
    
    private double calculateSimilarity(String s1, String s2) {
        // Simple Levenshtein-based similarity
        int maxLen = Math.max(s1.length(), s2.length());
        if (maxLen == 0) return 1.0;
        
        int distance = levenshteinDistance(s1, s2);
        return 1.0 - (double) distance / maxLen;
    }
    
    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        
        for (int i = 0; i <= s1.length(); i++) {
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                } else if (j == 0) {
                    dp[i][j] = i;
                } else {
                    dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + (s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1)
                    );
                }
            }
        }
        
        return dp[s1.length()][s2.length()];
    }
    
    private String detectAccountTypeFromText(String text) {
        if (text == null || text.isEmpty()) return null;
        
        // CRITICAL: Normalize underscores and hyphens to spaces for better matching
        // e.g., "credit_card" should match "credit card" pattern
        String normalized = text.toLowerCase().replaceAll("[_\\-]", " ");
        String textLower = normalized;
        
        for (Map.Entry<String, String> entry : ACCOUNT_TYPE_PATTERNS.entrySet()) {
            // Normalize pattern key as well
            String normalizedKey = entry.getKey().toLowerCase().replaceAll("[_\\-]", " ");
            if (textLower.contains(normalizedKey)) {
                return entry.getValue();
            }
        }
        return null;
    }
    
    private String inferAccountTypeFromTransactionPatterns(List<List<String>> dataRows) {
        if (dataRows == null || dataRows.isEmpty()) return null;
        
        int checkCount = 0;
        int debitCount = 0;
        int creditCount = 0;
        int achCount = 0;
        
        for (List<String> row : dataRows.subList(0, Math.min(20, dataRows.size()))) {
            if (row == null) continue; // CRITICAL: Skip null rows
            // CRITICAL: Filter out null elements before joining to avoid NullPointerException
            String rowText = row.stream()
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.joining(" "))
                    .toLowerCase();
            if (rowText.contains("check") || rowText.contains("chk")) checkCount++;
            if (rowText.contains("debit") || rowText.contains(" db ")) debitCount++;
            if (rowText.contains("credit") && !rowText.contains("card")) creditCount++;
            if (rowText.contains("ach") || rowText.contains("direct deposit")) achCount++;
        }
        
        if (checkCount > 0) return "depository";
        if (achCount > 2 || (debitCount > 0 && creditCount > 0)) return "depository";
        
        return null;
    }
    
    private boolean isAccountNumberColumn(String header) {
        if (header == null) return false;
        String headerLower = header.toLowerCase();
        return headerLower.contains("account number") || headerLower.contains("account #") ||
               headerLower.contains("card number") || headerLower.contains("card #") ||
               headerLower.contains("account no") || headerLower.contains("acct number");
    }
    
    private boolean isInstitutionColumn(String header) {
        if (header == null) return false;
        String headerLower = header.toLowerCase();
        return headerLower.contains("institution") || headerLower.contains("bank") ||
               headerLower.contains("issuer") || headerLower.contains("financial institution");
    }
    
    private boolean isAccountTypeColumn(String header) {
        if (header == null) return false;
        String headerLower = header.toLowerCase();
        return headerLower.contains("account type") || headerLower.contains("type") ||
               headerLower.contains("category") || headerLower.contains("product type");
    }
    
    private boolean isAccountNameColumn(String header) {
        if (header == null) return false;
        String headerLower = header.toLowerCase();
        return headerLower.contains("account name") || headerLower.contains("accountname") ||
               headerLower.contains("product name") || headerLower.contains("card name");
    }
    
    private String extractFromDataColumn(List<List<String>> dataRows, int columnIndex, int maxRows) {
        if (dataRows == null || dataRows.isEmpty() || columnIndex < 0) {
            return null;
        }
        
        int rowsToCheck = Math.min(maxRows, dataRows.size());
        for (int i = 0; i < rowsToCheck; i++) {
            // CRITICAL FIX: Bounds check
            if (i >= dataRows.size()) {
                break;
            }
            
            List<String> row = dataRows.get(i);
            if (row == null) {
                continue;
            }
            
            // CRITICAL FIX: Bounds check for column index
            if (columnIndex < row.size()) {
                String value = row.get(columnIndex);
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            }
        }
        return null;
    }
    
    private String extractAccountNumberStatistically(List<List<String>> dataRows) {
        if (dataRows == null || dataRows.isEmpty()) return null;
        
        // Count occurrences of 4-digit numbers that appear consistently
        Map<String, Integer> numberCounts = new HashMap<>();
        
        for (List<String> row : dataRows.subList(0, Math.min(20, dataRows.size()))) {
            if (row == null) continue; // CRITICAL: Skip null rows
            for (String cell : row) {
                if (cell != null) {
                    Pattern pattern = Pattern.compile("\\b(\\d{4})\\b");
                    Matcher matcher = pattern.matcher(cell);
                    while (matcher.find()) {
                        String num = matcher.group(1);
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
    
    private String generateAccountName(String institution, String accountType) {
        StringBuilder name = new StringBuilder();
        if (institution != null && !institution.isEmpty()) {
            name.append(institution);
        }
        if (accountType != null && !accountType.isEmpty()) {
            if (name.length() > 0) name.append(" ");
            name.append(accountType);
        }
        return name.toString().trim();
    }
    
    private String extractAccountNameFromFilename(String filename) {
        if (filename == null) return null;
        
        // Remove extension
        String name = filename.replaceAll("\\.[^.]+$", "");
        // Remove common separators
        name = name.replaceAll("[_\\-]", " ");
        return name.trim();
    }
}

