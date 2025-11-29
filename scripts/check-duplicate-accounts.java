package com.budgetbuddy.scripts;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Script to check for duplicate accounts in the database
 * Run with: mvn spring-boot:run -Dspring-boot.run.arguments="--check-duplicates"
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.budgetbuddy")
public class CheckDuplicateAccounts implements CommandLineRunner {

    @Autowired
    private AccountRepository accountRepository;

    public static void main(String[] args) {
        SpringApplication.run(CheckDuplicateAccounts.class, args);
    }

    @Override
    public void run(String... args) {
        if (args.length == 0 || !args[0].equals("--check-duplicates")) {
            System.out.println("Usage: mvn spring-boot:run -Dspring-boot.run.arguments=\"--check-duplicates\"");
            return;
        }

        System.out.println("=== Checking for Duplicate Accounts ===\n");

        // Get all accounts
        // Note: This is a simplified approach - in production, you'd query by userId
        // For now, we'll need to scan or query by a known userId
        // Let's create a method to find all accounts (for admin purposes)
        
        // Since we can't easily get all accounts without userId, let's check for duplicates
        // by plaidAccountId, accountNumber+institution, and accountId
        
        System.out.println("Checking for duplicates by:");
        System.out.println("1. plaidAccountId");
        System.out.println("2. accountNumber + institutionName");
        System.out.println("3. accountId (UUID)");
        System.out.println();

        // We need a userId to query - let's check if we can find accounts
        // For this script, we'll need to modify it to accept a userId or scan all
        
        System.out.println("NOTE: This script requires modification to query specific users.");
        System.out.println("Please provide a userId or modify the script to scan all accounts.");
    }
}

