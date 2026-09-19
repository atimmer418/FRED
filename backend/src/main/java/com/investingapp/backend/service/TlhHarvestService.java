package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.dto.TlhPreviewResponse;
import com.investingapp.backend.model.TaxLot;
import com.investingapp.backend.model.TlhCycleRun;
import com.investingapp.backend.model.TlhHarvestEvent;
import com.investingapp.backend.model.TlhUserState;
import com.investingapp.backend.model.User;
import com.investingapp.backend.model.WashSaleWindow;
import com.investingapp.backend.repository.*;
import com.investingapp.backend.service.TaxLotLedgerService.PositionSnapshot;
import com.investingapp.backend.service.TaxLotLedgerService.SalePreview;
import com.investingapp.backend.service.TlhEligibilityService.EvalResult;
import com.investingapp.backend.service.TlhEligibilityService.HarvestCandidate;
import com.investingapp.backend.service.TlhEligibilityService.Veto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TLH cycle orchestrator: daily harvest cycle, nightly reconciliation, and
 * the read-only preview. Tier + opt-in are enforced at query time
 * (findBySelectedTierInAndTlhEnabledTrue(["pro"])) — the codebase's scheduler
 * gating idiom — and re-checked at execution time by TlhExecutionService.
 *
 * Idempotency: the UNIQUE (user, symbol, cycle_date, mode) claim constraint
 * plus the mid-flight-event skip make any re-run a no-op. TLH is state-based,
 * not period-based — a missed day is subsumed by the next cycle.
 */
@Service
public class TlhHarvestService {

    private static final Logger logger = LoggerFactory.getLogger(TlhHarvestService.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final List<String> PRO_TIERS = List.of("pro");
    private static final int SYNC_PAGES_SAFETY_VALVE = 50;
    private static final int REBASELINE_AFTER_DAYS = 7;
    private static final int DRY_RUN_EXPIRY_DAYS = 7;
    private static final long PREVIEW_CACHE_TTL_MS = 60_000L;
    private static final BigDecimal DUST_QTY = new BigDecimal("0.001");

    private final UserRepository userRepository;
    private final TlhHarvestEventRepository eventRepository;
    private final TlhUserStateRepository userStateRepository;
    private final TlhCycleRunRepository cycleRunRepository;
    private final WashSaleWindowRepository windowRepository;
    private final TaxLotRepository taxLotRepository;
    private final WashSaleGuardService guard;
    private final TlhEligibilityService eligibilityService;
    private final ReplacementSelectionService replacementSelectionService;
    private final TaxLotLedgerService ledger;
    private final TlhExecutionService executionService;
    private final AlpacaService alpacaService;
    private final EncryptionService encryptionService;
    private final TlhCatalog catalog;
    private final TransactionTemplate claimTx;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String mode;
    private final int maxHarvestsPerCycle;
    private final int maxUsersPerCycle;
    private final long interUserDelayMs;
    private final int consecutiveFailureHalt;

    private final Map<Long, CachedPreview> previewCache = new ConcurrentHashMap<>();

    private record CachedPreview(TlhPreviewResponse response, long cachedAtMs) {
    }

    public TlhHarvestService(UserRepository userRepository,
            TlhHarvestEventRepository eventRepository,
            TlhUserStateRepository userStateRepository,
            TlhCycleRunRepository cycleRunRepository,
            WashSaleWindowRepository windowRepository,
            TaxLotRepository taxLotRepository,
            WashSaleGuardService guard,
            TlhEligibilityService eligibilityService,
            ReplacementSelectionService replacementSelectionService,
            TaxLotLedgerService ledger,
            TlhExecutionService executionService,
            AlpacaService alpacaService,
            EncryptionService encryptionService,
            TlhCatalog catalog,
            PlatformTransactionManager transactionManager,
            @Value("${app.tlh.mode:off}") String mode,
            @Value("${app.tlh.max-harvests-per-cycle:25}") int maxHarvestsPerCycle,
            @Value("${app.tlh.max-users-per-cycle:200}") int maxUsersPerCycle,
            @Value("${app.tlh.inter-user-delay-ms:250}") long interUserDelayMs,
            @Value("${app.tlh.consecutive-failure-halt:5}") int consecutiveFailureHalt) {
        this.userRepository = userRepository;
        this.eventRepository = eventRepository;
        this.userStateRepository = userStateRepository;
        this.cycleRunRepository = cycleRunRepository;
        this.windowRepository = windowRepository;
        this.taxLotRepository = taxLotRepository;
        this.guard = guard;
        this.eligibilityService = eligibilityService;
        this.replacementSelectionService = replacementSelectionService;
        this.ledger = ledger;
        this.executionService = executionService;
        this.alpacaService = alpacaService;
        this.encryptionService = encryptionService;
        this.catalog = catalog;
        this.claimTx = new TransactionTemplate(transactionManager);
        this.claimTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.mode = mode;
        this.maxHarvestsPerCycle = maxHarvestsPerCycle;
        this.maxUsersPerCycle = maxUsersPerCycle;
        this.interUserDelayMs = interUserDelayMs;
        this.consecutiveFailureHalt = consecutiveFailureHalt;
    }

    public record UserCycleResult(int candidates, int harvested, int vetoed, boolean skipped) {
    }

    // ==================== Daily cycle ====================

    public void runCycle(LocalDate today, String trigger) {
        if ("off".equalsIgnoreCase(mode)) {
            return;
        }
        // Fail closed: holidays, half-days, and clock outages all skip the
        // cycle — a market order must never rest overnight.
        if (!alpacaService.getMarketClock()) {
            logger.info("[TLH] Market closed (or clock unavailable) — skipping cycle for {}", today);
            return;
        }

        TlhCycleRun run = new TlhCycleRun(today, mode, trigger);
        cycleRunRepository.save(run);

        List<User> eligible = userRepository.findBySelectedTierInAndTlhEnabledTrue(PRO_TIERS).stream()
                .filter(user -> user.getAlpacaAccountId() != null)
                .filter(user -> "ACTIVE".equalsIgnoreCase(user.getAccountStatus()))
                .limit(maxUsersPerCycle)
                .toList();

        int harvested = 0, vetoed = 0, skipped = 0, failed = 0, candidates = 0, consecutiveFailures = 0;
        for (User user : eligible) {
            if (harvested >= maxHarvestsPerCycle) {
                logger.info("[TLH] Global harvest cap {} reached — stopping cycle", maxHarvestsPerCycle);
                break;
            }
            try {
                UserCycleResult result = runCycleForUser(user, today, trigger);
                harvested += result.harvested();
                vetoed += result.vetoed();
                candidates += result.candidates();
                if (result.skipped()) {
                    skipped++;
                }
                consecutiveFailures = 0;
            } catch (Exception e) {
                failed++;
                consecutiveFailures++;
                logger.error("[TLH] Cycle failed for user {}: {}", user.getId(), e.getMessage(), e);
                if (consecutiveFailures >= consecutiveFailureHalt) {
                    logger.error("TLH_ALERT cycle halted after {} consecutive user failures", consecutiveFailures);
                    run.setNotes("HALTED after " + consecutiveFailures + " consecutive failures");
                    break;
                }
            }
            pace();
        }

        run.setUsersEvaluated(eligible.size());
        run.setCandidates(candidates);
        run.setHarvested(harvested);
        run.setVetoed(vetoed);
        run.setSkipped(skipped);
        run.setFailed(failed);
        run.setFinishedAt(LocalDateTime.now(ET));
        cycleRunRepository.save(run);
        logger.info("[TLH] Cycle {} ({}): {} users, {} candidates, {} harvested, {} vetoed, {} skipped, {} failed",
                today, trigger, eligible.size(), candidates, harvested, vetoed, skipped, failed);
    }

    private void pace() {
        if (interUserDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(interUserDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public UserCycleResult runCycleForUser(User user, LocalDate today, String trigger) {
        // One live chain per user at a time keeps cash accounting exact
        if (eventRepository.existsByUserIdAndStatusIn(user.getId(), TlhHarvestEvent.IN_FLIGHT_STATUSES)) {
            return new UserCycleResult(0, 0, 0, true);
        }

        TlhUserState state = userStateRepository.findByUserId(user.getId())
                .orElseGet(() -> userStateRepository.save(new TlhUserState(user.getId())));
        if (state.getLedgerStatus() == TlhUserState.LedgerStatus.BACKFILLING) {
            return new UserCycleResult(0, 0, 0, true); // poller converges the backfill
        }

        String accountId = encryptionService.decrypt(user.getAlpacaAccountId());

        // Incremental ledger sync — NEVER harvest on a truncated scan
        if (!syncUserFills(user, state, accountId, SYNC_PAGES_SAFETY_VALVE)) {
            logger.warn("[TLH] Fill sync truncated for user {} — skipping this cycle", user.getId());
            return new UserCycleResult(0, 0, 0, true);
        }

        List<PositionSnapshot> positions = fetchPositions(accountId);
        if (positions == null) {
            throw new IllegalStateException("Positions unavailable");
        }
        if (positions.isEmpty()) {
            return new UserCycleResult(0, 0, 0, true);
        }

        Set<String> unreconciled = new HashSet<>(ledger.reconcile(user.getId(), positions)
                .unreconciledSymbols());
        unreconciled.addAll(taxLotRepository.findUnreconciledSymbols(user.getId()));

        Map<String, SalePreview> previews = new HashMap<>();
        for (PositionSnapshot position : positions) {
            previews.put(position.symbol().toUpperCase(), ledger.previewFifoSale(
                    user.getId(), position.symbol(), position.currentPrice(),
                    catalog.effectiveBufferPercent()));
        }

        EvalResult evaluation = eligibilityService.evaluate(
                user, true, positions, previews, unreconciled, today);
        List<Veto> vetoes = new ArrayList<>(evaluation.vetoes());
        List<HarvestCandidate> candidates = new ArrayList<>(evaluation.candidates());

        // Asset tradability is verified only for survivors (one HTTP call each)
        Iterator<HarvestCandidate> candidateIterator = candidates.iterator();
        while (candidateIterator.hasNext()) {
            HarvestCandidate candidate = candidateIterator.next();
            AlpacaService.AssetInfo asset = alpacaService.getAssetInfo(candidate.symbol());
            if (!asset.exists || !asset.active || !asset.tradable) {
                vetoes.add(new Veto(candidate.symbol(), TlhEligibilityService.VETO_ASSET_INACTIVE));
                candidateIterator.remove();
            }
        }

        // Same-run conflict resolution: nothing may be harvested and bought in
        // one plan, directly or via identity siblings.
        Set<String> plannedSymbols = new HashSet<>();
        for (HarvestCandidate candidate : candidates) {
            plannedSymbols.add(candidate.symbol());
        }
        List<PlannedHarvest> plan = new ArrayList<>();
        Set<String> chosenReplacements = new HashSet<>();
        for (HarvestCandidate candidate : candidates) {
            if (chosenReplacements.contains(candidate.symbol())) {
                vetoes.add(new Veto(candidate.symbol(), "SAME_CYCLE_CONFLICT"));
                continue;
            }
            ReplacementSelectionService.ReplacementDecision decision =
                    replacementSelectionService.select(user.getId(), candidate.symbol(), today, plannedSymbols);
            if (decision.kind() == ReplacementSelectionService.DecisionKind.SKIP_HARVEST) {
                vetoes.add(new Veto(candidate.symbol(), "NO_REPLACEMENT"));
                continue;
            }
            if (decision.symbol() != null) {
                chosenReplacements.add(decision.symbol());
                plannedSymbols.add(decision.symbol());
            }
            plan.add(new PlannedHarvest(candidate, decision,
                    positionFor(positions, candidate.symbol())));
        }

        persistVetoes(user, vetoes, today);

        int harvestedCount = 0;
        for (PlannedHarvest planned : plan) {
            TlhHarvestEvent event = claimHarvest(user, planned, today);
            if (event == null) {
                continue; // already claimed today (idempotent re-run)
            }
            if (event.getMode() == TlhHarvestEvent.HarvestMode.LIVE) {
                executionService.submitSell(event, user);
            }
            harvestedCount++;
        }
        return new UserCycleResult(plan.size(), harvestedCount, vetoes.size(), false);
    }

    private record PlannedHarvest(HarvestCandidate candidate,
            ReplacementSelectionService.ReplacementDecision decision, PositionSnapshot position) {
    }

    private PositionSnapshot positionFor(List<PositionSnapshot> positions, String symbol) {
        for (PositionSnapshot position : positions) {
            if (position.symbol().equalsIgnoreCase(symbol)) {
                return position;
            }
        }
        return null;
    }

    /**
     * The idempotent claim: event + provisional window committed atomically
     * BEFORE any order exists. Dry-run claims persist a PROPOSED event and NO
     * window — dry-run must never gate a real user's buys.
     */
    private TlhHarvestEvent claimHarvest(User user, PlannedHarvest planned, LocalDate today) {
        boolean dryRun = "dry-run".equalsIgnoreCase(mode);
        try {
            return claimTx.execute(status -> {
                TlhHarvestEvent event = new TlhHarvestEvent();
                event.setUserId(user.getId());
                event.setAlpacaAccountId(user.getAlpacaAccountId()); // encrypted snapshot as stored
                event.setSymbol(planned.candidate().symbol());
                event.setReplacementSymbol(planned.decision().symbol());
                event.setReplacementPolicy(planned.decision().policy());
                event.setMode(dryRun ? TlhHarvestEvent.HarvestMode.DRY_RUN : TlhHarvestEvent.HarvestMode.LIVE);
                event.setStatus(dryRun ? TlhHarvestEvent.EventStatus.PROPOSED
                        : TlhHarvestEvent.EventStatus.SELL_PENDING);
                event.setCycleDate(today);
                event.setQuantityTargeted(planned.candidate().qty());
                event.setEstCostBasis(planned.position() == null ? null : planned.position().costBasis());
                event.setEstimatedLoss(planned.candidate().estLoss());
                event.setBasisEstimated(planned.candidate().preview() != null
                        && planned.candidate().preview().basisEstimated());
                event.setSellClientOrderId("tlh-s-" + UUID.randomUUID());
                event = eventRepository.save(event);
                if (!dryRun) {
                    guard.openProvisionalWindow(event, today, planned.decision().symbol());
                }
                return event;
            });
        } catch (DataIntegrityViolationException e) {
            logger.info("[TLH] Claim for user {} {} on {} already exists — skipping",
                    user.getId(), planned.candidate().symbol(), today);
            return null;
        }
    }

    private void persistVetoes(User user, List<Veto> vetoes, LocalDate today) {
        boolean dryRun = "dry-run".equalsIgnoreCase(mode);
        LocalDateTime dedupCutoff = LocalDateTime.now(ET).minusDays(7);
        for (Veto veto : vetoes) {
            try {
                if (eventRepository.existsByUserIdAndSymbolAndVetoReasonAndCreatedAtAfter(
                        user.getId(), veto.symbol(), veto.reason(), dedupCutoff)) {
                    continue;
                }
                claimTx.executeWithoutResult(status -> {
                    TlhHarvestEvent event = new TlhHarvestEvent();
                    event.setUserId(user.getId());
                    event.setAlpacaAccountId(user.getAlpacaAccountId());
                    event.setSymbol(veto.symbol());
                    event.setMode(dryRun ? TlhHarvestEvent.HarvestMode.DRY_RUN
                            : TlhHarvestEvent.HarvestMode.LIVE);
                    event.setStatus(TlhHarvestEvent.EventStatus.VETOED);
                    event.setVetoReason(veto.reason());
                    event.setCycleDate(today);
                    eventRepository.save(event);
                });
            } catch (DataIntegrityViolationException e) {
                // (user, symbol, cycleDate, mode) already recorded today — fine
            }
        }
    }

    // ==================== Ledger sync ====================

    /**
     * Incremental fill ingestion from the per-user cursor. Returns false when
     * the page valve was hit (scan truncated) — the caller must NOT harvest.
     */
    private boolean syncUserFills(User user, TlhUserState state, String accountId, int maxPages) {
        // Small overlap on the cursor: the activities `after` filter is
        // timestamp-based, and same-instant fills must not be lost. Dedup by
        // activity id makes re-ingestion free.
        String after = overlapCursor(state.getCursorAfter());
        AlpacaService.FillActivityPage page =
                alpacaService.getFillActivities(accountId, after, null, maxPages);
        for (AlpacaService.FillActivity fill : page.activities) {
            executionService.applyIngestedFill(user.getId(), fill);
        }
        if (!page.activities.isEmpty()) {
            AlpacaService.FillActivity last = page.activities.get(page.activities.size() - 1);
            state.setCursorAfter(last.transactionTime);
        }
        state.setLastSyncAt(LocalDateTime.now(ET));
        userStateRepository.save(state);
        return page.exhausted;
    }

    private String overlapCursor(String cursorAfter) {
        if (cursorAfter == null || cursorAfter.isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(cursorAfter).minusHours(24).toString();
        } catch (Exception e) {
            return cursorAfter;
        }
    }

    // ==================== Positions ====================

    /** Parse the raw Alpaca positions JSON; null on failure (fail the user's cycle). */
    private List<PositionSnapshot> fetchPositions(String accountId) {
        String raw = alpacaService.getCurrentPositions(accountId);
        if (raw == null) {
            return null;
        }
        try {
            JsonNode array = objectMapper.readTree(raw);
            if (!array.isArray()) {
                return null;
            }
            List<PositionSnapshot> positions = new ArrayList<>();
            for (JsonNode position : array) {
                BigDecimal qty = parseDecimalSafely(position, "qty");
                if (qty == null || qty.compareTo(DUST_QTY) < 0) {
                    continue;
                }
                positions.add(new PositionSnapshot(
                        position.path("symbol").asText(""),
                        qty,
                        parseDecimalSafely(position, "qty_available"),
                        parseDecimalSafely(position, "market_value"),
                        parseDecimalSafely(position, "cost_basis"),
                        parseDecimalSafely(position, "unrealized_pl"),
                        parseDecimalSafely(position, "current_price"),
                        true));
            }
            return positions;
        } catch (Exception e) {
            logger.error("[TLH] Failed to parse positions for account {}: {}", accountId, e.getMessage());
            return null;
        }
    }

    private BigDecimal parseDecimalSafely(JsonNode node, String field) {
        try {
            if (!node.hasNonNull(field) || "null".equals(node.get(field).asText())) {
                return null;
            }
            return new BigDecimal(node.get(field).asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== Nightly reconciliation ====================

    /**
     * Nightly scope is the UNION of enrolled users, users with active
     * windows, and users with open work — never just enrolled users, so a
     * downgraded user's windows keep their disallowance accounting.
     */
    public void reconcileNightly(LocalDate today) {
        Set<Long> userIds = new LinkedHashSet<>();
        for (User user : userRepository.findBySelectedTierInAndTlhEnabledTrue(PRO_TIERS)) {
            userIds.add(user.getId());
        }
        userIds.addAll(windowRepository.findUserIdsWithActiveWindows());
        userIds.addAll(eventRepository.findUserIdsWithOpenWork(TlhHarvestEvent.IN_FLIGHT_STATUSES));

        int processed = 0, violations = 0, failures = 0;
        for (Long userId : userIds) {
            try {
                User user = userRepository.findById(userId).orElse(null);
                if (user == null || user.getAlpacaAccountId() == null) {
                    continue;
                }
                TlhUserState state = userStateRepository.findByUserId(userId)
                        .orElseGet(() -> userStateRepository.save(new TlhUserState(userId)));
                if (state.getLedgerStatus() == TlhUserState.LedgerStatus.BACKFILLING) {
                    continue; // the poller owns backfill
                }
                String accountId = encryptionService.decrypt(user.getAlpacaAccountId());
                syncUserFills(user, state, accountId, SYNC_PAGES_SAFETY_VALVE);
                violations += detectWashViolations(userId);

                List<PositionSnapshot> positions = fetchPositions(accountId);
                if (positions != null) {
                    ledger.reconcile(userId, positions);
                    rebaselineStale(user, state, positions);
                }
                processed++;
            } catch (Exception e) {
                failures++;
                logger.error("[TLH] Nightly reconcile failed for user {}: {}", userId, e.getMessage());
            }
        }

        guard.expireWindows(today);
        expireStaleDryRuns();
        logger.info("[TLH] Nightly reconcile: {} users processed, {} wash violations accounted, {} failures",
                processed, violations, failures);
    }

    /**
     * Any buy fill of a windowed symbol (or identity sibling) from ANY source
     * — user lump sum, ACATS, a future unguarded feature — shrinks the
     * harvest's allowedLoss via §1091 matching. The summary only ever
     * converges DOWNWARD toward 1099 truth.
     */
    private int detectWashViolations(Long userId) {
        int adjusted = 0;
        for (WashSaleWindow window : windowRepository.findByUserIdAndActiveTrue(userId)) {
            if (window.getHarvestEventId() == null) {
                continue;
            }
            TlhHarvestEvent event = eventRepository.findById(window.getHarvestEventId()).orElse(null);
            if (event == null || event.getRealizedLoss() == null
                    || event.getRealizedLoss().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            TaxLotLedgerService.DisallowanceResult result = ledger.applyWashDisallowance(
                    event, catalog.expandWithSiblings(window.getSymbol()));
            BigDecimal newAllowed = event.getRealizedLoss().subtract(result.totalDisallowed())
                    .max(BigDecimal.ZERO);
            if (event.getDisallowedLoss().compareTo(result.totalDisallowed()) != 0) {
                event.setDisallowedLoss(result.totalDisallowed());
                event.setAllowedLoss(newAllowed);
                eventRepository.save(event);
                if (result.totalDisallowed().compareTo(BigDecimal.ZERO) > 0) {
                    adjusted++;
                    logger.warn("TLH_ALERT wash-violation accounted for event {} ({}): disallowed {}",
                            event.getId(), event.getSymbol(), result.totalDisallowed());
                }
            }
        }
        return adjusted;
    }

    /** Corporate actions leave no FILL activity — re-baseline after 7 stale days. */
    private void rebaselineStale(User user, TlhUserState state, List<PositionSnapshot> positions) {
        Map<String, PositionSnapshot> bySymbol = new HashMap<>();
        for (PositionSnapshot position : positions) {
            bySymbol.put(position.symbol().toUpperCase(), position);
        }
        LocalDateTime staleCutoff = LocalDateTime.now(ET).minusDays(REBASELINE_AFTER_DAYS);
        Set<String> staleSymbols = new HashSet<>();
        for (TaxLot lot : taxLotRepository.findByUserIdAndStatus(user.getId(), TaxLot.LotStatus.UNRECONCILED)) {
            if (lot.getUpdatedAt() != null && lot.getUpdatedAt().isBefore(staleCutoff)) {
                staleSymbols.add(lot.getSymbol());
            }
        }
        for (String symbol : staleSymbols) {
            PositionSnapshot position = bySymbol.get(symbol);
            if (position == null) {
                continue; // position gone; lots stay closed-out via reconcile
            }
            if (guard.hasPendingBuyActivity(user.getId(), user, symbol)) {
                continue;
            }
            ledger.rebaselineSymbol(user.getId(), symbol, position);
            if (state.getLedgerStatus() == TlhUserState.LedgerStatus.READY) {
                state.setLedgerStatus(TlhUserState.LedgerStatus.DEGRADED);
                userStateRepository.save(state);
            }
        }
    }

    private void expireStaleDryRuns() {
        LocalDateTime cutoff = LocalDateTime.now(ET).minusDays(DRY_RUN_EXPIRY_DAYS);
        for (TlhHarvestEvent event : eventRepository.findByStatusAndModeAndCreatedAtBefore(
                TlhHarvestEvent.EventStatus.PROPOSED, TlhHarvestEvent.HarvestMode.DRY_RUN, cutoff)) {
            event.setStatus(TlhHarvestEvent.EventStatus.EXPIRED);
            eventRepository.save(event);
        }
    }

    // ==================== Preview (read-only) ====================

    /** Candidate/veto preview with NO persistence and NO orders; 60s TTL cache. */
    public TlhPreviewResponse previewForUser(User user) {
        CachedPreview cached = previewCache.get(user.getId());
        if (cached != null && System.currentTimeMillis() - cached.cachedAtMs() < PREVIEW_CACHE_TTL_MS) {
            return cached.response();
        }

        TlhPreviewResponse response = buildPreview(user);
        previewCache.put(user.getId(), new CachedPreview(response, System.currentTimeMillis()));
        return response;
    }

    private TlhPreviewResponse buildPreview(User user) {
        String ledgerStatus = userStateRepository.findByUserId(user.getId())
                .map(state -> state.getLedgerStatus().name())
                .orElse("NONE");
        if (!"READY".equals(ledgerStatus) && !"DEGRADED".equals(ledgerStatus)) {
            return new TlhPreviewResponse(ledgerStatus, List.of(), List.of(
                    new TlhPreviewResponse.VetoDto("*", TlhEligibilityService.VETO_LEDGER_NOT_READY)));
        }
        if (user.getAlpacaAccountId() == null) {
            return new TlhPreviewResponse(ledgerStatus, List.of(), List.of(
                    new TlhPreviewResponse.VetoDto("*", "NO_BROKERAGE_ACCOUNT")));
        }

        String accountId = encryptionService.decrypt(user.getAlpacaAccountId());
        List<PositionSnapshot> positions = fetchPositions(accountId);
        if (positions == null) {
            return new TlhPreviewResponse(ledgerStatus, List.of(), List.of(
                    new TlhPreviewResponse.VetoDto("*", "POSITIONS_UNAVAILABLE")));
        }

        Set<String> unreconciled = new HashSet<>(taxLotRepository.findUnreconciledSymbols(user.getId()));
        Map<String, SalePreview> previews = new HashMap<>();
        for (PositionSnapshot position : positions) {
            previews.put(position.symbol().toUpperCase(), ledger.previewFifoSale(
                    user.getId(), position.symbol(), position.currentPrice(),
                    catalog.effectiveBufferPercent()));
        }

        LocalDate today = LocalDate.now(ET);
        EvalResult evaluation = eligibilityService.evaluate(
                user, true, positions, previews, unreconciled, today);

        List<TlhPreviewResponse.CandidateDto> candidateDtos = new ArrayList<>();
        Set<String> plannedSymbols = new HashSet<>();
        for (HarvestCandidate candidate : evaluation.candidates()) {
            plannedSymbols.add(candidate.symbol());
        }
        List<TlhPreviewResponse.VetoDto> vetoDtos = new ArrayList<>();
        for (Veto veto : evaluation.vetoes()) {
            vetoDtos.add(new TlhPreviewResponse.VetoDto(veto.symbol(), veto.reason()));
        }
        for (HarvestCandidate candidate : evaluation.candidates()) {
            ReplacementSelectionService.ReplacementDecision decision =
                    replacementSelectionService.select(user.getId(), candidate.symbol(), today, plannedSymbols);
            if (decision.kind() == ReplacementSelectionService.DecisionKind.SKIP_HARVEST) {
                vetoDtos.add(new TlhPreviewResponse.VetoDto(candidate.symbol(), "NO_REPLACEMENT"));
                continue;
            }
            candidateDtos.add(new TlhPreviewResponse.CandidateDto(
                    candidate.symbol(), candidate.qty(), candidate.estLoss(), candidate.estLossPct(),
                    decision.symbol(), decision.policy().name()));
        }
        return new TlhPreviewResponse(ledgerStatus, candidateDtos, vetoDtos);
    }
}
