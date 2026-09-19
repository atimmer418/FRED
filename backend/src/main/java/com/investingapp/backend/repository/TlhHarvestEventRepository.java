package com.investingapp.backend.repository;

import com.investingapp.backend.model.TlhHarvestEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TlhHarvestEventRepository extends JpaRepository<TlhHarvestEvent, Long> {

    boolean existsByUserIdAndSymbolAndCycleDateAndMode(
            Long userId, String symbol, LocalDate cycleDate, TlhHarvestEvent.HarvestMode mode);

    boolean existsByUserIdAndStatusIn(Long userId, Collection<TlhHarvestEvent.EventStatus> statuses);

    boolean existsByUserId(Long userId);

    List<TlhHarvestEvent> findByStatusIn(Collection<TlhHarvestEvent.EventStatus> statuses);

    Optional<TlhHarvestEvent> findBySellClientOrderId(String sellClientOrderId);

    Optional<TlhHarvestEvent> findBySellOrderId(String sellOrderId);

    /** Cooldown check across a symbol and its identity siblings. */
    boolean existsByUserIdAndSymbolInAndModeAndStatusNotInAndCreatedAtAfter(
            Long userId, Collection<String> symbols, TlhHarvestEvent.HarvestMode mode,
            Collection<TlhHarvestEvent.EventStatus> excludedStatuses, LocalDateTime after);

    /** Veto dedup: same reason recorded recently. */
    boolean existsByUserIdAndSymbolAndVetoReasonAndCreatedAtAfter(
            Long userId, String symbol, String vetoReason, LocalDateTime after);

    List<TlhHarvestEvent> findTop200ByUserIdOrderByCreatedAtDesc(Long userId);

    int countByUserIdAndStatusIn(Long userId, Collection<TlhHarvestEvent.EventStatus> statuses);

    int countByUserIdAndModeAndTaxYear(Long userId, TlhHarvestEvent.HarvestMode mode, Integer taxYear);

    /** Re-entry worklist — deliberately tier-blind. */
    List<TlhHarvestEvent> findByReentryStatusAndReentryDueOnLessThanEqual(String reentryStatus, LocalDate dueOn);

    List<TlhHarvestEvent> findByReentryStatus(String reentryStatus);

    List<TlhHarvestEvent> findByStatusAndModeAndCreatedAtBefore(
            TlhHarvestEvent.EventStatus status, TlhHarvestEvent.HarvestMode mode, LocalDateTime before);

    @Query("select coalesce(sum(e.allowedLoss), 0) from TlhHarvestEvent e " +
            "where e.userId = :userId and e.mode = 'LIVE' and e.taxYear = :year")
    BigDecimal sumAllowedLossForYear(@Param("userId") Long userId, @Param("year") int year);

    @Query("select coalesce(sum(e.disallowedLoss), 0) from TlhHarvestEvent e " +
            "where e.userId = :userId and e.mode = 'LIVE' and e.taxYear = :year")
    BigDecimal sumDisallowedLossForYear(@Param("userId") Long userId, @Param("year") int year);

    @Query("select distinct e.userId from TlhHarvestEvent e where e.status in :statuses or e.reentryStatus in ('PENDING', 'SUBMITTED')")
    List<Long> findUserIdsWithOpenWork(@Param("statuses") Collection<TlhHarvestEvent.EventStatus> statuses);
}
