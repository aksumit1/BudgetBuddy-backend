package com.budgetbuddy.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Transaction entity representing a financial transaction
 */
@Entity
@Table(name = "transactions", indexes = {
    @Index(name = "idx_transaction_user", columnList = "user_id"),
    @Index(name = "idx_transaction_account", columnList = "account_id"),
    @Index(name = "idx_transaction_date", columnList = "transaction_date"),
    @Index(name = "idx_transaction_plaid", columnList = "plaid_transaction_id"),
    @Index(name = "idx_transaction_category", columnList = "category")
})
@EntityListeners(AuditingEntityListener.class)
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 255)
    private String description;

    @Column(length = 100)
    private String merchantName;

    @Column(length = 50)
    @Enumerated(EnumType.STRING)
    private TransactionCategory category;

    @Column(nullable = false)
    private LocalDate transactionDate;

    @Column(length = 3)
    private String currencyCode = "USD";

    @Column(length = 255)
    private String plaidTransactionId; // Plaid transaction identifier

    @Column
    private Boolean pending = false;

    @Column
    private String location; // JSON string for location data

    @Column
    private String paymentChannel; // online, in_store, etc.

    @Column
    private String authorizedDate; // ISO date string

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // Constructors
    public Transaction() {
    }

    public Transaction(User user, Account account, BigDecimal amount, LocalDate transactionDate) {
        this.user = user;
        this.account = account;
        this.amount = amount;
        this.transactionDate = transactionDate;
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

    public Account getAccount() {
        return account;
    }

    public void setAccount(Account account) {
        this.account = account;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    public TransactionCategory getCategory() {
        return category;
    }

    public void setCategory(TransactionCategory category) {
        this.category = category;
    }

    public LocalDate getTransactionDate() {
        return transactionDate;
    }

    public void setTransactionDate(LocalDate transactionDate) {
        this.transactionDate = transactionDate;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public String getPlaidTransactionId() {
        return plaidTransactionId;
    }

    public void setPlaidTransactionId(String plaidTransactionId) {
        this.plaidTransactionId = plaidTransactionId;
    }

    public Boolean getPending() {
        return pending;
    }

    public void setPending(Boolean pending) {
        this.pending = pending;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getPaymentChannel() {
        return paymentChannel;
    }

    public void setPaymentChannel(String paymentChannel) {
        this.paymentChannel = paymentChannel;
    }

    public String getAuthorizedDate() {
        return authorizedDate;
    }

    public void setAuthorizedDate(String authorizedDate) {
        this.authorizedDate = authorizedDate;
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

    public enum TransactionCategory {
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
        SALARY,
        TRANSFER,
        OTHER
    }
}

