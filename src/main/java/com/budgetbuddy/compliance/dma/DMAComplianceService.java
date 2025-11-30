package com.budgetbuddy.compliance.dma;

import com.budgetbuddy.compliance.AuditLogService;
import com.budgetbuddy.compliance.gdpr.GDPRComplianceService;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Digital Markets Act (DMA) Compliance Service
 * Implements DMA requirements for data portability and interoperability
 *
 * DMA Requirements:
 * - Article 6: Data Portability - Provide data in standardized, machine-readable format
 * - Article 7: Interoperability - Provide API access for data interoperability
 * - Article 8: Fair Access - Ensure fair access to data for third parties
 * - Article 9: Data Sharing - Enable data sharing with authorized third parties
 */
@Service
public class DMAComplianceService {

    private static final Logger logger = LoggerFactory.getLogger(DMAComplianceService.class);

    private final GDPRComplianceService gdprComplianceService;
    private final AuditLogService auditLogService;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final BudgetRepository budgetRepository;
    private final GoalRepository goalRepository;
    private final ObjectMapper objectMapper;

    public DMAComplianceService(
            final GDPRComplianceService gdprComplianceService,
            final AuditLogService auditLogService,
            final UserRepository userRepository,
            final TransactionRepository transactionRepository,
            final AccountRepository accountRepository,
            final BudgetRepository budgetRepository,
            final GoalRepository goalRepository,
            final ObjectMapper objectMapper) {
        this.gdprComplianceService = gdprComplianceService;
        this.auditLogService = auditLogService;
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.budgetRepository = budgetRepository;
        this.goalRepository = goalRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Article 6: Data Portability
     * Provide data in standardized, machine-readable format
     * Supports JSON, CSV, XML formats
     */
    public String exportDataPortable(final String userId, final String format) {
        logger.info("DMA: Exporting data in format: {} for user: {}", format, userId);

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
            throw new IllegalArgumentException("Unsupported format: " + format + ". Supported formats: JSON, CSV, XML");
        }
    }

    /**
     * Article 7: Interoperability
     * Provide API access for data interoperability
     * Returns standardized API endpoint for third-party access
     */
    public String getInteroperabilityEndpoint(final String userId) {
        // Return endpoint for third-party access (with proper authentication)
        String endpoint = "/api/dma/interoperability/" + userId;
        logger.debug("DMA: Interoperability endpoint for user {}: {}", userId, endpoint);
        return endpoint;
    }

    /**
     * Article 8: Fair Access
     * Enable third-party access to user data with proper authorization
     */
    public boolean authorizeThirdPartyAccess(final String userId, final String thirdPartyId, final String scope) {
        logger.info("DMA: Authorizing third-party access - User: {}, ThirdParty: {}, Scope: {}", userId, thirdPartyId, scope);
        
        // In production, this would:
        // 1. Verify third-party identity
        // 2. Check user consent
        // 3. Store authorization
        // 4. Log access for audit
        
        auditLogService.logAction(userId, "THIRD_PARTY_AUTHORIZATION", "DMA", thirdPartyId,
                java.util.Map.of("thirdPartyId", thirdPartyId, "scope", scope), null, null);
        
        return true; // Simplified - in production, implement proper authorization
    }

    /**
     * Article 9: Data Sharing
     * Share user data with authorized third parties
     */
    public String shareDataWithThirdParty(final String userId, final String thirdPartyId, final String dataType) {
        logger.info("DMA: Sharing data with third party - User: {}, ThirdParty: {}, DataType: {}", userId, thirdPartyId, dataType);
        
        // Verify authorization
        if (!authorizeThirdPartyAccess(userId, thirdPartyId, dataType)) {
            throw new IllegalStateException("Third-party access not authorized");
        }
        
        // Export data in standardized format
        String data = exportDataPortable(userId, "JSON");
        
        // Log data sharing
        auditLogService.logAction(userId, "DATA_SHARING", "DMA", thirdPartyId,
                java.util.Map.of("thirdPartyId", thirdPartyId, "dataType", dataType), null, null);
        
        return data;
    }

    /**
     * Export data as CSV
     */
    private String exportAsCSV(final String userId) {
        try {
            // Get user data
            UserTable user = userRepository.findById(userId).orElse(null);
            List<com.budgetbuddy.model.dynamodb.TransactionTable> transactions =
                    transactionRepository.findByUserId(userId, 0, 10000);
            List<com.budgetbuddy.model.dynamodb.AccountTable> accounts = accountRepository.findByUserId(userId);
            List<com.budgetbuddy.model.dynamodb.BudgetTable> budgets = budgetRepository.findByUserId(userId);
            List<com.budgetbuddy.model.dynamodb.GoalTable> goals = goalRepository.findByUserId(userId);

            // Build CSV
            StringBuilder csv = new StringBuilder();
            
            // Header
            csv.append("DataType,Id,UserId,Details,Timestamp\n");
            
            // User data
            if (user != null) {
                csv.append(String.format("USER,%s,%s,\"%s\",%s\n",
                        user.getUserId(), user.getUserId(),
                        "Email: " + user.getEmail(),
                        Instant.now().toString()));
            }
            
            // Transactions
            for (var transaction : transactions) {
                csv.append(String.format("TRANSACTION,%s,%s,\"Amount: %s, Date: %s\",%s\n",
                        transaction.getTransactionId(), userId,
                        transaction.getAmount(), transaction.getTransactionDate(),
                        transaction.getTransactionDate()));
            }
            
            // Accounts
            for (var account : accounts) {
                csv.append(String.format("ACCOUNT,%s,%s,\"Name: %s, Balance: %s\",%s\n",
                        account.getAccountId(), userId,
                        account.getAccountName(), account.getBalance(),
                        Instant.now().toString()));
            }
            
            // Budgets
            for (var budget : budgets) {
                csv.append(String.format("BUDGET,%s,%s,\"Category: %s, Limit: %s\",%s\n",
                        budget.getBudgetId(), userId,
                        budget.getCategory(), budget.getMonthlyLimit(),
                        Instant.now().toString()));
            }
            
            // Goals
            for (var goal : goals) {
                csv.append(String.format("GOAL,%s,%s,\"Name: %s, Target: %s\",%s\n",
                        goal.getGoalId(), userId,
                        goal.getName(), goal.getTargetAmount(),
                        Instant.now().toString()));
            }
            
            return csv.toString();
        } catch (Exception e) {
            logger.error("Failed to export data as CSV: {}", e.getMessage());
            throw new RuntimeException("Failed to export data as CSV", e);
        }
    }

    /**
     * Export data as XML
     */
    private String exportAsXML(final String userId) {
        try {
            // Get user data
            UserTable user = userRepository.findById(userId).orElse(null);
            List<com.budgetbuddy.model.dynamodb.TransactionTable> transactions =
                    transactionRepository.findByUserId(userId, 0, 10000);
            List<com.budgetbuddy.model.dynamodb.AccountTable> accounts = accountRepository.findByUserId(userId);
            List<com.budgetbuddy.model.dynamodb.BudgetTable> budgets = budgetRepository.findByUserId(userId);
            List<com.budgetbuddy.model.dynamodb.GoalTable> goals = goalRepository.findByUserId(userId);

            // Build XML
            StringBuilder xml = new StringBuilder();
            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            xml.append("<DMAExport userId=\"").append(userId).append("\" exportDate=\"").append(Instant.now()).append("\">\n");
            
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
            for (var transaction : transactions) {
                xml.append("    <Transaction>\n");
                xml.append("      <Id>").append(transaction.getTransactionId()).append("</Id>\n");
                xml.append("      <Amount>").append(transaction.getAmount()).append("</Amount>\n");
                xml.append("      <Date>").append(transaction.getTransactionDate()).append("</Date>\n");
                xml.append("      <Description>").append(transaction.getDescription()).append("</Description>\n");
                xml.append("    </Transaction>\n");
            }
            xml.append("  </Transactions>\n");
            
            // Accounts
            xml.append("  <Accounts>\n");
            for (var account : accounts) {
                xml.append("    <Account>\n");
                xml.append("      <Id>").append(account.getAccountId()).append("</Id>\n");
                xml.append("      <Name>").append(account.getAccountName()).append("</Name>\n");
                xml.append("      <Balance>").append(account.getBalance()).append("</Balance>\n");
                xml.append("    </Account>\n");
            }
            xml.append("  </Accounts>\n");
            
            // Budgets
            xml.append("  <Budgets>\n");
            for (var budget : budgets) {
                xml.append("    <Budget>\n");
                xml.append("      <Id>").append(budget.getBudgetId()).append("</Id>\n");
                xml.append("      <Category>").append(budget.getCategory()).append("</Category>\n");
                xml.append("      <MonthlyLimit>").append(budget.getMonthlyLimit()).append("</MonthlyLimit>\n");
                xml.append("    </Budget>\n");
            }
            xml.append("  </Budgets>\n");
            
            // Goals
            xml.append("  <Goals>\n");
            for (var goal : goals) {
                xml.append("    <Goal>\n");
                xml.append("      <Id>").append(goal.getGoalId()).append("</Id>\n");
                xml.append("      <Name>").append(goal.getName()).append("</Name>\n");
                xml.append("      <TargetAmount>").append(goal.getTargetAmount()).append("</TargetAmount>\n");
                xml.append("    </Goal>\n");
            }
            xml.append("  </Goals>\n");
            
            xml.append("</DMAExport>\n");
            
            return xml.toString();
        } catch (Exception e) {
            logger.error("Failed to export data as XML: {}", e.getMessage());
            throw new RuntimeException("Failed to export data as XML", e);
        }
    }
}

