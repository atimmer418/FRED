import { Injectable, inject, signal } from '@angular/core';
import { Subject } from 'rxjs';
import { AnalyticsService } from './analytics/analytics.service';

/**
 * Tells each top-level tab when it becomes the active slide in the swipeable
 * pager. Replaces the Ionic per-tab lifecycle hooks (ionViewWillEnter etc.),
 * which no longer fire once all four tabs are mounted together in the pager.
 */
@Injectable({ providedIn: 'root' })
export class TabActivationService {
  // equal: () => false makes every setActive() re-notify effects even when the
  // tab is unchanged, so a re-pulse (returning to a cached tab from a pushed
  // sub-page) re-runs that tab's on-enter work despite Angular signal dedup.
  private readonly _activeTab = signal<string>('tab1', { equal: () => false });
  readonly activeTab = this._activeTab.asReadonly();

  private readonly analytics = inject(AnalyticsService);

  setActive(tab: string): void {
    this._activeTab.set(tab);

    // The only hook that catches every visible tab change. Swipes, floating-bar taps and
    // programmatic moves all land here, and none of them produce a router navigation, so
    // NavigationEnd would miss them and router.url would report a stale tab.
    //
    // Called imperatively rather than from an effect on the signal: signal writes coalesce per
    // change-detection flush, so an effect neither counts calls nor sees every change. The
    // service drops an unchanged screen name, which is what filters the deliberate re-pulses
    // (returning to a cached tab) from real screen changes. Name only: no ids, no params.
    this.analytics.screen(`/tabs/${tab}`);
  }

  // Programmatic "move the pager to <tab>" requests (first-time tour). TabsPage
  // subscribes and calls slideToTab, which syncs the URL via replaceState. This
  // deliberately avoids a router navigation to /tabs/<tab>: Ionic's route reuse
  // is keyed on the stamped view URL, so a router move to a different tab URL
  // creates a second TabsPage instead of moving the existing pager.
  private readonly tabRequests = new Subject<string>();
  readonly tabRequest$ = this.tabRequests.asObservable();

  requestTab(tab: string): void {
    this.tabRequests.next(tab);
  }
}
