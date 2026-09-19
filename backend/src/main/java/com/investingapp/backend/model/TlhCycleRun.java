package com.investingapp.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Ops audit row for one TLH harvest cycle (scheduled or manual): who was
 * evaluated and what happened, so a quiet cycle is distinguishable from a
 * cycle that never ran.
 */
@Entity
@Table(name = "tlh_cycle_runs")
@Getter
@Setter
@NoArgsConstructor
public class TlhCycleRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_date", nullable = false)
    private LocalDate runDate;

    @Column(name = "mode", length = 10, nullable = false)
    private String mode;

    /** SCHEDULED | MANUAL — column renamed because `trigger` is a MySQL reserved word. */
    @Column(name = "run_trigger", length = 10, nullable = false)
    private String runTrigger;

    @Column(name = "users_evaluated", nullable = false)
    private Integer usersEvaluated = 0;

    @Column(name = "candidates", nullable = false)
    private Integer candidates = 0;

    @Column(name = "harvested", nullable = false)
    private Integer harvested = 0;

    @Column(name = "vetoed", nullable = false)
    private Integer vetoed = 0;

    @Column(name = "skipped", nullable = false)
    private Integer skipped = 0;

    @Column(name = "failed", nullable = false)
    private Integer failed = 0;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    public TlhCycleRun(LocalDate runDate, String mode, String runTrigger) {
        this.runDate = runDate;
        this.mode = mode;
        this.runTrigger = runTrigger;
        this.startedAt = LocalDateTime.now(ZoneId.of("America/New_York"));
    }
}
