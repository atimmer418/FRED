package com.investingapp.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A lease held by one scheduled job at a time (spec 3.9); the name is the assigned id.
 * {@code lockedUntil} is stored as UTC and compared only against the UTC clock JobLockService
 * supplies, never against Eastern time (Eastern wall time repeats and skips an hour across the
 * two DST transitions, which would make the lease arithmetic non-monotonic).
 */
@Entity
@Table(name = "job_locks")
@Getter
@Setter
@NoArgsConstructor
public class JobLock {

    @Id
    @Column(name = "name", length = 64)
    private String name;

    @Column(name = "locked_until", nullable = false)
    private LocalDateTime lockedUntil;

    @Column(name = "locked_by", length = 64)
    private String lockedBy;
}
