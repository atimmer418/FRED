import { fakeAsync, tick } from '@angular/core/testing';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { of } from 'rxjs';
import { LumpSumInvestmentPage } from './lump-sum-investment.page';
import { InvestmentService } from '../services/investment.service';
import { PortfolioService } from '../services/portfolio.service';
import { PasskeyService } from '../services/passkey.service';
import { PinService } from '../services/pin.service';
import { ToastService } from '../services/toast.service';
import { AnalyticsService } from '../services/analytics/analytics.service';
import { environment } from '../../environments/environment';

/**
 * Scope: ONLY the per-tap clientUuid on the lump-sum POST (decision 18). This page posts to
 * /investments/execute directly, so it is the request the backend keys on.
 */
describe('LumpSumInvestmentPage clientUuid', () => {
  const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
  let http: jasmine.SpyObj<HttpClient>;
  let pin: jasmine.SpyObj<PinService>;
  let page: LumpSumInvestmentPage;

  beforeEach(() => {
    http = jasmine.createSpyObj<HttpClient>('HttpClient', ['get', 'post']);
    pin = jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']);
    pin.hasPin.and.resolveTo(false);
    http.post.and.returnValue(of({ success: true, message: 'Investment initiated successfully!' }));

    page = new LumpSumInvestmentPage(
      jasmine.createSpyObj<Router>('Router', ['navigate']),
      { queryParams: of({}), params: of({}), snapshot: { queryParams: {} } } as unknown as ActivatedRoute,
      jasmine.createSpyObj<InvestmentService>('InvestmentService', ['initiateAcatsTransfer']),
      jasmine.createSpyObj<PortfolioService>('PortfolioService', ['getPortfolioDashboard']),
      http,
      jasmine.createSpyObj<PasskeyService>('PasskeyService', ['startAuthentication']),
      pin,
      jasmine.createSpyObj<ToastService>('ToastService', ['showToast']),
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
        'track',
        'trackOnboardingStepViewed',
        'trackOnboardingStepCompleted',
        'trackErrorShown',
        'screen',
      ]),
    );
  });

  it('a tap posts amount, type, fundingSource and a v4 clientUuid', fakeAsync(() => {
    page.investmentAmount = 500;

    page.makeInvestment();
    tick();
    tick(2000); // the deferred form reset the success path schedules

    expect(http.post).toHaveBeenCalledTimes(1);
    const [url, body] = http.post.calls.mostRecent().args;
    expect(url).toBe(`${environment.backendApiUrl}/investments/execute`);
    expect(body).toEqual({
      amount: 500,
      type: 'portfolio',
      fundingSource: 'bank',
      clientUuid: jasmine.stringMatching(UUID_RE),
    });
  }));

  it('two taps send two different clientUuids', fakeAsync(() => {
    page.investmentAmount = 500;

    page.makeInvestment();
    tick();
    page.makeInvestment();
    tick();
    tick(2000);

    expect(http.post).toHaveBeenCalledTimes(2);
    const first = http.post.calls.argsFor(0)[1] as { clientUuid: string };
    const second = http.post.calls.argsFor(1)[1] as { clientUuid: string };
    expect(first.clientUuid).toMatch(UUID_RE);
    expect(second.clientUuid).toMatch(UUID_RE);
    expect(first.clientUuid).not.toBe(second.clientUuid);
  }));
});
