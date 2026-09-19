import { Component, ElementRef, Input, OnInit, computed, effect, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ModalController } from '@ionic/angular/standalone';
import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';
import { SheetDragDismissDirective } from '../../directives/sheet-drag-dismiss.directive';
import { FreedomStatsService } from '../../services/freedom-stats.service';
import { RateAssumptionsService } from '../../services/rate-assumptions.service';
import { monthsToTarget } from '../../utils/freedom-projection.utils';

/** Dismiss payload for role 'saved'. */
export interface FreedomSheetResult {
  income: number;
  invest: number;
  annualReturn: number;
  withdrawalRate: number;
}

/**
 * Freedom Plan detail sheet — the full story behind the freedom date.
 *
 * Presented via ModalController with BOTTOM_SHEET_MODAL_OPTS from the
 * my-profile Freedom Plan card and the Freedom Date goal row. Everything is a
 * live what-if: edits to the plan tiles or the assumption steppers re-derive
 * the hero date instantly; nothing persists until "Save plan" dismisses with
 * role 'saved' (my-profile then persists income/invest via the existing
 * saveChanges flow and rates via RateAssumptionsService).
 */
@Component({
  selector: 'app-freedom-detail-sheet',
  templateUrl: './freedom-detail-sheet.component.html',
  styleUrls: ['./freedom-detail-sheet.component.scss'],
  standalone: true,
  imports: [CommonModule],
  // The sheet's host is its scroller (global.scss .ion-page override); the
  // class makes the sheet drag gesture defer to scrolling, and the directive
  // lifts the sheet above the keyboard while typing.
  host: { class: 'ion-content-scroll-host' },
  hostDirectives: [KeyboardAvoidDirective, SheetDragDismissDirective],
})
export class FreedomDetailSheetComponent implements OnInit {
  /** Seed values from my-profile (monthly dollars). */
  @Input() income = 0;
  @Input() invest = 0;
  @Input() equity = 0;

  private modalController = inject(ModalController);
  private rates = inject(RateAssumptionsService);
  private freedomStats = inject(FreedomStatsService);
  private keyboardAvoid = inject(KeyboardAvoidDirective);

  // ── Live what-if state ─────────────────────────────────────────────────────
  readonly incomeSig = signal(0);
  readonly investSig = signal(0);
  readonly annualReturn = signal(0.10);
  /** Fixed at the industry-standard 4% rule (customization removed 2026-09-12). */
  readonly withdrawalRate = signal(0.04);

  ngOnInit(): void {
    this.incomeSig.set(this.income || 0);
    this.investSig.set(this.invest || 0);
    // Snap any legacy free-form return (steppers allowed e.g. 9.5%) to the
    // nearest scenario preset — the chips are the only control now.
    const stored = this.rates.annualReturn();
    const nearest = this.RETURN_SCENARIOS.reduce((best, s) =>
      Math.abs(s.rate - stored) < Math.abs(best.rate - stored) ? s : best);
    this.annualReturn.set(nearest.rate);
    // Withdrawal stays 4% regardless of any legacy stored customization.
    this.keyboardAvoid.wrapperSelector = '.fd-tile';
  }

  // ── Derived projection ─────────────────────────────────────────────────────
  readonly portfolioGoal = computed(() => {
    const income = this.incomeSig();
    return income > 0 ? (income * 12) / this.withdrawalRate() : 0;
  });

  private readonly months = computed(() => {
    const goal = this.portfolioGoal();
    if (goal <= 0) return null;
    if (this.equity <= 0 && this.investSig() <= 0) return null;
    return monthsToTarget(this.equity, this.investSig(), goal, this.annualReturn());
  });

  readonly freedomYear = computed(() => {
    const m = this.months();
    if (m === null) return null;
    const currentYear = new Date().getFullYear();
    return Math.min(currentYear + Math.ceil(m / 12), currentYear + 100);
  });

  readonly freedomAge = computed(() => {
    const year = this.freedomYear();
    const birthYear = this.freedomStats.birthYear();
    if (year === null || !birthYear || birthYear <= 0) return null;
    const age = year - birthYear;
    return age > 0 ? age : null;
  });

  /**
   * Months-granular distance label. The hero year alone hides small
   * adjustments (a stepper tap often moves the projection by a few months,
   * which rounds away in the year) — this line moves on every change.
   */
  readonly timeAwayLabel = computed(() => {
    const m = this.months();
    if (m === null) return null;
    const total = Math.max(0, Math.round(m));
    if (total === 0) return 'you’re already there';
    const yr = Math.floor(total / 12);
    const mo = total % 12;
    if (yr === 0) return `${mo} mo away`;
    return mo === 0 ? `${yr} yr away` : `${yr} yr ${mo} mo away`;
  });

  readonly progressPct = computed(() => {
    const goal = this.portfolioGoal();
    return goal > 0 ? Math.min(100, Math.round((this.equity / goal) * 100)) : 0;
  });

  /** Months sooner if the user invested $100/mo more — the nudge insight. */
  readonly plus100Months = computed(() => {
    const m = this.months();
    const goal = this.portfolioGoal();
    if (m === null || goal <= 0) return null;
    const faster = monthsToTarget(this.equity, this.investSig() + 100, goal, this.annualReturn());
    const delta = Math.round(m - faster);
    return delta > 0 ? delta : null;
  });

  readonly isDefaultRates = computed(() =>
    this.annualReturn() === 0.10 && this.withdrawalRate() === 0.04);

  // Micro-feedback: pulse the hero whenever the projection moves so every
  // stepper tap and keystroke visibly registers. WAAPI (element.animate), not
  // CSS transitions — the global reduced-motion rule zeroes those.
  private elementRef = inject(ElementRef<HTMLElement>);
  private _pulseArmed = false;
  private _pulseFx = effect(() => {
    this.months();
    this.portfolioGoal();
    if (!this._pulseArmed) { this._pulseArmed = true; return; }
    const hero = this.elementRef.nativeElement.querySelector('.fd-hero-year') as HTMLElement | null;
    hero?.animate(
      [
        { transform: 'scale(1)' },
        { transform: 'scale(1.018)' },
        { transform: 'scale(1)' },
      ],
      { duration: 220, easing: 'ease-out' }
    );
  });

  // ── Formatting ─────────────────────────────────────────────────────────────
  pct(fraction: number): string {
    // Two decimals so quarter-point steps (4.25%) display exactly what the
    // math uses; trailing zeros trimmed (10.00 → "10", 4.50 → "4.5").
    return (fraction * 100).toFixed(2).replace(/\.?0+$/, '');
  }

  // ── Tile inputs (same sanitizing discipline as the my-profile tiles) ──────
  onIncomeInput(event: Event): void {
    this.incomeSig.set(this._sanitize(event));
  }

  onInvestInput(event: Event): void {
    this.investSig.set(this._sanitize(event));
  }

  /** Digits only, capped at 5 digits, input element rewritten in place. */
  private _sanitize(event: Event): number {
    const input = event.target as HTMLInputElement;
    const digits = (input.value || '').replace(/\D+/g, '').slice(0, 5);
    if (input.value !== digits) input.value = digits;
    return digits ? parseInt(digits, 10) : 0;
  }

  // ── Assumption controls ────────────────────────────────────────────────────

  /**
   * Return-rate presets, mirroring the Monte Carlo scenarios exactly (same
   * labels, same rates) so the freedom date and tab2's simulations always
   * speak the same language. Three choices by design: free-form return
   * customization invited over-tuning.
   */
  readonly RETURN_SCENARIOS: { label: string; rate: number }[] = [
    { label: 'Conservative (8%)', rate: 0.08 },
    { label: 'Moderate (10%)',    rate: 0.10 },
    { label: 'Optimistic (12%)',  rate: 0.12 },
  ];

  selectReturn(rate: number): void {
    this.annualReturn.set(rate);
  }

  resetRates(): void {
    this.annualReturn.set(0.10);
  }

  /** Save requires a complete plan — zeroed tiles can't dismiss as 'saved'. */
  readonly canSave = computed(() => this.incomeSig() > 0 && this.investSig() > 0);

  // ── Dismissal ──────────────────────────────────────────────────────────────
  save(): void {
    if (!this.canSave()) return;
    const result: FreedomSheetResult = {
      income: this.incomeSig(),
      invest: this.investSig(),
      annualReturn: this.annualReturn(),
      withdrawalRate: this.withdrawalRate(),
    };
    this.modalController.dismiss(result, 'saved');
  }

  close(): void {
    this.modalController.dismiss();
  }
}
