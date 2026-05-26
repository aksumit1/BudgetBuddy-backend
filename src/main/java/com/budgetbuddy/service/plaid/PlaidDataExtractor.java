package com.budgetbuddy.service.plaid;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.service.TransactionTypeCategoryService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Utility class for extracting data from Plaid API responses Extracted from PlaidSyncService for
 * better modularity
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
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Component
public class PlaidDataExtractor {

    private static final String TRANSACTION = "Transaction";

    private static final String UNKNOWN_ACCOUNT = "Unknown Account";

    private static final String OTHER = "other";

    private static final String PAYMENT = "payment";
    private static final String USD = "USD";

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaidDataExtractor.class);
    private final AccountRepository accountRepository;
    private final TransactionTypeCategoryService transactionTypeCategoryService;

    public PlaidDataExtractor(
            final AccountRepository accountRepository,
            final TransactionTypeCategoryService transactionTypeCategoryService) {
        this.accountRepository = accountRepository;
        this.transactionTypeCategoryService = transactionTypeCategoryService;
    }

    /** Extract account ID from Plaid account */
    public String extractAccountId(final Object plaidAccount) {
        try {
            if (plaidAccount instanceof com.plaid.client.model.AccountBase) {
                final com.plaid.client.model.AccountBase accountBase =
                        (com.plaid.client.model.AccountBase) plaidAccount;
                final String accountId = accountBase.getAccountId();
                if (accountId != null && !accountId.isEmpty()) {
                    return accountId;
                }
            }

            // Fallback: try reflection
            try {
                final java.lang.reflect.Method getAccountId =
                        plaidAccount.getClass().getMethod("getAccountId");
                final Object accountId = getAccountId.invoke(plaidAccount);
                if (accountId != null) {
                    final String accountIdStr = accountId.toString();
                    if (!accountIdStr.contains("class AccountBase")
                            && !accountIdStr.contains("\n")) {
                        return accountIdStr;
                    }
                }
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Could not extract account ID using reflection: {}", e.getMessage());
                }
            }

            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Failed to extract account ID from Plaid account. Type: {}",
                        plaidAccount.getClass().getName());
            }
            return null;
        } catch (Exception e) {
            LOGGER.error("Failed to extract account ID", e);
            return null;
        }
    }

    /** Extract transaction ID from Plaid transaction */
    public String extractTransactionId(final Object plaidTransaction) {
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                final com.plaid.client.model.Transaction transaction =
                        (com.plaid.client.model.Transaction) plaidTransaction;
                final String transactionId = transaction.getTransactionId();
                if (transactionId != null && !transactionId.isEmpty()) {
                    return transactionId;
                }
            }

            // Fallback: try reflection
            try {
                final java.lang.reflect.Method getTransactionId =
                        plaidTransaction.getClass().getMethod("getTransactionId");
                final Object idObj = getTransactionId.invoke(plaidTransaction);
                if (idObj != null) {
                    final String idStr = idObj.toString();
                    if (!idStr.contains("class Transaction") && !idStr.contains("\n")) {
                        return idStr;
                    }
                }
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Could not extract transaction ID via reflection: {}", e.getMessage());
                }
            }

            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Failed to extract transaction ID. Type: {}",
                        plaidTransaction.getClass().getName());
            }
            return null;
        } catch (Exception e) {
            LOGGER.error("Failed to extract transaction ID", e);
            return null;
        }
    }

    /**
     * Extract the {@code pending_transaction_id} from a Plaid Transaction.
     *
     * <p>When a pending transaction posts, Plaid issues a <em>new</em> record with a fresh {@code
     * transaction_id} and sets {@code pending_transaction_id} to the id of the pending row it
     * replaces. Handling this correctly is what prevents the "one purchase shown twice"
     * balance-drift bug: we look up our local pending row by this id and mutate it in place instead
     * of inserting a second record.
     *
     * @return the pending id, or {@code null} if this is not a posted-of-pending record.
     */
    public String extractPendingTransactionId(final Object plaidTransaction) {
        if (plaidTransaction == null) {
            return null;
        }
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                final String id =
                        ((com.plaid.client.model.Transaction) plaidTransaction)
                                .getPendingTransactionId();
                return id != null && !id.isEmpty() ? id : null;
            }
            final Object idObj =
                    plaidTransaction
                            .getClass()
                            .getMethod("getPendingTransactionId")
                            .invoke(plaidTransaction);
            if (idObj != null) {
                final String s = idObj.toString();
                return s.isEmpty() ? null : s;
            }
        } catch (NoSuchMethodException e) {
            // Older SDK — caller falls back to no-link behaviour.
        } catch (Exception e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("extractPendingTransactionId failed: {}", e.getMessage());
            }
        }
        return null;
    }

    /** Extract item ID from Plaid Item object */
    public String extractItemId(final Object plaidItem) {
        if (plaidItem == null) {
            return null;
        }
        try {
            final java.lang.reflect.Method getItemIdMethod =
                    plaidItem.getClass().getMethod("getItemId");
            final Object itemIdObj = getItemIdMethod.invoke(plaidItem);
            if (itemIdObj != null) {
                return itemIdObj.toString();
            }
        } catch (Exception e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Could not extract itemId from Item object: {}", e.getMessage());
            }
        }
        return null;
    }

    /** Update account from Plaid account data */
    public void updateAccountFromPlaid(final AccountTable account, final Object plaidAccount) {
        try {
            final java.time.Instant now = java.time.Instant.now();
            account.setUpdatedAt(now);

            com.plaid.client.model.AccountBase accountBase = null;
            if (plaidAccount instanceof com.plaid.client.model.AccountBase) {
                accountBase = (com.plaid.client.model.AccountBase) plaidAccount;
            } else {
                try {
                    final Class<?> accountBaseClass =
                            Class.forName("com.plaid.client.model.AccountBase");
                    if (accountBaseClass.isInstance(plaidAccount)) {
                        accountBase = (com.plaid.client.model.AccountBase) plaidAccount;
                    }
                } catch (Exception e) {
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn("Could not cast to AccountBase: {}", e.getMessage());
                    }
                }
            }

            if (accountBase != null) {
                // Extract account name — but only if the user hasn't
                // renamed the account themselves. Otherwise a sync after a
                // user rename silently reverts "Main Checking" back to
                // "Checking ...1234". See AccountTable.accountNameOverridden.
                final boolean userRenamed = Boolean.TRUE.equals(account.getAccountNameOverridden());
                if (!userRenamed) {
                    final String officialName = accountBase.getOfficialName();
                    if (officialName != null && !officialName.isEmpty()) {
                        account.setAccountName(officialName);
                    } else {
                        final String name = accountBase.getName();
                        if (name != null && !name.isEmpty()) {
                            account.setAccountName(name);
                        } else {
                            final String mask = accountBase.getMask();
                            if (mask != null && !mask.isEmpty()) {
                                account.setAccountName("Account " + mask);
                            } else {
                                account.setAccountName(UNKNOWN_ACCOUNT);
                            }
                        }
                    }
                }

                // Extract account number/mask
                final String mask = accountBase.getMask();
                if (mask != null && !mask.isEmpty()) {
                    account.setAccountNumber(mask);
                }

                // Extract account type
                String plaidAccountType = null;
                if (accountBase.getType() != null) {
                    plaidAccountType = accountBase.getType().toString();
                    account.setAccountType(plaidAccountType);
                }
                final com.plaid.client.model.AccountSubtype subtype = accountBase.getSubtype();
                if (subtype != null) {
                    account.setAccountSubtype(subtype.toString());
                }

                // CRITICAL FIX: Detect investment accounts (bonds, treasury, Certificate/CD) from
                // account name
                // This handles cases where Plaid categorizes these as "other" or "depository"
                final String accountName = account.getAccountName();
                final String institutionName = account.getInstitutionName();
                final String detectedAccountType =
                        detectInvestmentAccountType(accountName, institutionName, plaidAccountType);
                if (detectedAccountType != null) {
                    account.setAccountType(detectedAccountType);
                    LOGGER.debug(
                            "Detected investment account type '{}' from account name/institution: accountName='{}', institutionName='{}', originalType='{}'",
                            detectedAccountType,
                            accountName,
                            institutionName,
                            plaidAccountType);
                }

                // Extract balance
                if (accountBase.getBalances() != null) {
                    final com.plaid.client.model.AccountBalance balances =
                            accountBase.getBalances();
                    final Double available = balances.getAvailable();
                    final Double current = balances.getCurrent();
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
                        account.setCurrencyCode(USD);
                    }
                } else {
                    account.setBalance(java.math.BigDecimal.ZERO);
                    account.setCurrencyCode(USD);
                }

                if (account.getActive() == null) {
                    account.setActive(true);
                }
            } else {
                // Fallback: use reflection
                try {
                    final java.lang.reflect.Method getName =
                            plaidAccount.getClass().getMethod("getName");
                    final Object name = getName.invoke(plaidAccount);
                    if (name != null) {
                        account.setAccountName(name.toString());
                    }
                } catch (Exception e) {
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn(
                                "Could not extract account fields using reflection: {}",
                                e.getMessage());
                    }
                }

                if (account.getActive() == null) {
                    account.setActive(true);
                }
                if (account.getAccountName() == null) {
                    account.setAccountName(UNKNOWN_ACCOUNT);
                }
                if (account.getBalance() == null) {
                    account.setBalance(java.math.BigDecimal.ZERO);
                }
                if (account.getCurrencyCode() == null) {
                    account.setCurrencyCode(USD);
                }
            }
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Error updating account from Plaid data: {}", e.getMessage(), e);
            }
            account.setUpdatedAt(java.time.Instant.now());
            if (account.getActive() == null) {
                account.setActive(true);
            }
            if (account.getAccountName() == null) {
                account.setAccountName(UNKNOWN_ACCOUNT);
            }
            if (account.getBalance() == null) {
                account.setBalance(java.math.BigDecimal.ZERO);
            }
            if (account.getCurrencyCode() == null) {
                account.setCurrencyCode(USD);
            }
        }
    }

    /** Update transaction from Plaid transaction data */
    public void updateTransactionFromPlaid(
            final TransactionTable transaction, final Object plaidTransaction) {
        try {
            transaction.setUpdatedAt(java.time.Instant.now());

            com.plaid.client.model.Transaction plaidTx = null;
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                plaidTx = (com.plaid.client.model.Transaction) plaidTransaction;
            } else {
                try {
                    final Class<?> transactionClass =
                            Class.forName("com.plaid.client.model.Transaction");
                    if (transactionClass.isInstance(plaidTransaction)) {
                        plaidTx = (com.plaid.client.model.Transaction) plaidTransaction;
                    }
                } catch (Exception e) {
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn(
                                "Could not cast to Transaction via reflection: {}", e.getMessage());
                    }
                }
            }

            if (plaidTx != null) {
                // Extract raw amount from Plaid
                java.math.BigDecimal rawAmount = null;
                if (plaidTx.getAmount() != null) {
                    rawAmount = java.math.BigDecimal.valueOf(plaidTx.getAmount());
                }

                // CRITICAL: Normalize Plaid amount before storing
                // Plaid uses reverse convention for checking/savings/debit/money_market accounts:
                // -ve = income, +ve = expense
                // We normalize to standard convention: +ve = income, -ve = expense
                // This ensures both Plaid and Import transactions use the same convention
                AccountTable account = null;
                if (transaction.getAccountId() != null) {
                    final Optional<AccountTable> accountOpt =
                            accountRepository.findById(transaction.getAccountId());
                    if (accountOpt.isPresent()) {
                        account = accountOpt.get();
                    }
                }

                // If account lookup failed, try by Plaid account ID
                if (account == null && rawAmount != null) {
                    final String plaidAccountId = extractAccountIdFromTransaction(plaidTransaction);
                    if (plaidAccountId != null && !plaidAccountId.isEmpty()) {
                        final Optional<AccountTable> accountOpt =
                                accountRepository.findByPlaidAccountId(plaidAccountId);
                        if (accountOpt.isPresent()) {
                            account = accountOpt.get();
                            transaction.setAccountId(account.getAccountId());
                        }
                    }
                }

                // Normalize amount based on account type
                final java.math.BigDecimal normalizedAmount =
                        normalizePlaidAmount(rawAmount, account);
                transaction.setAmount(normalizedAmount);

                // Extract merchant name
                final String merchantName = plaidTx.getMerchantName();
                if (merchantName != null && !merchantName.isEmpty()) {
                    transaction.setMerchantName(merchantName);
                }

                // Extract description
                final String name = plaidTx.getName();
                if (name != null && !name.isEmpty()) {
                    transaction.setDescription(name);
                } else if (merchantName != null && !merchantName.isEmpty()) {
                    transaction.setDescription(merchantName);
                } else {
                    transaction.setDescription(TRANSACTION);
                }

                // Extract Plaid categories
                String plaidCategoryPrimary = null;
                String plaidCategoryDetailed = null;
                try {
                    final var pfc = plaidTx.getPersonalFinanceCategory();
                    if (pfc != null) {
                        if (pfc.getPrimary() != null) {
                            plaidCategoryPrimary = pfc.getPrimary();
                        }
                        if (pfc.getDetailed() != null) {
                            plaidCategoryDetailed = pfc.getDetailed();
                        }
                    }
                } catch (Exception e) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Could not extract personal_finance_category: {}", e.getMessage());
                    }
                }

                // Extract payment channel
                String paymentChannel = null;
                if (plaidTx.getPaymentChannel() != null) {
                    paymentChannel = plaidTx.getPaymentChannel().toString();
                    transaction.setPaymentChannel(paymentChannel);
                }

                // Use normalized amount (already set above)
                java.math.BigDecimal transactionAmount = transaction.getAmount();

                // CRITICAL: Write Plaid categories to importer fields (raw Plaid categories)
                transaction.setImporterCategoryPrimary(plaidCategoryPrimary);
                transaction.setImporterCategoryDetailed(plaidCategoryDetailed);

                // Account is already looked up above for amount normalization

                // CRITICAL: HSA account categorization logic
                // HSA deposits (positive amounts) should be categorized as "investment"
                // HSA debits (negative amounts) should be categorized as "healthcare" (if category
                // is generic/other)
                final boolean isHSAAccount =
                        account != null
                                && account.getAccountType() != null
                                && account.getAccountType()
                                        .toLowerCase(Locale.ROOT)
                                        .contains("hsa");

                // CRITICAL: Use unified service to determine internal categories (hybrid logic)
                // Only if user hasn't overridden categories
                if (!Boolean.TRUE.equals(transaction.getCategoryOverridden())) {
                    final TransactionTypeCategoryService.TypeResult preTypeResult =
                            transactionTypeCategoryService.determineTransactionType(
                                    account,
                                    null,
                                    null,
                                    transactionAmount,
                                    null,
                                    transaction.getDescription(),
                                    paymentChannel);
                    final TransactionTypeCategoryService.CategoryResult categoryResult =
                            transactionTypeCategoryService.determineCategory(
                                    plaidCategoryPrimary, // Raw Plaid categories
                                    plaidCategoryDetailed,
                                    account,
                                    transaction.getMerchantName(),
                                    transaction.getDescription(),
                                    transactionAmount,
                                    paymentChannel,
                                    null, // No transaction type indicator for Plaid
                                    "PLAID",
                                    preTypeResult != null
                                            ? preTypeResult.getTransactionType()
                                            : null);

                    if (categoryResult != null) {
                        String determinedPrimary = categoryResult.getCategoryPrimary();
                        String determinedDetailed = categoryResult.getCategoryDetailed();

                        // CRITICAL: Override category for HSA accounts
                        // IMPORTANT: Use rawAmount (before normalization) for HSA logic since
                        // normalization reverses sign
                        // Plaid convention: +ve = expense, -ve = income
                        // After normalization: -ve = expense, +ve = income
                        // So for HSA: rawAmount > 0 means deposit (should be investment), rawAmount
                        // < 0 means debit (should be healthcare)
                        if (isHSAAccount && rawAmount != null) {
                            if (rawAmount.compareTo(java.math.BigDecimal.ZERO) > 0) {
                                // HSA deposit (positive raw amount from Plaid) -> investment
                                determinedPrimary = "investment";
                                determinedDetailed = "otherInvestment";
                                LOGGER.debug(
                                        "HSA deposit categorized as investment: rawAmount={}, normalizedAmount={}",
                                        rawAmount,
                                        transactionAmount);
                            } else if (rawAmount.compareTo(java.math.BigDecimal.ZERO) < 0) {
                                // HSA debit (negative raw amount from Plaid) -> healthcare
                                // Only override if category is generic (other) or null
                                if (determinedPrimary == null || OTHER.equals(determinedPrimary)) {
                                    determinedPrimary = "healthcare";
                                    determinedDetailed = "healthcare";
                                    LOGGER.debug(
                                            "HSA debit categorized as healthcare: rawAmount={}, normalizedAmount={}",
                                            rawAmount,
                                            transactionAmount);
                                } else {
                                    // Keep existing category if it's already specific (e.g., from
                                    // Plaid)
                                    LOGGER.debug(
                                            "HSA debit keeping existing category: {}",
                                            determinedPrimary);
                                }
                            }
                        }

                        // CRITICAL: Check if determined category differs from Plaid's category
                        // If it does, this is an internal override (parser/rules/ML overrode Plaid)
                        // Set categoryOverridden=true to prevent Plaid re-sync from overriding it
                        boolean isInternalOverride = false;

                        // CRITICAL: Check if determined category differs from Plaid's category
                        // The CategoryResult.source field indicates where the category came from:
                        // - "PLAID" = Used Plaid category directly (no override)
                        // - "HYBRID", "ML", "PARSER", "RULE", "FALLBACK_*", "IOS_FALLBACK" =
                        // Internal override
                        // - "IMPORTER" = Used importer category (for imports, not Plaid)

                        final String source = categoryResult.getSource();
                        if (source != null && !"PLAID".equals(source)) {
                            // Source indicates internal logic overrode Plaid category
                            // This means parser/rules/ML determined a different category than Plaid
                            isInternalOverride = true;
                            LOGGER.debug(
                                    "Internal override detected from source: {} (determined: '{}', Plaid was: '{}')",
                                    source,
                                    determinedPrimary,
                                    plaidCategoryPrimary);
                        }
                        // If source is "PLAID", it means we used Plaid's category directly (no
                        // override)

                        transaction.setCategoryPrimary(determinedPrimary);
                        transaction.setCategoryDetailed(determinedDetailed);
                        transaction.setCategoryOverridden(isInternalOverride);

                        // CRITICAL: Credit card and loan payment amount adjustment
                        // For credit card and loan accounts, if category is "payment" and amount is
                        // negative,
                        // make it positive (payments reduce debt, so they should be positive)
                        if (account != null && account.getAccountType() != null) {
                            final String accountType =
                                    account.getAccountType().toLowerCase(Locale.ROOT);
                            final boolean isCreditCardOrLoan =
                                    accountType.contains("credit")
                                            || accountType.contains("loan")
                                            || accountType.contains("mortgage")
                                            || accountType.contains("creditline");

                            final boolean isPaymentCategory =
                                    PAYMENT.equalsIgnoreCase(determinedPrimary)
                                            || PAYMENT.equalsIgnoreCase(determinedDetailed);

                            if (isCreditCardOrLoan
                                    && isPaymentCategory
                                    && transactionAmount != null
                                    && transactionAmount.compareTo(java.math.BigDecimal.ZERO) < 0) {
                                // Negate the amount (making it positive)
                                final java.math.BigDecimal adjustedAmount =
                                        transactionAmount.negate();
                                transaction.setAmount(adjustedAmount);
                                LOGGER.debug(
                                        "Adjusted payment amount for {} account: {} → {} (payment category)",
                                        accountType,
                                        transactionAmount,
                                        adjustedAmount);
                                // Update transactionAmount for consistency in logging below
                                transactionAmount = adjustedAmount;
                            }
                        }

                        if (isInternalOverride) {
                            if (LOGGER.isInfoEnabled()) {
                                LOGGER.info(
                                        "✅ Internal category override applied: Plaid='{}' → Determined='{}' (source: {}, confidence: {:.2f}). "
                                                + "This override will be preserved during Plaid re-sync.",
                                        plaidCategoryPrimary != null
                                                ? plaidCategoryPrimary
                                                : "null",
                                        determinedPrimary,
                                        categoryResult.getSource(),
                                        categoryResult.getConfidence());
                            }
                        } else {
                            if (LOGGER.isDebugEnabled()) {
                                LOGGER.debug(
                                        "Determined category matches Plaid: {} / {} (source: {}, confidence: {:.2f})",
                                        determinedPrimary,
                                        determinedDetailed,
                                        categoryResult.getSource(),
                                        categoryResult.getConfidence());
                            }
                        }
                    } else {
                        // Fallback
                        transaction.setCategoryPrimary(OTHER);
                        transaction.setCategoryDetailed(OTHER);
                        transaction.setCategoryOverridden(false);
                    }
                } else {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Category already overridden by user, preserving: {} / {}",
                                transaction.getCategoryPrimary(),
                                transaction.getCategoryDetailed());
                    }

                    // CRITICAL: Credit card and loan payment amount adjustment (even if category
                    // was user-overridden)
                    // For credit card and loan accounts, if category is "payment" and amount is
                    // negative,
                    // make it positive (payments reduce debt, so they should be positive)
                    if (account != null && account.getAccountType() != null) {
                        final String accountType =
                                account.getAccountType().toLowerCase(Locale.ROOT);
                        final boolean isCreditCardOrLoan =
                                accountType.contains("credit")
                                        || accountType.contains("loan")
                                        || accountType.contains("mortgage")
                                        || accountType.contains("creditline");

                        final String existingCategoryPrimary = transaction.getCategoryPrimary();
                        final String existingCategoryDetailed = transaction.getCategoryDetailed();
                        final boolean isPaymentCategory =
                                PAYMENT.equalsIgnoreCase(existingCategoryPrimary)
                                        || PAYMENT.equalsIgnoreCase(existingCategoryDetailed);

                        if (isCreditCardOrLoan
                                && isPaymentCategory
                                && transactionAmount != null
                                && transactionAmount.compareTo(java.math.BigDecimal.ZERO) < 0) {
                            // Negate the amount (making it positive)
                            final java.math.BigDecimal adjustedAmount = transactionAmount.negate();
                            transaction.setAmount(adjustedAmount);
                            LOGGER.debug(
                                    "Adjusted payment amount for {} account (user-overridden category): {} → {} (payment category)",
                                    accountType,
                                    transactionAmount,
                                    adjustedAmount);
                        }
                    }
                }

                // Extract date
                if (plaidTx.getDate() != null) {
                    transaction.setTransactionDate(
                            plaidTx.getDate()
                                    .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
                } else {
                    transaction.setTransactionDate(
                            java.time.LocalDate.now()
                                    .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
                }

                // Extract currency code
                if (plaidTx.getIsoCurrencyCode() != null) {
                    transaction.setCurrencyCode(plaidTx.getIsoCurrencyCode());
                } else if (plaidTx.getUnofficialCurrencyCode() != null) {
                    transaction.setCurrencyCode(plaidTx.getUnofficialCurrencyCode());
                } else {
                    transaction.setCurrencyCode(USD);
                }

                // Extract pending status
                if (plaidTx.getPending() != null) {
                    transaction.setPending(plaidTx.getPending());
                }

                // CRITICAL: Determine and set transaction type using unified service (hybrid logic)
                // This must be done after all fields are set (category, amount, account)
                // BUT: Only recalculate if user hasn't explicitly overridden transactionType
                if (!Boolean.TRUE.equals(transaction.getTransactionTypeOverridden())) {
                    final TransactionTypeCategoryService.TypeResult typeResult =
                            transactionTypeCategoryService.determineTransactionType(
                                    account,
                                    transaction.getCategoryPrimary(),
                                    transaction.getCategoryDetailed(),
                                    transactionAmount,
                                    null, // No transaction type indicator for Plaid
                                    transaction.getDescription(),
                                    paymentChannel);

                    if (typeResult != null) {
                        transaction.setTransactionType(typeResult.getTransactionType().name());
                        // Only set overridden=false if it's currently null (preserve existing
                        // override state)
                        if (transaction.getTransactionTypeOverridden() == null) {
                            transaction.setTransactionTypeOverridden(false);
                        }
                        final String plaidTxId = plaidTx.getTransactionId();
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug(
                                    "Set transaction type to {} for Plaid transaction {} (source: {}, confidence: {:.2f})",
                                    typeResult.getTransactionType(),
                                    plaidTxId != null ? plaidTxId : "unknown",
                                    typeResult.getSource(),
                                    typeResult.getConfidence());
                        }
                    }
                } else {
                    final String plaidTxId = plaidTx.getTransactionId();
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Skipping transaction type recalculation for Plaid transaction {} (user override preserved)",
                                plaidTxId != null ? plaidTxId : "unknown");
                    }
                }
            } else {
                // Fallback: use reflection
                try {
                    final java.lang.reflect.Method getAmount =
                            plaidTransaction.getClass().getMethod("getAmount");
                    final Object amount = getAmount.invoke(plaidTransaction);
                    java.math.BigDecimal rawAmount = null;
                    if (amount instanceof Number n) {
                        rawAmount = java.math.BigDecimal.valueOf(n.doubleValue());
                    }

                    // Normalize amount (try to get account if possible)
                    AccountTable account = null;
                    if (transaction.getAccountId() != null) {
                        final Optional<AccountTable> accountOpt =
                                accountRepository.findById(transaction.getAccountId());
                        if (accountOpt.isPresent()) {
                            account = accountOpt.get();
                        }
                    }
                    if (account == null) {
                        final String plaidAccountId =
                                extractAccountIdFromTransaction(plaidTransaction);
                        if (plaidAccountId != null && !plaidAccountId.isEmpty()) {
                            final Optional<AccountTable> accountOpt =
                                    accountRepository.findByPlaidAccountId(plaidAccountId);
                            if (accountOpt.isPresent()) {
                                account = accountOpt.get();
                                transaction.setAccountId(account.getAccountId());
                            }
                        }
                    }

                    final java.math.BigDecimal normalizedAmount =
                            normalizePlaidAmount(rawAmount, account);
                    transaction.setAmount(normalizedAmount);

                    final java.lang.reflect.Method getName =
                            plaidTransaction.getClass().getMethod("getName");
                    final Object name = getName.invoke(plaidTransaction);
                    if (name != null) {
                        transaction.setDescription(name.toString());
                    }
                } catch (Exception e) {
                    if (LOGGER.isErrorEnabled()) {
                        LOGGER.error(
                                "Could not extract transaction fields via reflection: {}",
                                e.getMessage());
                    }
                }

                if (transaction.getDescription() == null
                        || transaction.getDescription().isEmpty()) {
                    transaction.setDescription(TRANSACTION);
                }
                if (transaction.getTransactionDate() == null
                        || transaction.getTransactionDate().isEmpty()) {
                    transaction.setTransactionDate(
                            java.time.LocalDate.now()
                                    .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
                }
                if (transaction.getAmount() == null) {
                    transaction.setAmount(java.math.BigDecimal.ZERO);
                }
                if (transaction.getCurrencyCode() == null
                        || transaction.getCurrencyCode().isEmpty()) {
                    transaction.setCurrencyCode(USD);
                }
            }
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Error updating transaction from Plaid data: {}", e.getMessage(), e);
            }
            if (transaction.getTransactionDate() == null
                    || transaction.getTransactionDate().isEmpty()) {
                transaction.setTransactionDate(
                        java.time.LocalDate.now()
                                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
            }
            if (transaction.getDescription() == null || transaction.getDescription().isEmpty()) {
                transaction.setDescription(TRANSACTION);
            }
            if (transaction.getAmount() == null) {
                transaction.setAmount(java.math.BigDecimal.ZERO);
            }
            if (transaction.getCurrencyCode() == null || transaction.getCurrencyCode().isEmpty()) {
                transaction.setCurrencyCode(USD);
            }
        }

        // CRITICAL: Determine and set transaction type after all fields are set
        // This ensures transactionType is set even in fallback/error cases
        // BUT: Only recalculate if user hasn't explicitly overridden transactionType
        if (!Boolean.TRUE.equals(transaction.getTransactionTypeOverridden())) {
            try {
                AccountTable account = null;
                if (transaction.getAccountId() != null) {
                    final Optional<AccountTable> accountOpt =
                            accountRepository.findById(transaction.getAccountId());
                    if (accountOpt.isPresent()) {
                        account = accountOpt.get();
                    }
                }

                final TransactionTypeCategoryService.TypeResult typeResult =
                        transactionTypeCategoryService.determineTransactionType(
                                account,
                                transaction.getCategoryPrimary(),
                                transaction.getCategoryDetailed(),
                                transaction.getAmount(),
                                null,
                                transaction.getDescription(),
                                transaction.getPaymentChannel());

                if (typeResult != null) {
                    transaction.setTransactionType(typeResult.getTransactionType().name());
                    // Only set overridden=false if it's currently null (preserve existing override
                    // state)
                    if (transaction.getTransactionTypeOverridden() == null) {
                        transaction.setTransactionTypeOverridden(false);
                    }
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Set transaction type to {} for transaction {} (source: {}, confidence: {:.2f})",
                                typeResult.getTransactionType(),
                                transaction.getTransactionId(),
                                typeResult.getSource(),
                                typeResult.getConfidence());
                    }
                }
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Error determining transaction type, defaulting to EXPENSE: {}",
                            e.getMessage());
                }
                transaction.setTransactionType(
                        com.budgetbuddy.model.TransactionType.EXPENSE.name());
                if (transaction.getTransactionTypeOverridden() == null) {
                    transaction.setTransactionTypeOverridden(false);
                }
            }
        } else {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Skipping transaction type recalculation for transaction {} (user override preserved)",
                        transaction.getTransactionId());
            }
        }
    }

    /**
     * Normalize Plaid amount to standard convention Plaid uses reverse convention for
     * checking/savings/debit/money_market accounts: -ve = income, +ve = expense Standard
     * convention: +ve = income, -ve = expense
     *
     * @param rawAmount Raw amount from Plaid (can be null)
     * @param account Account information (can be null)
     * @return Normalized amount (flipped sign for checking/savings/debit/money_market, unchanged
     *     for others)
     */
    public java.math.BigDecimal normalizePlaidAmount(
            final java.math.BigDecimal rawAmount, AccountTable account) {
        if (rawAmount == null) {
            return null;
        }

        // CRITICAL: Reverse sign for ALL Plaid transactions when storing
        // Plaid convention: expenses are positive, income is negative
        // Backend convention: expenses are negative, income is positive
        // This ensures consistent sign convention across all account types
        final java.math.BigDecimal normalized = rawAmount.negate();
        LOGGER.debug(
                "Normalized Plaid amount: {} → {} (reversed sign for all account types)",
                rawAmount,
                normalized);
        return normalized;
    }

    /** Extract account ID from Plaid transaction */
    public String extractAccountIdFromTransaction(final Object plaidTransaction) {
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                final com.plaid.client.model.Transaction transaction =
                        (com.plaid.client.model.Transaction) plaidTransaction;
                return transaction.getAccountId();
            }

            // Fallback: try reflection
            final java.lang.reflect.Method getAccountId =
                    plaidTransaction.getClass().getMethod("getAccountId");
            final Object accountId = getAccountId.invoke(plaidTransaction);
            if (accountId != null) {
                return accountId.toString();
            }
        } catch (Exception e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Could not extract account ID from Plaid transaction: {}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * Detect investment account types (bonds, treasury, Certificate/CD) from account name and
     * institution name Returns "INVESTMENT" if detected, null otherwise
     */
    private String detectInvestmentAccountType(
            final String accountName, final String institutionName, String plaidAccountType) {
        if (accountName == null && institutionName == null) {
            return null;
        }

        // Combine account name and institution name for detection
        final String combinedText =
                ((accountName != null ? accountName : "")
                                + " "
                                + (institutionName != null ? institutionName : ""))
                        .trim()
                        .toLowerCase(Locale.ROOT);

        if (combinedText.isEmpty()) {
            return null;
        }

        // Check for Certificate/CD keywords
        if (combinedText.contains("certificate")
                || combinedText.contains("cd ")
                || combinedText.contains(" c.d.")
                || combinedText.contains(" c.d ")
                || combinedText.contains("certificate of deposit")
                || combinedText.contains("cert of deposit")
                || combinedText.endsWith(" cd")
                || combinedText.endsWith(" c.d")) {
            return "INVESTMENT";
        }

        // Check for Treasury keywords (Treasury bills, notes, bonds)
        if (combinedText.contains("treasury")
                || combinedText.contains("t-bill")
                || combinedText.contains("tbill")
                || combinedText.contains("t bill")
                || combinedText.contains("treasury bill")
                || combinedText.contains("treasury note")
                || combinedText.contains("treasury bond")
                || combinedText.contains("t-note")
                || combinedText.contains("tnote")
                || combinedText.contains("t note")
                || combinedText.contains("t-bond")
                || combinedText.contains("tbond")
                || combinedText.contains("t bond")
                || combinedText.contains("us treasury")) {
            return "INVESTMENT";
        }

        // Check for Bond keywords (corporate bonds, municipal bonds, etc.)
        if (combinedText.contains("bond")
                || combinedText.contains("corporate bond")
                || combinedText.contains("municipal bond")
                || combinedText.contains("govt bond")
                || combinedText.contains("government bond")
                || combinedText.contains("treasury bond")) {
            return "INVESTMENT";
        }

        return null;
    }
}
