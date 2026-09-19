# Acceptance Check Manifest: First-Time Tour v2

Story: first-time tour v2 (spec `docs/superpowers/specs/2026-09-01-first-time-tour-v2-design.md`, plan `docs/superpowers/plans/2026-09-01-first-time-tour-v2.md`).
Range: `3574314..ff8d6de` on `develop` (the branch IS develop, so `git diff develop...HEAD` is empty; use the explicit range). Commit `530937d` inside the range belongs to another session (chat suggestion cache) and is not under review.
Working tree carries ~90 files of the user's uncommitted WIP. NEVER run git checkout/restore/stash/reset/clean. Never write under /frontend or /backend.

All test commands run from `frontend/`; karma exits 0 even on a load error, so grep the output for `TOTAL: N SUCCESS`.

## Verification run

Verified 2026-09-01 by verifier-agent. Karma run with `CHROME_BIN="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"`, all 8 specs run sequentially against the working tree. Raw logs:
`/private/tmp/claude-501/-Users-andrewtimmer-PersonalTypeshit-FRED/d84588ee-0bc3-4c5a-8be6-0485364a1a5d/scratchpad/karma/*.log`
and build log `.../scratchpad/build.log`.

**Verdict: APPROVED** (17/17 pass). UI/device behaviour is deferred to Andy's device pass; the verdict is gated on checks 1-17 only, per the task brief.

| # | Type | Check | Evidence | Status |
|---|---|---|---|---|
| 1 | test | `npx ng test --include='**/first-time-tour.service.spec.ts' --watch=false --browsers=ChromeHeadless` shows `TOTAL: 12 SUCCESS` | `Executed 12 of 12 SUCCESS (0.018 secs / 0.013 secs)` / `TOTAL: 12 SUCCESS`, exit 0 | pass |
| 2 | test | same for `**/spotlight-geometry.spec.ts`: `TOTAL: 6 SUCCESS` | `Executed 6 of 6 SUCCESS (0.004 secs / 0.003 secs)` / `TOTAL: 6 SUCCESS`, exit 0 | pass |
| 3 | test | same for `**/first-time-tour.component.spec.ts`: `TOTAL: 7 SUCCESS` | `Executed 7 of 7 SUCCESS (0.065 secs / 0.062 secs)` / `TOTAL: 7 SUCCESS`, exit 0 | pass |
| 4 | test | same for `**/tabs.page.spec.ts`: `TOTAL: 7 SUCCESS` | `Executed 7 of 7 SUCCESS (0.119 secs / 0.115 secs)` / `TOTAL: 7 SUCCESS`, exit 0 | pass |
| 5 | test | same for `**/app.component.spec.ts`: `TOTAL: 8 SUCCESS` | `Executed 8 of 8 SUCCESS (0.056 secs / 0.05 secs)` / `TOTAL: 8 SUCCESS`, exit 0 | pass |
| 6 | test | same for `**/my-profile.page.spec.ts`: `TOTAL: 27 SUCCESS` (working tree; includes the unstaged openFreedomSheet hook test) | `Executed 27 of 27 SUCCESS (0.114 secs / 0.102 secs)` / `TOTAL: 27 SUCCESS`, exit 0 | pass |
| 7 | test | same for `**/my-profile.goals-render.spec.ts`: `TOTAL: 4 SUCCESS` | `Executed 4 of 4 SUCCESS (0.088 secs / 0.082 secs)` / `TOTAL: 4 SUCCESS`, exit 0 | pass |
| 8 | test | same for `**/ai-chat.page.spec.ts`: `TOTAL: 16 SUCCESS` | `Executed 16 of 16 SUCCESS (0.149 secs / 0.111 secs)` / `TOTAL: 16 SUCCESS`, exit 0 | pass |
| 9 | build | `npx ng build --configuration dev` exits 0 with no `error TS` / `ERROR` lines (NOT `development`, which exits 127) | `EXIT=0`; `grep -cE "error TS\|ERROR " build.log` -> `0`; `Output location: frontend/www`. 11 WARNINGs, all pre-existing `TS-998113 IonToolbar/IonIcon is not used` in untouched pages (beneficiaries, change-bank-account, sell-withdraw, ...) plus one `empty-glob`; none in tour files. | pass |
| 10 | static | `grep -rn 'tourStep\$\|nextStep()\|skipTour\|completeTour\|isTourOnStep4\|tour-story-chip\|data-tour="tab3"' src/app` returns nothing | grep exit=1, zero output. Legacy numeric-step API fully removed. | pass |
| 11 | static | `grep -rn "app-first-time-tour" src/app` hits exactly `app.component.html` (host) and the component's own `selector:` line | `src/app/app.component.html:5:  <app-first-time-tour></app-first-time-tour>` and `src/app/components/first-time-tour/first-time-tour.component.ts:36:  selector: 'app-first-time-tour',` — exactly 2 hits. `tabs.page.html` no longer hosts it; `tabs.page.ts` import removed. | pass |
| 12 | static | `TOUR_STEPS` in `first-time-tour.component.ts` carries these five labels verbatim: `Your portfolio and its insights live here.` / `Your retirement options and analyses live here.` / `Your settings live here. Tap your pig to open your profile.` / `This is your Freedom Plan. Tap it to see how you get there.` / `Ask FRED anything here. Start with this one.` | `first-time-tour.component.ts:16-22` — all five present verbatim, in order, mapped to steps `tab1`/`tab2`(explain) and `tab3Avatar`/`profileFreedom`/`chatStory`(gated). | pass |
| 13 | static | no U+2014 em dash in: `services/first-time-tour.service.ts`, `components/first-time-tour/*` (7 files), and in the ADDED lines of `git diff 3574314..ff8d6de` for `ai-chat.page.ts`, `my-profile.page.ts`, `app.component.*`, `tabs.page.*`, `tab3.page.html` | Both greps exit=1 (no match). Note: the tour component dir holds **6** files, not 7 (`*.html/.scss/.spec.ts/.ts`, `spotlight-geometry.ts/.spec.ts`) — manifest miscount, all 6 were scanned. The `// ── Section ──` separators are U+2500 box-drawing, not em dashes. | pass |
| 14 | static | `grep -n "FORCE_FIRST_TIME_TOUR = " src/app/services/first-time-tour.service.ts` shows `= false` | `17:const FORCE_FIRST_TIME_TOUR = false;` | pass |
| 15 | static | `data-tour` targets exist in the working tree: `profile-avatar` in `tab3/tab3.page.html`, `freedom-row` in `pages/my-profile/my-profile.page.html`, `story-chip` binding in `pages/ai-chat/ai-chat.page.html` | `tab3.page.html:5: <div class="avatar-badge-wrap" data-tour="profile-avatar" (click)="goToMyProfile()">`; `my-profile.page.html:58: <div class="goal-row" data-tour="freedom-row" (click)="openFreedomSheet()">`; `ai-chat.page.html:97: [attr.data-tour]="question === FRED_STORY_TRIGGER ? 'story-chip' : null"`. Old `data-tour="tab3"` on the floating tab bar removed. | pass |
| 16 | static | in the WORKING TREE `pages/my-profile/my-profile.page.ts`, `openFreedomSheet()` calls `this.tourService?.suspend()` before `modal.present()` and `this.tourService?.freedomSheetClosed()` after `await modal.onDidDismiss` (these two lines are intentionally unstaged: they live inside the user's uncommitted freedom-sheet WIP and ride with it) | `my-profile.page.ts:688 this.tourService?.suspend();` immediately before `await modal.present();`; `:691 this.tourService?.freedomSheetClosed();` immediately after `const { role, data } = await modal.onDidDismiss<FreedomSheetResult>();`. Service obtained defensively at `:203-204` via `try { inject(FirstTimeTourService) } catch { null }`. | pass |
| 17 | static | gated rendering in `first-time-tour.component.html`: four `.ftt-panel` elements + one `.ftt-ring` when `isSpotlight`, no element covering the hole; `.ftt-ring` has `pointer-events: none` and `.ftt-panel` has `pointer-events: auto` in the scss | `first-time-tour.component.html:12-28` — `*ngIf="isSpotlight && layout"` wraps `*ngFor="let p of layout.panels"` (`.ftt-panel`) plus one `.ftt-ring` positioned on `layout.hole`; no element is rendered inside the hole. `spotlight-geometry.ts:48-53` always returns exactly 4 panels (top, bottom, left, right). SCSS: `.ftt-panel` `pointer-events: auto` (:32), `.ftt-ring` `pointer-events: none` (:41), `.ftt-overlay` `pointer-events: none` (:11) so the hole is fully transparent to taps, `.ftt-backdrop` (:20) and `.ftt-card` (:56) `auto`. | pass |

## Additional checks run beyond the manifest

- **Auth/JWT**: no auth or JWT code in the range. `git diff 3574314..ff8d6de | grep '^+.*jwt|Jwt|JWT|token'` returns only tour-storage-key lines and the other session's `userId` cache-key read. No `JwtTokenUtils` bypass.
- **Safe-area notch gate** (`node test/ui/safe-area-lint.mjs`) on the changed top-chrome files: exit 1 with one offender, `my-profile.page.html:1 ion-header > div.blue-hero-header`. **False positive, not in scope**: `.blue-hero-header` gets `padding: calc(env(safe-area-inset-top, 0px) + 2px) ...` from the shared partials `src/theme/_settings-shell.scss:73` and `src/theme/_blue-hero-header.scss:22`; the linter does not follow `@use` into shared theme partials. The hero markup is untouched by this range (only `data-tour="freedom-row"` was added, on a `.goal-row` far below).
- **Loading / error / empty states (static)**: the overlay has three rendering modes with styled branches — `isMeasuring` (dark backdrop + label while polling), `fallback` (backdrop + working Next that calls `forceGate()` so the tour can never wedge on a missing target), and `isSpotlight`. Poll bounded at `TARGET_POLL_MAX_ATTEMPTS = 188` x 16ms ~= 3s. All three timers (`pollTimer`, `settleTimer`, `cardMeasureTimer`) are cancelled in both `onStep()` and `ngOnDestroy()`.
- **Phase 2 (API)**: skipped, frontend-only diff, no endpoint touched.
- **Phase 3 (UI runtime)**: not run. No dev server or device in this environment; deferred to Andy's device pass per the task brief.

## Deferred to Andy's device pass

1. Full five-step walkthrough on device: tab1 -> tab2 (Next), pig-avatar gate, Freedom-row gate with the sheet suspend/resume, story-chip gate, completion.
2. Spotlight hole alignment on a notched iPhone for the pig avatar (it sits under `safe-area-top`) and card placement above the floating tab bar (`BOTTOM_RESERVE_PX = 110`).
3. `navigateBack('/tabs/chat')` from `freedomSheetClosed()`: confirm the swiper pager actually lands on chat and the back transition plays.
4. To replay the tour on device you must clear `localStorage['hasSeenFirstTimeTour']` or flip `FORCE_FIRST_TIME_TOUR` (never commit `true`).

## User-state coverage gap

The tour only starts for `userProgress.investmentConfirmationCompleted === true` AND `hasSeenFirstTimeTour !== 'true'` (`tab1.page.ts:32-37`, untouched by this range). No runtime test here covers either state. The device pass should cover: (a) a fresh post-investment-confirmation user (tour runs), (b) a user who already saw it (tour silent), (c) a mid-onboarding user (tour must not fire).
