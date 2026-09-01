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

  /** Called by my-profile after the Freedom sheet dismisses (any role). */
  freedomSheetClosed(): void {
    // Always clear a suspend, even off-step, so the overlay can never stay hidden.
    this.suspendedSubject.next(false);
    if (this.currentStep !== 'profileFreedom') return;
    this.stepSubject.next('chatStory');
    // pop() returns to the cached TabsPage by its own URL (a router navigation
    // to /tabs/chat would create a second TabsPage, see TabActivationService),
    // then the pager is moved to chat once that navigation has settled.
    this.navCtrl.pop()
      .then(popped => {
        if (popped) {
          this.tabActivation.requestTab('chat');
        } else {
          // Nothing to pop (deep link straight into my-profile): fall back to a route.
          return this.router.navigateByUrl('/tabs/chat').then(() => undefined);
        }
        return undefined;
      })
      .catch(err => console.warn('[tour] return to chat failed', err));
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
