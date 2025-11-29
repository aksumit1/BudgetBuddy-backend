package com.budgetbuddy.scripts;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Script to analyze and fix duplicate accounts
 * 
 * Usage:
 * mvn spring-boot:run -Dspring-boot.run.arguments="--analyze-duplicates [userId] [--dry-run]"
 * 
 * --dry-run: Only analyze, don't delete duplicates
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.budgetbuddy")
public class AnalyzeAndFixDuplicates implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(AnalyzeAndFixDuplicates.class);

    @Autowired
    private AccountRepository accountRepository;

    public static void main(String[] args) {
        SpringApplication.run(AnalyzeAndFixDuplicates.class, args);
    }

    @Override
    public void run(String... args) {
        if (args.length == 0 || !args[0].equals("--analyze-duplicates")) {
            System.out.println("Usage: mvn spring-boot:run -Dspring-boot.run.arguments=\"--analyze-duplicates [userId] [--dry-run]\"");
            System.out.println("  userId: User ID to analyze (required)");
            System.out.println("  --dry-run: Only analyze, don't delete duplicates (optional)");
            return;
        }

        if (args.length < 2) {
            System.out.println("Error: userId is required");
            System.out.println("Usage: mvn spring-boot:run -Dspring-boot.run.arguments=\"--analyze-duplicates [userId] [--dry-run]\"");
            return;
        }

        String userId = args[1];
        boolean dryRun = args.length > 2 && args[2].equals("--dry-run");

        if (dryRun) {
            System.out.println("=== DRY RUN MODE - No changes will be made ===\n");
        }

        System.out.println("Analyzing accounts for user: " + userId + "\n");

        List<AccountTable> accounts = accountRepository.findByUserId(userId);
        
        System.out.println("Total accounts found: " + accounts.size());
        System.out.println();

        // Find duplicates by plaidAccountId
        Map<String, List<AccountTable>> byPlaidId = accounts.stream()
                .filter(acc -> acc.getPlaidAccountId() != null && !acc.getPlaidAccountId().isEmpty())
                .collect(Collectors.groupingBy(AccountTable::getPlaidAccountId));
        
        List<AccountTable> toDelete = new ArrayList<>();
        
        System.out.println("=== Duplicates by plaidAccountId ===");
        for (Map.Entry<String, List<AccountTable>> entry : byPlaidId.entrySet()) {
            if (entry.getValue().size() > 1) {
                System.out.println("  Duplicate plaidAccountId: " + entry.getKey() + " (" + entry.getValue().size() + " accounts)");
                
                // Sort by createdAt to keep the oldest one
                List<AccountTable> duplicates = new ArrayList<>(entry.getValue());
                duplicates.sort(Comparator.comparing(acc -> acc.getCreatedAt() != null ? acc.getCreatedAt() : java.time.Instant.EPOCH));
                
                AccountTable keep = duplicates.get(0);
                System.out.println("    KEEPING: Account ID=" + keep.getAccountId() + 
                        ", Name=" + keep.getAccountName() + 
                        ", Created=" + keep.getCreatedAt() +
                        ", Active=" + keep.getActive());
                
                // Mark others for deletion
                for (int i = 1; i < duplicates.size(); i++) {
                    AccountTable duplicate = duplicates.get(i);
                    System.out.println("    DELETING: Account ID=" + duplicate.getAccountId() + 
                            ", Name=" + duplicate.getAccountName() + 
                            ", Created=" + duplicate.getCreatedAt() +
                            ", Active=" + duplicate.getActive());
                    toDelete.add(duplicate);
                }
            }
        }
        System.out.println();

        // Find duplicates by accountNumber + institutionName (for accounts without plaidAccountId)
        System.out.println("=== Duplicates by accountNumber + institutionName (no plaidAccountId) ===");
        Map<String, List<AccountTable>> byAccountNumber = accounts.stream()
                .filter(acc -> (acc.getPlaidAccountId() == null || acc.getPlaidAccountId().isEmpty()) &&
                              acc.getAccountNumber() != null && !acc.getAccountNumber().isEmpty() &&
                              acc.getInstitutionName() != null && !acc.getInstitutionName().isEmpty())
                .collect(Collectors.groupingBy(acc -> 
                    acc.getAccountNumber() + "|" + acc.getInstitutionName()));
        
        for (Map.Entry<String, List<AccountTable>> entry : byAccountNumber.entrySet()) {
            if (entry.getValue().size() > 1) {
                String[] parts = entry.getKey().split("\\|");
                System.out.println("  Duplicate: Number=" + parts[0] + ", Institution=" + parts[1] + " (" + entry.getValue().size() + " accounts)");
                
                // Sort by createdAt to keep the oldest one
                List<AccountTable> duplicates = new ArrayList<>(entry.getValue());
                duplicates.sort(Comparator.comparing(acc -> acc.getCreatedAt() != null ? acc.getCreatedAt() : java.time.Instant.EPOCH));
                
                AccountTable keep = duplicates.get(0);
                System.out.println("    KEEPING: Account ID=" + keep.getAccountId() + 
                        ", Name=" + keep.getAccountName() + 
                        ", Created=" + keep.getCreatedAt());
                
                // Mark others for deletion
                for (int i = 1; i < duplicates.size(); i++) {
                    AccountTable duplicate = duplicates.get(i);
                    System.out.println("    DELETING: Account ID=" + duplicate.getAccountId() + 
                            ", Name=" + duplicate.getAccountName() + 
                            ", Created=" + duplicate.getCreatedAt());
                    toDelete.add(duplicate);
                }
            }
        }
        System.out.println();

        // Summary
        System.out.println("=== Summary ===");
        System.out.println("Total accounts: " + accounts.size());
        System.out.println("Duplicates to delete: " + toDelete.size());
        System.out.println("Expected unique accounts after cleanup: " + (accounts.size() - toDelete.size()));
        System.out.println();

        // Delete duplicates
        if (!toDelete.isEmpty()) {
            if (dryRun) {
                System.out.println("DRY RUN: Would delete " + toDelete.size() + " duplicate accounts");
                System.out.println("Run without --dry-run to actually delete them");
            } else {
                System.out.println("Deleting " + toDelete.size() + " duplicate accounts...");
                for (AccountTable duplicate : toDelete) {
                    try {
                        accountRepository.delete(duplicate.getAccountId());
                        System.out.println("  Deleted: " + duplicate.getAccountId() + " (" + duplicate.getAccountName() + ")");
                    } catch (Exception e) {
                        System.err.println("  ERROR deleting " + duplicate.getAccountId() + ": " + e.getMessage());
                    }
                }
                System.out.println("Cleanup complete!");
            }
        } else {
            System.out.println("No duplicates found!");
        }
    }
}

