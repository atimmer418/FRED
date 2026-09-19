package com.investingapp.backend.service;

import com.investingapp.backend.dto.TlhEventDto;
import com.investingapp.backend.dto.TlhStatusResponse;
import com.investingapp.backend.dto.TlhSummaryResponse;
import com.investingapp.backend.model.TlhHarvestEvent;
import com.investingapp.backend.model.TlhUserState;
import com.investingapp.backend.model.User;
import com.investingapp.backend.model.WashSaleWindow;
import com.investingapp.backend.repository.TaxLotRepository;
import com.investingapp.backend.repository.TlhHarvestEventRepository;
import com.investingapp.backend.repository.TlhUserStateRepository;
import com.investingapp.backend.repository.WashSaleWindowRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Read-side aggregation for the TLH endpoints (my-profile "saved this year"). */
@Service
public class TlhSummaryService {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    /**
     * Compliance note: phrased to avoid the MarketNarrativeValidator blocklist
     * ("rebalance your" family) so a future narrative surface can quote it.
     */
    public static final String DISCLAIMER =
            "Estimated savings are an estimate, not tax advice. Figures cover this account only. "
            + "Activity in outside accounts (IRAs, other brokerages, or a spouse's accounts) can "
            + "disallow losses under IRS wash-sale rules. Realized losses offset capital gains first, "
            + "then up to $3,000 per year of ordinary income, with the remainder carried forward. "
            + "Your Alpaca 1099-B is the tax document of record.";

    private final TlhHarvestEventRepository eventRepository;
    private final WashSaleWindowRepository windowRepository;
    private final TlhUserStateRepository userStateRepository;
    private final TaxLotRepository taxLotRepository;
    private final BigDecimal estimateRate;
    private final String mode;

    public TlhSummaryService(TlhHarvestEventRepository eventRepository,
            WashSaleWindowRepository windowRepository,
            TlhUserStateRepository userStateRepository,
            TaxLotRepository taxLotRepository,
            @Value("${app.tlh.estimate-tax-rate:0.24}") BigDecimal estimateRate,
            @Value("${app.tlh.mode:off}") String mode) {
        this.eventRepository = eventRepository;
        this.windowRepository = windowRepository;
        this.userStateRepository = userStateRepository;
        this.taxLotRepository = taxLotRepository;
        this.estimateRate = estimateRate;
        this.mode = mode;
    }

    @Transactional(readOnly = true)
    public TlhSummaryResponse getSummary(User user, int year) {
        BigDecimal allowed = eventRepository.sumAllowedLossForYear(user.getId(), year);
        BigDecimal disallowed = eventRepository.sumDisallowedLossForYear(user.getId(), year);
        BigDecimal savings = allowed.multiply(estimateRate).setScale(2, RoundingMode.HALF_UP);
        int eventCount = eventRepository.countByUserIdAndModeAndTaxYear(
                user.getId(), TlhHarvestEvent.HarvestMode.LIVE, year);
        return new TlhSummaryResponse(year, allowed.setScale(2, RoundingMode.HALF_UP),
                disallowed.setScale(2, RoundingMode.HALF_UP), savings, estimateRate,
                eventCount, DISCLAIMER);
    }

    @Transactional(readOnly = true)
    public List<TlhEventDto> getEvents(User user, int limit) {
        List<TlhEventDto> events = new ArrayList<>();
        for (TlhHarvestEvent event : eventRepository.findTop200ByUserIdOrderByCreatedAtDesc(user.getId())) {
            if (events.size() >= limit) {
                break;
            }
            events.add(TlhEventDto.from(event));
        }
        return events;
    }

    @Transactional(readOnly = true)
    public TlhStatusResponse getStatus(User user, boolean tierEligible) {
        String ledgerStatus = userStateRepository.findByUserId(user.getId())
                .map(TlhUserState::getLedgerStatus)
                .map(Enum::name)
                .orElse("NONE");
        LocalDate today = LocalDate.now(ET);
        List<TlhStatusResponse.WindowDto> windows = new ArrayList<>();
        for (WashSaleWindow window : windowRepository.findByUserIdAndActiveTrue(user.getId())) {
            if (!today.isBefore(window.getStartDate()) && !today.isAfter(window.getEndDate())) {
                windows.add(new TlhStatusResponse.WindowDto(
                        window.getSymbol(), window.getEndDate(), window.getRedirectSymbol()));
            }
        }
        int inFlight = eventRepository.countByUserIdAndStatusIn(
                user.getId(), TlhHarvestEvent.IN_FLIGHT_STATUSES);
        return new TlhStatusResponse(
                Boolean.TRUE.equals(user.getTlhEnabled()),
                tierEligible,
                mode,
                ledgerStatus,
                windows,
                taxLotRepository.findUnreconciledSymbols(user.getId()),
                inFlight,
                DISCLAIMER);
    }

    /** Read-access rule input: does this user have any TLH history at all? */
    public boolean hasTlhHistory(User user) {
        return eventRepository.existsByUserId(user.getId())
                || windowRepository.existsByUserIdAndActiveTrue(user.getId());
    }
}
