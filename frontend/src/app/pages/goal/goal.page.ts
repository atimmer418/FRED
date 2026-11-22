import {
  CUSTOM_ELEMENTS_SCHEMA, Component, DestroyRef, ElementRef, NgZone, OnInit, ViewChild, computed, inject, signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import {
  IonContent, IonHeader, ModalController, NavController, ViewWillEnter, ViewWillLeave,
} from '@ionic/angular/standalone';
import type { SwiperContainer } from 'swiper/element';
import type { SwiperOptions } from 'swiper/types';
import { firstValueFrom, take } from 'rxjs';

import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';
import { presentActionSheet } from '../../components/fred-action-sheet/fred-action-sheet.component';
import { presentTextPrompt } from '../../components/fred-text-prompt-sheet/fred-text-prompt-sheet.component';
import { GOAL_PAGE_NOW } from './goal-clock';
import { AuthService } from '../../services/auth.service';
import { GoalsService } from '../../services/goals.service';
import { GoalDraft, GoalDraftService } from '../../services/goal-draft.service';
import { ToastService } from '../../services/toast.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { httpStatusOf, isNetworkFailure } from '../../services/analytics/analytics-error.utils';
import { toAmountBand, toCountBand, toPercentBand, toYearsBand } from '../../services/analytics/analytics-bands';
import { GoalAction, Tier } from '../../services/analytics/analytics-events';
import { RateAssumptionsService } from '../../services/rate-assumptions.service';
import {
  AccountState, Goal, GoalConfig, GoalType, GoalWrite, KidInput, KidsConfig, TargetConfig, UnitTally, VacationConfig, unitKey,
} from '../../models/goal.model';
import { isKidNotYetBorn, kidDeadline, simpleTarget, tallyStatus } from '../../utils/goal-plan.utils';
import { todayYmd } from '../../utils/local-date';
import { InvestmentFrequencyUtils } from '../../utils/investment-frequency.utils';
import { kidAgeToday, kidCostFigures, kidsAtHome } from '../../utils/kid-cost.utils';
import { CHILDBIRTH_DOLLAR_YEAR_LABEL, COLLEGE_PRICE_YEAR_LABEL } from '../../constants/kid-costs';
import { McInfoSheetComponent } from '../../components/mc-info-sheet/mc-info-sheet.component';
import { GoalRemoveConfirmSheetComponent } from '../../components/goal-remove-confirm-sheet/goal-remove-confirm-sheet.component';
import {
  GoalUnitDetailSheetComponent, SavingTrayHost, UnitDetail, UnitTitle,
} from '../../components/goal-unit-detail-sheet/goal-unit-detail-sheet.component';
import { InfoNote } from '../../components/goal-plan-info-sheet/info-note';
import { KidTimelineComponent } from '../../components/kid-timeline/kid-timeline.component';
import { TRAY_INSIDE_SELECTOR, TrayHostField } from '../../components/plan-amount-tray/amount-tray-host';
import { AmountTraySlotComponent } from '../../components/plan-amount-tray/amount-tray-slot.component';
import { SkeletonComponent } from '../../components/skeleton/skeleton.component';
import { TallyPillsComponent } from '../../components/tally-pills/tally-pills.component';
import { buildAmountScale, capAbove } from '../../utils/plan-amount-scale';
import { BOTTOM_SHEET_MODAL_OPTS } from '../../utils/bottom-sheet.utils';
import {
  GoalForm, creditsUntilFor, defaultForm, formToConfig, monthYearLabel, planInputsChanged, toLocalIso,
} from './goal-form.model';
import {
  EverydayView, GoalPlanInput, GoalPlanView, KidAccountKey, UnitPill, buildGoalPlanView, fmtCompact, fmtMoney,
} from './goal-plan.view-model';

/** A kid's balance: the 529, the brokerage, or the baby fund. */
export type KidBalanceKey = KidAccountKey | 'firstYear';

/** The simple or vacation goal's Saved so far tile, the one tray field on this page. */
export type BalanceField = 'current' | 'vacation';

/** Which balance an edit lands on. */
export type BalancePath = BalanceField | { kid: number; account: KidBalanceKey };

export type PlanMode = 'preview-new' | 'preview-edit' | 'saved';

/** One funded unit on a kid card. */
export interface KidUnitRow {
  key: KidBalanceKey;
  icon: 'child_care' | 'school' | 'savings';
  title: UnitTitle;
  /** "$47,800 by 2042", "$20,800 by Jun 2028". */
  targetLine: string;
  pill: UnitPill;
  monthly: number;
  finished: boolean;
  balance: number;
  /** What the unit saves toward, in dollars: the cap of its Saved so far tray. */
  target: number;
}

export interface KidCard {
  index: number;
  label: string;
  /** "Born 2024 · age 2", "Born 2026 · under 1", "This year", "Arrives 2028", "Not dated yet". */
  subLine: string;
  /** The card's header figure: the kid's whole month, investMonthly plus the everyday figure. */
  monthly: number;
  /** What this kid's unfinished units invest a month. */
  investMonthly: number;
  /** This kid's everyday cost line, from the view in config order. */
  everyday: EverydayView;
  units: KidUnitRow[];
}

const HERO_COUNT_MS = 600;
/** The Saved so far tray's minus and plus step. */
const BALANCE_FINE = 25;

/** A kid card's everyday line when the view carries none: nothing a month. */
const NO_EVERYDAY: EverydayView = { monthly: 0, label: 'Everyday', subLine: '', fromLine: null, state: 'grown' };

/** The server's unit key for a simple or vacation goal's one tally. */
const GOAL_UNIT = 'goal';

/** Every word the status chip can read. A new chip word must be added here and given a tone. */
type ChipText = 'Plan started' | 'Plan updated' | 'On plan' | 'Ahead' | 'Behind' | 'Done';

/** What the menu's Edit plan row says the quiz will ask about, per goal type. */
const EDIT_PLAN_HELPER: Record<GoalType, string> = {
  KIDS: 'Kids, costs and the return you assume',
  HOUSE: 'Price, down payment and the year',
  CAR: 'Price, down payment and the year',
  VACATION: 'Trips a year and the budget per trip',
  EMERGENCY_FUND: 'Monthly expenses, months covered and the year',
  WEDDING: 'The amount and the year',
  CUSTOM: 'The amount and the year',
};

/** The header menu's rows. Remove chains into GoalRemoveConfirmSheet as before. */
type MenuId = 'edit' | 'rename' | 'complete' | 'remove';

const STATUS_CHIP: Record<GoalPlanView['status']['kind'], ChipText> = {
  started: 'Plan started',
  imported: 'Plan updated',
  on_plan: 'On plan',
  ahead: 'Ahead',
  behind: 'Behind',
  done: 'Done',
};

/** The unit sheet's Status tone for each chip word. Keyed by ChipText, so a missing word fails to compile. */
const STATUS_TONE: Record<ChipText, NonNullable<UnitDetail['status']>['tone']> = {
  'Ahead': 'good',
  'On plan': 'good',
  'Done': 'good',
  'Behind': 'warn',
  'Plan started': 'muted',
  'Plan updated': 'muted',
};

const UNIT_TITLE: Record<KidBalanceKey, UnitTitle> = { firstYear: 'Baby fund', plan529: 'College', brokerage: 'Nest egg' };
const UNIT_ICON: Record<KidBalanceKey, KidUnitRow['icon']> = { firstYear: 'child_care', plan529: 'school', brokerage: 'savings' };

/**
 * The one-screen plan page. `/goals/:id` shows a saved goal (balances and names save as you go;
 * the header menu edits, renames, completes or removes it). The preview routes
 * (`/goals/new/:type/plan`, `/goals/:id/edit/plan`) render the quiz's draft from GoalDraftService
 * with a Start / Update CTA; a preview with no draft (a reload) falls back to my-profile.
 */
@Component({
  selector: 'app-goal',
  standalone: true,
  imports: [
    CommonModule, IonContent, IonHeader, KeyboardAvoidDirective, KidTimelineComponent, AmountTraySlotComponent, SkeletonComponent,
    TallyPillsComponent,
  ],
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  templateUrl: './goal.page.html',
  styleUrls: ['./goal.page.scss'],
})
export class GoalPage implements OnInit, ViewWillEnter, ViewWillLeave {
  private readonly root = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly route = inject(ActivatedRoute);
  private readonly navCtrl = inject(NavController);
  private readonly modalCtrl = inject(ModalController);
  private readonly zone = inject(NgZone);
  private readonly goalsService = inject(GoalsService);
  private readonly draftService = inject(GoalDraftService);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly toastService = inject(ToastService);
  private readonly analytics = inject(AnalyticsService);
  private readonly rates = inject(RateAssumptionsService);
  private readonly now = inject(GOAL_PAGE_NOW)();

  readonly fmtMoney = fmtMoney;
  readonly fmtCompact = fmtCompact;

  /** True on a preview route (route data), whatever the draft holds. */
  private readonly previewRoute = signal(false);
  /** The quiz's draft, copied here so clearing the service after a save never blanks the page. */
  private readonly draft = signal<GoalDraft | null>(null);
  /** The saved goal (saved mode and preview-edit), else null. */
  readonly existing = signal<Goal | null>(null);
  /** True once the route resolved to a goal or a draft. */
  readonly ready = signal(false);
  readonly saving = signal(false);
  /** A Missed one or Doubled up post is in flight: the pills wait for it. */
  readonly tallyBusy = signal(false);
  /**
   * The one return assumption, shared app-wide and set only on the Freedom Plan page. Read live,
   * so a plan opened after a change there already shows it.
   */
  readonly annualReturn = this.sharedRate();
  /** Pay frequency of the user's FRED schedule, '' when none (hides the per-paycheck figures). */
  readonly frequency = signal('');
  /** The kid card whose name is an input right now. */
  readonly renaming = signal<number | null>(null);
  /** The kid card the pager is on. */
  readonly activeKid = signal(0);
  /** The hero figure mid count-up; null once it tracks the view. */
  private readonly heroCounting = signal<number | null>(null);
  /**
   * Balances typed in a preview before the goal (or the account) exists. They feed the plan input
   * and so the POST / PUT body; the server anchors the plan at them. Kid balances by kid index.
   */
  private readonly draftBalances = signal<{
    current: number;
    vacation: number;
    kids: Array<Partial<Record<KidBalanceKey, number>>>;
  }>({ current: 0, vacation: 0, kids: [] });
  /**
   * The page's goal writes run one after another: a balance PUT, then a rename or Update, each
   * built from the goal the previous write returned. Never rejects (see queueWrite).
   */
  private pendingWrite: Promise<void> = Promise.resolve();

  @ViewChild('pager') set pagerRef(ref: ElementRef<SwiperContainer> | undefined) {
    this.initPager(ref?.nativeElement);
  }

  readonly mode = computed<PlanMode>(() => {
    if (!this.previewRoute()) return 'saved';
    return this.draft()?.existing ? 'preview-edit' : 'preview-new';
  });

  /** Imported from v2 and never planned: the first save is its first plan. */
  readonly isImported = computed(() => {
    const goal = this.existing();
    return !!goal && goal.clientId !== null && goal.anchorMonthly === 0;
  });

  /** Preview-edit only: saving will re-anchor, so the preview is anchored at now and reads Plan started. */
  readonly planChanged = computed(() => {
    const goal = this.existing();
    const form = this.draft()?.form;
    return this.mode() === 'preview-edit' && !!goal && !!form && this.reanchorsOnSave(form, goal);
  });

  /** The plan as the view model reads it: the draft in a preview, the stored goal otherwise. */
  readonly planInput = computed<GoalPlanInput>(() => {
    const goal = this.existing();
    const draft = this.draft();
    if (this.mode() === 'saved') return goal ?? this.placeholder();
    return draft ? this.buildDraft(draft.form, goal) : this.placeholder();
  });

  readonly view = computed<GoalPlanView>(() => {
    const goal = this.existing();
    const mode = this.mode();
    // A saved or unchanged plan keeps its anchored rate kind, so a goal that crosses the 36-month
    // line does not flip to cash with no action from the user. An unplanned import has no kind yet.
    const keepKind = !!goal && !this.isImported() && (mode === 'saved' || !this.planChanged());
    return buildGoalPlanView(this.planInput(), {
      marketRate: this.annualReturn(),
      frequency: this.frequency(),
      now: this.now,
      rateKindOverride: keepKind && goal ? goal.planRateKind : null,
      reanchorPending: mode === 'preview-new' || this.planChanged(),
    });
  });

  readonly title = computed(() => {
    const draft = this.draft();
    if (this.mode() !== 'saved' && draft) return draft.form.name.trim() || 'Goal';
    return this.existing()?.name || 'Goal';
  });

  readonly ctaLabel = computed<string | null>(() => {
    switch (this.mode()) {
      case 'preview-new': return 'Start this plan';
      case 'preview-edit': return 'Update my plan';
      default: return null;
    }
  });

  readonly isDueNow = computed(() => this.view().heroLabel === 'Due now');

  /** The hero figure: the count-up value while it runs, else the live monthly. */
  readonly heroAmount = computed(() => this.heroCounting() ?? this.view().monthlyTotal);

  /** Plan started / On plan / Ahead / Behind / Done, from the tally (which is never stale). */
  readonly statusChip = computed<ChipText>(() => STATUS_CHIP[this.view().status.kind]);

  /** The "How FRED plans this" notes a unit sheet's info button opens, skipping the lines a plan lacks. */
  readonly infoNotes = computed<InfoNote[]>(() => {
    const v = this.view();
    const notes: Array<[string, string, string | null]> = [
      ['account_balance_wallet', 'How FRED helps.', v.howFredHelps],
      ['checklist', 'What the monthly covers.', v.coversLine],
      ['trending_up', 'Why invest it.', v.whyInvestLine],
    ];
    return notes
      .filter((n): n is [string, string, string] => !!n[2])
      .map(([icon, lead, text]) => ({ icon, lead, text }));
  });

  /**
   * Every type but KIDS: "Saved $900 · 3% of your $30K", then " · Next $450 Jan 1" and
   * " · Behind by $450 since Nov 15" when the tally has them.
   */
  readonly bandText = computed(() => {
    const t = this.view().tallyLine;
    if (!t) return '';
    return [t.saved, t.part, t.next, t.net].filter((part): part is string => !!part).join(' · ');
  });

  /** The tally behind the "Saved so far" tile of a simple or vacation goal, or the stored balance before one is read. */
  readonly simpleBalance = computed(() => {
    const v = this.view();
    const tally = this.planInput().tally?.tally;
    if (tally !== undefined && tally !== null) return tally;
    return v.vacation ? v.vacation.savedThisYear : this.planInput().currentBalance;
  });

  /**
   * The share Missed one and Doubled up move, from the server's tally. 0 hides the pills: a preview,
   * a kids goal (its pills live in the unit sheet), a done or imported plan, or no credit to come.
   */
  readonly pillShare = computed(() => {
    const kind = this.view().status.kind;
    if (this.mode() !== 'saved' || this.view().kids || kind === 'done' || kind === 'imported') return 0;
    return this.existing()?.tally?.nextCreditAmount ?? 0;
  });

  /** Which balance the tile edits: a vacation's year of trips, else the goal's balance. */
  readonly balanceField = computed<BalanceField>(() => (this.view().vacation ? 'vacation' : 'current'));
  /** The slider's value while the tray is open, saved when it closes; null when nothing is pending. */
  readonly balanceDraft = signal<number | null>(null);
  /** What the tile shows: the draft while one is pending, else the stored balance. */
  readonly shownBalance = computed(() => this.balanceDraft() ?? this.simpleBalance());
  /** The tile's cap: the goal's target, or a vacation's year of trips. */
  private readonly balanceTarget = computed(() => {
    const v = this.view();
    return v.vacation ? v.vacation.annual : v.simple?.target ?? 0;
  });
  /**
   * A stored balance already past the target lifts the cap above it (capAbove), so the figure can
   * still go up and a touch never pulls it down. From the stored balance, not the draft: the
   * scale must not move under a finger.
   */
  readonly balanceScale = computed(() => buildAmountScale(0, capAbove(this.balanceTarget(), this.simpleBalance()), BALANCE_FINE));
  /**
   * The tile's slide tray. The value rides in balanceDraft while the tray is open (a PUT per
   * detent would flood the write queue); closing it, however it closes, saves the draft.
   */
  readonly tray = new SavingTrayHost<BalanceField>({
    root: this.root,
    destroyRef: inject(DestroyRef),
    fields: {
      current: this.balanceTrayField('Saved so far'),
      vacation: this.balanceTrayField('Saved so far this year'),
    },
    insideSelector: `${TRAY_INSIDE_SELECTOR}, .gp-tile-btn, .gp-done`,
    labelSelector: '.fd-tile-label.active',
    doneSelector: '.gp-done',
  }, field => this.commitBalance(field));

  readonly kidCards = computed<KidCard[]>(() => {
    const v = this.view();
    const accounts = v.kids;
    if (!accounts) return [];
    const kids = (this.planInput().config as KidsConfig).kids ?? [];
    return kids.map((kid, i) => {
      const units: KidUnitRow[] = [];
      const fund = v.firstYear?.rows.find(r => r.kidIndex === i);
      if (fund && !fund.hidden) {
        units.push({
          key: 'firstYear', icon: UNIT_ICON.firstYear, title: UNIT_TITLE.firstYear,
          targetLine: `${fmtMoney(fund.plan.target)} by ${monthYearLabel(fund.plan.due)}`,
          pill: fund.pill, monthly: fund.plan.monthly, finished: fund.plan.finished, balance: fund.balance,
          target: fund.plan.target,
        });
      }
      for (const key of ['plan529', 'brokerage'] as KidAccountKey[]) {
        const row = accounts.find(a => a.account === key)?.perKid[i];
        if (!row) continue;
        units.push({
          key, icon: UNIT_ICON[key], title: UNIT_TITLE[key],
          targetLine: `${fmtMoney(row.plan.milestones.at18)} by ${row.plan.deadline.year}`,
          pill: row.pill, monthly: row.plan.monthly, finished: false, balance: row.balance,
          target: row.plan.milestones.at18,
        });
      }
      const investMonthly = units.reduce((sum, u) => sum + (u.finished ? 0 : u.monthly), 0);
      const everyday = v.everyday?.[i] ?? NO_EVERYDAY;
      return {
        index: i,
        label: kid.label.trim() || `Kid ${i + 1}`,
        subLine: this.kidSubLine(kid),
        monthly: investMonthly + everyday.monthly,
        investMonthly,
        everyday,
        units,
      };
    });
  });

  // ── Lifecycle ───────────────────────────────────────────────────────────────

  async ngOnInit(): Promise<void> {
    const params = this.route.snapshot.paramMap;
    const typeParam = params.get('type');
    const idParam = params.get('id');
    this.loadFrequency();

    if (this.route.snapshot.data?.['preview']) {
      this.previewRoute.set(true);
      const draft = this.draftService.draft();
      // A reload of a preview URL finds no draft, and a draft for another goal is not this page's.
      const matches = !!draft
        && (typeParam === null || draft.form.type === typeParam)
        && (idParam === null || draft.existing?.id === Number(idParam));
      if (!draft || !matches) {
        this.goBack();
        return;
      }
      this.draft.set(draft);
      this.existing.set(draft.existing);
      this.ready.set(true);
      this.startCountUp();
      return;
    }

    const id = Number(idParam);
    if (idParam === null || !Number.isInteger(id) || id <= 0) {
      this.goBack();
      return;
    }
    let goal = this.goalsService.goals().find(g => g.id === id);
    if (!goal) {
      try {
        await firstValueFrom(this.goalsService.load());
      } catch (err) {
        if (httpStatusOf(err) === 401) {
          this.toastService.showToast('Your session ended. Sign in again to save this plan.', 'danger');
          this.authService.logout();
          return;
        }
        this.toastService.showToast('Could not load this goal. Try again.', 'danger');
        this.goBack();
        return;
      }
      goal = this.goalsService.goals().find(g => g.id === id);
    }
    if (!goal) {
      this.goBack();
      return;
    }
    this.existing.set(goal);
    this.ready.set(true);
    this.startCountUp();
  }

  /**
   * Back from an edit preview lands here: pick up the goal the Update wrote, so the hero matches
   * what was just saved.
   */
  ionViewWillEnter(): void {
    const goal = this.existing();
    if (this.mode() !== 'saved' || !goal) return;
    const latest = this.goalsService.goals().find(g => g.id === goal.id);
    if (latest && latest !== goal) this.existing.set(latest);
  }

  /** Leaving the page: the tray snaps shut, and a pending balance saves on the way out. */
  ionViewWillLeave(): void {
    this.tray.close(true);
  }

  /**
   * A preview's back returns to the quiz beneath; a saved page returns to my-profile. The draft is
   * left in place: the quiz reads it to tell "back to tweak an answer" from "my plan was created",
   * and clears it itself when it closes. Start and Update clear it here.
   */
  goBack(): void {
    if (this.previewRoute() && this.ready()) {
      void this.navCtrl.back();
      return;
    }
    void this.navCtrl.navigateBack('/my-profile');
  }

  // ── Header menu (saved mode) ─────────────────────────────────────────────────

  async openMenu(): Promise<void> {
    const goal = this.existing();
    if (!goal || this.mode() !== 'saved') return;
    const v = this.view();
    // The subtitle reads like the hero: the figure it shows, then the status chip.
    const lead = this.isDueNow() ? `${fmtMoney(v.dueNow)} due now` : `${fmtMoney(v.monthlyTotal)}/mo`;
    const picked = await presentActionSheet<MenuId>(this.modalCtrl, {
      header: { icon: goal.icon, title: goal.name, subtitle: `${lead} · ${this.statusChip()}` },
      actions: [
        { id: 'edit', icon: 'tune', label: 'Edit plan', helper: EDIT_PLAN_HELPER[goal.type] },
        { id: 'rename', icon: 'edit', label: 'Rename', helper: goal.name },
        ...(goal.type !== 'VACATION'
          ? [goal.completed
              ? { id: 'complete' as const, icon: 'undo', label: 'Reopen', helper: 'Puts it back among your active goals' }
              : { id: 'complete' as const, icon: 'check_circle', label: 'Mark completed', helper: 'Keeps it on your profile as done' }]
          : []),
        { id: 'remove', icon: 'delete', label: 'Remove goal', helper: 'Its plan goes with it', destructive: true },
      ],
    });
    switch (picked) {
      case 'edit': void this.editPlan(); break;
      case 'rename': void this.renameGoal(); break;
      case 'complete': void this.markCompleted(); break;
      case 'remove': void this.confirmRemove(); break;
    }
  }

  /** Pushes the quiz prefilled with every stored answer. */
  async editPlan(): Promise<void> {
    const goal = this.existing();
    if (!goal) return;
    this.flushEdits();
    await this.pendingWrite;
    await this.navCtrl.navigateForward(['/goals', goal.id, 'edit']);
  }

  /** Renames the goal itself (kids are renamed on their cards). Not a plan input. */
  async renameGoal(): Promise<void> {
    const goal = this.existing();
    if (!goal) return;
    // The prompt trims and never resolves an empty string on save.
    const name = await presentTextPrompt(this.modalCtrl, { title: 'Rename goal', label: 'Name', value: goal.name, maxlength: 50 });
    if (name === null) return;
    await this.queueWrite(g => (g.name === name ? null : { name }));
  }

  // ── CTA (preview modes) ─────────────────────────────────────────────────────

  /** POSTs the draft, then replaces this preview with the saved page. */
  async startPlan(): Promise<void> {
    if (this.saving() || this.mode() !== 'preview-new') return;
    const draft = this.draft();
    if (!draft) return;
    this.flushEdits();
    this.saving.set(true);
    try {
      await this.pendingWrite;
      const body = this.draftBody(draft.form, null);
      const created = await firstValueFrom(this.goalsService.create(body));
      this.trackGoalChange('created', draft.form.type, draft.form.icon);
      this.toastService.showToast('Plan started', 'success');
      await this.router.navigate(['/goals', created.id], { replaceUrl: true });
      this.draftService.clear();
    } catch (err) {
      await this.handleWriteError(err);
    } finally {
      this.saving.set(false);
    }
  }

  /** PUTs the edited draft, then pops back to the saved page beneath the quiz (Ruling P4). */
  async updatePlan(): Promise<void> {
    if (this.saving() || this.mode() !== 'preview-edit') return;
    const draft = this.draft();
    if (!draft) return;
    // A balance typed and not yet blurred is written first, and Update waits for it, so this body
    // is built from the goal that write returned.
    this.flushEdits();
    this.saving.set(true);
    try {
      await this.pendingWrite;
      const goal = this.existing();
      if (!goal) return;
      const wasImported = this.isImported();
      const body = this.draftBody(draft.form, goal);
      await firstValueFrom(this.goalsService.update(goal.id, body));
      this.trackGoalChange('updated', draft.form.type, draft.form.icon);
      this.toastService.showToast(wasImported ? 'Plan started' : 'Plan updated', 'success');
      await this.navCtrl.navigateBack(['/goals', goal.id]);
      this.draftService.clear();
    } catch (err) {
      await this.handleWriteError(err);
    } finally {
      this.saving.set(false);
    }
  }

  // ── Kid cards ───────────────────────────────────────────────────────────────

  onKidSlide(event: Event): void {
    const swiper = (event.target as SwiperContainer).swiper;
    this.activeKid.set(swiper?.activeIndex ?? 0);
  }

  beginRename(kidIndex: number): void {
    this.renaming.set(kidIndex);
  }

  /** Enter, the check or a blur: reads the field and commits. */
  commitRenameFrom(kidIndex: number, event: Event): void {
    const input = (event.target as HTMLElement).closest('.gp-kname')?.querySelector<HTMLInputElement>('.gp-rename')
      ?? (event.target as HTMLInputElement);
    void this.commitRename(kidIndex, input?.value ?? '');
  }

  /**
   * Saves a kid's name. Saved goal: a PUT chained on pendingWrite with the body built from
   * existing() when its turn comes, so a balance written just before is carried, not reverted.
   * `label` is not a plan input, so nothing re-anchors. Preview: patches the draft.
   */
  async commitRename(kidIndex: number, label: string): Promise<void> {
    if (this.renaming() === kidIndex) this.renaming.set(null);
    const trimmed = label.trim().slice(0, 30);
    if (!trimmed) return;
    if (this.mode() !== 'saved') {
      this.patchDraftKids(kids => ({ ...kids, kids: kids.kids.map((k, i) => (i === kidIndex ? { ...k, label: trimmed } : k)) }));
      return;
    }
    return this.queueWrite(goal => {
      const c = goal.config as KidsConfig;
      const kid = c.kids[kidIndex];
      if (!kid || kid.label === trimmed) return null;
      return { config: { ...c, kids: c.kids.map((k, i) => (i === kidIndex ? { ...k, label: trimmed } : k)) } };
    });
  }

  /**
   * Opens the detail sheet for one kid unit or the kid's everyday line: milestones, notes, sources,
   * per paycheck, the Saved so far balance (units only) and the info button. Resolves with the
   * presented modal, or null when there is nothing to open (an unpriced everyday line).
   */
  async openUnitDetail(kidIndex: number, unit: KidBalanceKey | 'everyday'): Promise<HTMLIonModalElement | null> {
    if (!this.unitDetail(kidIndex, unit)) return null;
    const detail = computed(() => this.unitDetail(kidIndex, unit));
    const modal = await this.modalCtrl.create({
      component: GoalUnitDetailSheetComponent,
      componentProps: {
        detail,
        // A saved goal logs the figure as a `set` on the unit's tally; a preview keeps it in the draft.
        onBalance: unit === 'everyday'
          ? null
          : (value: number) => this.mode() === 'saved'
            ? this.setTally({ kid: kidIndex, account: unit }, value)
            : this.updateBalance({ kid: kidIndex, account: unit }, value),
        onTally: unit === 'everyday'
          ? null
          : (kind: 'missed' | 'extra') => this.onTally({ kid: kidIndex, account: unit }, kind),
        notes: this.infoNotes(),
      },
      ...BOTTOM_SHEET_MODAL_OPTS,
    });
    await modal.present();
    return modal;
  }

  // ── Balances ────────────────────────────────────────────────────────────────

  /**
   * Missed one or Doubled up on a saved goal: one ledger entry, queued behind the page's other
   * writes. The toast reads the tally the server returns and offers Undo, which posts the opposite
   * entry for exactly what moved (a miss cut short at $0 moved less than the share). Undo's own
   * toast offers no second Undo, and neither does a tap that finished the unit (the server refuses
   * a Missed one or Doubled up there). A miss that moved nothing (already $0) says so. A unit that
   * finished meanwhile (409 UNIT_FINISHED) refreshes the goal with no toast, and the pills go.
   * `amount` is the Undo's exact figure; a tap sends none and the server moves one share.
   */
  async onTally(path: BalancePath, kind: 'missed' | 'extra', amount?: number, offerUndo = true): Promise<void> {
    if (this.mode() !== 'saved' || !this.existing()) return;
    this.tallyBusy.set(true);
    try {
      const result = await this.queueAdjust(path, kind, amount);
      if (!result) return;
      const tally = this.tallyFor(result.goal, path)?.tally ?? this.storedBalance(result.goal, path);
      // In cents: a floating-point difference of two cent figures can carry noise past 2 decimals,
      // which the server refuses.
      const moved = Math.round(Math.abs(result.before - tally) * 100) / 100;
      if (moved === 0) {
        if (kind === 'missed') {
          this.toastService.show({ severity: 'info', message: 'Saved so far is at $0; nothing more to take off.' });
        }
        return;
      }
      const undo = offerUndo && !this.unitFinished(result.goal, path)
        ? { label: 'Undo', run: () => void this.onTally(path, kind === 'missed' ? 'extra' : 'missed', moved, false) }
        : undefined;
      this.toastService.show({
        severity: 'success',
        message: `Saved so far is now ${fmtMoney(tally)}.`,
        ...(undo ? { action: undo } : {}),
      });
    } finally {
      this.tallyBusy.set(false);
    }
  }

  /**
   * Whether a unit takes no more Missed one or Doubled up, exactly as the server decides it (it
   * answers 409 then): the goal is completed, the tally is at its target, or today is past the
   * unit's last payday (creditsUntil).
   */
  private unitFinished(goal: Goal, path: BalancePath): boolean {
    if (goal.completed) return true;
    const account = typeof path === 'string' ? null : (goal.config as KidsConfig).kids[path.kid]?.accounts[path.account];
    const t = this.tallyFor(goal, path);
    const target = typeof path === 'string' ? goal.target : account?.target;
    const tally = t?.tally ?? this.storedBalance(goal, path);
    if (target != null && target > 0 && tally >= target) return true;
    const creditsUntil = t?.creditsUntil ?? (typeof path === 'string' ? null : account?.creditsUntil);
    return !!creditsUntil && todayYmd(this.now) > creditsUntil;
  }

  /** The server's unit key for a balance: "goal" for a simple or vacation goal, `kid:0:plan529` for a kid account. */
  unitFor(path: BalancePath): string {
    return typeof path === 'string' ? GOAL_UNIT : unitKey(path.kid, path.account);
  }

  private tallyFor(goal: Goal, path: BalancePath): UnitTally | null {
    return (typeof path === 'string' ? goal.tally : goal.unitTallies?.[this.unitFor(path)]) ?? null;
  }

  /**
   * The simple or vacation goal's Saved so far tray closed: its draft, if any, is saved. On a saved
   * goal that is a `set` entry on the tally; a preview records it in the draft as before.
   */
  private commitBalance(field: BalanceField): void {
    const draft = this.balanceDraft();
    if (draft === null) return;
    const saved = this.mode() === 'saved' ? this.setTally(field, draft) : this.updateBalance(field, draft);
    void saved.then(() => {
      // The tile keeps the draft until the save settles, so it never flashes the old figure; after
      // a failed save (the page toasts) it shows the stored balance again. A tray re-opened in the
      // meantime holds a newer draft, which its own close saves; until that write lands the tile
      // keeps showing it, so an earlier write settling first never drops the tile to its figure.
      if (this.tray.open() === null && this.balanceDraft() === draft) this.balanceDraft.set(null);
    });
  }

  private balanceTrayField(label: string): TrayHostField {
    return { label, scale: this.balanceScale, value: this.shownBalance, set: value => this.balanceDraft.set(value) };
  }

  /**
   * Preview: records a starting balance in the draft (sent with the POST / PUT). Saved goal, or an
   * edit preview's stored account: logs it with a PUT of the SAVED goal with just the balance
   * changed, so draft edits never ride along; the server keeps the anchor and stamps the date.
   * Writes are chained on pendingWrite and build their body from existing() when their turn
   * comes, so a second blur is built from the first write's response.
   */
  async updateBalance(path: BalancePath, value: number): Promise<void> {
    const amount = Math.max(0, Math.round(value));
    if (!this.existing() || this.isDraftOnlyAccount(path)) {
      this.draftBalances.update(b => {
        if (path === 'current') return { ...b, current: amount };
        if (path === 'vacation') return { ...b, vacation: amount };
        const kids = [...b.kids];
        kids[path.kid] = { ...kids[path.kid], [path.account]: amount };
        return { ...b, kids };
      });
      return;
    }
    return this.queueWrite(goal => {
      // Fields show whole dollars, so a stored value with cents compares rounded: focus then blur
      // never rewrites it.
      if (path === 'current') {
        return Math.round(goal.currentBalance) === amount ? null : { currentBalance: amount };
      }
      if (path === 'vacation') {
        const c = goal.config as VacationConfig;
        const year = this.now.getFullYear();
        if (Math.round(c.savedThisYear) === amount && c.savedYear === year) return null;
        return { config: { ...c, savedThisYear: amount, savedYear: year } };
      }
      const c = goal.config as KidsConfig;
      const kid = c.kids[path.kid];
      const account = kid?.accounts[path.account];
      if (!account || Math.round(account.balance) === amount) return null;
      const kids = c.kids.map((k, i) => i !== path.kid ? k : {
        ...k,
        accounts: { ...k.accounts, [path.account]: { ...account, balance: amount } },
      });
      return { config: { ...c, kids } };
    });
  }

  /** Posts the exact figure as a `set` entry, skipped when the tally already reads it in whole dollars. */
  private async setTally(path: BalancePath, value: number): Promise<void> {
    await this.queueAdjust(path, 'set', Math.max(0, Math.round(value)));
  }

  // ── Complete / remove ───────────────────────────────────────────────────────

  /** Toggles `completed`. Not offered for vacation goals. */
  async markCompleted(): Promise<void> {
    if (!this.existing() || this.saving()) return;
    this.flushEdits();
    this.saving.set(true);
    try {
      await this.pendingWrite;
      const goal = this.existing();
      if (!goal) return;
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
    if (!this.existing()) return;
    this.flushEdits();
    await this.pendingWrite;
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
      void this.navCtrl.navigateBack('/my-profile');
    } catch (err) {
      await this.handleWriteError(err);
    }
  }

  // ── Internals: hero count-up ────────────────────────────────────────────────

  /** Counts the hero figure up from 0 over 600ms on first render (rAF). Skipped under reduced motion. */
  private startCountUp(): void {
    const target = this.view().monthlyTotal;
    if (this.isDueNow() || !(target > 0) || this.reducedMotion()) return;
    const start = performance.now();
    const step = (t: number) => {
      const p = Math.min(1, (t - start) / HERO_COUNT_MS);
      const eased = 1 - (1 - p) ** 3;
      // Once done the figure follows the live view again (a chip tap, a toggle).
      this.heroCounting.set(p < 1 ? Math.round(this.view().monthlyTotal * eased) : null);
      if (p < 1) requestAnimationFrame(step);
    };
    this.heroCounting.set(0);
    requestAnimationFrame(step);
  }

  private reducedMotion(): boolean {
    try {
      return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    } catch {
      return false;
    }
  }

  // ── Internals: pager ────────────────────────────────────────────────────────

  /** Same setup as the tabs pager; the element is inert in unit tests (not upgraded). */
  private initPager(el: SwiperContainer | undefined): void {
    if (!el || typeof el.initialize !== 'function' || el.swiper) return;
    // One full-width card per view (the slide width comes from .gp-slide), 10px between cards.
    const params: SwiperOptions = { slidesPerView: 'auto', spaceBetween: 10 };
    Object.assign(el, params);
    // Outside the zone, like the tabs pager: Swiper listens to touch and pointer events on the
    // document, and inside the zone every one of them ran change detection, on this page and on
    // the quiz pushed over it (2026-11-11). The (swiperslidechange) binding still runs in Angular.
    this.zone.runOutsideAngular(() => el.initialize());
  }

  // ── Internals: kid copy ─────────────────────────────────────────────────────

  /**
   * "Born 2024 · age 2", "Born 2026 · under 1", "This year", "Arrives 2028", "Not dated yet"
   * (Ruling R4). Only a born kid carries the age.
   */
  private kidSubLine(kid: KidInput): string {
    if (kid.birthYear === null) return 'Not dated yet';
    if (kid.birthYear === this.now.getFullYear() && kid.birthMonth === null) return 'This year';
    if (isKidNotYetBorn(kid.birthYear, kid.birthMonth, this.now)) return `Arrives ${kid.birthYear}`;
    const age = kidAgeToday(kid.birthYear, kid.birthMonth, this.now);
    if (age === null) return `Born ${kid.birthYear}`;
    return `Born ${kid.birthYear} · ${age === 0 ? 'under 1' : `age ${age}`}`;
  }

  /**
   * The unit's own Status from its tally: "Behind by $450" / "Ahead by $450" when it is off plan,
   * else the chip word. A preview has no tally and reads the goal's chip.
   */
  private unitStatus(path: BalancePath, target: number | null): NonNullable<UnitDetail['status']> {
    const goal = this.existing();
    if (this.mode() !== 'saved' || !goal) return { text: this.statusChip(), tone: this.statusTone(this.statusChip()) };
    const status = tallyStatus({
      completed: goal.completed, imported: this.isImported(), tally: this.tallyFor(goal, path), target: target ?? 0, today: this.now,
    });
    const chip = STATUS_CHIP[status.kind];
    const text = status.kind === 'behind' ? `${chip} by ${fmtMoney(-status.net)}`
      : status.kind === 'ahead' ? `${chip} by ${fmtMoney(status.net)}` : chip;
    return { text, tone: this.statusTone(chip) };
  }

  /** What the pills move for one unit: the server's next credit, 0 in a preview, when imported, or once the unit is finished. */
  private unitShare(path: BalancePath): number {
    const goal = this.existing();
    if (this.mode() !== 'saved' || !goal || this.isImported() || this.unitFinished(goal, path)) return 0;
    return this.tallyFor(goal, path)?.nextCreditAmount ?? 0;
  }

  /** The unit sheet's Status tone for a chip word: Ahead / On plan / Done good, Behind warn, and so on. */
  private statusTone(chip: ChipText): NonNullable<UnitDetail['status']>['tone'] {
    return STATUS_TONE[chip];
  }

  /** The everyday line's sheet: a FRED estimate, no balance and no status. Null unless priced. */
  private everydayDetail(card: KidCard, kidIndex: number): UnitDetail | null {
    const e = card.everyday;
    if (e.state !== 'priced') return null;
    const kids = (this.planInput().config as KidsConfig).kids;
    const kid = kids[kidIndex];
    const age = kid ? kidAgeToday(kid.birthYear, kid.birthMonth, this.now) : null;
    const atHome = kidsAtHome(kids, this.now);
    return {
      kidLabel: card.label,
      title: 'Everyday',
      kind: 'everyday',
      targetLine: `Age ${age} · FRED estimate`,
      pill: 'Cash',
      monthly: e.monthly,
      perPaycheck: InvestmentFrequencyUtils.toPerPaycheckEquivalent(e.monthly, this.frequency()),
      finished: false,
      balance: null,
      target: null,
      status: null,
      share: 0,
      growthLine: null,
      milestones: [],
      notes: [`${e.subLine}.`, 'Steps up on its own at 3, 6, 9, 12 and 15, the ages where the USDA figures change.'],
      sourcesLine: `FRED estimate: USDA 2015 figures for a middle-income family, adjusted to ${kidCostFigures(atHome).asOfLabel} dollars and to ${atHome} kid${atHome === 1 ? '' : 's'} at home.`,
      sourcesSheetKids: atHome,
    };
  }

  /** The detail sheet's content for one unit or the everyday line, or null when there is none. */
  private unitDetail(kidIndex: number, unit: KidBalanceKey | 'everyday'): UnitDetail | null {
    const v = this.view();
    const card = this.kidCards()[kidIndex];
    if (!card) return null;
    if (unit === 'everyday') return this.everydayDetail(card, kidIndex);
    const row = card.units.find(u => u.key === unit);
    if (!row) return null;
    const kidsCount = (this.planInput().config as KidsConfig).kids.length;
    const perPaycheck = row.finished ? null : InvestmentFrequencyUtils.toPerPaycheckEquivalent(row.monthly, this.frequency());
    const notes: string[] = [];
    let milestones: UnitDetail['milestones'] = [];
    let sourcesLine: string;

    if (unit === 'firstYear') {
      const fund = v.firstYear?.rows.find(r => r.kidIndex === kidIndex);
      if (fund?.plan.rateKind === 'cash') notes.push('Planned as cash at ~4%: money with a date on it stays out of the market.');
      if (fund?.plan.undecided) {
        notes.push(fund.plan.rateKind === 'cash'
          ? 'No date yet: this runway ends within 3 years.'
          : 'No date yet, so this one is invested at your return assumption. Markets can be down when the day comes, so treat it as a head start.');
      }
      sourcesLine = `FRED estimate: first-year average (USDA, adjusted to ${kidCostFigures(kidsCount).asOfLabel} dollars) plus birth costs (Peterson-KFF, ${CHILDBIRTH_DOLLAR_YEAR_LABEL}), rounded up.`;
    } else {
      const account = v.kids?.find(a => a.account === unit)?.perKid[kidIndex];
      if (!account) return null;
      const m = account.plan.milestones;
      const year = (age: number) => (account.relative ? null : account.plan.deadline.year + (age - 18));
      milestones = [
        { label: 'At 18', year: year(18), value: fmtCompact(m.at18) },
        { label: 'At 22', year: year(22), value: fmtCompact(m.at22) },
        { label: 'At 30', year: year(30), value: fmtCompact(m.at30) },
      ];
      if (account.plan.rateKind === 'cash') {
        notes.push('Planned as cash at ~4%, like a high-yield savings account. Money you need within 3 years should stay out of the market.');
      }
      if (account.plan.assumedBirthYear) {
        notes.push('No date yet, so this kid is planned as if born this year. You save a little more than you may need.');
      }
      // Both accounts live outside FRED (Andy, 2026-11-11): FRED keeps score, the parent holds the money.
      sourcesLine = unit === 'plan529'
        ? `A 529 plan you open outside FRED. Four years at ${COLLEGE_PRICE_YEAR_LABEL} College Board prices, which tend to rise. Raise the amount any time from Edit plan.`
        : 'A brokerage account you open outside FRED. Your target, grown at the return you chose; at 22 and at 30 assume it stays invested.';
    }

    return {
      kidLabel: card.label,
      title: row.title,
      kind: unit === 'firstYear' ? 'fund' : 'account',
      targetLine: row.targetLine,
      pill: row.pill,
      monthly: row.monthly,
      perPaycheck,
      finished: row.finished,
      balance: row.balance,
      target: row.target,
      share: this.unitShare({ kid: kidIndex, account: unit }),
      growthLine: unit === 'firstYear' ? null : (v.kids?.find(a => a.account === unit)?.perKid[kidIndex]?.growthLine ?? null),
      status: this.unitStatus({ kid: kidIndex, account: unit }, row.target),
      milestones,
      notes,
      sourcesLine,
      sourcesSheetKids: unit === 'firstYear' ? kidsCount : null,
    };
  }

  // ── Internals: drafts ───────────────────────────────────────────────────────

  private placeholder(): GoalPlanInput {
    return this.buildDraft(defaultForm('CUSTOM', this.now), null);
  }

  private patchDraftKids(update: (kids: GoalForm['kids']) => GoalForm['kids']): void {
    this.draft.update(d => (d ? { ...d, form: { ...d.form, kids: update(d.form.kids) } } : d));
  }

  /** Saving re-anchors: a plan input changed, or this is an imported goal's first plan. */
  private reanchorsOnSave(form: GoalForm, goal: Goal): boolean {
    const pendingFirstPlan = goal.clientId !== null && goal.anchorMonthly === 0;
    return pendingFirstPlan || planInputsChanged(form, goal, this.now);
  }

  /** An edit preview's kid account the saved goal does not hold yet (a kid added, a fund switched on). */
  private isDraftOnlyAccount(path: BalancePath): boolean {
    const goal = this.existing();
    if (!goal || path === 'current' || path === 'vacation') return false;
    const stored = goal.type === 'KIDS' ? (goal.config as KidsConfig).kids[path.kid] : undefined;
    return !stored || !stored.accounts[path.account];
  }

  /** The goal as the draft would save it: the form's inputs, stored (or typed) balances and anchor. */
  private buildDraft(form: GoalForm, goal: Goal | null): GoalPlanInput {
    let config = formToConfig(form, goal, this.now);
    const typed = this.draftBalances();
    if (goal) {
      if (form.type === 'KIDS') config = this.withDraftOnlyBalances(config as KidsConfig, goal);
      const draft: GoalPlanInput = {
        type: form.type,
        name: form.name,
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
      return this.reanchorsOnSave(form, goal) ? this.reanchored(draft) : draft;
    }
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
      name: form.name,
      config,
      currentBalance: form.type === 'KIDS' || form.type === 'VACATION' ? 0 : typed.current,
      completed: false,
      clientId: null,
      anchorBalance: 0,
      anchorMonthly: 0,
      planRate: this.annualReturn(),
      planRateKind: 'market',
      planAnchoredAt: toLocalIso(this.now),
      balanceUpdatedAt: null,
    };
  }

  /** Edit preview: balances typed for accounts the saved goal does not hold yet. Stored accounts keep theirs. */
  private withDraftOnlyBalances(config: KidsConfig, goal: Goal): KidsConfig {
    const typed = this.draftBalances().kids;
    const stored = goal.type === 'KIDS' ? (goal.config as KidsConfig).kids : [];
    const pick = (i: number, key: KidBalanceKey, a: AccountState): AccountState => {
      const value = typed[i]?.[key];
      const draftOnly = !stored[i] || !stored[i].accounts[key];
      return draftOnly && value !== undefined ? { ...a, balance: value } : a;
    };
    return {
      ...config,
      kids: config.kids.map((kid, i) => ({
        ...kid,
        accounts: {
          plan529: pick(i, 'plan529', kid.accounts.plan529),
          brokerage: pick(i, 'brokerage', kid.accounts.brokerage),
          ...(kid.accounts.firstYear ? { firstYear: pick(i, 'firstYear', kid.accounts.firstYear) } : {}),
        },
      })),
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

  /** The POST / PUT body for a draft: live inputs, the preview's balances, and the proposed anchors. */
  private draftBody(form: GoalForm, goal: Goal | null): GoalWrite {
    const draft = this.planInput();
    const view = this.view();
    return {
      type: form.type,
      name: form.name.trim(),
      icon: form.icon,
      config: this.withAccountAnchors(form.type, draft.config, view),
      // KIDS balances live per account in config.
      currentBalance: form.type === 'KIDS' ? 0 : draft.currentBalance,
      completed: goal?.completed ?? false,
      // Invest-only: a KIDS whole month also carries everyday cash, which nothing grades.
      anchorMonthly: view.investMonthly,
      planRate: view.rate,
      planRateKind: view.rateKind,
      // The last day paydays credit and what the tally stops at. KIDS carry theirs per account.
      creditsUntil: creditsUntilFor(form.type, draft.config, undefined, undefined, this.now),
      target: view.kids ? null : view.vacation ? view.vacation.annual : view.simple?.target ?? null,
    };
  }

  /**
   * KIDS: stamp each account's anchorMonthly with that account's proposed monthly, its creditsUntil
   * and its target. A finished baby fund asks for nothing more, so it keeps the anchor it has.
   */
  private withAccountAnchors(type: GoalType, config: GoalConfig, view: GoalPlanView): GoalConfig {
    if (!view.kids) return config;
    const cfg = config as KidsConfig;
    const planFor = (account: KidAccountKey, i: number) => view.kids?.find(a => a.account === account)?.perKid[i]?.plan;
    const stamp = (state: AccountState, i: number, account: KidAccountKey): AccountState => {
      const plan = planFor(account, i);
      return {
        ...state,
        anchorMonthly: plan?.monthly ?? 0,
        creditsUntil: creditsUntilFor(type, cfg, i, account, this.now),
        target: plan?.milestones.at18 ?? null,
      };
    };
    const kids = cfg.kids.map((kid, i) => {
      const fund = kid.accounts.firstYear;
      const fundPlan = view.firstYear?.rows.find(r => r.kidIndex === i)?.plan;
      return {
        ...kid,
        accounts: {
          plan529: stamp(kid.accounts.plan529, i, 'plan529'),
          brokerage: stamp(kid.accounts.brokerage, i, 'brokerage'),
          ...(fund
            ? {
              firstYear: {
                ...fund,
                anchorMonthly: fundPlan && !fundPlan.finished ? fundPlan.monthly : fund.anchorMonthly,
                creditsUntil: creditsUntilFor(type, cfg, i, 'firstYear', this.now),
                target: fundPlan?.target ?? null,
              },
            }
            : {}),
        },
      };
    });
    return { ...cfg, kids };
  }

  // ── Internals: writes ───────────────────────────────────────────────────────

  /**
   * One PUT of the saved goal, queued behind the writes before it. `patchOf` runs when the write's
   * turn comes, against the goal the previous write returned; null means nothing to write. The
   * chain itself never rejects; the caller still sees its own outcome.
   */
  private queueWrite(patchOf: (goal: Goal) => Partial<GoalWrite> | null): Promise<void> {
    return this.enqueue(async () => {
      const goal = this.existing();
      if (!goal) return;
      const patch = patchOf(goal);
      if (!patch) return;
      try {
        const updated = await firstValueFrom(this.goalsService.update(goal.id, this.writeFrom(goal, patch)));
        this.existing.set(updated);
      } catch (err) {
        await this.handleWriteError(err);
      }
    });
  }

  /**
   * One ledger entry on the saved goal, queued like a PUT. Resolves with the server's answer, or
   * null when nothing was posted (a set that changes nothing) or the post failed (already handled).
   */
  private queueAdjust(
    path: BalancePath, kind: 'missed' | 'extra' | 'set', amount?: number,
  ): Promise<{ goal: Goal; capped: boolean; before: number } | null> {
    return this.enqueue(async () => {
      const goal = this.existing();
      if (!goal) return null;
      // Read when this entry's turn comes, so it is the tally the server moves from.
      const before = this.tallyFor(goal, path)?.tally ?? this.storedBalance(goal, path);
      if (kind === 'set' && Math.round(before) === amount) return null;
      try {
        const unit = this.unitFor(path);
        const result = await firstValueFrom(amount === undefined
          ? this.goalsService.adjust(goal.id, unit, kind)
          : this.goalsService.adjust(goal.id, unit, kind, amount));
        this.existing.set(result.goal);
        // Only a posted entry counts (a 409 or a failure lands in the catch). No amount, unit or kid.
        this.analytics.track({ name: 'goal_tally_adjusted', props: { goal_type: goal.type, kind } });
        return { ...result, before };
      } catch (err) {
        await this.handleAdjustError(err);
        return null;
      }
    });
  }

  /** Runs `job` after every write before it. The chain itself never rejects. */
  private enqueue<T>(job: () => Promise<T>): Promise<T> {
    const run = this.pendingWrite.then(job);
    this.pendingWrite = run.then(() => undefined, () => undefined);
    return run;
  }

  /** A unit's stored balance before any tally is read: the goal's, a vacation's year, or a kid account's. */
  private storedBalance(goal: Goal, path: BalancePath): number {
    if (path === 'current') return goal.currentBalance;
    if (path === 'vacation') return (goal.config as VacationConfig).savedThisYear;
    return (goal.config as KidsConfig).kids[path.kid]?.accounts[path.account]?.balance ?? 0;
  }

  /**
   * 409 UNIT_FINISHED: the unit finished under the user (its target or its last payday came). The
   * page picks up the stored goal and says nothing; the pills hide. Anything else is a failed save.
   */
  private async handleAdjustError(err: unknown): Promise<void> {
    const code = err instanceof HttpErrorResponse ? err.error?.code : undefined;
    if (httpStatusOf(err) !== 409 || code !== 'UNIT_FINISHED') {
      await this.handleWriteError(err);
      return;
    }
    const sent = (err as HttpErrorResponse).error?.goal as Goal | undefined;
    const id = this.existing()?.id;
    if (sent && sent.id === id) {
      this.existing.set(sent);
      return;
    }
    try {
      await firstValueFrom(this.goalsService.refresh());
      const latest = this.goalsService.goals().find(g => g.id === id);
      if (latest) this.existing.set(latest);
    } catch {
      // The pills stay; the next tap reports any failure.
    }
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

  /**
   * Settles the edits in flight before the action that follows: a focused field blurs (a kid's
   * rename commits) and the tray closes (its balance draft is queued), so the write chain holds
   * them when the caller awaits it.
   */
  private flushEdits(): void {
    (document.activeElement as HTMLElement | null)?.blur?.();
    this.tray.close();
  }

  /** Whole percent, decimals trimmed (0.10 to "10", 0.085 to "8.5"). */
  pct(fraction: number): string {
    return (fraction * 100).toFixed(2).replace(/\.?0+$/, '');
  }

  private sharedRate(): typeof this.rates.annualReturn {
    this.rates.ensureUser();
    return this.rates.annualReturn;
  }

  private loadFrequency(): void {
    this.authService.getCurrentInvestmentSchedule().subscribe({
      next: (schedule) => this.frequency.set(schedule?.frequency ? String(schedule.frequency).toLowerCase() : ''),
      error: () => this.frequency.set(''),
    });
  }

  /** 403 TIER_LIMIT: nudge, no toast. 401: toast and sign out. Anything else: toast and report. */
  private async handleWriteError(err: unknown): Promise<void> {
    const status = httpStatusOf(err);
    const code = err instanceof HttpErrorResponse ? err.error?.code : undefined;
    if (status === 403 && code === 'TIER_LIMIT') {
      const role = await this.openMultiGoalNudge();
      if (role === 'upgrade') {
        // No IAP flow yet: mirrors my-profile's upgradeToPlusClicked() stub.
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
    this.analytics.trackErrorShown(isNetworkFailure(err) ? 'network' : 'save_failed', 'goals', 'toast', status, true);
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
    this.trackNudge('shown');
    const { role } = await modal.onDidDismiss();
    this.trackNudge(role === 'upgrade' ? 'upgrade_tapped' : 'dismissed');
    return role;
  }

  // ── Internals: analytics ────────────────────────────────────────────────────

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

  /** `saved` is the stored goal for completed/reopened/removed, so unsaved draft edits never leak in. */
  private sendGoalChange(action: GoalAction, type: GoalType, icon: Goal['icon'], saved?: Goal): void {
    const view = saved
      ? buildGoalPlanView(saved, {
        marketRate: saved.planRate,
        frequency: this.frequency(),
        now: this.now,
        rateKindOverride: saved.planRateKind,
      })
      : this.view();
    const config = saved ? saved.config : this.planInput().config;
    const thisYear = this.now.getFullYear();

    let target: number;
    let deadlineYear: number | null;
    if (type === 'KIDS') {
      const c = config as KidsConfig;
      // Unfinished baby funds are part of what the plan saves for.
      const fundTargets = (view.firstYear?.rows ?? [])
        .filter(r => !r.plan.finished)
        .reduce((sum, r) => sum + r.plan.target, 0);
      target = c.kids.length * (c.plan529Target + c.brokerageTarget) + fundTargets;
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

  /** Same event, feature and tier mapping as my-profile's goals nudge. */
  private trackNudge(action: 'shown' | 'upgrade_tapped' | 'dismissed'): void {
    try {
      this.analytics.track({ name: 'paywall_nudge_shown', props: { feature: 'goals', tier: this.nudgeTier(), action } });
    } catch {
      // Analytics never breaks a user flow.
    }
  }

  /** The user's tier as the taxonomy names it. 'piggy' is the legacy name for core; unknown is null. */
  private nudgeTier(): Tier {
    let selected: string | undefined;
    // userProgress$ replays its latest value synchronously; nothing is waited for here.
    this.authService.userProgress$.pipe(take(1)).subscribe(p => (selected = p?.selectedTier ?? undefined)).unsubscribe();
    switch (selected) {
      case 'piggy':
      case 'core': return 'core';
      case 'plus': return 'plus';
      case 'pro': return 'pro';
      default: return null;
    }
  }
}
