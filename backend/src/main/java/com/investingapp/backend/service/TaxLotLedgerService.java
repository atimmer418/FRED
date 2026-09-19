package com.investingapp.backend.service;

import com.investingapp.backend.model.LotDisposal;
import com.investingapp.backend.model.TaxLot;
import com.investingapp.backend.model.TlhHarvestEvent;
import com.investingapp.backend.repository.LotDisposalRepository;
import com.investingapp.backend.repository.TaxLotRepository;
import com.investingapp.backend.repository.TlhHarvestEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The TLH correctness core: a per-share FIFO lot ledger reconstructed from
 * Alpaca FILL activity. Pure of Alpaca HTTP — fills are passed in.
 *
 * SINGLE SOURCE OF TRUTH RULE: TLH's own order fills are applied by the
 * poller as ONE synthetic fill per order (activityId = null, orderId set).
 * Activity ingestion (cycle sync + nightly) applies everything else and
 * dedups BOTH ways: by activity id, and by order id against rows the poller
 * already wrote. Without the order-id dedup, every live harvest would be
 * double-disposed the first night after it fills.
 *
 * Symbols whose ledger disagrees with the live Alpaca position go
 * UNRECONCILED and are never harvested (correct-by-refusal). Persistent
 * mismatches (corporate actions have no FILL activity) are re-baselined to a
 * single position-level REBASED lot with basisEstimated accounting.
 */
@Service
public class TaxLotLedgerService {

    private static final Logger logger = LoggerFactory.getLogger(TaxLotLedgerService.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final BigDecimal QTY_TOLERANCE = new BigDecimal("0.000001");
    private static final int WASH_MATCH_WINDOW_DAYS = 30;

    private final TaxLotRepository taxLotRepository;
    private final LotDisposalRepository lotDisposalRepository;
    private final TlhHarvestEventRepository harvestEventRepository;

    /** Per-user serialization of ledger mutations (single-JVM, like every FRED scheduler). */
    private final ConcurrentHashMap<Long, ReentrantLock> userLocks = new ConcurrentHashMap<>();

    public TaxLotLedgerService(TaxLotRepository taxLotRepository,
            LotDisposalRepository lotDisposalRepository,
            TlhHarvestEventRepository harvestEventRepository) {
        this.taxLotRepository = taxLotRepository;
        this.lotDisposalRepository = lotDisposalRepository;
        this.harvestEventRepository = harvestEventRepository;
    }

    /** One fill to apply. activityId null ⇒ synthetic (poller-applied TLH order). */
    public record FillRecord(String activityId, String orderId, String side, String symbol,
            BigDecimal qty, BigDecimal pricePerShare, LocalDateTime transactionTime,
            TaxLot.LotSource source) {
    }

    public record PositionSnapshot(String symbol, BigDecimal qty, BigDecimal qtyAvailable,
            BigDecimal marketValue, BigDecimal costBasis, BigDecimal unrealizedPl,
            BigDecimal currentPrice, boolean assetActiveTradable) {
    }

    public record SalePreview(BigDecimal totalQty, BigDecimal estRealizedPnl,
            boolean allLotsUnderwater, boolean basisEstimated, boolean hasOpenLots) {
    }

    public record ReconcileResult(List<String> unreconciledSymbols) {
    }

    public record DisallowanceResult(BigDecimal totalDisallowed) {
    }

    private ReentrantLock lockFor(Long userId) {
        return userLocks.computeIfAbsent(userId, id -> new ReentrantLock());
    }

    /**
     * Apply one fill to the ledger. Idempotent: duplicate activity ids and
     * order ids already applied (synthetically or otherwise) are no-ops.
     */
    @Transactional
    public void applyFill(Long userId, FillRecord fill) {
        ReentrantLock lock = lockFor(userId);
        lock.lock();
        try {
            if (isDuplicate(userId, fill)) {
                return;
            }
            boolean isBuy = "buy".equalsIgnoreCase(fill.side());
            if (isBuy) {
                applyBuy(userId, fill);
            } else {
                applySell(userId, fill);
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean isDuplicate(Long userId, FillRecord fill) {
        if (fill.activityId() != null) {
            if (taxLotRepository.existsBySourceActivityId(fill.activityId())
                    || lotDisposalRepository.existsBySellActivityId(fill.activityId())) {
                return true;
            }
            // The poller already applied this order synthetically (activityId-less rows)
            if (fill.orderId() != null
                    && (taxLotRepository.existsByUserIdAndSourceOrderId(userId, fill.orderId())
                            || lotDisposalRepository.existsByUserIdAndSellOrderId(userId, fill.orderId()))) {
                return true;
            }
        } else if (fill.orderId() != null) {
            // Synthetic re-application guard (poller retry)
            if (taxLotRepository.existsByUserIdAndSourceOrderId(userId, fill.orderId())
                    || lotDisposalRepository.existsByUserIdAndSellOrderId(userId, fill.orderId())) {
                return true;
            }
        }
        return false;
    }

    private void applyBuy(Long userId, FillRecord fill) {
        TaxLot lot = new TaxLot();
        lot.setUserId(userId);
        lot.setSymbol(fill.symbol().toUpperCase());
        lot.setOriginalQuantity(fill.qty());
        lot.setRemainingQuantity(fill.qty());
        lot.setCostPerShare(fill.pricePerShare().setScale(6, RoundingMode.HALF_UP));
        lot.setAcquiredAt(fill.transactionTime());
        lot.setSource(fill.source());
        lot.setSourceActivityId(fill.activityId());
        lot.setSourceOrderId(fill.orderId());
        lot.setStatus(TaxLot.LotStatus.OPEN);
        taxLotRepository.save(lot);
    }

    private void applySell(Long userId, FillRecord fill) {
        String symbol = fill.symbol().toUpperCase();
        Long harvestEventId = fill.orderId() == null ? null
                : harvestEventRepository.findBySellOrderId(fill.orderId())
                        .map(TlhHarvestEvent::getId).orElse(null);

        List<TaxLot> fifoQueue = taxLotRepository
                .findByUserIdAndSymbolAndStatusOrderByAcquiredAtAsc(userId, symbol, TaxLot.LotStatus.OPEN);
        BigDecimal remainingToSell = fill.qty();

        for (TaxLot lot : fifoQueue) {
            if (remainingToSell.compareTo(QTY_TOLERANCE) < 0) {
                break;
            }
            BigDecimal relieved = lot.getRemainingQuantity().min(remainingToSell);
            if (relieved.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            LotDisposal disposal = new LotDisposal();
            disposal.setUserId(userId);
            disposal.setTaxLotId(lot.getId());
            disposal.setSymbol(symbol);
            disposal.setQuantity(relieved);
            disposal.setProceedsPerShare(fill.pricePerShare().setScale(6, RoundingMode.HALF_UP));
            disposal.setRealizedPnl(fill.pricePerShare().subtract(lot.effectiveCostPerShare())
                    .multiply(relieved).setScale(2, RoundingMode.HALF_UP));
            disposal.setDisposedAt(fill.transactionTime());
            disposal.setSellOrderId(fill.orderId());
            disposal.setSellActivityId(fill.activityId());
            disposal.setHarvestEventId(harvestEventId);
            lotDisposalRepository.save(disposal);

            lot.setRemainingQuantity(lot.getRemainingQuantity().subtract(relieved));
            if (lot.getRemainingQuantity().compareTo(QTY_TOLERANCE) < 0) {
                lot.setRemainingQuantity(BigDecimal.ZERO);
                lot.setStatus(TaxLot.LotStatus.CLOSED);
            }
            taxLotRepository.save(lot);
            remainingToSell = remainingToSell.subtract(relieved);
        }

        if (remainingToSell.compareTo(QTY_TOLERANCE) >= 0) {
            // Sold more than the ledger holds — basis is unknowable for this
            // symbol until repaired. Correct-by-refusal.
            logger.error("[TLH] Oversell in ledger for user {} {}: {} unmatched — marking UNRECONCILED",
                    userId, symbol, remainingToSell);
            markSymbolUnreconciled(userId, symbol);
        }
    }

    /**
     * Poller path: apply TLH's own order fill as one synthetic fill
     * (activityId null, order id set — nightly ingestion dedups against it).
     */
    @Transactional
    public void applySyntheticOrderFill(Long userId, String orderId, String side, String symbol,
            BigDecimal filledQty, BigDecimal avgPrice, LocalDateTime filledAt, TaxLot.LotSource source) {
        applyFill(userId, new FillRecord(null, orderId, side, symbol, filledQty, avgPrice, filledAt, source));
    }

    /**
     * Compare ledger totals against live Alpaca positions. Any symbol whose
     * quantity or basis drifts beyond tolerance is marked UNRECONCILED.
     */
    @Transactional
    public ReconcileResult reconcile(Long userId, List<PositionSnapshot> positions) {
        ReentrantLock lock = lockFor(userId);
        lock.lock();
        try {
            List<String> unreconciled = new ArrayList<>();
            Map<String, PositionSnapshot> bySymbol = new HashMap<>();
            for (PositionSnapshot p : positions) {
                bySymbol.put(p.symbol().toUpperCase(), p);
            }

            Map<String, List<TaxLot>> openLots = new HashMap<>();
            for (TaxLot lot : taxLotRepository.findByUserIdAndStatus(userId, TaxLot.LotStatus.OPEN)) {
                openLots.computeIfAbsent(lot.getSymbol(), s -> new ArrayList<>()).add(lot);
            }

            Set<String> allSymbols = new HashSet<>();
            allSymbols.addAll(bySymbol.keySet());
            allSymbols.addAll(openLots.keySet());

            for (String symbol : allSymbols) {
                PositionSnapshot position = bySymbol.get(symbol);
                List<TaxLot> lots = openLots.getOrDefault(symbol, List.of());

                BigDecimal ledgerQty = lots.stream().map(TaxLot::getRemainingQuantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal alpacaQty = position == null ? BigDecimal.ZERO : position.qty();

                boolean qtyMismatch = ledgerQty.subtract(alpacaQty).abs().compareTo(QTY_TOLERANCE) > 0;
                boolean basisMismatch = false;
                if (!qtyMismatch && position != null && position.costBasis() != null
                        && position.costBasis().compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal ledgerBasis = lots.stream()
                            .map(l -> l.effectiveCostPerShare().multiply(l.getRemainingQuantity()))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal tolerance = new BigDecimal("5.00").max(
                            position.costBasis().multiply(new BigDecimal("0.005")));
                    basisMismatch = ledgerBasis.subtract(position.costBasis()).abs().compareTo(tolerance) > 0;
                }

                if (qtyMismatch || basisMismatch) {
                    // Only flag symbols with actual ledger or position presence
                    if (!lots.isEmpty() || (position != null && alpacaQty.compareTo(QTY_TOLERANCE) > 0)) {
                        logger.warn("[TLH] Ledger mismatch for user {} {}: ledgerQty={} alpacaQty={} basisMismatch={}",
                                userId, symbol, ledgerQty, alpacaQty, basisMismatch);
                        markSymbolUnreconciled(userId, symbol);
                        unreconciled.add(symbol);
                    }
                }
            }
            return new ReconcileResult(unreconciled);
        } finally {
            lock.unlock();
        }
    }

    private void markSymbolUnreconciled(Long userId, String symbol) {
        // Mark EVERY lot of the symbol, closed ones included: an oversell can
        // close the last open lot in the same pass, and the UNRECONCILED flag
        // must survive somewhere durable for findUnreconciledSymbols and the
        // 7-day re-baseline sweep.
        for (TaxLot lot : taxLotRepository.findByUserIdAndSymbol(userId, symbol)) {
            if (lot.getStatus() != TaxLot.LotStatus.UNRECONCILED) {
                lot.setStatus(TaxLot.LotStatus.UNRECONCILED);
                taxLotRepository.save(lot);
            }
        }
    }

    /**
     * Collapse a persistently UNRECONCILED symbol (corporate action, unknown
     * external history) to one position-level REBASED lot. Harvests of a
     * REBASED symbol run with basisEstimated=true.
     */
    @Transactional
    public void rebaselineSymbol(Long userId, String symbol, PositionSnapshot position) {
        ReentrantLock lock = lockFor(userId);
        lock.lock();
        try {
            if (position.qty() == null || position.qty().compareTo(QTY_TOLERANCE) <= 0
                    || position.costBasis() == null || position.costBasis().compareTo(BigDecimal.ZERO) <= 0) {
                logger.warn("[TLH] Cannot re-baseline {} for user {}: empty or basis-less position", symbol, userId);
                return;
            }
            for (TaxLot lot : taxLotRepository.findByUserIdAndSymbol(userId, symbol)) {
                if (lot.getStatus() != TaxLot.LotStatus.CLOSED) {
                    lot.setStatus(TaxLot.LotStatus.CLOSED);
                    lot.setRemainingQuantity(BigDecimal.ZERO);
                    taxLotRepository.save(lot);
                }
            }
            TaxLot rebased = new TaxLot();
            rebased.setUserId(userId);
            rebased.setSymbol(symbol.toUpperCase());
            rebased.setOriginalQuantity(position.qty());
            rebased.setRemainingQuantity(position.qty());
            rebased.setCostPerShare(position.costBasis()
                    .divide(position.qty(), 6, RoundingMode.HALF_UP));
            rebased.setAcquiredAt(LocalDateTime.now(ET));
            rebased.setSource(TaxLot.LotSource.REBASED);
            rebased.setStatus(TaxLot.LotStatus.OPEN);
            taxLotRepository.save(rebased);
            logger.warn("[TLH] Re-baselined {} for user {} to one REBASED lot (qty {}, basis/share {})",
                    symbol, userId, position.qty(), rebased.getCostPerShare());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Eligibility input: would a full-position FIFO sale at this price realize
     * ONLY losses? Every open lot must be underwater after the price buffer —
     * this is what makes FIFO relief order irrelevant and designs out the
     * partial-sell gain trap.
     */
    public SalePreview previewFifoSale(Long userId, String symbol, BigDecimal currentPrice,
            BigDecimal bufferPercent) {
        List<TaxLot> lots = taxLotRepository.findByUserIdAndSymbolAndStatusOrderByAcquiredAtAsc(
                userId, symbol.toUpperCase(), TaxLot.LotStatus.OPEN);
        if (lots.isEmpty()) {
            return new SalePreview(BigDecimal.ZERO, BigDecimal.ZERO, false, false, false);
        }
        BigDecimal bufferFactor = BigDecimal.ONE.subtract(
                bufferPercent.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal estPnl = BigDecimal.ZERO;
        boolean allUnderwater = true;
        boolean basisEstimated = false;
        for (TaxLot lot : lots) {
            totalQty = totalQty.add(lot.getRemainingQuantity());
            estPnl = estPnl.add(currentPrice.subtract(lot.effectiveCostPerShare())
                    .multiply(lot.getRemainingQuantity()));
            if (lot.effectiveCostPerShare().multiply(bufferFactor).compareTo(currentPrice) <= 0) {
                allUnderwater = false;
            }
            if (lot.getSource() == TaxLot.LotSource.REBASED) {
                basisEstimated = true;
            }
        }
        return new SalePreview(totalQty, estPnl.setScale(2, RoundingMode.HALF_UP),
                allUnderwater, basisEstimated, true);
    }

    /**
     * §1091 lot matching for the losses of one harvest event: earliest loss
     * shares are matched to the earliest replacement shares (buys of the
     * symbol or an identity sibling within ±30 days of the sale, excluding
     * the event's own replacement/re-entry legs, which are different
     * securities by construction). Recomputed from scratch each run so
     * nightly re-detection stays idempotent — disallowed amounts are SET,
     * never accumulated.
     */
    @Transactional
    public DisallowanceResult applyWashDisallowance(TlhHarvestEvent event, Set<String> symbolAndSiblings) {
        ReentrantLock lock = lockFor(event.getUserId());
        lock.lock();
        try {
            List<LotDisposal> disposals = lotDisposalRepository.findByHarvestEventId(event.getId());
            List<LotDisposal> lossDisposals = disposals.stream()
                    .filter(d -> d.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0)
                    .sorted(Comparator.comparing(LotDisposal::getId))
                    .toList();
            if (lossDisposals.isEmpty() || event.getSellFilledAt() == null) {
                return new DisallowanceResult(BigDecimal.ZERO);
            }

            LocalDateTime windowStart = event.getSellFilledAt().minusDays(WASH_MATCH_WINDOW_DAYS);
            LocalDateTime windowEnd = event.getSellFilledAt().plusDays(WASH_MATCH_WINDOW_DAYS);

            List<TaxLot> replacementLots = taxLotRepository
                    .findByUserIdAndSymbolInAndStatusAndAcquiredAtAfter(
                            event.getUserId(), symbolAndSiblings, TaxLot.LotStatus.OPEN, windowStart)
                    .stream()
                    .filter(lot -> !lot.getAcquiredAt().isAfter(windowEnd))
                    .filter(lot -> lot.getSourceOrderId() == null
                            || (!lot.getSourceOrderId().equals(event.getReplacementOrderId())
                                    && !lot.getSourceOrderId().equals(event.getReentryOrderId())))
                    .filter(lot -> lot.getSource() != TaxLot.LotSource.TLH_REPLACEMENT
                            && lot.getSource() != TaxLot.LotSource.TLH_REENTRY)
                    .sorted(Comparator.comparing(TaxLot::getAcquiredAt))
                    .toList();

            BigDecimal totalDisallowed = BigDecimal.ZERO;
            Iterator<TaxLot> replacementIterator = replacementLots.iterator();
            TaxLot currentLot = replacementIterator.hasNext() ? replacementIterator.next() : null;
            BigDecimal lotSharesLeft = currentLot == null ? BigDecimal.ZERO : currentLot.getOriginalQuantity();
            Map<Long, BigDecimal> perLotAllocation = new HashMap<>();

            for (LotDisposal disposal : lossDisposals) {
                BigDecimal lossPerShare = disposal.getRealizedPnl().abs()
                        .divide(disposal.getQuantity(), 6, RoundingMode.HALF_UP);
                BigDecimal disposalSharesLeft = disposal.getQuantity();
                BigDecimal disposalDisallowed = BigDecimal.ZERO;
                Long lastMatchedLotId = null;

                while (disposalSharesLeft.compareTo(QTY_TOLERANCE) > 0 && currentLot != null) {
                    BigDecimal matched = disposalSharesLeft.min(lotSharesLeft);
                    if (matched.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal allocated = lossPerShare.multiply(matched);
                        disposalDisallowed = disposalDisallowed.add(allocated);
                        perLotAllocation.merge(currentLot.getId(), allocated, BigDecimal::add);
                        lastMatchedLotId = currentLot.getId();
                        disposalSharesLeft = disposalSharesLeft.subtract(matched);
                        lotSharesLeft = lotSharesLeft.subtract(matched);
                    }
                    if (lotSharesLeft.compareTo(QTY_TOLERANCE) <= 0) {
                        currentLot = replacementIterator.hasNext() ? replacementIterator.next() : null;
                        lotSharesLeft = currentLot == null ? BigDecimal.ZERO : currentLot.getOriginalQuantity();
                    }
                }

                BigDecimal capped = disposalDisallowed.min(disposal.getRealizedPnl().abs())
                        .setScale(2, RoundingMode.HALF_UP);
                disposal.setDisallowedLoss(capped);
                disposal.setWashSale(capped.compareTo(BigDecimal.ZERO) > 0);
                disposal.setReplacementLotId(lastMatchedLotId);
                lotDisposalRepository.save(disposal);
                totalDisallowed = totalDisallowed.add(capped);
            }

            // Bump replacement-lot basis by its allocated share of the
            // disallowed loss (set from base cost — idempotent across re-runs)
            for (Map.Entry<Long, BigDecimal> allocation : perLotAllocation.entrySet()) {
                taxLotRepository.findById(allocation.getKey()).ifPresent(lot -> {
                    BigDecimal spreadQty = lot.getRemainingQuantity().compareTo(QTY_TOLERANCE) > 0
                            ? lot.getRemainingQuantity() : lot.getOriginalQuantity();
                    lot.setWashAdjustedCostPerShare(lot.getCostPerShare()
                            .add(allocation.getValue().divide(spreadQty, 6, RoundingMode.HALF_UP)));
                    lot.setHoldingPeriodTackedFrom(event.getSellFilledAt());
                    taxLotRepository.save(lot);
                });
            }

            return new DisallowanceResult(totalDisallowed.setScale(2, RoundingMode.HALF_UP));
        } finally {
            lock.unlock();
        }
    }
}
