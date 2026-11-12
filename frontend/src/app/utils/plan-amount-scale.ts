/**
 * The value grid behind the amount trays: a floor, a cap and the detents between them. A scale is
 * UNIFORM (one `step` end to end, as the Freedom Plan tiles use) or PIECEWISE (`segments` whose
 * step grows with the amount, so a finger travels the same distance per detent while the dollars
 * per detent climb). Either may carry a `fine` step for the minus and plus buttons. Pure math, no
 * DOM, so the tray, the pages and the specs can all lean on it. Scales are treated as immutable:
 * a page that needs a different one builds a new object.
 */

export type PlanField = 'income' | 'invest';

export interface AmountSegment {
  /** Where this segment ends and the next begins; the last one's `to` is the cap. */
  to: number;
  /** The distance between detents inside it. */
  step: number;
}

interface ScaleEnds {
  /** The slider's left end. */
  min: number;
  /** The slider's right end, and the hard cap. */
  max: number;
  /** One minus or plus tap. Without it a tap moves to the neighbouring detent. */
  fine?: number;
}

export interface UniformScale extends ScaleEnds {
  /** One detent, end to end. */
  step: number;
  segments?: undefined;
}

export interface PiecewiseScale extends ScaleEnds {
  /** From `min` upward, each running to its `to`; the last `to` is `max`. */
  segments: readonly AmountSegment[];
  step?: undefined;
}

export type PlanAmountScale = UniformScale | PiecewiseScale;

/** Andy's values (2026-11-10): the slider finds the ballpark, minus and plus settle the figure. */
export const PLAN_AMOUNT_SCALES: Readonly<Record<PlanField, PlanAmountScale>> = {
  income: { min: 500, max: 30_000, step: 250 },
  invest: { min: 0, max: 10_000, step: 100 },
};

/** A detent commits only once the finger is this far along the way to it (hysteresis). */
export const COMMIT_FRACTION = 0.65;

/** Round dollar steps a built scale picks from. */
export const STEP_LADDER: readonly number[] = [
  1, 5, 10, 25, 50, 100, 250, 500, 1_000, 2_500, 5_000, 10_000, 25_000, 50_000, 100_000,
];
/** Round numbers a breakpoint may land on: the step ladder carried on upward. */
const BREAKPOINT_LADDER: readonly number[] = [...STEP_LADDER, 250_000, 500_000, 1_000_000, 2_500_000, 5_000_000, 10_000_000];
/** A built scale has this many detents across the whole track, give or take the rounding. */
export const DETENTS_MIN = 80;
export const DETENTS_MAX = 160;
const DETENTS_TARGET = 120;
/** How many segments a built scale has at most: enough for the step to grow, few enough to read. */
const BUILT_SEGMENTS = 4;

/** Guards the float comparisons along a walk: amounts carry cents at most. */
const EPS = 1e-9;

export function isPiecewise(scale: PlanAmountScale): scale is PiecewiseScale {
  return scale.segments !== undefined;
}

// ── Detents ──────────────────────────────────────────────────────────────────

const detentCache = new WeakMap<PlanAmountScale, readonly number[]>();

/**
 * Every detent value, floor to cap, both included. Inside a segment the detents sit on multiples
 * of its step (a floor of $5 with a $25 step gives 25, 50, 75, not 30, 55, 80); a segment's end is
 * always a detent, so breakpoints land on their round numbers and the last detent is exactly the
 * cap, even when the cap is not round. Cached per scale object: the tray asks on every move.
 */
export function detents(scale: PlanAmountScale): readonly number[] {
  const cached = detentCache.get(scale);
  if (cached) return cached;
  const list = isPiecewise(scale) ? walkSegments(scale) : walkUniform(scale);
  detentCache.set(scale, list);
  return list;
}

function walkUniform(scale: UniformScale): number[] {
  const count = Math.floor((scale.max - scale.min) / scale.step) + 1;
  return Array.from({ length: count }, (_, i) => scale.min + i * scale.step);
}

function walkSegments(scale: PiecewiseScale): number[] {
  const out = [scale.min];
  let from = scale.min;
  for (const seg of scale.segments) {
    const to = Math.min(seg.to, scale.max);
    if (to <= from + EPS || seg.step <= 0) continue;
    // k * step, not a running sum, so nothing drifts over a long segment.
    for (let k = Math.floor(from / seg.step + EPS) + 1; k * seg.step <= to + EPS; k++) out.push(k * seg.step);
    if (out[out.length - 1] < to - EPS) out.push(to);
    from = to;
  }
  if (from < scale.max - EPS) out.push(scale.max);
  return out;
}

/** How many detents the scale has, both ends included. */
export function detentCount(scale: PlanAmountScale): number {
  if (isPiecewise(scale)) return detents(scale).length;
  return Math.floor((scale.max - scale.min) / scale.step) + 1;
}

/** The value at a detent; the index is clamped onto the scale. */
export function detentValue(scale: PlanAmountScale, index: number): number {
  const last = detentCount(scale) - 1;
  const i = Math.max(0, Math.min(last, Math.round(index)));
  if (isPiecewise(scale)) return detents(scale)[i];
  return scale.min + i * scale.step;
}

/** The value held onto the scale's range. */
export function clampToScale(scale: PlanAmountScale, value: number): number {
  return Math.max(scale.min, Math.min(scale.max, value));
}

/**
 * The cap for a field whose stored value may already sit past its natural one (a goal balance
 * over its target): the cap itself, or, once the value has overshot it, the next round number
 * above the value, so the figure can still go up and a first touch never pulls it down onto the
 * scale. Build it from the STORED value, not a draft, or the scale would move under a finger.
 */
export function capAbove(cap: number, value: number): number {
  if (!(value > cap) || !Number.isFinite(value)) return cap;
  const point = BREAKPOINT_LADDER.find(p => p > value + EPS);
  if (point !== undefined) return point;
  // Past the ladder: its 1, 2.5, 5 pattern carried on upward.
  for (let scale = 10; scale < 1e12; scale *= 10) {
    for (const p of [2_500_000, 5_000_000, 10_000_000]) if (p * scale > value + EPS) return p * scale;
  }
  return value;
}

/**
 * Where a value sits in index space: the detent at or below it, and how far it is toward the
 * next (0 exactly on a detent). The value is clamped first.
 */
function locate(scale: PlanAmountScale, value: number): { lower: number; frac: number } {
  const v = clampToScale(scale, value);
  const last = detentCount(scale) - 1;
  if (!isPiecewise(scale)) {
    const offset = (v - scale.min) / scale.step;
    const lower = Math.min(last, Math.floor(offset));
    return { lower, frac: lower === last ? 0 : offset - lower };
  }
  const list = detents(scale);
  let lo = 0;
  let hi = last;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (list[mid] <= v) lo = mid;
    else hi = mid - 1;
  }
  if (lo === last) return { lower: lo, frac: 0 };
  return { lower: lo, frac: (v - list[lo]) / (list[lo + 1] - list[lo]) };
}

/** The detent closest to a value; ties go up. */
export function nearestIndex(scale: PlanAmountScale, value: number): number {
  const { lower, frac } = locate(scale, value);
  return frac >= 0.5 ? lower + 1 : lower;
}

/**
 * Where along the track (0 to 1) a value sits: off-grid values interpolate, out-of-range ones pin
 * to an end. On a piecewise scale the track is index space (every detent the same distance apart),
 * so the position follows the detents, not the dollars.
 */
export function positionOf(scale: PlanAmountScale, value: number): number {
  if (isPiecewise(scale)) {
    const last = detentCount(scale) - 1;
    if (last <= 0) return 0;
    const { lower, frac } = locate(scale, value);
    return (lower + frac) / last;
  }
  const span = scale.max - scale.min;
  if (span <= 0) return 0;
  return (clampToScale(scale, value) - scale.min) / span;
}

/** The slider step in force at a value: its segment's, or the uniform step. A breakpoint belongs to the segment above it. */
export function stepAt(scale: PlanAmountScale, value: number): number {
  if (!isPiecewise(scale)) return scale.step;
  const seg = scale.segments.find(s => value < s.to) ?? scale.segments[scale.segments.length - 1];
  return seg.step;
}

/** The values where the step changes, strictly inside the ends: where the tray draws its ticks. */
export function breakpoints(scale: PlanAmountScale): number[] {
  if (!isPiecewise(scale)) return [];
  return scale.segments.slice(0, -1).map(s => s.to).filter(to => to > scale.min && to < scale.max);
}

/**
 * The detent a finger at `raw` (a fractional index along the track) settles on, given the one it
 * holds now: the next detent is adopted only once the finger is COMMIT_FRACTION of the way there,
 * so a finger resting between two never flickers.
 */
export function commitIndex(scale: PlanAmountScale, raw: number, current: number): number {
  const last = detentCount(scale) - 1;
  const r = Math.max(0, Math.min(last, raw));
  if (r > current + COMMIT_FRACTION) return Math.min(last, Math.floor(r + 1 - COMMIT_FRACTION));
  if (r < current - COMMIT_FRACTION) return Math.max(0, Math.ceil(r - 1 + COMMIT_FRACTION));
  return current;
}

/**
 * One minus or plus tap (or a hold tick, `multiplier` steps at once). A value off the scale lands
 * on the nearer end first. With a `fine` step the taps walk a grid of that step anchored at the
 * floor, clamped at the cap (so a cap of $1,234.56 is reached from $1,230); without one they walk
 * the detents. Either way an off-grid value rounds onto the grid in the tap's direction first.
 */
export function nudge(scale: PlanAmountScale, value: number, direction: 1 | -1, multiplier = 1): number {
  const v = clampToScale(scale, value);
  if (v !== value) return v;
  const extra = Math.max(1, Math.round(multiplier)) - 1;
  if (scale.fine !== undefined && scale.fine > 0) {
    const offset = (v - scale.min) / scale.fine;
    const target = Number.isInteger(offset)
      ? offset + direction * (extra + 1)
      : direction > 0 ? Math.ceil(offset) + extra : Math.floor(offset) - extra;
    return clampToScale(scale, scale.min + target * scale.fine);
  }
  const { lower, frac } = locate(scale, v);
  const target = frac === 0
    ? lower + direction * (extra + 1)
    : direction > 0 ? lower + 1 + extra : lower - extra;
  return detentValue(scale, target);
}

// ── Building a scale ─────────────────────────────────────────────────────────

/** The ladder value nearest to x by ratio (so 33 picks 25 over 50, and 170 picks 250 over 100). */
function nearestLadderStep(x: number): number {
  if (!(x > 0)) return STEP_LADDER[0];
  let best = STEP_LADDER[0];
  for (const step of STEP_LADDER) {
    if (Math.abs(Math.log(step / x)) < Math.abs(Math.log(best / x))) best = step;
  }
  return best;
}

/** The largest round number at or below x, or 0 when x is under the smallest. */
function roundDownToLadder(x: number): number {
  let best = 0;
  for (const point of BREAKPOINT_LADDER) {
    if (point <= x + EPS) best = point;
  }
  return best;
}

/** The detents a candidate set of steps gives, over fixed segment ends. */
function countFor(min: number, ends: readonly number[], steps: readonly number[]): number {
  return detentCount({ min, max: ends[ends.length - 1], segments: ends.map((to, i) => ({ to, step: steps[i] })) });
}

/**
 * Steps with segment `i` moved one rung in `dir` (up is coarser), the rest moved only as far as
 * keeps the steps non-decreasing with the amount. Null when that segment is at the ladder's end.
 */
function shifted(steps: readonly number[], i: number, dir: 1 | -1): number[] | null {
  const rung = STEP_LADDER.indexOf(steps[i]) + dir;
  if (rung < 0 || rung >= STEP_LADDER.length) return null;
  const next = steps.slice();
  next[i] = STEP_LADDER[rung];
  if (dir > 0) for (let j = i + 1; j < next.length; j++) next[j] = Math.max(next[j], next[i]);
  else for (let j = 0; j < i; j++) next[j] = Math.min(next[j], next[i]);
  return next;
}

/**
 * A piecewise scale from a floor to a cap, 80 to 160 detents across the track whenever the span
 * allows it (steps are whole dollars, so a span under a couple of hundred has fewer). The segment
 * ends are successive halves of the span floored onto round numbers; each segment's step is the
 * round step that gives it about its share of the detents, nudged coarser or finer until the total
 * lands in the band. The last detent is exactly `max`, round or not, so a withdrawable $1,234.56
 * is reachable. `fine` is what the minus and plus buttons move by.
 */
export function buildAmountScale(min: number, max: number, fine: number): PiecewiseScale {
  const fineStep = fine > 0 ? fine : undefined;
  if (!(max > min)) return { min, max: min, segments: [{ to: min, step: STEP_LADDER[0] }], fine: fineStep };

  // Segment ends, top down: half the remaining span each time, floored onto a round number. A
  // floor that sits high (say $100 to $300) swallows those, so the end is then measured from the
  // floor instead: still a round distance, and the step still gets to grow.
  const inner: number[] = [];
  let point = max;
  for (let i = 1; i < BUILT_SEGMENTS; i++) {
    point = min + (point - min) / 2;
    const absolute = roundDownToLadder(point);
    const rounded = absolute > min + EPS ? absolute : min + roundDownToLadder(point - min);
    if (rounded > min + EPS && rounded < max - EPS && (inner.length === 0 || rounded < inner[inner.length - 1])) inner.push(rounded);
  }
  const ends = [...inner.reverse(), max];

  // Each segment's share of the detents, then the steps made non-decreasing with the amount.
  const perSegment = DETENTS_TARGET / ends.length;
  let from = min;
  let steps = ends.map(to => {
    const step = nearestLadderStep((to - from) / perSegment);
    from = to;
    return step;
  });
  for (let i = 1; i < steps.length; i++) steps[i] = Math.max(steps[i], steps[i - 1]);

  // Walk the total into the band, one rung at a time, each move the one that lands nearest the
  // target. Bounded, and the best state seen wins, in case a narrow span can only straddle it.
  let best = steps;
  let bestDistance = Math.abs(countFor(min, ends, steps) - DETENTS_TARGET);
  for (let guard = 0; guard < 16; guard++) {
    const count = countFor(min, ends, steps);
    if (count >= DETENTS_MIN && count <= DETENTS_MAX) { best = steps; break; }
    const dir: 1 | -1 = count > DETENTS_MAX ? 1 : -1;
    let pick: number[] | null = null;
    let pickDistance = Infinity;
    for (let i = 0; i < steps.length; i++) {
      const candidate = shifted(steps, i, dir);
      if (!candidate) continue;
      const distance = Math.abs(countFor(min, ends, candidate) - DETENTS_TARGET);
      if (distance < pickDistance) { pick = candidate; pickDistance = distance; }
    }
    if (!pick) break;
    steps = pick;
    if (pickDistance < bestDistance) { best = steps; bestDistance = pickDistance; }
  }

  return { min, max, segments: ends.map((to, i) => ({ to, step: best[i] })), fine: fineStep };
}
