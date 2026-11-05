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
  AccountState, EmergencyFundConfig, Goal, GoalConfig, GoalType, GoalWrite, HouseCarConfig, KidInput, KidsConfig,
  TargetConfig, VacationConfig,
} from '../../models/goal.model';
import { horizonMonths, isKidNotYetBorn, kidDeadline, simpleTarget } from '../../utils/goal-plan.utils';
import { collegePresets, costCardSources, kidCostFigures } from '../../utils/kid-cost.utils';
import { AT_18_PRESETS, COLLEGE_PRICE_YEAR_LABEL } from '../../constants/kid-costs';
import { McInfoSheetComponent } from '../../components/mc-info-sheet/mc-info-sheet.component';
import { GoalRemoveConfirmSheetComponent } from '../../components/goal-remove-confirm-sheet/goal-remove-confirm-sheet.component';
import { BOTTOM_SHEET_MODAL_OPTS } from '../../utils/bottom-sheet.utils';
import {
  At18Choice, CUSTOM_ICON_OPTIONS, CollegeChoice, GoalForm, KidsQuizCard, MAX_KIDS, collegeAmount, costBirthLine,
  costFamilyLine, costHeadline, costNote, costSourceLine, costStageLine, defaultFirstYearFund, defaultForm,
  firstChildLine, formFromGoal, formToConfig, fundRowLines, isGoalType, kidTiming, kidsQuizErrors, newKidRow,
  planInputsChanged, shortRunwayLine, toLocalIso, validateForm, KidFormRow,
} from './goal-form.model';
import {
  FirstYearRow, GoalPlanInput, GoalPlanView, KidAccountKey, KidPlanRow, buildGoalPlanView, fmtCompact, fmtMoney,
  fmtMonthYear,
} from './goal-plan.view-model';

/** A kid's balance tile: the 529, the brokerage, or the first-year fund. */
export type KidBalanceKey = KidAccountKey | 'firstYear';

/** Which balance a balance tile edits. */
export type BalancePath = 'current' | 'vacation' | { kid: number; account: KidBalanceKey };

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
    kids: Array<Partial<Record<KidBalanceKey, number>>>;
  }>({ current: 0, vacation: 0, kids: [] });
  private readonly now = new Date();

  /** True in edit mode once a plan input differs from the saved goal: saving will re-anchor. */
  readonly planChanged = computed(() => {
    const goal = this.existing();
    return this.mode() === 'edit' && !!goal && planInputsChanged(this.form(), goal, this.now);
  });

  /**
   * In edit mode the stored rate kind holds until a plan input changes, so a goal that
   * crosses the 36-month line does not flip to cash with no action from the user.
   */
  readonly rateKindOverride = computed(() => {
    const goal = this.existing();
    return this.mode() === 'edit' && goal && !this.planChanged() ? goal.planRateKind : null;
  });

  /** The goal as it would be saved now: live inputs, saved (or, in setup, typed) balances and anchor. */
  readonly draftGoal = computed<GoalPlanInput>(() => this.buildDraft(this.form()));

  readonly view = computed<GoalPlanView>(() =>
    this.planViewOf(this.draftGoal(), this.mode() === 'setup' || this.planChanged()));

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

  // ── KIDS: quiz, what a kid costs, first-year fund ───────────────────────────

  readonly quizCards: ReadonlyArray<KidsQuizCard> = [1, 2, 3, 4, 5];
  readonly quizCard = signal<KidsQuizCard>(1);
  readonly collegeRows = collegePresets();
  readonly at18Presets = AT_18_PRESETS;
  readonly costSources = costCardSources();
  readonly costHeadline = costHeadline;
  readonly costFamilyLine = costFamilyLine;
  readonly costStageLine = costStageLine;
  readonly costBirthLine = costBirthLine;
  readonly costSourceLine = costSourceLine;
  readonly costNote = costNote;
  readonly firstChildLine = firstChildLine();
  readonly shortRunwayLine = shortRunwayLine;
  readonly fundRowLines = fundRowLines;

  /**
   * Setup: the fund switch follows defaultFirstYearFund until the user touches it.
   * Edit: the stored switch counts as touched and is never re-defaulted.
   */
  private readonly fundTouched = signal(false);
  /** null = the mode's default: open in setup, collapsed in edit. */
  private readonly costsOpenChoice = signal<boolean | null>(null);
  readonly costsOpen = computed(() => this.costsOpenChoice() ?? this.mode() === 'setup');
  readonly costSourcesOpen = signal(false);

  readonly quizCosts = computed(() => kidCostFigures(this.form().kids.kids.length));

  /** True when any kid could hold a fund: not yet born, or already holding a stored one. */
  readonly anyKidEligible = computed(() => {
    const stored = this.storedKids();
    return this.form().kids.kids.some((row, i) => {
      const t = kidTiming(row);
      return isKidNotYetBorn(t.birthYear, t.birthMonth, this.now) || !!stored[i]?.accounts.firstYear;
    });
  });

  /** What the fund would add per month, and its last end month, if it were on. */
  readonly fundPreview = computed<{ monthly: number; endsLabel: string } | null>(() => {
    const form = this.form();
    if (form.type !== 'KIDS') return null;
    const on: GoalForm = { ...form, kids: { ...form.kids, firstYearFund: true } };
    const goal = this.existing();
    const reanchor = this.mode() === 'setup' || (!!goal && planInputsChanged(on, goal, this.now));
    const fund = this.planViewOf(this.buildDraft(on), reanchor).firstYear;
    return fund?.endsLabel ? { monthly: fund.totalMonthly, endsLabel: fund.endsLabel } : null;
  });

  /** The shortest runway among decided kids not yet born, when under 12 months; else null. */
  readonly fundShortRunwayMonths = computed<number | null>(() => {
    const months = this.form().kids.kids
      .map(kidTiming)
      .filter(t => t.birthYear !== null && isKidNotYetBorn(t.birthYear, t.birthMonth, this.now))
      .map(t => horizonMonths(this.now, t.birthYear as number, t.birthMonth ?? 7));
    const shortest = months.length > 0 ? Math.min(...months) : null;
    return shortest !== null && shortest < 12 ? shortest : null;
  });

  /** Balances logged on the saved goal's first-year funds, which turning the fund off drops. */
  readonly fundLoggedBalance = computed(() =>
    this.storedKids().reduce((sum, kid) => sum + (kid.accounts.firstYear?.balance ?? 0), 0));

  readonly collegePresetSelected = computed(() =>
    this.collegeRows.some(p => p.key === this.form().kids.collegeChoice));
  readonly collegePriceYear = COLLEGE_PRICE_YEAR_LABEL;

  /** A college amount is set, so the cost note points at the 529 below. */
  readonly hasCollege = computed(() => (this.form().kids.plan529Target ?? 0) > 0);

  /** Hero line: "$F of this is the first-year fund, which ends Mon YYYY." when other targets exist too. */
  readonly heroFundLine = computed<string | null>(() => {
    const fund = this.view().firstYear;
    const c = this.draftGoal().config as KidsConfig;
    if (this.form().type !== 'KIDS' || !fund || !(fund.totalMonthly > 0) || !fund.endsLabel) return null;
    if (!(c.plan529Target > 0) && !(c.brokerageTarget > 0)) return null;
    return `${fmtMoney(fund.totalMonthly)} of this is the first-year fund, which ends ${fund.endsLabel}.`;
  });

  readonly visibleFundRows = computed<FirstYearRow[]>(() =>
    (this.view().firstYear?.rows ?? []).filter(r => !r.hidden));

  /** Any visible undecided fund still running and invested at the return assumption. */
  readonly showInvestedFundNote = computed(() =>
    this.visibleFundRows().some(r => r.plan.undecided && !r.plan.finished && r.plan.rateKind === 'market'));

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
    this.fundTouched.set(true);
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
    if (!this.validateAll()) return;
    this.step.set(2);
  }

  backToInputs(): void {
    this.step.set(1);
    this.quizCard.set(1);
  }

  // ── KIDS quiz ───────────────────────────────────────────────────────────────

  /** Validates the current card, then advances. Card 5 shows the plan. */
  nextQuizCard(): void {
    const card = this.quizCard();
    if (card === 5) {
      this.showPlan();
      return;
    }
    const errs = kidsQuizErrors(this.form(), card, this.now, this.existing());
    this.errors.set(errs);
    if (Object.keys(errs).length > 0) return;
    this.quizCard.set((card + 1) as KidsQuizCard);
  }

  prevQuizCard(): void {
    const card = this.quizCard();
    if (card === 1) return;
    this.errors.set({});
    this.quizCard.set((card - 1) as KidsQuizCard);
  }

  setCollegeChoice(choice: CollegeChoice): void {
    const amount = choice === 'custom' ? this.form().kids.plan529Target : collegeAmount(choice);
    this.patchKids({ collegeChoice: choice, plan529Target: amount });
  }

  setAt18Choice(choice: At18Choice): void {
    let amount: number | null;
    if (typeof choice === 'number') amount = choice;
    else amount = choice === 'custom' ? this.form().kids.brokerageTarget : null;
    this.patchKids({ at18Choice: choice, brokerageTarget: amount });
  }

  toggleFirstYearFund(): void {
    this.fundTouched.set(true);
    this.patchKids({ firstYearFund: !this.form().kids.firstYearFund });
  }

  toggleCosts(): void {
    this.costsOpenChoice.set(!this.costsOpen());
  }

  toggleCostSources(): void {
    this.costSourcesOpen.update(open => !open);
  }

  isCollegeChoice(choice: CollegeChoice): boolean {
    return this.form().kids.collegeChoice === choice;
  }

  isAt18Choice(choice: At18Choice): boolean {
    return this.form().kids.at18Choice === choice;
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

  trackFundRow(_i: number, row: FirstYearRow): number {
    return row.kidIndex;
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
    if (!this.validateAll()) {
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
      const account = kid?.accounts[path.account];
      if (!account || account.balance === amount) return;
      const kids = c.kids.map((k, i) => i !== path.kid ? k : {
        ...k,
        accounts: { ...k.accounts, [path.account]: { ...account, balance: amount } },
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
    if (partial.kids) this.applyFundDefault();
    this.revalidateIfShowing();
  }

  patchKid(index: number, partial: Partial<KidFormRow>): void {
    this.form.update(f => ({
      ...f,
      kids: { ...f.kids, kids: f.kids.kids.map((k, i) => (i === index ? { ...k, ...partial } : k)) },
    }));
    this.applyFundDefault();
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

  /** Once the user has hit "Show my plan" (or Next) with errors, keep messages live as they type. */
  private revalidateIfShowing(): void {
    if (Object.keys(this.errors()).length === 0) return;
    const form = this.form();
    this.errors.set(form.type === 'KIDS' && this.step() === 1
      ? kidsQuizErrors(form, this.quizCard(), this.now, this.existing())
      : validateForm(form, this.now, this.existing()));
  }

  /**
   * Validates the whole form and shows its errors. For KIDS, a failure lands on the first quiz
   * card with an error and shows that card's errors.
   */
  private validateAll(): boolean {
    const form = this.form();
    const errs = validateForm(form, this.now, this.existing());
    if (Object.keys(errs).length === 0) {
      this.errors.set({});
      return true;
    }
    if (form.type !== 'KIDS') {
      this.errors.set(errs);
      return false;
    }
    const card = this.quizCards.find(c => Object.keys(kidsQuizErrors(form, c, this.now, this.existing())).length > 0) ?? 5;
    this.quizCard.set(card);
    this.errors.set(kidsQuizErrors(form, card, this.now, this.existing()));
    return false;
  }

  /** Setup only, until the user touches the switch: the fund follows the count and timing. */
  private applyFundDefault(): void {
    const form = this.form();
    if (this.mode() !== 'setup' || this.fundTouched() || form.type !== 'KIDS') return;
    const value = defaultFirstYearFund(form.kids.kids, this.now);
    if (value !== form.kids.firstYearFund) {
      this.form.update(f => ({ ...f, kids: { ...f.kids, firstYearFund: value } }));
    }
  }

  /** The saved goal's kids, or [] in setup or for another type. */
  private storedKids(): KidInput[] {
    const goal = this.existing();
    return goal && goal.type === 'KIDS' ? (goal.config as KidsConfig).kids : [];
  }

  /** The goal as it would be saved from this form. See draftGoal. */
  private buildDraft(form: GoalForm): GoalPlanInput {
    const goal = this.existing();
    let config = formToConfig(form, goal, this.now);
    if (goal) {
      const draft: GoalPlanInput = {
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
      return planInputsChanged(form, goal, this.now) ? this.reanchored(draft) : draft;
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
            ...(kid.accounts.firstYear
              ? { firstYear: { ...kid.accounts.firstYear, balance: typed.kids[i]?.firstYear ?? 0 } }
              : {}),
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
  }

  /**
   * An edit draft whose plan inputs changed, anchored now exactly as the server will store it:
   * anchor balances reset to the current balances and no logged-balance date, so the preview
   * equals the plan after the save and the status reads Plan started meanwhile.
   */
  private reanchored(draft: GoalPlanInput): GoalPlanInput {
    let config = draft.config;
    if (draft.type === 'KIDS') {
      const c = config as KidsConfig;
      const reset = (a: AccountState): AccountState => ({ ...a, anchorBalance: a.balance, balanceUpdatedAt: null });
      config = {
        ...c,
        kids: c.kids.map(kid => ({
          ...kid,
          accounts: {
            plan529: reset(kid.accounts.plan529),
            brokerage: reset(kid.accounts.brokerage),
            ...(kid.accounts.firstYear ? { firstYear: reset(kid.accounts.firstYear) } : {}),
          },
        })),
      };
    }
    return {
      ...draft,
      config,
      planAnchoredAt: toLocalIso(this.now),
      anchorBalance: draft.currentBalance,
      balanceUpdatedAt: null,
    };
  }

  private planViewOf(draft: GoalPlanInput, reanchorPending: boolean): GoalPlanView {
    return buildGoalPlanView(draft, {
      marketRate: this.selectedRate(),
      frequency: this.frequency(),
      now: this.now,
      rateKindOverride: this.rateKindOverride(),
      reanchorPending,
    });
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

  /**
   * KIDS: stamp each account's anchorMonthly with that account's proposed monthly. A finished
   * first-year fund asks for nothing more, so it keeps the anchor it has.
   */
  private withAccountAnchors(config: GoalConfig, view: GoalPlanView): GoalConfig {
    if (!view.kids) return config;
    const cfg = config as KidsConfig;
    const monthlyFor = (account: KidAccountKey, i: number): number =>
      view.kids?.find(a => a.account === account)?.perKid[i]?.plan.monthly ?? 0;
    const kids = cfg.kids.map((kid, i) => {
      const fund = kid.accounts.firstYear;
      const fundPlan = view.firstYear?.rows.find(r => r.kidIndex === i)?.plan;
      return {
        ...kid,
        accounts: {
          plan529: { ...kid.accounts.plan529, anchorMonthly: monthlyFor('plan529', i) },
          brokerage: { ...kid.accounts.brokerage, anchorMonthly: monthlyFor('brokerage', i) },
          ...(fund
            ? { firstYear: { ...fund, anchorMonthly: fundPlan && !fundPlan.finished ? fundPlan.monthly : fund.anchorMonthly } }
            : {}),
        },
      };
    });
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
        if (c.plan529Target > 0) parts.push(`${fmtCompact(c.plan529Target)} college`);
        if (c.brokerageTarget > 0) parts.push(`${fmtCompact(c.brokerageTarget)} at 18`);
        if (c.firstYearFund) parts.push('first-year fund');
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
