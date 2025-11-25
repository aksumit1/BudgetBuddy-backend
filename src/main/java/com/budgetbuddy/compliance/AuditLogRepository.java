package com.budgetbuddy.compliance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for AuditLog entity
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    
    @Query("SELECT a FROM AuditLog a WHERE a.userId = :userId AND a.createdAt BETWEEN :startDate AND :endDate ORDER BY a.createdAt DESC")
    List<AuditLog> findByUserIdAndCreatedAtBetween(
            @Param("userId") Long userId, 
            @Param("startDate") LocalDateTime startDate, 
            @Param("endDate") LocalDateTime endDate);

    List<AuditLog> findByCreatedAtBefore(LocalDateTime cutoffDate);
}

