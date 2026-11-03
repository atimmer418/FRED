import {
  friendlyRound,
  futureValue,
  horizonMonths,
  kidDeadline,
  kidLumpSchedule,
  monthsBetween,
  requiredMonthly,
} from './goal-plan.utils';

const NOW = new Date(2026, 10, 15);
const JAN = new Date(2027, 0, 15);

describe('horizonMonths', () => {
  it('counts whole months from next month through the deadline month', () => expect(horizonMonths(NOW, 2044, 11)).toBe(216));
  it('is 217 to December 2044 and 215 when viewed in January 2027', () => { expect(horizonMonths(NOW, 2044, 12)).toBe(217); expect(horizonMonths(JAN, 2044, 12)).toBe(215); });
  it('floors at zero when the deadline has passed', () => expect(horizonMonths(NOW, 2026, 10)).toBe(0));
  it('is 1 for next month', () => expect(horizonMonths(NOW, 2026, 12)).toBe(1));
});

describe('monthsBetween', () => {
  it('matches horizonMonths counting', () => expect(monthsBetween('2026-11-15T10:00:00', '2027-11-01T00:00:00')).toBe(12));
  it('floors at zero', () => expect(monthsBetween('2027-01-01T00:00:00', '2026-06-01T00:00:00')).toBe(0));
});

describe('requiredMonthly', () => {
  it('pins $250K over 216 months at 10% as $417', () => expect(requiredMonthly(0, 250000, 216, 0.10)).toBe(417));
  it('pins 217 months as $413', () => expect(requiredMonthly(0, 250000, 217, 0.10)).toBe(413));
  it('is linear at zero rate', () => expect(requiredMonthly(0, 1200, 12, 0)).toBe(100));
  it('returns 0 when n is 0', () => expect(requiredMonthly(0, 1000, 0, 0.10)).toBe(0));
  it('returns 0 when the balance already covers the target', () => expect(requiredMonthly(5000, 4000, 12, 0.10)).toBe(0));
});

describe('futureValue', () => {
  it('round-trips the unrounded payment to within a dollar', () => {
    const m = 250000 / (((1 + 0.10 / 12) ** 216 - 1) / (0.10 / 12));
    expect(Math.abs(futureValue(0, m, 216, 0.10, []) - 250000)).toBeLessThan(1);
  });
  it('reaches at least the target with the rounded monthly', () => expect(futureValue(0, 417, 216, 0.10, [])).toBeGreaterThanOrEqual(250000));
  it('posts a lump at its index after growth', () => expect(futureValue(0, 0, 2, 0.12, [{ monthIndex: 1, amount: 100 }])).toBeCloseTo(101, 2));
});

describe('friendlyRound', () => {
  it('rounds by band', () => { expect(friendlyRound(999)).toBe(1000); expect(friendlyRound(480)).toBe(500); expect(friendlyRound(2130)).toBe(2250); expect(friendlyRound(20)).toBe(0); });
});

describe('kidLumpSchedule', () => {
  it('starts birthdays the year after birth and never in the deadline month', () => {
    const s = kidLumpSchedule(217, NOW, 2026, 12);   // born Dec 2026, 18 in Dec 2044 = index 217
    expect(s.birthday[0]).toBe(13);                  // Dec 2027
    expect(s.birthday).not.toContain(217);
    expect(s.birthday.length).toBe(17);
  });
  it('shifts correctly when viewed in January', () => { const s = kidLumpSchedule(215, JAN, 2026, 12); expect(s.birthday[0]).toBe(11); expect(s.birthday).not.toContain(1); });
  it('places an unknown birth month in July', () => expect(kidLumpSchedule(212, NOW, 2026, null).birthday[0]).toBe(8));
  it('has no birthday lumps before a future birth year', () => expect(kidLumpSchedule(60, NOW, 2030, 5).birthday[0]).toBe(horizonMonths(NOW, 2031, 5)));
  it('is empty when the only birthday would be the deadline month', () => expect(kidLumpSchedule(13, NOW, 2026, 12).birthday).toEqual([]));
  it('lists every January', () => expect(kidLumpSchedule(24, NOW, 2026, 7).newYear).toEqual([2, 14]));
});

describe('kidDeadline', () => {
  it('assumes born this year when undecided', () => expect(kidDeadline(NOW, null, null)).toEqual({ year: 2044, month: 7, assumedBirthYear: true }));
});
