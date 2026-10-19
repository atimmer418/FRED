import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  IonHeader,
  IonToolbar,
  IonTitle,
  IonContent,
  IonIcon,
  ModalController,
} from '@ionic/angular/standalone';
import { ScenarioType, ShowdownResult } from '../../services/monte-carlo.service';
import { SimulationHistoryService, SimulationHistoryEntry } from '../../services/simulation-history.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { addIcons } from 'ionicons';
import { diceOutline, schoolOutline } from 'ionicons/icons';
import { TabBarScrollDirective } from '../../directives/tab-bar-scroll.directive';
import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';
import { MonteCarloFlowComponent } from '../monte-carlo-flow/monte-carlo-flow.component';
import { McInfoSheetComponent } from '../mc-info-sheet/mc-info-sheet.component';
import { StrategyDeckComponent, EDU_CARD_DEFS } from '../strategy-deck/strategy-deck.component';
import { DrawdownDeckComponent } from '../drawdown-deck/drawdown-deck.component';
import { buildFade } from '../../utils/modal-fade.utils';
import { BOTTOM_SHEET_MODAL_OPTS } from '../../utils/bottom-sheet.utils';
import { RetirementSectionService } from '../../services/retirement-section.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { GatedFeature, Tier } from '../../services/analytics/analytics-events';


@Component({
  selector: 'app-retirement-planning',
  templateUrl: './retirement-planning.component.html',
  styleUrls: ['./retirement-planning.component.scss'],
  standalone: true,
  imports: [
    CommonModule,
    IonHeader,
    IonToolbar,
    IonTitle,
    IonContent,
    IonIcon,
    TabBarScrollDirective,
    KeyboardAvoidDirective,
  ]
})
export class RetirementPlanningComponent implements OnInit {
  selectedSection: 'simulator' | 'education' = 'simulator';

  // ── Tier gate ─────────────────────────────────────────────────────────────────

  private currentTier: Tier = null;

  /** True once a userProgress emission has been applied, so the gate state is not a guess. */
  private tierKnown = false;

  /** True once the user has deliberately landed on the (possibly gated) simulator section. */
  private simulatorArrived = false;

  /** One monte_carlo nudge per screen instance; the overlay is declarative and never re-presents. */
  private lockNudgeReported = false;

  get isMonteCarloLocked(): boolean {
    return this.currentTier !== 'plus' && this.currentTier !== 'pro';
  }

  get isPlus(): boolean { return this.currentTier === 'plus' || this.currentTier === 'pro'; }
  get isPro(): boolean  { return this.currentTier === 'pro'; }

  // ── Section helpers ───────────────────────────────────────────────────────────

  isEducationSection(): boolean { return this.selectedSection === 'education'; }
  isSimulatorSection(): boolean { return this.selectedSection === 'simulator'; }

  setSelectedSection(section: 'simulator' | 'education'): void {
    this.selectedSection = section;
    if (section === 'simulator') {
      this.simulatorArrived = true;
      this.reportMonteCarloLockIfShown();
    }
  }

  // ── Education section ─────────────────────────────────────────────────────────

  /** Card data for the 2×2 education grid (icon, name, tag, hint). */
  readonly EDU_STRATEGIES = EDU_CARD_DEFS;

  /** Guard against double-presenting a strategy deck modal. */
  private deckLock = false;

  async openStrategyDeck(key: string): Promise<void> {
    if (this.deckLock) return;
    this.deckLock = true;

    const prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const enterAnim = (baseEl: HTMLElement) => buildFade(baseEl, prefersReduced ? 0 : 500);
    const leaveAnim = (baseEl: HTMLElement) => buildFade(baseEl, prefersReduced ? 0 : 320).direction('reverse');

    let role: string | undefined;
    try {
      const modal = await this.modalController.create({
        component: StrategyDeckComponent,
        cssClass: 'mc-fullscreen-modal',
        enterAnimation: enterAnim,
        leaveAnimation: leaveAnim,
        componentProps: { strategyKey: key, userTier: this.currentTier },
      });
      await modal.present();
      ({ role } = await modal.onDidDismiss());
    } finally {
      this.deckLock = false;
    }

    if (role === 'stress-test') {
      this.setSelectedSection('simulator');
      this.toastService.showToast('Simulator ready. Tap the piggy', 'medium', 2400);
    } else if (role === 'drawdown') {
      await this.openDrawdownDeck();
    } else if (role === 'upgrade') {
      this.upgradeToPlusClicked();
    }
  }

  /** Guard against double-presenting the drawdown deck. Separate from deckLock:
   *  the strategy deck's dismissal chain runs while deckLock is still held. */
  private drawdownLock = false;

  /** Present the Optimal Drawdown deck (Piggy Pro). Reached via the strategy
   *  decks' 'drawdown' dismiss role; public so the spec can drive it directly. */
  async openDrawdownDeck(): Promise<void> {
    if (this.drawdownLock) return;
    this.drawdownLock = true;

    const prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const enterAnim = (baseEl: HTMLElement) => buildFade(baseEl, prefersReduced ? 0 : 500);
    const leaveAnim = (baseEl: HTMLElement) => buildFade(baseEl, prefersReduced ? 0 : 320).direction('reverse');

    let role: string | undefined;
    try {
      const modal = await this.modalController.create({
        component: DrawdownDeckComponent,
        cssClass: 'mc-fullscreen-modal',
        enterAnimation: enterAnim,
        leaveAnimation: leaveAnim,
        componentProps: { userId: this.currentUserId },
      });
      await modal.present();
      ({ role } = await modal.onDidDismiss());
    } finally {
      this.drawdownLock = false;
    }

    if (role === 'stress-test') {
      this.includeOutsideAccts = true;
      this.setSelectedSection('simulator');
      this.toastService.showToast('Simulator ready. Tap the piggy', 'medium', 2400);
    }
  }

  // ── Landing state ─────────────────────────────────────────────────────────────

  recentHistory: SimulationHistoryEntry[] = [];
  selectedScenario: ScenarioType = 'expected';
  includeOutsideAccts: boolean = false;

  /** Scenario options shown in the chip row. */
  readonly SCENARIOS: { key: ScenarioType; label: string }[] = [
    { key: 'conservative', label: 'Conservative (8%)' },
    { key: 'expected',     label: 'Moderate (10%)'    },
    { key: 'aggressive',   label: 'Optimistic (12%)'  },
  ];

  // ── Double-present guard ──────────────────────────────────────────────────────

  private flowLock = false;

  // ── Derived userId ────────────────────────────────────────────────────────────

  get currentUserId(): string {
    return String(this.authService.getCurrentUserId() ?? '');
  }

  // ── Constructor ───────────────────────────────────────────────────────────────

  constructor(
    private authService: AuthService,
    private historyService: SimulationHistoryService,
    private modalController: ModalController,
    private toastService: ToastService,
    private retirementSection: RetirementSectionService,
    private analytics: AnalyticsService,
  ) {
    addIcons({ diceOutline, schoolOutline });
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────────────

  ngOnInit(): void {
    this.authService.userProgress$.subscribe(progress => {
      this.currentTier = progress?.selectedTier ?? null;
      this.tierKnown = true;
      // The tier can arrive after the user is already looking at the gate.
      this.reportMonteCarloLockIfShown();
    });
    // Deep-link requests (e.g. the MFU Retire card, FRED-217) switch the visible section.
    this.retirementSection.request$.subscribe(section => this.setSelectedSection(section));
    this.loadHistory();
  }

  // ── History ───────────────────────────────────────────────────────────────────

  loadHistory(): void {
    if (this.currentUserId) {
      this.recentHistory = this.historyService.getHistory(this.currentUserId);
    }
  }

  // ── Analytics ─────────────────────────────────────────────────────────────────

  private trackPaywallNudge(
    feature: GatedFeature,
    action: 'shown' | 'upgrade_tapped' | 'dismissed',
  ): void {
    this.analytics.track({
      name: 'paywall_nudge_shown',
      props: { feature, tier: this.currentTier, action },
    });
  }

  /**
   * Reports the simulator lock as shown, once, when it is genuinely on screen.
   *
   * It is deliberately NOT reported from ngOnInit: all four tabs mount together in the swipeable
   * pager, so this component is constructed at launch for every user whether or not they ever open
   * the Retirement tab, and an init-time event would credit a nudge to all of them. Arriving at
   * the simulator section is the one moment this component can observe by itself; the tier has to
   * be known too, or a user whose progress has not loaded looks gated when they are not.
   */
  private reportMonteCarloLockIfShown(): void {
    if (this.lockNudgeReported || !this.simulatorArrived || !this.tierKnown) {
      return;
    }
    if (!this.isMonteCarloLocked) {
      return;
    }
    this.lockNudgeReported = true;
    this.trackPaywallNudge('monte_carlo', 'shown');
  }

  /**
   * The lock overlay's Upgrade button. Separate from upgradeToPlusClicked because the same upgrade
   * routine is reached from three different gates, and the event has to name the gated feature.
   */
  upgradeFromMonteCarloLock(): void {
    this.trackPaywallNudge('monte_carlo', 'upgrade_tapped');
    this.upgradeToPlusClicked();
  }

  // ── Modal presentation ────────────────────────────────────────────────────────

  async openSimulatorFlow(historyEntry?: SimulationHistoryEntry): Promise<void> {
    if (this.isMonteCarloLocked) { this.upgradeToPlusClicked(); return; }
    if (this.flowLock) return;
    this.flowLock = true;

    // At the user's action, after the double-present guard so one tap is one open.
    this.analytics.track({
      name: 'monte_carlo_opened',
      props: { source: historyEntry ? 'history' : 'orb', tier: this.currentTier },
    });

    // Slower fade in (premium reveal), snappier fade out on close.
    // Uses the shared buildFade util (WKWebView lesson: must drive both
    // opacity AND transform on the wrapper — see modal-fade.utils.ts).
    const enterAnim = (baseEl: HTMLElement) => buildFade(baseEl, 500);
    const leaveAnim = (baseEl: HTMLElement) => buildFade(baseEl, 320).direction('reverse');

    try {
      const modal = await this.modalController.create({
        component: MonteCarloFlowComponent,
        cssClass: 'mc-fullscreen-modal',
        enterAnimation: enterAnim,
        leaveAnimation: leaveAnim,
        componentProps: {
          initialScenario: this.selectedScenario,
          includeOutside: this.includeOutsideAccts,
          historyEntry: historyEntry ?? null,
          userTier: this.currentTier,
          userId: this.currentUserId,
        },
      });
      await modal.present();
      await modal.onDidDismiss();
    } finally {
      this.flowLock = false;
    }
    this.loadHistory();
  }

  upgradeToPlusClicked(): void {
    // TODO: trigger Plus subscription IAP
    console.log('[RetirementPlanning] Plus upgrade requested');
  }

  // Landing bottom sheets — presented via ModalController so they render at
  // the app root (above header + tab bar); position:fixed markup inside the
  // inner ion-content gets clipped by its scroll container on iOS.
  async openAssumptionsSheet(): Promise<void> {
    const modal = await this.modalController.create({
      component: McInfoSheetComponent,
      componentProps: { mode: 'assumptions' },
      ...BOTTOM_SHEET_MODAL_OPTS,
    });
    await modal.present();
  }

  async openChipNudge(): Promise<void> {
    const modal = await this.modalController.create({
      component: McInfoSheetComponent,
      componentProps: { mode: 'nudge' },
      ...BOTTOM_SHEET_MODAL_OPTS,
    });
    await modal.present();
    this.trackPaywallNudge('market_scenarios', 'shown');
    const { role } = await modal.onDidDismiss();
    if (role === 'upgrade') {
      this.trackPaywallNudge('market_scenarios', 'upgrade_tapped');
      this.upgradeToPlusClicked();
    } else {
      this.trackPaywallNudge('market_scenarios', 'dismissed');
    }
  }

  // ── Landing helper formatters ──────────────────────────────────────────────────

  fmtShort(v: number): string {
    if (v >= 1_000_000) return '$' + (v / 1_000_000).toFixed(1).replace(/\.0$/, '') + 'M';
    if (v >= 1_000) return '$' + Math.round(v / 1_000) + 'k';
    return '$' + Math.round(v);
  }

  fmtDollar(v: number): string {
    return new Intl.NumberFormat('en-US', {
      style: 'currency', currency: 'USD',
      minimumFractionDigits: 0, maximumFractionDigits: 0
    }).format(v);
  }

  relativeDate(ts: number): string {
    const diffMs = Date.now() - ts;
    const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24));
    if (diffDays === 0) return 'Today';
    if (diffDays === 1) return 'Yesterday';
    if (diffDays < 7) return diffDays + 'd ago';
    if (diffDays < 30) return Math.floor(diffDays / 7) + 'w ago';
    return Math.floor(diffDays / 30) + 'mo ago';
  }

  holdCount(results: ShowdownResult): number {
    return Object.values(results).filter(p => p >= 75).length;
  }

  holdClass(results: ShowdownResult): string {
    const n = this.holdCount(results);
    return n >= 4 ? 'good' : n >= 2 ? 'mid' : 'bad';
  }

  scenarioIcon(scenario: ScenarioType): string {
    return scenario === 'conservative' ? 'south_east'
         : scenario === 'aggressive'   ? 'north_east'
         : 'trending_up';
  }

  scenarioLabel(scenario: ScenarioType): string {
    return this.SCENARIOS.find(s => s.key === scenario)?.label ?? scenario;
  }
}
