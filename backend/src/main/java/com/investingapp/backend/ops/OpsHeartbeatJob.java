package com.investingapp.backend.ops;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.config.SchedulerGate;
import com.investingapp.backend.service.JobLockService;
import com.investingapp.backend.service.JobLocks;
import com.investingapp.backend.util.EasternTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * The 09:07 ET heartbeat (spec 12.3): one digest carrying every gauge, then the daily alerts the
 * schema can answer. The digest is raised every morning on purpose, so a morning without one is
 * itself the signal that the scheduler, the database or the alert channel is down. Minute :07
 * follows the TlhScheduler offset convention, away from the quarter-hour pollers.
 *
 * <p>Runs whatever paycheck.autopilot.enabled says (spec 12.1 keeps the heartbeat on when the
 * pipeline is off). The four alerts about the pipeline's own progress (inbox age, no webhooks,
 * SEARCHING over 24 hours, reconcile late) are raised only when the pipeline is on: with it off
 * the drainer and the reconcile never run, and those four would fire every morning.
 *
 * <p>Reads and raises only. It moves no money and writes no schedule, hold, Item or user row.
 * OpsAlertService.raise writes its dedupe row in its own transaction and delivers on its own
 * executor, so nothing here holds a connection while an alert is sent.
 */
@Component
public class OpsHeartbeatJob {

    private static final Logger logger = LoggerFactory.getLogger(OpsHeartbeatJob.class);

    static final Duration LOCK_LEASE = Duration.ofMinutes(5);
    static final long INBOX_OLDEST_AGE_LIMIT_SEC = 1800;
    static final String SUBJECT_DIGEST = "digest";
    static final String SUBJECT_NIGHTLY = "nightly";

    private final SchedulerGate schedulerGate;
    private final JobLockService jobLockService;
    private final OpsGaugeService gaugeService;
    private final OpsAlertService opsAlertService;
    private final PaycheckAutopilotProperties props;

    public OpsHeartbeatJob(SchedulerGate schedulerGate,
                           JobLockService jobLockService,
                           OpsGaugeService gaugeService,
                           OpsAlertService opsAlertService,
                           PaycheckAutopilotProperties props) {
        this.schedulerGate = schedulerGate;
        this.jobLockService = jobLockService;
        this.gaugeService = gaugeService;
        this.opsAlertService = opsAlertService;
        this.props = props;
    }

    @Scheduled(cron = "0 7 9 * * *", zone = "America/New_York")
    public void run() {
        if (!schedulerGate.isEnabled()) {
            return;
        }
        if (!jobLockService.tryAcquire(JobLocks.OPS_HEARTBEAT, LOCK_LEASE)) {
            logger.info("ops.heartbeat.skipped reason=lock_not_taken");
            return;
        }
        try {
            beat(EasternTime.now());
        } finally {
            jobLockService.release(JobLocks.OPS_HEARTBEAT);
        }
    }

    private void beat(LocalDateTime nowET) {
        OpsGaugeSnapshot s;
        try {
            s = gaugeService.snapshot(nowET);
        } catch (RuntimeException e) {
            logger.error("alert.ops.heartbeat_snapshot_failed cause={}", e.getClass().getSimpleName(), e);
            opsAlertService.raise(OpsAlerts.HEARTBEAT, SUBJECT_DIGEST, "heartbeat snapshot=failed", Map.of("snapshot", "failed"));
            return;
        }

        opsAlertService.raise(OpsAlerts.HEARTBEAT, SUBJECT_DIGEST, digestLine(s), s.toContext());

        boolean pipelineOn = props.isEnabled();
        String global = OpsAlertService.GLOBAL_SUBJECT;
        if (s.inboxFailed() > 0) {
            opsAlertService.raise(OpsAlerts.INBOX_FAILED, global,
                    "inbox.failed count=" + s.inboxFailed(), Map.of("inbox.failed", s.inboxFailed()));
        }
        if (pipelineOn && s.inboxOldestAgeSec() > INBOX_OLDEST_AGE_LIMIT_SEC) {
            opsAlertService.raise(OpsAlerts.INBOX_OLDEST_AGE, global,
                    "inbox.oldest_age_sec value=" + s.inboxOldestAgeSec(),
                    Map.of("inbox.oldest_age_sec", s.inboxOldestAgeSec()));
        }
        if (pipelineOn && s.plaidItemsBillable() > 0 && s.webhooksReceived24h() == 0) {
            opsAlertService.raise(OpsAlerts.WEBHOOKS_NONE_24H, global,
                    "webhooks.received_24h count=0 billable=" + s.plaidItemsBillable(),
                    Map.of("webhooks.received_24h", 0L, "plaid.items.billable", s.plaidItemsBillable()));
        }
        if (pipelineOn && s.searchingOver24h() > 0) {
            opsAlertService.raise(OpsAlerts.SEARCHING_24H, global,
                    "paycheck.searching_24h count=" + s.searchingOver24h(),
                    Map.of("searching.over_24h", s.searchingOver24h()));
        }
        if (s.manualReviewOpen() > 0) {
            opsAlertService.raise(OpsAlerts.HOLDS_MANUAL_REVIEW_PRESENT, global,
                    "holds.manual_review_open count=" + s.manualReviewOpen(),
                    Map.of("manual_review.open", s.manualReviewOpen()));
        }
        if (pipelineOn && !s.reconcileRanYesterday()) {
            opsAlertService.raise(OpsAlerts.RECONCILE_LATE, SUBJECT_NIGHTLY,
                    "paycheck.reconcile.late ran_yesterday=false", Map.of("reconcile.ran_yesterday", false));
        }
        logger.info("ops.heartbeat.sent billable={} backlog={} failed={} alerts_24h={}",
                s.plaidItemsBillable(), s.inboxBacklog(), s.inboxFailed(), s.opsAlerts24h());
    }

    private static String digestLine(OpsGaugeSnapshot s) {
        return "heartbeat billable=" + s.plaidItemsBillable()
                + " backlog=" + s.inboxBacklog()
                + " failed=" + s.inboxFailed()
                + " alerts_24h=" + s.opsAlerts24h();
    }
}
