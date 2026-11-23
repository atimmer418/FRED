import { Component, DestroyRef, ElementRef, Input, Signal, computed, effect, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { ModalController, ViewWillLeave } from '@ionic/angular/standalone';

import { SheetDragDismissDirective } from '../../directives/sheet-drag-dismiss.directive';
import { BOTTOM_SHEET_MODAL_OPTS } from '../../utils/bottom-sheet.utils';
import { buildAmountScale, capAbove } from '../../utils/plan-amount-scale';
import { AmountTrayHost, AmountTrayHostOptions, TRAY_INSIDE_SELECTOR } from '../plan-amount-tray/amount-tray-host';
import { AmountTraySlotComponent } from '../plan-amount-tray/amount-tray-slot.component';
import { KidCostSourcesSheetComponent } from '../kid-cost-sources-sheet/kid-cost-sources-sheet.component';
import { GoalPlanInfoSheetComponent } from '../goal-plan-info-sheet/goal-plan-info-sheet.component';
import { InfoNote } from '../goal-plan-info-sheet/info-note';
import { TallyPillsComponent } from '../tally-pills/tally-pills.component';
import { UnitPill, fmtMoney } from '../../pages/goal/goal-plan.view-model';

export type UnitTitle = 'Baby fund' | 'College' | 'Nest egg' | 'Everyday';

/** Everything the sheet shows for one kid unit, built by the plan page. */
export interface UnitDetail {
  kidLabel: string;
  title: UnitTitle;
  /** A baby fund, a 529 or nest egg account, or the kid's everyday cost line. */
  kind: 'fund' | 'account' | 'everyday';
  /** "$47,800 by 2042", "$20,800 by Jun 2028". */
  targetLine: string;
  pill: UnitPill;
  monthly: number;
  /** Null when the user has no FRED schedule. */
  perPaycheck: number | null;
  finished: boolean;
  /** The unit's "Saved so far" in dollars. Null for the everyday line, which holds no money. */
  balance: number | null;
  /** What the unit saves toward, in dollars: the Saved so far tray's cap. Null when it has none. */
  target: number | null;
  /** What Missed one and Doubled up move: the server's next credit for the unit. 0 for a finished or imported unit, a preview, and the everyday line. */
  share: number;
  /** "Assuming growth at 10%, worth ~$X today" for an invested unit; null for cash, the baby fund, a preview and the everyday line. */
  growthLine: string | null;
  /** The unit's Status ("On plan", "Behind by $450") and its tone. Null for the everyday line. */
  status: { text: string; tone: 'good' | 'warn' | 'info' | 'muted' } | null;
  /** Empty for a baby fund; At 18 / At 22 / At 30 for a 529 or nest egg. Years only for a dated, born kid. */
  milestones: Array<{ label: string; year: number | null; value: string }>;
  /** The cash note, the undecided-kid note, the monthly-only reason: whichever apply. */
  notes: string[];
  sourcesLine: string;
  /** The family size to open the kid cost sources sheet for (baby fund figures), else null. */
  sourcesSheetKids: number | null;
}

/** The Saved so far tray's cap when the unit carries no target. */
const UNIT_TARGET_FALLBACK = 50_000;
/** The Saved so far tray's minus and plus step. */
const BALANCE_FINE = 100;
/** The one tray field on this sheet. */
type BalanceField = 'balance';

/**
 * A tray host that saves on close. A balance rides as a draft while its tray is open (a write per
 * detent would flood the goal's write queue), and `onClose` commits it however the tray closed:
 * Done, a tap outside, the open field tapped again, submit, or leaving. Shared by this sheet and
 * the plan page's Saved so far tile.
 */
export class SavingTrayHost<F extends string> extends AmountTrayHost<F> {
  constructor(options: AmountTrayHostOptions<F>, private readonly onClose: (field: F) => void) {
    super(options);
  }

  override close(instant = false): void {
    const field = this.open();
    super.close(instant);
    if (field !== null) this.onClose(field);
  }
}

/**
 * One kid unit, in detail: the 22 and 30 milestones, the cash explanation, the per-paycheck
 * figure, the Saved so far balance (a slide tray, opened inside the sheet), the Status word and
 * the sources, opened by tapping anywhere on a unit row on the plan page. The detail is live (a
 * signal), so a saved balance re-renders in place; the sheet closes itself when the unit goes
 * away. Presented with BOTTOM_SHEET_MODAL_OPTS.
 */
@Component({
  selector: 'app-goal-unit-detail-sheet',
  standalone: true,
  imports: [DecimalPipe, AmountTraySlotComponent, TallyPillsComponent],
  templateUrl: './goal-unit-detail-sheet.component.html',
  styleUrls: ['./goal-unit-detail-sheet.component.scss'],
  host: { class: 'ion-content-scroll-host' },
  hostDirectives: [SheetDragDismissDirective],
})
export class GoalUnitDetailSheetComponent implements ViewWillLeave {
  /** The unit, live. Null once the unit no longer exists, which closes the sheet. */
  @Input({ required: true }) detail!: Signal<UnitDetail | null>;
  /** Saves a new Saved so far balance. Null leaves the balance read-only. */
  @Input() onBalance: ((value: number) => Promise<void>) | null = null;
  /** The "How FRED plans this" notes behind the info button. Empty hides the button. */
  @Input() notes: InfoNote[] = [];
  /** Posts a Missed one ('missed') or Doubled up ('extra') for this unit; the page owns the write and the toast. Null hides the pills. */
  @Input() onTally: ((kind: 'missed' | 'extra') => Promise<void>) | null = null;

  private readonly modalCtrl = inject(ModalController);

  readonly fmtMoney = fmtMoney;

  /** True while a Missed one or Doubled up is posting, so a second tap cannot overlap it. */
  readonly tallyBusy = signal(false);

  /** The slider's value while the tray is open, saved when it closes; null when nothing is pending. */
  readonly balanceDraft = signal<number | null>(null);
  /** What the tile shows: the draft while one is pending, else the live balance. */
  readonly shownBalance = computed(() => this.balanceDraft() ?? this.detail()?.balance ?? 0);
  private readonly unitTarget = computed(() => {
    const target = this.detail()?.target;
    return target && target > 0 ? target : UNIT_TARGET_FALLBACK;
  });
  /** A live balance already past the target lifts the cap above it (capAbove); from the live balance, not the draft, so the scale never moves under a finger. */
  readonly balanceScale = computed(() => buildAmountScale(0, capAbove(this.unitTarget(), this.detail()?.balance ?? 0), BALANCE_FINE));
  /** The Saved so far tray. The sheet's height follows it as it grows and collapses. */
  readonly tray = new SavingTrayHost<BalanceField>({
    root: inject<ElementRef<HTMLElement>>(ElementRef),
    destroyRef: inject(DestroyRef),
    fields: {
      balance: { label: 'Saved so far', scale: this.balanceScale, value: this.shownBalance, set: value => this.balanceDraft.set(value) },
    },
    insideSelector: `${TRAY_INSIDE_SELECTOR}, .ud-bal-btn, .ud-tray-done`,
    labelSelector: '.fd-tile-label.active',
    doneSelector: '.ud-tray-done',
  }, () => this.commitBalance());

  constructor() {
    effect(() => {
      if (this.detail && this.detail() === null) this.modalCtrl.dismiss();
    });
  }

  /** True while the balance is the sheet's to edit: a handler was given and the unit is still saving. */
  canEditBalance(): boolean {
    const d = this.detail();
    return !!this.onBalance && !!d && d.balance !== null && !d.finished;
  }

  /** The sheet is going (Done, a drag, the backdrop): the tray snaps shut and a pending balance saves. */
  ionViewWillLeave(): void {
    this.tray.close(true);
  }

  /** The tray closed: its draft, if any, is saved as a non-negative whole amount. */
  private commitBalance(): void {
    const draft = this.balanceDraft();
    if (draft === null || !this.onBalance) return;
    void this.onBalance(Math.max(0, Math.round(draft))).then(() => {
      // The tile keeps the draft until the save settles, so it never flashes the old figure; after
      // a failed save (the page toasts and the balance stays) it shows the live balance again. A
      // tray re-opened in the meantime holds a newer draft, which its own close saves; the tile
      // keeps showing it until that write lands, whatever order the writes settle in.
      if (this.tray.open() === null && this.balanceDraft() === draft) this.balanceDraft.set(null);
    });
  }

  /** A pill tap: the page posts it and shows the toast; the detail then refreshes by itself. */
  async tally(kind: 'missed' | 'extra'): Promise<void> {
    if (!this.onTally || this.tallyBusy()) return;
    this.tallyBusy.set(true);
    try {
      await this.onTally(kind);
    } finally {
      this.tallyBusy.set(false);
    }
  }

  async openInfo(): Promise<void> {
    const modal = await this.modalCtrl.create({
      component: GoalPlanInfoSheetComponent,
      componentProps: { notes: this.notes },
      ...BOTTOM_SHEET_MODAL_OPTS,
    });
    await modal.present();
  }

  async openSources(): Promise<void> {
    const kidsCount = this.detail()?.sourcesSheetKids ?? null;
    if (kidsCount === null) return;
    const modal = await this.modalCtrl.create({
      component: KidCostSourcesSheetComponent,
      componentProps: { kidsCount },
      ...BOTTOM_SHEET_MODAL_OPTS,
    });
    await modal.present();
  }

  close(): void {
    this.tray.close();
    this.modalCtrl.dismiss();
  }
}
