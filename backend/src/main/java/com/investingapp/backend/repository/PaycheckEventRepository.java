package com.investingapp.backend.repository;

import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** paycheck_events. Delete rules as on PaycheckStreamRepository. */
@Repository
public interface PaycheckEventRepository extends JpaRepository<PaycheckEvent, Long> {

    /** Unique in V6 (uq_paycheck_events_plaid_transaction): the replay guard. */
    Optional<PaycheckEvent> findByPlaidTransactionId(String plaidTransactionId);

    /** Posted rows that name this pending transaction. A list: V6 indexes the column but does not make it unique. */
    List<PaycheckEvent> findByPendingTransactionId(String pendingTransactionId);

    /** Posted (not pending) rows in one status, oldest first: what grouping reads. */
    List<PaycheckEvent> findByUserIdAndStatusAndPendingFalseOrderByPostedDateAsc(Long userId, PaycheckEventStatus status);

    List<PaycheckEvent> findByStreamIdAndStatusOrderByPostedDateDesc(Long streamId, PaycheckEventStatus status);

    @Transactional
    long deleteByUserId(Long userId);

    /** One account's events, for an account-level revoke (spec 5.7). */
    @Transactional
    long deleteByUserIdAndPlaidAccountId(Long userId, String plaidAccountId);

    /** 24-month retention (spec 7.9); the reconcile passes the cutoff date. */
    @Transactional
    long deleteByPostedDateBefore(LocalDate cutoff);
}
