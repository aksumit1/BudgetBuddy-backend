package com.budgetbuddy.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Global financial configuration for international support Supports region-specific account types,
 * payment systems, and category mappings
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = {"EI_EXPOSE_REP"},
        justification =
                "JSON DTO / DynamoDB entity getters expose lists by reference; "
                        + "the design is value-semantic and Jackson creates fresh instances; Spring constructor injection — beans are shared by design")
@Configuration
@ConfigurationProperties(prefix = "financial.global")
public class GlobalFinancialConfig {

    private static final String PENSION = "pension";

    /**
     * Region-specific account type mappings Key: Region code (US, UK, CA, AU, IN, EU, etc.) Value:
     * List of account type identifiers for that region
     */
    private Map<String, List<String>> regionAccountTypes =
            Map.of(
                    "US", List.of("401k", "403b", "ira", "rothira", "hsa", "529", "fsa"),
                    "UK", List.of("isa", "sip", PENSION),
                    "CA", List.of("rrsp", "tfsa", "resp"),
                    "AU", List.of("super", PENSION),
                    "IN", List.of("ppf", "epf", "nps"),
                    "EU", List.of(PENSION, "retirement"));

    /**
     * Region-specific payment system keywords Key: Region code Value: List of payment system
     * identifiers
     */
    private Map<String, List<String>> regionPaymentSystems =
            Map.of(
                    "US", List.of("ach", "ppd", "web", "nacha"),
                    "EU", List.of("sepa", "sepa credit", "sepa debit", "iban"),
                    "IN", List.of("neft", "rtgs", "imps", "upi"),
                    "UK", List.of("bacs", "fps", "chaps"),
                    "CA", List.of("eft", "wire"),
                    "AU", List.of("bpay", "eft", "direct debit"));

    /**
     * International bank name patterns (by region) Key: Region code Value: List of bank name
     * patterns
     */
    private Map<String, List<String>> regionBankPatterns =
            Map.of(
                    "US",
                            List.of(
                                    "chase",
                                    "citi",
                                    "wells fargo",
                                    "bank of america",
                                    "bofa",
                                    "capital one",
                                    "discover",
                                    "amex",
                                    "american express"),
                    "UK", List.of("barclays", "hsbc", "lloyds", "natwest", "rbs", "santander"),
                    "CA", List.of("rbc", "td", "scotiabank", "bmo", "cibc"),
                    "AU", List.of("commonwealth", "anz", "westpac", "nab"),
                    "IN", List.of("sbi", "hdfc", "icici", "axis", "pnb", "kotak"),
                    "EU",
                            List.of(
                                    "deutsche bank",
                                    "bnp paribas",
                                    "societe generale",
                                    "ing",
                                    "unicredit"));

    /**
     * Credit card payment keywords by region Key: Region code Value: List of credit card payment
     * keywords
     */
    private Map<String, List<String>> regionCreditCardKeywords =
            Map.of(
                    "US",
                            List.of(
                                    "citi autopay",
                                    "citi card",
                                    "citicard",
                                    "chase credit",
                                    "wells fargo credit",
                                    "wf credit",
                                    "wf card",
                                    "bank of america",
                                    "discover autopay",
                                    "discover e-payment",
                                    "amex autopay",
                                    "american express ach",
                                    "capital one autopay",
                                    "e-payment",
                                    "epayment",
                                    "amz_storecrd_pmt",
                                    "ppcr cc",
                                    "target card srvc payment",
                                    "discover e-payment",
                                    "discover autopay",
                                    "credit card",
                                    "credit crd",
                                    "walmart card",
                                    "walmart cc"
                                    // "web id:" and "ppd id:" removed — they are generic NACHA ACH
                                    // routing identifiers present on PayPal, Venmo, payroll, and
                                    // utility ACH rows. Matching on them mis-typed every such row
                                    // as PAYMENT on checking accounts.
                                    ),
                    "UK",
                            List.of(
                                    "barclaycard payment",
                                    "hsbc card payment",
                                    "lloyds card payment",
                                    "natwest card payment",
                                    "direct debit",
                                    "dd payment"),
                    "CA",
                            List.of(
                                    "rbc card payment",
                                    "td card payment",
                                    "scotiabank card payment",
                                    "bmo card payment",
                                    "cibc card payment",
                                    "autopay"),
                    "IN",
                            List.of(
                                    "hdfc card payment",
                                    "icici card payment",
                                    "axis card payment",
                                    "sbi card payment",
                                    "autopay",
                                    "auto debit"));

    /** Default region (fallback when region cannot be determined) */
    private String defaultRegion = "US";

    public Map<String, List<String>> getRegionAccountTypes() {
        return regionAccountTypes;
    }

    public void setRegionAccountTypes(final Map<String, List<String>> regionAccountTypes) {
        this.regionAccountTypes = regionAccountTypes;
    }

    public Map<String, List<String>> getRegionPaymentSystems() {
        return regionPaymentSystems;
    }

    public void setRegionPaymentSystems(final Map<String, List<String>> regionPaymentSystems) {
        this.regionPaymentSystems = regionPaymentSystems;
    }

    public Map<String, List<String>> getRegionBankPatterns() {
        return regionBankPatterns;
    }

    public void setRegionBankPatterns(final Map<String, List<String>> regionBankPatterns) {
        this.regionBankPatterns = regionBankPatterns;
    }

    public Map<String, List<String>> getRegionCreditCardKeywords() {
        return regionCreditCardKeywords;
    }

    public void setRegionCreditCardKeywords(
            final Map<String, List<String>> regionCreditCardKeywords) {
        this.regionCreditCardKeywords = regionCreditCardKeywords;
    }

    public String getDefaultRegion() {
        return defaultRegion;
    }

    public void setDefaultRegion(final String defaultRegion) {
        this.defaultRegion = defaultRegion;
    }

    /** Gets account types for a specific region */
    public List<String> getAccountTypesForRegion(final String region) {
        return regionAccountTypes.getOrDefault(
                region != null ? region.toUpperCase(Locale.ROOT) : defaultRegion,
                regionAccountTypes.get(defaultRegion));
    }

    /** Gets payment systems for a specific region */
    public List<String> getPaymentSystemsForRegion(final String region) {
        return regionPaymentSystems.getOrDefault(
                region != null ? region.toUpperCase(Locale.ROOT) : defaultRegion,
                regionPaymentSystems.get(defaultRegion));
    }

    /** Gets credit card keywords for a specific region */
    public List<String> getCreditCardKeywordsForRegion(final String region) {
        return regionCreditCardKeywords.getOrDefault(
                region != null ? region.toUpperCase(Locale.ROOT) : defaultRegion,
                regionCreditCardKeywords.get(defaultRegion));
    }
}
