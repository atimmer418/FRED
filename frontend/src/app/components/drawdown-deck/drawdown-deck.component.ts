import {
  Component,
  Input,
  OnInit,
  OnDestroy,
  ViewChild,
  ElementRef,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ModalController } from '@ionic/angular/standalone';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { PortfolioStoreService } from '../../services/portfolio-store.service';

// ─── Content types ────────────────────────────────────────────────────────────

type BucketKey = 'fred' | 'k401' | 'roth';

interface DrawdownBucket {
  title: string;
  body: string;
  balanceKey: BucketKey;
}

// ─── Content (verbatim from approved spec 2026-07-15) ────────────────────────

export const DRAWDOWN_BUCKETS: DrawdownBucket[] = [
  {
    title: 'Taxable first',
    body: 'Your FRED account. Withdrawals here are taxed at capital-gains rates (often 0-15%, the lowest rates you’ll ever pay), while your tax-advantaged accounts keep compounding untouched.',
    balanceKey: 'fred',
  },
  {
    title: 'Tax-deferred second',
    body: 'Traditional 401(k) and IRA. Every dollar out is ordinary income, so spreading withdrawals across your middle retirement years keeps you in lower brackets, and softens the required withdrawals that start at 73.',
    balanceKey: 'k401',
  },
  {
    title: 'Roth last',
    body: 'Tax-free growth is the most valuable dollar you own. The longer it compounds untouched, the more you keep, and it’s the cleanest account to pass on.',
    balanceKey: 'roth',
  },
];

/** 3 "why" rows: [icon, title, description] — all icons already in the font subset. */
export const DRAWDOWN_WHY: [string, string, string][] = [
  ['trending_down', 'Your early years are your cheapest', 'Before required withdrawals and Social Security kick in, your tax bracket is the lowest it may ever be. Taxable withdrawals fill those years at bargain rates.'],
  ['shield', 'It defuses the tax bomb at 73', 'Draining tax-deferred accounts second shrinks the forced withdrawals (RMDs) that can spike your bracket later.'],
  ['all_inclusive', 'Every extra Roth year is free growth', 'Growth the IRS never touches. Last-out means decades more tax-free compounding.'],
];

// ─── Component ────────────────────────────────────────────────────────────────

/**
 * Optimal Drawdown deck (Piggy Pro) — 2-slide fullscreen educational modal.
 * Personalized with the user's own buckets: FRED (taxable) value from the
 * cached portfolio store, 401(k)/Roth from the balances the Monte Carlo flow
 * persists to localStorage. Education never blocks on data — missing values
 * render as an em-dash and the slides stand alone.
 */
@Component({
  selector: 'app-drawdown-deck',
  templateUrl: './drawdown-deck.component.html',
  styleUrls: ['./drawdown-deck.component.scss'],
  standalone: true,
  imports: [CommonModule],
})
export class DrawdownDeckComponent implements OnInit, OnDestroy {

  /** Same per-user localStorage scoping as MonteCarloFlowComponent. */
  @Input() userId: string | null = null;

  @ViewChild('deckScroll') scrollRef!: ElementRef<HTMLElement>;

  readonly BUCKETS = DRAWDOWN_BUCKETS;
  readonly WHY = DRAWDOWN_WHY;
  readonly SLIDE_INDICES = [0, 1];

  currentSlide = 0;
  hintShown = false;

  fredValue: number | null = null;
  k401: number | null = null;
  roth: number | null = null;

  private destroy$ = new Subject<void>();

  constructor(
    private modalController: ModalController,
    private portfolioStore: PortfolioStoreService,
  ) {}

  ngOnInit(): void {
    this.readOutsideBalances();
    this.portfolioStore.load$().pipe(takeUntil(this.destroy$)).subscribe({
      next: bundle => {
        const v = bundle.dashboard?.summary?.portfolioValue;
        this.fredValue = (typeof v === 'number' && isFinite(v)) ? Math.round(v) : null;
      },
      error: () => { /* chip stays "—" — education never blocks on data */ },
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ── Personalization ───────────────────────────────────────────────────────

  get hasOutside(): boolean {
    return (this.k401 ?? 0) > 0 || (this.roth ?? 0) > 0;
  }

  chip(key: BucketKey): string {
    const v = key === 'fred' ? this.fredValue : key === 'k401' ? this.k401 : this.roth;
    return v == null ? '—' : this.fmtShort(v);
  }

  private readOutsideBalances(): void {
    try {
      const raw = localStorage.getItem(`fred.mcOutside.${this.userId ?? 'anon'}`);
      if (!raw) return;
      const d = JSON.parse(raw);
      if (typeof d.k401 === 'number' && d.k401 > 0) this.k401 = d.k401;
      if (typeof d.roth === 'number' && d.roth > 0) this.roth = d.roth;
    } catch { /* corrupt entry → treated as not entered */ }
  }

  /** Mirrors RetirementPlanningComponent.fmtShort (kept private per component, as elsewhere). */
  private fmtShort(v: number): string {
    if (v >= 1_000_000) return '$' + (v / 1_000_000).toFixed(1).replace(/\.0$/, '') + 'M';
    if (v >= 1_000) return '$' + Math.round(v / 1_000) + 'k';
    return '$' + Math.round(v);
  }

  // ── Slide helpers ─────────────────────────────────────────────────────────

  eyebrow(index: number): string {
    return `OPTIMAL DRAWDOWN · ${index + 1} OF 2`;
  }

  // ── Scroll/tap handlers (same interaction as the strategy decks) ─────────

  onScroll(): void {
    const el = this.scrollRef?.nativeElement;
    if (!el) return;
    const idx = Math.round(el.scrollLeft / el.clientWidth);
    this.currentSlide = idx;
    if (el.scrollLeft > 30 && !this.hintShown) {
      this.hintShown = true;
    }
  }

  onDeckClick(event: MouseEvent): void {
    if ((event.target as Element).closest('button')) return;
    const el = this.scrollRef?.nativeElement;
    if (!el) return;
    const w   = el.clientWidth;
    const x   = event.clientX - el.getBoundingClientRect().left;
    const idx = this.currentSlide;
    if (x > w * 0.5 && idx < 1) {
      el.scrollTo({ left: (idx + 1) * w, behavior: 'smooth' });
    } else if (x < w * 0.22 && idx > 0) {
      el.scrollTo({ left: (idx - 1) * w, behavior: 'smooth' });
    }
  }

  // ── Dismiss paths ─────────────────────────────────────────────────────────

  async dismiss(): Promise<void> {
    await this.modalController.dismiss(null, 'cancel');
  }

  async stressTest(): Promise<void> {
    await this.modalController.dismiss(null, 'stress-test');
  }

  async done(): Promise<void> {
    await this.modalController.dismiss(null, 'done');
  }
}
