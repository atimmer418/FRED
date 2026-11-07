import { Component, ElementRef, OnDestroy, OnInit, computed, effect, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
import { firstValueFrom } from 'rxjs';

import { Goal, GoalIcon, GoalType } from '../../models/goal.model';
import { GoalsService } from '../../services/goals.service';
import { GoalDraftService } from '../../services/goal-draft.service';
import { RateAssumptionsService } from '../../services/rate-assumptions.service';
import { QuizChipOption, QuizChipsComponent } from '../../components/quiz-controls/quiz-chips.component';
import { QuizPickGridComponent, QuizPickOption } from '../../components/quiz-controls/quiz-pick-grid.component';
import { QuizSliderComponent } from '../../components/quiz-controls/quiz-slider.component';
import { QuizStepperComponent } from '../../components/quiz-controls/quiz-stepper.component';
import { QuizSwitchRowComponent } from '../../components/quiz-controls/quiz-switch-row.component';
import { tickHaptic } from '../../components/quiz-controls/haptics';
import { GOAL_PAGE_NOW } from '../goal/goal-clock';
import {
  CUSTOM_ICON_OPTIONS, CollegeChoice, DEFAULT_AT_18, DEFAULT_COLLEGE, GoalForm, KidFormRow, KidsForm, MAX_KIDS,
  at18ChoiceFor, collegeAmount, defaultForm, formFromGoal, isGoalType, kidTiming, monthYearLabel, newKidRow,
  validateForm,
} from '../goal/goal-form.model';
import { fmtMoney } from '../goal/goal-plan.view-model';
import { isKidNotYetBorn, proposeSimplePlan, requiredMonthly } from '../../utils/goal-plan.utils';
import { collegePresets, familyMonthly, firstYearTargetForRank, kidCostFigures, kidRanks } from '../../utils/kid-cost.utils';
import { FundKind, formatSavings, fundSavingsRange, kidYearBounds, kidYearLabel } from '../../utils/goal-quiz.utils';
import { QuizStep, QuizStepId, quizStepsFor } from './goal-quiz.steps';
import { CALC_LINE_STAGGER_MS, GoalQuizCalcComponent } from './goal-quiz-calc.component';

/** The cost screen holds the general statistic this long before the family figure crossfades in. */
const COST_HOLD_MS = 2200;
const COST_FADE_MS = 500;
/** The calc screen's stay: never under 0.9s, never over 2.5s. */
const CALC_MIN_MS = 900;
const CALC_MAX_MS = 2500;

type DownPayment = number | 'full';

/** Which validateForm keys each step shows and blocks Continue on. */
function stepOwnsError(id: QuizStepId, key: string): boolean {
  switch (id) {
    case 'kids_when': return key.startsWith('kid.');
    case 'kids_fund': return key === 'kidsTargets';
    case 'kids_college': return key === 'plan529Target';
    case 'kids_nest': return key === 'brokerageTarget' || key === 'kidsTargets';
    case 'house_price':
    case 'car_price': return key === 'price';
    case 'house_terms':
    case 'car_terms': return key === 'targetYear';
    case 'vacation': return key === 'budgetPerTrip';
    case 'emergency': return key === 'monthlyExpenses' || key === 'targetYear';
    case 'wedding':
    case 'custom': return key === 'targetAmount' || key === 'targetYear';
    default: return false;
  }
}

/**
 * The quiz: one pushed page for every goal type. `/goals/new/:type` starts from the type's
 * defaults; `/goals/:id/edit` starts from the saved goal. The last step's "Show my plan" hands the
 * form to GoalDraftService, shows the calc screen, then pushes the plan page in preview mode.
 */
@Component({
  selector: 'app-goal-quiz',
  standalone: true,
  imports: [
    NgTemplateOutlet,
    QuizSliderComponent, QuizChipsComponent, QuizStepperComponent, QuizSwitchRowComponent, QuizPickGridComponent,
    GoalQuizCalcComponent,
  ],
  templateUrl: './goal-quiz.page.html',
  styleUrls: ['./goal-quiz.page.scss'],
})
export class GoalQuizPage implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly navCtrl = inject(NavController);
  private readonly goalsService = inject(GoalsService);
  private readonly draftService = inject(GoalDraftService);
  private readonly rates = inject(RateAssumptionsService);
  private readonly el = inject<ElementRef<HTMLElement>>(ElementRef);
  readonly now: Date = inject(GOAL_PAGE_NOW)();

  readonly form = signal<GoalForm>(defaultForm('CUSTOM', this.now));
  /** The saved goal on an edit, else null. */
  readonly existing = signal<Goal | null>(null);
  readonly stepIndex = signal(0);
  readonly steps = computed<QuizStep[]>(() => quizStepsFor(this.form(), this.now));
  readonly currentStep = computed<QuizStep>(() => {
    const steps = this.steps();
    return steps[Math.min(this.stepIndex(), steps.length - 1)];
  });
  readonly isLast = computed(() => this.stepIndex() >= this.steps().length - 1);
  /** True once the route resolved to a form. */
  readonly ready = signal(false);
  readonly calcShowing = signal(false);
  /** The one-line error under the current step's control, set by Continue and cleared by any change. */
  readonly error = signal<string | null>(null);

  // ── Kids ──────────────────────────────────────────────────────────────────

  readonly maxKids = MAX_KIDS;
  readonly kidRows = computed<KidFormRow[]>(() => this.form().kids.kids);
  readonly kidCount = computed(() => this.kidRows().length);
  readonly yearBounds = computed(() => {
    const year = this.now.getFullYear();
    return { min: year + 1, max: year + 20 };
  });
  readonly kidYearRange = computed(() => kidYearBounds(this.now));
  /** The Baby fund row shows only while at least one kid is not yet born. */
  readonly showBabyRow = computed(() =>
    this.kidRows().map(kidTiming).some(t => isKidNotYetBorn(t.birthYear, t.birthMonth, this.now)));
  readonly allFundsOff = computed(() => {
    const k = this.form().kids;
    return k.collegeChoice === 'none' && k.at18Choice === 'none' && !(k.firstYearFund && this.showBabyRow());
  });

  /** 'A' is the general statistic, 'B' the family monthly. */
  readonly costStat = signal<'A' | 'B'>('A');
  readonly costBig = computed(() => `$${Math.round(kidCostFigures(this.kidCount()).perChildTotal / 1000)}K`);
  readonly costMonthly = computed(() => fmtMoney(familyMonthly(this.kidCount())));
  readonly costHint = computed(() => {
    const n = this.kidCount();
    if (n <= 1) return 'For your kid';
    if (n === 2) return 'For both kids';
    return `For all ${n} kids`;
  });

  /** "Skip for now" is held in the grid until Continue, so the step does not vanish under the user. */
  readonly collegeSkipPending = signal(false);
  readonly collegeCustomOpen = signal(false);
  readonly collegeGridValue = computed<CollegeChoice>(() =>
    this.collegeSkipPending() ? 'none' : this.form().kids.collegeChoice);
  readonly collegeOptions: QuizPickOption<CollegeChoice>[] = [
    { icon: 'school', label: 'In-state public', amount: collegeAmount('public_tuition') ?? 0, value: 'public_tuition' },
    { icon: 'travel_explore', label: 'Out-of-state', amount: collegeAmount('public_full') ?? 0, value: 'public_full' },
    { icon: 'account_balance', label: 'Private', amount: collegeAmount('private_tuition') ?? 0, value: 'private_tuition' },
    { icon: 'do_not_disturb_on', label: 'Skip for now', amount: 0, value: 'none' },
  ];
  readonly collegeCustomAmount = computed(() => this.form().kids.plan529Target ?? 50_000);

  /** The nest egg slider's value; $0 is held here and reads as the switch being off on Continue. */
  readonly nestAmount = signal(DEFAULT_AT_18);
  readonly nestChip = computed(() => {
    const v = this.nestAmount();
    if (v <= 0) return 'Nothing extra at 18';
    return `~${fmtMoney(requiredMonthly(0, v, 216, this.rate()))} a month per kid`;
  });

  // ── Other types ───────────────────────────────────────────────────────────

  readonly iconOptions = CUSTOM_ICON_OPTIONS;
  readonly downPaymentOptions: QuizChipOption<DownPayment>[] = [
    { label: '10%', value: 10 }, { label: '20%', value: 20 }, { label: 'Full price', value: 'full' },
  ];
  readonly monthsOptions: QuizChipOption<3 | 6>[] = [{ label: '3 months', value: 3 }, { label: '6 months', value: 6 }];
  readonly byOptions: QuizChipOption<number>[] = [
    { label: '1 year', value: 1 }, { label: '2 years', value: 2 }, { label: '3 years', value: 3 },
  ];
  readonly downPayment = computed<DownPayment>(() => this.form().fullPrice ? 'full' : (this.form().downPaymentPct ?? 20));
  readonly byYears = computed(() => (this.form().targetYear ?? this.now.getFullYear()) - this.now.getFullYear());

  readonly termsChip = computed(() => {
    const f = this.form();
    const price = f.price ?? 0;
    const target = f.fullPrice ? price : (price * (f.downPaymentPct ?? 20)) / 100;
    const down = f.fullPrice ? `${fmtMoney(price)} in full` : `$${Math.round(target / 1000)}K down`;
    return `${down} · ~${fmtMoney(this.simpleMonthly(target))} a month`;
  });
  readonly vacationChip = computed(() => {
    const f = this.form();
    return `~${fmtMoney(Math.ceil((f.tripsPerYear * (f.budgetPerTrip ?? 0)) / 12))} a month`;
  });
  readonly emergencyChip = computed(() => {
    const f = this.form();
    const target = (f.monthlyExpenses ?? 0) * f.months;
    return `${fmtMoney(target)} by ${monthYearLabel({ year: f.targetYear ?? this.now.getFullYear(), month: 12 })}`;
  });
  readonly amountChip = computed(() => `~${fmtMoney(this.simpleMonthly(this.form().targetAmount ?? 0))} a month`);

  readonly fmtMoney = fmtMoney;

  private costTimer: ReturnType<typeof setTimeout> | null = null;
  private calcTimer: ReturnType<typeof setTimeout> | null = null;
  private enteredStepId: QuizStepId | null = null;

  constructor() {
    // The cost screen's hold starts the moment that step is entered and stops when it is left.
    effect(() => {
      const id = this.currentStep()?.id ?? null;
      if (id === this.enteredStepId) return;
      this.enteredStepId = id;
      if (id === 'kids_cost') this.startCostHold();
      else this.clearCostTimer();
    });
  }

  async ngOnInit(): Promise<void> {
    this.rates.ensureUser();
    const params = this.route.snapshot.paramMap;
    const typeParam = params.get('type');
    const idParam = params.get('id');

    if (typeParam !== null) {
      if (!isGoalType(typeParam)) {
        this.close();
        return;
      }
      this.form.set(this.withQuizDefaults(defaultForm(typeParam, this.now)));
      this.ready.set(true);
      return;
    }

    const id = Number(idParam);
    if (idParam === null || !Number.isInteger(id) || id <= 0) {
      this.close();
      return;
    }
    let goal = this.goalsService.goals().find(g => g.id === id);
    if (!goal) {
      try {
        await firstValueFrom(this.goalsService.refresh());
      } catch {
        this.close();
        return;
      }
      goal = this.goalsService.goals().find(g => g.id === id);
    }
    if (!goal) {
      this.close();
      return;
    }
    this.existing.set(goal);
    const form = formFromGoal(goal);
    this.form.set(form);
    this.nestAmount.set(form.kids.brokerageTarget ?? DEFAULT_AT_18);
    this.collegeCustomOpen.set(form.kids.collegeChoice === 'custom');
    this.ready.set(true);
  }

  ngOnDestroy(): void {
    this.clearCostTimer();
    if (this.calcTimer) clearTimeout(this.calcTimer);
  }

  // ── Navigation ────────────────────────────────────────────────────────────

  /** The subtitle for a step; the fund step asks for one when everything is off. */
  subtitleFor(step: QuizStep): string {
    return step.id === 'kids_fund' && this.allFundsOff() ? 'Pick at least one.' : step.subtitle;
  }

  ctaDisabled(): boolean {
    return this.currentStep().id === 'kids_fund' && this.allFundsOff();
  }

  next(): void {
    if (this.calcShowing()) return;
    const step = this.currentStep();
    const candidate = this.withPendingChoices(this.form(), step.id);
    const err = this.firstStepError(step.id, candidate);
    if (err) {
      this.error.set(err);
      return;
    }
    this.error.set(null);
    if (candidate !== this.form()) {
      // A skipped college or a $0 nest egg removes its own step: the index now points at the
      // step that followed it, or past the end.
      this.form.set(candidate);
      this.collegeSkipPending.set(false);
      if (this.stepIndex() >= this.steps().length) this.showPlan();
      return;
    }
    if (this.isLast()) this.showPlan();
    else this.stepIndex.update(i => i + 1);
  }

  back(): void {
    if (this.calcShowing()) return;
    this.error.set(null);
    if (this.stepIndex() === 0) {
      this.close();
      return;
    }
    this.stepIndex.update(i => i - 1);
  }

  /** Discards: a new quiz returns to my-profile, an edit returns to its plan page. */
  close(): void {
    const existing = this.existing();
    if (existing) void this.navCtrl.navigateBack(['/goals', existing.id]);
    else void this.navCtrl.navigateBack('/my-profile');
  }

  /** Hands the draft over, shows the calc screen for 0.9 to 2.5 seconds, then pushes the preview. */
  showPlan(): void {
    this.draftService.set(this.form(), this.existing());
    this.calcShowing.set(true);
    const lines = this.calcLines().length;
    const hold = this.reducedMotion()
      ? CALC_MIN_MS
      : Math.min(CALC_MAX_MS, Math.max(CALC_MIN_MS, 600 + CALC_LINE_STAGGER_MS * lines));
    this.calcTimer = setTimeout(() => this.navigateToPlan(), hold);
  }

  /** One line per unit of the plan for the calc screen. */
  calcLines(): string[] {
    const f = this.form();
    if (f.type !== 'KIDS') return ['Your target', 'Your monthly'];
    const k = f.kids;
    const units = [k.collegeChoice !== 'none' ? 'college' : null, k.at18Choice !== 'none' ? 'nest egg' : null]
      .filter((u): u is string => u !== null);
    const invested = units.length ? units.join(' and ') : 'nothing yet';
    return k.kids.map(row => {
      const t = kidTiming(row);
      const fund = k.firstYearFund && isKidNotYetBorn(t.birthYear, t.birthMonth, this.now);
      if (t.birthYear === null) return `${row.label}, not dated: ${fund ? 'starting the baby fund now' : invested}`;
      if (!isKidNotYetBorn(t.birthYear, t.birthMonth, this.now)) return `${row.label}, already here: ${invested}`;
      return `${row.label}, ${t.birthYear === this.now.getFullYear() ? 'this year' : t.birthYear}: ${fund ? 'baby fund first' : invested}`;
    });
  }

  reducedMotion(): boolean {
    try {
      return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    } catch {
      return false;
    }
  }

  // ── Kids: count and when ──────────────────────────────────────────────────

  setKidCount(n: number): void {
    const count = Math.max(1, Math.min(MAX_KIDS, Math.round(n)));
    const rows = this.kidRows();
    if (count === rows.length) return;
    const next = count < rows.length
      ? rows.slice(0, count)
      : [...rows, ...Array.from({ length: count - rows.length }, (_, i) => newKidRow(rows.length + i))];
    this.patchKids({ kids: next });
  }

  /** The slider position for a kid: the birth year, or one past the last year for "Not sure". */
  kidSliderValue(i: number): number {
    const row = this.kidRows()[i];
    const { max } = this.kidYearRange();
    return row.undecided || row.birthYear === null ? max + 1 : row.birthYear;
  }

  kidWhenLabel(i: number): string {
    const row = this.kidRows()[i];
    return kidYearLabel(row.undecided ? null : row.birthYear, this.now);
  }

  onKidSlider(i: number, event: Event): void {
    const v = +(event.target as HTMLInputElement).value;
    const { max } = this.kidYearRange();
    const row = this.kidRows()[i];
    const next: KidFormRow = v > max
      ? { ...row, birthYear: null, birthMonth: null, undecided: true }
      : { ...row, birthYear: v, birthMonth: null, undecided: false };
    if (next.birthYear === row.birthYear && next.undecided === row.undecided) return;
    this.patchKids({ kids: this.kidRows().map((r, idx) => (idx === i ? next : r)) });
    void tickHaptic();
  }

  // ── Kids: cost ────────────────────────────────────────────────────────────

  /** Crossfades between the two stats (WAAPI); a tap also cancels the pending automatic flip. */
  flipCostStat(): void {
    this.clearCostTimer();
    const next = this.costStat() === 'A' ? 'B' : 'A';
    const host = this.el.nativeElement;
    const a = host.querySelector<HTMLElement>('.stat-a');
    const b = host.querySelector<HTMLElement>('.stat-b');
    if (!this.reducedMotion() && a && b) {
      const [out, into] = next === 'B' ? [a, b] : [b, a];
      out.animate([{ opacity: 1 }, { opacity: 0 }], { duration: COST_FADE_MS, fill: 'forwards' });
      into.animate([{ opacity: 0 }, { opacity: 1 }], { duration: COST_FADE_MS, fill: 'forwards' });
    }
    this.costStat.set(next);
  }

  // ── Kids: fund switches ───────────────────────────────────────────────────

  fundOn(kind: FundKind): boolean {
    const k = this.form().kids;
    if (kind === 'baby') return k.firstYearFund;
    if (kind === 'college') return k.collegeChoice !== 'none';
    return k.at18Choice !== 'none';
  }

  setFund(kind: FundKind, on: boolean): void {
    if (kind === 'baby') {
      this.patchKids({ firstYearFund: on });
      return;
    }
    if (kind === 'college') {
      this.collegeSkipPending.set(false);
      this.collegeCustomOpen.set(false);
      this.setCollege(on ? DEFAULT_COLLEGE : 'none');
      return;
    }
    const amount = on ? DEFAULT_AT_18 : 0;
    this.nestAmount.set(amount);
    this.patchKids({ brokerageTarget: on ? amount : null, at18Choice: at18ChoiceFor(amount) });
  }

  fundLine(kind: FundKind): string {
    if (!this.fundOn(kind)) {
      const timings = this.kidRows().map(kidTiming);
      return `Saves you ${formatSavings(fundSavingsRange(kind, timings, this.now, this.rate()))}.`;
    }
    if (kind === 'college') return '4 years of tuition, in a 529.';
    if (kind === 'nest') return 'Invested now, theirs at 18.';
    const timings = this.kidRows().map(kidTiming);
    const ranks = kidRanks(timings);
    const unborn = timings
      .map((t, i) => (isKidNotYetBorn(t.birthYear, t.birthMonth, this.now) ? ranks[i] : null))
      .filter((r): r is number => r !== null);
    if (unborn.length === 1) return `${fmtMoney(firstYearTargetForRank(unborn[0]))} ready on day one.`;
    return 'Ready on day one, for each one.';
  }

  fundLineTone(kind: FundKind): 'normal' | 'saves' {
    return this.fundOn(kind) ? 'normal' : 'saves';
  }

  // ── Kids: college and nest egg ────────────────────────────────────────────

  pickCollege(choice: CollegeChoice): void {
    this.error.set(null);
    if (choice === 'none') {
      this.collegeSkipPending.set(true);
      return;
    }
    this.collegeSkipPending.set(false);
    this.setCollege(choice);
  }

  toggleCollegeCustom(): void {
    const open = !this.collegeCustomOpen();
    this.collegeCustomOpen.set(open);
    this.collegeSkipPending.set(false);
    if (open) this.setCollegeCustom(this.collegeCustomAmount());
    else if (this.form().kids.collegeChoice === 'custom') this.setCollege(DEFAULT_COLLEGE);
  }

  setCollegeCustom(amount: number): void {
    this.patchKids({ collegeChoice: 'custom', plan529Target: amount });
  }

  setNest(amount: number): void {
    this.nestAmount.set(amount);
    this.error.set(null);
    // $0 is held on the slider and committed as "off" by Continue, so this step stays put.
    if (amount <= 0) return;
    this.patchKids({ brokerageTarget: amount, at18Choice: at18ChoiceFor(amount) });
  }

  // ── Other types ───────────────────────────────────────────────────────────

  setPrice(v: number): void {
    this.patch({ price: v });
  }

  setDownPayment(v: DownPayment): void {
    if (v === 'full') this.patch({ fullPrice: true });
    else this.patch({ fullPrice: false, downPaymentPct: v });
  }

  onYearSlider(event: Event): void {
    const v = +(event.target as HTMLInputElement).value;
    if (v === this.form().targetYear) return;
    this.patch({ targetYear: v });
    void tickHaptic();
  }

  setTrips(n: number): void {
    this.patch({ tripsPerYear: Math.max(1, Math.min(10, Math.round(n))) });
  }

  setBudget(v: number): void {
    this.patch({ budgetPerTrip: v });
  }

  setExpenses(v: number): void {
    this.patch({ monthlyExpenses: v });
  }

  setMonths(m: 3 | 6): void {
    this.patch({ months: m });
  }

  setByYears(n: number): void {
    this.patch({ targetYear: this.now.getFullYear() + n });
  }

  setTargetAmount(v: number): void {
    this.patch({ targetAmount: v });
  }

  setIcon(icon: GoalIcon): void {
    this.patch({ icon });
  }

  /** The filled part of a native range track, the same paint the slider control uses. */
  trackFill(value: number, min: number, max: number): string {
    const span = max - min;
    const pct = span > 0 ? Math.max(0, Math.min(100, ((value - min) / span) * 100)) : 0;
    const ink = 'var(--bf-ink, #fff)';
    const rest = 'var(--bf-track, rgba(255,255,255,.22))';
    return `linear-gradient(to right, ${ink} 0%, ${ink} ${pct}%, ${rest} ${pct}%)`;
  }

  // ── Internals ─────────────────────────────────────────────────────────────

  private rate(): number {
    return this.rates.annualReturn();
  }

  private simpleMonthly(target: number): number {
    const f = this.form();
    return proposeSimplePlan({
      target,
      balance: this.existing()?.currentBalance ?? 0,
      targetYear: f.targetYear ?? this.now.getFullYear(),
      marketRate: this.rate(),
      forceCash: false,
      rateKindOverride: null,
      now: this.now,
    }).monthly;
  }

  private patch(partial: Partial<GoalForm>): void {
    this.error.set(null);
    this.form.update(f => ({ ...f, ...partial }));
  }

  private patchKids(partial: Partial<KidsForm>): void {
    this.error.set(null);
    this.form.update(f => ({ ...f, kids: { ...f.kids, ...partial } }));
  }

  private setCollege(choice: CollegeChoice): void {
    this.patchKids({ collegeChoice: choice, plan529Target: choice === 'none' ? null : collegeAmount(choice) });
  }

  /** The form as Continue would commit it: a held Skip or a $0 nest egg becomes the switch being off. */
  private withPendingChoices(form: GoalForm, stepId: QuizStepId): GoalForm {
    if (stepId === 'kids_college' && this.collegeSkipPending()) {
      return { ...form, kids: { ...form.kids, collegeChoice: 'none', plan529Target: null } };
    }
    if (stepId === 'kids_nest' && this.nestAmount() <= 0) {
      return { ...form, kids: { ...form.kids, at18Choice: 'none', brokerageTarget: null } };
    }
    return form;
  }

  private firstStepError(stepId: QuizStepId, form: GoalForm): string | null {
    const errors = validateForm(form, this.now, this.existing());
    const key = Object.keys(errors).find(k => stepOwnsError(stepId, k));
    return key ? errors[key] : null;
  }

  /** The quiz's own starting amounts; defaultForm leaves them blank for the typed inputs it served. */
  private withQuizDefaults(form: GoalForm): GoalForm {
    switch (form.type) {
      case 'HOUSE': return { ...form, price: 650_000 };
      case 'CAR': return { ...form, price: 35_000 };
      case 'VACATION': return { ...form, budgetPerTrip: 5_000 };
      case 'EMERGENCY_FUND': return { ...form, monthlyExpenses: 4_000 };
      case 'WEDDING': return { ...form, targetAmount: 30_000 };
      case 'CUSTOM': return { ...form, targetAmount: 10_000 };
      default: return form;
    }
  }

  private startCostHold(): void {
    this.clearCostTimer();
    this.costStat.set('A');
    this.costTimer = setTimeout(() => {
      this.costTimer = null;
      this.flipCostStat();
    }, COST_HOLD_MS);
  }

  private clearCostTimer(): void {
    if (this.costTimer) clearTimeout(this.costTimer);
    this.costTimer = null;
  }

  private navigateToPlan(): void {
    this.calcTimer = null;
    const existing = this.existing();
    const type: GoalType = this.form().type;
    if (existing) void this.navCtrl.navigateForward(['/goals', existing.id]);
    else void this.navCtrl.navigateForward(['/goals/new', type, 'plan']);
  }
}
