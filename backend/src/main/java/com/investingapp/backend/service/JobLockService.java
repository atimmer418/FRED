package com.investingapp.backend.service;

import com.investingapp.backend.repository.JobLockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Lease-based locks on job_locks (spec 3.9, 5.6). A crashed holder is taken over once its
 * lease expires. Both calls run in their own REQUIRES_NEW transaction (same shape as
 * TlhHarvestService.claimTx) so the lock row commits before the job body starts, even when
 * the caller is already inside a transaction (InvestmentExecutionService is class-level
 * @Transactional). Neither call throws: a job that cannot reach job_locks is skipped.
 *
 * <p>The lease clock is UTC, not Eastern: {@code job_locks.locked_until} is stored as UTC and
 * compared only against the UTC clock this service supplies, never against Eastern time. Eastern
 * wall time repeats an hour every November and skips an hour every March, so a lease measured on
 * it would either block a crashed holder's replacement for an extra hour or let two runners hold
 * the same name at once; UTC has no such gaps or repeats.
 */
@Service
public class JobLockService {

    private static final Logger logger = LoggerFactory.getLogger(JobLockService.class);

    private final JobLockRepository jobLockRepository;
    private final TransactionTemplate requiresNew;
    private final String owner;

    public JobLockService(JobLockRepository jobLockRepository, PlatformTransactionManager transactionManager) {
        this.jobLockRepository = jobLockRepository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName(); // pid@host
        this.owner = runtimeName.length() > 64 ? runtimeName.substring(0, 64) : runtimeName;
    }

    /** True when this process now holds the lock until now + lease. */
    public boolean tryAcquire(String name, Duration lease) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime until = now.plus(lease);
        try {
            Boolean acquired = requiresNew.execute(status ->
                    jobLockRepository.insertIgnore(name, until, owner) == 1
                            || jobLockRepository.renew(name, now, until, owner) == 1);
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException e) {
            logger.error("alert.job_lock.unavailable job={}", name, e);
            return false;
        }
    }

    /** Drops the row if this process still owns it; a failure is logged, the lease expires on its own. */
    public void release(String name) {
        try {
            requiresNew.executeWithoutResult(status -> jobLockRepository.deleteOwned(name, owner));
        } catch (RuntimeException e) {
            logger.error("alert.job_lock.release_failed job={}", name, e);
        }
    }
}
