import {
  CUSTOM_ELEMENTS_SCHEMA, Component, ElementRef, OnInit, ViewChild, computed, inject, signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import {
  ActionSheetController, AlertController, IonContent, IonHeader, ModalController, NavController, ViewWillEnter,
} from '@ionic/angular/standalone';
import type { SwiperContainer } from 'swiper/element';
import type { SwiperOptions } from 'swiper/types';
import { firstValueFrom, take } from 'rxjs';

import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';
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
  AccountState, Goal, GoalConfig, GoalType, GoalWrite, KidInput, KidsConfig, TargetConfig, VacationConfig,
} from '../../models/goal.model';
import { isKidNotYetBorn, kidDeadline, simpleTarget } from '../../utils/goal-plan.utils';
import { InvestmentFrequencyUtils } from '../../utils/investment-frequency.utils';
import { kidCostFigures } from '../../utils/kid-cost.utils';
import { CHILDBIRTH_DOLLAR_YEAR_LABEL, COLLEGE_PRICE_YEAR_LABEL } from '../../constants/kid-costs';
import { McInfoSheetComponent } from '../../components/mc-info-sheet/mc-info-sheet.component';
import { GoalRemoveConfirmSheetComponent } from '../../components/goal-remove-confirm-sheet/goal-remove-confirm-sheet.component';
import { GoalUnitDetailSheetComponent, UnitDetail, UnitTitle } from '../../components/goal-unit-detail-sheet/goal-unit-detail-sheet.component';
import { KidTimelineComponent } from '../../components/kid-timeline/kid-timeline.component';
import { BOTTOM_SHEET_MODAL_OPTS } from '../../utils/bottom-sheet.utils';
import { GoalForm, defaultForm, formToConfig, monthYearLabel, planInputsChanged, toLocalIso } from './goal-form.model';
import {
  GoalPlanInput, GoalPlanView, KidAccountKey, UnitPill, buildGoalPlanView, fmtCompact, fmtMoney,
} from './goal-plan.view-model';

/** A kid's balance: the 529, the brokerage, or the baby fund. */
export type KidBalanceKey = KidAccountKey | 'firstYear';

/** Which balance an inline field edits. */
export type BalancePath = 'current' | 'vacation' | { kid: number; account: KidBalanceKey };

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
}

export interface KidCard {
  index: number;
  label: string;
  /** "Born 2024", "This year", "Arrives 2028", "Not dated yet". */
  subLine: string;
  monthly: number;
  units: KidUnitRow[];
}

const HERO_COUNT_MS = 600;
const MONTH_ABBR = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

const STATUS_CHIP: Record<GoalPlanView['status']['kind'], string> = {
  started: 'Plan started',
  imported: 'Plan updated',
  on_plan: 'On plan',
  ahead: 'Ahead',
  behind: 'Behind',
  done: 'Done',
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
  imports: [CommonModule, IonContent, IonHeader, KeyboardAvoidDirective, KidTimelineComponent],
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  templateUrl: './goal.page.html',
  styleUrls: ['./goal.page.scss'],
})
export class GoalPage implements OnInit, ViewWillEnter {
  private readonly route = inject(ActivatedRoute);
  private readonly navCtrl = inject(NavController);
  private readonly modalCtrl = inject(ModalController);
  private readonly actionSheetCtrl = inject(ActionSheetController);
  private readonly alertCtrl = inject(AlertController);
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
  /**
   * The one return assumption, shared app-wide and set only on the Freedom Plan page. Read live,
   * so a plan opened after a change there already shows it.
   */
  readonly annualReturn = this.sharedRate();
  /** Pay frequency of the user's FRED schedule, '' when none (hides the per-paycheck figures). */
  readonly frequency = signal('');
  /** The kid card whose name is an input right now. */
  readonly renaming = signal<number | null>(null);
  /** The unit whose inline "Saved so far" field is open, as "kid-unit". */
  readonly balanceOpen = signal<string | null>(null);
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

  /** Plan started / On plan / Ahead / Behind / Done, or Update balance once a graded log is over 60 days old. */
  readonly statusChip = computed(() => {
    const s = this.view().status;
    const graded = s.kind === 'ahead' || s.kind === 'on_plan' || s.kind === 'behind';
    if (this.mode() === 'saved' && s.stale && graded) return 'Update balance';
    return STATUS_CHIP[s.kind];
  });

  /** Every type but KIDS: "Saved $X · Y% there · Target $T by Mon YYYY". */
  readonly bandLine = computed<{ saved: string; target: string }>(() => {
    const v = this.view();
    const pct = `${Math.round(v.progressPct)}% there`;
    if (v.vacation) {
      const trips = (this.planInput().config as VacationConfig).tripsPerYear;
      return {
        saved: `Saved ${fmtMoney(v.vacation.savedThisYear)} this year · ${pct}`,
        target: `${fmtMoney(v.vacation.annual)} for ${trips} ${trips === 1 ? 'trip' : 'trips'}`,
      };
    }
    if (v.simple) {
      const r = v.simple.reaches;
      return {
        saved: `Saved ${fmtMoney(this.planInput().currentBalance)} · ${pct}`,
        target: `Target ${fmtCompact(v.simple.target)} by ${MONTH_ABBR[r.month - 1]} ${r.year}`,
      };
    }
    return { saved: '', target: '' };
  });

  /** The balance the "Saved so far" tile shows for a simple or vacation goal. */
  readonly simpleBalance = computed(() => {
    const v = this.view();
    return v.vacation ? v.vacation.savedThisYear : this.planInput().currentBalance;
  });

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
        });
      }
      for (const key of ['plan529', 'brokerage'] as KidAccountKey[]) {
        const row = accounts.find(a => a.account === key)?.perKid[i];
        if (!row) continue;
        units.push({
          key, icon: UNIT_ICON[key], title: UNIT_TITLE[key],
          targetLine: `${fmtMoney(row.plan.milestones.at18)} by ${row.plan.deadline.year}`,
          pill: row.pill, monthly: row.plan.monthly, finished: false, balance: row.balance,
        });
      }
      return {
        index: i,
        label: kid.label.trim() || `Kid ${i + 1}`,
        subLine: this.kidSubLine(kid),
        monthly: units.reduce((sum, u) => sum + (u.finished ? 0 : u.monthly), 0),
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
    const sheet = await this.actionSheetCtrl.create({
      header: goal.name,
      buttons: [
        { text: 'Edit plan', handler: () => { void this.editPlan(); } },
        { text: 'Rename', handler: () => { void this.renameGoal(); } },
        ...(goal.type !== 'VACATION'
          ? [{ text: goal.completed ? 'Reopen' : 'Mark completed', handler: () => { void this.markCompleted(); } }]
          : []),
        { text: 'Remove goal', role: 'destructive', handler: () => { void this.confirmRemove(); } },
        { text: 'Cancel', role: 'cancel' },
      ],
    });
    await sheet.present();
  }

  /** Pushes the quiz prefilled with every stored answer. */
  async editPlan(): Promise<void> {
    const goal = this.existing();
    if (!goal) return;
    this.flushFocusedInput();
    await this.pendingWrite;
    await this.navCtrl.navigateForward(['/goals', goal.id, 'edit']);
  }

  /** Renames the goal itself (kids are renamed on their cards). Not a plan input. */
  async renameGoal(): Promise<void> {
    const goal = this.existing();
    if (!goal) return;
    const alert = await this.alertCtrl.create({
      header: 'Rename goal',
      inputs: [{ name: 'name', type: 'text', value: goal.name, attributes: { maxlength: 50 } }],
      buttons: [{ text: 'Cancel', role: 'cancel' }, { text: 'Save', role: 'save' }],
    });
    await alert.present();
    const { role, data } = await alert.onDidDismiss<{ values?: { name?: string } }>();
    if (role !== 'save') return;
    const name = (data?.values?.name ?? '').trim();
    if (!name) return;
    await this.queueWrite(g => (g.name === name ? null : { name }));
  }

  // ── CTA (preview modes) ─────────────────────────────────────────────────────

  /** POSTs the draft, then replaces this preview with the saved page. */
  async startPlan(): Promise<void> {
    if (this.saving() || this.mode() !== 'preview-new') return;
    const draft = this.draft();
    if (!draft) return;
    this.flushFocusedInput();
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
    this.flushFocusedInput();
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

  /** Opens the detail sheet for one kid unit: milestones, cash note, sources, per paycheck. */
  async openUnitDetail(kidIndex: number, unit: KidBalanceKey): Promise<void> {
    const detail = this.unitDetail(kidIndex, unit);
    if (!detail) return;
    const modal = await this.modalCtrl.create({
      component: GoalUnitDetailSheetComponent,
      componentProps: { detail },
      ...BOTTOM_SHEET_MODAL_OPTS,
    });
    await modal.present();
  }

  balanceKey(kidIndex: number, unit: KidBalanceKey): string {
    return `${kidIndex}-${unit}`;
  }

  /** Tapping a unit's target text reveals its "Saved so far" field (tapping again hides it). */
  toggleBalance(kidIndex: number, unit: KidBalanceKey): void {
    const key = this.balanceKey(kidIndex, unit);
    this.balanceOpen.update(open => (open === key ? null : key));
  }

  /** The status chip: "Update balance" scrolls to a balance field and opens it. */
  onChipTap(): void {
    if (this.statusChip() !== 'Update balance') return;
    const card = this.kidCards()[this.activeKid()];
    if (card?.units.length) this.balanceOpen.set(this.balanceKey(card.index, card.units[0].key));
    setTimeout(() => {
      const field = document.querySelector<HTMLInputElement>('.gp-bal-input, #gp-bal-current, #gp-bal-vacation');
      field?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      field?.focus();
    });
  }

  // ── Balances ────────────────────────────────────────────────────────────────

  onBalanceBlur(path: BalancePath, event: Event): void {
    void this.updateBalance(path, this.sanitize(event, 9) ?? 0);
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

  // ── Complete / remove ───────────────────────────────────────────────────────

  /** Toggles `completed`. Not offered for vacation goals. */
  async markCompleted(): Promise<void> {
    if (!this.existing() || this.saving()) return;
    this.flushFocusedInput();
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
    this.flushFocusedInput();
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

  /** Whole dollars: the fields only ever hold digits, so a stored value with cents shows rounded. */
  fmtNumber(value: number | null): string {
    return value == null ? '' : String(Math.round(value));
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
    el.initialize();
  }

  // ── Internals: kid copy ─────────────────────────────────────────────────────

  /** "Born 2024", "This year", "Arrives 2028", "Not dated yet" (Ruling R4). */
  private kidSubLine(kid: KidInput): string {
    if (kid.birthYear === null) return 'Not dated yet';
    if (kid.birthYear === this.now.getFullYear() && kid.birthMonth === null) return 'This year';
    return isKidNotYetBorn(kid.birthYear, kid.birthMonth, this.now) ? `Arrives ${kid.birthYear}` : `Born ${kid.birthYear}`;
  }

  /** The detail sheet's content for one unit, or null when the kid does not hold it. */
  private unitDetail(kidIndex: number, unit: KidBalanceKey): UnitDetail | null {
    const v = this.view();
    const card = this.kidCards()[kidIndex];
    const row = card?.units.find(u => u.key === unit);
    if (!card || !row) return null;
    const kidsCount = (this.planInput().config as KidsConfig).kids.length;
    const perPaycheck = row.finished ? null : InvestmentFrequencyUtils.toPerPaycheckEquivalent(row.monthly, this.frequency());
    const notes: string[] = [];
    let milestones: UnitDetail['milestones'] = [];
    let sourcesLine: string;

    if (unit === 'firstYear') {
      const fund = v.firstYear?.rows.find(r => r.kidIndex === kidIndex);
      if (fund?.plan.rateKind === 'cash') notes.push('Planned as cash at ~4%, since you need it within 3 years.');
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
      sourcesLine = unit === 'plan529'
        ? `Four years at ${COLLEGE_PRICE_YEAR_LABEL} College Board prices, which tend to rise. Raise the amount any time from Edit plan.`
        : 'Your target, grown at the return you chose. At 22 and at 30 assume it stays invested.';
    }

    return {
      kidLabel: card.label,
      title: row.title,
      targetLine: row.targetLine,
      pill: row.pill,
      monthly: row.monthly,
      perPaycheck,
      finished: row.finished,
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
      config: this.withAccountAnchors(draft.config, view),
      // KIDS balances live per account in config.
      currentBalance: form.type === 'KIDS' ? 0 : draft.currentBalance,
      completed: goal?.completed ?? false,
      anchorMonthly: view.monthlyTotal,
      planRate: view.rate,
      planRateKind: view.rateKind,
    };
  }

  /**
   * KIDS: stamp each account's anchorMonthly with that account's proposed monthly. A finished
   * baby fund asks for nothing more, so it keeps the anchor it has.
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

  // ── Internals: writes ───────────────────────────────────────────────────────

  /**
   * One PUT of the saved goal, queued behind the writes before it. `patchOf` runs when the write's
   * turn comes, against the goal the previous write returned; null means nothing to write. The
   * chain itself never rejects; the caller still sees its own outcome.
   */
  private queueWrite(patchOf: (goal: Goal) => Partial<GoalWrite> | null): Promise<void> {
    const run = this.pendingWrite.then(async () => {
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
    this.pendingWrite = run.catch(() => undefined);
    return run;
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

  /** Blurs a focused field so its blur handler (a balance write) runs before the action that follows. */
  private flushFocusedInput(): void {
    (document.activeElement as HTMLElement | null)?.blur?.();
  }

  /** Digits only, capped, input rewritten in place. Empty input yields null. */
  private sanitize(event: Event, maxDigits: number): number | null {
    const input = event.target as HTMLInputElement;
    const digits = (input.value || '').replace(/\D+/g, '').slice(0, maxDigits);
    if (input.value !== digits) input.value = digits;
    return digits ? parseInt(digits, 10) : null;
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
