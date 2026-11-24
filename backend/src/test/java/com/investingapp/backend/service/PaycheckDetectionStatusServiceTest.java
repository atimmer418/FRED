package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.dto.PaycheckDetectionStatusResponse;
import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.InvestmentFrequency;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PaycheckCadenceSource;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamState;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaycheckDetectionStatusServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    private final InvestmentScheduleRepository scheduleRepository = mock(InvestmentScheduleRepository.class);
    private final PaycheckStreamRepository streamRepository = mock(PaycheckStreamRepository.class);
    private final PaycheckAutopilotProperties props = mock(PaycheckAutopilotProperties.class);
    private PaycheckDetectionStatusService service;
    private User user;

    @BeforeEach
    void setUp() {
        when(props.isShadow()).thenReturn(true);
        when(props.getAffordabilityShare()).thenReturn(new BigDecimal("0.90"));
        service = new PaycheckDetectionStatusService(scheduleRepository, streamRepository, props);
        user = new User("status@fredvested.com"); // monthlyInvestment starts at 0.0
        user.setId(7L);
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.empty());
    }

    private InvestmentSchedule row(InvestmentSchedule.Status status) {
        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setId(70L);
        schedule.setUser(user);
        schedule.setMode(ScheduleMode.MANUAL);
        schedule.setStatus(status);
        schedule.setInvestmentAmount(new BigDecimal("250.00"));
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(schedule));
        return schedule;
    }

    private void acmeStream(Long ownerId) {
        PaycheckStream s = new PaycheckStream();
        s.setId(41L);
        s.setUserId(ownerId);
        s.setPayerToken("ACME CORP");
        s.setPayerDisplayName("Acme Corp");
        s.setRawDescriptor("ACME CORP PAYROLL");
        s.setState(PaycheckStreamState.CONFIRMED);
        s.setCadence(InvestmentFrequency.BIWEEKLY);
        s.setCadenceSource(PaycheckCadenceSource.OBSERVED);
        s.setAnchorDow((short) 5);
        s.setScore((short) 80);
        s.setLastAmount(new BigDecimal("2418.00"));
        s.setTrailingMedian(new BigDecimal("2418.00"));
        s.setTrailingCv(new BigDecimal("0.0100"));
        s.setPredictedNextDate(LocalDate.of(2026, 10, 23));
        when(streamRepository.findById(41L)).thenReturn(Optional.of(s));
        when(streamRepository.findByUserIdOrderByIdAsc(7L)).thenReturn(List.of(s));
    }

    @Test
    void searchingWithoutRowHasNoPrimaryAndDefaultGoal() {
        user.setPlaidAccountName("Plaid Checking");
        user.setPlaidAccountMask("0000");
        user.setPaycheckAutopilotOptOut(true);
        user.setPlaidHistoryComplete(true);

        PaycheckDetectionStatusResponse r = service.status(user, TODAY);

        assertThat(r.fundingAccountLabel()).isEqualTo("Plaid Checking ••••0000");
        assertThat(r.optOut()).isTrue();
        assertThat(r.historyComplete()).isTrue();
        assertThat(r.shadow()).isTrue();
        assertThat(r.mode()).isEqualTo("MANUAL");
        assertThat(r.state()).isEqualTo("SEARCHING");
        assertThat(r.itemStatus()).isEqualTo("OK");
        assertThat(r.paused()).isFalse();
        assertThat(r.primary()).isNull();
        assertThat(r.monthlyGoal()).isEqualByComparingTo("500");
        assertThat(r.goalSource()).isEqualTo("default");
        assertThat(r.savedAmount()).isNull();
        assertThat(r.amountSource()).isNull();
        assertThat(r.authorization()).isNull();
        assertThat(r.manualPrefill().frequency()).isEqualTo("BIWEEKLY");
        assertThat(r.manualPrefill().nextInvestmentDate()).isEqualTo(TODAY.plusDays(14));
        assertThat(r.manualPrefill().amount()).isEqualByComparingTo("230"); // round(500 / 2.17)
        verify(streamRepository, never()).findById(anyLong());
    }

    @Test
    void isPausedComesFromStatusNeverTheDeprecatedGetter() throws Exception {
        row(InvestmentSchedule.Status.PENDING_BANK);
        assertThat(service.status(user, TODAY).paused()).isTrue();
        row(InvestmentSchedule.Status.PAUSED_BY_USER);
        assertThat(service.status(user, TODAY).paused()).isTrue();
        row(InvestmentSchedule.Status.ACTIVE);
        PaycheckDetectionStatusResponse active = service.status(user, TODAY);
        assertThat(active.paused()).isFalse();

        String json = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(active);
        assertThat(json).contains("\"isPaused\":false").doesNotContain("\"paused\"");
    }

    @Test
    void primaryFromTheMirroredStreamWithPrefill() {
        user.setMonthlyInvestment(650.0);
        InvestmentSchedule schedule = row(InvestmentSchedule.Status.ACTIVE);
        schedule.setDetectionState(DetectionState.CONFIRMED);
        schedule.setPrimaryStreamId(41L);
        schedule.setNextExpectedDate(LocalDate.of(2026, 10, 23));
        acmeStream(7L);

        PaycheckDetectionStatusResponse r = service.status(user, TODAY);

        assertThat(r.state()).isEqualTo("CONFIRMED");
        assertThat(r.mode()).isEqualTo("MANUAL"); // shadow writes the mirror, never the mode (R1)
        assertThat(r.monthlyGoal()).isEqualByComparingTo("650");
        assertThat(r.goalSource()).isEqualTo("survey");
        PaycheckDetectionStatusResponse.PrimaryStreamView p = r.primary();
        assertThat(p.streamId()).isEqualTo(41L);
        assertThat(p.rawDescriptor()).isEqualTo("ACME CORP PAYROLL");
        assertThat(p.cadence()).isEqualTo("BIWEEKLY");
        assertThat(p.cadenceLabel()).startsWith("Every other ");
        assertThat(p.paychecksPerYear()).isEqualTo(26);
        assertThat(p.factor()).isEqualByComparingTo("2.17");
        assertThat(p.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(p.suggestedAmount()).isEqualTo(300);   // round(650 / 2.17)
        assertThat(p.affordabilityCap()).isEqualTo(2176); // floor(0.90 x 2418)
        assertThat(p.prefillAmount()).isEqualTo(300);
        assertThat(p.lowConfidence()).isFalse();
        assertThat(p.needsChooser()).isFalse();
        assertThat(r.manualPrefill().amount()).isEqualByComparingTo("250.00"); // savedAmount wins
    }

    @Test
    void noPrimaryInSearchingNeedsConsentOrUndetectableEvenWithAnId() {
        InvestmentSchedule schedule = row(InvestmentSchedule.Status.ACTIVE);
        schedule.setPrimaryStreamId(41L);
        acmeStream(7L);
        for (DetectionState state : List.of(DetectionState.SEARCHING, DetectionState.NEEDS_CONSENT, DetectionState.UNDETECTABLE)) {
            schedule.setDetectionState(state);
            assertThat(service.status(user, TODAY).primary()).as(state.name()).isNull();
        }
        verify(streamRepository, never()).findById(anyLong());
    }

    @Test
    void anotherUsersStreamIsNeverShown() {
        InvestmentSchedule schedule = row(InvestmentSchedule.Status.ACTIVE);
        schedule.setDetectionState(DetectionState.PROVISIONAL);
        schedule.setPrimaryStreamId(41L);
        acmeStream(8L);

        assertThat(service.status(user, TODAY).primary()).isNull();
    }
}
