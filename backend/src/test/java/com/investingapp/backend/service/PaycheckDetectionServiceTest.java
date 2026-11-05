package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentFrequency;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckEventStatus;
import com.investingapp.backend.model.PaycheckRejectReason;
import com.investingapp.backend.model.PaycheckRetiredReason;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamRole;
import com.investingapp.backend.model.PaycheckStreamState;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.paycheck.EvaluationOutcome;
import com.investingapp.backend.paycheck.EvaluationTrigger;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.DetectionEligibility.ExclusionReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaycheckDetectionServiceTest {

    private static final Long USER_ID = 42L;
    /** A Wednesday. */
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);
    /** Six biweekly Fridays; the next one is 2026-11-06. */
    private static final List<LocalDate> ACME_FRIDAYS = List.of(
            LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 28), LocalDate.of(2026, 9, 11),
            LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 23));
    /** Wire names are literals on purpose, as in ScheduleTransitionAnalyticsTest. */
    private static final String SWITCHED = "paycheck_stream_switched";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final InvestmentScheduleRepository scheduleRepository = mock(InvestmentScheduleRepository.class);
    private final PaycheckStreamRepository streamRepository = mock(PaycheckStreamRepository.class);
    private final PaycheckEventRepository eventRepository = mock(PaycheckEventRepository.class);
    private final AnalyticsOutboxService analyticsOutboxService = mock(AnalyticsOutboxService.class);
    private final PaycheckAutopilotProperties props = mock(PaycheckAutopilotProperties.class);

    private final List<PaycheckStream> storedStreams = new ArrayList<>();
    private final List<PaycheckEvent> storedEvents = new ArrayList<>();
    private final AtomicLong nextStreamId = new AtomicLong(100);
    private User user;
    private PaycheckDetectionService service;

    @BeforeEach
    void setUp() {
        when(props.isEnabled()).thenReturn(true);
        when(props.isShadow()).thenReturn(true);
        when(props.isPullsEnabled()).thenReturn(false);
        when(props.getScoreProvisional()).thenReturn(55);
        when(props.getScoreConfirmed()).thenReturn(70);
        when(props.getAffordabilityShare()).thenReturn(new BigDecimal("0.90"));
        when(props.getDaysRequested()).thenReturn(365);

        user = activeUser();
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));

        when(streamRepository.findByUserIdOrderByIdAsc(USER_ID)).thenAnswer(i -> new ArrayList<>(storedStreams));
        when(streamRepository.save(any(PaycheckStream.class))).thenAnswer(i -> {
            PaycheckStream s = i.getArgument(0);
            if (s.getId() == null) {
                s.setId(nextStreamId.getAndIncrement());
                storedStreams.add(s);
            }
            return s;
        });
        when(eventRepository.findByUserIdAndStatusAndPendingFalseOrderByPostedDateAsc(USER_ID, PaycheckEventStatus.ACTIVE))
                .thenAnswer(i -> storedEvents.stream().sorted(Comparator.comparing(PaycheckEvent::getPostedDate)).toList());

        service = new PaycheckDetectionService(userRepository, scheduleRepository, streamRepository,
                eventRepository, analyticsOutboxService, props);
    }

    @Test
    void manualRowWithoutOptOutKeepsEvaluating() {
        PaycheckStream acme = acmeCatalogRow();
        acmeHistory("acc-1");
        withSchedule(manualSchedule(InvestmentSchedule.Status.ACTIVE, null));

        EvaluationOutcome outcome = service.evaluate(USER_ID, EvaluationTrigger.FULL, TODAY);

        assertThat(outcome.excluded()).isEmpty();
        assertThat(outcome.state()).isEqualTo(DetectionState.CONFIRMED);
        assertThat(outcome.primaryStreamId()).isEqualTo(acme.getId());
        assertThat(outcome.primaryChanged()).isTrue();
        assertThat(outcome.switched()).isFalse();
        assertThat(storedStreams).containsExactly(acme);
        assertThat(acme.getState()).isEqualTo(PaycheckStreamState.CONFIRMED);
        assertThat(acme.getRole()).isEqualTo(PaycheckStreamRole.PRIMARY);
        assertThat(acme.getCadence()).isEqualTo(InvestmentFrequency.BIWEEKLY);
        assertThat(acme.getDepositsFound()).isEqualTo((short) 6);
        assertThat(acme.getLastDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(acme.getTrailingMedian()).isEqualByComparingTo("2418.37");
        assertThat(acme.getScore()).isGreaterThanOrEqualTo((short) 70);
        assertThat(storedEvents).allSatisfy(e -> assertThat(e.getStreamId()).isEqualTo(acme.getId()));
        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    @Test
    void rejectedOptOutNoTokenAndBrokenItemSkipped() {
        acmeCatalogRow();
        acmeHistory("acc-1");

        assertSkipped(u -> u.setAccountStatus("REJECTED"), ExclusionReason.ACCOUNT_REJECTED);
        assertSkipped(u -> u.setPaycheckAutopilotOptOut(true), ExclusionReason.OPTED_OUT);
        assertSkipped(u -> u.setPlaidAccessToken(null), ExclusionReason.NO_TOKEN);
        assertSkipped(u -> u.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED), ExclusionReason.ITEM_STATUS);

        verifyNoInteractions(streamRepository, eventRepository, scheduleRepository, analyticsOutboxService);
    }

    @Test
    void shadowMirrorsStateOntoManualRowAndNeverWritesMode() {
        PaycheckStream acme = acmeCatalogRow();
        acmeHistory("acc-1");
        InvestmentSchedule schedule = withSchedule(manualSchedule(InvestmentSchedule.Status.ACTIVE, null));

        service.evaluate(USER_ID, EvaluationTrigger.FULL, TODAY);

        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.CONFIRMED);
        assertThat(schedule.getPrimaryStreamId()).isEqualTo(acme.getId());
        assertThat(schedule.getNextExpectedDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(schedule.getLastCadenceCheckAt()).isNotNull();
        assertThat(schedule.getMode()).isEqualTo(ScheduleMode.MANUAL);
        assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.ACTIVE);
        assertThat(schedule.getHoldReason()).isNull();
        verify(scheduleRepository).save(schedule);
    }

    @Test
    void noScheduleRowStillRecordsStreams() {
        PaycheckStream acme = acmeCatalogRow();
        acmeHistory("acc-1");

        EvaluationOutcome outcome = service.evaluate(USER_ID, EvaluationTrigger.FULL, TODAY);

        assertThat(outcome.state()).isEqualTo(DetectionState.CONFIRMED);
        assertThat(acme.getRole()).isEqualTo(PaycheckStreamRole.PRIMARY);
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void evaluateNeverWritesStatusOrHold() {
        acmeCatalogRow();
        acmeHistory("acc-1");
        InvestmentSchedule schedule = withSchedule(
                spy(manualSchedule(InvestmentSchedule.Status.PAUSED_BY_USER, HoldReason.BANK_CHANGE)));

        service.evaluate(USER_ID, EvaluationTrigger.RECONCILE, TODAY);

        verify(schedule, never()).setStatus(any());
        verify(schedule, never()).setHoldReason(any());
        verify(schedule, never()).setMode(any());
        verify(schedule, never()).setInvestmentAmount(any());
        verify(schedule, never()).setMonthlyAmount(any());
        verify(schedule, never()).setNextInvestmentDate(any());
        verify(schedule, never()).setAchRequestId(any());
        verify(schedule, never()).setFrequency(any());
        assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.PAUSED_BY_USER);
        assertThat(schedule.getHoldReason()).isEqualTo(HoldReason.BANK_CHANGE);
        // The mirror still lands on a paused, held row, and the row still cannot run.
        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.CONFIRMED);
        assertThat(schedule.isReadyForInvestment(TODAY)).isFalse();
        verify(userRepository, never()).save(any());
        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    @Test
    void searchingUntilHistoryCompleteThenUndetectable() {
        PaycheckStream acme = acmeCatalogRow();
        acmeHistory("acc-1");
        user.setPlaidHistoryComplete(false);
        InvestmentSchedule schedule = withSchedule(manualSchedule(InvestmentSchedule.Status.ACTIVE, null));

        EvaluationOutcome searching = service.evaluate(USER_ID, EvaluationTrigger.INCREMENTAL, TODAY);

        assertThat(searching.state()).isEqualTo(DetectionState.SEARCHING);
        assertThat(searching.primaryStreamId()).isNull();
        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.SEARCHING);
        assertThat(schedule.getPrimaryStreamId()).isNull();
        assertThat(acme.getRole()).isEqualTo(PaycheckStreamRole.SECONDARY);

        // History complete, and the only inflow is a benefits payment.
        storedStreams.clear();
        storedEvents.clear();
        user.setPlaidHistoryComplete(true);
        for (LocalDate posted : List.of(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 23))) {
            PaycheckEvent benefit = deposit("edd-" + posted, "acc-1", "EDD UI BENEFIT", null, "900.55", posted);
            benefit.setCounterpartyType(null);
            benefit.setPfcDetailed("INCOME_OTHER");
        }

        EvaluationOutcome undetectable = service.evaluate(USER_ID, EvaluationTrigger.FULL, TODAY);

        assertThat(undetectable.state()).isEqualTo(DetectionState.UNDETECTABLE);
        assertThat(undetectable.primaryStreamId()).isNull();
        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.UNDETECTABLE);
        assertThat(schedule.getNextExpectedDate()).isNull();
        PaycheckStream benefits = storedStreams.get(0);
        assertThat(benefits.getState()).isEqualTo(PaycheckStreamState.REJECTED);
        assertThat(benefits.getRejectReason()).isEqualTo(PaycheckRejectReason.BENEFITS_SIGNAL);
        assertThat(benefits.getRole()).isEqualTo(PaycheckStreamRole.INTERRUPTION);
    }

    @Test
    void oneDepositIsCandidateNeverPrimary() {
        deposit("acme-only", "acc-1", "ACME CORP PAYROLL", "Acme Corp", "2418.37", LocalDate.of(2026, 10, 23));

        EvaluationOutcome outcome = service.evaluate(USER_ID, EvaluationTrigger.FULL, TODAY);

        assertThat(outcome.state()).isEqualTo(DetectionState.UNDETECTABLE);
        PaycheckStream candidate = storedStreams.get(0);
        assertThat(candidate.getState()).isEqualTo(PaycheckStreamState.CANDIDATE);
        assertThat(candidate.getRole()).isEqualTo(PaycheckStreamRole.NONE);
        assertThat(candidate.getDepositsFound()).isEqualTo((short) 1);
    }

    @Test
    void onlyTheFundingAccountLegCanBePrimary() {
        PaycheckStream savingsLeg = acmeCatalogRow();
        savingsLeg.setPlaidAccountId("acc-2");
        acmeHistory("acc-2");

        EvaluationOutcome outcome = service.evaluate(USER_ID, EvaluationTrigger.FULL, TODAY);

        assertThat(savingsLeg.getState()).isEqualTo(PaycheckStreamState.CONFIRMED);
        assertThat(savingsLeg.getRole()).isEqualTo(PaycheckStreamRole.SECONDARY);
        assertThat(outcome.state()).isEqualTo(DetectionState.UNDETECTABLE);
        assertThat(outcome.primaryStreamId()).isNull();
    }

    @Test
    void successionRetiresS1AndEmitsWholeMapOnce() {
        PaycheckStream acme = storedStream("ACME CORP", PaycheckStreamState.DORMANT, PaycheckStreamRole.PRIMARY);
        acme.setMissedCount((short) 1);
        acme.setCadence(InvestmentFrequency.BIWEEKLY);
        acme.setScore((short) 85);
        acme.setDepositsFound((short) 6);
        acme.setLastDate(LocalDate.of(2026, 9, 25));
        acme.setTrailingMedian(new BigDecimal("2418.37"));
        globexHistory();
        InvestmentSchedule schedule = withSchedule(manualSchedule(InvestmentSchedule.Status.ACTIVE, null));
        schedule.setPrimaryStreamId(acme.getId());

        // Order inside the one transaction: S1 retired, S2 promoted, the mirror moved, then the event.
        AtomicReference<String> stateAtEmit = new AtomicReference<>();
        when(analyticsOutboxService.record(eq(SWITCHED), eq(user), any())).thenAnswer(i -> {
            PaycheckStream globex = streamWithToken("GLOBEX");
            stateAtEmit.set(acme.getState() + "/" + acme.getRetiredReason() + "/" + globex.getRole() + "/"
                    + globex.getState() + "/" + globex.getId().equals(schedule.getPrimaryStreamId()));
            return null;
        });

        EvaluationOutcome outcome = service.evaluate(USER_ID, EvaluationTrigger.INCREMENTAL, TODAY);

        PaycheckStream globex = streamWithToken("GLOBEX");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> properties = ArgumentCaptor.forClass(Map.class);
        verify(analyticsOutboxService, times(1)).record(eq(SWITCHED), eq(user), properties.capture());
        assertThat(properties.getValue()).containsExactly(entry("frequency", "biweekly"), entry("reason", "succession"));
        assertThat(properties.getValue()).hasSize(2);
        assertThat(stateAtEmit.get()).isEqualTo("RETIRED/SUCCEEDED/PRIMARY/PROVISIONAL/true");
        assertThat(acme.getRole()).isEqualTo(PaycheckStreamRole.NONE);
        assertThat(acme.getRetiredAt()).isNotNull();
        assertThat(outcome.switched()).isTrue();
        assertThat(outcome.primaryChanged()).isTrue();
        assertThat(outcome.primaryStreamId()).isEqualTo(globex.getId());
        assertThat(outcome.state()).isEqualTo(DetectionState.PROVISIONAL);
        assertThat(schedule.getPrimaryStreamId()).isEqualTo(globex.getId());
        assertThat(schedule.getHoldReason()).isNull();

        // Nothing new: the switch is not re-announced.
        EvaluationOutcome again = service.evaluate(USER_ID, EvaluationTrigger.RECONCILE, TODAY);
        assertThat(again.switched()).isFalse();
        assertThat(again.primaryChanged()).isFalse();
        verify(analyticsOutboxService, times(1)).record(any(), any(), any());
    }

    @Test
    void successionNotTriggeredWhileS1KeepsPaying() {
        PaycheckStream acme = acmeCatalogRow();
        acme.setRole(PaycheckStreamRole.PRIMARY);
        acme.setState(PaycheckStreamState.CONFIRMED);
        acmeHistory("acc-1");
        globexHistory();

        EvaluationOutcome outcome = service.evaluate(USER_ID, EvaluationTrigger.INCREMENTAL, TODAY);

        assertThat(outcome.switched()).isFalse();
        assertThat(outcome.primaryChanged()).isFalse();
        assertThat(outcome.primaryStreamId()).isEqualTo(acme.getId());
        assertThat(acme.getRole()).isEqualTo(PaycheckStreamRole.PRIMARY);
        assertThat(streamWithToken("GLOBEX").getRole()).isEqualTo(PaycheckStreamRole.SECONDARY);
        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    @Test
    void refusedConsentWritesNothing() {
        acmeCatalogRow();
        acmeHistory("acc-1");
        user.setPlaidTransactionsConsented(false);
        InvestmentSchedule schedule = withSchedule(manualSchedule(InvestmentSchedule.Status.ACTIVE, null));
        schedule.setDetectionState(DetectionState.NEEDS_CONSENT);

        EvaluationOutcome outcome = service.evaluate(USER_ID, EvaluationTrigger.RECONCILE, TODAY);

        assertThat(outcome.state()).isEqualTo(DetectionState.NEEDS_CONSENT);
        assertThat(outcome.excluded()).isEmpty();
        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.NEEDS_CONSENT);
        verify(scheduleRepository, never()).save(any());
        verifyNoInteractions(streamRepository, eventRepository, analyticsOutboxService);
    }

    @Test
    void evaluateRunsUnderUserRowLock() throws Exception {
        acmeCatalogRow();
        acmeHistory("acc-1");

        service.evaluate(USER_ID, EvaluationTrigger.FULL, TODAY);

        InOrder order = inOrder(userRepository, streamRepository, eventRepository);
        order.verify(userRepository).findByIdForUpdate(USER_ID);
        order.verify(streamRepository).findByUserIdOrderByIdAsc(USER_ID);
        order.verify(eventRepository).findByUserIdAndStatusAndPendingFalseOrderByPostedDateAsc(USER_ID, PaycheckEventStatus.ACTIVE);
        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).findByIdForUpdateNoWait(any());

        Method evaluate = PaycheckDetectionService.class
                .getMethod("evaluate", Long.class, EvaluationTrigger.class, LocalDate.class);
        Transactional tx = evaluate.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.readOnly()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------

    private void assertSkipped(Consumer<User> change, ExclusionReason expected) {
        User excluded = activeUser();
        change.accept(excluded);
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(excluded));

        EvaluationOutcome outcome = service.evaluate(USER_ID, EvaluationTrigger.RECONCILE, TODAY);

        assertThat(outcome.excluded()).contains(expected);
        assertThat(outcome.state()).isNull();
        assertThat(outcome.primaryStreamId()).isNull();
    }

    private User activeUser() {
        User u = new User();
        u.setId(USER_ID);
        u.setAccountStatus("ACTIVE");
        u.setPlaidAccessToken("ENC:token-under-test");
        u.setPlaidItemId("item-1");
        u.setPlaidAccountId("acc-1");
        u.setPlaidItemStatus(PlaidItemStatus.OK);
        u.setPlaidHistoryComplete(true);
        u.setPaycheckAutopilotOptOut(false);
        u.setMonthlyInvestment(650.0);
        return u;
    }

    private InvestmentSchedule manualSchedule(InvestmentSchedule.Status status, HoldReason hold) {
        InvestmentSchedule s = new InvestmentSchedule();
        s.setId(7L);
        s.setUser(user);
        s.setStatus(status);
        s.setMode(ScheduleMode.MANUAL);
        s.setHoldReason(hold);
        s.setFrequency("BIWEEKLY");
        s.setInvestmentAmount(new BigDecimal("250.00"));
        s.setMonthlyAmount(new BigDecimal("542.50"));
        s.setStartDate(LocalDate.of(2026, 9, 1));
        s.setNextInvestmentDate(LocalDate.of(2026, 11, 6));
        s.setAchRequestId("rel-1");
        return s;
    }

    private InvestmentSchedule withSchedule(InvestmentSchedule schedule) {
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(schedule));
        return schedule;
    }

    private PaycheckStream storedStream(String token, PaycheckStreamState state, PaycheckStreamRole role) {
        PaycheckStream s = new PaycheckStream();
        s.setId(nextStreamId.getAndIncrement());
        s.setUserId(USER_ID);
        s.setPlaidItemId("item-1");
        s.setPlaidAccountId("acc-1");
        s.setPayerToken(token);
        s.setPayerDisplayName(token);
        s.setState(state);
        s.setRole(role);
        s.setCreatedAt(LocalDateTime.of(2026, 8, 1, 9, 0));
        storedStreams.add(s);
        return s;
    }

    /** The row T10 writes from /transactions/recurring/get for ACME before the first FULL evaluation. */
    private PaycheckStream acmeCatalogRow() {
        PaycheckStream s = storedStream("ACME CORP", PaycheckStreamState.CANDIDATE, PaycheckStreamRole.NONE);
        s.setPlaidStreamId("plaid-stream-acme");
        s.setPlaidStatus("MATURE");
        s.setPlaidFrequency("BIWEEKLY");
        s.setPlaidPfcDetailed("INCOME_SALARY");
        s.setPlaidPfcConfidence("VERY_HIGH");
        s.setPlaidIsActive(true);
        return s;
    }

    private void acmeHistory(String accountId) {
        for (int i = 0; i < ACME_FRIDAYS.size(); i++) {
            deposit("acme-" + i, accountId, "ACME CORP PAYROLL", "Acme Corp", "2418.37", ACME_FRIDAYS.get(i));
        }
    }

    /** A new employer at the same bank: two on-cadence Fridays, no Plaid stream yet. */
    private void globexHistory() {
        deposit("globex-1", "acc-1", "GLOBEX PAYROLL", "Globex", "1900.12", LocalDate.of(2026, 10, 9));
        deposit("globex-2", "acc-1", "GLOBEX PAYROLL", "Globex", "1900.12", LocalDate.of(2026, 10, 23));
    }

    private PaycheckEvent deposit(String txId, String accountId, String descriptor, String counterparty,
                                  String amount, LocalDate posted) {
        PaycheckEvent e = new PaycheckEvent();
        e.setUserId(USER_ID);
        e.setPlaidTransactionId(txId);
        e.setPlaidAccountId(accountId);
        e.setAmount(new BigDecimal(amount));
        e.setRawAmount(new BigDecimal(amount).negate());
        e.setPostedDate(posted);
        e.setPending(false);
        e.setStatus(PaycheckEventStatus.ACTIVE);
        e.setFirstSeenAt(posted.atTime(9, 0));
        e.setRawDescriptor(descriptor);
        e.setCounterpartyName(counterparty);
        e.setCounterpartyType("income_source");
        e.setPfcPrimary("INCOME");
        e.setPfcDetailed("INCOME_SALARY");
        e.setPfcConfidence("VERY_HIGH");
        e.setPaymentChannel("other");
        storedEvents.add(e);
        return e;
    }

    private PaycheckStream streamWithToken(String token) {
        return storedStreams.stream().filter(s -> token.equals(s.getPayerToken())).findFirst().orElseThrow();
    }
}
