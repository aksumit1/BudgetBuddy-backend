package com.budgetbuddy.repository;

import com.budgetbuddy.model.Goal;
import com.budgetbuddy.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository for Goal entity
 */
@Repository
public interface GoalRepository extends JpaRepository<Goal, Long> {
    
    List<Goal> findByUserAndActiveTrue(User user);
    
    @Query("SELECT g FROM Goal g WHERE g.user = :user AND g.active = true ORDER BY g.targetDate ASC")
    List<Goal> findActiveGoalsByUserOrderedByDate(@Param("user") User user);
    
    @Query("SELECT g FROM Goal g WHERE g.user = :user AND g.targetDate < :date AND g.active = true")
    List<Goal> findOverdueGoals(@Param("user") User user, @Param("date") LocalDate date);
}

