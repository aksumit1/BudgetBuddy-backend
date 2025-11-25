package com.budgetbuddy.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Account entity representing a linked financial account
 * Note: This is a domain model. For DynamoDB persistence, use AccountTable.
 */
public class Account {

    private Long id;

    @NotNull
    private User user;

    @NotBlank
    private String accountName;

    private String institutionName;

    private AccountType accountType;

    private String accountSubtype;

    private BigDecimal balance = BigDecimal.ZERO;

    private String currencyCode = "USD";

    private String plaidAccountId; // Plaid account identifier

    private String plaidItemId; // Plaid item identifier

    private Boolean active = true;

    private LocalDateTime lastSyncedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // Constructors
    public Account() {
    }

    public Account(final User user, final String accountName, final AccountType accountType) {
        this.user = user;
        this.accountName = accountName;
        this.accountType = accountType;
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

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(final String accountName) {
        this.accountName = accountName;
    }

    public String getInstitutionName() {
        return institutionName;
    }

    public void setInstitutionName(final String institutionName) {
        this.institutionName = institutionName;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public void setAccountType(final AccountType accountType) {
        this.accountType = accountType;
    }

    public String getAccountSubtype() {
        return accountSubtype;
    }

    public void setAccountSubtype(final String accountSubtype) {
        this.accountSubtype = accountSubtype;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(final BigDecimal balance) {
        this.balance = balance;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(final String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public String getPlaidAccountId() {
        return plaidAccountId;
    }

    public void setPlaidAccountId(final String plaidAccountId) {
        this.plaidAccountId = plaidAccountId;
    }

    public String getPlaidItemId() {
        return plaidItemId;
    }

    public void setPlaidItemId(final String plaidItemId) {
        this.plaidItemId = plaidItemId;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(final Boolean active) {
        this.active = active;
    }

    public LocalDateTime getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(final LocalDateTime lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
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

    public enum AccountType {
        CHECKING,
        SAVINGS,
        CREDIT_CARD,
        INVESTMENT,
        LOAN,
        MORTGAGE,
        OTHER
    }
}

