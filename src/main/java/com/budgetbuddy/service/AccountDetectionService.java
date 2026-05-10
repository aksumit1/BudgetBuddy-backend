package com.budgetbuddy.service;


import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Locale;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for detecting account information from imported files Detects account numbers,
 * institution names, and account types from: - Filenames (e.g., "chase_checking_1234.csv") - PDF
 * headers (account numbers, card numbers) - CSV/Excel headers (account number, account name
 * columns)
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException", "PMD.DataClass", "PMD.OnlyOneReturn"})
@Service
public class AccountDetectionService {

    private static final String A_Z = ".*[A-Z].*";

    private static final String B_D_5_D_4_B = ".*\\b\\d{5}(?:-\\d{4})?\\b.*";

    private static final String CAPITAL_ONE = "Capital One";

    private static final String CITIBANK = "Citibank";

    private static final String ACCOUNT = "account";

    private static final String ACCOUNT_NAME = "account name";

    private static final String ACTIVE_CASH = "active cash";

    private static final String AMAZON_PRIME_CARD = "amazon prime card";

    private static final String AMAZON_PRIME_REWARDS = "amazon prime rewards";

    private static final String AMAZON_PRIME_VISA = "amazon prime visa";

    private static final String AMERICAN_EXPRESS = "american express";

    private static final String AMEX_GOLD = "amex gold";

    private static final String AMEX_PLATINUM = "amex platinum";

    private static final String AMOUNT = "amount";

    private static final String AND_ACCOUNT_NUMBER = "and account number";

    private static final String BALANCE = "balance";

    private static final String BLACK = "black";

    private static final String BLUE_CASH = "blue cash";

    private static final String BONVOY = "bonvoy";

    private static final String BUSINESS = "business";

    private static final String CARDMEMBER_AGREEMENT = "cardmember agreement";

    private static final String CASH_BACK = "cash back";

    private static final String CHECK = "check";

    private static final String CHECKING = "checking";

    private static final String CLASSIC = "classic";

    private static final String CONTACT_US = "contact us";

    private static final String CREDIT = "credit";

    private static final String CREDIT_CARD = "credit card";

    private static final String DEBIT = "debit";

    private static final String DELTA = "delta";

    private static final String DEPOSITORY = "depository";

    private static final String DESCRIPTION = "description";

    private static final String DETAILS = "details";

    private static final String DIAMOND = "diamond";

    private static final String DISCOVER = "discover";

    private static final String DOUBLE_CASH = "double cash";

    private static final String ELITE = "elite";

    private static final String EVERYDAY = "everyday";

    private static final String EXCLUSIVE = "exclusive";

    private static final String FIDELITY = "fidelity";

    private static final String FREEDOM = "freedom";

    private static final String FREEDOM_ULTIMATE = "freedom ultimate";

    private static final String FREEDOM_UNLIMITED = "freedom unlimited";

    private static final String HILTON = "hilton";

    private static final String HYATT = "hyatt";

    private static final String IMPERIAL = "imperial";

    private static final String INFINITE = "infinite";

    private static final String INVESTMENT = "investment";

    private static final String MARRIOTT = "marriott";

    private static final String MARRIOTT_BONVOY = "marriott bonvoy";

    private static final String MARRIOTT_BONVOY_PREMIER = "marriott bonvoy premier";

    private static final String MASTERCARD = "mastercard";

    private static final String MASTERCARD_WORLD = "mastercard world";

    private static final String MASTERCARD_WORLD_ELITE = "mastercard world elite";

    private static final String MILES = "miles";

    private static final String PLATINUM = "platinum";

    private static final String POINTS = "points";

    private static final String PREFERRED = "preferred";

    private static final String PREMIER = "premier";

    private static final String PREMIUM = "premium";

    private static final String PRESTIGE = "prestige";

    private static final String PRIME_REWARDS_VISA = "prime rewards visa";

    private static final String PRIME_VISA = "prime visa";

    private static final String PRIME_VISA_SIGNATURE = "prime visa signature";

    private static final String QUICKSILVER = "quicksilver";

    private static final String RESERVE = "reserve";

    private static final String REWARDS = "rewards";

    private static final String ROYAL = "royal";

    private static final String SAPPHIRE = "sapphire";

    private static final String SAPPHIRE_PREFERRED = "sapphire preferred";

    private static final String SAPPHIRE_RESERVE = "sapphire reserve";

    private static final String SAVINGS = "savings";

    private static final String SAVOR = "savor";

    private static final String SCHWAB = "schwab";

    private static final String SIGNATURE = "signature";

    private static final String SILVER = "silver";

    private static final String SPARK = "spark";

    private static final String TRAVEL = "travel";

    private static final String UNLIMITED = "unlimited";

    private static final String VANGUARD = "vanguard";

    private static final String VENTURE = "venture";

    private static final String VISA_INFINITE = "visa infinite";

    private static final String VISA_PLATINUM = "visa platinum";

    private static final String VISA_SIGNATURE = "visa signature";

    private static final String WORLD = "world";

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountDetectionService.class);

    private final AccountRepository accountRepository;
    private final BalanceExtractor balanceExtractor;

    public AccountDetectionService(
            final AccountRepository accountRepository, final BalanceExtractor balanceExtractor) {
        this.accountRepository = accountRepository;
        this.balanceExtractor = balanceExtractor;
    }

    // Common bank/institution name patterns - Global support
    private static final List<String> INSTITUTION_KEYWORDS =
            Arrays.asList(
                    // US Major Banks
                    "chase",
                    "bank of america",
                    "wells fargo",
                    "citibank",
                    "citi",
                    "citicards",
                    "us bank",
                    "capital one",
                    AMERICAN_EXPRESS,
                    DISCOVER,
                    "synchrony",
                    "visa",
                    MASTERCARD,
                    "amex",
                    "jpmorgan",
                    "jpm",
                    "jpmc",
                    "bofa",
                    "bac",
                    "wf",
                    "wells",
                    "usbank",
                    "capone",
                    // US Regional Banks
                    "pnc",
                    "truist",
                    "citizens bank",
                    "fifth third",
                    "keybank",
                    "huntington",
                    "regions bank",
                    "m&t bank",
                    "comerica",
                    "zions bank",
                    "first national",
                    "first citizens",
                    "east west bank",
                    "cathay bank",
                    "bank of the west",
                    "first republic",
                    "silicon valley bank",
                    "svb",
                    // US Credit Unions
                    "navy federal",
                    "penfed",
                    "state employees",
                    "teachers federal",
                    "alliant",
                    // US Investment/Wealth Management
                    FIDELITY,
                    SCHWAB,
                    VANGUARD,
                    "morgan stanley",
                    "goldman sachs",
                    "merrill lynch",
                    "edward jones",
                    "raymond james",
                    "lpl financial",
                    "ameriprise",
                    // UK Banks
                    "hsbc",
                    "barclays",
                    "lloyds",
                    "natwest",
                    "rbs",
                    "royal bank of scotland",
                    "santander uk",
                    "halifax",
                    "nationwide",
                    "tsb",
                    "metrobank",
                    "first direct",
                    "monzo",
                    "revolut",
                    "starling",
                    "monese",
                    "chime",
                    "ally bank",
                    // French Banks
                    "bnp paribas",
                    "credit agricole",
                    "societe generale",
                    "credit mutuel",
                    "banque populaire",
                    "la banque postale",
                    "lcl",
                    "credit lyonnais",
                    "caisse d'epargne",
                    // German Banks
                    "deutsche bank",
                    "commerzbank",
                    "sparkasse",
                    "volksbank",
                    "postbank",
                    "dresdner bank",
                    "hypovereinsbank",
                    "landesbank",
                    "bayernlb",
                    // Italian Banks
                    "unicredit",
                    "intesa sanpaolo",
                    "monte dei paschi",
                    "banco popolare",
                    "banca mediolanum",
                    "banca popolare",
                    "mps",
                    "mediobanca",
                    // Spanish Banks
                    "bbva",
                    "santander",
                    "caixa",
                    "bankia",
                    "sabadell",
                    "bankinter",
                    "unicaja",
                    "ibercaja",
                    "kutxabank",
                    "abanca",
                    // Dutch Banks
                    "ing",
                    "rabobank",
                    "abn amro",
                    "sns bank",
                    "asn bank",
                    "triodos",
                    // Swiss Banks
                    "ubs",
                    "credit suisse",
                    "julius baer",
                    "pictet",
                    "lombard odier",
                    "vontobel",
                    "zuercher kantonalbank",
                    "postfinance",
                    // Belgian Banks
                    "kbc",
                    "belfius",
                    "axa bank",
                    "argenta",
                    "keytrade",
                    // Nordic Banks
                    "danske bank",
                    "nordea",
                    "seb",
                    "handelsbanken",
                    "swedbank",
                    "dnb",
                    "op financial",
                    "alandsbanken",
                    "aktia",
                    "sparebank",
                    // Other European Banks
                    "erste bank",
                    "raiffeisen",
                    "otp bank",
                    "kbc",
                    "sberbank",
                    "alfa bank",
                    "tinkoff",
                    "millennium",
                    "pkobp",
                    "ing bank slaski",
                    "mbank",
                    "pekao",
                    // Indian Major Banks
                    "sbi",
                    "state bank of india",
                    "icici",
                    "hdfc",
                    "axis bank",
                    "pnb",
                    "punjab national bank",
                    "kotak",
                    "yes bank",
                    "indusind",
                    "rbl",
                    "idfc",
                    "idbi",
                    "canara bank",
                    "union bank",
                    "indian bank",
                    "bank of baroda",
                    "bank of india",
                    "central bank",
                    "indian overseas bank",
                    // Indian Payment Platforms
                    "paytm",
                    "phonepe",
                    "gpay",
                    "google pay",
                    "amazon pay",
                    "mobikwik",
                    "freecharge",
                    "razorpay",
                    "payu",
                    "cashfree",
                    "instamojo",
                    // Chinese Major Banks
                    "icbc",
                    "ccb",
                    "boc",
                    "abc",
                    "bank of china",
                    "industrial and commercial bank",
                    "china construction bank",
                    "agricultural bank of china",
                    "china merchants bank",
                    "bank of communications",
                    "ping an bank",
                    "china minsheng",
                    "huaxia bank",
                    "spdb",
                    "china citic bank",
                    "evergrowing bank",
                    // Japanese Major Banks
                    "mufg",
                    "mizuho",
                    "smbc",
                    "sumitomo mitsui",
                    "mitsubishi ufj",
                    "resona",
                    "shinsei",
                    "saitama bank",
                    "shizuoka bank",
                    "fukuoka bank",
                    "hokuriku bank",
                    // Korean Banks
                    "kb",
                    "kookmin",
                    "shinhan",
                    "hana",
                    "woori",
                    "nh",
                    "nonghyup",
                    "keb",
                    "korea exchange bank",
                    "ibk",
                    "industrial bank of korea",
                    "kdb",
                    "korea development bank",
                    // Singapore Banks
                    "dbs",
                    "ocbc",
                    "uob",
                    "maybank singapore",
                    "cimb singapore",
                    // Malaysian Banks
                    "maybank",
                    "cimb",
                    "public bank",
                    "hong leong bank",
                    "ambank",
                    "rhb bank",
                    "affin bank",
                    "alliance bank",
                    "bank islam",
                    // Thai Banks
                    "bangkok bank",
                    "kasikorn",
                    "siam commercial",
                    "krung thai",
                    "tmb",
                    "thanachart",
                    "cimb thai",
                    "uob thai",
                    // Indonesian Banks
                    "bca",
                    "mandiri",
                    "bni",
                    "bri",
                    "cimb niaga",
                    "panin bank",
                    "maybank indonesia",
                    "uob indonesia",
                    "ocbc nisp",
                    "dbs indonesia",
                    // Vietnamese Banks
                    "vietcombank",
                    "bidv",
                    "vietinbank",
                    "agribank",
                    "techcombank",
                    "mbbank",
                    "acb",
                    "vietnam bank",
                    "sacombank",
                    "vpbank",
                    // Philippine Banks
                    "bdo",
                    "metrobank",
                    "bpi",
                    "security bank",
                    "eastwest bank",
                    "rcbc",
                    "pnb",
                    "unionbank",
                    "chinabank",
                    "landbank",
                    // Australian/New Zealand Banks
                    "commonwealth bank",
                    "anz",
                    "westpac",
                    "nab",
                    "asb",
                    "anz nz",
                    "bnz",
                    "kiwibank",
                    "bendigo bank",
                    "suncorp",
                    "bankwest",
                    "ing australia",
                    "macquarie",
                    // Canadian Banks
                    "rbc",
                    "td canada",
                    "scotiabank",
                    "bmo",
                    "cibc",
                    "national bank of canada",
                    "desjardins",
                    "tangerine",
                    "simplii",
                    "pc financial",
                    // Middle Eastern Banks
                    "emirates nbd",
                    "adcb",
                    "adib",
                    "fgb",
                    "first abu dhabi",
                    "dubai islamic",
                    "al rajhi",
                    "sabb",
                    "riyad bank",
                    "samba",
                    "banque saudi fransi",
                    "ncb",
                    "qnb",
                    "qatar national bank",
                    "doha bank",
                    "masraf al rayyan",
                    "kuwait finance house",
                    "national bank of kuwait",
                    "gulf bank",
                    "bank muscat",
                    "nbo",
                    "hsbc oman",
                    // Latin American Banks
                    "banco do brasil",
                    "itau",
                    "bradesco",
                    "santander brasil",
                    "caixa economica",
                    "banco de chile",
                    "santander chile",
                    "banco estado",
                    "scotiabank chile",
                    "banamex",
                    "banorte",
                    "santander mexico",
                    "hsbc mexico",
                    "bbva mexico",
                    "banco de bogota",
                    "bancolombia",
                    "davivienda",
                    "banco popular",
                    "banco de venezuela",
                    "banco mercantil",
                    "banesco",
                    "banco de la nacion",
                    "bbva peru",
                    "interbank",
                    "scotiabank peru",
                    // African Banks
                    "standard bank",
                    "absa",
                    "nedbank",
                    "fnb",
                    "firstrand",
                    "capitec",
                    "access bank",
                    "gtbank",
                    "zenith bank",
                    "uba",
                    "first bank",
                    "equity bank",
                    "kcb",
                    "cooperative bank",
                    "diamond trust",
                    // Global Credit Card Networks
                    MASTERCARD,
                    "visa",
                    AMERICAN_EXPRESS,
                    "amex",
                    DISCOVER,
                    "jcb",
                    "unionpay",
                    "diners club",
                    "diners",
                    "dinersclub",
                    "rupay",
                    // Global Investment Platforms
                    FIDELITY,
                    VANGUARD,
                    SCHWAB,
                    "td ameritrade",
                    "etrade",
                    "robinhood",
                    "morgan stanley",
                    "goldman sachs",
                    "interactive brokers",
                    "etoro",
                    "degiro",
                    "icici direct",
                    "hdfc securities",
                    "zerodha",
                    "upstox",
                    "groww",
                    "paytm money",
                    "mufg securities",
                    "nomura",
                    "samsung securities",
                    "mirae asset",
                    "citic securities",
                    "huatai securities",
                    "comdirect",
                    "consorsbank",
                    "binckbank",
                    "lynx",
                    "boursorama",
                    "selfbank",
                    "renta 4",
                    "finecobank",
                    "directa",
                    "trading 212",
                    "freetrade",
                    "revolut trading",
                    "webull",
                    "sofi",
                    "m1 finance",
                    "public",
                    "stash",
                    // Other Global Institutions
                    "standard chartered",
                    "jpmorgan chase",
                    "hsbc global",
                    "citibank global");

    // Account type patterns from filenames - Global support
    private static final Map<String, String> ACCOUNT_TYPE_PATTERNS = new HashMap<>();

    static {
        // Deposit Accounts - Global variations
        ACCOUNT_TYPE_PATTERNS.put(CHECKING, DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put(CHECK, DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put(SAVINGS, DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("saving", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("current", DEPOSITORY); // UK/India/Australia
        ACCOUNT_TYPE_PATTERNS.put("giro", DEPOSITORY); // European
        ACCOUNT_TYPE_PATTERNS.put("transaction", DEPOSITORY); // Australia/New Zealand
        ACCOUNT_TYPE_PATTERNS.put("transactional", DEPOSITORY);
        ACCOUNT_TYPE_PATTERNS.put("demand", DEPOSITORY); // India/Asia
        ACCOUNT_TYPE_PATTERNS.put("term deposit", DEPOSITORY); // Fixed deposit
        ACCOUNT_TYPE_PATTERNS.put("fixed deposit", DEPOSITORY); // India/Asia
        ACCOUNT_TYPE_PATTERNS.put("fd", DEPOSITORY); // Fixed deposit abbreviation
        ACCOUNT_TYPE_PATTERNS.put("recurring deposit", DEPOSITORY); // RD - India
        ACCOUNT_TYPE_PATTERNS.put("rd", DEPOSITORY); // Recurring deposit abbreviation
        ACCOUNT_TYPE_PATTERNS.put("time deposit", DEPOSITORY); // US term
        ACCOUNT_TYPE_PATTERNS.put("certificate of deposit", DEPOSITORY); // CD - US
        ACCOUNT_TYPE_PATTERNS.put("cd", DEPOSITORY); // Certificate of deposit
        ACCOUNT_TYPE_PATTERNS.put("money market", DEPOSITORY); // Money market account
        ACCOUNT_TYPE_PATTERNS.put("mm", DEPOSITORY); // Money market abbreviation
        // Credit Cards
        ACCOUNT_TYPE_PATTERNS.put(CREDIT, CREDIT);
        ACCOUNT_TYPE_PATTERNS.put("card", CREDIT);
        ACCOUNT_TYPE_PATTERNS.put("creditcard", CREDIT);
        ACCOUNT_TYPE_PATTERNS.put(CREDIT_CARD, CREDIT);
        ACCOUNT_TYPE_PATTERNS.put("citi cash card", CREDIT); // Cash card is a type of credit card
        ACCOUNT_TYPE_PATTERNS.put("Citi cashcard", CREDIT);
        ACCOUNT_TYPE_PATTERNS.put("visa", CREDIT);
        ACCOUNT_TYPE_PATTERNS.put(MASTERCARD, CREDIT);
        ACCOUNT_TYPE_PATTERNS.put("amex", CREDIT);
        ACCOUNT_TYPE_PATTERNS.put(AMERICAN_EXPRESS, CREDIT);
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
        ACCOUNT_TYPE_PATTERNS.put(INVESTMENT, INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("brokerage", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("trading", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("stock", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("stocks", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("equity", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("mutual fund", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("mutualfund", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("mf", INVESTMENT); // Mutual fund abbreviation
        ACCOUNT_TYPE_PATTERNS.put("etf", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("bond", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("bonds", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("government bond", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("corporate bond", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("t-bill", INVESTMENT); // Treasury bill
        ACCOUNT_TYPE_PATTERNS.put("treasury", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("commodity", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("forex", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("derivatives", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("options", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("futures", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("crypto", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("cryptocurrency", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("bitcoin", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("demat", INVESTMENT); // Dematerialized - India
        ACCOUNT_TYPE_PATTERNS.put("demat account", INVESTMENT); // India stock trading
        ACCOUNT_TYPE_PATTERNS.put("demat ac", INVESTMENT);
        // Retirement Accounts (US)
        ACCOUNT_TYPE_PATTERNS.put("ira", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("roth", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("rothira", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("401k", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("403b", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("pension", INVESTMENT);
        // Retirement Accounts (Other Regions)
        ACCOUNT_TYPE_PATTERNS.put("superannuation", INVESTMENT); // Australia
        ACCOUNT_TYPE_PATTERNS.put("super", INVESTMENT); // Australia abbreviation
        ACCOUNT_TYPE_PATTERNS.put("kiwisaver", INVESTMENT); // New Zealand
        ACCOUNT_TYPE_PATTERNS.put("ppf", INVESTMENT); // India - Public Provident Fund
        ACCOUNT_TYPE_PATTERNS.put("epf", INVESTMENT); // India - Employee Provident Fund
        ACCOUNT_TYPE_PATTERNS.put("provident", INVESTMENT); // India/Asia
        ACCOUNT_TYPE_PATTERNS.put("retirement", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("pension", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("cpf", INVESTMENT); // Central Provident Fund - Singapore
        ACCOUNT_TYPE_PATTERNS.put("epf malaysia", INVESTMENT); // Malaysia EPF
        ACCOUNT_TYPE_PATTERNS.put("kwsp", INVESTMENT); // Malaysia EPF abbreviation
        ACCOUNT_TYPE_PATTERNS.put("social security", INVESTMENT); // US/Global
        ACCOUNT_TYPE_PATTERNS.put("national pension", INVESTMENT); // Korea/Japan
        ACCOUNT_TYPE_PATTERNS.put("npf", INVESTMENT); // National Pension Fund
        // Investment Platforms
        ACCOUNT_TYPE_PATTERNS.put(FIDELITY, INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put(VANGUARD, INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put(SCHWAB, INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("robinhood", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("etrade", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("td ameritrade", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("icici direct", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("hdfc securities", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("zerodha", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("upstox", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("groww", INVESTMENT);
        ACCOUNT_TYPE_PATTERNS.put("paytm money", INVESTMENT);
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
    private static final Pattern ACCOUNT_NUMBER_PATTERN =
            Pattern.compile(
                    "(?:"
                            +
                            // Pattern 1: Account/Card ending/with last digits (most common)
                            "(?:(?:account|acct|card|credit\\s*card|debit\\s*card|savings\\s*account|checking\\s*account|investment\\s*account|brokerage\\s*account|loan\\s*account|mortgage\\s*account|auto\\s*loan|personal\\s*loan)\\s*(?:number|#|no\\.?)?\\s*(?:ending\\s*(?:in|with)?\\s*:?\\s*|with\\s*(?:last\\s*)?(?:4\\s*|four\\s*)?(?:digits?|numbers?)\\s*:?\\s*))"
                            + "|"
                            +
                            // Pattern 2: Last 4 digits (standalone phrase)
                            "(?:last\\s*(?:4\\s*|four\\s*)?(?:digits?|numbers?)\\s*:?\\s*)"
                            + "|"
                            +
                            // Pattern 3: Account/Card Number: (direct label)
                            "(?:(?:account|acct|card|credit\\s*card|debit\\s*card|savings|checking|investment|brokerage|loan|mortgage)\\s*(?:number|#|no\\.?)\\s*:?\\s*)"
                            + ")"
                            +
                            // Capture group: Account number (allows hyphens, spaces, masks)
                            // Increased mask limit to handle patterns like "xxxx xxxx xxxx 4666"
                            // (up to 24 chars of masks/spaces)
                            "([*xX\\s-]{0,24}(?:\\d[\\s-]*){3,19}\\d)",
                    Pattern.CASE_INSENSITIVE);

    // Pattern for credit/debit card numbers (16 digits, possibly masked, with various formats)
    // Handles patterns like:
    // - "Card ending in 1234", "Card # ending: 1234"
    // - "****1234", "xxxx1234" (masked)
    // - "1234567890123456" (full 16-digit card)
    // - "1234-5678-9012-3456" (formatted with hyphens)
    // CRITICAL: This is a fallback pattern specifically for cards when ACCOUNT_NUMBER_PATTERN
    // doesn't match
    private static final Pattern CARD_NUMBER_PATTERN =
            Pattern.compile(
                    "(?:(?:card|credit\\s*card|debit\\s*card)\\s*(?:number|#)?\\s*(?:ending\\s*(?:in|with)?\\s*:?\\s*|with\\s*(?:last\\s*)?(?:4\\s*|four\\s*)?(?:digits?|numbers?)\\s*:?\\s*)?)"
                            +
                            // Increased mask limit to handle patterns like "xxxx xxxx xxxx 4666"
                            // (up to 24 chars of masks/spaces)
                            "([*xX\\s-]{0,24}(?:\\d[\\s-]*){3,19}\\d)",
                    Pattern.CASE_INSENSITIVE);

    // Keywords for account number detection in headers/data
    // Enhanced with investment, loan, and savings account patterns
    private static final List<String> ACCOUNT_NUMBER_KEYWORDS =
            Arrays.asList(
                    // General account patterns
                    "account number",
                    "account #",
                    "account no",
                    "accountno",
                    "acct number",
                    "acct #",
                    "acct no",
                    // Card patterns
                    "card number",
                    "card #",
                    "card no",
                    "credit card number",
                    "credit card #",
                    "credit card no",
                    "debit card number",
                    "debit card #",
                    "debit card no",
                    // Savings/Checking patterns
                    "savings account number",
                    "savings account #",
                    "checking account number",
                    "checking account #",
                    "savings #",
                    "checking #",
                    // Investment patterns
                    "investment account number",
                    "investment account #",
                    "brokerage account number",
                    "brokerage account #",
                    "investment #",
                    "brokerage #",
                    "investment account",
                    "brokerage account",
                    // Loan patterns
                    "loan account number",
                    "loan account #",
                    "loan number",
                    "loan #",
                    "loan no",
                    "mortgage account number",
                    "mortgage account #",
                    "mortgage number",
                    "mortgage #",
                    "auto loan number",
                    "auto loan #",
                    "personal loan number",
                    "personal loan #",
                    // Ending patterns
                    "account ending",
                    "card ending",
                    "acct ending",
                    "account ending in",
                    "card ending in",
                    "acct ending in",
                    "account ending with",
                    "card ending with",
                    "acct ending with",
                    // Last digits patterns
                    "account with last 4",
                    "card with last 4",
                    "account last 4 digits",
                    "card last 4 digits",
                    "last 4 digits",
                    "last 4 numbers",
                    "last four digits",
                    "last four numbers",
                    // Alternative patterns
                    "account identifier",
                    "account id",
                    "account code");

    // Keywords for institution name detection
    private static final List<String> INSTITUTION_KEYWORDS_HEADERS =
            Arrays.asList(
                    "institution",
                    "institution name",
                    "bank",
                    "bank name",
                    "financial institution",
                    "issuer",
                    "issuer name",
                    "card issuer",
                    "bank name",
                    "banking institution",
                    "card account at",
                    "online account at",
                    "online chat at",
                    "write us at");

    // Keywords for product name detection
    private static final List<String> PRODUCT_NAME_KEYWORDS =
            Arrays.asList(
                    "product name",
                    "product",
                    "card name",
                    "account product",
                    "card product",
                    "product description",
                    "account description",
                    "card description",
                    "product type",
                    "card type",
                    "account type name");

    // Keywords for account type detection
    private static final List<String> ACCOUNT_TYPE_KEYWORDS =
            Arrays.asList(
                    "account type",
                    "type",
                    "account category",
                    "category",
                    "product type",
                    "card type",
                    "account classification");

    /** Detected account information */
    public static class DetectedAccount {
        private String accountNumber; // Last 4 digits or full number

        /**
         * Full (or best-available) account number extracted from the source. Stored masked with
         * {@link com.budgetbuddy.service.PrivacyRedaction} for display purposes; the raw form is
         * kept only in memory and never logged or serialised unredacted. Populated whenever the
         * source exposes more than the trailing 4 digits — older BoA PDFs carry
         * "XXXX-XXXX-XXXX-1234" style masks which we can preserve as-is.
         */
        private String fullAccountNumber;

        private String institutionName;
        private String accountName;
        private String accountType;
        private String accountSubtype;
        private String cardNumber; // For credit cards
        private String matchedAccountId; // Account ID if already matched to existing account
        private String
                accountHolderName; // Account holder/cardholder name (for family account username
        // validation)
        private java.math.BigDecimal balance; // Detected balance from statement/import
        private java.time.LocalDate
                balanceDate; // Date of the transaction from which balance was extracted (for date

        // comparison)

        public String getAccountNumber() {
            return accountNumber;
        }

        public void setAccountNumber(final String accountNumber) {
            this.accountNumber = accountNumber;
        }

        public String getFullAccountNumber() {
            return fullAccountNumber;
        }

        public void setFullAccountNumber(final String fullAccountNumber) {
            this.fullAccountNumber = fullAccountNumber;
        }

        public String getInstitutionName() {
            return institutionName;
        }

        public void setInstitutionName(final String institutionName) {
            this.institutionName = institutionName;
        }

        public String getAccountName() {
            return accountName;
        }

        public void setAccountName(final String accountName) {
            this.accountName = accountName;
        }

        public String getAccountType() {
            return accountType;
        }

        public void setAccountType(final String accountType) {
            this.accountType = accountType;
        }

        public String getAccountSubtype() {
            return accountSubtype;
        }

        public void setAccountSubtype(final String accountSubtype) {
            this.accountSubtype = accountSubtype;
        }

        public String getCardNumber() {
            return cardNumber;
        }

        public void setCardNumber(final String cardNumber) {
            this.cardNumber = cardNumber;
        }

        public String getMatchedAccountId() {
            return matchedAccountId;
        }

        public void setMatchedAccountId(final String matchedAccountId) {
            this.matchedAccountId = matchedAccountId;
        }

        public String getAccountHolderName() {
            return accountHolderName;
        }

        public void setAccountHolderName(final String accountHolderName) {
            this.accountHolderName = accountHolderName;
        }

        public java.math.BigDecimal getBalance() {
            return balance;
        }

        public void setBalance(final java.math.BigDecimal balance) {
            this.balance = balance;
        }

        public java.time.LocalDate getBalanceDate() {
            return balanceDate;
        }

        public void setBalanceDate(final java.time.LocalDate balanceDate) {
            this.balanceDate = balanceDate;
        }
    }

    /**
     * Detect account information from filename Examples: - "chase_checking_1234.csv" ->
     * institution: Chase, type: checking, number: 1234 - "bofa_credit_card_5678.pdf" ->
     * institution: Bank of America, type: credit, number: 5678
     */
    public DetectedAccount detectFromFilename(final String filename) {
        if (filename == null || filename.isEmpty()) {
            return null;
        }

        // CRITICAL: Handle whitespace-only filenames
        if (filename.isBlank()) {
            return null;
        }

        // Handle "unknown" or generated filenames - skip detection
        final String lowerFilename = filename.toLowerCase(Locale.ROOT);
        if (lowerFilename.startsWith("unknown")
                || lowerFilename.startsWith("import_")
                || lowerFilename.matches(
                        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(csv|xlsx|xls|pdf)$")) {
            LOGGER.info(
                    "⚠️ Skipping account detection for generated/UUID filename: '{}' (filename not useful for detection - will rely on transaction patterns)",
                    filename);
            return null;
        }

        final DetectedAccount detected = new DetectedAccount();

        // Remove file extension
        final String nameWithoutExt = lowerFilename.replaceAll("\\.(csv|xlsx|xls|pdf)$", "");
        LOGGER.info(
                "🔍 Analyzing filename for account detection: '{}' (without extension: '{}')",
                filename,
                nameWithoutExt);

        // Detect institution name
        final String institution = detectInstitutionFromFilename(nameWithoutExt);
        if (institution != null) {
            detected.setInstitutionName(institution);
            LOGGER.info("✓ Detected institution from filename: {}", institution);
        } else {
            LOGGER.info("⚠️ No institution detected from filename: {}", nameWithoutExt);
        }

        // Detect account type
        final String accountType = detectAccountTypeFromFilename(nameWithoutExt);
        if (accountType != null) {
            detected.setAccountType(accountType);
            LOGGER.info("✓ Detected account type from filename: {}", accountType);
            // Set subtype based on type
            if (DEPOSITORY.equals(accountType)) {
                if (nameWithoutExt.contains(CHECKING) || nameWithoutExt.contains(CHECK)) {
                    detected.setAccountSubtype(CHECKING);
                } else if (nameWithoutExt.contains(SAVINGS)
                        || nameWithoutExt.contains("saving")) {
                    detected.setAccountSubtype(SAVINGS);
                }
            } else if (CREDIT.equals(accountType)) {
                detected.setAccountSubtype(CREDIT_CARD);
            }
        } else {
            LOGGER.info("⚠️ No account type detected from filename: {}", nameWithoutExt);
        }

        // Extract account number (last 4 digits) from filename
        // Pattern matches: 4 digits at word boundary or after institution name (e.g., "Chase3100",
        // "chase_3100", "chase 3100")
        // Try multiple patterns to catch different formats
        String accountNum = null;

        // Pattern 1: 4 digits directly after letters (e.g., "Chase3100")
        final Pattern pattern1 = Pattern.compile("([a-z]+)(\\d{4})(?:_|\\s|$)", Pattern.CASE_INSENSITIVE);
        final Matcher matcher1 = pattern1.matcher(nameWithoutExt);
        if (matcher1.find()) {
            accountNum = matcher1.group(2);
        }

        // Pattern 2: 4 digits with separators (e.g., "chase_3100", "chase 3100")
        if (accountNum == null) {
            final Pattern pattern2 = Pattern.compile("(?:^|_|\\s)(\\d{4})(?:$|_|\\s)");
            final Matcher matcher2 = pattern2.matcher(nameWithoutExt);
            if (matcher2.find()) {
                accountNum = matcher2.group(1);
            }
        }

        if (accountNum != null) {
            detected.setAccountNumber(accountNum);
            LOGGER.info("✓ Extracted account number from filename: {}", accountNum);
        }

        // Generate account name if we have institution and type
        if (detected.getInstitutionName() != null && detected.getAccountType() != null) {
            final String accountName =
                    generateAccountName(
                            detected.getInstitutionName(),
                            detected.getAccountType(),
                            detected.getAccountSubtype(),
                            detected.getAccountNumber());
            detected.setAccountName(accountName);
        }

        return detected;
    }

    /**
     * Detect account information from PDF text content Looks for account numbers, card numbers,
     * institution names in PDF headers Enhanced with better keyword matching and logging
     */
    public DetectedAccount detectFromPDFContent(final String pdfText, final String filename) {
        if (pdfText == null || pdfText.isEmpty()) {
            return detectFromFilename(filename); // Fallback to filename
        }

        final DetectedAccount detected = new DetectedAccount();

        // Extract header text - includes first lines and Service Agreement section (for Chase
        // cards)
        final String headerText = extractHeaderTextWithServiceAgreement(pdfText);

        // Log header text summary (reduced verbosity for testing)
        final String[] headerLines = headerText.split("\n");
        LOGGER.info("Total header lines to analyze: {}", headerLines.length);

        final String lowerHeader = headerText.toLowerCase(Locale.ROOT);

        // Detect institution name from PDF content using strict matching (word boundaries)
        // This prevents false positives from substrings (e.g., "chase" in "purchase")
        final String institution = extractInstitutionFromTextStrict(headerText);
        if (institution != null) {
            detected.setInstitutionName(institution);
            LOGGER.info("✓ Extracted institution name from PDF: {}", institution);
        } else {
            // Fallback to filename
            final DetectedAccount fromFilename = detectFromFilename(filename);
            if (fromFilename != null && fromFilename.getInstitutionName() != null) {
                detected.setInstitutionName(fromFilename.getInstitutionName());
                LOGGER.info(
                        "✓ Using institution name from filename: {}",
                        fromFilename.getInstitutionName());
            }
        }

        // Detect account number using enhanced pattern matching
        final String accountNumber = extractAccountNumberFromText(headerText);
        if (accountNumber != null) {
            detected.setAccountNumber(accountNumber);
            LOGGER.info("✓ Extracted account number from PDF: {}", accountNumber);
        }

        // Detect card number (for credit cards) using enhanced pattern
        final Matcher cardMatcher = CARD_NUMBER_PATTERN.matcher(headerText);
        if (cardMatcher.find()) {
            try {
                String cardNum = cardMatcher.group(1);
                if (cardNum != null) {
                    cardNum = cardNum.replaceAll("[*xX]", "");
                    if (cardNum.length() >= 4) {
                        // CRITICAL FIX: Ensure safe substring
                        final int startIndex = Math.max(0, cardNum.length() - 4);
                        final String lastFour = cardNum.substring(startIndex);
                        detected.setCardNumber(lastFour);
                        if (detected.getAccountNumber() == null) {
                            detected.setAccountNumber(lastFour);
                            LOGGER.info(
                                    "✓ Extracted card number from PDF (used as account number): {}",
                                    lastFour);
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Error extracting card number from PDF: {}", e.getMessage());
            }
        }

        // Detect account type from content - enhanced credit card detection
        // Check for credit card indicators: credit limit, available credit, cash advances, etc.
        // These patterns work for ALL credit card issuers, not just specific ones
        final boolean isCreditCard = detectCreditCardFromContent(lowerHeader);

        if (isCreditCard) {
            detected.setAccountType(CREDIT);
            detected.setAccountSubtype(CREDIT_CARD);
            LOGGER.info("✓ Detected credit card account type from PDF content");
        } else if (lowerHeader.contains(CHECKING) || lowerHeader.contains("checking account")) {
            detected.setAccountType(DEPOSITORY);
            detected.setAccountSubtype(CHECKING);
            LOGGER.info("✓ Detected checking account type from PDF content");
        } else if (lowerHeader.contains(SAVINGS) || lowerHeader.contains("savings account")) {
            detected.setAccountType(DEPOSITORY);
            detected.setAccountSubtype(SAVINGS);
            LOGGER.info("✓ Detected savings account type from PDF content");
        } else if (lowerHeader.contains("loan") || lowerHeader.contains("mortgage")) {
            detected.setAccountType("loan");
            LOGGER.info("✓ Detected loan account type from PDF content");
        }

        // Extract balance from PDF header text (after account type is detected for better pattern
        // matching)
        final java.math.BigDecimal balance =
                extractBalanceFromHeaders(
                        Arrays.asList(headerText.split("\n")), detected.getAccountType());
        if (balance != null) {
            detected.setBalance(balance);
            LOGGER.info("✓ Extracted balance from PDF: {}", balance);
        }

        // Extract product/card name from PDF content (e.g., "Citi Double Cash® Card")
        // Look for patterns like: "Card Name", "Product Name Card", etc.
        final String productName = extractProductNameFromPDF(headerText);
        if (productName != null) {
            detected.setAccountName(productName);
            LOGGER.info("✓ Extracted product/card name from PDF: {}", productName);
        } else {
            // Generate account name if product name not found
            if (detected.getInstitutionName() != null && detected.getAccountType() != null) {
                final String accountName =
                        generateAccountName(
                                detected.getInstitutionName(),
                                detected.getAccountType(),
                                detected.getAccountSubtype(),
                                detected.getAccountNumber());
                detected.setAccountName(accountName);
                LOGGER.info("✓ Generated account name: {}", accountName);
            }
        }

        // Extract account holder/cardholder name for family account username validation
        final String accountHolderName = extractAccountHolderNameFromPDF(headerText);
        if (accountHolderName != null) {
            detected.setAccountHolderName(accountHolderName);
            LOGGER.info("✓ Extracted account holder name from PDF: {}", accountHolderName);
        }

        return detected;
    }

    /**
     * Detect account information from CSV/Excel headers Looks for account number, account name,
     * institution columns Enhanced with better keyword matching and value extraction from headers
     */
    public DetectedAccount detectFromHeaders(final List<String> headers, final String filename) {
        if (headers == null || headers.isEmpty()) {
            return detectFromFilename(filename);
        }

        final DetectedAccount detected = new DetectedAccount();
        final Map<String, String> headerMap = new HashMap<>();
        final Map<String, Integer> headerIndexMap = new HashMap<>(); // Track column indices

        // Log all headers (ignore empty lines)
        LOGGER.info("=== HEADER ANALYSIS START ===");
        LOGGER.info("Total headers found: {}", headers.size());
        for (int i = 0; i < headers.size(); i++) {
            final String header = headers.get(i);
            if (header != null && !header.isBlank()) {
                // Handle text on left/right side of same line (split by whitespace/tabs)
                final String[] parts = header.split("\\s{2,}|\\t+"); // Split on 2+ spaces or tabs
                for (final String part : parts) {
                    final String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        LOGGER.info(
                                "Header [Column {}]: '{}' (full: '{}')", i + 1, trimmed, header);
                    }
                }

                final String lower = header.toLowerCase(Locale.ROOT).trim();
                if (!lower.isEmpty()) {
                    headerMap.put(lower, header); // Keep original for display
                    headerIndexMap.put(lower, i); // Track column index
                }
            }
        }
        LOGGER.info("=== HEADER ANALYSIS END ===");

        // CRITICAL: Check if headers look like a transaction table (not account metadata)
        final boolean isTransactionTable = isTransactionTableHeadersInternal(headers);
        if (isTransactionTable) {
            LOGGER.info(
                    "⚠️ Headers appear to be transaction table headers - skipping account info extraction from header text");
            // For transaction tables, only extract from dedicated account metadata columns
            // Don't extract from generic transaction columns like "type", "description", etc.
        }

        // Extract account number from headers (look for patterns in header text itself)
        final String accountNumber = extractAccountNumberFromText(String.join(" ", headers));
        if (accountNumber != null) {
            detected.setAccountNumber(accountNumber);
            LOGGER.info("✓ Extracted account number from headers: {}", accountNumber);
        }

        // Look for account number column header
        final Integer accountNumberColumnIndex =
                findColumnIndex(headerMap, headerIndexMap, ACCOUNT_NUMBER_KEYWORDS);
        if (accountNumberColumnIndex != null) {
            final String headerName = headers.get(accountNumberColumnIndex);
            LOGGER.info(
                    "✓ Found account number column at index {}: '{}'",
                    accountNumberColumnIndex,
                    headerName);
            // Note: Account number value will be extracted from data rows, not header
        }

        // Look for account name column
        final Integer accountNameColumnIndex =
                findColumnIndex(
                        headerMap,
                        headerIndexMap,
                        Arrays.asList(ACCOUNT_NAME, "accountname", ACCOUNT, "acct name"));
        if (accountNameColumnIndex != null) {
            final String headerName = headers.get(accountNameColumnIndex);
            LOGGER.info(
                    "✓ Found account name column at index {}: '{}'",
                    accountNameColumnIndex,
                    headerName);
            // CRITICAL: Extract account name value from header text (e.g., "Account Name: Chase
            // Checking" -> "Chase Checking")
            final String accountName = extractAccountNameFromValue(headerName);
            if (accountName != null) {
                // If accountName is empty string, it means column exists but value not in header
                // Set a placeholder to indicate the column was found (value will come from data
                // rows)
                if (accountName.isEmpty()) {
                    detected.setAccountName(""); // Empty string indicates column exists
                    LOGGER.info(
                            "✓ Found account name column (value will be extracted from data rows)");
                } else {
                    detected.setAccountName(accountName);
                    LOGGER.info("✓ Extracted account name from header: {}", accountName);
                }
            }
        }

        // Look for institution column or product name
        final Integer institutionColumnIndex =
                findColumnIndex(headerMap, headerIndexMap, INSTITUTION_KEYWORDS_HEADERS);
        final Integer productNameColumnIndex =
                findColumnIndex(headerMap, headerIndexMap, PRODUCT_NAME_KEYWORDS);

        // Prefer product name over institution name
        if (productNameColumnIndex != null) {
            final String headerName = headers.get(productNameColumnIndex);
            LOGGER.info(
                    "✓ Found product name column at index {}: '{}'",
                    productNameColumnIndex,
                    headerName);
            // CRITICAL: Extract institution/product name value from header text
            final String institutionName = extractInstitutionFromValue(headerName);
            if (institutionName != null) {
                // If institutionName is empty string, it means column exists but value not in
                // header
                // Set a placeholder to indicate the column was found (value will come from data
                // rows)
                if (institutionName.isEmpty()) {
                    detected.setInstitutionName(""); // Empty string indicates column exists
                    LOGGER.info(
                            "✓ Found institution/product name column (value will be extracted from data rows)");
                } else {
                    detected.setInstitutionName(institutionName);
                    LOGGER.info("✓ Extracted institution name from header: {}", institutionName);
                }
            }
        } else if (institutionColumnIndex != null) {
            final String headerName = headers.get(institutionColumnIndex);
            LOGGER.info(
                    "✓ Found institution column at index {}: '{}'",
                    institutionColumnIndex,
                    headerName);
            // CRITICAL: Extract institution name value from header text
            // Even for transaction tables, extract from column header if it contains a value
            final String institutionName = extractInstitutionFromValue(headerName);
            if (institutionName != null) {
                // If institutionName is empty string, it means column exists but value not in
                // header
                // Set a placeholder to indicate the column was found (value will come from data
                // rows)
                if (institutionName.isEmpty()) {
                    detected.setInstitutionName(""); // Empty string indicates column exists
                    LOGGER.info(
                            "✓ Found institution column (value will be extracted from data rows)");
                } else {
                    detected.setInstitutionName(institutionName);
                    LOGGER.info("✓ Extracted institution name from header: {}", institutionName);
                }
            } else {
                // Column found but no value extracted - set empty string to indicate column exists
                detected.setInstitutionName("");
                LOGGER.info("✓ Found institution column (value will be extracted from data rows)");
            }
        }

        // Look for account type column (but only if it's NOT a transaction table)
        // In transaction tables, "type" usually means transaction type, not account type
        Integer accountTypeColumnIndex = null;
        if (!isTransactionTable) {
            accountTypeColumnIndex =
                    findColumnIndex(headerMap, headerIndexMap, ACCOUNT_TYPE_KEYWORDS);
            if (accountTypeColumnIndex != null) {
                final String headerName = headers.get(accountTypeColumnIndex);
                LOGGER.info(
                        "✓ Found account type column at index {}: '{}'",
                        accountTypeColumnIndex,
                        headerName);
                // Note: Account type value will be extracted from data rows, not header
            }
        } else {
            LOGGER.info(
                    "⚠️ Skipping account type column detection - headers are transaction table");
        }

        // CRITICAL: Only extract account type from header text if NOT a transaction table
        // Transaction tables often have "type" columns that refer to transaction type, not account
        // type
        if (!isTransactionTable) {
            final String accountType = extractAccountTypeFromText(String.join(" ", headers));
            if (accountType != null) {
                detected.setAccountType(accountType);
                LOGGER.info("✓ Extracted account type from headers: {}", accountType);
            }
        } else {
            LOGGER.info(
                    "⚠️ Skipping account type extraction from header text - headers are transaction table");
        }

        // CRITICAL: Only extract institution name from header text if NOT a transaction table
        // Transaction tables may contain words like "posting", "description" that contain bank name
        // substrings
        // Also skip if headers are too short (single word headers like "Date" don't contain
        // institution names)
        if (!isTransactionTable && headers.size() > 1) {
            final String headerText = String.join(" ", headers);
            // Only try extraction if header text has sufficient content (more than just column
            // names)
            // Single word headers like "Date" won't contain institution names
            if (headerText.trim().length()
                    > 10) { // At least 10 characters to have meaningful content
                final String institutionFromText = extractInstitutionFromTextStrict(headerText);
                if (institutionFromText != null) {
                    detected.setInstitutionName(institutionFromText);
                    LOGGER.info(
                            "✓ Extracted institution name from headers: {}", institutionFromText);
                }
            } else {
                LOGGER.info(
                        "⚠️ Skipping institution name extraction - header text too short (likely just column names)");
            }
        } else {
            if (isTransactionTable) {
                LOGGER.info(
                        "⚠️ Skipping institution name extraction from header text - headers are transaction table");
            } else {
                LOGGER.info(
                        "⚠️ Skipping institution name extraction from header text - only {} header(s) (likely just column names)",
                        headers.size());
            }
        }

        // Extract balance from headers (if account type is known or can be inferred)
        final String detectedAccountType = detected.getAccountType();
        final java.math.BigDecimal balance = extractBalanceFromHeaders(headers, detectedAccountType);
        if (balance != null) {
            detected.setBalance(balance);
            LOGGER.info("✓ Extracted balance from headers: {}", balance);
        }

        // CRITICAL: Prioritize filename detection, especially for transaction tables
        final DetectedAccount fromFilename = detectFromFilename(filename);
        if (fromFilename != null) {
            // For transaction tables, prioritize filename over header text
            if (isTransactionTable) {
                // Use filename values if available, only fall back to header values if filename
                // doesn't have them
                if (fromFilename.getInstitutionName() != null) {
                    detected.setInstitutionName(fromFilename.getInstitutionName());
                    LOGGER.info(
                            "✓ Using institution name from filename (transaction table): {}",
                            fromFilename.getInstitutionName());
                }
                if (fromFilename.getAccountType() != null) {
                    detected.setAccountType(fromFilename.getAccountType());
                    detected.setAccountSubtype(fromFilename.getAccountSubtype());
                    LOGGER.info(
                            "✓ Using account type from filename (transaction table): {} / {}",
                            fromFilename.getAccountType(),
                            fromFilename.getAccountSubtype());
                }
                if (fromFilename.getAccountNumber() != null) {
                    detected.setAccountNumber(fromFilename.getAccountNumber());
                    LOGGER.info(
                            "✓ Using account number from filename (transaction table): {}",
                            fromFilename.getAccountNumber());
                }
            } else {
                // For non-transaction tables, use header values if available, fall back to filename
                if (detected.getInstitutionName() == null
                        && fromFilename.getInstitutionName() != null) {
                    detected.setInstitutionName(fromFilename.getInstitutionName());
                    LOGGER.info(
                            "✓ Using institution name from filename: {}",
                            fromFilename.getInstitutionName());
                }
                if (detected.getAccountType() == null && fromFilename.getAccountType() != null) {
                    detected.setAccountType(fromFilename.getAccountType());
                    detected.setAccountSubtype(fromFilename.getAccountSubtype());
                    LOGGER.info(
                            "✓ Using account type from filename: {} / {}",
                            fromFilename.getAccountType(),
                            fromFilename.getAccountSubtype());
                }
                if (detected.getAccountNumber() == null
                        && fromFilename.getAccountNumber() != null) {
                    detected.setAccountNumber(fromFilename.getAccountNumber());
                    LOGGER.info(
                            "✓ Using account number from filename: {}",
                            fromFilename.getAccountNumber());
                }
            }
        }

        return detected;
    }

    /**
     * Extract account name value from header text Handles formats like "Account Name: Chase
     * Checking" or "Account Name" or "Chase Checking" If header is just a label (e.g., "Account
     * Name"), returns a placeholder to indicate column exists
     */
    private String extractAccountNameFromValue(final String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }

        // Remove the column label (e.g., "Account Name:" or "Account Name")
        String value = headerValue.trim();
        final String[] parts = value.split("[:：]", 2); // Split on colon (English or Chinese)
        if (parts.length > 1) {
            value = parts[1].trim();
        } else {
            // Check if it's just the label without a value
            final String lower = value.toLowerCase(Locale.ROOT);
            if (lower.contains(ACCOUNT_NAME)
                    || lower.contains("accountname")
                    || (lower.contains(ACCOUNT) && !lower.contains("number"))) {
                // This is just the label, no value - return placeholder to indicate column exists
                // The actual value will be extracted from data rows during import
                return ""; // Empty string indicates column exists but value not yet extracted
            }
        }

        return value.isEmpty() ? null : value;
    }

    /**
     * Extract institution name value from header text Handles formats like "Institution Name:
     * Chase" or "Institution Name" or "Chase" If header is just a label (e.g., "Institution Name"),
     * returns empty string to indicate column exists
     */
    private String extractInstitutionFromValue(final String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }

        // Remove the column label (e.g., "Institution Name:" or "Institution Name")
        String value = headerValue.trim();
        final String[] parts = value.split("[:：]", 2); // Split on colon (English or Chinese)
        if (parts.length > 1) {
            value = parts[1].trim();
        } else {
            // Check if it's just the label without a value
            final String lower = value.toLowerCase(Locale.ROOT);
            if (lower.contains("institution")
                    || lower.contains("bank")
                    || lower.contains("product name")
                    || lower.contains("productname")) {
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

    /** Check if headers are transaction table headers (public method for CSVImportService) */
    public boolean isTransactionTableHeaders(final List<String> headers) {
        return isTransactionTableHeadersInternal(headers);
    }

    /** Find column index for a list of keywords */
    private Integer findColumnIndex(
            final Map<String, String> headerMap,
            final Map<String, Integer> headerIndexMap,
            final List<String> keywords) {
        for (final String keyword : keywords) {
            if (headerMap.containsKey(keyword.toLowerCase(Locale.ROOT))) {
                return headerIndexMap.get(keyword.toLowerCase(Locale.ROOT));
            }
        }
        return null;
    }

    /**
     * Extract account number from text using enhanced pattern matching Handles edge cases: null
     * text, empty text, very long text, malformed patterns
     */
    private String extractAccountNumberFromText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        // Limit text length to prevent regex DoS attacks on very long strings
        if (text.length() > 10_000) {
            LOGGER.warn(
                    "Text too long for account number extraction ({} chars), truncating to 10000",
                    text.length());
            text = text.substring(0, 10_000);
        }

        try {
            // Try enhanced account number pattern (covers all account types: credit, debit,
            // savings, investment, loan)
            final Matcher matcher = ACCOUNT_NUMBER_PATTERN.matcher(text);
            if (matcher.find()) {
                try {
                    String accountNum = matcher.group(1);
                    if (accountNum != null && !accountNum.isBlank()) {
                        // CRITICAL FIX: Remove masks, hyphens, spaces, and other separators
                        // Keep only digits for consistent storage
                        // Example: "8-41007" -> "841007", "****1234" -> "1234", "1234 5678" ->
                        // "12345678"
                        accountNum = accountNum.replaceAll("[*xX\\s-]", "");
                        if (accountNum.length() >= 4) {
                            // Extract last 4 digits (for security, we only store last 4)
                            final int startIndex = Math.max(0, accountNum.length() - 4);
                            // Ensure we don't go out of bounds
                            if (startIndex < accountNum.length()) {
                                final String lastFour = accountNum.substring(startIndex);
                                // Account number extracted successfully
                                return lastFour;
                            }
                        }
                    }
                } catch (IndexOutOfBoundsException | IllegalStateException e) {
                    LOGGER.warn(
                            "Error extracting account number from pattern match: {}",
                            e.getMessage());
                }
            }

            // Fallback: Try card number pattern (for credit/debit cards specifically)
            // This handles cases where card-specific patterns weren't caught by the general pattern
            final Matcher cardMatcher = CARD_NUMBER_PATTERN.matcher(text);
            if (cardMatcher.find()) {
                try {
                    String cardNum = cardMatcher.group(1);
                    if (cardNum != null && !cardNum.isBlank()) {
                        // Remove masks, hyphens, spaces
                        cardNum = cardNum.replaceAll("[*xX\\s-]", "");
                        if (cardNum.length() >= 4) {
                            // Extract last 4 digits
                            final int startIndex = Math.max(0, cardNum.length() - 4);
                            if (startIndex < cardNum.length()) {
                                final String lastFour = cardNum.substring(startIndex);
                                // Card number extracted successfully
                                return lastFour;
                            }
                        }
                    }
                } catch (IndexOutOfBoundsException | IllegalStateException e) {
                    LOGGER.warn(
                            "Error extracting card number from pattern match: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Error extracting account number from text: {}", e.getMessage());
        }

        return null;
    }

    /** Extract account type from text */
    private String extractAccountTypeFromText(final String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        final String lower = text.toLowerCase(Locale.ROOT);

        // Check for account type patterns
        for (final Map.Entry<String, String> entry : ACCOUNT_TYPE_PATTERNS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * Match detected account to existing user account Returns matched account ID or null if no
     * match found
     */
    public String matchToExistingAccount(final String userId, final DetectedAccount detected) {
        if (userId == null || detected == null) {
            return null;
        }

        // Try to match by account number and institution
        if (detected.getAccountNumber() != null && detected.getInstitutionName() != null) {
            try {
                final Optional<AccountTable> accountOpt =
                        accountRepository.findByAccountNumberAndInstitution(
                                detected.getAccountNumber(), detected.getInstitutionName(), userId);
                if (accountOpt.isPresent()) {
                    LOGGER.info(
                            "Matched detected account to existing account: {} (accountId: {})",
                            detected.getAccountName(),
                            accountOpt.get().getAccountId());
                    return accountOpt.get().getAccountId();
                }
            } catch (Exception e) {
                LOGGER.warn("Error matching account by number and institution: {}", e.getMessage());
                // Continue to next matching strategy
            }
        }

        // Try to match by account number only
        // CRITICAL FIX: Normalize account numbers before comparison (handles hyphens, spaces, etc.)
        // Only try if account number is not null AND not empty
        if (detected.getAccountNumber() != null && !detected.getAccountNumber().isBlank()) {
            try {
                // Normalize detected account number
                final String normalizedDetected =
                        normalizeAccountNumberForMatching(detected.getAccountNumber());

                // Try exact match first (repository method)
                final Optional<AccountTable> accountOpt =
                        accountRepository.findByAccountNumber(detected.getAccountNumber(), userId);
                if (accountOpt.isPresent()) {
                    LOGGER.info(
                            "Matched detected account by number only (exact match): {} (accountId: {})",
                            detected.getAccountName(),
                            accountOpt.get().getAccountId());
                    return accountOpt.get().getAccountId();
                }

                // Fallback: Try normalized match (in case stored account number is in different
                // format)
                if (!normalizedDetected.isEmpty()) {
                    final List<AccountTable> userAccounts = accountRepository.findByUserId(userId);
                    for (final AccountTable account : userAccounts) {
                        if (account.getAccountNumber() != null) {
                            final String normalizedExisting =
                                    normalizeAccountNumberForMatching(account.getAccountNumber());
                            if (normalizedDetected.equals(normalizedExisting)) {
                                LOGGER.info(
                                        "Matched detected account by number only (normalized match): {} (accountId: {})",
                                        detected.getAccountName(),
                                        account.getAccountId());
                                return account.getAccountId();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Error matching account by number only: {}", e.getMessage());
                // Continue to next matching strategy
            }
        }

        // Try to match by institution name and account type
        if (detected.getInstitutionName() != null && detected.getAccountType() != null) {
            try {
                final List<AccountTable> userAccounts = accountRepository.findByUserId(userId);
                for (final AccountTable account : userAccounts) {
                    // CRITICAL FIX: Handle null institution name in account
                    final String accountInstitutionName = account.getInstitutionName();
                    if (detected.getInstitutionName()
                                    .equalsIgnoreCase(accountInstitutionName)
                            && detected.getAccountType().equals(account.getAccountType())) {
                        // If we have account number (not null and not empty), prefer exact match
                        // (with normalization)
                        // CRITICAL FIX: Normalize account numbers before comparison
                        // If account number is null or empty, match by institution and type only
                        final String detectedAccountNumber = detected.getAccountNumber();
                        boolean accountNumberMatches =
                                true; // Default to true if no account number to match

                        if (detectedAccountNumber != null
                                && !detectedAccountNumber.isBlank()) {
                            // We have an account number - must match
                            final String normalizedDetected =
                                    normalizeAccountNumberForMatching(detectedAccountNumber);
                            final String normalizedExisting =
                                    account.getAccountNumber() != null
                                            ? normalizeAccountNumberForMatching(
                                            account.getAccountNumber())
                                            : "";
                            accountNumberMatches = normalizedDetected.equals(normalizedExisting);
                        } else {
                            // No account number in detected account - match by institution and type
                            // only
                            accountNumberMatches = true;
                        }

                        if (accountNumberMatches) {
                            LOGGER.info(
                                    "Matched detected account by institution and type: {} (accountId: {})",
                                    detected.getAccountName(),
                                    account.getAccountId());
                            return account.getAccountId();
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Error matching account by institution and type: {}", e.getMessage());
                // Return null if all matching strategies fail
            }
        }

        LOGGER.info(
                "No existing account match found for detected account: {}",
                detected.getAccountName() != null ? detected.getAccountName() : "Unknown");
        return null;
    }

    // Helper methods

    /**
     * Normalize account number for matching - remove hyphens, spaces, and other separators, extract
     * last 4 digits CRITICAL: This ensures consistent comparison regardless of format (e.g.,
     * "8-41007" vs "841007" vs "8 41007")
     *
     * @param accountNumber Account number in any format (may contain hyphens, spaces, etc.)
     * @return Normalized account number (last 4 digits only, digits only)
     */
    private String normalizeAccountNumberForMatching(final String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return "";
        }

        // Remove all non-digit characters (hyphens, spaces, masks, etc.)
        final String digitsOnly = accountNumber.replaceAll("[^0-9]", "");

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
        final String normalized = filename.toLowerCase(Locale.ROOT).replaceAll("[_\\-]", " ");
        final String lower = normalized;

        for (final String keyword : INSTITUTION_KEYWORDS) {
            if (keyword != null) {
                // Check if normalized filename contains the keyword (with spaces normalized)
                final String normalizedKeyword = keyword.toLowerCase(Locale.ROOT).replaceAll("[_\\-]", " ");
                if (lower.contains(normalizedKeyword)) {
                    // Normalize institution name
                    return normalizeInstitutionName(keyword);
                }
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
        for (final String keyword : INSTITUTION_KEYWORDS) {
            final String pattern = "\\b" + Pattern.quote(keyword) + "\\b";
            INSTITUTION_PATTERN_CACHE.put(
                    keyword, Pattern.compile(pattern, Pattern.CASE_INSENSITIVE));
        }
    }

    /** Match structure to track all match information for scoring */
    private static final class InstitutionMatch {
        String normalizedName;
        double totalScore = 0.0;
        int headerFrequency = 0;
        int transactionFrequency = 0;
        boolean hasWebsiteMatch = false;
        int keywordSpecificity = 0; // 0=abbreviation, 1=partial, 2=full name
    }

    /**
     * Enhanced institution name extraction with: 1. Context-aware section prioritization (header vs
     * transaction) 2. Website pattern matching (www.<institution>.com) 3. Frequency-based ranking
     * (more occurrences = higher confidence)
     *
     * <p>Uses whole word matching to avoid false positives from substrings in transaction table
     * headers (e.g., "ing" in "posting", "description")
     */
    private String extractInstitutionFromTextStrict(final String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        // 1. Split text into header and transaction sections
        final String[] sections = splitIntoHeaderAndTransactionSections(text);
        final String headerSection = sections[0];
        final String transactionSection = sections[1];

        // 2. Find all institution matches with scoring
        final Map<String, InstitutionMatch> matches = new HashMap<>();

        // Search header section (higher priority)
        findInstitutionMatches(headerSection, matches, 1.0, true);

        // Search transaction section (lower priority)
        findInstitutionMatches(transactionSection, matches, 0.3, false);

        // 3. Select best match based on combined score
        return selectBestMatch(matches);
    }

    /**
     * Split text into header and transaction sections Header section: lines before transaction
     * table starts Transaction section: lines after transaction table headers
     */
    private String[] splitIntoHeaderAndTransactionSections(final String text) {
        if (text == null || text.isEmpty()) {
            return new String[] {"", ""};
        }

        final String[] lines = text.split("\n");
        final StringBuilder headerSection = new StringBuilder();
        final StringBuilder transactionSection = new StringBuilder();

        boolean inTransactionSection = false;

        // Look for transaction table indicators
        final List<String> transactionKeywords =
                Arrays.asList(
                        "date",
                        "posting date",
                        "transaction date",
                        "value date",
                        AMOUNT,
                        DEBIT,
                        CREDIT,
                        BALANCE,
                        DESCRIPTION,
                        DETAILS,
                        "memo",
                        "notes");

        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            if (line == null) {
                continue;
            }

            final String lowerLine = line.toLowerCase(Locale.ROOT).trim();

            // Check if this line looks like transaction table headers
            if (!inTransactionSection) {
                int transactionColumnCount = 0;
                for (final String keyword : transactionKeywords) {
                    if (lowerLine.contains(keyword)
                            && (lowerLine.equals(keyword)
                                    || lowerLine.startsWith(keyword + " ")
                                    || lowerLine.endsWith(" " + keyword)
                                    || lowerLine.contains(" " + keyword + " "))) {
                        transactionColumnCount++;
                    }
                }

                // If we find 2+ transaction-related columns, this is likely the transaction table
                // start
                if (transactionColumnCount >= 2) {
                    inTransactionSection = true;
                }
            }

            if (inTransactionSection) {
                transactionSection.append(line).append('\n');
            } else {
                headerSection.append(line).append('\n');
            }
        }

        return new String[] {headerSection.toString().trim(), transactionSection.toString().trim()};
    }

    /** Find institution matches in a section with scoring */
    private void findInstitutionMatches(
            final String section,
            final Map<String, InstitutionMatch> matches,
            final double baseScore,
            final boolean isHeader) {
        if (section == null || section.isEmpty()) {
            return;
        }

        final String lower = section.toLowerCase(Locale.ROOT);

        for (final String keyword : INSTITUTION_KEYWORDS) {
            final String normalized = normalizeInstitutionName(keyword);
            final InstitutionMatch match =
                    matches.computeIfAbsent(
                            normalized,
                            k -> {
                                final InstitutionMatch m = new InstitutionMatch();
                                m.normalizedName = normalized;
                                return m;
                            });

            // 1. Count frequency of institution name matches
            final Pattern namePattern =
                    INSTITUTION_PATTERN_CACHE.computeIfAbsent(
                            keyword,
                            k -> {
                                final String patternStr = "\\b" + Pattern.quote(k) + "\\b";
                                return Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
                            });

            final int frequency = countMatches(namePattern, lower);
            if (frequency > 0) {
                if (isHeader) {
                    match.headerFrequency += frequency;
                } else {
                    match.transactionFrequency += frequency;
                }

                // Add to score: base score * frequency (with diminishing returns using log1p)
                final double frequencyScore = baseScore * Math.log1p(frequency);
                match.totalScore += frequencyScore;
            }

            // 2. Check for website pattern match (www.<institution>.com)
            final String websitePattern = generateWebsitePattern(keyword);
            final Pattern websiteRegex =
                    WEBSITE_PATTERN_CACHE.computeIfAbsent(
                            keyword,
                            k -> {
                                return Pattern.compile(websitePattern, Pattern.CASE_INSENSITIVE);
                            });

            if (websiteRegex.matcher(lower).find()) {
                match.hasWebsiteMatch = true;
                // Website match is a strong signal - add significant bonus
                final double websiteBonus = isHeader ? 2.0 : 0.5; // Higher bonus in header
                match.totalScore += websiteBonus;
            }

            // 3. Keyword specificity scoring (update if this keyword is more specific)
            final int specificity = calculateKeywordSpecificity(keyword, normalized);
            if (match.keywordSpecificity < specificity) {
                match.keywordSpecificity = specificity;
            }
        }
    }

    /**
     * Generate website pattern from institution keyword Examples: "american express" ->
     * "www\\.americanexpress\\.com" "chase" -> "www\\.chase\\.com" "bank of america" ->
     * "www\\.bankofamerica\\.com"
     */
    private String generateWebsitePattern(final String keyword) {
        // Remove spaces, special characters, and convert to URL format
        final String urlName =
                keyword.toLowerCase(Locale.ROOT)
                        .replaceAll("\\s+", "") // Remove spaces
                        .replaceAll("&", "and") // Replace & with and
                        .replaceAll("[^a-z0-9]", ""); // Remove other special characters

        // Match variations: www.chase.com, chase.com, www.chase.net, etc.
        // Support common TLDs: com, net, org, and country-specific ones
        return "(?:www\\.)?"
                + Pattern.quote(urlName)
                + "\\.(?:com|net|org|co\\.uk|co\\.jp|co\\.kr|co\\.za|com\\.au|com\\.sg|com\\.my|com\\.in)";
    }

    /**
     * Calculate keyword specificity (full name > partial > abbreviation) Returns: 2 for full names,
     * 1 for partial, 0 for abbreviations
     */
    private int calculateKeywordSpecificity(final String keyword, final String normalized) {
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

    /** Count number of matches for a pattern in text */
    private int countMatches(final Pattern pattern, final String text) {
        int count = 0;
        final Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * Select best match based on combined scoring Ranking priority: 1. Total score (highest) 2.
     * Header frequency (more occurrences in header = better) 3. Transaction frequency (fewer in
     * transactions = better, but less weight) 4. Keyword specificity (full name > partial >
     * abbreviation) 5. Website match (has website = better)
     */
    private String selectBestMatch(final Map<String, InstitutionMatch> matches) {
        if (matches.isEmpty()) {
            return null;
        }

        // Add specificity bonus to total score for final ranking
        for (final InstitutionMatch match : matches.values()) {
            match.totalScore += match.keywordSpecificity * 0.2; // Small bonus for specificity
        }

        final InstitutionMatch best =
                matches.values().stream()
                        .max(
                                Comparator.comparingDouble((InstitutionMatch m) -> m.totalScore)
                                        .thenComparingInt((InstitutionMatch m) -> m.headerFrequency)
                                        .thenComparing(
                                                (InstitutionMatch m) ->
                                                        -m.transactionFrequency) // Negative: fewer
                                        // in transactions
                                        // = better
                                        .thenComparingInt(
                                                (InstitutionMatch m) -> m.keywordSpecificity)
                                        .thenComparing(
                                                (InstitutionMatch m) -> m.hasWebsiteMatch ? 1 : 0))
                        .orElse(null);

        if (best != null && best.totalScore > 0) {
            return best.normalizedName;
        }

        return null;
    }

    /**
     * Check if headers look like a transaction table (not account metadata) Transaction tables
     * typically have columns like: date, amount, description, balance, type
     */
    private boolean isTransactionTableHeadersInternal(final List<String> headers) {
        if (headers == null || headers.isEmpty()) {
            return false;
        }

        // Common transaction table column names
        final List<String> transactionKeywords =
                Arrays.asList(
                        "date",
                        "posting date",
                        "transaction date",
                        "value date",
                        AMOUNT,
                        DEBIT,
                        CREDIT,
                        BALANCE,
                        DESCRIPTION,
                        DETAILS,
                        "memo",
                        "notes",
                        "type",
                        "transaction type",
                        "category",
                        CHECK,
                        "check number",
                        "check or slip",
                        "reference",
                        "ref");

        int transactionColumnCount = 0;

        for (final String keyword : transactionKeywords) {
            // Check if keyword appears as a header (not just in text)
            for (final String header : headers) {
                if (header != null) {
                    final String headerLower = header.toLowerCase(Locale.ROOT).trim();
                    // Match whole word or exact header match
                    if (headerLower.equals(keyword)
                            || headerLower.contains(keyword)
                                    && (headerLower.startsWith(keyword + " ")
                                            || headerLower.endsWith(" " + keyword)
                                            || headerLower.contains(" " + keyword + " "))) {
                        transactionColumnCount++;
                        break; // Count each keyword only once
                    }
                }
            }
        }

        // If we find 3+ transaction-related columns, it's likely a transaction table
        final boolean isTransactionTable = transactionColumnCount >= 3;

        if (isTransactionTable) {
            LOGGER.info(
                    "⚠️ Detected transaction table headers (found {} transaction-related columns)",
                    transactionColumnCount);
        }

        return isTransactionTable;
    }

    private String normalizeInstitutionName(final String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return null;
        }

        // Normalize common variations
        final Map<String, String> normalizations = new HashMap<>();
        normalizations.put("bofa", "Bank of America");
        normalizations.put("bank of america", "Bank of America");
        normalizations.put("wf", "Wells Fargo");
        normalizations.put("wells fargo", "Wells Fargo");
        normalizations.put("usbank", "U.S. Bank");
        normalizations.put("us bank", "U.S. Bank");
        normalizations.put("capone", CAPITAL_ONE);
        normalizations.put("capitol one", CAPITAL_ONE);
        normalizations.put("capital one", CAPITAL_ONE);
        normalizations.put("jpm", "JPMorgan Chase");
        normalizations.put("jpmorgan", "JPMorgan Chase");
        normalizations.put("amex", "American Express");
        normalizations.put(AMERICAN_EXPRESS, "American Express");
        normalizations.put("chase", "Chase");
        normalizations.put("citi", CITIBANK);
        normalizations.put("citibank", CITIBANK);
        normalizations.put("citicards", CITIBANK);
        normalizations.put("east west bank", "East West Bank");
        normalizations.put("eastwest bank", "East West Bank");

        final String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
        if (normalizations.containsKey(lowerKeyword)) {
            return normalizations.get(lowerKeyword);
        }

        // Safely capitalize first letter
        if (keyword.length() > 0) {
            return keyword.substring(0, 1).toUpperCase(Locale.ROOT)
                    + (keyword.length() > 1 ? keyword.substring(1) : "");
        }
        return keyword;
    }

    private String detectAccountTypeFromFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return null;
        }

        // CRITICAL: Limit filename length to prevent performance issues
        if (filename.length() > 1000) {
            LOGGER.debug(
                    "Filename too long ({} chars), truncating for account type detection",
                    filename.length());
            filename = filename.substring(0, 1000);
        }

        // CRITICAL: Normalize underscores and hyphens to spaces for better matching
        final String normalized = filename.toLowerCase(Locale.ROOT).replaceAll("[_\\-]", " ");
        final String lower = normalized;

        // CRITICAL: Prioritize credit card patterns over checking/savings patterns
        // Check credit card patterns first (more specific)
        final String[] creditCardPatterns = {CREDIT_CARD, "creditcard", "card"};
        for (final String pattern : creditCardPatterns) {
            if (lower.contains(pattern)) {
                return "credit";
            }
        }

        // Then check other patterns
        for (final Map.Entry<String, String> entry : ACCOUNT_TYPE_PATTERNS.entrySet()) {
            if (entry.getKey() != null) {
                // Normalize pattern key for matching
                final String normalizedKey = entry.getKey().toLowerCase(Locale.ROOT).replaceAll("[_\\-]", " ");
                if (lower.contains(normalizedKey)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private String generateAccountName(
            final String institution, final String type, final String subtype, final String accountNumber) {
        final StringBuilder name = new StringBuilder();

        // CRITICAL FIX: Handle null/empty values safely
        if (institution != null && !institution.isEmpty()) {
            name.append(institution);
        }

        if (subtype != null && !subtype.isEmpty()) {
            if (name.length() > 0) {
                name.append(' ');
            }
            name.append(subtype);
        } else if (type != null && !type.isEmpty()) {
            if (name.length() > 0) {
                name.append(' ');
            }
            name.append(type);
        }

        if (accountNumber != null && !accountNumber.isEmpty()) {
            if (name.length() > 0) {
                name.append(' ');
            }
            name.append(accountNumber);
        }

        final String result = name.toString().trim();
        // CRITICAL FIX: Ensure we always return a non-empty name
        return result.isEmpty() ? "Unknown Account" : result;
    }

    /**
     * Detect credit card from PDF content using general patterns Works for ALL credit card issuers,
     * not just specific ones
     */
    private boolean detectCreditCardFromContent(final String lowerHeader) {
        // General credit card indicators that work for all issuers
        final List<String> creditCardIndicators =
                Arrays.asList(
                        CREDIT_CARD,
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
                        "annual percentage rate");

        // Check for any credit card indicator
        for (final String indicator : creditCardIndicators) {
            if (lowerHeader.contains(indicator)) {
                return true;
            }
        }

        // Also check for card product patterns (institution + card keywords)
        // This works for any institution in our keyword list
        boolean hasInstitution = false;
        for (final String institution : INSTITUTION_KEYWORDS) {
            if (lowerHeader.contains(institution.toLowerCase(Locale.ROOT))) {
                hasInstitution = true;
                break;
            }
        }

        // Common card product keywords (works for all issuers globally)
        final List<String> cardProductKeywords =
                Arrays.asList(
                        "card",
                        REWARDS,
                        PLATINUM,
                        "gold",
                        SILVER,
                        PREFERRED,
                        SIGNATURE,
                        WORLD,
                        ELITE,
                        INFINITE,
                        RESERVE,
                        FREEDOM,
                        SAPPHIRE,
                        DOUBLE_CASH,
                        CASH_BACK,
                        MILES,
                        POINTS,
                        // US Card Products
                        VENTURE,
                        SAVOR,
                        QUICKSILVER,
                        SPARK,
                        FREEDOM_UNLIMITED,
                        UNLIMITED,
                        BLUE_CASH,
                        EVERYDAY,
                        DELTA,
                        MARRIOTT,
                        HILTON,
                        HYATT,
                        "ihg",
                        "aeroplan",
                        "avios",
                        "skywards",
                        // European Card Products
                        CLASSIC,
                        PREMIUM,
                        BLACK,
                        "titanium",
                        "carbon",
                        "metal",
                        // Asian Card Products
                        DIAMOND,
                        IMPERIAL,
                        ROYAL,
                        PRESTIGE,
                        EXCLUSIVE,
                        // General Terms
                        CREDIT,
                        DEBIT,
                        "charge",
                        "prepaid",
                        "gift",
                        TRAVEL,
                        BUSINESS);

        if (hasInstitution) {
            for (final String keyword : cardProductKeywords) {
                if (lowerHeader.contains(keyword)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Extract header text from PDF, including Service Agreement section if found For Chase cards,
     * the card name is often in the Service Agreement section (~line 100) This method searches for
     * specific sections and combines header + section text By default extends to 150 lines, and if
     * no section found, extends to 200 lines
     */
    private String extractHeaderTextWithServiceAgreement(final String pdfText) {
        if (pdfText == null || pdfText.isEmpty()) {
            return "";
        }

        final String[] lines = pdfText.split("\n");
        if (lines.length == 0) {
            return pdfText.length() > 1000 ? pdfText.substring(0, 1000) : pdfText;
        }

        final StringBuilder headerBuilder = new StringBuilder();

        // 1. By default include first 300 lines (account info, statement header, and deeper
        // content)
        // Increased from 150 to 300 to better detect account holder names in longer headers
        final int defaultLines = Math.min(300, lines.length);
        headerBuilder.append(String.join("\n", Arrays.copyOf(lines, defaultLines)));

        // 2. Search for Service Agreement or Cardholder Agreement section
        final List<String> sectionKeywords =
                Arrays.asList(
                        "service agreement",
                        "cardholder agreement",
                        CARDMEMBER_AGREEMENT,
                        "terms and conditions",
                        "card agreement",
                        "account agreement");

        int sectionStartLine = -1;
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i].toLowerCase(Locale.ROOT).trim();
            for (final String keyword : sectionKeywords) {
                if (line.contains(keyword)) {
                    sectionStartLine = i;
                    LOGGER.debug("Found section '{}' at line {}", keyword, i + 1);
                    break;
                }
            }
            if (sectionStartLine >= 0) {
                break;
            }
        }

        // 3. If section found, extract text around it (50 lines before to 150 lines after)
        if (sectionStartLine >= 0) {
            final int sectionStart = Math.max(0, sectionStartLine - 50); // 50 lines before section
            final int sectionEnd =
                    Math.min(lines.length, sectionStartLine + 150); // 150 lines after section start

            // Only add if it's beyond the default 300 lines we already have
            if (sectionStart > defaultLines) {
                headerBuilder.append("\n\n=== SERVICE AGREEMENT SECTION ===\n");
                headerBuilder.append(
                        String.join("\n", Arrays.copyOfRange(lines, sectionStart, sectionEnd)));
            } else if (sectionEnd > defaultLines) {
                // Section overlaps with default lines, just extend
                headerBuilder.append("\n\n=== SERVICE AGREEMENT SECTION (extended) ===\n");
                headerBuilder.append(
                        String.join("\n", Arrays.copyOfRange(lines, defaultLines, sectionEnd)));
            }
        } else {
            // No section found - default 300 lines should be sufficient now
            // (Previously extended to 200, but now default is 300 so this path is less likely)
            if (lines.length > defaultLines) {
                LOGGER.debug(
                        "No Service Agreement section found, using default {} lines", defaultLines);
            }
        }

        final String headerText = headerBuilder.toString();

        // Log header text extraction summary
        LOGGER.info(
                "Extracted header text: {} lines from default section, {} total characters",
                defaultLines,
                headerText.length());

        return headerText;
    }

    /**
     * Extract product/card name from PDF content General approach that works for ALL credit card
     * issuers and product names Uses institution keywords list instead of hardcoded values
     */
    private String extractProductNameFromPDF(final String headerText) {
        if (headerText == null || headerText.isEmpty()) {
            return null;
        }

        // Pattern 0a: Extract Prime Visa from "YOUR PRIME VISA POINTS" or "Prime Visa" patterns
        // High priority - very specific pattern for Amazon Prime Visa cards
        final Pattern primeVisaPattern =
                Pattern.compile(
                        "(?i)(?:your\\s+)?(prime\\s+visa|amazon\\s+prime\\s+visa|prime\\s+rewards\\s+visa|prime\\s+visa\\s+signature)(?:\\s+points|\\s+card|\\s*®|\\s*™)?",
                        Pattern.CASE_INSENSITIVE);
        final Matcher primeVisaMatcher = primeVisaPattern.matcher(headerText);
        if (primeVisaMatcher.find()) {
            String cardName = primeVisaMatcher.group(1).trim();
            cardName = cardName.replaceAll("\\s+", " "); // Normalize whitespace
            // Capitalize properly: "Prime Visa" or "Amazon Prime Visa"
            cardName = capitalizeCardName(cardName);
            if (cardName.length() > 3 && cardName.length() < 100) {
                LOGGER.info("Extracted product name from 'Prime Visa' pattern: {}", cardName);
                return cardName;
            }
        }

        // Pattern 0b: Extract card name from "thank you for using your [card name]" phrases
        // Example: "thank you for using your marriott bonvoy® premier credit card"
        final Pattern thankYouPattern =
                Pattern.compile(
                        "(?i)thank\\s+you\\s+for\\s+using\\s+your\\s+([a-z0-9\\s®™©]+?)\\s*(?:credit\\s*)?card",
                        Pattern.CASE_INSENSITIVE);
        final Matcher thankYouMatcher = thankYouPattern.matcher(headerText);
        if (thankYouMatcher.find()) {
            String cardName = thankYouMatcher.group(1).trim();
            cardName = cardName.replaceAll("\\s+", " "); // Normalize whitespace
            if (cardName.length() > 3 && cardName.length() < 100) {
                LOGGER.info("Extracted product name from 'thank you' pattern: {}", cardName);
                return cardName;
            }
        }

        // Pattern 0c: Extract from "Reward your routine everywhere you shop with your [card name]"
        // phrases
        // Example: "Reward your routine everywhere you shop with your Prime Visa."
        final Pattern rewardPattern =
                Pattern.compile(
                        "(?i)reward\\s+(?:your\\s+)?(?:routine|everywhere|.*?)\\s+(?:with\\s+your\\s+)?([a-z0-9\\s®™©]+?)\\s*(?:card|\\s*®|\\s*™|\\s*\\.)?",
                        Pattern.CASE_INSENSITIVE);
        final Matcher rewardMatcher = rewardPattern.matcher(headerText);
        if (rewardMatcher.find()) {
            String cardName = rewardMatcher.group(1).trim();
            cardName = cardName.replaceAll("\\s+", " "); // Normalize whitespace
            // Check if it's a valid card name (contains card product keywords)
            if (isValidCardProductName(cardName)) {
                cardName = capitalizeCardName(cardName);
                if (cardName.length() > 3 && cardName.length() < 100) {
                    LOGGER.info("Extracted product name from 'reward' pattern: {}", cardName);
                    return cardName;
                }
            }
        }

        // Pattern 1: Look for lines containing institution keyword + product name + "card"
        // This works for any institution in our keyword list
        // CRITICAL: Process lines and collect potential matches, then prioritize by specificity
        final String[] lines = headerText.split("\n");
        // Store candidates with their matched indicators for better prioritization
        final List<Map.Entry<String, String>> candidateProductNames =
                new ArrayList<>(); // candidate -> matchedIndicator

        for (final String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }

            final String trimmedLine = line.trim();
            final String lowerLine = trimmedLine.toLowerCase(Locale.ROOT);

            // Check if line contains any institution keyword
            String foundInstitution = null;
            for (final String institution : INSTITUTION_KEYWORDS) {
                final String lowerInstitution = institution.toLowerCase(Locale.ROOT);
                // Use word boundaries to avoid false matches
                final Pattern institutionPattern =
                        Pattern.compile(
                                "\\b" + Pattern.quote(lowerInstitution) + "\\b",
                                Pattern.CASE_INSENSITIVE);
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
                final List<String> specificProductIndicators =
                        Arrays.asList(
                                // Multi-word card names (most specific first)
                                // Amazon/Prime Cards
                                PRIME_VISA,
                                AMAZON_PRIME_VISA,
                                AMAZON_PRIME_CARD,
                                PRIME_REWARDS_VISA,
                                PRIME_VISA_SIGNATURE,
                                AMAZON_PRIME_REWARDS,
                                "prime card",
                                // Chase Cards
                                MARRIOTT_BONVOY_PREMIER,
                                MARRIOTT_BONVOY,
                                DOUBLE_CASH,
                                CASH_BACK,
                                ACTIVE_CASH,
                                BLUE_CASH,
                                FREEDOM_ULTIMATE,
                                FREEDOM_UNLIMITED,
                                FREEDOM,
                                SAPPHIRE_RESERVE,
                                SAPPHIRE_PREFERRED,
                                SAPPHIRE,
                                // Visa/Mastercard/Amex Tier Cards
                                VISA_SIGNATURE,
                                VISA_INFINITE,
                                VISA_PLATINUM,
                                "visa classic",
                                MASTERCARD_WORLD,
                                MASTERCARD_WORLD_ELITE,
                                "mastercard platinum",
                                "mastercard gold",
                                AMEX_PLATINUM,
                                AMEX_GOLD,
                                "amex green",
                                "amex blue",
                                // Capital One Cards
                                QUICKSILVER,
                                VENTURE,
                                SAVOR,
                                SPARK,
                                // Citi Cards
                                DOUBLE_CASH,
                                PREMIER,
                                "diamond preferred",
                                // Discover Cards
                                "it",
                                MILES,
                                CASH_BACK,
                                // Single-word and other specific indicators
                                UNLIMITED,
                                EVERYDAY,
                                MILES,
                                POINTS,
                                HILTON,
                                HYATT,
                                DELTA,
                                MARRIOTT,
                                BONVOY,
                                PLATINUM,
                                "gold",
                                SILVER,
                                "titanium",
                                SIGNATURE,
                                WORLD,
                                ELITE,
                                INFINITE,
                                RESERVE,
                                PREFERRED,
                                "ultimate",
                                CLASSIC,
                                PREMIUM,
                                BLACK,
                                DIAMOND,
                                IMPERIAL,
                                ROYAL,
                                PRESTIGE,
                                EXCLUSIVE,
                                TRAVEL,
                                BUSINESS,
                                REWARDS,
                                "card",
                                "®",
                                "™");

                // Check for specific product indicators first (higher priority)
                // Use longest match first to prefer "marriott bonvoy premier" over just "marriott"
                String matchedProductIndicator = null;
                int longestMatchLength = 0;
                for (final String indicator : specificProductIndicators) {
                    final String lowerIndicator = indicator.toLowerCase(Locale.ROOT);
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
                final List<String> genericTermsToSkip =
                        Arrays.asList(
                                "mobile app",
                                "mobile",
                                "app",
                                "website",
                                "statement",
                                ACCOUNT,
                                "login",
                                "register",
                                "contact",
                                "support",
                                "help");

                // Additional check: skip lines that contain URLs (likely not card names)
                // Pattern matches: www., http://, https://, or domain extensions like .com, .org,
                // etc.
                final boolean containsUrl =
                        lowerLine.matches(".*\\b(?:www\\.|http://|https://|\\.[a-z]{2,})\\b.*");

                // Also check for common URL patterns like "chase.com", "wellsfargo.com"
                final boolean containsDomain =
                        lowerLine.matches(
                                ".*\\b(?:chase|wells\\s*fargo|bankofamerica|citibank|americanexpress|amex)\\s*\\.\\s*com\\b.*");

                // CRITICAL: Define strong product indicators BEFORE using them
                // Strong indicators are specific card names, not generic terms like "card"
                final List<String> strongProductIndicators =
                        Arrays.asList(
                                // Amazon/Prime Cards (highest priority - very specific)
                                PRIME_VISA,
                                AMAZON_PRIME_VISA,
                                AMAZON_PRIME_CARD,
                                PRIME_REWARDS_VISA,
                                PRIME_VISA_SIGNATURE,
                                AMAZON_PRIME_REWARDS,
                                // Chase Cards
                                FREEDOM_ULTIMATE,
                                FREEDOM_UNLIMITED,
                                FREEDOM,
                                SAPPHIRE_RESERVE,
                                SAPPHIRE_PREFERRED,
                                SAPPHIRE,
                                ACTIVE_CASH,
                                DOUBLE_CASH,
                                CASH_BACK,
                                BLUE_CASH,
                                MARRIOTT_BONVOY_PREMIER,
                                MARRIOTT_BONVOY,
                                // Visa/Mastercard/Amex Tier Cards
                                VISA_SIGNATURE,
                                VISA_INFINITE,
                                VISA_PLATINUM,
                                MASTERCARD_WORLD,
                                MASTERCARD_WORLD_ELITE,
                                AMEX_PLATINUM,
                                AMEX_GOLD,
                                // Other specific cards
                                QUICKSILVER,
                                SPARK,
                                VENTURE,
                                SAVOR);

                boolean hasGenericTerm = false;
                // CRITICAL: More aggressive filtering for generic terms
                // If line contains "mobile app" or similar terms, it's almost certainly not a card
                // name
                // unless it has a very strong product indicator
                for (final String term : genericTermsToSkip) {
                    final String lowerTerm = term.toLowerCase(Locale.ROOT);
                    if (lowerLine.contains(lowerTerm)) {
                        // If we have a strong product indicator, allow it
                        // Otherwise, reject the line
                        if (matchedProductIndicator == null) {
                            hasGenericTerm = true;
                            break;
                        } else {
                            // Check if the matched indicator is strong enough to override generic
                            // term
                            boolean isStrongEnough = false;
                            for (final String strongIndicator : strongProductIndicators) {
                                if (matchedProductIndicator
                                        .toLowerCase(Locale.ROOT)
                                        .contains(strongIndicator.toLowerCase(Locale.ROOT))) {
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
                    for (final String strongIndicator : strongProductIndicators) {
                        if (matchedProductIndicator
                                .toLowerCase(Locale.ROOT)
                                .contains(strongIndicator.toLowerCase(Locale.ROOT))) {
                            hasStrongIndicator = true;
                            break;
                        }
                    }
                }

                // Skip lines with URLs/domains unless they have a strong product indicator
                if ((containsUrl || containsDomain) && !hasStrongIndicator) {
                    LOGGER.info(
                            "Skipping line with URL/domain and no strong product indicator: {}",
                            trimmedLine);
                    continue;
                }

                // Also skip lines with action phrases that indicate website/branch instructions
                final List<String> actionPhrases =
                        Arrays.asList(
                                "activate for free",
                                "visit a",
                                "visit",
                                "go to",
                                "log in",
                                "sign in",
                                "register",
                                "enroll",
                                "call",
                                CONTACT_US,
                                "customer service");
                boolean hasActionPhrase = false;
                for (final String phrase : actionPhrases) {
                    if (lowerLine.contains(phrase.toLowerCase(Locale.ROOT))) {
                        hasActionPhrase = true;
                        break;
                    }
                }
                // Always skip lines with action phrases + URLs/domains (even if they have
                // indicators)
                if (hasActionPhrase && (containsUrl || containsDomain)) {
                    LOGGER.info("Skipping line with action phrase and URL/domain: {}", trimmedLine);
                    continue;
                }

                // CRITICAL: Blacklist patterns for contact information, addresses, and
                // non-product-name content
                final List<String> blacklistPatterns =
                        Arrays.asList(
                                // Contact information patterns
                                "write us at",
                                "write us",
                                "questions",
                                "question",
                                "if you have",
                                CONTACT_US,
                                "call us",
                                "email us",
                                "mail us",
                                "send us",
                                "customer service",
                                "customer support",
                                "technical support",
                                // Address patterns
                                "p\\.o\\. box",
                                "po box",
                                "p.o. box",
                                "post office box",
                                "street",
                                "avenue",
                                "road",
                                "boulevard",
                                "drive",
                                "lane",
                                "suite",
                                "apt",
                                "apartment",
                                "unit",
                                // ZIP code patterns (5 digits or ZIP+4)
                                "\\d{5}(?:-\\d{4})?",
                                "\\d{5}\\s+\\d{4}",
                                // State abbreviations followed by ZIP (e.g., "TX 79998")
                                "\\b[A-Z]{2}\\s+\\d{5}",
                                // City, State patterns
                                "el paso",
                                "san francisco",
                                "new york",
                                "los angeles",
                                // Phone number patterns
                                "\\d{1,3}[\\-.]?\\d{3}[\\-.]?\\d{3}[\\-.]?\\d{4}",
                                "\\(\\s*\\d{1,3}\\s*[\\-)]?\\s*\\d{3}",
                                // Email patterns
                                "@",
                                "\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b",
                                // Other non-product patterns
                                "electronic funds",
                                "funds services",
                                "services,",
                                "services.",
                                "you may also",
                                "you may",
                                "for more",
                                "for additional",
                                "please",
                                "thank you",
                                "sincerely",
                                "regards");

                boolean matchesBlacklist = false;
                for (final String blacklistPattern : blacklistPatterns) {
                    final Pattern pattern = Pattern.compile(blacklistPattern, Pattern.CASE_INSENSITIVE);
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
                    if (containsUrl
                            && !lowerLine.matches(".*(?:visa|mastercard|amex|discover).*")) {
                        hasGenericTerm = true;
                    }
                } else if (lowerLine.contains("online") && matchedProductIndicator == null) {
                    hasGenericTerm = true;
                }

                // If line has institution + specific product indicator, and is reasonably short
                // (likely a product name)
                if (matchedProductIndicator != null
                        && !hasGenericTerm
                        && trimmedLine.length() < 150) {
                    String productName = trimmedLine;

                    // For multi-word indicators like "marriott bonvoy premier", try to extract the
                    // full card name
                    // by finding the portion of the line that contains the matched indicator
                    if (matchedProductIndicator.contains(" ")) {
                        // Try to extract a more complete card name from the line
                        // Look for patterns like "Chase Marriott Bonvoy Premier Card" or "Marriott
                        // Bonvoy Premier"
                        final Pattern fullCardNamePattern =
                                Pattern.compile(
                                        "(?i)(?:chase\\s+)?(?:"
                                                + Pattern.quote(foundInstitution)
                                                + "\\s+)?([a-z0-9\\s®™©]*?"
                                                + Pattern.quote(matchedProductIndicator)
                                                + "[a-z0-9\\s®™©]*?)(?:\\s+card|\\s*®|\\s*™)?",
                                        Pattern.CASE_INSENSITIVE);
                        final Matcher fullCardMatcher = fullCardNamePattern.matcher(productName);
                        if (fullCardMatcher.find()) {
                            final String extractedName = fullCardMatcher.group(1).trim();
                            if (extractedName.length() > matchedProductIndicator.length()
                                    && extractedName.length() < 100) {
                                productName = extractedName;
                            }
                        }
                    }

                    // Clean up the product name
                    productName = productName.replaceAll("\\s+", " "); // Normalize whitespace

                    // CRITICAL: Validate product name before adding as candidate
                    if (!isValidProductName(productName)) {
                        LOGGER.info(
                                "Rejected candidate product name (validation failed): {}",
                                productName);
                        continue;
                    }

                    // Store candidate with its matched indicator for prioritization
                    candidateProductNames.add(
                            new java.util.AbstractMap.SimpleEntry<>(
                                    productName, matchedProductIndicator));
                    LOGGER.info(
                            "Found candidate product name: {} (matched indicator: {})",
                            productName,
                            matchedProductIndicator);
                }
            }
        }

        // Prioritize candidates: prefer specific card names over generic terms
        if (!candidateProductNames.isEmpty()) {
            // Sort by specificity: lines with specific card product names first
            // Order matters - most specific first
            final List<String> specificCardNames =
                    Arrays.asList(
                            // Amazon/Prime Cards (highest priority - very specific)
                            PRIME_VISA,
                            AMAZON_PRIME_VISA,
                            AMAZON_PRIME_CARD,
                            PRIME_REWARDS_VISA,
                            PRIME_VISA_SIGNATURE,
                            AMAZON_PRIME_REWARDS,
                            "prime card",
                            // Chase Cards
                            MARRIOTT_BONVOY_PREMIER,
                            MARRIOTT_BONVOY,
                            "bonvoy premier",
                            BONVOY,
                            FREEDOM_ULTIMATE,
                            FREEDOM_UNLIMITED,
                            FREEDOM,
                            ACTIVE_CASH,
                            SAPPHIRE_RESERVE,
                            SAPPHIRE_PREFERRED,
                            SAPPHIRE,
                            DOUBLE_CASH,
                            CASH_BACK,
                            BLUE_CASH,
                            // Visa/Mastercard/Amex Tier Cards
                            VISA_SIGNATURE,
                            VISA_INFINITE,
                            VISA_PLATINUM,
                            "visa classic",
                            MASTERCARD_WORLD_ELITE,
                            MASTERCARD_WORLD,
                            "mastercard platinum",
                            AMEX_PLATINUM,
                            AMEX_GOLD,
                            "amex green",
                            // Capital One Cards
                            QUICKSILVER,
                            SPARK,
                            VENTURE,
                            SAVOR,
                            // Citi Cards
                            DOUBLE_CASH,
                            PREMIER,
                            "diamond preferred",
                            // Discover Cards
                            "it",
                            MILES,
                            CASH_BACK,
                            // Other specific cards
                            UNLIMITED,
                            EVERYDAY,
                            PLATINUM,
                            "gold",
                            SILVER,
                            SIGNATURE,
                            WORLD,
                            ELITE,
                            INFINITE,
                            "ultimate",
                            HILTON,
                            HYATT,
                            DELTA,
                            MARRIOTT);

            // First pass: prioritize by matched indicator (most specific first)
            for (final String cardName : specificCardNames) {
                for (final Map.Entry<String, String> entry : candidateProductNames) {
                    final String candidate = entry.getKey();
                    final String matchedIndicator = entry.getValue();
                    final String lowerCandidate = candidate.toLowerCase(Locale.ROOT);
                    final String lowerMatchedIndicator =
                            matchedIndicator != null ? matchedIndicator.toLowerCase(Locale.ROOT) : "";

                    // Check if the matched indicator or candidate contains the specific card name
                    if (lowerMatchedIndicator.contains(cardName.toLowerCase(Locale.ROOT))
                            || lowerCandidate.contains(cardName.toLowerCase(Locale.ROOT))) {
                        LOGGER.info(
                                "Extracted product name (prioritized specific card name '{}'): {}",
                                cardName,
                                candidate);
                        return candidate;
                    }
                }
            }

            // Second pass: if no match by indicator, check candidate text only
            for (final String cardName : specificCardNames) {
                for (final Map.Entry<String, String> entry : candidateProductNames) {
                    final String candidate = entry.getKey();
                    final String lowerCandidate = candidate.toLowerCase(Locale.ROOT);
                    if (lowerCandidate.contains(cardName.toLowerCase(Locale.ROOT))) {
                        LOGGER.info(
                                "Extracted product name (prioritized by candidate text '{}'): {}",
                                cardName,
                                candidate);
                        return candidate;
                    }
                }
            }

            // If no specific card name found, validate and return the first valid candidate
            for (final Map.Entry<String, String> entry : candidateProductNames) {
                final String candidate = entry.getKey();
                if (isValidProductName(candidate)) {
                    LOGGER.info("Extracted product name (first valid candidate): {}", candidate);
                    return candidate;
                } else {
                    LOGGER.info("Skipped invalid candidate: {}", candidate);
                }
            }

            // If all candidates were invalid, return null
            // No valid product name candidates found after validation
            return null;
        }

        // Pattern 2: Regex pattern for "Institution Product Name Card" format
        // Build regex dynamically from institution keywords
        final StringBuilder institutionPattern = new StringBuilder("(?i)(?:");
        for (int i = 0; i < INSTITUTION_KEYWORDS.size(); i++) {
            if (i > 0) {
                institutionPattern.append('|');
            }
            institutionPattern.append(Pattern.quote(INSTITUTION_KEYWORDS.get(i)));
        }
        institutionPattern.append(")\\s+([A-Z][A-Za-z0-9\\s®™©]+?)\\s*(?:card|®|™)");

        final Pattern pattern = Pattern.compile(institutionPattern.toString(), Pattern.CASE_INSENSITIVE);
        final Matcher matcher = pattern.matcher(headerText);
        if (matcher.find()) {
            String productName = matcher.group(0).trim();
            productName = productName.replaceAll("\\s+", " "); // Normalize whitespace
            LOGGER.info("Extracted product name (regex pattern): {}", productName);
            return productName;
        }

        return null;
    }

    /** Capitalize card name properly (e.g., "prime visa" -> "Prime Visa") */
    private String capitalizeCardName(final String cardName) {
        if (cardName == null || cardName.isEmpty()) {
            return cardName;
        }

        // Handle special cases
        final String lower = cardName.toLowerCase(Locale.ROOT);
        if (lower.contains(PRIME_VISA)) {
            // Capitalize "Prime Visa" properly
            if (lower.startsWith("amazon")) {
                return "Amazon Prime Visa";
            } else if (lower.contains(REWARDS)) {
                return "Prime Rewards Visa";
            } else if (lower.contains(SIGNATURE)) {
                return "Prime Visa Signature";
            } else {
                return "Prime Visa";
            }
        }

        // Default: capitalize first letter of each word
        final String[] words = cardName.toLowerCase(Locale.ROOT).split("\\s+");
        final StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                result.append(' ');
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

    /** Check if a string is a valid card product name (contains card product keywords) */
    private boolean isValidCardProductName(final String name) {
        if (name == null || name.isBlank()) {
            return false;
        }

        final String lower = name.toLowerCase(Locale.ROOT);

        // Must contain at least one card product keyword
        final List<String> cardKeywords =
                Arrays.asList(
                        "prime",
                        "visa",
                        MASTERCARD,
                        "amex",
                        AMERICAN_EXPRESS,
                        DISCOVER,
                        PLATINUM,
                        "gold",
                        SILVER,
                        SIGNATURE,
                        WORLD,
                        ELITE,
                        INFINITE,
                        RESERVE,
                        PREFERRED,
                        FREEDOM,
                        SAPPHIRE,
                        BONVOY,
                        MARRIOTT,
                        HILTON,
                        HYATT,
                        DELTA,
                        VENTURE,
                        SAVOR,
                        QUICKSILVER,
                        SPARK,
                        CASH_BACK,
                        "cashback",
                        REWARDS,
                        MILES,
                        POINTS,
                        "card");

        for (final String keyword : cardKeywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Validate product name candidate Rejects names that: - Are longer than 5 words - Have
     * lowercase words (should be Caps or Camel case) - Match blacklist patterns (contact info,
     * addresses, etc.) - Are too long or too short
     */
    private boolean isValidProductName(final String productName) {
        if (productName == null || productName.isBlank()) {
            return false;
        }

        final String trimmed = productName.trim();

        // Check length (should be reasonable for a product name)
        if (trimmed.length() < 3 || trimmed.length() > 100) {
            // Rejected product name - length out of range
            return false;
        }

        // Check word count (should be 5 words or fewer)
        final String[] words = trimmed.split("\\s+");
        if (words.length > 7) {
            // Rejected product name - too many words
            return false;
        }

        // Check capitalization: should be Caps or Camel case, not all lowercase
        // Allow some lowercase words (like "of", "the", "and" in proper names)
        // But reject if ALL words are lowercase (except very short words like "of", "the")
        boolean hasCapitalizedWord = false;
        final List<String> allowedLowercaseWords =
                Arrays.asList("of", "the", "and", "or", "for", "at", "in", "on", "to", "b");

        for (final String word : words) {
            // Remove punctuation for checking
            final String cleanWord = word.replaceAll("[.,;:®™©]+", "").trim();
            if (cleanWord.isEmpty()) {
                continue;
            }

            // Check if word starts with uppercase (Caps or Camel case)
            if (Character.isUpperCase(cleanWord.charAt(0))) {
                hasCapitalizedWord = true;
            } else {
                // Word starts with lowercase
                // Allow if it's a common lowercase word in proper names
                if (!allowedLowercaseWords.contains(cleanWord.toLowerCase(Locale.ROOT))) {
                    // Check if word is all lowercase and longer than 4 characters (should be
                    // capitalized)
                    // Exception: allow if it's a known card product name
                    if (cleanWord.equals(cleanWord.toLowerCase(Locale.ROOT)) && cleanWord.length() > 4) {
                        // Check if it's a known card product keyword (these are sometimes lowercase
                        // in statements)
                        final List<String> lowercaseCardKeywords =
                                Arrays.asList(
                                        PLATINUM,
                                        "gold",
                                        SILVER,
                                        SIGNATURE,
                                        WORLD,
                                        ELITE,
                                        INFINITE,
                                        RESERVE,
                                        PREFERRED,
                                        FREEDOM,
                                        SAPPHIRE,
                                        BONVOY,
                                        MARRIOTT,
                                        HILTON,
                                        HYATT,
                                        DELTA,
                                        VENTURE,
                                        SAVOR,
                                        QUICKSILVER,
                                        SPARK,
                                        UNLIMITED,
                                        EVERYDAY,
                                        PREMIER,
                                        CLASSIC,
                                        PREMIUM,
                                        BLACK,
                                        "coral",
                                        DIAMOND,
                                        IMPERIAL,
                                        ROYAL,
                                        PRESTIGE,
                                        EXCLUSIVE,
                                        TRAVEL,
                                        BUSINESS,
                                        REWARDS,
                                        MILES,
                                        POINTS,
                                        "cash",
                                        DOUBLE_CASH,
                                        ACTIVE_CASH,
                                        BLUE_CASH,
                                        "simplicity",
                                        CASH_BACK,
                                        "latitude",
                                        MASTERCARD);
                        if (!lowercaseCardKeywords.contains(cleanWord.toLowerCase(Locale.ROOT))) {
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
        final String lowerTrimmed = trimmed.toLowerCase(Locale.ROOT);
        final List<String> blacklistPhrases =
                Arrays.asList(
                        "write us",
                        "questions",
                        "if you have",
                        CONTACT_US,
                        "call us",
                        "p.o. box",
                        "po box",
                        "post office",
                        "electronic funds",
                        "funds services",
                        "you may",
                        "for more",
                        "for additional",
                        "please",
                        "thank you",
                        "sincerely",
                        "regards");

        for (final String blacklistPhrase : blacklistPhrases) {
            if (lowerTrimmed.contains(blacklistPhrase)) {
                // Rejected product name - contains blacklist phrase
                return false;
            }
        }

        // Reject if contains address patterns (ZIP codes, state abbreviations with numbers)
        if (trimmed.matches(B_D_5_D_4_B)
                || // ZIP code
                trimmed.matches(".*\\b[A-Z]{2}\\s+\\d{5}\\b.*")) { // State + ZIP
            // Rejected product name - contains address pattern
            return false;
        }

        // Reject if contains email or phone patterns
        if (trimmed.matches(".*@.*")
                || // Email
                trimmed.matches(".*\\d{1,3}[\\-.]?\\d{3}[\\-.]?\\d{3}[\\-.]?\\d{4}.*")) { // Phone
            // Rejected product name - contains email or phone pattern
            return false;
        }

        return true;
    }

    /**
     * Extract account holder/cardholder name from PDF header text Looks for patterns like "Card
     * Member: John Doe", "Name: John Doe", etc. Also handles contextual patterns like name before
     * address, account number, card number, etc.
     *
     * <p>Strategy: Collect all candidates first, then apply filters and preferences to select the
     * best one.
     */
    private String extractAccountHolderNameFromPDF(final String headerText) {
        if (headerText == null || headerText.isEmpty()) {
            return null;
        }
        LOGGER.debug("Extracting account holder name from PDF header text: {}", headerText);
        final String[] lines = headerText.split("\\r?\\n");
        final List<String> excludedWords =
                Arrays.asList(
                        "sale",
                        "post",
                        "date",
                        DESCRIPTION,
                        AMOUNT,
                        "payments",
                        "credits",
                        "adjustments",
                        "summary",
                        "history");

        // Phrases that indicate this is not a name line (should be rejected entirely)
        // Note: This list is for documentation - actual filtering happens in extractAndValidateName
        // List<String> excludedPhrases = Arrays.asList(
        //     "account information", "your name", "account number", "and account number",
        //     "card number", "card information", "statement information"
        // );

        // Helper class to store candidate with priority and pattern type
        class NameCandidate {
            final String name;
            int priority; // Higher = better (direct patterns > contextual patterns) - mutable
            // for merging
            String patternType; // For logging - primary pattern type - mutable for merging
            boolean isAllCaps; // For preference - mutable for merging
            boolean isContextual; // 3-line address, etc. - mutable for merging
            int frequency = 1; // Number of times this name appears
            Set<String> patternTypes = new HashSet<>(); // All pattern types this name appears in

            NameCandidate(
                    final String name,
                    final int priority,
                    final String patternType,
                    final boolean isAllCaps,
                    final boolean isContextual) {
                this.name = name;
                this.priority = priority;
                this.patternType = patternType;
                this.isAllCaps = isAllCaps;
                this.isContextual = isContextual;
                this.patternTypes.add(patternType);
            }

            // Merge another candidate with the same name
            void merge(final NameCandidate other) {
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
             * Calculate composite score combining all factors with appropriate weightings Score
             * components: - Priority: 100 points per priority point (primary factor) - Frequency:
             * 50 points per occurrence with log scaling (diminishing returns) - Pattern types: 20
             * points per unique pattern type (cross-pattern presence) - All-caps: 100 point bonus
             * (statement headers preference) - Contextual: 50 point bonus (3-line address, etc.) -
             * Single word: -5000 point penalty (terrible reduction - single words are less likely
             * to be full names) - All lowercase: -3000 point penalty (terrible reduction - names
             * are typically capitalized)
             *
             * @return Composite score (higher is better)
             */
            double calculateCompositeScore() {
                // Priority: 90 points per priority point (most important)
                // Priority range: 70-100, so contributes 7000-10000 points
                final double priorityScore = priority * 90.0;

                // Frequency: 50 points per occurrence with log scaling to prevent overwhelming
                // log1p(x) = ln(1+x), so log1p(1) ≈ 0.69, log1p(3) ≈ 1.39, log1p(10) ≈ 2.40
                // This gives diminishing returns: 1 occurrence = 34.5, 3 = 69.5, 10 = 120 points
                final double frequencyScore = Math.log1p(frequency) * 50.0;

                // Pattern types: 20 points per unique pattern type
                // More pattern types = more confidence (appears in multiple contexts)
                final double patternTypesScore = patternTypes.size() * 20.0;

                // All-caps bonus: 100 points (statement headers are often all-caps)
                final double allCapsBonus = isAllCaps ? 100.0 : 0.0;

                // Contextual bonus: 50 points (3-line address, etc. are strong indicators)
                final double contextualBonus = isContextual ? 50.0 : 0.0;

                // Single word penalty: -5000 points (terrible reduction - single words are less
                // likely to be full names)
                // Check if name has only one word
                final String[] words = name.trim().split("\\s+");
                final double singleWordPenalty = words.length == 1 ? -5000.0 : 0.0;
                if (singleWordPenalty < 0) {
                    LOGGER.debug("Applying single word penalty (-5000) to candidate '{}'", name);
                }

                // All lowercase penalty: -3000 points (terrible reduction - names are typically
                // capitalized)
                // Check if name is all lowercase (but not empty and has letters)
                final boolean isAllLowercase =
                        name.equals(name.toLowerCase(Locale.ROOT))
                                && name.matches(".*[a-z].*")
                                && !name.matches(A_Z);
                final double allLowercasePenalty = isAllLowercase ? -3000.0 : 0.0;
                if (allLowercasePenalty < 0) {
                    LOGGER.debug("Applying all lowercase penalty (-3000) to candidate '{}'", name);
                }

                final double totalScore =
                        priorityScore
                                + frequencyScore
                                + patternTypesScore
                                + allCapsBonus
                                + contextualBonus
                                + singleWordPenalty
                                + allLowercasePenalty;

                return totalScore;
            }
        }

        // Use Map to track candidates by name (normalized) to merge duplicates
        final Map<String, NameCandidate> candidateMap = new HashMap<>();

        // Patterns to match account holder name directly
        final Pattern[] directPatterns = {
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
        for (final String line : lines) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // Skip lines that are clearly not name lines
            final String lowerLine = trimmed.toLowerCase(Locale.ROOT);
            if (lowerLine.contains("statement period")
                    || lowerLine.contains("account summary")
                    || lowerLine.contains("transaction summary")
                    || lowerLine.contains("payment history")
                    || lowerLine.contains("transaction history")
                    || lowerLine.contains("account information")
                    || lowerLine.contains("your name")
                    || lowerLine.contains(AND_ACCOUNT_NUMBER)
                    || lowerLine.contains("If you have")
                    || lowerLine.contains(BALANCE)
                    || lowerLine.contains("card member agreement")
                    || lowerLine.contains("card member information")
                    || lowerLine.contains("card member benefits")
                    || lowerLine.contains("card member services")
                    || lowerLine.contains("card member service")
                    || lowerLine.contains("cardmember service")
                    || lowerLine.contains(CARDMEMBER_AGREEMENT)
                    || lowerLine.contains("cardmember information")
                    || lowerLine.contains("cardmember benefits")
                    || lowerLine.contains("cardmember services")
                    || lowerLine.contains("cardmember support")
                    || lowerLine.contains("cardmember rewards")
                    || lowerLine.contains("account holder agreement")
                    || lowerLine.contains("account holder information")
                    || lowerLine.contains("account holder benefits")
                    || lowerLine.contains("account holder services")
                    || lowerLine.contains("account holder service")
                    || lowerLine.contains("account holder support")
                    || lowerLine.contains("account holder rewards")
                    || lowerLine.contains("passenger name")
                    || lowerLine.contains(ACCOUNT_NAME)
                    || lowerLine.contains("person name")
                    || lowerLine.contains("card name")
                    || lowerLine.contains("minimum payment")
                    || lowerLine.contains("alternate payment")
                    || lowerLine.matches(".*\\bdate\\s+description\\s+amount.*")
                    || // Column headers
                    lowerLine.matches(".*\\btransaction\\s+date.*")
                    || // Transaction table headers
                    (lowerLine.contains("date") && lowerLine.contains(AMOUNT))) {
                continue;
            }

            // Try each direct pattern
            for (int i = 0; i < directPatterns.length; i++) {
                final Pattern pattern = directPatterns[i];
                final Matcher matcher = pattern.matcher(trimmed);
                if (matcher.find()) {
                    // Extract name and clean it (remove newlines, extra spaces, etc.)
                    String rawName = matcher.group(1).trim();
                    // Remove any newlines or carriage returns that might be in the captured text
                    rawName = rawName.replaceAll("[\\r\\n]+", " ").trim();
                    // Also stop if we see common context markers in the captured text
                    if (rawName.contains("Account Number")
                            || rawName.contains("Card Number")
                            || rawName.contains("Account Ending")
                            || rawName.contains("Card Ending")) {
                        // Extract only the part before the context marker
                        final String[] parts = rawName.split("(?:Account|Card)\\s+(?:Number|Ending)", 2);
                        rawName = parts[0].trim();
                    }
                    // CRITICAL: Remove any remaining pattern prefixes (e.g., "Name:" if it wasn't
                    // properly excluded)
                    rawName =
                            rawName.replaceFirst(
                                            "^(?:name|user|cardholder|holder|card\\s*member)\\s*:?\\s*",
                                            "")
                                    .trim();
                    LOGGER.debug("Pattern {} matched, raw name: '{}'", i + 1, rawName);
                    final String name = extractAndValidateName(rawName, excludedWords);
                    if (name != null) {
                        final boolean isAllCaps =
                                name.equals(name.toUpperCase(Locale.ROOT)) && name.matches(A_Z);
                        final String patternType = "direct_pattern_" + (i + 1);
                        // Normalize name for map key (trim and normalize case for consistent
                        // merging)
                        // Use lowercase for key to handle case variations (e.g., "John Doe" vs
                        // "JOHN DOE")
                        final String normalizedName = name.trim().toLowerCase(Locale.ROOT);
                        // Merge with existing candidate if same name found
                        final NameCandidate existing = candidateMap.get(normalizedName);
                        if (existing != null) {
                            existing.merge(
                                    new NameCandidate(name, 100, patternType, isAllCaps, false));
                            // Merged account holder name candidate
                        } else {
                            final NameCandidate candidate =
                                    new NameCandidate(name, 100, patternType, isAllCaps, false);
                            candidateMap.put(normalizedName, candidate);
                        }
                    }
                }
            }
        }

        // STEP 2: Collect candidates from contextual patterns (name in previous line before certain
        // keywords)
        // Handle empty lines by looking backward for the previous non-empty line
        for (int i = 1; i < lines.length; i++) {
            final String currentLine = lines[i].trim();
            if (currentLine.isEmpty()) {
                continue; // Skip empty current line

                // Find previous non-empty line
            }
            String previousLine = null;
            for (int j = i - 1; j >= 0; j--) {
                final String prevCandidate = lines[j].trim();
                if (!prevCandidate.isEmpty()) {
                    previousLine = prevCandidate;
                    break;
                }
            }

            if (previousLine == null || previousLine.isEmpty()) {
                continue;
            }

            final String currentLineLower = currentLine.toLowerCase(Locale.ROOT);

            // Pattern: Name in previous line, followed by Address
            // Handle both 2-line (name, address) and 3-line (name, street, city state ZIP) formats
            boolean isAddressLine = false;
            boolean isThreeLineAddress = false;

            // Check if current line looks like an address (street address with number, or contains
            // address keywords)
            if (currentLineLower.matches(".*\\baddress\\b.*")
                    || currentLineLower.matches(".*\\bstreet\\b.*")
                    || currentLineLower.matches(".*\\bcity\\b.*")
                    || currentLineLower.matches(".*\\bstate\\b.*")
                    || currentLineLower.matches(".*\\bzip\\b.*")
                    || currentLineLower.matches(".*\\bpo\\s+box\\b.*")
                    || currentLineLower.matches(".*\\bp\\.o\\.\\s+box\\b.*")
                    || currentLineLower.matches(".*\\bapt\\.?\\b.*")
                    || currentLineLower.matches(".*\\bapartment\\b.*")
                    || currentLineLower.matches(B_D_5_D_4_B)
                    || // ZIP code with optional +4
                    currentLineLower.matches(
                            "^\\d+\\s+.*")) { // Line starting with number (street address)
                isAddressLine = true;
            }

            // Also check if next line (if exists) contains city/state/ZIP pattern
            // This handles 3-line format: name, street, city state ZIP
            // Example: "ASHTON BASHTON HASHTON\n73529 NE 43ST ST\nSEATTLE WA 98119-3579"
            if (i + 1 < lines.length) {
                final String nextLine = lines[i + 1].trim();
                final String nextLineLower = nextLine.toLowerCase(Locale.ROOT);
                if (!nextLine.isEmpty()) {
                    // Check if next line has ZIP code pattern (confirms it's a 3-line address)
                    if (nextLineLower.matches(B_D_5_D_4_B)
                            || // ZIP code with optional +4 (like "98119-3579")
                            nextLineLower.matches(
                                    ".*\\b\\d{5}\\s+\\d{4}\\b.*")) { // ZIP+4 separated by space
                        // (like "98119 3579")
                        // Current line should be a street address (starting with number or
                        // containing street keywords)
                        if (currentLineLower.matches("^\\d+\\s+.*")
                                || currentLineLower.matches(".*\\bstreet\\b.*")
                                || currentLineLower.matches(".*\\bavenue\\b.*")
                                || currentLineLower.matches(".*\\broad\\b.*")) {
                            isAddressLine = true;
                            isThreeLineAddress = true; // Mark as 3-line format (higher preference)
                        }
                    }
                }
            }

            if (isAddressLine) {
                final String name = extractAndValidateName(previousLine, excludedWords);
                if (name != null) {
                    final boolean isAllCaps =
                            name.equals(name.toUpperCase(Locale.ROOT)) && name.matches(A_Z);
                    final int priority =
                            isThreeLineAddress ? 90 : 80; // Higher priority for 3-line address
                    final String patternType = isThreeLineAddress ? "3_line_address" : "address";
                    // Normalize name for map key (trim and lowercase for consistent merging)
                    final String normalizedName = name.trim().toLowerCase(Locale.ROOT);
                    final NameCandidate existing = candidateMap.get(normalizedName);
                    if (existing != null) {
                        existing.merge(
                                new NameCandidate(name, priority, patternType, isAllCaps, true));
                        // Merged account holder name candidate
                    } else {
                        candidateMap.put(
                                normalizedName,
                                new NameCandidate(name, priority, patternType, isAllCaps, true));
                    }
                }
            }

            // Pattern: Name in previous line, followed by "Member since" or "Customer since"
            if (currentLineLower.matches(".*\\bmember\\s+since\\b.*")
                    || currentLineLower.matches(".*\\bcustomer\\s+since\\b.*")) {
                final String name = extractAndValidateName(previousLine, excludedWords);
                if (name != null) {
                    final boolean isAllCaps =
                            name.equals(name.toUpperCase(Locale.ROOT)) && name.matches(A_Z);
                    // Normalize name for map key (trim and lowercase for consistent merging)
                    final String normalizedName = name.trim().toLowerCase(Locale.ROOT);
                    final NameCandidate existing = candidateMap.get(normalizedName);
                    if (existing != null) {
                        existing.merge(
                                new NameCandidate(name, 85, "member_since", isAllCaps, true));
                        // Merged account holder name candidate
                    } else {
                        candidateMap.put(
                                normalizedName,
                                new NameCandidate(name, 85, "member_since", isAllCaps, true));
                    }
                }
            }

            // Pattern: Name in previous line, followed by Account number
            if (currentLineLower.matches(".*\\baccount\\s+(?:number|ending|#|ending\\s+in)\\b.*")
                    || currentLineLower.matches(".*\\baccount\\s+ending\\b.*")
                    || currentLineLower.matches(".*\\baccount\\s+\\*{0,4}\\d{4,}.*")
                    || currentLineLower.matches(".*\\bclosing\\s+date.*\\baccount\\s+ending\\b.*")
                    || currentLineLower.matches(".*\\bclosing\\s+date.*")) {
                final String name = extractAndValidateName(previousLine, excludedWords);
                if (name != null) {
                    final boolean isAllCaps =
                            name.equals(name.toUpperCase(Locale.ROOT)) && name.matches(A_Z);
                    // Normalize name for map key (trim and lowercase for consistent merging)
                    final String normalizedName = name.trim().toLowerCase(Locale.ROOT);
                    final NameCandidate existing = candidateMap.get(normalizedName);
                    if (existing != null) {
                        existing.merge(
                                new NameCandidate(name, 75, "account_number", isAllCaps, true));
                        // Merged account holder name candidate
                    } else {
                        candidateMap.put(
                                normalizedName,
                                new NameCandidate(name, 75, "account_number", isAllCaps, true));
                    }
                }
            }

            // Pattern: Name followed by Card number or Card ending in
            if (currentLineLower.matches(".*\\bcard\\s+(?:number|ending|#|ending\\s+in)\\b.*")
                    || currentLineLower.matches(".*\\bcard\\s+ending\\b.*")
                    || currentLineLower.matches(".*\\bcard\\s+\\*{0,4}\\d{4,}.*")
                    || currentLineLower.matches(".*\\b\\d{4}\\s+\\d{4}\\s+\\d{4}\\s+\\d{4}.*")) {
                final String name = extractAndValidateName(previousLine, excludedWords);
                if (name != null) {
                    final boolean isAllCaps =
                            name.equals(name.toUpperCase(Locale.ROOT)) && name.matches(A_Z);
                    // Normalize name for map key (trim and lowercase for consistent merging)
                    final String normalizedName = name.trim().toLowerCase(Locale.ROOT);
                    final NameCandidate existing = candidateMap.get(normalizedName);
                    if (existing != null) {
                        existing.merge(new NameCandidate(name, 75, "card_number", isAllCaps, true));
                        // Merged account holder name candidate
                    } else {
                        candidateMap.put(
                                normalizedName,
                                new NameCandidate(name, 75, "card_number", isAllCaps, true));
                    }
                }
            }

            // Pattern: Name on same line followed by Account Number or Account Ending in
            final Pattern nameBeforeAccountPattern =
                    Pattern.compile(
                            "^(.+?)\\s+(?:account\\s+(?:number|ending|#|ending\\s+in)|account\\s+ending|account\\s+\\*{0,4}\\d{4,}|\\d{1,9}\\s*-\\s*\\d{4,6})",
                            Pattern.CASE_INSENSITIVE);
            Matcher nameMatcher = nameBeforeAccountPattern.matcher(currentLine);
            if (nameMatcher.find()) {
                final String name = extractAndValidateName(nameMatcher.group(1).trim(), excludedWords);
                if (name != null) {
                    final boolean isAllCaps =
                            name.equals(name.toUpperCase(Locale.ROOT)) && name.matches(A_Z);
                    // Normalize name for map key (trim and lowercase for consistent merging)
                    final String normalizedName = name.trim().toLowerCase(Locale.ROOT);
                    final NameCandidate existing = candidateMap.get(normalizedName);
                    if (existing != null) {
                        existing.merge(
                                new NameCandidate(name, 70, "same_line_account", isAllCaps, false));
                        // Merged account holder name candidate
                    } else {
                        candidateMap.put(
                                normalizedName,
                                new NameCandidate(name, 70, "same_line_account", isAllCaps, false));
                    }
                }
            }

            // Pattern: Name on same line followed by Card number or Card ending in
            final Pattern nameBeforeCardPattern =
                    Pattern.compile(
                            "^(.+?)\\s+(?:card\\s+(?:number|ending|#|ending\\s+in)|card\\s+ending|card\\s+\\*{0,4}\\d{4,}|\\d{4}\\s+\\d{4}\\s+\\d{4}\\s+\\d{4})",
                            Pattern.CASE_INSENSITIVE);
            nameMatcher = nameBeforeCardPattern.matcher(currentLine);
            if (nameMatcher.find()) {
                final String name = extractAndValidateName(nameMatcher.group(1).trim(), excludedWords);
                if (name != null) {
                    final boolean isAllCaps =
                            name.equals(name.toUpperCase(Locale.ROOT)) && name.matches(A_Z);
                    // Normalize name for map key (trim and lowercase for consistent merging)
                    final String normalizedName = name.trim().toLowerCase(Locale.ROOT);
                    final NameCandidate existing = candidateMap.get(normalizedName);
                    if (existing != null) {
                        existing.merge(
                                new NameCandidate(name, 70, "same_line_card", isAllCaps, false));
                        // Merged account holder name candidate
                    } else {
                        candidateMap.put(
                                normalizedName,
                                new NameCandidate(name, 70, "same_line_card", isAllCaps, false));
                    }
                }
            }
        }

        // STEP 2B: Also collect same-line patterns for all lines (handles single-line cases)
        for (final String line : lines) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // Pattern: Name on same line followed by Account Number or Account Ending in
            final Pattern nameBeforeAccountPattern =
                    Pattern.compile(
                            "^(.+?)\\s+(?:account\\s+(?:number|ending|#|ending\\s+in)|account\\s+ending|account\\s+\\*{0,4}\\d{4,}|\\d{1,9}\\s*-\\s*\\d{4,6})",
                            Pattern.CASE_INSENSITIVE);
            Matcher nameMatcher = nameBeforeAccountPattern.matcher(trimmed);
            if (nameMatcher.find()) {
                final String name = extractAndValidateName(nameMatcher.group(1).trim(), excludedWords);
                if (name != null) {
                    final boolean isAllCaps =
                            name.equals(name.toUpperCase(Locale.ROOT)) && name.matches(A_Z);
                    // Normalize name for map key (trim and lowercase for consistent merging)
                    final String normalizedName = name.trim().toLowerCase(Locale.ROOT);
                    final NameCandidate existing = candidateMap.get(normalizedName);
                    if (existing != null) {
                        existing.merge(
                                new NameCandidate(
                                        name, 70, "same_line_account_all", isAllCaps, false));
                        // Merged account holder name candidate
                    } else {
                        candidateMap.put(
                                normalizedName,
                                new NameCandidate(
                                        name, 70, "same_line_account_all", isAllCaps, false));
                    }
                }
            }

            // Pattern: Name on same line followed by Card number or Card ending in
            final Pattern nameBeforeCardPattern =
                    Pattern.compile(
                            "^(.+?)\\s+(?:card\\s+(?:number|ending|#|ending\\s+in)|card\\s+ending|card\\s+\\*{0,4}\\d{4,}|\\d{4}\\s+\\d{4}\\s+\\d{4}\\s+\\d{4})",
                            Pattern.CASE_INSENSITIVE);
            nameMatcher = nameBeforeCardPattern.matcher(trimmed);
            if (nameMatcher.find()) {
                final String name = extractAndValidateName(nameMatcher.group(1).trim(), excludedWords);
                if (name != null) {
                    final boolean isAllCaps =
                            name.equals(name.toUpperCase(Locale.ROOT)) && name.matches(A_Z);
                    // Normalize name for map key (trim and lowercase for consistent merging)
                    final String normalizedName = name.trim().toLowerCase(Locale.ROOT);
                    final NameCandidate existing = candidateMap.get(normalizedName);
                    if (existing != null) {
                        existing.merge(
                                new NameCandidate(
                                        name, 70, "same_line_card_all", isAllCaps, false));
                        LOGGER.debug(
                                "Merged account holder name candidate (same line card - all lines): {} (frequency: {}, patterns: {})",
                                name,
                                existing.frequency,
                                existing.patternTypes);
                    } else {
                        candidateMap.put(
                                normalizedName,
                                new NameCandidate(
                                        name, 70, "same_line_card_all", isAllCaps, false));
                        LOGGER.debug(
                                "Collected account holder name candidate (same line card - all lines): {}",
                                name);
                    }
                }
            }
        }

        // STEP 3: Filter candidates - reject bank names, US state abbreviations, and invalid
        // candidates
        // US state abbreviations (50 states + DC)
        final List<String> usStateAbbreviations =
                Arrays.asList(
                        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", "HI", "ID",
                        "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS",
                        "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK",
                        "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV",
                        "WI", "WY", "DC");

        final List<NameCandidate> validCandidates = new ArrayList<>();
        LOGGER.debug("Filtering {} candidates", candidateMap.size());
        for (final NameCandidate candidate : candidateMap.values()) {
            final String lowerName = candidate.name.toLowerCase(Locale.ROOT);

            // Reject bank/institution names
            // CRITICAL: Use word boundaries to avoid false positives (e.g., "O'Brien" contains
            // "bri" but is not a bank name)
            boolean isBankName = false;
            for (final String bankName : INSTITUTION_KEYWORDS) {
                // Use word boundaries to match whole words only
                // This prevents "O'Brien" from matching "bri" or "BRI"
                final Pattern bankPattern =
                        Pattern.compile(
                                "\\b" + Pattern.quote(bankName) + "\\b", Pattern.CASE_INSENSITIVE);
                if (bankPattern.matcher(lowerName).find()) {
                    isBankName = true;
                    LOGGER.debug(
                            "Rejected account holder name candidate '{}' - contains bank/institution name '{}'",
                            candidate.name,
                            bankName);
                    break;
                }
            }
            if (isBankName) {
                continue;
            }

            // Reject names containing US state abbreviations (as whole words) - indicates address,
            // not name
            // Check for 2-letter state abbreviations as whole words
            final String[] words = candidate.name.split("\\s+");
            boolean containsStateAbbreviation = false;
            for (final String word : words) {
                // Remove punctuation for comparison (e.g., "WA," -> "WA")
                final String cleanWord = word.replaceAll("[.,;:]+$", "").trim().toUpperCase(Locale.ROOT);
                if (usStateAbbreviations.contains(cleanWord)) {
                    containsStateAbbreviation = true;
                    LOGGER.debug(
                            "Rejected account holder name candidate '{}' - contains US state abbreviation '{}' (likely address, not name)",
                            candidate.name,
                            cleanWord);
                    break;
                }
            }
            if (containsStateAbbreviation) {
                continue;
            }

            LOGGER.debug("Accepted candidate: '{}'", candidate.name);
            validCandidates.add(candidate);
        }

        if (validCandidates.isEmpty()) {
            LOGGER.info("No valid account holder name candidates found after filtering");
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
        for (final NameCandidate candidate : validCandidates) {
            final double score = candidate.calculateCompositeScore();
            LOGGER.debug(
                    "Candidate '{}' composite score: {:.2f} (priority: {}={:.0f}, frequency: {}={:.2f}, patterns: {}={:.0f}, all-caps: {}, contextual: {})",
                    candidate.name,
                    score,
                    candidate.priority,
                    candidate.priority * 100.0,
                    candidate.frequency,
                    Math.log1p(candidate.frequency) * 50.0,
                    candidate.patternTypes.size(),
                    candidate.patternTypes.size() * 20.0,
                    candidate.isAllCaps ? 100.0 : 0.0,
                    candidate.isContextual ? 50.0 : 0.0);
        }

        // Sort by composite score (descending - higher score is better)
        validCandidates.sort(
                (a, b) -> {
                    final double scoreA = a.calculateCompositeScore();
                    final double scoreB = b.calculateCompositeScore();
                    return Double.compare(scoreB, scoreA); // Descending order
                });

        for (final NameCandidate candidate : validCandidates) {
            final double score = candidate.calculateCompositeScore();
            LOGGER.debug(
                    "Candidate: '{}' (score: {:.2f}, priority: {}, frequency: {}, pattern_types: {}, all-caps: {}, contextual: {})",
                    candidate.name,
                    String.format("%.2f", score),
                    candidate.priority,
                    candidate.frequency,
                    candidate.patternTypes,
                    candidate.isAllCaps,
                    candidate.isContextual);
        }

        final NameCandidate bestCandidate = validCandidates.get(0);
        final double bestScore = bestCandidate.calculateCompositeScore();
        LOGGER.debug(
                "Selected account holder name '{}' from {} candidates (score: {:.2f}, pattern: {}, priority: {}, frequency: {}, pattern_types: {}, all-caps: {}, contextual: {})",
                bestCandidate.name,
                validCandidates.size(),
                String.format("%.2f", bestScore),
                bestCandidate.patternType,
                bestCandidate.priority,
                bestCandidate.frequency,
                bestCandidate.patternTypes,
                bestCandidate.isAllCaps,
                bestCandidate.isContextual);

        return bestCandidate.name;
    }

    /**
     * Extract and validate name from a candidate string Filters out names containing excluded words
     * and validates format
     */
    private String extractAndValidateName(final String candidate, final List<String> excludedWords) {
        if (candidate == null || candidate.isBlank()) {
            LOGGER.debug("extractAndValidateName: Candidate is null or empty");
            return null;
        }

        // Clean up the name (remove extra spaces, trailing punctuation except periods in suffixes
        // like "Jr.")
        // Only remove trailing punctuation if it's not part of a valid suffix (Jr., Sr., II, III,
        // etc.)
        // CRITICAL: Stop at newlines or other context markers (like "Account Number")
        String name = candidate.split("[\\r\\n]")[0].trim(); // Take only the first line
        // Also stop if we see common context markers
        if (name.contains("Account Number")
                || name.contains("Card Number")
                || name.contains("Account Ending")
                || name.contains("Card Ending")) {
            // Extract only the part before the context marker
            final String[] parts = name.split("(?:Account|Card)\\s+(?:Number|Ending)", 2);
            name = parts[0].trim();
        }
        name = name.replaceAll("\\s+", " ").trim();

        // Reject phrases that are clearly not names (like "and account number", "your name", etc.)
        // Check this BEFORE cleaning up trailing punctuation
        final String lowerName = name.toLowerCase(Locale.ROOT);
        if (lowerName.contains(AND_ACCOUNT_NUMBER)
                || lowerName.contains("your name and")
                || lowerName.contains("account information")
                || lowerName.contains("autopay")
                || lowerName.contains("interest")
                || lowerName.contains("P.O.")
                || lowerName.matches(".*\\baccount\\s+number\\b.*")
                || lowerName.matches(".*\\bcard\\s+number\\b.*")
                || lowerName.startsWith("your name")
                || AND_ACCOUNT_NUMBER.equals(lowerName)
                || "account number".equals(lowerName)
                || lowerName.contains("send general inquiries")
                || lowerName.contains("general inquiries")
                || (lowerName.startsWith("send ") && lowerName.contains("inquir"))) {
            LOGGER.debug(
                    "Rejected account holder name candidate '{}' - contains excluded phrase", name);
            return null;
        }

        // Reject agreement-related phrases that are clearly not names
        // These are informational header phrases, not actual account holder names
        if (lowerName.contains("agreement for details")
                || lowerName.contains(CARDMEMBER_AGREEMENT)
                || lowerName.contains("cardholder agreement")
                || lowerName.contains("cardmember service")
                || lowerName.contains("account holder service")
                || "agreement".equals(lowerName)
                || DETAILS.equals(lowerName)
                || "continued".equals(lowerName)
                || // Reject standalone "continued" (common in statement footers)
                (lowerName.contains("agreement") && lowerName.contains(DETAILS))) {
            LOGGER.debug(
                    "Rejected account holder name candidate '{}' - contains agreement-related phrase or 'continued'",
                    name);
            return null;
        }

        // Only remove trailing punctuation if it's not a suffix pattern
        if (!name.matches(".*\\s+(?:Jr|Sr|II|III|IV|V|VI|VII|VIII|IX|X)\\.?$")) {
            name = name.replaceAll("[.,;:]+$", "").trim();
        }

        // Check length
        if (name.length() < 2 || name.length() > 100) {
            LOGGER.debug(
                    "extractAndValidateName: Rejected '{}' - length {} is out of range (2-100)",
                    name,
                    name.length());
            return null;
        }

        // Must contain at least one letter
        if (!name.matches(".*[a-zA-Z].*")) {
            LOGGER.debug("extractAndValidateName: Rejected '{}' - does not contain letters", name);
            return null;
        }

        // Must not start with non-letter (except for titles like "Dr.", "Mr.", etc.)
        if (!name.matches("^[A-Za-z].*") && !name.matches("^[A-Za-z]{1,3}\\.\\s+.*")) {
            LOGGER.debug(
                    "extractAndValidateName: Rejected '{}' - does not start with letter", name);
            return null;
        }

        // Check for excluded words (case-insensitive) - reuse lowerName from earlier
        for (final String excludedWord : excludedWords) {
            // Check if name contains the excluded word as a whole word (not part of another word)
            // Use word boundaries to avoid false positives
            if (lowerName.matches(".*\\b" + Pattern.quote(excludedWord) + "\\b.*")) {
                LOGGER.debug(
                        "Rejected account holder name candidate '{}' - contains excluded word '{}'",
                        name,
                        excludedWord);
                return null;
            }
        }

        // Additional validation: should not contain only numbers, dates, or amounts
        // Check for date patterns
        if (name.matches(".*\\d{1,2}[/-]\\d{1,2}(?:[/-]\\d{2,4})?.*")) {
            return null;
        }

        // Check for phone number patterns
        if (name.matches(".*\\d{1,3}[\\-.]?\\d{3}[\\-.]?\\d{3}[\\-.]?\\d{4}.*")
                || name.matches(".*\\(\\s*\\d{1,3}\\s*[\\-)]?\\s*\\d{3}.*")) {
            return null;
        }

        // Should not end with unexpected special characters (except common name punctuation)
        // Allow apostrophes in names (e.g., "O'Brien", "D'Angelo")
        if (name.matches(".*[^a-zA-Z\\s\\-.,']$")) {
            return null;
        }

        // CRITICAL: Allow apostrophes in names - they are valid in many names (O'Brien, D'Angelo,
        // etc.)
        // The regex pattern already allows apostrophes, but we need to ensure they're not filtered
        // out
        // Apostrophes are already allowed in the character class [^a-zA-Z\\s\\-.,'] above

        // Additional validation checks for account holder names

        // 1. Reject names with more than 6 words
        final String[] words = name.trim().split("\\s+");
        if (words.length > 6) {
            LOGGER.debug(
                    "Rejected account holder name candidate '{}' - has more than 6 words ({})",
                    name,
                    words.length);
            return null;
        }

        // 2. Reject names with any full lowercase words of length greater than 4
        // This catches cases like "john doe smith" where words should be capitalized
        for (final String word : words) {
            // Remove punctuation for length check (e.g., "Jr." should be allowed)
            final String wordWithoutPunct = word.replaceAll("[.,;:']", "");
            if (wordWithoutPunct.length() > 4
                    && word.equals(word.toLowerCase(Locale.ROOT))
                    && word.matches(".*[a-z].*")) {
                LOGGER.debug(
                        "Rejected account holder name candidate '{}' - contains lowercase word '{}' of length > 4",
                        name,
                        word);
                return null;
            }
        }

        // 3. Reject names containing forward slash (/) or backslash (\)
        if (name.contains("/") || name.contains("\\")) {
            LOGGER.debug(
                    "Rejected account holder name candidate '{}' - contains '/' or '\\'", name);
            return null;
        }

        // 4. Reject names containing "www." (website URLs)
        if (lowerName.contains("www.")) {
            LOGGER.debug("Rejected account holder name candidate '{}' - contains 'www.'", name);
            return null;
        }

        return name;
    }

    /**
     * Extract balance from headers/text based on account type Delegates to BalanceExtractor for
     * comprehensive global support
     *
     * @param headers List of header strings
     * @param accountType Account type (creditCard, checking, savings, moneyMarket, etc.)
     * @return Detected balance or null if not found
     */
    public java.math.BigDecimal extractBalanceFromHeaders(
            final List<String> headers, final String accountType) {
        return balanceExtractor.extractBalanceFromHeaders(headers, accountType);
    }

    /**
     * Extract balance from last transaction's balance column Delegates to BalanceExtractor for
     * comprehensive global support
     *
     * @param balanceValue Balance value from last transaction row
     * @return Parsed balance or null if invalid
     */
    public java.math.BigDecimal extractBalanceFromTransactionValue(final String balanceValue) {
        return balanceExtractor.extractBalanceFromTransactionValue(balanceValue);
    }

    /**
     * Update account balance with date comparison logic Only updates balance if the new balance
     * date is newer than the existing balance date
     *
     * @param account The account to update
     * @param newBalance The new balance value
     * @param newBalanceDate The date of the transaction from which the new balance was extracted
     * @return true if balance was updated, false if not updated (due to date comparison)
     */
    public boolean updateAccountBalanceWithDateComparison(
            final AccountTable account,
            final java.math.BigDecimal newBalance,
            final java.time.LocalDate newBalanceDate) {
        if (account == null || newBalance == null || newBalanceDate == null) {
            LOGGER.debug("Cannot update balance: account, newBalance, or newBalanceDate is null");
            return false;
        }

        final java.time.LocalDate existingBalanceDate = account.getBalanceDate();

        // If no existing balance date, update the balance
        if (existingBalanceDate == null) {
            account.setBalance(newBalance);
            account.setBalanceDate(newBalanceDate);
            LOGGER.debug(
                    "Updated account balance (no existing balance date): accountId={}, balance={}, balanceDate={}",
                    account.getAccountId(),
                    newBalance,
                    newBalanceDate);
            return true;
        }

        // Only update if new balance date is newer (after) existing balance date
        if (newBalanceDate.isAfter(existingBalanceDate)) {
            account.setBalance(newBalance);
            account.setBalanceDate(newBalanceDate);
            LOGGER.debug(
                    "Updated account balance (new date is newer): accountId={}, balance={}, balanceDate={} (previous: {})",
                    account.getAccountId(),
                    newBalance,
                    newBalanceDate,
                    existingBalanceDate);
            return true;
        } else {
            LOGGER.debug(
                    "Skipped account balance update (new date is not newer): accountId={}, newBalanceDate={}, existingBalanceDate={}",
                    account.getAccountId(),
                    newBalanceDate,
                    existingBalanceDate);
            return false;
        }
    }

    // Getter methods for keyword lists (used by import services)
    public List<String> getAccountNumberKeywords() {
        return new ArrayList<>(ACCOUNT_NUMBER_KEYWORDS);
    }

    public List<String> getInstitutionKeywords() {
        final List<String> allKeywords = new ArrayList<>();
        allKeywords.addAll(INSTITUTION_KEYWORDS_HEADERS);
        allKeywords.addAll(PRODUCT_NAME_KEYWORDS);
        return allKeywords;
    }

    /**
     * Get institution keywords for filtering (prevents false positives in name detection) Returns
     * the main INSTITUTION_KEYWORDS list used for bank/institution name filtering
     */
    public List<String> getInstitutionKeywordsForFiltering() {
        return new ArrayList<>(INSTITUTION_KEYWORDS);
    }

    public List<String> getAccountTypeKeywords() {
        return new ArrayList<>(ACCOUNT_TYPE_KEYWORDS);
    }
}
