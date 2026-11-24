package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PaycheckCycle;
import com.investingapp.backend.model.PaycheckCycleState;
import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckEventStatus;
import com.investingapp.backend.model.PaycheckSkipReason;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamRole;
import com.investingapp.backend.model.PaycheckStreamState;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.paycheck.EvaluationOutcome;
import com.investingapp.backend.paycheck.EvaluationTrigger;
import com.investingapp.backend.paycheck.cycle.CycleGateChain;
import com.investingapp.backend.paycheck.cycle.KillSwitchGate;
import com.investingapp.backend.paycheck.cycle.ShadowGate;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckCycleRepository;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.PlaidItemStateService.ItemStateResult;
import com.investingapp.backend.service.PlaidItemStateService.RepairPath;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Final review DC-1 and DC-4, end to end through the REAL detection, cycle and Item-state services
 * over in-memory repositories (no evaluateWithoutCycles stub): a skipped biweekly paycheck and a
 * login break must both leave the primary in place once the payer's next deposit arrives.
 */
class PaycheckResumeWiringTest {

    private static final Long UID = 42L;
    private static final String ITEM = "item-1";
    private static final String ACCOUNT = "acc-1";
    /** Five biweekly Fridays; L = 2026-10-09. */
    private static final List<LocalDate> FRIDAYS = List.of(LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 28),
            LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 9));

    private final List<PaycheckStream> streams = new ArrayList<>();
    private final List<PaycheckEvent> events = new ArrayList<>();
    private final List<PaycheckCycle> cycles = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);

    private User user;
    private InvestmentSchedule schedule;
    private PaycheckDetectionService detection;
    private PaycheckCycleService cycleService;
    private PlaidItemStateService itemState;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        user = new User("resume@example.invalid");
        user.setId(UID);
        user.setAccountStatus("ACTIVE");
        user.setPlaidItemId(ITEM);
        user.setPlaidAccessToken("ENC:token");
        user.setPlaidAccountId(ACCOUNT);
        user.setPlaidItemStatus(PlaidItemStatus.OK);
        user.setPlaidHistoryComplete(true);
        user.setPaycheckAutopilotOptOut(false);
        user.setPlaidSyncCursor("cursor-1");
        schedule = new InvestmentSchedule();
        schedule.setId(9L);
        schedule.setUser(user);
        schedule.setMode(ScheduleMode.MANUAL);
        schedule.setStatus(InvestmentSchedule.Status.ACTIVE);
        schedule.setInvestmentAmount(new BigDecimal("250.00"));
        schedule.setFrequency("BIWEEKLY");

        UserRepository users = mock(UserRepository.class);
        when(users.findByIdForUpdate(UID)).thenAnswer(i -> Optional.of(user));
        when(users.findById(UID)).thenAnswer(i -> Optional.of(user));
        InvestmentScheduleRepository schedules = mock(InvestmentScheduleRepository.class);
        when(schedules.findTopByUserOrderByCreatedAtDesc(any())).thenAnswer(i -> Optional.of(schedule));

        PaycheckStreamRepository streamRepo = mock(PaycheckStreamRepository.class);
        when(streamRepo.findByUserIdOrderByIdAsc(UID)).thenAnswer(i -> new ArrayList<>(streams));
        when(streamRepo.save(any(PaycheckStream.class))).thenAnswer(i -> store(streams, i.getArgument(0)));
        when(streamRepo.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(streamRepo.findById(anyLong())).thenAnswer(i -> byId(streams, i.getArgument(0)));
        when(streamRepo.findByUserIdAndState(eq(UID), any())).thenAnswer(i ->
                streams.stream().filter(s -> s.getState() == i.getArgument(1)).toList());

        PaycheckEventRepository eventRepo = mock(PaycheckEventRepository.class);
        when(eventRepo.findByUserIdAndStatusAndPendingFalseOrderByPostedDateAsc(UID, PaycheckEventStatus.ACTIVE))
                .thenAnswer(i -> events.stream().filter(e -> e.getStatus() == PaycheckEventStatus.ACTIVE && !e.isPending())
                        .sorted(Comparator.comparing(PaycheckEvent::getPostedDate)).toList());
        when(eventRepo.findById(anyLong())).thenAnswer(i -> byId(events, i.getArgument(0)));
        when(eventRepo.findAllById(any())).thenAnswer(i -> {
            List<PaycheckEvent> found = new ArrayList<>();
            ((Iterable<Long>) i.getArgument(0)).forEach(id -> byId(events, id).ifPresent(found::add));
            return found;
        });
        when(eventRepo.findByStreamIdAndStatusOrderByPostedDateDesc(anyLong(), any())).thenAnswer(i -> events.stream()
                .filter(e -> Objects.equals(e.getStreamId(), i.getArgument(0)) && e.getStatus() == i.getArgument(1))
                .toList());
        when(eventRepo.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(eventRepo.deleteByUserId(UID)).thenAnswer(i -> {
            long n = events.size();
            events.clear();
            return n;
        });

        PaycheckCycleRepository cycleRepo = mock(PaycheckCycleRepository.class);
        when(cycleRepo.findByUserIdAndState(eq(UID), any())).thenAnswer(i ->
                cycles.stream().filter(c -> c.getState() == i.getArgument(1)).toList());
        when(cycleRepo.save(any(PaycheckCycle.class))).thenAnswer(i -> store(cycles, i.getArgument(0)));
        when(cycleRepo.findByStreamIdAndPeriodKey(anyLong(), any())).thenAnswer(i -> cycles.stream()
                .filter(c -> Objects.equals(c.getStreamId(), i.getArgument(0)) && Objects.equals(c.getPeriodKey(), i.getArgument(1)))
                .findFirst());

        PaycheckAutopilotProperties props = mock(PaycheckAutopilotProperties.class);
        when(props.isEnabled()).thenReturn(true);
        when(props.isShadow()).thenReturn(true);
        when(props.isPullsEnabled()).thenReturn(false);
        when(props.getScoreProvisional()).thenReturn(55);
        when(props.getScoreConfirmed()).thenReturn(70);
        when(props.getAffordabilityShare()).thenReturn(new BigDecimal("0.90"));

        cycleService = new PaycheckCycleService(cycleRepo, streamRepo, eventRepo, schedules, users,
                new CycleGateChain(List.of(new ShadowGate(), new KillSwitchGate())), props);
        detection = new PaycheckDetectionService(users, schedules, streamRepo, eventRepo,
                mock(AnalyticsOutboxService.class), props, cycleService);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        itemState = new PlaidItemStateService(users, schedules, streamRepo, eventRepo, cycleRepo,
                mock(PlaidItemLifecycleService.class), mock(NotificationService.class), mock(OpsAlertService.class),
                new UserRowLock(users, mock(EntityManager.class)), tx);

        // The row the recurring read seeds for ACME before the first FULL evaluation.
        PaycheckStream catalog = new PaycheckStream();
        catalog.setId(ids.getAndIncrement());
        catalog.setUserId(UID);
        catalog.setPlaidItemId(ITEM);
        catalog.setPlaidAccountId(ACCOUNT);
        catalog.setPayerToken("ACME CORP");
        catalog.setPayerDisplayName("ACME CORP");
        catalog.setState(PaycheckStreamState.CANDIDATE);
        catalog.setRole(PaycheckStreamRole.NONE);
        catalog.setPlaidStreamId("plaid-stream-acme");
        catalog.setPlaidStatus("MATURE");
        catalog.setPlaidFrequency("BIWEEKLY");
        catalog.setPlaidPfcDetailed("INCOME_SALARY");
        catalog.setPlaidPfcConfidence("VERY_HIGH");
        catalog.setPlaidIsActive(true);
        streams.add(catalog);
    }

    private <T> T store(List<T> rows, T row) {
        Long id = row instanceof PaycheckStream s ? s.getId() : ((PaycheckCycle) row).getId();
        if (id == null) {
            if (row instanceof PaycheckStream s) {
                s.setId(ids.getAndIncrement());
            } else {
                ((PaycheckCycle) row).setId(ids.getAndIncrement());
            }
            rows.add(row);
        }
        return row;
    }

    private static <T> Optional<T> byId(List<T> rows, Long id) {
        return rows.stream().filter(r -> id.equals(r instanceof PaycheckStream s ? s.getId()
                : r instanceof PaycheckEvent e ? e.getId() : ((PaycheckCycle) r).getId())).findFirst();
    }

    private PaycheckEvent deposit(LocalDate posted) {
        PaycheckEvent e = new PaycheckEvent();
        e.setId(ids.getAndIncrement());
        e.setUserId(UID);
        e.setPlaidTransactionId("tx-" + posted);
        e.setPlaidAccountId(ACCOUNT);
        e.setAmount(new BigDecimal("2418.37"));
        e.setRawAmount(new BigDecimal("-2418.37"));
        e.setPostedDate(posted);
        e.setPending(false);
        e.setStatus(PaycheckEventStatus.ACTIVE);
        e.setFirstSeenAt(posted.atTime(9, 0));
        e.setRawDescriptor("ACME CORP PAYROLL");
        e.setCounterpartyName("Acme Corp");
        e.setCounterpartyType("income_source");
        e.setPfcPrimary("INCOME");
        e.setPfcDetailed("INCOME_SALARY");
        e.setPfcConfidence("VERY_HIGH");
        e.setPaymentChannel("other");
        events.add(e);
        return e;
    }

    private PaycheckStream primaryAfterFiveFridays() {
        FRIDAYS.forEach(this::deposit);
        EvaluationOutcome first = detection.evaluate(UID, EvaluationTrigger.FULL, LocalDate.of(2026, 10, 10));
        assertThat(first.primaryStreamId()).as("sanity check: five Fridays make a primary").isNotNull();
        assertThat(openCycles()).as("sanity check: its cycle is open").hasSize(1);
        return byId(streams, first.primaryStreamId()).orElseThrow();
    }

    private List<PaycheckCycle> openCycles() {
        return cycles.stream().filter(c -> c.getState() == PaycheckCycleState.OPEN).toList();
    }

    @Test
    void oneSkippedPaycheckThenAnOnCadenceDepositKeepsThePrimary() {
        PaycheckStream primary = primaryAfterFiveFridays();
        assertThat(openCycles().get(0).getExpectedDate()).isEqualTo(LocalDate.of(2026, 10, 23));

        // The 10-23 paycheck never comes; the reconcile marks it missed.
        assertThat(cycleService.markMissesForUser(UID, LocalDate.of(2026, 11, 11))).isEqualTo(1);
        assertThat(primary.getState()).isEqualTo(PaycheckStreamState.DORMANT);
        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.DORMANT);

        // The payer resumes on cadence at L+28; the sync after the reconcile brings it in.
        PaycheckEvent resumed = deposit(LocalDate.of(2026, 11, 6));
        resumed.setFirstSeenAt(LocalDate.of(2026, 11, 12).atTime(9, 0));
        EvaluationOutcome outcome = detection.evaluateAndApply(UID, List.of(resumed.getId()), LocalDate.of(2026, 11, 12));

        assertThat(primary.getRole()).isEqualTo(PaycheckStreamRole.PRIMARY);
        assertThat(primary.getState()).isEqualTo(PaycheckStreamState.PROVISIONAL);
        assertThat(primary.getMissedCount()).isZero();
        assertThat(outcome.primaryStreamId()).isEqualTo(primary.getId());
        assertThat(outcome.state()).isEqualTo(DetectionState.PROVISIONAL);
        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.PROVISIONAL);
        assertThat(schedule.getMissedCount()).isZero();
        assertThat(cycles).anySatisfy(c -> {
            assertThat(c.getEventId()).isEqualTo(resumed.getId());
            assertThat(c.getState()).isEqualTo(PaycheckCycleState.SKIPPED);
            assertThat(c.getSkipReason()).isEqualTo(PaycheckSkipReason.SHADOW_CONFIRMED);
        });
    }

    @Test
    void loginRepairKeepsTheConfirmedPrimaryOnItsFirstNewDeposit() {
        FRIDAYS.forEach(this::deposit);
        deposit(LocalDate.of(2026, 10, 23));
        detection.evaluate(UID, EvaluationTrigger.FULL, LocalDate.of(2026, 10, 24));
        PaycheckStream primary = byId(streams, schedule.getPrimaryStreamId()).orElseThrow();
        assertThat(primary.getState()).as("sanity check: six Fridays confirm").isEqualTo(PaycheckStreamState.CONFIRMED);
        PaycheckCycle open = openCycles().get(0);
        assertThat(open.getExpectedDate()).isEqualTo(LocalDate.of(2026, 11, 6));

        assertThat(itemState.onLoginRequired(UID, ITEM, "ITEM_LOGIN_REQUIRED")).isEqualTo(ItemStateResult.APPLIED);
        assertThat(events).as("sanity check: the break purged the events").isEmpty();
        assertThat(itemState.onLoginRepaired(UID, ITEM, RepairPath.LOGIN_REPAIRED)).isEqualTo(ItemStateResult.APPLIED);
        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.CONFIRMED);

        PaycheckEvent next = deposit(LocalDate.of(2026, 11, 6));
        EvaluationOutcome outcome = detection.evaluateAndApply(UID, List.of(next.getId()), LocalDate.of(2026, 11, 6));

        assertThat(primary.getState()).isEqualTo(PaycheckStreamState.CONFIRMED);
        assertThat(primary.getRole()).isEqualTo(PaycheckStreamRole.PRIMARY);
        assertThat(outcome.state()).isEqualTo(DetectionState.CONFIRMED);
        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.CONFIRMED);
        assertThat(schedule.getPrimaryStreamId()).isEqualTo(primary.getId());
        assertThat(open.getState()).isEqualTo(PaycheckCycleState.SKIPPED);
        assertThat(open.getSkipReason()).isEqualTo(PaycheckSkipReason.SHADOW_CONFIRMED);
        assertThat(open.getEventId()).isEqualTo(next.getId());
    }
}
