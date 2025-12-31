package com.budgetbuddy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * P2: Configuration for import category keywords and patterns
 * Moves hard-coded keywords to configuration for easier maintenance and internationalization
 */
@Configuration
@ConfigurationProperties(prefix = "import.category")
public class ImportCategoryConfig {

    /**
     * Credit card payment keywords
     */
    private List<String> creditCardKeywords = List.of(
        "citi autopay", "citi card", "citicard", "citi credit", "citicardap",
        "chase autopay", "chase credit", "chase card",
        "amex autopay", "american express",
        "discover autopay", "discover e-payment", "discover e-payment",
        "wells fargo autopay", "wf autopay", "wf credit card",
        "bofa autopay", "bank of america",
        "capital one autopay",
        "web id:", "web id", "ppd id:",
        "e-payment", "epayment", "e payment",
        "amazon store card", "amazon storecard", "amz store card",
        "amz storecrd", "amz_storecrd_pmt", "amz storecrd pmt",
        "store card payment", "storecard payment"
    );

    /**
     * Interest payment keywords (including misspellings)
     */
    private List<String> interestKeywords = List.of(
        "interest", "intrst", "intr ", "intrest", "intr payment",
        "intrst payment", "intrst pymnt", "intr pymnt"
    );

    /**
     * Utility company keywords
     */
    private List<String> utilityKeywords = List.of(
        "utility", "utilities", "energy", "electric", "gas", "water",
        "billpay", "bill pay", "ppd id"
    );

    /**
     * Investment firm keywords
     */
    private List<String> investmentFirms = List.of(
        "morgan stanley", "morganstanley", "fidelity", "vanguard",
        "schwab", "charles schwab", "td ameritrade", "etrade",
        "robinhood", "wealthfront", "betterment"
    );

    public List<String> getCreditCardKeywords() {
        return creditCardKeywords;
    }

    public void setCreditCardKeywords(List<String> creditCardKeywords) {
        this.creditCardKeywords = creditCardKeywords;
    }

    public List<String> getInterestKeywords() {
        return interestKeywords;
    }

    public void setInterestKeywords(List<String> interestKeywords) {
        this.interestKeywords = interestKeywords;
    }

    public List<String> getUtilityKeywords() {
        return utilityKeywords;
    }

    public void setUtilityKeywords(List<String> utilityKeywords) {
        this.utilityKeywords = utilityKeywords;
    }

    public List<String> getInvestmentFirms() {
        return investmentFirms;
    }

    public void setInvestmentFirms(List<String> investmentFirms) {
        this.investmentFirms = investmentFirms;
    }
}

