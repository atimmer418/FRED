import { Component, OnInit, OnDestroy, Input } from '@angular/core';
import { ModalController, NavController } from '@ionic/angular/standalone';
import { CommonModule } from '@angular/common';
import { MonthlyFreedomUpdateService, MonthlyFreedomUpdateData, MilestoneDTO } from '../../services/monthly-freedom-update.service';
import { MfuStoreService } from '../../services/mfu-store.service';
import { ReviewService } from '../../services/review.service';
import { Router } from '@angular/router';
import { EquityPigUtils } from '../../utils/equity-pig.utils';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-monthly-freedom-update',
  templateUrl: './monthly-freedom-update.component.html',
  styleUrls: ['./monthly-freedom-update.component.scss'],
  standalone: true,
  imports: [CommonModule]
})
export class MonthlyFreedomUpdateComponent implements OnInit, OnDestroy {

  /** When true, skip the 5-second lock (reopened from FRED tab) */
  @Input() isReopen: boolean = false;

  /** Preloaded payload for present-when-ready. When set, the modal opens fully painted (no spinner). */
  @Input() preloaded: MonthlyFreedomUpdateData | null = null;

  data: MonthlyFreedomUpdateData | null = null;
  loading = true;
  error: string | null = null;

  // The close button is available immediately (no view lock).
  canDismiss = true;

  // "Seen" is committed exactly once at data-ready (non-reopen); this guards against a double fire.
  private committed = false;
  private reopenSub?: Subscription;

  constructor(
    private modalController: ModalController,
    private mfuService: MonthlyFreedomUpdateService,
    private mfuStore: MfuStoreService,
    private router: Router,
    private reviewService: ReviewService
  ) {}

  ngOnInit() {
    if (this.isReopen) {
      if (this.preloaded) {
        // Instant paint from the localStorage snapshot; live fields are revalidated below.
        this.data = this.preloaded;
        this.loading = false;
      }
      this.revalidateReopen(!this.preloaded);
    } else if (this.preloaded) {
      // Present-when-ready: payload already in hand → the "Preparing your update…" state never renders.
      this.data = this.preloaded;
      this.loading = false;
      this.mfuStore.persistSnapshot(this.data);
      this.maybeCommitSeen(this.data);
    } else {
      // Shell fallback (shouldShow was true but /generate errored upstream): self-load as before.
      this.loadData();
    }
  }

  ngOnDestroy() {
    this.reopenSub?.unsubscribe();
  }

  private static fontsLoaded = false;

  private loadData() {
    this.loading = true;

    const dataReady = new Promise<MonthlyFreedomUpdateData>((resolve, reject) => {
      this.mfuService.generateUpdate(this.isReopen).subscribe({
        next: (data) => resolve(data),
        error: (err) => reject(err)
      });
    });

    // Only wait for fonts on first open — after that they're cached
    const fontsReady = MonthlyFreedomUpdateComponent.fontsLoaded
      ? Promise.resolve()
      : document.fonts.load('24px "Material Symbols Outlined"').then(() => {
          MonthlyFreedomUpdateComponent.fontsLoaded = true;
        }).catch(() => {
          // Don't block on font failure
          MonthlyFreedomUpdateComponent.fontsLoaded = true;
        });

    Promise.all([dataReady, fontsReady]).then(([data]) => {
      this.data = data;
      this.loading = false;
      if (!this.isReopen) {
        this.mfuStore.persistSnapshot(data);
        this.maybeCommitSeen(data);
      }
    }).catch((err) => {
      console.error('Error loading Monthly Freedom Update:', err);
      this.error = 'Failed to load your monthly update.';
      this.loading = false;
      this.canDismiss = true;
    });
  }

  /**
   * Reopen: pull a fresh (warm, Alpaca-free) payload via the store's single-flight; patch ONLY the
   * live/projection fields over the snapshot so the freedom year matches the live app, or fill the
   * whole modal on a cold cache miss. Period/recap fields never swap.
   */
  private revalidateReopen(fillIfEmpty: boolean) {
    if (fillIfEmpty) {
      this.loading = true;
    }
    this.reopenSub = this.mfuStore.loadReopen$().subscribe({
      next: (fresh) => {
        if (this.data) {
          this.patchLiveFields(fresh);
        } else {
          this.data = fresh;
        }
        this.loading = false;
      },
      error: () => {
        // Keep the snapshot if we have one; only surface an error on a true cold miss.
        if (!this.data) {
          this.error = 'Failed to load your monthly update.';
          this.canDismiss = true;
        }
        this.loading = false;
      }
    });
  }

  /** Commit "seen" exactly once, anchored to data-ready (never the dismiss button). Non-reopen only. */
  private maybeCommitSeen(d: MonthlyFreedomUpdateData) {
    if (this.isReopen || this.committed) return;
    this.committed = true;
    this.mfuService.commitSeen(d.generatedForMonth).subscribe({
      error: (err) => console.error('Error committing MFU seen:', err)
    });
  }

  /** Overwrite only the live/projection fields so a reopened recap's numbers match the live app. */
  private patchLiveFields(fresh: MonthlyFreedomUpdateData) {
    if (!this.data) return;
    this.data = {
      ...this.data,
      projectedFreedomYear: fresh.projectedFreedomYear,
      currentEquityValue: fresh.currentEquityValue,
      statusPercentile: fresh.statusPercentile,
      bestNextMoveYear: fresh.bestNextMoveYear,
      bestNextMoveYearsEarlier: fresh.bestNextMoveYearsEarlier,
      bestNextMoveBoostAmount: fresh.bestNextMoveBoostAmount,
      currentInvestmentAmount: fresh.currentInvestmentAmount,
      recurringInvestmentAmount: fresh.recurringInvestmentAmount,
      showQuarterlyCompare: fresh.showQuarterlyCompare,
      equity12MonthsAgo: fresh.equity12MonthsAgo,
      netWorthChange: fresh.netWorthChange,
      freedomYearsChange: fresh.freedomYearsChange,
      yearlyContributions: fresh.yearlyContributions,
    };
  }

  dismiss() {
    if (!this.canDismiss) return;

    // "Seen" is committed at data-ready (maybeCommitSeen), NOT here, so the primary CTA and swipe
    // paths can't skip it. This handler only closes the modal and requests a review.
    this.modalController.dismiss();
    if (!this.isReopen) {
      this.reviewService.requestReviewIfFirstMFU();
    }
  }

  updateContribution() {
    // Dismiss with action so the caller can navigate
    this.modalController.dismiss({
      action: 'updateContribution',
      currentAmount: this.data?.currentInvestmentAmount || 0,
      frequency: this.data?.investmentFrequency || 'MONTHLY',
      boostAmount: this.data?.bestNextMoveBoostAmount || 50
    });
  }

  // --- Display helpers ---

  formatCurrency(amount: number | undefined | null): string {
    if (amount === undefined || amount === null) return '$0';
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 0,
      maximumFractionDigits: 0
    }).format(amount);
  }

  formatCurrencyCompact(amount: number | undefined | null): string {
    if (amount === undefined || amount === null) return '$0';
    const abs = Math.abs(amount);
    if (abs >= 1_000_000) return '$' + (amount / 1_000_000).toFixed(1) + 'M';
    if (abs >= 1_000) return '$' + (amount / 1_000).toFixed(1) + 'k';
    return '$' + amount.toFixed(0);
  }

  formatPercent(value: number | undefined | null): string {
    if (value === undefined || value === null) return '0%';
    return value.toFixed(1) + '%';
  }

  get freedomMessage(): string {
    if (!this.data) return '';
    if (this.data.daysBoughtBack > 0) {
      return `Bought back ${this.data.daysBoughtBack} days`;
    }
    return "Markets dip. Your system didn't.";
  }

  /**
   * Down-month body paragraphs. A member who opens the update every month should not reread the exact
   * same reassurance, so we rotate through this pool. House voice: calm, plain, no em dashes.
   */
  static readonly DOWN_DESCRIPTIONS: Array<(period: string) => string> = [
    (p) => `From <b>${p}</b>, FRED helped you stay on course. Market swings are normal and recover with time.`,
    (p) => `From <b>${p}</b>, FRED kept investing right on schedule. Down months are when steady habits do their quiet work.`,
    (p) => `Prices dipped over <b>${p}</b>, but your plan held steady. Consistency is what carries you through.`,
    (p) => `<b>${p}</b> got bumpy, and that's okay. FRED kept buying like the market was on a 10% discount.`,
    (p) => `Markets wobbled through <b>${p}</b>. Staying invested is exactly how a downturn turns into progress.`,
    (p) => `Over <b>${p}</b>, FRED held the line for you. Dips pass, and the habit is what compounds.`,
  ];

  /**
   * Up-month body paragraphs. Every variant keeps the "days bought back" stat; only the framing
   * around it changes so the good-news line stays fresh too.
   */
  static readonly UP_DESCRIPTIONS: Array<(period: string, days: number) => string> = [
    (p, d) => `From <b>${p}</b>, FRED helped move you about <b>${d} days</b> ahead of a typical 59.5 retirement age.`,
    (p, d) => `Good spree. Over <b>${p}</b>, FRED pulled your freedom date about <b>${d} days</b> closer.`,
    (p, d) => `<b>${p}</b> moved you forward: roughly <b>${d} days</b> off the wait for financial freedom.`,
    (p, d) => `FRED bought back about <b>${d} days</b> over <b>${p}</b>. That's time back in your temporal wallet.`,
  ];

  /**
   * Deterministic per-MFU variant index, seeded off mfuCount so the copy rotates month to month yet
   * stays put across the many getter calls a change-detection pass makes (and when a month is
   * reopened). Falls back to a stable hash of the month when mfuCount is not present.
   */
  private variantIndex(poolSize: number): number {
    const count = this.data?.mfuCount;
    const seed = (typeof count === 'number' && Number.isFinite(count) && count >= 0)
      ? count
      : this.hashSeed(this.data?.generatedForMonth || this.data?.periodEnd || '');
    return seed % poolSize;
  }

  private hashSeed(s: string): number {
    let h = 0;
    for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
    return h;
  }

  get freedomDescriptionHtml(): string {
    if (!this.data) return '';
    const period = this.data.periodLabel;
    if (this.data.daysBoughtBack > 0) {
      const pool = MonthlyFreedomUpdateComponent.UP_DESCRIPTIONS;
      return pool[this.variantIndex(pool.length)](period, this.data.daysBoughtBack);
    }
    const pool = MonthlyFreedomUpdateComponent.DOWN_DESCRIPTIONS;
    return pool[this.variantIndex(pool.length)](period);
  }

  get milestoneIcon(): string {
    if (!this.data || !this.data.milestones || this.data.milestones.length === 0) return 'military_tech';
    return this.data.milestones[0].type === 'DEFAULT' ? 'sentiment_satisfied' : 'military_tech';
  }

  get milestoneEquityPigSrc(): string {
    return EquityPigUtils.pigSrcFromLevel(this.data?.equityLevel);
  }

  private static readonly MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

  /** Month abbreviation from an ISO date (YYYY-MM-DD), read off the string to avoid a Date() timezone shift. */
  private monthAbbrev(iso: string | undefined): string {
    if (!iso || iso.length < 7) return '';
    const m = parseInt(iso.slice(5, 7), 10);
    return m >= 1 && m <= 12 ? MonthlyFreedomUpdateComponent.MONTHS[m - 1] : '';
  }

  /**
   * Branded hero subtitle: "Monthly Freedom Update · Aug" for a single month, or a range like
   * "Monthly Freedom Update · Jun–Aug" when the update spans multiple months — a member returning
   * after a gap, since the period runs first-of-last-logged-month → end-of-last-month. Derived from
   * periodStart/periodEnd (NOT the "MMM d to MMM d" periodLabel), so a multi-month span can never be
   * silently mislabeled as just its start month.
   */
  get heroSubtitle(): string {
    const start = this.monthAbbrev(this.data?.periodStart);
    const end = this.monthAbbrev(this.data?.periodEnd);
    if (!start && !end) return 'Monthly Freedom Update';
    const sameMonth = (this.data?.periodStart || '').slice(0, 7) === (this.data?.periodEnd || '').slice(0, 7);
    const span = sameMonth || !start ? (end || start)
               : !end ? start
               : `${start}–${end}`;
    return `Monthly Freedom Update · ${span}`;
  }

  /** Equity milestone keys that sit INSIDE a pig range ($50k/$250k/$500k): no new pig unlocks there. */
  private static readonly NON_BOUNDARY_EQUITY_KEYS = ['EQUITY_50K', 'EQUITY_250K', 'EQUITY_500K'];

  /**
   * Pig art is reserved for milestones that unlock a NEW pig range ($1k/$10k/$100k/$1M end-equity
   * tiers). A card whose equity milestone is $50k/$250k/$500k keeps the default milestone icon
   * (military_tech), and the DEFAULT "no milestones" card keeps its smiley (see milestoneIcon).
   */
  get showMilestonePig(): boolean {
    const type = this.data?.milestones?.[0]?.type;
    if (!type || type === 'DEFAULT') return false;
    return !type.split(',').some(key => MonthlyFreedomUpdateComponent.NON_BOUNDARY_EQUITY_KEYS.includes(key));
  }

  /**
   * Compute change from rounded start/end so the displayed values are consistent.
   * e.g. End $863 - Start $741 = exactly $122, not $123 from unrounded decimals.
   */
  get computedChange(): number {
    if (!this.data) return 0;
    return Math.round(this.data.endEquityValue) - Math.round(this.data.startEquityValue);
  }

  get changeDirection(): string {
    if (!this.data) return '';
    return this.computedChange >= 0 ? 'arrow_upward' : 'arrow_downward';
  }

  get changeClass(): string {
    if (!this.data) return '';
    return this.computedChange >= 0 ? 'mfu-stat__value--positive' : 'mfu-stat__value--negative';
  }

  /**
   * FRED-217: the member's projected freedom year has arrived. True once projectedFreedomYear is a
   * real year at or before the current one (a past year means they're over target). A missing/0/null
   * value is a not-yet-computed projection, never a false celebration, so it falls through to the
   * "What If You Invested More?" CTA.
   */
  get isFreedomYearReached(): boolean {
    const year = this.data?.projectedFreedomYear;
    if (!year || year <= 0) return false;
    return year <= new Date().getFullYear();
  }

  /** Retire CTA tap: dismiss with an action so the presenter opens the tab2 Education section. */
  exploreRetirement() {
    this.modalController.dismiss({ action: 'exploreRetirement' });
  }

  get bestNextMoveTitle(): string {
    if (!this.data) return '';
    return `Freedom Date: ${this.data.projectedFreedomYear} -> ${this.data.bestNextMoveYear}<br>(${this.data.bestNextMoveYearsEarlier} years sooner)`;
  }

  get bestNextMoveDescription(): string {
    if (!this.data) return '';
    const amount = this.data.bestNextMoveBoostAmount || 50;
    return `If you increased contributions by $${amount}/${this.data.frequencyLabel}.`;
  }

  formatYearlyNetWorth(amount: number | undefined | null): string {
    if (amount === undefined || amount === null) return '+$0';
    const prefix = amount >= 0 ? '+' : '-';
    return `${prefix}${this.formatCurrency(Math.abs(amount))}`;
  }

  formatYearsFreedom(years: number | undefined | null): string {
    if (years === undefined || years === null || years === 0) return '0';
    const prefix = years > 0 ? '-' : '+';
    return `${prefix}${Math.abs(years)}`;
  }
}

