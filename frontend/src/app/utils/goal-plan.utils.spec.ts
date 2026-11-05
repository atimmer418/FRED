import {
  firstYearDue,
  friendlyRound,
  futureValue,
  horizonMonths,
  isKidNotYetBorn,
  kidAccountRateKind,
  kidDeadline,
  kidLumpSchedule,
  monthsBetween,
  planStatus,
  proposeFirstYearPlan,
  proposeKidAccountPlan,
  proposeSimplePlan,
  proposeVacationPlan,
  requiredMonthly,
  simpleTarget,
  vacationStatus,
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

describe('non-finite guards', () => {
  it('returns an empty schedule and zero horizon for NaN inputs', () => {
    expect(kidLumpSchedule(NaN, NOW, 2026, 12)).toEqual({ birthday: [], newYear: [] });
    expect(horizonMonths(NOW, NaN, 12)).toBe(0);
  });
  it('returns zero from the other math for NaN inputs', () => {
    expect(monthsBetween('garbage', '2027-01-01T00:00:00')).toBe(0);
    expect(requiredMonthly(0, NaN, 12, 0.1)).toBe(0);
    expect(futureValue(0, 100, NaN, 0.1, [])).toBe(0);
  });
});

describe('proposeKidAccountPlan', () => {
  const base = { target: 250000, balance: 0, birthYear: 2026, birthMonth: 11, marketRate: 0.10, rateKindOverride: null, now: NOW };
  it('pins the ritual example: $296 a month, $1,000 birthday, $500 New Year, FV $250,526', () => {
    const p = proposeKidAccountPlan(base);
    expect(p.n).toBe(216); expect(p.monthly).toBe(296); expect(p.birthdayLump).toBe(1000); expect(p.newYearLump).toBe(500);
    expect(Math.round(futureValue(0, p.monthly, p.n, p.rate, p.lumps))).toBe(250526);
  });
  it('pins milestones from the target', () => { const p = proposeKidAccountPlan(base); expect(p.milestones.at18).toBe(250000); expect(p.milestones.at22).toBeCloseTo(372338.52, 0); expect(p.milestones.at30).toBeCloseTo(825912.24, 0); });
  it('keeps lumps at or under 35% of the yearly plan across horizons', () => {
    for (const birthYear of [2010, 2011, 2018, 2026]) {
      const p = proposeKidAccountPlan({ ...base, birthYear });
      expect(p.ritualsReason).toBeNull();
      expect((p.birthdayLump + p.newYearLump) / (12 * p.monthly + p.birthdayLump + p.newYearLump)).toBeLessThanOrEqual(0.35);
    }
  });
  it('reaches at least the target with the proposed plan across the grid', () => {
    for (const birthYear of [2010, 2011, 2018, 2026]) {
      for (const balance of [0, 20000]) {
        const p = proposeKidAccountPlan({ ...base, birthYear, balance });
        if (p.n === 0) continue;
        expect(futureValue(balance, p.monthly, p.n, p.rate, p.lumps)).toBeGreaterThanOrEqual(250000);
      }
    }
  });
  it('drops rituals under 24 months with a reason', () => { const p = proposeKidAccountPlan({ ...base, birthYear: 2010, birthMonth: 1 }); expect(p.ritualsReason).toBe('short_horizon'); expect(p.birthdayLump).toBe(0); expect(p.newYearLump).toBe(0); });
  it('drops rituals for a small plan', () => expect(proposeKidAccountPlan({ ...base, target: 3000 }).ritualsReason).toBe('small_plan'));
  it('uses the cash rate under 36 months', () => expect(proposeKidAccountPlan({ ...base, birthYear: 2009, birthMonth: 6 }).rateKind).toBe('cash'));
  it('keeps a market plan at market when overridden', () => expect(proposeKidAccountPlan({ ...base, birthYear: 2009, birthMonth: 6, rateKindOverride: 'market' }).rateKind).toBe('market'));
  it('handles n = 0 as due now', () => { const p = proposeKidAccountPlan({ ...base, birthYear: 2008, birthMonth: 10 }); expect(p.n).toBe(0); expect(p.monthly).toBe(0); expect(p.dueNow).toBe(250000); });
  it('flags an undecided birth year', () => expect(proposeKidAccountPlan({ ...base, birthYear: null, birthMonth: null }).assumedBirthYear).toBeTrue());
});

describe('proposeSimplePlan', () => {
  const s = (targetYear: number, now: Date, extra = {}) => proposeSimplePlan({ target: 30000, balance: 0, targetYear, marketRate: 0.10, forceCash: false, rateKindOverride: null, now, ...extra });
  it('uses market at 36 months and cash at 35', () => { expect(s(2029, new Date(2026, 11, 15)).n).toBe(36); expect(s(2029, new Date(2026, 11, 15)).rateKind).toBe('market'); expect(s(2029, new Date(2027, 0, 15)).rateKind).toBe('cash'); });
  it('keeps the anchored kind until a re-plan', () => expect(s(2029, new Date(2027, 0, 15), { rateKindOverride: 'market' }).rateKind).toBe('market'));
  it('forces cash for emergency funds even when overridden to market', () => expect(s(2032, NOW, { forceCash: true, rateKindOverride: 'market' }).rateKind).toBe('cash'));
  it('splits from you and from growth', () => { const p = s(2036, NOW); expect(p.fromYou + p.fromGrowth).toBeGreaterThanOrEqual(30000); expect(p.fromGrowth).toBeGreaterThan(0); expect(p.fromYou).toBe(p.monthly * p.n); });
  it('is due now when the deadline has passed', () => { const p = s(2026, new Date(2026, 11, 15)); expect(p.n).toBe(0); expect(p.dueNow).toBe(30000); });
});

describe('simpleTarget', () => {
  it('applies the down payment', () => expect(simpleTarget('HOUSE', { price: 1000000, downPaymentPct: 20, targetYear: 2032 })).toBe(200000));
  it('uses the full price when null', () => expect(simpleTarget('CAR', { price: 40000, downPaymentPct: null, targetYear: 2029 })).toBe(40000));
  it('multiplies emergency months', () => expect(simpleTarget('EMERGENCY_FUND', { monthlyExpenses: 4000, months: 6, targetYear: 2028 })).toBe(24000));
});

describe('proposeVacationPlan', () => {
  it('is ceil of trips times budget over 12', () => expect(proposeVacationPlan({ tripsPerYear: 4, budgetPerTrip: 2500, savedThisYear: 0, savedYear: 2026 }, NOW).monthly).toBe(834));
  it('expects monthly times the months elapsed so far', () => expect(proposeVacationPlan({ tripsPerYear: 4, budgetPerTrip: 2500, savedThisYear: 0, savedYear: 2026 }, NOW).expectedSoFar).toBe(8340));
  it('resets saved so far in a new year', () => { const p = proposeVacationPlan({ tripsPerYear: 1, budgetPerTrip: 1200, savedThisYear: 900, savedYear: 2025 }, NOW); expect(p.resetNeeded).toBeTrue(); expect(p.savedThisYear).toBe(0); });
});

describe('planStatus', () => {
  const A = { completed: false, imported: false, target: 250000, balance: 0, anchorBalance: 0, anchorMonthly: 417, planAnchoredAt: '2026-11-15T10:00:00', balanceUpdatedAt: null as string | null, rate: 0.10, lumps: [], now: new Date(2027, 10, 20) };
  it('is started until a balance is logged', () => expect(planStatus(A).kind).toBe('started'));
  it('is imported for a never-planned import', () => expect(planStatus({ ...A, imported: true }).kind).toBe('imported'));
  it('is on plan at the anchor date with the anchor balance', () => expect(planStatus({ ...A, balanceUpdatedAt: A.planAnchoredAt }).kind).toBe('on_plan'));
  it('is behind when the logged balance trails the plan', () => { const r = planStatus({ ...A, balanceUpdatedAt: '2027-11-15T10:00:00' }); expect(r.kind).toBe('behind'); expect(r.delta).toBeLessThan(-5000); });
  it('uses one symmetric tolerance', () => {
    // rate 0, 100 a month, 12 months: expected 1200; tolerance = max(50, 1% of 10000) = 100
    const B = { ...A, target: 10000, anchorMonthly: 100, rate: 0, balanceUpdatedAt: '2027-11-15T10:00:00' };
    expect(planStatus({ ...B, balance: 1300 }).kind).toBe('on_plan');
    expect(planStatus({ ...B, balance: 1301 }).kind).toBe('ahead');
    expect(planStatus({ ...B, balance: 1100 }).kind).toBe('on_plan');
    expect(planStatus({ ...B, balance: 1099 }).kind).toBe('behind');
  });
  it('is done at the target', () => expect(planStatus({ ...A, balance: 250000 }).kind).toBe('done'));
  it('is done when completed, ahead of imported', () => expect(planStatus({ ...A, completed: true, imported: true }).kind).toBe('done'));
  it('flags a stale balance after 60 days', () => expect(planStatus({ ...A, balanceUpdatedAt: '2027-08-01T10:00:00' }).stale).toBeTrue());
  it('is not stale when recent', () => expect(planStatus({ ...A, balanceUpdatedAt: '2027-11-10T10:00:00' }).stale).toBeFalse());
});

describe('vacationStatus', () => {
  it('never reads done', () => expect(vacationStatus({ annual: 10000, monthly: 834, savedThisYear: 10000, expectedSoFar: 8340, resetNeeded: false }, NOW).kind).toBe('ahead'));
  it('uses annual as the tolerance base (100 on 10000), symmetric', () => {
    const v = (saved: number) => vacationStatus({ annual: 10000, monthly: 834, savedThisYear: saved, expectedSoFar: 8340, resetNeeded: false }, NOW).kind;
    expect(v(8340 + 100)).toBe('on_plan');
    expect(v(8340 + 101)).toBe('ahead');
    expect(v(8340 - 100)).toBe('on_plan');
    expect(v(8340 - 101)).toBe('behind');
  });
  it('is started at the top of the year', () => expect(vacationStatus({ annual: 10000, monthly: 834, savedThisYear: 0, expectedSoFar: 0, resetNeeded: false }, new Date(2027, 0, 5)).kind).toBe('started'));
});

describe('isKidNotYetBorn', () => {
  it('is true for undecided', () => expect(isKidNotYetBorn(null, null, NOW)).toBeTrue());
  it('is false for a birth month that has started', () => expect(isKidNotYetBorn(2026, 11, NOW)).toBeFalse());
  it('is true from next month on', () => expect(isKidNotYetBorn(2026, 12, NOW)).toBeTrue());
  it('assumes July when the month is unknown', () => { expect(isKidNotYetBorn(2026, null, NOW)).toBeFalse(); expect(isKidNotYetBorn(2027, null, NOW)).toBeTrue(); });
});

describe('firstYearDue', () => {
  it('uses the birth month when decided', () => expect(firstYearDue(2028, 11, NOW)).toEqual({ year: 2028, month: 11 }));
  it('assumes July for an unknown month', () => expect(firstYearDue(2028, null, NOW)).toEqual({ year: 2028, month: 7 }));
  it('is 36 months after the creation month when undecided', () => expect(firstYearDue(null, null, NOW)).toEqual({ year: 2029, month: 11 }));
});

describe('kidAccountRateKind', () => {
  it('is market at 36 months and cash at 35', () => {
    expect(kidAccountRateKind(NOW, 2029, 11)).toBe('market');
    expect(kidAccountRateKind(NOW, 2029, 10)).toBe('cash');
  });
});

describe('proposeFirstYearPlan', () => {
  const p = (target: number, due: { year: number; month: number }, rateKind: 'market' | 'cash', undecided = false, now = NOW) =>
    proposeFirstYearPlan({ target, balance: 0, due, undecided, marketRate: 0.10, rateKind, now });
  it('pins rank 1 undecided: 36 months invested, $616', () => { const r = p(25700, { year: 2029, month: 11 }, 'market', true); expect(r.n).toBe(36); expect(r.monthly).toBe(616); });
  it('pins rank 2 undecided at $498', () => expect(p(20800, { year: 2029, month: 11 }, 'market', true).monthly).toBe(498));
  it('pins rank 1 planned for Nov 2028 in cash at $1,031', () => { const r = p(25700, { year: 2028, month: 11 }, 'cash'); expect(r.n).toBe(24); expect(r.monthly).toBe(1031); });
  it('pins rank 2 planned for Nov 2028 at $834 and Nov 2027 at $1,702', () => {
    expect(p(20800, { year: 2028, month: 11 }, 'cash').monthly).toBe(834);
    expect(p(20800, { year: 2027, month: 11 }, 'cash').monthly).toBe(1702);
  });
  it('asks for the whole target when the kid is due next month', () => { const r = p(20800, { year: 2026, month: 12 }, 'cash'); expect(r.n).toBe(1); expect(r.monthly).toBe(20800); });
  it('counts down from the due date', () => expect(p(25700, { year: 2029, month: 11 }, 'market', true, new Date(2027, 4, 15)).n).toBe(30));
  it('is finished at the due month with no monthly', () => { const r = p(20800, { year: 2026, month: 11 }, 'cash'); expect(r.finished).toBeTrue(); expect(r.monthly).toBe(0); });
  it('uses the cash rate for cash and the market rate for market', () => {
    expect(p(20800, { year: 2028, month: 11 }, 'cash').rate).toBe(0.04);
    expect(p(20800, { year: 2029, month: 11 }, 'market').rate).toBe(0.10);
  });
  it('pins futureValue of the Nov 2028 monthly after 23 months', () => expect(futureValue(0, 834, 23, 0.04, [])).toBeCloseTo(19902, -1));
});

describe('planStatus extraExpected', () => {
  it('adds to the expected balance, lowering the delta by exactly that amount', () => {
    const args = { completed: false, imported: false, target: 250000, balance: 3000, anchorBalance: 0, anchorMonthly: 417, planAnchoredAt: '2026-11-15T10:00:00', balanceUpdatedAt: '2027-05-15T10:00:00' as string | null, rate: 0.10, lumps: [], now: new Date(2027, 10, 20) };
    const without = planStatus(args);
    const withExtra = planStatus({ ...args, extraExpected: 5000 });
    expect(without.delta - withExtra.delta).toBeCloseTo(5000, 6);
  });
});
