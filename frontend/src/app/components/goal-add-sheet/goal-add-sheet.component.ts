import { Component, Input, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ModalController } from '@ionic/angular/standalone';
import { Goal, GoalIcon } from '../../services/goals.service';
import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';
import { SheetDragDismissDirective } from '../../directives/sheet-drag-dismiss.directive';
import { InvestmentFrequencyUtils } from '../../utils/investment-frequency.utils';

/**
 * Add-a-goal bottom sheet — FRED-216.
 *
 * Presented via ModalController with cssClass 'mc-bottom-sheet-modal'.
 * When the user taps "Track this goal", dismisses with role 'saved' and the
 * new goal data (without id — GoalsService assigns the id on save).
 *
 * When in edit mode (editGoal input provided), pre-populates fields and
 * dismisses with role 'updated' + the full updated Goal (with id).
 *
 * Keyboard avoidance: the modal sits above the main ion-content stack, so
 * the global focusin handler in app.component.ts (web fallback) covers it.
 * The mc-bottom-sheet-modal CSS class sets position:fixed bottom sheet chrome.
 */
@Component({
  selector: 'app-goal-add-sheet',
  templateUrl: './goal-add-sheet.component.html',
  styleUrls: ['./goal-add-sheet.component.scss'],
  standalone: true,
  imports: [CommonModule, FormsModule],
  // This root is the sheet's scroller (global.scss .ion-page override).
  // SheetDragDismissDirective provides drag-to-dismiss and defers to
  // scrolling (drag arms only at scrollTop 0).
  host: { class: 'ion-content-scroll-host' },
  // Same keyboard avoidance as sell-withdraw pages, applied to the sheet's
  // own scroller: pads for the overlaid keyboard + scrolls the focused field
  // above it.
  hostDirectives: [KeyboardAvoidDirective, SheetDragDismissDirective],
})
export class GoalAddSheetComponent implements OnInit {
  /** Provide to pre-populate fields for editing an existing goal. */
  @Input() editGoal: Goal | null = null;

  /**
   * The user's investment-schedule frequency (weekly/biweekly/semi_monthly/
   * monthly) — FRED's invest-per-paycheck model makes this the pay frequency.
   * Drives the per-paycheck → monthly estimate. Empty ⇒ treated as monthly.
   */
  @Input() payFrequency: string = '';

  // ── Fields ─────────────────────────────────────────────────────────────────
  goalName: string = '';
  selectedIcon: GoalIcon = 'flag';
  targetAmount: number | null = null;
  targetYear: number | null = null;
  startingValue: number | null = 0;
  perPaycheckOutsideFRED: number | null = null;

  // ── Icon palette ───────────────────────────────────────────────────────────
  readonly iconOptions: Array<{ icon: GoalIcon; label: string }> = [
    { icon: 'home',                label: 'Home'        },
    { icon: 'health_and_safety',   label: 'Health'      },
    { icon: 'flag',                label: 'Goal'        },
    { icon: 'balance',             label: 'Balance'     },
    { icon: 'school',              label: 'Education'   },
    { icon: 'directions_car',      label: 'Vehicle'     },
    { icon: 'savings',             label: 'Savings'     },
  ];

  // ── Icon-driven placeholders ───────────────────────────────────────────────
  // Example values swap with the selected icon so the empty form reads like a
  // real goal of that type. Record<GoalIcon, …> so a new icon can't ship
  // without example copy.
  private static readonly ICON_PLACEHOLDERS: Record<
    GoalIcon,
    { name: string; amount: string; year: string; perPaycheck: string }
  > = {
    home:                { name: 'House down payment', amount: '60,000', year: '2030', perPaycheck: '275' },
    health_and_safety:   { name: 'Emergency fund',     amount: '15,000', year: '2027', perPaycheck: '125' },
    flag:                { name: 'Dream vacation',     amount: '8,000',  year: '2027', perPaycheck: '100' },
    balance:             { name: 'Pay off debt',       amount: '12,000', year: '2028', perPaycheck: '175' },
    school:              { name: 'College fund',       amount: '40,000', year: '2035', perPaycheck: '150' },
    directions_car:      { name: 'New car',            amount: '25,000', year: '2029', perPaycheck: '200' },
    savings:             { name: 'Rainy day fund',     amount: '20,000', year: '2031', perPaycheck: '125' },
  };

  get placeholders(): { name: string; amount: string; year: string; perPaycheck: string } {
    return GoalAddSheetComponent.ICON_PLACEHOLDERS[this.selectedIcon]
      ?? GoalAddSheetComponent.ICON_PLACEHOLDERS['flag'];
  }

  /** Live monthly estimate from the per-paycheck amount + pay frequency. */
  get monthlyEstimate(): number {
    return InvestmentFrequencyUtils.toMonthlyEquivalent(
      this.perPaycheckOutsideFRED || 0, this.payFrequency);
  }

  get frequencyLabel(): string {
    switch ((this.payFrequency || '').toLowerCase()) {
      case 'weekly':       return 'weekly';
      case 'biweekly':     return 'biweekly';
      case 'semi_monthly':
      case 'semimonthly':  return 'twice-a-month';
      default:             return 'monthly';
    }
  }

  // ── Validation ─────────────────────────────────────────────────────────────
  nameError: string = '';
  amountError: string = '';
  yearError: string = '';
  startingError: string = '';
  perPaycheckError: string = '';

  private keyboardAvoid = inject(KeyboardAvoidDirective);

  constructor(private modalController: ModalController) {}

  ngOnInit(): void {
    // Bring the whole labeled field (incl. validation text) above the keyboard.
    this.keyboardAvoid.wrapperSelector = '.gs-field';
    if (this.editGoal) {
      this.goalName = this.editGoal.name;
      this.selectedIcon = this.editGoal.icon;
      this.targetAmount = this.editGoal.targetAmount;
      this.targetYear = this.editGoal.targetYear;
      this.startingValue = this.editGoal.startingValue;
      this.perPaycheckOutsideFRED = this.editGoal.perPaycheckOutsideFRED;
    }
  }

  get isEditing(): boolean {
    return this.editGoal != null;
  }

  selectIcon(icon: GoalIcon): void {
    this.selectedIcon = icon;
  }

  close(): void {
    this.modalController.dismiss();
  }

  save(): void {
    if (!this._validate()) return;

    const payload: Omit<Goal, 'id'> = {
      name: this.goalName.trim(),
      icon: this.selectedIcon,
      targetAmount: this.targetAmount!,
      targetYear: this.targetYear!,
      startingValue: this.startingValue ?? 0,
      perPaycheckOutsideFRED: this.perPaycheckOutsideFRED!,
      completed: this.editGoal?.completed ?? false,
    };

    if (this.isEditing) {
      this.modalController.dismiss(
        { ...payload, id: this.editGoal!.id } as Goal,
        'updated'
      );
    } else {
      this.modalController.dismiss(payload, 'saved');
    }
  }

  /** Toggle completed on the goal being edited (saves current field edits too). */
  markCompleted(): void {
    // allowPastYear: completing a goal is exactly what happens once its target
    // year arrives/passes — the add-flow year rule must not block it.
    if (!this.editGoal || !this._validate({ allowPastYear: true })) return;
    const updated: Goal = {
      id: this.editGoal.id,
      name: this.goalName.trim(),
      icon: this.selectedIcon,
      targetAmount: this.targetAmount!,
      targetYear: this.targetYear!,
      startingValue: this.startingValue ?? 0,
      perPaycheckOutsideFRED: this.perPaycheckOutsideFRED!,
      completed: !this.editGoal.completed,
    };
    this.modalController.dismiss(updated, 'completed');
  }

  /** Remove the goal being edited. */
  remove(): void {
    if (!this.editGoal) return;
    this.modalController.dismiss(this.editGoal.id, 'deleted');
  }

  // ── Private ────────────────────────────────────────────────────────────────

  private _validate(opts: { allowPastYear?: boolean } = {}): boolean {
    this.nameError = '';
    this.amountError = '';
    this.yearError = '';
    this.startingError = '';
    this.perPaycheckError = '';

    let valid = true;
    const currentYear = new Date().getFullYear();

    if (!this.goalName || !this.goalName.trim()) {
      this.nameError = 'Enter a goal name.';
      valid = false;
    }

    if (!this.targetAmount || this.targetAmount <= 0) {
      this.amountError = 'Enter a target amount greater than $0.';
      valid = false;
    }

    if (!this.targetYear || (!opts.allowPastYear && this.targetYear < currentYear)) {
      this.yearError = `Enter a year from ${currentYear} onward.`;
      valid = false;
    }

    if (this.startingValue == null || this.startingValue < 0) {
      this.startingError = 'Enter a starting value of $0 or more.';
      valid = false;
    }

    if (this.perPaycheckOutsideFRED == null || this.perPaycheckOutsideFRED < 0) {
      this.perPaycheckError = 'Enter a per-paycheck saving amount (can be $0).';
      valid = false;
    }

    // $0/paycheck is allowed — projection renders "late" (lump-sum tracking).

    return valid;
  }
}
