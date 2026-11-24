import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PlaidItemStatus, PrimaryBankAccount } from '../../models/primary-bank-account';
import { ReconnectCardComponent, reconnectCardFor } from './reconnect-card.component';

/** A linked, healthy Chase checking account; each case overrides only what it is about. */
function account(overrides: Partial<PrimaryBankAccount> = {}): PrimaryBankAccount {
  return {
    accountId: 'acc-1',
    institutionName: 'Chase',
    accountName: 'Plaid Checking',
    accountType: 'depository',
    accountSubtype: 'checking',
    mask: '0000',
    itemStatus: 'OK',
    transactionsConsented: true,
    consentExpiresAt: null,
    ...overrides,
  };
}

/** Spec 10's MANUAL sentence. Phase 1 is shadow, so every schedule is MANUAL. */
const MANUAL_SUB = 'Reconnect your Chase account so your investing keeps running.';
const ALL_STATUSES: PlaidItemStatus[] = ['OK', 'LOGIN_REQUIRED', 'PENDING_DISCONNECT', 'REVOKED', 'REMOVED', 'SYNC_LIMIT', 'NOT_SUPPORTED'];

describe('reconnectCardFor', () => {
  it('showsForLoginRequiredAndRevoked: the spec 9.7 ITEM_BROKEN row with the MANUAL sub', () => {
    for (const itemStatus of ['LOGIN_REQUIRED', 'REVOKED'] as PlaidItemStatus[]) {
      expect(reconnectCardFor(account({ itemStatus }))).withContext(itemStatus).toEqual({
        title: 'Reconnect your bank',
        sub: `FRED lost access to Chase. ${MANUAL_SUB}`,
        showButton: true,
      });
    }
    // A broken Item wins over a missing consent: one reconnect fixes both.
    expect(reconnectCardFor(account({ itemStatus: 'LOGIN_REQUIRED', transactionsConsented: false }))!.sub)
      .toBe(`FRED lost access to Chase. ${MANUAL_SUB}`);
  });

  it('hiddenForOkNotSupportedAndSyncLimit: and REMOVED, where reconnecting cannot help', () => {
    for (const itemStatus of ['OK', 'NOT_SUPPORTED', 'SYNC_LIMIT', 'REMOVED'] as PlaidItemStatus[]) {
      expect(reconnectCardFor(account({ itemStatus }))).withContext(itemStatus).toBeNull();
    }
    expect(reconnectCardFor(account({ itemStatus: 'SYNC_LIMIT', transactionsConsented: false }))).toBeNull();
  });

  it('hiddenWhenNoBankIsLinked: an unlinked user reports OK and no institution', () => {
    expect(reconnectCardFor(null)).toBeNull();
    expect(reconnectCardFor(undefined)).toBeNull();
    expect(reconnectCardFor(account({ institutionName: '', itemStatus: 'LOGIN_REQUIRED' }))).toBeNull();
  });

  it('needsConsentRowWhenFlagFalse: only an explicit false, never the unknown null', () => {
    expect(reconnectCardFor(account({ transactionsConsented: false }))).toEqual({
      title: 'Reconnect your bank',
      sub: `FRED needs permission to read deposits at Chase. ${MANUAL_SUB}`,
      showButton: true,
    });
    expect(reconnectCardFor(account({ transactionsConsented: null }))).toBeNull();
  });

  it('pendingDisconnectShowsTheDateLine: the ET date, or "soon" when there is none', () => {
    expect(reconnectCardFor(account({ itemStatus: 'PENDING_DISCONNECT', consentExpiresAt: '2026-11-20T09:30' }))).toEqual({
      title: 'Reconnect your bank',
      sub: `Your Chase connection expires Nov 20. ${MANUAL_SUB}`,
      showButton: true,
    });
    // Late evening ET must not roll to the next day: only the date part is read.
    expect(reconnectCardFor(account({ itemStatus: 'PENDING_DISCONNECT', consentExpiresAt: '2026-11-20T23:59:59' }))!.sub)
      .toBe(`Your Chase connection expires Nov 20. ${MANUAL_SUB}`);
    // T16's wire shape: the ET wall time with its offset.
    expect(reconnectCardFor(account({ itemStatus: 'PENDING_DISCONNECT', consentExpiresAt: '2026-12-01T09:00-05:00' }))!.sub)
      .toBe(`Your Chase connection expires Dec 1. ${MANUAL_SUB}`);
    expect(reconnectCardFor(account({ itemStatus: 'PENDING_DISCONNECT', consentExpiresAt: null }))!.sub)
      .toBe(`Your Chase connection expires soon. ${MANUAL_SUB}`);
  });

  it('copyHasNoEmDash: no title or sub of any row carries an em dash (house style)', () => {
    const EM_DASH = String.fromCharCode(0x2014);
    for (const itemStatus of ALL_STATUSES) {
      for (const transactionsConsented of [true, false, null]) {
        const card = reconnectCardFor(account({ itemStatus, transactionsConsented, consentExpiresAt: '2026-11-20T09:30' }));
        if (card) {
          expect(card.title).not.toContain(EM_DASH);
          expect(card.sub).not.toContain(EM_DASH);
        }
      }
    }
  });
});

describe('ReconnectCardComponent', () => {
  let fixture: ComponentFixture<ReconnectCardComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [ReconnectCardComponent] });
    fixture = TestBed.createComponent(ReconnectCardComponent);
  });

  it('renders the title, the sub and a Reconnect button that emits reconnect once', () => {
    const emitted = jasmine.createSpy('reconnect');
    fixture.componentInstance.reconnect.subscribe(emitted);
    fixture.componentRef.setInput('account', account({ itemStatus: 'LOGIN_REQUIRED' }));
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.reconnect-title')?.textContent?.trim()).toBe('Reconnect your bank');
    expect(el.querySelector('.reconnect-sub')?.textContent?.trim()).toBe(`FRED lost access to Chase. ${MANUAL_SUB}`);
    (el.querySelector('button.reconnect-btn') as HTMLButtonElement).click();
    expect(emitted).toHaveBeenCalledTimes(1);
  });

  it('renders nothing for a healthy Item', () => {
    fixture.componentRef.setInput('account', account());
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('.reconnect-card')).toBeNull();
  });
});
