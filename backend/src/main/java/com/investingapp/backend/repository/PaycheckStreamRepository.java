package com.investingapp.backend.repository;

import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * paycheck_streams. Derived deletes load the rows and remove them one by one, so the persistence
 * context stays consistent with the database; a derived delete needs a read-write transaction,
 * hence @Transactional (it joins the caller's when there is one).
 *
 * <p>A caller purging several paycheck tables deletes cycles, then events, then streams: MySQL
 * cascades a stream delete to its cycles, and Hibernate would then find no row for a cycle it was
 * also told to delete.
 */
@Repository
public interface PaycheckStreamRepository extends JpaRepository<PaycheckStream, Long> {

    List<PaycheckStream> findByUserIdOrderByIdAsc(Long userId);

    List<PaycheckStream> findByUserIdAndState(Long userId, PaycheckStreamState state);

    /** The natural key, unique in V6 (uq_paycheck_streams_payer). */
    Optional<PaycheckStream> findByUserIdAndPlaidAccountIdAndPayerToken(Long userId, String plaidAccountId,
            String payerToken);

    @Transactional
    long deleteByUserId(Long userId);
}
