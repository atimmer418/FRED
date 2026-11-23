import {
  AfterViewInit, Component, ElementRef, OnDestroy, QueryList, ViewChildren, computed, input,
} from '@angular/core';

/** One shimmer sweep, edge to edge. */
export const SKELETON_SHIMMER_MS = 1400;

const PILL_RADIUS = '999px';
const CIRCLE_RADIUS = '50%';

/**
 * SkeletonComponent: a grey placeholder block in the shape of the content it stands in for.
 *
 * One block by default (width, height, radius), or `lines` stacked text lines with the last one
 * shorter. The fill is flat #eef1f6; a gradient child sweeps across it on WAAPI because the global
 * reduced-motion rule zeroes CSS animation durations outright. Under reduced motion, or without
 * element.animate, the sweep never starts and the block stays flat.
 *
 * The host is aria-hidden: the loading region around it carries aria-busy="true" and the real
 * content and the skeleton are exclusive branches of one @if, so nothing pops in late.
 */
@Component({
  selector: 'app-skeleton',
  standalone: true,
  templateUrl: './skeleton.component.html',
  styleUrls: ['./skeleton.component.scss'],
  host: {
    'aria-hidden': 'true',
    '[style.width]': 'hostWidth()',
  },
})
export class SkeletonComponent implements AfterViewInit, OnDestroy {
  /** Any CSS length. A circle ignores it and takes the height. */
  readonly width = input<string>('100%');
  /** Any CSS length. With `lines`, the height of each line. */
  readonly height = input<string>('16px');
  /** A CSS length, `pill` (999px) or `circle` (50%, width = height). */
  readonly radius = input<string>('8px');
  /** Stacked text lines instead of one block. The last line is shorter. */
  readonly lines = input<number | undefined>(undefined);

  @ViewChildren('shimmer') private shimmers!: QueryList<ElementRef<HTMLElement>>;

  private animations: Animation[] = [];

  readonly borderRadius = computed(() => {
    const radius = this.radius();
    if (radius === 'pill') return PILL_RADIUS;
    if (radius === 'circle') return CIRCLE_RADIUS;
    return radius;
  });

  readonly hostWidth = computed(() => (this.radius() === 'circle' ? this.height() : this.width()));

  /** One entry per block; the template loops it. */
  readonly blocks = computed(() => {
    const n = this.lines();
    return Array.from({ length: n && n > 0 ? n : 1 }, (_, i) => i);
  });

  readonly blockCount = computed(() => this.blocks().length);

  ngAfterViewInit(): void {
    this.restart();
    // A changed `lines` count re-renders the blocks; the new ones need their own sweep.
    this.shimmers.changes.subscribe(() => this.restart());
  }

  ngOnDestroy(): void {
    this.stop();
  }

  private restart(): void {
    this.stop();
    if (this.prefersReducedMotion()) return;
    this.shimmers.forEach(ref => {
      const el = ref.nativeElement;
      if (typeof el.animate !== 'function') return;
      this.animations.push(el.animate(
        [{ transform: 'translateX(-100%)' }, { transform: 'translateX(100%)' }],
        { duration: SKELETON_SHIMMER_MS, iterations: Infinity, easing: 'linear' },
      ));
    });
  }

  private stop(): void {
    this.animations.forEach(a => a.cancel());
    this.animations = [];
  }

  private prefersReducedMotion(): boolean {
    return typeof window.matchMedia === 'function'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  }
}
