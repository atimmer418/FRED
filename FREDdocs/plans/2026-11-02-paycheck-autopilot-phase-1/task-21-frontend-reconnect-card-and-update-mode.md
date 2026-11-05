### Task 21: frontend-reconnect-card-and-update-mode

The reconnect card on `/recurring-investments`, driven by `GET /api/plaid/primary-bank-account`; the update-mode Link flow on `/change-bank-account` with the 409 `UPDATE_MODE_UNAVAILABLE` fallback to `link`; and the `POST /api/plaid/update-mode-complete` call on `onSuccess` from both Link pages when the token was created with `mode: 'update'` (ruling R10, spec 2.3, 8.8, 9.7, 10). Frontend only. Nothing here moves money, reads or writes a schedule's `status` or hold, or adds a client analytics event.

Facts this task depends on, restated so this file stands alone:

- **R10.** The card renders from `primary-bank-account` (`itemStatus`, plus `consentExpiresAt` and `transactionsConsented`, which T16 adds), not from the schedule response, so it works for MANUAL users and needs none of the Phase 2 schedule fields. Pause and resume stay visible (they are MANUAL controls); only "Reconnect" is added. This overrides the spec 9.7 "pause hidden" note for ITEM_BROKEN.
- **Phase 1 is shadow.** Every schedule row stays `mode = MANUAL`, so the card always uses the MANUAL sentence of spec 10 ("Reconnect your {institution} account so your investing keeps running.") and never the DETECTED one ("Paycheck investing is paused until you reconnect" is false for a MANUAL user, whose Item-state holds are not blocking).
- **Spec 9.7 rows the card implements.** ITEM_BROKEN (`itemStatus` LOGIN_REQUIRED or REVOKED): "Reconnect your bank" / "FRED lost access to {institution}." NEEDS_CONSENT (`transactionsConsented === false`): "FRED needs permission to read deposits at {institution}." PENDING_DISCONNECT: "Your {institution} connection expires {date}." with no hold. Action: "Reconnect" (`update`, retry with `link` on 409 `UPDATE_MODE_UNAVAILABLE`). No card for OK, REMOVED, SYNC_LIMIT, NOT_SUPPORTED.
- **R18.** The card is for users who can invest. The server side of R18 is the exclusion inside T03's `NotificationService.sendReconnect` (which T13 calls); on the client this task hides the card when `AccountStatusService.accountStatus$` reports `REJECTED`. A user with no schedule still sees the card on this page: by the time anyone reaches `/recurring-investments` they are past onboarding.
- **Spec 2.3 and 8.8.** Update mode keeps the same Item and access token, so `onSuccess` must never call `/plaid/exchange_public_token`; it calls `POST /api/plaid/update-mode-complete` with no body, which re-reads consent, re-points the webhook and clears PENDING_DISCONNECT. When Plaid refuses an update-mode token the server answers 409 `ErrorCodeResponse { code: "UPDATE_MODE_UNAVAILABLE", message }` (`PlaidController.java:71-74` at HEAD) and the client retries with `link`, whose exchange queues the old Item for removal.
- **T16's contract for the endpoint.** 200 `{ "result": "CONSENT_GRANTED" | "CONSENT_STILL_MISSING" | "REPAIRED" | "NO_CHANGE" }`, 409 `STALE_ITEM`, 409 `UPDATE_MODE_UNAVAILABLE` (no Item), 502 `PLAID_ERROR`, 401, 404. `consentExpiresAt` is `users.plaid_disconnect_at` (ET wall time), which T16 formats as an ISO string with its offset (`atZone(EasternTime.ZONE).toOffsetDateTime().toString()`, for example `2026-12-01T09:00-05:00`) or sends as null. FRED's primary `ObjectMapper` (`config/JacksonConfig.java:22-27`, `JsonMapper.builder()` with `JavaTimeModule` and Jackson's default timestamps) would write a raw `LocalDateTime` as a `[y, m, d, h, min]` array, so the typed reader also accepts an array, defensively. The card reads only the date part, which is already the ET date.
- **T20 (already merged before this task).** `LinkPlaidComponent` forwards a `change-bank` OAuth return from `/link-bank` to `/change-bank-account` with the query string intact; `plaid-link-session.ts` gained `PLAID_OAUTH_RETURN_HREF_KEY`, `storePlaidOAuthReturnHref`, `readPlaidOAuthReturnHref` (cleared by `clearPlaidLinkSession`), `plaidOAuthStateId` and `plaidOAuthReturnRoute`; `ChangeBankAccountPage.oauthReturnHref()` prefers the stored `/link-bank` href (it calls `plaidOAuthStateId` and a new private `currentHref()`). This task never edits T20's lines except the two import lines, which it rewrites in full as they read after T20; every other anchor below is quoted text T20 does not touch.

Paths are relative to `frontend/src/app/`. Line numbers are HEAD 137998c; files T20 edits (`utils/plaid-link-session.ts`, `change-bank-account/change-bank-account.page.ts`, `components/linkplaid/linkplaid.component.ts`, `components/linkplaid/linkplaid.component.oauth.spec.ts`) are anchored on quoted text.

This task adds no property, so `application-local.properties` needs no edit. Backend: none.

**Files:**
- Create: `models/primary-bank-account.ts`; `components/reconnect-card/reconnect-card.component.ts`, `.html`, `.scss`, `.spec.ts`; `services/plaid.service.primary-bank-account.spec.ts`
- Modify: `utils/plaid-link-session.ts` (after the quoted `PLAID_LINK_RETURN_KEY` and `PlaidLinkReturnTo` lines; `storePlaidLinkSession` at HEAD 12-19; after `readPlaidLinkReturn` at HEAD 34-40; `clearPlaidLinkSession` at HEAD 42-49); `services/plaid.service.ts` (imports 3 and 5; after `PlaidLinkResult` 20-25; after `getCurrentAccount()` 44-46); `recurring-investments/recurring-investments.page.ts` (imports after 36; `imports` array 58-59; fields 74-75; constructor 119-120; `ngOnInit` 127-131; after `ngOnDestroy` 133-136); `recurring-investments/recurring-investments.page.html` (after 14); `change-bank-account/change-bank-account.page.ts` (imports 6, 10; after 26; field after 53; `initializePlaidLink` 134-146, 150-156, 157-162; `openPlaid` 224; `getLinkTokenAuthenticated` 233-237; before `getAuthHeaders` 285); `components/linkplaid/linkplaid.component.ts` (imports 9, 16; field after 47; after 89; `onSuccess` 122-124; before `openPlaid` 223)
- Test: modify `recurring-investments/recurring-investments.page.spec.ts` (imports 6-13, `beforeEach` 58-74, append), `change-bank-account/change-bank-account.page.spec.ts` (imports, append a `describe`), `components/linkplaid/linkplaid.component.oauth.spec.ts` (import, two cases)
- Not edited: `.github/workflows/verify.yml` (the new globs are a manual prerequisite applied from Andy's checkout, Step 13); `change-bank-account.page.html` (its "Select New Bank Account" button opens Plaid in either mode).

**Interfaces:**
- Consumes: T16 `POST /api/plaid/update-mode-complete` and the two new `primary-bank-account` keys; Phase 0 `POST /api/plaid/create_link_token { mode }` and its 409 body; T20's forward and session helpers (above); `AccountStatusService.accountStatus$: Observable<string | null>` (`services/account-status.service.ts:14`, refreshed by `TabsPage` at `tabs/tabs.page.ts:82`); `PlaidService` `constructor(private http: HttpClient)` (`services/plaid.service.ts:34`); `ToastService.showToast(message, color, duration)` (`services/toast.service.ts:14`); `AnalyticsService.trackErrorShown`, `httpStatusOf`, `isNetworkFailure` (existing vocabulary only).
- Produces: in `models/primary-bank-account.ts`, `export type PlaidItemStatus = 'OK' | 'LOGIN_REQUIRED' | 'PENDING_DISCONNECT' | 'REVOKED' | 'REMOVED' | 'SYNC_LIMIT' | 'NOT_SUPPORTED';`, `export interface PrimaryBankAccount { accountId: string | null; institutionName: string; accountName: string | null; accountType: string | null; accountSubtype: string | null; mask: string | null; itemStatus: PlaidItemStatus; transactionsConsented: boolean | null; consentExpiresAt: string | null }`, `export type UpdateModeResult`, `export interface UpdateModeCompleteResponse { result: UpdateModeResult }`; in `utils/plaid-link-session.ts`, `export const PLAID_LINK_MODE_KEY = 'fred.plaid.linkMode'`, `export type PlaidLinkMode = 'link' | 'update'`, `storePlaidLinkSession(linkToken: string, returnTo: PlaidLinkReturnTo, mode: PlaidLinkMode = 'link'): void`, `readPlaidLinkMode(): PlaidLinkMode`; `PlaidService.getPrimaryBankAccount(): Observable<PrimaryBankAccount | null>`; `ReconnectCardComponent` (selector `app-reconnect-card`, `@Input() account: PrimaryBankAccount | null`, `@Output() reconnect: EventEmitter<void>`), `export interface ReconnectCard { title: string; sub: string; showButton: boolean }`, `export function reconnectCardFor(account: PrimaryBankAccount | null | undefined): ReconnectCard | null`; `RecurringInvestmentsPage.reconnectAccount`, `onReconnect()`, constructor gains an eleventh parameter `private plaidService: PlaidService` (appended last).

- [ ] **Step 0: Preconditions and baseline**

```
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1
grep -n 'readPlaidOAuthReturnHref' frontend/src/app/utils/plaid-link-session.ts
grep -n 'update-mode-complete' backend/src/main/java/com/investingapp/backend/controller/PlaidController.java
grep -n 'consentExpiresAt\|transactionsConsented' backend/src/main/java/com/investingapp/backend/controller/PlaidController.java
ls frontend/src/app/models frontend/src/app/components | grep -n 'primary-bank-account\|reconnect-card'
```

Expected: the first three each print at least one line (T20 and T16 have landed); the last prints nothing. If any of the first three is empty, stop: this task depends on T16 and T20.

Record the baseline counts of the specs this task extends (T20 may have added cases to the two OAuth specs and the change-bank page spec):

```
cd frontend
npx ng test --watch=false --browsers=ChromeHeadless --include='**/recurring-investments/recurring-investments.page.spec.ts'
npx ng test --watch=false --browsers=ChromeHeadless --include='**/change-bank-account/change-bank-account.page.spec.ts'
npx ng test --watch=false --browsers=ChromeHeadless --include='**/change-bank-account/change-bank-account.page.oauth.spec.ts'
npx ng test --watch=false --browsers=ChromeHeadless --include='**/components/linkplaid/linkplaid.component.oauth.spec.ts'
npx ng test --watch=false --browsers=ChromeHeadless --include='**/components/linkplaid/linkplaid.component.analytics.spec.ts'
```

Expected: each ends `Executed N of N SUCCESS`. At HEAD 137998c N is 12, 9, 5, 7 and 7; write down the numbers you see after T20 (call them R, C, O, L, A). Karma exits 0 even when the bundle fails to compile, so read the log, never the exit code; a "no message in 30000 ms" disconnect is environmental, re-run it.

- [ ] **Step 1: Failing spec for the card mapper and component**

Create `frontend/src/app/components/reconnect-card/reconnect-card.component.spec.ts`:

```ts
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
```

Run `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/components/reconnect-card/*.spec.ts'`. Expected: the bundle does not compile; the log shows `error TS2307: Cannot find module '../../models/primary-bank-account' or its corresponding type declarations.` and `error TS2307: Cannot find module './reconnect-card.component' or its corresponding type declarations.`, and no `SUCCESS` line.

- [ ] **Step 2: The model and the card**

Create `frontend/src/app/models/primary-bank-account.ts`:

```ts
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
```

Create `frontend/src/app/components/reconnect-card/reconnect-card.component.ts`:

```ts
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
```

Create `frontend/src/app/components/reconnect-card/reconnect-card.component.html`:

```html
<div class="reconnect-card" *ngIf="card as c" role="status">
  <div class="reconnect-row">
    <span class="material-symbols-outlined reconnect-icon" aria-hidden="true">link_off</span>
    <div class="reconnect-text">
      <span class="reconnect-title">{{ c.title }}</span>
      <span class="reconnect-sub">{{ c.sub }}</span>
    </div>
  </div>
  <button *ngIf="c.showButton" class="reconnect-btn" type="button" (click)="reconnect.emit()">
    <span>Reconnect</span>
    <span class="material-symbols-outlined" aria-hidden="true">sync</span>
  </button>
</div>
```

Create `frontend/src/app/components/reconnect-card/reconnect-card.component.scss` (the page's `.status-banner` shape at `recurring-investments.page.scss:25-77` in a `.status-error` palette, which the page does not have, plus a compact `.cta-btn`):

```scss
// Reconnect card: spec 9.7 .status-error row. Self-contained so it can sit outside the schedule
// wrapper and render with or without a schedule.
:host {
  // No box of its own: when there is no card, the 14px flex gap of .shell-inner gets no extra slot.
  display: contents;
}

.reconnect-card {
  border-radius: 14px;
  padding: 14px 16px;
  background: #fee2e2;
  border: 1.5px solid #fca5a5;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.reconnect-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.reconnect-icon {
  font-size: 28px;
  font-variation-settings: 'FILL' 1;
  flex-shrink: 0;
  color: #dc2626;
}

.reconnect-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.reconnect-title {
  font-family: 'Manrope', sans-serif;
  font-size: 16px;
  font-weight: 800;
  letter-spacing: -0.01em;
  color: #991b1b;
}

.reconnect-sub {
  font-family: 'Manrope', sans-serif;
  font-size: 12px;
  font-weight: 500;
  color: #b91c1c;
}

.reconnect-btn {
  height: 44px;
  border-radius: 12px;
  border: none;
  background: #2563EB;
  color: #ffffff;
  font-family: 'Manrope', sans-serif;
  font-size: 15px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  cursor: pointer;
  box-shadow: 0 8px 20px -4px rgba(37, 99, 235, 0.4);
  transition: transform 0.15s ease;

  .material-symbols-outlined { font-size: 20px; }

  &:active { transform: scale(0.99); }
}
```

Run the Step 1 command. Expected: `Executed 8 of 8 SUCCESS`.

- [ ] **Step 3: Failing spec for the typed reader**

Create `frontend/src/app/services/plaid.service.primary-bank-account.spec.ts`:

```ts
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom, of, throwError } from 'rxjs';
import { PlaidService } from './plaid.service';

/**
 * The typed reader behind the reconnect card. It must never invent a bank: the display cache's
 * localStorage and demo fallback would show a healthy "Chase Bank 1234" over a broken Item.
 */
describe('PlaidService.getPrimaryBankAccount', () => {
  let http: jasmine.SpyObj<HttpClient>;
  let service: PlaidService;

  beforeEach(() => {
    http = jasmine.createSpyObj<HttpClient>('HttpClient', ['get']);
    service = new PlaidService(http);
  });

  it('types the response with itemStatus, the consent flag and the expiry', async () => {
    http.get.and.returnValue(of({
      accountId: 'acc-1', institutionName: 'Chase', accountName: 'Plaid Checking', accountType: 'depository',
      accountSubtype: 'checking', mask: '0000', itemStatus: 'PENDING_DISCONNECT', transactionsConsented: false,
      consentExpiresAt: '2026-11-20T09:30-05:00',
    }));

    expect(await firstValueFrom(service.getPrimaryBankAccount())).toEqual({
      accountId: 'acc-1', institutionName: 'Chase', accountName: 'Plaid Checking', accountType: 'depository',
      accountSubtype: 'checking', mask: '0000', itemStatus: 'PENDING_DISCONNECT', transactionsConsented: false,
      consentExpiresAt: '2026-11-20T09:30-05:00',
    });
    expect(http.get.calls.mostRecent().args[0]).toMatch(/\/plaid\/primary-bank-account$/);
  });

  it('turns a Jackson date array into an ISO string and missing keys into null', async () => {
    http.get.and.returnValue(of({ accountId: 'acc-1', institutionName: 'Chase', itemStatus: 'OK', consentExpiresAt: [2026, 11, 20, 9, 30] }));

    expect(await firstValueFrom(service.getPrimaryBankAccount())).toEqual({
      accountId: 'acc-1', institutionName: 'Chase', accountName: null, accountType: null, accountSubtype: null,
      mask: null, itemStatus: 'OK', transactionsConsented: null, consentExpiresAt: '2026-11-20T09:30',
    });
  });

  it('is null when unlinked or on any error, and never touches the display cache', async () => {
    http.get.and.returnValue(of({ accountId: null, institutionName: null, itemStatus: 'OK' }));
    expect(await firstValueFrom(service.getPrimaryBankAccount())).toBeNull();

    http.get.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    expect(await firstValueFrom(service.getPrimaryBankAccount())).toBeNull();

    expect(await firstValueFrom(service.getCurrentAccount())).toBeNull();
  });
});
```

Run `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/services/plaid.service.primary-bank-account.spec.ts'`. Expected: no compile; the log shows `error TS2339: Property 'getPrimaryBankAccount' does not exist on type 'PlaidService'.`

- [ ] **Step 4: `PlaidService.getPrimaryBankAccount`**

`services/plaid.service.ts`: replace line 3 `import { BehaviorSubject, Observable, catchError, of, tap } from 'rxjs';` with:

```ts
import { BehaviorSubject, Observable, catchError, map, of, tap } from 'rxjs';
```

After line 5 `import { JwtTokenUtils } from '../utils/jwt-token.utils';` add:

```ts
import { PlaidItemStatus, PrimaryBankAccount } from '../models/primary-bank-account';
```

After the `PlaidLinkResult` interface (its closing `}` at HEAD 25, before `@Injectable({`) add:

```ts

/**
 * GET /plaid/primary-bank-account as it arrives. Any key can be missing, and consentExpiresAt is a
 * LocalDateTime that FRED's primary ObjectMapper (config/JacksonConfig) writes as a [y, m, d, h, min]
 * array unless the controller formats it as a string. Both shapes are accepted.
 */
interface PrimaryBankAccountWire {
  accountId?: string | null;
  institutionName?: string | null;
  accountName?: string | null;
  accountType?: string | null;
  accountSubtype?: string | null;
  mask?: string | null;
  itemStatus?: PlaidItemStatus | null;
  transactionsConsented?: boolean | null;
  consentExpiresAt?: string | number[] | null;
}

function toPrimaryBankAccount(raw: PrimaryBankAccountWire | null): PrimaryBankAccount | null {
  // An unlinked user still reports itemStatus OK, so linkage is judged by the institution.
  if (!raw || !raw.institutionName) {
    return null;
  }
  return {
    accountId: raw.accountId ?? null,
    institutionName: raw.institutionName,
    accountName: raw.accountName ?? null,
    accountType: raw.accountType ?? null,
    accountSubtype: raw.accountSubtype ?? null,
    mask: raw.mask ?? null,
    itemStatus: raw.itemStatus ?? 'OK',
    transactionsConsented: raw.transactionsConsented ?? null,
    consentExpiresAt: toLocalDateTimeString(raw.consentExpiresAt),
  };
}

function toLocalDateTimeString(value: string | number[] | null | undefined): string | null {
  if (value == null) {
    return null;
  }
  if (typeof value === 'string') {
    return value;
  }
  const [year, month, day, hour = 0, minute = 0] = value;
  if (!year || !month || !day) {
    return null;
  }
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${year}-${pad(month)}-${pad(day)}T${pad(hour)}:${pad(minute)}`;
}
```

After `getCurrentAccount()` (HEAD 44-46: `  getCurrentAccount(): Observable<BankAccount | null> {` / `    return this.currentAccountSubject.asObservable();` / `  }`) add:

```ts

  /**
   * The linked bank as the server reports it, typed, with itemStatus (spec 8.8), for surfaces that
   * react to the Item's health such as the reconnect card. Null when no bank is linked or the read
   * fails. Unlike the display cache it never falls back to localStorage or the demo account, and it
   * never writes the cache: a fabricated healthy bank would hide a broken one.
   */
  getPrimaryBankAccount(): Observable<PrimaryBankAccount | null> {
    return this.http.get<PrimaryBankAccountWire>(`${BACKEND_API_URL}/plaid/primary-bank-account`, {
      headers: this.getAuthHeaders()
    }).pipe(
      map(raw => toPrimaryBankAccount(raw)),
      catchError(() => of(null))
    );
  }
```

`loadBankAccountFromBackend`, the localStorage readers and `createDemoAccount` are untouched.

Run the Step 3 command. Expected: `Executed 3 of 3 SUCCESS`. Then the existing reader's spec as a regression gate: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/services/plaid.service.spec.ts'`. Expected: `Executed 4 of 4 SUCCESS` (unchanged).

- [ ] **Step 5: Failing recurring-page cases**

`recurring-investments/recurring-investments.page.spec.ts`: replace line 6 `import { of, throwError } from 'rxjs';` with `import { BehaviorSubject, of, throwError } from 'rxjs';`. After line 13 `import { AnalyticsService } from '../services/analytics/analytics.service';` add:

```ts
import { PlaidService } from '../services/plaid.service';
import { PrimaryBankAccount } from '../models/primary-bank-account';
import { reconnectCardFor } from '../components/reconnect-card/reconnect-card.component';
```

After the line `  let page: RecurringInvestmentsPage;` add:

```ts
  let plaid: jasmine.SpyObj<PlaidService>;
  let router: jasmine.SpyObj<Router>;
  let status$: BehaviorSubject<string | null>;
```

Replace the block from `    accountStatus = jasmine.createSpyObj<AccountStatusService>('AccountStatusService', ['isAccountActive']);` through the `    );` that closes `page = new RecurringInvestmentsPage(` (HEAD 58-74) with:

```ts
    status$ = new BehaviorSubject<string | null>('ACTIVE');
    accountStatus = jasmine.createSpyObj<AccountStatusService>('AccountStatusService', ['isAccountActive'], {
      accountStatus$: status$.asObservable(),
    });
    accountStatus.isAccountActive.and.returnValue(true);
    pin = jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']);
    pin.hasPin.and.resolveTo(false);
    plaid = jasmine.createSpyObj<PlaidService>('PlaidService', ['getPrimaryBankAccount']);
    plaid.getPrimaryBankAccount.and.returnValue(of(null));
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);

    page = new RecurringInvestmentsPage(
      router,
      { queryParams: of({}), params: of({}), snapshot: { queryParams: {} } } as unknown as ActivatedRoute,
      investments,
      jasmine.createSpyObj<PasskeyService>('PasskeyService', ['startAuthentication']),
      pin,
      jasmine.createSpyObj<ToastService>('ToastService', ['showToast']),
      accountStatus,
      jasmine.createSpyObj<AlertController>('AlertController', ['create']),
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      analytics,
      plaid,
    );
```

Replace the file's last four lines (`    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('network', 'portfolio', 'toast', null, false);` / `    expect(analytics.track).not.toHaveBeenCalled();` / `  }));` / `});`) with:

```ts
    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('network', 'portfolio', 'toast', null, false);
    expect(analytics.track).not.toHaveBeenCalled();
  }));

  // ── the reconnect card (R10, R18) ──────────────────────────────────────────

  const brokenBank: PrimaryBankAccount = {
    accountId: 'acc-1',
    institutionName: 'Chase',
    accountName: 'Plaid Checking',
    accountType: 'depository',
    accountSubtype: 'checking',
    mask: '0000',
    itemStatus: 'LOGIN_REQUIRED',
    transactionsConsented: true,
    consentExpiresAt: null,
  };

  it('cardRendersWithoutSchedule: a 404 schedule still loads the bank and yields a card', () => {
    investments.getCurrentSchedule.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
    plaid.getPrimaryBankAccount.and.returnValue(of(brokenBank));

    page.ngOnInit();

    expect(page.currentInvestment).toBeNull();
    expect(page.reconnectAccount).toEqual(brokenBank);
    expect(reconnectCardFor(page.reconnectAccount)).not.toBeNull();
    expect(analytics.track).not.toHaveBeenCalled();
  });

  it('reconnectGoesToChangeBankInUpdateMode', () => {
    page.onReconnect();

    expect(router.navigate).toHaveBeenCalledOnceWith(['/change-bank-account'], { queryParams: { mode: 'update' } });
  });

  it('hiddenForRejectedApplicant: R18, the card is for users who can invest', () => {
    investments.getCurrentSchedule.and.returnValue(of(schedule));
    plaid.getPrimaryBankAccount.and.returnValue(of(brokenBank));
    status$.next('REJECTED');

    page.ngOnInit();

    expect(page.reconnectAccount).toBeNull();
  });
});
```

Run the Step 0 recurring command. Expected: no compile; the log shows `error TS2554: Expected 10 arguments, but got 11.` and `error TS2339: Property 'reconnectAccount' does not exist on type 'RecurringInvestmentsPage'.` and the same for `'onReconnect'`.

- [ ] **Step 6: The card on the recurring page**

`recurring-investments/recurring-investments.page.ts`: after line 36 `import { httpStatusOf, isNetworkFailure } from '../services/analytics/analytics-error.utils';` add:

```ts
import { PlaidService } from '../services/plaid.service';
import { PrimaryBankAccount } from '../models/primary-bank-account';
import { ReconnectCardComponent } from '../components/reconnect-card/reconnect-card.component';
```

In the `imports` array replace `    KeyboardAvoidDirective\n  ],` (HEAD 58-59) with:

```ts
    KeyboardAvoidDirective,
    ReconnectCardComponent
  ],
```

Replace `  isLoading = false;\n  isDatePickerOpen = false;` (HEAD 74-75) with:

```ts
  isLoading = false;
  isDatePickerOpen = false;

  /** The linked bank from GET /plaid/primary-bank-account; null when unlinked or unreadable. */
  bankAccount: PrimaryBankAccount | null = null;
  private accountRejected = false;
```

Replace `    private analytics: AnalyticsService\n  ) {` (HEAD 119-120) with:

```ts
    private analytics: AnalyticsService,
    private plaidService: PlaidService
  ) {
```

In `ngOnInit` replace the quoted block

```ts
        this.suggestedAmount = Number(params['suggestedAmount']);
      }
    });
    this.loadCurrentInvestment();
  }
```

with:

```ts
        this.suggestedAmount = Number(params['suggestedAmount']);
      }
    });
    this.loadCurrentInvestment();
    this.loadBankAccount();
  }
```

After the `ngOnDestroy()` block (HEAD 133-136: `  ngOnDestroy() {` / `    this.destroy$.next();` / `    this.destroy$.complete();` / `  }`) add:

```ts

  /** What the reconnect card is given: nothing for a rejected applicant, who cannot invest (R18). */
  get reconnectAccount(): PrimaryBankAccount | null {
    return this.accountRejected ? null : this.bankAccount;
  }

  /**
   * The bank is read independently of the schedule (R10), so the card shows for a user with no
   * schedule too. A failed read leaves no card and no toast: the schedule is this page's job.
   */
  private loadBankAccount(): void {
    this.accountStatusService.accountStatus$
      .pipe(takeUntil(this.destroy$))
      .subscribe(status => this.accountRejected = status === 'REJECTED');
    this.plaidService.getPrimaryBankAccount()
      .pipe(takeUntil(this.destroy$))
      .subscribe(account => this.bankAccount = account);
  }

  /** Reconnect reopens the same Item in update mode; that page falls back to a fresh link on 409. */
  onReconnect(): void {
    this.router.navigate(['/change-bank-account'], { queryParams: { mode: 'update' } });
  }
```

`recurring-investments/recurring-investments.page.html`: after line 14 `  <div class="shell-inner">` (and before the blank line and `    <div *ngIf="currentInvestment" class="cards-wrapper">`) add:

```html

    <!-- Bank connection (spec 9.7, R10): read from primary-bank-account, so it shows with or without a schedule -->
    <app-reconnect-card [account]="reconnectAccount" (reconnect)="onReconnect()"></app-reconnect-card>
```

The status banner, pause and resume button, modify card and empty state are untouched.

Run the Step 0 recurring command. Expected: `Executed 15 of 15 SUCCESS` (R + 3).

- [ ] **Step 7: Failing update-mode cases on the change-bank page**

`change-bank-account/change-bank-account.page.spec.ts`: after line 11 `import { AnalyticsService } from '../services/analytics/analytics.service';` add:

```ts
import { PLAID_LINK_MODE_KEY, PLAID_LINK_RETURN_KEY, PLAID_LINK_TOKEN_KEY } from '../utils/plaid-link-session';
```

Append at the end of the file, after the last `});`:

```ts

/**
 * Update mode, reached from the reconnect card as /change-bank-account?mode=update (spec 2.3, 8.8).
 * The same Item and access token are kept, so success is update-mode-complete, never an exchange.
 */
describe('ChangeBankAccountPage update mode', () => {
  const RETURN_HREF = 'https://local.fredvested.com/change-bank-account?oauth_state_id=abc123';
  let http: jasmine.SpyObj<HttpClient>;
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let toast: jasmine.SpyObj<ToastService>;
  let page: ChangeBankAccountPage;
  let plaidConfig: any;
  let open: jasmine.Spy;

  const unavailable = () => throwError(() => new HttpErrorResponse({
    status: 409,
    error: { code: 'UPDATE_MODE_UNAVAILABLE', message: 'Plaid could not reopen this bank connection. Link the bank again.' },
  }));
  const postedUrls = () => http.post.calls.allArgs().map(args => args[0] as string);

  beforeEach(() => {
    plaidConfig = null;
    open = jasmine.createSpy('open');
    (window as any).Plaid = { create: (config: any) => { plaidConfig = config; return { open }; } };
    sessionStorage.clear();
    http = jasmine.createSpyObj<HttpClient>('HttpClient', ['get', 'post']);
    http.get.and.returnValue(of({}));
    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', ['track', 'trackErrorShown', 'screen']);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['showToast']);
    const pin = jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']);
    pin.hasPin.and.resolveTo(false);
    page = new ChangeBankAccountPage(
      jasmine.createSpyObj<Router>('Router', ['navigate']),
      http,
      toast,
      jasmine.createSpyObj<PlaidService>('PlaidService', ['refreshBankAccountData']),
      jasmine.createSpyObj<PasskeyService>('PasskeyService', ['startAuthentication']),
      pin,
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      analytics,
    );
    spyOn(page as any, 'requestedLinkMode').and.returnValue('update');
  });

  afterEach(() => {
    delete (window as any).Plaid;
    sessionStorage.clear();
  });

  it('posts mode update and stores the mode with the session only after the step-up', async () => {
    http.post.and.returnValue(of({ link_token: 'link-update-token' }));

    await page['initializePlaidLink']();

    expect(http.post.calls.mostRecent().args[1]).toEqual({ mode: 'update' });
    expect(sessionStorage.getItem(PLAID_LINK_MODE_KEY)).toBeNull();

    await page.openPlaid();

    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBe('change-bank');
    expect(sessionStorage.getItem(PLAID_LINK_MODE_KEY)).toBe('update');
    expect(open).toHaveBeenCalled();
  });

  it('updateModeFallsBackToLinkOn409: UPDATE_MODE_UNAVAILABLE retries with link, silently', async () => {
    http.post.and.returnValues(unavailable(), of({ link_token: 'link-fresh-token' }), of({ message: 'ok' }));

    await page['initializePlaidLink']();

    expect(http.post.calls.allArgs().map(args => args[1])).toEqual([{ mode: 'update' }, { mode: 'link' }]);
    expect(plaidConfig.token).toBe('link-fresh-token');
    expect(analytics.trackErrorShown).not.toHaveBeenCalled();

    await page.openPlaid();
    expect(sessionStorage.getItem(PLAID_LINK_MODE_KEY)).toBe('link');

    plaidConfig.onSuccess('public-sandbox-token', {});
    expect(postedUrls().some(url => url.endsWith('/plaid/exchange_public_token'))).toBeTrue();
    expect(postedUrls().some(url => url.endsWith('/plaid/update-mode-complete'))).toBeFalse();
  });

  it('a 409 for any other reason is not retried and reports bank_link', async () => {
    http.post.and.returnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'LINK_IN_PROGRESS', message: 'busy' } })));

    await page['initializePlaidLink']();

    expect(http.post).toHaveBeenCalledTimes(1);
    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('bank_link', 'portfolio', 'inline_banner', 409, false);
  });

  it('updateSuccessCallsCompleteNotExchange: onSuccess posts update-mode-complete with no body', async () => {
    http.post.and.returnValues(of({ link_token: 'link-update-token' }), of({ result: 'REPAIRED' }));

    await page['initializePlaidLink']();
    await page.openPlaid();
    plaidConfig.onSuccess('public-token-unused-in-update-mode', {});

    expect(postedUrls().filter(url => url.endsWith('/plaid/update-mode-complete')).length).toBe(1);
    expect(postedUrls().some(url => url.endsWith('/plaid/exchange_public_token'))).toBeFalse();
    expect(http.post.calls.mostRecent().args[1]).toEqual({});
    expect(toast.showToast).toHaveBeenCalledWith('Your bank is reconnected.', 'success', 3000);
    expect(sessionStorage.getItem(PLAID_LINK_MODE_KEY)).toBeNull();
  });

  it('CONSENT_STILL_MISSING says so instead of claiming success', async () => {
    http.post.and.returnValues(of({ link_token: 'link-update-token' }), of({ result: 'CONSENT_STILL_MISSING' }));

    await page['initializePlaidLink']();
    await page.openPlaid();
    plaidConfig.onSuccess('public-token-unused-in-update-mode', {});

    expect(toast.showToast).toHaveBeenCalledWith('FRED still needs permission to read deposits at your bank.', 'warning', 5000);
  });

  it('a failed completion reports bank_link on a toast with its status', async () => {
    http.post.and.returnValues(of({ link_token: 'link-update-token' }), throwError(() => new HttpErrorResponse({ status: 502 })));

    await page['initializePlaidLink']();
    await page.openPlaid();
    plaidConfig.onSuccess('public-token-unused-in-update-mode', {});

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('bank_link', 'portfolio', 'toast', 502, false);
    expect(page.statusMessage).toBe('Could not finish reconnecting your bank. Please try again.');
  });

  it('an OAuth return resumes in the mode stored with the session, not the URL', async () => {
    sessionStorage.setItem(PLAID_LINK_TOKEN_KEY, 'link-stored-token');
    sessionStorage.setItem(PLAID_LINK_RETURN_KEY, 'change-bank');
    sessionStorage.setItem(PLAID_LINK_MODE_KEY, 'update');
    // The return URL carries oauth_state_id, not ?mode=update.
    (page as any).requestedLinkMode.and.returnValue('link');
    spyOn(page as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);
    http.post.and.returnValue(of({ result: 'CONSENT_GRANTED' }));

    await page['initializePlaidLink']();
    expect(http.post).not.toHaveBeenCalled();

    plaidConfig.onSuccess('public-token-unused-in-update-mode', {});
    expect(postedUrls()).toEqual([jasmine.stringMatching(/\/plaid\/update-mode-complete$/)]);
  });
});
```

Run the Step 0 change-bank page command. Expected: no compile; the log shows `error TS2305: Module '"../utils/plaid-link-session"' has no exported member 'PLAID_LINK_MODE_KEY'.`

- [ ] **Step 8: Mode in the session, and update mode on the change-bank page**

`utils/plaid-link-session.ts`. After the quoted line `export const PLAID_LINK_RETURN_KEY = 'fred.plaid.linkReturn';` add:

```ts
export const PLAID_LINK_MODE_KEY = 'fred.plaid.linkMode';
```

After the quoted line `export type PlaidLinkReturnTo = 'onboarding' | 'change-bank';` add:

```ts

/**
 * The mode the parked token was created with (spec 2.3). An update-mode token must finish with
 * POST /plaid/update-mode-complete, never an exchange, and the OAuth return URL does not say which.
 */
export type PlaidLinkMode = 'link' | 'update';
```

Replace `storePlaidLinkSession` (HEAD 12-19, from `export function storePlaidLinkSession(linkToken: string, returnTo: PlaidLinkReturnTo): void {` through its closing `}`) with:

```ts
export function storePlaidLinkSession(linkToken: string, returnTo: PlaidLinkReturnTo, mode: PlaidLinkMode = 'link'): void {
  try {
    sessionStorage.setItem(PLAID_LINK_TOKEN_KEY, linkToken);
    sessionStorage.setItem(PLAID_LINK_RETURN_KEY, returnTo);
    sessionStorage.setItem(PLAID_LINK_MODE_KEY, mode);
  } catch {
    // Storage blocked: the OAuth return starts Link over with a fresh token.
  }
}
```

After `readPlaidLinkReturn` (the quoted block `    return sessionStorage.getItem(PLAID_LINK_RETURN_KEY) as PlaidLinkReturnTo | null;` / `  } catch {` / `    return null;` / `  }` / `}`) add:

```ts

/** The stored mode; anything but an explicit 'update', including nothing stored, is 'link'. */
export function readPlaidLinkMode(): PlaidLinkMode {
  try {
    return sessionStorage.getItem(PLAID_LINK_MODE_KEY) === 'update' ? 'update' : 'link';
  } catch {
    return 'link';
  }
}
```

In `clearPlaidLinkSession`, after the quoted line `    sessionStorage.removeItem(PLAID_LINK_RETURN_KEY);` add:

```ts
    sessionStorage.removeItem(PLAID_LINK_MODE_KEY);
```

`change-bank-account/change-bank-account.page.ts`. Replace line 6 `import { HttpClient, HttpHeaders } from '@angular/common/http';` with `import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';`. In the import from `'../utils/plaid-link-session'` (HEAD line 10) add `PlaidLinkMode` and `readPlaidLinkMode` to the braces, keeping every name already there; after T20 the line reads:

```ts
import { clearPlaidLinkSession, PlaidLinkMode, plaidOAuthStateId, readPlaidLinkMode, readPlaidLinkReturn, readPlaidLinkToken, readPlaidOAuthReturnHref, storePlaidLinkSession } from '../utils/plaid-link-session';
```

(T20's `oauthReturnHref()` calls `plaidOAuthStateId`, so dropping it from this line fails the build with `error TS2304: Cannot find name 'plaidOAuthStateId'.`)

After it add `import { UpdateModeCompleteResponse } from '../models/primary-bank-account';`. After `const BACKEND_API_URL = environment.backendApiUrl;` add:

```ts

/** The server's answer when Plaid refuses an update-mode token (PlaidController create_link_token, spec 8.8). */
function isUpdateModeUnavailable(error: unknown): boolean {
  return error instanceof HttpErrorResponse && error.status === 409 && error.error?.code === 'UPDATE_MODE_UNAVAILABLE';
}
```

After `  private currentLinkToken: string | null = null;` add:

```ts
  /** The mode the current token was created with; decides what onSuccess does (spec 2.3). */
  private linkMode: PlaidLinkMode = 'link';
```

In `initializePlaidLink` replace the quoted block (HEAD 134-146):

```ts
      let linkToken: string;
      if (resuming) {
        linkToken = storedToken as string;
      } else {
        const linkTokenData = await this.getLinkTokenAuthenticated().toPromise();
```

with:

```ts
      let linkToken: string;
      if (resuming) {
        linkToken = storedToken as string;
        // The OAuth return URL carries oauth_state_id, not ?mode, so the mode comes back with the session.
        this.linkMode = readPlaidLinkMode();
      } else {
        const linkTokenData = await this.requestLinkToken(this.requestedLinkMode());
```

(the lines from `        linkToken = linkTokenData.link_token;` through `      this.currentLinkToken = linkToken;` stay as they are). In `onSuccess` replace the quoted block

```ts
          this.isLoading = true;
          this.exchangePublicTokenAuthenticated(public_token);
        },
```

with:

```ts
          this.isLoading = true;
          if (this.linkMode === 'update') {
            // Same Item, same access token: nothing to exchange. The server re-reads consent (spec 2.3).
            this.completeUpdateMode();
          } else {
            this.exchangePublicTokenAuthenticated(public_token);
          }
        },
```

In `onLoad` replace `          this.statusMessage = 'Ready to change your bank account.';` with:

```ts
          this.statusMessage = this.linkMode === 'update' ? 'Ready to reconnect your bank.' : 'Ready to change your bank account.';
```

In `openPlaid` replace `        storePlaidLinkSession(this.currentLinkToken, 'change-bank');` with `        storePlaidLinkSession(this.currentLinkToken, 'change-bank', this.linkMode);`. The PIN step-up above it is unchanged, so an update-mode session is only ever stored after it.

Replace `getLinkTokenAuthenticated` (HEAD 233-237, from `  private getLinkTokenAuthenticated() {` through its closing `  }`) with:

```ts
  private getLinkTokenAuthenticated(mode: PlaidLinkMode = 'link') {
    return this.http.post<any>(`${BACKEND_API_URL}/plaid/create_link_token`, { mode }, {
      headers: this.getAuthHeaders()
    });
  }

  /**
   * A token in the requested mode. Plaid can refuse update mode (after USER_PERMISSION_REVOKED, or
   * with no Item) and the server answers 409 UPDATE_MODE_UNAVAILABLE; a fresh link is then the way
   * back, and its exchange queues the old Item for removal (spec 2.3, 8.8). Any other error rethrows.
   */
  private async requestLinkToken(mode: PlaidLinkMode): Promise<{ link_token: string }> {
    try {
      const data = await this.getLinkTokenAuthenticated(mode).toPromise();
      this.linkMode = mode;
      return data;
    } catch (error) {
      if (mode === 'update' && isUpdateModeUnavailable(error)) {
        const data = await this.getLinkTokenAuthenticated('link').toPromise();
        this.linkMode = 'link';
        return data;
      }
      throw error;
    }
  }

  /** 'update' only when opened as /change-bank-account?mode=update (the reconnect card). Stubbed in specs. */
  private requestedLinkMode(): PlaidLinkMode {
    return new URLSearchParams(window.location.search).get('mode') === 'update' ? 'update' : 'link';
  }
```

Before `  private getAuthHeaders(): HttpHeaders {` add:

```ts
  /** Update mode keeps the Item and its access token, so the server only re-reads it (spec 2.3, 8.8). */
  private completeUpdateMode(): void {
    this.plaidSubscription = this.http.post<UpdateModeCompleteResponse>(
      `${BACKEND_API_URL}/plaid/update-mode-complete`,
      {},
      { headers: this.getAuthHeaders() }
    ).subscribe({
      next: (response) => {
        this.isLoading = false;
        if (response?.result === 'CONSENT_STILL_MISSING') {
          this.statusMessage = 'Your bank is connected. FRED still needs permission to read deposits.';
          this.toastService.showToast('FRED still needs permission to read deposits at your bank.', 'warning', 5000);
        } else {
          this.statusMessage = 'Your bank connection is up to date.';
          this.toastService.showToast('Your bank is reconnected.', 'success', 3000);
        }
        void this.loadCurrentBankAccount();
        this.plaidService.refreshBankAccountData();
      },
      error: (err) => {
        this.isLoading = false;
        this.analytics.trackErrorShown(
          isNetworkFailure(err) ? 'network' : 'bank_link',
          'portfolio',
          'toast',
          httpStatusOf(err),
          false,
        );
        this.statusMessage = 'Could not finish reconnecting your bank. Please try again.';
        this.toastService.showToast('Could not finish reconnecting your bank. Please try again.', 'danger', 5000);
      }
    });
  }

```

The error copy contains "Could not", so `change-bank-account.page.html:119` puts it in the error card with "Try again"; the status line never carries a server message, a code or an amount.

Run the Step 0 change-bank page command. Expected: `Executed 16 of 16 SUCCESS` (C + 7). Then the Step 0 change-bank OAuth command. Expected: `Executed O of O SUCCESS` (unchanged; 5 at HEAD plus T20's cases). Then T20's two other consumers of `plaid-link-session.ts`, as regression gates: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/utils/plaid-link-session.spec.ts' --include='**/app.component.oauth-return.spec.ts'`. Expected: `TOTAL: 11 SUCCESS` (T20's 5 plus 6, unchanged).

- [ ] **Step 9: Failing onboarding-page cases**

`components/linkplaid/linkplaid.component.oauth.spec.ts`: add `PLAID_LINK_MODE_KEY` to the braces of the import from `'../../utils/plaid-link-session'` (HEAD line 7), keeping every name already there. Before the quoted line `  it('goBack returns to KYC verification', () => {` add:

```ts
  it('updateSuccessCallsCompleteNotExchange: a resumed update-mode session completes instead of exchanging', async () => {
    sessionStorage.setItem(PLAID_LINK_TOKEN_KEY, 'link-stored-token');
    sessionStorage.setItem(PLAID_LINK_RETURN_KEY, 'onboarding');
    sessionStorage.setItem(PLAID_LINK_MODE_KEY, 'update');
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);
    post.and.returnValue(of({ result: 'REPAIRED' }));

    await component.initializePlaidLink();
    plaidConfig.onSuccess('public-token-unused-in-update-mode', {});

    expect(post.calls.allArgs().map(args => args[0] as string)).toEqual([jasmine.stringMatching(/\/plaid\/update-mode-complete$/)]);
    expect(navCtrl.navigateForward).toHaveBeenCalledWith('/investment-schedule', { replaceUrl: true });
  });

  it('linkModeSuccessStillExchanges: a fresh onboarding token never calls update-mode-complete', async () => {
    await component.initializePlaidLink();
    expect(sessionStorage.getItem(PLAID_LINK_MODE_KEY)).toBe('link');
    post.and.returnValue(of({ message: 'ok' }));

    plaidConfig.onSuccess('public-sandbox-token', {});

    expect(post.calls.mostRecent().args[0]).toMatch(/\/plaid\/exchange_public_token$/);
  });

```

Run the Step 0 linkplaid OAuth command. Expected: `Executed L+2 of L+2` with 1 FAILED: `updateSuccessCallsCompleteNotExchange` fails with `Expected $[0] = '.../plaid/exchange_public_token' to equal <jasmine.stringMatching(/\/plaid\/update-mode-complete$/)>.`; `linkModeSuccessStillExchanges` passes (Step 8's default `mode = 'link'` already stores `link`).

- [ ] **Step 10: The same `onSuccess` branch on the onboarding page**

`components/linkplaid/linkplaid.component.ts`. Replace line 9 `import { Observable, throwError, Subscription } from 'rxjs';` with `import { Observable, of, throwError, Subscription } from 'rxjs';`. In the import from `'../../utils/plaid-link-session'` (HEAD line 16) add `PlaidLinkMode` and `readPlaidLinkMode` to the braces, keeping every name already there; after T20 the line reads:

```ts
import { clearPlaidLinkSession, PlaidLinkMode, readPlaidLinkMode, readPlaidLinkReturn, readPlaidLinkToken, storePlaidLinkSession, storePlaidOAuthReturnHref } from '../../utils/plaid-link-session';
```

After it add `import { UpdateModeCompleteResponse } from '../../models/primary-bank-account';`. After `  private plaidSubscription: Subscription | undefined;` add:

```ts
  /** The mode the current token was created with (spec 2.3). This page only asks for 'link'. */
  private linkMode: PlaidLinkMode = 'link';
```

Replace the quoted block

```ts
      let linkToken: string;

      if (this.isUserAuthenticated) {
```

with:

```ts
      let linkToken: string;
      // A resumed session carries the mode it was stored with; a fresh token here is always 'link'.
      this.linkMode = resuming ? readPlaidLinkMode() : 'link';

      if (this.isUserAuthenticated) {
```

In `onSuccess` replace the quoted block

```ts
          if (this.isUserAuthenticated) {
            this.exchangePublicTokenAuthenticated(public_token);
          } else {
```

with:

```ts
          if (this.isUserAuthenticated) {
            if (this.linkMode === 'update') {
              // Same Item, same access token: nothing to exchange (spec 2.3).
              this.completeUpdateMode();
            } else {
              this.exchangePublicTokenAuthenticated(public_token);
            }
          } else {
```

Before the quoted line `  openPlaid() {` add:

```ts
  /**
   * Update mode keeps the Item, so the bank step is already satisfied: finish it exactly as a
   * successful exchange does. A failure leaves the user here with the retry button.
   */
  private completeUpdateMode(): void {
    this.plaidSubscription = this.http.post<UpdateModeCompleteResponse>(`${BACKEND_API_URL}/plaid/update-mode-complete`, {}, { headers: this.getAuthHeaders() })
      .pipe(
        tap(() => {
          this.statusMessage = 'Bank account reconnected!';
          this.authService.completeStep('linkPlaid').subscribe({
            next: () => this.navCtrl.navigateForward('/investment-schedule', { replaceUrl: true }),
            error: () => this.navCtrl.navigateForward('/investment-schedule', { replaceUrl: true }),
          });
        }),
        catchError(err => {
          this.isLoading = false;
          this.analytics.trackErrorShown('bank_link', 'onboarding', 'inline_banner', httpStatusOf(err), true);
          this.statusMessage = 'Error: Could not finish reconnecting your bank. Please try again.';
          return of(null);
        })
      ).subscribe();
  }

```

`storePlaidLinkSession(linkToken, 'onboarding');` stays as it is: the default mode is `'link'`.

Run the Step 0 linkplaid OAuth command. Expected: `Executed L+2 of L+2 SUCCESS` (L is 11 after T20: 7 at HEAD plus T20's 4; so 13). Then the Step 0 linkplaid analytics command. Expected: `Executed 7 of 7 SUCCESS` (A, unchanged).

- [ ] **Step 11: Type check, lint, AOT build, taxonomy**

```
cd frontend
npx tsc --noEmit
npx ng lint
npx ng build --configuration dev; echo "build exit $?"
npm run test:events-documented
```

Expected: `tsc` prints nothing; `ng lint` ends `All files pass linting.`; the build ends with the bundle summary and `build exit 0` (`dev` is a real configuration; `development` is not and exits 127); the events check passes unchanged, because this task adds no client event and no property key.

- [ ] **Step 12: Static checks on what this task wrote**

```
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1
EMDASH="$(printf '\342\200\224')"
git diff HEAD -- frontend/src | grep -n "^+.*$EMDASH"
grep -n "$EMDASH" frontend/src/app/models/primary-bank-account.ts frontend/src/app/components/reconnect-card/reconnect-card.component.* frontend/src/app/services/plaid.service.primary-bank-account.spec.ts
grep -n 'app-reconnect-card\|class="cards-wrapper"' frontend/src/app/recurring-investments/recurring-investments.page.html
grep -n 'getIsPaused\|isPausedForAnyReason\|holdReason' frontend/src/app/components/reconnect-card/reconnect-card.component.ts frontend/src/app/models/primary-bank-account.ts
grep -n "trackErrorShown\|\.track(" frontend/src/app/components/reconnect-card/reconnect-card.component.ts
```

Expected: the first two print nothing (no em dash added; existing lines with one are not re-added); the third prints the `app-reconnect-card` line BEFORE the `cards-wrapper` line, so the card sits outside `*ngIf="currentInvestment"`; the last two print nothing (the card reads no schedule state and tracks nothing).

- [ ] **Step 13: Manual prerequisite for Andy (CI gate)**

The routine's git credentials cannot push `.github/workflows/*`, so the implementer does not edit `verify.yml`. Hand Andy this line for `.github/workflows/verify.yml`, to add after the line that runs `**/services/analytics/*.spec.ts`, from his local checkout:

```
          npx ng test --include='**/components/reconnect-card/*.spec.ts' --include='**/services/plaid.service.primary-bank-account.spec.ts' --include='**/recurring-investments/recurring-investments.page.spec.ts' --include='**/change-bank-account/*.spec.ts' --include='**/components/linkplaid/linkplaid.component.oauth.spec.ts' --include='**/components/linkplaid/linkplaid.component.analytics.spec.ts' --watch=false --browsers=ChromeHeadless
```

Device check (Andy, optional before Phase 1 exit; local backend through the tunnel with `https://local.fredvested.com/link-bank`, since there is no deployed dev front end): link a Sandbox bank (`user_good`), then in the local database named by `spring.datasource.url` in `application-local.properties` run `UPDATE users SET plaid_item_status = 'LOGIN_REQUIRED' WHERE plaid_item_id IS NOT NULL ORDER BY plaid_item_linked_at DESC LIMIT 1;`. Open Recurring Investments: the red card reads "Reconnect your bank" above the schedule, and Pause stays. Tap Reconnect, pass the PIN, finish Plaid: the toast says "Your bank is reconnected.", and on reopening Recurring Investments the card is gone (T16 moved the Item back to OK).

- [ ] **Step 14: Commit**

Hunk-verify each modified file with `git diff HEAD -- <file>` (concurrent sessions edit this checkout; stage nothing you did not write), then:

```
git add frontend/src/app/models/primary-bank-account.ts frontend/src/app/components/reconnect-card/reconnect-card.component.ts frontend/src/app/components/reconnect-card/reconnect-card.component.html frontend/src/app/components/reconnect-card/reconnect-card.component.scss frontend/src/app/components/reconnect-card/reconnect-card.component.spec.ts frontend/src/app/services/plaid.service.ts frontend/src/app/services/plaid.service.primary-bank-account.spec.ts frontend/src/app/utils/plaid-link-session.ts frontend/src/app/recurring-investments/recurring-investments.page.ts frontend/src/app/recurring-investments/recurring-investments.page.html frontend/src/app/recurring-investments/recurring-investments.page.spec.ts frontend/src/app/change-bank-account/change-bank-account.page.ts frontend/src/app/change-bank-account/change-bank-account.page.spec.ts frontend/src/app/components/linkplaid/linkplaid.component.ts frontend/src/app/components/linkplaid/linkplaid.component.oauth.spec.ts
git commit -m "feat(plaid): reconnect card on recurring investments and the update-mode Link path"
```

Never stage `application-local.properties`, `ITPM/agent-memory/*` or `.github/workflows/verify.yml`.

### Verification

From `frontend/`, one spec at a time (Karma's exit code is 0 even on a compile failure; read the `Executed` line):

| Command | Expected |
|---|---|
| `npx ng test --watch=false --browsers=ChromeHeadless --include='**/components/reconnect-card/*.spec.ts'` | `Executed 8 of 8 SUCCESS` |
| `npx ng test --watch=false --browsers=ChromeHeadless --include='**/services/plaid.service.primary-bank-account.spec.ts'` | `Executed 3 of 3 SUCCESS` |
| `npx ng test --watch=false --browsers=ChromeHeadless --include='**/services/plaid.service.spec.ts'` | `Executed 4 of 4 SUCCESS` (unchanged) |
| `npx ng test --watch=false --browsers=ChromeHeadless --include='**/utils/plaid-link-session.spec.ts' --include='**/app.component.oauth-return.spec.ts'` | `TOTAL: 11 SUCCESS` (T20's specs, unchanged) |
| `npx ng test --watch=false --browsers=ChromeHeadless --include='**/recurring-investments/recurring-investments.page.spec.ts'` | `Executed 15 of 15 SUCCESS` (12 + 3) |
| `npx ng test --watch=false --browsers=ChromeHeadless --include='**/change-bank-account/change-bank-account.page.spec.ts'` | `Executed 16 of 16 SUCCESS` (9 + 7, plus any case T20 added here) |
| `npx ng test --watch=false --browsers=ChromeHeadless --include='**/change-bank-account/change-bank-account.page.oauth.spec.ts'` | the Step 0 count, all SUCCESS |
| `npx ng test --watch=false --browsers=ChromeHeadless --include='**/components/linkplaid/linkplaid.component.oauth.spec.ts'` | the Step 0 count plus 2, all SUCCESS |
| `npx ng test --watch=false --browsers=ChromeHeadless --include='**/components/linkplaid/linkplaid.component.analytics.spec.ts'` | `Executed 7 of 7 SUCCESS` |
| `npx tsc --noEmit`; `npx ng lint`; `npx ng build --configuration dev`; `npm run test:events-documented` | no output; `All files pass linting.`; exit 0; pass |

Plus the Step 12 greps. The full frontend suite still shows only the 14 pre-existing failures (FRED-226); none of them is in a file this task touches. No backend test runs: this task changes no backend file.

### Produces

- `frontend/src/app/models/primary-bank-account.ts`: `PlaidItemStatus`, `PrimaryBankAccount { accountId, institutionName, accountName, accountType, accountSubtype, mask, itemStatus: PlaidItemStatus, transactionsConsented: boolean | null, consentExpiresAt: string | null }`, `UpdateModeResult`, `UpdateModeCompleteResponse`.
- `frontend/src/app/utils/plaid-link-session.ts`: `PLAID_LINK_MODE_KEY`, `PlaidLinkMode = 'link' | 'update'`, `storePlaidLinkSession(linkToken, returnTo, mode = 'link')`, `readPlaidLinkMode(): PlaidLinkMode`; `clearPlaidLinkSession()` also clears the mode.
- `PlaidService.getPrimaryBankAccount(): Observable<PrimaryBankAccount | null>` (display cache and demo fallback untouched).
- `ReconnectCardComponent` (`app-reconnect-card`, `@Input() account`, `@Output() reconnect`), `ReconnectCard { title, sub, showButton }`, `reconnectCardFor(account): ReconnectCard | null` (ITEM_BROKEN copy for LOGIN_REQUIRED and REVOKED, NEEDS_CONSENT when `transactionsConsented === false`, the PENDING_DISCONNECT date line, spec 10's MANUAL sentence; null for OK, REMOVED, SYNC_LIMIT, NOT_SUPPORTED and unlinked).
- `RecurringInvestmentsPage`: card above the schedule wrapper, shown with or without a schedule, hidden for a REJECTED account (R18); `reconnectAccount`, `onReconnect()` to `/change-bank-account?mode=update`; constructor's eleventh parameter `PlaidService`; pause and resume untouched.
- `ChangeBankAccountPage`: reads `?mode`, posts `{ mode }`, retries `link` on 409 `UPDATE_MODE_UNAVAILABLE`, stores the mode with the session after the PIN step-up, resumes in the stored mode, and on an update-mode `onSuccess` posts `/plaid/update-mode-complete` instead of exchanging.
- `LinkPlaidComponent`: the same `onSuccess` branch for a resumed update-mode session.
- No client analytics event or property; no backend change; no property added.
