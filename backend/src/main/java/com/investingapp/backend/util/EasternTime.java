package com.investingapp.backend.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * The one clock for money paths and notifications (spec 5.8, decision 17).
 *
 * <p>Every cron computes {@code todayET} once through {@link #today()} and passes it down, so a job
 * that straddles midnight keeps one calendar day. Callers never read {@code LocalDate.now()} with
 * the JVM default, even though {@code BackendApplication.main} pins that default to the same zone:
 * tests and tools run without main, and money math must not depend on where it runs.
 */
public final class EasternTime {

    public static final ZoneId ZONE = ZoneId.of("America/New_York");

    private EasternTime() {
    }

    /** The Eastern calendar day right now. */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** The Eastern wall clock right now. */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }
}
