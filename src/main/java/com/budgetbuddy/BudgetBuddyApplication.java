package com.budgetbuddy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main entry point for BudgetBuddy Backend Server
 * 
 * Enterprise-ready backend for BudgetBuddy iOS application
 * Features:
 * - Plaid Integration
 * - Security & Authentication
 * - Monitoring & Analytics
 * - Compliance & Audit Logging
 */
@SpringBootApplication
@EnableJpaAuditing
@EnableCaching
@EnableAsync
@EnableScheduling
public class BudgetBuddyApplication {

    public static void main(String[] args) {
        SpringApplication.run(BudgetBuddyApplication.class, args);
    }
}

