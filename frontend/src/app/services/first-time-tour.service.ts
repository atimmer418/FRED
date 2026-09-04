import { Injectable } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
import { BehaviorSubject } from 'rxjs';
import { filter } from 'rxjs/operators';
import { TabActivationService } from './tab-activation.service';

/**
 * Tour steps, in order. 'tab1' and 'tab2' are explain steps (Next button).
 * The other three are gated: the overlay darkens everything except one
 * target, and the tour advances only when the user performs the real action.
 */
export type TourStep = 'tab1' | 'tab2' | 'tab3Avatar' | 'profileFreedom' | 'chatStory';

export const TOUR_STORAGE_KEY = 'hasSeenFirstTimeTour';

/** Flip to true to run the tour on every tab1 load (dev only, never commit true). */
const FORCE_FIRST_TIME_TOUR = false;

/** Pause on tab3 after popping back from my-profile, before the pager swipes to chat. */
export const TAB3_BEAT_MS = 900;
/** Poll cadence while waiting for the pager's chat slide to settle. */
export const TAB_SETTLE_POLL_MS = 50;
/** Cap on the settle wait (about 2.5s); the story step then shows regardless. */
export const TAB_SETTLE_MAX_POLLS = 50;

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

  private transitSubject = new BehaviorSubject<boolean>(false);
  /**
   * True while the tour ferries the user from the Freedom sheet back to chat
   * (pop to tab3, beat, swipe). The overlay shows a plain dim veil: no card,
   * no spotlight, but the screen never flashes back to normal mid-tour.
   */
  readonly transit$ = this.transitSubject.asObservable();

  /** Pending timer for the tab3 beat / settle poll while returning to chat. */
  private transitTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private router: Router,
    private navCtrl: NavController,
    private tabActivation: TabActivationService
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

  get isInTransit(): boolean {
    return this.transitSubject.value;
  }

  hasSeenTour(): boolean {
    if (FORCE_FIRST_TIME_TOUR) return false;
    return localStorage.getItem(TOUR_STORAGE_KEY) === 'true';
  }

  startTour(): void {
    // A running tour is never restarted: every remount of Tab1Page (AppLock's
    // post-reauth /tabs/tab1 nav, a second TabsPage) calls this, and a reset
    // would throw a mid-tour user back to step 1.
    if (this.hasSeenTour() || this.currentStep !== null) return;
    this.suspendedSubject.next(false);
    this.stepSubject.next('tab1');
  }

  /**
   * Explain steps only. Ignored on gated steps. The tab moves go through
   * TabActivationService, which slides the existing pager: a router
   * navigation to /tabs/<tab> would build a second TabsPage instead (Ionic
   * reuses views by their stamped URL), and its fresh Tab1Page would restart
   * the tour.
   */
  next(): void {
    switch (this.currentStep) {
      case 'tab1':
        this.tabActivation.requestTab('tab2');
        this.stepSubject.next('tab2');
        break;
      case 'tab2':
        this.tabActivation.requestTab('tab3');
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

  /**
   * Called by my-profile after the Freedom sheet dismisses (any role).
   * The return to chat is staged so the user can follow it: pop back so tab3
   * visibly lands, rest a beat, swipe the pager to chat, and only show the
   * story-chip step once that slide has settled. A dim transit veil covers
   * the whole ride. pop() reuses the cached TabsPage by its own URL
   * (a router navigation to /tabs/chat would create a second TabsPage, see
   * TabActivationService).
   */
  freedomSheetClosed(): void {
    if (this.currentStep !== 'profileFreedom') {
      // Off-step a suspend must still lift, or the overlay stays hidden for good.
      this.suspendedSubject.next(false);
      this.transitSubject.next(false);
      return;
    }
    // Lift the sheet's suspend and raise the transit veil instead: the screen
    // stays grayed out for the whole ride back to chat.
    this.suspendedSubject.next(false);
    this.transitSubject.next(true);
    this.navCtrl.pop()
      .then(popped => {
        if (!popped) {
          // Nothing to pop (deep link straight into my-profile): fall back to a
          // route and show the step right away.
          return this.router.navigateByUrl('/tabs/chat').then(() => this.showChatStory());
        }
        this.transitTimer = setTimeout(() => {
          this.transitTimer = null;
          this.tabActivation.requestTab('chat');
          this.awaitTabSettle('chat');
        }, TAB3_BEAT_MS);
        return undefined;
      })
      .catch(err => {
        console.warn('[tour] return to chat failed', err);
        this.showChatStory();
      });
  }

  /** Poll the pager's settle signal, then reveal the story step (capped, never hangs). */
  private awaitTabSettle(tab: string): void {
    let polls = 0;
    const tick = () => {
      this.transitTimer = null;
      if (this.tabActivation.activeTab() === tab || polls >= TAB_SETTLE_MAX_POLLS) {
        this.showChatStory();
        return;
      }
      polls++;
      this.transitTimer = setTimeout(tick, TAB_SETTLE_POLL_MS);
    };
    tick();
  }

  private showChatStory(): void {
    // A skip/complete during the transit wins; do not resurrect the tour.
    if (this.currentStep !== 'profileFreedom') return;
    this.suspendedSubject.next(false);
    this.transitSubject.next(false);
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
    if (this.transitTimer) {
      clearTimeout(this.transitTimer);
      this.transitTimer = null;
    }
    localStorage.setItem(TOUR_STORAGE_KEY, 'true');
    this.suspendedSubject.next(false);
    this.transitSubject.next(false);
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
