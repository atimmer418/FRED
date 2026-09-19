/**
 * Velocity-matched settle timing for the tab pager (FRED-? "buttery swipes").
 *
 * Native pagers (Instagram/Snapchat = UIScrollView paging) never animate the
 * post-release snap with an authored duration: the settle inherits the finger's
 * release velocity, so fast flicks snap quickly and gentle releases glide. By
 * default Swiper does the opposite — a fixed `speed` ms CSS transition whatever
 * the release velocity — which reads as a velocity discontinuity ("collision")
 * at finger-up.
 *
 * The pager keeps Swiper's slide-target decision but overrides the settle
 * DURATION per swipe: with an ease-out cubic curve whose initial slope is
 * y1/x1 = 3 (cubic-bezier(0.33, 1, 0.68, 1), set in tabs.page.scss), the
 * transition's initial speed is 3·distance/duration. Solving for continuity
 * with the finger (initial speed == release velocity) gives
 * duration = 3·distance/velocity, clamped so nothing feels laggy or snappy
 * beyond taste.
 */

/** One tracked touch position during a drag. */
export interface DragSample {
  /** Horizontal touch position, px. */
  x: number;
  /** Timestamp, ms (performance.now() clock). */
  t: number;
}

/** How far back the velocity window reaches, ms. */
export const SAMPLE_WINDOW_MS = 100;

/**
 * Recency time-constant (ms) for the release-velocity weighting. Smaller =
 * more weight on the final movement (twitchier, tracks a late flick harder);
 * larger = closer to a flat average over the whole window. ~50ms keys the
 * estimate to roughly the last frame or two of the drag.
 */
export const VELOCITY_RECENCY_TAU_MS = 70;

/** Clamp floor: even the hardest flick animates at least this long. */
export const SETTLE_MIN_MS = 150;

/** Clamp ceiling: even a dead-slow release settles within this. */
export const SETTLE_MAX_MS = 380;

/** Used when velocity gives no signal (snap-backs, ~zero-speed releases). */
export const SETTLE_DEFAULT_MS = 300;

/** Initial slope (y1/x1) of the settle curve cubic-bezier(0.33, 1, 0.68, 1). */
const CURVE_INITIAL_SLOPE = 3;

/** Below this speed (px/ms) the release is treated as velocity-less. */
const MIN_MEANINGFUL_VELOCITY = 0.01;

/** Drop samples that fell out of the velocity window. */
export function pruneSamples(
  samples: DragSample[],
  now: number,
  windowMs: number = SAMPLE_WINDOW_MS,
): DragSample[] {
  return samples.filter((s) => now - s.t <= windowMs);
}

/**
 * Signed release velocity in px/ms (positive = rightward), RECENCY-WEIGHTED.
 *
 * Each adjacent-sample segment gets a per-segment velocity, weighted by its own
 * duration AND by an exponential decay from finger-up (t = last sample). The
 * movement right before release therefore dominates, so a slow-drag-then-flick
 * reads as fast instead of being flattened by a plain window average (which was
 * the old behavior and left the settle lagging the finger on late flicks).
 * Returns 0 when there is not enough signal. A two-sample drag reduces to that
 * single segment's velocity (the weighting cancels in the ratio).
 */
export function releaseVelocity(samples: DragSample[]): number {
  if (samples.length < 2) {
    return 0;
  }
  const lastT = samples[samples.length - 1].t;
  let weightedSum = 0;
  let weightTotal = 0;
  for (let i = 1; i < samples.length; i++) {
    const prev = samples[i - 1];
    const cur = samples[i];
    const dt = cur.t - prev.t;
    if (dt <= 0) {
      continue; // degenerate/zero-duration segment — no usable velocity
    }
    const segVelocity = (cur.x - prev.x) / dt;
    const weight = dt * Math.exp(-(lastT - cur.t) / VELOCITY_RECENCY_TAU_MS);
    weightedSum += segVelocity * weight;
    weightTotal += weight;
  }
  return weightTotal > 0 ? weightedSum / weightTotal : 0;
}

/**
 * Settle duration for the remaining travel, matched to the finger.
 *
 * @param remainingPx      absolute distance left to the target slide, px
 * @param velocityToTarget release velocity projected onto the target direction,
 *                         px/ms — positive when the finger was moving toward
 *                         the target, negative when it opposed it (snap-back)
 */
export function settleDurationMs(remainingPx: number, velocityToTarget: number): number {
  if (velocityToTarget < MIN_MEANINGFUL_VELOCITY) {
    return SETTLE_DEFAULT_MS;
  }
  const matched = (CURVE_INITIAL_SLOPE * remainingPx) / velocityToTarget;
  return Math.round(Math.min(SETTLE_MAX_MS, Math.max(SETTLE_MIN_MS, matched)));
}
