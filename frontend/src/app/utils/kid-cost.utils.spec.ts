import { KID_COST_SOURCES, USDA_2015_ANNUAL_BY_BAND, USDA_2015_TOTAL } from '../constants/kid-costs';
import {
  collegePresets,
  costCardSources,
  familyMonthly,
  familySizeFactor,
  firstYearFactor,
  firstYearTargetForRank,
  inflationFactor,
  kidCostFigures,
  kidRanks,
  perKidMonthly,
} from './kid-cost.utils';

describe('kid cost constants', () => {
  it('six bands of three years sum to the USDA total', () =>
    expect(USDA_2015_ANNUAL_BY_BAND.reduce((s, b) => s + 3 * b.annual, 0)).toBe(USDA_2015_TOTAL));
  it('pins the inflation factor', () => expect(inflationFactor()).toBeCloseTo(1.421586, 6));
});

describe('familySizeFactor', () => {
  it('follows the USDA table for 1 to 3 and the FRED estimate for 4 and 5', () => {
    expect(familySizeFactor(1)).toBe(1.27);
    expect(familySizeFactor(2)).toBe(1);
    expect(familySizeFactor(3)).toBe(0.76);
    expect(familySizeFactor(4)).toBe(0.64);
    expect(familySizeFactor(5)).toBe(0.568);
    expect(familySizeFactor(6)).toBe(0.52);
    expect(familySizeFactor(7)).toBe(0.52);
    expect(familySizeFactor(0)).toBe(1.27);
  });
  it('extends by the third-child marginal of 0.28', () => {
    expect(4 * familySizeFactor(4) - 3 * familySizeFactor(3)).toBeCloseTo(0.28, 10);
    expect(5 * familySizeFactor(5) - 4 * familySizeFactor(4)).toBeCloseTo(0.28, 10);
    expect(6 * familySizeFactor(6) - 5 * familySizeFactor(5)).toBeCloseTo(0.28, 10);
  });
});

describe('firstYearFactor', () => {
  it('is 1.27, 1.00, then 0.76 for every later rank', () =>
    expect([1, 2, 3, 4, 5].map(firstYearFactor)).toEqual([1.27, 1, 0.76, 0.76, 0.76]));
});

describe('perKidMonthly and familyMonthly', () => {
  it('familyMonthly(3) is 3510 and perKidMonthly(2) is 1540', () => {
    expect(familyMonthly(3)).toBe(3510);
    expect(perKidMonthly(2)).toBe(1540);
  });
  it('pins every family size to the nearest $10', () => {
    expect([1, 2, 3, 4, 5].map(perKidMonthly)).toEqual([1950, 1540, 1170, 980, 870]);
    expect([1, 2, 3, 4, 5].map(familyMonthly)).toEqual([1950, 3070, 3510, 3940, 4370]);
  });
});

describe('kidCostFigures', () => {
  it('pins per-child and family totals from unrounded inputs', () => {
    expect(kidCostFigures(1).perChildTotal).toBe(421763);
    expect(kidCostFigures(1).familyTotal).toBe(421763);
    expect(kidCostFigures(2).perChildTotal).toBe(332097);
    expect(kidCostFigures(2).familyTotal).toBe(664193);
    expect(kidCostFigures(3).perChildTotal).toBe(252393);
    expect(kidCostFigures(3).familyTotal).toBe(757180);
  });
  it('per-kid totals for 1..6 kids are 421763, 332097, 252393, 212542, 188631, 172690 within $1', () => {
    const pins = [421763, 332097, 252393, 212542, 188631, 172690];
    [1, 2, 3, 4, 5, 6].forEach((n, i) =>
      expect(Math.abs(kidCostFigures(n).perChildTotal - pins[i])).withContext(`${n} kids`).toBeLessThanOrEqual(1));
  });
  it('names the family size', () =>
    expect([1, 2, 3, 4, 5, 6].map(n => kidCostFigures(n).sizeWording))
      .toEqual(['only', 'two', 'three_plus', 'four', 'five', 'six']));
  it('pins the four stages for two kids', () => {
    const s = kidCostFigures(2).stages;
    expect(s.map(x => x.key)).toEqual(['0-2', '3-5', '6-11', '12-17']);
    expect(s.map(x => Math.round(x.total))).toEqual([54077, 54290, 108879, 114850]);
    expect(Math.round(s[2].annual)).toBe(18147);
    expect(Math.round(s[3].annual)).toBe(19142);
    expect(Math.round(s[0].monthly)).toBe(1502);
  });
  it('stage totals sum to the per-child total within two dollars', () => {
    const f = kidCostFigures(3);
    expect(Math.abs(f.stages.reduce((a, s) => a + s.total, 0) - f.perChildTotal)).toBeLessThanOrEqual(2);
  });
  it('carries the birth figure and the as-of label', () => {
    const f = kidCostFigures(2);
    expect(f.childbirthOutOfPocket).toBe(2743);
    expect(f.asOfLabel).toBe('September 2026');
  });
});

describe('firstYearTargetForRank', () => {
  it('firstYearTargetForRank keeps 25700, 20800, 16500 and 16500 for rank 5', () =>
    expect([1, 2, 3, 4, 5].map(firstYearTargetForRank)).toEqual([25700, 20800, 16500, 16500, 16500]));
});

describe('kidRanks', () => {
  it('orders by birth month with undecided last', () =>
    expect(
      kidRanks([
        { birthYear: null, birthMonth: null },
        { birthYear: 2029, birthMonth: 3 },
        { birthYear: 2024, birthMonth: null },
      ])
    ).toEqual([3, 2, 1]));
  it('keeps list order for two undecided kids', () =>
    expect(
      kidRanks([
        { birthYear: null, birthMonth: null },
        { birthYear: null, birthMonth: null },
      ])
    ).toEqual([1, 2]));
});

describe('collegePresets and sources', () => {
  it('is four years at the College Board prices', () =>
    expect(collegePresets().map(p => p.amount)).toEqual([47800, 123960, 180000]));
  it('keeps the College Board off the cost card', () => {
    expect(costCardSources().length).toBe(3);
    expect(costCardSources().some(s => s.name.startsWith('College Board'))).toBeFalse();
  });
  it('has no em dash and every source has a year and an https url', () => {
    expect(JSON.stringify(KID_COST_SOURCES)).not.toMatch(/\u2014/);
    KID_COST_SOURCES.forEach(s => {
      expect(s.year.length).toBeGreaterThan(0);
      expect(s.url.startsWith('https://')).toBeTrue();
    });
  });
});
