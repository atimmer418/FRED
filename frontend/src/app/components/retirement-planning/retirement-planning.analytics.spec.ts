import { of } from 'rxjs';
import { ModalController } from '@ionic/angular/standalone';
import { RetirementPlanningComponent } from './retirement-planning.component';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import {
  SimulationHistoryEntry,
  SimulationHistoryService,
} from '../../services/simulation-history.service';
import { RetirementSectionService } from '../../services/retirement-section.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';

/**
 * Analytics on the retirement landing: opening the simulator, and the two paywall gates that live
 * on this screen (the simulator lock and the Pro-only scenario chips).
 *
 * Built directly rather than through TestBed, matching retirement-planning.section-deeplink.spec.
 */
describe('RetirementPlanningComponent — analytics', () => {

  const HISTORY_ENTRY: SimulationHistoryEntry = {
    timestamp: 1_700_000_000_000,
    scenario: 'expected',
    totalPortfolioValue: 1_000_000,
    monthlySpend: 4_000,
    yearsToLast: 30,
    includeOutside: false,
    k401Balance: 0,
    rothBalance: 0,
    monthlyContribution: 0,
    yearsToRetirement: 0,
    results: {
      traditional: 95,
      sbloc: 80,
      'annuity-growth': 70,
      'dynamic-guardrails': 92,
      'full-annuity': 60,
    },
  };

  /** Fullscreen/sheet modal whose onDidDismiss resolves with the given role. */
  function makeModal(role: string | undefined) {
    return {
      present: jasmine.createSpy('present').and.returnValue(Promise.resolve()),
      onDidDismiss: jasmine.createSpy('onDidDismiss').and.returnValue(Promise.resolve({ role })),
    } as unknown as HTMLIonModalElement;
  }

  function build(tier: 'core' | 'plus' | 'pro' | null) {
    const analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', ['track']);
    const modalCtrl = jasmine.createSpyObj<ModalController>('ModalController', ['create', 'dismiss']);
    const toast = jasmine.createSpyObj<ToastService>('ToastService', ['showToast']);
    const authService = {
      userProgress$: of(tier === null ? {} : { selectedTier: tier }),
      getCurrentUserId: () => 42,
    } as unknown as AuthService;
    const historyService = { getHistory: () => [] } as unknown as SimulationHistoryService;
    const cmp = new RetirementPlanningComponent(
      authService,
      historyService,
      modalCtrl,
      toast,
      new RetirementSectionService(),
      analytics,
    );
    return { cmp, analytics, modalCtrl };
  }

  describe('monte_carlo_opened', () => {

    it('reports an orb run with the user tier', async () => {
      const { cmp, analytics, modalCtrl } = build('plus');
      modalCtrl.create.and.returnValue(Promise.resolve(makeModal(undefined)));
      cmp.ngOnInit();
      analytics.track.calls.reset();

      await cmp.openSimulatorFlow();

      expect(analytics.track).toHaveBeenCalledTimes(1);
      expect(analytics.track).toHaveBeenCalledWith({
        name: 'monte_carlo_opened',
        props: { source: 'orb', tier: 'plus' },
      });
    });

    it('reports a saved projection as a history re-run', async () => {
      const { cmp, analytics, modalCtrl } = build('pro');
      modalCtrl.create.and.returnValue(Promise.resolve(makeModal(undefined)));
      cmp.ngOnInit();
      analytics.track.calls.reset();

      await cmp.openSimulatorFlow(HISTORY_ENTRY);

      expect(analytics.track).toHaveBeenCalledWith({
        name: 'monte_carlo_opened',
        props: { source: 'history', tier: 'pro' },
      });
    });

    it('does not report an open the gate refused', async () => {
      const { cmp, analytics, modalCtrl } = build('core');
      cmp.ngOnInit();
      analytics.track.calls.reset();

      await cmp.openSimulatorFlow();

      expect(modalCtrl.create).not.toHaveBeenCalled();
      expect(analytics.track).not.toHaveBeenCalledWith({
        name: 'monte_carlo_opened',
        props: { source: 'orb', tier: 'core' },
      });
    });
  });

  describe('paywall_nudge_shown — the simulator lock', () => {

    it('reports the lock once, when a gated user lands on the simulator section', () => {
      const { cmp, analytics } = build('core');
      cmp.ngOnInit();

      cmp.setSelectedSection('simulator');

      expect(analytics.track).toHaveBeenCalledTimes(1);
      expect(analytics.track).toHaveBeenCalledWith({
        name: 'paywall_nudge_shown',
        props: { feature: 'monte_carlo', tier: 'core', action: 'shown' },
      });

      cmp.setSelectedSection('education');
      cmp.setSelectedSection('simulator');
      expect(analytics.track).toHaveBeenCalledTimes(1);
    });

    it('reports nothing on init, because every tab is mounted at launch', () => {
      const { cmp, analytics } = build('core');

      cmp.ngOnInit();

      expect(analytics.track).not.toHaveBeenCalled();
    });

    it('reports nothing for a user who is not gated', () => {
      const { cmp, analytics } = build('plus');
      cmp.ngOnInit();

      cmp.setSelectedSection('simulator');

      expect(analytics.track).not.toHaveBeenCalled();
    });

    it('reports nothing until the tier is known', () => {
      const { cmp, analytics } = build('core');

      cmp.setSelectedSection('simulator');

      expect(analytics.track).not.toHaveBeenCalled();
    });

    it('reports the upgrade tap from the lock overlay', () => {
      const { cmp, analytics } = build(null);
      cmp.ngOnInit();
      analytics.track.calls.reset();

      cmp.upgradeFromMonteCarloLock();

      expect(analytics.track).toHaveBeenCalledTimes(1);
      expect(analytics.track).toHaveBeenCalledWith({
        name: 'paywall_nudge_shown',
        props: { feature: 'monte_carlo', tier: null, action: 'upgrade_tapped' },
      });
    });
  });

  describe('paywall_nudge_shown — the scenario chips', () => {

    it('reports the nudge as shown, then the upgrade tap', async () => {
      const { cmp, analytics, modalCtrl } = build('plus');
      modalCtrl.create.and.returnValue(Promise.resolve(makeModal('upgrade')));
      cmp.ngOnInit();
      analytics.track.calls.reset();

      await cmp.openChipNudge();

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

    it('reports a dismissal when the sheet is closed without upgrading', async () => {
      const { cmp, analytics, modalCtrl } = build('plus');
      modalCtrl.create.and.returnValue(Promise.resolve(makeModal(undefined)));
      cmp.ngOnInit();
      analytics.track.calls.reset();

      await cmp.openChipNudge();

      expect(analytics.track).toHaveBeenCalledTimes(2);
      expect(analytics.track.calls.argsFor(1)).toEqual([{
        name: 'paywall_nudge_shown',
        props: { feature: 'market_scenarios', tier: 'plus', action: 'dismissed' },
      }]);
    });
  });
});
