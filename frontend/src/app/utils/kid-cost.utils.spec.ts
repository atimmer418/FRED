import { KID_COST_SOURCES, USDA_2015_ANNUAL_BY_BAND, USDA_2015_TOTAL } from '../constants/kid-costs';
import {
  collegePresets,
  costCardSources,
  familySizeFactor,
  firstYearTargetForRank,
  inflationFactor,
  kidCostFigures,
  kidRanks,
} from './kid-cost.utils';

describe('kid cost constants', () => {
  it('six bands of three years sum to the USDA total', () =>
    expect(USDA_2015_ANNUAL_BY_BAND.reduce((s, b) => s + 3 * b.annual, 0)).toBe(USDA_2015_TOTAL));
  it('pins the inflation factor', () => expect(inflationFactor()).toBeCloseTo(1.421586, 6));
});

describe('familySizeFactor', () => {
  it('follows the USDA table', () => {
    expect(familySizeFactor(1)).toBe(1.27);
    expect(familySizeFactor(2)).toBe(1);
    expect(familySizeFactor(3)).toBe(0.76);
    expect(familySizeFactor(7)).toBe(0.76);
    expect(familySizeFactor(0)).toBe(1.27);
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
  it('names the family size', () =>
    expect([1, 2, 3, 5].map(n => kidCostFigures(n).sizeWording)).toEqual(['only', 'two', 'three_plus', 'three_plus']));
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
  it('goes by birth order', () =>
    expect([1, 2, 3, 4].map(firstYearTargetForRank)).toEqual([25700, 20800, 16500, 16500]));
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
