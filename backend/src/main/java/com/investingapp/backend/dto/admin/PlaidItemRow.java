package com.investingapp.backend.dto.admin;

import java.time.LocalDateTime;

/** One row of GET /api/admin/plaid/items, keyed by public_id; never the token or cursor as values. Times are ET. */
public record PlaidItemRow(String publicId, String itemStatus, Boolean transactionsConsented,
        boolean hasCursor, boolean historyComplete, LocalDateTime lastSyncAt, LocalDateTime linkedAt,
        boolean pendingRemoval) {
}
