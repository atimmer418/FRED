package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckEventStatus;
import com.investingapp.backend.model.PaycheckRejectReason;
import com.investingapp.backend.model.PaycheckRetiredReason;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamRole;
import com.investingapp.backend.model.PaycheckStreamState;
import com.investingapp.backend.model.User;
import com.investingapp.backend.paycheck.EvaluationOutcome;
import com.investingapp.backend.paycheck.EvaluationTrigger;
import com.investingapp.backend.paycheck.detect.AmountPrefill;
import com.investingapp.backend.paycheck.detect.CadenceDeriver;
import com.investingapp.backend.paycheck.detect.CadenceResult;
import com.investingapp.backend.paycheck.detect.DepositGroup;
import com.investingapp.backend.paycheck.detect.DepositGrouper;
import com.investingapp.backend.paycheck.detect.DepositStats;
import com.investingapp.backend.paycheck.detect.HardGates;
import com.investingapp.backend.paycheck.detect.PayCalendar;
import com.investingapp.backend.paycheck.detect.PayerTokenizer;
import com.investingapp.backend.paycheck.detect.PrimarySelector;
import com.investingapp.backend.paycheck.detect.ScoreBreakdown;
import com.investingapp.backend.paycheck.detect.StreamFacts;
import com.investingapp.backend.paycheck.detect.StreamScorer;
import com.investingapp.backend.paycheck.detect.StreamStateRule;
import com.investingapp.backend.paycheck.detect.SuccessionRule;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.DetectionEligibility.ExclusionReason;
import com.investingapp.backend.util.ColumnText;
import com.investingapp.backend.util.EasternTime;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Paycheck detection's one orchestrator (spec 4, rulings R1, R2, R5, R8). Groups a user's posted
 * inflow events, applies the hard gates, scores, upserts paycheck_streams, keeps or selects the
 * primary, runs the basic succession rule and mirrors the outcome onto the schedule row when one
 * exists, in every mode. The rules themselves are the pure functions in paycheck/detect; this class
 * only sequences them and persists what they decide.
 *
 * <p>Money is out of reach here by construction. The schedule's mode, status, hold, amounts, dates
 * and ACH fields are never written (PhaseOneMoneyInvarianceTest reads this file to prove it), no
 * Item state is written, and nothing here calls Plaid, so the transaction holds no outbound HTTP.
 */
@Service
public class PaycheckDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(PaycheckDetectionService.class);

    /** Spec 4.4: cadence, median and CV read the last six posted deposits. */
    static final int TRAILING_DEPOSITS = 6;

    /** V6 widths of the stream columns this class writes from Plaid-derived text. */
    private static final int PAYER_TOKEN_WIDTH = 64;
    private static final int DISPLAY_NAME_WIDTH = 120;
    private static final int DESCRIPTOR_WIDTH = 255;
    private static final int PLAID_ID_WIDTH = 80;

    /** trailing_cv is DECIMAL(6,4). */
    private static final BigDecimal CV_MAX = new BigDecimal("99.9999");

    /** Streams that can become primary in this evaluation. */
    private static final EnumSet<PaycheckStreamState> QUALIFYING =
            EnumSet.of(PaycheckStreamState.PROVISIONAL, PaycheckStreamState.CONFIRMED);

    /** Streams that keep a role they already hold: a missed paycheck does not demote the primary. */
    private static final EnumSet<PaycheckStreamState> KEEPS_ROLE =
            EnumSet.of(PaycheckStreamState.PROVISIONAL, PaycheckStreamState.CONFIRMED, PaycheckStreamState.DORMANT);

    /** Successor candidates, best first: highest score, then the larger trailing median. */
    private static final Comparator<PaycheckStream> SUCCESSOR_ORDER = Comparator
            .comparingInt((PaycheckStream s) -> s.getScore()).reversed()
            .thenComparing(PaycheckStream::getTrailingMedian, Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder()));

    private final UserRepository userRepository;
    private final InvestmentScheduleRepository scheduleRepository;
    private final PaycheckStreamRepository streamRepository;
    private final PaycheckEventRepository eventRepository;
    private final AnalyticsOutboxService analyticsOutboxService;
    private final PaycheckAutopilotProperties props;

    public PaycheckDetectionService(UserRepository userRepository,
                                    InvestmentScheduleRepository scheduleRepository,
                                    PaycheckStreamRepository streamRepository,
                                    PaycheckEventRepository eventRepository,
                                    AnalyticsOutboxService analyticsOutboxService,
                                    PaycheckAutopilotProperties props) {
        this.userRepository = userRepository;
        this.scheduleRepository = scheduleRepository;
        this.streamRepository = streamRepository;
        this.eventRepository = eventRepository;
        this.analyticsOutboxService = analyticsOutboxService;
        this.props = props;
    }

    /**
     * One evaluation, in one transaction, under the user row lock (Decision 4: a shadow user may
     * have no schedule row to lock, and a concurrent exchange already serializes on this row).
     * Excluded users (R8) are reported and nothing is written.
     */
    @Transactional
    public EvaluationOutcome evaluate(Long userId, EvaluationTrigger trigger, LocalDate todayET) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("No user " + userId));
        Optional<ExclusionReason> excluded = DetectionEligibility.exclusion(user, props);
        if (excluded.isPresent()) {
            logger.info("paycheck.evaluate.skipped user={} trigger={} reason={}", userId, trigger, excluded.get());
            return EvaluationOutcome.skipped(excluded.get());
        }
        InvestmentSchedule schedule = scheduleRepository.findTopByUserOrderByCreatedAtDesc(user).orElse(null);
        if (Boolean.FALSE.equals(user.getPlaidTransactionsConsented())) {
            // No Transactions data can arrive, and PlaidItemStateService owns the NEEDS_CONSENT
            // mirror; recomputing from older events would overwrite it.
            logger.info("paycheck.evaluate.skipped user={} trigger={} reason=CONSENT_REFUSED", userId, trigger);
            return new EvaluationOutcome(DetectionState.NEEDS_CONSENT,
                    schedule == null ? null : schedule.getPrimaryStreamId(), false, false, Optional.empty());
        }
        return evaluateLocked(user, schedule, trigger, todayET);
    }

    /** The body of evaluate; the caller holds the user row lock and has checked eligibility. */
    private EvaluationOutcome evaluateLocked(User user, InvestmentSchedule schedule,
                                             EvaluationTrigger trigger, LocalDate todayET) {
        Long userId = user.getId();
        LocalDateTime nowET = EasternTime.now();

        List<PaycheckStream> streams = new ArrayList<>(streamRepository.findByUserIdOrderByIdAsc(userId));
        PaycheckStream previousPrimary = streams.stream()
                .filter(s -> s.getRole() == PaycheckStreamRole.PRIMARY && s.getState() != PaycheckStreamState.RETIRED)
                .findFirst()
                .orElse(null);
        Long previousPrimaryId = previousPrimary == null ? null : previousPrimary.getId();

        List<PaycheckEvent> deposits = eventRepository
                .findByUserIdAndStatusAndPendingFalseOrderByPostedDateAsc(userId, PaycheckEventStatus.ACTIVE);
        // Each grouped stream's cadence, by stream id. T12 opens the primary's cycle from this map.
        Map<Long, CadenceResult> cadences = new HashMap<>();
        List<PaycheckEvent> relinked = new ArrayList<>();
        for (DepositGroup group : DepositGrouper.group(deposits)) {
            String token = ColumnText.fit(group.payerToken(), PAYER_TOKEN_WIDTH);
            if (token == null || token.isBlank() || group.depositsAsc().isEmpty()) {
                continue;
            }
            PaycheckStream stream = findOrCreate(streams, user, group.plaidAccountId(), token, nowET);
            applyGroup(stream, group, todayET, nowET).ifPresent(cadence -> cadences.put(stream.getId(), cadence));
            for (PaycheckEvent deposit : group.depositsAsc()) {
                if (!stream.getId().equals(deposit.getStreamId())) {
                    deposit.setStreamId(stream.getId());
                    relinked.add(deposit);
                }
            }
        }

        PaycheckStream primary = null;
        boolean switched = false;
        if (Boolean.TRUE.equals(user.getPlaidHistoryComplete())) {
            List<PaycheckStream> qualifying = streams.stream()
                    .filter(s -> QUALIFYING.contains(s.getState()))
                    .filter(s -> fundsTheSchedule(user, s))
                    .toList();
            if (previousPrimary != null) {
                Optional<Long> successor = maybeSucceed(user, schedule, previousPrimary, qualifying, todayET);
                if (successor.isPresent()) {
                    primary = byId(streams, successor.get());
                    switched = true;
                } else if (KEEPS_ROLE.contains(previousPrimary.getState())) {
                    primary = previousPrimary;
                }
            }
            if (primary == null && !qualifying.isEmpty()) {
                primary = byId(streams, PrimarySelector.select(qualifying, monthlyGoal(user)).primaryStreamId());
            }
        }
        assignRoles(streams, primary);
        streamRepository.saveAll(streams);
        if (!relinked.isEmpty()) {
            eventRepository.saveAll(relinked);
        }

        DetectionState state = detectionState(user, primary);
        Long primaryId = primary == null ? null : primary.getId();
        if (schedule != null) {
            // R1: the mirror, in every mode. Nothing that moves money is touched.
            schedule.setDetectionState(state);
            schedule.setPrimaryStreamId(primaryId);
            schedule.setNextExpectedDate(primary == null ? null : primary.getPredictedNextDate());
            schedule.setLastCadenceCheckAt(nowET);
            scheduleRepository.save(schedule);
        }

        boolean primaryChanged = !Objects.equals(previousPrimaryId, primaryId);
        logger.info("paycheck.evaluate user={} trigger={} state={} streams={} primary_stream={} primary_changed={} switched={}",
                userId, trigger, state, streams.size(), primaryId, primaryChanged, switched);
        return new EvaluationOutcome(state, primaryId, primaryChanged, switched, Optional.empty());
    }

    /** Basic succession (spec 6.1 conditions 1 to 3, R5), judged as of today in Eastern time. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> maybeSucceed(User user, InvestmentSchedule schedule, PaycheckStream s1,
                                       List<PaycheckStream> candidates) {
        return maybeSucceed(user, schedule, s1, candidates, EasternTime.today());
    }

    /**
     * Basic succession (spec 6.1 conditions 1 to 3, no recency exception; R5). The first candidate
     * with a different payer, best first, that SuccessionRule accepts becomes the primary. Effects,
     * in this order, inside the caller's transaction: S1 RETIRED with SUCCEEDED, S2 PRIMARY and
     * PROVISIONAL, the schedule mirror's primary_stream_id, then paycheck_stream_switched with
     * exactly frequency then reason. No amount, authorization, hold or notice change (Phase 2).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> maybeSucceed(User user, InvestmentSchedule schedule, PaycheckStream s1,
                                       List<PaycheckStream> candidates, LocalDate todayET) {
        List<PaycheckStream> ordered = candidates.stream()
                .filter(s2 -> !Objects.equals(s2.getId(), s1.getId()))
                .filter(s2 -> !Objects.equals(s2.getPayerToken(), s1.getPayerToken()))
                .sorted(SUCCESSOR_ORDER)
                .toList();
        for (PaycheckStream s2 : ordered) {
            if (!SuccessionRule.succeeds(s1, s2, todayET)) {
                continue;
            }
            LocalDateTime nowET = EasternTime.now();
            s1.setState(PaycheckStreamState.RETIRED);
            s1.setRetiredReason(PaycheckRetiredReason.SUCCEEDED);
            s1.setRetiredAt(nowET);
            s1.setRole(PaycheckStreamRole.NONE);
            s1.setUpdatedAt(nowET);
            streamRepository.save(s1);

            s2.setRole(PaycheckStreamRole.PRIMARY);
            s2.setState(PaycheckStreamState.PROVISIONAL);
            s2.setUpdatedAt(nowET);
            streamRepository.save(s2);

            if (schedule != null) {
                schedule.setPrimaryStreamId(s2.getId());
            }

            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("frequency", InvestmentScheduleService.frequencySlug(
                    s2.getCadence() == null ? null : s2.getCadence().name()));
            properties.put("reason", ServerAnalyticsEvent.REASON_SUCCESSION);
            analyticsOutboxService.record(ServerAnalyticsEvent.PAYCHECK_STREAM_SWITCHED, user, properties);

            logger.info("paycheck.stream.switched user={} from_stream={} to_stream={}",
                    user.getId(), s1.getId(), s2.getId());
            return Optional.of(s2.getId());
        }
        return Optional.empty();
    }

    private PaycheckStream findOrCreate(List<PaycheckStream> streams, User user, String accountId,
                                        String token, LocalDateTime nowET) {
        String account = ColumnText.fit(accountId, PLAID_ID_WIDTH);
        for (PaycheckStream existing : streams) {
            if (Objects.equals(existing.getPlaidAccountId(), account) && token.equals(existing.getPayerToken())) {
                return existing;
            }
        }
        PaycheckStream created = new PaycheckStream();
        created.setUserId(user.getId());
        created.setPlaidItemId(ColumnText.fit(user.getPlaidItemId(), PLAID_ID_WIDTH));
        created.setPlaidAccountId(account);
        created.setPayerToken(token);
        created.setPayerDisplayName(token);
        created.setState(PaycheckStreamState.CANDIDATE);
        created.setRole(PaycheckStreamRole.NONE);
        created.setCreatedAt(nowET);
        PaycheckStream saved = streamRepository.save(created);
        streams.add(saved);
        return saved;
    }

    /**
     * Stats, gates, score and state for one group (spec 4.2 to 4.5). Returns the cadence when the
     * stream passed the gates, so the caller can hand it to the cycle code.
     */
    private Optional<CadenceResult> applyGroup(PaycheckStream stream, DepositGroup group,
                                               LocalDate todayET, LocalDateTime nowET) {
        List<PaycheckEvent> all = group.depositsAsc();
        PaycheckEvent latest = all.get(all.size() - 1);
        List<PaycheckEvent> trailing = all.subList(Math.max(0, all.size() - TRAILING_DEPOSITS), all.size());
        List<LocalDate> dates = trailing.stream().map(PaycheckEvent::getPostedDate).toList();
        List<BigDecimal> amounts = trailing.stream().map(PaycheckEvent::getAmount).toList();
        BigDecimal median = DepositStats.trailingMedian(amounts);
        BigDecimal cv = amounts.size() < 2 ? null : DepositStats.trailingCv(amounts);

        String displayName = PayerTokenizer.displayName(latest.getCounterpartyName(),
                latest.getCounterpartyType(), latest.getMerchantName(), stream.getPayerToken());
        stream.setPayerDisplayName(ColumnText.fit(Objects.requireNonNullElse(displayName, stream.getPayerToken()),
                DISPLAY_NAME_WIDTH));
        stream.setRawDescriptor(ColumnText.fit(latest.getRawDescriptor(), DESCRIPTOR_WIDTH));
        stream.setDepositsFound(toShort(all.size()));
        stream.setFirstDate(all.get(0).getPostedDate());
        stream.setLastDate(latest.getPostedDate());
        stream.setLastAmount(latest.getAmount());
        stream.setTrailingMedian(median == null ? null : median.setScale(2, RoundingMode.HALF_UP));
        stream.setTrailingCv(cv == null ? null : cv.min(CV_MAX).setScale(4, RoundingMode.HALF_UP));
        stream.setUpdatedAt(nowET);

        if (stream.getState() == PaycheckStreamState.RETIRED) {
            // Spec 6.1: a retired stream's later deposits are recorded, never confirmed or promoted.
            return Optional.empty();
        }
        if (all.size() < 2) {
            // Spec 4.2 gate 9: fewer than 2 posted deposits is a candidate, never rejected.
            markCandidate(stream);
            return Optional.empty();
        }
        Optional<StreamFacts> facts = StreamFacts.of(stream);
        Optional<CadenceResult> cadence = CadenceDeriver.derive(dates, facts.map(StreamFacts::plaidFrequency));
        Optional<PaycheckRejectReason> reject = HardGates.evaluate(group, facts, cadence, median, todayET);
        if (reject.isPresent()) {
            stream.setState(PaycheckStreamState.REJECTED);
            stream.setRejectReason(reject.get());
            stream.setScore((short) 0);
            stream.setScoreBreakdownJson(null);
            return Optional.empty();
        }
        if (cadence.isEmpty()) {
            markCandidate(stream);
            return Optional.empty();
        }

        CadenceResult result = cadence.get();
        ScoreBreakdown breakdown = StreamScorer.score(group, facts, result, median, cv);
        int onCadence = StreamStateRule.onCadenceCount(dates, result);
        boolean plaidMature = facts.map(f -> "MATURE".equals(f.plaidStatus())).orElse(false);
        PaycheckStreamState state = StreamStateRule.state(breakdown.total(), onCadence, plaidMature, facts.isPresent(), props);
        if (stream.getMissedCount() > 0 && QUALIFYING.contains(state)) {
            // A miss stands until T12 matches the next deposit and resets missed_count (spec 6.4).
            state = PaycheckStreamState.DORMANT;
        }
        stream.setState(state);
        stream.setRejectReason(null);
        stream.setScore(toShort(breakdown.total()));
        stream.setScoreBreakdownJson(breakdown.toJson());
        stream.setCadence(result.cadence());
        stream.setCadenceSource(result.source());
        stream.setAnchorDow(result.anchorDow() == null ? null : result.anchorDow().shortValue());
        stream.setAnchorParityDate(result.anchorParityDate());
        stream.setAnchorDay1(result.anchorDay1());
        stream.setAnchorDay2(result.anchorDay2());
        stream.setPredictedNextDate(PayCalendar.predictedNext(result, latest.getPostedDate()));
        return cadence;
    }

    private static void markCandidate(PaycheckStream stream) {
        stream.setState(PaycheckStreamState.CANDIDATE);
        stream.setRejectReason(null);
        stream.setScore((short) 0);
        stream.setScoreBreakdownJson(null);
    }

    /** Roles follow states and the chosen primary; RETIRED holds none (spec 4.2, 4.5, 6.1). */
    private static void assignRoles(List<PaycheckStream> streams, PaycheckStream primary) {
        for (PaycheckStream s : streams) {
            PaycheckStreamRole role;
            if (s.getState() == PaycheckStreamState.RETIRED) {
                role = PaycheckStreamRole.NONE;
            } else if (primary != null && s.getId().equals(primary.getId())) {
                role = PaycheckStreamRole.PRIMARY;
            } else if (s.getState() == PaycheckStreamState.REJECTED
                    && s.getRejectReason() == PaycheckRejectReason.BENEFITS_SIGNAL) {
                role = PaycheckStreamRole.INTERRUPTION;
            } else if (KEEPS_ROLE.contains(s.getState())) {
                role = PaycheckStreamRole.SECONDARY;
            } else {
                role = PaycheckStreamRole.NONE;
            }
            s.setRole(role);
        }
    }

    /** Spec 4.5 screen-1 outcome: SEARCHING until history completes, then the primary or nothing. */
    private static DetectionState detectionState(User user, PaycheckStream primary) {
        if (!Boolean.TRUE.equals(user.getPlaidHistoryComplete())) {
            return DetectionState.SEARCHING;
        }
        if (primary == null) {
            return DetectionState.UNDETECTABLE;
        }
        return switch (primary.getState()) {
            case CONFIRMED -> DetectionState.CONFIRMED;
            case DORMANT -> DetectionState.DORMANT;
            default -> DetectionState.PROVISIONAL;
        };
    }

    /** Spec 4.5: until split-deposit summing (Phase 3), only the funding-account leg can be primary. */
    private static boolean fundsTheSchedule(User user, PaycheckStream stream) {
        return user.getPlaidAccountId() == null || user.getPlaidAccountId().equals(stream.getPlaidAccountId());
    }

    /** Spec 4.1: the survey goal, else 500. */
    private static BigDecimal monthlyGoal(User user) {
        Double goal = user.getMonthlyInvestment();
        return goal == null || goal <= 0 ? BigDecimal.valueOf(AmountPrefill.DEFAULT_GOAL) : BigDecimal.valueOf(goal);
    }

    private static PaycheckStream byId(List<PaycheckStream> streams, Long id) {
        if (id == null) {
            return null;
        }
        return streams.stream().filter(s -> id.equals(s.getId())).findFirst().orElse(null);
    }

    private static short toShort(int value) {
        return (short) Math.min(value, Short.MAX_VALUE);
    }
}
