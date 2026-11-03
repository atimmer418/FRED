import { Goal, KidsConfig } from '../../models/goal.model';
import {
  defaultForm, formFromGoal, formToConfig, planInputsChanged, validateForm,
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

  it('rejects an empty name', () => {
    const f = defaultForm('CUSTOM', NOW);
    f.name = '   ';
    f.targetAmount = 5000;
    expect(validateForm(f, NOW)['name']).toBe('Give this goal a name.');
  });

  it('rejects an adult birth year with the documented message', () => {
    const f = defaultForm('KIDS', NOW);
    f.kids.plan529Target = 10_000;
    f.kids.kids[0] = { label: 'Kid 1', birthYear: 2000, birthMonth: null, undecided: false };
    expect(validateForm(f, NOW)['kid.0.birthYear'])
      .toBe('Already 18 or older. Use Something else with a target year instead.');
  });

  it('accepts a birth year up to ten years out and rejects eleven', () => {
    const f = defaultForm('KIDS', NOW);
    f.kids.plan529Target = 10_000;
    f.kids.kids[0] = { label: 'Kid 1', birthYear: 2036, birthMonth: null, undecided: false };
    expect(validateForm(f, NOW)['kid.0.birthYear']).toBeUndefined();
    f.kids.kids[0].birthYear = 2037;
    expect(validateForm(f, NOW)['kid.0.birthYear']).toBe('Enter a year from 2009 to 2036.');
  });

  it('requires at least one kids target', () => {
    const f = defaultForm('KIDS', NOW);
    expect(validateForm(f, NOW)['kidsTargets']).toBe('Enter at least one target for your kids.');
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
    const config = formToConfig(form, existing) as KidsConfig;
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
    expect(planInputsChanged(form, existing)).toBeFalse();
    form.name = 'Renamed';
    form.icon = 'flag';
    form.kids.kids[0].label = 'Someone else';
    expect(planInputsChanged(form, existing)).toBeFalse();
    form.kids.plan529Target = 50_000;
    expect(planInputsChanged(form, existing)).toBeTrue();
  });
});
