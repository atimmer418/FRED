import {
  SHEET_EXIT_SPRING,
  SHEET_SNAPBACK_SPRING,
  rubberband,
  sheetShouldDismiss,
  springKeyframes,
} from './sheet-spring.utils';

describe('sheet-spring.utils', () => {
  describe('rubberband', () => {
    it('gives less than the raw overshoot (resistance)', () => {
      expect(rubberband(100, 500)).toBeLessThan(100);
      expect(rubberband(100, 500)).toBeGreaterThan(0);
    });

    it('is monotonically increasing', () => {
      expect(rubberband(50, 500)).toBeLessThan(rubberband(100, 500));
      expect(rubberband(100, 500)).toBeLessThan(rubberband(200, 500));
    });

    it('returns 0 for 0', () => {
      expect(rubberband(0, 500)).toBe(0);
    });

    it('never exceeds the dimension asymptote', () => {
      // f(x) = (x*d*c)/(d + c*x) -> d as x -> infinity
      expect(rubberband(1e9, 500)).toBeLessThan(500);
    });
  });

  describe('sheetShouldDismiss', () => {
    const height = 500;

    it('dismisses when dragged past half with no velocity', () => {
      expect(sheetShouldDismiss(300, 0, height)).toBeTrue();
    });

    it('stays when dragged a little with no velocity', () => {
      expect(sheetShouldDismiss(100, 0, height)).toBeFalse();
    });

    it('dismisses on a fast downward flick from near the top', () => {
      // 30px dragged but flicked at 1.2 px/ms: 30 + 420 = 450 -> 0.9 > 0.5
      expect(sheetShouldDismiss(30, 1.2, height)).toBeTrue();
    });

    it('stays on an upward flick even when dragged far', () => {
      // 280px dragged but flicking back up at 1 px/ms: 280 - 350 < 0
      expect(sheetShouldDismiss(280, -1, height)).toBeFalse();
    });
  });

  describe('springKeyframes', () => {
    it('starts at from and ends at to', () => {
      const { positions, durationMs } = springKeyframes(
        { from: 200, to: 0, velocityPxMs: -0.5 },
        SHEET_SNAPBACK_SPRING,
      );
      expect(positions[0]).toBeCloseTo(200, 0);
      expect(positions[positions.length - 1]).toBeCloseTo(0, 0);
      expect(durationMs).toBeGreaterThanOrEqual(120);
      expect(durationMs).toBeLessThanOrEqual(700);
    });

    it('is monotone toward the target when critically damped from rest', () => {
      const { positions } = springKeyframes(
        { from: 0, to: 400, velocityPxMs: 0 },
        SHEET_EXIT_SPRING,
      );
      for (let i = 1; i < positions.length; i++) {
        expect(positions[i]).toBeGreaterThanOrEqual(positions[i - 1] - 0.01);
      }
    });

    it('overshoots the target when underdamped with incoming velocity', () => {
      const { positions } = springKeyframes(
        { from: 150, to: 0, velocityPxMs: -2.5 },
        { dampingRatio: 0.7, response: 0.4 },
      );
      const min = Math.min(...positions);
      expect(min).toBeLessThan(-1); // crossed past the target
    });

    it('settles faster with velocity already toward the target', () => {
      const slow = springKeyframes({ from: 400, to: 0, velocityPxMs: 0 }, SHEET_EXIT_SPRING);
      const fast = springKeyframes({ from: 400, to: 0, velocityPxMs: -3 }, SHEET_EXIT_SPRING);
      expect(fast.durationMs).toBeLessThanOrEqual(slow.durationMs);
    });

    it('handles a zero-distance release without degenerate output', () => {
      const { positions, durationMs } = springKeyframes(
        { from: 0, to: 0, velocityPxMs: 0 },
        SHEET_SNAPBACK_SPRING,
      );
      expect(positions.length).toBeGreaterThanOrEqual(2);
      expect(durationMs).toBeGreaterThanOrEqual(120);
    });
  });
});
