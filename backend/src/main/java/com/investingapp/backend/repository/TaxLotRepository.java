package com.investingapp.backend.repository;

import com.investingapp.backend.model.TaxLot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface TaxLotRepository extends JpaRepository<TaxLot, Long> {

    /** The FIFO relief queue for one symbol. */
    List<TaxLot> findByUserIdAndSymbolAndStatusOrderByAcquiredAtAsc(Long userId, String symbol, TaxLot.LotStatus status);

    List<TaxLot> findByUserIdAndStatus(Long userId, TaxLot.LotStatus status);

    List<TaxLot> findByUserIdAndSymbol(Long userId, String symbol);

    /** Activity-level dedup for ingested fills. */
    boolean existsBySourceActivityId(String sourceActivityId);

    /** Order-level dedup: true when the poller already applied this order synthetically. */
    boolean existsByUserIdAndSourceOrderId(Long userId, String sourceOrderId);

    /** Replacement-share candidates for wash matching (symbol or sibling, window-bounded). */
    List<TaxLot> findByUserIdAndSymbolInAndStatusAndAcquiredAtAfter(
            Long userId, Collection<String> symbols, TaxLot.LotStatus status, LocalDateTime after);

    /** Sibling recent-buy veto: any OPEN lot of these symbols acquired after the cutoff. */
    boolean existsByUserIdAndSymbolInAndStatusAndAcquiredAtAfter(
            Long userId, Collection<String> symbols, TaxLot.LotStatus status, LocalDateTime after);

    @Query("select distinct l.symbol from TaxLot l where l.userId = :userId and l.status = 'UNRECONCILED'")
    List<String> findUnreconciledSymbols(@Param("userId") Long userId);
}
