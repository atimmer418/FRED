/**
 * GET /api/plaid/primary-bank-account, typed (spec 8.8). Display fields only: the Plaid access token
 * never leaves the server. PlaidService.getPrimaryBankAccount() is the one typed reader.
 */

/** Mirrors backend model/PlaidItemStatus.java. OK is also what an unlinked user reports. */
export type PlaidItemStatus = 'OK' | 'LOGIN_REQUIRED' | 'PENDING_DISCONNECT' | 'REVOKED' | 'REMOVED' | 'SYNC_LIMIT' | 'NOT_SUPPORTED';

export interface PrimaryBankAccount {
  accountId: string | null;
  institutionName: string;
  accountName: string | null;
  accountType: string | null;
  accountSubtype: string | null;
  /** Last four digits; null for links made before the mask was stored. */
  mask: string | null;
  itemStatus: PlaidItemStatus;
  /** Spec 2.3 tri-state: true when Plaid lists transactions, null when unknown, false when refused. */
  transactionsConsented: boolean | null;
  /**
   * users.plaid_disconnect_at, ET wall time. The server sends it with its offset
   * ('2026-12-01T09:00-05:00'); the reader also turns a Jackson date array into 'YYYY-MM-DDTHH:mm'.
   * Either way the date part is the ET date. Null when none.
   */
  consentExpiresAt: string | null;
}

/**
 * T16's UpdateModeResult values. A 200 { result } carries only CONSENT_GRANTED, CONSENT_STILL_MISSING,
 * REPAIRED or NO_CHANGE; STALE_ITEM and PLAID_ERROR arrive as the code of a 409 or 502 error body.
 */
export type UpdateModeResult = 'CONSENT_GRANTED' | 'CONSENT_STILL_MISSING' | 'REPAIRED' | 'NO_CHANGE' | 'STALE_ITEM' | 'PLAID_ERROR';

export interface UpdateModeCompleteResponse {
  result: UpdateModeResult;
}
