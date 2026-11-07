/**
 * The family timeline under a kids plan's hero. Pure, no Angular: places each kid's birth year,
 * "Now" and the last 18th birthday on a 0..100 bar.
 */

export interface TimelineDot {
  kind: 'kid' | 'now' | 'end';
  label: string;
  year: number | null;
  /** A kid with no birth year: drawn dashed at this year. */
  undated: boolean;
  /** Strictly before Now on the bar (drawn filled green). */
  passed: boolean;
  /** 0..100 along the bar. */
  position: number;
}

/** The first ten years from the earliest event take this share of the width. */
const NEAR_YEARS = 10;
const NEAR_PCT = 65;

/**
 * Dots in bar order. The bar runs from the earliest event (the earliest dated birth year, or this
 * year when that is earlier) to the last 18th birthday. The first ten years map linearly onto
 * 0..65 and the rest onto 65..100, so near-term dots never pile up; a span of ten years or less
 * maps linearly onto 0..100. Years are whole calendar years: Now sits at this year, so a kid
 * expected this year sits beside Now and is not passed.
 */
export function buildKidTimeline(
  kids: ReadonlyArray<{ label: string; birthYear: number | null }>,
  now: Date,
): { dots: TimelineDot[]; passedPct: number } {
  const thisYear = now.getFullYear();
  const years = kids.map(k => k.birthYear ?? thisYear);
  const start = Math.min(thisYear, ...years);
  const last = (years.length > 0 ? Math.max(...years) : thisYear) + 18;
  const span = last - start;

  // Clamped: Now can sit past the end once every kid has turned 18.
  const position = (year: number): number => {
    const t = year - start;
    let pct: number;
    if (span <= 0) pct = 0;
    else if (span <= NEAR_YEARS) pct = (t / span) * 100;
    else if (t <= NEAR_YEARS) pct = (t / NEAR_YEARS) * NEAR_PCT;
    else pct = NEAR_PCT + ((t - NEAR_YEARS) / (span - NEAR_YEARS)) * (100 - NEAR_PCT);
    return Math.min(100, Math.max(0, pct));
  };

  const nowPct = position(thisYear);
  const nowDot: TimelineDot = { kind: 'now', label: 'Now', year: thisYear, undated: false, passed: false, position: nowPct };
  const kidDots: TimelineDot[] = kids.map((kid, i) => {
    const pct = position(kid.birthYear ?? thisYear);
    return {
      kind: 'kid',
      label: kid.label.trim() || `Kid ${i + 1}`,
      year: kid.birthYear,
      undated: kid.birthYear === null,
      passed: pct < nowPct,
      position: pct,
    };
  });
  const endDot: TimelineDot = {
    kind: 'end',
    label: kids.length >= 2 ? 'All 18' : '18',
    year: last,
    undated: false,
    passed: false,
    position: 100,
  };

  // Stable sort by position; at a tie Now comes before the kids beside it.
  const rank = (d: TimelineDot): number => (d.kind === 'now' ? 0 : d.kind === 'kid' ? 1 : 2);
  const dots = [nowDot, ...kidDots, endDot]
    .map((d, i) => ({ d, i }))
    .sort((a, b) => a.d.position - b.d.position || rank(a.d) - rank(b.d) || a.i - b.i)
    .map(x => x.d);

  return { dots, passedPct: nowPct };
}
