package com.budgetbuddy.compliance.dma;

import com.budgetbuddy.compliance.AuditLogService;
import com.budgetbuddy.compliance.gdpr.GDPRComplianceService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Digital Markets Act (DMA) Compliance Service Implements DMA requirements for data portability and
 * interoperability
 *
 * <p>DMA Requirements: - Article 6: Data Portability - Provide data in standardized,
 * machine-readable format - Article 7: Interoperability - Provide API access for data
 * interoperability - Article 8: Fair Access - Ensure fair access to data for third parties -
 * Article 9: Data Sharing - Enable data sharing with authorized third parties
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
// `\n` in the format strings here is a literal LF (CSV rows / raw
// HTTP body templates), not a platform newline — we do NOT want %n.
@SuppressFBWarnings(
        value = {"VA_FORMAT_STRING_USES_NEWLINE", "EI_EXPOSE_REP2"},
        justification =
                "literal LF in CSV / wire format (not platform newline); "
                        + "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Service
public class DMAComplianceService {

    private static final String ID_1 = "      <Id>";

    private static final String ID = "</Id>\n";

    private static final Logger LOGGER = LoggerFactory.getLogger(DMAComplianceService.class);

    private final GDPRComplianceService gdprComplianceService;
    private final AuditLogService auditLogService;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final BudgetRepository budgetRepository;
    private final GoalRepository goalRepository;

    public DMAComplianceService(
            final GDPRComplianceService gdprComplianceService,
            final AuditLogService auditLogService,
            final UserRepository userRepository,
            final TransactionRepository transactionRepository,
            final AccountRepository accountRepository,
            final BudgetRepository budgetRepository,
            final GoalRepository goalRepository) {
        this.gdprComplianceService = gdprComplianceService;
        this.auditLogService = auditLogService;
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.budgetRepository = budgetRepository;
        this.goalRepository = goalRepository;
    }

    /**
     * Article 6: Data Portability Provide data in standardized, machine-readable format Supports
     * JSON, CSV, XML formats
     */
    public String exportDataPortable(final String userId, final String format) {
        LOGGER.info("DMA: Exporting data in format: {} for user: {}", format, userId);

        // Log data export for compliance
        auditLogService.logDataExport(userId, UUID.randomUUID().toString());

        // Use GDPR service for data export (DMA extends GDPR requirements)
        if ("JSON".equalsIgnoreCase(format)) {
            return gdprComplianceService.exportDataPortable(userId);
        } else if ("CSV".equalsIgnoreCase(format)) {
            return exportAsCSV(userId);
        } else if ("XML".equalsIgnoreCase(format)) {
            return exportAsXML(userId);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported format: " + format + ". Supported formats: JSON, CSV, XML");
        }
    }

    /**
     * Article 7: Interoperability Provide API access for data interoperability Returns standardized
     * API endpoint for third-party access
     */
    public String getInteroperabilityEndpoint(final String userId) {
        // Return endpoint for third-party access (with proper authentication)
        final String endpoint = "/api/dma/interoperability/" + userId;
        LOGGER.debug("DMA: Interoperability endpoint for user {}: {}", userId, endpoint);
        return endpoint;
    }

    /** Article 8: Fair Access Enable third-party access to user data with proper authorization */
    public boolean authorizeThirdPartyAccess(
            final String userId, final String thirdPartyId, final String scope) {
        LOGGER.info(
                "DMA: Authorizing third-party access - User: {}, ThirdParty: {}, Scope: {}",
                userId,
                thirdPartyId,
                scope);

        // In production, this would:
        // 1. Verify third-party identity
        // 2. Check user consent
        // 3. Store authorization
        // 4. Log access for audit

        auditLogService.logAction(
                userId,
                "THIRD_PARTY_AUTHORIZATION",
                "DMA",
                thirdPartyId,
                java.util.Map.of("thirdPartyId", thirdPartyId, "scope", scope),
                null,
                null);

        return true; // Simplified - in production, implement proper authorization
    }

    /** Article 9: Data Sharing Share user data with authorized third parties */
    public String shareDataWithThirdParty(
            final String userId, final String thirdPartyId, final String dataType) {
        LOGGER.info(
                "DMA: Sharing data with third party - User: {}, ThirdParty: {}, DataType: {}",
                userId,
                thirdPartyId,
                dataType);

        // Verify authorization
        if (!authorizeThirdPartyAccess(userId, thirdPartyId, dataType)) {
            throw new IllegalStateException("Third-party access not authorized");
        }

        // Export data in standardized format
        final String data = exportDataPortable(userId, "JSON");

        // Log data sharing
        auditLogService.logAction(
                userId,
                "DATA_SHARING",
                "DMA",
                thirdPartyId,
                java.util.Map.of("thirdPartyId", thirdPartyId, "dataType", dataType),
                null,
                null);

        return data;
    }

    /** Export data as CSV */
    private String exportAsCSV(final String userId) {
        try {
            // Get user data
            final UserTable user = userRepository.findById(userId).orElse(null);
            final List<com.budgetbuddy.model.dynamodb.TransactionTable> transactions =
                    transactionRepository.findByUserId(userId, 0, 10_000);
            final List<com.budgetbuddy.model.dynamodb.AccountTable> accounts =
                    accountRepository.findByUserId(userId);
            final List<com.budgetbuddy.model.dynamodb.BudgetTable> budgets =
                    budgetRepository.findByUserId(userId);
            final List<com.budgetbuddy.model.dynamodb.GoalTable> goals =
                    goalRepository.findByUserId(userId);

            // Build CSV
            final StringBuilder csv = new StringBuilder();

            // Header
            csv.append("DataType,Id,UserId,Details,Timestamp\n");

            // User data
            if (user != null) {
                csv.append(
                        String.format(
                                "USER,%s,%s,\"%s\",%s\n",
                                user.getUserId(),
                                user.getUserId(),
                                "Email: " + user.getEmail(),
                                Instant.now().toString()));
            }

            // Transactions
            for (final var transaction : transactions) {
                csv.append(
                        String.format(
                                "TRANSACTION,%s,%s,\"Amount: %s, Date: %s\",%s\n",
                                transaction.getTransactionId(),
                                userId,
                                transaction.getAmount(),
                                transaction.getTransactionDate(),
                                transaction.getTransactionDate()));
            }

            // Accounts
            for (final var account : accounts) {
                csv.append(
                        String.format(
                                "ACCOUNT,%s,%s,\"Name: %s, Balance: %s\",%s\n",
                                account.getAccountId(),
                                userId,
                                account.getAccountName(),
                                account.getBalance(),
                                Instant.now().toString()));
            }

            // Budgets
            for (final var budget : budgets) {
                csv.append(
                        String.format(
                                "BUDGET,%s,%s,\"Category: %s, Limit: %s\",%s\n",
                                budget.getBudgetId(),
                                userId,
                                budget.getCategory(),
                                budget.getMonthlyLimit(),
                                Instant.now().toString()));
            }

            // Goals
            for (final var goal : goals) {
                csv.append(
                        String.format(
                                "GOAL,%s,%s,\"Name: %s, Target: %s\",%s\n",
                                goal.getGoalId(),
                                userId,
                                goal.getName(),
                                goal.getTargetAmount(),
                                Instant.now().toString()));
            }

            return csv.toString();
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Failed to export data as CSV: {}", e.getMessage());
            }
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Failed to export data as CSV", e);
        }
    }

    /** Export data as XML */
    private String exportAsXML(final String userId) {
        try {
            // Get user data
            final UserTable user = userRepository.findById(userId).orElse(null);
            final List<com.budgetbuddy.model.dynamodb.TransactionTable> transactions =
                    transactionRepository.findByUserId(userId, 0, 10_000);
            final List<com.budgetbuddy.model.dynamodb.AccountTable> accounts =
                    accountRepository.findByUserId(userId);
            final List<com.budgetbuddy.model.dynamodb.BudgetTable> budgets =
                    budgetRepository.findByUserId(userId);
            final List<com.budgetbuddy.model.dynamodb.GoalTable> goals =
                    goalRepository.findByUserId(userId);

            // Build XML
            final StringBuilder xml = new StringBuilder();
            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            xml.append("<DMAExport userId=\"")
                    .append(userId)
                    .append("\" exportDate=\"")
                    .append(Instant.now())
                    .append("\">\n");

            // User data
            if (user != null) {
                xml.append("  <User>\n");
                xml.append("    <Email>").append(user.getEmail()).append("</Email>\n");
                xml.append("    <FirstName>").append(user.getFirstName()).append("</FirstName>\n");
                xml.append("    <LastName>").append(user.getLastName()).append("</LastName>\n");
                xml.append("  </User>\n");
            }

            // Transactions
            xml.append("  <Transactions>\n");
            for (final var transaction : transactions) {
                xml.append("    <Transaction>\n");
                xml.append(ID_1).append(transaction.getTransactionId()).append(ID);
                xml.append("      <Amount>").append(transaction.getAmount()).append("</Amount>\n");
                xml.append("      <Date>")
                        .append(transaction.getTransactionDate())
                        .append("</Date>\n");
                xml.append("      <Description>")
                        .append(transaction.getDescription())
                        .append("</Description>\n");
                xml.append("    </Transaction>\n");
            }
            xml.append("  </Transactions>\n");

            // Accounts
            xml.append("  <Accounts>\n");
            for (final var account : accounts) {
                xml.append("    <Account>\n");
                xml.append(ID_1).append(account.getAccountId()).append(ID);
                xml.append("      <Name>").append(account.getAccountName()).append("</Name>\n");
                xml.append("      <Balance>").append(account.getBalance()).append("</Balance>\n");
                xml.append("    </Account>\n");
            }
            xml.append("  </Accounts>\n");

            // Budgets
            xml.append("  <Budgets>\n");
            for (final var budget : budgets) {
                xml.append("    <Budget>\n");
                xml.append(ID_1).append(budget.getBudgetId()).append(ID);
                xml.append("      <Category>").append(budget.getCategory()).append("</Category>\n");
                xml.append("      <MonthlyLimit>")
                        .append(budget.getMonthlyLimit())
                        .append("</MonthlyLimit>\n");
                xml.append("    </Budget>\n");
            }
            xml.append("  </Budgets>\n");

            // Goals
            xml.append("  <Goals>\n");
            for (final var goal : goals) {
                xml.append("    <Goal>\n");
                xml.append(ID_1).append(goal.getGoalId()).append(ID);
                xml.append("      <Name>").append(goal.getName()).append("</Name>\n");
                xml.append("      <TargetAmount>")
                        .append(goal.getTargetAmount())
                        .append("</TargetAmount>\n");
                xml.append("    </Goal>\n");
            }
            xml.append("  </Goals>\n");

            xml.append("</DMAExport>\n");

            return xml.toString();
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Failed to export data as XML: {}", e.getMessage());
            }
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Failed to export data as XML", e);
        }
    }
}
