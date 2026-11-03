import { buildGoalPlanView, fmtCompact, GoalPlanInput } from './goal-plan.view-model';
import { AccountState, KidsConfig } from '../../models/goal.model';

const NOW = new Date(2026, 10, 15); // 15 Nov 2026
const Y = NOW.getFullYear();

function account(balance = 0, extra: Partial<AccountState> = {}): AccountState {
  return { balance, balanceUpdatedAt: null, anchorBalance: 0, anchorMonthly: 0, ...extra };
}

function base(partial: Partial<GoalPlanInput>): GoalPlanInput {
  return {
    type: 'CUSTOM',
    config: { targetAmount: 200_000, targetYear: Y + 6 },
    currentBalance: 0,
    completed: false,
    clientId: null,
    anchorBalance: 0,
    anchorMonthly: 0,
    planRate: 0.10,
    planRateKind: 'market',
    planAnchoredAt: '2026-01-01T00:00:00',
    balanceUpdatedAt: null,
    ...partial,
  };
}

const ctx = (extra: Partial<{ marketRate: number; frequency: string; rateKindOverride: 'market' | 'cash' | null }> = {}) => ({
  marketRate: 0.10,
  frequency: 'biweekly',
  now: NOW,
  rateKindOverride: null,
  ...extra,
});

function kidsGoal(kids: KidsConfig['kids'], extra: Partial<GoalPlanInput> = {}): GoalPlanInput {
  const config: KidsConfig = { plan529Target: 100_000, brokerageTarget: 250_000, kids };
  return base({ type: 'KIDS', config, ...extra });
}

describe('buildGoalPlanView', () => {
  it('aggregates kids accounts into one status and progress', () => {
    const goal = kidsGoal([
      {
        label: 'Ada', birthYear: Y - 2, birthMonth: 3,
        accounts: {
          plan529: account(10_000, { balanceUpdatedAt: '2026-06-01T00:00:00', anchorBalance: 8_000, anchorMonthly: 200 }),
          brokerage: account(20_000, { balanceUpdatedAt: '2026-09-01T00:00:00', anchorBalance: 15_000, anchorMonthly: 300 }),
        },
      },
      {
        label: 'Ben', birthYear: Y - 5, birthMonth: 8,
        accounts: { plan529: account(5_000), brokerage: account(0) },
      },
    ]);
    const view = buildGoalPlanView(goal, ctx());

    expect(view.kids?.length).toBe(2);
    const p529 = view.kids!.find(k => k.account === 'plan529')!;
    expect(p529.label).toBe('529 plans');
    expect(p529.perKid.length).toBe(2);
    expect(p529.perKid[0].balance).toBe(10_000);
    expect(p529.totals.monthly).toBe(p529.perKid[0].plan.monthly + p529.perKid[1].plan.monthly);
    const brokerage = view.kids!.find(k => k.account === 'brokerage')!;
    expect(view.monthlyTotal).toBe(p529.totals.monthly + brokerage.totals.monthly);

    // balance 35K of a 2 × 350K family target
    expect(view.progressPct).toBeCloseTo((35_000 / 700_000) * 100, 6);
    // graded at the latest account log, as one family plan
    expect(['ahead', 'on_plan', 'behind']).toContain(view.status.kind);
    expect(view.status.asOf).toBe('2026-09-01T00:00:00');
  });

  it('grades status with the stored plan rate, not the live chip', () => {
    const goal = base({
      currentBalance: 30_000,
      anchorBalance: 20_000,
      anchorMonthly: 1_000,
      planRate: 0.10,
      balanceUpdatedAt: '2026-10-01T00:00:00',
    });
    const at8 = buildGoalPlanView(goal, ctx({ marketRate: 0.08 }));
    const at12 = buildGoalPlanView(goal, ctx({ marketRate: 0.12 }));

    expect(at8.monthlyTotal).not.toBe(at12.monthlyTotal); // the live plan moves
    expect(at12.status).toEqual(at8.status);              // the status does not
  });

  it('reads imported for a never-planned import', () => {
    const view = buildGoalPlanView(base({ clientId: 'v2-abc', anchorMonthly: 0, balanceUpdatedAt: '2026-11-01T00:00:00', currentBalance: 500 }), ctx());
    expect(view.status.kind).toBe('imported');
    expect(view.subtitle).toBe('Plan updated, take a look');

    const planned = buildGoalPlanView(base({ clientId: 'v2-abc', anchorMonthly: 900 }), ctx());
    expect(planned.status.kind).not.toBe('imported');
  });

  it('builds each documented subtitle shape and never emits an em dash', () => {
    const decided = kidsGoal([
      { label: 'A', birthYear: Y - 4, birthMonth: 2, accounts: { plan529: account(), brokerage: account() } },
      { label: 'B', birthYear: Y - 2, birthMonth: 5, accounts: { plan529: account(), brokerage: account() } },
      { label: 'C', birthYear: Y, birthMonth: 1, accounts: { plan529: account(), brokerage: account() } },
    ]);
    const kidsView = buildGoalPlanView(decided, ctx());
    expect(kidsView.subtitle).toMatch(new RegExp(`^\\$[\\d,]+/mo · 3 kids · ${Y + 14} to ${Y + 18}$`));

    const undecided = kidsGoal([
      { label: 'A', birthYear: null, birthMonth: null, accounts: { plan529: account(), brokerage: account() } },
      { label: 'B', birthYear: Y + 2, birthMonth: 4, accounts: { plan529: account(), brokerage: account() } },
    ]);
    const relView = buildGoalPlanView(undecided, ctx());
    expect(relView.subtitle).toMatch(/^\$[\d,]+\/mo · 2 kids · 18-year plans$/);
    expect(relView.relative).toBeTrue();
    expect(relView.kids![0].perKid.every(k => k.relative)).toBeTrue();

    const vacation = buildGoalPlanView(base({
      type: 'VACATION',
      config: { tripsPerYear: 4, budgetPerTrip: 2_500, savedThisYear: 0, savedYear: Y },
    }), ctx());
    expect(vacation.subtitle).toBe('$834/mo · 4 trips a year');
    expect(vacation.vacation?.monthly).toBe(834);

    const house = buildGoalPlanView(base({
      type: 'HOUSE',
      config: { price: 500_000, downPaymentPct: 20, targetYear: Y + 6 },
    }), ctx());
    expect(house.subtitle).toMatch(new RegExp(`^\\$[\\d,]+/mo · 20% down · ${Y + 6}$`));

    const car = buildGoalPlanView(base({
      type: 'CAR',
      config: { price: 40_000, downPaymentPct: null, targetYear: Y + 4 },
    }), ctx());
    expect(car.subtitle).toMatch(new RegExp(`^\\$[\\d,]+/mo · full price · ${Y + 4}$`));

    const custom = buildGoalPlanView(base({}), ctx());
    expect(custom.subtitle).toMatch(new RegExp(`^\\$[\\d,]+/mo · \\$200K by ${Y + 6}$`));
    expect(custom.headline).toBe(`Reaches $200K in Dec ${Y + 6}`);

    const shortKid = kidsGoal([
      { label: 'Teen', birthYear: Y - 17, birthMonth: 12, accounts: { plan529: account(), brokerage: account() } },
    ]);
    expect(buildGoalPlanView(shortKid, ctx()).reasonLine)
      .toBe('Under two years to go, so this one is a steady monthly amount');

    const all = [kidsView, relView, vacation, house, car, custom, buildGoalPlanView(shortKid, ctx())];
    for (const v of all) {
      const text = [v.headline, v.subtitle, v.reasonLine ?? ''].join(' ');
      expect(text).not.toContain('\u2014');
    }

    expect(fmtCompact(372_338.52)).toBe('$372K');
    expect(fmtCompact(1_180_000)).toBe('$1.18M');
  });

  it('hides per paycheck without a frequency', () => {
    expect(buildGoalPlanView(base({}), ctx({ frequency: '' })).perPaycheck).toBeNull();
    expect(buildGoalPlanView(base({}), ctx({ frequency: 'biweekly' })).perPaycheck).toBeGreaterThan(0);
  });
});
