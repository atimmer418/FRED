import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { environment } from '../../../environments/environment';
import { AnalyticsConsentService } from './analytics-consent.service';
import { AnalyticsService } from './analytics.service';
import { ANALYTICS_ADAPTER } from './analytics-adapter.token';
import { AnalyticsAdapter } from './analytics-adapter';

/** Consent is the server's to decide; this service only writes it and applies what came back. */
describe('AnalyticsConsentService', () => {
  let service: AnalyticsConsentService;
  let analytics: AnalyticsService;
  let http: HttpTestingController;

  const noopAdapter: AnalyticsAdapter = {
    init: () => undefined,
    isReady: () => true,
    capture: () => undefined,
    identify: () => undefined,
    screen: () => undefined,
    reset: () => undefined,
    setOptOut: () => undefined,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [AnalyticsConsentService, AnalyticsService, { provide: ANALYTICS_ADAPTER, useValue: noopAdapter }],
    });
    service = TestBed.inject(AnalyticsConsentService);
    analytics = TestBed.inject(AnalyticsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('writes the opt-out to the server', () => {
    service.setOptOut(false).subscribe();

    const request = http.expectOne(`${environment.backendApiUrl}/user/analytics-consent`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ optOut: false });
    request.flush({ analyticsOptOut: false });
  });

  it('applies what the server stored, not what was requested', () => {
    let reported: boolean | undefined;
    service.setOptOut(false).subscribe((value) => (reported = value));

    // Server disagrees: it is the authority, so the client follows it.
    http.expectOne(`${environment.backendApiUrl}/user/analytics-consent`).flush({ analyticsOptOut: true });

    expect(reported).toBeTrue();
    expect(analytics.isOptedOut()).toBeTrue();
  });

  it('leaves consent untouched when the write fails', () => {
    analytics.applyServerConsent(false);
    expect(analytics.isOptedOut()).toBeFalse();

    service.setOptOut(true).subscribe({ error: () => undefined });
    http.expectOne(`${environment.backendApiUrl}/user/analytics-consent`)
      .flush({ message: 'nope' }, { status: 500, statusText: 'Server Error' });

    expect(analytics.isOptedOut()).toBeFalse();
  });

  it('reports the current consent state for the settings toggle', () => {
    analytics.applyServerConsent(false);
    expect(service.isOptedOut()).toBeFalse();

    analytics.applyServerConsent(true);
    expect(service.isOptedOut()).toBeTrue();
  });
});
