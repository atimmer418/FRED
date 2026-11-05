package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PaycheckCycle;
import com.investingapp.backend.model.PaycheckCycleState;
import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckEventStatus;
import com.investingapp.backend.model.PaycheckRetiredReason;
import com.investingapp.backend.model.PaycheckSkipReason;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamRole;
import com.investingapp.backend.model.PaycheckStreamState;
import com.investingapp.backend.model.User;
import com.investingapp.backend.paycheck.cycle.CycleContext;
import com.investingapp.backend.paycheck.cycle.CycleGateChain;
import com.investingapp.backend.paycheck.cycle.CycleMatcher;
import com.investingapp.backend.paycheck.cycle.MatchDecision;
import com.investingapp.backend.paycheck.cycle.MissDetector;
import com.investingapp.backend.paycheck.cycle.WouldHavePulled;
import com.investingapp.backend.paycheck.detect.CadenceResult;
import com.investingapp.backend.paycheck.detect.PayCalendar;
import com.investingapp.backend.paycheck.detect.PayerTokenizer;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckCycleRepository;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.util.EasternTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Spec 4.6, 4.7, 6.4 cycles and reconcile passes. MANDATORY writers; never status, mode, hold, notice or analytics. */
@Service
public class PaycheckCycleService {

    private static final Logger logger = LoggerFactory.getLogger(PaycheckCycleService.class);

    static final int OPEN_ATTEMPTS = 3;        // periods to step past one already taken
    static final int MAX_MISSES_PER_RUN = 60;  // per user per run, so an outage cannot loop

    private static final Set<PaycheckCycleState> CLOSED_WITH_DEPOSIT =
            EnumSet.of(PaycheckCycleState.SKIPPED, PaycheckCycleState.CONFIRMED, PaycheckCycleState.QUEUED);
    private static final Set<PaycheckStreamState> TRUSTED =
            EnumSet.of(PaycheckStreamState.PROVISIONAL, PaycheckStreamState.CONFIRMED, PaycheckStreamState.DORMANT);
    /** Mirror states that describe the Item, not the payer; a miss never writes DORMANT over them. */
    private static final Set<DetectionState> ITEM_STATE_MIRRORS =
            EnumSet.of(DetectionState.ITEM_BROKEN, DetectionState.NEEDS_CONSENT, DetectionState.UNDETECTABLE);

    private final PaycheckCycleRepository cycleRepository;
    private final PaycheckStreamRepository streamRepository;
    private final PaycheckEventRepository eventRepository;
    private final InvestmentScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final CycleGateChain gateChain;
    private final PaycheckAutopilotProperties props;

    public PaycheckCycleService(PaycheckCycleRepository cycleRepository, PaycheckStreamRepository streamRepository,
                PaycheckEventRepository eventRepository, InvestmentScheduleRepository scheduleRepository,
                UserRepository userRepository, CycleGateChain gateChain, PaycheckAutopilotProperties props) {
        this.cycleRepository = cycleRepository;
        this.streamRepository = streamRepository;
        this.eventRepository = eventRepository;
        this.scheduleRepository = scheduleRepository;
        this.userRepository = userRepository;
        this.gateChain = gateChain;
        this.props = props;
    }

    public static CadenceResult cadenceOf(PaycheckStream stream) {
        return new CadenceResult(stream.getCadence(), stream.getCadenceSource(),
                stream.getAnchorDow() == null ? null : stream.getAnchorDow().intValue(),
                stream.getAnchorParityDate(), stream.getAnchorDay1(), stream.getAnchorDay2(), 0, true, 0);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PaycheckCycle> openCycleForPrimary(Long userId, Long primaryStreamId) {
        Optional<PaycheckStream> primary = streamRepository.findById(primaryStreamId)
                .filter(s -> userId.equals(s.getUserId()) && TRUSTED.contains(s.getState())
                        && s.getCadence() != null && s.getLastDate() != null);
        if (primary.isEmpty()) {
            return Optional.empty();
        }
        CadenceResult cadence = cadenceOf(primary.get());
        return openCycleIfNone(primary.get(), scheduleOf(userId), cadence,
                PayCalendar.predictedNext(cadence, primary.get().getLastDate()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PaycheckCycle> openCycleIfNone(PaycheckStream primary, Optional<InvestmentSchedule> schedule,
                CadenceResult cadence, LocalDate expected) {
        Optional<PaycheckCycle> open = openCycleOf(primary);
        if (open.isEmpty() && cadence.cadence() != null && expected != null) {
            open = createFirstFreePeriod(primary, schedule, cadence, expected);
        }
        open.ifPresent(cycle -> {
            primary.setPredictedNextDate(cycle.getExpectedDate());
            schedule.filter(s -> mirrors(s, primary)).ifPresent(s -> s.setNextExpectedDate(cycle.getExpectedDate()));
        });
        return open;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<MatchDecision> applyNewDeposits(Long userId, Long primaryStreamId, List<Long> eventIds, LocalDateTime nowET) {
        User user = userRepository.findById(userId).orElse(null);
        PaycheckStream primary = streamRepository.findById(primaryStreamId).orElse(null);
        if (user == null || primary == null || !userId.equals(primary.getUserId())) {
            return List.of();
        }
        return applyNewDeposits(user, scheduleRepository.findTopByUserOrderByCreatedAtDesc(user), primary, eventIds, nowET);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<MatchDecision> applyNewDeposits(User user, Optional<InvestmentSchedule> schedule, PaycheckStream primary,
                List<Long> eventIds, LocalDateTime nowET) {
        List<PaycheckEvent> events = new ArrayList<>();
        for (Long id : eventIds) {
            eventRepository.findById(id).ifPresent(events::add);
        }
        events.sort(Comparator.comparing(PaycheckEvent::getPostedDate).thenComparing(PaycheckEvent::getId));
        Set<String> ppdIds = knownPpdIds(primary);
        CadenceResult cadence = cadenceOf(primary);
        List<MatchDecision> decisions = new ArrayList<>();
        for (PaycheckEvent event : events) {
            // isPostedInflow re-reads the status: an event added and removed in one batch is no longer ACTIVE.
            if (!isPostedInflow(event) || !belongsTo(event, primary, ppdIds)) {
                continue;
            }
            List<PaycheckCycle> closed = closedCyclesOf(primary);
            if (closed.stream().anyMatch(c -> event.getId().equals(c.getEventId()))) {
                continue; // a replayed deposit already sits on a cycle
            }
            event.setStreamId(primary.getId());
            Optional<PaycheckCycle> open = openCycleOf(primary);
            MatchDecision decision = CycleMatcher.match(event, primary, open, lastConfirmed(closed), event.getFirstSeenAt());
            if (decision == MatchDecision.CONFIRMABLE) {
                decision = skipThroughGates(user, schedule, primary, cadence, open, event, nowET);
            } else if (decision == MatchDecision.LATE_SEEN) {
                open.filter(c -> CycleMatcher.inWindow(c, event.getPostedDate())).ifPresent(c -> {
                    close(c, event, PaycheckSkipReason.LATE_SEEN, null, null, nowET);
                    afterDeposit(schedule, primary, cadence, event, nowET);
                });
            }
            logger.debug("paycheck.cycle.match user={} event={} decision={}", user.getId(), event.getId(), decision);
            decisions.add(decision);
        }
        return decisions;
    }

    @Transactional(readOnly = true)
    public List<Long> usersWithOverdueOpenCycles(LocalDate todayET) {
        return distinctUsers(cycleRepository.findByStateAndWindowEndBefore(PaycheckCycleState.OPEN,
                todayET.minusDays(MissDetector.MISS_GRACE_DAYS)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int markMissesForUser(Long userId, LocalDate todayET) {
        Optional<InvestmentSchedule> schedule = scheduleOf(userId);
        Deque<PaycheckCycle> due = cycleRepository.findByUserIdAndState(userId, PaycheckCycleState.OPEN).stream()
                .filter(c -> MissDetector.missed(c, todayET))
                .sorted(Comparator.comparing(PaycheckCycle::getExpectedDate))
                .collect(Collectors.toCollection(ArrayDeque::new));
        LocalDateTime nowET = EasternTime.now();
        int marked = 0;
        while (!due.isEmpty() && marked < MAX_MISSES_PER_RUN) {
            PaycheckCycle cycle = due.poll();
            cycle.setState(PaycheckCycleState.MISSED);
            cycle.setUpdatedAt(nowET);
            cycleRepository.save(cycle);
            marked++;
            PaycheckStream stream = streamRepository.findById(cycle.getStreamId()).orElse(null);
            if (stream == null || !TRUSTED.contains(stream.getState())) {
                continue; // a RETIRED stream's leftover cycle closes without effect
            }
            // Dormancy means the payer went quiet: a deposit dated after this period (spec 4.7 rule 5
            // can confirm a later period) leaves the stream as it is; only this period closes as missed.
            boolean quietSince = stream.getLastDate() == null || !stream.getLastDate().isAfter(cycle.getExpectedDate());
            if (quietSince) {
                stream.setMissedCount((short) Math.min(stream.getMissedCount() + 1, Short.MAX_VALUE));
                stream.setState(PaycheckStreamState.DORMANT);
                stream.setUpdatedAt(nowET);
                streamRepository.save(stream);
                // An Item-state mirror (PlaidItemStateService owns it) is not a missed paycheck (DC-3).
                schedule.filter(s -> mirrors(s, stream) && !ITEM_STATE_MIRRORS.contains(s.getDetectionState())).ifPresent(s -> {
                    s.setDetectionState(DetectionState.DORMANT);
                    s.setMissedCount(stream.getMissedCount());
                    if (s.getDormantSince() == null) {
                        s.setDormantSince(cycle.getWindowEnd());
                    }
                });
            }
            if (stream.getRole() == PaycheckStreamRole.PRIMARY) {
                CadenceResult cadence = cadenceOf(stream);
                openCycleIfNone(stream, schedule, cadence, PayCalendar.predictedNext(cadence, cycle.getExpectedDate()))
                        .filter(next -> !due.contains(next) && MissDetector.missed(next, todayET))
                        .ifPresent(due::add);
            }
        }
        if (marked > 0) {
            logger.info("paycheck.reconcile.misses marked={}", marked);
        }
        return marked;
    }

    @Transactional(readOnly = true)
    public List<Long> usersWithStaleConfirmed(LocalDateTime nowET) {
        return distinctUsers(cycleRepository.findByStateAndConfirmedAtBefore(PaycheckCycleState.CONFIRMED,
                nowET.minusDays(MissDetector.STALE_CONFIRMED_DAYS)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int markStaleForUser(Long userId, LocalDateTime nowET) {
        int stale = 0;
        for (PaycheckCycle cycle : cycleRepository.findByUserIdAndState(userId, PaycheckCycleState.CONFIRMED)) {
            if (MissDetector.stale(cycle, nowET)) {
                cycle.setState(PaycheckCycleState.SKIPPED);
                cycle.setSkipReason(PaycheckSkipReason.STALE);
                cycle.setUpdatedAt(nowET);
                cycleRepository.save(cycle);
                stale++;
            }
        }
        return stale;
    }

    @Transactional(readOnly = true)
    public List<Long> usersWithDormantStreamsOver(LocalDate todayET) {
        return distinctUsers(cycleRepository.findByStateAndWindowEndBefore(PaycheckCycleState.MISSED,
                todayET.minusDays(MissDetector.DORMANT_RETIRE_DAYS - 1)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int retireDormantForUser(Long userId, LocalDate todayET) {
        List<PaycheckCycle> missed = cycleRepository.findByUserIdAndState(userId, PaycheckCycleState.MISSED);
        LocalDateTime nowET = EasternTime.now();
        int retired = 0;
        for (PaycheckStream stream : streamRepository.findByUserIdAndState(userId, PaycheckStreamState.DORMANT)) {
            Optional<LocalDate> since = MissDetector.dormantSince(stream, missed);
            if (since.isPresent() && MissDetector.retireDue(since.get(), todayET)) {
                stream.setState(PaycheckStreamState.RETIRED);
                stream.setRetiredReason(PaycheckRetiredReason.DORMANT_120);
                stream.setRetiredAt(nowET);
                stream.setUpdatedAt(nowET);
                streamRepository.save(stream);
                retired++;
            }
        }
        if (retired > 0) {
            logger.info("paycheck.reconcile.retired count={}", retired);
        }
        return retired;
    }

    private static List<Long> distinctUsers(List<PaycheckCycle> cycles) {
        return cycles.stream().map(PaycheckCycle::getUserId).filter(Objects::nonNull).distinct().sorted().toList();
    }

    private MatchDecision skipThroughGates(User user, Optional<InvestmentSchedule> schedule, PaycheckStream primary,
                CadenceResult cadence, Optional<PaycheckCycle> open, PaycheckEvent event,
                LocalDateTime nowET) {
        LocalDate posted = event.getPostedDate();
        PaycheckCycle target = open.filter(c -> CycleMatcher.inWindow(c, posted)).orElse(null);
        if (target == null) { // rule 5: outside the window with the gap holding, the deposit opens its own period
            String key = PayCalendar.periodKey(cadence, posted);
            if (open.isPresent() && key.equals(open.get().getPeriodKey())) {
                target = open.get();
            } else if (cycleRepository.findByStreamIdAndPeriodKey(primary.getId(), key).isPresent()) {
                return MatchDecision.PERIOD_TAKEN;
            } else {
                target = newCycle(primary, schedule, cadence, posted, key, nowET);
            }
        }
        BigDecimal wouldPull = WouldHavePulled.amount(schedule, user, primary, props.getAffordabilityShare());
        PaycheckSkipReason reason = gateChain.firstSkip(new CycleContext(user, schedule, primary, event, wouldPull, props))
                .orElseThrow(() -> new IllegalStateException(
                        "Phase 1 has no CONFIRMED writer: a cycle gate must skip (shadow or kill switch)"));
        close(target, event, reason, wouldPull, nowET, nowET);
        afterDeposit(schedule, primary, cadence, event, nowET);
        return MatchDecision.CONFIRMABLE;
    }

    private void close(PaycheckCycle cycle, PaycheckEvent event, PaycheckSkipReason reason, BigDecimal amountToPull,
                LocalDateTime confirmedAt, LocalDateTime nowET) {
        cycle.setState(PaycheckCycleState.SKIPPED);
        cycle.setSkipReason(reason);
        cycle.setEventId(event.getId());
        cycle.setDepositAmount(event.getAmount());
        cycle.setConfirmedAt(confirmedAt);
        cycle.setAmountToPull(amountToPull);
        cycle.setUpdatedAt(nowET);
        cycleRepository.save(cycle);
        logger.info("paycheck.cycle.skipped reason={}", reason);
        logger.debug("paycheck.cycle.skipped user={} cycle={} stream={}", cycle.getUserId(), cycle.getId(), cycle.getStreamId());
    }

    /** deposits_found, median and CV stay with PaycheckDetectionService, which recomputes them from events. */
    private void afterDeposit(Optional<InvestmentSchedule> schedule, PaycheckStream primary, CadenceResult cadence,
                PaycheckEvent event, LocalDateTime nowET) {
        LocalDate posted = event.getPostedDate();
        if (primary.getLastDate() == null || !posted.isBefore(primary.getLastDate())) {
            primary.setLastDate(posted);
            primary.setLastAmount(event.getAmount());
        }
        primary.setMissedCount((short) 0);
        if (primary.getState() == PaycheckStreamState.DORMANT) {
            primary.setState(PaycheckStreamState.PROVISIONAL);
        }
        primary.setUpdatedAt(nowET);
        streamRepository.save(primary);
        schedule.filter(s -> mirrors(s, primary)).ifPresent(s -> {
            s.setMissedCount((short) 0);
            s.setDormantSince(null);
            if (s.getDetectionState() == DetectionState.DORMANT) {
                s.setDetectionState(DetectionState.PROVISIONAL);
            }
        });
        openCycleIfNone(primary, schedule, cadence, PayCalendar.predictedNext(cadence, posted));
    }

    private Optional<PaycheckCycle> createFirstFreePeriod(PaycheckStream primary, Optional<InvestmentSchedule> schedule,
                CadenceResult cadence, LocalDate expected) {
        LocalDate candidate = expected;
        for (int attempt = 0; attempt < OPEN_ATTEMPTS; attempt++) {
            String key = PayCalendar.periodKey(cadence, candidate);
            if (cycleRepository.findByStreamIdAndPeriodKey(primary.getId(), key).isEmpty()) {
                PaycheckCycle cycle = newCycle(primary, schedule, cadence, candidate, key, EasternTime.now());
                cycleRepository.save(cycle);
                return Optional.of(cycle);
            }
            candidate = PayCalendar.predictedNext(cadence, candidate);
        }
        logger.warn("paycheck.cycle.no_free_period attempts={}", OPEN_ATTEMPTS);
        return Optional.empty();
    }

    private static PaycheckCycle newCycle(PaycheckStream primary, Optional<InvestmentSchedule> schedule,
                CadenceResult cadence, LocalDate expected, String periodKey, LocalDateTime createdAt) {
        var window = PayCalendar.window(cadence.cadence(), expected);
        PaycheckCycle cycle = new PaycheckCycle();
        cycle.setUserId(primary.getUserId());
        cycle.setScheduleId(schedule.map(InvestmentSchedule::getId).orElse(null));
        cycle.setStreamId(primary.getId());
        cycle.setPeriodKey(periodKey);
        cycle.setState(PaycheckCycleState.OPEN);
        cycle.setExpectedDate(expected);
        cycle.setWindowStart(window.start());
        cycle.setWindowEnd(window.end());
        cycle.setCreatedAt(createdAt);
        return cycle;
    }

    private Optional<PaycheckCycle> openCycleOf(PaycheckStream stream) {
        return cycleRepository.findByUserIdAndState(stream.getUserId(), PaycheckCycleState.OPEN).stream()
                .filter(c -> c.getState() == PaycheckCycleState.OPEN && stream.getId().equals(c.getStreamId()))
                .min(Comparator.comparing(PaycheckCycle::getExpectedDate));
    }

    private List<PaycheckCycle> closedCyclesOf(PaycheckStream stream) {
        List<PaycheckCycle> closed = new ArrayList<>();
        for (PaycheckCycleState state : CLOSED_WITH_DEPOSIT) {
            cycleRepository.findByUserIdAndState(stream.getUserId(), state).stream()
                    .filter(c -> stream.getId().equals(c.getStreamId()) && c.getEventId() != null)
                    .forEach(closed::add);
        }
        return closed;
    }

    private Optional<LocalDate> lastConfirmed(List<PaycheckCycle> closed) {
        List<Long> eventIds = closed.stream().map(PaycheckCycle::getEventId).toList();
        if (eventIds.isEmpty()) {
            return Optional.empty();
        }
        return eventRepository.findAllById(eventIds).stream().map(PaycheckEvent::getPostedDate).max(Comparator.naturalOrder());
    }

    private Set<String> knownPpdIds(PaycheckStream primary) {
        return eventRepository.findByStreamIdAndStatusOrderByPostedDateDesc(primary.getId(), PaycheckEventStatus.ACTIVE)
                .stream().map(PaycheckEvent::getPpdId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static boolean isPostedInflow(PaycheckEvent e) {
        return e.getStatus() == PaycheckEventStatus.ACTIVE && !e.isPending()
                && e.getRawAmount() != null && e.getRawAmount().signum() < 0;
    }

    /** raw_descriptor is original_description else name (T07), so it stands for both token arguments. */
    private static boolean belongsTo(PaycheckEvent e, PaycheckStream primary, Set<String> ppdIds) {
        if (!Objects.equals(e.getPlaidAccountId(), primary.getPlaidAccountId())) {
            return false;
        }
        if (primary.getId().equals(e.getStreamId())) {
            return true;
        }
        if (e.getRawDescriptor() != null && primary.getPayerToken() != null
                && primary.getPayerToken().equals(PayerTokenizer.token(e.getRawDescriptor(), e.getRawDescriptor()))) {
            return true;
        }
        return e.getPpdId() != null && ppdIds.contains(e.getPpdId());
    }

    private Optional<InvestmentSchedule> scheduleOf(Long userId) {
        return userRepository.findById(userId).flatMap(scheduleRepository::findTopByUserOrderByCreatedAtDesc);
    }

    private static boolean mirrors(InvestmentSchedule schedule, PaycheckStream stream) {
        return schedule.getPrimaryStreamId() == null || schedule.getPrimaryStreamId().equals(stream.getId());
    }
}
