package com.budgetbuddy.repository;

import com.budgetbuddy.model.Budget;
import com.budgetbuddy.model.Transaction;
import com.budgetbuddy.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Budget entity
 */
@Repository
public interface BudgetRepository extends JpaRepository<Budget, Long> {
    
    List<Budget> findByUser(User user);
    
    Optional<Budget> findByUserAndCategory(User user, Transaction.TransactionCategory category);
    
    @Query("SELECT b FROM Budget b WHERE b.user = :user")
    List<Budget> findAllByUser(@Param("user") User user);
}

