package com.budgetbuddy.repository;

import com.budgetbuddy.model.Account;
import com.budgetbuddy.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Account entity
 * Optimized queries for cost-effective data access
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    
    List<Account> findByUserAndActiveTrue(User user);
    
    Optional<Account> findByPlaidAccountId(String plaidAccountId);
    
    List<Account> findByPlaidItemId(String plaidItemId);
    
    @Query("SELECT a FROM Account a WHERE a.user = :user AND a.active = true")
    List<Account> findActiveAccountsByUser(@Param("user") User user);
    
    @Query("SELECT COUNT(a) FROM Account a WHERE a.user = :user AND a.active = true")
    long countActiveAccountsByUser(@Param("user") User user);
    
    @Query("SELECT a FROM Account a WHERE a.user = :user AND a.lastSyncedAt < :cutoffDate")
    List<Account> findAccountsNeedingSync(@Param("user") User user, @Param("cutoffDate") java.time.LocalDateTime cutoffDate);
}

