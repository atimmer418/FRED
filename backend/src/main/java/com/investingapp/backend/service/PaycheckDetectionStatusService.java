package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.dto.PaycheckDetectionStatusResponse;
import com.investingapp.backend.dto.PaycheckDetectionStatusResponse.ManualPrefillView;
import com.investingapp.backend.dto.PaycheckDetectionStatusResponse.PrimaryStreamView;
import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.InvestmentFrequency;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamState;
import com.investingapp.backend.model.User;
import com.investingapp.backend.paycheck.detect.AmountPrefill;
import com.investingapp.backend.paycheck.detect.CadenceResult;
import com.investingapp.backend.paycheck.detect.PayCalendar;
import com.investingapp.backend.paycheck.detect.PrimarySelector;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import com.investingapp.backend.util.EasternTime;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Spec 8.1 body from the database alone: never calls Plaid, never writes. */
@Service
public class PaycheckDetectionStatusService {

    private static final Set<DetectionState> NO_PRIMARY =
            EnumSet.of(DetectionState.SEARCHING, DetectionState.NEEDS_CONSENT, DetectionState.UNDETECTABLE);
    private static final Set<PaycheckStreamState> QUALIFYING =
            EnumSet.of(PaycheckStreamState.PROVISIONAL, PaycheckStreamState.CONFIRMED);
    static final int MANUAL_DEFAULT_DAYS = 14;
    private static final String MASK_DOTS = "••••";

    private final InvestmentScheduleRepository scheduleRepository;
    private final PaycheckStreamRepository streamRepository;
    private final PaycheckAutopilotProperties props;

    public PaycheckDetectionStatusService(InvestmentScheduleRepository scheduleRepository,
            PaycheckStreamRepository streamRepository, PaycheckAutopilotProperties props) {
        this.scheduleRepository = scheduleRepository;
        this.streamRepository = streamRepository;
        this.props = props;
    }

    public PaycheckDetectionStatusResponse status(User user) {
        return status(user, EasternTime.today());
    }

    PaycheckDetectionStatusResponse status(User user, LocalDate todayET) {
        InvestmentSchedule schedule = scheduleRepository.findTopByUserOrderByCreatedAtDesc(user).orElse(null);
        DetectionState state = schedule != null && schedule.getDetectionState() != null
                ? schedule.getDetectionState() : DetectionState.SEARCHING;
        BigDecimal share = props.getAffordabilityShare();
        // The goal does not depend on cadence; BIWEEKLY with no median yields it and its source.
        AmountPrefill.Prefill base =
                AmountPrefill.compute(user.getMonthlyInvestment(), InvestmentFrequency.BIWEEKLY, null, share);
        PrimaryStreamView primary = primaryStream(user, schedule, state)
                .map(stream -> primaryView(user, schedule, stream, share)).orElse(null);
        BigDecimal savedAmount = schedule == null ? null : schedule.getInvestmentAmount();

        return new PaycheckDetectionStatusResponse(
                schedule == null || schedule.getMode() == null ? "MANUAL" : schedule.getMode().name(),
                state.name(),
                schedule == null || schedule.getHoldReason() == null ? null : schedule.getHoldReason().name(),
                user.getPlaidItemStatus() == null ? null : user.getPlaidItemStatus().name(),
                schedule != null && schedule.getStatus() != InvestmentSchedule.Status.ACTIVE,
                props.isShadow(), Boolean.TRUE.equals(user.getPaycheckAutopilotOptOut()),
                schedule == null ? null : schedule.getDetectionStartedAt(),
                Boolean.TRUE.equals(user.getPlaidHistoryComplete()),
                fundingAccountLabel(user), user.getPlaidInstitutionName(),
                base.monthlyGoal(), base.goalSource(), primary, savedAmount, null, null,
                manualPrefill(primary, savedAmount, base, todayET));
    }

    private Optional<PaycheckStream> primaryStream(User user, InvestmentSchedule schedule, DetectionState state) {
        if (schedule == null || schedule.getPrimaryStreamId() == null || NO_PRIMARY.contains(state)) {
            return Optional.empty();
        }
        return streamRepository.findById(schedule.getPrimaryStreamId())
                .filter(stream -> user.getId().equals(stream.getUserId()));
    }

    private PrimaryStreamView primaryView(User user, InvestmentSchedule schedule, PaycheckStream stream,
            BigDecimal share) {
        InvestmentFrequency cadence = stream.getCadence();
        AmountPrefill.Prefill prefill = AmountPrefill.compute(user.getMonthlyInvestment(),
                cadence != null ? cadence : InvestmentFrequency.BIWEEKLY, stream.getTrailingMedian(), share);
        PrimarySelector.Selection selection = selection(user, prefill.monthlyGoal());
        LocalDate next = schedule.getNextExpectedDate() != null
                ? schedule.getNextExpectedDate() : stream.getPredictedNextDate();
        return new PrimaryStreamView(stream.getId(), stream.getPayerDisplayName(), stream.getRawDescriptor(),
                stream.getDepositsFound(), cadence == null ? null : cadence.name(), cadenceLabel(stream),
                cadence == null ? null : cadence.perYear(), cadence == null ? null : cadence.monthlyFactor(),
                stream.getLastAmount(), stream.getLastDate(), next,
                prefill.affordabilityCap(), prefill.suggestedAmount(), prefill.prefillAmount(),
                selection.lowConfidence(), selection.needsChooser());
    }

    private PrimarySelector.Selection selection(User user, BigDecimal monthlyGoal) {
        List<PaycheckStream> qualifying = streamRepository.findByUserIdOrderByIdAsc(user.getId()).stream()
                .filter(stream -> QUALIFYING.contains(stream.getState())).toList();
        return qualifying.isEmpty()
                ? new PrimarySelector.Selection(null, List.of(), false, false)
                : PrimarySelector.select(qualifying, monthlyGoal);
    }

    private static String cadenceLabel(PaycheckStream stream) {
        if (stream.getCadence() == null) {
            return null;
        }
        return PayCalendar.cadenceLabel(new CadenceResult(stream.getCadence(), stream.getCadenceSource(),
                stream.getAnchorDow() == null ? null : stream.getAnchorDow().intValue(),
                stream.getAnchorParityDate(), stream.getAnchorDay1(), stream.getAnchorDay2(), 0, true, 0));
    }

    private static ManualPrefillView manualPrefill(PrimaryStreamView primary, BigDecimal savedAmount,
            AmountPrefill.Prefill base, LocalDate todayET) {
        String frequency = primary != null && primary.cadence() != null
                ? primary.cadence() : InvestmentFrequency.BIWEEKLY.name();
        LocalDate next = primary != null && primary.nextExpectedDate() != null
                ? primary.nextExpectedDate() : todayET.plusDays(MANUAL_DEFAULT_DAYS);
        BigDecimal amount = savedAmount != null ? savedAmount
                : BigDecimal.valueOf(primary != null ? primary.prefillAmount() : base.suggestedAmount());
        return new ManualPrefillView(frequency, next, amount);
    }

    static String fundingAccountLabel(User user) {
        String name = user.getPlaidAccountName();
        String mask = user.getPlaidAccountMask();
        if (name == null || name.isBlank()) {
            return mask == null ? null : MASK_DOTS + mask;
        }
        return mask == null ? name : name + " " + MASK_DOTS + mask;
    }
}
