/**
 * Spring physics for the bottom-sheet drag-dismiss gesture
 * (SheetDragDismissDirective).
 *
 * Native sheets (Spotify = UIKit's UISpringTimingParameters) feed the
 * gesture's release velocity into a spring, so the sheet keeps moving at
 * finger speed and decelerates naturally — the settle duration is emergent,
 * never authored. Ionic's sheet gesture instead plays a fixed 500ms
 * ease-curve whatever the fling, which is the "clunky close" Andy flagged.
 *
 * CSS/WAAPI easings cannot take an initial velocity, so the spring is solved
 * analytically here (damped harmonic oscillator seeded with the release
 * velocity) and SAMPLED into dense WAAPI keyframes. Transform-only keyframes
 * with linear inter-frame easing stay compositor-eligible and work on every
 * supported iOS (no CSS linear() dependency, which would need iOS 17.2+).
 *
 * Spring parametrization follows Apple's (dampingRatio, response):
 *   omega0 = 2*pi / response,  zeta = dampingRatio  (mass 1).
 */

export interface SpringSpec {
  /** 1 = critically damped (no overshoot); < 1 bounces slightly. */
  dampingRatio: number;
  /** Apple-style response (s) — the spring's characteristic period. */
  response: number;
}

/** Exit (dismiss) — no bounce, brisk. */
export const SHEET_EXIT_SPRING: SpringSpec = { dampingRatio: 1, response: 0.32 };

/** Snap-back to open — a soft catch with a hint of life. */
export const SHEET_SNAPBACK_SPRING: SpringSpec = { dampingRatio: 0.85, response: 0.34 };

/** Motion is considered settled when within this many px of the target. */
const REST_DELTA_PX = 0.35;

const MIN_DURATION_MS = 120;
const MAX_DURATION_MS = 700;

/** ~60 samples across the animation is visually indistinguishable from exact. */
const MAX_SAMPLES = 80;

/**
 * UIKit's rubber-band curve for overdrag past a hard limit:
 * f(x, d, c) = (x·d·c) / (d + c·x), c = 0.55 — approaches d asymptotically.
 *
 * @param overshootPx how far past the limit the finger has pulled
 * @param dimensionPx the reference dimension (sheet height)
 */
export function rubberband(overshootPx: number, dimensionPx: number, c = 0.55): number {
  return (overshootPx * dimensionPx * c) / (dimensionPx + c * overshootPx);
}

/**
 * Commit-vs-cancel at release — same projection Ionic/UIKit use: the drag
 * offset plus 350ms of projected velocity, dismissed past half the height.
 * A fast downward flick dismisses from near the top; a fast upward flick
 * rescues a sheet dragged nearly closed.
 *
 * @param offsetPx     current downward drag offset (>= 0)
 * @param velocityPxMs release velocity, positive = downward (px/ms)
 * @param heightPx     sheet height
 */
export function sheetShouldDismiss(offsetPx: number, velocityPxMs: number, heightPx: number): boolean {
  if (heightPx <= 0) {
    return false;
  }
  return (offsetPx + velocityPxMs * 350) / heightPx > 0.5;
}

export interface SpringMotion {
  /** Sampled positions (px), uniformly spaced in time over durationMs. */
  positions: number[];
  durationMs: number;
}

/**
 * Solve the damped spring x(t) from `from` to `to` with initial velocity
 * `velocityPxMs`, and sample it into uniform keyframe positions.
 */
export function springKeyframes(
  motion: { from: number; to: number; velocityPxMs: number },
  spec: SpringSpec,
): SpringMotion {
  const omega0 = (2 * Math.PI) / spec.response; // rad/s
  const zeta = spec.dampingRatio;
  const u0 = motion.from - motion.to; // px
  const v0 = motion.velocityPxMs * 1000; // px/s

  // Analytic displacement u(t) (t in seconds).
  let u: (t: number) => number;
  if (zeta < 1) {
    const omegaD = omega0 * Math.sqrt(1 - zeta * zeta);
    const b = (v0 + zeta * omega0 * u0) / omegaD;
    u = (t) => Math.exp(-zeta * omega0 * t) * (u0 * Math.cos(omegaD * t) + b * Math.sin(omegaD * t));
  } else {
    const b = v0 + omega0 * u0;
    u = (t) => (u0 + b * t) * Math.exp(-omega0 * t);
  }

  // Rest time: last 1ms step at which the spring is still displaced.
  let restMs = 0;
  for (let ms = 0; ms <= MAX_DURATION_MS; ms++) {
    if (Math.abs(u(ms / 1000)) > REST_DELTA_PX) {
      restMs = ms;
    }
  }
  const durationMs = Math.min(MAX_DURATION_MS, Math.max(MIN_DURATION_MS, restMs + 16));

  const samples = Math.min(MAX_SAMPLES, Math.max(2, Math.ceil(durationMs / 12)));
  const positions: number[] = [];
  for (let i = 0; i <= samples; i++) {
    const t = (durationMs / 1000) * (i / samples);
    // Pin the final sample to the exact target so fill:'both' holds it clean.
    positions.push(i === samples ? motion.to : motion.to + u(t));
  }
  return { positions, durationMs };
}
