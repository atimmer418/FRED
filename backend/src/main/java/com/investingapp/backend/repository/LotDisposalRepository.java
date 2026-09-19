package com.investingapp.backend.repository;

import com.investingapp.backend.model.LotDisposal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LotDisposalRepository extends JpaRepository<LotDisposal, Long> {

    /** Recent disposals of a symbol — replacement-chain loss-sale check. */
    List<LotDisposal> findByUserIdAndSymbolAndDisposedAtAfter(Long userId, String symbol, LocalDateTime after);

    boolean existsBySellActivityIdAndTaxLotId(String sellActivityId, Long taxLotId);

    /** Activity-level dedup for sell fills (any lot already relieved by this activity). */
    boolean existsBySellActivityId(String sellActivityId);

    /** Order-level dedup: the poller already applied this sell order synthetically. */
    boolean existsByUserIdAndSellOrderId(Long userId, String sellOrderId);

    List<LotDisposal> findByHarvestEventId(Long harvestEventId);
}
