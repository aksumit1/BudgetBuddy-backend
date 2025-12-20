package com.budgetbuddy.service.plaid;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.service.PlaidCategoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Utility class for extracting data from Plaid API responses
 * Extracted from PlaidSyncService for better modularity
 */
@Component
public class PlaidDataExtractor {

    private static final Logger logger = LoggerFactory.getLogger(PlaidDataExtractor.class);
    private final PlaidCategoryMapper categoryMapper;
    private final AccountRepository accountRepository;
    private final com.budgetbuddy.service.TransactionTypeDeterminer transactionTypeDeterminer;

    public PlaidDataExtractor(PlaidCategoryMapper categoryMapper, 
                             AccountRepository accountRepository,
                             com.budgetbuddy.service.TransactionTypeDeterminer transactionTypeDeterminer) {
        this.categoryMapper = categoryMapper;
        this.accountRepository = accountRepository;
        this.transactionTypeDeterminer = transactionTypeDeterminer;
    }

    /**
     * Extract account ID from Plaid account
     */
    public String extractAccountId(final Object plaidAccount) {
        try {
            if (plaidAccount instanceof com.plaid.client.model.AccountBase) {
                com.plaid.client.model.AccountBase accountBase = (com.plaid.client.model.AccountBase) plaidAccount;
                String accountId = accountBase.getAccountId();
                if (accountId != null && !accountId.isEmpty()) {
                    return accountId;
                }
            }
            
            // Fallback: try reflection
            try {
                java.lang.reflect.Method getAccountId = plaidAccount.getClass().getMethod("getAccountId");
                Object accountId = getAccountId.invoke(plaidAccount);
                if (accountId != null) {
                    String accountIdStr = accountId.toString();
                    if (!accountIdStr.contains("class AccountBase") && !accountIdStr.contains("\n")) {
                        return accountIdStr;
                    }
                }
            } catch (Exception e) {
                logger.warn("Could not extract account ID using reflection: {}", e.getMessage());
            }
            
            logger.error("Failed to extract account ID from Plaid account. Type: {}", 
                    plaidAccount.getClass().getName());
            return null;
        } catch (Exception e) {
            logger.error("Failed to extract account ID", e);
            return null;
        }
    }

    /**
     * Extract transaction ID from Plaid transaction
     */
    public String extractTransactionId(final Object plaidTransaction) {
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                com.plaid.client.model.Transaction transaction = (com.plaid.client.model.Transaction) plaidTransaction;
                String transactionId = transaction.getTransactionId();
                if (transactionId != null && !transactionId.isEmpty()) {
                    return transactionId;
                }
            }
            
            // Fallback: try reflection
            try {
                java.lang.reflect.Method getTransactionId = plaidTransaction.getClass().getMethod("getTransactionId");
                Object idObj = getTransactionId.invoke(plaidTransaction);
                if (idObj != null) {
                    String idStr = idObj.toString();
                    if (!idStr.contains("class Transaction") && !idStr.contains("\n")) {
                        return idStr;
                    }
                }
            } catch (Exception e) {
                logger.warn("Could not extract transaction ID via reflection: {}", e.getMessage());
            }
            
            logger.error("Failed to extract transaction ID. Type: {}", 
                    plaidTransaction.getClass().getName());
            return null;
        } catch (Exception e) {
            logger.error("Failed to extract transaction ID", e);
            return null;
        }
    }

    /**
     * Extract item ID from Plaid Item object
     */
    public String extractItemId(final Object plaidItem) {
        if (plaidItem == null) {
            return null;
        }
        try {
            java.lang.reflect.Method getItemIdMethod = plaidItem.getClass().getMethod("getItemId");
            Object itemIdObj = getItemIdMethod.invoke(plaidItem);
            if (itemIdObj != null) {
                return itemIdObj.toString();
            }
        } catch (Exception e) {
            logger.debug("Could not extract itemId from Item object: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Update account from Plaid account data
     */
    public void updateAccountFromPlaid(final AccountTable account, final Object plaidAccount) {
        try {
            java.time.Instant now = java.time.Instant.now();
            account.setUpdatedAt(now);
            
            com.plaid.client.model.AccountBase accountBase = null;
            if (plaidAccount instanceof com.plaid.client.model.AccountBase) {
                accountBase = (com.plaid.client.model.AccountBase) plaidAccount;
            } else {
                try {
                    Class<?> accountBaseClass = Class.forName("com.plaid.client.model.AccountBase");
                    if (accountBaseClass.isInstance(plaidAccount)) {
                        accountBase = (com.plaid.client.model.AccountBase) plaidAccount;
                    }
                } catch (Exception e) {
                    logger.warn("Could not cast to AccountBase: {}", e.getMessage());
                }
            }
            
            if (accountBase != null) {
                // Extract account name
                String officialName = accountBase.getOfficialName();
                if (officialName != null && !officialName.isEmpty()) {
                    account.setAccountName(officialName);
                } else {
                    String name = accountBase.getName();
                    if (name != null && !name.isEmpty()) {
                        account.setAccountName(name);
                    } else {
                        String mask = accountBase.getMask();
                        if (mask != null && !mask.isEmpty()) {
                            account.setAccountName("Account " + mask);
                        } else {
                            account.setAccountName("Unknown Account");
                        }
                    }
                }
                
                // Extract account number/mask
                String mask = accountBase.getMask();
                if (mask != null && !mask.isEmpty()) {
                    account.setAccountNumber(mask);
                }
                
                // Extract account type
                if (accountBase.getType() != null) {
                    account.setAccountType(accountBase.getType().toString());
                }
                com.plaid.client.model.AccountSubtype subtype = accountBase.getSubtype();
                if (subtype != null) {
                    account.setAccountSubtype(subtype.toString());
                }
                
                // Extract balance
                if (accountBase.getBalances() != null) {
                    com.plaid.client.model.AccountBalance balances = accountBase.getBalances();
                    Double available = balances.getAvailable();
                    Double current = balances.getCurrent();
                    if (available != null) {
                        account.setBalance(java.math.BigDecimal.valueOf(available));
                    } else if (current != null) {
                        account.setBalance(java.math.BigDecimal.valueOf(current));
                    } else {
                        account.setBalance(java.math.BigDecimal.ZERO);
                    }
                    
                    // Extract currency code
                    if (balances.getIsoCurrencyCode() != null) {
                        account.setCurrencyCode(balances.getIsoCurrencyCode());
                    } else if (balances.getUnofficialCurrencyCode() != null) {
                        account.setCurrencyCode(balances.getUnofficialCurrencyCode());
                    } else {
                        account.setCurrencyCode("USD");
                    }
                } else {
                    account.setBalance(java.math.BigDecimal.ZERO);
                    account.setCurrencyCode("USD");
                }
                
                if (account.getActive() == null) {
                    account.setActive(true);
                }
            } else {
                // Fallback: use reflection
                try {
                    java.lang.reflect.Method getName = plaidAccount.getClass().getMethod("getName");
                    Object name = getName.invoke(plaidAccount);
                    if (name != null) {
                        account.setAccountName(name.toString());
                    }
                } catch (Exception e) {
                    logger.warn("Could not extract account fields using reflection: {}", e.getMessage());
                }
                
                if (account.getActive() == null) {
                    account.setActive(true);
                }
                if (account.getAccountName() == null) {
                    account.setAccountName("Unknown Account");
                }
                if (account.getBalance() == null) {
                    account.setBalance(java.math.BigDecimal.ZERO);
                }
                if (account.getCurrencyCode() == null) {
                    account.setCurrencyCode("USD");
                }
            }
        } catch (Exception e) {
            logger.error("Error updating account from Plaid data: {}", e.getMessage(), e);
            account.setUpdatedAt(java.time.Instant.now());
            if (account.getActive() == null) {
                account.setActive(true);
            }
            if (account.getAccountName() == null) {
                account.setAccountName("Unknown Account");
            }
            if (account.getBalance() == null) {
                account.setBalance(java.math.BigDecimal.ZERO);
            }
            if (account.getCurrencyCode() == null) {
                account.setCurrencyCode("USD");
            }
        }
    }

    /**
     * Update transaction from Plaid transaction data
     */
    public void updateTransactionFromPlaid(final TransactionTable transaction, final Object plaidTransaction) {
        try {
            transaction.setUpdatedAt(java.time.Instant.now());
            
            com.plaid.client.model.Transaction plaidTx = null;
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                plaidTx = (com.plaid.client.model.Transaction) plaidTransaction;
            } else {
                try {
                    Class<?> transactionClass = Class.forName("com.plaid.client.model.Transaction");
                    if (transactionClass.isInstance(plaidTransaction)) {
                        plaidTx = (com.plaid.client.model.Transaction) plaidTransaction;
                    }
                } catch (Exception e) {
                    logger.warn("Could not cast to Transaction via reflection: {}", e.getMessage());
                }
            }
            
            if (plaidTx != null) {
                // Extract amount
                if (plaidTx.getAmount() != null) {
                    transaction.setAmount(java.math.BigDecimal.valueOf(plaidTx.getAmount()));
                }
                
                // Extract merchant name
                String merchantName = plaidTx.getMerchantName();
                if (merchantName != null && !merchantName.isEmpty()) {
                    transaction.setMerchantName(merchantName);
                }
                
                // Extract description
                String name = plaidTx.getName();
                if (name != null && !name.isEmpty()) {
                    transaction.setDescription(name);
                } else if (merchantName != null && !merchantName.isEmpty()) {
                    transaction.setDescription(merchantName);
                } else {
                    transaction.setDescription("Transaction");
                }
                
                // Extract Plaid categories
                String plaidCategoryPrimary = null;
                String plaidCategoryDetailed = null;
                try {
                    var pfc = plaidTx.getPersonalFinanceCategory();
                    if (pfc != null) {
                        if (pfc.getPrimary() != null) {
                            plaidCategoryPrimary = pfc.getPrimary();
                        }
                        if (pfc.getDetailed() != null) {
                            plaidCategoryDetailed = pfc.getDetailed();
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Could not extract personal_finance_category: {}", e.getMessage());
                }
                
                // Extract payment channel BEFORE category mapping (needed for ACH credit detection)
                String paymentChannel = null;
                if (plaidTx.getPaymentChannel() != null) {
                    paymentChannel = plaidTx.getPaymentChannel().toString();
                    transaction.setPaymentChannel(paymentChannel);
                }
                
                // Extract amount BEFORE category mapping (needed for ACH credit detection)
                java.math.BigDecimal transactionAmount = transaction.getAmount();
                
                // Map categories (now with paymentChannel and amount for ACH credit detection)
                PlaidCategoryMapper.CategoryMapping categoryMapping;
                if (plaidCategoryPrimary != null || plaidCategoryDetailed != null) {
                    categoryMapping = categoryMapper.mapPlaidCategory(
                        plaidCategoryPrimary,
                        plaidCategoryDetailed,
                        transaction.getMerchantName(),
                        transaction.getDescription(),
                        paymentChannel,
                        transactionAmount
                    );
                } else {
                    categoryMapping = categoryMapper.mapPlaidCategory(
                        null,
                        null,
                        transaction.getMerchantName(),
                        transaction.getDescription(),
                        paymentChannel,
                        transactionAmount
                    );
                }
                
                transaction.setPlaidCategoryPrimary(plaidCategoryPrimary);
                transaction.setPlaidCategoryDetailed(plaidCategoryDetailed);
                
                // CRITICAL: Check if this is an HSA account transaction
                // HSA deposits (positive amounts) should be investment, debits (negative amounts) should be expenses
                String accountType = null;
                if (transaction.getAccountId() != null) {
                    Optional<AccountTable> accountOpt = accountRepository.findById(transaction.getAccountId());
                    if (accountOpt.isPresent()) {
                        accountType = accountOpt.get().getAccountType();
                    }
                }
                
                // If account type lookup failed, try by Plaid account ID
                if (accountType == null || accountType.isEmpty()) {
                    String plaidAccountId = extractAccountIdFromTransaction(plaidTransaction);
                    if (plaidAccountId != null && !plaidAccountId.isEmpty()) {
                        Optional<AccountTable> accountOpt = accountRepository.findByPlaidAccountId(plaidAccountId);
                        if (accountOpt.isPresent()) {
                            accountType = accountOpt.get().getAccountType();
                        }
                    }
                }
                
                // Apply HSA-specific categorization
                if (accountType != null && ("hsa".equalsIgnoreCase(accountType) || "healthsavingsaccount".equalsIgnoreCase(accountType))) {
                    if (transactionAmount != null && transactionAmount.compareTo(java.math.BigDecimal.ZERO) > 0) {
                        // HSA deposit (positive amount) → investment
                        transaction.setCategoryPrimary("investment");
                        transaction.setCategoryDetailed("otherInvestment"); // Generic investment category for HSA deposits
                        transaction.setCategoryOverridden(false); // Not user-overridden, system-determined
                        logger.debug("HSA deposit detected - categorized as investment: accountId={}, amount={}", 
                                transaction.getAccountId(), transactionAmount);
                    } else {
                        // HSA debit (negative amount) → expense (keep existing category or use healthcare)
                        // If category is already set to something appropriate, keep it; otherwise use healthcare
                        if (categoryMapping == null || categoryMapping.getPrimary() == null || "other".equals(categoryMapping.getPrimary())) {
                            transaction.setCategoryPrimary("healthcare");
                            transaction.setCategoryDetailed("healthcare");
                        } else {
                            // Keep the mapped category (might be healthcare, groceries, etc.)
                            transaction.setCategoryPrimary(categoryMapping.getPrimary());
                            transaction.setCategoryDetailed(categoryMapping.getDetailed());
                        }
                        transaction.setCategoryOverridden(categoryMapping != null && categoryMapping.isOverridden());
                        logger.debug("HSA debit detected - categorized as expense: accountId={}, amount={}, category={}", 
                                transaction.getAccountId(), transactionAmount, transaction.getCategoryPrimary());
                    }
                } else {
                    // Not an HSA account - use normal categorization
                    if (categoryMapping != null) {
                        transaction.setCategoryPrimary(categoryMapping.getPrimary());
                        transaction.setCategoryDetailed(categoryMapping.getDetailed());
                        transaction.setCategoryOverridden(categoryMapping.isOverridden());
                    } else {
                        // Fallback to default category if mapping is null
                        logger.warn("Category mapping is null for transaction, using default 'other' category");
                        transaction.setCategoryPrimary("other");
                        transaction.setCategoryDetailed("other");
                        transaction.setCategoryOverridden(false);
                    }
                }
                
                // Extract date
                if (plaidTx.getDate() != null) {
                    transaction.setTransactionDate(plaidTx.getDate().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
                } else {
                    transaction.setTransactionDate(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
                }
                
                // Extract currency code
                if (plaidTx.getIsoCurrencyCode() != null) {
                    transaction.setCurrencyCode(plaidTx.getIsoCurrencyCode());
                } else if (plaidTx.getUnofficialCurrencyCode() != null) {
                    transaction.setCurrencyCode(plaidTx.getUnofficialCurrencyCode());
                } else {
                    transaction.setCurrencyCode("USD");
                }
                
                // Extract pending status
                if (plaidTx.getPending() != null) {
                    transaction.setPending(plaidTx.getPending());
                }
                
                // Extract account ID
                AccountTable account = null;
                if (plaidTx.getAccountId() != null) {
                    Optional<AccountTable> accountOpt = accountRepository.findByPlaidAccountId(plaidTx.getAccountId());
                    if (accountOpt.isPresent()) {
                        account = accountOpt.get();
                        transaction.setAccountId(account.getAccountId());
                    }
                }
                
                // CRITICAL: Determine and set transaction type based on account, category, and amount
                // This must be done after all fields are set (category, amount, account)
                // BUT: Only recalculate if user hasn't explicitly overridden transactionType
                if (!Boolean.TRUE.equals(transaction.getTransactionTypeOverridden())) {
                    AccountTable accountForType = account != null ? account : (transaction.getAccountId() != null ? 
                            accountRepository.findById(transaction.getAccountId()).orElse(null) : null);
                    com.budgetbuddy.model.TransactionType transactionType = transactionTypeDeterminer.determineTransactionType(
                            accountForType,
                            transaction.getCategoryPrimary(),
                            transaction.getCategoryDetailed(),
                            transaction.getAmount()
                    );
                    transaction.setTransactionType(transactionType.name());
                    // Only set overridden=false if it's currently null (preserve existing override state)
                    if (transaction.getTransactionTypeOverridden() == null) {
                        transaction.setTransactionTypeOverridden(false);
                    }
                    String plaidTxId = plaidTx.getTransactionId();
                    logger.debug("Set transaction type to {} for Plaid transaction {} (not overridden)", transactionType, 
                            plaidTxId != null ? plaidTxId : "unknown");
                } else {
                    String plaidTxId = plaidTx.getTransactionId();
                    logger.debug("Skipping transaction type recalculation for Plaid transaction {} (user override preserved)", 
                            plaidTxId != null ? plaidTxId : "unknown");
                }
            } else {
                // Fallback: use reflection
                try {
                    java.lang.reflect.Method getAmount = plaidTransaction.getClass().getMethod("getAmount");
                    Object amount = getAmount.invoke(plaidTransaction);
                    if (amount != null && amount instanceof Number) {
                        transaction.setAmount(java.math.BigDecimal.valueOf(((Number) amount).doubleValue()));
                    }
                    
                    java.lang.reflect.Method getName = plaidTransaction.getClass().getMethod("getName");
                    Object name = getName.invoke(plaidTransaction);
                    if (name != null) {
                        transaction.setDescription(name.toString());
                    }
                } catch (Exception e) {
                    logger.error("Could not extract transaction fields via reflection: {}", e.getMessage());
                }
                
                if (transaction.getDescription() == null || transaction.getDescription().isEmpty()) {
                    transaction.setDescription("Transaction");
                }
                if (transaction.getTransactionDate() == null || transaction.getTransactionDate().isEmpty()) {
                    transaction.setTransactionDate(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
                }
                if (transaction.getAmount() == null) {
                    transaction.setAmount(java.math.BigDecimal.ZERO);
                }
                if (transaction.getCurrencyCode() == null || transaction.getCurrencyCode().isEmpty()) {
                    transaction.setCurrencyCode("USD");
                }
            }
        } catch (Exception e) {
            logger.error("Error updating transaction from Plaid data: {}", e.getMessage(), e);
            if (transaction.getTransactionDate() == null || transaction.getTransactionDate().isEmpty()) {
                transaction.setTransactionDate(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
            }
            if (transaction.getDescription() == null || transaction.getDescription().isEmpty()) {
                transaction.setDescription("Transaction");
            }
            if (transaction.getAmount() == null) {
                transaction.setAmount(java.math.BigDecimal.ZERO);
            }
            if (transaction.getCurrencyCode() == null || transaction.getCurrencyCode().isEmpty()) {
                transaction.setCurrencyCode("USD");
            }
        }
        
        // CRITICAL: Determine and set transaction type after all fields are set
        // This ensures transactionType is set even in fallback/error cases
        // BUT: Only recalculate if user hasn't explicitly overridden transactionType
        if (!Boolean.TRUE.equals(transaction.getTransactionTypeOverridden())) {
            try {
                AccountTable account = null;
                if (transaction.getAccountId() != null) {
                    Optional<AccountTable> accountOpt = accountRepository.findById(transaction.getAccountId());
                    if (accountOpt.isPresent()) {
                        account = accountOpt.get();
                    }
                }
                
                com.budgetbuddy.model.TransactionType transactionType = transactionTypeDeterminer.determineTransactionType(
                        account,
                        transaction.getCategoryPrimary(),
                        transaction.getCategoryDetailed(),
                        transaction.getAmount()
                );
                transaction.setTransactionType(transactionType.name());
                // Only set overridden=false if it's currently null (preserve existing override state)
                if (transaction.getTransactionTypeOverridden() == null) {
                    transaction.setTransactionTypeOverridden(false);
                }
                logger.debug("Set transaction type to {} for transaction {} (not overridden)", transactionType, transaction.getTransactionId());
            } catch (Exception e) {
                logger.warn("Error determining transaction type, defaulting to EXPENSE: {}", e.getMessage());
                transaction.setTransactionType(com.budgetbuddy.model.TransactionType.EXPENSE.name());
                if (transaction.getTransactionTypeOverridden() == null) {
                    transaction.setTransactionTypeOverridden(false);
                }
            }
        } else {
            logger.debug("Skipping transaction type recalculation for transaction {} (user override preserved)", transaction.getTransactionId());
        }
    }

    /**
     * Extract account ID from Plaid transaction
     */
    public String extractAccountIdFromTransaction(final Object plaidTransaction) {
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                com.plaid.client.model.Transaction transaction = (com.plaid.client.model.Transaction) plaidTransaction;
                return transaction.getAccountId();
            }
            
            // Fallback: try reflection
            java.lang.reflect.Method getAccountId = plaidTransaction.getClass().getMethod("getAccountId");
            Object accountId = getAccountId.invoke(plaidTransaction);
            if (accountId != null) {
                return accountId.toString();
            }
        } catch (Exception e) {
            logger.warn("Could not extract account ID from Plaid transaction: {}", e.getMessage());
        }
        return null;
    }
}

