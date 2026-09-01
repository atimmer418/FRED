# First-Time Tour v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Next-button tour with a five-step guided tour where three steps darken the screen except one real target the user must tap (pig avatar → Freedom Date row → "What's your story FRED?" chip).

**Architecture:** `FirstTimeTourService` becomes a named-step state machine that navigates via the router and advances gated steps from router events and two explicit hooks. `FirstTimeTourComponent` moves from TabsPage to AppComponent (so it survives the push to `/my-profile`) and renders gated steps as four dark panels around a measured target rect, leaving the target itself uncovered and tappable. A pure geometry helper computes the panels so the layout is unit-tested without a DOM.

**Tech Stack:** Angular 19 standalone components, Ionic 8 (`NavController`, `ModalController`), RxJS, Karma + Jasmine (`npx ng test --include=... --watch=false --browsers=ChromeHeadless`).

**Spec:** `docs/superpowers/specs/2026-09-01-first-time-tour-v2-design.md`

## Global Constraints

- No em dashes in any user-facing copy or code comments (house style). Use a comma, colon, or parentheses.
- `FORCE_FIRST_TIME_TOUR` must be `false` in every commit.
- Tour copy is fixed (verbatim from the spec):
  1. `Your portfolio and its insights live here.`
  2. `Your retirement options and analyses live here.`
  3. `Your settings live here. Tap your pig to open your profile.`
  4. `This is your Freedom Plan. Tap it to see how you get there.`
  5. `Ask FRED anything here. Start with this one.`
- Design system: Manrope font, `#2563EB` primary, `#111827` text, 16px card radius, 24px pill buttons (matches the existing tour card).
- The working tree carries Andy's unrelated WIP (87 modified files). Stage ONLY the paths named in each task's commit step, never `git add -A` or `git add .`. Before staging an already-modified file (ai-chat, tabs, my-profile, tab3, floating-tab-bar, app.component), run `git diff HEAD -- <file>` and confirm every hunk is yours; if a hunk is not yours, stage with `git add -p` and skip it.
- Test command shape (run from `frontend/`): `npx ng test --include='**/<name>.spec.ts' --watch=false --browsers=ChromeHeadless`. The full `ng test` has pre-existing bit-rotted specs (e.g. `tab1.page.spec.ts` provides no router); only run the specs named in each task.
- AOT gate: `cd frontend && npx ng build --configuration dev` (NOT `development`, which exits 127).

---

## File structure

| File | Responsibility |
|---|---|
| `frontend/src/app/services/first-time-tour.service.ts` (rewrite) | Step state machine, navigation, router-driven advance, suspend/resume, localStorage flag, force constant. |
| `frontend/src/app/services/first-time-tour.service.spec.ts` (new) | State machine tests with Router + NavController stubs. |
| `frontend/src/app/components/first-time-tour/spotlight-geometry.ts` (new) | Pure function: target rect + viewport → hole, four panels, card placement. |
| `frontend/src/app/components/first-time-tour/spotlight-geometry.spec.ts` (new) | Geometry tests. |
| `frontend/src/app/components/first-time-tour/first-time-tour.component.{ts,html,scss}` (rewrite) | Renders explain/gated/fallback/suspended states, polls for targets. |
| `frontend/src/app/components/first-time-tour/first-time-tour.component.spec.ts` (new) | Render-state tests with a service stub. |
| `frontend/src/app/app.component.{ts,html}` + spec | Hosts the overlay. |
| `frontend/src/app/tabs/tabs.page.{ts,html}` + spec | Stops hosting the overlay. |
| `frontend/src/app/components/floating-tab-bar/floating-tab-bar.component.html` | Drops the old `data-tour="tab3"`. |
| `frontend/src/app/tab3/tab3.page.html` | `data-tour="profile-avatar"`. |
| `frontend/src/app/pages/my-profile/my-profile.page.{ts,html}` + 2 specs | `data-tour="freedom-row"`, suspend/closed hooks. |
| `frontend/src/app/pages/ai-chat/ai-chat.page.{ts,html,scss}` + spec | `data-tour="story-chip"`, chip guarantee, tap hook, style cleanup. |

---

### Task 1: Tour service state machine

**Files:**
- Rewrite: `frontend/src/app/services/first-time-tour.service.ts`
- Create: `frontend/src/app/services/first-time-tour.service.spec.ts`

**Interfaces:**
- Consumes: `Router` (`events`, `navigateByUrl`), `NavController.navigateBack`.
- Produces (used by Tasks 2, 4, 5):
  ```ts
  export type TourStep = 'tab1' | 'tab2' | 'tab3Avatar' | 'profileFreedom' | 'chatStory';
  export const TOUR_STORAGE_KEY = 'hasSeenFirstTimeTour';
  class FirstTimeTourService {
    readonly step$: Observable<TourStep | null>;
    readonly suspended$: Observable<boolean>;
    get currentStep(): TourStep | null;
    get isSuspended(): boolean;
    hasSeenTour(): boolean;
    startTour(): void;
    next(): void;            // explain steps only
    forceGate(): void;       // fallback: advance a gated step as if its gate passed
    suspend(): void;
    resume(): void;
    freedomSheetClosed(): void;
    storyChipTapped(): void;
    skip(): void;
    complete(): void;
  }
  ```

- [ ] **Step 1: Write the failing service spec**

Create `frontend/src/app/services/first-time-tour.service.spec.ts`:

```ts
import { TestBed } from '@angular/core/testing';
import { NavigationEnd, Router } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
import { Subject } from 'rxjs';
import { FirstTimeTourService, TOUR_STORAGE_KEY } from './first-time-tour.service';

describe('FirstTimeTourService', () => {
  let svc: FirstTimeTourService;
  let routerEvents: Subject<unknown>;
  let navigateByUrl: jasmine.Spy;
  let navigateBack: jasmine.Spy;

  beforeEach(() => {
    localStorage.removeItem(TOUR_STORAGE_KEY);
    routerEvents = new Subject<unknown>();
    navigateByUrl = jasmine.createSpy('navigateByUrl').and.returnValue(Promise.resolve(true));
    navigateBack = jasmine.createSpy('navigateBack').and.returnValue(Promise.resolve(true));
    TestBed.configureTestingModule({
      providers: [
        { provide: Router, useValue: { events: routerEvents.asObservable(), navigateByUrl } },
        { provide: NavController, useValue: { navigateBack } },
      ],
    });
    svc = TestBed.inject(FirstTimeTourService);
  });

  afterEach(() => localStorage.removeItem(TOUR_STORAGE_KEY));

  function landOn(url: string) {
    routerEvents.next(new NavigationEnd(1, url, url));
  }

  it('starts at tab1 when the flag is unset, and is a no-op when set', () => {
    localStorage.setItem(TOUR_STORAGE_KEY, 'true');
    svc.startTour();
    expect(svc.currentStep).toBeNull();

    localStorage.removeItem(TOUR_STORAGE_KEY);
    svc.startTour();
    expect(svc.currentStep).toBe('tab1');
  });

  it('next walks tab1 → tab2 → tab3Avatar and navigates the pager', () => {
    svc.startTour();
    svc.next();
    expect(svc.currentStep).toBe('tab2');
    expect(navigateByUrl).toHaveBeenCalledWith('/tabs/tab2');
    svc.next();
    expect(svc.currentStep).toBe('tab3Avatar');
    expect(navigateByUrl).toHaveBeenCalledWith('/tabs/tab3');
  });

  it('next is ignored on gated steps', () => {
    svc.startTour();
    svc.next();
    svc.next(); // tab3Avatar
    navigateByUrl.calls.reset();
    svc.next();
    expect(svc.currentStep).toBe('tab3Avatar');
    expect(navigateByUrl).not.toHaveBeenCalled();
  });

  it('landing on /my-profile advances tab3Avatar → profileFreedom, and nothing else', () => {
    svc.startTour();
    landOn('/my-profile');
    expect(svc.currentStep).toBe('tab1');

    svc.next();
    svc.next(); // tab3Avatar
    landOn('/my-profile');
    expect(svc.currentStep).toBe('profileFreedom');
  });

  it('freedomSheetClosed on profileFreedom navigates back to chat and sets chatStory', () => {
    svc.startTour();
    svc.next();
    svc.next();
    landOn('/my-profile');
    svc.suspend();
    expect(svc.isSuspended).toBeTrue();

    svc.freedomSheetClosed();
    expect(svc.isSuspended).toBeFalse();
    expect(navigateBack).toHaveBeenCalledWith('/tabs/chat');
    expect(svc.currentStep).toBe('chatStory');
  });

  it('freedomSheetClosed is a no-op on other steps', () => {
    svc.startTour();
    svc.freedomSheetClosed();
    expect(navigateBack).not.toHaveBeenCalled();
    expect(svc.currentStep).toBe('tab1');
  });

  it('storyChipTapped on chatStory completes and writes the flag; no-op otherwise', () => {
    svc.startTour();
    svc.storyChipTapped();
    expect(svc.currentStep).toBe('tab1');
    expect(localStorage.getItem(TOUR_STORAGE_KEY)).toBeNull();

    svc.next();
    svc.next();
    landOn('/my-profile');
    svc.freedomSheetClosed();
    svc.storyChipTapped();
    expect(svc.currentStep).toBeNull();
    expect(localStorage.getItem(TOUR_STORAGE_KEY)).toBe('true');
  });

  it('suspend/resume toggle suspended$ without changing the step', () => {
    svc.startTour();
    svc.suspend();
    expect(svc.isSuspended).toBeTrue();
    expect(svc.currentStep).toBe('tab1');
    svc.resume();
    expect(svc.isSuspended).toBeFalse();
  });

  it('suspend is ignored when the tour is not running', () => {
    svc.suspend();
    expect(svc.isSuspended).toBeFalse();
  });

  it('skip writes the flag, clears the step, and does not navigate', () => {
    svc.startTour();
    svc.skip();
    expect(svc.currentStep).toBeNull();
    expect(localStorage.getItem(TOUR_STORAGE_KEY)).toBe('true');
    expect(navigateByUrl).not.toHaveBeenCalled();
    expect(navigateBack).not.toHaveBeenCalled();
  });

  it('forceGate advances gated steps as if the gate passed', () => {
    svc.startTour();
    svc.next();
    svc.next(); // tab3Avatar
    svc.forceGate();
    expect(navigateByUrl).toHaveBeenCalledWith('/my-profile');
    expect(svc.currentStep).toBe('profileFreedom');

    svc.forceGate();
    expect(navigateBack).toHaveBeenCalledWith('/tabs/chat');
    expect(svc.currentStep).toBe('chatStory');

    svc.forceGate();
    expect(svc.currentStep).toBeNull();
    expect(localStorage.getItem(TOUR_STORAGE_KEY)).toBe('true');
  });

  it('forceGate is ignored on explain steps', () => {
    svc.startTour();
    svc.forceGate();
    expect(svc.currentStep).toBe('tab1');
    expect(navigateByUrl).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run the spec to verify it fails**

Run: `cd frontend && npx ng test --include='**/first-time-tour.service.spec.ts' --watch=false --browsers=ChromeHeadless`
Expected: FAIL (compile errors: `TOUR_STORAGE_KEY` not exported, `next`/`forceGate`/`suspended$` do not exist).

- [ ] **Step 3: Rewrite the service**

Replace the full contents of `frontend/src/app/services/first-time-tour.service.ts`:

```ts
import { Injectable } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
import { BehaviorSubject } from 'rxjs';
import { filter } from 'rxjs/operators';

/**
 * Tour steps, in order. 'tab1' and 'tab2' are explain steps (Next button).
 * The other three are gated: the overlay darkens everything except one
 * target, and the tour advances only when the user performs the real action.
 */
export type TourStep = 'tab1' | 'tab2' | 'tab3Avatar' | 'profileFreedom' | 'chatStory';

export const TOUR_STORAGE_KEY = 'hasSeenFirstTimeTour';

/** Flip to true to run the tour on every tab1 load (dev only, never commit true). */
const FORCE_FIRST_TIME_TOUR = false;

@Injectable({
  providedIn: 'root'
})
export class FirstTimeTourService {

  private stepSubject = new BehaviorSubject<TourStep | null>(null);
  /** Current step, or null when the tour is not running. */
  readonly step$ = this.stepSubject.asObservable();

  private suspendedSubject = new BehaviorSubject<boolean>(false);
  /** True while the overlay should be hidden without changing step (Freedom sheet open). */
  readonly suspended$ = this.suspendedSubject.asObservable();

  constructor(
    private router: Router,
    private navCtrl: NavController
  ) {
    // The pig-avatar gate passes when the router lands on /my-profile. Driving
    // it from router events means tab3 needs no tour hook at all.
    this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe(e => this.onNavigationEnd(e.urlAfterRedirects));
  }

  get currentStep(): TourStep | null {
    return this.stepSubject.value;
  }

  get isSuspended(): boolean {
    return this.suspendedSubject.value;
  }

  hasSeenTour(): boolean {
    if (FORCE_FIRST_TIME_TOUR) return false;
    return localStorage.getItem(TOUR_STORAGE_KEY) === 'true';
  }

  startTour(): void {
    if (this.hasSeenTour()) return;
    this.suspendedSubject.next(false);
    this.stepSubject.next('tab1');
  }

  /** Explain steps only. Ignored on gated steps. */
  next(): void {
    switch (this.currentStep) {
      case 'tab1':
        this.go('/tabs/tab2');
        this.stepSubject.next('tab2');
        break;
      case 'tab2':
        this.go('/tabs/tab3');
        this.stepSubject.next('tab3Avatar');
        break;
    }
  }

  /**
   * Fallback used by the overlay when a gated target cannot be found on
   * screen: advance as if the gate had been passed so the tour never wedges.
   */
  forceGate(): void {
    switch (this.currentStep) {
      case 'tab3Avatar':
        this.go('/my-profile');
        this.stepSubject.next('profileFreedom');
        break;
      case 'profileFreedom':
        this.freedomSheetClosed();
        break;
      case 'chatStory':
        this.complete();
        break;
    }
  }

  suspend(): void {
    if (this.currentStep === null) return;
    this.suspendedSubject.next(true);
  }

  resume(): void {
    this.suspendedSubject.next(false);
  }

  /** Called by my-profile after the Freedom sheet dismisses (any role). */
  freedomSheetClosed(): void {
    if (this.currentStep !== 'profileFreedom') return;
    this.suspendedSubject.next(false);
    // TabsPage is cached in the root outlet; its paramMap subscription slides
    // the pager to chat and navigateBack plays the back transition.
    this.navCtrl.navigateBack('/tabs/chat')
      .catch(err => console.warn('[tour] navigateBack to chat failed', err));
    this.stepSubject.next('chatStory');
  }

  /** Called by ai-chat when the story chip is tapped. */
  storyChipTapped(): void {
    if (this.currentStep !== 'chatStory') return;
    this.complete();
  }

  skip(): void {
    this.complete();
  }

  complete(): void {
    localStorage.setItem(TOUR_STORAGE_KEY, 'true');
    this.suspendedSubject.next(false);
    this.stepSubject.next(null);
  }

  private onNavigationEnd(url: string): void {
    if (this.currentStep === 'tab3Avatar' && url.startsWith('/my-profile')) {
      this.stepSubject.next('profileFreedom');
    }
  }

  private go(url: string): void {
    this.router.navigateByUrl(url)
      .catch(err => console.warn('[tour] navigation failed', url, err));
  }
}
```

- [ ] **Step 4: Run the spec to verify it passes**

Run: `cd frontend && npx ng test --include='**/first-time-tour.service.spec.ts' --watch=false --browsers=ChromeHeadless`
Expected: 12 specs, all PASS.

The rest of the app still references `tourStep$`, `nextStep`, `skipTour`, `currentStep === 4`. Those are fixed in Tasks 2 and 5; the AOT build is not a gate until Task 6.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/services/first-time-tour.service.ts frontend/src/app/services/first-time-tour.service.spec.ts
git commit -m "feat(tour): named-step state machine with gated steps and router-driven advance"
```

---

### Task 2: Spotlight geometry helper

**Files:**
- Create: `frontend/src/app/components/first-time-tour/spotlight-geometry.ts`
- Create: `frontend/src/app/components/first-time-tour/spotlight-geometry.spec.ts`

**Interfaces:**
- Produces (used by Task 3):
  ```ts
  export interface Rect { top: number; left: number; width: number; height: number; }
  export interface SpotlightLayout {
    hole: Rect;
    panels: Rect[];        // exactly 4: top, bottom, left, right
    cardTop: number;       // px from viewport top
  }
  export const HOLE_PAD = 8;
  export const CARD_GAP = 16;
  export function computeSpotlight(
    target: Rect, viewportW: number, viewportH: number, cardHeight: number, bottomReserve: number
  ): SpotlightLayout;
  ```

- [ ] **Step 1: Write the failing geometry spec**

Create `frontend/src/app/components/first-time-tour/spotlight-geometry.spec.ts`:

```ts
import { computeSpotlight, HOLE_PAD, CARD_GAP } from './spotlight-geometry';

describe('computeSpotlight', () => {
  const VW = 390;
  const VH = 844;

  it('pads the hole around the target and tiles the viewport with four panels', () => {
    const target = { top: 60, left: 20, width: 52, height: 52 };
    const layout = computeSpotlight(target, VW, VH, 140, 110);

    expect(layout.hole).toEqual({ top: 52, left: 12, width: 68, height: 68 });
    expect(layout.panels.length).toBe(4);

    const [top, bottom, left, right] = layout.panels;
    expect(top).toEqual({ top: 0, left: 0, width: VW, height: 52 });
    expect(bottom).toEqual({ top: 120, left: 0, width: VW, height: VH - 120 });
    expect(left).toEqual({ top: 52, left: 0, width: 12, height: 68 });
    expect(right).toEqual({ top: 52, left: 80, width: VW - 80, height: 68 });

    // Panels + hole cover the viewport exactly once.
    const area = layout.panels.reduce((a, p) => a + p.width * p.height, 0) + layout.hole.width * layout.hole.height;
    expect(area).toBe(VW * VH);
  });

  it('clamps the hole to the viewport for edge targets', () => {
    const target = { top: 2, left: 0, width: 40, height: 40 };
    const layout = computeSpotlight(target, VW, VH, 140, 110);
    expect(layout.hole.top).toBe(0);
    expect(layout.hole.left).toBe(0);
    expect(layout.hole.height).toBe(2 + 40 + HOLE_PAD);
    expect(layout.hole.width).toBe(40 + HOLE_PAD);
    expect(layout.panels[0].height).toBe(0);
    expect(layout.panels[2].width).toBe(0);
  });

  it('places the card below the hole when there is room above the bottom reserve', () => {
    const target = { top: 60, left: 20, width: 52, height: 52 };
    const layout = computeSpotlight(target, VW, VH, 140, 110);
    const holeBottom = 52 + 68;
    expect(layout.cardTop).toBe(holeBottom + CARD_GAP);
  });

  it('places the card above the hole when the target sits near the bottom', () => {
    const target = { top: 700, left: 20, width: 350, height: 60 };
    const layout = computeSpotlight(target, VW, VH, 140, 110);
    const holeTop = 700 - HOLE_PAD;
    expect(layout.cardTop).toBe(holeTop - CARD_GAP - 140);
  });

  it('never places the card above the top safe margin', () => {
    const target = { top: 30, left: 20, width: 350, height: 780 };
    const layout = computeSpotlight(target, VW, VH, 140, 110);
    expect(layout.cardTop).toBe(CARD_GAP);
  });
});
```

- [ ] **Step 2: Run the spec to verify it fails**

Run: `cd frontend && npx ng test --include='**/spotlight-geometry.spec.ts' --watch=false --browsers=ChromeHeadless`
Expected: FAIL (module `./spotlight-geometry` not found).

- [ ] **Step 3: Implement the helper**

Create `frontend/src/app/components/first-time-tour/spotlight-geometry.ts`:

```ts
/**
 * Pure layout math for the gated tour steps. Given the target's rect, produce
 * the padded hole, the four dark panels that tile the viewport around it, and
 * where the tooltip card should sit. No DOM access, so it is unit-testable.
 */

export interface Rect {
  top: number;
  left: number;
  width: number;
  height: number;
}

export interface SpotlightLayout {
  hole: Rect;
  /** Exactly four panels, in order: top, bottom, left, right. */
  panels: Rect[];
  /** Card top edge, px from the viewport top. */
  cardTop: number;
}

/** Breathing room between the target and the hole edge. */
export const HOLE_PAD = 8;
/** Gap between the hole and the card, and the card's minimum top margin. */
export const CARD_GAP = 16;

export function computeSpotlight(
  target: Rect,
  viewportW: number,
  viewportH: number,
  cardHeight: number,
  bottomReserve: number
): SpotlightLayout {
  const holeTop = Math.max(0, target.top - HOLE_PAD);
  const holeLeft = Math.max(0, target.left - HOLE_PAD);
  const holeBottom = Math.min(viewportH, target.top + target.height + HOLE_PAD);
  const holeRight = Math.min(viewportW, target.left + target.width + HOLE_PAD);

  const hole: Rect = {
    top: holeTop,
    left: holeLeft,
    width: holeRight - holeLeft,
    height: holeBottom - holeTop,
  };

  const panels: Rect[] = [
    { top: 0, left: 0, width: viewportW, height: hole.top },
    { top: holeBottom, left: 0, width: viewportW, height: viewportH - holeBottom },
    { top: hole.top, left: 0, width: hole.left, height: hole.height },
    { top: hole.top, left: holeRight, width: viewportW - holeRight, height: hole.height },
  ];

  const belowTop = holeBottom + CARD_GAP;
  const fitsBelow = belowTop + cardHeight <= viewportH - bottomReserve;
  const cardTop = fitsBelow
    ? belowTop
    : Math.max(CARD_GAP, hole.top - CARD_GAP - cardHeight);

  return { hole, panels, cardTop };
}
```

- [ ] **Step 4: Run the spec to verify it passes**

Run: `cd frontend && npx ng test --include='**/spotlight-geometry.spec.ts' --watch=false --browsers=ChromeHeadless`
Expected: 5 specs, all PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/components/first-time-tour/spotlight-geometry.ts frontend/src/app/components/first-time-tour/spotlight-geometry.spec.ts
git commit -m "feat(tour): pure spotlight geometry (hole, four panels, card placement)"
```

---

### Task 3: Overlay component rewrite

**Files:**
- Rewrite: `frontend/src/app/components/first-time-tour/first-time-tour.component.ts`
- Rewrite: `frontend/src/app/components/first-time-tour/first-time-tour.component.html`
- Rewrite: `frontend/src/app/components/first-time-tour/first-time-tour.component.scss`
- Create: `frontend/src/app/components/first-time-tour/first-time-tour.component.spec.ts`

**Interfaces:**
- Consumes: `FirstTimeTourService` (`step$`, `suspended$`, `next`, `forceGate`, `skip`), `computeSpotlight`.
- Produces: `<app-first-time-tour>` selector (unchanged). Target selectors it queries: `[data-tour="profile-avatar"]`, `[data-tour="freedom-row"]`, `[data-tour="story-chip"]` (added in Tasks 4 and 5).

- [ ] **Step 1: Write the failing component spec**

Create `frontend/src/app/components/first-time-tour/first-time-tour.component.spec.ts`:

```ts
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { BehaviorSubject } from 'rxjs';
import { FirstTimeTourComponent, TARGET_POLL_MS, TARGET_POLL_MAX_ATTEMPTS } from './first-time-tour.component';
import { FirstTimeTourService, TourStep } from '../../services/first-time-tour.service';

describe('FirstTimeTourComponent', () => {
  let fixture: ComponentFixture<FirstTimeTourComponent>;
  let step$: BehaviorSubject<TourStep | null>;
  let suspended$: BehaviorSubject<boolean>;
  let svc: { next: jasmine.Spy; forceGate: jasmine.Spy; skip: jasmine.Spy };
  let mounted: HTMLElement[] = [];

  function mountTarget(attr: string, w = 52, h = 52): HTMLElement {
    const el = document.createElement('div');
    el.setAttribute('data-tour', attr);
    el.style.cssText = `position:fixed;top:60px;left:20px;width:${w}px;height:${h}px;`;
    document.body.appendChild(el);
    mounted.push(el);
    return el;
  }

  function q(sel: string): HTMLElement | null {
    return fixture.nativeElement.querySelector(sel);
  }

  beforeEach(() => {
    step$ = new BehaviorSubject<TourStep | null>(null);
    suspended$ = new BehaviorSubject<boolean>(false);
    svc = {
      next: jasmine.createSpy('next'),
      forceGate: jasmine.createSpy('forceGate'),
      skip: jasmine.createSpy('skip'),
    };
    TestBed.configureTestingModule({
      imports: [FirstTimeTourComponent],
      providers: [
        { provide: FirstTimeTourService, useValue: { ...svc, step$: step$.asObservable(), suspended$: suspended$.asObservable() } },
      ],
    });
    fixture = TestBed.createComponent(FirstTimeTourComponent);
    fixture.detectChanges();
  });

  afterEach(() => {
    mounted.forEach(el => el.remove());
    mounted = [];
    fixture.destroy();
  });

  it('renders nothing when the tour is not running', () => {
    expect(q('.ftt-overlay')).toBeNull();
  });

  it('explain step: full backdrop, label, Next and Skip', () => {
    step$.next('tab1');
    fixture.detectChanges();
    expect(q('.ftt-backdrop')).not.toBeNull();
    expect(q('.ftt-panel')).toBeNull();
    expect(q('.ftt-label')!.textContent!.trim()).toBe('Your portfolio and its insights live here.');
    expect(q('.ftt-next')).not.toBeNull();
    expect(q('.ftt-skip')).not.toBeNull();

    q('.ftt-next')!.click();
    expect(svc.next).toHaveBeenCalled();
  });

  it('gated step with a visible target: four panels, ring, no Next, label from step', fakeAsync(() => {
    mountTarget('profile-avatar');
    step$.next('tab3Avatar');
    fixture.detectChanges();
    tick(TARGET_POLL_MS);
    fixture.detectChanges();

    expect(q('.ftt-backdrop')).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('.ftt-panel').length).toBe(4);
    expect(q('.ftt-ring')).not.toBeNull();
    expect(q('.ftt-next')).toBeNull();
    expect(q('.ftt-label')!.textContent!.trim()).toBe('Your settings live here. Tap your pig to open your profile.');

    // Hole is the target rect padded by 8px.
    const ring = q('.ftt-ring')!;
    expect(ring.style.top).toBe('52px');
    expect(ring.style.left).toBe('12px');
    expect(ring.style.width).toBe('68px');
    expect(ring.style.height).toBe('68px');

    tick(2000); // drain the settle re-measure timer
  }));

  it('gated step with no target falls back to explain rendering after the poll deadline', fakeAsync(() => {
    step$.next('profileFreedom');
    fixture.detectChanges();
    tick(TARGET_POLL_MS * TARGET_POLL_MAX_ATTEMPTS + TARGET_POLL_MS);
    fixture.detectChanges();

    expect(q('.ftt-panel')).toBeNull();
    expect(q('.ftt-backdrop')).not.toBeNull();
    expect(q('.ftt-next')).not.toBeNull();
    expect(q('.ftt-label')!.textContent!.trim()).toBe('This is your Freedom Plan. Tap it to see how you get there.');

    q('.ftt-next')!.click();
    expect(svc.forceGate).toHaveBeenCalled();
    expect(svc.next).not.toHaveBeenCalled();
  }));

  it('suspended hides the whole overlay without losing the step', fakeAsync(() => {
    mountTarget('story-chip');
    step$.next('chatStory');
    fixture.detectChanges();
    tick(TARGET_POLL_MS);
    fixture.detectChanges();
    expect(q('.ftt-overlay')).not.toBeNull();

    suspended$.next(true);
    fixture.detectChanges();
    expect(q('.ftt-overlay')).toBeNull();

    suspended$.next(false);
    fixture.detectChanges();
    expect(q('.ftt-overlay')).not.toBeNull();
    tick(2000);
  }));

  it('Skip calls the service on any step', () => {
    step$.next('tab2');
    fixture.detectChanges();
    q('.ftt-skip')!.click();
    expect(svc.skip).toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run the spec to verify it fails**

Run: `cd frontend && npx ng test --include='**/first-time-tour.component.spec.ts' --watch=false --browsers=ChromeHeadless`
Expected: FAIL (`TARGET_POLL_MS` not exported; component still subscribes to `tourStep$`).

- [ ] **Step 3: Rewrite the component class**

Replace the full contents of `frontend/src/app/components/first-time-tour/first-time-tour.component.ts`:

```ts
import {
  Component, OnInit, OnDestroy, ElementRef, ViewChild, ChangeDetectorRef, NgZone
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { FirstTimeTourService, TourStep } from '../../services/first-time-tour.service';
import { computeSpotlight, Rect, SpotlightLayout } from './spotlight-geometry';

interface TourStepDef {
  step: TourStep;
  kind: 'explain' | 'gated';
  label: string;
  targetSelector?: string;
}

const TOUR_STEPS: TourStepDef[] = [
  { step: 'tab1', kind: 'explain', label: 'Your portfolio and its insights live here.' },
  { step: 'tab2', kind: 'explain', label: 'Your retirement options and analyses live here.' },
  { step: 'tab3Avatar', kind: 'gated', label: 'Your settings live here. Tap your pig to open your profile.', targetSelector: '[data-tour="profile-avatar"]' },
  { step: 'profileFreedom', kind: 'gated', label: 'This is your Freedom Plan. Tap it to see how you get there.', targetSelector: '[data-tour="freedom-row"]' },
  { step: 'chatStory', kind: 'gated', label: 'Ask FRED anything here. Start with this one.', targetSelector: '[data-tour="story-chip"]' },
];

/** Poll cadence while waiting for a gated target to appear. */
export const TARGET_POLL_MS = 16;
/** ~3s at 16ms: after this the step falls back to explain rendering. */
export const TARGET_POLL_MAX_ATTEMPTS = 188;
/** Re-measure once after the page/slide transition settles. */
const SETTLE_REMEASURE_MS = 600;
/** Space kept clear above the floating tab bar when placing the card. */
const BOTTOM_RESERVE_PX = 110;
/** Card height used before the card has been measured. */
const CARD_HEIGHT_FALLBACK_PX = 140;

@Component({
  selector: 'app-first-time-tour',
  templateUrl: './first-time-tour.component.html',
  styleUrls: ['./first-time-tour.component.scss'],
  standalone: true,
  imports: [CommonModule]
})
export class FirstTimeTourComponent implements OnInit, OnDestroy {

  @ViewChild('card') cardRef?: ElementRef<HTMLElement>;

  currentStep: TourStep | null = null;
  suspended = false;
  /** Set when a gated target never appeared; the step renders like an explain step. */
  fallback = false;
  layout: SpotlightLayout | null = null;

  readonly stepOrder: TourStep[] = TOUR_STEPS.map(s => s.step);

  private subs = new Subscription();
  private pollTimer: ReturnType<typeof setTimeout> | null = null;
  private settleTimer: ReturnType<typeof setTimeout> | null = null;
  private lastTargetSelector: string | null = null;
  private readonly onViewportChange = () => this.remeasure();

  constructor(
    private tourService: FirstTimeTourService,
    private cdr: ChangeDetectorRef,
    private zone: NgZone
  ) {}

  ngOnInit() {
    this.subs.add(this.tourService.step$.subscribe(step => this.onStep(step)));
    this.subs.add(this.tourService.suspended$.subscribe(s => {
      this.suspended = s;
      this.cdr.markForCheck();
    }));
    window.addEventListener('resize', this.onViewportChange);
    window.addEventListener('orientationchange', this.onViewportChange);
  }

  ngOnDestroy() {
    this.subs.unsubscribe();
    this.cancelTimers();
    window.removeEventListener('resize', this.onViewportChange);
    window.removeEventListener('orientationchange', this.onViewportChange);
  }

  // ── Template state ────────────────────────────────────────────────────────

  get isVisible(): boolean {
    return this.currentStep !== null && !this.suspended;
  }

  get stepDef(): TourStepDef | null {
    return TOUR_STEPS.find(s => s.step === this.currentStep) ?? null;
  }

  get stepLabel(): string {
    return this.stepDef?.label ?? '';
  }

  /** Gated rendering (panels + ring) is only used once the target is measured. */
  get isSpotlight(): boolean {
    return this.stepDef?.kind === 'gated' && !this.fallback && this.layout !== null;
  }

  /** Explain steps and fallback gated steps show Next. */
  get showsNext(): boolean {
    return this.stepDef?.kind === 'explain' || this.fallback;
  }

  /** While a gated target is still being polled, show a plain dark screen with just the label. */
  get isMeasuring(): boolean {
    return this.stepDef?.kind === 'gated' && !this.fallback && this.layout === null;
  }

  get cardTopPx(): number | null {
    return this.isSpotlight && this.layout ? this.layout.cardTop : null;
  }

  onNext() {
    if (this.fallback) {
      this.tourService.forceGate();
    } else {
      this.tourService.next();
    }
  }

  onSkip() {
    this.tourService.skip();
  }

  // ── Step handling ─────────────────────────────────────────────────────────

  private onStep(step: TourStep | null) {
    this.cancelTimers();
    this.currentStep = step;
    this.fallback = false;
    this.layout = null;
    this.lastTargetSelector = null;
    this.cdr.markForCheck();

    const def = this.stepDef;
    if (def?.kind === 'gated' && def.targetSelector) {
      this.lastTargetSelector = def.targetSelector;
      this.startTargetPoll(def.targetSelector);
    }
  }

  private startTargetPoll(selector: string) {
    let attempts = 0;
    const tick = () => {
      this.pollTimer = null;
      const rect = this.measure(selector);
      if (rect) {
        this.applyRect(rect);
        this.settleTimer = setTimeout(() => this.remeasure(), SETTLE_REMEASURE_MS);
        return;
      }
      attempts++;
      if (attempts >= TARGET_POLL_MAX_ATTEMPTS) {
        this.fallback = true;
        this.cdr.markForCheck();
        return;
      }
      this.pollTimer = setTimeout(tick, TARGET_POLL_MS);
    };
    tick();
  }

  private measure(selector: string): Rect | null {
    const el = document.querySelector(selector);
    if (!el) return null;
    const r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return null;
    return { top: r.top, left: r.left, width: r.width, height: r.height };
  }

  private remeasure() {
    if (!this.lastTargetSelector || this.fallback) return;
    const rect = this.measure(this.lastTargetSelector);
    if (rect) this.applyRect(rect);
  }

  private applyRect(rect: Rect) {
    const cardHeight = this.cardRef?.nativeElement.offsetHeight || CARD_HEIGHT_FALLBACK_PX;
    this.layout = computeSpotlight(rect, window.innerWidth, window.innerHeight, cardHeight, BOTTOM_RESERVE_PX);
    this.cdr.markForCheck();
    // The card is measured for the first time on this pass; re-run once so the
    // placement uses the real height instead of the fallback.
    if (!this.cardRef) {
      this.zone.runOutsideAngular(() => setTimeout(() => this.zone.run(() => {
        if (this.layout && this.cardRef) {
          this.layout = computeSpotlight(rect, window.innerWidth, window.innerHeight, this.cardRef.nativeElement.offsetHeight, BOTTOM_RESERVE_PX);
          this.cdr.markForCheck();
        }
      }), 0));
    }
  }

  private cancelTimers() {
    if (this.pollTimer) { clearTimeout(this.pollTimer); this.pollTimer = null; }
    if (this.settleTimer) { clearTimeout(this.settleTimer); this.settleTimer = null; }
  }
}
```

- [ ] **Step 4: Rewrite the template**

Replace the full contents of `frontend/src/app/components/first-time-tour/first-time-tour.component.html`:

```html
<div class="ftt-overlay" *ngIf="isVisible" [class.ftt-overlay--spotlight]="isSpotlight">

  <!-- Explain / fallback / measuring: one full backdrop that blocks taps -->
  <div
    *ngIf="!isSpotlight"
    class="ftt-backdrop"
    [class.ftt-backdrop--dark]="isMeasuring || fallback"
  ></div>

  <!-- Spotlight: four panels around the hole. The hole has NO element, so the
       real target underneath receives the real tap. -->
  <ng-container *ngIf="isSpotlight && layout">
    <div
      *ngFor="let p of layout.panels"
      class="ftt-panel"
      [style.top.px]="p.top"
      [style.left.px]="p.left"
      [style.width.px]="p.width"
      [style.height.px]="p.height"
    ></div>
    <div
      class="ftt-ring"
      [style.top.px]="layout.hole.top"
      [style.left.px]="layout.hole.left"
      [style.width.px]="layout.hole.width"
      [style.height.px]="layout.hole.height"
    ></div>
  </ng-container>

  <!-- Tooltip card -->
  <div
    #card
    class="ftt-card"
    [class.ftt-card--placed]="cardTopPx !== null"
    [style.top.px]="cardTopPx"
  >
    <div class="ftt-step-indicator">
      <span
        *ngFor="let s of stepOrder"
        class="ftt-dot"
        [class.ftt-dot-active]="s === currentStep"
      ></span>
    </div>

    <p class="ftt-label">{{ stepLabel }}</p>

    <div class="ftt-actions">
      <button class="ftt-next" (click)="onNext()" *ngIf="showsNext">
        Next
      </button>
      <button class="ftt-skip" (click)="onSkip()">Skip tour</button>
    </div>
  </div>

</div>
```

- [ ] **Step 5: Rewrite the styles**

Replace the full contents of `frontend/src/app/components/first-time-tour/first-time-tour.component.scss`:

```scss
// -------------------------------------------------------
// First-time tour overlay, FRED design system.
// Hosted at app level (app.component.html) so it survives page pushes.
// -------------------------------------------------------

.ftt-overlay {
  position: fixed;
  inset: 0;
  // Above pages and the floating tab bar, below Ionic overlays (ion-modal is 20000+).
  z-index: 9999;
  pointer-events: none;
  animation: ftt-fade-in 0.25s ease both;
}

// Explain / fallback: a single backdrop that blocks every tap.
.ftt-backdrop {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  pointer-events: auto;

  &--dark {
    background: rgba(0, 0, 0, 0.75);
  }
}

// Spotlight: four panels tile the viewport around the hole. Each blocks taps;
// the hole has no element, so the target underneath is live.
.ftt-panel {
  position: absolute;
  background: rgba(0, 0, 0, 0.75);
  pointer-events: auto;
}

// Outline + pulse around the hole. Border only, never intercepts taps.
.ftt-ring {
  position: absolute;
  box-sizing: border-box;
  border: 2px solid #2563eb;
  border-radius: 12px;
  pointer-events: none;
  animation: ftt-ring-pulse 1.4s ease-in-out infinite;
}

.ftt-card {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  width: calc(100% - 48px);
  max-width: 360px;
  background: #fff;
  border-radius: 16px;
  padding: 24px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.24);
  pointer-events: auto;

  // Spotlight placement: top is set inline from the layout, no vertical centering.
  &--placed {
    transform: translateX(-50%);
  }
}

.ftt-step-indicator {
  display: flex;
  gap: 6px;
  margin-bottom: 16px;
  justify-content: center;
}

.ftt-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #d1d5db;
  transition: background 0.2s ease, width 0.2s ease;

  &.ftt-dot-active {
    background: #2563eb;
    width: 20px;
    border-radius: 4px;
  }
}

.ftt-label {
  font-family: 'Manrope', sans-serif;
  font-size: 16px;
  font-weight: 600;
  color: #111827;
  text-align: center;
  margin: 0 0 24px;
  line-height: 1.4;
}

.ftt-actions {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.ftt-next {
  background: #2563eb;
  color: #fff;
  font-family: 'Manrope', sans-serif;
  font-size: 15px;
  font-weight: 700;
  border: none;
  border-radius: 24px;
  padding: 12px 32px;
  width: 100%;
  cursor: pointer;
  transition: background 0.2s ease;

  &:active {
    background: #1d4ed8;
  }
}

.ftt-skip {
  background: transparent;
  border: none;
  color: #9ca3af;
  font-family: 'Manrope', sans-serif;
  font-size: 13px;
  cursor: pointer;
  padding: 0;
}

@keyframes ftt-fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

@keyframes ftt-ring-pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(37, 99, 235, 0.45); }
  50% { box-shadow: 0 0 0 10px rgba(37, 99, 235, 0); }
}
```

Note: `global.scss` zeroes animation durations under `prefers-reduced-motion` with `!important`; that only affects the fade and pulse, which are cosmetic. No WAAPI needed here.

- [ ] **Step 6: Run the component spec to verify it passes**

Run: `cd frontend && npx ng test --include='**/first-time-tour.component.spec.ts' --watch=false --browsers=ChromeHeadless`
Expected: 6 specs, all PASS. (The mounted target is `position:fixed`, so its rect is viewport-relative and unaffected by Karma's body margin.)

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/components/first-time-tour/
git commit -m "feat(tour): overlay renders gated steps as four panels around a live target"
```

---

### Task 4: Host the overlay at app level and add the tab3 / my-profile targets

**Files:**
- Modify: `frontend/src/app/app.component.ts:36-38` (imports array), `frontend/src/app/app.component.html`
- Modify: `frontend/src/app/app.component.spec.ts` (two `providers` arrays)
- Modify: `frontend/src/app/tabs/tabs.page.html:1`, `frontend/src/app/tabs/tabs.page.ts:19,35`
- Modify: `frontend/src/app/tabs/tabs.page.spec.ts:5,50-53`
- Modify: `frontend/src/app/components/floating-tab-bar/floating-tab-bar.component.html:18`
- Modify: `frontend/src/app/tab3/tab3.page.html:5`
- Modify: `frontend/src/app/pages/my-profile/my-profile.page.ts` (imports, guarded inject field near line 190, `openFreedomSheet` at 666-680), `frontend/src/app/pages/my-profile/my-profile.page.html:58`
- Modify: `frontend/src/app/pages/my-profile/my-profile.page.spec.ts:243`, `frontend/src/app/pages/my-profile/my-profile.goals-render.spec.ts:59`

**Interfaces:**
- Consumes: `FirstTimeTourComponent`, `FirstTimeTourService.suspend()` / `.freedomSheetClosed()`.
- Produces: DOM targets `[data-tour="profile-avatar"]` and `[data-tour="freedom-row"]`.

- [ ] **Step 1: Add a failing my-profile hook test**

In `frontend/src/app/pages/my-profile/my-profile.page.spec.ts`, add the import at the top with the other service imports:

```ts
import { FirstTimeTourService } from '../../services/first-time-tour.service';
```

In the `setupDom()` helper's `TestBed.configureTestingModule` providers array (starts near line 226), add after the `ModalController` line:

```ts
      { provide: FirstTimeTourService, useValue: { suspend: jasmine.createSpy('suspend'), freedomSheetClosed: jasmine.createSpy('freedomSheetClosed') } },
```

Replace the `{ provide: ModalController, useValue: {} }` line in that same array with a stub whose `create` resolves a fake modal:

```ts
      { provide: ModalController, useValue: {
          create: jasmine.createSpy('create').and.returnValue(Promise.resolve({
            present: () => Promise.resolve(),
            onDidDismiss: () => Promise.resolve({ role: 'cancel' }),
          }))
      } },
```

Then add this test inside `describe('MyProfilePage — live plan rendering (DOM)')` (which calls `setupDom(SAVED_PROGRESS)` in its `beforeEach`), after its last `it`:

```ts
  it('openFreedomSheet suspends the tour before presenting and reports the close after dismiss', async () => {
    const page = harness.fixture.componentInstance;
    const tour = TestBed.inject(FirstTimeTourService) as any;

    await page.openFreedomSheet();

    expect(tour.suspend).toHaveBeenCalledTimes(1);
    expect(tour.freedomSheetClosed).toHaveBeenCalledTimes(1);
    expect(tour.suspend).toHaveBeenCalledBefore(tour.freedomSheetClosed);
  });
```

(`openFreedomSheet` also calls `ratesService.ensureUser()`; `RateAssumptionsService` is the real one there and only reads localStorage, so no stub is needed.)

Also add the same `FirstTimeTourService` provider line (and import) to `frontend/src/app/pages/my-profile/my-profile.goals-render.spec.ts` after its `ModalController` provider (line 59); that spec constructs the page via TestBed too.

- [ ] **Step 2: Run the my-profile spec to verify it fails**

Run: `cd frontend && npx ng test --include='**/my-profile.page.spec.ts' --watch=false --browsers=ChromeHeadless`
Expected: the new test FAILS (`suspend` not called).

- [ ] **Step 3: Move the overlay host**

`frontend/src/app/app.component.ts`: add the import after the `PortfolioStoreService` import:

```ts
import { FirstTimeTourComponent } from './components/first-time-tour/first-time-tour.component';
```

and change the component's `imports` to:

```ts
  imports: [IonApp, IonRouterOutlet, CommonModule, FirstTimeTourComponent],
```

`frontend/src/app/app.component.html`, full contents:

```html
<ion-app>
  <ion-router-outlet></ion-router-outlet>
  <!-- Tour overlay lives here, not in TabsPage: Ionic display:none's the
       tabs page when /my-profile is pushed, and the tour must survive that. -->
  <app-first-time-tour></app-first-time-tour>
</ion-app>
```

`frontend/src/app/tabs/tabs.page.html`: delete line 1 (`<app-first-time-tour></app-first-time-tour>`) and the blank line after it.

`frontend/src/app/tabs/tabs.page.ts`: delete the line `import { FirstTimeTourComponent } from '../components/first-time-tour/first-time-tour.component';` and change the imports array line `FirstTimeTourComponent, FloatingTabBarComponent,` to `FloatingTabBarComponent,`.

`frontend/src/app/tabs/tabs.page.spec.ts`: change line 5 to `import { IonRouterOutlet } from '@ionic/angular/standalone';` and delete these three lines from the providers array:

```ts
        // FirstTimeTourComponent (real, unconditional in TabsPage's template) injects
        // NavController — trivial no-op stub, since construction only stores the ref.
        { provide: NavController, useValue: {} },
```

`frontend/src/app/app.component.spec.ts`: add the import

```ts
import { FirstTimeTourService } from './services/first-time-tour.service';
```

and a stub constant next to the other stubs:

```ts
const firstTimeTourStub = {
  step$: new BehaviorSubject(null).asObservable(),
  suspended$: new BehaviorSubject(false).asObservable(),
};
```

then add `{ provide: FirstTimeTourService, useValue: firstTimeTourStub },` to BOTH providers arrays (the `setup()` helper and the baseline smoke test).

`frontend/src/app/components/floating-tab-bar/floating-tab-bar.component.html`: delete line 18 (`[attr.data-tour]="t.tab === 'tab3' ? 'tab3' : null"`).

- [ ] **Step 4: Add the targets and hooks**

`frontend/src/app/tab3/tab3.page.html` line 5 becomes:

```html
        <div class="avatar-badge-wrap" data-tour="profile-avatar" (click)="goToMyProfile()">
```

`frontend/src/app/pages/my-profile/my-profile.page.html` line 58 becomes:

```html
      <div class="goal-row" data-tour="freedom-row" (click)="openFreedomSheet()">
```

`frontend/src/app/pages/my-profile/my-profile.page.ts`: add the import after the `FreedomDetailSheetComponent` import:

```ts
import { FirstTimeTourService } from '../../services/first-time-tour.service';
```

Directly below the existing `ratesService` guarded-inject block (ends near line 192), add:

```ts
  // Guarded for the same reason as ratesService: the math specs construct this
  // page with `new MyProfilePage(...)`, outside any injection context.
  private tourService: FirstTimeTourService | null = (() => {
    try { return inject(FirstTimeTourService); } catch { return null; }
  })();
```

In `openFreedomSheet()`, change

```ts
    await modal.present();
    const { role, data } = await modal.onDidDismiss<FreedomSheetResult>();
```

to

```ts
    // Tour: hide the overlay while the sheet is up, then hand control back
    // when it closes (either call is a no-op unless the tour is on this step).
    this.tourService?.suspend();
    await modal.present();
    const { role, data } = await modal.onDidDismiss<FreedomSheetResult>();
    this.tourService?.freedomSheetClosed();
```

- [ ] **Step 5: Run the affected specs**

Run: `cd frontend && npx ng test --include='**/{my-profile.page,my-profile.goals-render,tabs.page,app.component}.spec.ts' --watch=false --browsers=ChromeHeadless`
Expected: all PASS, including the new `openFreedomSheet` test. If the brace-glob is not expanded by the shell, run the four `--include` invocations separately.

- [ ] **Step 6: Hunk-verify and commit**

Run `git diff HEAD -- frontend/src/app/app.component.ts frontend/src/app/tabs/tabs.page.ts frontend/src/app/tabs/tabs.page.html frontend/src/app/tab3/tab3.page.html frontend/src/app/pages/my-profile/my-profile.page.ts frontend/src/app/pages/my-profile/my-profile.page.html frontend/src/app/components/floating-tab-bar/floating-tab-bar.component.html` and confirm every hunk is from this task. If any file carries unrelated WIP hunks, use `git add -p` for that file and stage only yours.

```bash
git add frontend/src/app/app.component.ts frontend/src/app/app.component.html frontend/src/app/app.component.spec.ts \
        frontend/src/app/tabs/tabs.page.ts frontend/src/app/tabs/tabs.page.html frontend/src/app/tabs/tabs.page.spec.ts \
        frontend/src/app/components/floating-tab-bar/floating-tab-bar.component.html \
        frontend/src/app/tab3/tab3.page.html \
        frontend/src/app/pages/my-profile/my-profile.page.ts frontend/src/app/pages/my-profile/my-profile.page.html \
        frontend/src/app/pages/my-profile/my-profile.page.spec.ts frontend/src/app/pages/my-profile/my-profile.goals-render.spec.ts
git commit -m "feat(tour): host overlay at app level; pig avatar + Freedom row targets and sheet hooks"
```

---

### Task 5: Chat integration (story chip guarantee + tap hook)

**Files:**
- Modify: `frontend/src/app/pages/ai-chat/ai-chat.page.ts` (imports line 20, constructor 107-122, getter 124-126, `ngOnDestroy` 193-197, `loadDailySuggestions` 254-272, `selectSuggestion` 275-287)
- Modify: `frontend/src/app/pages/ai-chat/ai-chat.page.html:92-101`
- Modify: `frontend/src/app/pages/ai-chat/ai-chat.page.scss:784-797`
- Modify: `frontend/src/app/pages/ai-chat/ai-chat.page.spec.ts:1-33`

**Interfaces:**
- Consumes: `FirstTimeTourService.step$`, `.currentStep`, `.storyChipTapped()`, `TourStep`.
- Produces: DOM target `[data-tour="story-chip"]`.

- [ ] **Step 1: Update the spec mock and add failing tests**

In `frontend/src/app/pages/ai-chat/ai-chat.page.spec.ts`, change the imports at the top:

```ts
import { of, BehaviorSubject, throwError } from 'rxjs';
import { FirstTimeTourService, TourStep } from '../../services/first-time-tour.service';
```

Add one line to `makeChatServiceMock()` after `saveSession`:

```ts
    markSuggestionAsUsed: jasmine.createSpy('markSuggestionAsUsed'),
```

Add a tour mock factory after `makeChatServiceMock`:

```ts
function makeTourMock() {
  const step$ = new BehaviorSubject<TourStep | null>(null);
  const mock = {
    step$: step$.asObservable(),
    currentStep: null as TourStep | null,
    storyChipTapped: jasmine.createSpy('storyChipTapped'),
    setStep(step: TourStep | null) { mock.currentStep = step; step$.next(step); },
  };
  return mock;
}
```

Inside the existing `describe('AiChatPage activation gating')`, declare `let tour: ReturnType<typeof makeTourMock>;` next to `chat`, build it in `beforeEach` (`tour = makeTourMock();`), and replace the stale override line with:

```ts
      .overrideProvider(FirstTimeTourService, { useValue: tour });
```

Then add these tests after the existing `it`:

```ts
  it('entering the chatStory step guarantees the story chip at the front of the suggestions', () => {
    chat.getDailySuggestions.and.returnValue(of(['How do ETFs work?', 'What is a Roth IRA?']));
    const fixture = TestBed.createComponent(AiChatPage);
    const c = fixture.componentInstance;
    c.onChatActiveChange(true);
    expect(c.dailySuggestions).not.toContain(c.FRED_STORY_TRIGGER);

    tour.setStep('chatStory');
    expect(c.dailySuggestions[0]).toBe(c.FRED_STORY_TRIGGER);
    expect(c.dailySuggestions.length).toBe(3);
  });

  it('a late suggestions response cannot remove the story chip while on chatStory', () => {
    const fixture = TestBed.createComponent(AiChatPage);
    const c = fixture.componentInstance;
    tour.setStep('chatStory');
    chat.getDailySuggestions.and.returnValue(of(['Only one question']));
    c.loadDailySuggestions();
    expect(c.dailySuggestions[0]).toBe(c.FRED_STORY_TRIGGER);
  });

  it('a failed suggestions request still leaves the story chip while on chatStory', () => {
    const fixture = TestBed.createComponent(AiChatPage);
    const c = fixture.componentInstance;
    tour.setStep('chatStory');
    chat.getDailySuggestions.and.returnValue(throwError(() => new Error('boom')));
    c.loadDailySuggestions();
    expect(c.dailySuggestions).toEqual([c.FRED_STORY_TRIGGER]);
  });

  it('entering chatStory with an existing conversation resets to an empty chat first', () => {
    const fixture = TestBed.createComponent(AiChatPage);
    const c = fixture.componentInstance;
    c.messages = [{ role: 'user', content: 'hi', timestamp: new Date() } as any];
    tour.setStep('chatStory');
    expect(c.messages.length).toBe(0);
    expect(chat.createSession).toHaveBeenCalled();
    expect(c.dailySuggestions[0]).toBe(c.FRED_STORY_TRIGGER);
  });

  it('tapping the story chip on chatStory notifies the tour', () => {
    const fixture = TestBed.createComponent(AiChatPage);
    const c = fixture.componentInstance;
    spyOn(c, 'sendMessage');
    tour.setStep('chatStory');
    c.selectSuggestion(c.FRED_STORY_TRIGGER);
    expect(tour.storyChipTapped).toHaveBeenCalled();
    expect(c.sendMessage).toHaveBeenCalled();
  });
```

- [ ] **Step 2: Run the spec to verify it fails**

Run: `cd frontend && npx ng test --include='**/ai-chat.page.spec.ts' --watch=false --browsers=ChromeHeadless`
Expected: compile FAIL (`TourStep` import fine, but `isTourOnStep4` compares to `4` against the new union type) or the four new tests FAIL.

- [ ] **Step 3: Implement the chat changes**

`frontend/src/app/pages/ai-chat/ai-chat.page.ts`:

Change the import on line 20 to:

```ts
import { FirstTimeTourService, TourStep } from '../../services/first-time-tour.service';
```

Add a field next to `private streamSubscription`:

```ts
  private tourSub: Subscription | null = null;
```

In the constructor, after the `effect(...)` block, add:

```ts
    this.tourSub = this.tourService.step$.subscribe(step => this.onTourStep(step));
```

Replace the `isTourOnStep4` getter with:

```ts
  get isTourOnStoryStep(): boolean {
    return this.tourService.currentStep === 'chatStory';
  }
```

In `ngOnDestroy`, add `this.tourSub?.unsubscribe();`.

Add these two methods after `loadDailySuggestions`:

```ts
  /**
   * Final tour step: the story chip must be on screen and tappable no matter
   * what the backend returned. Reset to an empty chat if a conversation is
   * showing (only reachable when replaying the tour), then pin the chip.
   */
  private onTourStep(step: TourStep | null) {
    if (step !== 'chatStory') return;
    if (!this.isEmptyState) {
      this.currentSession = this.chatService.createSession();
      this.messages = [];
    }
    this.ensureStoryChip();
    this.cdr.detectChanges();
  }

  private ensureStoryChip() {
    if (!this.dailySuggestions.includes(this.FRED_STORY_TRIGGER)) {
      this.dailySuggestions = [this.FRED_STORY_TRIGGER, ...this.dailySuggestions];
    }
  }
```

In `loadDailySuggestions`, in the `next` handler change

```ts
        this.dailySuggestions = filteredQuestions;
        this.cdr.detectChanges();
```

to

```ts
        this.dailySuggestions = filteredQuestions;
        if (this.isTourOnStoryStep) this.ensureStoryChip();
        this.cdr.detectChanges();
```

and in the `error` handler change

```ts
        this.dailySuggestions = [];
        this.cdr.detectChanges();
```

to

```ts
        this.dailySuggestions = [];
        if (this.isTourOnStoryStep) this.ensureStoryChip();
        this.cdr.detectChanges();
```

In `selectSuggestion`, replace

```ts
    // If tour is on step 4 and this is the story chip, complete the tour
    if (this.isTourOnStep4 && question === this.FRED_STORY_TRIGGER) {
      this.tourService.completeTour();
    }
```

with

```ts
    // Final tour gate: the story chip tap completes the tour; the send proceeds as normal.
    if (this.isTourOnStoryStep && question === this.FRED_STORY_TRIGGER) {
      this.tourService.storyChipTapped();
    }
```

`frontend/src/app/pages/ai-chat/ai-chat.page.html` lines 93-97: replace

```html
          <button
            class="suggestion-card"
            *ngFor="let question of dailySuggestions; let i = index"
            (click)="selectSuggestion(question)"
            [class.tour-story-chip]="isTourOnStep4 && question === FRED_STORY_TRIGGER">
```

with

```html
          <button
            class="suggestion-card"
            *ngFor="let question of dailySuggestions; let i = index"
            (click)="selectSuggestion(question)"
            [attr.data-tour]="question === FRED_STORY_TRIGGER ? 'story-chip' : null">
```

`frontend/src/app/pages/ai-chat/ai-chat.page.scss`: delete the block from the comment `/* Tour step 4 — highlighted story chip sits above the global tour backdrop */` through the closing brace of `@keyframes tour-chip-pulse` (lines 784-797). The overlay's hole and ring replace it.

- [ ] **Step 4: Run the spec to verify it passes**

Run: `cd frontend && npx ng test --include='**/ai-chat.page.spec.ts' --watch=false --browsers=ChromeHeadless`
Expected: 6 specs, all PASS.

- [ ] **Step 5: Hunk-verify and commit**

Run `git diff HEAD -- frontend/src/app/pages/ai-chat/` and confirm every hunk is from this task (ai-chat is in Andy's modified set). Use `git add -p` if not.

```bash
git add frontend/src/app/pages/ai-chat/ai-chat.page.ts frontend/src/app/pages/ai-chat/ai-chat.page.html frontend/src/app/pages/ai-chat/ai-chat.page.scss frontend/src/app/pages/ai-chat/ai-chat.page.spec.ts
git commit -m "feat(tour): chat guarantees the story chip on the final step and reports the tap"
```

---

### Task 6: Gates (AOT build, full tour spec run, verifier, device checklist)

**Files:** none new.

- [ ] **Step 1: Confirm nothing still references the old API**

Run: `cd frontend && grep -rn "tourStep\$\|nextStep()\|skipTour\|completeTour\|isTourOnStep4\|tour-story-chip\|data-tour=\"tab3\"" src/app`
Expected: no output.

- [ ] **Step 2: AOT build**

Run: `cd frontend && npx ng build --configuration dev 2>&1 | tail -20`
Expected: build succeeds with no errors (warnings about bundle budgets are pre-existing).

- [ ] **Step 3: Run every spec touched by this plan together**

Run each of these and confirm PASS:

```bash
cd frontend
npx ng test --include='**/first-time-tour.service.spec.ts' --watch=false --browsers=ChromeHeadless
npx ng test --include='**/spotlight-geometry.spec.ts' --watch=false --browsers=ChromeHeadless
npx ng test --include='**/first-time-tour.component.spec.ts' --watch=false --browsers=ChromeHeadless
npx ng test --include='**/tabs.page.spec.ts' --watch=false --browsers=ChromeHeadless
npx ng test --include='**/app.component.spec.ts' --watch=false --browsers=ChromeHeadless
npx ng test --include='**/my-profile.page.spec.ts' --watch=false --browsers=ChromeHeadless
npx ng test --include='**/my-profile.goals-render.spec.ts' --watch=false --browsers=ChromeHeadless
npx ng test --include='**/ai-chat.page.spec.ts' --watch=false --browsers=ChromeHeadless
```

- [ ] **Step 4: Confirm the force constant is off**

Run: `grep -n "FORCE_FIRST_TIME_TOUR = " frontend/src/app/services/first-time-tour.service.ts`
Expected: `const FORCE_FIRST_TIME_TOUR = false;`

- [ ] **Step 5: Verifier pass**

Dispatch `verifier-agent` with this Acceptance Check Manifest:

1. Service spec, geometry spec, component spec, tabs spec, app.component spec, both my-profile specs, ai-chat spec: all green via the commands in Step 3.
2. `ng build --configuration dev` clean.
3. `grep` from Step 1 returns nothing.
4. `<app-first-time-tour>` appears exactly once in `src/app`, in `app.component.html`.
5. Tour copy in `first-time-tour.component.ts` matches the five strings in Global Constraints verbatim; no em dash character (U+2014) in any file touched by this plan.
6. `FORCE_FIRST_TIME_TOUR = false`.
7. `openFreedomSheet` calls `suspend()` before `present()` and `freedomSheetClosed()` after `onDidDismiss`.

- [ ] **Step 6: Device checklist for Andy (manual, on the iPhone build)**

Flip `FORCE_FIRST_TIME_TOUR` to `true` locally (do not commit), run the app, and check:

1. Tab1 and tab2 steps: page visible under a light dim; Next slides the pager.
2. Tab3 step: everything dark except the pig; tapping anything else does nothing; tapping the pig opens My Profile with the overlay still showing.
3. My Profile step: everything dark except the Freedom Date row; tapping it opens the sheet with no overlay; closing the sheet (Save, drag, or X) lands on chat with the final step showing.
4. Chat step: only the story chip is tappable (composer and other chips inert); tapping it sends the message and the overlay disappears for good.
5. Skip on the My Profile step leaves you on My Profile with no overlay.
6. Rotate or background/resume mid-spotlight: hole re-aligns to the target.

Flip the constant back to `false` and confirm `git diff` on the service file is empty before any commit.
