package com.investingapp.backend.repository;

import com.investingapp.backend.model.OpsAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/** ops_alerts: the dedupe ledger behind OpsAlertService.raise (spec 12.3). */
@Repository
public interface OpsAlertRepository extends JpaRepository<OpsAlert, Long> {

    /** The dedupe lookup: today's row for this alert type and subject, if one was raised. */
    Optional<OpsAlert> findByAlertTypeAndSubjectIdAndAlertDay(String alertType, String subjectId, LocalDate alertDay);

    /** Alerts first raised after the cut-off. The cut-off is Eastern wall time, like first_raised_at. */
    long countByFirstRaisedAtAfter(LocalDateTime cutoff);
}
