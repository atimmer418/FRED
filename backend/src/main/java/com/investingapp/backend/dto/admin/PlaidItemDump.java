package com.investingapp.backend.dto.admin;

import com.investingapp.backend.model.User;

import java.time.LocalDateTime;

public record PlaidItemDump(String publicId, String accountStatus, String plaidItemId, String plaidAccountId,
                            String plaidInstitutionName, String plaidAccountName, String plaidAccountType,
                            String plaidAccountSubtype, String plaidAccountMask, String plaidItemStatus,
                            String plaidItemErrorCode, Boolean plaidTransactionsConsented, boolean hasAccessToken,
                            boolean hasSyncCursor, Boolean plaidHistoryComplete, String plaidTransactionsUpdateStatus,
                            LocalDateTime plaidItemLinkedAt, LocalDateTime plaidWebhookAckedAt,
                            LocalDateTime plaidLastSyncAt, LocalDateTime plaidStreamsReadAt,
                            LocalDateTime plaidDisconnectAt, Boolean paycheckAutopilotOptOut) {

    public static PlaidItemDump of(User u) {
        return new PlaidItemDump(u.getPublicId(), u.getAccountStatus(), u.getPlaidItemId(), u.getPlaidAccountId(),
                u.getPlaidInstitutionName(), u.getPlaidAccountName(), u.getPlaidAccountType(),
                u.getPlaidAccountSubtype(), u.getPlaidAccountMask(),
                u.getPlaidItemStatus() == null ? null : u.getPlaidItemStatus().name(),
                u.getPlaidItemErrorCode(), u.getPlaidTransactionsConsented(), u.getPlaidAccessToken() != null,
                u.getPlaidSyncCursor() != null, u.getPlaidHistoryComplete(), u.getPlaidTransactionsUpdateStatus(),
                u.getPlaidItemLinkedAt(), u.getPlaidWebhookAckedAt(), u.getPlaidLastSyncAt(),
                u.getPlaidStreamsReadAt(), u.getPlaidDisconnectAt(), u.getPaycheckAutopilotOptOut());
    }
}
