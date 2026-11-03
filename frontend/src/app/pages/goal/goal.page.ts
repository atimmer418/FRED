import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { IonContent, IonHeader, ModalController, NavController } from '@ionic/angular/standalone';
import { catchError, filter, firstValueFrom, of, take, timeout } from 'rxjs';

import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';
import { AuthService } from '../../services/auth.service';
import { GoalsService } from '../../services/goals.service';
import { Goal } from '../../models/goal.model';
import { McInfoSheetComponent } from '../../components/mc-info-sheet/mc-info-sheet.component';
import { BOTTOM_SHEET_MODAL_OPTS } from '../../utils/bottom-sheet.utils';
import {
  CUSTOM_ICON_OPTIONS, GoalForm, MAX_KIDS, defaultForm, formFromGoal, isGoalType, newKidRow,
  validateForm, KidFormRow,
} from './goal-form.model';

type NumericField = 'price' | 'targetYear' | 'budgetPerTrip' | 'monthlyExpenses' | 'targetAmount';

const MONTH_NAMES = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

/**
 * Full page for creating (`/goals/new/:type`) or editing (`/goals/:id`) a goal.
 * Step 1 = setup inputs. Step 2 (plan view and saving) is added in Task 11.
 */
@Component({
  selector: 'app-goal',
  standalone: true,
  imports: [CommonModule, FormsModule, IonContent, IonHeader, KeyboardAvoidDirective],
  templateUrl: './goal.page.html',
  styleUrls: ['./goal.page.scss'],
})
export class GoalPage implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly navCtrl = inject(NavController);
  private readonly modalCtrl = inject(ModalController);
  private readonly goalsService = inject(GoalsService);
  private readonly authService = inject(AuthService);

  readonly iconOptions = CUSTOM_ICON_OPTIONS;
  readonly maxKids = MAX_KIDS;
  readonly monthNames = MONTH_NAMES;
  readonly tripChoices: ReadonlyArray<1 | 2 | 3 | 4> = [1, 2, 3, 4];
  readonly monthChoices: ReadonlyArray<3 | 6> = [3, 6];

  readonly mode = signal<'setup' | 'edit'>('setup');
  readonly step = signal<1 | 2>(1);
  readonly form = signal<GoalForm>(defaultForm('CUSTOM', new Date()));
  readonly errors = signal<Record<string, string>>({});
  /** The saved goal when editing, else null. Task 11 reads this for save paths. */
  readonly existing = signal<Goal | null>(null);
  /** True once the route resolved to a valid form and no tier gate applies. */
  readonly ready = signal(false);

  async ngOnInit(): Promise<void> {
    const params = this.route.snapshot.paramMap;
    const typeParam = params.get('type');
    const idParam = params.get('id');

    if (typeParam !== null) {
      if (!isGoalType(typeParam)) {
        this.goBack();
        return;
      }
      this.mode.set('setup');
      this.form.set(defaultForm(typeParam, new Date()));
      if (await this.isTierGated()) {
        await this.openMultiGoalNudge();
        this.goBack();
        return;
      }
      this.ready.set(true);
      return;
    }

    const id = Number(idParam);
    if (idParam === null || !Number.isInteger(id) || id <= 0) {
      this.goBack();
      return;
    }
    let goal = this.goalsService.goals().find(g => g.id === id);
    if (!goal) {
      await firstValueFrom(this.goalsService.load());
      goal = this.goalsService.goals().find(g => g.id === id);
    }
    if (!goal) {
      this.goBack();
      return;
    }
    this.mode.set('edit');
    this.existing.set(goal);
    this.form.set(formFromGoal(goal));
    this.ready.set(true);
  }

  get title(): string {
    return this.mode() === 'setup' ? 'New goal' : (this.existing()?.name || 'Goal');
  }

  goBack(): void {
    this.navCtrl.navigateBack('/my-profile');
  }

  /** Validates, then moves to the plan view. Stays on step 1 with errors if invalid. */
  showPlan(): void {
    const errs = validateForm(this.form(), new Date());
    this.errors.set(errs);
    if (Object.keys(errs).length > 0) return;
    this.step.set(2);
  }

  backToInputs(): void {
    this.step.set(1);
  }

  // ── Form updates ────────────────────────────────────────────────────────────

  patch(partial: Partial<GoalForm>): void {
    this.form.update(f => ({ ...f, ...partial }));
    this.revalidateIfShowing();
  }

  patchKids(partial: Partial<GoalForm['kids']>): void {
    this.form.update(f => ({ ...f, kids: { ...f.kids, ...partial } }));
    this.revalidateIfShowing();
  }

  patchKid(index: number, partial: Partial<KidFormRow>): void {
    this.form.update(f => ({
      ...f,
      kids: { ...f.kids, kids: f.kids.kids.map((k, i) => (i === index ? { ...k, ...partial } : k)) },
    }));
    this.revalidateIfShowing();
  }

  onNumber(field: NumericField, event: Event): void {
    const maxDigits = field === 'targetYear' ? 4 : 9;
    this.patch({ [field]: this.sanitize(event, maxDigits) } as Partial<GoalForm>);
  }

  onKidsTarget(field: 'brokerageTarget' | 'plan529Target', event: Event): void {
    this.patchKids({ [field]: this.sanitize(event, 9) } as Partial<GoalForm['kids']>);
  }

  onKidYear(index: number, event: Event): void {
    this.patchKid(index, { birthYear: this.sanitize(event, 4) });
  }

  setKidMonth(index: number, month: number | null): void {
    this.patchKid(index, { birthMonth: month });
  }

  toggleUndecided(index: number): void {
    const row = this.form().kids.kids[index];
    if (!row) return;
    const undecided = !row.undecided;
    this.patchKid(index, undecided ? { undecided, birthYear: null, birthMonth: null } : { undecided });
  }

  addKid(): void {
    const kids = this.form().kids.kids;
    if (kids.length >= MAX_KIDS) return;
    this.patchKids({ kids: [...kids, newKidRow(kids.length)] });
  }

  removeKid(index: number): void {
    const kids = this.form().kids.kids;
    if (kids.length <= 1 || index < 0 || index >= kids.length) return;
    this.patchKids({ kids: kids.filter((_, i) => i !== index) });
  }

  setIcon(icon: GoalForm['icon']): void {
    this.patch({ icon });
  }

  /** 10 / 20 chips. Clears the full-price flag. */
  setDownPaymentPct(pct: number): void {
    this.patch({ downPaymentPct: pct, fullPrice: false });
  }

  setFullPrice(): void {
    this.patch({ fullPrice: true });
  }

  setTrips(n: 1 | 2 | 3 | 4): void {
    this.patch({ tripsPerYear: n });
  }

  setMonths(n: 3 | 6): void {
    this.patch({ months: n });
  }

  fmtNumber(value: number | null): string {
    return value == null ? '' : String(value);
  }

  // ── Internals ───────────────────────────────────────────────────────────────

  /** Digits only, capped, input rewritten in place. Empty input yields null. */
  private sanitize(event: Event, maxDigits: number): number | null {
    const input = event.target as HTMLInputElement;
    const digits = (input.value || '').replace(/\D+/g, '').slice(0, maxDigits);
    if (input.value !== digits) input.value = digits;
    return digits ? parseInt(digits, 10) : null;
  }

  /** Once the user has hit "Show my plan" with errors, keep messages live as they type. */
  private revalidateIfShowing(): void {
    if (Object.keys(this.errors()).length > 0) {
      this.errors.set(validateForm(this.form(), new Date()));
    }
  }

  /** Setup mode: one active goal already and not Plus/Pro. */
  private async isTierGated(): Promise<boolean> {
    // Cached by the service's TTL, so this is cheap when my-profile already loaded goals.
    try {
      await firstValueFrom(this.goalsService.load());
    } catch {
      // Fall through with whatever the signal holds.
    }
    const active = this.goalsService.goals().filter(g => !g.completed).length;
    if (active < 1) return false;
    const progress = await firstValueFrom(
      this.authService.userProgress$.pipe(
        filter(p => !!p),
        take(1),
        timeout(3000),
        catchError(() => of(null)),
      ),
    );
    const tier = progress?.selectedTier;
    return !(tier === 'plus' || tier === 'pro');
  }

  private async openMultiGoalNudge(): Promise<void> {
    const modal = await this.modalCtrl.create({
      component: McInfoSheetComponent,
      componentProps: {
        mode: 'nudge',
        nudgeTitle: 'Plan more than one goal with Piggy Plus',
        nudgeBody: 'Kids, a house, a car, trips. Each gets its own plan, side by side.',
        nudgeCtaLabel: 'Upgrade to Piggy Plus',
        targetTier: 'plus',
      },
      ...BOTTOM_SHEET_MODAL_OPTS,
    });
    await modal.present();
    await modal.onDidDismiss();
  }
}
