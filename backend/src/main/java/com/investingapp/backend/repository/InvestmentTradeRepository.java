package com.investingapp.backend.repository;

import com.investingapp.backend.model.InvestmentTrade;
import com.investingapp.backend.model.InvestmentTrade.TradeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface InvestmentTradeRepository extends JpaRepository<InvestmentTrade, Long> {
    
    /**
     * Find trades by execution ID
     */
    List<InvestmentTrade> findByInvestmentExecutionId(Long executionId);
    
    /**
     * Find trades that need status check
     */
    @Query("SELECT it FROM InvestmentTrade it WHERE " +
           "it.status IN (:statuses) AND " +
           "it.submittedAt < :cutoffTime")
    List<InvestmentTrade> findTradesAwaitingStatusCheck(
        @Param("statuses") List<TradeStatus> statuses,
        @Param("cutoffTime") LocalDateTime cutoffTime
    );
    
    /**
     * Find trades by Alpaca order ID
     */
    InvestmentTrade findByAlpacaOrderId(String alpacaOrderId);
    
    /**
     * Find pending trades for execution
     */
    List<InvestmentTrade> findByInvestmentExecutionIdAndStatus(Long executionId, TradeStatus status);

    /**
     * TLH in-flight-buy veto: recent open buy trades for a symbol. Time-bounded
     * so stale SUBMITTED rows can't permanently veto a harvest.
     */
    List<InvestmentTrade> findByUserIdAndSymbolAndStatusInAndCreatedAtAfter(
            Long userId, String symbol, java.util.Collection<TradeStatus> statuses, LocalDateTime after);

    /** User deletion: InvestmentTrade carries a bare user_id column (InvestmentTrade.java:15-16). */
    void deleteAllByUserId(Long userId);
}
