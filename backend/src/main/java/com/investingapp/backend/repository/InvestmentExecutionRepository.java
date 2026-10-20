package com.investingapp.backend.repository;

import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentExecution.ExecutionStatus;
import com.investingapp.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface InvestmentExecutionRepository extends JpaRepository<InvestmentExecution, Long> {
    
    /**
     * Find all scheduled executions for today
     */
    @Query("SELECT ie FROM InvestmentExecution ie WHERE " +
           "DATE(ie.scheduledDate) = DATE(:date) AND " +
           "ie.status = :status")
    List<InvestmentExecution> findScheduledExecutionsForDate(
        @Param("date") LocalDateTime date, 
        @Param("status") ExecutionStatus status
    );
    
    /**
     * Find executions that need funding status check
     */
    @Query("SELECT ie FROM InvestmentExecution ie WHERE " +
           "ie.status = :status AND " +
           "ie.executionDate < :cutoffTime")
    List<InvestmentExecution> findExecutionsAwaitingFundingCheck(
        @Param("status") ExecutionStatus status,
        @Param("cutoffTime") LocalDateTime cutoffTime
    );
    
    /**
     * Find executions that need trading status check
     */
    @Query("SELECT ie FROM InvestmentExecution ie WHERE " +
           "ie.status = :status AND " +
           "ie.fundingCompletedAt < :cutoffTime")
    List<InvestmentExecution> findExecutionsAwaitingTradingCheck(
        @Param("status") ExecutionStatus status,
        @Param("cutoffTime") LocalDateTime cutoffTime
    );
    
    /**
     * Find executions by user and status
     */
    List<InvestmentExecution> findByUserIdAndStatus(Long userId, ExecutionStatus status);
    
    /**
     * Find pending funding executions for a user today (to detect multiple ACH attempts)
     */
    @Query("SELECT ie FROM InvestmentExecution ie WHERE " +
           "ie.user.id = :userId AND " +
           "ie.executionDate >= :startOfDay AND ie.executionDate <= :endOfDay AND " +
           "(ie.status = 'FUNDING_INITIATED' OR ie.status = 'FUNDING_COMPLETED')")
    List<InvestmentExecution> findPendingFundingExecutionsForUserToday(
        @Param("userId") Long userId,
        @Param("startOfDay") LocalDateTime startOfDay,
        @Param("endOfDay") LocalDateTime endOfDay
    );
    
    /**
     * Find recent executions for a user
     */
    @Query("SELECT ie FROM InvestmentExecution ie WHERE " +
           "ie.user.id = :userId AND " +
           "ie.scheduledDate >= :since " +
           "ORDER BY ie.scheduledDate DESC")
    List<InvestmentExecution> findRecentExecutionsForUser(
        @Param("userId") Long userId,
        @Param("since") LocalDateTime since
    );
    
    /**
     * Find all executions by user ordered by creation date
     */
    List<InvestmentExecution> findByUserOrderByCreatedAtDesc(User user);
    
    /**
     * Find all executions with a specific status (for delayed trading initiation)
     */
    List<InvestmentExecution> findByStatus(ExecutionStatus status);

    /**
     * Find all executions associated with a specific Alpaca transfer ID
     */
    List<InvestmentExecution> findByAlpacaTransferId(String alpacaTransferId);

    /**
     * Count completed recurring investment executions for a user
     */
    @Query("SELECT COUNT(ie) FROM InvestmentExecution ie WHERE ie.user.id = :userId AND ie.status = 'COMPLETED'")
    long countCompletedExecutionsByUser(@Param("userId") Long userId);

    /**
     * Sum of amounts for completed executions in a date range for a user (by updated_at date)
     */
    @Query("SELECT COALESCE(SUM(ie.amount), 0) FROM InvestmentExecution ie WHERE ie.user.id = :userId AND ie.status = 'COMPLETED' AND ie.updatedAt >= :startDate AND ie.updatedAt <= :endDate")
    java.math.BigDecimal sumCompletedAmountsByUserAndDateRange(
        @Param("userId") Long userId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );

    /**
     * Check if user has any completed executions in a given month
     */
    @Query("SELECT COUNT(ie) > 0 FROM InvestmentExecution ie WHERE ie.user.id = :userId AND ie.status = 'COMPLETED' AND YEAR(ie.executionDate) = :year AND MONTH(ie.executionDate) = :month")
    boolean hasCompletedExecutionInMonth(
        @Param("userId") Long userId,
        @Param("year") int year,
        @Param("month") int month
    );

    /**
     * Find all executions whose scheduledDate falls on the given calendar date.
     * Used for D+0 and D+1 payday notifications.
     */
    @Query("SELECT ie FROM InvestmentExecution ie WHERE FUNCTION('DATE', ie.scheduledDate) = :date")
    List<InvestmentExecution> findByScheduledDate(@Param("date") java.time.LocalDate date);
    /**
     * Person-property signal for analytics: has any money of this user's actually landed.
     * Funding completed, not merely initiated.
     */
    boolean existsByUserAndFundingCompletedAtIsNotNull(User user);

    // --- Phase 0 idempotency (spec 3.8, 5.4, 5.5) -------------------------------------------

    /** source_key pre-check: one row per MANUAL due date per schedule, one per lump-sum tap. */
    boolean existsBySourceKey(String sourceKey);

    /** night_key pre-check: one recurring execution per user per Eastern night. */
    boolean existsByNightKey(String nightKey);

    /** Lump-sum duplicate short-circuit on POST /api/investments/execute. */
    Optional<InvestmentExecution> findBySourceKey(String sourceKey);

    /**
     * 11:59 selection: still SCHEDULED, never claimed, and created before the batch started (a
     * 23:59:xx row runs the next night). Excluding an already-claimed row (transferAttemptedAt IS
     * NOT NULL) is load-bearing: without it, a row stranded by a crash between the claim and the
     * status write would be selected again every night, its claim would always come up short
     * against the fresh rows beside it, and the whole user would be skipped forever.
     */
    List<InvestmentExecution> findByStatusAndTransferAttemptedAtIsNullAndCreatedAtBefore(ExecutionStatus status, LocalDateTime before);

    /** Deferral guard (T10 bank change, T11 deletion): is money in flight for this user. */
    boolean existsByUserAndStatus(User user, ExecutionStatus status);

    /** Deferral guard (T11): the most recent settled transfer, for the 60-day window. */
    Optional<InvestmentExecution> findTopByUserAndFundingCompletedAtIsNotNullOrderByFundingCompletedAtDesc(User user);

    /**
     * Compare-and-set claim before the Alpaca POST (spec 5.5 b). Only rows nobody attempted yet
     * are claimed; the caller compares the row count with the batch size and skips the user when
     * they differ. Run it in a REQUIRES_NEW TransactionTemplate so the claim commits before HTTP.
     */
    @Modifying
    @Query("UPDATE InvestmentExecution e SET e.transferAttemptedAt = :at, e.batchNight = :night " +
           "WHERE e.id IN :ids AND e.transferAttemptedAt IS NULL AND e.alpacaTransferId IS NULL")
    int claimForBatch(@Param("ids") List<Long> ids, @Param("at") LocalDateTime at, @Param("night") LocalDate night);

}
