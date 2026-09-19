package com.investingapp.backend.scheduler;

import com.investingapp.backend.service.TlhExecutionService;
import com.investingapp.backend.service.TlhHarvestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Tax Loss Harvesting crons. Thin wrappers only — mode gating (off | dry-run
 * | live) lives inside the services, and every job body is try/caught so a
 * TLH failure can never take down a shared scheduler thread.
 *
 * The minute offsets are LOAD-BEARING: the funding poller fires on :00/:30,
 * the trading poller on :00/:15/:30/:45, and DRIP at 10:00 — TLH runs at
 * :07/:22/:37/:52 (and 13:07 / 16:40 / 22:15) so its harvest sells never race
 * a same-minute contribution buy.
 *
 * Single-instance assumption (in-memory ledger locks, per-JVM pacing), same
 * as every existing FRED scheduler. Kill switch: TLH_MODE=off stops all new
 * orders (restart required — @Value binds at startup); graceful drain: stay
 * live with TLH_MAX_PER_CYCLE=0 so open chains complete and re-entries fire.
 */
@Component
public class TlhScheduler {

    private static final Logger logger = LoggerFactory.getLogger(TlhScheduler.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final TlhHarvestService harvestService;
    private final TlhExecutionService executionService;

    public TlhScheduler(TlhHarvestService harvestService, TlhExecutionService executionService) {
        this.harvestService = harvestService;
        this.executionService = executionService;
    }

    /** 13:07 ET weekdays: the daily harvest cycle (~2.8 market hours left for sell + replace). */
    @Scheduled(cron = "0 7 13 * * MON-FRI", zone = "America/New_York")
    public void runDailyCycleJob() {
        try {
            logger.info("[TLH] Daily cycle starting");
            harvestService.runCycle(LocalDate.now(ET), "SCHEDULED");
            logger.info("[TLH] Daily cycle complete");
        } catch (Exception e) {
            logger.error("[TLH] Daily cycle failed", e);
        }
    }

    /** Every 15 min (offset :07) during market hours: order lifecycle, re-entries, backfill. */
    @Scheduled(cron = "0 7/15 9-16 * * MON-FRI", zone = "America/New_York")
    public void pollJob() {
        try {
            executionService.pollOpenEvents();
        } catch (Exception e) {
            logger.error("[TLH] Poll job failed", e);
        }
    }

    /** 16:40 ET: no TLH order rests overnight — cancel-then-verify at the broker. */
    @Scheduled(cron = "0 40 16 * * MON-FRI", zone = "America/New_York")
    public void eodSweepJob() {
        try {
            executionService.sweepEndOfDay(LocalDate.now(ET));
        } catch (Exception e) {
            logger.error("[TLH] EOD sweep failed", e);
        }
    }

    /** 22:15 ET nightly: fill ingestion, wash-violation accounting, reconcile, window expiry. */
    @Scheduled(cron = "0 15 22 * * *", zone = "America/New_York")
    public void nightlyReconcileJob() {
        try {
            logger.info("[TLH] Nightly reconciliation starting");
            harvestService.reconcileNightly(LocalDate.now(ET));
            logger.info("[TLH] Nightly reconciliation complete");
        } catch (Exception e) {
            logger.error("[TLH] Nightly reconciliation failed", e);
        }
    }
}
