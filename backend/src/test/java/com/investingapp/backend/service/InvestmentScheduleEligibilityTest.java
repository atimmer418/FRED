package com.investingapp.backend.service;

import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvestmentScheduleEligibilityTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);
    private final InvestmentScheduleRepository repository = mock(InvestmentScheduleRepository.class);
    private final InvestmentScheduleService service = new InvestmentScheduleService(
            repository, mock(PortfolioService.class), mock(StreakService.class), mock(AnalyticsOutboxService.class));

    private static InvestmentSchedule manualDueToday() {
        InvestmentSchedule s = new InvestmentSchedule();
        s.setMode(ScheduleMode.MANUAL);
        s.setIsPaused(false);
        s.setAchRequestId("rel-1");
        s.setStartDate(TODAY.minusMonths(1));
        s.setNextInvestmentDate(TODAY);
        return s;
    }

    private static boolean ready(Consumer<InvestmentSchedule> change, LocalDate today) {
        InvestmentSchedule s = manualDueToday();
        change.accept(s);
        return s.isReadyForInvestment(today);
    }

    @Test
    void fourManualPinsAndTodayComesFromTheArgument() {
        assertThat(ready(s -> { }, TODAY)).isTrue();
        assertThat(ready(s -> s.setNextInvestmentDate(TODAY.minusDays(3)), TODAY)).isTrue();
        assertThat(ready(s -> { s.setStartDate(TODAY); s.setNextInvestmentDate(TODAY.plusWeeks(1)); }, TODAY)).isTrue();
        assertThat(ready(s -> s.setIsPaused(true), TODAY)).isFalse();
        assertThat(ready(s -> s.setAchRequestId(null), TODAY)).isFalse();
        assertThat(ready(s -> { }, TODAY.minusDays(1))).isFalse();
    }

    @Test
    void manualHeldByReturnNotReady() {
        for (HoldReason hold : List.of(HoldReason.ACH_RETURN_UNAUTHORIZED, HoldReason.ACH_RETURN_ACCOUNT, HoldReason.MANUAL_REVIEW)) {
            assertThat(ready(s -> s.setHoldReason(hold), TODAY)).as(hold.name()).isFalse();
        }
        assertThat(ready(s -> s.setHoldReason(HoldReason.BANK_CHANGE), TODAY)).isTrue();
    }

    @Test
    void detectedModeIsNeverReadyInPhaseZero() {
        InvestmentSchedule detected = manualDueToday();
        detected.setMode(ScheduleMode.DETECTED);
        when(repository.findByUser_AccountStatus("ACTIVE")).thenReturn(List.of(detected, manualDueToday()));

        assertThat(service.getSchedulesReadyForInvestment(TODAY)).hasSize(1).allMatch(s -> s.getMode() == ScheduleMode.MANUAL);
    }

    @Test
    void resetForBankChangeHoldsWithoutPausing() {
        User user = new User("bank@fredvested.com");
        InvestmentSchedule s = manualDueToday();
        when(repository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(s));

        service.resetForBankChange(user);

        assertThat(s.getAchRequestId()).isNull();
        assertThat(s.getHoldReason()).isEqualTo(HoldReason.BANK_CHANGE);
        assertThat(s.getIsPaused()).isFalse();
    }
}
