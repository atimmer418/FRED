import { TestBed } from '@angular/core/testing';
import { TabActivationService } from './tab-activation.service';
import { AnalyticsService } from './analytics/analytics.service';

/**
 * Tab changes never produce a router navigation (the pager rewrites the URL with replaceState), so
 * this service is the only place that sees every one of them. If this wiring goes, screen views go
 * silently.
 */
describe('TabActivationService screen views', () => {
  let service: TabActivationService;
  let screenCalls: string[];

  beforeEach(() => {
    screenCalls = [];
    TestBed.configureTestingModule({
      providers: [
        TabActivationService,
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
