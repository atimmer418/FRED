import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { IonContent, IonHeader, ModalController, NavController } from '@ionic/angular/standalone';
import { Observable, catchError, filter, firstValueFrom, of, take, timeout } from 'rxjs';

import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';
import { AuthService } from '../../services/auth.service';
import { GoalsService } from '../../services/goals.service';
import { ToastService } from '../../services/toast.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { httpStatusOf } from '../../services/analytics/analytics-error.utils';
import { toAmountBand, toCountBand, toPercentBand, toYearsBand } from '../../services/analytics/analytics-bands';
import { GoalAction } from '../../services/analytics/analytics-events';
import {
  DEFAULT_ANNUAL_RETURN, RETURN_SCENARIOS, RateAssumptionsService,
} from '../../services/rate-assumptions.service';
import {
  EmergencyFundConfig, Goal, GoalConfig, GoalType, GoalWrite, HouseCarConfig, KidsConfig, TargetConfig,
  VacationConfig,
} from '../../models/goal.model';
import { kidDeadline, simpleTarget } from '../../utils/goal-plan.utils';
import { McInfoSheetComponent } from '../../components/mc-info-sheet/mc-info-sheet.component';
import { GoalRemoveConfirmSheetComponent } from '../../components/goal-remove-confirm-sheet/goal-remove-confirm-sheet.component';
import { BOTTOM_SHEET_MODAL_OPTS } from '../../utils/bottom-sheet.utils';
import {
  CUSTOM_ICON_OPTIONS, GoalForm, MAX_KIDS, defaultForm, formFromGoal, formToConfig, isGoalType, newKidRow,
  planInputsChanged, validateForm, KidFormRow,
} from './goal-form.model';
import {
  GoalPlanInput, GoalPlanView, KidAccountKey, KidPlanRow, buildGoalPlanView, fmtCompact, fmtMoney, fmtMonthYear,
} from './goal-plan.view-model';

/** Which balance a balance tile edits. */
export type BalancePath = 'current' | 'vacation' | { kid: number; account: KidAccountKey };

type NumericField = 'price' | 'targetYear' | 'budgetPerTrip' | 'monthlyExpenses' | 'targetAmount';

const MONTH_NAMES = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

/**
 * Full page for creating (`/goals/new/:type`) or editing (`/goals/:id`) a goal.
 * Step 1 = setup inputs. Step 2 = the plan view with its save paths.
 * Edit mode opens on step 2; "Edit" on the inputs row goes back to step 1.
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
  private readonly router = inject(Router);
  private readonly toastService = inject(ToastService);
  private readonly analytics = inject(AnalyticsService);
  private readonly rates = inject(RateAssumptionsService);

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

  // ── Plan view state ────────────────────────────────────────────────────────

  readonly returnScenarios = RETURN_SCENARIOS;
  readonly fmtMoney = fmtMoney;
  readonly fmtCompact = fmtCompact;

  /** The chip the user is looking at. Written to the shared rate only in save(). */
  readonly selectedRate = signal(this.initialRate());
  /** Pay frequency of the user's FRED schedule, '' when none (hides the per-paycheck line). */
  readonly frequency = signal('');
  readonly saving = signal(false);
  readonly kidDetailsOpen = signal<Record<KidAccountKey, boolean>>({ plan529: false, brokerage: false });
  /**
   * Starting balances typed in setup step 2, before the goal exists. They feed draftGoal() and so
   * the POST body; the server anchors the new plan at them. Kid balances are keyed by kid index.
   */
  readonly setupBalances = signal<{
    current: number;
    vacation: number;
    kids: Array<Partial<Record<KidAccountKey, number>>>;
  }>({ current: 0, vacation: 0, kids: [] });
  private readonly now = new Date();

  /**
   * In edit mode the stored rate kind holds until a plan input changes, so a goal that
   * crosses the 36-month line does not flip to cash with no action from the user.
   */
  readonly rateKindOverride = computed(() => {
    const goal = this.existing();
    return this.mode() === 'edit' && goal && !planInputsChanged(this.form(), goal) ? goal.planRateKind : null;
  });

  /** The goal as it would be saved now: live inputs, saved (or, in setup, typed) balances and anchor. */
  readonly draftGoal = computed<GoalPlanInput>(() => {
    const form = this.form();
    const goal = this.existing();
    let config = formToConfig(form, goal);
    if (goal) {
      return {
        type: form.type,
        config,
        currentBalance: goal.currentBalance,
        completed: goal.completed,
        clientId: goal.clientId,
        anchorBalance: goal.anchorBalance,
        anchorMonthly: goal.anchorMonthly,
        planRate: goal.planRate,
        planRateKind: goal.planRateKind,
        planAnchoredAt: goal.planAnchoredAt,
        balanceUpdatedAt: goal.balanceUpdatedAt,
      };
    }
    const typed = this.setupBalances();
    if (form.type === 'KIDS') {
      const c = config as KidsConfig;
      config = {
        ...c,
        kids: c.kids.map((kid, i) => ({
          ...kid,
          accounts: {
            plan529: { ...kid.accounts.plan529, balance: typed.kids[i]?.plan529 ?? 0 },
            brokerage: { ...kid.accounts.brokerage, balance: typed.kids[i]?.brokerage ?? 0 },
          },
        })),
      };
    } else if (form.type === 'VACATION') {
      config = { ...(config as VacationConfig), savedThisYear: typed.vacation, savedYear: this.now.getFullYear() };
    }
    return {
      type: form.type,
      config,
      currentBalance: form.type === 'KIDS' || form.type === 'VACATION' ? 0 : typed.current,
      completed: false,
      clientId: null,
      anchorBalance: 0,
      anchorMonthly: 0,
      planRate: this.selectedRate(),
      planRateKind: 'market',
      planAnchoredAt: this.now.toISOString(),
      balanceUpdatedAt: null,
    };
  });

  readonly view = computed<GoalPlanView>(() => buildGoalPlanView(this.draftGoal(), {
    marketRate: this.selectedRate(),
    frequency: this.frequency(),
    now: this.now,
    rateKindOverride: this.rateKindOverride(),
  }));

  /** Imported from v2 and never planned: the first save is its first plan. */
  readonly isImported = computed(() => {
    const goal = this.existing();
    return !!goal && goal.clientId !== null && goal.anchorMonthly === 0;
  });

  readonly ctaLabel = computed(() =>
    this.mode() === 'setup' || this.isImported() ? 'Start this plan' : 'Save changes');

  readonly isDefaultRate = computed(() => this.selectedRate() === DEFAULT_ANNUAL_RETURN);

  /** Any kid planned as if born this year. */
  readonly hasUndecidedKid = computed(() =>
    (this.view().kids ?? []).some(a => a.perKid.some(k => k.plan.assumedBirthYear)));

  /** One-line summary of the step 1 inputs, shown collapsed on the plan view. */
  readonly inputsSummary = computed(() => this.summarizeInputs(this.draftGoal()));

  /** "Ahead by $X, as of Mon YYYY" and friends. Edit mode only: a draft has no status yet. */
  readonly statusLine = computed<string | null>(() => {
    if (this.mode() !== 'edit') return null;
    const s = this.view().status;
    const asOf = fmtMonthYear(s.asOf);
    const when = asOf ? `, as of ${asOf}` : '';
    switch (s.kind) {
      case 'started': return 'Plan started';
      case 'imported': return 'Plan updated';
      case 'done': return 'Done';
      case 'ahead': return `Ahead by ${fmtMoney(Math.abs(s.delta))}${when}`;
      case 'on_plan': return `On plan${asOf ? ` as of ${asOf}` : ''}`;
      case 'behind': return `Behind by ${fmtMoney(Math.abs(s.delta))}${when}`;
    }
  });

  readonly catchUpLine = computed<string | null>(() =>
    this.mode() === 'edit' && this.view().status.kind === 'behind'
      ? `${fmtMoney(this.view().monthlyTotal)} a month gets you back on plan`
      : null);

  /** Soft nudge for a balance last logged over 60 days ago. Graded statuses only. */
  readonly showUpdateBalance = computed(() => {
    const s = this.view().status;
    return this.mode() === 'edit' && s.stale && (s.kind === 'ahead' || s.kind === 'on_plan' || s.kind === 'behind');
  });

  async ngOnInit(): Promise<void> {
    const params = this.route.snapshot.paramMap;
    const typeParam = params.get('type');
    const idParam = params.get('id');
    this.loadFrequency();

    if (typeParam !== null) {
      if (!isGoalType(typeParam)) {
        this.goBack();
        return;
      }
      this.mode.set('setup');
      this.form.set(defaultForm(typeParam, new Date()));
      if (await this.isTierGated()) {
        const role = await this.openMultiGoalNudge();
        if (role === 'upgrade') {
          // No IAP flow yet: mirrors my-profile's upgradeToPlusClicked() stub.
          console.log('[GoalPage] Plus upgrade requested');
        }
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
    this.step.set(2);
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

  // ── Plan view: rate chips ──────────────────────────────────────────────────

  selectRate(rate: number): void {
    this.selectedRate.set(rate);
  }

  resetRate(): void {
    this.selectedRate.set(DEFAULT_ANNUAL_RETURN);
  }

  trackAccount(_i: number, a: { account: KidAccountKey }): KidAccountKey {
    return a.account;
  }

  trackIndex(i: number): number {
    return i;
  }

  trackLabel(_i: number, m: { label: string }): string {
    return m.label;
  }

  toggleKidDetails(account: KidAccountKey): void {
    this.kidDetailsOpen.update(open => ({ ...open, [account]: !open[account] }));
  }

  /** "$X/mo, $B each birthday, $Y each New Year, all kids" (zero lumps left out). */
  accountLine(totals: { monthly: number; birthday: number; newYear: number }): string {
    const parts = [`${fmtMoney(totals.monthly)}/mo`];
    if (totals.birthday > 0) parts.push(`${fmtMoney(totals.birthday)} each birthday`);
    if (totals.newYear > 0) parts.push(`${fmtMoney(totals.newYear)} each New Year`);
    parts.push('all kids');
    return parts.join(', ');
  }

  // ── Plan view: save paths ──────────────────────────────────────────────────

  /** POST in setup, PUT in edit. Commits the shared rate only once the write succeeds. */
  async save(): Promise<void> {
    if (this.saving()) return;
    const form = this.form();
    const errs = validateForm(form, new Date());
    if (Object.keys(errs).length > 0) {
      this.errors.set(errs);
      this.step.set(1);
      return;
    }
    const goal = this.existing();
    const draft = this.draftGoal();
    const view = this.view();
    const wasImported = this.isImported();
    const body: GoalWrite = {
      type: form.type,
      name: form.name.trim(),
      icon: form.icon,
      // The draft carries saved balances in edit mode and typed starting balances in setup.
      config: this.withAccountAnchors(draft.config, view),
      // KIDS balances live per account in config.
      currentBalance: form.type === 'KIDS' ? 0 : draft.currentBalance,
      completed: goal?.completed ?? false,
      anchorMonthly: view.monthlyTotal,
      planRate: view.rate,
      planRateKind: view.rateKind,
    };

    this.saving.set(true);
    try {
      const request$: Observable<Goal> = goal
        ? this.goalsService.update(goal.id, body)
        : this.goalsService.create(body);
      await firstValueFrom(request$);
      this.rates.setRates(this.selectedRate(), this.rates.withdrawalRate());
      this.trackGoalChange(goal ? 'updated' : 'created', form.type, form.icon);
      this.toastService.showToast(goal && !wasImported ? 'Plan updated' : 'Plan started', 'success');
      // my-profile's ionViewWillEnter refreshes the list; do not rely on the patched signal.
      this.router.navigate(['/my-profile']);
    } catch (err) {
      await this.handleWriteError(err);
    } finally {
      this.saving.set(false);
    }
  }

  /**
   * Fires goal_changed after a successful write. Bands only: no name, id, balance or raw amount.
   * The count is read after the service has patched its signal, so it is what the user is left with.
   */
  private trackGoalChange(action: GoalAction, type: GoalType, icon: Goal['icon'], saved?: Goal): void {
    // Analytics never breaks a user flow: a tracking bug must not turn a good write into an error.
    try {
      this.sendGoalChange(action, type, icon, saved);
    } catch {
      // Swallowed on purpose.
    }
  }

  /** `saved` is the stored goal for completed/reopened/removed, so unsaved form edits never leak in. */
  private sendGoalChange(action: GoalAction, type: GoalType, icon: Goal['icon'], saved?: Goal): void {
    const view = saved
      ? buildGoalPlanView(saved, {
        marketRate: saved.planRate,
        frequency: this.frequency(),
        now: this.now,
        rateKindOverride: saved.planRateKind,
      })
      : this.view();
    const config = saved ? saved.config : this.draftGoal().config;
    const thisYear = this.now.getFullYear();

    let target: number;
    let deadlineYear: number | null;
    if (type === 'KIDS') {
      const c = config as KidsConfig;
      target = c.kids.length * (c.plan529Target + c.brokerageTarget);
      deadlineYear = c.kids.length
        ? Math.max(...c.kids.map(k => kidDeadline(this.now, k.birthYear, k.birthMonth).year))
        : null;
    } else if (type === 'VACATION') {
      const c = config as VacationConfig;
      target = c.tripsPerYear * c.budgetPerTrip;
      deadlineYear = null;
    } else {
      target = simpleTarget(type, config);
      deadlineYear = (config as TargetConfig).targetYear;
    }

    this.analytics.track({
      name: 'goal_changed',
      props: {
        action,
        goal_type: type,
        icon,
        plan_rate: this.planRateLabel(view.rateKind, view.rate),
        target_band: toAmountBand(target),
        years_to_target: toYearsBand(deadlineYear === null ? null : deadlineYear - thisYear),
        progress: target > 0 ? toPercentBand(view.progressPct) : null,
        goal_count: toCountBand(this.goalsService.goals().length),
      },
    });
  }

  /** The assumption the plan was proposed at. Snaps a legacy rate to the nearest chip so the set stays closed. */
  private planRateLabel(kind: string, rate: number): '8' | '10' | '12' | 'cash' {
    if (kind === 'cash') return 'cash';
    const pct = Math.round(rate * 100);
    return pct >= 11 ? '12' : pct >= 9 ? '10' : '8';
  }

  /** Toggles `completed`. Not offered for vacation goals. */
  async markCompleted(): Promise<void> {
    const goal = this.existing();
    if (!goal || this.saving()) return;
    this.saving.set(true);
    try {
      const updated = await firstValueFrom(
        this.goalsService.update(goal.id, this.writeFrom(goal, { completed: !goal.completed })));
      this.existing.set(updated);
      this.trackGoalChange(updated.completed ? 'completed' : 'reopened', updated.type, updated.icon, updated);
      this.toastService.showToast(updated.completed ? 'Goal marked complete' : 'Goal reopened', 'success');
    } catch (err) {
      await this.handleWriteError(err);
    } finally {
      this.saving.set(false);
    }
  }

  async confirmRemove(): Promise<void> {
    const goal = this.existing();
    if (!goal) return;
    const modal = await this.modalCtrl.create({
      component: GoalRemoveConfirmSheetComponent,
      componentProps: { goalName: goal.name },
      ...BOTTOM_SHEET_MODAL_OPTS,
    });
    await modal.present();
    const { role } = await modal.onDidDismiss();
    if (role !== 'remove') return;
    try {
      await firstValueFrom(this.goalsService.remove(goal.id), { defaultValue: undefined });
      this.trackGoalChange('removed', goal.type, goal.icon, goal);
      this.toastService.showToast('Goal removed', 'success');
      this.goBack();
    } catch (err) {
      await this.handleWriteError(err);
    }
  }

  /** Balance tile blur. */
  onBalanceBlur(path: BalancePath, event: Event): void {
    this.updateBalance(path, this.sanitize(event, 9) ?? 0);
  }

  /**
   * Setup: records a starting balance in draft state (sent with the POST).
   * Edit: logs a balance. Sends the SAVED goal with just the balance changed, so unsaved
   * plan-input edits never ride along; the server keeps the anchor and stamps the date.
   */
  async updateBalance(path: BalancePath, value: number): Promise<void> {
    const amount = Math.max(0, Math.round(value));
    const goal = this.existing();
    if (!goal) {
      this.setupBalances.update(b => {
        if (path === 'current') return { ...b, current: amount };
        if (path === 'vacation') return { ...b, vacation: amount };
        const kids = [...b.kids];
        kids[path.kid] = { ...kids[path.kid], [path.account]: amount };
        return { ...b, kids };
      });
      return;
    }
    let patch: Partial<GoalWrite>;
    if (path === 'current') {
      if (goal.currentBalance === amount) return;
      patch = { currentBalance: amount };
    } else if (path === 'vacation') {
      const c = goal.config as VacationConfig;
      const year = new Date().getFullYear();
      if (c.savedThisYear === amount && c.savedYear === year) return;
      patch = { config: { ...c, savedThisYear: amount, savedYear: year } };
    } else {
      const c = goal.config as KidsConfig;
      const kid = c.kids[path.kid];
      if (!kid || kid.accounts[path.account].balance === amount) return;
      const kids = c.kids.map((k, i) => i !== path.kid ? k : {
        ...k,
        accounts: { ...k.accounts, [path.account]: { ...k.accounts[path.account], balance: amount } },
      });
      patch = { config: { ...c, kids } };
    }
    try {
      const updated = await firstValueFrom(this.goalsService.update(goal.id, this.writeFrom(goal, patch)));
      this.existing.set(updated);
    } catch (err) {
      await this.handleWriteError(err);
    }
  }

  /** Milestone tiles for one kid account: years shown only for a decided, born kid. */
  milestones(row: KidPlanRow): Array<{ label: string; year: number | null; value: string }> {
    const m = row.plan.milestones;
    const year = (age: number) => (row.relative ? null : row.plan.deadline.year + (age - 18));
    return [
      { label: 'At 18', year: year(18), value: fmtCompact(m.at18) },
      { label: 'At 22', year: year(22), value: fmtCompact(m.at22) },
      { label: 'At 30', year: year(30), value: fmtCompact(m.at30) },
    ];
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
    // No data (timeout or error): fail open. The backend enforces the limit with a 403 TIER_LIMIT.
    // defaultValue: a source that completes without emitting resolves to undefined, never rejects.
    const progress = await firstValueFrom(
      this.authService.userProgress$.pipe(
        filter(p => !!p),
        take(1),
        timeout(3000),
        catchError(() => of(undefined)),
      ),
      { defaultValue: undefined },
    );
    if (progress === undefined) return false;
    // Real data with a missing or unknown tier counts as not Plus/Pro.
    const tier = progress?.selectedTier;
    return !(tier === 'plus' || tier === 'pro');
  }

  private initialRate(): number {
    this.rates.ensureUser();
    return this.rates.annualReturn();
  }

  private loadFrequency(): void {
    this.authService.getCurrentInvestmentSchedule().subscribe({
      next: (schedule) => this.frequency.set(schedule?.frequency ? String(schedule.frequency).toLowerCase() : ''),
      error: () => this.frequency.set(''),
    });
  }

  /** KIDS: stamp each account's anchorMonthly with that account's proposed monthly. */
  private withAccountAnchors(config: GoalConfig, view: GoalPlanView): GoalConfig {
    if (!view.kids) return config;
    const cfg = config as KidsConfig;
    const monthlyFor = (account: KidAccountKey, i: number): number =>
      view.kids?.find(a => a.account === account)?.perKid[i]?.plan.monthly ?? 0;
    const kids = cfg.kids.map((kid, i) => ({
      ...kid,
      accounts: {
        plan529: { ...kid.accounts.plan529, anchorMonthly: monthlyFor('plan529', i) },
        brokerage: { ...kid.accounts.brokerage, anchorMonthly: monthlyFor('brokerage', i) },
      },
    }));
    return { ...cfg, kids };
  }

  /** The saved goal as a write body, with a patch on top. Plan inputs and anchor unchanged. */
  private writeFrom(goal: Goal, patch: Partial<GoalWrite>): GoalWrite {
    return {
      type: goal.type,
      name: goal.name,
      icon: goal.icon,
      config: goal.config,
      currentBalance: goal.currentBalance,
      completed: goal.completed,
      anchorMonthly: goal.anchorMonthly,
      planRate: goal.planRate,
      planRateKind: goal.planRateKind,
      ...patch,
    };
  }

  /** 403 TIER_LIMIT: nudge, no toast. 401: toast and sign out. Anything else: toast and report. */
  private async handleWriteError(err: unknown): Promise<void> {
    const status = httpStatusOf(err);
    const code = err instanceof HttpErrorResponse ? err.error?.code : undefined;
    if (status === 403 && code === 'TIER_LIMIT') {
      const role = await this.openMultiGoalNudge();
      if (role === 'upgrade') {
        console.log('[GoalPage] Plus upgrade requested');
      }
      return;
    }
    if (status === 401) {
      this.toastService.showToast('Your session ended. Sign in again to save this plan.', 'danger');
      this.authService.logout();
      return;
    }
    this.toastService.showToast('Could not save this goal. Try again.', 'danger');
    this.analytics.trackErrorShown('save_failed', 'goals', 'toast', status, true);
  }

  private summarizeInputs(goal: GoalPlanInput): string {
    switch (goal.type) {
      case 'KIDS': {
        const c = goal.config as KidsConfig;
        const parts = [`${c.kids.length} ${c.kids.length === 1 ? 'kid' : 'kids'}`];
        if (c.plan529Target > 0) parts.push(`${fmtCompact(c.plan529Target)} 529`);
        if (c.brokerageTarget > 0) parts.push(`${fmtCompact(c.brokerageTarget)} brokerage`);
        return parts.join(' · ');
      }
      case 'HOUSE':
      case 'CAR': {
        const c = goal.config as HouseCarConfig;
        const thing = goal.type === 'HOUSE' ? 'home' : 'car';
        const lead = c.downPaymentPct == null
          ? `Full price on a ${fmtCompact(c.price)} ${thing}`
          : `${c.downPaymentPct}% down on a ${fmtCompact(c.price)} ${thing} · ${fmtCompact(c.price * c.downPaymentPct / 100)}`;
        return `${lead} · ${c.targetYear}`;
      }
      case 'VACATION': {
        const c = goal.config as VacationConfig;
        return `${c.tripsPerYear} ${c.tripsPerYear === 1 ? 'trip' : 'trips'} a year · ${fmtCompact(c.budgetPerTrip)} each`;
      }
      case 'EMERGENCY_FUND': {
        const c = goal.config as EmergencyFundConfig;
        return `${c.months} months of ${fmtCompact(c.monthlyExpenses)} · ${c.targetYear}`;
      }
      default: {
        const c = goal.config as TargetConfig;
        return `${fmtCompact(c.targetAmount)} by ${c.targetYear}`;
      }
    }
  }

  private async openMultiGoalNudge(): Promise<string | undefined> {
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
    const { role } = await modal.onDidDismiss();
    return role;
  }
}
