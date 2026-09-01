import {
  Component, OnInit, OnDestroy, ElementRef, ViewChild, ChangeDetectorRef, NgZone
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { FirstTimeTourService, TourStep } from '../../services/first-time-tour.service';
import { computeSpotlight, Rect, SpotlightLayout } from './spotlight-geometry';

interface TourStepDef {
  step: TourStep;
  kind: 'explain' | 'gated';
  label: string;
  targetSelector?: string;
}

const TOUR_STEPS: TourStepDef[] = [
  { step: 'tab1', kind: 'explain', label: 'Your portfolio and its insights live here.' },
  { step: 'tab2', kind: 'explain', label: 'Your retirement options and analyses live here.' },
  { step: 'tab3Avatar', kind: 'gated', label: 'Your settings live here. Tap your pig to open your profile.', targetSelector: '[data-tour="profile-avatar"]' },
  { step: 'profileFreedom', kind: 'gated', label: 'This is your Freedom Plan. Tap it to see how you get there.', targetSelector: '[data-tour="freedom-row"]' },
  { step: 'chatStory', kind: 'gated', label: 'Ask FRED anything here. Start with this one.', targetSelector: '[data-tour="story-chip"]' },
];

/** Poll cadence while waiting for a gated target to appear. */
export const TARGET_POLL_MS = 16;
/** ~3s at 16ms: after this the step falls back to explain rendering. */
export const TARGET_POLL_MAX_ATTEMPTS = 188;
/**
 * Consecutive identical measurements required before the poll stops. The step
 * changes while the pager/page transition is still running, so the first hit
 * is often a mid-flight position; the hole would sit where the target WAS.
 */
export const STABLE_TICKS = 3;
/** Re-measure once after the page/slide transition settles. */
const SETTLE_REMEASURE_MS = 600;
/** Space kept clear above the floating tab bar when placing the card. */
const BOTTOM_RESERVE_PX = 110;
/** Card height used before the card has been measured. */
const CARD_HEIGHT_FALLBACK_PX = 140;

function sameRect(a: Rect, b: Rect): boolean {
  return a.top === b.top && a.left === b.left && a.width === b.width && a.height === b.height;
}

@Component({
  selector: 'app-first-time-tour',
  templateUrl: './first-time-tour.component.html',
  styleUrls: ['./first-time-tour.component.scss'],
  standalone: true,
  imports: [CommonModule]
})
export class FirstTimeTourComponent implements OnInit, OnDestroy {

  @ViewChild('card') cardRef?: ElementRef<HTMLElement>;

  currentStep: TourStep | null = null;
  suspended = false;
  /** Set when a gated target never appeared; the step renders like an explain step. */
  fallback = false;
  layout: SpotlightLayout | null = null;

  readonly stepOrder: TourStep[] = TOUR_STEPS.map(s => s.step);

  private subs = new Subscription();
  private pollTimer: ReturnType<typeof setTimeout> | null = null;
  private settleTimer: ReturnType<typeof setTimeout> | null = null;
  private cardMeasureTimer: ReturnType<typeof setTimeout> | null = null;
  private lastTargetSelector: string | null = null;
  private readonly onViewportChange = () => this.remeasure();

  constructor(
    private tourService: FirstTimeTourService,
    private cdr: ChangeDetectorRef,
    private zone: NgZone
  ) {}

  ngOnInit() {
    this.subs.add(this.tourService.step$.subscribe(step => this.onStep(step)));
    this.subs.add(this.tourService.suspended$.subscribe(s => {
      this.suspended = s;
      this.cdr.markForCheck();
    }));
    window.addEventListener('resize', this.onViewportChange);
    window.addEventListener('orientationchange', this.onViewportChange);
  }

  ngOnDestroy() {
    this.subs.unsubscribe();
    this.cancelTimers();
    window.removeEventListener('resize', this.onViewportChange);
    window.removeEventListener('orientationchange', this.onViewportChange);
  }

  // ── Template state ────────────────────────────────────────────────────────

  get isVisible(): boolean {
    return this.currentStep !== null && !this.suspended;
  }

  get stepDef(): TourStepDef | null {
    return TOUR_STEPS.find(s => s.step === this.currentStep) ?? null;
  }

  get stepLabel(): string {
    return this.stepDef?.label ?? '';
  }

  /** Gated rendering (panels + ring) is only used once the target is measured. */
  get isSpotlight(): boolean {
    return this.stepDef?.kind === 'gated' && !this.fallback && this.layout !== null;
  }

  /** Explain steps and fallback gated steps show Next. */
  get showsNext(): boolean {
    return this.stepDef?.kind === 'explain' || this.fallback;
  }

  /** While a gated target is still being polled, show a plain dark screen with just the label. */
  get isMeasuring(): boolean {
    return this.stepDef?.kind === 'gated' && !this.fallback && this.layout === null;
  }

  get cardTopPx(): number | null {
    return this.isSpotlight && this.layout ? this.layout.cardTop : null;
  }

  onNext() {
    if (this.fallback) {
      this.tourService.forceGate();
    } else {
      this.tourService.next();
    }
  }

  onSkip() {
    this.tourService.skip();
  }

  // ── Step handling ─────────────────────────────────────────────────────────

  private onStep(step: TourStep | null) {
    this.cancelTimers();
    this.currentStep = step;
    this.fallback = false;
    this.layout = null;
    this.lastTargetSelector = null;
    this.cdr.markForCheck();

    const def = this.stepDef;
    if (def?.kind === 'gated' && def.targetSelector) {
      this.lastTargetSelector = def.targetSelector;
      this.startTargetPoll(def.targetSelector);
    }
  }

  /**
   * Polls for the gated target, applying every new rect it sees and stopping
   * only once the rect has held still for STABLE_TICKS in a row. The
   * TARGET_POLL_MAX_ATTEMPTS budget bounds the whole loop; spending it with a
   * rect already applied simply keeps that last rect. Only a target that was
   * NEVER found falls back to explain rendering.
   */
  private startTargetPoll(selector: string) {
    let attempts = 0;
    let applied: Rect | null = null;
    let stableTicks = 0;
    const tick = () => {
      this.pollTimer = null;
      attempts++;
      const rect = this.measure(selector);
      if (rect && (!applied || !sameRect(applied, rect))) {
        applied = rect;
        stableTicks = 1;
        this.applyRect(rect);
        if (this.settleTimer) {
          clearTimeout(this.settleTimer);
        }
        this.settleTimer = setTimeout(() => this.remeasure(), SETTLE_REMEASURE_MS);
      } else if (rect) {
        stableTicks++;
      }
      if (rect && stableTicks >= STABLE_TICKS) {
        return;
      }
      if (attempts >= TARGET_POLL_MAX_ATTEMPTS) {
        if (!applied) {
          this.fallback = true;
          this.cdr.markForCheck();
        }
        return;
      }
      this.pollTimer = setTimeout(tick, TARGET_POLL_MS);
    };
    tick();
  }

  private measure(selector: string): Rect | null {
    const el = document.querySelector(selector);
    if (!el) return null;
    const r = el.getBoundingClientRect();
    // A target translated off-screen mid-transition still has a real size, but
    // computeSpotlight would clamp it to a zero-width hole at the viewport edge
    // (a fully dark screen). Treat it as not measurable yet.
    const offScreen = r.right <= 0 || r.bottom <= 0 || r.left >= window.innerWidth || r.top >= window.innerHeight;
    if (r.width <= 0 || r.height <= 0 || offScreen) return null;
    return { top: r.top, left: r.left, width: r.width, height: r.height };
  }

  private remeasure() {
    if (!this.lastTargetSelector || this.fallback) return;
    const rect = this.measure(this.lastTargetSelector);
    if (rect) this.applyRect(rect);
  }

  private applyRect(rect: Rect) {
    const cardHeight = this.cardRef?.nativeElement.offsetHeight || CARD_HEIGHT_FALLBACK_PX;
    this.layout = computeSpotlight(rect, window.innerWidth, window.innerHeight, cardHeight, BOTTOM_RESERVE_PX);
    this.cdr.markForCheck();
    // The card is measured for the first time on this pass; re-run once so the
    // placement uses the real height instead of the fallback.
    if (!this.cardRef) {
      this.zone.runOutsideAngular(() => {
        this.cardMeasureTimer = setTimeout(() => this.zone.run(() => {
          this.cardMeasureTimer = null;
          if (this.currentStep !== null && this.layout && this.cardRef) {
            this.layout = computeSpotlight(rect, window.innerWidth, window.innerHeight, this.cardRef.nativeElement.offsetHeight, BOTTOM_RESERVE_PX);
            this.cdr.markForCheck();
          }
        }), 0);
      });
    }
  }

  private cancelTimers() {
    if (this.pollTimer) { clearTimeout(this.pollTimer); this.pollTimer = null; }
    if (this.settleTimer) { clearTimeout(this.settleTimer); this.settleTimer = null; }
    if (this.cardMeasureTimer) { clearTimeout(this.cardMeasureTimer); this.cardMeasureTimer = null; }
  }
}
