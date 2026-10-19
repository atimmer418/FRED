import { ChangeDetectorRef, ElementRef } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { fakeAsync, tick } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { ModalController } from '@ionic/angular/standalone';
import { MonteCarloFlowComponent } from './monte-carlo-flow.component';
import { MonteCarloService, ShowdownResult } from '../../services/monte-carlo.service';
import { SimulationHistoryService } from '../../services/simulation-history.service';
import { PortfolioService, PortfolioDashboardData } from '../../services/portfolio.service';
import { AuthService } from '../../services/auth.service';
import { AlpacaService } from '../../services/alpaca.service';
import { ToastService } from '../../services/toast.service';
import { FreedomStatsService } from '../../services/freedom-stats.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';

/**
 * Analytics on the Monte Carlo flow: the input steps, the finished result, the result-screen
 * controls, the Pro gate and the two visible failures.
 *
 * The component is built directly with stubs. ngOnInit is deliberately not run: it subscribes to
 * progress, fetches a birth year and starts timers, none of which any event here depends on. The
 * step order is built with buildStepOrder(), which is what ngOnInit would have called.
 */
describe('MonteCarloFlowComponent — analytics', () => {

  const RESULT: ShowdownResult = {
    traditional: 95,
    sbloc: 80,
    'annuity-growth': 70,
    'dynamic-guardrails': 92,
    'full-annuity': 60,
  };

  const LAST_USED_KEY = 'fred.mcLastUsed.anon';
  const OUTSIDE_KEY = 'fred.mcOutside.anon';

  function dashboard(portfolioValue: number): PortfolioDashboardData {
    return { summary: { portfolioValue } } as unknown as PortfolioDashboardData;
  }

  function sliderEvent(value: number): Event {
    return { target: { value: String(value) } } as unknown as Event;
  }

  function textEvent(value: string): Event {
    return { target: { value } } as unknown as Event;
  }

  function makeModal() {
    return {
      present: jasmine.createSpy('present').and.returnValue(Promise.resolve()),
      onDidDismiss: jasmine.createSpy('onDidDismiss').and.returnValue(Promise.resolve({})),
    } as unknown as HTMLIonModalElement;
  }

  function build(result: ShowdownResult = RESULT) {
    const analytics = jasmine.createSpyObj<AnalyticsService>(
      'AnalyticsService', ['track', 'trackErrorShown'],
    );
    const monteCarlo = jasmine.createSpyObj<MonteCarloService>(
      'MonteCarloService', ['runShowdown', 'solveMaxMonthlySpend'],
    );
    monteCarlo.runShowdown.and.returnValue(result);
    monteCarlo.solveMaxMonthlySpend.and.returnValue(5_000);
    const history = jasmine.createSpyObj<SimulationHistoryService>(
      'SimulationHistoryService', ['saveEntry'],
    );
    const portfolio = jasmine.createSpyObj<PortfolioService>(
      'PortfolioService', ['getPortfolioDashboard'],
    );
    portfolio.getPortfolioDashboard.and.returnValue(of(dashboard(84_200)));
    const toast = jasmine.createSpyObj<ToastService>('ToastService', ['showToast']);
    const modalCtrl = jasmine.createSpyObj<ModalController>('ModalController', ['create', 'dismiss']);
    modalCtrl.create.and.returnValue(Promise.resolve(makeModal()));
    const cdr = jasmine.createSpyObj<ChangeDetectorRef>('ChangeDetectorRef', ['detectChanges']);
    const auth = { userProgress$: of(null) } as unknown as AuthService;
    const alpaca = { getKycData: () => of({}) } as unknown as AlpacaService;
    const freedomStats = {
      monthlyActualInvestment: () => 0,
      stats: () => ({ freedomYear: '2050' }),
    } as unknown as FreedomStatsService;

    const cmp = new MonteCarloFlowComponent(
      monteCarlo,
      history,
      portfolio,
      auth,
      alpaca,
      toast,
      modalCtrl,
      cdr,
      new ElementRef(document.createElement('div')),
      freedomStats,
      analytics,
    );
    cmp.buildStepOrder();
    return { cmp, analytics, portfolio, toast, modalCtrl };
  }

  afterEach(() => {
    localStorage.removeItem(LAST_USED_KEY);
    localStorage.removeItem(OUTSIDE_KEY);
  });

  // ── monte_carlo_step_completed ──────────────────────────────────────────────

  describe('monte_carlo_step_completed', () => {

    it('reports a dragged portfolio value at Continue, and nothing per slider frame', () => {
      const { cmp, analytics } = build();

      cmp.onPortfolioSlider(sliderEvent(400));
      cmp.onPortfolioSlider(sliderEvent(500));
      cmp.onPortfolioSlider(sliderEvent(600));
      expect(analytics.track).not.toHaveBeenCalled();

      cmp.onContinueClick();

      expect(analytics.track).toHaveBeenCalledTimes(1);
      expect(analytics.track).toHaveBeenCalledWith({
        name: 'monte_carlo_step_completed',
        props: { step: 'portfolio', input_method: 'slider' },
      });
    });

    it('reports a typed portfolio value', () => {
      const { cmp, analytics } = build();

      cmp.portfolioTypingRaw = '250,000';
      cmp.commitPortfolioTyping();
      cmp.onContinueClick();

      expect(analytics.track).toHaveBeenCalledWith({
        name: 'monte_carlo_step_completed',
        props: { step: 'portfolio', input_method: 'typed' },
      });
    });

    it('reports the live-portfolio chip as fetched', async () => {
      const { cmp, analytics } = build();

      await cmp.fetchPortfolio();
      cmp.onContinueClick();

      expect(analytics.track).toHaveBeenCalledWith({
        name: 'monte_carlo_step_completed',
        props: { step: 'portfolio', input_method: 'fetched' },
      });
    });

    it('reports a step the user accepted untouched as no input method', () => {
      const { cmp, analytics } = build();

      cmp.onContinueClick();

      expect(analytics.track).toHaveBeenCalledWith({
        name: 'monte_carlo_step_completed',
        props: { step: 'portfolio', input_method: null },
      });
    });

    it('reports the outside-accounts step as typed, and nothing per keystroke', () => {
      const { cmp, analytics } = build();
      cmp.includeOutside = true;
      cmp.buildStepOrder();
      cmp.showStep('outside');

      cmp.onK401Input(textEvent('250000'));
      cmp.onRothInput(textEvent('85000'));
      expect(analytics.track).not.toHaveBeenCalled();

      cmp.onContinueClick();

      expect(analytics.track).toHaveBeenCalledTimes(1);
      expect(analytics.track).toHaveBeenCalledWith({
        name: 'monte_carlo_step_completed',
        props: { step: 'outside', input_method: 'typed' },
      });
    });

    it('reports the spend step', () => {
      const { cmp, analytics } = build();
      cmp.showStep('spend');

      cmp.onSpendSlider(sliderEvent(5_500));
      cmp.onContinueClick();

      expect(analytics.track).toHaveBeenCalledWith({
        name: 'monte_carlo_step_completed',
        props: { step: 'spend', input_method: 'slider' },
      });
    });

    it('reports the duration step', fakeAsync(() => {
      const { cmp, analytics } = build();
      cmp.showStep('duration');

      cmp.onDurationSlider(sliderEvent(40));
      cmp.onContinueClick();

      expect(analytics.track).toHaveBeenCalledWith({
        name: 'monte_carlo_step_completed',
        props: { step: 'duration', input_method: 'slider' },
      });

      cmp.ngOnDestroy(); // stop the calculating theater the duration CTA started
    }));
  });

  // ── monte_carlo_result_viewed ───────────────────────────────────────────────

  describe('monte_carlo_result_viewed', () => {

    /**
     * Runs the real path from the duration CTA to the results screen. The calculating theater
     * measures itself with performance.now(), which zone's fakeAsync does not virtualize, so the
     * clock is stubbed to report the run finished on its first frame.
     */
    function runToResults(cmp: MonteCarloFlowComponent): void {
      let firstReading = true;
      spyOn(performance, 'now').and.callFake(() => {
        if (firstReading) {
          firstReading = false;
          return 0;
        }
        return 99_999;
      });
      cmp.showStep('duration');
      cmp.onContinueClick();
      tick(16);   // one theater frame: the stubbed clock reports the run complete
      tick(400);  // the reveal timer, and the calc-line fade
    }

    it('reports the outcome, scenario and how many strategies held', fakeAsync(() => {
      const { cmp, analytics } = build();
      cmp.scenario.set('conservative');

      runToResults(cmp);

      expect(analytics.track).toHaveBeenCalledWith({
        name: 'monte_carlo_result_viewed',
        props: {
          outcome: 'strong',          // traditional 95 >= 90
          scenario: 'conservative',
          strategies_held: '2_3',     // 95, 92 and 80 cleared 75
          is_rerun: false,
          included_outside_accounts: false,
        },
      });
      cmp.ngOnDestroy();
    }));

    it('reports a weaker run as moderate, with outside accounts and the re-run flag', fakeAsync(() => {
      const { cmp, analytics } = build({
        traditional: 80,
        sbloc: 40,
        'annuity-growth': 30,
        'dynamic-guardrails': 20,
        'full-annuity': 10,
      });
      cmp.includeOutside = true;
      cmp.isHistoryRerun.set(true);

      runToResults(cmp);

      expect(analytics.track).toHaveBeenCalledWith({
        name: 'monte_carlo_result_viewed',
        props: {
          outcome: 'moderate',        // 80 is under 90 and at or over 75
          scenario: 'expected',
          strategies_held: '1',
          is_rerun: true,
          included_outside_accounts: true,
        },
      });
      cmp.ngOnDestroy();
    }));

    it('reports a failing run as at risk', fakeAsync(() => {
      const { cmp, analytics } = build({
        traditional: 40,
        sbloc: 40,
        'annuity-growth': 30,
        'dynamic-guardrails': 20,
        'full-annuity': 10,
      });

      runToResults(cmp);

      expect(analytics.track).toHaveBeenCalledWith({
        name: 'monte_carlo_result_viewed',
        props: {
          outcome: 'risk',
          scenario: 'expected',
          strategies_held: '0',
          is_rerun: false,
          included_outside_accounts: false,
        },
      });
      cmp.ngOnDestroy();
    }));
  });

  // ── monte_carlo_result_acted ────────────────────────────────────────────────

  describe('monte_carlo_result_acted', () => {

    it('collapses a burst of contribution taps into one increase', fakeAsync(() => {
      const { cmp, analytics } = build();
      cmp.userTier = 'pro';

      cmp.stepContrib(100);
      cmp.stepContrib(100);
      cmp.stepContrib(100);
      expect(analytics.track).not.toHaveBeenCalled();

      tick(700);

      expect(analytics.track).toHaveBeenCalledTimes(1);
      expect(analytics.track).toHaveBeenCalledWith({
        name: 'monte_carlo_result_acted',
        props: { action: 'adjusted_contribution', direction: 'increase', scenario: null },
      });
    }));

    it('reports nothing when a burst ends where it started', fakeAsync(() => {
      const { cmp, analytics } = build();
      cmp.monthlyContrib.set(500);

      cmp.stepContrib(100);
      cmp.stepContrib(-100);
      tick(700);

      expect(analytics.track).not.toHaveBeenCalled();
    }));

    it('reports nothing when a tap is clamped at a bound', fakeAsync(() => {
      const { cmp, analytics } = build();
      cmp.yearsToRetirement.set(0);

      cmp.stepRetirement(-1);
      tick(700);

      expect(analytics.track).not.toHaveBeenCalled();
    }));

    it('reports a retirement decrease', fakeAsync(() => {
      const { cmp, analytics } = build();
      cmp.yearsToRetirement.set(10);

      cmp.stepRetirement(-1);
      cmp.stepRetirement(-1);
      tick(700);

      expect(analytics.track).toHaveBeenCalledTimes(1);
      expect(analytics.track).toHaveBeenCalledWith({
        name: 'monte_carlo_result_acted',
        props: { action: 'adjusted_retirement', direction: 'decrease', scenario: null },
      });
    }));

    it('closes an open burst before starting one on the other stepper', fakeAsync(() => {
      const { cmp, analytics } = build();
      cmp.monthlyContrib.set(500);
      cmp.yearsToRetirement.set(10);

      cmp.stepContrib(100);
      cmp.stepRetirement(1);

      expect(analytics.track).toHaveBeenCalledTimes(1);
      expect(analytics.track).toHaveBeenCalledWith({
        name: 'monte_carlo_result_acted',
        props: { action: 'adjusted_contribution', direction: 'increase', scenario: null },
      });

      tick(700);

      expect(analytics.track).toHaveBeenCalledTimes(2);
      expect(analytics.track).toHaveBeenCalledWith({
        name: 'monte_carlo_result_acted',
        props: { action: 'adjusted_retirement', direction: 'increase', scenario: null },
      });
    }));

    it('reports an open burst when the flow is closed mid-burst', async () => {
      const { cmp, analytics } = build();
      cmp.monthlyContrib.set(500);

      cmp.stepContrib(100);
      await cmp.closeFlow();

      expect(analytics.track).toHaveBeenCalledWith({
        name: 'monte_carlo_result_acted',
        props: { action: 'adjusted_contribution', direction: 'increase', scenario: null },
      });
    });

    it('reports a scenario flip with the internal key', () => {
      const { cmp, analytics } = build();

      cmp.flipScenario('aggressive');

      expect(analytics.track).toHaveBeenCalledTimes(1);
      expect(analytics.track).toHaveBeenCalledWith({
        name: 'monte_carlo_result_acted',
        props: { action: 'changed_scenario', direction: null, scenario: 'aggressive' },
      });
    });

    it('reports a share at the tap', async () => {
      const { cmp, analytics } = build();

      await cmp.shareResults();

      expect(analytics.track).toHaveBeenCalledWith({
        name: 'monte_carlo_result_acted',
        props: { action: 'shared', direction: null, scenario: null },
      });
    });

    it('reports opening the assumptions sheet', async () => {
      const { cmp, analytics } = build();

      await cmp.openAssumptions();

      expect(analytics.track).toHaveBeenCalledTimes(1);
      expect(analytics.track).toHaveBeenCalledWith({
        name: 'monte_carlo_result_acted',
        props: { action: 'opened_assumptions', direction: null, scenario: null },
      });
    });
  });

  // ── paywall_nudge_shown ─────────────────────────────────────────────────────

  describe('paywall_nudge_shown', () => {

    it('reports the Pro gate as shown, then the upgrade tap, and not a dismissal', () => {
      const { cmp, analytics } = build();
      cmp.userTier = 'plus';

      cmp.openNudge();
      cmp.upgradeClicked();

      expect(analytics.track).toHaveBeenCalledTimes(2);
      expect(analytics.track.calls.argsFor(0)).toEqual([{
        name: 'paywall_nudge_shown',
        props: { feature: 'market_scenarios', tier: 'plus', action: 'shown' },
      }]);
      expect(analytics.track.calls.argsFor(1)).toEqual([{
        name: 'paywall_nudge_shown',
        props: { feature: 'market_scenarios', tier: 'plus', action: 'upgrade_tapped' },
      }]);
    });

    it('reports a dismissal on maybe-later', () => {
      const { cmp, analytics } = build();
      cmp.userTier = 'plus';

      cmp.openNudge();
      cmp.closeNudge();

      expect(analytics.track).toHaveBeenCalledTimes(2);
      expect(analytics.track.calls.argsFor(1)).toEqual([{
        name: 'paywall_nudge_shown',
        props: { feature: 'market_scenarios', tier: 'plus', action: 'dismissed' },
      }]);
    });

    it('reports nothing when the backdrop fires while the sheet is closed', () => {
      const { cmp, analytics } = build();

      cmp.closeNudge();

      expect(analytics.track).not.toHaveBeenCalled();
    });
  });

  // ── error_shown ─────────────────────────────────────────────────────────────

  describe('error_shown', () => {

    it('reports a failed portfolio fetch with its status and no message', async () => {
      const { cmp, analytics, portfolio, toast } = build();
      portfolio.getPortfolioDashboard.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 503, error: 'upstream broker name' })),
      );

      await cmp.fetchPortfolio();

      expect(toast.showToast).toHaveBeenCalled();
      expect(analytics.trackErrorShown).toHaveBeenCalledTimes(1);
      expect(analytics.trackErrorShown).toHaveBeenCalledWith(
        'load_failed', 'monte_carlo', 'toast', 503, true,
      );
    });

    it('reports the empty-holdings notice with no status', async () => {
      const { cmp, analytics, portfolio } = build();
      portfolio.getPortfolioDashboard.and.returnValue(of(dashboard(0)));

      await cmp.fetchPortfolio();

      expect(analytics.trackErrorShown).toHaveBeenCalledWith(
        'load_failed', 'monte_carlo', 'toast', null, true,
      );
    });

    it('reports nothing when the fetch succeeds', async () => {
      const { cmp, analytics } = build();

      await cmp.fetchPortfolio();

      expect(analytics.trackErrorShown).not.toHaveBeenCalled();
    });

    it('reports a failed share as unavailable', async () => {
      const { cmp, analytics, toast } = build();
      // A non-element passes the capture guard and makes the capture itself fail, which is the
      // only deterministic way into the catch: the share path's two dynamic imports (html2canvas
      // and @capacitor/share) cannot be substituted from a spec.
      spyOn(document, 'querySelector').and.returnValue({} as HTMLElement);

      await cmp.shareResults();

      expect(toast.showToast).toHaveBeenCalledWith('Unable to share. Please try again.', 'danger');
      expect(analytics.trackErrorShown).toHaveBeenCalledWith(
        'unavailable', 'monte_carlo', 'toast', null, true,
      );
    });
  });
});
