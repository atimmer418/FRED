package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.*;
import com.investingapp.backend.paycheck.cycle.*;
import com.investingapp.backend.paycheck.detect.*;
import com.investingapp.backend.repository.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.*;

import java.io.IOException;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;

import static com.investingapp.backend.model.PaycheckCycleState.CONFIRMED;
import static com.investingapp.backend.model.PaycheckCycleState.MISSED;
import static com.investingapp.backend.model.PaycheckCycleState.OPEN;
import static com.investingapp.backend.model.PaycheckCycleState.SKIPPED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaycheckCycleServiceTest {

    private static final Long USER_ID = 42L;
    private static final LocalDate EXPECTED = LocalDate.of(2026, 9, 25);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 25, 9, 5);

    private PaycheckCycleRepository cycleRepository;
    private PaycheckStreamRepository streamRepository;
    private PaycheckEventRepository eventRepository;
    private PaycheckAutopilotProperties props;
    private PaycheckCycleService service;
    private User user;
    private InvestmentSchedule schedule;
    private PaycheckStream primary;
    private PaycheckCycle open;
    private PaycheckEvent deposit;

    @BeforeEach
    void setUp() {
        cycleRepository = mock(PaycheckCycleRepository.class);
        streamRepository = mock(PaycheckStreamRepository.class);
        eventRepository = mock(PaycheckEventRepository.class);
        InvestmentScheduleRepository scheduleRepository = mock(InvestmentScheduleRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        props = mock(PaycheckAutopilotProperties.class);
        when(props.isShadow()).thenReturn(true);
        when(props.getAffordabilityShare()).thenReturn(new BigDecimal("0.90"));
        service = new PaycheckCycleService(cycleRepository, streamRepository, eventRepository, scheduleRepository,
                userRepository, new CycleGateChain(List.of(new ShadowGate(), new KillSwitchGate())), props);

        user = new User("ada@example.com");
        user.setId(USER_ID);
        schedule = new InvestmentSchedule();
        schedule.setId(9L);
        schedule.setUser(user);
        schedule.setInvestmentAmount(new BigDecimal("150.00"));
        schedule.setPrimaryStreamId(7L);
        primary = new PaycheckStream();
        primary.setId(7L);
        primary.setUserId(USER_ID);
        primary.setPlaidAccountId("acc-1");
        primary.setPayerToken("ACME CORP");
        primary.setState(PaycheckStreamState.PROVISIONAL);
        primary.setRole(PaycheckStreamRole.PRIMARY);
        primary.setCadence(InvestmentFrequency.BIWEEKLY);
        primary.setCadenceSource(PaycheckCadenceSource.OBSERVED);
        primary.setAnchorDow((short) 5);
        primary.setAnchorParityDate(LocalDate.of(2026, 9, 11));
        primary.setTrailingMedian(new BigDecimal("2418.00"));
        primary.setTrailingCv(new BigDecimal("0.0500"));
        primary.setDepositsFound((short) 4);
        primary.setLastDate(LocalDate.of(2026, 9, 11));
        open = cycle(100L, USER_ID, OPEN, EXPECTED);
        deposit = deposit(500L, "acc-1", "ACME CORP PAYROLL 2418 PPD");

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(schedule));
        when(streamRepository.findById(7L)).thenReturn(Optional.of(primary));
        when(eventRepository.findById(500L)).thenReturn(Optional.of(deposit));
        when(cycleRepository.findByUserIdAndState(USER_ID, OPEN)).thenReturn(List.of(open));
    }

    private static PaycheckCycle cycle(Long id, Long userId, PaycheckCycleState state, LocalDate expected) {
        PaycheckCycle c = new PaycheckCycle();
        c.setId(id);
        c.setUserId(userId);
        c.setStreamId(7L);
        c.setScheduleId(9L);
        c.setState(state);
        c.setPeriodKey(expected.toString());
        c.setExpectedDate(expected);
        c.setWindowStart(expected.minusDays(7));
        c.setWindowEnd(expected.plusDays(11));
        c.setCreatedAt(NOW.minusDays(14));
        return c;
    }

    private static PaycheckEvent deposit(Long id, String accountId, String descriptor) {
        PaycheckEvent e = new PaycheckEvent();
        e.setId(id);
        e.setUserId(USER_ID);
        e.setPlaidAccountId(accountId);
        e.setAmount(new BigDecimal("2418.00"));
        e.setRawAmount(new BigDecimal("-2418.00"));
        e.setPostedDate(EXPECTED);
        e.setStatus(PaycheckEventStatus.ACTIVE);
        e.setFirstSeenAt(LocalDateTime.of(2026, 9, 25, 9, 0));
        e.setRawDescriptor(descriptor);
        return e;
    }

    private List<PaycheckCycle> savedCycles(int times) {
        ArgumentCaptor<PaycheckCycle> saved = ArgumentCaptor.forClass(PaycheckCycle.class);
        verify(cycleRepository, times(times)).save(saved.capture());
        return saved.getAllValues();
    }

    private List<MatchDecision> apply(Optional<InvestmentSchedule> row, Long... ids) {
        return service.applyNewDeposits(user, row, primary, List.of(ids), NOW);
    }

    @Test
    void openCycleForPrimaryWritesOpenCycleAndMirror() {
        when(cycleRepository.findByUserIdAndState(USER_ID, OPEN)).thenReturn(List.of());
        CadenceResult cadence = PaycheckCycleService.cadenceOf(primary);
        LocalDate expected = PayCalendar.predictedNext(cadence, primary.getLastDate());
        var window = PayCalendar.window(InvestmentFrequency.BIWEEKLY, expected);

        PaycheckCycle c = service.openCycleForPrimary(USER_ID, 7L).orElseThrow();

        assertThat(c).extracting(PaycheckCycle::getState, PaycheckCycle::getStreamId, PaycheckCycle::getScheduleId,
                        PaycheckCycle::getExpectedDate, PaycheckCycle::getPeriodKey, PaycheckCycle::getWindowStart, PaycheckCycle::getWindowEnd)
                .containsExactly(OPEN, 7L, 9L, expected, PayCalendar.periodKey(cadence, expected), window.start(), window.end());
        assertThat(schedule.getNextExpectedDate()).isEqualTo(expected);
        when(cycleRepository.findByUserIdAndState(USER_ID, OPEN)).thenReturn(List.of(c));
        assertThat(service.openCycleForPrimary(USER_ID, 7L)).contains(c); // the second call writes nothing
        assertThat(savedCycles(1)).containsExactly(c);
    }

    @Test
    void shadowSkipRecordsWouldHaveAmountFromManualRow() {
        assertThat(apply(Optional.of(schedule), 500L)).containsExactly(MatchDecision.CONFIRMABLE);
        assertThat(open).extracting(PaycheckCycle::getState, PaycheckCycle::getSkipReason, PaycheckCycle::getEventId,
                PaycheckCycle::getConfirmedAt).containsExactly(SKIPPED, PaycheckSkipReason.SHADOW_CONFIRMED, 500L, NOW);
        assertThat(open.getDepositAmount()).isEqualByComparingTo("2418.00");
        assertThat(open.getAmountToPull()).isEqualByComparingTo("150.00");
        LocalDate nextExpected = PayCalendar.predictedNext(PaycheckCycleService.cadenceOf(primary), EXPECTED);
        PaycheckCycle next = savedCycles(2).get(1);
        assertThat(next.getState()).isEqualTo(OPEN);
        assertThat(next.getExpectedDate()).isEqualTo(nextExpected);
        assertThat(schedule.getNextExpectedDate()).isEqualTo(nextExpected);
        assertThat(schedule.getHoldReason()).isNull();
        assertThat(schedule.getMode()).isEqualTo(ScheduleMode.MANUAL);
        assertThat(deposit.getStreamId()).isEqualTo(7L);
        assertThat(primary.getLastDate()).isEqualTo(EXPECTED);
    }

    @Test
    void shadowPrefillFromSurveyGoalWhenNoRow() {
        user.setMonthlyInvestment(650.0);
        apply(Optional.empty(), 500L);
        assertThat(open.getSkipReason()).isEqualTo(PaycheckSkipReason.SHADOW_CONFIRMED);
        assertThat(open.getAmountToPull()).isEqualByComparingTo("300");
        assertThat(savedCycles(2).get(1).getScheduleId()).isNull();
    }

    @Test
    void confirmedUnreachableWhilePullsDisabled() {
        when(props.isShadow()).thenReturn(false);
        apply(Optional.of(schedule), 500L);
        assertThat(open.getSkipReason()).isEqualTo(PaycheckSkipReason.KILL_SWITCH);
        assertThat(savedCycles(2)).noneMatch(c -> c.getState() == CONFIRMED);
    }

    @Test
    void noSkipIsAnIllegalStateInPhaseOne() {
        when(props.isShadow()).thenReturn(false);
        when(props.isPullsEnabled()).thenReturn(true);
        assertThatThrownBy(() -> apply(Optional.of(schedule), 500L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no CONFIRMED writer");
        assertThat(open.getState()).isEqualTo(OPEN);
        verify(cycleRepository, never()).save(any());
    }

    @Test
    void depositsOfOtherPayersAreIgnored() {
        PaycheckEvent otherAccount = deposit(501L, "acc-2", "ACME CORP PAYROLL 2418 PPD");
        when(eventRepository.findById(501L)).thenReturn(Optional.of(otherAccount));
        deposit.setRawDescriptor("VENMO CASHOUT JANE");
        assertThat(apply(Optional.of(schedule), 500L, 501L)).isEmpty();
        assertThat(deposit.getStreamId()).isNull();
        verify(cycleRepository, never()).save(any());
    }

    @Test
    void lateSeenClosesOpenCycleWithoutWouldHaveAmount() {
        deposit.setFirstSeenAt(LocalDateTime.of(2026, 10, 5, 8, 0));
        assertThat(apply(Optional.of(schedule), 500L)).containsExactly(MatchDecision.LATE_SEEN);
        assertThat(open.getSkipReason()).isEqualTo(PaycheckSkipReason.LATE_SEEN);
        assertThat(open.getEventId()).isEqualTo(500L);
        assertThat(open.getConfirmedAt()).isNull();
        assertThat(open.getAmountToPull()).isNull();
    }

    @Test
    void missSetsDormantWithoutHoldOrStatusChange() {
        InvestmentSchedule.Status statusBefore = schedule.getStatus();
        assertThat(service.markMissesForUser(USER_ID, LocalDate.of(2026, 10, 14))).isEqualTo(1);
        assertThat(open.getState()).isEqualTo(MISSED);
        assertThat(primary.getState()).isEqualTo(PaycheckStreamState.DORMANT);
        assertThat(primary.getMissedCount()).isEqualTo((short) 1);
        assertThat(schedule).extracting(InvestmentSchedule::getDetectionState, InvestmentSchedule::getMissedCount,
                        InvestmentSchedule::getDormantSince, InvestmentSchedule::getHoldReason, InvestmentSchedule::getStatus,
                        InvestmentSchedule::getMode)
                .containsExactly(DetectionState.DORMANT, (short) 1, LocalDate.of(2026, 10, 6), null, statusBefore, ScheduleMode.MANUAL);
        PaycheckCycle next = savedCycles(2).get(1);
        assertThat(next.getState()).isEqualTo(OPEN);
        assertThat(next.getExpectedDate()).isEqualTo(PayCalendar.predictedNext(PaycheckCycleService.cadenceOf(primary), EXPECTED));
    }

    @Test
    void missedPeriodWithALaterDepositDoesNotMakeTheStreamDormant() {
        primary.setLastDate(LocalDate.of(2026, 10, 2)); // a deposit landed after the missed period's expected date
        assertThat(service.markMissesForUser(USER_ID, LocalDate.of(2026, 10, 14))).isEqualTo(1);
        assertThat(open.getState()).isEqualTo(MISSED);
        assertThat(primary.getState()).isEqualTo(PaycheckStreamState.PROVISIONAL);
        assertThat(primary.getMissedCount()).isZero();
        assertThat(schedule).extracting(InvestmentSchedule::getDetectionState, InvestmentSchedule::getMissedCount,
                        InvestmentSchedule::getDormantSince)
                .containsExactly(null, (short) 0, null);
    }

    @Test
    void retiresAfter120DaysKeepsTokenAndCadence() {
        primary.setState(PaycheckStreamState.DORMANT);
        primary.setMissedCount((short) 9);
        primary.setLastDate(LocalDate.of(2026, 5, 1));
        PaycheckCycle miss = cycle(101L, USER_ID, MISSED, LocalDate.of(2026, 5, 15)); // window_end 2026-05-26
        when(cycleRepository.findByUserIdAndState(USER_ID, MISSED)).thenReturn(List.of(miss));
        when(streamRepository.findByUserIdAndState(USER_ID, PaycheckStreamState.DORMANT)).thenReturn(List.of(primary));
        assertThat(service.retireDormantForUser(USER_ID, LocalDate.of(2026, 9, 22))).isZero();
        assertThat(service.retireDormantForUser(USER_ID, LocalDate.of(2026, 9, 23))).isEqualTo(1);
        assertThat(primary).extracting(PaycheckStream::getState, PaycheckStream::getRetiredReason, PaycheckStream::getPayerToken,
                        PaycheckStream::getCadence, PaycheckStream::getAnchorDow)
                .containsExactly(PaycheckStreamState.RETIRED, PaycheckRetiredReason.DORMANT_120, "ACME CORP",
                        InvestmentFrequency.BIWEEKLY, (short) 5);
        assertThat(primary.getRetiredAt()).isNotNull();
        assertThat(primary.getTrailingMedian()).isEqualByComparingTo("2418.00");
        assertThat(schedule.getPrimaryStreamId()).isEqualTo(7L); // R6: stream state only
    }

    @Test
    void staleConfirmedBecomesSkippedStale() {
        PaycheckCycle confirmed = cycle(102L, USER_ID, CONFIRMED, EXPECTED);
        confirmed.setConfirmedAt(NOW.minusDays(8));
        when(cycleRepository.findByUserIdAndState(USER_ID, CONFIRMED)).thenReturn(List.of(confirmed));
        assertThat(service.markStaleForUser(USER_ID, NOW)).isEqualTo(1);
        assertThat(confirmed.getState()).isEqualTo(SKIPPED);
        assertThat(confirmed.getSkipReason()).isEqualTo(PaycheckSkipReason.STALE);
    }

    @Test
    void reconcileFindersReturnDistinctUsersAndPinCutoffs() {
        PaycheckCycle a = cycle(1L, 43L, OPEN, EXPECTED);
        PaycheckCycle b = cycle(2L, USER_ID, OPEN, EXPECTED);
        when(cycleRepository.findByStateAndWindowEndBefore(OPEN, LocalDate.of(2026, 10, 7))).thenReturn(List.of(a, b, a));
        assertThat(service.usersWithOverdueOpenCycles(LocalDate.of(2026, 10, 14))).containsExactly(42L, 43L);
        // 120 days on or before today means window_end before today minus 119.
        when(cycleRepository.findByStateAndWindowEndBefore(MISSED, LocalDate.of(2026, 5, 27))).thenReturn(List.of(b));
        assertThat(service.usersWithDormantStreamsOver(LocalDate.of(2026, 9, 23))).containsExactly(42L);
        when(cycleRepository.findByStateAndConfirmedAtBefore(CONFIRMED, NOW.minusDays(7))).thenReturn(List.of(a));
        assertThat(service.usersWithStaleConfirmed(NOW)).containsExactly(43L);
    }

    @Test
    void cycleCodeIsMandatoryAndTouchesNoHoldStatusModeNoticeOrVendor() throws IOException {
        for (Method m : PaycheckCycleService.class.getDeclaredMethods()) {
            Transactional tx = m.getAnnotation(Transactional.class);
            if (m.getName().startsWith("usersWith")) {
                assertThat(tx.readOnly()).as(m.getName()).isTrue();
            } else if (Modifier.isPublic(m.getModifiers()) && !m.getName().equals("cadenceOf")) {
                assertThat(tx.propagation()).as(m.getName()).isEqualTo(Propagation.MANDATORY);
            }
        }
        Path root = Path.of("src/main/java/com/investingapp/backend");
        List<Path> sources;
        try (Stream<Path> files = Files.walk(root.resolve("paycheck/cycle"))) {
            sources = new ArrayList<>(files.filter(p -> p.toString().endsWith(".java")).toList());
        }
        sources.add(root.resolve("service/PaycheckCycleService.java"));
        assertThat(sources).hasSize(10);
        // Concatenated so this test file never matches the source-walking invariance tests.
        List<String> forbidden = List.of("set" + "HoldReason(", "set" + "Status(", "set" + "Mode(", "getIs" + "Paused(",
                "isPausedFor" + "AnyReason(", "AnalyticsOutbox", "NotificationService", "EmailService", "PlaidApi", "Alpaca");
        for (Path source : sources) {
            String text = Files.readString(source);
            forbidden.forEach(token -> assertThat(text).as(source + " contains " + token).doesNotContain(token));
        }
    }
}
