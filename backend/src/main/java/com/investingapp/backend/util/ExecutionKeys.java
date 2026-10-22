package com.investingapp.backend.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Idempotency keys on investment_executions (spec 3.8, 5.4). source_key is UNIQUE: one row per
 * MANUAL due date per schedule, one row per lump-sum tap. night_key is UNIQUE and NULL for lump
 * sums: one recurring execution per user per Eastern night. Both are pre-checked before insert;
 * the database constraints are the backstop.
 */
public final class ExecutionKeys {

    private ExecutionKeys() {
    }

    public static String scheduled(long scheduleId, LocalDate dueDate) {
        return "SCHED:" + scheduleId + ":" + dueDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public static String night(long userId, LocalDate nightET) {
        return userId + ":" + nightET.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public static String lump(long userId, String clientUuid) {
        return "LUMP:" + userId + ":" + clientUuid;
    }
}
