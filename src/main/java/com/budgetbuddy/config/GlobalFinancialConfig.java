package com.budgetbuddy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Global financial configuration for international support
 * Supports region-specific account types, payment systems, and category mappings
 */
@Configuration
@ConfigurationProperties(prefix = "financial.global")
public class GlobalFinancialConfig {

    /**
     * Region-specific account type mappings
     * Key: Region code (US, UK, CA, AU, IN, EU, etc.)
     * Value: List of account type identifiers for that region
     */
    private Map<String, List<String>> regionAccountTypes = Map.of(
        "US", List.of("401k", "403b", "ira", "rothira", "hsa", "529", "fsa"),
        "UK", List.of("isa", "sip", "pension"),
        "CA", List.of("rrsp", "tfsa", "resp"),
        "AU", List.of("super", "pension"),
        "IN", List.of("ppf", "epf", "nps"),
        "EU", List.of("pension", "retirement")
    );

    /**
     * Region-specific payment system keywords
     * Key: Region code
     * Value: List of payment system identifiers
     */
    private Map<String, List<String>> regionPaymentSystems = Map.of(
        "US", List.of("ach", "ppd", "web", "nacha"),
        "EU", List.of("sepa", "sepa credit", "sepa debit", "iban"),
        "IN", List.of("neft", "rtgs", "imps", "upi"),
        "UK", List.of("bacs", "fps", "chaps"),
        "CA", List.of("eft", "wire"),
        "AU", List.of("bpay", "eft", "direct debit")
    );

    /**
     * International bank name patterns (by region)
     * Key: Region code
     * Value: List of bank name patterns
     */
    private Map<String, List<String>> regionBankPatterns = Map.of(
        "US", List.of("chase", "citi", "wells fargo", "bank of america", "bofa", "capital one", "discover", "amex", "american express"),
        "UK", List.of("barclays", "hsbc", "lloyds", "natwest", "rbs", "santander"),
        "CA", List.of("rbc", "td", "scotiabank", "bmo", "cibc"),
        "AU", List.of("commonwealth", "anz", "westpac", "nab"),
        "IN", List.of("sbi", "hdfc", "icici", "axis", "pnb", "kotak"),
        "EU", List.of("deutsche bank", "bnp paribas", "societe generale", "ing", "unicredit")
    );

    /**
     * Credit card payment keywords by region
     * Key: Region code
     * Value: List of credit card payment keywords
     */
    private Map<String, List<String>> regionCreditCardKeywords = Map.of(
        "US", List.of(
            "citi autopay", "citi card", "citicard", "chase autopay", "chase credit",
            "wells fargo autopay", "wf autopay", "bofa autopay", "bank of america",
            "discover autopay", "discover e-payment", "amex autopay", "american express",
            "capital one autopay", "web id:", "ppd id:", "e-payment", "epayment"
        ),
        "UK", List.of(
            "barclaycard payment", "hsbc card payment", "lloyds card payment",
            "natwest card payment", "direct debit", "dd payment"
        ),
        "CA", List.of(
            "rbc card payment", "td card payment", "scotiabank card payment",
            "bmo card payment", "cibc card payment", "autopay"
        ),
        "IN", List.of(
            "hdfc card payment", "icici card payment", "axis card payment",
            "sbi card payment", "autopay", "auto debit"
        )
    );

    /**
     * Default region (fallback when region cannot be determined)
     */
    private String defaultRegion = "US";

    public Map<String, List<String>> getRegionAccountTypes() {
        return regionAccountTypes;
    }

    public void setRegionAccountTypes(Map<String, List<String>> regionAccountTypes) {
        this.regionAccountTypes = regionAccountTypes;
    }

    public Map<String, List<String>> getRegionPaymentSystems() {
        return regionPaymentSystems;
    }

    public void setRegionPaymentSystems(Map<String, List<String>> regionPaymentSystems) {
        this.regionPaymentSystems = regionPaymentSystems;
    }

    public Map<String, List<String>> getRegionBankPatterns() {
        return regionBankPatterns;
    }

    public void setRegionBankPatterns(Map<String, List<String>> regionBankPatterns) {
        this.regionBankPatterns = regionBankPatterns;
    }

    public Map<String, List<String>> getRegionCreditCardKeywords() {
        return regionCreditCardKeywords;
    }

    public void setRegionCreditCardKeywords(Map<String, List<String>> regionCreditCardKeywords) {
        this.regionCreditCardKeywords = regionCreditCardKeywords;
    }

    public String getDefaultRegion() {
        return defaultRegion;
    }

    public void setDefaultRegion(String defaultRegion) {
        this.defaultRegion = defaultRegion;
    }

    /**
     * Gets account types for a specific region
     */
    public List<String> getAccountTypesForRegion(String region) {
        return regionAccountTypes.getOrDefault(region != null ? region.toUpperCase() : defaultRegion,
            regionAccountTypes.get(defaultRegion));
    }

    /**
     * Gets payment systems for a specific region
     */
    public List<String> getPaymentSystemsForRegion(String region) {
        return regionPaymentSystems.getOrDefault(region != null ? region.toUpperCase() : defaultRegion,
            regionPaymentSystems.get(defaultRegion));
    }

    /**
     * Gets credit card keywords for a specific region
     */
    public List<String> getCreditCardKeywordsForRegion(String region) {
        return regionCreditCardKeywords.getOrDefault(region != null ? region.toUpperCase() : defaultRegion,
            regionCreditCardKeywords.get(defaultRegion));
    }
}

