package com.investingapp.backend.repository;

import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InvestmentScheduleRepository extends JpaRepository<InvestmentSchedule, Long> {
    
    /**
     * Find the most recent investment schedule for a user
     */
    Optional<InvestmentSchedule> findTopByUserOrderByCreatedAtDesc(User user);
    
    /**
     * Find all investment schedules for a user ordered by creation date
     */
    List<InvestmentSchedule> findByUserOrderByCreatedAtDesc(User user);

    /** Person-property signal for analytics: does this user have a schedule at all. */
    boolean existsByUser(User user);
    
    /**
     * Find investment schedule by user and achRequestId
     */
    Optional<InvestmentSchedule> findByUserAndAchRequestId(User user, String achRequestId);
    
    /**
     * Find active (non-paused) investment schedules for a user
     */
    @Query("SELECT is FROM InvestmentSchedule is WHERE is.user = :user AND is.isPaused = false ORDER BY is.createdAt DESC")
    List<InvestmentSchedule> findActiveSchedulesByUser(@Param("user") User user);
    
    /**
     * Check if user has any active investment schedules
     */
    @Query("SELECT COUNT(is) > 0 FROM InvestmentSchedule is WHERE is.user = :user AND is.isPaused = false")
    boolean hasActiveSchedules(@Param("user") User user);
    
    /**
     * Find investment schedule by ID and user (for security)
     */
    Optional<InvestmentSchedule> findByIdAndUser(Long id, User user);

    /**
     * Find all schedules whose owner has the given Alpaca account status
     */
    List<InvestmentSchedule> findByUser_AccountStatus(String accountStatus);

    /**
     * Delete all investment schedules belonging to a user (used on account rejection)
     */
    void deleteAllByUser(User user);

    /**
     * MANUAL schedules, not paused, whose next investment date matches the given date.
     * Used for the MANUAL D-1 payday notice (spec 5.8, 10). DETECTED schedules are never selected
     * here; Phase 2 adds findDetectedByNextExpectedDate for them.
     */
    @Query("SELECT s FROM InvestmentSchedule s WHERE s.nextInvestmentDate = :date AND s.isPaused = false"
            + " AND s.mode = com.investingapp.backend.model.ScheduleMode.MANUAL")
    List<InvestmentSchedule> findActiveByNextInvestmentDate(@Param("date") java.time.LocalDate date);
}