import { AccountState, Goal, KidInput, KidsConfig } from '../../models/goal.model';
import {
  GoalForm, MAX_KIDS, defaultFirstYearFund, defaultForm, formFromGoal, formToConfig, kidsQuizErrors, planInputsChanged,
  validateForm,
} from './goal-form.model';

const NOW = new Date(2026, 5, 15);

function goalOf(partial: Partial<Goal> & Pick<Goal, 'type' | 'config'>): Goal {
  return {
    id: 1, clientId: null, name: 'G', icon: 'flag', currentBalance: 0, balanceUpdatedAt: null,
    anchorBalance: 0, anchorMonthly: 0, planRate: 0.07, planRateKind: 'market',
    planAnchoredAt: '2026-01-01', completed: false, sortOrder: 0, createdAt: '', updatedAt: '',
    ...partial,
  };
}

function kidsGoal(): Goal {
  const acct = (balance: number) => ({ balance, balanceUpdatedAt: '2026-05-01', anchorBalance: balance, anchorMonthly: 50 });
  const config: KidsConfig = {
    brokerageTarget: 20_000,
    plan529Target: 40_000,
    kids: [
      { label: 'Ava', birthYear: 2022, birthMonth: 3, accounts: { brokerage: acct(100), plan529: acct(200) } },
      { label: 'Ben', birthYear: 2024, birthMonth: null, accounts: { brokerage: acct(300), plan529: acct(400) } },
    ],
  };
  return goalOf({ type: 'KIDS', name: 'Our kids', icon: 'child_care', config });
}

function account(balance: number): AccountState {
  return { balance, balanceUpdatedAt: null, anchorBalance: balance, anchorMonthly: 100 };
}

/** A stored kids goal with the fund on and one kid holding a fund. */
function fundGoal(kid: Partial<KidInput> & Pick<KidInput, 'birthYear' | 'birthMonth'>, fundBalance = 5_000): Goal {
  const config: KidsConfig = {
    brokerageTarget: 25_000,
    plan529Target: 47_800,
    firstYearFund: true,
    kids: [{
      label: 'Kid 1',
      firstYearTarget: 25_700,
      firstYearDueYear: kid.birthYear ?? 2029,
      firstYearDueMonth: kid.birthMonth ?? 6,
      accounts: { brokerage: account(0), plan529: account(0), firstYear: account(fundBalance) },
      ...kid,
    }],
  };
  return goalOf({ type: 'KIDS', name: 'Our kids', icon: 'child_care', config });
}

function kidsForm(rows: Array<{ birthYear: number | null; birthMonth: number | null }>): GoalForm {
  const f = defaultForm('KIDS', NOW);
  f.kids.kids = rows.map((r, i) => ({
    label: `Kid ${i + 1}`, birthYear: r.birthYear, birthMonth: r.birthMonth, undecided: r.birthYear == null,
  }));
  return f;
}

describe('goal-form.model', () => {
  it('defaults one undecided kid for KIDS and a name for every type', () => {
    const kids = defaultForm('KIDS', NOW);
    expect(kids.kids.kids.length).toBe(1);
    expect(kids.kids.kids[0].undecided).toBeTrue();
    expect(kids.kids.kids[0].label).toBe('Kid 1');
    const names = (['KIDS', 'HOUSE', 'EMERGENCY_FUND', 'CAR', 'VACATION', 'WEDDING', 'CUSTOM'] as const)
      .map(t => defaultForm(t, NOW).name);
    expect(names).toEqual(['Our kids', 'First home', 'Emergency fund', 'Next car', 'Trips', 'Wedding', 'My goal']);
    expect(defaultForm('CUSTOM', NOW).icon).toBe('flag');
    expect(defaultForm('HOUSE', NOW).targetYear).toBe(2031);
    expect(defaultForm('CAR', NOW).targetYear).toBe(2029);
    expect(defaultForm('EMERGENCY_FUND', NOW).targetYear).toBe(2028);
  });

  it('defaults the kids quiz to public tuition, $100,000 at 18 and the fund on for an undecided kid', () => {
    const f = defaultForm('KIDS', NOW);
    expect(f.kids.collegeChoice).toBe('public_tuition');
    expect(f.kids.plan529Target).toBe(47_800);
    expect(f.kids.at18Choice).toBe(100_000);
    expect(f.kids.brokerageTarget).toBe(100_000);
    expect(f.kids.firstYearFund).toBeTrue();
    expect(validateForm(f, NOW)).toEqual({});
  });

  it('MAX_KIDS is 5 and defaultFirstYearFund is true whenever a kid is unborn', () => {
    expect(MAX_KIDS).toBe(5);
    // NOW is June 2026. The old under-12-months rule is gone: one month out still defaults on.
    const row = (birthYear: number | null, birthMonth: number | null) =>
      ({ label: 'K', birthYear, birthMonth, undecided: birthYear == null });
    expect(defaultFirstYearFund([row(2026, 7)], NOW)).toBeTrue();
    expect(defaultFirstYearFund([row(2027, 5)], NOW)).toBeTrue();
    expect(defaultFirstYearFund([row(null, null)], NOW)).toBeTrue();
    expect(defaultFirstYearFund([row(2027, 6), row(2027, 5)], NOW)).toBeTrue();
    expect(defaultFirstYearFund([row(2020, 3), row(null, null)], NOW)).toBeTrue();
    // Born kids only: off.
    expect(defaultFirstYearFund([row(2020, 3)], NOW)).toBeFalse();
    expect(defaultFirstYearFund([row(2020, 3), row(2026, 6)], NOW)).toBeFalse();
  });

  it('formToConfig carries giftDays and formFromGoal reads a missing giftDays as false', () => {
    const f = defaultForm('KIDS', NOW);
    expect(f.kids.giftDays).toBeFalse();
    expect((formToConfig(f, null, NOW) as KidsConfig).giftDays).toBeFalse();
    f.kids.giftDays = true;
    expect((formToConfig(f, null, NOW) as KidsConfig).giftDays).toBeTrue();

    const missing = kidsGoal();
    expect(formFromGoal(missing).kids.giftDays).toBeFalse();
    const nulled = kidsGoal();
    (nulled.config as KidsConfig).giftDays = null;
    expect(formFromGoal(nulled).kids.giftDays).toBeFalse();
    const on = kidsGoal();
    (on.config as KidsConfig).giftDays = true;
    expect(formFromGoal(on).kids.giftDays).toBeTrue();
  });

  it('flags a giftDays flip as a plan input change', () => {
    const existing = kidsGoal();
    const form = formFromGoal(existing);
    expect(planInputsChanged(form, existing, NOW)).toBeFalse();
    form.kids.giftDays = true;
    expect(planInputsChanged(form, existing, NOW)).toBeTrue();
  });

  it('carries any trip count from 1 to 10', () => {
    const f = defaultForm('VACATION', NOW);
    f.tripsPerYear = 10;
    f.budgetPerTrip = 1_000;
    expect((formToConfig(f, null, NOW) as { tripsPerYear: number }).tripsPerYear).toBe(10);
  });

  it('gives the first child the rank 1 target even in a three-kid plan', () => {
    const f = kidsForm([{ birthYear: 2027, birthMonth: 3 }, { birthYear: 2028, birthMonth: 1 }, { birthYear: null, birthMonth: null }]);
    const config = formToConfig(f, null, NOW) as KidsConfig;
    expect(config.kids.map(k => k.firstYearTarget)).toEqual([25_700, 20_800, 16_500]);

    const reordered = kidsForm([{ birthYear: null, birthMonth: null }, { birthYear: 2028, birthMonth: 1 }, { birthYear: 2027, birthMonth: 3 }]);
    const config2 = formToConfig(reordered, null, NOW) as KidsConfig;
    expect(config2.kids.map(k => k.firstYearTarget)).toEqual([16_500, 20_800, 25_700]);
  });

  it('stores a due date: the birth month for a decided kid, now + 36 months for an undecided one', () => {
    const f = kidsForm([{ birthYear: 2027, birthMonth: 3 }, { birthYear: null, birthMonth: null }, { birthYear: 2028, birthMonth: null }]);
    const config = formToConfig(f, null, NOW) as KidsConfig;
    expect(config.firstYearFund).toBeTrue();
    expect([config.kids[0].firstYearDueYear, config.kids[0].firstYearDueMonth]).toEqual([2027, 3]);
    expect([config.kids[1].firstYearDueYear, config.kids[1].firstYearDueMonth]).toEqual([2029, 6]);
    expect([config.kids[2].firstYearDueYear, config.kids[2].firstYearDueMonth]).toEqual([2028, 7]);
    expect(config.kids[1].accounts.firstYear).toEqual({ balance: 0, balanceUpdatedAt: null, anchorBalance: 0, anchorMonthly: 0 });
  });

  it('carries a stored fund over unchanged after the kid has arrived', () => {
    // Born March 2026, the stored fund ended then. NOW is June 2026.
    const existing = fundGoal({ birthYear: 2026, birthMonth: 3 });
    const config = formToConfig(formFromGoal(existing), existing, NOW) as KidsConfig;
    expect(config.firstYearFund).toBeTrue();
    expect(config.kids[0].accounts.firstYear).toEqual(account(5_000));
    expect(config.kids[0].firstYearTarget).toBe(25_700);
    expect([config.kids[0].firstYearDueYear, config.kids[0].firstYearDueMonth]).toEqual([2026, 3]);
  });

  it('does not flag a plan change because a kid was born', () => {
    // Planned for Aug 2026 while still on the way; viewed in October after the birth.
    const existing = fundGoal({ birthYear: 2026, birthMonth: 8 });
    const later = new Date(2026, 9, 1);
    expect(planInputsChanged(formFromGoal(existing), existing, later)).toBeFalse();
  });

  it('recomputes targets when a kid is added and keeps them when nothing about timing changed', () => {
    const existing = fundGoal({ birthYear: null, birthMonth: null, firstYearTarget: 30_000, firstYearDueYear: 2029, firstYearDueMonth: 1 });
    const form = formFromGoal(existing);
    const kept = formToConfig(form, existing, NOW) as KidsConfig;
    expect(kept.kids[0].firstYearTarget).toBe(30_000);
    expect([kept.kids[0].firstYearDueYear, kept.kids[0].firstYearDueMonth]).toEqual([2029, 1]);
    expect(planInputsChanged(form, existing, NOW)).toBeFalse();

    form.kids.kids.push({ label: 'Kid 2', birthYear: null, birthMonth: null, undecided: true });
    const added = formToConfig(form, existing, NOW) as KidsConfig;
    expect(added.kids.map(k => k.firstYearTarget)).toEqual([25_700, 20_800]);
    // The stored kid keeps its end date and balance; the new one gets a fresh 36-month runway.
    expect([added.kids[0].firstYearDueYear, added.kids[0].firstYearDueMonth]).toEqual([2029, 1]);
    expect(added.kids[0].accounts.firstYear?.balance).toBe(5_000);
    expect([added.kids[1].firstYearDueYear, added.kids[1].firstYearDueMonth]).toEqual([2029, 6]);
    expect(planInputsChanged(form, existing, NOW)).toBeTrue();
  });

  it('turns the fund off in the config when no kid would hold one', () => {
    const born = kidsForm([{ birthYear: 2020, birthMonth: 4 }]);
    born.kids.firstYearFund = true;
    const config = formToConfig(born, null, NOW) as KidsConfig;
    expect(config.firstYearFund).toBeFalse();
    expect(config.kids[0].accounts.firstYear).toBeUndefined();

    const off = kidsForm([{ birthYear: null, birthMonth: null }]);
    off.kids.firstYearFund = false;
    const offConfig = formToConfig(off, null, NOW) as KidsConfig;
    expect(offConfig.firstYearFund).toBeFalse();
    expect(offConfig.kids[0].firstYearTarget).toBeUndefined();
    expect(offConfig.kids[0].firstYearDueYear).toBeUndefined();
    expect(offConfig.kids[0].accounts.firstYear).toBeUndefined();
  });

  it('accepts a first-year-only kids plan', () => {
    const f = kidsForm([{ birthYear: null, birthMonth: null }]);
    f.kids.collegeChoice = 'none';
    f.kids.plan529Target = null;
    f.kids.at18Choice = 'none';
    f.kids.brokerageTarget = null;
    expect(validateForm(f, NOW)).toEqual({});
    f.kids.firstYearFund = false;
    expect(validateForm(f, NOW)['kidsTargets']).toBe('Enter at least one target for your kids.');
    // Fund on but every kid already born: nobody holds a fund, so a target is still needed.
    const born = kidsForm([{ birthYear: 2020, birthMonth: 1 }]);
    born.kids.collegeChoice = 'none';
    born.kids.plan529Target = null;
    born.kids.at18Choice = 'none';
    born.kids.brokerageTarget = null;
    expect(validateForm(born, NOW)['kidsTargets']).toBe('Enter at least one target for your kids.');
  });

  it('requires an amount for Another amount', () => {
    const f = kidsForm([{ birthYear: null, birthMonth: null }]);
    f.kids.collegeChoice = 'custom';
    f.kids.plan529Target = null;
    f.kids.at18Choice = 'custom';
    f.kids.brokerageTarget = null;
    const errs = validateForm(f, NOW);
    expect(errs['plan529Target']).toBe('Enter an amount greater than $0.');
    expect(errs['brokerageTarget']).toBe('Enter an amount greater than $0.');
  });

  it('infers the choices from stored targets', () => {
    const goal = kidsGoal();
    const c = goal.config as KidsConfig;
    const formOf = (plan529Target: number, brokerageTarget: number) =>
      formFromGoal({ ...goal, config: { ...c, plan529Target, brokerageTarget } });
    expect(formOf(123_960, 0).kids.collegeChoice).toBe('public_full');
    expect(formOf(123_960, 0).kids.at18Choice).toBe('none');
    expect(formOf(70_000, 50_000).kids.collegeChoice).toBe('custom');
    expect(formOf(70_000, 50_000).kids.at18Choice).toBe(50_000);
    expect(formOf(0, 30_000).kids.collegeChoice).toBe('none');
    expect(formOf(0, 30_000).kids.at18Choice).toBe('custom');
    expect(formOf(47_800, 0).kids.collegeChoice).toBe('public_tuition');
    expect(formOf(180_000, 0).kids.collegeChoice).toBe('private_tuition');
    expect(formOf(47_800, 0).kids.firstYearFund).toBeFalse();
    expect(formFromGoal(fundGoal({ birthYear: null, birthMonth: null })).kids.firstYearFund).toBeTrue();
  });

  it('flags a plan-input change when the fund switch flips or a due date moves', () => {
    const existing = fundGoal({ birthYear: 2027, birthMonth: 9 });
    const form = formFromGoal(existing);
    expect(planInputsChanged(form, existing, NOW)).toBeFalse();
    form.kids.firstYearFund = false;
    expect(planInputsChanged(form, existing, NOW)).toBeTrue();

    const moved = formFromGoal(existing);
    moved.kids.kids[0].birthMonth = 10;
    const config = formToConfig(moved, existing, NOW) as KidsConfig;
    expect([config.kids[0].firstYearDueYear, config.kids[0].firstYearDueMonth]).toEqual([2027, 10]);
    expect(planInputsChanged(moved, existing, NOW)).toBeTrue();
  });

  it('gives a newly switched-on fund on a stored goal its rank target and flags a plan change', () => {
    const legacy = goalOf({
      type: 'KIDS',
      config: {
        brokerageTarget: 25_000, plan529Target: 0,
        kids: [{ label: 'Kid 1', birthYear: null, birthMonth: null, accounts: { brokerage: account(0), plan529: account(0) } }],
      } as KidsConfig,
    });
    const form = formFromGoal(legacy);
    expect(form.kids.firstYearFund).toBeFalse();
    expect(planInputsChanged(form, legacy, NOW)).toBeFalse();
    form.kids.firstYearFund = true;
    const config = formToConfig(form, legacy, NOW) as KidsConfig;
    expect(config.firstYearFund).toBeTrue();
    expect(config.kids[0].firstYearTarget).toBe(25_700);
    expect([config.kids[0].firstYearDueYear, config.kids[0].firstYearDueMonth]).toEqual([2029, 6]);
    expect(planInputsChanged(form, legacy, NOW)).toBeTrue();
  });

  it('keeps a stored fund and its balance when a born kid\'s birth month is corrected', () => {
    const existing = fundGoal({ birthYear: 2026, birthMonth: 3, firstYearTarget: 30_000 });
    const form = formFromGoal(existing);
    form.kids.kids[0].birthMonth = 2;
    const config = formToConfig(form, existing, NOW) as KidsConfig;
    expect(config.kids[0].accounts.firstYear?.balance).toBe(5_000);
    expect([config.kids[0].firstYearDueYear, config.kids[0].firstYearDueMonth]).toEqual([2026, 2]);
    // Timing changed, so the target is recomputed for the kid's rank.
    expect(config.kids[0].firstYearTarget).toBe(25_700);
    expect(config.firstYearFund).toBeTrue();
  });

  it('validates one quiz card at a time', () => {
    const f = kidsForm([{ birthYear: 2050, birthMonth: null }]);
    f.kids.kids[0].undecided = false;
    f.kids.collegeChoice = 'custom';
    f.kids.plan529Target = null;
    f.kids.at18Choice = 'custom';
    f.kids.brokerageTarget = null;
    f.name = ' ';
    expect(kidsQuizErrors(f, 1, NOW)).toEqual({});
    expect(Object.keys(kidsQuizErrors(f, 2, NOW))).toEqual(['kid.0.birthYear']);
    expect(kidsQuizErrors(f, 3, NOW)).toEqual({});
    expect(kidsQuizErrors(f, 4, NOW)).toEqual({ plan529Target: 'Enter an amount greater than $0.' });
    expect(Object.keys(kidsQuizErrors(f, 5, NOW)).sort()).toEqual(['brokerageTarget', 'name']);
  });

  it('rejects an empty name', () => {
    const f = defaultForm('CUSTOM', NOW);
    f.name = '   ';
    f.targetAmount = 5000;
    expect(validateForm(f, NOW)['name']).toBe('Give this goal a name.');
  });

  it('rejects an adult birth year with the documented message', () => {
    const f = defaultForm('KIDS', NOW);
    f.kids.kids[0] = { label: 'Kid 1', birthYear: 2000, birthMonth: null, undecided: false };
    expect(validateForm(f, NOW)['kid.0.birthYear'])
      .toBe('Already 18 or older. Use Something else with a target year instead.');
  });

  it('accepts a birth year up to ten years out and rejects eleven', () => {
    const f = defaultForm('KIDS', NOW);
    f.kids.kids[0] = { label: 'Kid 1', birthYear: 2036, birthMonth: null, undecided: false };
    expect(validateForm(f, NOW)['kid.0.birthYear']).toBeUndefined();
    f.kids.kids[0].birthYear = 2037;
    expect(validateForm(f, NOW)['kid.0.birthYear']).toBe('Enter a year from 2009 to 2036.');
  });

  it('requires at least one kids target when the fund is off', () => {
    const f = defaultForm('KIDS', NOW);
    f.kids.firstYearFund = false;
    f.kids.collegeChoice = 'none';
    f.kids.plan529Target = null;
    f.kids.at18Choice = 'none';
    f.kids.brokerageTarget = null;
    expect(validateForm(f, NOW)['kidsTargets']).toBe('Enter at least one target for your kids.');
    f.kids.at18Choice = 'custom';
    f.kids.brokerageTarget = 5000;
    expect(validateForm(f, NOW)['kidsTargets']).toBeUndefined();
  });

  it('rejects a year beyond fifty years out', () => {
    const f = defaultForm('WEDDING', NOW);
    f.targetAmount = 20_000;
    f.targetYear = 2077;
    expect(validateForm(f, NOW)['targetYear']).toBe('Enter a year from 2026 to 2076.');
    f.targetYear = 2076;
    expect(validateForm(f, NOW)['targetYear']).toBeUndefined();
  });

  it('carries over account states by kid index on edit', () => {
    const existing = kidsGoal();
    const form = formFromGoal(existing);
    form.kids.kids.push({ label: 'Kid 3', birthYear: null, birthMonth: null, undecided: true });
    const config = formToConfig(form, existing, NOW) as KidsConfig;
    expect(config.kids.length).toBe(3);
    expect(config.kids[0].accounts.plan529.balance).toBe(200);
    expect(config.kids[1].accounts.brokerage.balance).toBe(300);
    expect(config.kids[2].accounts.brokerage).toEqual({
      balance: 0, balanceUpdatedAt: null, anchorBalance: 0, anchorMonthly: 0,
    });
  });

  it('detects a plan-input change and ignores a name-only change', () => {
    const existing = kidsGoal();
    const form = formFromGoal(existing);
    expect(planInputsChanged(form, existing, NOW)).toBeFalse();
    form.name = 'Renamed';
    form.icon = 'flag';
    form.kids.kids[0].label = 'Someone else';
    expect(planInputsChanged(form, existing, NOW)).toBeFalse();
    form.kids.plan529Target = 50_000;
    expect(planInputsChanged(form, existing, NOW)).toBeTrue();
  });

  it('accepts an unchanged stored target year the calendar has passed, and rejects a new past year', () => {
    const existing = goalOf({ type: 'CUSTOM', config: { targetAmount: 5_000, targetYear: 2025 } });
    const form = formFromGoal(existing);
    expect(validateForm(form, NOW, existing)['targetYear']).toBeUndefined();
    form.targetYear = 2024;
    expect(validateForm(form, NOW, existing)['targetYear']).toBe('Enter a year from 2026 to 2076.');
    // Setup has no stored year: a past year is still an error.
    expect(validateForm({ ...form, targetYear: 2025 }, NOW)['targetYear']).toBe('Enter a year from 2026 to 2076.');
  });

  it('accepts an unchanged stored kid birth year that has turned 18, and rejects a changed one', () => {
    const existing = kidsGoal();
    (existing.config as KidsConfig).kids[0].birthYear = 2008;
    const form = formFromGoal(existing);
    expect(validateForm(form, NOW, existing)['kid.0.birthYear']).toBeUndefined();
    form.kids.kids[0].birthYear = 2007;
    expect(validateForm(form, NOW, existing)['kid.0.birthYear']).toContain('Already 18 or older');
    expect(validateForm(formFromGoal(existing), NOW)['kid.0.birthYear']).toContain('Already 18 or older');
  });

  it('shows one error, not two, for a custom amount over the cap', () => {
    const form = defaultForm('KIDS', NOW);
    form.kids.collegeChoice = 'custom';
    form.kids.plan529Target = 100_000_001;
    const errors = validateForm(form, NOW);
    expect(errors['plan529Target']).toBe('That is more than we can plan for. Try a smaller amount.');
    expect(errors['kidsTargets']).toBeUndefined();
  });
});
