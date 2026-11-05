package com.investingapp.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.dto.admin.ShadowReport;
import com.investingapp.backend.dto.admin.ShadowReport.CycleRow;
import com.investingapp.backend.dto.admin.ShadowReport.Summary;
import com.investingapp.backend.dto.admin.ShadowReport.UserRow;
import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PaycheckCycle;
import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckSkipReason;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamRole;
import com.investingapp.backend.model.PaycheckStreamState;
import com.investingapp.backend.model.User;
import com.investingapp.backend.paycheck.detect.AmountPrefill;
import com.investingapp.backend.paycheck.detect.PrimarySelector;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckCycleRepository;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.DetectionEligibility.ExclusionReason;
import com.investingapp.backend.util.EasternTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Spec 12.4 / R13. DB only, read only. R8's rejected, opted-out and token-less users are left out. */
@Service
public class ShadowReportService {

    private static final Logger logger = LoggerFactory.getLogger(ShadowReportService.class);
    static final int DEFAULT_WINDOW_DAYS = 7;
    static final int LAG_UNKNOWN = -1;
    private static final Set<ExclusionReason> LEFT_OUT =
            EnumSet.of(ExclusionReason.ACCOUNT_REJECTED, ExclusionReason.OPTED_OUT, ExclusionReason.NO_TOKEN);
    private static final TypeReference<LinkedHashMap<String, Integer>> BREAKDOWN = new TypeReference<>() {
    };
    private static final BigDecimal BAND_50 = new BigDecimal("0.50");
    private static final BigDecimal BAND_80 = new BigDecimal("0.80");
    private static final BigDecimal BAND_90 = new BigDecimal("0.90");

    private final UserRepository userRepository;
    private final InvestmentScheduleRepository scheduleRepository;
    private final PaycheckStreamRepository streamRepository;
    private final PaycheckCycleRepository cycleRepository;
    private final PaycheckEventRepository eventRepository;
    private final PaycheckAutopilotProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ShadowReportService(UserRepository userRepository, InvestmentScheduleRepository scheduleRepository,
                               PaycheckStreamRepository streamRepository, PaycheckCycleRepository cycleRepository,
                               PaycheckEventRepository eventRepository, PaycheckAutopilotProperties props) {
        this.userRepository = userRepository;
        this.scheduleRepository = scheduleRepository;
        this.streamRepository = streamRepository;
        this.cycleRepository = cycleRepository;
        this.eventRepository = eventRepository;
        this.props = props;
    }

    /** Shadow cycles confirmed on or after since (ET day); null means the last 7 ET days. */
    @Transactional(readOnly = true)
    public ShadowReport build(LocalDate since) {
        LocalDate today = EasternTime.today();
        LocalDate from = since != null ? since : today.minusDays(DEFAULT_WINDOW_DAYS);
        List<User> users = new ArrayList<>(userRepository.findByPlaidAccessTokenIsNotNull());
        users.sort(Comparator.comparing(User::getId));
        List<UserRow> rows = new ArrayList<>();
        for (User user : users) {
            Optional<ExclusionReason> excluded = DetectionEligibility.exclusion(user, props);
            if (excluded.isEmpty() || !LEFT_OUT.contains(excluded.get())) {
                rows.add(row(user, from));
            }
        }
        Summary summary = summarize(rows);
        logger.info("paycheck.shadow_report users={} shadow_cycles={}", summary.users(), summary.shadowCycles());
        return new ShadowReport(from, today, props.isShadow(), summary, List.copyOf(rows));
    }

    public static String payerTokenHash(String payerToken) {
        if (payerToken == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payerToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    private UserRow row(User user, LocalDate from) {
        Optional<InvestmentSchedule> schedule = scheduleRepository.findTopByUserOrderByCreatedAtDesc(user);
        Optional<PaycheckStream> primary = streamRepository.findByUserIdOrderByIdAsc(user.getId()).stream()
                .filter(s -> s.getRole() == PaycheckStreamRole.PRIMARY && s.getState() != PaycheckStreamState.RETIRED)
                .findFirst();
        List<CycleRow> cycles = cycleRepository
                .findByUserIdAndSkipReasonAndConfirmedAtAfter(user.getId(), PaycheckSkipReason.SHADOW_CONFIRMED, from.atStartOfDay())
                .stream().sorted(Comparator.comparing(PaycheckCycle::getConfirmedAt)).map(this::cycleRow).toList();
        return new UserRow(
                user.getPublicId(),
                detectionState(schedule, user, primary),
                user.getPlaidItemStatus() == null ? null : user.getPlaidItemStatus().name(),
                primary.map(s -> payerTokenHash(s.getPayerToken())).orElse(null),
                primary.map(s -> (int) s.getScore()).orElse(null),
                primary.map(this::breakdown).orElse(Map.of()),
                primary.map(s -> s.getCadence() == null ? null : s.getCadence().name()).orElse(null),
                primary.map(s -> (int) s.getDepositsFound()).orElse(0),
                primary.map(s -> (int) s.getMissedCount()).orElse(0),
                primary.map(s -> lowConfidence(s, user)).orElse(false),
                cycles);
    }

    /** The R1 mirror when set, else the primary stream, else history. */
    static String detectionState(Optional<InvestmentSchedule> schedule, User user, Optional<PaycheckStream> primary) {
        if (schedule.isPresent() && schedule.get().getDetectionState() != null) {
            return schedule.get().getDetectionState().name();
        }
        if (primary.isPresent()) {
            return primary.get().getState().name();
        }
        return Boolean.TRUE.equals(user.getPlaidHistoryComplete()) ? DetectionState.UNDETECTABLE.name() : DetectionState.SEARCHING.name();
    }

    private CycleRow cycleRow(PaycheckCycle cycle) {
        LocalDate posted = cycle.getEventId() == null ? null
                : eventRepository.findById(cycle.getEventId()).map(PaycheckEvent::getPostedDate).orElse(null);
        LocalDate confirmedOn = cycle.getConfirmedAt().toLocalDate();
        int lag = posted == null ? LAG_UNKNOWN : (int) ChronoUnit.DAYS.between(posted, confirmedOn);
        BigDecimal pull = cycle.getAmountToPull() == null ? null : cycle.getAmountToPull().setScale(2, RoundingMode.HALF_UP);
        BigDecimal share = share(pull, cycle.getDepositAmount());
        return new CycleRow(cycle.getPeriodKey(), cycle.getExpectedDate(), posted, confirmedOn, lag, pull,
                pullShareBand(share), share != null && share.compareTo(props.getAffordabilityShare()) <= 0);
    }

    static BigDecimal share(BigDecimal pull, BigDecimal deposit) {
        if (pull == null || deposit == null || deposit.signum() <= 0) {
            return null;
        }
        return pull.divide(deposit, 4, RoundingMode.HALF_UP);
    }

    static String pullShareBand(BigDecimal share) {
        if (share == null) {
            return null;
        }
        if (share.compareTo(BAND_50) < 0) {
            return "under_50";
        }
        if (share.compareTo(BAND_80) < 0) {
            return "50_79";
        }
        return share.compareTo(BAND_90) <= 0 ? "80_90" : "over_90";
    }

    private Map<String, Integer> breakdown(PaycheckStream stream) {
        String body = stream.getScoreBreakdownJson();
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(body, BREAKDOWN);
        } catch (JsonProcessingException e) {
            logger.warn("paycheck.shadow_report breakdown_unreadable stream={}", stream.getId());
            return Map.of();
        }
    }

    private static boolean lowConfidence(PaycheckStream primary, User user) {
        Double goal = user.getMonthlyInvestment();
        BigDecimal monthlyGoal = goal == null || goal <= 0 ? BigDecimal.valueOf(AmountPrefill.DEFAULT_GOAL) : BigDecimal.valueOf(goal);
        return PrimarySelector.select(List.of(primary), monthlyGoal).lowConfidence();
    }

    static Summary summarize(List<UserRow> rows) {
        List<Integer> lags = rows.stream().flatMap(r -> r.shadowCycles().stream()).map(CycleRow::confirmLagDays).filter(l -> l >= 0).toList();
        double mean = Math.round(lags.stream().mapToInt(Integer::intValue).average().orElse(0.0) * 100.0) / 100.0;
        return new Summary(rows.size(), count(rows, DetectionState.PROVISIONAL), count(rows, DetectionState.CONFIRMED),
                count(rows, DetectionState.UNDETECTABLE), rows.stream().mapToInt(r -> r.shadowCycles().size()).sum(), mean);
    }

    private static int count(List<UserRow> rows, DetectionState state) {
        return (int) rows.stream().filter(r -> state.name().equals(r.detectionState())).count();
    }
}
