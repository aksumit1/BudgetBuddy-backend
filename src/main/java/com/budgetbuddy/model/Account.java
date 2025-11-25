package com.budgetbuddy.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Account entity representing a linked financial account
 */
@Entity
@Table(name = "accounts", indexes = {
    @Index(name = "idx_account_user", columnList = "user_id"),
    @Index(name = "idx_account_plaid", columnList = "plaid_account_id"),
    @Index(name = "idx_account_type", columnList = "account_type")
})
@EntityListeners(AuditingEntityListener.class)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @NotBlank
    @Column(nullable = false, length = 255)
    private String accountName;

    @Column(length = 255)
    private String institutionName;

    @Column(length = 50)
    @Enumerated(EnumType.STRING)
    private AccountType accountType;

    @Column(length = 50)
    private String accountSubtype;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(length = 3)
    private String currencyCode = "USD";

    @Column(length = 255)
    private String plaidAccountId; // Plaid account identifier

    @Column(length = 255)
    private String plaidItemId; // Plaid item identifier

    @Column(nullable = false)
    private Boolean active = true;

    @Column
    private LocalDateTime lastSyncedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // Constructors
    public Account() {
    }

    public Account(User user, String accountName, AccountType accountType) {
        this.user = user;
        this.accountName = accountName;
        this.accountType = accountType;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getInstitutionName() {
        return institutionName;
    }

    public void setInstitutionName(String institutionName) {
        this.institutionName = institutionName;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public void setAccountType(AccountType accountType) {
        this.accountType = accountType;
    }

    public String getAccountSubtype() {
        return accountSubtype;
    }

    public void setAccountSubtype(String accountSubtype) {
        this.accountSubtype = accountSubtype;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public String getPlaidAccountId() {
        return plaidAccountId;
    }

    public void setPlaidAccountId(String plaidAccountId) {
        this.plaidAccountId = plaidAccountId;
    }

    public String getPlaidItemId() {
        return plaidItemId;
    }

    public void setPlaidItemId(String plaidItemId) {
        this.plaidItemId = plaidItemId;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public LocalDateTime getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(LocalDateTime lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
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

