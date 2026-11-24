package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.dto.admin.ShadowReport;
import com.investingapp.backend.dto.admin.ShadowReport.CycleRow;
import com.investingapp.backend.dto.admin.ShadowReport.UserRow;
import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.InvestmentFrequency;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PaycheckCycle;
import com.investingapp.backend.model.PaycheckCycleState;
import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckSkipReason;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamRole;
import com.investingapp.backend.model.PaycheckStreamState;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckCycleRepository;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import com.investingapp.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShadowReportServiceTest {

    private static final LocalDate SINCE = LocalDate.of(2026, 10, 1);
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private UserRepository userRepository;
    private PaycheckCycleRepository cycleRepository;
    private ShadowReportService service;
    private User ada;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        InvestmentScheduleRepository scheduleRepository = mock(InvestmentScheduleRepository.class);
        PaycheckStreamRepository streamRepository = mock(PaycheckStreamRepository.class);
        cycleRepository = mock(PaycheckCycleRepository.class);
        PaycheckEventRepository eventRepository = mock(PaycheckEventRepository.class);
        PaycheckAutopilotProperties props = mock(PaycheckAutopilotProperties.class);
        when(props.isEnabled()).thenReturn(true);
        when(props.isShadow()).thenReturn(true);
        when(props.getAffordabilityShare()).thenReturn(new BigDecimal("0.90"));
        service = new ShadowReportService(userRepository, scheduleRepository, streamRepository, cycleRepository, eventRepository, props);

        ada = user(7L, "pub-ada-7", "ada@fredvested.com");
        when(userRepository.findByPlaidAccessTokenIsNotNull()).thenReturn(List.of(ada));
        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setDetectionState(DetectionState.PROVISIONAL);
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(ada)).thenReturn(Optional.of(schedule));
        when(streamRepository.findByUserIdOrderByIdAsc(7L)).thenReturn(List.of(primaryStream()));
        cycles(cycle(301L, "300.00", LocalDateTime.of(2026, 10, 24, 23, 10)));
        when(eventRepository.findById(301L)).thenReturn(Optional.of(event(LocalDate.of(2026, 10, 23))));
        when(eventRepository.findById(302L)).thenReturn(Optional.of(event(LocalDate.of(2026, 11, 6))));
    }

    @Test
    void rowsKeyedByPublicIdNeverEmail() throws Exception {
        ShadowReport report = service.build(SINCE);
        assertThat(report.users()).extracting(UserRow::publicId).containsExactly("pub-ada-7");
        assertThat(json.writeValueAsString(report)).doesNotContain("ada@fredvested.com").doesNotContain("item-7");
    }

    @Test
    void payerIsHashNeverDescriptor() throws Exception {
        ShadowReport report = service.build(SINCE);
        // printf 'ACME CORP' | shasum -a 256 | cut -c1-16
        assertThat(ShadowReportService.payerTokenHash("ACME CORP")).isEqualTo("d2683906516246b0");
        assertThat(ShadowReportService.payerTokenHash(null)).isNull();
        assertThat(report.users().get(0).primaryPayerTokenHash()).isEqualTo("d2683906516246b0");
        assertThat(json.writeValueAsString(report)).doesNotContainIgnoringCase("acme");
    }

    @Test
    void shadowCyclesCarryWouldHavePulled() {
        ShadowReport report = service.build(SINCE);
        UserRow row = report.users().get(0);
        assertThat(report.shadow()).isTrue();
        assertThat(row.detectionState()).isEqualTo("PROVISIONAL");
        assertThat(row.itemStatus()).isEqualTo("OK");
        assertThat(row.score()).isEqualTo(64);
        assertThat(row.breakdown()).containsEntry("total", 64).containsEntry("cadenceFit", 14);
        assertThat(row.cadence()).isEqualTo("BIWEEKLY");
        assertThat(row.depositsFound()).isEqualTo(6);
        assertThat(row.misses()).isEqualTo(1);
        assertThat(row.lowConfidence()).isFalse();
        CycleRow cycle = row.shadowCycles().get(0);
        assertThat(cycle.periodKey()).isEqualTo("2026-10-23");
        assertThat(cycle.wouldHavePulled()).isEqualByComparingTo("300.00");
        assertThat(cycle.pullShareBand()).isEqualTo("under_50");
        assertThat(cycle.passesAffordability()).isTrue();
        assertThat(report.summary().provisional()).isEqualTo(1);
        assertThat(report.summary().shadowCycles()).isEqualTo(1);
    }

    @Test
    void reportCarriesNoDepositAmount() throws Exception {
        assertThat(json.writeValueAsString(service.build(SINCE)))
                .doesNotContain("depositAmount").doesNotContain("trailingMedian").doesNotContain("2450")
                .doesNotContain("rawDescriptor").doesNotContain("payerDisplayName").doesNotContain("PAYROLL");
    }

    @Test
    void confirmLagIsEtDayMinusPostedDate() {
        cycles(cycle(301L, "300.00", LocalDateTime.of(2026, 10, 24, 23, 10)),
                cycle(302L, "300.00", LocalDateTime.of(2026, 11, 6, 8, 0)),
                cycle(null, "300.00", LocalDateTime.of(2026, 11, 20, 9, 0)));

        ShadowReport report = service.build(SINCE);

        List<CycleRow> rows = report.users().get(0).shadowCycles();
        assertThat(rows).extracting(CycleRow::confirmedOnET)
                .containsExactly(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 20));
        assertThat(rows).extracting(CycleRow::confirmLagDays).containsExactly(1, 0, -1);
        assertThat(rows.get(2).postedDate()).isNull();
        assertThat(report.summary().meanConfirmLagDays()).isEqualTo(0.5);
    }

    @Test
    void pullShareBandsAndAffordability() {
        assertThat(ShadowReportService.share(new BigDecimal("300.00"), new BigDecimal("2450.00"))).isEqualByComparingTo("0.1224");
        assertThat(ShadowReportService.share(new BigDecimal("300.00"), null)).isNull();
        Map.of("0.4999", "under_50", "0.5000", "50_79", "0.7999", "50_79", "0.8000", "80_90", "0.9000", "80_90", "0.9001", "over_90")
                .forEach((share, band) -> assertThat(ShadowReportService.pullShareBand(new BigDecimal(share))).isEqualTo(band));
        assertThat(ShadowReportService.pullShareBand(null)).isNull();
        cycles(cycle(301L, "2300.00", LocalDateTime.of(2026, 10, 24, 23, 10)));
        CycleRow row = service.build(SINCE).users().get(0).shadowCycles().get(0);
        assertThat(row.pullShareBand()).isEqualTo("over_90");
        assertThat(row.passesAffordability()).isFalse();
    }

    @Test
    void excludedUsersAreLeftOut() {
        User bob = user(8L, "pub-bob-8", "bob@fredvested.com");
        bob.setAccountStatus("REJECTED");
        User cy = user(9L, "pub-cy-9", "cy@fredvested.com");
        cy.setPaycheckAutopilotOptOut(true);
        User dan = user(10L, "pub-dan-10", "dan@fredvested.com");
        dan.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED);
        dan.setPlaidHistoryComplete(false);
        when(userRepository.findByPlaidAccessTokenIsNotNull()).thenReturn(List.of(dan, cy, ada, bob));

        ShadowReport report = service.build(SINCE);

        assertThat(report.users()).extracting(UserRow::publicId).containsExactly("pub-ada-7", "pub-dan-10");
        assertThat(report.users().get(1).itemStatus()).isEqualTo("LOGIN_REQUIRED");
        assertThat(report.users().get(1).detectionState()).isEqualTo("SEARCHING");
        assertThat(ShadowReportService.detectionState(Optional.empty(), ada, Optional.of(primaryStream()))).isEqualTo("PROVISIONAL");
        assertThat(ShadowReportService.detectionState(Optional.empty(), ada, Optional.empty())).isEqualTo("UNDETECTABLE");
    }

    private void cycles(PaycheckCycle... rows) {
        when(cycleRepository.findByUserIdAndSkipReasonAndConfirmedAtAfter(eq(7L), eq(PaycheckSkipReason.SHADOW_CONFIRMED), any()))
                .thenReturn(List.of(rows));
    }

    private static User user(Long id, String publicId, String email) {
        User user = new User(email);
        user.setId(id);
        user.setPublicId(publicId);
        user.setAccountStatus("ACTIVE");
        user.setPlaidAccessToken("ENC:token-" + id);
        user.setPlaidItemId("item-" + id);
        user.setPlaidItemStatus(PlaidItemStatus.OK);
        user.setMonthlyInvestment(650.0);
        user.setPlaidHistoryComplete(true);
        user.setPaycheckAutopilotOptOut(false);
        return user;
    }

    private static PaycheckStream primaryStream() {
        PaycheckStream stream = new PaycheckStream();
        stream.setId(41L);
        stream.setUserId(7L);
        stream.setPlaidAccountId("acc-7");
        stream.setPayerToken("ACME CORP");
        stream.setPayerDisplayName("Acme Corp");
        stream.setRawDescriptor("ACME CORP PAYROLL DIR DEP");
        stream.setState(PaycheckStreamState.PROVISIONAL);
        stream.setRole(PaycheckStreamRole.PRIMARY);
        stream.setCadence(InvestmentFrequency.BIWEEKLY);
        stream.setScore((short) 64);
        stream.setScoreBreakdownJson("{\"pfc\":20,\"plaidStatus\":10,\"median\":10,\"cadenceFit\":14,\"stability\":5,\"payrollToken\":5,\"total\":64}");
        stream.setDepositsFound((short) 6);
        stream.setMissedCount((short) 1);
        stream.setTrailingMedian(new BigDecimal("2450.00"));
        stream.setTrailingCv(new BigDecimal("0.0100"));
        stream.setLastDate(LocalDate.of(2026, 10, 9));
        return stream;
    }

    private static PaycheckCycle cycle(Long eventId, String pull, LocalDateTime confirmedAt) {
        PaycheckCycle cycle = new PaycheckCycle();
        cycle.setUserId(7L);
        cycle.setStreamId(41L);
        cycle.setPeriodKey("2026-10-23");
        cycle.setState(PaycheckCycleState.SKIPPED);
        cycle.setSkipReason(PaycheckSkipReason.SHADOW_CONFIRMED);
        cycle.setExpectedDate(LocalDate.of(2026, 10, 23));
        cycle.setEventId(eventId);
        cycle.setDepositAmount(new BigDecimal("2450.00"));
        cycle.setConfirmedAt(confirmedAt);
        cycle.setAmountToPull(new BigDecimal(pull));
        return cycle;
    }

    private static PaycheckEvent event(LocalDate posted) {
        PaycheckEvent event = new PaycheckEvent();
        event.setPostedDate(posted);
        return event;
    }
}
