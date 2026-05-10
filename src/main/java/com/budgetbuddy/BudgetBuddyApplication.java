package com.budgetbuddy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main entry point for BudgetBuddy Backend Server
 *
 * <p>Enterprise-ready backend for BudgetBuddy iOS application Features: - Plaid Integration -
 * Security & Authentication - Monitoring & Analytics - Compliance & Audit Logging
 */
// Checkstyle HideUtilityClassConstructor flags this class because its only
// declared method is `static void main(...)`. Spring Boot's recommended
// pattern is to keep a public no-arg constructor on the @SpringBootApplication
// class, and BudgetBuddyApplicationTest reflects on it via
// `getConstructors()` (which only returns public ctors) for coverage.
// Suppress with justification — making the ctor private/package-private
// would either break the test or the boot conventions.
@SuppressWarnings("checkstyle:HideUtilityClassConstructor")
@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableScheduling
public class BudgetBuddyApplication {

    public BudgetBuddyApplication() {
        // Spring Boot entry point — main(String[]) is the only meaningful path.
    }

    public static void main(final String[] args) {
        SpringApplication.run(BudgetBuddyApplication.class, args);
    }
}
