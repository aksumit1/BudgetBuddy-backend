package com.budgetbuddy.repository;

import com.budgetbuddy.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for User entity
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    Optional<User> findByEmail(String email);
    
    boolean existsByEmail(String email);
    
    @Query("SELECT u FROM User u WHERE u.enabled = false AND u.createdAt < :cutoffDate")
    List<User> findInactiveUsersBefore(@Param("cutoffDate") LocalDateTime cutoffDate);
    
    @Query("SELECT COUNT(u) FROM User u WHERE u.createdAt >= :startDate")
    long countUsersCreatedAfter(@Param("startDate") LocalDateTime startDate);
}

