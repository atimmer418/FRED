import {
  COMMIT_FRACTION,
  DETENTS_MAX,
  DETENTS_MIN,
  PLAN_AMOUNT_SCALES,
  PiecewiseScale,
  STEP_LADDER,
  breakpoints,
  buildAmountScale,
  capAbove,
  clampToScale,
  commitIndex,
  detentCount,
  detentValue,
  detents,
  isPiecewise,
  nearestIndex,
  nudge,
  positionOf,
  stepAt,
} from './plan-amount-scale';

describe('plan-amount-scale', () => {
  const income = PLAN_AMOUNT_SCALES.income;
  const invest = PLAN_AMOUNT_SCALES.invest;

  describe('detents', () => {
    it('lays a uniform grid from the floor to the cap, both ends included', () => {
      expect(detentCount(income)).toBe(119);          // $500 to $30,000 by $250
      expect(detentValue(income, 0)).toBe(500);
      expect(detentValue(income, 1)).toBe(750);
      expect(detentValue(income, 118)).toBe(30_000);
      expect(detentCount(invest)).toBe(101);          // $0 to $10,000 by $100
      expect(detentValue(invest, 0)).toBe(0);
      expect(detentValue(invest, 100)).toBe(10_000);
    });

    it('clamps an index off either end of the grid', () => {
      expect(detentValue(income, -3)).toBe(500);
      expect(detentValue(income, 500)).toBe(30_000);
    });

    it('finds the nearest detent, ties going up, and clamps the value first', () => {
      expect(nearestIndex(income, 5000)).toBe(18);
      expect(nearestIndex(income, 5100)).toBe(18);    // $5,100 is nearer $5,000 than $5,250
      expect(nearestIndex(income, 5125)).toBe(19);    // exactly between: up
      expect(nearestIndex(income, 99_999)).toBe(118);
      expect(nearestIndex(income, 0)).toBe(0);
    });
  });

  describe('positions', () => {
    it('interpolates an off-grid value and pins out-of-range ones to the ends', () => {
      expect(positionOf(invest, 0)).toBe(0);
      expect(positionOf(invest, 5000)).toBe(0.5);
      expect(positionOf(invest, 10_000)).toBe(1);
      expect(positionOf(invest, 4333)).toBeCloseTo(0.4333, 6);
      expect(positionOf(income, 0)).toBe(0);          // unset income draws at the left end
      expect(positionOf(income, 99_999)).toBe(1);
    });

    it('clamps onto the scale', () => {
      expect(clampToScale(income, 0)).toBe(500);
      expect(clampToScale(income, 99_999)).toBe(30_000);
      expect(clampToScale(income, 7250)).toBe(7250);
    });
  });

  describe('hysteresis commit', () => {
    it('keeps the current detent while the finger rests short of the threshold', () => {
      expect(COMMIT_FRACTION).toBe(0.65);
      expect(commitIndex(invest, 10.5, 10)).toBe(10);
      expect(commitIndex(invest, 10.64, 10)).toBe(10);
      expect(commitIndex(invest, 9.4, 10)).toBe(10);
    });

    it('adopts the next detent once the finger is 65% of the way there, in either direction', () => {
      expect(commitIndex(invest, 10.66, 10)).toBe(11);
      expect(commitIndex(invest, 9.34, 10)).toBe(9);
    });

    it('jumps several detents on a fast move, landing on the one the finger is nearest past the threshold', () => {
      expect(commitIndex(invest, 14.2, 10)).toBe(14);
      expect(commitIndex(invest, 14.7, 10)).toBe(15);
      expect(commitIndex(invest, 5.3, 10)).toBe(5);
      expect(commitIndex(invest, 4.8, 10)).toBe(5);
    });

    it('never leaves the grid', () => {
      expect(commitIndex(invest, -3, 0)).toBe(0);
      expect(commitIndex(invest, 400, 99)).toBe(100);
    });
  });

  describe('nudge', () => {
    it('moves one step from a value on the grid, and clamps at the ends', () => {
      expect(nudge(income, 5000, 1)).toBe(5250);
      expect(nudge(income, 5000, -1)).toBe(4750);
      expect(nudge(income, 30_000, 1)).toBe(30_000);
      expect(nudge(income, 500, -1)).toBe(500);
      expect(nudge(invest, 0, -1)).toBe(0);
    });

    it('rounds an off-grid value onto the grid in the direction of the tap', () => {
      expect(nudge(income, 4333, 1)).toBe(4500);
      expect(nudge(income, 4333, -1)).toBe(4250);
      expect(nudge(invest, 433, 1)).toBe(500);
      expect(nudge(invest, 433, -1)).toBe(400);
    });

    it('lands a value off the scale on the nearer end first, whichever way it was tapped', () => {
      expect(nudge(income, 99_999, -1)).toBe(30_000);
      expect(nudge(income, 99_999, 1)).toBe(30_000);
      expect(nudge(income, 0, 1)).toBe(500);         // unset income: the first plus lands on the floor
    });

    it('takes several steps at once for a held button, still clamped and still on the grid', () => {
      expect(nudge(invest, 1000, 1, 5)).toBe(1500);
      expect(nudge(invest, 1000, -1, 25)).toBe(0);
      expect(nudge(invest, 9800, 1, 5)).toBe(10_000);
      expect(nudge(income, 4333, 1, 5)).toBe(5500);  // onto the grid, then four more
      expect(nudge(income, 4333, -1, 5)).toBe(3250);
    });
  });
  // ───────────────────────────────────────────────────────────────────────────
  // Piecewise scales
  // ───────────────────────────────────────────────────────────────────────────

  /** The recurring-investment scale from the rollout spec: $5 to $500 by $5, then $50, $250, $500. */
  const recurring: PiecewiseScale = {
    min: 5, max: 10_000, fine: 5,
    segments: [{ to: 500, step: 5 }, { to: 2000, step: 50 }, { to: 5000, step: 250 }, { to: 10_000, step: 500 }],
  };
  /** A cap that is not round: withdrawable cash. */
  const cash: PiecewiseScale = { min: 5, max: 1234.56, fine: 5, segments: [{ to: 500, step: 5 }, { to: 1234.56, step: 25 }] };

  describe('piecewise detents', () => {
    it('tells the two kinds apart', () => {
      expect(isPiecewise(income)).toBeFalse();
      expect(isPiecewise(recurring)).toBeTrue();
    });

    it('lists the detents of a uniform scale too, floor to cap', () => {
      const list = detents(invest);
      expect(list.length).toBe(101);
      expect(list[0]).toBe(0);
      expect(list[1]).toBe(100);
      expect(list[100]).toBe(10_000);
    });

    it('walks each segment at its step, from the floor to the cap, both ends included', () => {
      const list = detents(recurring);
      expect(list.length).toBe(100 + 30 + 12 + 10);     // 5..500 (100), ..2000 (30), ..5000 (12), ..10,000 (10)
      expect(detentCount(recurring)).toBe(152);
      expect(list[0]).toBe(5);
      expect(list[1]).toBe(10);
      expect(list[99]).toBe(500);
      expect(list[100]).toBe(550);
      expect(list[129]).toBe(2000);
      expect(list[130]).toBe(2250);
      expect(list[141]).toBe(5000);
      expect(list[142]).toBe(5500);
      expect(list[151]).toBe(10_000);
      expect(detentValue(recurring, 100)).toBe(550);
      expect(detentValue(recurring, -4)).toBe(5);
      expect(detentValue(recurring, 999)).toBe(10_000);
    });

    it('sits detents on multiples of the step, so an odd floor does not skew the grid', () => {
      const odd: PiecewiseScale = { min: 5, max: 100, segments: [{ to: 100, step: 25 }] };
      expect(detents(odd)).toEqual([5, 25, 50, 75, 100]);
    });

    it('makes the last detent exactly the cap, round or not, and every breakpoint a detent', () => {
      const list = detents(cash);
      expect(list[list.length - 1]).toBe(1234.56);
      expect(list[list.length - 2]).toBe(1225);
      expect(list).toContain(500);
      expect(breakpoints(cash)).toEqual([500]);
      expect(breakpoints(recurring)).toEqual([500, 2000, 5000]);
      expect(breakpoints(income)).toEqual([]);
    });

    it('hands out the same list for the same scale object', () => {
      expect(detents(recurring)).toBe(detents(recurring));
    });

    it('reads the step in force at a value: the segment it is in, a breakpoint belonging to the one above', () => {
      expect(stepAt(recurring, 5)).toBe(5);
      expect(stepAt(recurring, 499)).toBe(5);
      expect(stepAt(recurring, 500)).toBe(50);
      expect(stepAt(recurring, 2000)).toBe(250);
      expect(stepAt(recurring, 10_000)).toBe(500);
      expect(stepAt(recurring, 99_999)).toBe(500);
      expect(stepAt(income, 7000)).toBe(250);
    });

    it('positions a value in index space: every detent the same distance apart, dollars be damned', () => {
      expect(positionOf(recurring, 5)).toBe(0);
      expect(positionOf(recurring, 500)).toBeCloseTo(99 / 151, 9);      // the first breakpoint, two thirds along
      expect(positionOf(recurring, 525)).toBeCloseTo(99.5 / 151, 9);    // half-way to the next detent
      expect(positionOf(recurring, 10_000)).toBe(1);
      expect(positionOf(recurring, 0)).toBe(0);                         // pinned to the ends
      expect(positionOf(recurring, 50_000)).toBe(1);
    });

    it('finds the nearest detent across a breakpoint, ties going up', () => {
      expect(nearestIndex(recurring, 500)).toBe(99);
      expect(nearestIndex(recurring, 524)).toBe(99);
      expect(nearestIndex(recurring, 525)).toBe(100);
      expect(nearestIndex(recurring, 1999)).toBe(129);
      expect(nearestIndex(recurring, 99_999)).toBe(151);
    });

    it('commits by index on a piecewise scale too', () => {
      expect(commitIndex(recurring, 99.6, 99)).toBe(99);
      expect(commitIndex(recurring, 99.7, 99)).toBe(100);
      expect(commitIndex(recurring, 400, 150)).toBe(151);
    });
  });

  describe('nudge with a fine step', () => {
    it('walks the fine grid from the floor, whatever the slider step is where the value sits', () => {
      expect(nudge(recurring, 2000, 1)).toBe(2005);
      expect(nudge(recurring, 2000, -1)).toBe(1995);
      expect(nudge(recurring, 5, -1)).toBe(5);
      expect(nudge(recurring, 10_000, 1)).toBe(10_000);
    });

    it('rounds an off-grid value onto the fine grid in the tap direction', () => {
      expect(nudge(recurring, 2003, 1)).toBe(2005);
      expect(nudge(recurring, 2003, -1)).toBe(2000);
    });

    it('reaches a cap that is not on the grid, and steps off it back onto the grid', () => {
      expect(nudge(cash, 1230, 1)).toBe(1234.56);
      expect(nudge(cash, 1234.56, 1)).toBe(1234.56);
      expect(nudge(cash, 1234.56, -1)).toBe(1230);
    });

    it('takes several fine steps for a held button, clamped', () => {
      expect(nudge(recurring, 100, 1, 5)).toBe(125);
      expect(nudge(recurring, 9990, 1, 25)).toBe(10_000);
      expect(nudge(recurring, 2003, -1, 5)).toBe(1980);
    });

    it('without a fine step, a tap on a piecewise scale moves to the neighbouring detent', () => {
      const coarse: PiecewiseScale = { ...recurring, fine: undefined };
      expect(nudge(coarse, 500, 1)).toBe(550);
      expect(nudge(coarse, 500, -1)).toBe(495);
      expect(nudge(coarse, 2100, 1)).toBe(2250);
      expect(nudge(coarse, 2100, -1)).toBe(2000);
      expect(nudge(coarse, 495, 1, 5)).toBe(700);
    });
  });

  // ───────────────────────────────────────────────────────────────────────────
  // buildAmountScale
  // ───────────────────────────────────────────────────────────────────────────

  describe('buildAmountScale', () => {
    const inBand = (scale: PiecewiseScale) => {
      const n = detentCount(scale);
      return n >= DETENTS_MIN && n <= DETENTS_MAX;
    };

    it('builds round segments whose step grows with the amount, 80 to 160 detents across', () => {
      const scale = buildAmountScale(0, 20_000, 25);
      expect(scale.min).toBe(0);
      expect(scale.max).toBe(20_000);
      expect(scale.fine).toBe(25);
      expect(scale.segments).toEqual([
        { to: 2500, step: 100 }, { to: 5000, step: 100 }, { to: 10_000, step: 250 }, { to: 20_000, step: 250 },
      ]);
      expect(detentCount(scale)).toBe(111);
      expect(inBand(scale)).toBeTrue();
    });

    it('makes the last detent exactly the cap when the cap is not round (withdrawable cash)', () => {
      const scale = buildAmountScale(5, 1234.56, 5);
      const list = detents(scale);
      expect(list[0]).toBe(5);
      expect(list[list.length - 1]).toBe(1234.56);
      expect(scale.segments.map(s => s.to)).toEqual([100, 250, 500, 1234.56]);
      expect(scale.segments.map(s => s.step)).toEqual([5, 5, 10, 25]);
      expect(inBand(scale)).toBeTrue();
    });

    it('lands every breakpoint on a round number from the ladder and never lets the step shrink', () => {
      for (const [min, max, fine] of [[0, 50_000, 100], [0, 5_000_000, 1000], [25, 100_000, 25], [5, 10_000, 5], [0, 750, 25]]) {
        const scale = buildAmountScale(min, max, fine);
        const inner = scale.segments.slice(0, -1);
        for (const seg of inner) expect(STEP_LADDER.concat([250_000, 500_000, 1_000_000, 2_500_000])).toContain(seg.to);
        for (let i = 1; i < scale.segments.length; i++) expect(scale.segments[i].step).toBeGreaterThanOrEqual(scale.segments[i - 1].step);
        for (const seg of scale.segments) expect(STEP_LADDER).toContain(seg.step);
        expect(scale.segments[scale.segments.length - 1].to).toBe(max);
        expect(inBand(scale)).withContext(`${min}..${max}`).toBeTrue();
      }
    });

    it('stays in the band for every span of $200 or more up to $5M, from the floors the app uses', () => {
      for (const min of [0, 5, 25]) {
        for (let max = min + 200; max <= min + 2000; max += 7) expect(inBand(buildAmountScale(min, max, 5))).withContext(`${min}..${max}`).toBeTrue();
        for (let max = min + 2000; max <= 5_000_000; max = Math.ceil(max * 1.05)) {
          const scale = buildAmountScale(min, max, 5);
          expect(inBand(scale)).withContext(`${min}..${max}`).toBeTrue();
          const list = detents(scale);
          expect(list[0]).toBe(min);
          expect(list[list.length - 1]).toBe(max);
        }
      }
    });

    it('copes with a floor that sits high: the ends are measured from the floor instead', () => {
      const scale = buildAmountScale(100, 300, 5);
      expect(scale.segments.length).toBeGreaterThan(1);
      expect(inBand(scale)).toBeTrue();
      expect(detents(scale)[0]).toBe(100);
    });

    it('comes as close as whole dollars allow on a small span', () => {
      expect(detentCount(buildAmountScale(0, 50, 25))).toBe(51);
      expect(detents(buildAmountScale(0, 100, 25)).length).toBe(101);
    });

    it('degrades to a single detent when the cap is not above the floor, and drops a fine step that is not positive', () => {
      const flat = buildAmountScale(100, 100, 5);
      expect(detents(flat)).toEqual([100]);
      expect(flat.max).toBe(100);
      expect(buildAmountScale(0, 1000, 0).fine).toBeUndefined();
    });
  });

  describe('capAbove', () => {
    it('keeps the cap while the value sits within it, and lifts it to the next round number above a value that has overshot', () => {
      expect(capAbove(10_000, 8_000)).toBe(10_000);
      expect(capAbove(10_000, 10_000)).toBe(10_000);
      expect(capAbove(10_000, 12_000)).toBe(25_000);
      expect(capAbove(47_800, 50_000)).toBe(100_000);
      expect(capAbove(6_000, 6_500)).toBe(10_000);
      expect(capAbove(50_000, 50_000.5)).toBe(100_000);
    });

    it('carries the ladder on upward past $10M, and leaves a cap alone for a value that is not a number', () => {
      expect(capAbove(1_000_000, 12_000_000)).toBe(25_000_000);
      expect(capAbove(1_000_000, 60_000_000)).toBe(100_000_000);
      expect(capAbove(10_000, NaN)).toBe(10_000);
      expect(capAbove(10_000, Infinity)).toBe(10_000);
    });

    it('a scale built on it holds the overshoot as a plain detent position, not pinned at the end', () => {
      const scale = buildAmountScale(0, capAbove(10_000, 12_000), 25);
      expect(scale.max).toBe(25_000);
      expect(positionOf(scale, 12_000)).toBeLessThan(1);
      expect(nudge(scale, 12_000, 1)).toBe(12_025);
    });
  });
});
