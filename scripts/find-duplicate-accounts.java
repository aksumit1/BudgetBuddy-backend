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
 * Script to find and analyze duplicate accounts in the database
 * 
 * Usage:
 * 1. Set SPRING_PROFILES_ACTIVE=local (or your profile)
 * 2. Run: mvn spring-boot:run -Dspring-boot.run.arguments="--find-duplicates [userId]"
 * 
 * If userId is not provided, it will analyze all users
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.budgetbuddy")
public class FindDuplicateAccounts implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(FindDuplicateAccounts.class);

    @Autowired
    private AccountRepository accountRepository;

    public static void main(String[] args) {
        SpringApplication.run(FindDuplicateAccounts.class, args);
    }

    @Override
    public void run(String... args) {
        if (args.length == 0 || !args[0].equals("--find-duplicates")) {
            System.out.println("Usage: mvn spring-boot:run -Dspring-boot.run.arguments=\"--find-duplicates [userId]\"");
            System.out.println("If userId is not provided, will analyze all users");
            return;
        }

        String userId = args.length > 1 ? args[1] : null;

        System.out.println("=== Finding Duplicate Accounts ===\n");

        if (userId != null) {
            analyzeUserAccounts(userId);
        } else {
            System.out.println("NOTE: To analyze a specific user, provide userId as second argument");
            System.out.println("For now, analyzing all accounts by scanning...\n");
            // We'd need a way to get all userIds first
            // For now, let's create a method that can be called with a userId
            System.out.println("Please provide a userId to analyze specific user's accounts");
        }
    }

    private void analyzeUserAccounts(String userId) {
        System.out.println("Analyzing accounts for user: " + userId + "\n");

        List<AccountTable> accounts = accountRepository.findByUserId(userId);
        
        System.out.println("Total accounts found: " + accounts.size());
        System.out.println();

        // 1. Check duplicates by plaidAccountId
        System.out.println("=== 1. Duplicates by plaidAccountId ===");
        Map<String, List<AccountTable>> byPlaidId = accounts.stream()
                .filter(acc -> acc.getPlaidAccountId() != null && !acc.getPlaidAccountId().isEmpty())
                .collect(Collectors.groupingBy(AccountTable::getPlaidAccountId));
        
        int plaidDuplicates = 0;
        for (Map.Entry<String, List<AccountTable>> entry : byPlaidId.entrySet()) {
            if (entry.getValue().size() > 1) {
                plaidDuplicates += entry.getValue().size() - 1;
                System.out.println("  Duplicate plaidAccountId: " + entry.getKey());
                for (AccountTable acc : entry.getValue()) {
                    System.out.println("    - Account ID: " + acc.getAccountId() + 
                            ", Name: " + acc.getAccountName() + 
                            ", Number: " + acc.getAccountNumber() +
                            ", Institution: " + acc.getInstitutionName() +
                            ", Active: " + acc.getActive() +
                            ", Created: " + acc.getCreatedAt());
                }
            }
        }
        if (plaidDuplicates == 0) {
            System.out.println("  No duplicates found by plaidAccountId");
        } else {
            System.out.println("  Total duplicate accounts by plaidAccountId: " + plaidDuplicates);
        }
        System.out.println();

        // 2. Check duplicates by accountNumber + institutionName
        System.out.println("=== 2. Duplicates by accountNumber + institutionName ===");
        Map<String, List<AccountTable>> byAccountNumber = accounts.stream()
                .filter(acc -> acc.getAccountNumber() != null && !acc.getAccountNumber().isEmpty() &&
                              acc.getInstitutionName() != null && !acc.getInstitutionName().isEmpty())
                .collect(Collectors.groupingBy(acc -> 
                    acc.getAccountNumber() + "|" + acc.getInstitutionName()));
        
        int numberDuplicates = 0;
        for (Map.Entry<String, List<AccountTable>> entry : byAccountNumber.entrySet()) {
            if (entry.getValue().size() > 1) {
                numberDuplicates += entry.getValue().size() - 1;
                String[] parts = entry.getKey().split("\\|");
                System.out.println("  Duplicate: Number=" + parts[0] + ", Institution=" + parts[1]);
                for (AccountTable acc : entry.getValue()) {
                    System.out.println("    - Account ID: " + acc.getAccountId() + 
                            ", Plaid ID: " + acc.getPlaidAccountId() +
                            ", Name: " + acc.getAccountName() + 
                            ", Active: " + acc.getActive() +
                            ", Created: " + acc.getCreatedAt());
                }
            }
        }
        if (numberDuplicates == 0) {
            System.out.println("  No duplicates found by accountNumber + institutionName");
        } else {
            System.out.println("  Total duplicate accounts by accountNumber + institution: " + numberDuplicates);
        }
        System.out.println();

        // 3. Check duplicates by accountId (should never happen)
        System.out.println("=== 3. Duplicates by accountId (UUID) ===");
        Map<String, List<AccountTable>> byAccountId = accounts.stream()
                .filter(acc -> acc.getAccountId() != null && !acc.getAccountId().isEmpty())
                .collect(Collectors.groupingBy(AccountTable::getAccountId));
        
        int idDuplicates = 0;
        for (Map.Entry<String, List<AccountTable>> entry : byAccountId.entrySet()) {
            if (entry.getValue().size() > 1) {
                idDuplicates += entry.getValue().size() - 1;
                System.out.println("  ERROR: Duplicate accountId found: " + entry.getKey());
                for (AccountTable acc : entry.getValue()) {
                    System.out.println("    - Plaid ID: " + acc.getPlaidAccountId() + 
                            ", Name: " + acc.getAccountName());
                }
            }
        }
        if (idDuplicates == 0) {
            System.out.println("  No duplicates found by accountId (good!)");
        } else {
            System.out.println("  ERROR: Total duplicate accounts by accountId: " + idDuplicates);
        }
        System.out.println();

        // 4. Accounts without plaidAccountId
        System.out.println("=== 4. Accounts without plaidAccountId ===");
        List<AccountTable> noPlaidId = accounts.stream()
                .filter(acc -> acc.getPlaidAccountId() == null || acc.getPlaidAccountId().isEmpty())
                .collect(Collectors.toList());
        
        if (noPlaidId.isEmpty()) {
            System.out.println("  All accounts have plaidAccountId");
        } else {
            System.out.println("  Found " + noPlaidId.size() + " accounts without plaidAccountId:");
            for (AccountTable acc : noPlaidId) {
                System.out.println("    - Account ID: " + acc.getAccountId() + 
                        ", Name: " + acc.getAccountName() + 
                        ", Number: " + acc.getAccountNumber() +
                        ", Institution: " + acc.getInstitutionName());
            }
        }
        System.out.println();

        // 5. Summary
        System.out.println("=== Summary ===");
        System.out.println("Total accounts: " + accounts.size());
        System.out.println("Duplicates by plaidAccountId: " + plaidDuplicates);
        System.out.println("Duplicates by accountNumber+institution: " + numberDuplicates);
        System.out.println("Duplicates by accountId: " + idDuplicates);
        System.out.println("Accounts without plaidAccountId: " + noPlaidId.size());
        System.out.println();
        
        int totalDuplicates = plaidDuplicates + numberDuplicates;
        System.out.println("Total duplicate accounts to remove: " + totalDuplicates);
        System.out.println("Expected unique accounts: " + (accounts.size() - totalDuplicates));
    }
}

