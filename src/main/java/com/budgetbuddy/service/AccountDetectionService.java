package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for detecting account information from imported files
 * Detects account numbers, institution names, and account types from:
 * - Filenames (e.g., "chase_checking_1234.csv")
 * - PDF headers (account numbers, card numbers)
 * - CSV/Excel headers (account number, account name columns)
 */
@Service
public class AccountDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(AccountDetectionService.class);
    
    private final AccountRepository accountRepository;
    private final BalanceExtractor balanceExtractor;
    
    public AccountDetectionService(AccountRepository accountRepository, BalanceExtractor balanceExtractor) {
        this.accountRepository = accountRepository;
        this.balanceExtractor = balanceExtractor;
    }
    
    // Common bank/institution name patterns - Global support
    private static final List<String> INSTITUTION_KEYWORDS = Arrays.asList(
        // US Major Banks
        "chase", "bank of america", "wells fargo", "citibank", "citi", "citicards", "us bank", "capital one",
        "american express", "discover", "synchrony", "visa", "mastercard", "amex",
        "jpmorgan", "jpm", "jpmc", "bofa", "bac","wf", "wells", "usbank", "capone",
        // US Regional Banks
        "pnc", "truist", "citizens bank", "fifth third", "keybank", "huntington", "regions bank",
        "m&t bank", "comerica", "zions bank", "first national", "first citizens", "east west bank",
        "cathay bank", "bank of the west", "first republic", "silicon valley bank", "svb",
        // US Credit Unions
        "navy federal", "penfed", "state employees", "teachers federal", "alliant",
        // US Investment/Wealth Management
        "fidelity", "schwab", "vanguard", "morgan stanley", "goldman sachs", "merrill lynch",
        "edward jones", "raymond james", "lpl financial", "ameriprise",
        // UK Banks
        "hsbc", "barclays", "lloyds", "natwest", "rbs", "royal bank of scotland",
        "santander uk", "halifax", "nationwide", "tsb", "metrobank", "first direct",
        "monzo", "revolut", "starling", "monese", "chime", "ally bank",
        // French Banks
        "bnp paribas", "credit agricole", "societe generale", "credit mutuel", "banque populaire",
        "la banque postale", "lcl", "credit lyonnais", "caisse d'epargne",
        // German Banks
        "deutsche bank", "commerzbank", "sparkasse", "volksbank", "postbank", "dresdner bank",
        "hypovereinsbank", "landesbank", "bayernlb",
        // Italian Banks
        "unicredit", "intesa sanpaolo", "monte dei paschi", "banco popolare", "banca mediolanum",
        "banca popolare", "mps", "mediobanca",
        // Spanish Banks
        "bbva", "santander", "caixa", "bankia", "sabadell", "bankinter", "unicaja",
        "ibercaja", "kutxabank", "abanca",
        // Dutch Banks
        "ing", "rabobank", "abn amro", "sns bank", "asn bank", "triodos",
        // Swiss Banks
        "ubs", "credit suisse", "julius baer", "pictet", "lombard odier", "vontobel",
        "zuercher kantonalbank", "postfinance",
        // Belgian Banks
        "kbc", "belfius", "axa bank", "argenta", "keytrade",
        // Nordic Banks
        "danske bank", "nordea", "seb", "handelsbanken", "swedbank", "dnb", "op financial",
        "alandsbanken", "aktia", "sparebank",
        // Other European Banks
        "erste bank", "raiffeisen", "otp bank", "kbc", "sberbank", "alfa bank", "tinkoff",
        "millennium", "pkobp", "ing bank slaski", "mbank", "pekao",
        // Indian Major Banks
        "sbi", "state bank of india", "icici", "hdfc", "axis bank", "pnb", "punjab national bank",
        "kotak", "yes bank", "indusind", "rbl", "idfc", "idbi", "canara bank", "union bank",
        "indian bank", "bank of baroda", "bank of india", "central bank", "indian overseas bank",
        // Indian Payment Platforms
        "paytm", "phonepe", "gpay", "google pay", "amazon pay", "mobikwik", "freecharge",
        "razorpay", "payu", "cashfree", "instamojo",
        // Chinese Major Banks
        "icbc", "ccb", "boc", "abc", "bank of china", "industrial and commercial bank",
        "china construction bank", "agricultural bank of china", "china merchants bank",
        "bank of communications", "ping an bank", "china minsheng", "huaxia bank",
        "spdb", "china citic bank", "evergrowing bank",
        // Japanese Major Banks
        "mufg", "mizuho", "smbc", "sumitomo mitsui", "mitsubishi ufj", "resona", "shinsei",
        "saitama bank", "shizuoka bank", "fukuoka bank", "hokuriku bank",
        // Korean Banks
        "kb", "kookmin", "shinhan", "hana", "woori", "nh", "nonghyup", "keb", "korea exchange bank",
        "ibk", "industrial bank of korea", "kdb", "korea development bank",
        // Singapore Banks
        "dbs", "ocbc", "uob", "maybank singapore", "cimb singapore",
        // Malaysian Banks
        "maybank", "cimb", "public bank", "hong leong bank", "ambank", "rhb bank",
        "affin bank", "alliance bank", "bank islam",
        // Thai Banks
        "bangkok bank", "kasikorn", "siam commercial", "krung thai", "tmb", "thanachart",
        "cimb thai", "uob thai",
        // Indonesian Banks
        "bca", "mandiri", "bni", "bri", "cimb niaga", "panin bank", "maybank indonesia",
        "uob indonesia", "ocbc nisp", "dbs indonesia",
        // Vietnamese Banks
        "vietcombank", "bidv", "vietinbank", "agribank", "techcombank", "mbbank", "acb",
        "vietnam bank", "sacombank", "vpbank",
        // Philippine Banks
        "bdo", "metrobank", "bpi", "security bank", "eastwest bank", "rcbc", "pnb",
        "unionbank", "chinabank", "landbank",
        // Australian/New Zealand Banks
        "commonwealth bank", "anz", "westpac", "nab", "asb", "anz nz", "bnz", "kiwibank",
        "bendigo bank", "suncorp", "bankwest", "ing australia", "macquarie",
        // Canadian Banks
        "rbc", "td canada", "scotiabank", "bmo", "cibc", "national bank of canada",
        "desjardins", "tangerine", "simplii", "pc financial",
        // Middle Eastern Banks
        "emirates nbd", "adcb", "adib", "fgb", "first abu dhabi", "dubai islamic",
        "al rajhi", "sabb", "riyad bank", "samba", "banque saudi fransi", "ncb",
        "qnb", "qatar national bank", "doha bank", "masraf al rayyan",
        "kuwait finance house", "national bank of kuwait", "gulf bank",
        "bank muscat", "nbo", "hsbc oman",
        // Latin American Banks
        "banco do brasil", "itau", "bradesco", "santander brasil", "caixa economica",
        "banco de chile", "santander chile", "banco estado", "scotiabank chile",
        "banamex", "banorte", "santander mexico", "hsbc mexico", "bbva mexico",
        "banco de bogota", "bancolombia", "davivienda", "banco popular",
        "banco de venezuela", "banco mercantil", "banesco",
        "banco de la nacion", "bbva peru", "interbank", "scotiabank peru",
        // African Banks
        "standard bank", "absa", "nedbank", "fnb", "firstrand", "capitec",
        "access bank", "gtbank", "zenith bank", "uba", "first bank",
        "equity bank", "kcb", "cooperative bank", "diamond trust",
        // Global Credit Card Networks
        "mastercard", "visa", "american express", "amex", "discover", "jcb", "unionpay",
        "diners club", "diners", "dinersclub", "rupay",
        // Global Investment Platforms
        "fidelity", "vanguard", "schwab", "td ameritrade", "etrade", "robinhood",
        "morgan stanley", "goldman sachs", "interactive brokers", "etoro", "degiro",
        "icici direct", "hdfc securities", "zerodha", "upstox", "groww", "paytm money",
        "mufg securities", "nomura", "samsung securities", "mirae asset", "citic securities",
        "huatai securities", "comdirect", "consorsbank", "binckbank", "lynx", "boursorama",
        "selfbank", "renta 4", "finecobank", "directa", "trading 212", "freetrade",
        "revolut trading", "webull", "sofi", "m1 finance", "public", "stash",
        // Other Global Institutions
        "standard chartered", "jpmorgan chase", "hsbc global", "citibank global"
    );
    
    // Account type patterns from filenames - Global support
    private static final Map<String, String> ACCOUNT_TYPE_PATTERNS = new HashMap<>();
    static {
        // Deposit Accounts - Global variations
        ACCOUNT_TYPE_PATTERNS.put("checking", "depository");
        ACCOUNT_TYPE_PATTERNS.put("check", "depository");
        ACCOUNT_TYPE_PATTERNS.put("savings", "depository");
        ACCOUNT_TYPE_PATTERNS.put("saving", "depository");
        ACCOUNT_TYPE_PATTERNS.put("current", "depository"); // UK/India/Australia
        ACCOUNT_TYPE_PATTERNS.put("giro", "depository"); // European
        ACCOUNT_TYPE_PATTERNS.put("transaction", "depository"); // Australia/New Zealand
        ACCOUNT_TYPE_PATTERNS.put("transactional", "depository");
        ACCOUNT_TYPE_PATTERNS.put("demand", "depository"); // India/Asia
        ACCOUNT_TYPE_PATTERNS.put("term deposit", "depository"); // Fixed deposit
        ACCOUNT_TYPE_PATTERNS.put("fixed deposit", "depository"); // India/Asia
        ACCOUNT_TYPE_PATTERNS.put("fd", "depository"); // Fixed deposit abbreviation
        ACCOUNT_TYPE_PATTERNS.put("recurring deposit", "depository"); // RD - India
        ACCOUNT_TYPE_PATTERNS.put("rd", "depository"); // Recurring deposit abbreviation
        ACCOUNT_TYPE_PATTERNS.put("time deposit", "depository"); // US term
        ACCOUNT_TYPE_PATTERNS.put("certificate of deposit", "depository"); // CD - US
        ACCOUNT_TYPE_PATTERNS.put("cd", "depository"); // Certificate of deposit
        ACCOUNT_TYPE_PATTERNS.put("money market", "depository"); // Money market account
        ACCOUNT_TYPE_PATTERNS.put("mm", "depository"); // Money market abbreviation
        // Credit Cards
        ACCOUNT_TYPE_PATTERNS.put("credit", "credit");
        ACCOUNT_TYPE_PATTERNS.put("card", "credit");
        ACCOUNT_TYPE_PATTERNS.put("creditcard", "credit");
        ACCOUNT_TYPE_PATTERNS.put("credit card", "credit");
        ACCOUNT_TYPE_PATTERNS.put("citi cash card", "credit"); // Cash card is a type of credit card
        ACCOUNT_TYPE_PATTERNS.put("Citi cashcard", "credit");
        ACCOUNT_TYPE_PATTERNS.put("visa", "credit");
        ACCOUNT_TYPE_PATTERNS.put("mastercard", "credit");
        ACCOUNT_TYPE_PATTERNS.put("amex", "credit");
        ACCOUNT_TYPE_PATTERNS.put("american express", "credit");
        // Loans - Global variations
        ACCOUNT_TYPE_PATTERNS.put("loan", "loan");
        ACCOUNT_TYPE_PATTERNS.put("mortgage", "loan");
        ACCOUNT_TYPE_PATTERNS.put("home loan", "loan"); // India/Australia
        ACCOUNT_TYPE_PATTERNS.put("housing loan", "loan"); // India/Asia
        ACCOUNT_TYPE_PATTERNS.put("auto", "loan");
        ACCOUNT_TYPE_PATTERNS.put("car loan", "loan");
        ACCOUNT_TYPE_PATTERNS.put("vehicle loan", "loan"); // India/Asia
        ACCOUNT_TYPE_PATTERNS.put("student", "loan");
        ACCOUNT_TYPE_PATTERNS.put("education loan", "loan"); // India/Asia
        ACCOUNT_TYPE_PATTERNS.put("personal", "loan");
        ACCOUNT_TYPE_PATTERNS.put("business loan", "loan");
        ACCOUNT_TYPE_PATTERNS.put("commercial loan", "loan");
        ACCOUNT_TYPE_PATTERNS.put("line of credit", "loan");
        ACCOUNT_TYPE_PATTERNS.put("loc", "loan"); // Line of credit
        ACCOUNT_TYPE_PATTERNS.put("overdraft", "loan"); // OD - India/UK
        ACCOUNT_TYPE_PATTERNS.put("od", "loan"); // Overdraft abbreviation
        ACCOUNT_TYPE_PATTERNS.put("credit line", "loan");
        ACCOUNT_TYPE_PATTERNS.put("heloc", "loan"); // Home equity line of credit
        ACCOUNT_TYPE_PATTERNS.put("home equity", "loan");
        // Investment Accounts - Global variations
        ACCOUNT_TYPE_PATTERNS.put("investment", "investment");
        ACCOUNT_TYPE_PATTERNS.put("brokerage", "investment");
        ACCOUNT_TYPE_PATTERNS.put("trading", "investment");
        ACCOUNT_TYPE_PATTERNS.put("stock", "investment");
        ACCOUNT_TYPE_PATTERNS.put("stocks", "investment");
        ACCOUNT_TYPE_PATTERNS.put("equity", "investment");
        ACCOUNT_TYPE_PATTERNS.put("mutual fund", "investment");
        ACCOUNT_TYPE_PATTERNS.put("mutualfund", "investment");
        ACCOUNT_TYPE_PATTERNS.put("mf", "investment"); // Mutual fund abbreviation
        ACCOUNT_TYPE_PATTERNS.put("etf", "investment");
        ACCOUNT_TYPE_PATTERNS.put("bond", "investment");
        ACCOUNT_TYPE_PATTERNS.put("bonds", "investment");
        ACCOUNT_TYPE_PATTERNS.put("government bond", "investment");
        ACCOUNT_TYPE_PATTERNS.put("corporate bond", "investment");
        ACCOUNT_TYPE_PATTERNS.put("t-bill", "investment"); // Treasury bill
        ACCOUNT_TYPE_PATTERNS.put("treasury", "investment");
        ACCOUNT_TYPE_PATTERNS.put("commodity", "investment");
        ACCOUNT_TYPE_PATTERNS.put("forex", "investment");
        ACCOUNT_TYPE_PATTERNS.put("derivatives", "investment");
        ACCOUNT_TYPE_PATTERNS.put("options", "investment");
        ACCOUNT_TYPE_PATTERNS.put("futures", "investment");
        ACCOUNT_TYPE_PATTERNS.put("crypto", "investment");
        ACCOUNT_TYPE_PATTERNS.put("cryptocurrency", "investment");
        ACCOUNT_TYPE_PATTERNS.put("bitcoin", "investment");
        ACCOUNT_TYPE_PATTERNS.put("demat", "investment"); // Dematerialized - India
        ACCOUNT_TYPE_PATTERNS.put("demat account", "investment"); // India stock trading
        ACCOUNT_TYPE_PATTERNS.put("demat ac", "investment");
        // Retirement Accounts (US)
        ACCOUNT_TYPE_PATTERNS.put("ira", "investment");
        ACCOUNT_TYPE_PATTERNS.put("roth", "investment");
        ACCOUNT_TYPE_PATTERNS.put("rothira", "investment");
        ACCOUNT_TYPE_PATTERNS.put("401k", "investment");
        ACCOUNT_TYPE_PATTERNS.put("403b", "investment");
        ACCOUNT_TYPE_PATTERNS.put("pension", "investment");
        // Retirement Accounts (Other Regions)
        ACCOUNT_TYPE_PATTERNS.put("superannuation", "investment"); // Australia
        ACCOUNT_TYPE_PATTERNS.put("super", "investment"); // Australia abbreviation
        ACCOUNT_TYPE_PATTERNS.put("kiwisaver", "investment"); // New Zealand
        ACCOUNT_TYPE_PATTERNS.put("ppf", "investment"); // India - Public Provident Fund
        ACCOUNT_TYPE_PATTERNS.put("epf", "investment"); // India - Employee Provident Fund
        ACCOUNT_TYPE_PATTERNS.put("provident", "investment"); // India/Asia
        ACCOUNT_TYPE_PATTERNS.put("retirement", "investment");
        ACCOUNT_TYPE_PATTERNS.put("pension", "investment");
        ACCOUNT_TYPE_PATTERNS.put("cpf", "investment"); // Central Provident Fund - Singapore
        ACCOUNT_TYPE_PATTERNS.put("epf malaysia", "investment"); // Malaysia EPF
        ACCOUNT_TYPE_PATTERNS.put("kwsp", "investment"); // Malaysia EPF abbreviation
        ACCOUNT_TYPE_PATTERNS.put("social security", "investment"); // US/Global
        ACCOUNT_TYPE_PATTERNS.put("national pension", "investment"); // Korea/Japan
        ACCOUNT_TYPE_PATTERNS.put("npf", "investment"); // National Pension Fund
        // Investment Platforms
        ACCOUNT_TYPE_PATTERNS.put("fidelity", "investment");
        ACCOUNT_TYPE_PATTERNS.put("vanguard", "investment");
        ACCOUNT_TYPE_PATTERNS.put("schwab", "investment");
        ACCOUNT_TYPE_PATTERNS.put("robinhood", "investment");
        ACCOUNT_TYPE_PATTERNS.put("etrade", "investment");
        ACCOUNT_TYPE_PATTERNS.put("td ameritrade", "investment");
        ACCOUNT_TYPE_PATTERNS.put("icici direct", "investment");
        ACCOUNT_TYPE_PATTERNS.put("hdfc securities", "investment");
        ACCOUNT_TYPE_PATTERNS.put("zerodha", "investment");
        ACCOUNT_TYPE_PATTERNS.put("upstox", "investment");
        ACCOUNT_TYPE_PATTERNS.put("groww", "investment");
        ACCOUNT_TYPE_PATTERNS.put("paytm money", "investment");
    }
    
    // Enhanced patterns for account numbers with better keyword matching
    // Handles variations like:
    // - "Account Number ending 1234"
    // - "Account Number ending: 1234"
    // - "Account Number ending in: 1234"
    // - "Account Number ending in 1234"
    // Comprehensive account number patterns for all account types:
    // 
    // Credit/Debit Cards:
    // - "Card ending in 1234", "Card # ending in 1234", "Card number ending: 1234"
    // - "Credit Card: 1234", "Debit Card: 1234", "Card Number: 1234"
    // - "Last 4 digits: 1234", "Last 4: 1234", "Last Four Digits: 1234"
    // - "****1234", "xxxx1234" (masked cards)
    // 
    // Savings/Checking Accounts:
    // - "Account Number: 1234", "Account #: 1234", "Acct No.: 1234"
    // - "Savings Account: 1234-5678", "Checking Account Number: 1234 5678"
    // - "Account Ending 8-41007" (with hyphens)
    // - "Account # ending in: 1234"
    // 
    // Investment Accounts:
    // - "Investment Account: 12345678", "Brokerage Account #: 1234"
    // - "Account Number: 1234-5678-9012" (may have multiple segments)
    // 
    // Loan Accounts:
    // - "Loan Account: 1234", "Mortgage Account #: 1234-5678"
    // - "Auto Loan Number: 1234", "Personal Loan: 1234"
    // 
    // Common variations:
    // - "Acct Ending: 1234", "Acct # Ending: 1234"
    // - Numbers with hyphens: "8-41007", "1234-5678"
    // - Numbers with spaces: "8 41007", "1234 5678"
    // - Full account numbers: "123456789012" (up to 19 digits)
    // - Masked numbers: "****1234", "xxxx1234"
    // 
    // CRITICAL FIX: Pattern handles hyphens, spaces, and separators in account numbers
    // Matches: "Account Ending 8-41007" -> extracts "8-41007" (normalized to "841007")
    private static final Pattern ACCOUNT_NUMBER_PATTERN = Pattern.compile(
        "(?:" +
        // Pattern 1: Account/Card ending/with last digits (most common)
        "(?:(?:account|acct|card|credit\\s*card|debit\\s*card|savings\\s*account|checking\\s*account|investment\\s*account|brokerage\\s*account|loan\\s*account|mortgage\\s*account|auto\\s*loan|personal\\s*loan)\\s*(?:number|#|no\\.?)?\\s*(?:ending\\s*(?:in|with)?\\s*:?\\s*|with\\s*(?:last\\s*)?(?:4\\s*|four\\s*)?(?:digits?|numbers?)\\s*:?\\s*))" +
        "|" +
        // Pattern 2: Last 4 digits (standalone phrase)
        "(?:last\\s*(?:4\\s*|four\\s*)?(?:digits?|numbers?)\\s*:?\\s*)" +
        "|" +
        // Pattern 3: Account/Card Number: (direct label)
        "(?:(?:account|acct|card|credit\\s*card|debit\\s*card|savings|checking|investment|brokerage|loan|mortgage)\\s*(?:number|#|no\\.?)\\s*:?\\s*)" +
        ")" +
        // Capture group: Account number (allows hyphens, spaces, masks)
        // Increased mask limit to handle patterns like "xxxx xxxx xxxx 4666" (up to 24 chars of masks/spaces)
        "([*xX\\s-]{0,24}(?:\\d[\\s-]*){3,19}\\d)",
        Pattern.CASE_INSENSITIVE
    );
    
    // Pattern for credit/debit card numbers (16 digits, possibly masked, with various formats)
    // Handles patterns like:
    // - "Card ending in 1234", "Card # ending: 1234"
    // - "****1234", "xxxx1234" (masked)
    // - "1234567890123456" (full 16-digit card)
    // - "1234-5678-9012-3456" (formatted with hyphens)
    // CRITICAL: This is a fallback pattern specifically for cards when ACCOUNT_NUMBER_PATTERN doesn't match
    private static final Pattern CARD_NUMBER_PATTERN = Pattern.compile(
        "(?:(?:card|credit\\s*card|debit\\s*card)\\s*(?:number|#)?\\s*(?:ending\\s*(?:in|with)?\\s*:?\\s*|with\\s*(?:last\\s*)?(?:4\\s*|four\\s*)?(?:digits?|numbers?)\\s*:?\\s*)?)" +
        // Increased mask limit to handle patterns like "xxxx xxxx xxxx 4666" (up to 24 chars of masks/spaces)
        "([*xX\\s-]{0,24}(?:\\d[\\s-]*){3,19}\\d)",
        Pattern.CASE_INSENSITIVE
    );
    
    // Keywords for account number detection in headers/data
    // Enhanced with investment, loan, and savings account patterns
    private static final List<String> ACCOUNT_NUMBER_KEYWORDS = Arrays.asList(
        // General account patterns
        "account number", "account #", "account no", "accountno", "acct number", "acct #", "acct no",
        // Card patterns
        "card number", "card #", "card no", "credit card number", "credit card #", "credit card no",
        "debit card number", "debit card #", "debit card no",
        // Savings/Checking patterns
        "savings account number", "savings account #", "checking account number", "checking account #",
        "savings #", "checking #",
        // Investment patterns
        "investment account number", "investment account #", "brokerage account number", "brokerage account #",
        "investment #", "brokerage #", "investment account", "brokerage account",
        // Loan patterns
        "loan account number", "loan account #", "loan number", "loan #", "loan no",
        "mortgage account number", "mortgage account #", "mortgage number", "mortgage #",
        "auto loan number", "auto loan #", "personal loan number", "personal loan #",
        // Ending patterns
        "account ending", "card ending", "acct ending", "account ending in", "card ending in", "acct ending in",
        "account ending with", "card ending with", "acct ending with",
        // Last digits patterns
        "account with last 4", "card with last 4", "account last 4 digits", "card last 4 digits",
        "last 4 digits", "last 4 numbers", "last four digits", "last four numbers",
        // Alternative patterns
        "account identifier", "account id", "account code"
    );
    
    // Keywords for institution name detection
    private static final List<String> INSTITUTION_KEYWORDS_HEADERS = Arrays.asList(
        "institution", "institution name", "bank", "bank name", "financial institution",
        "issuer", "issuer name", "card issuer", "bank name", "banking institution", "card account at",
        "online account at", "online chat at", "write us at"
    );
    
    // Keywords for product name detection
    private static final List<String> PRODUCT_NAME_KEYWORDS = Arrays.asList(
        "product name", "product", "card name", "account product", "card product",
        "product description", "account description", "card description",
        "product type", "card type", "account type name"
    );
    
    // Keywords for account type detection
    private static final List<String> ACCOUNT_TYPE_KEYWORDS = Arrays.asList(
        "account type", "type", "account category", "category",
        "product type", "card type", "account classification"
    );
    
    /**
     * Detected account information
     */
    public static class DetectedAccount {
        private String accountNumber; // Last 4 digits or full number
        private String institutionName;
        private String accountName;
        private String accountType;
        private String accountSubtype;
        private String cardNumber; // For credit cards
        private String matchedAccountId; // Account ID if already matched to existing account
        private String accountHolderName; // Account holder/cardholder name (for family account username validation)
        private java.math.BigDecimal balance; // Detected balance from statement/import
        private java.time.LocalDate balanceDate; // Date of the transaction from which balance was extracted (for date comparison)
        
        public String getAccountNumber() { return accountNumber; }
        public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
        
        public String getInstitutionName() { return institutionName; }
        public void setInstitutionName(String institutionName) { this.institutionName = institutionName; }
        
        public String getAccountName() { return accountName; }
        public void setAccountName(String accountName) { this.accountName = accountName; }
        
        public String getAccountType() { return accountType; }
        public void setAccountType(String accountType) { this.accountType = accountType; }
        
        public String getAccountSubtype() { return accountSubtype; }
        public void setAccountSubtype(String accountSubtype) { this.accountSubtype = accountSubtype; }
        
        public String getCardNumber() { return cardNumber; }
        public void setCardNumber(String cardNumber) { this.cardNumber = cardNumber; }
        
        public String getMatchedAccountId() { return matchedAccountId; }
        public void setMatchedAccountId(String matchedAccountId) { this.matchedAccountId = matchedAccountId; }
        
        public String getAccountHolderName() { return accountHolderName; }
        public void setAccountHolderName(String accountHolderName) { this.accountHolderName = accountHolderName; }
        
        public java.math.BigDecimal getBalance() { return balance; }
        public void setBalance(java.math.BigDecimal balance) { this.balance = balance; }
        
        public java.time.LocalDate getBalanceDate() { return balanceDate; }
        public void setBalanceDate(java.time.LocalDate balanceDate) { this.balanceDate = balanceDate; }
    }
    
    /**
     * Detect account information from filename
     * Examples:
     * - "chase_checking_1234.csv" -> institution: Chase, type: checking, number: 1234
     * - "bofa_credit_card_5678.pdf" -> institution: Bank of America, type: credit, number: 5678
     */
    public DetectedAccount detectFromFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return null;
        }
        
        // CRITICAL: Handle whitespace-only filenames
        if (filename.trim().isEmpty()) {
            return null;
        }
        
        // Handle "unknown" or generated filenames - skip detection
        String lowerFilename = filename.toLowerCase();
        if (lowerFilename.startsWith("unknown") || 
            lowerFilename.startsWith("import_") ||
            lowerFilename.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(csv|xlsx|xls|pdf)$")) {
            logger.info("⚠️ Skipping account detection for generated/UUID filename: '{}' (filename not useful for detection - will rely on transaction patterns)", filename);
            return null;
        }
        
        DetectedAccount detected = new DetectedAccount();
        
        // Remove file extension
        String nameWithoutExt = lowerFilename.replaceAll("\\.(csv|xlsx|xls|pdf)$", "");
        logger.info("🔍 Analyzing filename for account detection: '{}' (without extension: '{}')", filename, nameWithoutExt);
        
        // Detect institution name
        String institution = detectInstitutionFromFilename(nameWithoutExt);
        if (institution != null) {
            detected.setInstitutionName(institution);
            logger.info("✓ Detected institution from filename: {}", institution);
        } else {
            logger.info("⚠️ No institution detected from filename: {}", nameWithoutExt);
        }
        
        // Detect account type
        String accountType = detectAccountTypeFromFilename(nameWithoutExt);
        if (accountType != null) {
            detected.setAccountType(accountType);
            logger.info("✓ Detected account type from filename: {}", accountType);
            // Set subtype based on type
            if (accountType.equals("depository")) {
                if (nameWithoutExt.contains("checking") || nameWithoutExt.contains("check")) {
                    detected.setAccountSubtype("checking");
                } else if (nameWithoutExt.contains("savings") || nameWithoutExt.contains("saving")) {
                    detected.setAccountSubtype("savings");
                }
            } else if (accountType.equals("credit")) {
                detected.setAccountSubtype("credit card");
            }
        } else {
            logger.info("⚠️ No account type detected from filename: {}", nameWithoutExt);
        }
        
        // Extract account number (last 4 digits) from filename
        // Pattern matches: 4 digits at word boundary or after institution name (e.g., "Chase3100", "chase_3100", "chase 3100")
        // Try multiple patterns to catch different formats
        String accountNum = null;
        
        // Pattern 1: 4 digits directly after letters (e.g., "Chase3100")
        Pattern pattern1 = Pattern.compile("([a-z]+)(\\d{4})(?:_|\\s|$)", Pattern.CASE_INSENSITIVE);
        Matcher matcher1 = pattern1.matcher(nameWithoutExt);
        if (matcher1.find()) {
            accountNum = matcher1.group(2);
        }
        
        // Pattern 2: 4 digits with separators (e.g., "chase_3100", "chase 3100")
        if (accountNum == null) {
            Pattern pattern2 = Pattern.compile("(?:^|_|\\s)(\\d{4})(?:$|_|\\s)");
            Matcher matcher2 = pattern2.matcher(nameWithoutExt);
            if (matcher2.find()) {
                accountNum = matcher2.group(1);
            }
        }
        
        if (accountNum != null) {
            detected.setAccountNumber(accountNum);
            logger.info("✓ Extracted account number from filename: {}", accountNum);
        }
        
        // Generate account name if we have institution and type
        if (detected.getInstitutionName() != null && detected.getAccountType() != null) {
            String accountName = generateAccountName(detected.getInstitutionName(), 
                                                     detected.getAccountType(), 
                                                     detected.getAccountSubtype(),
                                                     detected.getAccountNumber());
            detected.setAccountName(accountName);
        }
        
        return detected;
    }
    
    /**
     * Detect account information from PDF text content
     * Looks for account numbers, card numbers, institution names in PDF headers
     * Enhanced with better keyword matching and logging
     */
    public DetectedAccount detectFromPDFContent(String pdfText, String filename) {
        if (pdfText == null || pdfText.isEmpty()) {
            return detectFromFilename(filename); // Fallback to filename
        }
        
        DetectedAccount detected = new DetectedAccount();
        
        // Extract header text - includes first lines and Service Agreement section (for Chase cards)
        String headerText = extractHeaderTextWithServiceAgreement(pdfText);
        
        // Log header text summary (reduced verbosity for testing)
        String[] headerLines = headerText.split("\n");
        logger.info("Total header lines to analyze: {}", headerLines.length);
        
        String lowerHeader = headerText.toLowerCase();
        
        // Detect institution name from PDF content using strict matching (word boundaries)
        // This prevents false positives from substrings (e.g., "chase" in "purchase")
        String institution = extractInstitutionFromTextStrict(headerText);
        if (institution != null) {
            detected.setInstitutionName(institution);
            logger.info("✓ Extracted institution name from PDF: {}", institution);
        } else {
            // Fallback to filename
            DetectedAccount fromFilename = detectFromFilename(filename);
            if (fromFilename != null && fromFilename.getInstitutionName() != null) {
                detected.setInstitutionName(fromFilename.getInstitutionName());
                logger.info("✓ Using institution name from filename: {}", fromFilename.getInstitutionName());
            }
        }
        
        // Detect account number using enhanced pattern matching
        String accountNumber = extractAccountNumberFromText(headerText);
        if (accountNumber != null) {
            detected.setAccountNumber(accountNumber);
            logger.info("✓ Extracted account number from PDF: {}", accountNumber);
        }
        
        // Detect card number (for credit cards) using enhanced pattern
        Matcher cardMatcher = CARD_NUMBER_PATTERN.matcher(headerText);
        if (cardMatcher.find()) {
            try {
                String cardNum = cardMatcher.group(1);
                if (cardNum != null) {
                    cardNum = cardNum.replaceAll("[*xX]", "");
                    if (cardNum.length() >= 4) {
                        // CRITICAL FIX: Ensure safe substring
                        int startIndex = Math.max(0, cardNum.length() - 4);
                        String lastFour = cardNum.substring(startIndex);
                        detected.setCardNumber(lastFour);
                        if (detected.getAccountNumber() == null) {
                            detected.setAccountNumber(lastFour);
                            logger.info("✓ Extracted card number from PDF (used as account number): {}", lastFour);
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Error extracting card number from PDF: {}", e.getMessage());
            }
        }
        
        // Detect account type from content - enhanced credit card detection
        // Check for credit card indicators: credit limit, available credit, cash advances, etc.
        // These patterns work for ALL credit card issuers, not just specific ones
        boolean isCreditCard = detectCreditCardFromContent(lowerHeader);
        
        if (isCreditCard) {
            detected.setAccountType("credit");
            detected.setAccountSubtype("credit card");
            logger.info("✓ Detected credit card account type from PDF content");
        } else if (lowerHeader.contains("checking") || lowerHeader.contains("checking account")) {
            detected.setAccountType("depository");
            detected.setAccountSubtype("checking");
            logger.info("✓ Detected checking account type from PDF content");
        } else if (lowerHeader.contains("savings") || lowerHeader.contains("savings account")) {
            detected.setAccountType("depository");
            detected.setAccountSubtype("savings");
            logger.info("✓ Detected savings account type from PDF content");
        } else if (lowerHeader.contains("loan") || lowerHeader.contains("mortgage")) {
            detected.setAccountType("loan");
            logger.info("✓ Detected loan account type from PDF content");
        }
        
        // Extract balance from PDF header text (after account type is detected for better pattern matching)
        java.math.BigDecimal balance = extractBalanceFromHeaders(
            Arrays.asList(headerText.split("\n")), detected.getAccountType());
        if (balance != null) {
            detected.setBalance(balance);
            logger.info("✓ Extracted balance from PDF: {}", balance);
        }
        
        // Extract product/card name from PDF content (e.g., "Citi Double Cash® Card")
        // Look for patterns like: "Card Name", "Product Name Card", etc.
        String productName = extractProductNameFromPDF(headerText);
        if (productName != null) {
            detected.setAccountName(productName);
            logger.info("✓ Extracted product/card name from PDF: {}", productName);
        } else {
            // Generate account name if product name not found
            if (detected.getInstitutionName() != null && detected.getAccountType() != null) {
                String accountName = generateAccountName(detected.getInstitutionName(),
                                                         detected.getAccountType(),
                                                         detected.getAccountSubtype(),
                                                         detected.getAccountNumber());
                detected.setAccountName(accountName);
                logger.info("✓ Generated account name: {}", accountName);
            }
        }
        
        // Extract account holder/cardholder name for family account username validation
        String accountHolderName = extractAccountHolderNameFromPDF(headerText);
        if (accountHolderName != null) {
            detected.setAccountHolderName(accountHolderName);
            logger.info("✓ Extracted account holder name from PDF: {}", accountHolderName);
        }
        
        return detected;
    }
    
    /**
     * Detect account information from CSV/Excel headers
     * Looks for account number, account name, institution columns
     * Enhanced with better keyword matching and value extraction from headers
     */
    public DetectedAccount detectFromHeaders(List<String> headers, String filename) {
        if (headers == null || headers.isEmpty()) {
            return detectFromFilename(filename);
        }
        
        DetectedAccount detected = new DetectedAccount();
        Map<String, String> headerMap = new HashMap<>();
        Map<String, Integer> headerIndexMap = new HashMap<>(); // Track column indices
        
        // Log all headers (ignore empty lines)
        logger.info("=== HEADER ANALYSIS START ===");
        logger.info("Total headers found: {}", headers.size());
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            if (header != null && !header.trim().isEmpty()) {
                // Handle text on left/right side of same line (split by whitespace/tabs)
                String[] parts = header.split("\\s{2,}|\\t+"); // Split on 2+ spaces or tabs
                for (String part : parts) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        logger.info("Header [Column {}]: '{}' (full: '{}')", i + 1, trimmed, header);
                    }
                }
                
                String lower = header.toLowerCase().trim();
                if (!lower.isEmpty()) {
                    headerMap.put(lower, header); // Keep original for display
                    headerIndexMap.put(lower, i); // Track column index
                }
            }
        }
        logger.info("=== HEADER ANALYSIS END ===");
        
        // CRITICAL: Check if headers look like a transaction table (not account metadata)
        boolean isTransactionTable = isTransactionTableHeadersInternal(headers);
        if (isTransactionTable) {
            logger.info("⚠️ Headers appear to be transaction table headers - skipping account info extraction from header text");
            // For transaction tables, only extract from dedicated account metadata columns
            // Don't extract from generic transaction columns like "type", "description", etc.
        }
        
        // Extract account number from headers (look for patterns in header text itself)
        String accountNumber = extractAccountNumberFromText(String.join(" ", headers));
        if (accountNumber != null) {
            detected.setAccountNumber(accountNumber);
            logger.info("✓ Extracted account number from headers: {}", accountNumber);
        }
        
        // Look for account number column header
        Integer accountNumberColumnIndex = findColumnIndex(headerMap, headerIndexMap, ACCOUNT_NUMBER_KEYWORDS);
        if (accountNumberColumnIndex != null) {
            String headerName = headers.get(accountNumberColumnIndex);
            logger.info("✓ Found account number column at index {}: '{}'", accountNumberColumnIndex, headerName);
            // Note: Account number value will be extracted from data rows, not header
        }
        
        // Look for account name column
        Integer accountNameColumnIndex = findColumnIndex(headerMap, headerIndexMap, 
            Arrays.asList("account name", "accountname", "account", "acct name"));
        if (accountNameColumnIndex != null) {
            String headerName = headers.get(accountNameColumnIndex);
            logger.info("✓ Found account name column at index {}: '{}'", accountNameColumnIndex, headerName);
            // CRITICAL: Extract account name value from header text (e.g., "Account Name: Chase Checking" -> "Chase Checking")
            String accountName = extractAccountNameFromValue(headerName);
            if (accountName != null) {
                // If accountName is empty string, it means column exists but value not in header
                // Set a placeholder to indicate the column was found (value will come from data rows)
                if (accountName.isEmpty()) {
                    detected.setAccountName(""); // Empty string indicates column exists
                    logger.info("✓ Found account name column (value will be extracted from data rows)");
                } else {
                    detected.setAccountName(accountName);
                    logger.info("✓ Extracted account name from header: {}", accountName);
                }
            }
        }
        
        // Look for institution column or product name
        Integer institutionColumnIndex = findColumnIndex(headerMap, headerIndexMap, INSTITUTION_KEYWORDS_HEADERS);
        Integer productNameColumnIndex = findColumnIndex(headerMap, headerIndexMap, PRODUCT_NAME_KEYWORDS);
        
        // Prefer product name over institution name
        if (productNameColumnIndex != null) {
            String headerName = headers.get(productNameColumnIndex);
            logger.info("✓ Found product name column at index {}: '{}'", productNameColumnIndex, headerName);
            // CRITICAL: Extract institution/product name value from header text
            String institutionName = extractInstitutionFromValue(headerName);
            if (institutionName != null) {
                // If institutionName is empty string, it means column exists but value not in header
                // Set a placeholder to indicate the column was found (value will come from data rows)
                if (institutionName.isEmpty()) {
                    detected.setInstitutionName(""); // Empty string indicates column exists
                    logger.info("✓ Found institution/product name column (value will be extracted from data rows)");
                } else {
                    detected.setInstitutionName(institutionName);
                    logger.info("✓ Extracted institution name from header: {}", institutionName);
                }
            }
        } else if (institutionColumnIndex != null) {
            String headerName = headers.get(institutionColumnIndex);
            logger.info("✓ Found institution column at index {}: '{}'", institutionColumnIndex, headerName);
            // CRITICAL: Extract institution name value from header text
            // Even for transaction tables, extract from column header if it contains a value
            String institutionName = extractInstitutionFromValue(headerName);
            if (institutionName != null) {
                // If institutionName is empty string, it means column exists but value not in header
                // Set a placeholder to indicate the column was found (value will come from data rows)
                if (institutionName.isEmpty()) {
                    detected.setInstitutionName(""); // Empty string indicates column exists
                    logger.info("✓ Found institution column (value will be extracted from data rows)");
                } else {
                    detected.setInstitutionName(institutionName);
                    logger.info("✓ Extracted institution name from header: {}", institutionName);
                }
            } else {
                // Column found but no value extracted - set empty string to indicate column exists
                detected.setInstitutionName("");
                logger.info("✓ Found institution column (value will be extracted from data rows)");
            }
        }
        
        // Look for account type column (but only if it's NOT a transaction table)
        // In transaction tables, "type" usually means transaction type, not account type
        Integer accountTypeColumnIndex = null;
        if (!isTransactionTable) {
            accountTypeColumnIndex = findColumnIndex(headerMap, headerIndexMap, ACCOUNT_TYPE_KEYWORDS);
            if (accountTypeColumnIndex != null) {
                String headerName = headers.get(accountTypeColumnIndex);
                logger.info("✓ Found account type column at index {}: '{}'", accountTypeColumnIndex, headerName);
                // Note: Account type value will be extracted from data rows, not header
            }
        } else {
            logger.info("⚠️ Skipping account type column detection - headers are transaction table");
        }
        
        // CRITICAL: Only extract account type from header text if NOT a transaction table
        // Transaction tables often have "type" columns that refer to transaction type, not account type
        if (!isTransactionTable) {
            String accountType = extractAccountTypeFromText(String.join(" ", headers));
            if (accountType != null) {
                detected.setAccountType(accountType);
                logger.info("✓ Extracted account type from headers: {}", accountType);
            }
        } else {
            logger.info("⚠️ Skipping account type extraction from header text - headers are transaction table");
        }
        
        // CRITICAL: Only extract institution name from header text if NOT a transaction table
        // Transaction tables may contain words like "posting", "description" that contain bank name substrings
        // Also skip if headers are too short (single word headers like "Date" don't contain institution names)
        if (!isTransactionTable && headers.size() > 1) {
            String headerText = String.join(" ", headers);
            // Only try extraction if header text has sufficient content (more than just column names)
            // Single word headers like "Date" won't contain institution names
            if (headerText.trim().length() > 10) { // At least 10 characters to have meaningful content
                String institutionFromText = extractInstitutionFromTextStrict(headerText);
                if (institutionFromText != null) {
                    detected.setInstitutionName(institutionFromText);
                    logger.info("✓ Extracted institution name from headers: {}", institutionFromText);
                }
            } else {
                logger.info("⚠️ Skipping institution name extraction - header text too short (likely just column names)");
            }
        } else {
            if (isTransactionTable) {
                logger.info("⚠️ Skipping institution name extraction from header text - headers are transaction table");
            } else {
                logger.info("⚠️ Skipping institution name extraction from header text - only {} header(s) (likely just column names)", headers.size());
            }
        }
        
        // Extract balance from headers (if account type is known or can be inferred)
        String detectedAccountType = detected.getAccountType();
        java.math.BigDecimal balance = extractBalanceFromHeaders(headers, detectedAccountType);
        if (balance != null) {
            detected.setBalance(balance);
            logger.info("✓ Extracted balance from headers: {}", balance);
        }
        
        // CRITICAL: Prioritize filename detection, especially for transaction tables
        DetectedAccount fromFilename = detectFromFilename(filename);
        if (fromFilename != null) {
            // For transaction tables, prioritize filename over header text
            if (isTransactionTable) {
                // Use filename values if available, only fall back to header values if filename doesn't have them
                if (fromFilename.getInstitutionName() != null) {
                    detected.setInstitutionName(fromFilename.getInstitutionName());
                    logger.info("✓ Using institution name from filename (transaction table): {}", fromFilename.getInstitutionName());
                }
                if (fromFilename.getAccountType() != null) {
                    detected.setAccountType(fromFilename.getAccountType());
                    detected.setAccountSubtype(fromFilename.getAccountSubtype());
                    logger.info("✓ Using account type from filename (transaction table): {} / {}", 
                        fromFilename.getAccountType(), fromFilename.getAccountSubtype());
                }
                if (fromFilename.getAccountNumber() != null) {
                    detected.setAccountNumber(fromFilename.getAccountNumber());
                    logger.info("✓ Using account number from filename (transaction table): {}", fromFilename.getAccountNumber());
                }
            } else {
                // For non-transaction tables, use header values if available, fall back to filename
                if (detected.getInstitutionName() == null && fromFilename.getInstitutionName() != null) {
                    detected.setInstitutionName(fromFilename.getInstitutionName());
                    logger.info("✓ Using institution name from filename: {}", fromFilename.getInstitutionName());
                }
                if (detected.getAccountType() == null && fromFilename.getAccountType() != null) {
                    detected.setAccountType(fromFilename.getAccountType());
                    detected.setAccountSubtype(fromFilename.getAccountSubtype());
                    logger.info("✓ Using account type from filename: {} / {}", 
                        fromFilename.getAccountType(), fromFilename.getAccountSubtype());
                }
                if (detected.getAccountNumber() == null && fromFilename.getAccountNumber() != null) {
                    detected.setAccountNumber(fromFilename.getAccountNumber());
                    logger.info("✓ Using account number from filename: {}", fromFilename.getAccountNumber());
                }
            }
        }
        
        return detected;
    }
    
    /**
     * Extract account name value from header text
     * Handles formats like "Account Name: Chase Checking" or "Account Name" or "Chase Checking"
     * If header is just a label (e.g., "Account Name"), returns a placeholder to indicate column exists
     */
    private String extractAccountNameFromValue(String headerValue) {
        if (headerValue == null || headerValue.trim().isEmpty()) {
            return null;
        }
        
        // Remove the column label (e.g., "Account Name:" or "Account Name")
        String value = headerValue.trim();
        String[] parts = value.split("[:：]", 2); // Split on colon (English or Chinese)
        if (parts.length > 1) {
            value = parts[1].trim();
        } else {
            // Check if it's just the label without a value
            String lower = value.toLowerCase();
            if (lower.contains("account name") || lower.contains("accountname") || 
                (lower.contains("account") && !lower.contains("number"))) {
                // This is just the label, no value - return placeholder to indicate column exists
                // The actual value will be extracted from data rows during import
                return ""; // Empty string indicates column exists but value not yet extracted
            }
        }
        
        return value.isEmpty() ? null : value;
    }
    
    /**
     * Extract institution name value from header text
     * Handles formats like "Institution Name: Chase" or "Institution Name" or "Chase"
     * If header is just a label (e.g., "Institution Name"), returns empty string to indicate column exists
     */
    private String extractInstitutionFromValue(String headerValue) {
        if (headerValue == null || headerValue.trim().isEmpty()) {
            return null;
        }
        
        // Remove the column label (e.g., "Institution Name:" or "Institution Name")
        String value = headerValue.trim();
        String[] parts = value.split("[:：]", 2); // Split on colon (English or Chinese)
        if (parts.length > 1) {
            value = parts[1].trim();
        } else {
            // Check if it's just the label without a value
            String lower = value.toLowerCase();
            if (lower.contains("institution") || lower.contains("bank") || 
                lower.contains("product name") || lower.contains("productname")) {
                // This is just the label, no value - return empty string to indicate column exists
                // The actual value will be extracted from data rows during import
                return ""; // Empty string indicates column exists but value not yet extracted
            }
        }
        
        if (value.isEmpty()) {
            return null;
        }
        
        // Normalize the institution name
        return normalizeInstitutionName(value);
    }
    
    /**
     * Check if headers are transaction table headers (public method for CSVImportService)
     */
    public boolean isTransactionTableHeaders(List<String> headers) {
        return isTransactionTableHeadersInternal(headers);
    }
    
    /**
     * Find column index for a list of keywords
     */
    private Integer findColumnIndex(Map<String, String> headerMap, Map<String, Integer> headerIndexMap, 
                                    List<String> keywords) {
        for (String keyword : keywords) {
            if (headerMap.containsKey(keyword.toLowerCase())) {
                return headerIndexMap.get(keyword.toLowerCase());
            }
        }
        return null;
    }
    
    /**
     * Extract account number from text using enhanced pattern matching
     * Handles edge cases: null text, empty text, very long text, malformed patterns
     */
    private String extractAccountNumberFromText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        
        // Limit text length to prevent regex DoS attacks on very long strings
        if (text.length() > 10000) {
            logger.warn("Text too long for account number extraction ({} chars), truncating to 10000", text.length());
            text = text.substring(0, 10000);
        }
        
        try {
            // Try enhanced account number pattern (covers all account types: credit, debit, savings, investment, loan)
            Matcher matcher = ACCOUNT_NUMBER_PATTERN.matcher(text);
            if (matcher.find()) {
                try {
                    String accountNum = matcher.group(1);
                    if (accountNum != null && !accountNum.trim().isEmpty()) {
                        // CRITICAL FIX: Remove masks, hyphens, spaces, and other separators
                        // Keep only digits for consistent storage
                        // Example: "8-41007" -> "841007", "****1234" -> "1234", "1234 5678" -> "12345678"
                        accountNum = accountNum.replaceAll("[*xX\\s-]", "");
                        if (accountNum.length() >= 4) {
                            // Extract last 4 digits (for security, we only store last 4)
                            int startIndex = Math.max(0, accountNum.length() - 4);
                            // Ensure we don't go out of bounds
                            if (startIndex < accountNum.length()) {
                                String lastFour = accountNum.substring(startIndex);
                                // Account number extracted successfully
                                return lastFour;
                            }
                        }
                    }
                } catch (IndexOutOfBoundsException | IllegalStateException e) {
                    logger.warn("Error extracting account number from pattern match: {}", e.getMessage());
                }
            }
            
            // Fallback: Try card number pattern (for credit/debit cards specifically)
            // This handles cases where card-specific patterns weren't caught by the general pattern
            Matcher cardMatcher = CARD_NUMBER_PATTERN.matcher(text);
            if (cardMatcher.find()) {
                try {
                    String cardNum = cardMatcher.group(1);
                    if (cardNum != null && !cardNum.trim().isEmpty()) {
                        // Remove masks, hyphens, spaces
                        cardNum = cardNum.replaceAll("[*xX\\s-]", "");
                        if (cardNum.length() >= 4) {
                            // Extract last 4 digits
                            int startIndex = Math.max(0, cardNum.length() - 4);
                            if (startIndex < cardNum.length()) {
                                String lastFour = cardNum.substring(startIndex);
                                // Card number extracted successfully
                                return lastFour;
                            }
                        }
                    }
                } catch (IndexOutOfBoundsException | IllegalStateException e) {
                    logger.warn("Error extracting card number from pattern match: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("Error extracting account number from text: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Extract account type from text
     */
    private String extractAccountTypeFromText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        
        String lower = text.toLowerCase();
        
        // Check for account type patterns
        for (Map.Entry<String, String> entry : ACCOUNT_TYPE_PATTERNS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        
        return null;
    }
    
    /**
     * Extract institution name from text
     */
    private String extractInstitutionFromText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        
        String institution = detectInstitutionFromText(text);
        if (institution != null) {
            return institution;
        }
        
        return null;
    }
    
    /**
     * Match detected account to existing user account
     * Returns matched account ID or null if no match found
     */
    public String matchToExistingAccount(String userId, DetectedAccount detected) {
        if (userId == null || detected == null) {
            return null;
        }
        
        // Try to match by account number and institution
        if (detected.getAccountNumber() != null && detected.getInstitutionName() != null) {
            try {
                Optional<AccountTable> accountOpt = accountRepository.findByAccountNumberAndInstitution(
                    detected.getAccountNumber(),
                    detected.getInstitutionName(),
                    userId
                );
                if (accountOpt.isPresent()) {
                    logger.info("Matched detected account to existing account: {} (accountId: {})",
                        detected.getAccountName(), accountOpt.get().getAccountId());
                    return accountOpt.get().getAccountId();
                }
            } catch (Exception e) {
                logger.warn("Error matching account by number and institution: {}", e.getMessage());
                // Continue to next matching strategy
            }
        }
        
        // Try to match by account number only
        // CRITICAL FIX: Normalize account numbers before comparison (handles hyphens, spaces, etc.)
        // Only try if account number is not null AND not empty
        if (detected.getAccountNumber() != null && !detected.getAccountNumber().trim().isEmpty()) {
            try {
                // Normalize detected account number
                String normalizedDetected = normalizeAccountNumberForMatching(detected.getAccountNumber());
                
                // Try exact match first (repository method)
                Optional<AccountTable> accountOpt = accountRepository.findByAccountNumber(
                    detected.getAccountNumber(),
                    userId
                );
                if (accountOpt.isPresent()) {
                    logger.info("Matched detected account by number only (exact match): {} (accountId: {})",
                        detected.getAccountName(), accountOpt.get().getAccountId());
                    return accountOpt.get().getAccountId();
                }
                
                // Fallback: Try normalized match (in case stored account number is in different format)
                if (!normalizedDetected.isEmpty()) {
                    List<AccountTable> userAccounts = accountRepository.findByUserId(userId);
                    for (AccountTable account : userAccounts) {
                        if (account.getAccountNumber() != null) {
                            String normalizedExisting = normalizeAccountNumberForMatching(account.getAccountNumber());
                            if (normalizedDetected.equals(normalizedExisting)) {
                                logger.info("Matched detected account by number only (normalized match): {} (accountId: {})",
                                    detected.getAccountName(), account.getAccountId());
                                return account.getAccountId();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Error matching account by number only: {}", e.getMessage());
                // Continue to next matching strategy
            }
        }
        
        // Try to match by institution name and account type
        if (detected.getInstitutionName() != null && detected.getAccountType() != null) {
            try {
                List<AccountTable> userAccounts = accountRepository.findByUserId(userId);
                for (AccountTable account : userAccounts) {
                    // CRITICAL FIX: Handle null institution name in account
                    String accountInstitutionName = account.getInstitutionName();
                    if (accountInstitutionName != null &&
                        detected.getInstitutionName().equalsIgnoreCase(accountInstitutionName) &&
                        detected.getAccountType().equals(account.getAccountType())) {
                        // If we have account number (not null and not empty), prefer exact match (with normalization)
                        // CRITICAL FIX: Normalize account numbers before comparison
                        // If account number is null or empty, match by institution and type only
                        String detectedAccountNumber = detected.getAccountNumber();
                        boolean accountNumberMatches = true; // Default to true if no account number to match
                        
                        if (detectedAccountNumber != null && !detectedAccountNumber.trim().isEmpty()) {
                            // We have an account number - must match
                            String normalizedDetected = normalizeAccountNumberForMatching(detectedAccountNumber);
                            String normalizedExisting = account.getAccountNumber() != null ? 
                                normalizeAccountNumberForMatching(account.getAccountNumber()) : "";
                            accountNumberMatches = normalizedDetected.equals(normalizedExisting);
                        } else {
                            // No account number in detected account - match by institution and type only
                            accountNumberMatches = true;
                        }
                        
                        if (accountNumberMatches) {
                            logger.info("Matched detected account by institution and type: {} (accountId: {})",
                                detected.getAccountName(), account.getAccountId());
                            return account.getAccountId();
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Error matching account by institution and type: {}", e.getMessage());
                // Return null if all matching strategies fail
            }
        }
        
        logger.info("No existing account match found for detected account: {}",
            detected.getAccountName() != null ? detected.getAccountName() : "Unknown");
        return null;
    }
    
    // Helper methods
    
    /**
     * Normalize account number for matching - remove hyphens, spaces, and other separators, extract last 4 digits
     * CRITICAL: This ensures consistent comparison regardless of format (e.g., "8-41007" vs "841007" vs "8 41007")
     * 
     * @param accountNumber Account number in any format (may contain hyphens, spaces, etc.)
     * @return Normalized account number (last 4 digits only, digits only)
     */
    private String normalizeAccountNumberForMatching(String accountNumber) {
        if (accountNumber == null || accountNumber.trim().isEmpty()) {
            return "";
        }
        
        // Remove all non-digit characters (hyphens, spaces, masks, etc.)
        String digitsOnly = accountNumber.replaceAll("[^0-9]", "");
        
        if (digitsOnly.length() == 0) {
            return "";
        }
        
        // Extract last 4 digits (for security and consistency)
        if (digitsOnly.length() > 4) {
            return digitsOnly.substring(digitsOnly.length() - 4);
        }
        
        return digitsOnly;
    }
    
    private String detectInstitutionFromFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return null;
        }
        
        // CRITICAL: Limit filename length to prevent performance issues
        if (filename.length() > 1000) {
            filename = filename.substring(0, 1000);
        }
        
        // CRITICAL: Normalize underscores and hyphens to spaces for better matching
        // e.g., "wells_fargo" should match "wells fargo" keyword
        String normalized = filename.toLowerCase().replaceAll("[_\\-]", " ");
        String lower = normalized;
        
        for (String keyword : INSTITUTION_KEYWORDS) {
            if (keyword != null) {
                // Check if normalized filename contains the keyword (with spaces normalized)
                String normalizedKeyword = keyword.toLowerCase().replaceAll("[_\\-]", " ");
                if (lower.contains(normalizedKeyword)) {
                    // Normalize institution name
                    return normalizeInstitutionName(keyword);
                }
            }
        }
        return null;
    }
    
    private String detectInstitutionFromText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        
        // CRITICAL: Limit text length to prevent performance issues and regex DoS
        if (text.length() > 10000) {
            text = text.substring(0, 10000);
        }
        
        String lower = text.toLowerCase();
        for (String keyword : INSTITUTION_KEYWORDS) {
            if (keyword != null && lower.contains(keyword)) {
                return normalizeInstitutionName(keyword);
            }
        }
        return null;
    }
    
    // Cache compiled patterns for institution keywords to avoid recompiling in loops
    private static final Map<String, Pattern> INSTITUTION_PATTERN_CACHE = new HashMap<>();
    
    // Cache compiled patterns for website matching
    private static final Map<String, Pattern> WEBSITE_PATTERN_CACHE = new HashMap<>();
    
    static {
        // Pre-compile patterns for common institution keywords
        for (String keyword : INSTITUTION_KEYWORDS) {
            String pattern = "\\b" + Pattern.quote(keyword) + "\\b";
            INSTITUTION_PATTERN_CACHE.put(keyword, Pattern.compile(pattern, Pattern.CASE_INSENSITIVE));
        }
    }
    
    /**
     * Match structure to track all match information for scoring
     */
    private static class InstitutionMatch {
        String normalizedName;
        double totalScore = 0.0;
        int headerFrequency = 0;
        int transactionFrequency = 0;
        boolean hasWebsiteMatch = false;
        int keywordSpecificity = 0; // 0=abbreviation, 1=partial, 2=full name
    }
    
    /**
     * Enhanced institution name extraction with:
     * 1. Context-aware section prioritization (header vs transaction)
     * 2. Website pattern matching (www.<institution>.com)
     * 3. Frequency-based ranking (more occurrences = higher confidence)
     * 
     * Uses whole word matching to avoid false positives from substrings
     * in transaction table headers (e.g., "ing" in "posting", "description")
     */
    private String extractInstitutionFromTextStrict(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        
        // 1. Split text into header and transaction sections
        String[] sections = splitIntoHeaderAndTransactionSections(text);
        String headerSection = sections[0];
        String transactionSection = sections[1];
        
        // 2. Find all institution matches with scoring
        Map<String, InstitutionMatch> matches = new HashMap<>();
        
        // Search header section (higher priority)
        findInstitutionMatches(headerSection, matches, 1.0, true);
        
        // Search transaction section (lower priority)
        findInstitutionMatches(transactionSection, matches, 0.3, false);
        
        // 3. Select best match based on combined score
        return selectBestMatch(matches);
    }
    
    /**
     * Split text into header and transaction sections
     * Header section: lines before transaction table starts
     * Transaction section: lines after transaction table headers
     */
    private String[] splitIntoHeaderAndTransactionSections(String text) {
        if (text == null || text.isEmpty()) {
            return new String[]{"", ""};
        }
        
        String[] lines = text.split("\n");
        StringBuilder headerSection = new StringBuilder();
        StringBuilder transactionSection = new StringBuilder();
        
        boolean inTransactionSection = false;
        
        // Look for transaction table indicators
        List<String> transactionKeywords = Arrays.asList(
            "date", "posting date", "transaction date", "value date",
            "amount", "debit", "credit", "balance",
            "description", "details", "memo", "notes"
        );
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line == null) continue;
            
            String lowerLine = line.toLowerCase().trim();
            
            // Check if this line looks like transaction table headers
            if (!inTransactionSection) {
                int transactionColumnCount = 0;
                for (String keyword : transactionKeywords) {
                    if (lowerLine.contains(keyword) && 
                        (lowerLine.equals(keyword) || 
                         lowerLine.startsWith(keyword + " ") || 
                         lowerLine.endsWith(" " + keyword) ||
                         lowerLine.contains(" " + keyword + " "))) {
                        transactionColumnCount++;
                    }
                }
                
                // If we find 2+ transaction-related columns, this is likely the transaction table start
                if (transactionColumnCount >= 2) {
                    inTransactionSection = true;
                }
            }
            
            if (inTransactionSection) {
                transactionSection.append(line).append("\n");
            } else {
                headerSection.append(line).append("\n");
            }
        }
        
        return new String[]{
            headerSection.toString().trim(),
            transactionSection.toString().trim()
        };
    }
    
    /**
     * Find institution matches in a section with scoring
     */
    private void findInstitutionMatches(String section, Map<String, InstitutionMatch> matches, 
                                        double baseScore, boolean isHeader) {
        if (section == null || section.isEmpty()) {
            return;
        }
        
        String lower = section.toLowerCase();
        
        for (String keyword : INSTITUTION_KEYWORDS) {
            String normalized = normalizeInstitutionName(keyword);
            InstitutionMatch match = matches.computeIfAbsent(normalized, k -> {
                InstitutionMatch m = new InstitutionMatch();
                m.normalizedName = normalized;
                return m;
            });
            
            // 1. Count frequency of institution name matches
            Pattern namePattern = INSTITUTION_PATTERN_CACHE.computeIfAbsent(keyword, k -> {
                String patternStr = "\\b" + Pattern.quote(k) + "\\b";
                return Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
            });
            
            int frequency = countMatches(namePattern, lower);
            if (frequency > 0) {
                if (isHeader) {
                    match.headerFrequency += frequency;
                } else {
                    match.transactionFrequency += frequency;
                }
                
                // Add to score: base score * frequency (with diminishing returns using log1p)
                double frequencyScore = baseScore * Math.log1p(frequency);
                match.totalScore += frequencyScore;
            }
            
            // 2. Check for website pattern match (www.<institution>.com)
            String websitePattern = generateWebsitePattern(keyword);
            Pattern websiteRegex = WEBSITE_PATTERN_CACHE.computeIfAbsent(keyword, k -> {
                return Pattern.compile(websitePattern, Pattern.CASE_INSENSITIVE);
            });
            
            if (websiteRegex.matcher(lower).find()) {
                match.hasWebsiteMatch = true;
                // Website match is a strong signal - add significant bonus
                double websiteBonus = isHeader ? 2.0 : 0.5; // Higher bonus in header
                match.totalScore += websiteBonus;
            }
            
            // 3. Keyword specificity scoring (update if this keyword is more specific)
            int specificity = calculateKeywordSpecificity(keyword, normalized);
            if (match.keywordSpecificity < specificity) {
                match.keywordSpecificity = specificity;
            }
        }
    }
    
    /**
     * Generate website pattern from institution keyword
     * Examples: "american express" -> "www\\.americanexpress\\.com"
     *           "chase" -> "www\\.chase\\.com"
     *           "bank of america" -> "www\\.bankofamerica\\.com"
     */
    private String generateWebsitePattern(String keyword) {
        // Remove spaces, special characters, and convert to URL format
        String urlName = keyword.toLowerCase()
            .replaceAll("\\s+", "")  // Remove spaces
            .replaceAll("&", "and")  // Replace & with and
            .replaceAll("[^a-z0-9]", "");  // Remove other special characters
        
        // Match variations: www.chase.com, chase.com, www.chase.net, etc.
        // Support common TLDs: com, net, org, and country-specific ones
        return "(?:www\\.)?" + Pattern.quote(urlName) + "\\.(?:com|net|org|co\\.uk|co\\.jp|co\\.kr|co\\.za|com\\.au|com\\.sg|com\\.my|com\\.in)";
    }
    
    /**
     * Calculate keyword specificity (full name > partial > abbreviation)
     * Returns: 2 for full names, 1 for partial, 0 for abbreviations
     */
    private int calculateKeywordSpecificity(String keyword, String normalized) {
        // Full names (2+ words or long single word > 10 chars) = 2
        if (keyword.split("\\s+").length >= 2 || keyword.length() > 10) {
            return 2;
        }
        // Partial names (medium length 5-10 chars) = 1
        if (keyword.length() >= 5 && keyword.length() <= 10) {
            return 1;
        }
        // Abbreviations (short < 5 chars) = 0
        return 0;
    }
    
    /**
     * Count number of matches for a pattern in text
     */
    private int countMatches(Pattern pattern, String text) {
        int count = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }
    
    /**
     * Select best match based on combined scoring
     * Ranking priority:
     * 1. Total score (highest)
     * 2. Header frequency (more occurrences in header = better)
     * 3. Transaction frequency (fewer in transactions = better, but less weight)
     * 4. Keyword specificity (full name > partial > abbreviation)
     * 5. Website match (has website = better)
     */
    private String selectBestMatch(Map<String, InstitutionMatch> matches) {
        if (matches.isEmpty()) {
            return null;
        }
        
        // Add specificity bonus to total score for final ranking
        for (InstitutionMatch match : matches.values()) {
            match.totalScore += match.keywordSpecificity * 0.2; // Small bonus for specificity
        }
        
        InstitutionMatch best = matches.values().stream()
            .max(Comparator
                .comparingDouble((InstitutionMatch m) -> m.totalScore)
                .thenComparingInt((InstitutionMatch m) -> m.headerFrequency)
                .thenComparing((InstitutionMatch m) -> -m.transactionFrequency) // Negative: fewer in transactions = better
                .thenComparingInt((InstitutionMatch m) -> m.keywordSpecificity)
                .thenComparing((InstitutionMatch m) -> m.hasWebsiteMatch ? 1 : 0))
            .orElse(null);
        
        if (best != null && best.totalScore > 0) {
            return best.normalizedName;
        }
        
        return null;
    }
    
    /**
     * Check if headers look like a transaction table (not account metadata)
     * Transaction tables typically have columns like: date, amount, description, balance, type
     */
    private boolean isTransactionTableHeadersInternal(List<String> headers) {
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        
        // Common transaction table column names
        List<String> transactionKeywords = Arrays.asList(
            "date", "posting date", "transaction date", "value date",
            "amount", "debit", "credit", "balance",
            "description", "details", "memo", "notes",
            "type", "transaction type", "category",
            "check", "check number", "check or slip", "reference", "ref"
        );
        
        int transactionColumnCount = 0;
        
        for (String keyword : transactionKeywords) {
            // Check if keyword appears as a header (not just in text)
            for (String header : headers) {
                if (header != null) {
                    String headerLower = header.toLowerCase().trim();
                    // Match whole word or exact header match
                    if (headerLower.equals(keyword) || 
                        headerLower.contains(keyword) && 
                        (headerLower.startsWith(keyword + " ") || 
                         headerLower.endsWith(" " + keyword) ||
                         headerLower.contains(" " + keyword + " "))) {
                        transactionColumnCount++;
                        break; // Count each keyword only once
                    }
                }
            }
        }
        
        // If we find 3+ transaction-related columns, it's likely a transaction table
        boolean isTransactionTable = transactionColumnCount >= 3;
        
        if (isTransactionTable) {
            logger.info("⚠️ Detected transaction table headers (found {} transaction-related columns)", transactionColumnCount);
        }
        
        return isTransactionTable;
    }
    
    private String normalizeInstitutionName(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return null;
        }
        
        // Normalize common variations
        Map<String, String> normalizations = new HashMap<>();
        normalizations.put("bofa", "Bank of America");
        normalizations.put("bank of america", "Bank of America");
        normalizations.put("wf", "Wells Fargo");
        normalizations.put("wells fargo", "Wells Fargo");
        normalizations.put("usbank", "U.S. Bank");
        normalizations.put("us bank", "U.S. Bank");
        normalizations.put("capone", "Capital One");
        normalizations.put("capitol one", "Capital One");
        normalizations.put("capital one", "Capital One");
        normalizations.put("jpm", "JPMorgan Chase");
        normalizations.put("jpmorgan", "JPMorgan Chase");
        normalizations.put("amex", "American Express");
        normalizations.put("american express", "American Express");
        normalizations.put("chase", "Chase");
        normalizations.put("citi", "Citibank");
        normalizations.put("citibank", "Citibank");
        normalizations.put("citicards", "Citibank");
        normalizations.put("east west bank", "East West Bank");
        normalizations.put("eastwest bank", "East West Bank");
        
        String lowerKeyword = keyword.toLowerCase();
        if (normalizations.containsKey(lowerKeyword)) {
            return normalizations.get(lowerKeyword);
        }
        
        // Safely capitalize first letter
        if (keyword.length() > 0) {
            return keyword.substring(0, 1).toUpperCase() + 
                   (keyword.length() > 1 ? keyword.substring(1) : "");
        }
        return keyword;
    }
    
    private String detectAccountTypeFromFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return null;
        }
        
        // CRITICAL: Limit filename length to prevent performance issues
        if (filename.length() > 1000) {
            logger.debug("Filename too long ({} chars), truncating for account type detection", filename.length());
            filename = filename.substring(0, 1000);
        }
        
        // CRITICAL: Normalize underscores and hyphens to spaces for better matching
        String normalized = filename.toLowerCase().replaceAll("[_\\-]", " ");
        String lower = normalized;
        
        // CRITICAL: Prioritize credit card patterns over checking/savings patterns
        // Check credit card patterns first (more specific)
        String[] creditCardPatterns = {"credit card", "creditcard", "card"};
        for (String pattern : creditCardPatterns) {
            if (lower.contains(pattern)) {
                return "credit";
            }
        }
        
        // Then check other patterns
        for (Map.Entry<String, String> entry : ACCOUNT_TYPE_PATTERNS.entrySet()) {
            if (entry.getKey() != null) {
                // Normalize pattern key for matching
                String normalizedKey = entry.getKey().toLowerCase().replaceAll("[_\\-]", " ");
                if (lower.contains(normalizedKey)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }
    
    private String generateAccountName(String institution, String type, String subtype, String accountNumber) {
        StringBuilder name = new StringBuilder();
        
        // CRITICAL FIX: Handle null/empty values safely
        if (institution != null && !institution.isEmpty()) {
            name.append(institution);
        }
        
        if (subtype != null && !subtype.isEmpty()) {
            if (name.length() > 0) {
                name.append(" ");
            }
            name.append(subtype);
        } else if (type != null && !type.isEmpty()) {
            if (name.length() > 0) {
                name.append(" ");
            }
            name.append(type);
        }
        
        if (accountNumber != null && !accountNumber.isEmpty()) {
            if (name.length() > 0) {
                name.append(" ");
            }
            name.append(accountNumber);
        }
        
        String result = name.toString().trim();
        // CRITICAL FIX: Ensure we always return a non-empty name
        return result.isEmpty() ? "Unknown Account" : result;
    }
    
    /**
     * Detect credit card from PDF content using general patterns
     * Works for ALL credit card issuers, not just specific ones
     */
    private boolean detectCreditCardFromContent(String lowerHeader) {
        // General credit card indicators that work for all issuers
        List<String> creditCardIndicators = Arrays.asList(
            "credit card",
            "card statement",
            "credit limit",
            "available credit",
            "cash advance",
            "cash advances",
            "minimum payment",
            "payment due date",
            "billing period",
            "credit counseling",
            "new balance",
            "previous balance",
            "purchases",
            "interest charge",
            "annual fee",
            "apr",
            "annual percentage rate"
        );
        
        // Check for any credit card indicator
        for (String indicator : creditCardIndicators) {
            if (lowerHeader.contains(indicator)) {
                return true;
            }
        }
        
        // Also check for card product patterns (institution + card keywords)
        // This works for any institution in our keyword list
        boolean hasInstitution = false;
        for (String institution : INSTITUTION_KEYWORDS) {
            if (lowerHeader.contains(institution.toLowerCase())) {
                hasInstitution = true;
                break;
            }
        }
        
        // Common card product keywords (works for all issuers globally)
        List<String> cardProductKeywords = Arrays.asList(
            "card", "rewards", "platinum", "gold", "silver", "preferred",
            "signature", "world", "elite", "infinite", "reserve", "freedom",
            "sapphire", "double cash", "cash back", "miles", "points",
            // US Card Products
            "venture", "savor", "quicksilver", "spark", "freedom unlimited",
            "unlimited", "blue cash", "everyday", "delta", "marriott", "hilton",
            "hyatt", "ihg", "aeroplan", "avios", "skywards",
            // European Card Products
            "classic", "premium", "black", "titanium", "carbon", "metal",
            // Asian Card Products
            "diamond", "imperial", "royal", "prestige", "exclusive",
            // General Terms
            "credit", "debit", "charge", "prepaid", "gift", "travel", "business"
        );
        
        if (hasInstitution) {
            for (String keyword : cardProductKeywords) {
                if (lowerHeader.contains(keyword)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Extract header text from PDF, including Service Agreement section if found
     * For Chase cards, the card name is often in the Service Agreement section (~line 100)
     * This method searches for specific sections and combines header + section text
     * By default extends to 150 lines, and if no section found, extends to 200 lines
     */
    private String extractHeaderTextWithServiceAgreement(String pdfText) {
        if (pdfText == null || pdfText.isEmpty()) {
            return "";
        }
        
        String[] lines = pdfText.split("\n");
        if (lines.length == 0) {
            return pdfText.length() > 1000 ? pdfText.substring(0, 1000) : pdfText;
        }
        
        StringBuilder headerBuilder = new StringBuilder();
        
        // 1. By default include first 300 lines (account info, statement header, and deeper content)
        // Increased from 150 to 300 to better detect account holder names in longer headers
        int defaultLines = Math.min(300, lines.length);
        headerBuilder.append(String.join("\n", Arrays.copyOf(lines, defaultLines)));
        
        // 2. Search for Service Agreement or Cardholder Agreement section
        List<String> sectionKeywords = Arrays.asList(
            "service agreement", "cardholder agreement", "cardmember agreement",
            "terms and conditions", "card agreement", "account agreement"
        );
        
        int sectionStartLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].toLowerCase().trim();
            for (String keyword : sectionKeywords) {
                if (line.contains(keyword)) {
                    sectionStartLine = i;
                    logger.debug("Found section '{}' at line {}", keyword, i + 1);
                    break;
                }
            }
            if (sectionStartLine >= 0) {
                break;
            }
        }
        
        // 3. If section found, extract text around it (50 lines before to 150 lines after)
        if (sectionStartLine >= 0) {
            int sectionStart = Math.max(0, sectionStartLine - 50); // 50 lines before section
            int sectionEnd = Math.min(lines.length, sectionStartLine + 150); // 150 lines after section start
            
            // Only add if it's beyond the default 300 lines we already have
            if (sectionStart > defaultLines) {
                headerBuilder.append("\n\n=== SERVICE AGREEMENT SECTION ===\n");
                headerBuilder.append(String.join("\n", Arrays.copyOfRange(lines, sectionStart, sectionEnd)));
            } else if (sectionEnd > defaultLines) {
                // Section overlaps with default lines, just extend
                headerBuilder.append("\n\n=== SERVICE AGREEMENT SECTION (extended) ===\n");
                headerBuilder.append(String.join("\n", Arrays.copyOfRange(lines, defaultLines, sectionEnd)));
            }
        } else {
            // No section found - default 300 lines should be sufficient now
            // (Previously extended to 200, but now default is 300 so this path is less likely)
            if (lines.length > defaultLines) {
                logger.debug("No Service Agreement section found, using default {} lines", defaultLines);
            }
        }
        
        String headerText = headerBuilder.toString();
        
        // Log header text extraction summary
        logger.info("Extracted header text: {} lines from default section, {} total characters", 
                defaultLines, headerText.length());
        
        return headerText;
    }
    
    /**
     * Extract product/card name from PDF content
     * General approach that works for ALL credit card issuers and product names
     * Uses institution keywords list instead of hardcoded values
     */
    private String extractProductNameFromPDF(String headerText) {
        if (headerText == null || headerText.isEmpty()) {
            return null;
        }
        
        // Pattern 0a: Extract Prime Visa from "YOUR PRIME VISA POINTS" or "Prime Visa" patterns
        // High priority - very specific pattern for Amazon Prime Visa cards
        Pattern primeVisaPattern = Pattern.compile(
            "(?i)(?:your\\s+)?(prime\\s+visa|amazon\\s+prime\\s+visa|prime\\s+rewards\\s+visa|prime\\s+visa\\s+signature)(?:\\s+points|\\s+card|\\s*®|\\s*™)?",
            Pattern.CASE_INSENSITIVE
        );
        Matcher primeVisaMatcher = primeVisaPattern.matcher(headerText);
        if (primeVisaMatcher.find()) {
            String cardName = primeVisaMatcher.group(1).trim();
            cardName = cardName.replaceAll("\\s+", " "); // Normalize whitespace
            // Capitalize properly: "Prime Visa" or "Amazon Prime Visa"
            cardName = capitalizeCardName(cardName);
            if (cardName.length() > 3 && cardName.length() < 100) {
                logger.info("Extracted product name from 'Prime Visa' pattern: {}", cardName);
                return cardName;
            }
        }
        
        // Pattern 0b: Extract card name from "thank you for using your [card name]" phrases
        // Example: "thank you for using your marriott bonvoy® premier credit card"
        Pattern thankYouPattern = Pattern.compile(
            "(?i)thank\\s+you\\s+for\\s+using\\s+your\\s+([a-z0-9\\s®™©]+?)\\s*(?:credit\\s*)?card",
            Pattern.CASE_INSENSITIVE
        );
        Matcher thankYouMatcher = thankYouPattern.matcher(headerText);
        if (thankYouMatcher.find()) {
            String cardName = thankYouMatcher.group(1).trim();
            cardName = cardName.replaceAll("\\s+", " "); // Normalize whitespace
            if (cardName.length() > 3 && cardName.length() < 100) {
                logger.info("Extracted product name from 'thank you' pattern: {}", cardName);
                return cardName;
            }
        }
        
        // Pattern 0c: Extract from "Reward your routine everywhere you shop with your [card name]" phrases
        // Example: "Reward your routine everywhere you shop with your Prime Visa."
        Pattern rewardPattern = Pattern.compile(
            "(?i)reward\\s+(?:your\\s+)?(?:routine|everywhere|.*?)\\s+(?:with\\s+your\\s+)?([a-z0-9\\s®™©]+?)\\s*(?:card|\\s*®|\\s*™|\\s*\\.)?",
            Pattern.CASE_INSENSITIVE
        );
        Matcher rewardMatcher = rewardPattern.matcher(headerText);
        if (rewardMatcher.find()) {
            String cardName = rewardMatcher.group(1).trim();
            cardName = cardName.replaceAll("\\s+", " "); // Normalize whitespace
            // Check if it's a valid card name (contains card product keywords)
            if (isValidCardProductName(cardName)) {
                cardName = capitalizeCardName(cardName);
                if (cardName.length() > 3 && cardName.length() < 100) {
                    logger.info("Extracted product name from 'reward' pattern: {}", cardName);
                    return cardName;
                }
            }
        }
        
        // Pattern 1: Look for lines containing institution keyword + product name + "card"
        // This works for any institution in our keyword list
        // CRITICAL: Process lines and collect potential matches, then prioritize by specificity
        String[] lines = headerText.split("\n");
        // Store candidates with their matched indicators for better prioritization
        List<Map.Entry<String, String>> candidateProductNames = new ArrayList<>(); // candidate -> matchedIndicator
        
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            
            String trimmedLine = line.trim();
            String lowerLine = trimmedLine.toLowerCase();
            
            // Check if line contains any institution keyword
            String foundInstitution = null;
            for (String institution : INSTITUTION_KEYWORDS) {
                String lowerInstitution = institution.toLowerCase();
                // Use word boundaries to avoid false matches
                Pattern institutionPattern = Pattern.compile("\\b" + Pattern.quote(lowerInstitution) + "\\b", Pattern.CASE_INSENSITIVE);
                if (institutionPattern.matcher(lowerLine).find()) {
                    foundInstitution = institution;
                    break;
                }
            }
            
            // If we found an institution, check if this looks like a product name line
            if (foundInstitution != null) {
                // Product name indicators (works for all card types globally)
                // Prioritize specific card product names over generic terms
                // List ordered by specificity (most specific first)
                List<String> specificProductIndicators = Arrays.asList(
                    // Multi-word card names (most specific first)
                    // Amazon/Prime Cards
                    "prime visa", "amazon prime visa", "amazon prime card", "prime rewards visa",
                    "prime visa signature", "amazon prime rewards", "prime card",
                    // Chase Cards
                    "marriott bonvoy premier", "marriott bonvoy", "double cash", "cash back", 
                    "active cash", "blue cash", "freedom ultimate", "freedom unlimited", "freedom", 
                    "sapphire reserve", "sapphire preferred", "sapphire",
                    // Visa/Mastercard/Amex Tier Cards
                    "visa signature", "visa infinite", "visa platinum", "visa classic",
                    "mastercard world", "mastercard world elite", "mastercard platinum", "mastercard gold",
                    "amex platinum", "amex gold", "amex green", "amex blue",
                    // Capital One Cards
                    "quicksilver", "venture", "savor", "spark",
                    // Citi Cards
                    "double cash", "premier", "diamond preferred",
                    // Discover Cards
                    "it", "miles", "cash back",
                    // Single-word and other specific indicators
                    "unlimited", "everyday", "miles", "points",
                    "hilton", "hyatt", "delta", "marriott", "bonvoy",
                    "platinum", "gold", "silver", "titanium",
                    "signature", "world", "elite", "infinite", "reserve", "preferred", "ultimate",
                    "classic", "premium", "black", "diamond", "imperial",
                    "royal", "prestige", "exclusive", "travel", "business", "rewards",
                    "card", "®", "™"
                );
                
                // Check for specific product indicators first (higher priority)
                // Use longest match first to prefer "marriott bonvoy premier" over just "marriott"
                String matchedProductIndicator = null;
                int longestMatchLength = 0;
                for (String indicator : specificProductIndicators) {
                    String lowerIndicator = indicator.toLowerCase();
                    if (lowerLine.contains(lowerIndicator)) {
                        // Prefer longer matches (more specific)
                        if (indicator.length() > longestMatchLength) {
                            matchedProductIndicator = indicator;
                            longestMatchLength = indicator.length();
                        }
                    }
                }
                
                // Skip lines with generic terms that are not card products (lower priority)
                // Only skip if these appear with URLs or are clearly not card names
                List<String> genericTermsToSkip = Arrays.asList(
                    "mobile app", "mobile", "app", "website", "statement",
                    "account", "login", "register", "contact", "support", "help"
                );
                
                // Additional check: skip lines that contain URLs (likely not card names)
                // Pattern matches: www., http://, https://, or domain extensions like .com, .org, etc.
                boolean containsUrl = lowerLine.matches(".*\\b(?:www\\.|http://|https://|\\.[a-z]{2,})\\b.*");
                
                // Also check for common URL patterns like "chase.com", "wellsfargo.com"
                boolean containsDomain = lowerLine.matches(".*\\b(?:chase|wells\\s*fargo|bankofamerica|citibank|americanexpress|amex)\\s*\\.\\s*com\\b.*");
                
                // CRITICAL: Define strong product indicators BEFORE using them
                // Strong indicators are specific card names, not generic terms like "card"
                List<String> strongProductIndicators = Arrays.asList(
                    // Amazon/Prime Cards (highest priority - very specific)
                    "prime visa", "amazon prime visa", "amazon prime card", "prime rewards visa",
                    "prime visa signature", "amazon prime rewards",
                    // Chase Cards
                    "freedom ultimate", "freedom unlimited", "freedom", "sapphire reserve", 
                    "sapphire preferred", "sapphire", "active cash", "double cash", "cash back",
                    "blue cash", "marriott bonvoy premier", "marriott bonvoy",
                    // Visa/Mastercard/Amex Tier Cards
                    "visa signature", "visa infinite", "visa platinum",
                    "mastercard world", "mastercard world elite",
                    "amex platinum", "amex gold",
                    // Other specific cards
                    "quicksilver", "spark", "venture", "savor"
                );
                
                boolean hasGenericTerm = false;
                // CRITICAL: More aggressive filtering for generic terms
                // If line contains "mobile app" or similar terms, it's almost certainly not a card name
                // unless it has a very strong product indicator
                for (String term : genericTermsToSkip) {
                    String lowerTerm = term.toLowerCase();
                    if (lowerLine.contains(lowerTerm)) {
                        // If we have a strong product indicator, allow it
                        // Otherwise, reject the line
                        if (matchedProductIndicator == null) {
                            hasGenericTerm = true;
                            break;
                        } else {
                            // Check if the matched indicator is strong enough to override generic term
                            boolean isStrongEnough = false;
                            for (String strongIndicator : strongProductIndicators) {
                                if (matchedProductIndicator.toLowerCase().contains(strongIndicator.toLowerCase())) {
                                    isStrongEnough = true;
                                    break;
                                }
                            }
                            // If not strong enough, still reject
                            if (!isStrongEnough) {
                                hasGenericTerm = true;
                                break;
                            }
                        }
                    }
                }
                
                boolean hasStrongIndicator = false;
                if (matchedProductIndicator != null) {
                    for (String strongIndicator : strongProductIndicators) {
                        if (matchedProductIndicator.toLowerCase().contains(strongIndicator.toLowerCase())) {
                            hasStrongIndicator = true;
                            break;
                        }
                    }
                }
                
                // Skip lines with URLs/domains unless they have a strong product indicator
                if ((containsUrl || containsDomain) && !hasStrongIndicator) {
                    logger.info("Skipping line with URL/domain and no strong product indicator: {}", trimmedLine);
                    continue;
                }
                
                // Also skip lines with action phrases that indicate website/branch instructions
                List<String> actionPhrases = Arrays.asList(
                    "activate for free", "visit a", "visit", "go to", "log in", "sign in",
                    "register", "enroll", "call", "contact us", "customer service"
                );
                boolean hasActionPhrase = false;
                for (String phrase : actionPhrases) {
                    if (lowerLine.contains(phrase.toLowerCase())) {
                        hasActionPhrase = true;
                        break;
                    }
                }
                // Always skip lines with action phrases + URLs/domains (even if they have indicators)
                if (hasActionPhrase && (containsUrl || containsDomain)) {
                    logger.info("Skipping line with action phrase and URL/domain: {}", trimmedLine);
                    continue;
                }
                
                // CRITICAL: Blacklist patterns for contact information, addresses, and non-product-name content
                List<String> blacklistPatterns = Arrays.asList(
                    // Contact information patterns
                    "write us at", "write us", "questions", "question", "if you have",
                    "contact us", "call us", "email us", "mail us", "send us",
                    "customer service", "customer support", "technical support",
                    // Address patterns
                    "p\\.o\\. box", "po box", "p.o. box", "post office box",
                    "street", "avenue", "road", "boulevard", "drive", "lane",
                    "suite", "apt", "apartment", "unit",
                    // ZIP code patterns (5 digits or ZIP+4)
                    "\\d{5}(?:-\\d{4})?", "\\d{5}\\s+\\d{4}",
                    // State abbreviations followed by ZIP (e.g., "TX 79998")
                    "\\b[A-Z]{2}\\s+\\d{5}",
                    // City, State patterns
                    "el paso", "san francisco", "new york", "los angeles",
                    // Phone number patterns
                    "\\d{1,3}[\\-.]?\\d{3}[\\-.]?\\d{3}[\\-.]?\\d{4}",
                    "\\(\\s*\\d{1,3}\\s*[\\-)]?\\s*\\d{3}",
                    // Email patterns
                    "@", "\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b",
                    // Other non-product patterns
                    "electronic funds", "funds services", "services,", "services.",
                    "you may also", "you may", "for more", "for additional",
                    "please", "thank you", "sincerely", "regards"
                );
                
                boolean matchesBlacklist = false;
                for (String blacklistPattern : blacklistPatterns) {
                    Pattern pattern = Pattern.compile(blacklistPattern, Pattern.CASE_INSENSITIVE);
                    if (pattern.matcher(trimmedLine).find()) {
                        matchesBlacklist = true;
                        // Skipping line matching blacklist pattern
                        break;
                    }
                }
                if (matchesBlacklist) {
                    continue;
                }
                
                // Skip "online" only if it appears with URLs or is clearly not part of a card name
                if (lowerLine.contains("online") && matchedProductIndicator != null) {
                    // Only skip if it's clearly a website reference (contains URL)
                    if (containsUrl && !lowerLine.matches(".*(?:visa|mastercard|amex|discover).*")) {
                        hasGenericTerm = true;
                    }
                } else if (lowerLine.contains("online") && matchedProductIndicator == null) {
                    hasGenericTerm = true;
                }
                
                // If line has institution + specific product indicator, and is reasonably short (likely a product name)
                if (matchedProductIndicator != null && !hasGenericTerm && trimmedLine.length() < 150) {
                    String productName = trimmedLine;
                    
                    // For multi-word indicators like "marriott bonvoy premier", try to extract the full card name
                    // by finding the portion of the line that contains the matched indicator
                    if (matchedProductIndicator != null && matchedProductIndicator.contains(" ")) {
                        // Try to extract a more complete card name from the line
                        // Look for patterns like "Chase Marriott Bonvoy Premier Card" or "Marriott Bonvoy Premier"
                        Pattern fullCardNamePattern = Pattern.compile(
                            "(?i)(?:chase\\s+)?(?:" + Pattern.quote(foundInstitution) + "\\s+)?([a-z0-9\\s®™©]*?" + 
                            Pattern.quote(matchedProductIndicator) + "[a-z0-9\\s®™©]*?)(?:\\s+card|\\s*®|\\s*™)?",
                            Pattern.CASE_INSENSITIVE
                        );
                        Matcher fullCardMatcher = fullCardNamePattern.matcher(productName);
                        if (fullCardMatcher.find()) {
                            String extractedName = fullCardMatcher.group(1).trim();
                            if (extractedName.length() > matchedProductIndicator.length() && extractedName.length() < 100) {
                                productName = extractedName;
                            }
                        }
                    }
                    
                    // Clean up the product name
                    productName = productName.replaceAll("\\s+", " "); // Normalize whitespace
                    
                    // CRITICAL: Validate product name before adding as candidate
                    if (!isValidProductName(productName)) {
                        logger.info("Rejected candidate product name (validation failed): {}", productName);
                        continue;
                    }
                    
                    // Store candidate with its matched indicator for prioritization
                    candidateProductNames.add(new java.util.AbstractMap.SimpleEntry<>(productName, matchedProductIndicator));
                    logger.info("Found candidate product name: {} (matched indicator: {})", productName, matchedProductIndicator);
                }
            }
        }
        
        // Prioritize candidates: prefer specific card names over generic terms
        if (!candidateProductNames.isEmpty()) {
            // Sort by specificity: lines with specific card product names first
            // Order matters - most specific first
            List<String> specificCardNames = Arrays.asList(
                // Amazon/Prime Cards (highest priority - very specific)
                "prime visa", "amazon prime visa", "amazon prime card", "prime rewards visa",
                "prime visa signature", "amazon prime rewards", "prime card",
                // Chase Cards
                "marriott bonvoy premier", "marriott bonvoy", "bonvoy premier", "bonvoy",
                "freedom ultimate", "freedom unlimited", "freedom", "active cash",
                "sapphire reserve", "sapphire preferred", "sapphire",
                "double cash", "cash back", "blue cash",
                // Visa/Mastercard/Amex Tier Cards
                "visa signature", "visa infinite", "visa platinum", "visa classic",
                "mastercard world elite", "mastercard world", "mastercard platinum",
                "amex platinum", "amex gold", "amex green",
                // Capital One Cards
                "quicksilver", "spark", "venture", "savor",
                // Citi Cards
                "double cash", "premier", "diamond preferred",
                // Discover Cards
                "it", "miles", "cash back",
                // Other specific cards
                "unlimited", "everyday", "platinum", "gold", "silver",
                "signature", "world", "elite", "infinite", "ultimate",
                "hilton", "hyatt", "delta", "marriott"
            );
            
            // First pass: prioritize by matched indicator (most specific first)
            for (String cardName : specificCardNames) {
                for (Map.Entry<String, String> entry : candidateProductNames) {
                    String candidate = entry.getKey();
                    String matchedIndicator = entry.getValue();
                    String lowerCandidate = candidate.toLowerCase();
                    String lowerMatchedIndicator = matchedIndicator != null ? matchedIndicator.toLowerCase() : "";
                    
                    // Check if the matched indicator or candidate contains the specific card name
                    if (lowerMatchedIndicator.contains(cardName.toLowerCase()) || 
                        lowerCandidate.contains(cardName.toLowerCase())) {
                        logger.info("Extracted product name (prioritized specific card name '{}'): {}", cardName, candidate);
                        return candidate;
                    }
                }
            }
            
            // Second pass: if no match by indicator, check candidate text only
            for (String cardName : specificCardNames) {
                for (Map.Entry<String, String> entry : candidateProductNames) {
                    String candidate = entry.getKey();
                    String lowerCandidate = candidate.toLowerCase();
                    if (lowerCandidate.contains(cardName.toLowerCase())) {
                        logger.info("Extracted product name (prioritized by candidate text '{}'): {}", cardName, candidate);
                        return candidate;
                    }
                }
            }
            
            // If no specific card name found, validate and return the first valid candidate
            for (Map.Entry<String, String> entry : candidateProductNames) {
                String candidate = entry.getKey();
                if (isValidProductName(candidate)) {
                    logger.info("Extracted product name (first valid candidate): {}", candidate);
                    return candidate;
                } else {
                    logger.info("Skipped invalid candidate: {}", candidate);
                }
            }
            
            // If all candidates were invalid, return null
            // No valid product name candidates found after validation
            return null;
        }
        
        // Pattern 2: Regex pattern for "Institution Product Name Card" format
        // Build regex dynamically from institution keywords
        StringBuilder institutionPattern = new StringBuilder("(?i)(?:");
        for (int i = 0; i < INSTITUTION_KEYWORDS.size(); i++) {
            if (i > 0) {
                institutionPattern.append("|");
            }
            institutionPattern.append(Pattern.quote(INSTITUTION_KEYWORDS.get(i)));
        }
        institutionPattern.append(")\\s+([A-Z][A-Za-z0-9\\s®™©]+?)\\s*(?:card|®|™)");
        
        Pattern pattern = Pattern.compile(institutionPattern.toString(), Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(headerText);
        if (matcher.find()) {
            String productName = matcher.group(0).trim();
            productName = productName.replaceAll("\\s+", " "); // Normalize whitespace
            logger.info("Extracted product name (regex pattern): {}", productName);
            return productName;
        }
        
        return null;
    }
    
    /**
     * Capitalize card name properly (e.g., "prime visa" -> "Prime Visa")
     */
    private String capitalizeCardName(String cardName) {
        if (cardName == null || cardName.isEmpty()) {
            return cardName;
        }
        
        // Handle special cases
        String lower = cardName.toLowerCase();
        if (lower.contains("prime visa")) {
            // Capitalize "Prime Visa" properly
            if (lower.startsWith("amazon")) {
                return "Amazon Prime Visa";
            } else if (lower.contains("rewards")) {
                return "Prime Rewards Visa";
            } else if (lower.contains("signature")) {
                return "Prime Visa Signature";
            } else {
                return "Prime Visa";
            }
        }
        
        // Default: capitalize first letter of each word
        String[] words = cardName.toLowerCase().split("\\s+");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                result.append(" ");
            }
            if (!words[i].isEmpty()) {
                result.append(Character.toUpperCase(words[i].charAt(0)));
                if (words[i].length() > 1) {
                    result.append(words[i].substring(1));
                }
            }
        }
        return result.toString();
    }
    
    /**
     * Check if a string is a valid card product name (contains card product keywords)
     */
    private boolean isValidCardProductName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        
        String lower = name.toLowerCase();
        
        // Must contain at least one card product keyword
        List<String> cardKeywords = Arrays.asList(
            "prime", "visa", "mastercard", "amex", "american express", "discover",
            "platinum", "gold", "silver", "signature", "world", "elite", "infinite",
            "reserve", "preferred", "freedom", "sapphire", "bonvoy", "marriott",
            "hilton", "hyatt", "delta", "venture", "savor", "quicksilver", "spark",
            "cash back", "cashback","rewards", "miles", "points", "card"
        );
        
        for (String keyword : cardKeywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Validate product name candidate
     * Rejects names that:
     * - Are longer than 5 words
     * - Have lowercase words (should be Caps or Camel case)
     * - Match blacklist patterns (contact info, addresses, etc.)
     * - Are too long or too short
     */
    private boolean isValidProductName(String productName) {
        if (productName == null || productName.trim().isEmpty()) {
            return false;
        }
        
        String trimmed = productName.trim();
        
        // Check length (should be reasonable for a product name)
        if (trimmed.length() < 3 || trimmed.length() > 100) {
            // Rejected product name - length out of range
            return false;
        }
        
        // Check word count (should be 5 words or fewer)
        String[] words = trimmed.split("\\s+");
        if (words.length > 7) {
            // Rejected product name - too many words
            return false;
        }
        
        // Check capitalization: should be Caps or Camel case, not all lowercase
        // Allow some lowercase words (like "of", "the", "and" in proper names)
        // But reject if ALL words are lowercase (except very short words like "of", "the")
        boolean hasCapitalizedWord = false;
        List<String> allowedLowercaseWords = Arrays.asList("of", "the", "and", "or", "for", "at", "in", "on", "to", "b");
        
        for (String word : words) {
            // Remove punctuation for checking
            String cleanWord = word.replaceAll("[.,;:®™©]+", "").trim();
            if (cleanWord.isEmpty()) continue;
            
            // Check if word starts with uppercase (Caps or Camel case)
            if (Character.isUpperCase(cleanWord.charAt(0))) {
                hasCapitalizedWord = true;
            } else {
                // Word starts with lowercase
                // Allow if it's a common lowercase word in proper names
                if (!allowedLowercaseWords.contains(cleanWord.toLowerCase())) {
                    // Check if word is all lowercase and longer than 4 characters (should be capitalized)
                    // Exception: allow if it's a known card product name
                    if (cleanWord.equals(cleanWord.toLowerCase()) && cleanWord.length() > 4) {
                        // Check if it's a known card product keyword (these are sometimes lowercase in statements)
                        List<String> lowercaseCardKeywords = Arrays.asList(
                            "platinum", "gold", "silver", "signature", "world", "elite", "infinite",
                            "reserve", "preferred", "freedom", "sapphire", "bonvoy", "marriott",
                            "hilton", "hyatt", "delta", "venture", "savor", "quicksilver", "spark",
                            "unlimited", "everyday", "premier", "classic", "premium", "black", "coral",
                            "diamond", "imperial", "royal", "prestige", "exclusive", "travel",
                            "business", "rewards", "miles", "points", "cash", "double cash", "active cash", "blue cash",
                            "simplicity", "cash back", "latitude", "mastercard"
                        );
                        if (!lowercaseCardKeywords.contains(cleanWord.toLowerCase())) {
                            // Rejected product name - invalid lowercase word
                            return false;
                        }
                    }
                }
            }
        }
        
        // Must have at least one capitalized word (unless it's a single known card keyword)
        if (!hasCapitalizedWord && words.length > 1) {
            // Rejected product name - no capitalized words
            return false;
        }
        
        // Additional blacklist checks for contact information and addresses
        String lowerTrimmed = trimmed.toLowerCase();
        List<String> blacklistPhrases = Arrays.asList(
            "write us", "questions", "if you have", "contact us", "call us",
            "p.o. box", "po box", "post office", "electronic funds",
            "funds services", "you may", "for more", "for additional",
            "please", "thank you", "sincerely", "regards"
        );
        
        for (String blacklistPhrase : blacklistPhrases) {
            if (lowerTrimmed.contains(blacklistPhrase)) {
                // Rejected product name - contains blacklist phrase
                return false;
            }
        }
        
        // Reject if contains address patterns (ZIP codes, state abbreviations with numbers)
        if (trimmed.matches(".*\\b\\d{5}(?:-\\d{4})?\\b.*") || // ZIP code
            trimmed.matches(".*\\b[A-Z]{2}\\s+\\d{5}\\b.*")) { // State + ZIP
            // Rejected product name - contains address pattern
            return false;
        }
        
        // Reject if contains email or phone patterns
        if (trimmed.matches(".*@.*") || // Email
            trimmed.matches(".*\\d{1,3}[\\-.]?\\d{3}[\\-.]?\\d{3}[\\-.]?\\d{4}.*")) { // Phone
            // Rejected product name - contains email or phone pattern
            return false;
        }
        
        return true;
    }
    
    /**
     * Extract account holder/cardholder name from PDF header text
     * Looks for patterns like "Card Member: John Doe", "Name: John Doe", etc.
     * Also handles contextual patterns like name before address, account number, card number, etc.
     * 
     * Strategy: Collect all candidates first, then apply filters and preferences to select the best one.
     */
    private String extractAccountHolderNameFromPDF(String headerText) {
        if (headerText == null || headerText.isEmpty()) {
            return null;
        }
        logger.debug("Extracting account holder name from PDF header text: {}", headerText);
        String[] lines = headerText.split("\\r?\\n");
        List<String> excludedWords = Arrays.asList("sale", "post", "date", "description", "amount", 
            "payments", "credits", "adjustments", "summary", "history");
        
        // Phrases that indicate this is not a name line (should be rejected entirely)
        // Note: This list is for documentation - actual filtering happens in extractAndValidateName
        // List<String> excludedPhrases = Arrays.asList(
        //     "account information", "your name", "account number", "and account number",
        //     "card number", "card information", "statement information"
        // );
        
        // Helper class to store candidate with priority and pattern type
        class NameCandidate {
            final String name;
            int priority; // Higher = better (direct patterns > contextual patterns) - mutable for merging
            String patternType; // For logging - primary pattern type - mutable for merging
            boolean isAllCaps; // For preference - mutable for merging
            boolean isContextual; // 3-line address, etc. - mutable for merging
            int frequency = 1; // Number of times this name appears
            Set<String> patternTypes = new HashSet<>(); // All pattern types this name appears in
            
            NameCandidate(String name, int priority, String patternType, boolean isAllCaps, boolean isContextual) {
                this.name = name;
                this.priority = priority;
                this.patternType = patternType;
                this.isAllCaps = isAllCaps;
                this.isContextual = isContextual;
                this.patternTypes.add(patternType);
            }
            
            // Merge another candidate with the same name
            void merge(NameCandidate other) {
                this.frequency++;
                this.patternTypes.addAll(other.patternTypes);
                // Update priority to highest seen
                if (other.priority > this.priority) {
                    this.priority = other.priority;
                    this.patternType = other.patternType; // Use pattern type from highest priority
                }
                // Update flags if other candidate has better flags
                if (other.isAllCaps && !this.isAllCaps) {
                    this.isAllCaps = true;
                }
                if (other.isContextual && !this.isContextual) {
                    this.isContextual = true;
                }
            }
            
            /**
             * Calculate composite score combining all factors with appropriate weightings
             * Score components:
             * - Priority: 100 points per priority point (primary factor)
             * - Frequency: 50 points per occurrence with log scaling (diminishing returns)
             * - Pattern types: 20 points per unique pattern type (cross-pattern presence)
             * - All-caps: 100 point bonus (statement headers preference)
             * - Contextual: 50 point bonus (3-line address, etc.)
             * - Single word: -5000 point penalty (terrible reduction - single words are less likely to be full names)
             * - All lowercase: -3000 point penalty (terrible reduction - names are typically capitalized)
             * 
             * @return Composite score (higher is better)
             */
            double calculateCompositeScore() {
                // Priority: 90 points per priority point (most important)
                // Priority range: 70-100, so contributes 7000-10000 points
                double priorityScore = priority * 90.0;
                
                // Frequency: 50 points per occurrence with log scaling to prevent overwhelming
                // log1p(x) = ln(1+x), so log1p(1) ≈ 0.69, log1p(3) ≈ 1.39, log1p(10) ≈ 2.40
                // This gives diminishing returns: 1 occurrence = 34.5, 3 = 69.5, 10 = 120 points
                double frequencyScore = Math.log1p(frequency) * 50.0;
                
                // Pattern types: 20 points per unique pattern type
                // More pattern types = more confidence (appears in multiple contexts)
                double patternTypesScore = patternTypes.size() * 20.0;
                
                // All-caps bonus: 100 points (statement headers are often all-caps)
                double allCapsBonus = isAllCaps ? 100.0 : 0.0;
                
                // Contextual bonus: 50 points (3-line address, etc. are strong indicators)
                double contextualBonus = isContextual ? 50.0 : 0.0;
                
                // Single word penalty: -5000 points (terrible reduction - single words are less likely to be full names)
                // Check if name has only one word
                String[] words = name.trim().split("\\s+");
                double singleWordPenalty = (words.length == 1) ? -5000.0 : 0.0;
                if (singleWordPenalty < 0) {
                    logger.debug("Applying single word penalty (-5000) to candidate '{}'", name);
                }
                
                // All lowercase penalty: -3000 points (terrible reduction - names are typically capitalized)
                // Check if name is all lowercase (but not empty and has letters)
                boolean isAllLowercase = name.equals(name.toLowerCase()) && name.matches(".*[a-z].*") && !name.matches(".*[A-Z].*");
                double allLowercasePenalty = isAllLowercase ? -3000.0 : 0.0;
                if (allLowercasePenalty < 0) {
                    logger.debug("Applying all lowercase penalty (-3000) to candidate '{}'", name);
                }
                
                double totalScore = priorityScore + frequencyScore + patternTypesScore + allCapsBonus + contextualBonus + singleWordPenalty + allLowercasePenalty;
                
                return totalScore;
            }
        }
        
        // Use Map to track candidates by name (normalized) to merge duplicates
        Map<String, NameCandidate> candidateMap = new HashMap<>();
        
        // Patterns to match account holder name directly
        Pattern[] directPatterns = {
            // Pattern 1: "Card Member: John Doe" or "Cardmember: John Doe"
            Pattern.compile("(?i)card\\s*member\\s*:?\\s*(.+)"),
            // Pattern 2: "Name: John Doe" or "User: John Doe" or "Cardholder:" or "Holder:"
            Pattern.compile("(?i)(?:name|user|cardholder|holder)\\s*:?\\s*(.+)"),
            // Pattern 3: "Account Holder: John Doe"
            Pattern.compile("(?i)account\\s*holder\\s*:?\\s*(.+)"),
            // Pattern 4: "Primary Account Holder: John Doe"
            Pattern.compile("(?i)primary\\s+account\\s+holder\\s*:?\\s*(.+)"),
            // Pattern 5: "Primary Cardholder: John Doe"
            Pattern.compile("(?i)primary\\s+cardholder\\s*:?\\s*(.+)"),
            // Pattern 6: "Account Owner: John Doe" (for investment accounts)
            Pattern.compile("(?i)account\\s+owner\\s*:?\\s*(.+)"),
            // Pattern 7: "Beneficial Owner: John Doe" (for trust/beneficiary accounts)
            Pattern.compile("(?i)beneficial\\s+owner\\s*:?\\s*(.+)"),
            // Pattern 8: "Beneficiary: John Doe"
            Pattern.compile("(?i)beneficiary\\s*:?\\s*(.+)")
        };
        
        // STEP 1: Collect all candidates from direct patterns (priority 100)
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            
            // Skip lines that are clearly not name lines
            String lowerLine = trimmed.toLowerCase();
            if (lowerLine.contains("statement period") ||
                lowerLine.contains("account summary") ||
                lowerLine.contains("transaction summary") ||
                lowerLine.contains("payment history") ||
                lowerLine.contains("transaction history") ||
                lowerLine.contains("account information") ||
                lowerLine.contains("your name") ||
                lowerLine.contains("and account number") ||
                lowerLine.contains("If you have") ||
                lowerLine.contains("balance") ||
                lowerLine.contains("card member agreement") ||
                lowerLine.contains("card member information") ||
                lowerLine.contains("card member benefits") ||
                lowerLine.contains("card member services") ||
                lowerLine.contains("card member service") ||
                lowerLine.contains("cardmember service") ||
                lowerLine.contains("cardmember agreement") ||
                lowerLine.contains("cardmember information") ||
                lowerLine.contains("cardmember benefits") ||
                lowerLine.contains("cardmember services") ||
                lowerLine.contains("cardmember support") ||
                lowerLine.contains("cardmember rewards") ||
                lowerLine.contains("account holder agreement") ||
                lowerLine.contains("account holder information") ||
                lowerLine.contains("account holder benefits") ||
                lowerLine.contains("account holder services") ||
                lowerLine.contains("account holder service") ||
                lowerLine.contains("account holder support") ||
                lowerLine.contains("account holder rewards") ||
                lowerLine.contains("passenger name") ||
                lowerLine.contains("account name") ||
                lowerLine.contains("person name") ||
                lowerLine.contains("card name") ||
                lowerLine.contains("minimum payment") ||
                lowerLine.contains("alternate payment") ||
                lowerLine.matches(".*\\bdate\\s+description\\s+amount.*") || // Column headers
                lowerLine.matches(".*\\btransaction\\s+date.*") || // Transaction table headers
                (lowerLine.contains("date") && lowerLine.contains("amount"))) {
                continue;
            }
            
            // Try each direct pattern
            for (int i = 0; i < directPatterns.length; i++) {
                Pattern pattern = directPatterns[i];
                Matcher matcher = pattern.matcher(trimmed);
                if (matcher.find()) {
                    // Extract name and clean it (remove newlines, extra spaces, etc.)
                    String rawName = matcher.group(1).trim();
                    // Remove any newlines or carriage returns that might be in the captured text
                    rawName = rawName.replaceAll("[\\r\\n]+", " ").trim();
                    // Also stop if we see common context markers in the captured text
                    if (rawName.contains("Account Number") || rawName.contains("Card Number") || 
                        rawName.contains("Account Ending") || rawName.contains("Card Ending")) {
                        // Extract only the part before the context marker
                        String[] parts = rawName.split("(?:Account|Card)\\s+(?:Number|Ending)", 2);
                        rawName = parts[0].trim();
                    }
                    // CRITICAL: Remove any remaining pattern prefixes (e.g., "Name:" if it wasn't properly excluded)
                    rawName = rawName.replaceFirst("^(?:name|user|cardholder|holder|card\\s*member)\\s*:?\\s*", "").trim();
                    logger.debug("Pattern {} matched, raw name: '{}'", i + 1, rawName);
                    String name = extractAndValidateName(rawName, excludedWords);
                    if (name != null) {
                        boolean isAllCaps = name.equals(name.toUpperCase()) && name.matches(".*[A-Z].*");
                        String patternType = "direct_pattern_" + (i + 1);
                        // Normalize name for map key (trim and normalize case for consistent merging)
                        // Use lowercase for key to handle case variations (e.g., "John Doe" vs "JOHN DOE")
                        String normalizedName = name.trim().toLowerCase();
                        // Merge with existing candidate if same name found
                        NameCandidate existing = candidateMap.get(normalizedName);
                        if (existing != null) {
                            existing.merge(new NameCandidate(name, 100, patternType, isAllCaps, false));
                            // Merged account holder name candidate
                        } else {
                            NameCandidate candidate = new NameCandidate(name, 100, patternType, isAllCaps, false);
                            candidateMap.put(normalizedName, candidate);
                        }
                    }
                }
            }
        }
        
        // STEP 2: Collect candidates from contextual patterns (name in previous line before certain keywords)
        // Handle empty lines by looking backward for the previous non-empty line
        for (int i = 1; i < lines.length; i++) {
            String currentLine = lines[i].trim();
            if (currentLine.isEmpty()) continue; // Skip empty current line
            
            // Find previous non-empty line
            String previousLine = null;
            for (int j = i - 1; j >= 0; j--) {
                String prevCandidate = lines[j].trim();
                if (!prevCandidate.isEmpty()) {
                    previousLine = prevCandidate;
                    break;
                }
            }
            
            if (previousLine == null || previousLine.isEmpty()) continue;
            
            String currentLineLower = currentLine.toLowerCase();
            
            // Pattern: Name in previous line, followed by Address
            // Handle both 2-line (name, address) and 3-line (name, street, city state ZIP) formats
            boolean isAddressLine = false;
            boolean isThreeLineAddress = false;
            
            // Check if current line looks like an address (street address with number, or contains address keywords)
            if (currentLineLower.matches(".*\\baddress\\b.*") || 
                currentLineLower.matches(".*\\bstreet\\b.*") ||
                currentLineLower.matches(".*\\bcity\\b.*") ||
                currentLineLower.matches(".*\\bstate\\b.*") ||
                currentLineLower.matches(".*\\bzip\\b.*") ||
                currentLineLower.matches(".*\\bpo\\s+box\\b.*") ||
                currentLineLower.matches(".*\\bp\\.o\\.\\s+box\\b.*") ||
                currentLineLower.matches(".*\\bapt\\.?\\b.*") ||
                currentLineLower.matches(".*\\bapartment\\b.*") ||
                currentLineLower.matches(".*\\b\\d{5}(?:-\\d{4})?\\b.*") || // ZIP code with optional +4
                currentLineLower.matches("^\\d+\\s+.*")) { // Line starting with number (street address)
                isAddressLine = true;
            }
            
            // Also check if next line (if exists) contains city/state/ZIP pattern
            // This handles 3-line format: name, street, city state ZIP
            // Example: "ASHTON BASHTON HASHTON\n73529 NE 43ST ST\nSEATTLE WA 98119-3579"
            if (i + 1 < lines.length) {
                String nextLine = lines[i + 1].trim();
                String nextLineLower = nextLine.toLowerCase();
                if (!nextLine.isEmpty()) {
                    // Check if next line has ZIP code pattern (confirms it's a 3-line address)
                    if (nextLineLower.matches(".*\\b\\d{5}(?:-\\d{4})?\\b.*") || // ZIP code with optional +4 (like "98119-3579")
                        nextLineLower.matches(".*\\b\\d{5}\\s+\\d{4}\\b.*")) { // ZIP+4 separated by space (like "98119 3579")
                        // Current line should be a street address (starting with number or containing street keywords)
                        if (currentLineLower.matches("^\\d+\\s+.*") || 
                            currentLineLower.matches(".*\\bstreet\\b.*") ||
                            currentLineLower.matches(".*\\bavenue\\b.*") ||
                            currentLineLower.matches(".*\\broad\\b.*")) {
                            isAddressLine = true;
                            isThreeLineAddress = true; // Mark as 3-line format (higher preference)
                        }
                    }
                }
            }
            
            if (isAddressLine) {
                String name = extractAndValidateName(previousLine, excludedWords);
                if (name != null) {
                    boolean isAllCaps = name.equals(name.toUpperCase()) && name.matches(".*[A-Z].*");
                    int priority = isThreeLineAddress ? 90 : 80; // Higher priority for 3-line address
                    String patternType = isThreeLineAddress ? "3_line_address" : "address";
                    // Normalize name for map key (trim and lowercase for consistent merging)
                    String normalizedName = name.trim().toLowerCase();
                    NameCandidate existing = candidateMap.get(normalizedName);
                    if (existing != null) {
                        existing.merge(new NameCandidate(name, priority, patternType, isAllCaps, true));
                        // Merged account holder name candidate
                    } else {
                        candidateMap.put(normalizedName, new NameCandidate(name, priority, patternType, isAllCaps, true));
                    }
                }
            }
            
            // Pattern: Name in previous line, followed by "Member since" or "Customer since"
            if (currentLineLower.matches(".*\\bmember\\s+since\\b.*") ||
                currentLineLower.matches(".*\\bcustomer\\s+since\\b.*")) {
                String name = extractAndValidateName(previousLine, excludedWords);
                if (name != null) {
                    boolean isAllCaps = name.equals(name.toUpperCase()) && name.matches(".*[A-Z].*");
                    // Normalize name for map key (trim and lowercase for consistent merging)
                    String normalizedName = name.trim().toLowerCase();
                    NameCandidate existing = candidateMap.get(normalizedName);
                    if (existing != null) {
                        existing.merge(new NameCandidate(name, 85, "member_since", isAllCaps, true));
                        // Merged account holder name candidate
                    } else {
                        candidateMap.put(normalizedName, new NameCandidate(name, 85, "member_since", isAllCaps, true));
                    }
                }
            }
            
            // Pattern: Name in previous line, followed by Account number
            if (currentLineLower.matches(".*\\baccount\\s+(?:number|ending|#|ending\\s+in)\\b.*") ||
                currentLineLower.matches(".*\\baccount\\s+ending\\b.*") ||
                currentLineLower.matches(".*\\baccount\\s+\\*{0,4}\\d{4,}.*") ||
                currentLineLower.matches(".*\\bclosing\\s+date.*\\baccount\\s+ending\\b.*") ||
                currentLineLower.matches(".*\\bclosing\\s+date.*")) {
                String name = extractAndValidateName(previousLine, excludedWords);
                if (name != null) {
                    boolean isAllCaps = name.equals(name.toUpperCase()) && name.matches(".*[A-Z].*");
                    // Normalize name for map key (trim and lowercase for consistent merging)
                    String normalizedName = name.trim().toLowerCase();
                    NameCandidate existing = candidateMap.get(normalizedName);
                    if (existing != null) {
                        existing.merge(new NameCandidate(name, 75, "account_number", isAllCaps, true));
                        // Merged account holder name candidate
                    } else {
                        candidateMap.put(normalizedName, new NameCandidate(name, 75, "account_number", isAllCaps, true));
                    }
                }
            }
            
            // Pattern: Name followed by Card number or Card ending in
            if (currentLineLower.matches(".*\\bcard\\s+(?:number|ending|#|ending\\s+in)\\b.*") ||
                currentLineLower.matches(".*\\bcard\\s+ending\\b.*") ||
                currentLineLower.matches(".*\\bcard\\s+\\*{0,4}\\d{4,}.*") ||
                currentLineLower.matches(".*\\b\\d{4}\\s+\\d{4}\\s+\\d{4}\\s+\\d{4}.*")) {
                String name = extractAndValidateName(previousLine, excludedWords);
                if (name != null) {
                    boolean isAllCaps = name.equals(name.toUpperCase()) && name.matches(".*[A-Z].*");
                    // Normalize name for map key (trim and lowercase for consistent merging)
                    String normalizedName = name.trim().toLowerCase();
                    NameCandidate existing = candidateMap.get(normalizedName);
                    if (existing != null) {
                        existing.merge(new NameCandidate(name, 75, "card_number", isAllCaps, true));
                        // Merged account holder name candidate
                    } else {
                        candidateMap.put(normalizedName, new NameCandidate(name, 75, "card_number", isAllCaps, true));
                    }
                }
            }
            
            // Pattern: Name on same line followed by Account Number or Account Ending in
            Pattern nameBeforeAccountPattern = Pattern.compile("^(.+?)\\s+(?:account\\s+(?:number|ending|#|ending\\s+in)|account\\s+ending|account\\s+\\*{0,4}\\d{4,}|\\d{1,9}\\s*-\\s*\\d{4,6})", 
                Pattern.CASE_INSENSITIVE);
            Matcher nameMatcher = nameBeforeAccountPattern.matcher(currentLine);
            if (nameMatcher.find()) {
                String name = extractAndValidateName(nameMatcher.group(1).trim(), excludedWords);
                if (name != null) {
                    boolean isAllCaps = name.equals(name.toUpperCase()) && name.matches(".*[A-Z].*");
                    // Normalize name for map key (trim and lowercase for consistent merging)
                    String normalizedName = name.trim().toLowerCase();
                    NameCandidate existing = candidateMap.get(normalizedName);
                    if (existing != null) {
                        existing.merge(new NameCandidate(name, 70, "same_line_account", isAllCaps, false));
                        // Merged account holder name candidate
                    } else {
                        candidateMap.put(normalizedName, new NameCandidate(name, 70, "same_line_account", isAllCaps, false));
                    }
                }
            }
            
            // Pattern: Name on same line followed by Card number or Card ending in
            Pattern nameBeforeCardPattern = Pattern.compile("^(.+?)\\s+(?:card\\s+(?:number|ending|#|ending\\s+in)|card\\s+ending|card\\s+\\*{0,4}\\d{4,}|\\d{4}\\s+\\d{4}\\s+\\d{4}\\s+\\d{4})", 
                Pattern.CASE_INSENSITIVE);
            nameMatcher = nameBeforeCardPattern.matcher(currentLine);
            if (nameMatcher.find()) {
                String name = extractAndValidateName(nameMatcher.group(1).trim(), excludedWords);
                if (name != null) {
                    boolean isAllCaps = name.equals(name.toUpperCase()) && name.matches(".*[A-Z].*");
                    // Normalize name for map key (trim and lowercase for consistent merging)
                    String normalizedName = name.trim().toLowerCase();
                    NameCandidate existing = candidateMap.get(normalizedName);
                    if (existing != null) {
                        existing.merge(new NameCandidate(name, 70, "same_line_card", isAllCaps, false));
                        // Merged account holder name candidate
                    } else {
                        candidateMap.put(normalizedName, new NameCandidate(name, 70, "same_line_card", isAllCaps, false));
                    }
                }
            }
        }
        
        // STEP 2B: Also collect same-line patterns for all lines (handles single-line cases)
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            
            // Pattern: Name on same line followed by Account Number or Account Ending in
            Pattern nameBeforeAccountPattern = Pattern.compile("^(.+?)\\s+(?:account\\s+(?:number|ending|#|ending\\s+in)|account\\s+ending|account\\s+\\*{0,4}\\d{4,}|\\d{1,9}\\s*-\\s*\\d{4,6})", 
                Pattern.CASE_INSENSITIVE);
            Matcher nameMatcher = nameBeforeAccountPattern.matcher(trimmed);
            if (nameMatcher.find()) {
                String name = extractAndValidateName(nameMatcher.group(1).trim(), excludedWords);
                if (name != null) {
                    boolean isAllCaps = name.equals(name.toUpperCase()) && name.matches(".*[A-Z].*");
                    // Normalize name for map key (trim and lowercase for consistent merging)
                    String normalizedName = name.trim().toLowerCase();
                    NameCandidate existing = candidateMap.get(normalizedName);
                    if (existing != null) {
                        existing.merge(new NameCandidate(name, 70, "same_line_account_all", isAllCaps, false));
                        // Merged account holder name candidate
                    } else {
                        candidateMap.put(normalizedName, new NameCandidate(name, 70, "same_line_account_all", isAllCaps, false));
                    }
                }
            }
            
            // Pattern: Name on same line followed by Card number or Card ending in
            Pattern nameBeforeCardPattern = Pattern.compile("^(.+?)\\s+(?:card\\s+(?:number|ending|#|ending\\s+in)|card\\s+ending|card\\s+\\*{0,4}\\d{4,}|\\d{4}\\s+\\d{4}\\s+\\d{4}\\s+\\d{4})", 
                Pattern.CASE_INSENSITIVE);
            nameMatcher = nameBeforeCardPattern.matcher(trimmed);
            if (nameMatcher.find()) {
                String name = extractAndValidateName(nameMatcher.group(1).trim(), excludedWords);
                if (name != null) {
                    boolean isAllCaps = name.equals(name.toUpperCase()) && name.matches(".*[A-Z].*");
                    // Normalize name for map key (trim and lowercase for consistent merging)
                    String normalizedName = name.trim().toLowerCase();
                    NameCandidate existing = candidateMap.get(normalizedName);
                    if (existing != null) {
                        existing.merge(new NameCandidate(name, 70, "same_line_card_all", isAllCaps, false));
                        logger.debug("Merged account holder name candidate (same line card - all lines): {} (frequency: {}, patterns: {})", 
                            name, existing.frequency, existing.patternTypes);
                    } else {
                        candidateMap.put(normalizedName, new NameCandidate(name, 70, "same_line_card_all", isAllCaps, false));
                        logger.debug("Collected account holder name candidate (same line card - all lines): {}", name);
                    }
                }
            }
        }
        
        // STEP 3: Filter candidates - reject bank names, US state abbreviations, and invalid candidates
        // US state abbreviations (50 states + DC)
        List<String> usStateAbbreviations = Arrays.asList(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
            "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
            "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
            "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
            "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "DC"
        );
        
        List<NameCandidate> validCandidates = new ArrayList<>();
        logger.debug("Filtering {} candidates", candidateMap.size());
        for (NameCandidate candidate : candidateMap.values()) {
            String lowerName = candidate.name.toLowerCase();
            
            // Reject bank/institution names
            // CRITICAL: Use word boundaries to avoid false positives (e.g., "O'Brien" contains "bri" but is not a bank name)
            boolean isBankName = false;
            for (String bankName : INSTITUTION_KEYWORDS) {
                // Use word boundaries to match whole words only
                // This prevents "O'Brien" from matching "bri" or "BRI"
                Pattern bankPattern = Pattern.compile("\\b" + Pattern.quote(bankName) + "\\b", Pattern.CASE_INSENSITIVE);
                if (bankPattern.matcher(lowerName).find()) {
                    isBankName = true;
                    logger.debug("Rejected account holder name candidate '{}' - contains bank/institution name '{}'", candidate.name, bankName);
                    break;
                }
            }
            if (isBankName) {
                continue;
            }
            
            // Reject names containing US state abbreviations (as whole words) - indicates address, not name
            // Check for 2-letter state abbreviations as whole words
            String[] words = candidate.name.split("\\s+");
            boolean containsStateAbbreviation = false;
            for (String word : words) {
                // Remove punctuation for comparison (e.g., "WA," -> "WA")
                String cleanWord = word.replaceAll("[.,;:]+$", "").trim().toUpperCase();
                if (usStateAbbreviations.contains(cleanWord)) {
                    containsStateAbbreviation = true;
                    logger.debug("Rejected account holder name candidate '{}' - contains US state abbreviation '{}' (likely address, not name)", candidate.name, cleanWord);
                    break;
                }
            }
            if (containsStateAbbreviation) {
                continue;
            }
            
            logger.debug("Accepted candidate: '{}'", candidate.name);
            validCandidates.add(candidate);
        }
        
        if (validCandidates.isEmpty()) {
            logger.info("No valid account holder name candidates found after filtering");
            return null;
        }
        
        // STEP 4: Apply composite scoring and select the best candidate
        // Composite score combines all factors with appropriate weightings:
        // - Priority: 100 points per priority point (primary factor, range 7000-10000)
        // - Frequency: 50 points per occurrence with log scaling (diminishing returns)
        // - Pattern types: 20 points per unique pattern type (cross-pattern presence)
        // - All-caps: 100 point bonus (statement headers preference)
        // - Contextual: 50 point bonus (3-line address, etc.)
        // 
        // This allows a high-frequency candidate to potentially beat a low-frequency candidate
        // with slightly higher priority, while still strongly favoring high-priority patterns.

        // Calculate composite scores for all candidates
        for (NameCandidate candidate : validCandidates) {
            double score = candidate.calculateCompositeScore();
            logger.debug("Candidate '{}' composite score: {:.2f} (priority: {}={:.0f}, frequency: {}={:.2f}, patterns: {}={:.0f}, all-caps: {}, contextual: {})", 
                candidate.name, score,
                candidate.priority, candidate.priority * 100.0,
                candidate.frequency, Math.log1p(candidate.frequency) * 50.0,
                candidate.patternTypes.size(), candidate.patternTypes.size() * 20.0,
                candidate.isAllCaps ? 100.0 : 0.0,
                candidate.isContextual ? 50.0 : 0.0);
        }

        // Sort by composite score (descending - higher score is better)
        validCandidates.sort((a, b) -> {
            double scoreA = a.calculateCompositeScore();
            double scoreB = b.calculateCompositeScore();
            return Double.compare(scoreB, scoreA); // Descending order
        });

        for (NameCandidate candidate : validCandidates) {
            double score = candidate.calculateCompositeScore();
            logger.debug("Candidate: '{}' (score: {:.2f}, priority: {}, frequency: {}, pattern_types: {}, all-caps: {}, contextual: {})", 
                candidate.name, String.format("%.2f", score), candidate.priority, candidate.frequency, candidate.patternTypes, candidate.isAllCaps, candidate.isContextual);
        }
        
        NameCandidate bestCandidate = validCandidates.get(0);
        double bestScore = bestCandidate.calculateCompositeScore();
        logger.debug("Selected account holder name '{}' from {} candidates (score: {:.2f}, pattern: {}, priority: {}, frequency: {}, pattern_types: {}, all-caps: {}, contextual: {})", 
            bestCandidate.name, validCandidates.size(), String.format("%.2f", bestScore), bestCandidate.patternType, bestCandidate.priority, 
            bestCandidate.frequency, bestCandidate.patternTypes, bestCandidate.isAllCaps, bestCandidate.isContextual);
        
        return bestCandidate.name;
    }
    
    /**
     * Extract and validate name from a candidate string
     * Filters out names containing excluded words and validates format
     */
    private String extractAndValidateName(String candidate, List<String> excludedWords) {
        if (candidate == null || candidate.trim().isEmpty()) {
            logger.debug("extractAndValidateName: Candidate is null or empty");
            return null;
        }
        
        // Clean up the name (remove extra spaces, trailing punctuation except periods in suffixes like "Jr.")
        // Only remove trailing punctuation if it's not part of a valid suffix (Jr., Sr., II, III, etc.)
        // CRITICAL: Stop at newlines or other context markers (like "Account Number")
        String name = candidate.split("[\\r\\n]")[0].trim(); // Take only the first line
        // Also stop if we see common context markers
        if (name.contains("Account Number") || name.contains("Card Number") || 
            name.contains("Account Ending") || name.contains("Card Ending")) {
            // Extract only the part before the context marker
            String[] parts = name.split("(?:Account|Card)\\s+(?:Number|Ending)", 2);
            name = parts[0].trim();
        }
        name = name.replaceAll("\\s+", " ").trim();
        
        // Reject phrases that are clearly not names (like "and account number", "your name", etc.)
        // Check this BEFORE cleaning up trailing punctuation
        String lowerName = name.toLowerCase();
        if (lowerName.contains("and account number") ||
            lowerName.contains("your name and") ||
            lowerName.contains("account information") ||
            lowerName.contains("autopay") ||
            lowerName.contains("interest") ||
            lowerName.contains("P.O.") ||
            lowerName.matches(".*\\baccount\\s+number\\b.*") ||
            lowerName.matches(".*\\bcard\\s+number\\b.*") ||
            lowerName.startsWith("your name") ||
            lowerName.equals("and account number") ||
            lowerName.equals("account number") ||
            lowerName.contains("send general inquiries") ||
            lowerName.contains("general inquiries") ||
            (lowerName.startsWith("send ") && lowerName.contains("inquir"))) {
            logger.debug("Rejected account holder name candidate '{}' - contains excluded phrase", name);
            return null;
        }
        
        // Reject agreement-related phrases that are clearly not names
        // These are informational header phrases, not actual account holder names
        if (lowerName.contains("agreement for details") ||
            lowerName.contains("cardmember agreement") ||
            lowerName.contains("cardholder agreement") ||
            lowerName.contains("cardmember service") ||
            lowerName.contains("account holder service") ||
            lowerName.equals("agreement") ||
            lowerName.equals("details") ||
            lowerName.equals("continued") || // Reject standalone "continued" (common in statement footers)
            (lowerName.contains("agreement") && lowerName.contains("details"))) {
            logger.debug("Rejected account holder name candidate '{}' - contains agreement-related phrase or 'continued'", name);
            return null;
        }
        
        // Only remove trailing punctuation if it's not a suffix pattern
        if (!name.matches(".*\\s+(?:Jr|Sr|II|III|IV|V|VI|VII|VIII|IX|X)\\.?$")) {
            name = name.replaceAll("[.,;:]+$", "").trim();
        }
        
        // Check length
        if (name.length() < 2 || name.length() > 100) {
            logger.debug("extractAndValidateName: Rejected '{}' - length {} is out of range (2-100)", name, name.length());
            return null;
        }
        
        // Must contain at least one letter
        if (!name.matches(".*[a-zA-Z].*")) {
            logger.debug("extractAndValidateName: Rejected '{}' - does not contain letters", name);
            return null;
        }
        
        // Must not start with non-letter (except for titles like "Dr.", "Mr.", etc.)
        if (!name.matches("^[A-Za-z].*") && !name.matches("^[A-Za-z]{1,3}\\.\\s+.*")) {
            logger.debug("extractAndValidateName: Rejected '{}' - does not start with letter", name);
            return null;
        }
        
        // Check for excluded words (case-insensitive) - reuse lowerName from earlier
        for (String excludedWord : excludedWords) {
            // Check if name contains the excluded word as a whole word (not part of another word)
            // Use word boundaries to avoid false positives
            if (lowerName.matches(".*\\b" + Pattern.quote(excludedWord) + "\\b.*")) {
                logger.debug("Rejected account holder name candidate '{}' - contains excluded word '{}'", name, excludedWord);
                return null;
            }
        }
        
        // Additional validation: should not contain only numbers, dates, or amounts
        // Check for date patterns
        if (name.matches(".*\\d{1,2}[/-]\\d{1,2}(?:[/-]\\d{2,4})?.*")) {
            return null;
        }
        
        // Check for phone number patterns
        if (name.matches(".*\\d{1,3}[\\-.]?\\d{3}[\\-.]?\\d{3}[\\-.]?\\d{4}.*") ||
            name.matches(".*\\(\\s*\\d{1,3}\\s*[\\-)]?\\s*\\d{3}.*")) {
            return null;
        }
        
        // Should not end with unexpected special characters (except common name punctuation)
        // Allow apostrophes in names (e.g., "O'Brien", "D'Angelo")
        if (name.matches(".*[^a-zA-Z\\s\\-.,']$")) {
            return null;
        }
        
        // CRITICAL: Allow apostrophes in names - they are valid in many names (O'Brien, D'Angelo, etc.)
        // The regex pattern already allows apostrophes, but we need to ensure they're not filtered out
        // Apostrophes are already allowed in the character class [^a-zA-Z\\s\\-.,'] above
        
        // Additional validation checks for account holder names
        
        // 1. Reject names with more than 6 words
        String[] words = name.trim().split("\\s+");
        if (words.length > 6) {
            logger.debug("Rejected account holder name candidate '{}' - has more than 6 words ({})", name, words.length);
            return null;
        }
        
        // 2. Reject names with any full lowercase words of length greater than 4
        // This catches cases like "john doe smith" where words should be capitalized
        for (String word : words) {
            // Remove punctuation for length check (e.g., "Jr." should be allowed)
            String wordWithoutPunct = word.replaceAll("[.,;:']", "");
            if (wordWithoutPunct.length() > 4 && word.equals(word.toLowerCase()) && word.matches(".*[a-z].*")) {
                logger.debug("Rejected account holder name candidate '{}' - contains lowercase word '{}' of length > 4", name, word);
                return null;
            }
        }
        
        // 3. Reject names containing forward slash (/) or backslash (\)
        if (name.contains("/") || name.contains("\\")) {
            logger.debug("Rejected account holder name candidate '{}' - contains '/' or '\\'", name);
            return null;
        }
        
        // 4. Reject names containing "www." (website URLs)
        if (lowerName.contains("www.")) {
            logger.debug("Rejected account holder name candidate '{}' - contains 'www.'", name);
            return null;
        }
        
        return name;
    }
    
    /**
     * Extract balance from headers/text based on account type
     * Delegates to BalanceExtractor for comprehensive global support
     * 
     * @param headers List of header strings
     * @param accountType Account type (creditCard, checking, savings, moneyMarket, etc.)
     * @return Detected balance or null if not found
     */
    public java.math.BigDecimal extractBalanceFromHeaders(List<String> headers, String accountType) {
        return balanceExtractor.extractBalanceFromHeaders(headers, accountType);
    }
    
    /**
     * Extract balance from last transaction's balance column
     * Delegates to BalanceExtractor for comprehensive global support
     * 
     * @param balanceValue Balance value from last transaction row
     * @return Parsed balance or null if invalid
     */
    public java.math.BigDecimal extractBalanceFromTransactionValue(String balanceValue) {
        return balanceExtractor.extractBalanceFromTransactionValue(balanceValue);
    }
    
    /**
     * Update account balance with date comparison logic
     * Only updates balance if the new balance date is newer than the existing balance date
     * 
     * @param account The account to update
     * @param newBalance The new balance value
     * @param newBalanceDate The date of the transaction from which the new balance was extracted
     * @return true if balance was updated, false if not updated (due to date comparison)
     */
    public boolean updateAccountBalanceWithDateComparison(
            com.budgetbuddy.model.dynamodb.AccountTable account,
            java.math.BigDecimal newBalance,
            java.time.LocalDate newBalanceDate) {
        if (account == null || newBalance == null || newBalanceDate == null) {
            logger.debug("Cannot update balance: account, newBalance, or newBalanceDate is null");
            return false;
        }
        
        java.time.LocalDate existingBalanceDate = account.getBalanceDate();
        
        // If no existing balance date, update the balance
        if (existingBalanceDate == null) {
            account.setBalance(newBalance);
            account.setBalanceDate(newBalanceDate);
            logger.debug("Updated account balance (no existing balance date): accountId={}, balance={}, balanceDate={}", 
                account.getAccountId(), newBalance, newBalanceDate);
            return true;
        }
        
        // Only update if new balance date is newer (after) existing balance date
        if (newBalanceDate.isAfter(existingBalanceDate)) {
            account.setBalance(newBalance);
            account.setBalanceDate(newBalanceDate);
            logger.debug("Updated account balance (new date is newer): accountId={}, balance={}, balanceDate={} (previous: {})", 
                account.getAccountId(), newBalance, newBalanceDate, existingBalanceDate);
            return true;
        } else {
            logger.debug("Skipped account balance update (new date is not newer): accountId={}, newBalanceDate={}, existingBalanceDate={}", 
                account.getAccountId(), newBalanceDate, existingBalanceDate);
            return false;
        }
    }
    
    // Getter methods for keyword lists (used by import services)
    public List<String> getAccountNumberKeywords() {
        return new ArrayList<>(ACCOUNT_NUMBER_KEYWORDS);
    }
    
    public List<String> getInstitutionKeywords() {
        List<String> allKeywords = new ArrayList<>();
        allKeywords.addAll(INSTITUTION_KEYWORDS_HEADERS);
        allKeywords.addAll(PRODUCT_NAME_KEYWORDS);
        return allKeywords;
    }
    
    /**
     * Get institution keywords for filtering (prevents false positives in name detection)
     * Returns the main INSTITUTION_KEYWORDS list used for bank/institution name filtering
     */
    public List<String> getInstitutionKeywordsForFiltering() {
        return new ArrayList<>(INSTITUTION_KEYWORDS);
    }
    
    public List<String> getAccountTypeKeywords() {
        return new ArrayList<>(ACCOUNT_TYPE_KEYWORDS);
    }
}

