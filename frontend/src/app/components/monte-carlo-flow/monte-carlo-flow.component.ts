import {
  Component, OnInit, OnDestroy, Input, signal, ChangeDetectorRef, ElementRef
} from '@angular/core';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  ModalController,
} from '@ionic/angular/standalone';
import { Capacitor } from '@capacitor/core';
import { Keyboard } from '@capacitor/keyboard';
import { MonteCarloService, ScenarioType, ShowdownParams, ShowdownResult } from '../../services/monte-carlo.service';
import { SimulationHistoryService, SimulationHistoryEntry } from '../../services/simulation-history.service';
import { PortfolioService } from '../../services/portfolio.service';
import { AuthService } from '../../services/auth.service';
import { AlpacaService } from '../../services/alpaca.service';
import { ToastService } from '../../services/toast.service';
import { FreedomStatsService } from '../../services/freedom-stats.service';
import { McInfoSheetComponent } from '../mc-info-sheet/mc-info-sheet.component';
import { BOTTOM_SHEET_MODAL_OPTS } from '../../utils/bottom-sheet.utils';

// ─── Flow step machine type ──────────────────────────────────────────────────

export type FlowStep = 'portfolio' | 'outside' | 'spend' | 'duration' | 'calc' | 'results';

// ─── Ordered strategy definitions ────────────────────────────────────────────

export const STRATEGY_DEFS = [
  { key: 'traditional',         name: 'Yield-Based Income',  tag: 'Live off dividends and interest, never panic-sell' },
  { key: 'dynamic-guardrails',  name: 'Dynamic Guardrails', tag: 'Spend more in good years, less in bad' },
  { key: 'sbloc',               name: 'SBLOC',              tag: 'Borrow through downturns, never sell low' },
  { key: 'annuity-growth',      name: 'Annuity + Growth',   tag: 'Guarantee essentials, grow the rest' },
  { key: 'full-annuity',        name: 'Full Annuity',       tag: 'Convert everything to lifetime income' },
] as const;

// ─── Calculating theater copy ─────────────────────────────────────────────────

const CALC_LINES = [
  'Crashing the market on purpose…',
  'Testing a 1970s-style inflation decade…',
  'Compounding the good years…',
  'Checking every withdrawal strategy…',
];

// ─── Slider helpers ───────────────────────────────────────────────────────────

const PF_MIN = 10_000;
const PF_MAX = 5_000_000;

function sliderToValue(t: number): number {
  return Math.round(PF_MIN * Math.pow(PF_MAX / PF_MIN, t / 1000) / 1000) * 1000;
}

function valueToSlider(v: number): number {
  const c = Math.min(Math.max(v, PF_MIN), PF_MAX);
  return Math.round(1000 * Math.log(c / PF_MIN) / Math.log(PF_MAX / PF_MIN));
}

// ─── Component ───────────────────────────────────────────────────────────────

@Component({
  selector: 'app-monte-carlo-flow',
  templateUrl: './monte-carlo-flow.component.html',
  styleUrls: ['./monte-carlo-flow.component.scss'],
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
  ]
})
export class MonteCarloFlowComponent implements OnInit, OnDestroy {

  // ── Inputs from the presenting component ──────────────────────────────────

  /** Initial scenario to run (from the landing chip selection). */
  @Input() initialScenario: ScenarioType = 'expected';

  /** Whether the "Include outside accounts" toggle is on. */
  @Input() includeOutside: boolean = false;

  /** When set, skip the full theater and re-run with these inputs. */
  @Input() historyEntry: SimulationHistoryEntry | null = null;

  /** Current user tier ('core'|'plus'|'pro'|null). */
  @Input() userTier: string | null = null;

  /** Current userId (for history save). */
  @Input() userId: string | null = null;

  // ── Derived tier flags ───────────────────────────────────────────────────

  get isPlus(): boolean { return this.userTier === 'plus' || this.userTier === 'pro'; }
  get isPro(): boolean  { return this.userTier === 'pro'; }

  // ── Step machine ─────────────────────────────────────────────────────────

  currentStep = signal<FlowStep>('portfolio');
  isHistoryRerun = signal(false);

  private stepOrder: FlowStep[] = [];

  buildStepOrder(): void {
    this.stepOrder = [
      'portfolio',
      ...(this.includeOutside ? ['outside' as FlowStep] : []),
      'spend',
      'duration',
    ];
  }

  get inputSteps(): FlowStep[] {
    return this.stepOrder;
  }

  stepIndex(step: FlowStep): number {
    return this.stepOrder.indexOf(step);
  }

  get currentStepIndex(): number {
    return this.stepOrder.indexOf(this.currentStep());
  }

  get stepCount(): number {
    return this.stepOrder.length;
  }

  // ── Portfolio step ────────────────────────────────────────────────────────

  portfolioSliderRaw = signal(valueToSlider(1_000_000));
  portfolioValue = signal(1_000_000);
  portfolioTyping = signal(false);
  portfolioTypingRaw = '';
  portfolioFetching = signal(false);

  get portfolioSliderPct(): number {
    const v = this.portfolioSliderRaw();
    return ((v - 0) / (1000 - 0)) * 100;
  }

  onPortfolioSlider(event: Event): void {
    const t = +(event.target as HTMLInputElement).value;
    this.portfolioSliderRaw.set(t);
    this.portfolioValue.set(sliderToValue(t));
  }

  syncPortfolioSlider(): void {
    this.portfolioSliderRaw.set(valueToSlider(this.portfolioValue()));
  }

  onPortfolioReadoutClick(): void {
    this.portfolioTypingRaw = String(this.portfolioValue());
    this.portfolioTyping.set(true);
    setTimeout(() => {
      const el = document.getElementById('pfTypeInput') as HTMLInputElement;
      el?.focus();
      el?.select();
    }, 50);
  }

  commitPortfolioTyping(): void {
    const stripped = this.portfolioTypingRaw.replace(/[^0-9]/g, '');
    const v = parseInt(stripped, 10);
    if (!isNaN(v) && v > 0) {
      this.portfolioValue.set(v);
      this.syncPortfolioSlider();
    }
    this.portfolioTyping.set(false);
  }

  onPortfolioTypingKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') (event.target as HTMLElement).blur();
  }

  async fetchPortfolio(): Promise<void> {
    if (this.portfolioFetching()) return;
    this.portfolioFetching.set(true);
    this.portfolioService.getPortfolioDashboard().subscribe({
      next: (data) => {
        const v = data?.summary?.portfolioValue;
        if (v && v > 0) {
          this.portfolioValue.set(Math.round(v));
          this.syncPortfolioSlider();
          this.toastService.showToast('Live portfolio value applied', 'success', 2000);
        } else {
          this.toastService.showToast('No holdings found. Enter a value manually', 'medium', 2500);
        }
        this.portfolioFetching.set(false);
      },
      error: () => {
        this.toastService.showToast('Could not fetch portfolio. Enter a value manually', 'medium', 2500);
        this.portfolioFetching.set(false);
      }
    });
  }

  get portfolioContinueEnabled(): boolean {
    return this.portfolioValue() > 0;
  }

  // ── Outside accounts step ─────────────────────────────────────────────────

  k401Balance = signal(0);
  rothBalance = signal(0);
  k401Raw = '';
  rothRaw = '';

  get combinedTotal(): number {
    return this.portfolioValue() + this.k401Balance() + this.rothBalance();
  }

  onK401Input(event: Event): void {
    const raw = (event.target as HTMLInputElement).value.replace(/[^0-9]/g, '');
    this.k401Raw = raw ? (+raw).toLocaleString('en-US') : '';
    (event.target as HTMLInputElement).value = this.k401Raw;
    this.k401Balance.set(raw ? +raw : 0);
  }

  onRothInput(event: Event): void {
    const raw = (event.target as HTMLInputElement).value.replace(/[^0-9]/g, '');
    this.rothRaw = raw ? (+raw).toLocaleString('en-US') : '';
    (event.target as HTMLInputElement).value = this.rothRaw;
    this.rothBalance.set(raw ? +raw : 0);
  }

  // ── Spend step ────────────────────────────────────────────────────────────

  monthlySpend = signal(4000);

  get spendYearly(): string {
    return this.fmt$(this.monthlySpend() * 12);
  }

  onSpendSlider(event: Event): void {
    this.monthlySpend.set(+(event.target as HTMLInputElement).value);
  }

  get spendContinueEnabled(): boolean {
    return this.monthlySpend() > 0;
  }

  // ── Duration step ─────────────────────────────────────────────────────────

  yearsToLast = signal(30);
  birthYear = signal<number>(0);

  get durationHint(): string {
    const by = this.birthYear();
    const yrs = this.yearsToLast();
    // Withdrawals begin AFTER the seeded accumulation phase, so the ages
    // must anchor to the retirement date, not today.
    const ytr = this.yearsToRetirement();
    if (by > 0) {
      const currentYear = new Date().getFullYear();
      const retireAge = (currentYear - by) + ytr;
      return `Retiring at ${retireAge}, planning to age ${retireAge + yrs}`;
    }
    return `${yrs} years of retirement income`;
  }

  onDurationSlider(event: Event): void {
    this.yearsToLast.set(+(event.target as HTMLInputElement).value);
  }

  // ── Scenario ──────────────────────────────────────────────────────────────

  scenario = signal<ScenarioType>('expected');

  setScenario(sc: ScenarioType): void {
    this.scenario.set(sc);
  }

  // ── Calculating theater ───────────────────────────────────────────────────

  calcCount = signal(0);
  calcLine = signal(CALC_LINES[0]);
  private _calcTimer: any = null;
  private _calcLineTimer: any = null;
  private destroy$ = new Subject<void>();

  // ── Keyboard avoidance (native-only — mirrors KeyboardAvoidDirective detection) ──

  private _keyboardHeight = 0;
  private _activeFocused: HTMLElement | null = null;
  private _kbShow?: Promise<any>;
  private _kbHide?: Promise<any>;
  private _focusinHandler?: (e: Event) => void;

  private startCalcTheater(durationMs: number): void {
    const isShort = durationMs < 2000;
    this.calcCount.set(0);
    this.calcLine.set(isShort ? 'Re-testing your plan…' : CALC_LINES[0]);
    const t0 = performance.now();
    let lineIdx = 0;

    this._calcTimer = setInterval(() => {
      const elapsed = performance.now() - t0;
      const t = Math.min(1, elapsed / durationMs);
      const eased = 1 - Math.pow(1 - t, 3);
      this.calcCount.set(Math.round(eased * 1000));

      if (!isShort) {
        const newIdx = Math.min(CALC_LINES.length - 1, Math.floor(t * CALC_LINES.length));
        if (newIdx !== lineIdx) {
          lineIdx = newIdx;
          // Soft fade: momentarily zero opacity then swap text
          this._fadeCalcLine(CALC_LINES[newIdx]);
        }
      }

      if (t >= 1) {
        clearInterval(this._calcTimer);
        this._calcTimer = null;
        this._runSimAndShowResults();
      }
    }, 16);
  }

  private _fadeCalcLine(newText: string): void {
    // CSS transition on calc-line handles the visual fade; just swap text
    this._calcLineTimer = setTimeout(() => {
      this.calcLine.set(newText);
    }, 200);
  }

  // ── Simulation ────────────────────────────────────────────────────────────

  showdownResult = signal<ShowdownResult | null>(null);
  maxMonthlySpend = signal(0);
  resultsSaved = false;

  private _buildShowdownParams(): ShowdownParams {
    return {
      portfolioValue: this.includeOutside
        ? this.portfolioValue() + this.k401Balance() + this.rothBalance()
        : this.portfolioValue(),
      monthlySpend: this.monthlySpend(),
      yearsToLast: this.yearsToLast(),
      scenario: this.scenario(),
      monthlyContribution: this.monthlyContrib(),
      yearsToRetirement: this.yearsToRetirement(),
    };
  }

  private _runSimAndShowResults(): void {
    const params = this._buildShowdownParams();
    const result = this.monteCarloService.runShowdown(params);
    this.showdownResult.set(result);
    if (this.isPro) {
      this.maxMonthlySpend.set(
        this.monteCarloService.solveMaxMonthlySpend(params)
      );
    }
    setTimeout(() => {
      this.showStep('results');
      // Save to history after results are shown
      if (!this.resultsSaved) {
        this.resultsSaved = true;
        this._saveToHistory();
      }
    }, 150);
  }

  // ─── Live re-run (Pro panel adjusters and scenario flip) ──────────────────

  private _liveRerun(): void {
    const params = this._buildShowdownParams();
    const result = this.monteCarloService.runShowdown(params);
    this.showdownResult.set(result);
    if (this.isPro) {
      this.maxMonthlySpend.set(
        this.monteCarloService.solveMaxMonthlySpend(params)
      );
    }
    this.cdr.detectChanges();
    this._pulsePanelOutcomes();
  }

  /**
   * Micro-feedback after a live re-run: pulse the numbers that just changed
   * so each stepper tap visibly lands. WAAPI, not CSS transitions — the
   * global reduced-motion rule zeroes those.
   */
  private _pulsePanelOutcomes(): void {
    const host = this.el.nativeElement;
    for (const sel of ['.est-txt']) {
      (host.querySelector(sel) as HTMLElement | null)?.animate(
        [
          { transform: 'scale(1)' },
          { transform: 'scale(1.018)' },
          { transform: 'scale(1)' },
        ],
        { duration: 220, easing: 'ease-out' }
      );
    }
  }

  // ─── Pro panel ────────────────────────────────────────────────────────────

  monthlyContrib = signal(0);
  yearsToRetirement = signal(0);

  get retireDisplayLabel(): string {
    const by = this.birthYear();
    const ytr = this.yearsToRetirement();
    if (by > 0) {
      const currentYear = new Date().getFullYear();
      const retireAge = (currentYear - by) + ytr;
      return `Retire at age ${retireAge}`;
    }
    return `Retire in ${ytr} year${ytr !== 1 ? 's' : ''}`;
  }

  get retireDisplaySub(): string {
    const ytr = this.yearsToRetirement();
    return ytr === 0 ? 'Retiring now' : `${ytr} more working year${ytr !== 1 ? 's' : ''}`;
  }

  stepContrib(delta: number): void {
    const next = Math.max(0, Math.min(10000, this.monthlyContrib() + delta));
    this.monthlyContrib.set(next);
    this._liveRerun();
  }

  stepRetirement(delta: number): void {
    const next = Math.max(0, Math.min(40, this.yearsToRetirement() + delta));
    this.yearsToRetirement.set(next);
    this._liveRerun();
  }

  flipScenario(sc: ScenarioType): void {
    this.scenario.set(sc);
    this._liveRerun();
  }

  // ─── Badge helper ─────────────────────────────────────────────────────────

  badge(pct: number): { cls: string; label: string } {
    if (pct >= 90) return { cls: 'strong',   label: 'Strong' };
    if (pct >= 75) return { cls: 'moderate', label: 'Moderate' };
    return             { cls: 'risk',      label: 'At risk' };
  }

  get strategyDefs() { return STRATEGY_DEFS; }

  successFor(key: string): number {
    const r = this.showdownResult();
    if (!r) return 0;
    return (r as any)[key] ?? 0;
  }

  // ─── Results eyebrow ──────────────────────────────────────────────────────

  get resultsEyebrow(): string {
    const sc = this.scenario();
    // Display names diverge from the internal keys (expected → Moderate etc.).
    const label = this.SCENARIOS.find(s => s.key === sc)?.label ?? sc;
    const pv = this.portfolioValue() + (this.includeOutside ? this.k401Balance() + this.rothBalance() : 0);
    // Disclose the accumulation phase for ALL tiers — the levers that control
    // it sit behind the Pro veil, but the base run always includes it.
    const ytr = this.yearsToRetirement();
    const retire = ytr > 0 ? (ytr == 1 ? 'retire in 1 yr\n' : `retire in ${ytr} yrs\n`) : '';
    return `${retire}${label} · ${this.fmtShort(pv)} · ${this.fmt$(this.monthlySpend())}/mo · ${this.yearsToLast()} yrs`;
  }

  // ─── History ──────────────────────────────────────────────────────────────

  private _saveToHistory(): void {
    if (!this.userId) return;
    const entry: SimulationHistoryEntry = {
      timestamp: Date.now(),
      scenario: this.scenario(),
      totalPortfolioValue: this.portfolioValue() + (this.includeOutside ? this.k401Balance() + this.rothBalance() : 0),
      monthlySpend: this.monthlySpend(),
      yearsToLast: this.yearsToLast(),
      includeOutside: this.includeOutside,
      k401Balance: this.k401Balance(),
      rothBalance: this.rothBalance(),
      monthlyContribution: this.monthlyContrib(),
      yearsToRetirement: this.yearsToRetirement(),
      results: this.showdownResult()!,
    };
    this.historyService.saveEntry(this.userId, entry);
  }

  // ─── Navigation ──────────────────────────────────────────────────────────

  showStep(step: FlowStep): void {
    this._restoreTransform(); // clear keyboard offset before crossfade
    this.currentStep.set(step);
  }

  goNext(): void {
    const cur = this.currentStep();
    if (cur === 'duration') {
      this.showStep('calc');
      const dur = this.isHistoryRerun() ? 900 : 5500;
      this.startCalcTheater(dur);
      return;
    }
    const idx = this.stepOrder.indexOf(cur);
    if (idx >= 0 && idx < this.stepOrder.length - 1) {
      this.showStep(this.stepOrder[idx + 1]);
    }
  }

  goBack(): void {
    const cur = this.currentStep();
    const idx = this.stepOrder.indexOf(cur);
    if (idx > 0) this.showStep(this.stepOrder[idx - 1]);
  }

  get canGoBack(): boolean {
    const cur = this.currentStep();
    return this.stepOrder.indexOf(cur) > 0;
  }

  // ─── Assumptions sheet ───────────────────────────────────────────────────

  nudgeSheetOpen = signal(false);

  /**
   * Market assumptions — the standard McInfoSheetComponent bottom sheet
   * (drag-to-dismiss via SheetDragDismissDirective, same as every other
   * sheet), stacked above this fullscreen modal. Replaced a hand-rolled
   * inline slide-up that could only be closed by tapping (2026-09-14),
   * which also kept a duplicate copy of the assumptions content.
   */
  async openAssumptions(): Promise<void> {
    const modal = await this.modalController.create({
      component: McInfoSheetComponent,
      componentProps: { mode: 'assumptions' },
      ...BOTTOM_SHEET_MODAL_OPTS,
    });
    await modal.present();
  }

  openNudge(): void { this.nudgeSheetOpen.set(true); }
  closeNudge(): void { this.nudgeSheetOpen.set(false); }

  // ─── Share ───────────────────────────────────────────────────────────────

  async shareResults(): Promise<void> {
    try {
      const el = document.querySelector('.flow-results-capture') as HTMLElement;
      if (!el) return;
      // @ts-ignore
      const html2canvasModule = await import('html2canvas') as any;
      const html2canvas = html2canvasModule.default ?? html2canvasModule;
      const canvas = await html2canvas(el, {
        backgroundColor: '#16309b',
        scale: 2,
        useCORS: true,
        logging: false,
      });
      const dataUrl: string = canvas.toDataURL('image/png');
      // @ts-ignore
      const { Share } = await import('@capacitor/share') as any;
      await Share.share({
        title: 'My FRED Retirement Projection',
        text: `My retirement plan: ${this.yearsToLast()} years, ${this.fmt$(this.monthlySpend())}/mo.`,
        url: dataUrl,
        dialogTitle: 'Share your projection',
      });
    } catch (err) {
      console.error('[MCFlow] Share failed:', err);
      this.toastService.showToast('Unable to share. Please try again.', 'danger');
    }
  }

  // ─── Done / Close ─────────────────────────────────────────────────────────

  async closeFlow(): Promise<void> {
    this._cancelTimers();
    await this.modalController.dismiss({ saved: this.resultsSaved });
  }

  // ─── Upgrade CTA (nudge sheet) ────────────────────────────────────────────

  upgradeClicked(): void {
    this.closeNudge();
    // Emit dismiss with upgrade flag — parent can navigate to upgrade screen
    this.modalController.dismiss({ upgrade: true });
  }

  // ─── Lifecycle ────────────────────────────────────────────────────────────

  constructor(
    private monteCarloService: MonteCarloService,
    private historyService: SimulationHistoryService,
    private portfolioService: PortfolioService,
    private authService: AuthService,
    private alpacaService: AlpacaService,
    private toastService: ToastService,
    private modalController: ModalController,
    private cdr: ChangeDetectorRef,
    private el: ElementRef<HTMLElement>,
    private freedomStats: FreedomStatsService,
  ) {}

  ngOnInit(): void {
    this.scenario.set(this.initialScenario);
    this.buildStepOrder();
    this._setupKeyboardAvoid();

    // Pre-fill monthlyContrib with the SAME goal-first, schedule-fallback
    // resolution that computes the freedom date (freedom-stats.service) — the
    // seeded retirement year and the seeded contribution must describe the
    // same plan, or the base run simulates reaching the freedom date with a
    // contribution the date never assumed.
    this.authService.userProgress$.pipe(takeUntil(this.destroy$)).subscribe(progress => {
      const goal = progress?.monthlyInvestment ?? 0;
      this.monthlyContrib.set(
        goal > 0 ? goal : Math.round(this.freedomStats.monthlyActualInvestment()));
    });

    // Seed retirement timing from the live freedom date, so the panel opens
    // describing THIS user's plan instead of "retiring now with $0/mo". Also
    // makes the base showdown include the real accumulation phase. History
    // re-runs keep their saved inputs.
    if (!this.historyEntry) {
      const fy = parseInt(this.freedomStats.stats().freedomYear, 10);
      const yrs = fy - new Date().getFullYear();
      if (!isNaN(fy) && yrs > 0) {
        this.yearsToRetirement.set(Math.min(40, yrs));
      }
    }

    // Fetch birth year for retirement-age display
    this._fetchBirthYear();

    if (this.historyEntry) {
      // Re-run from history: pre-fill all inputs and jump straight to calc
      this._prefillFromHistory(this.historyEntry);
      this.isHistoryRerun.set(true);
      this.resultsSaved = false;
      setTimeout(() => {
        this.showStep('calc');
        this.startCalcTheater(900);
      }, 0);
    } else {
      // New run: restore last-used inputs from localStorage
      this._restoreLastUsed();
      this.showStep('portfolio');
    }

    // Persist outside account balances per-user
    this._restoreOutsideBalances();
  }

  ngOnDestroy(): void {
    this._cancelTimers();
    this.destroy$.next();
    this.destroy$.complete();
  }

  private _cancelTimers(): void {
    if (this._calcTimer) { clearInterval(this._calcTimer); this._calcTimer = null; }
    if (this._calcLineTimer) { clearTimeout(this._calcLineTimer); this._calcLineTimer = null; }
    this._teardownKeyboardAvoid();
  }

  // ── Keyboard avoidance ────────────────────────────────────────────────────────
  // Uses the same Capacitor Keyboard detection source as KeyboardAvoidDirective.
  // On web the Keyboard plugin never fires, so this is a no-op (native-only).

  private _setupKeyboardAvoid(): void {
    // Native only: on web the Keyboard plugin is "not implemented" and
    // addListener returns a rejected promise that Angular logs as an ERROR.
    if (!Capacitor.isNativePlatform()) return;

    // Track which input inside this modal has focus
    this._focusinHandler = (e: Event) => {
      const el = e.target as HTMLElement;
      if (el?.matches('input, select, textarea')) {
        this._activeFocused = el;
      }
    };
    this.el.nativeElement.addEventListener('focusin', this._focusinHandler);

    // Mirror the directive: listen on the Capacitor Keyboard plugin events
    this._kbShow = Keyboard.addListener('keyboardWillShow', (info: { keyboardHeight: number }) => {
      this._keyboardHeight = info.keyboardHeight;
      // Wait 300ms for the keyboard animation to settle (same as directive)
      setTimeout(() => this._applyOcclusion(), 300);
    });

    this._kbHide = Keyboard.addListener('keyboardWillHide', () => {
      this._keyboardHeight = 0;
      this._restoreTransform();
    });
  }

  private _applyOcclusion(): void {
    if (!this._keyboardHeight || !this._activeFocused) return;
    const rect = this._activeFocused.getBoundingClientRect();
    const visibleBottom = window.innerHeight - this._keyboardHeight - 16; // 16px margin
    const overshoot = rect.bottom - visibleBottom;
    if (overshoot > 0) {
      const fscreen = this.el.nativeElement.querySelector<HTMLElement>('.fscreen.on');
      if (fscreen) fscreen.style.transform = `translateY(-${Math.round(overshoot)}px)`;
    }
  }

  private _restoreTransform(): void {
    this.el.nativeElement.querySelectorAll<HTMLElement>('.fscreen').forEach(f => {
      f.style.transform = '';
    });
    this._activeFocused = null;
  }

  private _teardownKeyboardAvoid(): void {
    if (this._focusinHandler) {
      this.el.nativeElement.removeEventListener('focusin', this._focusinHandler);
      this._focusinHandler = undefined;
    }
    this._kbShow?.then(h => h.remove());
    this._kbHide?.then(h => h.remove());
    this._kbShow = undefined;
    this._kbHide = undefined;
  }

  private _fetchBirthYear(): void {
    this.alpacaService.getKycData().subscribe({
      next: (kyc: any) => {
        const dob: string | undefined = kyc?.identity?.date_of_birth;
        if (dob) {
          const parsed = parseInt(dob.substring(0, 4), 10);
          if (!isNaN(parsed)) this.birthYear.set(parsed);
        }
      },
      error: () => { /* birth year unavailable — fall back to "Retire in N years" display */ }
    });
  }

  private _prefillFromHistory(entry: SimulationHistoryEntry): void {
    this.scenario.set(entry.scenario);
    this.monthlySpend.set(entry.monthlySpend);
    this.yearsToLast.set(entry.yearsToLast);
    this.monthlyContrib.set(entry.monthlyContribution);
    this.yearsToRetirement.set(entry.yearsToRetirement);
    // Reconstruct the portfolio value
    if (entry.includeOutside) {
      const base = entry.totalPortfolioValue - entry.k401Balance - entry.rothBalance;
      this.portfolioValue.set(Math.max(0, base));
      this.k401Balance.set(entry.k401Balance);
      this.rothBalance.set(entry.rothBalance);
    } else {
      this.portfolioValue.set(entry.totalPortfolioValue);
    }
    this.syncPortfolioSlider();
  }

  // ─── Last-used input persistence ─────────────────────────────────────────

  private get _lastUsedKey(): string {
    return `fred.mcLastUsed.${this.userId ?? 'anon'}`;
  }

  private _restoreLastUsed(): void {
    try {
      const raw = localStorage.getItem(this._lastUsedKey);
      if (!raw) return;
      const d = JSON.parse(raw);
      if (d.portfolioValue > 0) {
        this.portfolioValue.set(d.portfolioValue);
        this.syncPortfolioSlider();
      }
      if (d.monthlySpend > 0) this.monthlySpend.set(d.monthlySpend);
      if (d.yearsToLast > 0) this.yearsToLast.set(d.yearsToLast);
    } catch { /* ignore corrupt */ }
  }

  private _persistLastUsed(): void {
    try {
      localStorage.setItem(this._lastUsedKey, JSON.stringify({
        portfolioValue: this.portfolioValue(),
        monthlySpend: this.monthlySpend(),
        yearsToLast: this.yearsToLast(),
      }));
    } catch { /* no-op */ }
  }

  // ─── Outside account balance persistence ─────────────────────────────────

  private get _outsideKey(): string {
    return `fred.mcOutside.${this.userId ?? 'anon'}`;
  }

  private _restoreOutsideBalances(): void {
    try {
      const raw = localStorage.getItem(this._outsideKey);
      if (!raw) return;
      const d = JSON.parse(raw);
      if (d.k401 != null) {
        this.k401Balance.set(d.k401);
        this.k401Raw = d.k401 > 0 ? d.k401.toLocaleString('en-US') : '';
      }
      if (d.roth != null) {
        this.rothBalance.set(d.roth);
        this.rothRaw = d.roth > 0 ? d.roth.toLocaleString('en-US') : '';
      }
    } catch { /* ignore */ }
  }

  saveOutsideBalances(): void {
    try {
      localStorage.setItem(this._outsideKey, JSON.stringify({
        k401: this.k401Balance(),
        roth: this.rothBalance(),
      }));
    } catch { /* no-op */ }
  }

  // ─── Date helpers ────────────────────────────────────────────────────────

  get currentYear(): number {
    return new Date().getFullYear();
  }

  /** Current age derived from birth year; 0 if unavailable. */
  get currentAge(): number {
    const by = this.birthYear();
    return by > 0 ? this.currentYear - by : 0;
  }

  /** The age at which the user would retire given yearsToRetirement. */
  get retireAge(): number {
    return this.currentAge + this.yearsToRetirement();
  }

  // ─── Formatting helpers ───────────────────────────────────────────────────

  fmt$(n: number): string {
    return '$' + Math.round(n).toLocaleString('en-US');
  }

  fmtShort(n: number): string {
    if (n >= 1_000_000) return '$' + (n / 1_000_000).toFixed(1).replace(/\.0$/, '') + 'M';
    return '$' + Math.round(n / 1000) + 'k';
  }

  // ─── Template helpers ─────────────────────────────────────────────────────

  readonly SCENARIOS: { key: ScenarioType; label: string }[] = [
    { key: 'conservative', label: 'Conservative (8%)' },
    { key: 'expected',     label: 'Moderate (10%)' },
    { key: 'aggressive',   label: 'Optimistic (12%)' },
  ];

  isStep(step: FlowStep): boolean {
    return this.currentStep() === step;
  }

  isInputStep(): boolean {
    return this.stepOrder.includes(this.currentStep());
  }

  dotsArray(): number[] {
    return Array.from({ length: this.stepCount });
  }

  portfolioSliderStyle(): string {
    const pct = this.portfolioSliderPct;
    return `linear-gradient(to right, #fff 0%, #fff ${pct}%, rgba(255,255,255,.22) ${pct}%)`;
  }

  spendSliderStyle(): string {
    const v = this.monthlySpend();
    const pct = ((v - 500) / (20000 - 500)) * 100;
    return `linear-gradient(to right, #fff 0%, #fff ${pct}%, rgba(255,255,255,.22) ${pct}%)`;
  }

  durationSliderStyle(): string {
    const v = this.yearsToLast();
    const pct = ((v - 10) / (70 - 10)) * 100;
    return `linear-gradient(to right, #fff 0%, #fff ${pct}%, rgba(255,255,255,.22) ${pct}%)`;
  }

  onContinueClick(): void {
    this._persistLastUsed();
    if (this.includeOutside && this.currentStep() === 'outside') {
      this.saveOutsideBalances();
    }
    this.goNext();
  }
}
