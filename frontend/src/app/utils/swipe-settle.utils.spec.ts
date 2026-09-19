import {
  DragSample,
  SETTLE_DEFAULT_MS,
  SETTLE_MAX_MS,
  SETTLE_MIN_MS,
  pruneSamples,
  releaseVelocity,
  settleDurationMs,
} from './swipe-settle.utils';

describe('swipe-settle.utils', () => {
  describe('pruneSamples', () => {
    it('drops samples older than the window', () => {
      const samples: DragSample[] = [
        { x: 0, t: 0 },
        { x: 10, t: 50 },
        { x: 20, t: 120 },
      ];
      // At now=160 with a 100ms window, t=0 and t=50 are stale; t=120 survives.
      expect(pruneSamples(samples, 160)).toEqual([{ x: 20, t: 120 }]);
    });

    it('keeps everything inside the window', () => {
      const samples: DragSample[] = [
        { x: 0, t: 100 },
        { x: 5, t: 150 },
      ];
      expect(pruneSamples(samples, 180)).toEqual(samples);
    });
  });

  describe('releaseVelocity', () => {
    it('returns the steady speed for constant-velocity motion', () => {
      const samples: DragSample[] = [
        { x: 0, t: 0 },
        { x: 15, t: 15 },
        { x: 30, t: 30 },
        { x: 45, t: 45 },
      ];
      // Every segment is 1 px/ms; any weighting must still return 1.
      expect(releaseVelocity(samples)).toBeCloseTo(1, 5);
    });

    it('weights the most recent movement above a flat window average', () => {
      const samples: DragSample[] = [
        { x: 100, t: 1000 }, // ─┐ slow segment: 1 px/ms
        { x: 130, t: 1030 }, // ─┘
        { x: 190, t: 1060 }, //   recent, fast segment: 2 px/ms
      ];
      // A flat average would be 90px/60ms = 1.5; recency-weighting leans toward
      // the faster final segment.
      const v = releaseVelocity(samples);
      expect(v).toBeGreaterThan(1.5);
      expect(v).toBeCloseTo(1.646, 2);
    });

    it('reads a slow-drag-then-flick as fast, not averaged-down', () => {
      const plainWindowAverage = 65 / 80; // 0.8125 px/ms across the whole window
      const samples: DragSample[] = [
        { x: 0, t: 0 },
        { x: 5, t: 50 },  // slow drag: 0.1 px/ms
        { x: 65, t: 80 }, // flick just before release: 2 px/ms
      ];
      expect(releaseVelocity(samples)).toBeGreaterThan(plainWindowAverage + 0.2);
    });

    it('is negative for leftward movement', () => {
      const samples: DragSample[] = [
        { x: 200, t: 0 },
        { x: 140, t: 40 },
      ];
      expect(releaseVelocity(samples)).toBeCloseTo(-1.5, 5);
    });

    it('returns 0 with fewer than two samples', () => {
      expect(releaseVelocity([])).toBe(0);
      expect(releaseVelocity([{ x: 10, t: 5 }])).toBe(0);
    });

    it('returns 0 when timestamps are degenerate', () => {
      const samples: DragSample[] = [
        { x: 0, t: 100 },
        { x: 50, t: 100 },
      ];
      expect(releaseVelocity(samples)).toBe(0);
    });
  });

  describe('settleDurationMs', () => {
    it('matches the curve initial slope to the release velocity', () => {
      // 3 * 150px / 2 px/ms = 225ms — inside the clamps.
      expect(settleDurationMs(150, 2)).toBe(225);
    });

    it('clamps fast flicks to the minimum', () => {
      // 3 * 100 / 6 = 50ms → clamped up.
      expect(settleDurationMs(100, 6)).toBe(SETTLE_MIN_MS);
    });

    it('clamps slow releases to the maximum', () => {
      // 3 * 300 / 0.5 = 1800ms → clamped down.
      expect(settleDurationMs(300, 0.5)).toBe(SETTLE_MAX_MS);
    });

    it('falls back to the default when velocity opposes the target', () => {
      expect(settleDurationMs(120, -1.5)).toBe(SETTLE_DEFAULT_MS);
    });

    it('falls back to the default when velocity is ~zero', () => {
      expect(settleDurationMs(120, 0)).toBe(SETTLE_DEFAULT_MS);
      expect(settleDurationMs(120, 0.005)).toBe(SETTLE_DEFAULT_MS);
    });
  });
});
