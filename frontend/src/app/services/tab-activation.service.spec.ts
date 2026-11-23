import { TestBed } from '@angular/core/testing';
import { TabActivationService } from './tab-activation.service';
import { Router } from '@angular/router';
import { AnalyticsService } from './analytics/analytics.service';

/**
 * Tab changes never produce a router navigation (the pager rewrites the URL with replaceState), so
 * this service is the only place that sees every one of them. If this wiring goes, screen views go
 * silently.
 */
describe('TabActivationService screen views', () => {
  let service: TabActivationService;
  let screenCalls: string[];
  let routerStub: { url: string };

  beforeEach(() => {
    screenCalls = [];
    routerStub = { url: '/tabs/tab1' };
    TestBed.configureTestingModule({
      providers: [
        TabActivationService,
        { provide: Router, useValue: routerStub },
        {
          provide: AnalyticsService,
          useValue: { screen: (name: string) => screenCalls.push(name) },
        },
      ],
    });
    service = TestBed.inject(TabActivationService);
  });

  it('reports a screen view for each activated tab', () => {
    service.setActive('tab1');
    service.setActive('chat');
    service.setActive('tab3');

    expect(screenCalls).toEqual(['/tabs/tab1', '/tabs/chat', '/tabs/tab3']);
  });

  it('remembers the tab so a cold start can reopen on it', () => {
    localStorage.setItem('userId', 'u-1');
    try {
      service.setActive('tab3');

      const record = JSON.parse(localStorage.getItem('fred.lastScreen.v1.u-1')!);
      expect(record.url).toBe('/tabs/tab3');
    } finally {
      localStorage.removeItem('userId');
      localStorage.removeItem('fred.lastScreen.v1.u-1');
    }
  });

  // The pager re-pulses the active tab when a pushed page starts to leave, which an edge swipe
  // does at the start of the gesture. The swipe can still be cancelled, so the pushed page must
  // stay the remembered screen.
  it('does not remember the tab while a pushed page is the routed screen', () => {
    localStorage.setItem('userId', 'u-1');
    routerStub.url = '/my-profile';
    try {
      service.setActive('tab3');

      expect(localStorage.getItem('fred.lastScreen.v1.u-1')).toBeNull();
      expect(screenCalls).toEqual(['/tabs/tab3']);
    } finally {
      localStorage.removeItem('userId');
      localStorage.removeItem('fred.lastScreen.v1.u-1');
    }
  });

  it('still sets the active tab signal', () => {
    service.setActive('tab2');

    expect(service.activeTab()).toBe('tab2');
  });

  it('sends a name with no ids or query params in it', () => {
    service.setActive('tab1');

    expect(screenCalls[0]).toBe('/tabs/tab1');
    expect(screenCalls[0]).not.toMatch(/[?=&]|\d{3,}/);
  });

  it('passes re-pulses through to the service, which is where they are de-duplicated', () => {
    // setActive deliberately re-notifies on an unchanged tab (returning to a cached tab re-runs
    // that tab's on-enter work). Dropping the duplicate is AnalyticsService's job, not this one's,
    // so the signal behaviour stays intact.
    service.setActive('tab1');
    service.setActive('tab1');

    expect(screenCalls).toEqual(['/tabs/tab1', '/tabs/tab1']);
  });
});
