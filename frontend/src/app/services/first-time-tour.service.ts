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
