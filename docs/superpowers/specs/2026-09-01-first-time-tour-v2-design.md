# First-Time Tour v2 — Design

**Date:** 2026-09-01
**Status:** Approved by Andy (design), pending spec review
**Replaces:** FRED-117 first-time tour (`first-time-tour.service.ts`, `first-time-tour.component.*`)

## Problem

The current tour (4 numbered steps, "Next" on every step) does not function as a guided tour:

- Steps 1 and 2 have no target; they show a card over a 75% black backdrop, so the user cannot see the tab being described.
- Step 3 spotlights the tab3 tab-bar button, not the pig avatar. The spotlight runs one `querySelector` after a fixed 400ms timeout with no retry; on a slow slide it finds nothing and shows a full-dark screen with no hole.
- No step waits for the user to do anything. Every step is a Next button.
- There is no Freedom Plan step, and there cannot be one: the overlay lives in `tabs.page.html`, and Ionic applies `ion-page-hidden` (display:none) to TabsPage when `/my-profile` is pushed, so the overlay would vanish the instant the user taps the pig.
- Step 4 relies on the backend returning "What's your story FRED?" in the daily suggestions. If the request is slow, fails, or the chip was filtered, the step is a dark screen with only "Skip tour".
- Step 4 uses `pointer-events: none` on the whole backdrop, so every chip and the composer are tappable during the tour.
- No way to replay the tour except clearing localStorage.
- The tour mocks in `tabs.page.spec.ts` and `ai-chat.page.spec.ts` stub methods (`isTourActive`, `getCurrentStep`) that do not exist on the service.

## Goal

A tour that shows, in order:

1. Tab 1: the user's portfolio and its insights.
2. Tab 2: retirement options and analyses.
3. Tab 3: settings, with the screen dark except the pig avatar in the top-left; the user must tap it to continue.
4. My Profile: the screen dark except the Freedom Date row; the user must tap it. The Freedom sheet opens normally. When it closes, the tour moves to chat.
5. Chat: the screen dark except the "What's your story FRED?" chip; the user must tap it. That sends the message and completes the tour.

Plus a code-level constant to force the tour to run every time, for testing.

## Design

### 1. State model (`FirstTimeTourService`, rewrite)

```ts
export type TourStep =
  | 'tab1'            // explain
  | 'tab2'            // explain
  | 'tab3Avatar'      // gated: tap pig avatar
  | 'profileFreedom'  // gated: tap Freedom Date row, sheet opens, advance on dismiss
  | 'chatStory';      // gated: tap story chip, completes tour

/** Flip to true to run the tour on every tab1 load (dev only, never commit true). */
const FORCE_FIRST_TIME_TOUR = false;
```

Public surface:

| Member | Behavior |
|---|---|
| `step$` / `currentStep` | `BehaviorSubject<TourStep \| null>`; `null` = not running. |
| `hasSeenTour()` | `false` when `FORCE_FIRST_TIME_TOUR`; else `localStorage['hasSeenFirstTimeTour'] === 'true'`. |
| `startTour()` | No-op if `hasSeenTour()`. Sets step `tab1`. |
| `next()` | Explain steps only. `tab1 → tab2` (navigates `/tabs/tab2`), `tab2 → tab3Avatar` (navigates `/tabs/tab3`). Ignored on gated steps. |
| `suspend()` / `resume()` | Hides/shows the overlay without changing step. Used while the Freedom sheet is open. Exposed as `suspended$`. |
| `freedomSheetClosed()` | Only acts when step is `profileFreedom`: resumes, navigates `navCtrl.navigateBack('/tabs/chat')`, sets step `chatStory`. |
| `storyChipTapped()` | Only acts when step is `chatStory`: `complete()`. |
| `skip()` / `complete()` | Writes the localStorage flag, sets step `null`. No navigation. |

Router-driven advance: the service subscribes to `Router.events` (`NavigationEnd`). When step is `tab3Avatar` and `urlAfterRedirects` starts with `/my-profile`, step becomes `profileFreedom`. No hook in tab3 is needed for the pig tap.

Intra-tab moves (`tab1 -> tab2 -> tab3`, and the return to chat) do NOT use router navigation to `/tabs/<tab>`: Ionic's route reuse is keyed on the view's stamped URL, so a router move to a different tab URL creates a second TabsPage (and its fresh Tab1Page would restart the tour). Instead the service calls `TabActivationService.requestTab(tab)`; TabsPage subscribes and calls `slideToTab`, whose `onSlideChange`/`onTransitionEnd` sync the URL via `replaceState`. The return from my-profile is `navCtrl.pop()` (which reuses the cached TabsPage by its own URL) followed by `requestTab('chat')` once the pop resolves. `/my-profile` remains a real forward navigation.

Step state is in-memory only. If the app reloads mid-tour, the tour restarts at `tab1` on the next tab1 load (the flag is written only on complete/skip).

Trigger is unchanged: `Tab1Page.ngOnInit` starts the tour once `userProgress.investmentConfirmationCompleted === true && !hasSeenTour()`.

### 2. Overlay host moves to `app.component.html`

`<app-first-time-tour>` is removed from `tabs.page.html` and placed inside `<ion-app>` after the `<ion-router-outlet>`. Position is `fixed; inset: 0` with a z-index above pages but below Ionic overlays (`z-index: 9999`; ion-modal is 20000+). Ionic hides inactive pages with display:none, but the app component is never hidden, so the overlay survives the push to `/my-profile` and the return to `/tabs/chat`.

### 3. Overlay component (`FirstTimeTourComponent`, rewrite)

Step definitions live in the component:

```ts
interface TourStepDef {
  step: TourStep;
  kind: 'explain' | 'gated';
  label: string;
  targetSelector?: string;   // gated only
}
```

| Step | Kind | Target | Copy |
|---|---|---|---|
| tab1 | explain | none | Your portfolio and its insights live here. |
| tab2 | explain | none | Your retirement options and analyses live here. |
| tab3Avatar | gated | `[data-tour="profile-avatar"]` | Your settings live here. Tap your pig to open your profile. |
| profileFreedom | gated | `[data-tour="freedom-row"]` | This is your Freedom Plan. Tap it to see how you get there. |
| chatStory | gated | `[data-tour="story-chip"]` | Ask FRED anything here. Start with this one. |

No em dashes anywhere in tour copy (house style).

**Explain rendering:** one full backdrop at `rgba(0,0,0,0.55)` so the page is legible underneath, plus a card (dots, label, **Next**, Skip tour). The backdrop blocks taps.

**Gated rendering:** four dark panels (`rgba(0,0,0,0.75)`) laid out around the target rect (top, bottom, left, right), leaving the hole itself with no overlay element. The real target receives the real tap; everything else is under a panel and inert. A ring element (border only, `pointer-events: none`, pulsing box-shadow) outlines the hole with 8px padding and a 12px radius. The card shows dots, label, and Skip tour only (no Next). Card sits below the hole if there is room for it above the tab bar, else above the hole.

**Target measurement:** on entering a gated step, poll on a 16ms timer until `document.querySelector(selector)` exists with a nonzero `getBoundingClientRect()`, up to 3000ms. A rect fully outside the viewport counts as not found; after the first hit, polling continues until the rect is unchanged for 3 consecutive ticks so a mid-transition position is never committed. Re-measure once 600ms after first found (slide/page transition settle) and on `resize` / `orientationchange`. Store the rect and derive the four panel geometries from it.

**Fallback:** if the target is not found within 3000ms, render the step as an explain step (full backdrop + label + Next). Next on a fallback advances the state machine as if the gate had been passed: `tab3Avatar` → navigate `/my-profile` and set `profileFreedom`; `profileFreedom` → `freedomSheetClosed()`; `chatStory` → `complete()`. The user never sees a dead dark screen.

**Suspended:** when `suspended$` is true the whole overlay is hidden (`display: none`), step unchanged.

### 4. Target hooks

- `tab3.page.html`: `data-tour="profile-avatar"` on `.avatar-badge-wrap`.
- `my-profile.page.html`: `data-tour="freedom-row"` on the Freedom Date `.goal-row`.
- `my-profile.page.ts` `openFreedomSheet()`: call `tourService.suspend()` before `modal.present()`, and `tourService.freedomSheetClosed()` after `onDidDismiss` resolves (regardless of role). Both are no-ops unless the tour is on `profileFreedom`.
- `ai-chat.page.html`: `data-tour="story-chip"` on the chip whose text equals `FRED_STORY_TRIGGER`.
- `ai-chat.page.ts`:
  - `isTourOnStoryStep` getter replaces `isTourOnStep4`.
  - When the step becomes `chatStory`: if `!isEmptyState`, call `startNewChat()` so the chips are visible (only reachable in replay mode). Then ensure `FRED_STORY_TRIGGER` is in `dailySuggestions`; if absent (backend omitted it, filtered as used, or request failed), prepend it client-side. `loadDailySuggestions()` applies the same guarantee while on that step, so a late response cannot remove the chip.
  - `selectSuggestion()`: when on `chatStory` and the question is the story trigger, call `tourService.storyChipTapped()`; the send proceeds as today.
- Remove `.tour-story-chip` z-index hack and `tour-chip-pulse` from `ai-chat.page.scss` (the hole handles visibility; the ring handles the pulse).
- `floating-tab-bar.component.html`: remove the `data-tour="tab3"` attribute (no longer a target).

### 5. Replay toggle

`FORCE_FIRST_TIME_TOUR` in `first-time-tour.service.ts` (see section 1). Comment in as `true` to run the tour on every tab1 load; the flag write on complete is still performed but ignored. Not gated on environment; the constant must be `false` in committed code.

### 6. Error handling

- Navigation promise rejection (router refuses a nav): caught and logged; step still advances so the tour cannot wedge.
- Target never found: fallback to explain rendering (section 3).
- Skip on any step: completes without navigating; the user stays wherever they are (including my-profile).
- Backgrounding mid-tour: step is in-memory and persists across resume. The `resize` handler re-measures on return.
- Expired-subscription users: tour starts only when fully onboarded, same as today; tab gating for expired subs is unchanged.

### 7. Testing

**`first-time-tour.service.spec.ts` (new):**
- `startTour` is a no-op when the flag is set; runs when unset.
- `next` walks `tab1 → tab2 → tab3Avatar` and calls the router with `/tabs/tab2` then `/tabs/tab3`; `next` is ignored on gated steps.
- `NavigationEnd` to `/my-profile` advances `tab3Avatar → profileFreedom`, and does nothing on other steps.
- `freedomSheetClosed` on `profileFreedom` navigates back to `/tabs/chat` and sets `chatStory`; no-op on other steps.
- `storyChipTapped` on `chatStory` completes and writes the flag; no-op otherwise.
- `suspend`/`resume` toggle `suspended$` without changing step.
- `skip` writes the flag and clears the step without navigating.

**`first-time-tour.component.spec.ts` (new):**
- Panel geometry: given a target rect and viewport size, the four panels tile the viewport minus the padded hole.
- Explain steps render Next; gated steps do not.
- Gated step with no matching element falls back to explain rendering after the timeout (fake async).
- Suspended hides the overlay.

**Existing specs:**
- `tabs.page.spec.ts`: remove the FirstTimeTour NavController stub comment/provider (component no longer in TabsPage).
- `ai-chat.page.spec.ts`: replace the stale mock with the real service shape (`currentStep`, `step$`, `storyChipTapped`); add a test that entering `chatStory` prepends the story chip when the backend list lacks it.
- `app.component.spec.ts`: the overlay component is now imported by AppComponent; provide `FirstTimeTourService` (or let the real one run with `RouterTestingModule` + `NavController` stub) so construction succeeds.

**Gates:** karma specs green, `ng build` (AOT) clean, verifier-agent pass, then a device pass on Andy's iPhone for the three gated taps (hole geometry needs a real WKWebView check) and for the Freedom sheet suspend/resume.

## Files touched

- `frontend/src/app/services/first-time-tour.service.ts` (rewrite) + new spec
- `frontend/src/app/components/first-time-tour/first-time-tour.component.{ts,html,scss}` (rewrite) + new spec
- `frontend/src/app/app.component.{ts,html}` (host the overlay)
- `frontend/src/app/tabs/tabs.page.{ts,html}` + spec (remove the overlay)
- `frontend/src/app/tab1/tab1.page.ts` (unchanged trigger; no edit expected)
- `frontend/src/app/tab3/tab3.page.html` (data-tour)
- `frontend/src/app/pages/my-profile/my-profile.page.{ts,html}` (data-tour + two hooks)
- `frontend/src/app/pages/ai-chat/ai-chat.page.{ts,html,scss}` + spec (data-tour, chip guarantee, tap hook, style cleanup)
- `frontend/src/app/components/floating-tab-bar/floating-tab-bar.component.html` (drop old data-tour)

## Out of scope

- Persisting tour progress across app reloads.
- A user-facing "replay tour" setting.
- Backend changes (the story chip guarantee is client-side).
