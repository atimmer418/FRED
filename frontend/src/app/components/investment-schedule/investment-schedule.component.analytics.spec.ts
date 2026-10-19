import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../services/auth.service';
import { InvestmentService } from '../../services/investment.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { InvestmentScheduleComponent } from './investment-schedule.component';

const ACATS_KEY = 'pendingAcats';

describe('InvestmentScheduleComponent — analytics', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let router: jasmine.SpyObj<Router>;
  let navCtrl: jasmine.SpyObj<NavController>;
  let createOrUpdate: jasmine.Spy;
  let authService: AuthService;
  let investmentService: InvestmentService;
  let http: HttpClient;
  let component: InvestmentScheduleComponent;

  function build(): InvestmentScheduleComponent {
    return new InvestmentScheduleComponent(router, navCtrl, authService, investmentService, http, analytics);
  }

  beforeEach(() => {
    localStorage.removeItem(ACATS_KEY);

    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'track',
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
      'screen',
    ]);
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);
    navCtrl = jasmine.createSpyObj<NavController>('NavController', ['navigateForward']);
    createOrUpdate = jasmine.createSpy('createOrUpdateInvestmentSchedule').and.returnValue(of({}));
    authService = {
      userProgress$: of(null),
      getCurrentInvestmentSchedule: () => of(null),
      getCurrentProgress: () => null,
      getUserProgress: () => of({ monthlyInvestment: 2000 }),
      createOrUpdateInvestmentSchedule: createOrUpdate,
      completeStep: jasmine.createSpy('completeStep').and.returnValue(of({})),
    } as unknown as AuthService;
    investmentService = {
      pendingAcatsRequest: null,
      initiateAcatsTransfer: jasmine.createSpy('initiateAcatsTransfer').and.returnValue(of({})),
    } as unknown as InvestmentService;
    http = { post: jasmine.createSpy('post').and.returnValue(of({})) } as unknown as HttpClient;

    component = build();
  });

  afterEach(() => {
    localStorage.removeItem(ACATS_KEY);
  });

  it('reports the step as viewed and not resumed with no saved transfer draft', () => {
    component.ngOnInit();

    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledWith('investment_schedule', null, false);
  });

  it('reports resumed when a transfer draft was restored from storage', () => {
    localStorage.setItem(ACATS_KEY, JSON.stringify({ dtc: '0226', account: '12345678' }));

    component = build();
    component.ngOnInit();

    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledWith('investment_schedule', null, true);
  });

  it('sends nothing while the user is still picking a frequency or an amount', () => {
    component.ngOnInit();
    analytics.track.calls.reset();
    analytics.trackOnboardingStepCompleted.calls.reset();

    component.onFrequencyChange('WEEKLY');
    component.onAmountChange();

    expect(analytics.track).not.toHaveBeenCalled();
    expect(analytics.trackOnboardingStepCompleted).not.toHaveBeenCalled();
  });

  it('reports the step completed at the tap, before the schedule is saved', () => {
    component.ngOnInit();

    component.goToStockPreferences();

    expect(analytics.trackOnboardingStepCompleted).toHaveBeenCalledWith('investment_schedule', null);
    expect(analytics.trackOnboardingStepCompleted).toHaveBeenCalledBefore(createOrUpdate);
  });

  it('reports a failed schedule save as an alert, with the status and no message', () => {
    spyOn(window, 'alert');
    component.ngOnInit();
    createOrUpdate.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 500, error: { message: 'ACH relationship missing' } })),
    );

    component.goToStockPreferences();

    expect(analytics.trackErrorShown).toHaveBeenCalledWith('save_failed', 'onboarding', 'alert', 500, true);
    expect(JSON.stringify(analytics.trackErrorShown.calls.allArgs())).not.toContain('ACH');
  });

  it('reports a connection that never reached the server as a network error', () => {
    spyOn(window, 'alert');
    component.ngOnInit();
    createOrUpdate.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    component.goToStockPreferences();

    expect(analytics.trackErrorShown).toHaveBeenCalledWith('network', 'onboarding', 'alert', null, true);
  });

  it('never sends the account number the user typed for a transfer', () => {
    spyOn(window, 'alert');
    component.ngOnInit();
    component.showTransferOptions = true;
    component.transferBrokerageDtc = '0226';
    component.transferAccountNumber = '98765432';

    component.goToStockPreferences();

    const everySentArgument = JSON.stringify([
      analytics.track.calls.allArgs(),
      analytics.trackOnboardingStepViewed.calls.allArgs(),
      analytics.trackOnboardingStepCompleted.calls.allArgs(),
      analytics.trackErrorShown.calls.allArgs(),
    ]);
    expect(everySentArgument).not.toContain('98765432');
  });
});
