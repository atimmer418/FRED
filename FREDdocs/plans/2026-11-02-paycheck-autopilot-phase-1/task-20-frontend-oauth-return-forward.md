### Task 20: frontend-oauth-return-forward

A change-bank OAuth return that lands on `/link-bank` reaches `/change-bank-account` with its query string intact, instead of being discarded (ruling R9). The task does three things. It parks the href Plaid redirected to, so the change-bank page can resume Link with that href as `receivedRedirectUri`. It makes `LinkPlaidComponent` forward a change-bank return before any `Plaid.create`, leaving the session alone. And it makes `AppComponent` keep an OAuth return on a cold start, because without that the forward is never reached (see "Why AppComponent is in this task"). The task is frontend only. It adds no property, no endpoint, no analytics event and no analytics property. It touches no backend file and no money path: nothing here can move money, write `InvestmentSchedule.status`, change `isReadyForInvestment`, write a hold, or call Plaid or Alpaca.

Paths are repo-relative; `F/` = `frontend/src/app/`. Every anchor below was checked against HEAD 137998c on `feature/paycheck-autopilot-phase-1`. T20 depends on no other task and is a parallel start. No earlier task edits these files, so the line numbers hold. Each edit still quotes the text it replaces, so it applies by text, not by line.

**Facts this task depends on (restated so this file stands alone):**

- **R9, verbatim in substance.** Andy registered one redirect URI per environment, ending in `/link-bank`: local `https://local.fredvested.com/link-bank`, dev `https://dev.fredvested.com/link-bank` (Railway dev `PLAID_REDIRECT_URI` was set 2026-11-02, R16). The backend puts that one URI on every link token, whichever page asked for it (`plaid.redirect.uri`). So every OAuth return lands on `/link-bank`, including one started on `/change-bank-account`. Today `LinkPlaidComponent` (`F/components/linkplaid/linkplaid.component.ts:80-88`) sees the stored marker `'change-bank'`, clears the session, starts a fresh onboarding Link, and on success calls `completeStep('linkPlaid')` and navigates to `/investment-schedule`. The user's bank change is lost. R9: forward to `/change-bank-account` with the full query string, instead of clearing the session. Cost if wrong: none, the forward is a navigation.
- **The session slot** (`F/utils/plaid-link-session.ts`, 49 lines): `sessionStorage` keys `fred.plaid.linkToken` and `fred.plaid.linkReturn`, `type PlaidLinkReturnTo = 'onboarding' | 'change-bank'`, `storePlaidLinkSession`, `readPlaidLinkToken`, `readPlaidLinkReturn`, `clearPlaidLinkSession`. Both pages share it. Onboarding stores before `open()`. Change-bank stores only after the PIN step-up in `openPlaid` (`change-bank-account.page.ts:221-225`), so a stored change-bank token proves the step-up passed, and the resume skips it. Both pages consume the session right after resuming (single use).
- **Why AppComponent is in this task.** `frontend/src/main.ts:29` boots the router with `withDisabledInitialNavigation()`. Nothing navigates to the URL the browser opened. `AppComponent` picks the first route. It calls `tryOptimisticNav()` in `ngOnInit` (`app.component.ts:175`): for a JWT whose `ns` claim is `'complete'`, while `router.url` is still `'/'`, it navigates to `/tabs/tab1`. Then `navigateBasedOnProgress` (`:312-366`) calls `router.navigateByUrl(targetRoute, { replaceUrl: true })` with a bare path. Angular 19 rewrites the browser URL at `BeforeActivateRoutes`, before the routed component's `ngOnInit`. So on an OAuth return, which is a fresh page load at `/link-bank?oauth_state_id=...`, two things go wrong. A fully onboarded user (the only kind who uses change-bank) goes to `/tabs/tab1`, and `LinkPlaidComponent` never mounts. A mid-onboarding user goes to `/link-bank` with no query string, so `oauthReturnHref()` returns null and Phase 0's onboarding resume never fires. The contract's forward would therefore never run on the path R9 describes. This task adds a small capture to `AppComponent`. It is listed as a deviation in the Produces recap.
- **What Plaid is told.** `receivedRedirectUri` is the URL the bank redirected to, which is the `/link-bank` href. After a forward, the address bar reads `/change-bank-account?oauth_state_id=...`. Plaid's matching rule against the token's `redirect_uri` is unverified, so the change-bank page resumes with the stored `/link-bank` href (Decision 73, I34). It uses the stored href only when that href carries the current `oauth_state_id`, so a leftover from an earlier round trip can never stand in.
- **Karma conventions** (copy them). The command is `npx ng test --watch=false --browsers=ChromeHeadless --include='<glob>'` from `frontend/`. Always scope with `--include`: the bare run fails on legacy TestBed specs. `--include` still type-checks the whole app. Karma can exit 0 when the bundle fails to compile, so read the log, not the exit status. Specs build the class with `new Page(...)` and spies, no TestBed, except `app.component.spec.ts`, which uses TestBed with stubs (copied below). Private methods are stubbed with `spyOn(x as any, 'name')`. Baseline at HEAD, measured: `linkplaid.component.oauth.spec.ts` 7, `linkplaid.component.analytics.spec.ts` 7, `change-bank-account.page.oauth.spec.ts` 5, `change-bank-account.page.spec.ts` 9, `app.component.spec.ts` 8, all SUCCESS. None of them is among the 14 known failures (FRED-226).
- **House rules this task keeps.** No em dashes in any new line (code, comments, spec titles, commit message). Existing lines that contain one are not quoted or touched. No new client analytics. One existing emission is suppressed where it would be false; see Step 4. Never `git checkout`, `git restore`, `git stash` or `git reset` on this tree. Stage only the files named in Step 11.

**Local-properties note:** this task adds no property. The gitignored `backend/src/main/resources/application-local.properties` needs no edit for T20. The optional device check in Step 10 runs against the local backend, through the tunnel, with the existing `plaid.redirect.uri` pointing at `https://local.fredvested.com/link-bank`. Check that key's value in your local file without printing any secret.

**Manual prerequisite (not a step here):** CI gates a spec only once its glob is in `.github/workflows/verify.yml`. The itpm routine cannot push workflow files, so Andy adds this line from his own checkout, next to the existing `npx ng test --include=...` lines (`verify.yml:119,121`). Do not edit `verify.yml` in this task:

```
npx ng test --include='**/utils/plaid-link-session.spec.ts' --include='**/linkplaid/linkplaid.component.oauth.spec.ts' --include='**/change-bank-account/change-bank-account.page.oauth.spec.ts' --include='**/app.component.oauth-return.spec.ts' --watch=false --browsers=ChromeHeadless
```

**Files:**
- Modify: `F/utils/plaid-link-session.ts` (whole file, 49 lines, replaced in Step 2)
- Modify: `F/components/linkplaid/linkplaid.component.ts:16` (import), `:65-66` (`ngOnInit` view event), `:76-88` (return gate), after `:244` (new private helper)
- Modify: `F/change-bank-account/change-bank-account.page.ts:10` (import), `:239-242` (`oauthReturnHref`)
- Modify: `F/app.component.ts:14` (import), `:45` (field), `:174-175` (cold-start capture), `:333-336` (`navigateBasedOnProgress`), before `:368` (three private methods), `:498-499` (`tryOptimisticNav` guard)
- Test: create `F/utils/plaid-link-session.spec.ts` (5 specs) and `F/app.component.oauth-return.spec.ts` (6 specs). Replace `F/components/linkplaid/linkplaid.component.oauth.spec.ts` (7 to 11 specs; one existing case rewritten, see Step 3). Modify `F/change-bank-account/change-bank-account.page.oauth.spec.ts` (5 to 8 specs). Unchanged regression gates: `linkplaid.component.analytics.spec.ts`, `change-bank-account.page.spec.ts`, `app.component.spec.ts`.

**Interfaces:**
- Consumes (all at HEAD, no other task): `storePlaidLinkSession(linkToken: string, returnTo: PlaidLinkReturnTo): void`, `readPlaidLinkToken(): string | null`, `readPlaidLinkReturn(): PlaidLinkReturnTo | null`, `clearPlaidLinkSession(): void`, `PLAID_LINK_TOKEN_KEY`, `PLAID_LINK_RETURN_KEY` (`F/utils/plaid-link-session.ts`); `JwtTokenUtils.getValidJwtToken(): string | null` (`F/utils/jwt-token.utils.ts:118`); `Router.navigateByUrl(url, { replaceUrl: true })`; `AppComponent.navigateBasedOnProgress(progress: UserProgress): void` (public, `:312`); `UserProgress` (`F/services/auth.service.ts:15`).
- Produces: `export const PLAID_OAUTH_RETURN_HREF_KEY = 'fred.plaid.oauthReturnHref'`; `export function storePlaidOAuthReturnHref(href: string): void`; `export function readPlaidOAuthReturnHref(): string | null`; `clearPlaidLinkSession()` also removes `PLAID_OAUTH_RETURN_HREF_KEY`; `export function plaidOAuthStateId(href: string): string | null`; `export function plaidOAuthReturnRoute(href: string): string | null`; `PlaidLinkReturnTo` and `storePlaidLinkSession`'s signature unchanged. Behaviour: `LinkPlaidComponent.initializePlaidLink()` forwards a change-bank return to `'/change-bank-account' + <query>` with `{ replaceUrl: true }` before any `Plaid.create`, and keeps the session. `ChangeBankAccountPage` resumes with the stored `/link-bank` href. `AppComponent` sends a cold-start OAuth return to its page with the query kept.
- Seam with T21 (depends on T20). T21 adds `mode` to the session (`storePlaidLinkSession(linkToken, returnTo, mode = 'link')`, `readPlaidLinkMode()`). It must keep the `PLAID_OAUTH_RETURN_HREF_KEY` removal in `clearPlaidLinkSession`. It must read the update mode from the session, not from the URL: a forwarded return carries only Plaid's query string, so `?mode=update` from the original change-bank URL is not on it. T21 also edits both pages' `onSuccess` callbacks, which this task does not touch. After this task the change-bank page's session import (`change-bank-account.page.ts:10`) also names `plaidOAuthStateId` and `readPlaidOAuthReturnHref`, and the linkplaid import names `storePlaidOAuthReturnHref`; T21 must keep every one of them when it adds its own names.

- [ ] **Step 1: Failing spec for the session helpers**

Create `frontend/src/app/utils/plaid-link-session.spec.ts`:

```ts
import {
  PLAID_LINK_RETURN_KEY,
  PLAID_LINK_TOKEN_KEY,
  PLAID_OAUTH_RETURN_HREF_KEY,
  clearPlaidLinkSession,
  plaidOAuthReturnRoute,
  plaidOAuthStateId,
  readPlaidOAuthReturnHref,
  storePlaidLinkSession,
  storePlaidOAuthReturnHref,
} from './plaid-link-session';

/** The one redirect URI registered for the local environment (R9), as the bank sends the user back. */
const LINK_BANK_HREF = 'https://local.fredvested.com/link-bank?oauth_state_id=abc123';

describe('plaid-link-session OAuth return helpers', () => {
  beforeEach(() => sessionStorage.clear());
  afterEach(() => sessionStorage.clear());

  it('stores and reads the OAuth return href, and clearPlaidLinkSession removes it with the session', () => {
    expect(readPlaidOAuthReturnHref()).toBeNull();
    storePlaidLinkSession('link-token', 'change-bank');
    storePlaidOAuthReturnHref(LINK_BANK_HREF);

    expect(sessionStorage.getItem(PLAID_OAUTH_RETURN_HREF_KEY)).toBe(LINK_BANK_HREF);
    expect(readPlaidOAuthReturnHref()).toBe(LINK_BANK_HREF);

    clearPlaidLinkSession();

    expect(readPlaidOAuthReturnHref()).toBeNull();
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBeNull();
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBeNull();
  });

  it('reads the oauth_state_id, and null when there is none or the href is not a URL', () => {
    expect(plaidOAuthStateId(LINK_BANK_HREF)).toBe('abc123');
    expect(plaidOAuthStateId('https://local.fredvested.com/link-bank')).toBeNull();
    expect(plaidOAuthStateId('not a url')).toBeNull();
  });

  it('routes a change-bank return to /change-bank-account with the whole query string kept', () => {
    storePlaidLinkSession('link-token', 'change-bank');

    expect(plaidOAuthReturnRoute('https://local.fredvested.com/link-bank?oauth_state_id=abc123&x=1'))
      .toBe('/change-bank-account?oauth_state_id=abc123&x=1');
  });

  it('routes an onboarding return back to /link-bank with the query string kept', () => {
    storePlaidLinkSession('link-token', 'onboarding');

    expect(plaidOAuthReturnRoute(LINK_BANK_HREF)).toBe('/link-bank?oauth_state_id=abc123');
  });

  it('is not a return without a stored token, without oauth_state_id, or with an unknown marker', () => {
    expect(plaidOAuthReturnRoute(LINK_BANK_HREF)).toBeNull();

    storePlaidLinkSession('link-token', 'change-bank');
    expect(plaidOAuthReturnRoute('https://local.fredvested.com/link-bank')).toBeNull();

    sessionStorage.setItem(PLAID_LINK_RETURN_KEY, 'settings');
    expect(plaidOAuthReturnRoute(LINK_BANK_HREF)).toBeNull();
  });
});
```

Run:

```
cd "$(git rev-parse --show-toplevel)/frontend" && npx ng test --watch=false --browsers=ChromeHeadless --include='**/utils/plaid-link-session.spec.ts'
```

Expected: the bundle does not compile. The log contains `error TS2305: Module '"./plaid-link-session"' has no exported member 'PLAID_OAUTH_RETURN_HREF_KEY'.` and one TS2305 line for each of `plaidOAuthReturnRoute`, `plaidOAuthStateId`, `readPlaidOAuthReturnHref` and `storePlaidOAuthReturnHref`. Karma may still exit 0, so read the log.

- [ ] **Step 2: The OAuth return href and the two pure helpers**

Replace the whole of `frontend/src/app/utils/plaid-link-session.ts` (49 lines at HEAD; the four existing functions keep their bodies, and `clearPlaidLinkSession` gains one line) with:

```ts
/**
 * Plaid OAuth round trip. When a bank uses OAuth, Link leaves the app for the bank's page and the
 * bank sends the user back to the redirect URI with ?oauth_state_id=... in the URL. Link must then
 * be re-created with the SAME link_token it left with, so the token is parked in sessionStorage for
 * the duration of one Link session, with a marker naming the page that started it.
 *
 * Every link token names one redirect URI per environment, and it ends in /link-bank, so a
 * change-bank return lands on /link-bank and is routed on to /change-bank-account. The href it
 * landed on is parked too, because that is the URL Link must be told it was redirected to.
 * Nothing else is stored: no public token, no account data. The href carries only Plaid's
 * one-time oauth_state_id.
 */
export const PLAID_LINK_TOKEN_KEY = 'fred.plaid.linkToken';
export const PLAID_LINK_RETURN_KEY = 'fred.plaid.linkReturn';
export const PLAID_OAUTH_RETURN_HREF_KEY = 'fred.plaid.oauthReturnHref';

export type PlaidLinkReturnTo = 'onboarding' | 'change-bank';

export function storePlaidLinkSession(linkToken: string, returnTo: PlaidLinkReturnTo): void {
  try {
    sessionStorage.setItem(PLAID_LINK_TOKEN_KEY, linkToken);
    sessionStorage.setItem(PLAID_LINK_RETURN_KEY, returnTo);
  } catch {
    // Storage blocked: the OAuth return starts Link over with a fresh token.
  }
}

export function readPlaidLinkToken(): string | null {
  try {
    return sessionStorage.getItem(PLAID_LINK_TOKEN_KEY);
  } catch {
    return null;
  }
}

/**
 * Which page stored the current token, or null if none is stored. A page must check this before
 * resuming with a stored token: onboarding and change-bank share one sessionStorage slot, so a
 * token left behind by one flow must never be reused by the other.
 */
export function readPlaidLinkReturn(): PlaidLinkReturnTo | null {
  try {
    return sessionStorage.getItem(PLAID_LINK_RETURN_KEY) as PlaidLinkReturnTo | null;
  } catch {
    return null;
  }
}

/** Parks the full URL the bank's OAuth redirect landed on, for the page that resumes Link. */
export function storePlaidOAuthReturnHref(href: string): void {
  try {
    sessionStorage.setItem(PLAID_OAUTH_RETURN_HREF_KEY, href);
  } catch {
    // Storage blocked: the resuming page falls back to its own URL.
  }
}

export function readPlaidOAuthReturnHref(): string | null {
  try {
    return sessionStorage.getItem(PLAID_OAUTH_RETURN_HREF_KEY);
  } catch {
    return null;
  }
}

/** Plaid's oauth_state_id on the given href, or null when it has none or is not a URL. */
export function plaidOAuthStateId(href: string): string | null {
  try {
    return new URL(href).searchParams.get('oauth_state_id');
  } catch {
    return null;
  }
}

/**
 * Where an OAuth return belongs: the page whose Link session is stored, with the redirect's whole
 * query string kept. Null when the href carries no oauth_state_id, no token is stored, or the
 * marker names no page.
 */
export function plaidOAuthReturnRoute(href: string): string | null {
  if (plaidOAuthStateId(href) === null || readPlaidLinkToken() === null) {
    return null;
  }
  const search = new URL(href).search;
  switch (readPlaidLinkReturn()) {
    case 'change-bank':
      return '/change-bank-account' + search;
    case 'onboarding':
      return '/link-bank' + search;
    default:
      return null;
  }
}

export function clearPlaidLinkSession(): void {
  try {
    sessionStorage.removeItem(PLAID_LINK_TOKEN_KEY);
    sessionStorage.removeItem(PLAID_LINK_RETURN_KEY);
    sessionStorage.removeItem(PLAID_OAUTH_RETURN_HREF_KEY);
  } catch {
    // Nothing to clear.
  }
}
```

Run the Step 1 command. Expected: `TOTAL: 5 SUCCESS`.

- [ ] **Step 3: Failing specs for the forward on /link-bank**

Replace the whole of `frontend/src/app/components/linkplaid/linkplaid.component.oauth.spec.ts` (121 lines at HEAD) with the file below. It keeps six existing cases as they are, apart from the Router spy, the analytics spy, the return URL constant and the `storeSession` helper; two of them (the onboarding resume and the no-stored-token case) also gain one `navigateByUrl` assertion, and the resume case gains a contract name in its title. It rewrites one case. At HEAD, "a token left behind by the other flow does not resume here and is discarded" stored the marker `'change-bank'`. That marker now forwards, so the case becomes "a corrupted return marker", which is the other situation the HEAD comment at `:85-86` names. It adds four new cases.

```ts
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
import { of } from 'rxjs';
import { AuthService } from '../../services/auth.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { PLAID_LINK_RETURN_KEY, PLAID_LINK_TOKEN_KEY, PLAID_OAUTH_RETURN_HREF_KEY } from '../../utils/plaid-link-session';
import { LinkPlaidComponent } from './linkplaid.component';

/**
 * The onboarding route is /link-bank (app.routes.ts:38). It is also the one redirect URI every link
 * token names (R9; local https://local.fredvested.com/link-bank), so every OAuth return lands here,
 * including one that started on /change-bank-account.
 */
const RETURN_HREF = 'https://local.fredvested.com/link-bank?oauth_state_id=abc123';

describe('LinkPlaidComponent OAuth round trip', () => {
  let post: jasmine.Spy;
  let open: jasmine.Spy;
  let router: jasmine.SpyObj<Router>;
  let navCtrl: jasmine.SpyObj<NavController>;
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let component: LinkPlaidComponent;
  let plaidConfig: any;

  function storeSession(token: string, marker: string): void {
    sessionStorage.setItem(PLAID_LINK_TOKEN_KEY, token);
    sessionStorage.setItem(PLAID_LINK_RETURN_KEY, marker);
  }

  beforeEach(() => {
    plaidConfig = null;
    open = jasmine.createSpy('open');
    (window as any).Plaid = { create: (config: any) => { plaidConfig = config; return { open }; } };
    sessionStorage.clear();
    post = jasmine.createSpy('post').and.returnValue(of({ link_token: 'link-sandbox-token', expiration: '' }));
    router = jasmine.createSpyObj<Router>('Router', ['navigate', 'navigateByUrl']);
    router.navigateByUrl.and.returnValue(Promise.resolve(true));
    navCtrl = jasmine.createSpyObj<NavController>('NavController', ['navigateBack', 'navigateForward']);
    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
    ]);
    component = new LinkPlaidComponent(
      { post } as unknown as HttpClient,
      router,
      navCtrl,
      { completeStep: jasmine.createSpy('completeStep').and.returnValue(of({})) } as unknown as AuthService,
      analytics,
    );
    (component as any).isUserAuthenticated = true;
  });

  afterEach(() => {
    delete (window as any).Plaid;
    sessionStorage.clear();
  });

  it('posts mode link and stores the token for the round trip', async () => {
    await component.initializePlaidLink();

    expect(post.calls.mostRecent().args[1]).toEqual({ mode: 'link' });
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBe('link-sandbox-token');
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBe('onboarding');
    expect(plaidConfig.token).toBe('link-sandbox-token');
    expect(plaidConfig.receivedRedirectUri).toBeUndefined();
    expect(open).not.toHaveBeenCalled();
  });

  it('resumes an onboarding return in place with the stored token and receivedRedirectUri (onboardingReturnStillResumesInPlace)', async () => {
    storeSession('link-stored-token', 'onboarding');
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    await component.initializePlaidLink();

    expect(post).not.toHaveBeenCalled();
    expect(plaidConfig.token).toBe('link-stored-token');
    expect(plaidConfig.receivedRedirectUri).toBe(RETURN_HREF);
    expect(open).toHaveBeenCalled();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('consumes the stored session as soon as it resumes, so a stale oauth_state_id cannot replay it', async () => {
    storeSession('link-stored-token', 'onboarding');
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    await component.initializePlaidLink();

    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBeNull();
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBeNull();
  });

  it('forwards a change-bank return to /change-bank-account with the query string kept (forwardsChangeBankReturnWithQueryStringPreserved)', async () => {
    storeSession('link-change-bank-token', 'change-bank');
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    await component.initializePlaidLink();

    expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/change-bank-account?oauth_state_id=abc123', { replaceUrl: true });
  });

  it('leaves the session to the change-bank page and never builds Link here (forwardNeverClearsSessionOrCreatesLink)', async () => {
    storeSession('link-change-bank-token', 'change-bank');
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    await component.initializePlaidLink();

    expect(post).not.toHaveBeenCalled();
    expect(plaidConfig).toBeNull();
    expect(open).not.toHaveBeenCalled();
    expect(navCtrl.navigateForward).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBe('link-change-bank-token');
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBe('change-bank');
  });

  it('stores the /link-bank href Plaid redirected to before forwarding (forwardStoresOriginalLinkBankHref)', async () => {
    storeSession('link-change-bank-token', 'change-bank');
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    await component.initializePlaidLink();

    expect(sessionStorage.getItem(PLAID_OAUTH_RETURN_HREF_KEY)).toBe(RETURN_HREF);
  });

  it('records no onboarding step view for a change-bank return passing through', () => {
    storeSession('link-change-bank-token', 'change-bank');
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    component.ngOnInit();

    expect(analytics.trackOnboardingStepViewed).not.toHaveBeenCalled();
    expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/change-bank-account?oauth_state_id=abc123', { replaceUrl: true });
  });

  it('a corrupted return marker does not resume here and is discarded', async () => {
    storeSession('link-corrupted-token', 'settings');
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    await component.initializePlaidLink();

    expect(post).toHaveBeenCalled();
    expect(plaidConfig.token).toBe('link-sandbox-token');
    expect(plaidConfig.receivedRedirectUri).toBeUndefined();
    expect(open).not.toHaveBeenCalled();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBe('link-sandbox-token');
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBe('onboarding');
  });

  it('an OAuth return with no stored token starts Link over with a fresh token', async () => {
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    await component.initializePlaidLink();

    expect(post).toHaveBeenCalled();
    expect(plaidConfig.token).toBe('link-sandbox-token');
    expect(plaidConfig.receivedRedirectUri).toBeUndefined();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('success and exit both clear the stored link session', async () => {
    await component.initializePlaidLink();
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBe('link-sandbox-token');
    plaidConfig.onExit(null, {});
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBeNull();
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBeNull();

    await component.initializePlaidLink();
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBe('link-sandbox-token');
    plaidConfig.onSuccess('public-sandbox-token', {});
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBeNull();
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBeNull();
  });

  it('goBack returns to KYC verification', () => {
    component.goBack();

    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/kyc-verification');
  });
});
```

Run:

```
cd "$(git rev-parse --show-toplevel)/frontend" && npx ng test --watch=false --browsers=ChromeHeadless --include='**/linkplaid/linkplaid.component.oauth.spec.ts'
```

Expected: `Executed 11 of 11 (4 FAILED)`. The four failures are the three `forward...` cases and "records no onboarding step view". The log shows `Expected spy Router.navigateByUrl to have been called only once, and with given args:` followed by `[ '/change-bank-account?oauth_state_id=abc123', Object({ replaceUrl: true }) ]` and `But it was never called.`, then `Expected spy post not to have been called.`, `Expected null to be 'https://local.fredvested.com/link-bank?oauth_state_id=abc123'.` and `Expected spy AnalyticsService.trackOnboardingStepViewed not to have been called.` The other seven pass.

- [ ] **Step 4: Forward the change-bank return, and keep it out of the onboarding funnel**

`frontend/src/app/components/linkplaid/linkplaid.component.ts`:

At `:16` replace

```ts
import { clearPlaidLinkSession, readPlaidLinkReturn, readPlaidLinkToken, storePlaidLinkSession } from '../../utils/plaid-link-session';
```

with

```ts
import { clearPlaidLinkSession, readPlaidLinkReturn, readPlaidLinkToken, storePlaidLinkSession, storePlaidOAuthReturnHref } from '../../utils/plaid-link-session';
```

At `:65-66` replace

```ts
    // Nothing is drafted on this screen, so there is no saved state to resume from.
    this.analytics.trackOnboardingStepViewed('link_plaid', null, false);
```

with

```ts
    // Nothing is drafted on this screen, so there is no saved state to resume from. A change-bank
    // OAuth return only passes through on its way to /change-bank-account, so it is not a view of
    // this onboarding step.
    if (!this.changeBankOAuthReturn()) {
      this.analytics.trackOnboardingStepViewed('link_plaid', null, false);
    }
```

At `:76-88` replace the block that starts `      // Back from the bank's OAuth page: Link must resume with the SAME link_token it left with.` and ends with the `      }` that closes `if (returnHref !== null && storedToken !== null && !resuming) {` (the line `      let linkToken: string;` at `:89` stays) with:

```ts
      // Back from the bank's OAuth page: Link must resume with the SAME link_token it left with.
      // Without a stored token (storage cleared, new browser) Link simply starts over. onboarding
      // and change-bank share one sessionStorage slot, so a token left behind by the other flow
      // must never be reused here: the return marker has to match this page too.
      const returnHref = this.oauthReturnHref();
      const storedToken = returnHref ? readPlaidLinkToken() : null;
      const storedReturn = returnHref ? readPlaidLinkReturn() : null;
      if (returnHref !== null && storedReturn === 'change-bank') {
        // Every link token names one redirect URI per environment, and it is this page, so a
        // change-bank round trip lands here. Hand it to the page that started it, with the query
        // string intact and the session untouched: that page resumes Link with its own stored
        // token, which only exists because its PIN step-up passed. The href Plaid redirected to is
        // kept for that page to pass as receivedRedirectUri.
        storePlaidOAuthReturnHref(returnHref);
        this.router.navigateByUrl('/change-bank-account' + new URL(returnHref).search, { replaceUrl: true });
        return;
      }
      const resuming = returnHref !== null && storedToken !== null && storedReturn === 'onboarding';
      if (returnHref !== null && storedToken !== null && !resuming) {
        // A corrupted marker: discard it and fetch a fresh token below.
        clearPlaidLinkSession();
      }
```

After `:244` (the `}` that closes `private oauthReturnHref(): string | null {`), insert:

```ts

  /** True when this load is a change-bank OAuth return, which belongs on /change-bank-account. */
  private changeBankOAuthReturn(): boolean {
    return this.oauthReturnHref() !== null && readPlaidLinkReturn() === 'change-bank';
  }
```

Notes for the implementer:
- The forward runs before the first `await` in `initializePlaidLink` and before the `isUserAuthenticated` check. So it happens synchronously inside `ngOnInit`, with or without a JWT, and no link token is requested. The change-bank page handles authentication as it does today.
- `isLoading` stays `true` on the forward, so the Connect button stays disabled until the route changes.
- The query comes from `new URL(returnHref).search`. In production `oauthReturnHref()` returns `window.location.href`, so this is exactly `window.location.search`. Taking it from `returnHref` lets the specs drive it through the existing `oauthReturnHref` stub.
- `Router` is already injected (`:51`). Nothing else in the constructor changes, so `linkplaid.component.analytics.spec.ts` needs no edit. Its `ngOnInit` case still sees no OAuth return and still records the view.

Run:

```
cd "$(git rev-parse --show-toplevel)/frontend" && npx ng test --watch=false --browsers=ChromeHeadless --include='**/linkplaid/linkplaid.component.oauth.spec.ts' --include='**/linkplaid/linkplaid.component.analytics.spec.ts'
```

Expected: `TOTAL: 18 SUCCESS` (11 OAuth plus 7 analytics).

- [ ] **Step 5: Failing specs for resuming a forwarded return on the change-bank page**

`frontend/src/app/change-bank-account/change-bank-account.page.oauth.spec.ts`:

At `:11` replace

```ts
import { PLAID_LINK_RETURN_KEY, PLAID_LINK_TOKEN_KEY } from '../utils/plaid-link-session';
```

with

```ts
import { PLAID_LINK_RETURN_KEY, PLAID_LINK_TOKEN_KEY, PLAID_OAUTH_RETURN_HREF_KEY } from '../utils/plaid-link-session';
```

After `:14` (`const RETURN_HREF = 'https://fredvested.com/change-bank-account?oauth_state_id=abc123';`) insert:

```ts
/**
 * Every link token names one redirect URI, /link-bank (R9), so a change-bank return lands there,
 * is forwarded here with its query string, and the /link-bank href is parked for this page (T20).
 */
const LINK_BANK_HREF = 'https://local.fredvested.com/link-bank?oauth_state_id=abc123';
const FORWARDED_HREF = 'https://local.fredvested.com/change-bank-account?oauth_state_id=abc123';
```

Before the final `});` of the file (after the `it('success and exit both clear the stored link session', ...)` case), insert:

```ts

  it('resumes a forwarded return with the /link-bank href Plaid redirected to (changeBankResumeUsesOriginalLinkBankHref)', async () => {
    sessionStorage.setItem(PLAID_LINK_TOKEN_KEY, 'link-stored-token');
    sessionStorage.setItem(PLAID_LINK_RETURN_KEY, 'change-bank');
    sessionStorage.setItem(PLAID_OAUTH_RETURN_HREF_KEY, LINK_BANK_HREF);
    spyOn(page as any, 'currentHref').and.returnValue(FORWARDED_HREF);

    await page['initializePlaidLink']();

    expect(http.post).not.toHaveBeenCalled();
    expect(plaidConfig.token).toBe('link-stored-token');
    expect(plaidConfig.receivedRedirectUri).toBe(LINK_BANK_HREF);
    expect(open).toHaveBeenCalled();
    expect(sessionStorage.getItem(PLAID_OAUTH_RETURN_HREF_KEY)).toBeNull();
  });

  it('ignores a stored href that carries a different oauth_state_id', async () => {
    sessionStorage.setItem(PLAID_LINK_TOKEN_KEY, 'link-stored-token');
    sessionStorage.setItem(PLAID_LINK_RETURN_KEY, 'change-bank');
    sessionStorage.setItem(PLAID_OAUTH_RETURN_HREF_KEY, 'https://local.fredvested.com/link-bank?oauth_state_id=older999');
    spyOn(page as any, 'currentHref').and.returnValue(FORWARDED_HREF);

    await page['initializePlaidLink']();

    expect(plaidConfig.token).toBe('link-stored-token');
    expect(plaidConfig.receivedRedirectUri).toBe(FORWARDED_HREF);
  });

  it('a stored href alone never turns a plain load into an OAuth return', async () => {
    sessionStorage.setItem(PLAID_OAUTH_RETURN_HREF_KEY, LINK_BANK_HREF);
    spyOn(page as any, 'currentHref').and.returnValue('https://local.fredvested.com/change-bank-account');

    await page['initializePlaidLink']();

    expect(http.post).toHaveBeenCalled();
    expect(plaidConfig.receivedRedirectUri).toBeUndefined();
    expect(open).not.toHaveBeenCalled();
  });
```

Run:

```
cd "$(git rev-parse --show-toplevel)/frontend" && npx ng test --watch=false --browsers=ChromeHeadless --include='**/change-bank-account/change-bank-account.page.oauth.spec.ts'
```

Expected: `Executed 8 of 8 (3 FAILED)`. Each new case fails with `Error: <spyOn> : currentHref() method does not exist`. The five existing cases pass.

- [ ] **Step 6: Prefer the parked /link-bank href when it matches this return**

`frontend/src/app/change-bank-account/change-bank-account.page.ts`:

At `:10` replace

```ts
import { clearPlaidLinkSession, readPlaidLinkReturn, readPlaidLinkToken, storePlaidLinkSession } from '../utils/plaid-link-session';
```

with

```ts
import { clearPlaidLinkSession, plaidOAuthStateId, readPlaidLinkReturn, readPlaidLinkToken, readPlaidOAuthReturnHref, storePlaidLinkSession } from '../utils/plaid-link-session';
```

Replace `:239-242`, which is exactly

```ts
  /** The current URL when it carries Plaid's oauth_state_id, else null. Stubbed in specs. */
  private oauthReturnHref(): string | null {
    return window.location.search.includes('oauth_state_id') ? window.location.href : null;
  }
```

with

```ts
  /**
   * The URL Plaid redirected to when this load is an OAuth return, else null. Every link token
   * names the one registered redirect URI (/link-bank), so a change-bank return arrives here
   * forwarded, and the /link-bank href parked before the forward is the one Link must resume with.
   * It is used only when it carries this load's oauth_state_id, so a leftover from an earlier round
   * trip never stands in for the current one. Stubbed in specs.
   */
  private oauthReturnHref(): string | null {
    const current = this.currentHref();
    const stateId = plaidOAuthStateId(current);
    if (stateId === null) {
      return null;
    }
    const original = readPlaidOAuthReturnHref();
    return original !== null && plaidOAuthStateId(original) === stateId ? original : current;
  }

  /** window.location.href, behind a method so specs can set the URL. */
  private currentHref(): string {
    return window.location.href;
  }
```

Nothing else on the page changes. `initializePlaidLink` (`:116-205`) already sets `config.receivedRedirectUri = returnHref` when resuming, and calls `clearPlaidLinkSession()` right after `Plaid.create`. That call now also drops the parked href, so the href is single use with the token. The PIN step-up in `openPlaid` is untouched: a resume still skips it, because the stored token only exists after it passed.

Run:

```
cd "$(git rev-parse --show-toplevel)/frontend" && npx ng test --watch=false --browsers=ChromeHeadless --include='**/change-bank-account/change-bank-account.page.oauth.spec.ts' --include='**/change-bank-account/change-bank-account.page.spec.ts'
```

Expected: `TOTAL: 17 SUCCESS` (8 OAuth plus 9 page).

- [ ] **Step 7: Failing specs for the cold-start capture in AppComponent**

Create `frontend/src/app/app.component.oauth-return.spec.ts`. The stubs copy `app.component.spec.ts:19-62`, which passes at HEAD (8 of 8):

```ts
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { BehaviorSubject, Subject } from 'rxjs';
import { AppComponent } from './app.component';
import { AppLockService } from './services/app-lock.service';
import { AuthService, UserProgress } from './services/auth.service';
import { PasskeyService } from './services/passkey.service';
import { PushNotificationService } from './services/push-notification.service';
import { PortfolioStoreService } from './services/portfolio-store.service';
import { FirstTimeTourService } from './services/first-time-tour.service';
import { PLAID_LINK_RETURN_KEY, PLAID_LINK_TOKEN_KEY, PLAID_OAUTH_RETURN_HREF_KEY } from './utils/plaid-link-session';

/** The bank sends the user back to the one redirect URI registered for the environment (R9). */
const BOOT_HREF = 'https://local.fredvested.com/link-bank?oauth_state_id=abc123';

const ALL_DONE: UserProgress = {
  getStartedCompleted: true, surveyInitialCompleted: true, fiPlanResultsCompleted: true,
  authFinalizeCompleted: true, kycVerificationCompleted: true, linkPlaidCompleted: true,
  investmentScheduleCompleted: true, investmentConfirmationCompleted: true,
};
const LINKING_NEXT: UserProgress = {
  ...ALL_DONE, linkPlaidCompleted: false, investmentScheduleCompleted: false, investmentConfirmationCompleted: false,
};

function authStub() {
  return {
    isLoggedIn$: new BehaviorSubject<boolean>(false).asObservable(),
    userProgress$: new BehaviorSubject<UserProgress | null>(null).asObservable(),
    reAuthInProgress$: new BehaviorSubject<boolean>(false).asObservable(),
    authSucceeded$: new Subject<void>().asObservable(),
    getCurrentProgress: jasmine.createSpy('getCurrentProgress').and.returnValue(null),
    getUnifiedProgress: jasmine.createSpy('getUnifiedProgress').and.returnValue({ ...ALL_DONE, getStartedCompleted: false }),
    loadUserProgress: jasmine.createSpy('loadUserProgress'),
    logout: jasmine.createSpy('logout'),
    isAuthenticated: jasmine.createSpy('isAuthenticated').and.returnValue(false),
  };
}

function lockStub() {
  return {
    isEnabled: jasmine.createSpy('isEnabled').and.returnValue(false),
    isCurrentlyLocked: jasmine.createSpy('isCurrentlyLocked').and.returnValue(false),
    resumeNoLock$: new Subject<void>().asObservable(),
    isLocked$: new BehaviorSubject<boolean>(false).asObservable(),
    hideAllCovers: jasmine.createSpy('hideAllCovers'),
  };
}

function seedToken(ns: string): void {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = btoa(JSON.stringify({ sub: 'u@fred.com', exp: 9999999999, ns }));
  localStorage.setItem('jwtToken', `${header}.${body}.fake-signature`);
  localStorage.setItem('jwtExpiration', String(Math.floor(Date.now() / 1000) + 3600));
}

function clearToken(): void {
  localStorage.removeItem('jwtToken');
  localStorage.removeItem('jwtExpiration');
}

function storeSession(marker: string): void {
  sessionStorage.setItem(PLAID_LINK_TOKEN_KEY, 'link-stored-token');
  sessionStorage.setItem(PLAID_LINK_RETURN_KEY, marker);
}

describe('AppComponent: a Plaid OAuth return at cold start', () => {
  let router: Router;

  /** Boots AppComponent as if the browser had opened `href`; ngOnInit runs inside detectChanges. */
  async function boot(href: string): Promise<AppComponent> {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authStub() },
        { provide: AppLockService, useValue: lockStub() },
        { provide: PasskeyService, useValue: {} },
        { provide: PushNotificationService, useValue: { registerDeviceToken: jasmine.createSpy('registerDeviceToken').and.returnValue(Promise.resolve()) } },
        { provide: PortfolioStoreService, useValue: { prime: jasmine.createSpy('prime') } },
        {
          provide: FirstTimeTourService,
          useValue: {
            step$: new BehaviorSubject(null).asObservable(),
            suspended$: new BehaviorSubject(false).asObservable(),
            transit$: new BehaviorSubject(false).asObservable(),
          },
        },
      ],
    }).compileComponents();
    router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.returnValue(Promise.resolve(true));
    spyOn(router, 'navigate').and.returnValue(Promise.resolve(true));
    spyOn(AppComponent.prototype as any, 'bootHref').and.returnValue(href);
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  afterEach(() => {
    clearToken();
    sessionStorage.clear();
  });

  it('stands optimistic navigation aside and parks the href Plaid redirected to', async () => {
    seedToken('complete');
    storeSession('change-bank');

    await boot(BOOT_HREF);

    expect(router.navigateByUrl).not.toHaveBeenCalledWith('/tabs/tab1', jasmine.anything());
    expect(sessionStorage.getItem(PLAID_OAUTH_RETURN_HREF_KEY)).toBe(BOOT_HREF);
  });

  it('routes a change-bank return to /change-bank-account with the query string, exactly once', async () => {
    seedToken('complete');
    storeSession('change-bank');
    const app = await boot(BOOT_HREF);

    app.navigateBasedOnProgress(ALL_DONE);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/change-bank-account?oauth_state_id=abc123', { replaceUrl: true });

    (router.navigateByUrl as jasmine.Spy).calls.reset();
    app.navigateBasedOnProgress(ALL_DONE);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/tabs/tab1', { replaceUrl: true });
  });

  it('keeps the query string on /link-bank for an onboarding return', async () => {
    seedToken('link-bank');
    storeSession('onboarding');
    const app = await boot(BOOT_HREF);

    app.navigateBasedOnProgress(LINKING_NEXT);

    expect(router.navigateByUrl).toHaveBeenCalledWith('/link-bank?oauth_state_id=abc123', { replaceUrl: true });
  });

  it('drops a change-bank return the progress does not allow and routes as before', async () => {
    seedToken('link-bank');
    storeSession('change-bank');
    const app = await boot(BOOT_HREF);

    app.navigateBasedOnProgress(LINKING_NEXT);

    expect(router.navigateByUrl).toHaveBeenCalledWith('/link-bank', { replaceUrl: true });
    expect(router.navigateByUrl).not.toHaveBeenCalledWith('/change-bank-account?oauth_state_id=abc123', jasmine.anything());
  });

  it('holds the return while no valid JWT exists, so it survives a re-authentication', async () => {
    storeSession('change-bank');
    const app = await boot(BOOT_HREF);

    app.navigateBasedOnProgress(ALL_DONE);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/tabs/tab1', { replaceUrl: true });

    (router.navigateByUrl as jasmine.Spy).calls.reset();
    seedToken('complete');
    app.navigateBasedOnProgress(ALL_DONE);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/change-bank-account?oauth_state_id=abc123', { replaceUrl: true });
  });

  it('a cold start with oauth_state_id but no stored Link session is not a return', async () => {
    seedToken('complete');

    await boot(BOOT_HREF);

    expect(router.navigateByUrl).toHaveBeenCalledWith('/tabs/tab1', jasmine.objectContaining({ replaceUrl: true }));
    expect(sessionStorage.getItem(PLAID_OAUTH_RETURN_HREF_KEY)).toBeNull();
  });
});
```

Run:

```
cd "$(git rev-parse --show-toplevel)/frontend" && npx ng test --watch=false --browsers=ChromeHeadless --include='**/app.component.oauth-return.spec.ts'
```

Expected: `Executed 6 of 6 (6 FAILED)`. Every case fails inside `boot` with `Error: <spyOn> : bootHref() method does not exist`.

- [ ] **Step 8: Capture the return once at cold start, spend it once in navigateBasedOnProgress**

`frontend/src/app/app.component.ts`:

After `:14` (`import { JwtTokenUtils } from './utils/jwt-token.utils';`) insert:

```ts
import { plaidOAuthReturnRoute, storePlaidOAuthReturnHref } from './utils/plaid-link-session';
```

Replace `:45`, which is exactly `  private userActivity$ = new Subject<void>();`, with:

```ts
  private userActivity$ = new Subject<void>();

  /**
   * Where a Plaid OAuth return still has to go (for example
   * '/change-bank-account?oauth_state_id=...'), or null. Set once at cold start by
   * capturePlaidOAuthReturn and spent once by navigateBasedOnProgress.
   */
  private plaidOAuthReturn: string | null = null;
```

Replace `:174-175`, which is exactly

```ts
    // This only paints faster; it never sets isLoggedIn, calls completeStep, or gates KYC/bank/money.
    this.tryOptimisticNav();
```

with

```ts
    // This only paints faster; it never sets isLoggedIn, calls completeStep, or gates KYC/bank/money.
    // A Plaid OAuth return is captured first: while one is pending, tryOptimisticNav stands aside.
    this.capturePlaidOAuthReturn();
    this.tryOptimisticNav();
```

(The other `this.tryOptimisticNav();` at `:190`, inside the `authSucceeded$` subscription, is unchanged.)

In `navigateBasedOnProgress`, replace `:333-336`, which is exactly

```ts
      targetRoute = '/tabs/tab1';
    }

    const currentBaseUrl = this.router.url.split('?')[0].split('#')[0];
```

with

```ts
      targetRoute = '/tabs/tab1';
    }

    // A Plaid OAuth return goes where its Link session started, query string included, before any
    // rule below can send it to a bare route and drop the oauth_state_id.
    const oauthReturn = this.takePlaidOAuthReturn(targetRoute);
    if (oauthReturn !== null) {
      this.router.navigateByUrl(oauthReturn, { replaceUrl: true }).finally(() => {
        this.hideCoversWhenReady(oauthReturn);
      });
      return;
    }

    const currentBaseUrl = this.router.url.split('?')[0].split('#')[0];
```

Immediately before `:368` (`  private hideCoversWhenReady(targetRoute?: string): void {`), insert:

```ts
  /**
   * Plaid OAuth return (R9). The bank sends the user back to the one redirect URI registered per
   * environment (https://local.fredvested.com/link-bank locally) with ?oauth_state_id=..., as a
   * fresh page load. Initial navigation is disabled (main.ts) and navigateBasedOnProgress routes to
   * bare paths, so without this the query string is dropped before either Link page reads it, and a
   * fully onboarded user coming back from a bank change is sent to /tabs/tab1. The href Plaid
   * redirected to is parked for the change-bank page, which resumes Link with it.
   */
  private capturePlaidOAuthReturn(): void {
    const href = this.bootHref();
    this.plaidOAuthReturn = plaidOAuthReturnRoute(href);
    if (this.plaidOAuthReturn !== null) {
      storePlaidOAuthReturnHref(href);
    }
  }

  /** window.location.href at cold start, behind a method so specs can set it. */
  private bootHref(): string {
    return window.location.href;
  }

  /**
   * The pending OAuth return route, at most once, when this progress allows it: the change-bank
   * page only for a fully onboarded user, the onboarding Link page only while linking is the next
   * step. Without a valid JWT it is kept, so it survives a re-authentication. Otherwise it is
   * spent, and a route the progress does not allow is dropped, leaving the normal routing.
   */
  private takePlaidOAuthReturn(targetRoute: string): string | null {
    const pending = this.plaidOAuthReturn;
    if (pending === null || !JwtTokenUtils.getValidJwtToken()) {
      return null;
    }
    this.plaidOAuthReturn = null;
    const page = pending.split('?')[0];
    const allowed = page === '/change-bank-account' ? targetRoute === '/tabs/tab1' : targetRoute === '/link-bank';
    return allowed ? pending : null;
  }

```

Replace `:498-499`, which is exactly

```ts
  private tryOptimisticNav(): boolean {
    if (!JwtTokenUtils.getValidJwtToken()) return false;
```

with

```ts
  private tryOptimisticNav(): boolean {
    // A pending Plaid OAuth return must reach its Link page; tab1 would drop it.
    if (this.plaidOAuthReturn !== null) return false;
    if (!JwtTokenUtils.getValidJwtToken()) return false;
```

Notes for the implementer:
- `capturePlaidOAuthReturn` runs after the `?testing=true` and `devPage` early returns (`:136-139`, `:165-169`). Those dev paths are unchanged.
- An ordinary cold start (no `oauth_state_id`, or no stored token) leaves `plaidOAuthReturn` null. Every existing path then behaves exactly as at HEAD, which `app.component.spec.ts` (8 cases) proves in Step 9.
- While a return is held for a missing JWT, `tryOptimisticNav` stays aside. It is only a paint-speed shortcut ("purely additive", per its own javadoc), and the next `navigateBasedOnProgress` with a valid JWT spends the return.
- `holdAuthenticatedUserOnFallback` (progress could not load) is unchanged. A return that meets it is not spent, and the user lands where that method sends them; if a later progress load succeeds with a valid JWT, that `navigateBasedOnProgress` spends the return and moves the user on to its page.

Run:

```
cd "$(git rev-parse --show-toplevel)/frontend" && npx ng test --watch=false --browsers=ChromeHeadless --include='**/app.component.oauth-return.spec.ts' --include='**/app.component.spec.ts'
```

Expected: `TOTAL: 14 SUCCESS` (6 new plus the 8 existing `app.component.spec.ts` cases).

- [ ] **Step 9: Run everything this task touches, build, and check the copy rules**

```
cd "$(git rev-parse --show-toplevel)/frontend" && npx ng test --watch=false --browsers=ChromeHeadless --include='**/utils/plaid-link-session.spec.ts' --include='**/linkplaid/linkplaid.component.oauth.spec.ts' --include='**/linkplaid/linkplaid.component.analytics.spec.ts' --include='**/change-bank-account/change-bank-account.page.oauth.spec.ts' --include='**/change-bank-account/change-bank-account.page.spec.ts' --include='**/app.component.oauth-return.spec.ts' --include='**/app.component.spec.ts'
cd "$(git rev-parse --show-toplevel)/frontend" && npx ng build --configuration dev
cd "$(git rev-parse --show-toplevel)/frontend" && npm run test:events-documented
cd "$(git rev-parse --show-toplevel)" && git diff HEAD -- frontend/src | perl -CSD -ne 'print if /^\+.*\x{2014}/'
cd "$(git rev-parse --show-toplevel)" && perl -CSD -ne 'print "$ARGV:$.: $_" if /\x{2014}/; close ARGV if eof' frontend/src/app/utils/plaid-link-session.spec.ts frontend/src/app/app.component.oauth-return.spec.ts
cd "$(git rev-parse --show-toplevel)" && grep -rn 'fred.plaid.oauthReturnHref' frontend/src/app
```

Expected:
- Karma: `TOTAL: 54 SUCCESS` (5 + 11 + 7 + 8 + 9 + 6 + 8). If Karma disconnects with "no message in 30000 ms", that is environmental: re-run before investigating.
- `ng build --configuration dev`: exit 0, `Application bundle generation complete.`, no `error TS` line. Use `dev`, never `development`, which exits 127 and can look like success.
- `test:events-documented`: passes unchanged, since no event name or property key was added.
- Both em-dash greps print nothing.
- The last grep prints exactly one line, the `PLAID_OAUTH_RETURN_HREF_KEY` constant in `frontend/src/app/utils/plaid-link-session.ts`.

- [ ] **Step 10: Optional device check (manual, not a gate for the commit)**

This needs the local backend on `:8080` and the Cloudflare tunnel (no dev front end is deployed, R16), with `plaid.redirect.uri` set to `https://local.fredvested.com/link-bank`. Use a phone browser, not the Capacitor app: without universal links (decision 1) the app cannot receive the redirect. Sign in at `https://local.fredvested.com` as a fully onboarded Sandbox user. Open Settings, then Change bank account. Pass the PIN, choose the Sandbox OAuth institution `ins_127287` (Platypus OAuth Bank), and complete its OAuth page. Expected: the bank sends the browser to `/link-bank?oauth_state_id=...`, the app lands on `/change-bank-account` with the same query, Link reopens without a second PIN prompt, and finishing shows "Your bank account has been updated successfully." If Plaid opens the bank in a pop-up instead of the same tab, the redirect never reloads the app and this path is not exercised. Record that in the PR and stop there. Do not change code to force it.

- [ ] **Step 11: Commit**

Hunk-verify each modified file with `git diff HEAD -- <file>` (concurrent sessions edit this checkout; stage nothing you did not write), then:

```
cd "$(git rev-parse --show-toplevel)"
git add frontend/src/app/utils/plaid-link-session.ts frontend/src/app/utils/plaid-link-session.spec.ts frontend/src/app/components/linkplaid/linkplaid.component.ts frontend/src/app/components/linkplaid/linkplaid.component.oauth.spec.ts frontend/src/app/change-bank-account/change-bank-account.page.ts frontend/src/app/change-bank-account/change-bank-account.page.oauth.spec.ts frontend/src/app/app.component.ts frontend/src/app/app.component.oauth-return.spec.ts
git status --short
git commit -m "fix(plaid): route a change-bank OAuth return to its page instead of discarding it"
```

`git status --short` should show those eight paths staged (`M ` or `A `) and nothing else of yours. Never stage `ITPM/agent-memory/*` or `application-local.properties`.

## Verification

```
cd "$(git rev-parse --show-toplevel)/frontend" && npx ng test --watch=false --browsers=ChromeHeadless --include='**/utils/plaid-link-session.spec.ts' --include='**/linkplaid/linkplaid.component.oauth.spec.ts' --include='**/linkplaid/linkplaid.component.analytics.spec.ts' --include='**/change-bank-account/change-bank-account.page.oauth.spec.ts' --include='**/change-bank-account/change-bank-account.page.spec.ts' --include='**/app.component.oauth-return.spec.ts' --include='**/app.component.spec.ts'
cd "$(git rev-parse --show-toplevel)/frontend" && npx ng build --configuration dev
```

Expected: `TOTAL: 54 SUCCESS`, measured from a HEAD baseline of 36 on the five pre-existing files (7 + 7 + 5 + 9 + 8). The 18 new or rewritten specs: 5 in `plaid-link-session.spec.ts`, 4 new in the onboarding OAuth spec plus 1 rewritten, 3 in the change-bank OAuth spec, and 6 in `app.component.oauth-return.spec.ts`. The build exits 0 with no `error TS`. Contract test names, as they appear in the spec titles: `forwardsChangeBankReturnWithQueryStringPreserved`, `forwardNeverClearsSessionOrCreatesLink`, `forwardStoresOriginalLinkBankHref`, `onboardingReturnStillResumesInPlace` (all in `linkplaid.component.oauth.spec.ts`), and `changeBankResumeUsesOriginalLinkBankHref` (in `change-bank-account.page.oauth.spec.ts`). There is no backend change, so there is no gradle run and no scratch MySQL.

## Produces

- `frontend/src/app/utils/plaid-link-session.ts`: `export const PLAID_OAUTH_RETURN_HREF_KEY = 'fred.plaid.oauthReturnHref'`; `export function storePlaidOAuthReturnHref(href: string): void`; `export function readPlaidOAuthReturnHref(): string | null`; `clearPlaidLinkSession()` now also removes `PLAID_OAUTH_RETURN_HREF_KEY`; `export function plaidOAuthStateId(href: string): string | null`; `export function plaidOAuthReturnRoute(href: string): string | null`. `PlaidLinkReturnTo` (`'onboarding' | 'change-bank'`) and `storePlaidLinkSession(linkToken: string, returnTo: PlaidLinkReturnTo): void` are unchanged.
- The forward: `LinkPlaidComponent.initializePlaidLink()`, on `returnHref !== null && storedReturn === 'change-bank'`, calls `storePlaidOAuthReturnHref(returnHref)` and then `router.navigateByUrl('/change-bank-account' + <the return's query string>, { replaceUrl: true })`, and returns before any `Plaid.create`, with the session kept. Private `LinkPlaidComponent.changeBankOAuthReturn(): boolean` keeps that pass-through out of `onboarding_step_viewed`.
- `ChangeBankAccountPage.oauthReturnHref()` prefers `readPlaidOAuthReturnHref()` when the current URL carries `oauth_state_id` and the parked href carries the same one. New private seam `currentHref(): string`.
- `AppComponent`: private `plaidOAuthReturn: string | null`, `capturePlaidOAuthReturn()`, `bootHref()`, `takePlaidOAuthReturn(targetRoute: string): string | null`. `navigateBasedOnProgress` spends a cold-start return once, and `tryOptimisticNav` stands aside while one is pending.

Deviations from the contract entry, each with its reason:
1. `F/app.component.ts` and a new spec `F/app.component.oauth-return.spec.ts` are added, although the contract lists neither. `main.ts` disables initial navigation, `tryOptimisticNav` sends an onboarded user from `'/'` to `/tabs/tab1`, and `navigateBasedOnProgress` routes to bare paths. On a cold OAuth return, then, `LinkPlaidComponent` never mounts for a change-bank user, and the onboarding page loses its `oauth_state_id`. Without this capture the contract's forward could not run on the path R9 describes. The same change also repairs Phase 0's onboarding resume. The forward in `LinkPlaidComponent` stays as the contract defines it, for any arrival on `/link-bank` that does not pass through a cold start (a later universal-link handler, for example).
2. The forward takes the query from `new URL(returnHref).search`, not `window.location.search`. The two are identical in production, because `oauthReturnHref()` returns `window.location.href`, and this lets the spec drive the forward through the existing stub.
3. The change-bank page uses the parked href only when its `oauth_state_id` matches the current one. It reads the URL through a new `currentHref()` seam, because the existing specs stub `oauthReturnHref` itself.
4. `LinkPlaidComponent.ngOnInit` skips `trackOnboardingStepViewed` for a change-bank return passing through. Otherwise a settings user records a false onboarding view. No event or property is added.
5. Two pure helpers are exported beyond the contract's two names: `plaidOAuthStateId` and `plaidOAuthReturnRoute`. AppComponent and the change-bank page both need them.
6. "The change-bank page spec" is `change-bank-account.page.oauth.spec.ts`, the page's OAuth spec. `change-bank-account.page.spec.ts` runs unchanged as a regression gate. One existing onboarding case that stored the marker `'change-bank'` is rewritten as a corrupted-marker case, because that marker now forwards.
