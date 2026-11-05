import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { PrimaryBankAccount } from '../../models/primary-bank-account';

/** What the card shows. reconnectCardFor returns null when there is nothing to reconnect. */
export interface ReconnectCard {
  title: string;
  sub: string;
  /** True on every Phase 1 row; the field exists for spec 9.7 hold rows that carry no action. */
  showButton: boolean;
}

const RECONNECT_TITLE = 'Reconnect your bank';

/**
 * The spec 9.7 bank rows for /recurring-investments, from the bank account alone (R10), so it works
 * with or without a schedule. Every Phase 1 schedule is MANUAL (shadow), so the second sentence is
 * always spec 10's MANUAL sentence. Phase 2 adds the DETECTED sentence once a mode is on the wire.
 */
export function reconnectCardFor(account: PrimaryBankAccount | null | undefined): ReconnectCard | null {
  if (!account || !account.institutionName) {
    return null;
  }
  const bank = account.institutionName;
  const keepRunning = `Reconnect your ${bank} account so your investing keeps running.`;
  switch (account.itemStatus) {
    case 'LOGIN_REQUIRED':
    case 'REVOKED':
      return { title: RECONNECT_TITLE, sub: `FRED lost access to ${bank}. ${keepRunning}`, showButton: true };
    case 'PENDING_DISCONNECT':
      return {
        title: RECONNECT_TITLE,
        sub: `Your ${bank} connection expires ${expiryLabel(account.consentExpiresAt)}. ${keepRunning}`,
        showButton: true,
      };
    case 'OK':
      // Only Plaid's explicit refusal shows the consent row; null means one sync will tell (spec 2.3).
      return account.transactionsConsented === false
        ? { title: RECONNECT_TITLE, sub: `FRED needs permission to read deposits at ${bank}. ${keepRunning}`, showButton: true }
        : null;
    default:
      // REMOVED, SYNC_LIMIT, NOT_SUPPORTED: reconnecting the same Item cannot change them.
      return null;
  }
}

/** 'Nov 20' from the ET date part of consentExpiresAt; read as a calendar date so no zone can shift it. */
function expiryLabel(consentExpiresAt: string | null): string {
  const match = consentExpiresAt ? /^(\d{4})-(\d{2})-(\d{2})/.exec(consentExpiresAt) : null;
  if (!match) {
    return 'soon';
  }
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

@Component({
  selector: 'app-reconnect-card',
  templateUrl: './reconnect-card.component.html',
  styleUrls: ['./reconnect-card.component.scss'],
  standalone: true,
  imports: [CommonModule],
})
export class ReconnectCardComponent {
  @Input() account: PrimaryBankAccount | null = null;
  @Output() reconnect = new EventEmitter<void>();

  get card(): ReconnectCard | null {
    return reconnectCardFor(this.account);
  }
}
