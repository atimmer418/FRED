import { Component, Input, OnInit, ViewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ModalController } from '@ionic/angular/standalone';
import { ToastService } from '../../services/toast.service';
import { McInfoSheetComponent } from '../mc-info-sheet/mc-info-sheet.component';
import { BOTTOM_SHEET_MODAL_OPTS } from '../../utils/bottom-sheet.utils';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { DeckExit, PlanStrategy, Tier } from '../../services/analytics/analytics-events';

// ─── Content types ────────────────────────────────────────────────────────────

interface StrategySlides {
  /** Hero headline for slide 0. */
  whatH:  string;
  /** Slide 0 body — may contain <strong> HTML. */
  what:   string;
  /** [big-number, label] stat chip on slide 0. */
  stat:   [string, string];
  /** 3 "how" steps: [icon, title, description]. */
  how:    [string, string, string][];
  /** 3 "why" rows: [icon, title, description]. */
  why:    [string, string, string][];
  /** 4 safety rules: [title, description]. All rendered with verified_user icon. */
  safe:   [string, string][];
  /** 3 playbook recap bullets. */
  recap:  string[];
}

interface StrategyData {
  /** Typed as PlanStrategy so the deck key and the analytics vocabulary cannot drift apart. */
  key:    PlanStrategy;
  icon:   string;
  name:   string;
  tag:    string;
  hint:   string;
  slides: StrategySlides;
}

// ─── Strategy content (verbatim from approved prototype FRED-208-prototype.html) ──

export const STRATEGIES: StrategyData[] = [
  {
    key: 'yield', icon: 'payments', name: 'Yield-Based Income',
    tag: 'Live off dividends and interest, never panic-sell',
    hint: '5 slides · 3 min',
    slides: {
      whatH: 'Get paid by what you own.',
      what:  `A portfolio built to <strong>pay you ~4% a year</strong> in dividends and interest. Your spending money arrives as income the portfolio generates. The shares themselves stay invested and keep compounding.`,
      stat:  ['4%', 'target blended yield'],
      how: [
        ['payments',     'Build the income mix',  'Dividend stocks and ETFs, bond ladders, and REITs blended to a ~4% overall yield.'],
        ['event_repeat', 'Income lands as cash',  'Dividends and interest arrive monthly and quarterly. That cash is your paycheck.'],
        ['lock',         'Principal stays put',   'You spend the income, not the shares. In lean years, a small principal top-up bridges the gap.'],
      ],
      why: [
        ['trending_down',  'Crashes cut prices, not payouts',   'Market prices swing wildly; dividend and interest streams are historically far steadier.'],
        ['psychology',     'It removes the hardest decision',   'No agonizing over when to sell: income arrives on schedule regardless of headlines.'],
        ['all_inclusive',  'Principal keeps compounding',       'What you never sell can recover, grow, and eventually become your legacy.'],
      ],
      safe: [
        ['Diversify the income', 'No single stock over 5% of the portfolio: a dividend cut shouldn’t dent your lifestyle.'],
        ['Don’t chase yield',   'Anything promising 6%+ usually carries hidden risk. High yield is a warning, not a gift.'],
        ['Keep a cash buffer',   'Six months of spending in cash rides out dividend cuts without forced selling.'],
        ['Check payout health',  'Favor companies paying out a sustainable share of earnings, not borrowing to pay you.'],
      ],
      recap: [
        'A ~4% blended yield becomes your paycheck',
        'Shares stay invested and keep compounding',
        'Buffer + diversification protect the income stream',
      ],
    },
  },
  {
    key: 'guardrails', icon: 'tune', name: 'Dynamic Guardrails',
    tag: 'Spend more in good years, less in bad',
    hint: '5 slides · 3 min',
    slides: {
      whatH: 'Flexible beats fragile.',
      what:  `A withdrawal plan with <strong>built-in course correction</strong>. You start around 5% and adjust spending with simple rules when markets move: small trims in bad years, raises in good ones.`,
      stat:  ['5%', 'starting withdrawal rate'],
      how: [
        ['play_arrow',      'Start at 5%',               'Withdraw about 5% of your portfolio in year one (more than the old 4% rule allows).'],
        ['tune',            'Check once a year',          'If withdrawals exceed 6% of the current portfolio, cut spending 10%. Below 4%? Give yourself a 10% raise.'],
        ['event_available', 'Otherwise, inflation bump',  'In normal years your spending simply rises with inflation. One check, once a year.'],
      ],
      why: [
        ['query_stats',         'Small trims, huge effect',    'A temporary 10% cut in a downturn dramatically extends how long money lasts. Flexibility is the strongest lever in retirement math.'],
        ['history',             'Battle-tested',               'Guardrails survived nearly every historical market, including retiring right into 2008.'],
        ['sentiment_satisfied', 'Raises are part of the plan', 'Unlike rigid rules, good markets translate into real, guilt-free spending increases.'],
      ],
      safe: [
        ['Write the rules down',       'Pre-commit to the exact triggers. Decisions made calmly beat decisions made in a crash.'],
        ['Automate the annual check',  'Put it on the calendar after your yearly rebalance. One look, one adjustment.'],
        ['Hold 2 years of cash',       'A cash cushion means trims never touch groceries. They trim the extras.'],
        ['Don’t override the system', 'The rules only work if euphoria and panic can’t talk you out of them.'],
      ],
      recap: [
        'Start at 5%, adjust with two simple triggers',
        'Temporary trims extend your money dramatically',
        'Cash cushion keeps cuts comfortable',
      ],
    },
  },
  {
    key: 'annuity', icon: 'security', name: 'Annuity',
    tag: 'Guaranteed income for life',
    hint: '5 slides · 3 min',
    slides: {
      whatH: 'Build your own pension.',
      what:  `Trade part of your portfolio for a <strong>guaranteed monthly check for life</strong>. An insurer takes on the market risk. Your essentials get paid no matter what stocks do, for as long as you live.`,
      stat:  ['40-60%', 'of portfolio annuitized'],
      how: [
        ['security',     'Cover the essentials',         'Annuitize enough that housing, food, and healthcare are paid by the guaranteed check alone.'],
        ['trending_up',  'Invest the rest for growth',   'The remaining portfolio stays in stocks for lifestyle spending, inflation, and legacy.'],
        ['event_repeat', 'Checks arrive for life',       'Every month, regardless of markets, headlines, or how long you live.'],
      ],
      why: [
        ['all_inclusive', 'You can’t outlive it',          'Longevity is retirement’s biggest unknown. Lifetime income turns it from a risk into a feature.'],
        ['shield',        'Essentials are sequence-proof',  'A crash in year one can’t touch your rent money: the worst retirement risk, neutralized.'],
        ['psychology',    'Permission to stay invested',    'With basics guaranteed, riding out volatility in the growth bucket gets emotionally easy.'],
      ],
      safe: [
        ['Only A-rated insurers',            'The guarantee is only as strong as the company. Check AM Best ratings first.'],
        ['Shop at least 3 quotes',           'Payouts for the same dollar vary surprisingly widely between insurers.'],
        ['Know it’s (mostly) irreversible', 'Annuitizing is a one-way door. Start smaller if unsure. You can add later.'],
        ['Never annuitize everything',       'Keep growth assets for inflation and flexibility. 100% annuity is 0% adaptability.'],
      ],
      recap: [
        'Guaranteed check covers all essentials',
        'Growth bucket handles lifestyle and inflation',
        'Rated insurers + multiple quotes, always',
      ],
    },
  },
  {
    key: 'sbloc', icon: 'account_balance', name: 'SBLOC',
    tag: 'Borrow through downturns, never sell low',
    hint: '5 slides · 3 min',
    slides: {
      whatH: 'Never sell in a crash again.',
      what:  `A <strong>credit line backed by your portfolio</strong>. In normal years you sell shares to live. In crash years you borrow your living money instead, so you never lock in losses at the bottom.`,
      stat:  ['<20%', 'max line vs portfolio'],
      how: [
        ['sell',            'Good years: sell as usual', 'Markets up? Sell ~4% for living expenses like any withdrawal plan.'],
        ['account_balance', 'Bad years: draw the line',  'Portfolio down 10%+? Borrow living money against it instead of selling depressed shares.'],
        ['undo',            'Recovery: repay first',     'When markets recover, sell enough to clear the loan, then resume normal withdrawals.'],
      ],
      why: [
        ['trending_down', 'Sequence risk is the killer',  'Selling through a crash early in retirement does permanent damage. Borrowing bridges the dip.'],
        ['history',       'Dips have always recovered',   'Every major U.S. downturn has recovered, typically within a few years. The line buys that time.'],
        ['payments',      'Cheap when used right',        'Interest on 1-2 crash-years of spending costs far less than selling 30% off your shares.'],
      ],
      safe: [
        ['Stay under 20%',              'Keep the line small relative to the portfolio. Margin calls happen to the greedy.'],
        ['Set it up before you retire', 'Approval needs a qualifying portfolio. Arrange the line while you don’t need it.'],
        ['Model 6-8% rates',            'SBLOC rates float. If the math only works at 4%, it doesn’t work.'],
        ['Repay aggressively',          'The line is a bridge, not a lifestyle. Clear it in the first recovery years, and know this is the most advanced strategy here.'],
      ],
      recap: [
        'Borrow in crashes, sell in recoveries',
        'Line stays under 20% of portfolio',
        'Most advanced strategy: rules are non-negotiable',
      ],
    },
  },
];

/** Minimal card data exported for the education landing grid in the parent component. */
export const EDU_CARD_DEFS = STRATEGIES.map(s => ({
  key:  s.key,
  icon: s.icon,
  name: s.name,
  tag:  s.tag,
  hint: s.hint,
}));

// ─── Component ────────────────────────────────────────────────────────────────

@Component({
  selector: 'app-strategy-deck',
  templateUrl: './strategy-deck.component.html',
  styleUrls: ['./strategy-deck.component.scss'],
  standalone: true,
  imports: [CommonModule],
})
export class StrategyDeckComponent implements OnInit {

  @Input() strategyKey = 'yield';

  /** Subscription tier passed by the presenter (same pattern as MonteCarloFlowComponent). */
  @Input() userTier: string | null = null;

  get isPro(): boolean { return this.userTier === 'pro'; }

  @ViewChild('deckScroll') scrollRef!: ElementRef<HTMLElement>;

  strategy!: StrategyData;

  currentSlide = 0;
  hintShown    = false;

  /**
   * Furthest slide the user reached. Accumulated by onScroll (which runs per scroll frame, so it
   * only records state) and reported once, when the deck closes.
   */
  maxSlideReached = 0;

  /** Guard against double-presenting the upgrade nudge. */
  private nudgeLock = false;

  readonly SLIDE_INDICES = [0, 1, 2, 3, 4];

  private readonly H1_LABELS = [
    '',                // slide 0 — rendered via strategy.slides.whatH
    'How it works.',
    'Why it works.',
    'Do it safely.',
    'The playbook.',
  ];

  constructor(
    private modalController: ModalController,
    private toastService: ToastService,
    private analytics: AnalyticsService,
  ) {}

  ngOnInit(): void {
    this.strategy = STRATEGIES.find(s => s.key === this.strategyKey) ?? STRATEGIES[0];
  }

  // ── Slide helpers ─────────────────────────────────────────────────────────

  eyebrow(index: number): string {
    return `${this.strategy.name.toUpperCase()} · ${index + 1} OF 5`;
  }

  h1(index: number): string {
    return this.H1_LABELS[index];
  }

  // ── Scroll/tap handlers ───────────────────────────────────────────────────

  onScroll(): void {
    const el = this.scrollRef?.nativeElement;
    if (!el) return;
    const idx = Math.round(el.scrollLeft / el.clientWidth);
    this.currentSlide = idx;
    if (idx > this.maxSlideReached) {
      this.maxSlideReached = idx;
    }
    if (el.scrollLeft > 30 && !this.hintShown) {
      this.hintShown = true;
    }
  }

  onDeckClick(event: MouseEvent): void {
    // Buttons in the slides have their own (click) handlers; exclude them from
    // the tap-to-advance zone check.
    if ((event.target as Element).closest('button')) return;
    const el = this.scrollRef?.nativeElement;
    if (!el) return;
    const w   = el.clientWidth;
    const x   = event.clientX - el.getBoundingClientRect().left;
    const idx = this.currentSlide;
    if (x > w * 0.5 && idx < 4) {
      el.scrollTo({ left: (idx + 1) * w, behavior: 'smooth' });
    } else if (x < w * 0.22 && idx > 0) {
      el.scrollTo({ left: (idx - 1) * w, behavior: 'smooth' });
    }
  }

  /**
   * One strategy_viewed per deck session, fired from each dismiss path rather than per slide: the
   * exit role and how far the user got are the two things worth knowing, and they are only both
   * available at the close. Every way out of the deck is one of the five DeckExit values.
   */
  private trackDeckViewed(exit: DeckExit): void {
    if (!this.strategy) {
      return;
    }
    this.analytics.track({
      name: 'strategy_viewed',
      props: {
        strategy: this.strategy.key,
        surface: 'retirement_planning',
        max_slide_reached: this.maxSlideReached,
        slide_count: this.SLIDE_INDICES.length,
        exit,
      },
    });
  }

  /** The tier as analytics knows it. An unrecognised plan reports null rather than a guess. */
  private get analyticsTier(): Tier {
    const tier = this.userTier;
    return tier === 'core' || tier === 'plus' || tier === 'pro' ? tier : null;
  }

  private trackDrawdownNudge(action: 'shown' | 'upgrade_tapped' | 'dismissed'): void {
    this.analytics.track({
      name: 'paywall_nudge_shown',
      props: { feature: 'optimal_drawdown', tier: this.analyticsTier, action },
    });
  }

  // ── Dismiss paths ─────────────────────────────────────────────────────────

  async dismiss(): Promise<void> {
    this.trackDeckViewed('cancel');
    await this.modalController.dismiss(null, 'cancel');
  }

  async stressTest(): Promise<void> {
    this.trackDeckViewed('stress_test');
    await this.modalController.dismiss(null, 'stress-test');
  }

  async done(): Promise<void> {
    this.trackDeckViewed('done');
    await this.modalController.dismiss(null, 'done');
  }

  /**
   * "Discover the Optimal Drawdown" (playbook slide).
   * Pro → dismiss with role 'drawdown'; the presenter opens the drawdown deck.
   * Non-pro → present the Piggy Pro nudge over this deck so "Maybe later"
   * keeps the user on the playbook slide; an upgrade tap dismisses the deck
   * with role 'upgrade' for the presenter to route.
   * The lock releases in finally so a rejected create/present can never strand it.
   */
  async discoverDrawdown(): Promise<void> {
    if (this.isPro) {
      this.trackDeckViewed('drawdown');
      await this.modalController.dismiss(null, 'drawdown');
      return;
    }
    if (this.nudgeLock) return;
    this.nudgeLock = true;

    let role: string | undefined;
    try {
      const sheet = await this.modalController.create({
        component: McInfoSheetComponent,
        ...BOTTOM_SHEET_MODAL_OPTS,
        componentProps: {
          mode: 'nudge',
          nudgeTitle: 'Unlock the Optimal Drawdown',
          nudgeBody: 'See the tax-smart order for spending your taxable, 401(k), and Roth accounts in retirement, personalized to your portfolio, updating as it grows.',
          targetTier: 'pro',
        },
      });
      await sheet.present();
      this.trackDrawdownNudge('shown');
      ({ role } = await sheet.onDidDismiss());
    } finally {
      this.nudgeLock = false;
    }
    if (role === 'upgrade') {
      this.trackDrawdownNudge('upgrade_tapped');
      this.trackDeckViewed('upgrade');
      await this.modalController.dismiss(null, 'upgrade');
    } else {
      this.trackDrawdownNudge('dismissed');
    }
  }
}
