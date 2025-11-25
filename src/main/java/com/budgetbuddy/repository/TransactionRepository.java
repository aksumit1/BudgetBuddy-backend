package com.budgetbuddy.repository;

import com.budgetbuddy.model.Transaction;
import com.budgetbuddy.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository for Transaction entity
 * Optimized with pagination and date range queries to minimize data transfer
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    
    Page<Transaction> findByUserOrderByTransactionDateDesc(User user, Pageable pageable);
    
    List<Transaction> findByUserAndTransactionDateBetween(User user, LocalDate startDate, LocalDate endDate);
    
    @Query("SELECT t FROM Transaction t WHERE t.user = :user AND t.transactionDate >= :startDate AND t.transactionDate <= :endDate ORDER BY t.transactionDate DESC")
    List<Transaction> findTransactionsInDateRange(@Param("user") User user, 
                                                 @Param("startDate") LocalDate startDate, 
                                                 @Param("endDate") LocalDate endDate);
    
    @Query("SELECT t FROM Transaction t WHERE t.user = :user AND t.category = :category AND t.transactionDate >= :startDate")
    List<Transaction> findTransactionsByCategorySince(@Param("user") User user, 
                                                       @Param("category") Transaction.TransactionCategory category,
                                                       @Param("startDate") LocalDate startDate);
    
    @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.user = :user AND t.transactionDate >= :startDate AND t.transactionDate <= :endDate")
    java.math.BigDecimal sumAmountByUserAndDateRange(@Param("user") User user,
                                                      @Param("startDate") LocalDate startDate,
                                                      @Param("endDate") LocalDate endDate);
    
    Optional<Transaction> findByPlaidTransactionId(String plaidTransactionId);
    
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.user = :user AND t.transactionDate >= :startDate")
    long countTransactionsSince(@Param("user") User user, @Param("startDate") LocalDate startDate);
    
    // For data archiving - find old transactions
    @Query("SELECT t FROM Transaction t WHERE t.transactionDate < :cutoffDate")
    List<Transaction> findTransactionsBefore(@Param("cutoffDate") LocalDate cutoffDate);
}

