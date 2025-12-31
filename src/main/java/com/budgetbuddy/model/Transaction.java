package com.budgetbuddy.model;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Transaction entity representing a financial transaction
 * Note: This is a domain model. For DynamoDB persistence, use TransactionTable.
 */
public class Transaction implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    @NotNull
    private User user;

    @NotNull
    private Account account;

    private BigDecimal amount;

    private String description;

    private String merchantName;

    private TransactionCategory category;

    private LocalDate transactionDate;

    private String currencyCode = "USD";

    private String plaidTransactionId; // Plaid transaction identifier

    private Boolean pending = false;

    private String location; // JSON string for location data

    private String paymentChannel; // online, in_store, etc.

    private String authorizedDate; // ISO date string

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // Constructors
    public Transaction() {
    }

    public Transaction(final User user, final Account account, final BigDecimal amount, final LocalDate transactionDate) {
        this.user = user;
        this.account = account;
        this.amount = amount;
        this.transactionDate = transactionDate;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(final Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(final User user) {
        this.user = user;
    }

    public Account getAccount() {
        return account;
    }

    public void setAccount(final Account account) {
        this.account = account;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(final BigDecimal amount) {
        this.amount = amount;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(final String merchantName) {
        this.merchantName = merchantName;
    }

    public TransactionCategory getCategory() {
        return category;
    }

    public void setCategory(final TransactionCategory category) {
        this.category = category;
    }

    public LocalDate getTransactionDate() {
        return transactionDate;
    }

    public void setTransactionDate(final LocalDate transactionDate) {
        this.transactionDate = transactionDate;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(final String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public String getPlaidTransactionId() {
        return plaidTransactionId;
    }

    public void setPlaidTransactionId(final String plaidTransactionId) {
        this.plaidTransactionId = plaidTransactionId;
    }

    public Boolean getPending() {
        return pending;
    }

    public void setPending(final Boolean pending) {
        this.pending = pending;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(final String location) {
        this.location = location;
    }

    public String getPaymentChannel() {
        return paymentChannel;
    }

    public void setPaymentChannel(final String paymentChannel) {
        this.paymentChannel = paymentChannel;
    }

    public String getAuthorizedDate() {
        return authorizedDate;
    }

    public void setAuthorizedDate(final String authorizedDate) {
        this.authorizedDate = authorizedDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(final LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public enum TransactionCategory {
        // Expense categories
        FOOD_DINING,
        TRANSPORTATION,
        SHOPPING,
        ENTERTAINMENT,
        BILLS_UTILITIES,
        TRAVEL,
        HEALTHCARE,
        EDUCATION,
        GROCERIES,
        GAS_STATIONS,
        RENT,
        SUBSCRIPTIONS, // Recurring subscriptions (streaming, software, etc.)
        UTILITIES, // Utilities (electricity, water, gas, internet, phone)
        PAYMENT, // Credit card payments, recurring ACH payments, automatic payments
        PET, // Pet supplies, pet care, veterinary expenses
        CASH, // Cash withdrawals, ATM transactions
        OTHER,
        
        // Income categories
        SALARY,
        INTEREST, // Interest income
        DIVIDEND, // Dividend income
        STIPEND, // Stipend income
        RENT_INCOME, // Rental income
        TIPS, // Tips income
        DEPOSIT, // Generic deposit/ACH credit
        OTHER_INCOME, // Other income types
        INCOME, // Legacy - use specific income categories instead
        
        // Investment categories
        INVESTMENT, // Legacy - use specific investment types
        CD, // Certificate of Deposit
        BONDS, // Bonds
        MUNICIPAL_BONDS, // Municipal bonds
        T_BILLS, // Treasury Bills
        STOCKS, // Stocks
        FOUR_ZERO_ONE_K, // 401K
        FIVE_TWO_NINE, // 529 plan
        IRA, // IRA
        MUTUAL_FUNDS, // Mutual funds
        ETF, // ETF
        MONEY_MARKET, // Money Market
        PRECIOUS_METALS, // Precious Metals
        CRYPTO, // Crypto
        OTHER_INVESTMENT, // Other investment types
        
        // Transfer (legacy - use payment for transfers)
        TRANSFER
    }
}

