package com.investingapp.backend.scheduler;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.config.SchedulerGate;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.service.JobLockService;
import com.investingapp.backend.service.JobLocks;
import com.investingapp.backend.service.PaycheckReconcileService;
import com.investingapp.backend.service.PaycheckReconcileService.ReconcileReport;
import com.investingapp.backend.util.EasternTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * The 22:30 ET reconcile cron (spec 5.6, 11): scheduler gate, master flag (spec 12.1), own lock, one
 * Eastern reading. RECONCILE_RAN on every run that took the lock feeds T05's RECONCILE_LATE check.
 */
@Component
public class PaycheckReconcileScheduler {

    private static final Logger logger = LoggerFactory.getLogger(PaycheckReconcileScheduler.class);

    /** Longer than the 50-minute dispatch window plus phase A. */
    static final Duration LEASE = Duration.ofMinutes(60);

    private final PaycheckReconcileService reconcileService;
    private final PaycheckAutopilotProperties props;
    private final SchedulerGate schedulerGate;
    private final JobLockService jobLockService;
    private final OpsAlertService opsAlertService;

    public PaycheckReconcileScheduler(PaycheckReconcileService reconcileService,
            PaycheckAutopilotProperties props,
            SchedulerGate schedulerGate,
            JobLockService jobLockService,
            OpsAlertService opsAlertService) {
        this.reconcileService = reconcileService;
        this.props = props;
        this.schedulerGate = schedulerGate;
        this.jobLockService = jobLockService;
        this.opsAlertService = opsAlertService;
    }

    @Scheduled(cron = "0 30 22 * * *", zone = "America/New_York")
    public void run() {
        if (!schedulerGate.isEnabled()) {
            return;
        }
        if (!props.isEnabled()) {
            logger.info("paycheck.reconcile.skipped reason=disabled");
            return;
        }
        if (!jobLockService.tryAcquire(JobLocks.PAYCHECK_RECONCILE, LEASE)) {
            logger.warn("paycheck.reconcile.skipped reason=lock_not_taken lock={}", JobLocks.PAYCHECK_RECONCILE);
            return;
        }
        LocalDateTime nowET = EasternTime.now();
        LocalDate todayET = nowET.toLocalDate();
        ReconcileReport report = null;
        try {
            report = reconcileService.run(todayET, nowET);
            logger.info("paycheck.reconcile.done {}", report);
        } catch (RuntimeException e) {
            logger.error("alert.paycheck.reconcile.threw exception={}", e.getClass().getSimpleName(), e);
            opsAlertService.raise(OpsAlerts.RECONCILE_THREW, PaycheckReconcileService.ALERT_SUBJECT, "nightly reconcile threw", Map.of("exception", e.getClass().getSimpleName()));
        } finally {
            if (report != null) {
                opsAlertService.raise(OpsAlerts.RECONCILE_RAN, PaycheckReconcileService.ALERT_SUBJECT,
                        "nightly reconcile completed", report.toContext());
            } else {
                opsAlertService.raise(OpsAlerts.RECONCILE_RAN, PaycheckReconcileService.ALERT_SUBJECT,
                        "nightly reconcile did not complete", Map.of("completed", false));
            }
            jobLockService.release(JobLocks.PAYCHECK_RECONCILE);
        }
    }
}
