package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The per-user row lock with a re-read, for every Phase 1 writer that locks a user and then saves.
 *
 * <p>Open-in-view is on. On a request thread the request's EntityManager may already hold the User
 * (a controller's findById), and Hibernate locks a managed instance without re-reading it, so a
 * guard would read a stale copy and a save would write that copy back over another transaction's
 * commit (PlaidService and InitSyncEnqueuer refresh for the same reason). Both refreshes here are
 * locking reads, so they also see the latest committed row inside an older outer transaction.
 * Call inside a transaction.
 */
@Component
public class UserRowLock {

    private final UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    public UserRowLock(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** For unit tests that build services with {@code new}. */
    public UserRowLock(UserRepository userRepository, EntityManager entityManager) {
        this.userRepository = userRepository;
        this.entityManager = entityManager;
    }

    /** findByIdForUpdate, then a locked re-read of the row it returned. */
    public Optional<User> lockFresh(Long userId) {
        Optional<User> locked = userRepository.findByIdForUpdate(userId);
        locked.ifPresent(user -> entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE));
        return locked;
    }

    /**
     * Re-reads a schedule under its own row lock, so a decision about hold_reason sees what a writer
     * that does not take the user lock (the Phase 0 MANUAL_REVIEW writers) has committed.
     */
    public void refreshLocked(InvestmentSchedule schedule) {
        entityManager.refresh(schedule, LockModeType.PESSIMISTIC_WRITE);
    }
}
