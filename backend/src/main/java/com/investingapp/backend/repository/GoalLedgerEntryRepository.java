package com.investingapp.backend.repository;

import com.investingapp.backend.model.Goal;
import com.investingapp.backend.model.GoalLedgerEntry;
import com.investingapp.backend.model.GoalLedgerEntry.LedgerKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface GoalLedgerEntryRepository extends JpaRepository<GoalLedgerEntry, Long> {

    List<GoalLedgerEntry> findByGoalAndUnitAndEntryDateGreaterThanEqualOrderByEntryDateAscIdAsc(
            Goal goal, String unit, LocalDate from);

    boolean existsByGoalAndUnitAndEntryDateAndKind(Goal goal, String unit, LocalDate entryDate, LedgerKind kind);

    Optional<GoalLedgerEntry> findTopByGoalAndUnitAndKindOrderByEntryDateDescIdDesc(
            Goal goal, String unit, LedgerKind kind);

    void deleteAllByGoalIn(Collection<Goal> goals);
}
