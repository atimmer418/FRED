/**
 * The family timeline under a kids plan's hero. Pure, no Angular: places each kid's birth year,
 * "Now" and the last 18th birthday on a 0..100 bar.
 */

export interface TimelineDot {
  kind: 'kid' | 'now' | 'end';
  label: string;
  year: number | null;
  /** A kid with no birth year: drawn dashed, parked just before the end. */
  undated: boolean;
  /** A kid expected this year with no month known: labelled "This year", like the card (Ruling R4). */
  expectedThisYear: boolean;
  /** Born in an earlier calendar year than Now (drawn filled green). Never true for an undated kid. */
  passed: boolean;
  /** 0..100 along the bar. */
  position: number;
}

/** The first ten years from the earliest event take this share of the width. */
const NEAR_YEARS = 10;
const NEAR_PCT = 65;

/**
 * The least distance, in percent of the bar, between neighbouring dots. Six kids plus Now and the
 * end make eight dots; seven gaps of 12 is 84, so the even-out passes always fit on the bar.
 */
const MIN_GAP = 12;

/**
 * Dots in bar order. The bar runs from the earliest event (the earliest dated birth year, or this
 * year when that is earlier) to the last dated kid's 18th birthday, or this year + 18 when no kid
 * is dated. Undated kids do not move either end. The first ten years map linearly onto 0..65 and
 * the rest onto 65..100; a span of ten years or less maps linearly onto 0..100.
 *
 * Each undated kid is parked at the end (after the dated kids, before the end dot). The dots are
 * then sorted by scale position (at a tie: Now, dated kid, undated kid, end) and evened out so no
 * two neighbours sit closer than MIN_GAP: a forward pass pushes each dot to at least MIN_GAP past
 * the one before it, then the end is pinned at 100 and a backward pass pulls each dot to at most
 * MIN_GAP before the one after it. So a few kids born in the last couple of years get real room
 * on the bar, and the green fill (passedPct) runs to wherever the Now dot lands.
 *
 * Years are whole calendar years: a dated kid is passed when born before this year, so a kid
 * expected this year sits just after Now and is not passed. The month only decides the label: a
 * kid dated this year with no month reads "This year"; with a month, the year (born or arriving).
 */
export function buildKidTimeline(
  kids: ReadonlyArray<{ label: string; birthYear: number | null; birthMonth?: number | null }>,
  now: Date,
): { dots: TimelineDot[]; passedPct: number } {
  const thisYear = now.getFullYear();
  const datedYears = kids.map(k => k.birthYear).filter((y): y is number => y !== null);
  const start = Math.min(thisYear, ...datedYears);
  const last = (datedYears.length > 0 ? Math.max(...datedYears) : thisYear) + 18;
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

  const nowDot: TimelineDot = {
    kind: 'now', label: 'Now', year: thisYear, undated: false, expectedThisYear: false, passed: false, position: position(thisYear),
  };
  const kidDots: TimelineDot[] = kids.map((kid, i) => ({
    kind: 'kid',
    label: kid.label.trim() || `Kid ${i + 1}`,
    year: kid.birthYear,
    undated: kid.birthYear === null,
    expectedThisYear: kid.birthYear === thisYear && (kid.birthMonth ?? null) === null,
    passed: kid.birthYear !== null && kid.birthYear < thisYear,
    position: kid.birthYear === null ? 100 : position(kid.birthYear),
  }));
  const endDot: TimelineDot = {
    kind: 'end',
    label: kids.length >= 2 ? 'All 18' : '18',
    year: last,
    undated: false,
    expectedThisYear: false,
    passed: false,
    position: 100,
  };

  // Stable sort by scale position; at a tie Now, then dated kids, then undated kids, then the end.
  const rank = (d: TimelineDot): number =>
    d.kind === 'now' ? 0 : d.kind === 'end' ? 3 : d.undated ? 2 : 1;
  const dots = [nowDot, ...kidDots, endDot]
    .map((d, i) => ({ d, i }))
    .sort((a, b) => a.d.position - b.d.position || rank(a.d) - rank(b.d) || a.i - b.i)
    .map(x => x.d);

  // Even out: push forward to at least MIN_GAP apart, pin the end, pull back to fit.
  for (let i = 1; i < dots.length; i++) {
    dots[i].position = Math.max(dots[i].position, dots[i - 1].position + MIN_GAP);
  }
  dots[dots.length - 1].position = 100;
  for (let i = dots.length - 2; i >= 0; i--) {
    dots[i].position = Math.min(dots[i].position, dots[i + 1].position - MIN_GAP);
  }
  for (const d of dots) d.position = Math.min(100, Math.max(0, d.position));

  return { dots, passedPct: nowDot.position };
}
