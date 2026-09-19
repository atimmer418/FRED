package com.investingapp.backend.repository;

import com.investingapp.backend.model.DripExecution;
import com.investingapp.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DripExecutionRepository extends JpaRepository<DripExecution, Long> {

    /**
     * Check if a dividend activity has already been processed (dedup check)
     */
    boolean existsByAlpacaActivityId(String alpacaActivityId);

    /**
     * Find all DRIP executions with ORDER_PLACED status (for order status checking)
     */
    List<DripExecution> findByStatus(DripExecution.DripStatus status);

    /**
     * Find the most recent DRIP execution for a given user (by dividend date descending).
     * Used to determine the 'after' window for the next dividend check.
     */
    @Query("SELECT d FROM DripExecution d WHERE d.user = :user ORDER BY d.dividendDate DESC")
    List<DripExecution> findByUserOrderByDividendDateDesc(@Param("user") User user);

    /**
     * Find all DRIP executions for a user
     */
    List<DripExecution> findByUser(User user);

    /**
     * Find a DRIP execution by its Alpaca order ID
     */
    Optional<DripExecution> findByAlpacaOrderId(String alpacaOrderId);

    /**
     * TLH in-flight-buy veto: a dividend reinvestment for this symbol is
     * detected or already ordered.
     */
    boolean existsByUserAndSymbolAndStatusIn(User user, String symbol,
            java.util.Collection<DripExecution.DripStatus> statuses);
}
