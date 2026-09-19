package com.investingapp.backend.repository;

import com.investingapp.backend.model.WashSaleWindow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WashSaleWindowRepository extends JpaRepository<WashSaleWindow, Long> {

    List<WashSaleWindow> findByUserIdAndActiveTrue(Long userId);

    List<WashSaleWindow> findByUserIdAndSymbolInAndActiveTrue(Long userId, Collection<String> symbols);

    List<WashSaleWindow> findByActiveTrueAndEndDateBefore(LocalDate date);

    Optional<WashSaleWindow> findByHarvestEventId(Long harvestEventId);

    boolean existsByUserIdAndActiveTrue(Long userId);

    @Query("select distinct w.userId from WashSaleWindow w where w.active = true")
    List<Long> findUserIdsWithActiveWindows();
}
