package com.investingapp.backend.ops;

import java.time.LocalDateTime;
import java.util.Map;

/** One alert, cut to the ledger widths; raisedAt is Eastern wall time; context holds ids, counts and codes only. */
public record OpsAlertMessage(String type, String subjectId, String message, Map<String, Object> context,
        LocalDateTime raisedAt) {
}
