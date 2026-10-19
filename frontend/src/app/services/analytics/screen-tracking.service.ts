import { Injectable } from '@angular/core';
import { ActivatedRoute, NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';
import { AnalyticsService } from './analytics.service';

/**
 * Reports a screen view for every routed page.
 *
 * Hooks the router once rather than adding a call to each page: a per-page call is a line someone
 * forgets on the next page they add, and the silence looks exactly like "nobody visited".
 *
 * The name is built from the route CONFIG, not the URL, so a parameterised route reports
 * '/beneficiaries/edit/:id' and never the id itself.
 *
 * Tabs are deliberately excluded. Switching tabs does not navigate (the pager rewrites the URL
 * with replaceState), so the router would both miss real tab changes and report '/tabs/:tab' for
 * the one navigation it does see, which would double count against the pager's own, more precise
 * '/tabs/tab3'. TabActivationService owns those.
 */
@Injectable({ providedIn: 'root' })
export class ScreenTrackingService {

  private started = false;

  constructor(
    private router: Router,
    private route: ActivatedRoute,
    private analytics: AnalyticsService,
  ) {}

  /** Called once at startup. Safe to call again; it will not subscribe twice. */
  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;

    this.router.events
      .pipe(filter((event): event is NavigationEnd => event instanceof NavigationEnd))
      .subscribe(() => {
        const screen = this.currentScreenName();
        if (screen && !screen.startsWith('/tabs')) {
          this.analytics.screen(screen);
        }
      });
  }

  /**
   * Builds a screen name from the activated route's configured paths, e.g.
   * '/beneficiaries/edit/:id'. Returns '/' for the root.
   */
  private currentScreenName(): string {
    const segments: string[] = [];
    let route = this.route.root;

    while (route.firstChild) {
      route = route.firstChild;
      const path = route.routeConfig?.path;
      if (path) {
        segments.push(path);
      }
    }

    return `/${segments.join('/')}`;
  }
}
